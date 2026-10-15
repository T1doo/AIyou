#!/usr/bin/env python3
"""Verify actual KSP v10 additive candidate schema; never synthesize a Room export."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
directory = root / 'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase'
export = directory / '10.json'
asset = root / 'core/data/src/androidTest/assets/records-schema-v10.json'
receipt = root / '.local-evidence/d05-local-candidates/schema-generation.json'
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
FIELDS = [('id','TEXT'),('entryId','TEXT'),('sourceRevision','INTEGER'),('sourceHash','TEXT'),
          ('generation','INTEGER'),('ruleVersion','INTEGER'),('version','INTEGER'),('state','TEXT'),
          ('lastMutationId','TEXT'),('createdAtMillis','INTEGER'),('payload','BLOB')]

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def checkout(path):
    return subprocess.check_output(['git', 'show', head + ':' + path.relative_to(root).as_posix()], cwd=root)

def historical():
    result = {}
    for version in range(2, 10):
        source = directory / f'{version}.json'
        fixture = asset.parent / f'records-schema-v{version}.json'
        raw = source.read_bytes()
        if raw != fixture.read_bytes() or raw != checkout(source) or raw != checkout(fixture):
            raise SystemExit('Historical schema/fixture differs from exact checkout: ' + str(version))
        result[str(version)] = digest(raw)
    return result

def normalized(sql):
    return re.sub(r'\s+', '', sql.replace(chr(96), '').replace('"', '')).lower()

def validate(raw):
    schema = json.loads(raw)['database']
    old = json.loads((directory / '9.json').read_bytes())['database']
    by_name = {entity['tableName']: entity for entity in schema['entities']}
    prior = {entity['tableName']: entity for entity in old['entities']}
    if schema['version'] != 10 or len(schema['entities']) != 17 or set(by_name) != set(prior) | {'local_candidate_batches'}:
        raise SystemExit('Unexpected actual v10 entity inventory')
    if any(by_name[name] != entity for name, entity in prior.items()):
        raise SystemExit('v10 changed a prior independent entity')
    entity = by_name['local_candidate_batches']
    expected_fields = [{'fieldPath': name, 'columnName': name, 'affinity': affinity, 'notNull': True}
                       for name, affinity in FIELDS]
    if entity['fields'] != expected_fields or entity['primaryKey'] != {'autoGenerate': False, 'columnNames': ['id']}:
        raise SystemExit('v10 candidate fields/primary key differ from exact contract')
    foreign = [{'table':'entries','onDelete':'CASCADE','onUpdate':'NO ACTION','columns':['entryId'],'referencedColumns':['id']}]
    if entity.get('foreignKeys') != foreign:
        raise SystemExit('v10 candidate source foreign key differs')
    expected_sql = 'CREATE TABLE IF NOT EXISTS local_candidate_batches (' + ', '.join(
        name + ' ' + affinity + ' NOT NULL' for name, affinity in FIELDS) + \
        ', PRIMARY KEY(id), FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE)'
    actual_sql = entity['createSql'].replace(chr(36) + '{TABLE_NAME}', 'local_candidate_batches')
    if normalized(expected_sql) != normalized(actual_sql):
        raise SystemExit('v10 candidate CREATE SQL differs')
    indices = entity.get('indices', [])
    index_name = 'index_local_candidate_batches_entryId_sourceRevision'
    if len(indices) != 1 or indices[0]['name'] != index_name or indices[0]['unique'] is not False or \
            indices[0]['columnNames'] != ['entryId','sourceRevision'] or indices[0].get('orders', []) != []:
        raise SystemExit('v10 candidate index differs')
    index_sql = indices[0]['createSql'].replace(chr(36) + '{TABLE_NAME}', 'local_candidate_batches')
    if normalized(index_sql) != normalized('CREATE INDEX IF NOT EXISTS ' + index_name + ' ON local_candidate_batches (entryId,sourceRevision)'):
        raise SystemExit('v10 candidate index CREATE SQL differs')
    if not re.fullmatch('[0-9a-f]{32}', schema['identityHash']):
        raise SystemExit('Missing actual Room identity')

if sys.argv[1:] == ['--capture']:
    prior = historical()
    if export.exists() != asset.exists():
        raise SystemExit('Partial v10 schema/fixture state; preserve and stop')
    if export.exists():
        raw = export.read_bytes()
        if raw != asset.read_bytes() or raw != checkout(export) or raw != checkout(asset):
            raise SystemExit('v10 schema/fixture differs from exact checkout')
        validate(raw); mode, expected = 'CHECKED_IN', digest(raw)
    else:
        tracked = subprocess.check_output(['git','ls-files','--',str(export.relative_to(root)),str(asset.relative_to(root))], cwd=root)
        if tracked:
            raise SystemExit('Tracked v10 schema/fixture missing; stop')
        mode, expected = 'BOOTSTRAP', None
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps({'head':head,'mode':mode,'expected_sha256':expected,'historical':prior}) + '\n')
elif sys.argv[1:] == ['--verify']:
    state = json.loads(receipt.read_text())
    if state['head'] != head or state['mode'] not in ('BOOTSTRAP','CHECKED_IN') or state['historical'] != historical():
        raise SystemExit('Checkout or historical bytes changed during v10 generation')
    generated = export.read_bytes(); validate(generated)
    if state['mode'] == 'CHECKED_IN':
        if digest(generated) != state['expected_sha256'] or generated != asset.read_bytes() or generated != checkout(export) or generated != checkout(asset):
            raise SystemExit('KSP changed committed v10 bytes; preserve and reject drift')
    else:
        if asset.exists():
            raise SystemExit('Unexpected v10 fixture during bootstrap')
        asset.write_bytes(generated)
    state.update({'generated_sha256':digest(generated),'bytes':len(generated),'scope':
        'exact checkout bytes verified' if state['mode'] == 'CHECKED_IN' else
        'actual generated untracked v10 schema/fixture; commit bytes then require another exact-head CI'})
    receipt.write_text(json.dumps(state, indent=2) + '\n')
    print('LOCAL_CANDIDATE_SCHEMA=' + json.dumps(state, separators=(',',':')))
else:
    raise SystemExit('Usage: verify-local-candidate-schema.py --capture|--verify')
