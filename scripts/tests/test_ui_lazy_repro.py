"""Synthetic XML admission controls only; never Android/Compose execution evidence."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('ui_lazy_repro', Path(__file__).resolve().parents[1] / 'reproduce-ui-lazy-source.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class UiLazyReproductionAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='ui-lazy-xml-admission-')
        self.folder = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def fixture(self, failure=None, method=None, skipped=False, filename='TEST-control.xml'):
        suite = ET.Element('testsuite', tests='1', failures='1' if failure else '0', errors='0', skipped='1' if skipped else '0')
        case = ET.SubElement(suite, 'testcase', classname=gate.CASE, name=method or gate.METHOD)
        if failure:
            ET.SubElement(case, 'failure', type=failure).text = failure + '\nat CurrentImageSearchPresentationTest.kt:131'
        if skipped:
            ET.SubElement(case, 'skipped')
        path = self.folder / filename
        path.write_bytes(ET.tostring(suite))
        return path

    def test_recognized_original_timeout_is_a_completed_negative_control(self):
        self.fixture('androidx.compose.ui.test.ComposeTimeoutException')
        result = gate.inspect_xml(self.folder, 'red', 1, 0)
        self.assertEqual((1, 1, 0, 0), (result['tests'], result['failures'], result['errors'], result['skipped']))

    def test_green_requires_the_actual_single_passing_method(self):
        self.fixture()
        self.assertEqual(0, gate.inspect_xml(self.folder, 'green', 0, 0)['failures'])

    def test_completed_green_failure_remains_a_failed_gate(self):
        self.fixture('androidx.compose.ui.test.ComposeTimeoutException')
        with self.assertRaises(gate.UnexpectedOutcome) as caught:
            gate.inspect_xml(self.folder, 'green', 1, 0)
        self.assertEqual(1, caught.exception.actual['failures'])

    def test_independent_date_requires_its_own_exact_method_ownership(self):
        path = self.fixture()
        tree = ET.fromstring(path.read_bytes())
        case = tree.find('testcase')
        case.set('classname', gate.DATE_CASE)
        case.set('name', gate.DATE_METHOD)
        path.write_bytes(ET.tostring(tree))
        with self.assertRaises(ValueError):
            gate.inspect_xml(self.folder, 'green', 0, 0)
        actual = gate.inspect_xml(self.folder, 'green', 0, 0, gate.DATE_CASE, gate.DATE_METHOD)
        self.assertEqual(gate.DATE_CASE, actual['class'])

    def test_oom_cannot_pass_as_the_expected_negative(self):
        self.fixture('java.lang.OutOfMemoryError')
        with self.assertRaises(ValueError):
            gate.inspect_xml(self.folder, 'red', 1, 0)

    def test_resource_failure_in_either_stream_cannot_be_hidden_by_matching_xml(self):
        self.fixture('androidx.compose.ui.test.ComposeTimeoutException')
        for text in ['java.lang.OutOfMemoryError: Java heap space', 'Gradle build daemon disappeared unexpectedly']:
            with self.subTest(text=text), self.assertRaises(ValueError):
                gate.reject_resource_failure(text)

    def test_expected_case_failure_text_is_separate_from_resource_failure(self):
        gate.reject_resource_failure('ComposeTimeoutException: Condition not satisfied after3000ms')

    def test_wrong_method_cannot_borrow_control_ownership(self):
        self.fixture('androidx.compose.ui.test.ComposeTimeoutException', method='unrelated')
        with self.assertRaises(ValueError):
            gate.inspect_xml(self.folder, 'red', 1, 0)

    def test_duplicate_method_results_cannot_pass(self):
        self.fixture()
        self.fixture(filename='TEST-duplicate.xml')
        with self.assertRaises(ValueError):
            gate.inspect_xml(self.folder, 'green', 0, 0)

    def test_skip_cannot_pass(self):
        self.fixture(skipped=True)
        with self.assertRaises(ValueError):
            gate.inspect_xml(self.folder, 'green', 0, 0)

    def test_compile_failure_without_xml_cannot_pass_as_red(self):
        with self.assertRaises(ValueError):
            gate.inspect_xml(self.folder, 'red', 1, 0)

    def test_stale_xml_cannot_support_a_new_command(self):
        path = self.fixture()
        os.utime(path, ns=(1, 1))
        with self.assertRaises(ValueError):
            gate.inspect_xml(self.folder, 'green', 0, 2)

    def test_inconsistent_suite_failure_count_is_rejected(self):
        path = self.fixture()
        value = ET.fromstring(path.read_bytes())
        value.set('failures', '1')
        path.write_bytes(ET.tostring(value))
        with self.assertRaises(ValueError):
            gate.inspect_xml(self.folder, 'green', 0, 0)

    def test_nonzero_exit_cannot_pass_green(self):
        self.fixture()
        with self.assertRaises(ValueError):
            gate.inspect_xml(self.folder, 'green', 1, 0)

    def test_timeout_or_success_cannot_masquerade_as_expected_red(self):
        self.fixture('androidx.compose.ui.test.ComposeTimeoutException')
        for code in [0, 124, 137]:
            with self.subTest(exit=code), self.assertRaises(ValueError):
                gate.inspect_xml(self.folder, 'red', code, 0)
