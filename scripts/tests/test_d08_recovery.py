import base64
import copy
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from d02_kvm_acl import render
from d08_kvm_acl import RecoveryKvmAcl, grant_plan
from d08_recovery_contract import (SOURCE, BRANCH, MESSAGE, EXPECTED_REQUEST, FILES, PHASES, PROBE, METHOD,
                                  admit, check_source_changes, validate_device_result, evidence_frame, decode_evidence, decode_log)


def context():
    env = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "T1doo/AIyou", "RUNNER_OS": "Linux",
           "GITHUB_EVENT_NAME": "push", "GITHUB_REF": BRANCH, "GITHUB_RUN_ATTEMPT": "1",
           "GITHUB_SHA": "a" * 40, "GITHUB_RUN_ID": "123"}
    event = {"repository": {"full_name": "T1doo/AIyou", "private": False}, "ref": BRANCH,
             "head_commit": {"message": MESSAGE}}
    return env, event


def result_fixture():
    fixtures = {n: {"bytes": 99, "sha256": h * 64} for n, h in
                [("fixture.png", "a"), ("fixture.wav", "b"), ("recovery.aiyoupk", "c")]}
    fixtures["token"] = "d" * 32
    result = {"source_sha": SOURCE, "run_token": "d" * 32, "result": "PASS", "lastPhase": PHASES[-1],
              "trace": [{"phase": p, "elapsedRealtimeMs": i * 10} for i, p in enumerate(PHASES)], "fixtures": fixtures,
              "restoredExport": {"rangeStart": 0, "rangeEndExclusive": 4410, "sourceFrames": 44100, "members": 6,
                                 "actualPngSha256": "a" * 64, "actualWavSha256": "b" * 64}}
    terminal = "\n".join(["INSTRUMENTATION_RESULT: d08_result=PASS", "INSTRUMENTATION_RESULT: d08_source=" + SOURCE,
                          "INSTRUMENTATION_RESULT: d08_token=" + "d" * 32, "INSTRUMENTATION_CODE: -1"])
    xml = ('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="' + PROBE +
           '.RecoveryInstrumentation" name="' + METHOD + '"/></testsuite>')
    return terminal, result, xml


def entry_module():
    spec = importlib.util.spec_from_file_location("d08_test_entry", ROOT / "scripts/run-d08-recovery.py")
    entry = importlib.util.module_from_spec(spec); spec.loader.exec_module(entry)
    return entry


