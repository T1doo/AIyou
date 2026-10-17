#!/usr/bin/env python3
"""Verify the image-grid host evidence ZIP without extraction, SDK, network or dependencies."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile


def verify(archive, repo_root=None):
    with zipfile.ZipFile(archive) as bundle:
        names = bundle.namelist()
        if len(names) != len(set(names)) or any(Path(n).is_absolute() or '..' in Path(n).parts for n in names):
            raise ValueError('Duplicate or unsafe archive path')
        manifest = json.loads(bundle.read('manifest.json'))
        if manifest['format'] != 1:
            raise ValueError('Unsupported evidence format')
        if set(names) != set(manifest['files']) | {'manifest.json'}:
            raise ValueError('Archive file inventory mismatch')
        for name, expected in manifest['files'].items():
            data = bundle.read(name)
            if len(data) != expected['bytes'] or hashlib.sha256(data).hexdigest() != expected['sha256']:
                raise ValueError(f'File hash/size mismatch: {name}')
        modules = {'core:ai': 'core/ai', 'core:domain': 'core/domain',
                   'remote-text': 'checks/ai-protocol/remote-text'}
        for cohort in manifest['cohorts']:
            prefix = cohort['id'] + '/'
            source_prefix = cohort['source_prefix']
            sources = {name[len(source_prefix):]: bundle.read(name) for name in names if name.startswith(source_prefix)}
            if not sources:
                raise ValueError('Missing captured source inputs')
            fingerprint = hashlib.sha256()
            for relative, data in sorted(sources.items()):
                fingerprint.update(relative.encode() + b'\0')
                fingerprint.update(hashlib.sha256(data).digest())
                if repo_root is not None and cohort['id'] == 'closure':
                    if (repo_root / relative).read_bytes() != data:
                        raise ValueError(f'Final checkout input differs: {relative}')
            digest = fingerprint.hexdigest()
            report = json.loads(bundle.read(prefix + 'verified-report.json'))
            methods = json.loads(bundle.read(prefix + 'methods.json'))
            times = json.loads(bundle.read(prefix + 'file-times.json'))
            if report['status'] != 'PASS' or report['source_sha256'] != digest or cohort['source_sha256'] != digest:
                raise ValueError(f'Source fingerprint mismatch: {cohort["id"]}')
            if {r['module'] for r in report['results']} != set(modules):
                raise ValueError('Module inventory mismatch')
            total = 0
            for result in report['results']:
                module = result['module']
                label = module.split(':')[-1]
                capture = prefix + 'captures/' + label + '.json'
                if json.loads(bundle.read(capture))['source_sha256'] != digest:
                    raise ValueError(f'Input capture mismatch: {capture}')
                expected = set()
                for relative, data in sources.items():
                    if relative.startswith(modules[module] + '/src/test/kotlin/') and relative.endswith('.kt'):
                        text = data.decode()
                        package = re.search(r'^package\s+([\w.]+)', text, re.MULTILINE)
                        test_class = re.search(r'\bclass\s+(\w+)', text)
                        for method in re.findall(r'@Test(?:\s*\([^)]*\))?\s+fun\s+(\w+)\s*\(', text):
                            key = (package[1] + '.' + test_class[1], method)
                            if key in expected:
                                raise ValueError('Duplicate source test method')
                            expected.add(key)
                actual = set()
                xml_prefix = prefix + 'xml/' + label + '/'
                xml_names = {n for n in names if n.startswith(xml_prefix)}
                if xml_names != {xml_prefix + Path(x['path']).name for x in result['xml']}:
                    raise ValueError('Raw XML inventory mismatch')
                for evidence in result['xml']:
                    xml_name = xml_prefix + Path(evidence['path']).name
                    data = bundle.read(xml_name)
                    if hashlib.sha256(data).hexdigest() != evidence['sha256']:
                        raise ValueError(f'Report/raw XML hash mismatch: {xml_name}')
                    suite = ET.fromstring(data)
                    cases = suite.findall('testcase')
                    if not cases or int(suite.get('tests', '-1')) != len(cases) or evidence['tests'] != len(cases):
                        raise ValueError('Invalid/nonempty test count required')
                    if any(int(suite.get(k, '0')) for k in ('failures', 'errors', 'skipped')):
                        raise ValueError('Nonpassing raw suite')
                    if times[xml_name] < times[capture]:
                        raise ValueError('Recorded XML predates input capture')
                    for case in cases:
                        key = (case.get('classname'), case.get('name'))
                        if key in actual or any(case.find(k) is not None for k in ('failure', 'error', 'skipped')):
                            raise ValueError('Duplicate or nonpassing actual method')
                        actual.add(key)
                if actual != expected or actual != set(map(tuple, result['methods'])) or actual != set(map(tuple, methods[module])):
                    raise ValueError(f'Exact source/actual/report method mismatch: {module}')
                if len(actual) != result['tests'] or any(result[k] for k in ('failures', 'errors', 'skipped')):
                    raise ValueError('Verified result counters mismatch')
                total += len(actual)
            if total != cohort['tests']:
                raise ValueError('Cohort total mismatch')
            print(f'PASS {cohort["id"]}: {total} methods; raw XML, captures, source fingerprint and reports agree; {digest}')
        historical = 0
        historical_methods = set()
        for name in names:
            if name.startswith('historical-queue/xml/'):
                suite = ET.fromstring(bundle.read(name))
                cases = suite.findall('testcase')
                if int(suite.get('tests', '-1')) != len(cases) or any(int(suite.get(k, '0')) for k in ('failures', 'errors', 'skipped')):
                    raise ValueError('Invalid historical queue XML')
                if any(any(c.find(k) is not None for k in ('failure', 'error', 'skipped')) for c in cases):
                    raise ValueError('Nonpassing historical queue case')
                for case in cases:
                    key = (case.get('classname'), case.get('name'))
                    if key in historical_methods:
                        raise ValueError('Duplicate historical queue method')
                    historical_methods.add(key)
                historical += len(cases)
        if historical != 149 or len(historical_methods) != 149:
            raise ValueError('Historical queue count mismatch')
        python_log = bundle.read('historical-python/tests.log').decode()
        if re.findall(r'Ran (\d+) tests', python_log) != ['151'] or python_log.strip().splitlines()[-1] != 'OK':
            raise ValueError('Historical Python log mismatch')
        print('PASS historical evidence: queue 149; Python 151; neither is new coverage in this closure')
        if repo_root is not None:
            print('PASS closure source inputs match the current checkout byte for byte')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--repo-root', type=Path)
    args = parser.parse_args()
    try:
        verify(args.archive, args.repo_root)
    except (ValueError, KeyError, IndexError, OSError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f'FAIL: {error}', file=sys.stderr)
        raise SystemExit(1)
