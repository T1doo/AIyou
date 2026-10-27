"""Offline extracted-artifact authentication; no SDK, network or device execution."""
from contextlib import contextmanager, ExitStack
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'scripts'))
import d08_probe_artifact as probe
from d08_recovery_contract import SOURCE, APK_HASH, BRANCH, MESSAGE, EXPECTED_REQUEST, admit


@contextmanager
def synthetic_artifact(root, change_report=None, change_receipt=None):
    # Deliberate test-only pins; production has no CLI/environment override.
    apk=b'synthetic APK member; never installed or executed'
    apk_sha=hashlib.sha256(apk).hexdigest()
    report={'source_sha':probe.PROBE_SOURCE,'run_id':str(probe.PROBE_RUN),'run_attempt':1,'status':'PASS',
            'scope':'SDK-only compile/sign/inspect; ALL DEVICE EXECUTION NOT RUN','new_keys':0,'network_requests':0,
            'apk':{'path':probe.APK_MEMBER,'bytes':len(apk),'sha256':apk_sha,'certificate_sha256':probe.PROBE_CERTIFICATE},
            'commands':[{'stage':s,'exit':0} for s in probe.STAGES]}
    receipt={'source_sha':probe.PROBE_SOURCE,'run_id':probe.PROBE_RUN,'run_attempt':1,
             'job':'d01','stage':'recovery-probe','producer_outcome':'success'}
    if change_report:change_report(report)
    if change_receipt:change_receipt(receipt)
    contents={probe.APK_MEMBER:apk,probe.REPORT_MEMBER:json.dumps(report).encode(),probe.RECEIPT_MEMBER:json.dumps(receipt).encode()}
    pins={n:(len(b),hashlib.sha256(b).hexdigest()) for n,b in contents.items()}
    for n,b in contents.items():
        p=root/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
    with mock.patch.object(probe,'MEMBERS',pins),mock.patch.object(probe,'PROBE_SHA',apk_sha):
        yield contents


