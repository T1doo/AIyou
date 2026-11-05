"""Mutation checks over the actual CI XML guard; these are synthetic XML, not UI runs."""
import re
import tempfile
import textwrap
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
CLASS = 'com.t1doo.aiyou.MixedCapturePresentationTest'


class CaptureInventoryTests(unittest.TestCase):
    def methods(self):
        source = (ROOT / 'app/src/test/kotlin/com/t1doo/aiyou/MixedCapturePresentationTest.kt').read_text(encoding='utf8')
        return re.findall(r'@Test\b(?:(?!@Test).)*?\bfun\s+(\w+)\s*\(', source, re.S)

    def guard(self, path):
        workflow = (ROOT / '.github/workflows/android.yml').read_text(encoding='utf8')
        start = workflow.index("          source = Path('app/build/test-results/testDebugUnitTest/TEST-" + CLASS + ".xml')")
        end = workflow.index('          target = Path(', start)
        code = textwrap.dedent(workflow[start:end])
        code = code[code.index('\n') + 1:]
        exec(compile(code, 'actual CI capture guard', 'exec'), {'source': path, 'ET': ET})

    def fixture(self, path, mode):
        methods = self.methods()
        self.assertEqual(11, len(methods))
        if mode == 'missing': methods.pop()
        if mode == 'foreign': methods[-1] = 'unreviewedForeignMethod'
        if mode == 'duplicate': methods[-1] = methods[0]
        suite = ET.Element('testsuite', tests=str(len(methods)), failures='0', errors='0', skipped='0')
        for method in methods:
            ET.SubElement(suite, 'testcase', classname=CLASS, name=method)
        if mode == 'wrong-class': suite[0].set('classname', 'other.Class')
        if mode in ('failure', 'error', 'skipped'): ET.SubElement(suite[0], mode)
        if mode == 'count-lie': suite.set('tests', '9')
        path.write_bytes(ET.tostring(suite))

    def test_current_eleven_exact_methods_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'suite.xml'
            self.fixture(path, 'valid')
            self.guard(path)

    def test_missing_foreign_duplicate_failed_and_count_mismatched_results_are_rejected(self):
        for mode in ('missing', 'foreign', 'duplicate', 'wrong-class', 'failure', 'error', 'skipped', 'count-lie'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                path = Path(temp) / 'suite.xml'
                self.fixture(path, mode)
                with self.assertRaises(AssertionError):
                    self.guard(path)
