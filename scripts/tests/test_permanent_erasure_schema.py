"""Actual file/checkout gate in an owned synthetic Git repo; no KSP/Room/device proof."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class PermanentErasureSchemaOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="aiyou-erasure-gate-")
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        self.script = self.root / "scripts/verify-permanent-erasure-schema.py"
        shutil.copyfile(Path(__file__).resolve().parents[1] / "verify-permanent-erasure-schema.py", self.script)
        self.export = self.root / "core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/8.json"
        self.asset = self.root / "core/data/src/androidTest/assets/records-schema-v8.json"
        self.export.parent.mkdir(parents=True)
        self.asset.parent.mkdir(parents=True)
        legacy = [{"tableName": "synthetic_legacy_" + str(i), "fields": []} for i in range(13)]
        (self.export.parent / "7.json").write_text(json.dumps({"database": {"version": 7, "entities": legacy}}))
        def entity(name, columns, keys, indexes):
            return {"tableName": name, "foreignKeys": [],
                    "fields": [{"columnName": n, "affinity": a, "notNull": True} for n, a in columns.items()],
                    "primaryKey": {"columnNames": keys}, "indices": indexes}
        self.generated = {"database": {"version": 8, "entities": legacy + [
            entity("permanent_erasure_receipts", {"entryId": "TEXT", "sourceRevision": "INTEGER", "generation": "INTEGER",
                "mutationId": "TEXT", "phase": "TEXT", "plan": "TEXT", "packageProof": "TEXT"}, ["entryId"],
                [{"columnNames": ["mutationId"], "unique": True}]),
            entity("record_package_receipts", {"id": "TEXT", "entryId": "TEXT", "sourceRevision": "INTEGER",
                "operation": "TEXT", "state": "TEXT"}, ["id"], []),
            entity("owned_file_creations", {"bucket": "TEXT", "name": "TEXT", "entryId": "TEXT", "state": "TEXT",
                "identity": "TEXT"}, ["bucket", "name"], [{"columnNames": ["entryId"], "unique": False}]),
        ]}}
        self.raw = json.dumps(self.generated).encode()
        self.git("init", "-b", "schema-test")
        (self.root / "README").write_text("Synthetic ownership-gate fixture, never a generated product schema proof\n")
        self.commit()

    def tearDown(self):
        self.temporary.cleanup()

    def git(self, *args):
        return subprocess.run(["git", "-c", "user.name=Schema Fixture", "-c", "user.email=fixture@example.invalid",
            "-c", "commit.gpgsign=false", *args], cwd=self.root, capture_output=True, check=True)

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-m", "Synthetic gate fixture")

    def gate(self, phase):
        return subprocess.run(["python3", str(self.script), phase], cwd=self.root, capture_output=True, text=True)

    def test_bootstrap_preserves_legacy_and_requires_committed_bytes_then_another_exact_head_run(self):
        legacy = (self.export.parent / "7.json").read_bytes()
        self.assertEqual(0, self.gate("--capture").returncode)
        self.export.write_bytes(self.raw)
        result = self.gate("--verify")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.raw, self.asset.read_bytes())
        self.assertEqual(legacy, (self.export.parent / "7.json").read_bytes())
        self.assertIn("require another exact-head CI", result.stdout)
        self.commit()
        self.assertEqual(0, self.gate("--capture").returncode)
        self.assertEqual(0, self.gate("--verify").returncode)

    def test_committed_generation_drift_is_preserved_and_rejected(self):
        self.export.write_bytes(self.raw); self.asset.write_bytes(self.raw); self.commit()
        self.assertEqual(0, self.gate("--capture").returncode)
        changed = self.raw + b"\n"
        self.export.write_bytes(changed)
        self.assertNotEqual(0, self.gate("--verify").returncode)
        self.assertEqual(changed, self.export.read_bytes())
        self.assertEqual(self.raw, self.asset.read_bytes())

    def test_partial_tracked_missing_or_modified_fixture_cannot_be_repaired_by_bootstrap(self):
        self.export.write_bytes(self.raw)
        self.assertNotEqual(0, self.gate("--capture").returncode)
        self.assertFalse(self.asset.exists())
        self.asset.write_bytes(self.raw); self.commit(); self.asset.unlink(); self.export.unlink()
        self.assertNotEqual(0, self.gate("--capture").returncode)
        self.assertFalse(self.asset.exists())
        self.assertFalse(self.export.exists())

    def test_head_change_stops_generation_without_creating_fixture(self):
        self.assertEqual(0, self.gate("--capture").returncode)
        (self.root / "README").write_text("Changed synthetic source\n"); self.commit()
        self.export.write_bytes(self.raw)
        self.assertNotEqual(0, self.gate("--verify").returncode)
        self.assertFalse(self.asset.exists())

    def test_wrong_receipt_key_fk_columns_and_changed_original_entity_fail_without_creating_fixture(self):
        self.assertEqual(0, self.gate("--capture").returncode)
        for change in ("columns", "legacy", "inventory", "foreign_key", "key", "mutation_index"):
            value = json.loads(self.raw)
            if change == "columns":
                value["database"]["entities"][-1]["fields"] = value["database"]["entities"][-1]["fields"][:-1]
            elif change == "legacy":
                value["database"]["entities"][0]["fields"].append({"columnName": "changed"})
            elif change == "foreign_key":
                value["database"]["entities"][-1]["foreignKeys"] = [{"table": "entries"}]
            elif change == "key":
                value["database"]["entities"][-1]["primaryKey"]["columnNames"] = ["name"]
            elif change == "mutation_index":
                value["database"]["entities"][-3]["indices"][0]["unique"] = False
            else:
                value["database"]["entities"].append({"tableName": "unregistered"})
            changed = json.dumps(value).encode()
            self.export.write_bytes(changed)
            self.assertNotEqual(0, self.gate("--verify").returncode)
            self.assertFalse(self.asset.exists())
            self.assertEqual(changed, self.export.read_bytes())