def entry_module():
    spec=importlib.util.spec_from_file_location('d08_artifact_entry',ROOT/'scripts/run-d08-recovery.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module


class ProbeArtifactTests(unittest.TestCase):
    def test_complete_three_member_proof_is_read_only_and_missing_or_drifted_member_rejected(self):
        for kind in ['valid','missing-apk','missing-report','missing-receipt','apk-drift','report-drift','receipt-drift','extra']:
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as d:
                root=Path(d)
                with synthetic_artifact(root) as contents:
                    if kind.startswith('missing-'):
                        n={'apk':probe.APK_MEMBER,'report':probe.REPORT_MEMBER,'receipt':probe.RECEIPT_MEMBER}[kind[8:]];(root/n).unlink()
                    elif kind.endswith('-drift'):
                        n={'apk':probe.APK_MEMBER,'report':probe.REPORT_MEMBER,'receipt':probe.RECEIPT_MEMBER}[kind[:-6]]
                        b=bytearray((root/n).read_bytes());b[-1]^=1;(root/n).write_bytes(b)
                    elif kind=='extra':(root/'unreviewed.apk').write_bytes(b'extra')
                    before={str(p.relative_to(root)):p.read_bytes() for p in root.rglob('*') if p.is_file()}
                    if kind=='valid':self.assertEqual(root/probe.APK_MEMBER,probe.verify_probe(root))
                    else:
                        with self.assertRaises(ValueError):probe.verify_probe(root)
                    self.assertEqual(before,{str(p.relative_to(root)):p.read_bytes() for p in root.rglob('*') if p.is_file()})

    def test_consistent_hashes_do_not_substitute_for_source_run_success_and_signer_semantics(self):
        changes=[lambda r:r.update(source_sha=SOURCE),lambda r:r.update(run_id='40447188648'),
                 lambda r:r.update(run_attempt=2),lambda r:r.update(status='FAIL'),
                 lambda r:r['apk'].update(certificate_sha256='f'*64),lambda r:r['apk'].update(path='old/probe.apk'),
                 lambda r:r['commands'].pop(),lambda r:r['commands'][0].update(exit=1)]
        for change in changes:
            with self.subTest(change=change),tempfile.TemporaryDirectory() as d:
                with synthetic_artifact(Path(d),change_report=change):
                    with self.assertRaises(ValueError):probe.verify_probe(Path(d))
        with tempfile.TemporaryDirectory() as d:
            with synthetic_artifact(Path(d),change_receipt=lambda r:r.update(producer_outcome='failure')):
                with self.assertRaises(ValueError):probe.verify_probe(Path(d))

    def test_links_and_directories_cannot_replace_fixed_members(self):
        for kind in ['symlink-file','symlink-parent','hardlink','directory']:
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as d:
                base=Path(d);root=base/'input';root.mkdir()
                with synthetic_artifact(root) as contents:
                    apk=root/probe.APK_MEMBER
                    if kind=='symlink-parent':
                        original=apk.parent;target=base/'redirect';original.rename(target);original.symlink_to(target,target_is_directory=True)
                    else:
                        apk.unlink();outside=base/'outside.apk';outside.write_bytes(contents[probe.APK_MEMBER])
                        if kind=='symlink-file':apk.symlink_to(outside)
                        elif kind=='hardlink':os.link(outside,apk)
                        else:apk.mkdir()
                    with self.assertRaises(ValueError):probe.verify_probe(root)

    def test_replacement_during_read_is_rejected_even_when_bytes_still_match(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            with synthetic_artifact(root):
                real=hashlib.sha256;apk=root/probe.APK_MEMBER;changed=[]
                class UpdatingHash:
                    def __init__(self):self.value=real()
                    def update(self,b):
                        self.value.update(b)
                        if not changed:changed.append(True);apk.write_bytes(apk.read_bytes())
                    def hexdigest(self):return self.value.hexdigest()
                with mock.patch.object(probe.hashlib,'sha256',UpdatingHash):
                    with self.assertRaisesRegex(ValueError,'identity changed'):probe.verify_probe(root)

    def test_new_request_keeps_production_fixed_and_old_tuple_or_old_request_is_not_admitted(self):
        self.assertEqual('958ab2a08536161d8a0776ef155b6368d7df09c6',SOURCE)
        self.assertEqual('dadce271185cc1ff1e8a577f1986d4b4290fbcb60daf7dd187c7088b5d1b693a',APK_HASH)
        current=json.loads((ROOT/'checks/device-recovery/request.json').read_text())
        self.assertIs(type(current.get('executionApproved')),bool)
        self.assertEqual({**EXPECTED_REQUEST,'executionApproved':current['executionApproved']},current)
        self.assertTrue(all(type(current.get(k)) is type(v) for k,v in EXPECTED_REQUEST.items()))
        # Dev remains unarmed. Only the separately admitted, exact device event
        # may run this same regression after an explicit approval delta.
        if current['executionApproved']:
            actual_env=dict(os.environ)
            admit(current,actual_env,json.loads(Path(actual_env['GITHUB_EVENT_PATH']).read_text()))
        env={'GITHUB_ACTIONS':'true','GITHUB_REPOSITORY':'T1doo/AIyou','RUNNER_OS':'Linux','GITHUB_EVENT_NAME':'push',
             'GITHUB_REF':BRANCH,'GITHUB_RUN_ATTEMPT':'1','GITHUB_SHA':'a'*40,'GITHUB_RUN_ID':'123'}
        event={'repository':{'full_name':'T1doo/AIyou','private':False},'ref':BRANCH,'head_commit':{'message':MESSAGE}}
        admit(EXPECTED_REQUEST,env,event)
        old='refs/heads/device/d02-kvm-acceptance-20261012'
        with self.assertRaises(ValueError):admit(EXPECTED_REQUEST,{**env,'GITHUB_REF':old},{**event,'ref':old})
        with self.assertRaises(ValueError):admit({k:v for k,v in EXPECTED_REQUEST.items() if not k.startswith('probe')},env,event)
        with self.assertRaises(ValueError):admit({**EXPECTED_REQUEST,'probeSourceSha':SOURCE},env,event)

    def test_unapproved_prepare_and_execute_stop_before_artifact_sdk_or_device_access(self):
        entry=entry_module()
        for method in [entry.prepare_probe,entry.execute]:
            with self.subTest(method=method.__name__),mock.patch.object(entry,'REQUEST') as request, \
                    mock.patch.object(entry,'verify_probe') as verify,mock.patch.object(entry,'fixed_file') as fixed, \
                    mock.patch.object(entry,'load_driver') as driver,mock.patch.object(entry.subprocess,'run') as command:
                request.read_text.return_value=json.dumps({**EXPECTED_REQUEST,'executionApproved':False})
                with self.assertRaisesRegex(RuntimeError,'unapproved'):method()
                verify.assert_not_called();fixed.assert_not_called();driver.assert_not_called();command.assert_not_called()
        with mock.patch('sys.argv',['run-d08-recovery.py','prepare-build']),mock.patch.object(entry,'execute') as execute:
            with self.assertRaises(SystemExit):entry.main()
            execute.assert_not_called()

    def test_bad_artifact_stops_execute_before_driver_and_prepare_retains_sdk_terms_checks(self):
        entry=entry_module()
        with mock.patch.object(entry,'recheck',return_value={}),mock.patch.object(entry,'fixed_file') as fixed, \
                mock.patch.object(entry,'verify_probe',side_effect=ValueError('missing report')),mock.patch.object(entry,'load_driver') as driver:
            with self.assertRaisesRegex(ValueError,'missing report'):entry.execute()
            fixed.assert_called_once_with(entry.APK,27161705,APK_HASH);driver.assert_not_called()
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(entry,'recheck',return_value={}))
            verify=stack.enter_context(mock.patch.object(entry,'verify_probe'))
            fixed=stack.enter_context(mock.patch.object(entry,'fixed_file'))
            command=stack.enter_context(mock.patch.object(entry.subprocess,'run'))
            terms=stack.enter_context(mock.patch.object(entry,'verify_terms'))
            stack.enter_context(mock.patch.dict(os.environ,{'ANDROID_HOME':'/synthetic-existing-sdk'}))
            entry.prepare_probe();verify.assert_called_once_with(entry.PROBE_DIR)
            fixed.assert_called_once_with(entry.APK,27161705,APK_HASH)
            self.assertEqual([str(x) for x in command.call_args.args[0]][1:], [str(ROOT/'scripts/check-sdk.py')])
            terms.assert_called_once_with(Path('/synthetic-existing-sdk'))


if __name__=='__main__':unittest.main()
