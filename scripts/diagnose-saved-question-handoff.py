#!/usr/bin/env python3
"""Fixed original saved-question handoff diagnosis. Never full/device acceptance."""
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
EXECUTION_APPROVED = True
BASE = '385463c5b168b0c66721a998e0c4b9f0b03c38f4'
REF = 'refs/heads/diagnostic/saved-question-handoff-20261102'
MESSAGE = '[saved-question-handoff] 385463c exactly one method'
WORKFLOW = '.github/workflows/saved-question-handoff.yml'
SOURCES = {'app/src/test/kotlin/com/t1doo/aiyou/SelectedQuestionConsentPresentationTest.kt': 'f80f5687a6bc53658468f5a86aa80c34f8bb9d081850b23447db75ad3fa32b2b'}
CASES = {('com.t1doo.aiyou.SelectedQuestionConsentPresentationTest', 'actualProductionQuestionInputAndConnectionAreSeparateFromRecordSavingAndPackageGateCannotBeBypassed')}
ALLOWED = set(SOURCES) | {WORKFLOW, 'scripts/diagnose-saved-question-handoff.py', 'scripts/tests/test_saved_question_handoff.py'}
OUTPUT = 'build/saved-question-handoff'
REPORTS = 'app/build/test-results/testDebugUnitTest'
CAPTURES = 'app/build/reports/tests/testDebugUnitTest/saved-question-handoff'
MAX_ARTIFACT = 8 * 1024 * 1024


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, timeout=10).decode().strip()


def request(env, event):
    require(EXECUTION_APPROVED, 'Execution remains disabled')
    require(env.get('GITHUB_ACTIONS') == 'true' and env.get('GITHUB_EVENT_NAME') == 'push', 'Push CI only')
    require(env.get('GITHUB_REPOSITORY') == 'T1doo/AIyou' and event['repository']['private'] is False, 'Original public repository only')
    require(env.get('GITHUB_REF') == REF and event['head_commit']['message'] == MESSAGE, 'Wrong exact request')
    require(env.get('GITHUB_RUN_ATTEMPT') == '1', 'Attempt1 only')
    require(re.fullmatch('[0-9a-f]{40}', env.get('GITHUB_SHA', '')), 'Missing source SHA')
    require(re.fullmatch('[1-9][0-9]*', env.get('GITHUB_RUN_ID', '')), 'Missing run')


def source_contract(root):
    require(not git(root, 'status', '--porcelain'), 'Clean checkout required')
    subprocess.run(['git', 'merge-base', '--is-ancestor', BASE, 'HEAD'], cwd=root, check=True, timeout=10)
    require(set(git(root, 'diff', '--name-only', BASE, 'HEAD').splitlines()) <= ALLOWED, 'Unreviewed changes')
    for path in ('app/src/main', 'core'):
        require(git(root, 'rev-parse', BASE + ':' + path) == git(root, 'rev-parse', 'HEAD:' + path), 'Changed production')
    for path, digest in SOURCES.items():
        require(hashlib.sha256((root / path).read_bytes()).hexdigest() == digest, 'Changed fixed test')


def admit(root, env):
    request(env, json.loads(Path(env['GITHUB_EVENT_PATH']).read_text()))
    require(git(root, 'rev-parse', 'HEAD') == env['GITHUB_SHA'], 'Wrong checkout')
    source_contract(root)
    require(git(root, 'rev-parse', 'HEAD^') == BASE, 'One fixed admission commit only')
    folder = root / OUTPUT
    folder.mkdir(parents=True, exist_ok=False)
    data = {'kind': 'DIAGNOSTIC ONLY, not D01/device', 'source_sha': env['GITHUB_SHA'],
            'run_id': int(env['GITHUB_RUN_ID']), 'attempt': 1, 'production': BASE,
            'tests': SOURCES, 'started_ns': time.time_ns()}
    (folder / 'admission.json').write_text(json.dumps(data, indent=2) + '\n')
    return data


def fresh(root, path, started_ns, maximum):
    require(not path.is_symlink() and path.resolve().is_relative_to(root.resolve()), 'Unsafe evidence')
    require(path.is_file() and path.stat().st_mtime_ns >= started_ns and 0 < path.stat().st_size <= maximum,
            'Missing, stale or oversized evidence')
    return path.read_bytes()


