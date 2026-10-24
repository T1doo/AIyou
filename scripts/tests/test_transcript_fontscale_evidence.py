"""Exercise the actual font-receipt gate; synthetic samples are not Compose results."""
import ast
import hashlib
import json
import math
from pathlib import Path
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[1] / "verify-test-results.py"
TREE = ast.parse(SOURCE.read_text())
FUNCTION = next(node for node in TREE.body if isinstance(node, ast.FunctionDef)
                and node.name == "verify_transcript_fontscale")
NAMESPACE = {"Path": Path, "hashlib": hashlib, "json": json, "math": math}
exec(compile(ast.Module(body=[FUNCTION], type_ignores=[]), str(SOURCE), "exec"), NAMESPACE)
VERIFY = NAMESPACE["verify_transcript_fontscale"]


class TranscriptFontEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name)
        self.head = "a" * 40
        self.reset()

    def reset(self):
        for label, scale, line in (("normal", 1.0, 24.0), ("double", 2.0, 40.0)):
            value = {"source_sha": self.head, "font_case": label, "measurement": {
                "configurationFontScale": scale, "layoutFontScale": scale, "density": 1.0,
                "firstLineHeightPx": line, "layoutWidthPx": 224, "layoutHeightPx": int(line * 2),
                "lineCount": 2, "hasVisualOverflow": False, "text_sha256": "b" * 64}}
            self.write(label, value)
        (self.folder / "editor-fontscale.json").unlink(missing_ok=True)

    def write(self, label, value):
        (self.folder / f"editor-fontscale-{label}.json").write_text(json.dumps(value))

    def mutate(self, field, value, *, root=False):
        path = self.folder / "editor-fontscale-double.json"
        sample = json.loads(path.read_text())
        (sample if root else sample["measurement"])[field] = value
        self.write("double", sample)

    def test_actual_non_linear_growth_is_accepted_without_claiming_physical_doubling(self):
        receipt = VERIFY(self.folder, self.head)
        self.assertEqual(2, len(receipt["input_files"]))
        self.assertEqual(40.0, receipt["double"]["firstLineHeightPx"])
        self.assertEqual(receipt, json.loads((self.folder / "editor-fontscale.json").read_text()))

    def test_two_x_label_without_platform_and_real_layout_growth_is_rejected(self):
        for key, value in (("configurationFontScale", 1.0), ("layoutFontScale", 1.0), ("firstLineHeightPx", 24.0)):
            with self.subTest(key=key):
                self.reset(); self.mutate(key, value)
                with self.assertRaises(ValueError):
                    VERIFY(self.folder, self.head)
                self.assertFalse((self.folder / "editor-fontscale.json").exists())

    def test_missing_or_foreign_source_sample_never_produces_a_passing_pair(self):
        (self.folder / "editor-fontscale-normal.json").unlink()
        with self.assertRaises(FileNotFoundError):
            VERIFY(self.folder, self.head)
        self.reset(); self.mutate("source_sha", "c" * 40, root=True)
        with self.assertRaises(ValueError):
            VERIFY(self.folder, self.head)
        self.assertFalse((self.folder / "editor-fontscale.json").exists())

    def test_overflow_invalid_metrics_and_empty_layouts_are_rejected(self):
        for key, value in (("hasVisualOverflow", True), ("density", float("nan")),
                           ("firstLineHeightPx", float("inf")), ("lineCount", 0),
                           ("layoutWidthPx", True)):
            with self.subTest(key=key):
                self.reset(); self.mutate(key, value)
                with self.assertRaises(ValueError):
                    VERIFY(self.folder, self.head)
                self.assertFalse((self.folder / "editor-fontscale.json").exists())

    def test_changed_text_or_pixel_density_cannot_masquerade_as_font_growth(self):
        for key, value in (("text_sha256", "c" * 64), ("density", 2.0)):
            with self.subTest(key=key):
                self.reset(); self.mutate(key, value)
                with self.assertRaises(ValueError):
                    VERIFY(self.folder, self.head)


if __name__ == "__main__":
    unittest.main()
