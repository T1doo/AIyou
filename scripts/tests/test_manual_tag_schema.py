"""Exercise the actual schema ownership gate in an owned synthetic Git repository, no SDK/KSP."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class ManualTagSchemaOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="aiyou-schema-gate-")
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        self.script = self.root / "scripts/verify-manual-tag-schema.py"
        shutil.copyfile(Path(__file__).resolve().parents[1] / "verify-manual-tag-schema.py", self.script)
        self.export = self.root / "core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/5.json"
        self.asset = self.root / "core/data/src/androidTest/assets/records-schema-v5.json"
        self.export.parent.mkdir(parents=True)
        self.asset.parent.mkdir(parents=True)
        self.git("init", "-b", "schema-test")
        (self.root / "README").write_text("Synthetic gate fixture, not a Room schema proof\n")
        self.commit()
        # Mock generator output is used only to test file ownership, never as product schema.
        self.generated = json.dumps({"database": {"version": 5, "entities": [{} for _ in range(9)]}}).encode()

    def tearDown(self):
        self.temporary.cleanup()

    def git(self, *args):
        return subprocess.run(["git", "-c", "user.name=Schema Fixture", "-c", "user.email=fixture@example.invalid",
                               "-c", "commit.gpgsign=false", *args], cwd=self.root, capture_output=True, check=True)

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-m", "Synthetic ownership fixture")

    def run_gate(self, phase):
        return subprocess.run(["python3", str(self.script), phase], cwd=self.root, capture_output=True, text=True)

    def test_bootstrap_creates_only_missing_fixture_and_requires_another_exact_head_run(self):
        self.assertEqual(0, self.run_gate("--capture").returncode)
        self.export.write_bytes(self.generated)
        verified = self.run_gate("--verify")
        self.assertEqual(0, verified.returncode, verified.stderr)
        self.assertEqual(self.generated, self.asset.read_bytes())
        self.assertIn("require another exact-head CI", verified.stdout)

    def test_checked_in_generation_drift_fails_without_repairing_either_file(self):
        self.export.write_bytes(self.generated)
        self.asset.write_bytes(self.generated)
        self.commit()
        self.assertEqual(0, self.run_gate("--capture").returncode)
        changed = self.generated + b"\n"
        self.export.write_bytes(changed)
        rejected = self.run_gate("--verify")
        self.assertNotEqual(0, rejected.returncode)
        self.assertEqual(changed, self.export.read_bytes())
        self.assertEqual(self.generated, self.asset.read_bytes())
        self.export.write_bytes(self.generated)
        self.assertEqual(0, self.run_gate("--verify").returncode)

    def test_partial_or_changed_fixture_is_preserved_and_cannot_be_bootstrapped(self):
        self.export.write_bytes(self.generated)
        self.assertNotEqual(0, self.run_gate("--capture").returncode)
        self.assertFalse(self.asset.exists())
        self.asset.write_bytes(b"synthetic drift")
        self.assertNotEqual(0, self.run_gate("--capture").returncode)
        self.assertEqual(b"synthetic drift", self.asset.read_bytes())

    def test_head_change_between_capture_and_generation_cannot_create_fixture(self):
        self.assertEqual(0, self.run_gate("--capture").returncode)
        (self.root / "README").write_text("Changed synthetic source\n")
        self.commit()
        self.export.write_bytes(self.generated)
        self.assertNotEqual(0, self.run_gate("--verify").returncode)
        self.assertFalse(self.asset.exists())
        self.assertEqual(self.generated, self.export.read_bytes())
