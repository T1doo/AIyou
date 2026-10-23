"""Synthetic transfer fixtures: same-run data verification, not hosted execution evidence."""
from contextlib import contextmanager
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("ci_inputs", Path(__file__).resolve().parents[1] / "verify-ci-stage-inputs.py")
inputs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inputs)


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value))


class StageInputTests(unittest.TestCase):
    def test_immutable_id_downloads_keep_the_verified_input_root_without_name_subdirectory(self):
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/android.yml").read_text()
        blocks = re.findall(r"^      - name: Download[^\n]*\n(?:(?!^      - ).*\n)*", workflow, re.MULTILINE)
        self.assertEqual(3, len(blocks))
        for block in blocks:
            self.assertIn("actions/download-artifact@d3f86a106a0bac45b974a628896c90dbdf5c8093", block)
            self.assertRegex(block, r"artifact-ids: \$\{\{ needs\.contracts\.outputs\.(schema|protocol)_id \}\}")
            self.assertIn("          merge-multiple: true\n", block)
            self.assertRegex(block, r"path: build/ci-inputs/(schema|protocol)\n")

    @contextmanager
    def fixture(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder) / "checkout"
            schema = Path(folder) / "schema"
            protocol = Path(folder) / "protocol"
            env = {"GITHUB_SHA": "1" * 40, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2"}
            files = ["core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/%d.json" % n for n in range(11, 15)] + inputs.SCHEMA_EXTRAS
            raw = {}
            for name in files:
                raw[name] = ("synthetic checkout " + name).encode()
                for base in (root, schema):
                    p = base / name
                    p.parent.mkdir(parents=True, exist_ok=True)
                    p.write_bytes(raw[name])
            for name, version in inputs.SCHEMA_METADATA.items():
                digest = hashlib.sha256(raw[files[version - 11]]).hexdigest()
                save(schema / name, {"head": env["GITHUB_SHA"], "mode": "CHECKED_IN", "expected_sha256": digest, "generated_sha256": digest})
            for base, stage in ((schema, "schema"), (protocol, "protocol")):
                save(base / ("build/ci-stage-evidence/%s/receipt.json" % stage),
                     {"source_sha": env["GITHUB_SHA"], "run_id": 123, "run_attempt": 2, "job": "contracts", "stage": stage, "producer_outcome": "success"})
            suite = ET.Element("testsuite", tests="3", failures="0", errors="0", skipped="0")
            methods = [["synthetic.Domain", name] for name in ("one", "two", "three")]
            for cls, name in methods:
                ET.SubElement(suite, "testcase", classname=cls, name=name)
            xml = ET.tostring(suite)
            relative = "checks/ai-protocol/build/reports/xml-snapshots/synthetic/core_domain/TEST-synthetic.Domain.xml"
            xml_path = protocol / relative
            xml_path.parent.mkdir(parents=True, exist_ok=True)
            xml_path.write_bytes(xml)
            module = {"module": "core:domain", "tests": 3, "failures": 0, "errors": 0, "skipped": 0, "methods": methods,
                      "xml": [{"path": relative, "tests": 3, "sha256": hashlib.sha256(xml).hexdigest()}]}
            report = protocol / "checks/ai-protocol/build/reports/verified-contracts.json"
            save(report, {"head": env["GITHUB_SHA"], "status": "PASS", "source_sha256": "a" * 64, "results": [module]})
            save(protocol / "core/domain/build/reports/inputs-at-test.json", {"source_sha256": "a" * 64})
            remote = {"module": "remote-text", "tests": 26, "failures": 0, "errors": 0, "skipped": 0, "methods": [], "xml": []}
            for name, (cls, count) in inputs.BACKUP_XML.items():
                backup_suite = ET.Element("testsuite", tests=str(count), failures="0", errors="0", skipped="0")
                for index in range(count):
                    method = "synthetic%d" % index
                    ET.SubElement(backup_suite, "testcase", classname=cls, name=method)
                    remote["methods"].append([cls, method])
                backup_raw = ET.tostring(backup_suite)
                backup_path = "checks/ai-protocol/build/reports/xml-snapshots/synthetic/remote-text/" + name
                p = protocol / backup_path
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(backup_raw)
                remote["xml"].append({"path": backup_path, "tests": count, "sha256": hashlib.sha256(backup_raw).hexdigest()})
            record = json.loads(report.read_text())
            record["results"].append(remote)
            save(report, record)
            save(protocol / "checks/ai-protocol/remote-text/build/reports/inputs-at-test.json", {"source_sha256": "a" * 64})
            with patch.object(inputs, "checkout_files", return_value=files), patch.object(inputs, "checkout_bytes", side_effect=lambda _, name: raw[name]):
                yield root, schema, protocol, env, report, xml_path, raw

    def test_valid_transfer_keeps_source_bytes_and_labels_imported_domain_without_rerun(self):
        with self.fixture() as (root, schema, protocol, env, _, xml, original):
            result = json.loads(inputs.transfer(root, schema, protocol, env).read_text())
            copied = root / "core/domain/build/test-results/test" / xml.name
            self.assertEqual(xml.read_bytes(), copied.read_bytes())
            self.assertIn("NOT rerun", result["scope"])
            self.assertEqual(123, result["run_id"])
            self.assertEqual(3, len(result["host_xml"]))
            for name, (_, count) in inputs.BACKUP_XML.items():
                copied_backup = root / inputs.BACKUP_TARGET / name
                source_backup = protocol / ("checks/ai-protocol/build/reports/xml-snapshots/synthetic/remote-text/" + name)
                self.assertEqual(source_backup.read_bytes(), copied_backup.read_bytes())
                self.assertEqual(count, len(list(ET.fromstring(copied_backup.read_bytes()).iter("testcase"))))
            for name, raw in original.items():
                self.assertEqual(raw, (root / name).read_bytes())

    def test_foreign_source_run_attempt_job_stage_or_failed_producer_cannot_publish(self):
        changes = {"source_sha": "2" * 40, "run_id": 124, "run_attempt": 1, "job": "foreign", "stage": "other", "producer_outcome": "failure"}
        for key, value in changes.items():
            with self.subTest(key=key), self.fixture() as (root, schema, protocol, env, _, _, _):
                path = schema / "build/ci-stage-evidence/schema/receipt.json"
                receipt = json.loads(path.read_text())
                save(path, {**receipt, key: value})
                with self.assertRaises(ValueError):
                    inputs.transfer(root, schema, protocol, env)
                self.assertFalse((root / "core/domain/build").exists())

    def test_missing_changed_extra_symlink_schema_or_wrong_generation_metadata_fails_closed(self):
        for kind in ("missing", "changed", "extra", "symlink", "metadata", "lock"):
            with self.subTest(kind=kind), self.fixture() as (root, schema, protocol, env, _, _, original):
                p = schema / next(iter(original))
                if kind == "missing":
                    p.unlink()
                elif kind == "changed":
                    p.write_bytes(b"synthetic different schema")
                elif kind == "extra":
                    (schema / "unexpected.txt").write_text("synthetic")
                elif kind == "symlink":
                    p.unlink()
                    p.symlink_to(root / next(iter(original)))
                elif kind == "lock":
                    (schema / "app/gradle.lockfile").write_text("different lock")
                else:
                    name = next(iter(inputs.SCHEMA_METADATA))
                    record = json.loads((schema / name).read_text())
                    save(schema / name, {**record, "head": "2" * 40})
                with self.assertRaises(ValueError):
                    inputs.transfer(root, schema, protocol, env)
                self.assertFalse((root / "core/domain/build").exists())
                for name, raw in original.items():
                    self.assertEqual(raw, (root / name).read_bytes())

    def test_domain_digest_path_methods_and_nonpassing_xml_are_checked_before_publish(self):
        for kind in ("digest", "path", "methods", "skipped", "missing", "capture"):
            with self.subTest(kind=kind), self.fixture() as (root, schema, protocol, env, report_path, xml, _):
                report = json.loads(report_path.read_text())
                module = report["results"][0]
                if kind == "digest":
                    module["xml"][0]["sha256"] = "0" * 64
                elif kind == "path":
                    module["xml"][0]["path"] = "../outside/TEST-synthetic.Domain.xml"
                elif kind == "methods":
                    module["methods"][0][1] = "foreign"
                elif kind == "missing":
                    xml.unlink()
                elif kind == "capture":
                    save(protocol / "core/domain/build/reports/inputs-at-test.json", {"source_sha256": "b" * 64})
                else:
                    suite = ET.fromstring(xml.read_bytes())
                    ET.SubElement(suite.find("testcase"), "skipped")
                    xml.write_bytes(ET.tostring(suite))
                    module["xml"][0]["sha256"] = hashlib.sha256(xml.read_bytes()).hexdigest()
                save(report_path, report)
                with self.assertRaises(ValueError):
                    inputs.transfer(root, schema, protocol, env)
                self.assertFalse((root / "core/domain/build").exists())

    def test_existing_domain_evidence_is_preserved_without_overwrite(self):
        with self.fixture() as (root, schema, protocol, env, _, _, _):
            p = root / "core/domain/build/test-results/test/existing.xml"
            p.parent.mkdir(parents=True)
            p.write_bytes(b"synthetic existing bytes")
            with self.assertRaisesRegex(ValueError, "overwrite"):
                inputs.transfer(root, schema, protocol, env)
            self.assertEqual(b"synthetic existing bytes", p.read_bytes())

    def test_no_source_domain_cannot_become_successful_import(self):
        with self.fixture() as (root, schema, protocol, env, report_path, _, _):
            report = json.loads(report_path.read_text())
            report["results"][0].update(tests=0, xml=[], methods=[])
            save(report_path, report)
            with self.assertRaisesRegex(ValueError, "Missing domain"):
                inputs.transfer(root, schema, protocol, env)

    def test_both_required_backup_xml_are_authenticated_before_any_host_xml_publish(self):
        for name in inputs.BACKUP_XML:
            for kind in ("missing", "digest", "count", "methods", "skipped", "capture", "module"):
                with self.subTest(name=name, kind=kind), self.fixture() as (root, schema, protocol, env, report_path, _, _):
                    report = json.loads(report_path.read_text())
                    module = report["results"][1]
                    item = next(x for x in module["xml"] if x["path"].endswith(name))
                    path = protocol / item["path"]
                    if kind == "missing":
                        path.unlink()
                    elif kind == "digest":
                        path.write_bytes(b"synthetic changed XML")
                    elif kind == "count":
                        item["tests"] += 1
                    elif kind == "methods":
                        cls = inputs.BACKUP_XML[name][0]
                        next(x for x in module["methods"] if x[0] == cls)[1] = "foreign"
                    elif kind == "capture":
                        save(protocol / "checks/ai-protocol/remote-text/build/reports/inputs-at-test.json", {"source_sha256": "b" * 64})
                    elif kind == "module":
                        module["failures"] = 1
                    else:
                        suite = ET.fromstring(path.read_bytes())
                        ET.SubElement(suite.find("testcase"), "skipped")
                        path.write_bytes(ET.tostring(suite))
                        item["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
                    save(report_path, report)
                    with self.assertRaises(ValueError):
                        inputs.transfer(root, schema, protocol, env)
                    self.assertFalse((root / "core/domain/build").exists())
                    self.assertFalse((root / inputs.BACKUP_TARGET).exists())

    def test_preview_schema_only_transfer_does_not_require_or_create_host_xml(self):
        with self.fixture() as (root, schema, _, env, _, _, _):
            result = json.loads(inputs.transfer(root, schema, None, env).read_text())
            self.assertEqual([], result["host_xml"])
            self.assertFalse((root / "core/domain/build").exists())
            self.assertFalse((root / inputs.BACKUP_TARGET).exists())