class AdmissionTests(unittest.TestCase):
    def test_exact_request_and_context_only(self):
        env, event = context(); admit(copy.deepcopy(EXPECTED_REQUEST), env, event)
        for key, value in [("GITHUB_RUN_ATTEMPT", "2"), ("GITHUB_EVENT_NAME", "workflow_dispatch"),
                           ("GITHUB_REF", "refs/heads/codex/other"), ("GITHUB_REPOSITORY", "other/AIyou"),
                           ("RUNNER_OS", "Windows"), ("GITHUB_SHA", "short"), ("GITHUB_RUN_ID", "0")]:
            with self.subTest(key=key), self.assertRaises(ValueError): admit(EXPECTED_REQUEST, {**env, key: value}, event)
        for change in ({"repository": {"full_name": "T1doo/AIyou", "private": True}},
                       {"head_commit": {"message": MESSAGE + "\nsuffix"}}, {"ref": BRANCH + "-other"}):
            with self.assertRaises(ValueError): admit(EXPECTED_REQUEST, env, {**event, **change})

    def test_false_and_bool_number_confusions_cannot_arm_execution(self):
        env, event = context()
        for key, value in [("executionApproved", False), ("executionApproved", 1), ("attempt", True),
                           ("newTermsAcceptance", 0), ("realData", 0), ("driverSeconds", 2100),
                           ("artifactId", 99), ("apkSha256", "f" * 64)]:
            with self.subTest(key=key), self.assertRaises(ValueError): admit({**EXPECTED_REQUEST, key: value}, env, event)
        with self.assertRaises(ValueError): admit({**EXPECTED_REQUEST, "shellCommand": "anything"}, env, event)

    def test_committed_unarmed_entry_rejects_before_any_environment_or_sdk_operation(self):
        entry = entry_module()
        with mock.patch.object(entry, "git") as git, mock.patch.object(entry.subprocess, "run") as command:
            with self.assertRaisesRegex(RuntimeError, "unapproved"):
                # A controlled false request tests the guard even after a later approval delta.
                with mock.patch.object(entry, "REQUEST") as request:
                    request.read_text.return_value = json.dumps({**EXPECTED_REQUEST, "executionApproved": False})
                    entry.admission({})
            git.assert_not_called(); command.assert_not_called()

    def test_only_new_isolated_sources_never_production_or_old_request(self):
        check_source_changes([("A", "checks/device-recovery/android/build.gradle.kts"),
                              ("A", ".github/workflows/d08-recovery.yml"), ("A", "scripts/d08_kvm_acl.py")])
        for value in [[], [("M", "app/src/main/AndroidManifest.xml")], [("M", ".github/workflows/android.yml")],
                      [("A", "checks/sdk-device/kvm-request-20261012.json")], [("A", "checks/device-recovery/../secrets")],
                      [("D", "checks/device-recovery/request.json")]]:
            with self.assertRaises(ValueError): check_source_changes(value)

    def test_workflow_has_one_bounded_non_dispatch_path_and_pinned_original_artifact(self):
        s = (ROOT / ".github/workflows/d08-recovery.yml").read_text()
        self.assertIn("timeout-minutes: 35", s); self.assertIn("runs-on: ubuntu-24.04", s)
        self.assertIn("github.run_attempt == 1", s); self.assertIn(MESSAGE, s)
        self.assertIn("run-id: 40123738360", s); self.assertIn("artifact-ids: 12572840840", s)
        self.assertIn("python3 scripts/run-d08-recovery.py admit", s)
        self.assertLess(s.index("run-d08-recovery.py admit"), s.index("actions/setup-java@"))
        self.assertNotIn("continue-on-error", s); self.assertNotIn("workflow_dispatch:", s)
        self.assertNotIn("pull_request:", s); self.assertNotIn(":app:assembleDebug", s)
        self.assertNotIn("uses: actions/upload-artifact", s); self.assertNotIn("uses: actions/cache", s)
        self.assertEqual(s.count("runs-on:"), 1)


class CleanupTests(unittest.TestCase):
    def test_absent_package_has_no_mutation_and_replaced_apk_is_retained(self):
        entry = entry_module()
        for changed in (False, True):
            calls = []
            def command(argv, name, **kwargs):
                calls.append([str(a) for a in argv])
                value = ""
                if name.endswith("-path") and changed: value = "package:/data/app/owned/base.apk\n"
                if name.endswith("-hash"): value = "0" * 64 + "  /data/app/owned/base.apk\n"
                return types.SimpleNamespace(stdout=value, stderr="", returncode=0)
            if changed:
                with self.assertRaisesRegex(RuntimeError, "changed"):
                    entry.cleanup_package(command, "adb", "emulator-5554", PROBE, "f" * 64, "cleanup")
            else: entry.cleanup_package(command, "adb", "emulator-5554", PROBE, "f" * 64, "cleanup")
            self.assertFalse(any("force-stop" in a or "uninstall" in a for a in calls))

    def test_same_installed_bytes_removed_once_and_actual_absence_required(self):
        entry = entry_module(); calls = []
        def command(argv, name, **kwargs):
            calls.append(argv)
            if name.endswith("-path"): value = "package:/data/app/owned/base.apk\n"
            elif name.endswith("-hash"): value = "f" * 64 + "  /data/app/owned/base.apk\n"
            elif name.endswith("-uninstall"): value = "Success\n"
            else: value = ""
            return types.SimpleNamespace(stdout=value, stderr="", returncode=0)
        entry.cleanup_package(command, "adb", "emulator-5554", PROBE, "f" * 64, "cleanup")
        self.assertEqual(sum("uninstall" in a for a in calls), 1)
        self.assertEqual(sum("force-stop" in a for a in calls), 1)
        self.assertIn("pm", calls[-1]); self.assertIn("path", calls[-1])
        def lost_removal(argv, name, **kwargs):
            result = command(argv, name, **kwargs)
            if name.endswith("-absent"): result.stdout = "package:/data/app/owned/base.apk\n"
            return result
        with self.assertRaisesRegex(RuntimeError, "unconfirmed"):
            entry.cleanup_package(lost_removal, "adb", "emulator-5554", PROBE, "f" * 64, "cleanup")


