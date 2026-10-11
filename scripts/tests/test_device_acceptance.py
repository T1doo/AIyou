"""Runner protocol guards only: these fixtures do not execute Android or validate storage."""
import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from d02_device_acceptance import collect_source, parse_result


def passing():
    return '\n'.join((
        'INSTRUMENTATION_STATUS: class=sample.NativeTest',
        'INSTRUMENTATION_STATUS: test=one',
        'INSTRUMENTATION_STATUS_CODE: 1',
        'INSTRUMENTATION_STATUS: aiyou_phase=read',
        'INSTRUMENTATION_STATUS: aiyou_token=owned',
        'INSTRUMENTATION_STATUS: aiyou_pid=73',
        'INSTRUMENTATION_STATUS_CODE: 2',
        'INSTRUMENTATION_STATUS: class=sample.NativeTest',
        'INSTRUMENTATION_STATUS: test=one',
        'INSTRUMENTATION_STATUS_CODE: 0',
        'OK (1 test)',
        'INSTRUMENTATION_CODE: -1',
    ))


class DeviceProtocolTests(unittest.TestCase):
    def parse(self, text):
        return parse_result(text, 1, [('sample.NativeTest', 'one')], phase='read', token='owned')

    def test_complete_exact_result_has_pid(self):
        self.assertEqual(73, self.parse(passing())['pid'])

    def test_skip_does_not_pass_as_one_case(self):
        with self.assertRaises(ValueError): self.parse(passing().replace('STATUS_CODE: 0', 'STATUS_CODE: -3'))

    def test_missing_terminal_is_rejected(self):
        with self.assertRaises(ValueError): self.parse(passing().replace('INSTRUMENTATION_CODE: -1', ''))

    def test_repeated_terminal_is_rejected(self):
        with self.assertRaises(ValueError): self.parse(passing() + '\nINSTRUMENTATION_CODE: -1')

    def test_equal_count_different_method_is_rejected(self):
        with self.assertRaises(ValueError): self.parse(passing().replace('test=one', 'test=other'))

    def test_wrong_phase_or_token_is_rejected(self):
        for field in ('aiyou_phase=read', 'aiyou_token=owned'):
            with self.subTest(field=field), self.assertRaises(ValueError): self.parse(passing().replace(field, field + '-wrong'))

    def test_invalid_or_missing_pid_is_rejected(self):
        for value in ('', '0', '-1', 'unknown'):
            with self.subTest(value=value), self.assertRaises(ValueError): self.parse(passing().replace('aiyou_pid=73', 'aiyou_pid=' + value))

    def test_mismatched_start_identity_is_rejected(self):
        with self.assertRaises(ValueError): self.parse(passing().replace('test=one', 'test=other', 1))

    def test_unclosed_trailing_status_is_rejected(self):
        with self.assertRaises(ValueError): self.parse(passing() + '\nINSTRUMENTATION_STATUS: unexpected=incomplete')

    def test_duplicate_bundle_fields_are_rejected(self):
        with self.assertRaises(ValueError): self.parse(passing().replace('test=one', 'test=one\nINSTRUMENTATION_STATUS: test=one', 1))

    def test_pass_before_start_is_rejected(self):
        with self.assertRaises(ValueError): self.parse(passing().replace('STATUS_CODE: 1', 'STATUS_CODE: 0', 1))

    def test_malformed_or_truncated_tagged_record_is_rejected(self):
        for line in ('INSTRUMENTATION_STATUS: broken', 'INSTRUMENTATION_STATUS_CODE: -', 'INSTRUMENTATION_CODE: -'):
            with self.subTest(line=line), self.assertRaises(ValueError): self.parse(passing() + '\n' + line)

    def test_inventory_contains_all_original_19_methods(self):
        actual = collect_source(Path(__file__).resolve().parents[2])
        self.assertEqual([13, 4, 2], [len(cases) for cases in actual.values()])


if __name__ == '__main__':
    unittest.main()