def exact_cases(root, started_ns):
    folder = root / REPORTS
    expected = {'TEST-' + name + '.xml' for name, _ in CASES}
    require({p.name for p in folder.glob('TEST-*.xml')} == expected, 'Wrong XML inventory')
    rows = []
    for name in sorted(expected):
        suite = ET.fromstring(fresh(root, folder / name, started_ns, MAX_ARTIFACT))
        cases = list(suite.iter('testcase'))
        require(int(suite.get('tests', '-1')) == len(cases), 'XML test count mismatch')
        for plural, tag in [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]:
            require(int(suite.get(plural, '0')) == sum(len(c.findall(tag)) for c in cases), 'XML status mismatch')
        for case in cases:
            rows.append({'class': case.get('classname'), 'method': case.get('name'),
                         'failures': len(case.findall('failure')), 'errors': len(case.findall('error')),
                         'skipped': len(case.findall('skipped'))})
    require(len(rows) == len(CASES) and {(r['class'], r['method']) for r in rows} == CASES, 'Empty, duplicate or foreign method')
    return rows


def collect(root, env):
    folder = root / OUTPUT
    destination = folder / 'evidence'
    destination.mkdir(parents=True, exist_ok=False)
    result = {'kind': 'DIAGNOSTIC ONLY, not D01/device', 'source_sha': env.get('GITHUB_SHA'),
              'status': 'FAIL', 'files': [], 'evidence_issues': []}
    try:
        receipt = json.loads((folder / 'admission.json').read_text())
        require(receipt['source_sha'] == env['GITHUB_SHA'] == git(root, 'rev-parse', 'HEAD') and
                receipt['run_id'] == int(env['GITHUB_RUN_ID']) and receipt['attempt'] == int(env['GITHUB_RUN_ATTEMPT']) == 1,
                'Foreign admission')
        paths = [folder / 'admission.json']
        paths += [root / REPORTS / ('TEST-' + name + '.xml') for name in sorted({c for c, _ in CASES})]
        paths += [root / CAPTURES / n for n in ('before-scroll.json', 'after-scroll.json')]
        paths += [folder / 'gradle.log']
        total = 0
        for path in paths:
            try:
                # Preserve XML/font/PNGs before the log. Reject rather than truncate oversized originals.
                # Reserve 64 KiB for metadata. Every fixed file remains independently accountable.
                data = fresh(root, path, receipt['started_ns'], MAX_ARTIFACT - 65536)
                require(total + len(data) <= MAX_ARTIFACT - 65536, 'Artifact cap exceeded')
                (destination / path.name).write_bytes(data)
                total += len(data)
                result['files'].append({'name': path.name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
            except (OSError, ValueError) as error:
                result['evidence_issues'].append({'name': path.name, 'reason': str(error) if isinstance(error, ValueError) else type(error).__name__})
        result['actual'] = exact_cases(root, receipt['started_ns'])
        xml_path=root/REPORTS/('TEST-'+next(iter(CASES))[0]+'.xml')
        suite=ET.fromstring(fresh(root,xml_path,receipt['started_ns'],MAX_ARTIFACT))
        streams='\n'.join(value.text or '' for tag in ('system-err','system-out') for value in suite.iter(tag))
        phases=[line.strip().removeprefix('QUESTION_HISTORY_PHASE ') for line in streams.splitlines() if line.strip().startswith('QUESTION_HISTORY_PHASE ')]
        require(len(phases)<=64,'Unbounded phase inventory')
        result['phases']=phases
        result['scope']='One original method; geometry is sampled only before/after, not continuous consumption proof'
        require(env.get('TARGET_OUTCOME') == env.get('SCHEMA_OUTCOME') == 'success' and
                all(r['failures'] == r['errors'] == r['skipped'] == 0 for r in result['actual']), 'Original failure remains FAIL')
        expected=['opened','before-fetch','after-fetch','before-scroll','after-scroll','before-display','after-display','before-click','after-click','selected','switched','use-clicked']
        require(phases==expected,'Missing exact original handoff stages')
        for name,stage in [('before-scroll.json','before-scroll'),('after-scroll.json','after-scroll')]:
            sample=json.loads(fresh(root,root/CAPTURES/name,receipt['started_ns'],16384))
            require(sample['source_sha']==env['GITHUB_SHA'] and sample['stage']==stage and sample['status']=='OBSERVED', 'Missing same-source geometry')
        require(not result['evidence_issues'], 'Missing or rejected evidence remains FAIL')
        source_contract(root)
        result['status'] = 'PASS'
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, subprocess.SubprocessError) as error:
        # Fixed reasons only: no environment, event body, credentials or user data in a diagnostic summary.
        result['reason'] = str(error) if isinstance(error, ValueError) else type(error).__name__
    (destination / 'RESULT.json').write_text(json.dumps(result, indent=2) + '\n')
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
