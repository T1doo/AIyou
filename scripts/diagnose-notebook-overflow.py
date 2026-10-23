#!/usr/bin/env python3
"""One fixed Compose measurement, never the formal D01 or device acceptance gate."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = '20f581f465b42853ee3648519cc379cd0cdd0a76'
TEST_OWNER = 'aa7c5967453f1251798ec695e2e018aad9a94559'
SOURCE = 'app/src/test/kotlin/com/t1doo/aiyou/MixedNotebookPresentationTest.kt'
TEST_SHA256 = 'e789bbaff59a759b7c076146abb665d598abeabd322404838f61d60377b92285'
MESSAGE = '[ui-overflow-probe] fixed Notebook measurement'
REF = 'refs/heads/codex/d03-short-audio-design-20261020'
CLASS = 'com.t1doo.aiyou.MixedNotebookPresentationTest'
METHOD = 'realNotebookUsesBundledLuluOnlyWhenPreferenceIsReadyAndVisibleWithoutWritingRecords'
ALLOWED = {SOURCE, '.github/workflows/android.yml', 'scripts/diagnose-notebook-overflow.py',
           'scripts/tests/test_notebook_overflow_diagnostic.py'}
OUTPUT = 'build/notebook-overflow'


def require(value, message):
    if not value:
        raise ValueError(message)


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, timeout=10).decode().strip()


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
    require(git(root, 'rev-parse', 'HEAD:' + SOURCE) == test_blob and hashlib.sha256((root / SOURCE).read_bytes()).hexdigest() == TEST_SHA256, 'Test differs from aa7c596')
    trees = {}
    for path in ('app/src/main', 'core'):
        expected = git(root, 'rev-parse', PRODUCTION + ':' + path)
        require(git(root, 'rev-parse', 'HEAD:' + path) == expected, 'Production differs from20f581f')
        trees[path] = expected
    receipt = {'kind': 'DIAGNOSTIC ONLY; formal348/Preview/lint/native/device NOT RUN',
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
        require(env.get('TARGET_OUTCOME') == env.get('SCHEMA_OUTCOME') == 'success' and result['actual']['failures'] == 0, 'Original method/schema failure remains FAIL')
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
