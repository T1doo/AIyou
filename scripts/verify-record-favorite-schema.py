#!/usr/bin/env python3
"""Check real KSP v9 bytes, preserving historical exports and exact-checkout provenance."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
directory = root / 'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase'
export = directory / '9.json'
asset = root / 'core/data/src/androidTest/assets/records-schema-v9.json'
receipt = root / '.local-evidence/record-favorites/schema-generation.json'
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def checkout(path):
    return subprocess.check_output(['git', 'show', head + ':' + path.relative_to(root).as_posix()], cwd=root)

def historical():
    result = {}
    for version in range(2, 9):
        source = directory / f'{version}.json'
        fixture = asset.parent / f'records-schema-v{version}.json'
        raw = source.read_bytes()
        if raw != fixture.read_bytes() or raw != checkout(source) or raw != checkout(fixture):
            raise SystemExit('Historical schema/fixture differs from exact checkout: ' + str(version))
        result[str(version)] = digest(raw)
    return result

def validate(generated):
    schema = json.loads(generated)['database']
    old = json.loads((directory / '8.json').read_bytes())['database']
    by_name = {e['tableName']: e for e in schema['entities']}
    if schema['version'] != 9 or len(schema['entities']) != 16 or set(by_name) != {e['tableName'] for e in old['entities']}:
        raise SystemExit('Unexpected actual v9 entity inventory')
    for prior in old['entities']:
        actual = by_name[prior['tableName']]
        if prior['tableName'] != 'entries':
            if actual != prior:
                raise SystemExit('v9 changed a prior independent entity: ' + prior['tableName'])
            continue
        additions = [
            {'fieldPath': 'isFavorite', 'columnName': 'isFavorite', 'affinity': 'INTEGER', 'notNull': True, 'defaultValue': '0'},
            {'fieldPath': 'favoriteVersion', 'columnName': 'favoriteVersion', 'affinity': 'INTEGER', 'notNull': True, 'defaultValue': '0'},
            {'fieldPath': 'lastFavoriteMutationId', 'columnName': 'lastFavoriteMutationId', 'affinity': 'TEXT', 'notNull': False},
            {'fieldPath': 'lastFavoriteSourceRevision', 'columnName': 'lastFavoriteSourceRevision', 'affinity': 'INTEGER', 'notNull': True, 'defaultValue': '0'},
        ]
        expected = dict(prior)
        expected['fields'] = prior['fields'] + additions
        expected['createSql'] = prior['createSql'].replace(', PRIMARY KEY',
            ', `isFavorite` INTEGER NOT NULL DEFAULT 0, `favoriteVersion` INTEGER NOT NULL DEFAULT 0, '
            '`lastFavoriteMutationId` TEXT, `lastFavoriteSourceRevision` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY', 1)
        if actual != expected:
            raise SystemExit('v9 entries differs from exact four-column additive contract')

if sys.argv[1:] == ['--capture']:
    prior = historical()
    if export.exists() != asset.exists():
        raise SystemExit('Partial v9 schema/fixture state; preserve and stop')
    if export.exists():
        raw = export.read_bytes()
        if raw != asset.read_bytes() or raw != checkout(export) or raw != checkout(asset):
            raise SystemExit('v9 schema/fixture differs from exact checkout')
        validate(raw)
        mode, expected = 'CHECKED_IN', digest(raw)
    else:
        tracked = subprocess.check_output(['git', 'ls-files', '--', str(export.relative_to(root)), str(asset.relative_to(root))], cwd=root)
        if tracked:
            raise SystemExit('Tracked v9 schema/fixture is missing; stop')
        mode, expected = 'BOOTSTRAP', None
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps({'head': head, 'mode': mode, 'expected_sha256': expected, 'historical': prior}) + '\n')
elif sys.argv[1:] == ['--verify']:
    state = json.loads(receipt.read_text())
    if state['head'] != head or state['mode'] not in ('BOOTSTRAP', 'CHECKED_IN') or state['historical'] != historical():
        raise SystemExit('Checkout or historical bytes changed during v9 generation')
    generated = export.read_bytes()
    validate(generated)
    if state['mode'] == 'CHECKED_IN':
        if digest(generated) != state['expected_sha256'] or generated != asset.read_bytes() or generated != checkout(export) or generated != checkout(asset):
            raise SystemExit('KSP changed committed v9 bytes; preserve and reject drift')
    else:
        if asset.exists():
            raise SystemExit('Unexpected v9 fixture appeared during bootstrap')
        asset.write_bytes(generated)
    state.update({'generated_sha256': digest(generated), 'bytes': len(generated), 'scope':
        'exact checkout bytes verified' if state['mode'] == 'CHECKED_IN' else
        'actual generated untracked v9 schema/fixture; commit bytes then require another exact-head CI'})
    receipt.write_text(json.dumps(state, indent=2) + '\n')
    print('RECORD_FAVORITE_SCHEMA=' + json.dumps(state, separators=(',', ':')))
else:
    raise SystemExit('Usage: verify-record-favorite-schema.py --capture|--verify')
