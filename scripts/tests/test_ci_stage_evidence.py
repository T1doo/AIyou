"""Provenance and failure archives are not substitutes for execution/XML evidence."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("ci_stage", Path(__file__).resolve().parents[1] / "ci-stage-evidence.py")
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


class StageEvidenceTests(unittest.TestCase):
    def env(self):
        return {"GITHUB_SHA": "1" * 40, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2", "GITHUB_JOB": "d01"}

    def test_failure_retains_its_outcome_without_inventing_test_pass_or_counts(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(stage.subprocess, "check_output", return_value="1" * 40):
            result = json.loads(stage.capture(Path(folder), "compose", "failure", self.env()).read_text())
            self.assertEqual("failure", result["producer_outcome"])
            self.assertEqual("1" * 40, result["source_sha"])
            self.assertNotIn("PASS", result["kind"])
            self.assertIsInstance(result["test_counts"], str)
            self.assertEqual(2, result["run_attempt"])

    def test_foreign_checkout_is_rejected_before_creating_archive_provenance(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(stage.subprocess, "check_output", return_value="2" * 40):
            with self.assertRaisesRegex(ValueError, "differs"):
                stage.capture(Path(folder), "compose", "success", self.env())
            self.assertFalse((Path(folder) / "build").exists())

    def test_invalid_stage_or_execution_identity_cannot_escape_or_claim_a_run(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(stage.subprocess, "check_output", return_value="1" * 40):
            for name in ("../outside", "a/b", "", "Upper"):
                with self.assertRaises(ValueError):
                    stage.capture(Path(folder), name, "success", self.env())
            for key, value in (("GITHUB_SHA", ""), ("GITHUB_RUN_ID", "0"), ("GITHUB_RUN_ATTEMPT", "1;echo"), ("GITHUB_JOB", "../x")):
                with self.assertRaises(ValueError):
                    stage.capture(Path(folder), "compose", "success", {**self.env(), key: value})
            with self.assertRaises(ValueError):
                stage.capture(Path(folder), "compose", "unknown", self.env())

    def test_late_second_writer_cannot_overwrite_original_stage_outcome(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(stage.subprocess, "check_output", return_value="1" * 40):
            root = Path(folder)
            original = stage.capture(root, "compose", "failure", self.env())
            raw = original.read_bytes()
            with self.assertRaises(FileExistsError):
                stage.capture(root, "compose", "success", self.env())
            self.assertEqual(raw, original.read_bytes())
