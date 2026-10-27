"""Authenticate only the three reviewed SDK-probe artifact members. No commands/network.

The archive ID/digest identifies the reviewed Actions download; after extraction
we authenticate every member's exact bytes, not a report's untrusted assertions.
This does not arm device execution or alter the fixed production APK.
"""
import hashlib
import json
import os
from pathlib import Path
import stat

PROBE_SOURCE = 'e3ac0d9c2311d8bb9a0008df1e5c4421461b991b'
PROBE_RUN = 40450280096
PROBE_ARTIFACT = 12711100888
PROBE_ARCHIVE_SHA = 'de6255599fe18779ddfc334e8c72258420509e9152b4575749ba254f676237b4'
PROBE_SHA = '82bf4c6d3426ed5556fa8d8415451a591806d328e8180ca5e37504fa0e8a4478'
PROBE_CERTIFICATE = '058d5d4b7feb4cc1aa9913afde11fcf96184e2b5eb508f2d5da10c5e505d02bd'
APK_MEMBER = 'checks/device-recovery/android/build/outputs/apk/debug/recovery-probe-debug.apk'
REPORT_MEMBER = 'checks/device-recovery/android/build/reports/sdk-probe-build.json'
RECEIPT_MEMBER = 'build/ci-stage-evidence/recovery-probe/receipt.json'
MEMBERS = {
    APK_MEMBER: (49555, PROBE_SHA),
    REPORT_MEMBER: (15745, '2b2293b0675f1366a0ecc9660e39517cd4ef18f6f7f0851de31c0375292b88b2'),
    RECEIPT_MEMBER: (330, '121fefbf9f441d52dcdf59a574815888527de59370e5a273f64963d3e9ae33ec'),
}
STAGES = ('android-module-descriptor', 'android-jmod', 'android-jlink', 'javac-android-only', 'd8',
          'aapt2-link', 'zipalign', 'verify-existing-debug-app', 'sign-existing-job-debug-key',
          'verify-probe-signature', 'verify-probe-alignment', 'probe-badging', 'probe-binary-manifest')


def identity(s):
    return (s.st_dev, s.st_ino, s.st_mode, s.st_nlink, s.st_uid, s.st_size, s.st_mtime_ns, s.st_ctime_ns)


def fixed_file(path, expected_size, expected_sha, capture=False):
    """Bounded read, no symlink/hardlink or replacement acceptance; never writes."""
    path = Path(path)
    if path.resolve() != path.absolute(): raise ValueError('Redirected artifact path')
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_uid != os.getuid() or before.st_size != expected_size:
        raise ValueError('Fixed regular artifact member missing/different')
    digest = hashlib.sha256(); total = 0; chunks = []
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as f:
        if identity(os.fstat(f.fileno())) != identity(before): raise ValueError('Artifact replaced before read')
        while chunk := f.read(min(65536, expected_size-total+1)):
            total += len(chunk)
            if total > expected_size: raise ValueError('Artifact grew during read')
            digest.update(chunk)
            if capture: chunks.append(chunk)
        if identity(os.fstat(f.fileno())) != identity(before) or identity(path.lstat()) != identity(before):
            raise ValueError('Artifact identity changed during read')
    if total != expected_size or digest.hexdigest() != expected_sha:
        raise ValueError('Fixed artifact byte digest mismatch')
    return b''.join(chunks) if capture else None


def verify_probe(root):
    """Read-only verification, safe to use offline; device caller separately admits."""
    root = Path(root)
    if root.resolve() != root.absolute() or root.is_symlink() or not root.is_dir():
        raise ValueError('Fixed extracted probe directory missing/redirected')
    expected_dirs = {str(p) for n in MEMBERS for p in Path(n).parents if str(p) != '.'}
    before = {'.': identity(root.lstat())}; files = set(); folders = set()
    for path in root.rglob('*'):
        s = path.lstat(); name = str(path.relative_to(root))
        if stat.S_ISDIR(s.st_mode): folders.add(name)
        elif stat.S_ISREG(s.st_mode): files.add(name)
        else: raise ValueError('Linked or special extracted probe member')
        before[name] = identity(s)
    if files != set(MEMBERS) or folders != expected_dirs:
        raise ValueError('Exact three-member probe artifact required; missing/extra content')
    raw = {n: fixed_file(root/n, size, sha, capture=n != APK_MEMBER) for n, (size, sha) in MEMBERS.items()}
    report = json.loads(raw[REPORT_MEMBER]); receipt = json.loads(raw[RECEIPT_MEMBER])
    if report.get('source_sha') != PROBE_SOURCE or report.get('run_id') != str(PROBE_RUN) or \
            type(report.get('run_attempt')) is not int or report['run_attempt'] != 1 or report.get('status') != 'PASS' or \
            report.get('scope') != 'SDK-only compile/sign/inspect; ALL DEVICE EXECUTION NOT RUN' or \
            any(type(report.get(k)) is not int or report[k] != 0 for k in ['new_keys', 'network_requests']):
        raise ValueError('Probe producer source/run/scope mismatch')
    expected_apk = {'path': APK_MEMBER, 'bytes': MEMBERS[APK_MEMBER][0], 'sha256': PROBE_SHA, 'certificate_sha256': PROBE_CERTIFICATE}
    if report.get('apk') != expected_apk or tuple(c.get('stage') for c in report.get('commands', [])) != STAGES or \
            any(type(c.get('exit')) is not int or c['exit'] != 0 for c in report['commands']):
        raise ValueError('Probe APK identity or complete successful build proof differs')
    if any(receipt.get(k) != v or type(receipt.get(k)) is not type(v) for k, v in {
            'source_sha': PROBE_SOURCE, 'run_id': PROBE_RUN, 'run_attempt': 1,
            'job': 'd01', 'stage': 'recovery-probe', 'producer_outcome': 'success'}.items()):
        raise ValueError('Probe stage receipt differs')
    if any(identity((root/name).lstat()) != value for name, value in before.items()):
        raise ValueError('Extracted probe changed during authentication')
    return root/APK_MEMBER
