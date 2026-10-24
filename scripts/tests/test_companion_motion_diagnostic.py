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
spec = importlib.util.spec_from_file_location('companion_probe', ROOT / 'scripts/diagnose-companion-motion.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class CompanionDiagnosticTests(unittest.TestCase):
    def formal_workflow(self):
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        formal = workflow.split('\n  companion-motion-diagnostic:\n')[0]
        lines = formal.splitlines(keepends=True)
        lines = [line for line in lines if not line.startswith('run-name:') and not line.startswith('    if: ${{ !(')]
        formal = ''.join(lines)
        comment = ('# The exact public-repo/branch/message tuple is push-only. Before its approved push, require\n'
                   '# no open PR for that branch: PR synchronize retains the normal acceptance path.\n'
                   '# Every other push retains all three formal jobs; runtime admission also requires attempt1.\n')
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

    def trace(self, root):
        path = root / probe.TRACE
        path.parent.mkdir(parents=True, exist_ok=True)
        observations = []
        for phase, points in probe.CHECKPOINTS.items():
            for point in points:
                semantics = {tag: {'count': 1, 'nodes': [{'disabled': phase == 'background',
                              'left': 24, 'top': 20, 'right': 336, 'bottom': 68}]} for tag in probe.BUTTON_TAGS}
                row = {'phase': phase, 'point': point, 'button': {'semantics': semantics}}
                observations.append(row)
                if phase in probe.HOST_PHASES and point == 'before':
                    observations.append({**row, 'point': 'publicationFrame-1'})
        data = {'source_sha': '1' * 40, 'method': probe.METHOD, 'observations': observations}
        path.write_text(json.dumps(data))
        for name in probe.FRAMES:
            (path.parent / name).write_bytes(b'synthetic fixture; not actual PNG or Android evidence')
        return path, data

    def test_only_the_exact_public_push_request_is_admitted(self):
        probe.request(self.env(), self.event())
        for key, value in [('GITHUB_REF', 'refs/heads/main'), ('GITHUB_EVENT_NAME', 'pull_request'),
                           ('GITHUB_ACTIONS', 'false'), ('GITHUB_SHA', ''), ('GITHUB_RUN_ID', '0'),
                           ('GITHUB_RUN_ATTEMPT', '2')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                probe.request({**self.env(), key: value}, self.event())
        for event in [{'repository': {'private': True}, 'head_commit': {'message': probe.MESSAGE}},
                      {'repository': {'private': False}, 'head_commit': {'message': probe.MESSAGE + ' extra'}},
                      {'repository': {'private': False}, 'head_commit': {'message': '[ui-companion-probe] b438 finite motion admission'}},
                      {'repository': {'private': False}, 'head_commit': {'message': '[ui-companion-probe] b438 all action admissions'}},
                      {'repository': {'private': False}, 'head_commit': {'message': '[ui-companion-probe] b438 host state publication'}}]:
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
                self.trace(root)
                with patch.object(probe, 'git', return_value='1' * 40):
                    result = probe.collect(root, {**self.env(), 'TARGET_OUTCOME': outcome, 'SCHEMA_OUTCOME': schema})
                expected = 'PASS' if not failure and outcome == schema == 'success' and not missing else 'FAIL'
                self.assertEqual(expected, result['status'])
                self.assertIn('not D01', result['kind'])

    def test_trace_identity_empty_duplicate_and_incomplete_checkpoints_cannot_pass(self):
        for kind in ('source', 'method', 'empty', 'duplicate', 'wrong-phase', 'incomplete', 'unbounded', 'missing', 'capture'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); folder = root / probe.OUTPUT; folder.mkdir(parents=True)
                (folder / 'admission.json').write_text(json.dumps({'source_sha': '1' * 40, 'run_id': 123, 'run_attempt': 1, 'started_ns': 0}))
                self.xml(root / 'app/build/test-results/testDebugUnitTest')
                trace, data = self.trace(root)
                if kind == 'source': data['source_sha'] = '2' * 40
                elif kind == 'method': data['method'] = 'other'
                elif kind == 'empty': data['observations'] = []
                elif kind == 'duplicate': data['observations'].append(dict(data['observations'][0]))
                elif kind == 'wrong-phase': data['observations'][0]['phase'] = 'other'
                elif kind == 'incomplete': data['observations'].pop()
                elif kind == 'unbounded': data['observations'] *= 129
                elif kind == 'capture': (trace.parent / probe.FRAMES[-1]).unlink()
                trace.write_text(json.dumps(data))
                if kind == 'missing': trace.unlink()
                with patch.object(probe, 'git', return_value='1' * 40):
                    self.assertEqual('FAIL', probe.collect(root, self.env())['status'])

    def test_host_publication_requires_ordered_bounded_frames_and_actual_consumption(self):
        for kind in ('one', 'four', 'missing-frame', 'duplicate-frame', 'reversed-frames', 'five',
                     'missing-consumed', 'duplicate-consumed', 'late-before', 'unconsumed',
                     'disabled-consumed', 'encourage-disabled', 'quiet-disabled', 'duplicate-button', 'missing-button', 'missing-buttons', 'enabled-background',
                     'retired-disabled', 'pending-disabled', 'pending-missing-button', 'missing-frame-semantics'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); folder = root / probe.OUTPUT; folder.mkdir(parents=True)
                (folder / 'admission.json').write_text(json.dumps({'source_sha': '1' * 40, 'run_id': 123, 'run_attempt': 1, 'started_ns': 0}))
                self.xml(root / 'app/build/test-results/testDebugUnitTest')
                trace, data = self.trace(root)
                phase = 'fallback-asset'
                rows = data['observations']
                frame_index = next(i for i, row in enumerate(rows) if (row['phase'], row['point']) == (phase, 'publicationFrame-1'))
                if kind in ('four', 'five'):
                    count = 4 if kind == 'four' else 5
                    rows[frame_index:frame_index + 1] = [{**rows[frame_index], 'point': f'publicationFrame-{i}'} for i in range(1, count + 1)]
                elif kind == 'missing-frame': rows.pop(frame_index)
                elif kind == 'duplicate-frame': rows.insert(frame_index, dict(rows[frame_index]))
                elif kind == 'reversed-frames': rows[frame_index:frame_index + 1] = [
                     {**rows[frame_index], 'point': 'publicationFrame-2'}, {**rows[frame_index], 'point': 'publicationFrame-1'}]
                elif kind == 'missing-consumed': rows.remove(next(row for row in rows if (row['phase'], row['point']) == (phase, 'consumed')))
                elif kind == 'duplicate-consumed': rows.append({'phase': phase, 'point': 'consumed'})
                elif kind == 'late-before':
                    row = next(row for row in rows if (row['phase'], row['point']) == (phase, 'before')); rows.remove(row); rows.append(row)
                elif kind == 'unconsumed': rows.append({'phase': phase, 'point': 'unconsumed'})
                elif kind in ('disabled-consumed', 'encourage-disabled', 'quiet-disabled', 'duplicate-button', 'missing-button', 'missing-buttons', 'enabled-background', 'retired-disabled', 'pending-disabled', 'pending-missing-button', 'missing-frame-semantics'):
                    target_phase, target_point = (phase, 'consumed')
                    if kind == 'enabled-background': target_phase = 'background'
                    elif kind == 'retired-disabled': target_phase, target_point = 'retired-owner-callbacks', 'afterCallbacks'
                    elif kind in ('pending-disabled', 'pending-missing-button', 'missing-frame-semantics'): target_point = 'publicationFrame-1'
                    row = next(row for row in rows if (row['phase'], row['point']) == (target_phase, target_point))
                    # Independent JSON values: changing an in-progress sample must not alter a later confirmed sample.
                    row['button'] = json.loads(json.dumps(row['button']))
                    tag = {'encourage-disabled': 'companion-encourage', 'quiet-disabled': 'companion-quiet'}.get(kind, 'companion-greet')
                    sample = row['button']['semantics'][tag]
                    if kind in ('missing-buttons', 'missing-frame-semantics'): row['button'] = {}
                    elif kind == 'duplicate-button': sample['count'] = 2; sample['nodes'] *= 2
                    elif kind in ('missing-button', 'pending-missing-button'): sample['count'] = 0; sample['nodes'] = []
                    else: sample['nodes'][0]['disabled'] = kind != 'enabled-background'
                trace.write_text(json.dumps(data))
                with patch.object(probe, 'git', return_value='1' * 40):
                    result = probe.collect(root, self.env())
                self.assertEqual('PASS' if kind in ('one', 'four', 'pending-disabled', 'pending-missing-button') else 'FAIL', result['status'])
                self.assertEqual(['fallback-asset'] if kind == 'unconsumed' else [], result['trace']['unconsumed_phases'])
                self.assertEqual([row['point'] for row in rows if row['phase'] == phase and row['point'].startswith('publicationFrame-')],
                                 result['trace']['publication_frames_by_phase'][phase])

    def test_formal_workflow_bodies_and_budgets_remain_byte_identical(self):
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        # Authenticate the original bytes without requiring old Git objects in a shallow checkout.
        self.assertEqual('b59cc039ff252f218ae6ae91ec449de05fe4cb9ec525e55beb99e01de5107a13',
                         hashlib.sha256((self.formal_workflow().rstrip('\n') + '\n').encode()).hexdigest())
        selector = ("github.event_name == 'push' && github.repository == 'T1doo/AIyou' && "
                    "github.event.repository.private == false && github.ref == 'refs/heads/codex/d03-short-audio-design-20261020' && "
                    "github.event.head_commit.message == '[ui-companion-probe] b438 actual button publication'")
        self.assertEqual(5, workflow.count(selector))
        self.assertEqual(3, workflow.count('    if: ${{ !(' + selector + ') }}'))
        self.assertEqual(1, workflow.count('    if: ${{ ' + selector + ' }}'))
        self.assertTrue(workflow.splitlines()[1].startswith('run-name: ${{ ' + selector + ' && '))
        self.assertNotIn('startsWith(', workflow)
        self.assertNotIn('continue-on-error', workflow)

    def test_diagnostic_keeps_all_current_schema_guards_and_fixed_target(self):
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        diagnostic = workflow.split('\n  companion-motion-diagnostic:\n', 1)[1]
        import re
        expected = set(re.findall(r'python3 scripts/verify-([a-z-]+)-schema.py --capture', self.formal_workflow()))
        loops = re.findall(r'for kind in ([a-z -]+); do', diagnostic)
        self.assertEqual(2, len(loops))
        self.assertTrue(expected)
        self.assertTrue(all(set(loop.split()) == expected for loop in loops))
        self.assertEqual(1, diagnostic.count(':app:testDebugUnitTest --tests'))
        self.assertIn("--tests '" + probe.CLASS + '.' + probe.METHOD + "'", diagnostic)
        self.assertIn('timeout-minutes: 12', diagnostic)
        self.assertIn('540s scripts/gradle.sh', diagnostic)
        self.assertIn('app/build/reports/companion-motion/', diagnostic)
        self.assertNotIn('needs:', diagnostic)
        self.assertNotIn('ScreenshotTest', diagnostic)