class AclTests(unittest.TestCase):
    def base(self, extended=False):
        v = {"user:": 6, "group:": 6, "other:": 0}
        if extended: v.update({"mask:": 6, "user:2002": 4})
        return v

    def fixture(self, before):
        state, calls = [dict(before)], []
        acl = RecoveryKvmAcl(None, {})
        acl.uid = 1001
        acl.node = mock.Mock(return_value=(1, 2, 3, 0, 109))
        def command(args, name):
            args = [str(a) for a in args]; calls.append(args)
            if args[0] == "getfacl":
                return types.SimpleNamespace(stdout="\n".join(k + ":" + render(v) for k, v in state[0].items()))
            if "-m" in args:
                state[0] = grant_plan(before, 1001)[0]
            elif "-x" in args:
                state[0].pop("user:1001")
                if ",m:" in args[args.index("-x") + 1]: state[0].pop("mask:")
            return types.SimpleNamespace(stdout="")
        acl.command = command
        return acl, state, calls

    def test_new_mask_removed_and_existing_mask_unchanged_exactly(self):
        for extended in (False, True):
            before = self.base(extended); acl, state, calls = self.fixture(before)
            with mock.patch("d08_kvm_acl.os.access", side_effect=lambda *_: state[0] != before):
                acl.preflight(); acl.grant(); acl.restore()
            self.assertEqual(state[0], before)
            mutation = [a for a in calls if a[0] == "sudo"]
            self.assertEqual(mutation[0][-2], "u:1001:rw-" + ("" if extended else ",m::rw-"))
            self.assertEqual(mutation[1][-2], "u:1001" + ("" if extended else ",m:"))
            self.assertIn("exact original", acl.report["kvm_cleanup"])

    def test_denied_existing_mask_never_widens_and_existing_uid_not_owned(self):
        for value in [{**self.base(True), "mask:": 4}, {**self.base(), "group:": 4},
                      {**self.base(), "user:1001": 6}]:
            with self.assertRaises(RuntimeError): grant_plan(value, 1001)

    def test_already_accessible_has_no_permission_command(self):
        acl, state, calls = self.fixture(self.base())
        with mock.patch("d08_kvm_acl.os.access", return_value=True):
            acl.preflight(); acl.grant(); acl.restore()
        self.assertEqual([a for a in calls if a[0] == "sudo"], [])

    def test_external_acl_or_identity_change_stops_cleanup_mutations(self):
        for kind in ("acl", "node"):
            before = self.base(); acl, state, calls = self.fixture(before)
            with mock.patch("d08_kvm_acl.os.access", side_effect=lambda *_: state[0] != before):
                acl.preflight(); acl.grant()
            count = sum(a[0] == "sudo" for a in calls)
            if kind == "acl": state[0]["user:2002"] = 4
            else: acl.node.return_value = (2, 2, 3, 0, 109)
            with self.assertRaises(RuntimeError): acl.restore()
            self.assertEqual(sum(a[0] == "sudo" for a in calls), count)

    def test_partial_grant_failure_still_requires_exact_cleanup(self):
        before = self.base(); acl, state, calls = self.fixture(before); command = acl.command
        def partial(args, name):
            result = command(args, name)
            if name == "kvm-grant": raise RuntimeError("controlled lost ACK")
            return result
        acl.command = partial
        with mock.patch("d08_kvm_acl.os.access", side_effect=lambda *_: state[0] != before):
            acl.preflight()
            with self.assertRaises(RuntimeError): acl.grant()
            acl.restore()
        self.assertEqual(state[0], before)


