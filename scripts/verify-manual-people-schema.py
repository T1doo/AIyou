#!/usr/bin/env python3
"""Verify actual KSP v12 additive manual people schema; never synthesize a Room export."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
directory = root / 'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase'
export = directory / '12.json'
asset = root / 'core/data/src/androidTest/assets/records-schema-v12.json'
receipt = root / '.local-evidence/f05-manual-people/schema-generation.json'
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
FIELDS = [('entryId','TEXT',True),('version','INTEGER',True),('lastMutationId','TEXT',True),('ciphertext','BLOB',True)]

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def checkout(path):
    return subprocess.check_output(['git', 'show', head + ':' + path.relative_to(root).as_posix()], cwd=root)

def historical():
    result = {}
    for version in range(2, 12):
        source = directory / f'{version}.json'
        fixture = asset.parent / f'records-schema-v{version}.json'
        if not source.is_file() or not fixture.is_file():
            raise SystemExit('Actual checked-in Room v' + str(version) + ' schema/fixture is required before v12 generation; never synthesize it')
        raw = source.read_bytes()
        if raw != fixture.read_bytes() or raw != checkout(source) or raw != checkout(fixture):
            raise SystemExit('Historical schema/fixture differs from exact checkout: ' + str(version))
        result[str(version)] = digest(raw)
    return result

def normalized(sql):
    return re.sub(r'\s+', '', sql.replace(chr(96), '').replace('"', '')).lower()

def validate(raw):
    schema = json.loads(raw)['database']
    old = json.loads((directory / '11.json').read_bytes())['database']
    by_name = {entity['tableName']: entity for entity in schema['entities']}
    prior = {entity['tableName']: entity for entity in old['entities']}
    if schema['version'] != 12 or len(schema['entities']) != 19 or set(by_name) != set(prior) | {'entry_manual_people'}:
        raise SystemExit('Unexpected actual v12 entity inventory')
    if any(by_name[name] != entity for name, entity in prior.items()):
        raise SystemExit('v12 changed a prior independent entity')
    entity = by_name['entry_manual_people']
    expected_fields = [dict({'fieldPath': name, 'columnName': name, 'affinity': affinity}, **({'notNull': True} if required else {}))
                       for name, affinity, required in FIELDS]
    if entity['fields'] != expected_fields or entity['primaryKey'] != {'autoGenerate': False, 'columnNames': ['entryId']}:
        raise SystemExit('v12 manual people fields/primary key differ from exact contract')
    foreign = [{'table':'entries','onDelete':'CASCADE','onUpdate':'NO ACTION','columns':['entryId'],'referencedColumns':['id']}]
    if entity.get('foreignKeys') != foreign:
        raise SystemExit('v12 manual people source foreign key differs')
    expected_sql = 'CREATE TABLE IF NOT EXISTS entry_manual_people (' + ', '.join(
        name + ' ' + affinity + (' NOT NULL' if required else '') for name, affinity, required in FIELDS) + \
        ', PRIMARY KEY(entryId), FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE)'
    actual_sql = entity['createSql'].replace(chr(36) + '{TABLE_NAME}', 'entry_manual_people')
    if normalized(expected_sql) != normalized(actual_sql):
        raise SystemExit('v12 manual people CREATE SQL differs')
    if entity.get('indices', []):
        raise SystemExit('Unexpected v12 manual people index')
    if not re.fullmatch('[0-9a-f]{32}', schema['identityHash']):
        raise SystemExit('Missing actual Room identity')
    if schema.get('views', []) != old.get('views', []):
        raise SystemExit('Unexpected v12 views')
    expected_setup = [
        'CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)',
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '" + schema['identityHash'] + "')",
    ]
    if schema.get('setupQueries') != expected_setup:
        raise SystemExit('v12 Room setup differs from its identity')

if sys.argv[1:] == ['--capture']:
    prior = historical()
    if export.exists() != asset.exists():
        raise SystemExit('Partial v12 schema/fixture state; preserve and stop')
    if export.exists():
        raw = export.read_bytes()
        if raw != asset.read_bytes() or raw != checkout(export) or raw != checkout(asset):
            raise SystemExit('v12 schema/fixture differs from exact checkout')
        validate(raw); mode, expected = 'CHECKED_IN', digest(raw)
    else:
        tracked = subprocess.check_output(['git','ls-files','--',str(export.relative_to(root)),str(asset.relative_to(root))], cwd=root)
        if tracked:
            raise SystemExit('Tracked v12 schema/fixture missing; stop')
        mode, expected = 'BOOTSTRAP', None
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps({'head':head,'mode':mode,'expected_sha256':expected,'historical':prior}) + '\n')
elif sys.argv[1:] == ['--verify']:
    state = json.loads(receipt.read_text())
    if state['head'] != head or state['mode'] not in ('BOOTSTRAP','CHECKED_IN') or state['historical'] != historical():
        raise SystemExit('Checkout or historical bytes changed during v12 generation')
    generated = export.read_bytes(); validate(generated)
    if state['mode'] == 'CHECKED_IN':
        if digest(generated) != state['expected_sha256'] or generated != asset.read_bytes() or generated != checkout(export) or generated != checkout(asset):
            raise SystemExit('KSP changed committed v12 bytes; preserve and reject drift')
    else:
        if asset.exists():
            raise SystemExit('Unexpected v12 fixture during bootstrap')
        asset.write_bytes(generated)
    state.update({'generated_sha256':digest(generated),'bytes':len(generated),'scope':
        'exact checkout bytes verified' if state['mode'] == 'CHECKED_IN' else
        'actual generated untracked v12 schema/fixture; commit bytes then require another exact-head CI'})
    receipt.write_text(json.dumps(state, indent=2) + '\n')
    print('MANUAL_PEOPLE_SCHEMA=' + json.dumps(state, separators=(',',':')))
else:
    raise SystemExit('Usage: verify-manual-people-schema.py --capture|--verify')
