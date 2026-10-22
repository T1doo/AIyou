"""Execute actual migration/DAO/trigger SQL in host SQLite; not Room/SQLCipher evidence."""
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / "core/data/src/main/kotlin/com/t1doo/aiyou/data/RecordsDatabase.kt").read_text()


def statements(block):
    return [json.loads('"' + value + '"') for value in re.findall(r'db\.execSQL\("((?:[^"\\]|\\.)*)"\)', block)]


class AttachmentSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys=ON")
        schema = json.loads((ROOT / "core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/2.json").read_text())
        for table in schema["database"]["entities"]:
            self.db.execute(table["createSql"].replace("${TABLE_NAME}", table["tableName"]))
            for index in table.get("indices", []):
                self.db.execute(index["createSql"].replace("${TABLE_NAME}", table["tableName"]))
        text = "合成v2原文 🌿"
        with self.db:
            self.db.execute("INSERT INTO entries VALUES('synthetic',1,1000,'Asia/Shanghai','LOCAL_ONLY')")
            self.db.execute("INSERT INTO entry_revisions VALUES('synthetic',1,?,?,1000)",
                            (text, hashlib.sha256(text.encode()).hexdigest()))
            self.db.execute("INSERT INTO capture_draft VALUES(1,'draft',3,'合成独立草稿')")
            self.db.execute("INSERT INTO local_jobs VALUES('synthetic:1:LOCAL_INDEX','synthetic',1,'LOCAL_INDEX','PENDING')")
        self.before = self.snapshot()
        migration = SOURCE.split("internal val recordsMigration2To3 =", 1)[1].split("internal fun installRevisionGuard", 1)[0]
        guard = (ROOT / "core/data/src/main/kotlin/com/t1doo/aiyou/data/AttachmentIntentSqlSchema.kt").read_text()
        update = re.search(r'val updateGuard = ("(?:[^"\\]|\\.)*")', guard)
        identity = re.search(r'val identityGuard = """(.*?)""".trimIndent\(\)', guard, re.S)
        self.assertIsNotNone(update)
        self.assertIsNotNone(identity)
        self.assertIn("AttachmentIntentSqlSchema.guards.forEach(db::execSQL)", SOURCE)
        guards = [json.loads(update.group(1)), identity.group(1).strip()]
        with self.db:
            for query in statements(migration) + guards:
                self.db.execute(query)
        self.intent = ("synthetic-attachment", "synthetic", 1, "image/png", "a" * 64, 100, "WRITING")

    def tearDown(self):
        self.db.close()

    def snapshot(self):
        return {table: self.db.execute(f"SELECT * FROM {table} ORDER BY 1").fetchall()
                for table in ("entries", "entry_revisions", "capture_draft", "local_jobs")}

    def test_actual_migration_retains_all_original_draft_and_job_fields(self):
        self.assertEqual(self.before, self.snapshot())
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())
        self.assertEqual([], self.db.execute("SELECT * FROM attachments").fetchall())
        self.assertEqual([(0, "entryId"), (1, "revision")],
                         [(row[0], row[2]) for row in self.db.execute("PRAGMA index_info(index_attachments_entryId_revision)")])

    def test_attachment_association_rejects_missing_original_revision_and_duplicate_identity(self):
        with self.assertRaises(sqlite3.IntegrityError), self.db:
            self.db.execute("INSERT INTO attachments VALUES(?,?,?,?,?,?,?)", (*self.intent[:2], 99, *self.intent[3:]))
        self.assertEqual([], self.db.execute("SELECT * FROM attachments").fetchall())
        with self.db:
            self.db.execute("INSERT INTO attachments VALUES(?,?,?,?,?,?,?)", self.intent)
        with self.assertRaises(sqlite3.IntegrityError), self.db:
            self.db.execute("INSERT INTO attachments VALUES(?,?,?,?,?,?,?)", self.intent)
        self.assertEqual([self.intent], self.db.execute("SELECT * FROM attachments").fetchall())
        self.assertEqual(self.before, self.snapshot())

    def test_immutable_intent_trigger_and_actual_state_compare_and_set(self):
        with self.db:
            self.db.execute("INSERT INTO attachments VALUES(?,?,?,?,?,?,?)", self.intent)
        for column, value in (("id", "other"), ("entryId", "other"), ("revision", 2),
                              ("mediaType", "audio/ogg"), ("sha256", "b" * 64), ("byteSize", 101)):
            with self.assertRaises(sqlite3.IntegrityError), self.db:
                self.db.execute(f"UPDATE attachments SET {column}=?", (value,))
        query = re.search(r'@Query\("(UPDATE attachments [^"]+)"\)', SOURCE).group(1)
        with self.db:
            self.assertEqual(0, self.db.execute(query, {"next": "READY", "id": self.intent[0], "expected": "STAGED"}).rowcount)
            self.assertEqual(1, self.db.execute(query, {"next": "STAGED", "id": self.intent[0], "expected": "WRITING"}).rowcount)
            self.assertEqual(1, self.db.execute(query, {"next": "READY", "id": self.intent[0], "expected": "STAGED"}).rowcount)
        self.assertEqual(self.intent[:6] + ("READY",), self.db.execute("SELECT * FROM attachments").fetchone())

    def test_replace_cannot_bypass_intent_or_preserve_epoch_by_changing_image_to_audio(self):
        with self.db:
            self.db.execute("INSERT INTO attachments VALUES(?,?,?,?,?,?,?)", self.intent)
        for mime in ("audio/ogg", "image/png"):
            with self.assertRaises(sqlite3.IntegrityError), self.db:
                self.db.execute("INSERT OR REPLACE INTO attachments VALUES(?,?,?,?,?,?,?)",
                                (*self.intent[:3], mime, "b" * 64, 90, "READY"))
            self.assertEqual([self.intent], self.db.execute("SELECT * FROM attachments").fetchall())
        self.assertEqual(self.before, self.snapshot())

    def test_final_state_transaction_failure_retains_staged_association_and_text(self):
        with self.db:
            self.db.execute("INSERT INTO attachments VALUES(?,?,?,?,?,?,?)", self.intent[:6] + ("STAGED",))
            self.db.execute("CREATE TRIGGER synthetic_failure BEFORE UPDATE ON attachments WHEN NEW.state='READY' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        with self.assertRaises(sqlite3.IntegrityError), self.db:
            self.db.execute("UPDATE attachments SET state='READY'")
        self.assertEqual(self.intent[:6] + ("STAGED",), self.db.execute("SELECT * FROM attachments").fetchone())
        self.assertEqual(self.before, self.snapshot())


if __name__ == "__main__":
    unittest.main()
