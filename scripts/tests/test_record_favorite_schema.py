"""Synthetic ownership gate fixtures only; this never generates a product Room export."""
import copy
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class RecordFavoriteSchemaOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='aiyou-favorite-gate-')
        self.root=Path(self.temporary.name)
        actual=Path(__file__).resolve().parents[2]
        (self.root/'scripts').mkdir()
        self.script=self.root/'scripts/verify-record-favorite-schema.py'
        shutil.copyfile(actual/'scripts/verify-record-favorite-schema.py',self.script)
        self.directory=self.root/'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase'
        self.assets=self.root/'core/data/src/androidTest/assets'
        self.directory.mkdir(parents=True);self.assets.mkdir(parents=True)
        for version in range(2,9):
            raw=(actual/f'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/{version}.json').read_bytes()
            (self.directory/f'{version}.json').write_bytes(raw)
            (self.assets/f'records-schema-v{version}.json').write_bytes(raw)
        self.export=self.directory/'9.json';self.asset=self.assets/'records-schema-v9.json'
        self.value=json.loads((self.directory/'8.json').read_bytes())
        self.value['database']['version']=9
        entry=next(e for e in self.value['database']['entities'] if e['tableName']=='entries')
        for name,affinity,not_null,default in [('isFavorite','INTEGER',True,'0'),('favoriteVersion','INTEGER',True,'0'),
                ('lastFavoriteMutationId','TEXT',False,None),('lastFavoriteSourceRevision','INTEGER',True,'0')]:
            field={'fieldPath':name,'columnName':name,'affinity':affinity,'notNull':not_null}
            if default is not None:field['defaultValue']=default
            entry['fields'].append(field)
        entry['createSql']=entry['createSql'].replace(', PRIMARY KEY',
            ', `isFavorite` INTEGER NOT NULL DEFAULT 0, `favoriteVersion` INTEGER NOT NULL DEFAULT 0, '
            '`lastFavoriteMutationId` TEXT, `lastFavoriteSourceRevision` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY',1)
        self.raw=json.dumps(self.value).encode()
        self.git('init','-b','synthetic-gate');self.commit()
    def tearDown(self):self.temporary.cleanup()
    def git(self,*args):
        return subprocess.run(['git','-c','user.name=Synthetic Gate','-c','user.email=fixture@example.invalid',
            '-c','commit.gpgsign=false',*args],cwd=self.root,capture_output=True,check=True)
    def commit(self):self.git('add','.');self.git('commit','-m','Synthetic ownership gate fixture')
    def gate(self,phase):return subprocess.run(['python3',str(self.script),phase],cwd=self.root,capture_output=True,text=True)

    def test_bootstrap_then_exact_checkout_requires_second_run_and_preserves_history(self):
        prior=(self.directory/'8.json').read_bytes()
        self.assertEqual(0,self.gate('--capture').returncode)
        self.export.write_bytes(self.raw);result=self.gate('--verify')
        self.assertEqual(0,result.returncode,result.stderr);self.assertEqual(self.raw,self.asset.read_bytes())
        self.assertEqual(prior,(self.directory/'8.json').read_bytes());self.assertIn('require another exact-head CI',result.stdout)
        self.commit();self.assertEqual(0,self.gate('--capture').returncode)
        self.assertEqual(0,self.gate('--verify').returncode)

    def test_wrong_default_nullable_column_source_or_independent_entity_is_preserved_and_rejected(self):
        self.assertEqual(0,self.gate('--capture').returncode)
        for mutation in ('default','nullable','extra','original','independent'):
            value=copy.deepcopy(self.value);entry=next(e for e in value['database']['entities'] if e['tableName']=='entries')
            if mutation=='default':entry['fields'][-1]['defaultValue']='1'
            elif mutation=='nullable':entry['fields'][-2]['notNull']=True
            elif mutation=='extra':entry['fields'].append({'columnName':'hidden'})
            elif mutation=='original':entry['fields'][0]['notNull']=False
            else:next(e for e in value['database']['entities'] if e['tableName']=='capture_draft')['createSql']+=' '
            raw=json.dumps(value).encode();self.export.write_bytes(raw)
            self.assertNotEqual(0,self.gate('--verify').returncode)
            self.assertFalse(self.asset.exists());self.assertEqual(raw,self.export.read_bytes())

    def test_historical_drift_during_generation_fails_without_creating_asset(self):
        self.assertEqual(0,self.gate('--capture').returncode);self.export.write_bytes(self.raw)
        path=self.directory/'7.json';changed=path.read_bytes()+b'\n';path.write_bytes(changed)
        self.assertNotEqual(0,self.gate('--verify').returncode);self.assertEqual(changed,path.read_bytes());self.assertFalse(self.asset.exists())

    def test_partial_missing_tracked_and_generated_drift_are_never_overwritten(self):
        self.export.write_bytes(self.raw);self.assertNotEqual(0,self.gate('--capture').returncode)
        self.asset.write_bytes(self.raw);self.commit();self.assertEqual(0,self.gate('--capture').returncode)
        changed=self.raw+b'\n';self.export.write_bytes(changed)
        self.assertNotEqual(0,self.gate('--verify').returncode);self.assertEqual(self.raw,self.asset.read_bytes())
        self.export.unlink();self.asset.unlink();self.assertNotEqual(0,self.gate('--capture').returncode)
        self.assertFalse(self.asset.exists())

    def test_checkout_change_after_capture_fails_without_creating_asset(self):
        self.assertEqual(0,self.gate('--capture').returncode)
        (self.root/'README').write_text('Synthetic changed checkout\n');self.commit();self.export.write_bytes(self.raw)
        self.assertNotEqual(0,self.gate('--verify').returncode);self.assertFalse(self.asset.exists())
