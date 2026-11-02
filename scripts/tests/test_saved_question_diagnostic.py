"""Bounded synthetic routing/evidence checks; no Gradle, Android or hosted job execution."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('saved_question_probe', ROOT / 'scripts/diagnose-saved-questions.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class SavedQuestionDiagnosticTests(unittest.TestCase):
    def env(self):
        return {'GITHUB_ACTIONS': 'true', 'GITHUB_EVENT_NAME': 'push', 'GITHUB_REPOSITORY': 'T1doo/AIyou',
                'GITHUB_REF': probe.REF, 'GITHUB_SHA': '1' * 40, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1',
                'TARGET_OUTCOME': 'success', 'SCHEMA_OUTCOME': 'success'}

    def event(self):
        return {'repository': {'private': False}, 'head_commit': {'message': probe.MESSAGE}}

    def fixture(self, root):
        folder = root / probe.OUTPUT
        folder.mkdir(parents=True)
        (folder / 'admission.json').write_text(json.dumps({'source_sha': '1' * 40, 'run_id': 123, 'attempt': 1, 'started_ns': 0}))
        (folder / 'gradle.log').write_text('QUESTION_UI_REVEAL before tag=question-save-or-open-history\n')
        reports = root / probe.REPORTS
        reports.mkdir(parents=True)
        for name in sorted({c for c, _ in probe.CASES}):
            methods = sorted(m for c, m in probe.CASES if c == name)
            suite = ET.Element('testsuite', tests=str(len(methods)), failures='0', errors='0', skipped='0')
            for method in methods:
                ET.SubElement(suite, 'testcase', classname=name, name=method)
            (reports / ('TEST-' + name + '.xml')).write_bytes(ET.tostring(suite))
        captures = root / probe.CAPTURES
        captures.mkdir(parents=True)
        for name in ('01-normal.png', '02-double-font.png'):
            # Header fixture only. Pixel validity is enforced in the fixed real Compose test, not simulated here.
            (captures / name).write_bytes(b'\x89PNG\r\n\x1a\nsynthetic collector fixture; not screenshot evidence')
        (captures / 'fontscale.json').write_text(json.dumps({'source_sha': '1' * 40, 'configurationFontScale': 2,
            'layoutFontScale': 2, 'inputHasVisualOverflow': False, 'entryWrites': 0}))

    def collect(self, root, env=None):
        with patch.object(probe, 'git', return_value='1' * 40), patch.object(probe, 'source_contract'):
            return probe.collect(root, env or self.env())

    def test_reviewed_enable_still_requires_exact_public_attempt1_request(self):
        self.assertTrue(probe.EXECUTION_APPROVED)
        with patch.object(probe, 'EXECUTION_APPROVED', False), self.assertRaises(ValueError):
            probe.request(self.env(), self.event())
        with patch.object(probe, 'EXECUTION_APPROVED', True):
            probe.request(self.env(), self.event())
            for key, value in [('GITHUB_REF', 'refs/heads/codex/mobile-tabs-20261027'), ('GITHUB_EVENT_NAME', 'workflow_dispatch'),
                ('GITHUB_RUN_ATTEMPT', '2'), ('GITHUB_REPOSITORY', 'other/AIyou'), ('GITHUB_SHA', ''), ('GITHUB_RUN_ID', '0')]:
                with self.subTest(key=key), self.assertRaises(ValueError):
                    probe.request({**self.env(), key: value}, self.event())
            for event in [{'repository': {'private': True}, 'head_commit': {'message': probe.MESSAGE}},
                          {'repository': {'private': False}, 'head_commit': {'message': probe.MESSAGE + ' extra'}}]:
                with self.assertRaises(ValueError):
                    probe.request(self.env(), event)

    def test_fixed_three_filters_budgets_and_existing_workflow_unchanged(self):
        workflow = (ROOT / probe.WORKFLOW).read_text()
        self.assertIn("branches: ['" + probe.REF.removeprefix('refs/heads/') + "']", workflow)
        self.assertNotIn('workflow_dispatch', workflow)
        self.assertNotIn('pull_request:', workflow)
        self.assertIn("if: ${{ github.event_name == 'push' && github.repository == 'T1doo/AIyou'", workflow)
        self.assertNotIn('if: ${{ false &&', workflow)
        self.assertIn('github.run_attempt == 1', workflow)
        self.assertIn(probe.MESSAGE, workflow)
        self.assertEqual(3, workflow.count('--tests '))
        for name, method in probe.CASES:
            self.assertIn("--tests '" + name + '.' + method + "'", workflow)
        for expected in ('runs-on: ubuntu-24.04', 'timeout-minutes: 12', '540s scripts/gradle.sh',
                         '--kill-after=10s', 'retention-days: 1', 'persist-credentials: false', 'contents: read'):
            self.assertIn(expected, workflow)
        for forbidden in ('continue-on-error', 'sdkmanager', 'sudo', 'secrets.', 'needs:', ':app:assembleDebug', 'ScreenshotTest'):
            self.assertNotIn(forbidden, workflow)
        self.assertEqual('9697616c2bf12d3ba103dbcd939d82d36b95a102056d3a53f51f85659a50905e',
                         hashlib.sha256((ROOT / '.github/workflows/android.yml').read_bytes()).hexdigest())
        self.assertFalse(probe.REF.removeprefix('refs/heads/').startswith('codex/'))
        self.assertEqual(8 * 1024 * 1024, probe.MAX_ARTIFACT)
        for path, digest in probe.SOURCES.items():
            self.assertEqual(digest, hashlib.sha256((ROOT / path).read_bytes()).hexdigest())
        form = (ROOT / next(p for p in probe.SOURCES if p.endswith('/SavedQuestionsPresentationTest.kt'))).read_text()
        self.assertIn('@GraphicsMode(GraphicsMode.Mode.NATIVE)', form)
        self.assertIn('pixels.firstOrNull {(it ushr 24)!=0}', form)
        self.assertIn('pixels.any {(it ushr 24)!=0&&it!=visibleColor}', form)
        self.assertIn('assertFalse(layout.hasVisualOverflow)', form)

    def test_exact_three_success_and_original_failure_skip_error_are_never_laundered(self):
        for kind in ('pass', 'failure', 'error', 'skip', 'target', 'schema', 'duplicate', 'foreign', 'extra-xml', 'missing-xml'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temp:
                root = Path(temp);self.fixture(root);env = self.env()
                path = sorted((root / probe.REPORTS).glob('TEST-*.xml'))[0]
                suite = ET.fromstring(path.read_bytes());case = suite.find('testcase')
                if kind in ('failure', 'error', 'skip'):
                    tag = {'failure': 'failure', 'error': 'error', 'skip': 'skipped'}[kind]
                    ET.SubElement(case, tag).text = 'synthetic original failure'
                    suite.set({'failure': 'failures', 'error': 'errors', 'skip': 'skipped'}[kind], '1')
                if kind == 'duplicate':
                    ET.SubElement(suite, 'testcase', **case.attrib);suite.set('tests', str(len(suite.findall('testcase'))))
                if kind == 'foreign':case.set('name', 'unapprovedMethod')
                path.write_bytes(ET.tostring(suite))
                if kind == 'extra-xml':(path.parent / 'TEST-extra.xml').write_text('<testsuite/>')
                if kind == 'missing-xml':path.unlink()
                if kind in ('target', 'schema'):env[{'target': 'TARGET_OUTCOME', 'schema': 'SCHEMA_OUTCOME'}[kind]] = 'failure'
                result = self.collect(root, env)
                self.assertEqual('PASS' if kind == 'pass' else 'FAIL', result['status'])
                if kind == 'pass':self.assertEqual(3, len(result['actual']))
                self.assertTrue((root / probe.OUTPUT / 'evidence/RESULT.json').is_file())

    def test_missing_font_png_foreign_source_nonzero_writes_and_artifact_overflow_fail_closed(self):
        for kind in ('font', 'png', 'source', 'writes', 'size', 'symlink', 'missing-log'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temp:
                root = Path(temp);self.fixture(root)
                captures = root / probe.CAPTURES;path = captures / 'fontscale.json'
                data = json.loads(path.read_text())
                if kind == 'source':data['source_sha'] = '2' * 40
                if kind == 'writes':data['entryWrites'] = 1
                path.write_text(json.dumps(data))
                if kind == 'font':path.unlink()
                if kind == 'png':(captures / '01-normal.png').unlink()
                if kind == 'size':(root / probe.OUTPUT / 'gradle.log').write_bytes(b'x' * probe.MAX_ARTIFACT)
                if kind == 'missing-log':(root / probe.OUTPUT / 'gradle.log').unlink()
                if kind == 'symlink':
                    path.unlink();path.symlink_to(root / probe.OUTPUT / 'admission.json')
                result = self.collect(root)
                self.assertEqual('FAIL', result['status'])
                if kind in ('size', 'missing-log'):
                    evidence = root / probe.OUTPUT / 'evidence'
                    self.assertEqual(2, len(list(evidence.glob('TEST-*.xml'))))
                    self.assertTrue((evidence / '01-normal.png').is_file())
                    self.assertFalse((evidence / 'gradle.log').exists())
                    self.assertIn('gradle.log', [i['name'] for i in result['evidence_issues']])
                    self.assertLessEqual(sum(p.stat().st_size for p in evidence.iterdir()), probe.MAX_ARTIFACT)

    def test_stale_files_and_changed_source_cannot_admit(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp);self.fixture(root);path = root / probe.OUTPUT / 'gradle.log'
            with self.assertRaises(ValueError):probe.fresh(root, path, path.stat().st_mtime_ns + 1, 1024)
        def fake_git(root, *args):
            if args == ('status', '--porcelain'):return ''
            if args[:2] == ('diff', '--name-only'):return 'app/src/main/AndroidManifest.xml'
            return 'unchanged'
        with patch.object(probe, 'git', side_effect=fake_git), patch.object(probe.subprocess, 'run'):
            with self.assertRaises(ValueError):probe.source_contract(ROOT)


if __name__ == '__main__':
    unittest.main()
