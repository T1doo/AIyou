#!/usr/bin/env python3
"""Verify exact checked-in Room16 output and the unchanged v2–15 source contracts."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
directory = root / 'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase'
export = directory / '16.json'
asset = root / 'core/data/src/main/assets/reminder-schema-v16.json'
receipt = root / '.local-evidence/saved-questions/schema-generation.json'
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def checkout(path):
    return subprocess.check_output(['git', 'show', head + ':' + path.relative_to(root).as_posix()], cwd=root)

def validate():
    raw = export.read_bytes()
    current = json.loads(raw)['database']
    old = json.loads((directory / '15.json').read_bytes())['database']
    assert current['version'] == 16 and len(current['entities']) == 23
    assert current['entities'][:-1] == old['entities'] and current.get('views', []) == old.get('views', [])
    new = current['entities'][-1]
    fields = [('id', 'TEXT'), ('version', 'INTEGER'), ('lastMutationId', 'TEXT'),
              ('createdAtMillis', 'INTEGER'), ('updatedAtMillis', 'INTEGER'), ('state', 'TEXT'), ('payload', 'BLOB')]
    assert new['tableName'] == 'saved_questions'
    assert new['fields'] == [dict(fieldPath=n, columnName=n, affinity=a, notNull=True) for n, a in fields]
    assert new['primaryKey'] == {'autoGenerate': False, 'columnNames': ['id']}
    assert not new.get('indices', []) and not new.get('foreignKeys', [])
    expected = 'CREATE TABLE IF NOT EXISTS saved_questions (' + ', '.join(n + ' ' + a + ' NOT NULL' for n, a in fields) + ', PRIMARY KEY(id))'
    normalize = lambda s: re.sub(r'\s+', '', s.replace('`', '').replace('"', '')).lower()
    assert normalize(new['createSql'].replace('${TABLE_NAME}', 'saved_questions')) == normalize(expected)
    assert re.fullmatch('[0-9a-f]{32}', current['identityHash'])
    assert current['setupQueries'] == ['CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)',
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '" + current['identityHash'] + "')"]
    assert raw == asset.read_bytes() == checkout(export) == checkout(asset)
    for version in range(2, 16):
        p = directory / f'{version}.json'
        assert p.read_bytes() == checkout(p), f'Historical v{version} changed'
    return raw

if sys.argv[1:] == ['--capture']:
    raw = validate()
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps(dict(head=head, mode='CHECKED_IN', expected_sha256=digest(raw),
                                      generated_sha256=digest(raw), bytes=len(raw))) + '\n')
elif sys.argv[1:] == ['--verify']:
    state = json.loads(receipt.read_text())
    raw = validate()
    assert state == dict(head=head, mode='CHECKED_IN', expected_sha256=digest(raw), generated_sha256=digest(raw), bytes=len(raw))
    print('SAVED_QUESTION_SCHEMA=' + json.dumps(state, separators=(',', ':')))
else:
    raise SystemExit('Usage: verify-saved-question-schema.py --capture|--verify')
