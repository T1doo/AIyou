#!/usr/bin/env python3
"""One fixed heading observation; never formal D01 or device acceptance."""
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
PRODUCTION = 'd711fc9b07892631057d25107cbfd26f4b960bed'
TEST_OWNER = 'a3ecad2d565663d8b0ab636c33e1ff42e89ac9e2'
SOURCE = 'app/src/test/kotlin/com/t1doo/aiyou/LocalOriginalSearchPresentationTest.kt'
TEST_SHA256 = 'e8527848704841f039b9a47896869731af66f8030fb00c738237c2037c36fdd3'
MESSAGE = '[ui-heading-probe] d711 bounded intrinsic disclosure'
REF = 'refs/heads/codex/d03-short-audio-design-20261020'
CLASS = 'com.t1doo.aiyou.LocalOriginalSearchPresentationTest'
METHOD = 'emptyAdvancedFiltersUseRealKeyboardDisclosureWithoutQueryOrInputReplacement'
OUTPUT = 'build/memory-heading-diagnostic'
CAPTURES = 'app/build/reports/tests/testDebugUnitTest/original-search-disclosure'
FRAMES = ('01-browse-collapsed-double-font', '02-browse-expanded-double-font')
ALLOWED = {SOURCE, '.github/workflows/android.yml', 'scripts/diagnose-memory-heading.py',
           'scripts/tests/test_memory_heading_diagnostic.py', 'scripts/tests/test_companion_motion_diagnostic.py'}


def require(value, message):
    if not value:
        raise ValueError(message)


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, timeout=10).decode().strip()


def request(env, event):
    require(env.get('GITHUB_ACTIONS') == 'true' and env.get('GITHUB_EVENT_NAME') == 'push', 'Push CI only')
    require(env.get('GITHUB_REPOSITORY') == 'T1doo/AIyou' and event['repository']['private'] is False, 'Public original repo only')
    require(env.get('GITHUB_REF') == REF and event['head_commit']['message'] == MESSAGE, 'Wrong exact request')
    require(re.fullmatch('[0-9a-f]{40}', env.get('GITHUB_SHA', '')), 'Missing source SHA')
    require(re.fullmatch('[1-9][0-9]*', env.get('GITHUB_RUN_ID', '')), 'Missing run')
    require(env.get('GITHUB_RUN_ATTEMPT') == '1', 'Attempt1 only')


