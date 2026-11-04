"""An existing, recognized agreement remains required; the gate never writes it."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'check-sdk.py'


class SdkAcceptanceTest(unittest.TestCase):
    def check_record(self, record, expected):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for package, revision in [('platforms/android-37.0', '2'), ('build-tools/36.0.0', '36.0.0')]:
                directory = root / package
                directory.mkdir(parents=True)
                (directory / 'source.properties').write_text('Pkg.Revision=' + revision)
            license_file = root / 'licenses/android-sdk-license'
            if record is not None:
                license_file.parent.mkdir()
                license_file.write_text(record)
            result = subprocess.run([sys.executable, str(SCRIPT)], capture_output=True, text=True,
                                    env={**os.environ, 'ANDROID_HOME': str(root), 'ANDROID_SDK_ROOT': str(root)})
            self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
            self.assertEqual(license_file.read_text() if license_file.exists() else None, record)

    def test_previous_explicit_acceptance_is_supported(self):
        self.check_record('24333f8a63b6825ea9c5514f83c2829b004d1fee\n', 0)

    def test_current_explicit_acceptance_is_supported(self):
        self.check_record('9002c006f4b8d9a16e715a9fa4df30ddb8abf9d9\n', 0)

    def test_unknown_terms_remain_rejected(self):
        self.check_record('unreviewed-agreement\n', 1)

    def test_missing_acceptance_is_not_created(self):
        self.check_record(None, 1)
