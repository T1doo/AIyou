import io
import contextlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from d02_elf import ElfError
from d02_native_elf_audit import ABIS, MAX_NATIVE_BYTES, apk_members, enforce_doc_endpoint, audit, command
import d02_native_elf_audit as native_audit
from test_d02_elf import fixture


def apk(entries=None):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        for name, data in (entries or [(f"lib/{abi}/libsqlcipher.so", b"native " + abi.encode()) for abi in ABIS]):
            archive.writestr(name, data)
    return stream.getvalue()


def report(endpoint="FAIL"):
    return {"kind": "D02 actual APK ELF static evidence", "evidence_validation": "PASS",
            "abis": [{"abi": abi, "llvm_crosscheck": "PASS",
                      "checks": [{"page": 16384, "android_doc_relro_endpoint": endpoint,
                                  "nonrelro_writable_load_overlaps": [],
                                  "nonrelro_alloc_write_section_overlaps": []}]}
                     for abi in ABIS]}


class NativeApkEvidenceTests(unittest.TestCase):
    def test_actual_zip_members_have_payload_bytes_offsets_and_hashes(self):
        raw = apk()
        records = apk_members(raw)
        self.assertEqual(set(ABIS), {a for a, _, _ in records})
        for abi, data, meta in records:
            self.assertEqual(b"native " + abi.encode(), data)
            self.assertEqual(data, raw[meta["payload_offset"]:meta["payload_offset"] + len(data)])
            self.assertEqual(64, len(meta["sha256"]))
            self.assertEqual("FAIL", meta["uncompressed_payload_16k_alignment"])

    def test_missing_or_unexpected_abi_never_becomes_complete_evidence(self):
        entries = [(f"lib/{abi}/libsqlcipher.so", b"elf") for abi in ABIS]
        for wrong in (entries[:-1], entries + [("lib/other/libsqlcipher.so", b"elf")]):
            with self.subTest(wrong=wrong), self.assertRaises(ElfError):
                apk_members(apk(wrong))

    def test_duplicate_native_and_other_zip_names_are_rejected(self):
        entries = [(f"lib/{abi}/libsqlcipher.so", b"elf") for abi in ABIS]
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)  # only duplicate fixture ZIP construction
            for wrong in (entries + [entries[0]], entries + [("other", b"1"), ("other", b"2")]):
                with self.subTest(wrong=wrong), self.assertRaises(ElfError):
                    apk_members(apk(wrong))

    def test_bounded_native_members_reject_empty_and_oversize(self):
        for content in (b"", b"x" * (MAX_NATIVE_BYTES + 1)):
            entries = [(f"lib/{abi}/libsqlcipher.so", content if i == 0 else b"elf")
                       for i, abi in enumerate(ABIS)]
            with self.subTest(size=len(content)), self.assertRaises(ElfError):
                apk_members(apk(entries))

    def test_doc_gate_rejects_zero_overlap_and_forged_aggregate_pass(self):
        wrong = report()
        wrong["android_doc_relro_endpoint_16k"] = "PASS"
        with self.assertRaisesRegex(ElfError, "DOC_LITERAL_RELRO FAIL"):
            enforce_doc_endpoint(wrong)

    def test_doc_gate_requires_all_four_and_independent_agreement(self):
        wrong = report("PASS"); wrong["abis"].pop()
        with self.assertRaises(ElfError): enforce_doc_endpoint(wrong)
        wrong = report("PASS"); wrong["abis"][0]["llvm_crosscheck"] = "NOT RUN"
        with self.assertRaises(ElfError): enforce_doc_endpoint(wrong)
        wrong = report("PASS"); wrong["abis"][0]["checks"] *= 2
        with self.assertRaises(ElfError): enforce_doc_endpoint(wrong)

    def test_one_endpoint_failure_is_not_hidden_by_other_three(self):
        wrong = report("PASS")
        wrong["abis"][-1]["checks"][0]["android_doc_relro_endpoint"] = "FAIL"
        with self.assertRaisesRegex(ElfError, "x86_64"): enforce_doc_endpoint(wrong)
        enforce_doc_endpoint(report("PASS"))

    def test_redirected_output_is_rejected_before_sdk_or_binary_access(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            target = folder / "outside"; target.mkdir()
            link = folder / "redirect"; link.symlink_to(target, target_is_directory=True)
            with self.assertRaisesRegex(ElfError, "redirected"):
                audit(folder / "absent.apk", folder / "absent-sdk", link / "out", "a" * 40)
            self.assertFalse((target / "out").exists())

    def test_real_owned_tool_failure_preserves_actual_stdout_and_stderr(self):
        with tempfile.TemporaryDirectory() as temp:
            log = Path(temp) / "owned-tool.log"
            with self.assertRaises(ElfError):
                command([sys.executable, "-c",
                         "import sys;print('synthetic stdout');print('synthetic stderr',file=sys.stderr);sys.exit(7)"],
                        log_path=log)
            self.assertEqual("synthetic stdout\nsynthetic stderr\n", log.read_text())

    def test_real_owned_command_timeout_preserves_actual_partial_streams(self):
        with tempfile.TemporaryDirectory() as temp:
            log = Path(temp) / "owned-timeout.log"
            with self.assertRaises(subprocess.TimeoutExpired):
                command([sys.executable, "-c",
                         "import sys,time;print('owned partial stdout',flush=True);"
                         "print('owned partial stderr',file=sys.stderr,flush=True);time.sleep(1)"],
                        timeout=.2, log_path=log)
            self.assertEqual("owned partial stdout\nowned partial stderr\n", log.read_text())

    def test_zipalign_timeout_keeps_both_streams_and_failed_report(self):
        # Actual own child's timeout is replayed at the SDK boundary. The APK/LLVM
        # fixtures are synthetic; this proves fault preservation, not SDK agreement.
        with self.assertRaises(subprocess.TimeoutExpired) as caught:
            subprocess.run([sys.executable, "-c",
                            "import sys,time;print('owned zipalign stdout',flush=True);"
                            "print('owned zipalign stderr',file=sys.stderr,flush=True);time.sleep(1)"],
                           capture_output=True, text=True, timeout=.2)
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            license_path = folder / "fixture-license"; license_path.write_bytes(b"fixture only")
            entries = []
            for abi, (bits, machine) in ABIS.items():
                data = bytearray(fixture(bits=bits)); struct.pack_into("<H", data, 18, machine)
                entries.append((f"lib/{abi}/libsqlcipher.so", bytes(data)))
            apk_path = folder / "synthetic.apk"; apk_path.write_bytes(apk(entries))
            output = folder / "evidence"
            def synthetic_command(args, timeout=30, log_path=None):
                if str(args[0]) == "git": return "a" * 40
                text = "LOAD 0x0 0x0 0x0 0x400 0x10000 RW 0x4000\n"
                if log_path: Path(log_path).write_text(text)
                return text
            with patch.object(native_audit, "existing_tools", return_value=(
                    folder / "synthetic-llvm", folder / "synthetic-zipalign", license_path, b"fixture only", {})), \
                    patch.object(native_audit, "command", side_effect=synthetic_command), \
                    patch.object(native_audit.subprocess, "run", side_effect=caught.exception), \
                    contextlib.redirect_stdout(io.StringIO()), self.assertRaises(subprocess.TimeoutExpired):
                audit(apk_path, folder / "not-sdk", output, "a" * 40)
            self.assertEqual("owned zipalign stdout\nowned zipalign stderr\n",
                             (output / "apk-zipalign.txt").read_text())
            result = json.loads((output / "REPORT.json").read_text())
            self.assertEqual("FAIL", result["evidence_validation"])
            self.assertEqual("TimeoutExpired", result["failure_type"])
            self.assertEqual("NOT RUN", result["device_runtime"])
            self.assertTrue(result["tools"]["acceptance_records_unchanged"])
            self.assertNotIn("zipalign_exit", result)


if __name__ == "__main__":
    unittest.main()
