#!/usr/bin/env python3
"""Controlled native-component evidence, bound to actual production sources and real JUnit XML."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
project = root / 'checks/reminder-native'
receipt = project / 'build/controlled-native-inputs.json'
app_names = ['AndroidReminderDriver.kt', 'AndroidReminderRuntime.kt', 'ReminderReceivers.kt',
             'ReminderJobService.kt', 'ReminderOperationBridge.kt', 'ReminderVmRuntime.kt',
             'ReminderNativeEngine.kt', 'ReminderProcessOwner.kt', 'OriginalAudioOutput.kt', 'PlatformOriginalAudioOutput.kt']
ui_names = ['AppAccessController.kt', 'AppAccessPreferenceOperations.kt', 'FileAppAccessPreference.kt']

def inputs():
    files = []
    for directory in ['core/model/src/main', 'core/domain/src/main', 'core/data/src/main',
                      'checks/reminder-native/src']:
        files.extend(p for p in (root / directory).rglob('*') if p.is_file())
    files += [root / 'app/src/main/java/com/t1doo/aiyou/records' / n for n in app_names]
    files += [root / 'app/src/main/java/com/t1doo/aiyou/ui' / n for n in ui_names]
    files += list(project.glob('*.kts')) + list(project.glob('*.lockfile')) + list((project / 'locks').glob('*'))
    files += [root / 'gradle/libs.versions.toml', root / 'scripts/verify-reminder-native-results.py']
    hashes = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(files))}
    return {'sha': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
            'files': hashes, 'fingerprint': hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()}

def verify_sync():
    for directory, names in [('records', app_names), ('ui', ui_names)]:
        for name in names:
            assert (root / 'app/src/main/java/com/t1doo/aiyou' / directory / name).read_bytes() == (project / 'build/app-production' / name).read_bytes(), name

if sys.argv[1:] == ['--capture']:
    verify_sync()
    receipt.write_text(json.dumps(inputs(), indent=2) + '\n')
elif not sys.argv[1:]:
    state = json.loads(receipt.read_text())
    assert state == inputs(), 'Production/test inputs changed during/after controlled native tests'
    verify_sync()
    cases = {}
    for path in sorted((project / 'src/test/kotlin').rglob('*Test.kt')):
        text = path.read_text()
        name = 'com.t1doo.aiyou.records.' + path.stem
        cases[name] = set(re.findall(r'@Test(?:\([^)]*\))?\s+fun\s+(\w+)', text))
        assert cases[name], path
    actual = {}
    for path in (project / 'build/test-results/test').glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        assert all(int(suite.get(k, '0')) == 0 for k in ['failures', 'errors', 'skipped']), path
        rows = list(suite.iter('testcase'))
        assert len(rows) == int(suite.get('tests', '-1')) > 0
        for case in rows:
            assert not any(case.find(k) is not None for k in ['failure', 'error', 'skipped'])
            actual.setdefault(case.get('classname'), set()).add(case.get('name'))
    assert actual == cases, (actual, cases)
    export = root / 'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/15.json'
    generated = project / 'build/actual-room-export/com.t1doo.aiyou.data.RecordsDatabase/15.json'
    assert export.read_bytes() == generated.read_bytes(), 'Host KSP differs from actual committed Room v15'
    result = {'sha': state['sha'], 'fingerprint': state['fingerprint'], 'tests': sum(map(len, actual.values())),
              'classes': {k: len(v) for k, v in actual.items()}, 'device_permissions_alarms_notifications': 'NOT RUN',
              'sqlcipher_native_runtime': 'NOT RUN', 'unused_activity_and_resource_symbols': 'controlled shells'}
    (project / 'build/controlled-native-results.json').write_text(json.dumps(result, indent=2) + '\n')
    print('REMINDER_CONTROLLED_NATIVE=' + json.dumps(result, separators=(',', ':')))
else:
    raise SystemExit('Usage: verify-reminder-native-results.py [--capture]')
