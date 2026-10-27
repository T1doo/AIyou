#!/usr/bin/env python3
"""Compile only: fixed platform-only D08 probe using the existing licensed CI SDK.

No Maven/Gradle, downloads, key generation, HOME fallback, adb or device operations.
Input hashes record integrity of already approved runner tools, not new trust roots.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / 'checks/device-recovery/android'
PACKAGE = 'com.t1doo.aiyou.recoveryprobe'
ANDROID = '{http://schemas.android.com/apk/res/android}'
VERSION = '958ab-one-recovery'
NAMES = ('ExportCheck', 'FixtureDocuments', 'RecoveryInstrumentation')
SOURCES = tuple(PROJECT / 'src/main/java/com/t1doo/aiyou/recoveryprobe' / (n + '.java') for n in NAMES)
MANIFEST = PROJECT / 'src/main/AndroidManifest.xml'
OUTPUT = PROJECT / 'build/outputs/apk/debug/recovery-probe-debug.apk'
REPORT = PROJECT / 'build/reports/sdk-probe-build.json'
LICENSE = '24333f8a63b6825ea9c5514f83c2829b004d1fee'
SECONDS = 180
COMPILE_ONLY_WORKFLOW_STEPS = "      - name: Compile and verify the platform-only recovery probe (all device execution NOT RUN)\n        id: sdk_probe\n        if: github.event_name == 'push' && github.repository == 'T1doo/AIyou' && github.event.repository.private == false && github.run_attempt == 1\n        run: timeout --signal=KILL 180s python3 scripts/d08_sdk_probe.py --ci-compile-only\n      - name: Preserve SDK-only probe compilation and failure evidence\n        if: always() && steps.sdk_probe.outcome != 'skipped'\n        uses: ./.github/actions/preserve-stage\n        with:\n          stage: recovery-probe\n          outcome: ${{ steps.sdk_probe.outcome }}\n          name: d01-stage-recovery-probe\n          paths: |\n            checks/device-recovery/android/build/reports/sdk-probe-build.json\n            checks/device-recovery/android/build/outputs/apk/debug/recovery-probe-debug.apk\n"


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''): h.update(b)
    return h.hexdigest()


def regular(path):
    s = path.lstat()
    if not stat.S_ISREG(s.st_mode) or s.st_nlink != 1:
        raise ValueError('Required regular non-linked input absent')
    return s


def confined(path, root):
    """No HOME/symlink fallback for workspace signing material or owned outputs."""
    parts = path.relative_to(root).parts
    current = root
    for part in parts:
        current = current / part
        if current.is_symlink(): raise ValueError('Refuse redirected workspace path')
    return path


def identity(path):
    s = regular(path)
    return {'bytes': s.st_size, 'sha256': digest(path), 'device': s.st_dev, 'inode': s.st_ino,
            'mtime_ns': s.st_mtime_ns}


def properties(path):
    regular(path)
    return dict((k.strip(), v.strip().strip('"')) for line in path.read_text().splitlines()
                if '=' in line and not line.startswith('#') for k, v in [line.split('=', 1)])


def sdk_inputs(sdk, jdk):
    """Same pinned/previously accepted SDK baseline as check-sdk; never installs."""
    platform = sdk / 'platforms/android-37.0'
    tools = sdk / 'build-tools/36.0.0'
    for folder, version in [(platform, '2'), (tools, '36.0.0')]:
        if properties(folder / 'source.properties').get('Pkg.Revision') != version:
            raise ValueError('Fixed SDK package revision missing/different')
    accepted = sdk / 'licenses/android-sdk-license'; regular(accepted)
    if LICENSE not in accepted.read_text().splitlines():
        raise ValueError('Matching existing SDK acceptance absent; never accept new terms')
    release = properties(jdk / 'release')
    if release.get('JAVA_RUNTIME_VERSION') != '17.0.17+10' or 'Adoptium' not in release.get('IMPLEMENTOR', ''):
        raise ValueError('Previously approved Temurin 17.0.17+10 required')
    files = {'javac': jdk / 'bin/javac', 'java': jdk / 'bin/java', 'jdk_modules': jdk / 'lib/modules',
             'jmod': jdk / 'bin/jmod', 'jlink': jdk / 'bin/jlink', 'jrt_fs': jdk / 'lib/jrt-fs.jar',
             'android_core': platform / 'core-for-system-modules.jar',
             'jdk_release': jdk / 'release', 'android_jar': platform / 'android.jar',
             'platform_properties': platform / 'source.properties', 'tools_properties': tools / 'source.properties',
             'sdk_license': accepted, 'aapt2': tools / 'aapt2', 'zipalign': tools / 'zipalign',
             'd8': tools / 'lib/d8.jar', 'apksigner': tools / 'lib/apksigner.jar'}
    return files, {name: identity(path) for name, path in files.items()}


def android_system_image(files, work, run):
    """AOSP JdkImageTransformDelegate sequence, using only the existing SDK/JDK.

    The descriptor alone uses --system none with the Android java.base patch.
    Probe sources compile against the resulting image, never the host java.base.
    Reference: platform/tools/base JdkImageTransformDelegate.kt, blob ac9dd004.
    """
    core = files['android_core']
    with zipfile.ZipFile(core) as source:
        entries = source.infolist()
        names = [e.filename for e in entries]
        if len(names) != len(set(names)) or len(names) > 100000 or \
                sum(e.file_size for e in entries) > 128 * 1024 * 1024 or \
                any(n.startswith('/') or '\\' in n or '..' in n.split('/') for n in names) or \
                'module-info.class' in names or \
                not {'java/lang/Object.class', 'java/lang/String.class'}.issubset(names):
            raise ValueError('Invalid Android core system-module archive')
        packages = sorted({n.rsplit('/', 1)[0].replace('/', '.') for n in names if n.endswith('.class') and '/' in n})
        if not packages or any(not re.fullmatch(r'[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*', p, re.ASCII) for p in packages):
            raise ValueError('Invalid Android core package name')
        descriptor = work/'module-info.java'
        descriptor.write_text('module java.base {\n'+''.join(' exports '+p+';\n' for p in packages)+'}\n')
        descriptor_dir = work/'descriptor'; descriptor_dir.mkdir()
        run('android-module-descriptor', [files['javac'], '-proc:none', '-implicit:none', '--system', 'none',
            '--patch-module', 'java.base='+str(core), '-source', '17', '-target', '17', '-d', descriptor_dir, descriptor])
        descriptor_class = descriptor_dir/'module-info.class'; regular(descriptor_class)
        modular = work/'android-core-module.jar'
        with zipfile.ZipFile(modular, 'x', compression=zipfile.ZIP_STORED) as output:
            output.writestr('module-info.class', descriptor_class.read_bytes())
            for entry in entries:
                output.writestr(entry, source.read(entry))
    jmods = work/'android-jmods'; jmods.mkdir()
    run('android-jmod', [files['jmod'], 'create', '--module-version', '17.0.17', '--target-platform', 'android',
        '--class-path', modular, jmods/'java.base.jmod'])
    image = work/'android-system'
    run('android-jlink', [files['jlink'], '--module-path', jmods, '--add-modules', 'java.base',
        '--output', image, '--disable-plugin', 'system-modules'])
    regular(image/'lib/modules')
    shutil.copyfile(files['jrt_fs'], image/'lib/jrt-fs.jar')
    if digest(image/'lib/jrt-fs.jar') != digest(files['jrt_fs']):
        raise ValueError('JDK image filesystem provider copy changed')
    return image


def probe_javac_args(files, image, classes, sources):
    return [files['javac'], '-encoding', 'UTF-8', '-proc:none', '-implicit:none', '--system', image,
            '-source', '17', '-target', '17', '-classpath', files['android_jar'], '-d', classes, *sources]


def generate_manifest(raw):
    if b'<!DOCTYPE' in raw or b'<!ENTITY' in raw: raise ValueError('Unexpected XML entity declaration')
    root = ET.fromstring(raw)
    if root.tag != 'manifest' or set(root.attrib) or root.findall('uses-permission') or root.find('uses-sdk') is not None:
        raise ValueError('Unexpected source manifest or permission changes')
    app = root.find('application'); probe = root.find('instrumentation')
    if len(root.findall('application')) != 1 or len(root.findall('instrumentation')) != 1 or \
            app is None or probe is None or app.get(ANDROID+'testOnly') != 'true' or \
            app.get(ANDROID+'allowBackup') != 'false' or app.get(ANDROID+'usesCleartextTraffic') != 'false' or \
            probe.get(ANDROID+'name') != '.RecoveryInstrumentation' or probe.get(ANDROID+'targetPackage') != PACKAGE:
        raise ValueError('Original test-only probe identities required')
    providers = app.findall('provider')
    if len(providers) != 1 or providers[0].get(ANDROID+'name') != '.FixtureDocuments' or \
            providers[0].get(ANDROID+'authorities') != PACKAGE+'.documents' or \
            providers[0].get(ANDROID+'permission') != 'android.permission.MANAGE_DOCUMENTS':
        raise ValueError('Exact existing SAF provider required')
    # Preserve the original AGP debug variant: the owned evidence collector requires run-as.
    app.set(ANDROID+'debuggable', 'true')
    root.set('package', PACKAGE); root.set(ANDROID+'versionCode', '1'); root.set(ANDROID+'versionName', VERSION)
    ET.SubElement(root, 'uses-sdk', {ANDROID+'minSdkVersion': '26', ANDROID+'targetSdkVersion': '37'})
    ET.register_namespace('android', ANDROID[1:-1])
    return ET.tostring(root, encoding='utf-8', xml_declaration=True)


def dex_classes(data):
    def u32(at): return struct.unpack_from('<I', data, at)[0]
    if len(data) < 112 or data[:4] != b'dex\n' or data[7] != 0 or u32(32) != len(data) or \
            u32(36) != 112 or u32(40) != 0x12345678 or u32(8) != zlib.adler32(data[12:]) & 0xffffffff or \
            data[12:32] != hashlib.sha1(data[32:]).digest(): raise ValueError('Invalid actual DEX')
    strings, string_at, types, type_at, count, at = u32(56), u32(60), u32(64), u32(68), u32(96), u32(100)
    if not 3 <= count <= 512 or strings > 50000 or types > 50000 or \
            string_at+strings*4 > len(data) or type_at+types*4 > len(data) or at+count*32 > len(data):
        raise ValueError('DEX inventory bounds')
    found = []
    for i in range(count):
        t = u32(at+i*32)
        if t >= types: raise ValueError('Invalid DEX type')
        s = u32(type_at+t*4)
        if s >= strings: raise ValueError('Invalid DEX string')
        start = u32(string_at+s*4)
        for k in range(5):
            byte = data[start]; start += 1
            if byte < 128: break
        else: raise ValueError('Invalid DEX string length')
        end = data.index(0, start); name = data[start:end].decode('ascii')
        if not name.startswith('Lcom/t1doo/aiyou/recoveryprobe/') or not name.endswith(';'):
            raise ValueError('Unexpected third-party defined DEX class')
        found.append(name)
    required = {'Lcom/t1doo/aiyou/recoveryprobe/'+n+';' for n in NAMES}
    if len(found) != len(set(found)) or not required.issubset(found): raise ValueError('Missing/duplicate actual probe class')
    return sorted(found)


def certificate(text):
    matches = re.findall(r'^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$', text, re.M)
    if len(matches) != 1 or re.search(r'^Signer #[2-9]', text, re.M): raise ValueError('One actual signer required')
    return matches[0]


def verify_binary_manifest(badging, tree):
    # Locked Build Tools 36 aapt2 emits minSdkVersion and decoded XML values.
    # Bind attributes to their actual element, not a substring on another node.
    if len(re.findall(r"^package: name='"+re.escape(PACKAGE)+r"' versionCode='1' versionName='"+re.escape(VERSION)+r"'(?: .*)?$",badging,re.M)) != 1 or \
            badging.splitlines().count("minSdkVersion:'26'") != 1 or \
            badging.splitlines().count("targetSdkVersion:'37'") != 1:
        raise ValueError('Actual binary manifest identity/permission mismatch')
    nodes=[];stack=[]
    for line in tree.splitlines():
        element=re.fullmatch(r'( *)E: ([\w-]+) \(line=\d+\)',line)
        attribute=re.fullmatch(r'( *)A: ([^=(]+)(?:\(0x[0-9a-f]+\))?=(.*)',line)
        if element:
            indent=len(element[1]);name=element[2]
            while stack and stack[-1][0]>=indent:stack.pop()
            node={'path':tuple(n[1]['path'][-1] for n in stack)+(name,), 'attributes':{}}
            if name.startswith('uses-permission'):raise ValueError('Probe permission declaration forbidden')
            nodes.append(node);stack.append((indent,node))
        elif attribute:
            if not stack or len(attribute[1])<=stack[-1][0] or attribute[2] in stack[-1][1]['attributes']:
                raise ValueError('Ambiguous binary manifest attribute')
            stack[-1][1]['attributes'][attribute[2]]=attribute[3]
        elif line and not re.fullmatch(r' *N: android=http://schemas.android.com/apk/res/android \(line=\d+\)',line):
            raise ValueError('Unexpected fixed-tool binary manifest output')
    ns='http://schemas.android.com/apk/res/android:'
    def require(path,expected):
        matches=[n for n in nodes if n['path']==tuple(path.split('/'))]
        if len(matches)!=1 or any(matches[0]['attributes'].get(k)!=v for k,v in expected.items()):
            raise ValueError('Actual binary manifest element identity/permission mismatch: '+path)
    def string(value):return json.dumps(value)+' (Raw: '+json.dumps(value)+')'
    require('manifest',{'package':string(PACKAGE),ns+'versionCode':'1',ns+'versionName':string(VERSION)})
    require('manifest/queries',{})
    require('manifest/application',{ns+'testOnly':'true',ns+'debuggable':'true',ns+'allowBackup':'false',ns+'usesCleartextTraffic':'false'})
    require('manifest/application/provider',{ns+'name':string('.FixtureDocuments'),ns+'authorities':string(PACKAGE+'.documents'),
        ns+'permission':string('android.permission.MANAGE_DOCUMENTS'),ns+'exported':'true',ns+'grantUriPermissions':'true'})
    require('manifest/instrumentation',{ns+'name':string('.RecoveryInstrumentation'),ns+'targetPackage':string(PACKAGE)})
    require('manifest/uses-sdk',{ns+'minSdkVersion':'26',ns+'targetSdkVersion':'37'})


def verify_apk(path, badging, tree):
    verify_binary_manifest(badging, tree)
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if len(names) != len(set(names)) or z.testzip() is not None or names.count('classes.dex') != 1 or \
                any(n.startswith(('lib/', 'assets/')) or '..' in n.split('/') or n.startswith('/') for n in names):
            raise ValueError('Unexpected/duplicate actual APK contents')
        return dex_classes(z.read('classes.dex'))


def ci_admission(env):
    if env.get('GITHUB_ACTIONS') != 'true' or env.get('GITHUB_REPOSITORY') != 'T1doo/AIyou' or \
            env.get('GITHUB_EVENT_NAME') != 'push' or env.get('GITHUB_RUN_ATTEMPT') != '1' or \
            env.get('GITHUB_JOB') != 'd01' or env.get('RUNNER_ENVIRONMENT') != 'github-hosted' or \
            env.get('RUNNER_OS') != 'Linux' or env.get('RUNNER_ARCH') != 'X64' or \
            not env.get('GITHUB_REF', '').startswith('refs/heads/codex/') or \
            not re.fullmatch('[0-9a-f]{40}', env.get('GITHUB_SHA', '')):
        raise ValueError('Only the existing formal public CI push attempt1 compile stage is admitted')
    event = json.loads(Path(env['GITHUB_EVENT_PATH']).read_text())
    if event.get('repository', {}).get('full_name') != 'T1doo/AIyou' or event.get('repository', {}).get('private') is not False:
        raise ValueError('Public original repository required')


def bounded_command(args, deadline, clock=time.monotonic, execute=subprocess.run, stdin=None):
    remaining = deadline-clock()
    if remaining <= 0: raise TimeoutError('Whole probe build deadline expired before command')
    try:
        result = execute([str(x) for x in args], cwd=ROOT, capture_output=True, text=True, timeout=remaining, input=stdin)
    except subprocess.TimeoutExpired:
        # Never stringify an exception carrying command arguments or stdin.
        raise TimeoutError('Probe command exceeded the shared build deadline') from None
    # A late successful return is still outside the shared build deadline.
    if clock() >= deadline: raise TimeoutError('Whole probe build deadline exceeded by command')
    return result


def compile_only():
    start = time.monotonic(); deadline = start+SECONDS
    ci_admission(os.environ)
    if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True, timeout=5).strip() != os.environ['GITHUB_SHA']:
        raise ValueError('Exact source checkout required')
    confined(REPORT, ROOT); REPORT.parent.mkdir(parents=True, exist_ok=True)
    report = {'source_sha': os.environ['GITHUB_SHA'], 'run_id': os.environ['GITHUB_RUN_ID'], 'run_attempt': 1,
              'status': 'FAIL', 'scope': 'SDK-only compile/sign/inspect; ALL DEVICE EXECUTION NOT RUN',
              'commands': [], 'new_keys': 0, 'network_requests': 0}
    REPORT.write_text(json.dumps(report, indent=2)+'\n')
    try:
        _compile_with_sdk(report, start, deadline)
    except BaseException as failure:
        report.update(status='FAIL', error_type=type(failure).__name__, error=str(failure)[-6500:],
                      elapsed_seconds=time.monotonic()-start)
        raise
    finally:
        REPORT.write_text(json.dumps(report, indent=2)+'\n')


def _compile_with_sdk(report, start, deadline):
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT']).resolve()
    jdk = Path(os.environ['JAVA_HOME']).resolve()
    files, snapshots = sdk_inputs(sdk, jdk)
    source_inputs = (*SOURCES, MANIFEST, ROOT/'scripts/d08_sdk_probe.py')
    inputs = {}
    for path in source_inputs:
        confined(path, ROOT)
        rel = str(path.relative_to(ROOT)); raw = path.read_bytes(); regular(path)
        if raw != subprocess.check_output(['git', 'show', 'HEAD:'+rel], cwd=ROOT, timeout=5): raise ValueError('Modified source input')
        inputs[rel] = hashlib.sha256(raw).hexdigest()
    # Reuse ONLY the debug identity already created by this job's normal app assemble.
    # Never search HOME, create a key, export it or include its bytes/hash in evidence.
    key = confined(ROOT/'.toolchains/android-user-home/debug.keystore', ROOT); key_stat = regular(key)
    if key_stat.st_uid != os.getuid(): raise ValueError('Existing job-owned workspace debug key required')
    key_identity = (key_stat.st_dev, key_stat.st_ino, key_stat.st_size, key_stat.st_mtime_ns)
    app = ROOT/'app/build/outputs/apk/debug/app-debug.apk'; app_identity = identity(app)
    permissions = json.loads((ROOT/'app/build/reports/record-ai-permissions.json').read_text())
    if permissions.get('source_sha') != os.environ['GITHUB_SHA'] or permissions.get('internet_permission') is not False or \
            permissions.get('sha256') != app_identity['sha256']: raise ValueError('Same-job verified app identity required')
    confined(OUTPUT, ROOT); confined(REPORT, ROOT)
    if OUTPUT.exists() or OUTPUT.is_symlink(): raise ValueError('Refuse to replace an existing probe APK')
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    report.update({'source_sha': os.environ['GITHUB_SHA'], 'run_id': os.environ['GITHUB_RUN_ID'], 'run_attempt': 1,
              'status': 'FAIL', 'scope': 'SDK-only compile/sign/inspect; ALL DEVICE EXECUTION NOT RUN',
              'sdk_trust_basis': 'Existing approved CI SDK/JDK installation and matching terms; observed hashes are integrity, not new trust roots',
              'inputs': inputs, 'tools': snapshots, 'commands': [], 'new_keys': 0, 'network_requests': 0})
    def save(): REPORT.write_text(json.dumps(report, indent=2)+'\n')
    def check():
        if time.monotonic() >= deadline: raise TimeoutError('Whole 180-second probe build budget exhausted')
        for name, path in files.items():
            if identity(path) != snapshots[name]: raise ValueError('Existing SDK/JDK tool identity changed')
        for rel, expected in inputs.items():
            if digest(ROOT/rel) != expected: raise ValueError('Source changed during compilation')
        s = regular(key)
        if (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns) != key_identity or identity(app) != app_identity:
            raise ValueError('Existing same-job signing/app identity changed')
    def run(label, args, stdin=None):
        check()
        result = bounded_command(args, deadline, stdin=stdin)
        record = {'stage': label, 'exit': result.returncode, 'elapsed_seconds': time.monotonic()-start}
        if label != 'sign-existing-job-debug-key':
            record.update(stdout=result.stdout, stderr=result.stderr)
        report['commands'].append(record)
        save()
        if result.returncode:
            detail = '' if label == 'sign-existing-job-debug-key' else (result.stderr+result.stdout)[-6000:]
            raise RuntimeError(label+' failed: '+detail)
        return result.stdout
    save()
    try:
        build = PROJECT/'build'; build.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='d08-sdk-', dir=build) as folder:
            work = Path(folder); generated = work/'AndroidManifest.xml'; generated.write_bytes(generate_manifest(MANIFEST.read_bytes()))
            classes = work/'classes'; classes.mkdir(); dex = work/'dex'; dex.mkdir()
            image = android_system_image(files, work, run)
            for name, path in [('generated_android_modules', image/'lib/modules'), ('generated_jrt_fs', image/'lib/jrt-fs.jar')]:
                files[name] = path; snapshots[name] = identity(path)
            report['android_system_image'] = {n: snapshots[n] for n in ['generated_android_modules', 'generated_jrt_fs']}
            run('javac-android-only', probe_javac_args(files, image, classes, SOURCES))
            bytecode = sorted(classes.rglob('*.class'))
            if not bytecode: raise ValueError('No actual Java classes produced')
            run('d8', [files['java'], '-cp', files['d8'], 'com.android.tools.r8.D8', '--min-api', '26',
                '--lib', files['android_jar'], '--output', dex, *bytecode])
            dex_files = list(dex.glob('*.dex'))
            if [f.name for f in dex_files] != ['classes.dex']: raise ValueError('One actual bounded probe DEX required')
            report['classes'] = dex_classes(dex_files[0].read_bytes())
            unsigned = work/'unsigned.apk'
            run('aapt2-link', [files['aapt2'], 'link', '-o', unsigned, '-I', files['android_jar'], '--manifest', generated])
            with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_STORED) as z: z.write(dex_files[0], 'classes.dex')
            aligned = work/'aligned.apk'; signed = work/'signed.apk'
            run('zipalign', [files['zipalign'], '-P', '16', '-f', '4', unsigned, aligned])
            signer = [files['java'], '-jar', files['apksigner']]
            app_signer = certificate(run('verify-existing-debug-app', [*signer, 'verify', '--print-certs', app]))
            run('sign-existing-job-debug-key', [*signer, 'sign', '--ks', key, '--ks-key-alias', 'androiddebugkey',
                '--ks-pass', 'stdin', '--key-pass', 'stdin', '--out', signed, aligned], stdin='android\nandroid\n')
            if certificate(run('verify-probe-signature', [*signer, 'verify', '--verbose', '--print-certs', signed])) != app_signer:
                raise ValueError('Probe signer differs from existing job debug APK')
            run('verify-probe-alignment', [files['zipalign'], '-c', '-P', '16', '4', signed])
            badging = run('probe-badging', [files['aapt2'], 'dump', 'badging', signed])
            tree = run('probe-binary-manifest', [files['aapt2'], 'dump', 'xmltree', signed, '--file', 'AndroidManifest.xml'])
            report['classes'] = verify_apk(signed, badging, tree); check()
            OUTPUT.parent.mkdir(parents=True, exist_ok=True)
            # Exclusive publication: no existing artifact is overwritten.
            with OUTPUT.open('xb') as out: out.write(signed.read_bytes())
            if time.monotonic() >= deadline: raise TimeoutError('Whole probe build deadline exceeded at publication')
            report.update(status='PASS', apk={'path': str(OUTPUT.relative_to(ROOT)), 'bytes': OUTPUT.stat().st_size,
                'sha256': digest(OUTPUT), 'certificate_sha256': app_signer}, elapsed_seconds=time.monotonic()-start)
    except BaseException as failure:
        report.update(error_type=type(failure).__name__, error=str(failure)[-6500:], elapsed_seconds=time.monotonic()-start)
        raise
    finally: save()
    print('D08_SDK_COMPILE_ONLY '+json.dumps({k: report[k] for k in ['source_sha', 'status', 'scope', 'apk', 'elapsed_seconds']}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ci-compile-only', action='store_true')
    args = parser.parse_args()
    if not args.ci_compile_only: parser.error('Explicit compile-only stage required; no action performed')
    compile_only()


if __name__ == '__main__': main()
