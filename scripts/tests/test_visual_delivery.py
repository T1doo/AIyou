"""Packaging regressions use tiny synthetic bytes, never UI or render evidence."""
import contextlib
import hashlib
import importlib.util
import io
import json
import re
import os
import struct
import subprocess
import sys
import zlib
from unittest.mock import patch
from pathlib import Path
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

spec = importlib.util.spec_from_file_location("visual_delivery", Path(__file__).resolve().parents[1] / "package-visual-previews.py")
delivery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delivery)


def png(payload=b"synthetic", width=1, height=1):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) +
            chunk(b"tEXt", b"fixture\x00" + payload) +
            chunk(b"IDAT", zlib.compress((b"\x00" + b"\x00\x00\x00\xff" * width) * height)) + chunk(b"IEND", b""))


class VisualDeliveryTests(unittest.TestCase):
    def test_upload_cache_pin_matches_actual_workflow_not_a_mirrored_fixture(self):
        project = Path(__file__).resolve().parents[2]
        workflow = (project / ".github/workflows/android.yml").read_text()
        action = (project / ".github/actions/upload-visual-packs/index.js").read_text()
        pins = set(re.findall(r"actions/upload-artifact@([0-9a-f]{40})", workflow))
        helper = re.search(r"const SHA = '([0-9a-f]{40})';", action)
        self.assertIsNotNone(helper)
        self.assertEqual(1, len(pins))
        self.assertEqual(pins, {helper.group(1)})

    def fixture(self, root):
        reference = root / "app/src/screenshotTestDebug/reference"
        reference.mkdir(parents=True)
        items = []
        for method in delivery.METHODS:
            group = delivery.METHODS[method]
            count = 2 if method == "LocalCompanionRender" else 14 if group == "components" else 4 if method.startswith("Local") else 2 if group in ("complete", "review") else delivery.GROUPS[group][1]
            for n in range(count):
                name = f"com.t1doo.aiyou.D01ScreenshotPreviewsKt.{method}_{n}.png"
                data = png(("SYNTHETIC PACKAGING FIXTURE " + name).encode())
                (reference / name).write_bytes(data)
                items.append({"file": name, "width": 1, "height": 1,
                              "sha256": hashlib.sha256(data).hexdigest()})
        report = root / "app/build/reports/d01-preview-manifest.json"
        report.parent.mkdir(parents=True)
        report.write_text(json.dumps({"kind": "synthetic packaging regression fixtures, not Preview renders", "count": 410, "pngs": items}))
        return reference, report, items

    def test_all_410_original_bytes_and_lists_are_preserved_in_small_zips(self):
        with tempfile.TemporaryDirectory() as temp, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temp)
            reference, _, _ = self.fixture(root)
            index = delivery.package(root)
            self.assertEqual(410, sum(p["count"] for p in index["packs"]))
            self.assertEqual([28, 28, 28, 112, 2, 14, 134, 60, 4], [p["count"] for p in index["packs"]])
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
            self.assertEqual(410, len(list(reference.glob("*.png"))))
            self.assertEqual([], list((root / "app/build/outputs/d01-visual-packs").glob("*.zip")))

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
                report.write_text(json.dumps({"kind": "synthetic packaging regression fixtures, not Preview renders", "count": 410, "pngs": items}))
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
                report.write_text(json.dumps({"kind": "synthetic packaging regression fixtures, not Preview renders", "count": 410, "pngs": items}))
                with self.assertRaises(ValueError):
                    delivery.package(root)

    def save_report(self, report, items):
        report.write_text(json.dumps({"kind": "synthetic packaging regression fixtures, not Preview renders", "count": 410, "pngs": items}))

    def pad_review(self, reference, report, items):
        for item in items:
            if delivery.group_for(item["file"]) == "review":
                data = png(b"synthetic padding " + b"X" * 145000)
                (reference / item["file"]).write_bytes(data)
                item["sha256"] = hashlib.sha256(data).hexdigest()
        self.save_report(report, items)

    def test_review_over_original_17mb_limit_splits_all_134_without_changing_any_png(self):
        with tempfile.TemporaryDirectory() as temp, contextlib.redirect_stdout(io.StringIO()):
            root=Path(temp);reference,report,items=self.fixture(root);self.pad_review(reference,report,items)
            index=delivery.package(root);review=[p for p in index["packs"] if p["group"]=="review"]
            self.assertGreater(len(review),1);self.assertEqual(134,sum(p["count"] for p in review))
            self.assertEqual(410,len(index["pngs"]));self.assertEqual(410,sum(p["count"] for p in index["packs"]))
            self.assertGreater(sum(i["bytes"] for i in index["pngs"] if delivery.group_for(i["file"])=="review"),delivery.MAX_ZIP_BYTES)
            found=[]
            for part in review:
                self.assertLessEqual(part["bytes"],17000000)
                with zipfile.ZipFile(root/"app/build/outputs/d01-visual-packs"/part["file"]) as z:
                    m=json.loads(z.read("MANIFEST.json"));self.assertEqual(part["part"],m["part"]);self.assertEqual(len(review),m["parts"])
                    for item in m["pngs"]:
                        data=z.read("pngs/"+item["file"]);self.assertEqual((reference/item["file"]).read_bytes(),data)
                        self.assertEqual(item["sha256"],hashlib.sha256(data).hexdigest());self.assertEqual((1,1),delivery.verified_dimensions(data))
                        found.append(item["file"])
            expected=sorted(i["file"] for i in items if delivery.group_for(i["file"])=="review")
            self.assertEqual(expected,found);self.assertEqual(len(found),len(set(found)))

    def test_exact_boundary_counts_utf8_names_manifest_readme_and_zip_headers(self):
        names=["合成.ReviewNowRender_0.png","合成.ReviewNowRender_1.png"]
        items=[{"file":n,"width":1,"height":1,"sha256":hashlib.sha256(png(n.encode())).hexdigest(),"bytes":len(png(n.encode()))} for n in names]
        group="review";label=delivery.GROUPS[group][0]
        exact=delivery.zip_size(group,label,items,1,1)
        self.assertEqual([items],delivery.partition(group,label,items,exact))
        self.assertEqual(2,len(delivery.partition(group,label,items,exact-1)))
        buffer=io.BytesIO()
        with zipfile.ZipFile(buffer,"w",compression=zipfile.ZIP_STORED,allowZip64=False) as z:
            for name,data in delivery.metadata(group,label,items,1,1):delivery.write_member(z,name,data)
            for item in items:delivery.write_member(z,"pngs/"+item["file"],png(item["file"].encode()))
        self.assertEqual(exact,len(buffer.getvalue()))

    def test_part_count_digit_growth_and_every_group_remain_complete_under_small_bound(self):
        with tempfile.TemporaryDirectory() as temp, contextlib.redirect_stdout(io.StringIO()):
            root=Path(temp);self.fixture(root);index=delivery.package(root,max_zip_bytes=5000)
            self.assertTrue(any(p["parts"]>=10 for p in index["packs"]))
            for group,(_,count) in delivery.GROUPS.items():
                parts=[p for p in index["packs"] if p["group"]==group]
                self.assertEqual(count,sum(p["count"] for p in parts))
                self.assertEqual(list(range(1,len(parts)+1)),[p["part"] for p in parts])
                for p in parts:
                    self.assertEqual(len(parts),p["parts"]);self.assertLessEqual(p["bytes"],5000)
                    self.assertEqual(p["bytes"],(root/"app/build/outputs/d01-visual-packs"/p["file"]).stat().st_size)

    def test_corrupt_png_with_updated_hash_and_wrong_manifest_dimensions_are_rejected(self):
        for mode in ("crc","truncated","dimensions"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                root=Path(temp);reference,report,items=self.fixture(root);item=items[0];data=(reference/item["file"]).read_bytes()
                if mode=="crc":data=data[:-1]+bytes([data[-1]^1])
                elif mode=="truncated":data=data[:-4]
                else:item["width"]=2
                (reference/item["file"]).write_bytes(data);item["sha256"]=hashlib.sha256(data).hexdigest();self.save_report(report,items)
                with self.assertRaises(ValueError):delivery.package(root)
                self.assertFalse((root/"app/build/outputs/d01-visual-packs/INDEX.json").exists())

    def test_single_image_must_fit_with_its_metadata_and_limit_cannot_be_raised(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);reference,report,items=self.fixture(root)
            with self.assertRaisesRegex(ValueError,"single PNG.*exceeds"):delivery.package(root,max_zip_bytes=500)
            self.assertEqual(410,len(list(reference.glob("*.png"))))
            with self.assertRaisesRegex(ValueError,"cannot be raised"):delivery.package(root,max_zip_bytes=17000001)
            data=png(b"X"*17000000);(reference/items[0]["file"]).write_bytes(data);items[0]["sha256"]=hashlib.sha256(data).hexdigest();self.save_report(report,items)
            with self.assertRaisesRegex(ValueError,"single PNG exceeds"):delivery.package(root)
            self.assertEqual(data,(reference/items[0]["file"]).read_bytes())

    def test_manifest_order_does_not_change_stable_zip_bytes_and_unlisted_outputs_are_preserved(self):
        with tempfile.TemporaryDirectory() as temp, contextlib.redirect_stdout(io.StringIO()):
            root=Path(temp);reference,report,items=self.fixture(root);first=delivery.package(root)
            output=root/"app/build/outputs/d01-visual-packs";old=output/"d01-visual-review.zip";old.write_bytes(b"unlisted prior bytes")
            self.save_report(report,list(reversed(items)));second=delivery.package(root)
            self.assertEqual([(p["file"],p["sha256"]) for p in first["packs"]],[(p["file"],p["sha256"]) for p in second["packs"]])
            self.assertEqual(b"unlisted prior bytes",old.read_bytes());self.assertNotIn(old.name,[p["file"] for p in second["packs"]])

    def test_existing_different_zip_and_changed_source_during_write_preserve_evidence_without_index(self):
        for mode in ("existing","changed"):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as temp,contextlib.redirect_stdout(io.StringIO()):
                root=Path(temp);reference,_,items=self.fixture(root);out=root/"app/build/outputs/d01-visual-packs"
                if mode=="existing":
                    out.mkdir(parents=True);(out/"d01-visual-now-001.zip").write_bytes(b"preserve existing")
                    with self.assertRaisesRegex(ValueError,"existing ZIP differs"):delivery.package(root)
                    self.assertEqual(b"preserve existing",(out/"d01-visual-now-001.zip").read_bytes())
                else:
                    real=delivery.read_png;calls=0
                    def changed(*args):
                        nonlocal calls
                        calls+=1
                        if calls==len(items)+1:(reference/items[0]["file"]).write_bytes(b"changed during write")
                        return real(*args)
                    with patch.object(delivery,"read_png",changed),self.assertRaises(ValueError):delivery.package(root)
                self.assertFalse((out/"INDEX.json").exists())

    def fake_upload_action(self, root, fail=False):
        action=root.parent.parent/"_actions/actions/upload-artifact/ea165f8d65b6e75b540449e92b4886f43607fa02"
        (action/"dist/upload").mkdir(parents=True)
        (action/"action.yml").write_text("runs:\n  using: 'node20'\n  main: 'dist/upload/index.js'\n")
        (action/"dist/upload/index.js").write_text("const fs=require('node:fs');fs.appendFileSync(process.env.VISUAL_SYNTHETIC_RECEIPT,JSON.stringify(Object.fromEntries(Object.entries(process.env).filter(([k])=>k.startsWith('INPUT_'))))+'\\n');" + ("process.exit(7);" if fail else ""))
        return action

    def run_upload(self,root,receipt):
        script=Path(__file__).resolve().parents[2]/".github/actions/upload-visual-packs/index.js"
        return subprocess.run(["node",str(script)],env={**os.environ,"GITHUB_WORKSPACE":str(root),"VISUAL_SYNTHETIC_RECEIPT":str(receipt),"INPUT_PATH":"untrusted inherited multi-path","INPUT_NAME":"untrusted-name"},capture_output=True,text=True)

    def test_each_multipart_zip_uses_an_independent_upload_and_only_its_indexed_file(self):
        with tempfile.TemporaryDirectory() as temp,contextlib.redirect_stdout(io.StringIO()):
            root=Path(temp)/"work/project/project";reference,report,items=self.fixture(root);self.pad_review(reference,report,items)
            index=delivery.package(root);self.fake_upload_action(root);receipt=Path(temp)/"receipts.jsonl"
            (root/"app/build/outputs/d01-visual-packs/d01-visual-review.zip").write_bytes(b"old unlisted ZIP")
            result=self.run_upload(root,receipt);self.assertEqual(0,result.returncode,result.stderr)
            calls=[json.loads(line) for line in receipt.read_text().splitlines()];self.assertEqual(len(index["packs"]),len(calls))
            for pack,call in zip(index["packs"],calls):
                self.assertEqual(pack["file"][:-4],call["INPUT_NAME"]);self.assertEqual(str(root/"app/build/outputs/d01-visual-packs"/pack["file"]),call["INPUT_PATH"])
                self.assertEqual("0",call["INPUT_COMPRESSION-LEVEL"]);self.assertEqual("14",call["INPUT_RETENTION-DAYS"])
                self.assertEqual("error",call["INPUT_IF-NO-FILES-FOUND"]);self.assertEqual("false",call["INPUT_OVERWRITE"])

    def test_upload_preflight_rejects_bad_coverage_tamper_or_unavailable_pinned_entry_before_first_upload(self):
        for mode in ("coverage","tamper","entry","runtime","source"):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as temp,contextlib.redirect_stdout(io.StringIO()):
                root=Path(temp)/"work/project/project";self.fixture(root);index=delivery.package(root);action=self.fake_upload_action(root);out=root/"app/build/outputs/d01-visual-packs";receipt=Path(temp)/"receipts"
                if mode=="coverage":
                    index["packs"][-1]["pngs"][0]=index["packs"][0]["pngs"][0];(out/"INDEX.json").write_text(json.dumps(index))
                elif mode=="tamper":(out/index["packs"][-1]["file"]).write_bytes(b"tampered")
                elif mode=="entry":(action/"dist/upload/index.js").unlink()
                elif mode=="source":(root/"app/build/reports/d01-preview-manifest.json").write_text("changed source manifest")
                else:(action/"action.yml").write_text("runs:\n  using: 'node16'\n  main: 'dist/upload/index.js'\n")
                result=self.run_upload(root,receipt);self.assertNotEqual(0,result.returncode);self.assertFalse(receipt.exists())

    def test_failed_upload_stops_without_retry_or_following_parts(self):
        with tempfile.TemporaryDirectory() as temp,contextlib.redirect_stdout(io.StringIO()):
            root=Path(temp)/"work/project/project";self.fixture(root);delivery.package(root);self.fake_upload_action(root,fail=True);receipt=Path(temp)/"receipts"
            result=self.run_upload(root,receipt);self.assertNotEqual(0,result.returncode)
            self.assertEqual(1,len(receipt.read_text().splitlines()))


if __name__ == "__main__":
    unittest.main()
