#!/usr/bin/env python3
"""Verify exact checked-in Room17 output and the unchanged v2–16 source contracts."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
directory = root / 'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase'
export = directory / '17.json'
asset = root / 'core/data/src/main/assets/reminder-schema-v17.json'
receipt = root / '.local-evidence/chat-history/schema-generation.json'
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def checkout(path):
    return subprocess.check_output(['git', 'show', head + ':' + path.relative_to(root).as_posix()], cwd=root)

def validate(committed=True):
    raw = export.read_bytes()
    current = json.loads(raw)['database']
    old = json.loads((directory / '16.json').read_bytes())['database']
    assert current['version'] == 17 and len(current['entities']) == 25
    assert current['entities'][:-2] == old['entities'] and current.get('views', []) == old.get('views', [])
    messages, sources = current['entities'][-2:]
    fields = [('id', 'TEXT'), ('version', 'INTEGER'), ('originProof', 'TEXT'), ('resultHash', 'TEXT'),
              ('createdAtMillis', 'INTEGER'), ('updatedAtMillis', 'INTEGER'), ('state', 'TEXT'),
              ('lastMutationId', 'TEXT'), ('question', 'BLOB'), ('answer', 'BLOB')]
    relation = [('messageId', 'TEXT'), ('ordinal', 'INTEGER'), ('entryId', 'TEXT'),
                ('sourceRevision', 'INTEGER'), ('sourceHash', 'TEXT'), ('generation', 'INTEGER')]
    for table, name, columns, primary in [(messages, 'chat_messages', fields, ['id']),
                                          (sources, 'chat_message_sources', relation, ['messageId', 'ordinal'])]:
        assert table['tableName'] == name
        assert table['fields'] == [dict(fieldPath=n, columnName=n, affinity=a, notNull=True) for n, a in columns]
        assert table['primaryKey'] == {'autoGenerate': False, 'columnNames': primary}
    assert not messages.get('indices', []) and not messages.get('foreignKeys', [])
    assert sources['foreignKeys'] == [dict(table=t, onDelete='CASCADE', onUpdate='NO ACTION',
        columns=[c], referencedColumns=['id']) for t,c in [('chat_messages','messageId'),('entries','entryId')]]
    assert sources['indices'] == [dict(name='index_chat_message_sources_entryId', unique=False,
        columnNames=['entryId'], orders=[], createSql='CREATE INDEX IF NOT EXISTS `index_chat_message_sources_entryId` ON `${TABLE_NAME}` (`entryId`)')]
    normalize = lambda s: re.sub(r'\s+', '', s.replace('`', '').replace('"', '')).lower()
    expected_messages = 'CREATE TABLE IF NOT EXISTS chat_messages (' + ', '.join(n+' '+a+' NOT NULL' for n,a in fields)+', PRIMARY KEY(id))'
    expected_sources = 'CREATE TABLE IF NOT EXISTS chat_message_sources (' + ', '.join(n+' '+a+' NOT NULL' for n,a in relation)+', PRIMARY KEY(messageId,ordinal), FOREIGN KEY(messageId) REFERENCES chat_messages(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE)'
    for table,name,expected in [(messages,'chat_messages',expected_messages),(sources,'chat_message_sources',expected_sources)]:
        assert normalize(table['createSql'].replace('${TABLE_NAME}',name)) == normalize(expected)
    assert re.fullmatch('[0-9a-f]{32}', current['identityHash'])
    assert current['setupQueries'] == ['CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)',
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '" + current['identityHash'] + "')"]
    assert raw == asset.read_bytes()
    if committed:
        assert raw == checkout(export) == checkout(asset)
    for version in range(2, 17):
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
    print('CHAT_MESSAGE_SCHEMA=' + json.dumps(state, separators=(',', ':')))
else:
    raise SystemExit('Usage: verify-chat-message-schema.py --capture|--verify')