def admit(root, env):
    request(env, json.loads(Path(env['GITHUB_EVENT_PATH']).read_text()))
    require(git(root, 'rev-parse', 'HEAD') == env['GITHUB_SHA'] and not git(root, 'status', '--porcelain'), 'Exact clean checkout required')
    subprocess.run(['git', 'merge-base', '--is-ancestor', TEST_OWNER, 'HEAD'], cwd=root, timeout=10, check=True)
    require(set(git(root, 'diff', '--name-only', PRODUCTION, 'HEAD').splitlines()) <= ALLOWED, 'Unreviewed changes')
    blob = git(root, 'rev-parse', TEST_OWNER + ':' + SOURCE)
    require(git(root, 'rev-parse', 'HEAD:' + SOURCE) == blob and hashlib.sha256((root / SOURCE).read_bytes()).hexdigest() == TEST_SHA256, 'Changed fixed test')
    trees = {}
    for path in ('app/src/main', 'core'):
        trees[path] = git(root, 'rev-parse', PRODUCTION + ':' + path)
        require(git(root, 'rev-parse', 'HEAD:' + path) == trees[path], 'Changed production')
    result = {'kind': 'DIAGNOSTIC ONLY, not D01/device', 'source_sha': env['GITHUB_SHA'],
              'run_id': int(env['GITHUB_RUN_ID']), 'attempt': 1, 'production_owner': PRODUCTION,
              'production_trees': trees, 'test_owner': TEST_OWNER, 'test_blob': blob,
              'test_sha256': TEST_SHA256, 'method': CLASS + '.' + METHOD, 'started_ns': time.time_ns()}
    folder = root / OUTPUT
    folder.mkdir(parents=True, exist_ok=False)
    (folder / 'admission.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def fresh(path, started_ns, maximum):
    require(not path.is_symlink() and path.stat().st_mtime_ns >= started_ns and 0 < path.stat().st_size <= maximum, 'Missing/stale/unsafe evidence: ' + path.name)
    return path.read_bytes()


def single_case(folder, started_ns):
    paths = list(folder.glob('TEST-*.xml'))
    require(len(paths) == 1, 'Exactly one fresh XML required')
    raw = fresh(paths[0], started_ns, 4 * 1024 * 1024)
    suite = ET.fromstring(raw)
    cases = list(suite.iter('testcase'))
    require(int(suite.get('tests', '-1')) == len(cases) == 1, 'Empty/duplicate case')
    case = cases[0]
    require((case.get('classname'), case.get('name')) == (CLASS, METHOD), 'Wrong method')
    counts = {key: int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped')}
    require(all(counts[key] == len(case.findall({'failures': 'failure', 'errors': 'error', 'skipped': 'skipped'}[key])) for key in counts), 'XML counts disagree')
    require(counts['errors'] == counts['skipped'] == 0 and counts['failures'] in (0, 1), 'Error/skip cannot pass')
    return {'tests': 1, **counts, 'xml_sha256': hashlib.sha256(raw).hexdigest()}


def collect(root, env):
    folder = root / OUTPUT
    folder.mkdir(parents=True, exist_ok=True)
    result = {'kind': 'DIAGNOSTIC ONLY, not D01/device', 'source_sha': env.get('GITHUB_SHA'), 'status': 'FAIL', 'captures': []}
    try:
        receipt = json.loads((folder / 'admission.json').read_text())
        require(receipt['source_sha'] == env['GITHUB_SHA'] == git(root, 'rev-parse', 'HEAD') and
                receipt['run_id'] == int(env['GITHUB_RUN_ID']) and receipt['attempt'] == int(env['GITHUB_RUN_ATTEMPT']) == 1, 'Foreign admission')
        result['actual'] = single_case(root / 'app/build/test-results/testDebugUnitTest', receipt['started_ns'])
        for index, name in enumerate(FRAMES):
            path = root / CAPTURES / (name + '.json')
            if not path.exists():
                continue
            raw = fresh(path, receipt['started_ns'], 16384)
            data = json.loads(raw)
            require(data['source_sha'] == env['GITHUB_SHA'] and data['stage'] == 'BROWSE' and
                    type(data['queries']) is int and data['queries'] == 0 and data['fontScale'] == 2 and
                    data['disclosure'] == ('已收起' if index == 0 else '已展开'), 'Foreign/incorrect actual state')
            for key in ('headingParagraphWidthPx', 'headingParagraphHeightPx'):
                require(type(data[key]) in (float, int) and math.isfinite(data[key]) and data[key] > 0, 'Invalid actual geometry')
            require(type(data['headingLineCount']) is int and data['headingLineCount'] > 0, 'Invalid line count')
            for key in ('headingWidthOverflow', 'headingHeightOverflow'):
                require(type(data[key]) is bool, 'Missing actual overflow flag')
            for key in ('headingSizePx', 'headingConstraints', 'headingBoundsDp', 'disclosureBoundsDp'):
                require(isinstance(data[key], str) and 0 < len(data[key]) <= 1024, 'Missing actual bounds/constraints')
            png = fresh(root / CAPTURES / (name + '.png'), receipt['started_ns'], 4 * 1024 * 1024)
            require(png.startswith(b'\x89PNG\r\n\x1a\n'), 'Missing actual PNG')
            result['captures'].append({'file': name, 'measurement': data, 'json_sha256': hashlib.sha256(raw).hexdigest(), 'png_sha256': hashlib.sha256(png).hexdigest()})
        require(env.get('TARGET_OUTCOME') == env.get('SCHEMA_OUTCOME') == 'success' and result['actual']['failures'] == 0, 'Original method/schema failure remains FAIL')
        require(len(result['captures']) == 2, 'Success requires both fresh captures')
        require(all(c['measurement']['headingLineCount'] == 1 and not c['measurement']['headingWidthOverflow'] and
                    not c['measurement']['headingHeightOverflow'] for c in result['captures']), 'Observed overflow remains FAIL')
        result['status'] = 'PASS'
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, subprocess.SubprocessError) as error:
        result['reason'] = str(error)
    with (folder / 'RESULT.json').open('x') as output:
        json.dump(result, output, indent=2)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--admit', action='store_true')
    group.add_argument('--collect', action='store_true')
    args = parser.parse_args()
    result = admit(ROOT, os.environ) if args.admit else collect(ROOT, os.environ)
    print(json.dumps(result, indent=2))
    if args.collect and result['status'] != 'PASS':
        raise SystemExit(1)
