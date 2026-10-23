#!/usr/bin/env python3
"""One fixed Lulu motion admission trace, never formal D01 or device acceptance."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = 'b4380bdb17e52681609a552c8542de1b705f199d'
TEST_OWNER = 'e0db9e90c10a8e4f61490f5c2fbb1b5d845755c8'
SOURCE = 'app/src/test/kotlin/com/t1doo/aiyou/CompanionSettingsPresentationTest.kt'
TEST_SHA256 = '2337c497f0f03737ba562155f539ead955b36459047c3c3dacbdc83fc7457c39'
MESSAGE = '[ui-companion-probe] b438 actual button publication'
REF = 'refs/heads/codex/d03-short-audio-design-20261020'
CLASS = 'com.t1doo.aiyou.CompanionSettingsPresentationTest'
METHOD = 'finiteInteractionMotionStopsOnReductionAndBackgroundWithoutAutomaticReplay'
ALLOWED = {SOURCE, '.github/workflows/android.yml', 'scripts/diagnose-companion-motion.py',
           'scripts/tests/test_companion_motion_diagnostic.py',
           'scripts/diagnose-notebook-overflow.py', 'scripts/tests/test_notebook_overflow_diagnostic.py'}
OUTPUT = 'build/companion-motion-diagnostic'
TRACE = 'app/build/reports/companion-motion/interactions/motion-probe.json'
FRAMES = ('00-idle.png', '01-greeting.png', '02-encouraging.png', '02b-quiet-still.png', '03-background-still.png')
HOST_PHASES = ('initial-owner', 'motion-disabled', 'motion-enabled', 'background',
               'foreground-return', 'owner-replacement', 'fallback-asset')
BUTTON_TAGS = ('companion-greet', 'companion-encourage', 'companion-quiet')
CHECKPOINTS = {
    'greeting-before-reduction': ('scrollStable', 'before', 'afterClick', 'firstFrame', '64ms'),
    'encouragement-before-quiet': ('scrollStable', 'before', 'afterClick', 'firstFrame', '96ms'),
    'quiet-cancel': ('scrollStable', 'before', 'afterClick', 'firstFrame'),
    'encouragement-before-background': ('scrollStable', 'before', 'afterClick', 'firstFrame', '96ms'),
    'encouragement-natural-finish': ('scrollStable', 'before', 'afterClick', 'firstFrame', '96ms', 'settled'),
    'fallback-greeting': ('scrollStable', 'before', 'afterClick', 'firstFrame', '64ms'),
    'retired-owner-callbacks': ('before', 'afterCallbacks'),
    **{phase: ('before', 'consumed', 'settled') for phase in HOST_PHASES},
}


def require(value, message):
    if not value:
        raise ValueError(message)


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, timeout=10).decode().strip()


def buttons_match(row, enabled=None):
    samples = row.get('button', {}).get('semantics', {})
    if not isinstance(samples, dict):
        return False
    for tag in BUTTON_TAGS:
        sample = samples.get(tag, {})
        if not isinstance(sample, dict):
            return False
        nodes = sample.get('nodes', [])
        count = sample.get('count')
        if type(count) is not int or count < 0 or not isinstance(nodes, list) or count != len(nodes):
            return False
        if any(not isinstance(node, dict) or type(node.get('disabled')) is not bool or
               any(type(node.get(key)) not in (int, float) or not math.isfinite(node[key])
                   for key in ('left', 'top', 'right', 'bottom')) for node in nodes):
            return False
        if enabled is not None and (count != 1 or nodes[0]['disabled'] is not (not enabled)):
            return False
    return True


def request(env, event):
    require(env.get('GITHUB_ACTIONS') == 'true' and env.get('GITHUB_EVENT_NAME') == 'push', 'CI push only')
    require(env.get('GITHUB_REPOSITORY') == 'T1doo/AIyou' and event['repository']['private'] is False, 'Public original repository only')
    require(env.get('GITHUB_REF') == REF and event['head_commit']['message'] == MESSAGE, 'Wrong fixed branch or request')
    require(re.fullmatch('[0-9a-f]{40}', env.get('GITHUB_SHA', '')), 'Missing exact execution SHA')
    require(re.fullmatch('[1-9][0-9]*', env.get('GITHUB_RUN_ID', '')), 'Missing run identity')
    require(env.get('GITHUB_RUN_ATTEMPT') == '1', 'Only the first attempt is authorized')


def admit(root, env):
    event = json.loads(Path(env['GITHUB_EVENT_PATH']).read_text())
    request(env, event)
    require(git(root, 'rev-parse', 'HEAD') == env['GITHUB_SHA'] and not git(root, 'status', '--porcelain'), 'Checkout must be exact and clean')
    subprocess.run(['git', 'merge-base', '--is-ancestor', TEST_OWNER, 'HEAD'], cwd=root, timeout=10, check=True)
    require(set(git(root, 'diff', '--name-only', PRODUCTION, 'HEAD').splitlines()) <= ALLOWED, 'Unreviewed source difference')
    test_blob = git(root, 'rev-parse', TEST_OWNER + ':' + SOURCE)
    require(git(root, 'rev-parse', 'HEAD:' + SOURCE) == test_blob and hashlib.sha256((root / SOURCE).read_bytes()).hexdigest() == TEST_SHA256, 'Test differs from the fixed trace owner')
    trees = {}
    for path in ('app/src/main', 'core'):
        expected = git(root, 'rev-parse', PRODUCTION + ':' + path)
        require(git(root, 'rev-parse', 'HEAD:' + path) == expected, 'Production differs from b4380bd')
        trees[path] = expected
    receipt = {'kind': 'DIAGNOSTIC ONLY; formal354/Preview/lint/native/device NOT RUN',
               'source_sha': env['GITHUB_SHA'], 'run_id': int(env['GITHUB_RUN_ID']), 'run_attempt': int(env['GITHUB_RUN_ATTEMPT']),
               'production_owner': PRODUCTION, 'production_trees': trees, 'test_owner': TEST_OWNER,
               'test_blob': test_blob, 'test_sha256': TEST_SHA256, 'class': CLASS, 'method': METHOD, 'started_ns': time.time_ns()}
    folder = root / OUTPUT
    folder.mkdir(parents=True, exist_ok=False)
    (folder / 'admission.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


def single_case(folder, started_ns):
    paths = list(folder.glob('TEST-*.xml'))
    require(len(paths) == 1, 'Require exactly one fresh XML file')
    path = paths[0]
    require(not path.is_symlink() and path.stat().st_mtime_ns >= started_ns and 0 < path.stat().st_size <= 4 * 1024 * 1024, 'Stale/unsafe XML')
    raw = path.read_bytes()
    suite = ET.fromstring(raw)
    cases = list(suite.iter('testcase'))
    require(int(suite.get('tests', '-1')) == len(cases) == 1, 'Require exactly one case; empty/duplicate rejected')
    case = cases[0]
    require((case.get('classname'), case.get('name')) == (CLASS, METHOD), 'Wrong actual method')
    counts = {k: int(suite.get(k, '0')) for k in ('failures', 'errors', 'skipped')}
    require(all(counts[k] == len(case.findall(k[:-1] if k != 'skipped' else k)) for k in counts), 'Count and actual XML disagree')
    require(counts['errors'] == counts['skipped'] == 0 and counts['failures'] in (0, 1), 'Error/skip is not a diagnostic pass')
    return {'tests': 1, **counts, 'xml': path.name, 'xml_bytes': len(raw), 'xml_sha256': hashlib.sha256(raw).hexdigest()}


def collect(root, env):
    folder = root / OUTPUT
    folder.mkdir(parents=True, exist_ok=True)
    result = {'kind': 'DIAGNOSTIC ONLY; not D01 or device acceptance', 'status': 'FAIL',
              'source_sha': env.get('GITHUB_SHA'), 'admission_authenticated': False,
              'target_outcome': env.get('TARGET_OUTCOME'), 'schema_outcome': env.get('SCHEMA_OUTCOME')}
    try:
        receipt = json.loads((folder / 'admission.json').read_text())
        require(receipt['source_sha'] == env['GITHUB_SHA'] == git(root, 'rev-parse', 'HEAD') and
                receipt['run_id'] == int(env['GITHUB_RUN_ID']) and receipt['run_attempt'] == int(env['GITHUB_RUN_ATTEMPT']), 'Foreign admission')
        result['admission_authenticated'] = True
        result['actual'] = single_case(root / 'app/build/test-results/testDebugUnitTest', receipt['started_ns'])
        result['captures'] = []
        for name in FRAMES:
            path = (root / TRACE).parent / name
            if path.exists():
                require(not path.is_symlink() and path.stat().st_mtime_ns >= receipt['started_ns'] and
                        0 < path.stat().st_size <= 2 * 1024 * 1024, 'Stale/unsafe capture')
                raw = path.read_bytes()
                result['captures'].append({'file': name, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()})
        trace = root / TRACE
        require(not trace.is_symlink() and trace.stat().st_mtime_ns >= receipt['started_ns'] and
                0 < trace.stat().st_size <= 2 * 1024 * 1024, 'Missing/stale/unsafe trace')
        raw = trace.read_bytes()
        data = json.loads(raw)
        require(data['source_sha'] == env['GITHUB_SHA'] and data['method'] == METHOD, 'Foreign trace')
        observations = data['observations']
        require(isinstance(observations, list) and 0 < len(observations) <= 512, 'Empty/unbounded trace')
        points = [(row['phase'], row['point']) for row in observations]
        publication_frames = {phase: [point for current, point in points
                                     if current == phase and point.startswith('publicationFrame-')]
                              for phase in HOST_PHASES}
        unconsumed = [phase for phase, point in points if point == 'unconsumed']
        semantics_mismatches = []
        for row in observations:
            phase, point = row['phase'], row['point']
            if phase in HOST_PHASES and (point in CHECKPOINTS[phase] or point.startswith('publicationFrame-')):
                enabled = phase != 'background' if point in ('consumed', 'settled') else None
            elif phase == 'retired-owner-callbacks' and point in CHECKPOINTS[phase]:
                enabled = True
            else:
                continue
            if not buttons_match(row, enabled):
                semantics_mismatches.append((phase, point))
        result['trace'] = {'path': TRACE, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
                           'observations': len(observations),
                           'publication_frames_by_phase': publication_frames, 'unconsumed_phases': unconsumed,
                           'button_semantics_mismatches': semantics_mismatches,
                           'checkpoints_by_phase': {phase: [point for point in expected if (phase, point) in points]
                                                    for phase, expected in CHECKPOINTS.items()}}
        require(all(points.count((phase, point)) == 1 for phase, expected in CHECKPOINTS.items() for point in expected),
                'Incomplete/duplicate phase-and-point trace remains FAIL')
        require(not unconsumed, 'An unconsumed host publication remains FAIL')
        require(not semantics_mismatches, 'Published/retired-owner actual button semantics differ; remains FAIL')
        for phase, frames in publication_frames.items():
            require(1 <= len(frames) <= 4 and frames == [f'publicationFrame-{i}' for i in range(1, len(frames) + 1)],
                    'Host publication must have one to four consecutive frames: ' + phase)
            ordered = [point for current, point in points if current == phase and
                       (point in CHECKPOINTS[phase] or point.startswith('publicationFrame-'))]
            require(ordered == ['before', *frames, 'consumed', 'settled'], 'Host publication checkpoint order differs: ' + phase)
        require(env.get('TARGET_OUTCOME') == env.get('SCHEMA_OUTCOME') == 'success' and result['actual']['failures'] == 0, 'Original method/schema failure remains FAIL')
        require(len(result['captures']) == len(FRAMES), 'A successful complete method must produce all five captures')
        result['status'] = 'PASS'
    except (OSError, ValueError, KeyError, ET.ParseError, subprocess.SubprocessError) as error:
        result['reason'] = str(error)
    with (folder / 'RESULT.json').open('x') as output:
        json.dump(result, output, indent=2)
        output.write('\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--admit', action='store_true')
    group.add_argument('--collect', action='store_true')
    args = parser.parse_args()
    result = admit(ROOT, os.environ) if args.admit else collect(ROOT, os.environ)
    print(json.dumps(result, indent=2))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as summary:
            summary.write('## DIAGNOSTIC ONLY — not D01 acceptance\n\n```json\n' + json.dumps(result, indent=2) + '\n```\n')
    if args.collect and result['status'] != 'PASS':
        raise SystemExit(1)
