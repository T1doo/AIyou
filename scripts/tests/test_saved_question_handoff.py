"""Fixed one-method admission/collector contracts. These do not execute Compose or consume CI."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('handoff_probe',ROOT/'scripts/diagnose-saved-question-handoff.py')
probe=importlib.util.module_from_spec(spec);spec.loader.exec_module(probe)
PHASES=['opened','before-fetch','after-fetch','before-scroll','after-scroll','before-display','after-display','before-click','after-click','selected','switched','use-clicked']
class HandoffTests(unittest.TestCase):
    def env(self):
        return {'GITHUB_ACTIONS':'true','GITHUB_EVENT_NAME':'push','GITHUB_REPOSITORY':'T1doo/AIyou','GITHUB_REF':probe.REF,
            'GITHUB_SHA':'1'*40,'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'1','TARGET_OUTCOME':'success','SCHEMA_OUTCOME':'success'}
    def event(self):return {'repository':{'private':False},'head_commit':{'message':probe.MESSAGE}}
    def fixture(self,root):
        p=root/probe.OUTPUT;p.mkdir(parents=True)
        (p/'admission.json').write_text(json.dumps({'source_sha':'1'*40,'run_id':123,'attempt':1,'started_ns':0}))
        (p/'gradle.log').write_text('Synthetic test fixture; not Android execution evidence\n')
        reports=root/probe.REPORTS;reports.mkdir(parents=True)
        suite=ET.Element('testsuite',tests='1',failures='0',errors='0',skipped='0')
        cls,method=next(iter(probe.CASES));ET.SubElement(suite,'testcase',classname=cls,name=method)
        ET.SubElement(suite,'system-err').text='\n'.join('QUESTION_HISTORY_PHASE '+p for p in PHASES)
        xml=reports/('TEST-'+cls+'.xml');xml.write_bytes(ET.tostring(suite))
        captures=root/probe.CAPTURES;captures.mkdir(parents=True)
        for stage in ['before-scroll','after-scroll']:
            (captures/(stage+'.json')).write_text(json.dumps({'source_sha':'1'*40,'stage':stage,'status':'OBSERVED','target':{'top':16},'vertical':{'value':0,'maxValue':100}}))
        return xml
    def collect(self,root,env=None):
        with patch.object(probe,'git',return_value='1'*40),patch.object(probe,'source_contract'):
            return probe.collect(root,env or self.env())
    def test_exact_public_push_attempt1_only(self):
        probe.request(self.env(),self.event())
        for key,value in [('GITHUB_EVENT_NAME','workflow_dispatch'),('GITHUB_REPOSITORY','other/repo'),('GITHUB_REF','refs/heads/codex/mobile-tabs-20261027'),('GITHUB_RUN_ATTEMPT','2'),('GITHUB_SHA',''),('GITHUB_RUN_ID','0')]:
            with self.subTest(key=key),self.assertRaises(ValueError):probe.request({**self.env(),key:value},self.event())
        for event in [{'repository':{'private':True},'head_commit':{'message':probe.MESSAGE}},{'repository':{'private':False},'head_commit':{'message':probe.MESSAGE+' extra'}}]:
            with self.assertRaises(ValueError):probe.request(self.env(),event)
    def test_exact_one_filter_budgets_sources_and_formal_workflow_unchanged(self):
        s=(ROOT/probe.WORKFLOW).read_text()
        self.assertEqual(1,s.count('--tests '));self.assertEqual(1,len(probe.CASES))
        cls,method=next(iter(probe.CASES));self.assertIn("--tests '"+cls+'.'+method+"'",s)
        for item in ['runs-on: ubuntu-24.04','timeout-minutes: 12','540s scripts/gradle.sh','--kill-after=10s','retention-days: 1','persist-credentials: false','contents: read',probe.REF,probe.MESSAGE,'github.run_attempt == 1','github.event.repository.private == false']:
            self.assertIn(item,s)
        for item in ['workflow_dispatch','pull_request:',':app:assembleDebug','ScreenshotTest','sdkmanager','sudo','continue-on-error','secrets.']:
            self.assertNotIn(item,s)
        self.assertEqual('296d16beb5b3275e0fe604d1d4a93e0e5cffe721c7c9dcd0ebb863093ca3b094',hashlib.sha256((ROOT/'.github/workflows/android.yml').read_bytes()).hexdigest())
        self.assertFalse(probe.REF.removeprefix('refs/heads/').startswith('codex/'))
        self.assertEqual(8*1024*1024,probe.MAX_ARTIFACT)
        for path,digest in probe.SOURCES.items():self.assertEqual(digest,hashlib.sha256((ROOT/path).read_bytes()).hexdigest())
    def test_original_failed_error_skip_duplicate_foreign_and_extra_cases_remain_fail(self):
        for kind in ['pass','failure','error','skip','duplicate','foreign','extra-xml','missing-xml','target','schema']:
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as temp:
                root=Path(temp);xml=self.fixture(root);env=self.env();suite=ET.parse(xml).getroot();case=suite.find('testcase')
                if kind in ['failure','error','skip']:
                    tag={'failure':'failure','error':'error','skip':'skipped'}[kind]
                    ET.SubElement(case,tag).text='synthetic original failure';suite.set({'failure':'failures','error':'errors','skip':'skipped'}[kind],'1')
                    suite.find('system-err').text='QUESTION_HISTORY_PHASE opened\nQUESTION_HISTORY_PHASE before-fetch'
                if kind=='duplicate':ET.SubElement(suite,'testcase',**case.attrib);suite.set('tests','2')
                if kind=='foreign':case.set('name','unapproved')
                xml.write_bytes(ET.tostring(suite))
                if kind=='extra-xml':(xml.parent/'TEST-extra.xml').write_text('<testsuite/>')
                if kind=='missing-xml':xml.unlink()
                if kind in ['target','schema']:env[{'target':'TARGET_OUTCOME','schema':'SCHEMA_OUTCOME'}[kind]]='failure'
                result=self.collect(root,env);self.assertEqual('PASS' if kind=='pass' else 'FAIL',result['status'])
                if kind in ['failure','error','skip']:self.assertEqual(['opened','before-fetch'],result['phases'])
    def test_missing_stale_unsafe_or_oversized_geometry_and_log_never_pass(self):
        for kind in ['missing','foreign-source','unavailable','symlink','oversize','missing-log','phases']:
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as temp:
                root=Path(temp);xml=self.fixture(root);path=root/probe.CAPTURES/'before-scroll.json'
                if kind=='missing':path.unlink()
                if kind=='foreign-source':v=json.loads(path.read_text());v['source_sha']='2'*40;path.write_text(json.dumps(v))
                if kind=='unavailable':v=json.loads(path.read_text());v['status']='UNAVAILABLE';path.write_text(json.dumps(v))
                if kind=='symlink':path.unlink();path.symlink_to(root/probe.OUTPUT/'admission.json')
                if kind=='oversize':(root/probe.OUTPUT/'gradle.log').write_bytes(b'x'*probe.MAX_ARTIFACT)
                if kind=='missing-log':(root/probe.OUTPUT/'gradle.log').unlink()
                if kind=='phases':s=ET.parse(xml).getroot();s.find('system-err').text='QUESTION_HISTORY_PHASE opened';xml.write_bytes(ET.tostring(s))
                self.assertEqual('FAIL',self.collect(root)['status'])
if __name__=='__main__':unittest.main()
