"""Synthetic fixed admission and evidence controls; no Compose/device claim."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('heading_probe', ROOT / 'scripts/diagnose-memory-heading.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class HeadingDiagnosticTests(unittest.TestCase):
    def env(self):
        return {'GITHUB_ACTIONS': 'true', 'GITHUB_EVENT_NAME': 'push', 'GITHUB_REPOSITORY': 'T1doo/AIyou',
                'GITHUB_REF': probe.REF, 'GITHUB_SHA': '1' * 40, 'GITHUB_RUN_ID': '123',
                'GITHUB_RUN_ATTEMPT': '1', 'TARGET_OUTCOME': 'success', 'SCHEMA_OUTCOME': 'success'}

    def event(self):
        return {'repository': {'private': False}, 'head_commit': {'message': probe.MESSAGE}}

    def fixture(self, root, failure=False, count=1, skip=False, error=False, method=probe.METHOD):
        reports = root / 'app/build/test-results/testDebugUnitTest'
        reports.mkdir(parents=True)
        suite = ET.Element('testsuite', tests=str(count), failures=str(int(failure)), errors=str(int(error)), skipped=str(int(skip)))
        for _ in range(count):
            case = ET.SubElement(suite, 'testcase', classname=probe.CLASS, name=method)
            for tag, enabled in [('failure', failure), ('skipped', skip), ('error', error)]:
                if enabled:
                    ET.SubElement(case, tag).text = 'synthetic result'
        (reports / 'TEST-synthetic.xml').write_bytes(ET.tostring(suite))
        folder = root / probe.OUTPUT
        folder.mkdir(parents=True)
        (folder / 'admission.json').write_text(json.dumps({'source_sha': '1' * 40, 'run_id': 123, 'attempt': 1, 'started_ns': 0}))
        captures = root / probe.CAPTURES
        captures.mkdir(parents=True)
        for index, name in enumerate(probe.FRAMES):
            data = {'source_sha': '1' * 40, 'stage': 'BROWSE', 'queries': 0, 'fontScale': 2,
                    'disclosure': '已收起' if index == 0 else '已展开', 'headingParagraphWidthPx': 220.0,
                    'headingParagraphHeightPx': 40.0, 'headingLineCount': 1,
                    'headingWidthOverflow': False, 'headingHeightOverflow': False,
                    'headingSizePx': 'synthetic size', 'headingConstraints': 'synthetic constraints',
                    'headingBoundsDp': 'synthetic bounds', 'disclosureBoundsDp': 'synthetic bounds'}
            (captures / (name + '.json')).write_text(json.dumps(data))
            (captures / (name + '.png')).write_bytes(b'\x89PNG\r\n\x1a\nsynthetic header fixture; not actual image evidence')

    def test_exact_public_push_attempt1_only(self):
        probe.request(self.env(), self.event())
        for key, value in [('GITHUB_RUN_ATTEMPT', '2'), ('GITHUB_REF', 'refs/heads/main'),
                           ('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_REPOSITORY', 'other/AIyou'),
                           ('GITHUB_SHA', ''), ('GITHUB_RUN_ID', '0'), ('GITHUB_ACTIONS', 'false')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                probe.request({**self.env(), key: value}, self.event())
        for event in [{'repository': {'private': True}, 'head_commit': {'message': probe.MESSAGE}},
                      {'repository': {'private': False}, 'head_commit': {'message': probe.MESSAGE + ' extra'}}]:
            with self.assertRaises(ValueError):
                probe.request(self.env(), event)

    def test_empty_duplicate_foreign_skip_error_and_stale_xml_are_rejected(self):
        for options in [{'count': 0}, {'count': 2}, {'method': 'other'}, {'skip': True}, {'error': True}]:
            with self.subTest(options=options), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.fixture(root, **options)
                with self.assertRaises(ValueError):
                    probe.single_case(root / 'app/build/test-results/testDebugUnitTest', 0)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            path = root / 'app/build/test-results/testDebugUnitTest/TEST-synthetic.xml'
            with self.assertRaises(ValueError):
                probe.single_case(path.parent, path.stat().st_mtime_ns + 1)

    def test_original_red_and_incomplete_or_foreign_captures_cannot_turn_green(self):
        for kind in ('valid', 'original-red', 'schema-fail', 'target-fail', 'missing-first', 'missing-second',
                     'foreign-source', 'queries', 'waiting', 'overflow', 'wrong-font', 'missing-geometry', 'bad-png'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.fixture(root, failure=kind == 'original-red')
                env = self.env()
                if kind == 'schema-fail': env['SCHEMA_OUTCOME'] = 'failure'
                if kind == 'target-fail': env['TARGET_OUTCOME'] = 'failure'
                captures = root / probe.CAPTURES
                if kind.startswith('missing-') and kind in ('missing-first', 'missing-second'):
                    (captures / (probe.FRAMES[int(kind == 'missing-second')] + '.json')).unlink()
                path = captures / (probe.FRAMES[0] + '.json')
                data = json.loads(path.read_text()) if path.exists() else None
                if kind == 'foreign-source': data['source_sha'] = '2' * 40
                if kind == 'queries': data['queries'] = 1
                if kind == 'waiting': data['stage'] = 'WAITING'
                if kind == 'overflow': data['headingWidthOverflow'] = True
                if kind == 'wrong-font': data['fontScale'] = 1
                if kind == 'missing-geometry': del data['headingConstraints']
                if data is not None: path.write_text(json.dumps(data))
                if kind == 'bad-png': (captures / (probe.FRAMES[0] + '.png')).write_bytes(b'bad')
                with patch.object(probe, 'git', return_value='1' * 40):
                    result = probe.collect(root, env)
                self.assertEqual('PASS' if kind == 'valid' else 'FAIL', result['status'])
                if kind == 'original-red': self.assertEqual(1, result['actual']['failures'])

    def test_exact_fixed_source_and_workflow_routing_preserve_formal_bodies(self):
        source = (ROOT / probe.SOURCE).read_bytes()
        self.assertEqual(probe.TEST_SHA256, hashlib.sha256(source).hexdigest())
        self.assertIn('heading.hasVisualOverflow', source.decode())
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        old = ("github.event_name == 'push' && github.repository == 'T1doo/AIyou' && "
               "github.event.repository.private == false && github.ref == 'refs/heads/codex/d03-short-audio-design-20261020' && "
               "github.event.head_commit.message == '[ui-companion-probe] b438 actual button publication'")
        new = old.replace('[ui-companion-probe] b438 actual button publication', probe.MESSAGE)
        self.assertEqual(5, workflow.count(new))
        self.assertEqual(3, workflow.count('    if: ${{ !((' + old + ') || (' + new + ')) }}'))
        self.assertIn('    if: ${{ ' + new + ' }}', workflow)
        self.assertIn(new + " && 'DIAGNOSTIC ONLY - memory heading overflow (not D01 acceptance)'", workflow)
        self.assertNotIn('startsWith(', workflow)
        self.assertNotIn('continue-on-error', workflow)
        diagnostic = workflow.split('\n  memory-heading-diagnostic:\n', 1)[1]
        self.assertEqual(1, diagnostic.count(':app:testDebugUnitTest --tests'))
        self.assertIn("--tests '" + probe.CLASS + '.' + probe.METHOD + "'", diagnostic)
        for required in ('timeout-minutes: 12', '540s scripts/gradle.sh', 'persist-credentials: false',
                         'cancel-in-progress: false', 'if: always()', probe.CAPTURES):
            self.assertIn(required, diagnostic)
        self.assertNotIn('needs:', diagnostic)
        self.assertNotIn('ScreenshotTest', diagnostic)
        import re
        loops = re.findall(r'for kind in ([a-z -]+); do', diagnostic)
        formal = workflow.split('\n  companion-motion-diagnostic:\n')[0]
        schemas = set(re.findall(r'python3 scripts/verify-([a-z-]+)-schema.py --capture', formal))
        self.assertEqual(2, len(loops))
        self.assertTrue(all(set(loop.split()) == schemas for loop in loops))
