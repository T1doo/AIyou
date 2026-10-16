"""Synthetic v11 gate controls; never a generated or accepted product Room schema."""
import copy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class RecordEventDateSchemaOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='aiyou-event-date-gate-')
        self.root = Path(self.temporary.name)
        actual = Path(__file__).resolve().parents[2]
        (self.root / 'scripts').mkdir()
        self.script = self.root / 'scripts/verify-record-event-date-schema.py'
        shutil.copyfile(actual / 'scripts/verify-record-event-date-schema.py', self.script)
        self.directory = self.root / 'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase'
        self.assets = self.root / 'core/data/src/androidTest/assets'
        self.directory.mkdir(parents=True)
        self.assets.mkdir(parents=True)
        self.historical = {}
        for version in range(2, 11):
            source = Path(f'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/{version}.json')
            fixture = Path(f'core/data/src/androidTest/assets/records-schema-v{version}.json')
            for relative in (source, fixture):
                raw = (actual / relative).read_bytes()
                (self.root / relative).write_bytes(raw)
                self.historical[relative] = raw
            self.assertEqual(self.historical[source], self.historical[fixture])
        self.export = self.directory / '11.json'
        self.asset = self.assets / 'records-schema-v11.json'
        self.receipt = self.root / '.local-evidence/f05-event-date/schema-generation.json'
        # This deliberately synthetic control starts with the actual v10 bytes.
        # It is only used inside this owned temporary Git checkout, never as a
        # replacement for a KSP-generated product export or Android evidence.
        self.value = json.loads((self.directory / '10.json').read_bytes())
        self.value['database']['version'] = 11
        self.value['database']['identityHash'] = '0' * 32
        fields = [('entryId', 'TEXT', True), ('version', 'INTEGER', True),
                  ('lastMutationId', 'TEXT', True), ('lastSourceRevision', 'INTEGER', True),
                  ('localDate', 'TEXT', False), ('timeZone', 'TEXT', False)]
        event = {
            'tableName': 'entry_event_dates',
            'fields': [dict({'fieldPath': name, 'columnName': name, 'affinity': affinity},
                            **({'notNull': True} if required else {}))
                       for name, affinity, required in fields],
            'createSql': 'CREATE TABLE IF NOT EXISTS `${TABLE_NAME}` (' + ', '.join(
                '`' + name + '` ' + affinity + (' NOT NULL' if required else '')
                for name, affinity, required in fields) +
                ', PRIMARY KEY(`entryId`), FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) '
                'ON UPDATE NO ACTION ON DELETE CASCADE)',
            'primaryKey': {'autoGenerate': False, 'columnNames': ['entryId']},
            'foreignKeys': [{'table': 'entries', 'onDelete': 'CASCADE', 'onUpdate': 'NO ACTION',
                             'columns': ['entryId'], 'referencedColumns': ['id']}],
            'indices': [],
        }
        self.value['database']['entities'].append(event)
        self.value['database']['setupQueries'] = [
            'CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)',
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '" + '0' * 32 + "')",
        ]
        self.raw = self.encode(self.value)
        (self.root / '.gitignore').write_text('.local-evidence/\n')
        self.git('init', '-b', 'synthetic-gate')
        self.commit()

    def tearDown(self):
        self.temporary.cleanup()

    @staticmethod
    def encode(value):
        return json.dumps(value).encode()

    def git(self, *args):
        return subprocess.run(
            ['git', '-c', 'user.name=Synthetic Gate', '-c', 'user.email=fixture@example.invalid',
             '-c', 'commit.gpgsign=false', *args], cwd=self.root, capture_output=True, check=True)

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-m', 'Synthetic event date gate fixture')

    def gate(self, phase):
        return subprocess.run([sys.executable, str(self.script), phase], cwd=self.root,
                              capture_output=True, text=True)

    def assert_pass(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def assert_rejected(self, result, reason=None):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn('RECORD_EVENT_DATE_SCHEMA=', result.stdout)
        if reason is not None:
            self.assertIn(reason, result.stderr)

    def assert_historical_preserved(self):
        for relative, raw in self.historical.items():
            self.assertEqual(raw, (self.root / relative).read_bytes(), str(relative))

    def capture_bootstrap(self):
        self.assert_pass(self.gate('--capture'))
        state = json.loads(self.receipt.read_text())
        self.assertEqual('BOOTSTRAP', state['mode'])
        self.assertIsNone(state['expected_sha256'])
        self.assertNotIn('generated_sha256', state)
        self.assertEqual({str(version): hashlib.sha256((self.directory / f'{version}.json').read_bytes()).hexdigest()
                          for version in range(2, 11)}, state['historical'])

    def capture_checked_in(self):
        self.export.write_bytes(self.raw)
        self.asset.write_bytes(self.raw)
        self.commit()
        self.assert_pass(self.gate('--capture'))
        self.assertEqual('CHECKED_IN', json.loads(self.receipt.read_text())['mode'])

    def test_bootstrap_writes_only_generated_bytes_and_requires_commit_then_another_exact_head_run(self):
        self.capture_bootstrap()
        first_head = json.loads(self.receipt.read_text())['head']
        self.export.write_bytes(self.raw)
        result = self.gate('--verify')
        self.assert_pass(result)
        self.assertEqual(self.raw, self.export.read_bytes())
        self.assertEqual(self.raw, self.asset.read_bytes())
        state = json.loads(self.receipt.read_text())
        self.assertEqual('BOOTSTRAP', state['mode'])
        self.assertEqual(hashlib.sha256(self.raw).hexdigest(), state['generated_sha256'])
        self.assertEqual(len(self.raw), state['bytes'])
        self.assertIn('commit bytes then require another exact-head CI', state['scope'])
        self.assertIn('require another exact-head CI', result.stdout)
        self.assert_rejected(self.gate('--verify'), 'Unexpected v11 fixture during bootstrap')
        self.commit()
        self.assert_pass(self.gate('--capture'))
        committed = json.loads(self.receipt.read_text())
        self.assertNotEqual(first_head, committed['head'])
        self.assertEqual('CHECKED_IN', committed['mode'])
        self.assertEqual(hashlib.sha256(self.raw).hexdigest(), committed['expected_sha256'])
        self.assert_pass(self.gate('--verify'))
        self.assertEqual('exact checkout bytes verified', json.loads(self.receipt.read_text())['scope'])
        self.assert_historical_preserved()

    def test_no_generated_export_cannot_report_success_or_create_a_fixture(self):
        self.capture_bootstrap()
        before = self.receipt.read_bytes()
        self.assert_rejected(self.gate('--verify'))
        self.assertEqual(before, self.receipt.read_bytes())
        self.assertFalse(self.export.exists())
        self.assertFalse(self.asset.exists())
        self.assert_historical_preserved()

    def test_verify_without_capture_preserves_unowned_generated_export(self):
        self.export.write_bytes(self.raw)
        self.assert_rejected(self.gate('--verify'))
        self.assertEqual(self.raw, self.export.read_bytes())
        self.assertFalse(self.receipt.exists())
        self.assertFalse(self.asset.exists())

    def test_checkout_change_after_capture_cannot_reuse_the_receipt(self):
        self.capture_bootstrap()
        before = self.receipt.read_bytes()
        (self.root / 'README').write_text('Synthetic second checkout\n')
        self.commit()
        self.export.write_bytes(self.raw)
        self.assert_rejected(self.gate('--verify'), 'Checkout or historical bytes changed')
        self.assertEqual(before, self.receipt.read_bytes())
        self.assertEqual(self.raw, self.export.read_bytes())
        self.assertFalse(self.asset.exists())

    def test_unknown_or_mismatched_capture_receipt_is_not_reusable(self):
        self.capture_bootstrap()
        captured = json.loads(self.receipt.read_text())
        self.export.write_bytes(self.raw)
        for drift in ('unknown_mode', 'unknown_head', 'historical_hash'):
            with self.subTest(drift=drift):
                state = copy.deepcopy(captured)
                if drift == 'unknown_mode':
                    state['mode'] = 'UNKNOWN'
                elif drift == 'unknown_head':
                    state['head'] = '0' * 40
                else:
                    state['historical']['2'] = '0' * 64
                self.receipt.write_text(json.dumps(state))
                before = self.receipt.read_bytes()
                self.assert_rejected(self.gate('--verify'), 'Checkout or historical bytes changed')
                self.assertEqual(before, self.receipt.read_bytes())
                self.assertEqual(self.raw, self.export.read_bytes())
                self.assertFalse(self.asset.exists())

    def test_partial_untracked_export_or_fixture_is_preserved_without_capture(self):
        for path in (self.export, self.asset):
            with self.subTest(path=path.name):
                path.write_bytes(self.raw)
                self.assert_rejected(self.gate('--capture'), 'Partial v11 schema/fixture state')
                self.assertEqual(self.raw, path.read_bytes())
                self.assertFalse(self.receipt.exists())
                path.unlink()  # Only this test's own temporary fixture.

    def test_missing_tracked_pair_is_preserved_and_cannot_be_bootstrapped(self):
        self.capture_checked_in()
        before = self.receipt.read_bytes()
        self.export.unlink()
        self.asset.unlink()
        self.assert_rejected(self.gate('--capture'), 'Tracked v11 schema/fixture missing')
        self.assertEqual(before, self.receipt.read_bytes())
        self.assertFalse(self.export.exists())
        self.assertFalse(self.asset.exists())

    def test_partial_tracked_pair_is_preserved_without_overwriting_the_receipt(self):
        self.capture_checked_in()
        before = self.receipt.read_bytes()
        self.export.unlink()
        self.assert_rejected(self.gate('--capture'), 'Partial v11 schema/fixture state')
        self.assertEqual(before, self.receipt.read_bytes())
        self.assertEqual(self.raw, self.asset.read_bytes())
        self.assertFalse(self.export.exists())

    def test_capture_requires_v11_bytes_from_the_exact_committed_checkout(self):
        self.capture_checked_in()
        before = self.receipt.read_bytes()
        changed = self.raw + b'\n'
        self.export.write_bytes(changed)
        self.asset.write_bytes(changed)
        self.assert_rejected(self.gate('--capture'), 'differs from exact checkout')
        self.assertEqual(before, self.receipt.read_bytes())
        self.assertEqual(changed, self.export.read_bytes())
        self.assertEqual(changed, self.asset.read_bytes())

    def test_checked_in_generation_drift_is_preserved_even_if_semantically_equivalent(self):
        self.capture_checked_in()
        before = self.receipt.read_bytes()
        changed = self.raw + b'\n'
        self.export.write_bytes(changed)
        self.assert_rejected(self.gate('--verify'), 'preserve and reject drift')
        self.assertEqual(before, self.receipt.read_bytes())
        self.assertEqual(changed, self.export.read_bytes())
        self.assertEqual(self.raw, self.asset.read_bytes())

    def test_each_historical_schema_and_fixture_remains_exact_checkout_bytes(self):
        for relative, raw in self.historical.items():
            with self.subTest(path=str(relative)):
                path = self.root / relative
                changed = raw + b'\n'
                path.write_bytes(changed)
                self.assert_rejected(self.gate('--capture'), 'Historical schema/fixture differs')
                self.assertEqual(changed, path.read_bytes())
                self.assertFalse(self.receipt.exists())
                path.write_bytes(raw)
        self.assert_historical_preserved()

    def test_historical_change_after_capture_rejects_generated_export_and_preserves_every_byte(self):
        self.capture_bootstrap()
        before = self.receipt.read_bytes()
        self.export.write_bytes(self.raw)
        path = self.directory / '10.json'
        changed = path.read_bytes() + b'\n'
        path.write_bytes(changed)
        self.assert_rejected(self.gate('--verify'), 'Historical schema/fixture differs')
        self.assertEqual(changed, path.read_bytes())
        self.assertEqual(before, self.receipt.read_bytes())
        self.assertEqual(self.raw, self.export.read_bytes())
        self.assertFalse(self.asset.exists())

    def test_event_field_nullability_defaults_order_and_primary_key_drift_are_rejected(self):
        self.capture_bootstrap()
        for drift in ('nullable_date', 'nullable_zone', 'nullable_version', 'default',
                      'missing', 'order', 'wrong_affinity', 'primary', 'generated_primary'):
            with self.subTest(drift=drift):
                value = copy.deepcopy(self.value)
                event = value['database']['entities'][-1]
                if drift == 'nullable_date':
                    event['fields'][4]['notNull'] = True
                elif drift == 'nullable_zone':
                    event['fields'][5]['notNull'] = True
                elif drift == 'nullable_version':
                    event['fields'][1].pop('notNull')
                elif drift == 'default':
                    event['fields'][4]['defaultValue'] = "''"
                elif drift == 'missing':
                    event['fields'].pop()
                elif drift == 'order':
                    event['fields'][4:6] = reversed(event['fields'][4:6])
                elif drift == 'wrong_affinity':
                    event['fields'][4]['affinity'] = 'INTEGER'
                elif drift == 'primary':
                    event['primaryKey']['columnNames'] = ['entryId', 'version']
                else:
                    event['primaryKey']['autoGenerate'] = True
                self.assert_invalid_generated(value, 'fields/primary key differ')

    def test_foreign_key_cascade_source_and_update_contract_cannot_drift(self):
        self.capture_bootstrap()
        for key, replacement in (('onDelete', 'NO ACTION'), ('onUpdate', 'CASCADE'),
                                 ('table', 'entry_revisions'), ('columns', ['version']),
                                 ('referencedColumns', ['currentRevision'])):
            with self.subTest(key=key):
                value = copy.deepcopy(self.value)
                value['database']['entities'][-1]['foreignKeys'][0][key] = replacement
                self.assert_invalid_generated(value, 'source foreign key differs')

    def test_create_sql_and_added_index_drift_cannot_be_hidden_by_correct_metadata(self):
        self.capture_bootstrap()
        for drift in ('required_column', 'date_nullable', 'zone_nullable', 'foreign',
                      'primary', 'extra_column', 'index'):
            with self.subTest(drift=drift):
                value = copy.deepcopy(self.value)
                event = value['database']['entities'][-1]
                if drift == 'required_column':
                    event['createSql'] = event['createSql'].replace('`version` INTEGER NOT NULL', '`version` INTEGER')
                elif drift == 'date_nullable':
                    event['createSql'] = event['createSql'].replace('`localDate` TEXT', '`localDate` TEXT NOT NULL')
                elif drift == 'zone_nullable':
                    event['createSql'] = event['createSql'].replace('`timeZone` TEXT', '`timeZone` TEXT NOT NULL')
                elif drift == 'foreign':
                    event['createSql'] = event['createSql'].replace('ON DELETE CASCADE', 'ON DELETE NO ACTION')
                elif drift == 'primary':
                    event['createSql'] = event['createSql'].replace('PRIMARY KEY(`entryId`)', 'PRIMARY KEY(`version`)')
                elif drift == 'extra_column':
                    event['createSql'] = event['createSql'].replace(', PRIMARY KEY', ', guessedTime INTEGER, PRIMARY KEY')
                else:
                    event['indices'] = [{'name': 'unexpected_event_index', 'columnNames': ['localDate']}]
                self.assert_invalid_generated(value, 'event date index' if drift == 'index' else 'CREATE SQL differs')

    def test_inventory_prior_entity_and_room_identity_drift_are_rejected(self):
        self.capture_bootstrap()
        for drift in ('version', 'missing', 'extra', 'prior_entity', 'identity'):
            with self.subTest(drift=drift):
                value = copy.deepcopy(self.value)
                schema = value['database']
                if drift == 'version':
                    schema['version'] = 12
                elif drift == 'missing':
                    schema['entities'].pop()
                elif drift == 'extra':
                    schema['entities'].append(copy.deepcopy(schema['entities'][-1]))
                elif drift == 'prior_entity':
                    schema['entities'][0]['createSql'] += ' '
                else:
                    schema['identityHash'] = 'not-a-Room-identity'
                self.assert_invalid_generated(value)

    def test_added_view_cannot_extend_the_additive_event_table_contract(self):
        self.capture_bootstrap()
        value = copy.deepcopy(self.value)
        value['database']['views'] = [{
            'viewName': 'guessed_event_dates',
            'createSql': 'CREATE VIEW guessed_event_dates AS SELECT * FROM entry_event_dates',
        }]
        self.assert_invalid_generated(value, 'Unexpected v11 views')

    def test_room_setup_queries_must_match_the_generated_identity_exactly(self):
        self.capture_bootstrap()
        for drift in ('mismatched_identity', 'missing_query', 'extra_query', 'master_table'):
            with self.subTest(drift=drift):
                value = copy.deepcopy(self.value)
                queries = value['database']['setupQueries']
                if drift == 'mismatched_identity':
                    queries[1] = queries[1].replace('0' * 32, '1' * 32)
                elif drift == 'missing_query':
                    queries.pop()
                elif drift == 'extra_query':
                    queries.append('DELETE FROM entry_event_dates')
                else:
                    queries[0] = queries[0].replace('identity_hash TEXT', 'identity_hash INTEGER')
                self.assert_invalid_generated(value, 'Room setup differs from its identity')

    def assert_invalid_generated(self, value, reason=None):
        raw = self.encode(value)
        before = self.receipt.read_bytes()
        self.export.write_bytes(raw)
        self.assert_rejected(self.gate('--verify'), reason)
        self.assertEqual(before, self.receipt.read_bytes())
        self.assertEqual(raw, self.export.read_bytes())
        self.assertFalse(self.asset.exists())
        self.assert_historical_preserved()


if __name__ == '__main__':
    unittest.main()
