"""Synthetic ownership/DDL drift controls, never a generated product Room fixture."""
import copy
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class LocalCandidateSchemaOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='aiyou-local-candidate-gate-')
        self.root = Path(self.temporary.name)
        actual = Path(__file__).resolve().parents[2]
        (self.root/'scripts').mkdir()
        self.script = self.root/'scripts/verify-local-candidate-schema.py'
        shutil.copyfile(actual/'scripts/verify-local-candidate-schema.py', self.script)
        self.directory = self.root/'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase'
        self.assets = self.root/'core/data/src/androidTest/assets'
        self.directory.mkdir(parents=True); self.assets.mkdir(parents=True)
        for version in range(2, 10):
            raw = (actual/f'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/{version}.json').read_bytes()
            (self.directory/f'{version}.json').write_bytes(raw)
            (self.assets/f'records-schema-v{version}.json').write_bytes(raw)
        self.export = self.directory/'10.json'; self.asset = self.assets/'records-schema-v10.json'
        self.value = json.loads((self.directory/'9.json').read_bytes())
        self.value['database']['version'] = 10
        self.value['database']['identityHash'] = '0' * 32
        fields = [('id','TEXT'),('entryId','TEXT'),('sourceRevision','INTEGER'),('sourceHash','TEXT'),
                  ('generation','INTEGER'),('ruleVersion','INTEGER'),('version','INTEGER'),('state','TEXT'),
                  ('lastMutationId','TEXT'),('createdAtMillis','INTEGER'),('payload','BLOB')]
        self.candidate = {'tableName':'local_candidate_batches',
            'fields':[{'fieldPath':name,'columnName':name,'affinity':affinity,'notNull':True} for name,affinity in fields],
            'createSql':'CREATE TABLE IF NOT EXISTS local_candidate_batches (' + ', '.join(
                name+' '+affinity+' NOT NULL' for name,affinity in fields) +
                ', PRIMARY KEY(id), FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE)',
            'primaryKey':{'autoGenerate':False,'columnNames':['id']},
            'foreignKeys':[{'table':'entries','onDelete':'CASCADE','onUpdate':'NO ACTION','columns':['entryId'],'referencedColumns':['id']}],
            'indices':[{'name':'index_local_candidate_batches_entryId_sourceRevision','unique':False,
                'columnNames':['entryId','sourceRevision'],'orders':[],
                'createSql':'CREATE INDEX IF NOT EXISTS index_local_candidate_batches_entryId_sourceRevision ON local_candidate_batches (entryId,sourceRevision)'}]}
        self.value['database']['entities'].append(self.candidate)
        self.raw = json.dumps(self.value).encode()
        self.git('init','-b','synthetic-gate'); self.commit()
    def tearDown(self):
        self.temporary.cleanup()
    def git(self, *args):
        return subprocess.run(['git','-c','user.name=Synthetic Gate','-c','user.email=fixture@example.invalid',
            '-c','commit.gpgsign=false',*args], cwd=self.root, capture_output=True, check=True)
    def commit(self):
        self.git('add','.'); self.git('commit','-m','Synthetic candidate ownership fixture')
    def gate(self, phase):
        return subprocess.run(['python3',str(self.script),phase], cwd=self.root, capture_output=True, text=True)
    def test_bootstrap_requires_committed_bytes_and_another_exact_head_run(self):
        self.assertEqual(0,self.gate('--capture').returncode)
        self.export.write_bytes(self.raw); result=self.gate('--verify')
        self.assertEqual(0,result.returncode,result.stderr); self.assertEqual(self.raw,self.asset.read_bytes())
        self.assertIn('require another exact-head CI',result.stdout)
        self.commit(); self.assertEqual(0,self.gate('--capture').returncode)
        self.assertEqual(0,self.gate('--verify').returncode)
    def test_fields_nullability_default_and_primary_key_drift_are_rejected(self):
        self.assertEqual(0,self.gate('--capture').returncode)
        for drift in ('nullable','default','missing','primary'):
            value=copy.deepcopy(self.value); candidate=value['database']['entities'][-1]
            if drift=='nullable': candidate['fields'][-1]['notNull']=False
            elif drift=='default': candidate['fields'][0]['defaultValue']="''"
            elif drift=='missing': candidate['fields'].pop()
            else: candidate['primaryKey']['columnNames']=['entryId']
            raw=json.dumps(value).encode(); self.export.write_bytes(raw)
            self.assertNotEqual(0,self.gate('--verify').returncode)
            self.assertFalse(self.asset.exists()); self.assertEqual(raw,self.export.read_bytes())
    def test_foreign_key_index_and_create_sql_drift_are_rejected(self):
        self.assertEqual(0,self.gate('--capture').returncode)
        for drift in ('foreign','index','sql'):
            value=copy.deepcopy(self.value); candidate=value['database']['entities'][-1]
            if drift=='foreign': candidate['foreignKeys'][0]['onDelete']='NO ACTION'
            elif drift=='index': candidate['indices'][0]['columnNames'].reverse()
            else: candidate['createSql']=candidate['createSql'].replace('payload BLOB NOT NULL','payload BLOB')
            self.export.write_bytes(json.dumps(value).encode())
            self.assertNotEqual(0,self.gate('--verify').returncode); self.assertFalse(self.asset.exists())
    def test_previous_entity_or_historical_bytes_are_never_rewritten(self):
        self.assertEqual(0,self.gate('--capture').returncode)
        value=copy.deepcopy(self.value); value['database']['entities'][0]['createSql']+=' '
        self.export.write_bytes(json.dumps(value).encode())
        self.assertNotEqual(0,self.gate('--verify').returncode); self.assertFalse(self.asset.exists())
        self.export.write_bytes(self.raw); old=self.directory/'9.json'; changed=old.read_bytes()+b'\n'; old.write_bytes(changed)
        self.assertNotEqual(0,self.gate('--verify').returncode)
        self.assertEqual(changed,old.read_bytes()); self.assertFalse(self.asset.exists())
    def test_partial_missing_tracked_and_generated_drift_are_preserved(self):
        self.export.write_bytes(self.raw); self.assertNotEqual(0,self.gate('--capture').returncode)
        self.asset.write_bytes(self.raw); self.commit(); self.assertEqual(0,self.gate('--capture').returncode)
        changed=self.raw+b'\n'; self.export.write_bytes(changed)
        self.assertNotEqual(0,self.gate('--verify').returncode); self.assertEqual(self.raw,self.asset.read_bytes())
        self.export.unlink(); self.asset.unlink()
        self.assertNotEqual(0,self.gate('--capture').returncode); self.assertFalse(self.asset.exists())
    def test_checkout_change_after_capture_cannot_claim_the_old_receipt(self):
        self.assertEqual(0,self.gate('--capture').returncode)
        (self.root/'README').write_text('Synthetic next checkout\n'); self.commit(); self.export.write_bytes(self.raw)
        self.assertNotEqual(0,self.gate('--verify').returncode); self.assertFalse(self.asset.exists())
