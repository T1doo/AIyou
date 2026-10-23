"""Synthetic admission/XML controls; no Android or hosted execution."""
import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('notebook_probe', ROOT / 'scripts/diagnose-notebook-overflow.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class NotebookDiagnosticTests(unittest.TestCase):
    def formal_workflow(self):
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        formal = workflow.split('\n  notebook-overflow-diagnostic:\n')[0]
        lines = formal.splitlines(keepends=True)
        lines = [line for line in lines if not line.startswith('run-name:') and not line.startswith('    if: ${{ !(')]
        formal = ''.join(lines)
        comment = ('# The fixed diagnostic marker is push-only. Before its one approved push, require\n'
                   '# no open PR for that branch: PR synchronize retains the normal acceptance path.\n'
                   '# Any marker-prefixed push avoids the three formal jobs; bad requests fail admit.\n')
        return formal.replace(comment, '')

    def env(self):
        return {'GITHUB_ACTIONS': 'true', 'GITHUB_EVENT_NAME': 'push', 'GITHUB_REPOSITORY': 'T1doo/AIyou',
                'GITHUB_REF': probe.REF, 'GITHUB_SHA': '1' * 40, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1',
                'TARGET_OUTCOME': 'success', 'SCHEMA_OUTCOME': 'success'}

    def event(self):
        return {'repository': {'private': False}, 'head_commit': {'message': probe.MESSAGE}}

    def xml(self, folder, *, count=1, failure=False, skipped=False, error=False, method=probe.METHOD):
        suite = ET.Element('testsuite', tests=str(count), failures=str(int(failure)), errors=str(int(error)), skipped=str(int(skipped)))
        for _ in range(count):
            case = ET.SubElement(suite, 'testcase', classname=probe.CLASS, name=method)
            for name, enabled in [('failure', failure), ('skipped', skipped), ('error', error)]:
                if enabled:
                    ET.SubElement(case, name).text = 'synthetic result'
        folder.mkdir(parents=True, exist_ok=True)
        target = folder / 'TEST-synthetic.xml'
        target.write_bytes(ET.tostring(suite))
        return target

    def test_only_the_exact_public_push_request_is_admitted(self):
        probe.request(self.env(), self.event())
        for key, value in [('GITHUB_REF', 'refs/heads/main'), ('GITHUB_EVENT_NAME', 'pull_request'),
                           ('GITHUB_ACTIONS', 'false'), ('GITHUB_SHA', ''), ('GITHUB_RUN_ID', '0')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                probe.request({**self.env(), key: value}, self.event())
        for event in [{'repository': {'private': True}, 'head_commit': {'message': probe.MESSAGE}},
                      {'repository': {'private': False}, 'head_commit': {'message': probe.MESSAGE + ' extra'}}]:
            with self.assertRaises(ValueError):
                probe.request(self.env(), event)

    def test_exact_xml_rejects_empty_duplicate_foreign_skip_error_and_stale(self):
        for options in [{'count': 0}, {'count': 2}, {'method': 'other'}, {'skipped': True}, {'error': True}]:
            with self.subTest(options=options), tempfile.TemporaryDirectory() as temp:
                self.xml(Path(temp), **options)
                with self.assertRaises(ValueError):
                    probe.single_case(Path(temp), 0)
        with tempfile.TemporaryDirectory() as temp:
            path = self.xml(Path(temp))
            self.assertEqual(1, probe.single_case(Path(temp), 0)['tests'])
            with self.assertRaises(ValueError):
                probe.single_case(Path(temp), path.stat().st_mtime_ns + 1)

    def test_method_schema_and_missing_xml_failures_cannot_turn_green(self):
        for failure, outcome, schema, missing in [(False, 'success', 'success', False),
                (True, 'failure', 'success', False), (True, 'success', 'success', False),
                (False, 'failure', 'success', False), (False, 'success', 'failure', False),
                (False, 'success', 'success', True)]:
            with self.subTest(failure=failure, outcome=outcome, schema=schema, missing=missing), tempfile.TemporaryDirectory() as temp:
                root = Path(temp);folder = root / probe.OUTPUT;folder.mkdir(parents=True)
                (folder / 'admission.json').write_text(json.dumps({'source_sha': '1' * 40, 'run_id': 123, 'run_attempt': 1, 'started_ns': 0}))
                if not missing:
                    self.xml(root / 'app/build/test-results/testDebugUnitTest', failure=failure)
                with patch.object(probe, 'git', return_value='1' * 40):
                    result = probe.collect(root, {**self.env(), 'TARGET_OUTCOME': outcome, 'SCHEMA_OUTCOME': schema})
                expected = 'PASS' if not failure and outcome == schema == 'success' and not missing else 'FAIL'
                self.assertEqual(expected, result['status'])
                self.assertIn('not D01', result['kind'])

    def test_formal_workflow_bodies_and_budgets_remain_byte_identical(self):
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        # Authenticate the original bytes without requiring old Git objects in a shallow checkout.
        self.assertEqual('ba77c84c51b9e32134532a155fc0b37fd3c5d22737f8367c8fbef26402defcf3',
                         hashlib.sha256((self.formal_workflow().rstrip('\n') + '\n').encode()).hexdigest())
        self.assertEqual(3, workflow.count("    if: ${{ !(github.event_name == 'push' && startsWith(github.event.head_commit.message, '[ui-overflow-probe]')) }}"))
        self.assertNotIn('continue-on-error', workflow)

    def test_diagnostic_keeps_all_current_schema_guards_and_fixed_target(self):
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        diagnostic = workflow.split('\n  notebook-overflow-diagnostic:\n', 1)[1]
        import re
        expected = set(re.findall(r'python3 scripts/verify-([a-z-]+)-schema.py --capture', self.formal_workflow()))
        loops = re.findall(r'for kind in ([a-z -]+); do', diagnostic)
        self.assertEqual(2, len(loops))
        self.assertTrue(expected)
        self.assertTrue(all(set(loop.split()) == expected for loop in loops))
        self.assertEqual(1, diagnostic.count(':app:testDebugUnitTest --tests'))
        self.assertIn("--tests '" + probe.CLASS + '.' + probe.METHOD + "'", diagnostic)
        self.assertIn('timeout-minutes: 12', diagnostic)
        self.assertNotIn('needs:', diagnostic)
        self.assertNotIn('ScreenshotTest', diagnostic)
