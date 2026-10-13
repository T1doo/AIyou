"""Packaging regressions use tiny synthetic bytes, never UI or render evidence."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("visual_delivery", Path(__file__).resolve().parents[1] / "package-visual-previews.py")
delivery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delivery)


class VisualDeliveryTests(unittest.TestCase):
    def fixture(self, root):
        reference = root / "app/src/screenshotTestDebug/reference"
        reference.mkdir(parents=True)
        items = []
        for method in delivery.METHODS:
            group = delivery.METHODS[method]
            count = 2 if method == "LocalCompanionRender" else 14 if group == "components" else 4 if method.startswith("Local") else 2 if group in ("complete", "review") else delivery.GROUPS[group][1]
            for n in range(count):
                name = f"com.t1doo.aiyou.D01ScreenshotPreviewsKt.{method}_{n}.png"
                data = ("SYNTHETIC PACKAGING FIXTURE " + name).encode()
                (reference / name).write_bytes(data)
                items.append({"file": name, "width": 1, "height": 1,
                              "sha256": hashlib.sha256(data).hexdigest()})
        report = root / "app/build/reports/d01-preview-manifest.json"
        report.parent.mkdir(parents=True)
        report.write_text(json.dumps({"count": 386, "pngs": items}))
        return reference, report, items

    def test_all_386_original_bytes_and_lists_are_preserved_in_small_zips(self):
        with tempfile.TemporaryDirectory() as temp, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temp)
            reference, _, _ = self.fixture(root)
            index = delivery.package(root)
            self.assertEqual(386, sum(p["count"] for p in index["packs"]))
            self.assertEqual([28, 28, 28, 112, 2, 14, 114, 60], [p["count"] for p in index["packs"]])
            for pack in index["packs"]:
                target = root / "app/build/outputs/d01-visual-packs" / pack["file"]
                self.assertEqual(pack["bytes"], target.stat().st_size)
                self.assertLessEqual(pack["bytes"], delivery.MAX_ZIP_BYTES)
                with zipfile.ZipFile(target) as archive:
                    manifest = json.loads(archive.read("MANIFEST.json"))
                    self.assertEqual("NOT RUN", manifest["visual_inspection"])
                    for item in pack["pngs"]:
                        self.assertEqual((reference / item["file"]).read_bytes(), archive.read("pngs/" + item["file"]))

    def test_oversized_output_rejected_and_input_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            reference, _, items = self.fixture(root)
            with self.assertRaisesRegex(ValueError, "exceeds"):
                delivery.package(root, max_zip_bytes=100)
            self.assertEqual(386, len(list(reference.glob("*.png"))))
            self.assertFalse((root / "app/build/outputs/d01-visual-packs/d01-visual-now.zip").exists())

    def test_changed_png_and_unsafe_duplicate_or_unknown_paths_rejected(self):
        for mode in ("changed", "unsafe", "duplicate", "unknown"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                reference, report, items = self.fixture(root)
                if mode == "changed":
                    (reference / items[0]["file"]).write_bytes(b"CHANGED")
                elif mode == "unsafe":
                    items[0]["file"] = "../escape.png"
                elif mode == "duplicate":
                    items[1] = items[0]
                else:
                    (reference / "UnknownRender_0.png").write_bytes((reference / items[0]["file"]).read_bytes())
                    items[0]["file"] = "UnknownRender_0.png"
                report.write_text(json.dumps({"count": 386, "pngs": items}))
                with self.assertRaises(ValueError):
                    delivery.package(root)

    def test_wrong_total_or_wrong_group_count_rejected(self):
        for wrong_total in (True, False):
            with self.subTest(wrong_total=wrong_total), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                reference, report, items = self.fixture(root)
                if wrong_total:
                    items.pop()
                else:
                    original = items[0]["file"]
                    new = original.replace("NowRender", "TasksRender") + "_extra.png"
                    (reference / new).write_bytes((reference / original).read_bytes())
                    items[0]["file"] = new
                report.write_text(json.dumps({"count": 386, "pngs": items}))
                with self.assertRaises(ValueError):
                    delivery.package(root)


if __name__ == "__main__":
    unittest.main()
