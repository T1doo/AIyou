#!/usr/bin/env python3
"""Isolated Compose JVM controls: original RED/current GREEN, then independent date.

Runs only in the existing licensed CI toolchain. A recognized negative control is
separate from the unchanged full UI gate; compilation errors/OOM/skips never pass.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path('app/src/test/kotlin/com/t1doo/aiyou/CurrentImageSearchPresentationTest.kt')
CASE = 'com.t1doo.aiyou.CurrentImageSearchPresentationTest'
METHOD = 'localEvidenceCardsUseCurrentSourceAndReturnWithoutChangingQuestion'
UNOBSERVED_METHOD = 'evidenceModeStartsAfterReadySearchWithoutAnyTestObserver'
BASELINE_SHA = '63db4d52c998ae65cf82c1b44bed6798cb11b1038e044a321b4285e569a8e2f7'
DATE_CASE = 'com.t1doo.aiyou.RecordEventDatePresentationTest'
RECYCLE_CASE = 'com.t1doo.aiyou.RecycleBinPresentationTest'
DATE_METHOD = 'narrowDoubleFontManualSetCorrectionAndClearEachRequireVisiblePreview'


class UnexpectedOutcome(ValueError):
    """A completed owned method failed; it remains a failed gate, never a pass."""
    def __init__(self, message, actual):
        super().__init__(message)
        self.actual = actual


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def reject_resource_failure(text):
    if any(marker in text for marker in ['OutOfMemoryError', 'Java heap space', 'daemon disappeared unexpectedly']):
        raise ValueError('Resource failure is separate from the isolated lazy-node control')


def inspect_xml(folder, phase, exit_code, started_ns, expected_class=CASE, expected_method=METHOD):
    paths = sorted(Path(folder).glob('TEST-*.xml'))
    cases, inventory = [], []
    for path in paths:
        if path.stat().st_mtime_ns < started_ns:
            raise ValueError('Stale XML cannot support a new method run')
        raw = path.read_bytes()
        suite = ET.fromstring(raw)
        actual = list(suite.iter('testcase'))
        if int(suite.get('tests', -1)) != len(actual):
            raise ValueError('JUnit count differs from actual cases')
        if int(suite.get('failures', 0)) != sum(c.find('failure') is not None for c in actual):
            raise ValueError('JUnit failure count differs from actual cases')
        if int(suite.get('errors', 0)) or int(suite.get('skipped', 0)):
            raise ValueError('Error/skip is not a recognized negative control')
        cases.extend(actual)
        inventory.append({'file': path.name, 'bytes': len(raw), 'sha256': digest(raw)})
    if len(cases) != 1 or cases[0].get('classname') != expected_class or cases[0].get('name') != expected_method:
        raise ValueError('Require exactly the requested actual method, without duplicates')
    case = cases[0]
    if case.find('error') is not None or case.find('skipped') is not None:
        raise ValueError('Method error/skip cannot be accepted')
    failures = list(case.findall('failure'))
    actual = {'phase': phase, 'exit': exit_code, 'class': expected_class, 'method': expected_method,
              'tests': 1, 'failures': len(failures), 'errors': 0, 'skipped': 0, 'xml': inventory}
    if phase == 'red':
        if expected_class != CASE or expected_method != METHOD:
            raise ValueError('Historical RED ownership is fixed to the original method')
        text = '\n'.join(ET.tostring(f, encoding='unicode') for f in failures)
        if exit_code != 1 or len(failures) != 1 or 'ComposeTimeoutException' not in text or \
                'CurrentImageSearchPresentationTest.kt:131' not in text or 'OutOfMemoryError' in text:
            raise ValueError('RED must be the isolated original lazy-node timeout at line131')
    elif phase == 'green':
        if exit_code == 1 and len(failures) == 1:
            raise UnexpectedOutcome('Owned GREEN method failed; another independent diagnosis may run, but the gate stays FAIL', actual)
        if exit_code != 0 or failures:
            raise ValueError('GREEN requires one actually passing method')
    else:
        raise ValueError('Unknown controlled phase')
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--expected-head', required=True)
    scope = parser.add_mutually_exclusive_group()
    scope.add_argument('--date-only', action='store_true', help='Diagnose date sublayers without repeating already verified evidence controls')
    scope.add_argument('--ui-failures-only', action='store_true', help='Individually run the four actual failed methods from full D01 run39101762817')
    scope.add_argument('--date-retry-only', action='store_true', help='Run only the remaining actual event-date retry failure')
    scope.add_argument('--recycle-only', action='store_true', help='Run only the actual remaining lazy recycle navigation failure from run39105360059')
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise SystemExit('Single-method Compose reproduction requires the existing licensed CI toolchain')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if head != args.expected_head or subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT):
        raise SystemExit('Require the exact clean reviewed checkout')
    if digest((ROOT / 'gradle/wrapper/gradle-wrapper.jar').read_bytes()) != \
            '3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98':
        raise SystemExit('Wrapper checksum differs')
    subprocess.run([sys.executable, 'scripts/check-sdk.py'], cwd=ROOT, check=True)
    output = ROOT / 'app/build/reports/ui-lazy-repro'
    output.mkdir(parents=True, exist_ok=False)
    report = {'status': 'FAIL', 'head': head, 'scope': 'one method per isolated Compose JVM invocation; original/current evidence controls, observer-free mode and independent date diagnosis; no device/Room/SQLCipher acceptance',
              'baseline_head': '269d62babac3795e61b1efe620a01c1751cd6b64', 'baseline_sha256': BASELINE_SHA, 'phases': []}
    if args.date_only:
        report['scope'] = 'date sublayers only, one actual method per isolated Compose JVM; evidence controls NOT RUN this invocation; no device/Room/SQLCipher acceptance'
    if args.ui_failures_only:
        report['scope'] = 'four actual failed UI methods from run39101762817 only, one requested method per isolated Compose JVM; not the full UI gate or device acceptance'
    if args.date_retry_only:
        report['scope'] = 'one remaining event-date retry method only; previously passing image/stale/date-layout controls NOT RUN; not full UI/device acceptance'
    if args.recycle_only:
        report['scope'] = 'one actual remaining recycle navigation method from run39105360059 only; other controlled methods NOT RUN; not full UI/device acceptance'
    source = ROOT / SOURCE
    fixed = source.read_bytes()
    baseline = (ROOT / 'checks/ui-lazy-repro/CurrentImageSearchPresentationTest.kt.source').read_bytes()
    if digest(baseline) != BASELINE_SHA or baseline == fixed:
        raise SystemExit('Verbatim original test fixture differs or no correction exists')
    report['fixed_sha256'] = digest(fixed)
    (output / 'fixed-test.kt.source').write_bytes(fixed)
    (output / 'original-test.kt.source').write_bytes(baseline)
    try:
        errors = []
        report['unexpected_outcomes'] = errors
        plan = [
                ('red', baseline, CASE, METHOD, 'red'),
                ('green', fixed, CASE, METHOD, 'green'),
                ('mode-no-observer', fixed, CASE, UNOBSERVED_METHOD, 'green'),
                ('date-fields-only', fixed, DATE_CASE, 'standaloneDoubleFontDateFieldsHaveFiniteLayout', 'green'),
                ('date-dialog-only', fixed, DATE_CASE, 'standaloneLoadedDoubleFontDateDialogHasFiniteLayout', 'green'),
                ('date-independent', fixed, DATE_CASE, DATE_METHOD, 'green')]
        if args.ui_failures_only:
            plan = [
                ('image-original', fixed, CASE, METHOD, 'green'),
                ('image-citations', fixed, CASE, 'syntheticSelectedAnswerCitationsOpenOriginalAndReturningRequiresNewConfirmation', 'green'),
                ('date-filter-retry', fixed, DATE_CASE, 'eventDateBasisCombinesKeywordTagFavoriteAndNeverMatchesUnknownCaptureDate', 'green'),
                ('date-stale-confirm', fixed, DATE_CASE, 'uncertainSaveRetainsSameIntentAndStaleConfirmCannotWriteAfterNavigation', 'green')]
        if args.date_retry_only:
            plan = [('date-filter-retry', fixed, DATE_CASE, 'eventDateBasisCombinesKeywordTagFavoriteAndNeverMatchesUnknownCaptureDate', 'green')]
        if args.recycle_only:
            plan = [('recycle-lazy-navigation', fixed, RECYCLE_CASE, 'actualMoveAndExplicitRestoreKeepFullOriginalTagsAndRequireEachConfirmation', 'green')]
        for label, raw, case, method, expectation in plan:
            if args.date_only and not label.startswith('date-'):
                continue
            source.write_bytes(raw)
            started = time.time_ns()
            command = ['timeout', '--signal=TERM', '--kill-after=10s', '180s',
                       'scripts/gradle.sh', ':app:testDebugUnitTest', '--tests', case + '.' + method]
            with (output / (label + '.stdout.log')).open('xb') as stdout, \
                    (output / (label + '.stderr.log')).open('xb') as stderr:
                process = subprocess.run(command, cwd=ROOT, stdout=stdout, stderr=stderr)
            test_source = {CASE: SOURCE,
                           DATE_CASE: Path('app/src/test/kotlin/com/t1doo/aiyou/RecordEventDatePresentationTest.kt'),
                           RECYCLE_CASE: Path('app/src/test/kotlin/com/t1doo/aiyou/RecycleBinPresentationTest.kt')}[case]
            state = {'label': label, 'command': command, 'exit': process.returncode,
                     'overlay_source_sha256': digest(raw), 'test_source': str(test_source),
                     'test_source_sha256': digest((ROOT / test_source).read_bytes())}
            report['phases'].append(state)
            results = ROOT / 'app/build/test-results/testDebugUnitTest'
            # Preserve actual results even when admission fails; never borrow a preceding run.
            snapshot = output / (label + '-xml')
            snapshot.mkdir()
            for path in sorted(results.glob('TEST-*.xml')):
                shutil.copyfile(path, snapshot / path.name)
            reject_resource_failure('\n'.join((output / (label + '.' + stream + '.log')).read_text(errors='replace')
                                             for stream in ['stdout', 'stderr']))
            try:
                state.update(inspect_xml(results, expectation, process.returncode, started, case, method))
            except UnexpectedOutcome as error:
                state.update(error.actual)
                state['error'] = str(error)
                errors.append(label + ': ' + str(error))
                report.setdefault('first_unexpected_phase', label)
        source.write_bytes(fixed)
        if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT):
            raise ValueError('Controlled run left checkout drift; full gate must not proceed')
        if errors:
            raise ValueError('; '.join(errors))
        report['status'] = 'PASS'
    except Exception as error:
        report.setdefault('first_unexpected_phase', report['phases'][-1]['label'] if report['phases'] else 'preflight')
        report['error'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        source.write_bytes(fixed)
        (output / 'RESULT.json').write_text(json.dumps(report, indent=2) + '\n')
        for phase in report['phases']:
            for stream in ['stdout', 'stderr']:
                path = output / (phase['label'] + '.' + stream + '.log')
                if path.exists():
                    print('UI_LAZY_REPRO_STREAM ' + phase['label'] + ' ' + stream, flush=True)
                    print(path.read_text(errors='replace'), flush=True)
            for path in sorted((output / (phase['label'] + '-xml')).glob('TEST-*.xml')):
                print('UI_LAZY_REPRO_XML ' + phase['label'] + ' ' + path.name, flush=True)
                print(path.read_text(), flush=True)
        print('UI_LAZY_SINGLE_METHOD_REPRO=' + json.dumps(report, separators=(',', ':')), flush=True)


if __name__ == '__main__':
    main()
