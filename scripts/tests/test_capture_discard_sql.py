"""Actual DAO DELETE against file SQLite across child PIDs; not Room/SQLCipher/Android evidence."""
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / 'core/data/src/main/kotlin/com/t1doo/aiyou/data/RecordsDatabase.kt').read_text()
DELETE = re.search(r'@Query\("(DELETE FROM capture_draft [^"]+)"\)', SOURCE).group(1)


class CaptureDiscardSqlTest(unittest.TestCase):
    def test_conditional_delete_survives_separate_process_and_preserves_originals(self):
        with tempfile.TemporaryDirectory(prefix='aiyou-capture-synthetic-') as directory:
            path = str(Path(directory) / 'synthetic.db')
            with sqlite3.connect(path) as db:
                schema = json.loads((ROOT / 'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/3.json').read_text())
                for table in schema['database']['entities']:
                    db.execute(table['createSql'].replace('${TABLE_NAME}', table['tableName']))
                db.execute("INSERT INTO capture_draft VALUES(1,'synthetic-draft',2,'合成草稿')")
                db.execute("INSERT INTO entries VALUES('original',1,1000,'Etc/UTC','LOCAL_ONLY')")
            program = '''import json, os, sqlite3, sys
with sqlite3.connect(sys.argv[1]) as db:
    changed = db.execute(sys.argv[2], {'id': 'synthetic-draft', 'version': 2}).rowcount
print(json.dumps({'pid': os.getpid(), 'changed': changed}))
'''
            written = json.loads(subprocess.check_output([sys.executable, '-c', program, path, DELETE], text=True, timeout=10))
            read = json.loads(subprocess.check_output([sys.executable, '-c', '''import json, os, sqlite3, sys
with sqlite3.connect(sys.argv[1]) as db:
    print(json.dumps({'pid': os.getpid(), 'drafts': db.execute('SELECT * FROM capture_draft').fetchall(), 'entries': db.execute('SELECT id FROM entries').fetchall()}))
''', path], text=True, timeout=10))
            self.assertNotEqual(written['pid'], read['pid'])
            self.assertEqual(1, written['changed'])
            self.assertEqual([], read['drafts'])
            self.assertEqual([['original']], read['entries'])
            with sqlite3.connect(path) as db:
                db.execute("INSERT INTO capture_draft VALUES(1,'synthetic-new',1,'合成新草稿')")
                self.assertEqual(0, db.execute(DELETE, {'id': 'synthetic-draft', 'version': 2}).rowcount)
                self.assertEqual(1, db.execute('SELECT count(*) FROM capture_draft').fetchone()[0])

    def test_version_mismatch_and_transaction_failure_preserve_draft(self):
        with sqlite3.connect(':memory:') as db:
            db.execute('CREATE TABLE capture_draft(slot INTEGER PRIMARY KEY, id TEXT, version INTEGER, text TEXT)')
            db.execute("INSERT INTO capture_draft VALUES(1,'synthetic',3,'合成草稿')")
            db.commit()
            self.assertEqual(0, db.execute(DELETE, {'id': 'synthetic', 'version': 2}).rowcount)
            db.rollback()
            with self.assertRaisesRegex(RuntimeError, 'synthetic transaction failure'), db:
                self.assertEqual(1, db.execute(DELETE, {'id': 'synthetic', 'version': 3}).rowcount)
                raise RuntimeError('synthetic transaction failure')
            self.assertEqual([('synthetic', 3, '合成草稿')], db.execute('SELECT id,version,text FROM capture_draft').fetchall())


if __name__ == '__main__':
    unittest.main()
