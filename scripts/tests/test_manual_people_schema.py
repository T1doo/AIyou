"""Admission failures preserve bytes; these tests create no synthetic Room v11/v12 exports."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class ManualPeopleSchemaAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='aiyou-people-gate-')
        self.root = Path(self.temp.name)
        actual = Path(__file__).resolve().parents[2]
        (self.root / 'scripts').mkdir()
        self.script = self.root / 'scripts/verify-manual-people-schema.py'
        shutil.copyfile(actual / self.script.relative_to(self.root), self.script)
        self.schemas = self.root / 'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase'
        self.assets = self.root / 'core/data/src/androidTest/assets'
        self.schemas.mkdir(parents=True)
        self.assets.mkdir(parents=True)
        self.originals = {}
        for version in range(2, 11):
            for relative in (Path(f'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/{version}.json'),
                             Path(f'core/data/src/androidTest/assets/records-schema-v{version}.json')):
                raw = (actual / relative).read_bytes()
                (self.root / relative).write_bytes(raw)
                self.originals[relative] = raw
        self.git('init', '-b', 'synthetic-admission')
        self.git('add', '.')
        self.git('commit', '-m', 'Actual historical bytes in isolated admission fixture')

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.run(['git', '-c', 'user.name=Synthetic Admission', '-c',
            'user.email=fixture@example.invalid', '-c', 'commit.gpgsign=false', *args],
            cwd=self.root, capture_output=True, check=True)

    def capture(self):
        return subprocess.run([sys.executable, str(self.script), '--capture'], cwd=self.root,
                              capture_output=True, text=True)

    def assert_no_generated_files(self):
        self.assertFalse((self.root / '.local-evidence/f05-manual-people/schema-generation.json').exists())
        for version in (11, 12):
            self.assertFalse((self.schemas / f'{version}.json').exists())
            self.assertFalse((self.assets / f'records-schema-v{version}.json').exists())

    def test_missing_actual_eleven_predecessor_rejects_before_any_schema_or_receipt_is_written(self):
        result = self.capture()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Actual checked-in Room v11 schema/fixture is required', result.stderr)
        self.assertNotIn('MANUAL_PEOPLE_SCHEMA=', result.stdout)
        self.assert_no_generated_files()
        for path, raw in self.originals.items():
            self.assertEqual(raw, (self.root / path).read_bytes())

    def test_same_pair_with_dirty_historical_bytes_cannot_borrow_committed_ownership(self):
        export = self.schemas / '10.json'
        asset = self.assets / 'records-schema-v10.json'
        changed = export.read_bytes() + b'\n'
        export.write_bytes(changed)
        asset.write_bytes(changed)
        result = self.capture()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Historical schema/fixture differs from exact checkout: 10', result.stderr)
        self.assertEqual(changed, export.read_bytes())
        self.assertEqual(changed, asset.read_bytes())
        self.assert_no_generated_files()