class ResultTests(unittest.TestCase):
    def test_actual_runner_evidence_envelope_round_trip_and_truncation_fail_closed(self):
        entry = entry_module(); captured = io.StringIO()
        png = b"\x89PNG\r\n\x1a\n" + b"\0\0\0\rIHDR" + (2).to_bytes(4, "big") * 2
        import contextlib
        with contextlib.redirect_stdout(captured):
            for name in FILES: entry.emit_probe(name, png if name.endswith(".png") else b"synthetic")
        lines = captured.getvalue().splitlines()
        self.assertEqual(set(decode_log(lines)), set(FILES))
        self.assertEqual(decode_log("2026-10-25T00:00:00Z " + line for line in lines), decode_log(lines))
        for invalid in (lines[:-1], lines + lines[:3], [*lines[:2], lines[1], *lines[2:]]):
            with self.assertRaises(ValueError): decode_log(invalid)

    def test_complete_terminal_phase_chain_fixture_hashes_and_original_xml(self):
        terminal, result, xml = result_fixture()
        validate_device_result(terminal, result, xml, "d" * 32)

    def test_missing_duplicate_failed_or_reordered_real_checkpoint_never_passes(self):
        terminal, base, xml = result_fixture()
        for kind in ("missing", "duplicate", "reorder", "failed", "cleanup", "range", "hash", "token"):
            r = copy.deepcopy(base)
            if kind == "missing": r["trace"].pop()
            elif kind == "duplicate": r["trace"].append(r["trace"][-1])
            elif kind == "reorder": r["trace"][0], r["trace"][1] = r["trace"][1], r["trace"][0]
            elif kind == "failed": r["result"] = "FAIL"
            elif kind == "cleanup": r["appCleanup"] = "UNCONFIRMED"
            elif kind == "range": r["restoredExport"]["rangeEndExclusive"] = 4409
            elif kind == "token": r["fixtures"]["token"] = "0" * 32
            else: r["restoredExport"]["actualWavSha256"] = "f" * 64
            with self.subTest(kind=kind), self.assertRaises(ValueError): validate_device_result(terminal, r, xml, "d" * 32)

    def test_extra_terminal_wrong_source_or_skipped_xml_rejected(self):
        terminal, result, xml = result_fixture()
        for value in [terminal + "\nINSTRUMENTATION_CODE: 0", terminal + "\nINSTRUMENTATION_CODE: -1",
                      terminal.replace(SOURCE, "a" * 40), terminal.replace("d08_result=PASS", "d08_result=FAIL")]:
            with self.assertRaises(ValueError): validate_device_result(value, result, xml, "d" * 32)
        with self.assertRaises(ValueError): validate_device_result(terminal, result, xml.replace('skipped="0"', 'skipped="1"'), "d" * 32)

    def test_evidence_allowlist_hash_duplicate_and_missing_guards(self):
        # Header-only controlled PNG is sufficient for this envelope test, not image validation.
        png = b"\x89PNG\r\n\x1a\n" + b"\0\0\0\rIHDR" + (2).to_bytes(4, "big") * 2
        frames = [evidence_frame(n, png if n.endswith(".png") else b"synthetic") for n in FILES]
        self.assertEqual(set(decode_evidence(frames)), set(FILES))
        for values in [frames[:-1], frames + [frames[0]], [{**frames[0], "sha256": "0" * 64}, *frames[1:]]]:
            with self.assertRaises(ValueError): decode_evidence(values)
        for name in ["app.apk", "backup.zip", "database.db", "../result.json"]:
            with self.assertRaises(ValueError): evidence_frame(name, b"synthetic")

    def test_probe_manifest_is_self_targeted_and_has_no_uses_permission(self):
        import xml.etree.ElementTree as ET
        manifest = ET.parse(ROOT / "checks/device-recovery/android/src/main/AndroidManifest.xml").getroot()
        a = "{http://schemas.android.com/apk/res/android}"
        self.assertEqual(manifest.findall("uses-permission"), [])
        self.assertEqual(manifest.find("instrumentation").get(a + "targetPackage"), PROBE)
        self.assertEqual(manifest.find("application").get(a + "testOnly"), "true")
        provider = manifest.find("application/provider")
        self.assertEqual(provider.get(a + "permission"), "android.permission.MANAGE_DOCUMENTS")


if __name__ == "__main__": unittest.main()
