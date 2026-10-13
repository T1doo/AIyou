"""Runner protocol guards only: these fixtures do not execute Android or validate storage."""
import sys
import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from d02_device_acceptance import NATIVE_METHOD_COUNT, collect_source, parse_result

spec = importlib.util.spec_from_file_location("device_apk_inventory", Path(__file__).resolve().parents[1] / "inspect-d02-device-test-apks.py")
apk_inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apk_inventory)


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

    def test_inventory_contains_original_19_and_source_bound_8_methods(self):
        actual = collect_source(Path(__file__).resolve().parents[2])
        self.assertEqual([13, 4, 2, 8], [len(cases) for cases in actual.values()])
        self.assertEqual(NATIVE_METHOD_COUNT, sum(map(len, actual.values())))

    def test_unregistered_source_class_cannot_silently_keep_passing_count(self):
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "core/data/src/androidTest/kotlin"
            shutil.copytree(root / "core/data/src/androidTest/kotlin", destination)
            (destination / "com/t1doo/aiyou/data/UnregisteredTest.kt").write_text("@Test fun hiddenCase() {}")
            with self.assertRaisesRegex(ValueError, "Unregistered native test class"):
                collect_source(temporary)

    def test_compiled_fixture_assets_reject_missing_or_changed_exports(self):
        root = Path(__file__).resolve().parents[2]
        assets = {f"assets/records-schema-v{version}.json":
            (root / f"core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/{version}.json").read_bytes()
            for version in (2, 3, 4)}
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "synthetic-inventory-only.zip"
            with zipfile.ZipFile(apk, "w") as archive:
                for name, content in assets.items(): archive.writestr(name, content)
            self.assertEqual({"2", "3", "4"}, set(apk_inventory.verify_schema_assets(apk, root)))
            for changed in (dict(list(assets.items())[1:]), {**assets, "assets/records-schema-v4.json": b"{}"}):
                with zipfile.ZipFile(apk, "w") as archive:
                    for name, content in changed.items(): archive.writestr(name, content)
                with self.assertRaises(ValueError): apk_inventory.verify_schema_assets(apk, root)

    def test_unregistered_project_dex_class_and_wrong_method_are_rejected(self):
        expected = {"com.t1doo.aiyou.data.RegisteredTest": ("one",)}
        apk_inventory.verify_inventory(expected, expected)
        for actual in (
            {**expected, "com.t1doo.aiyou.data.UnregisteredTest": ("hidden",)},
            {"com.t1doo.aiyou.data.RegisteredTest": ("other",)},
            {"com.t1doo.aiyou.data.RegisteredTest": ("one", "one")},
        ):
            with self.subTest(actual=actual), self.assertRaises(ValueError):
                apk_inventory.verify_inventory(actual, expected)


if __name__ == '__main__':
    unittest.main()
