#!/usr/bin/env python3
"""Deliver unchanged rendered PNGs in bounded ordinary ZIPs, never visual approval."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import tempfile
from png_integrity import verified_dimensions
import zipfile

GROUPS = {"now": ("此刻", 28), "memories": ("回忆", 28), "tasks": ("待办", 28),
          "components": ("八种组件", 112), "shell": ("主导航", 2),
          "complete": ("完整长文本", 14), "review": ("白蓝手机页面与正式本机状态", 134),
          "attachments": ("本机附件与历史版本只读状态", 60), "drafts": ("新记录草稿清空确认", 4)}
METHODS = {"NowRender": "now", "MemoriesRender": "memories", "TasksRender": "tasks",
           "ShellRender": "shell", **dict.fromkeys(
               ("MemoryRender", "ComposerRender", "PrivacyRender", "SourceRender",
                "ProposalRender", "ProviderRender", "NoticeRender", "MascotRender"), "components"),
           **dict.fromkeys(("PrivacyCompleteRender", "MemoryCompleteRender", "NoticeCompleteRender",
                            "ProposalCompleteRender", "SourceCompleteRender", "MascotCompleteRender",
                            "ProviderCompleteRender"), "complete"),
           "ReviewNowRender": "review", "ReviewMemoriesRender": "review",
           "LocalCaptureDiscardRender": "drafts",
           "UnifiedKeywordHeaderRender": "review", "UnifiedKeywordResultsRender": "review", "UnifiedKeywordAcceptedMemoryRender": "review", "UnifiedKeywordCaptureDateRender": "review", "UnifiedKeywordManualTagsRender": "review", "UnifiedKeywordManualPeopleRender": "review", "UnifiedKeywordFavoritesRender": "review", "UnifiedKeywordReadyImagesRender": "review",
           **dict.fromkeys(("LocalTasksRender", "LocalSettingsRender", "LocalCompanionRender", "LocalEmptyRender", "LocalRestoredRender", "LocalSavingRender", "LocalSaveFailedRender",
                            "LocalReadFailedRender", "LocalSavedRender", "LocalRejectedInputRender", "LocalMemoriesRender",
                            "LocalLongListRender", "LocalOriginalReadRender", "LocalMissingOriginalRender", "LocalOriginalReadFailureRender",
                            "LocalOriginalEditRender", "LocalOriginalEditSavingRender", "LocalOriginalEditFailedRender", "LocalOriginalEditConflictRender", "LocalOriginalEditStorageFailedRender", "LocalOriginalHistoryRender", "LocalOriginalHistoryReadRender", "LocalOriginalHistoryLoadingRender", "LocalOriginalHistoryFailedRender", "LocalOriginalHistoryUnavailableRender", "LocalHistoryEditorRender", "LocalHistoryEditorFailedRender", "LocalHistoryEditorUnchangedRender", "LocalHistoryReplacementRender"), "review")}
MAX_ZIP_BYTES = 17_000_000  # Decimal MB; allow margin for the download artifact's outer ZIP.
METHODS.update(dict.fromkeys(("LocalAttachmentEmptyRender", "LocalAttachmentLoadingRender", "LocalAttachmentSavingRender",
                             "LocalAttachmentConfirmFailedRender", "LocalAttachmentReadyRender", "LocalAttachmentFailedRestartRender",
                             "LocalAttachmentCorruptRender", "LocalAttachmentUnavailableRender",
                             "LocalHistoricalAttachmentReadyRender", "LocalHistoricalAttachmentFailedRender",
                             "LocalHistoricalAttachmentCorruptRender", "LocalHistoricalAttachmentUnavailableRender",
                             "LocalHistoricalAttachmentEntryRender", "LocalAttachmentJpegReadyRender", "LocalAttachmentJpegStrippedRender"), "attachments"))


def group_for(file):
    matches = [group for method, group in METHODS.items()
               if re.search(r"(?<![A-Za-z0-9])" + method + r"(?![A-Za-z0-9])", file)]
    if len(matches) != 1:
        raise ValueError(f"unrecognized/ambiguous Preview filename: {file}")
    return matches[0]


def read_png(reference, item, maximum):
    path = reference / item["file"]
    if path.stat().st_size > maximum:
        raise ValueError(f"single PNG exceeds {maximum} bytes: {item['file']}")
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum:
        raise ValueError(f"single PNG exceeds {maximum} bytes: {item['file']}")
    if hashlib.sha256(data).hexdigest() != item["sha256"]:
        raise ValueError(f"PNG changed since render verification: {item['file']}")
    dimensions = verified_dimensions(data)
    if any(type(item[key]) is not int for key in ("width", "height")) or dimensions != (item["width"], item["height"]):
        raise ValueError(f"PNG dimensions differ from render manifest: {item['file']}")
    if "bytes" in item and len(data) != item["bytes"]:
        raise ValueError("PNG changed during packaging")
    return data


def metadata(group, label, pngs, part, parts, source_kind="unspecified source; no render acceptance"):
    manifest = {"group": group, "label": label, "part": part, "parts": parts,
                "group_count": GROUPS[group][1], "source_count": sum(count for _, count in GROUPS.values()), "source_kind": source_kind,
                "count": len(pngs), "pngs": pngs,
                "visual_inspection": "NOT RUN", "approved_goldens": False}
    readme = (f"D01 {label}：第 {part}/{parts} 包，本包 {len(pngs)} 张，本组 {GROUPS[group][1]} 张原始 PNG。\n"
              f"无缩放、重编码或降质。全套{sum(count for _, count in GROUPS.values())}张的完整清单与分包对应见INDEX.json。\n"
              f"来源：{source_kind}。尚未视觉验收，也不是已批准 golden。\n"
              "MANIFEST.json 保留原文件名、尺寸、字节数、SHA-256及本组分包序号。\n"
              "每个ZIP独立上传；请核查截断、重叠、可读性和内容分区。\n")
    return [("README.txt", readme.encode("utf-8")),
            ("MANIFEST.json", (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))]


def zip_size(group, label, pngs, part, parts, source_kind="unspecified source; no render acceptance"):
    # Seekable ZIP_STORED without comments/extras/ZIP64: local headers + central directory + EOCD.
    entries = [(name, len(data)) for name, data in metadata(group, label, pngs, part, parts, source_kind)]
    entries += [("pngs/" + item["file"], item["bytes"]) for item in pngs]
    return 22 + sum(30 + 46 + 2 * len(name.encode("utf-8")) + size for name, size in entries)


def partition(group, label, pngs, maximum, source_kind="unspecified source; no render acceptance"):
    parts = 1
    while True:
        batches, current = [], []
        for item in pngs:
            if zip_size(group, label, current + [item], len(batches) + 1, parts, source_kind) > maximum:
                if current:
                    batches.append(current)
                    current = []
                if zip_size(group, label, [item], len(batches) + 1, parts, source_kind) > maximum:
                    raise ValueError(f"single PNG plus manifest/README/ZIP exceeds {maximum}: {item['file']}")
            current.append(item)
        if current:
            batches.append(current)
        if len(batches) == parts:
            return batches
        # Starting at one, increasing the count can only increase its encoded byte length.
        if len(batches) < parts or len(batches) > len(pngs):
            raise ValueError("unstable ZIP partition")
        parts = len(batches)


def write_member(archive, name, data):
    entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    entry.compress_type = zipfile.ZIP_STORED
    entry.create_system = 3
    entry.external_attr = 0o600 << 16
    archive.writestr(entry, data)


def package(root, max_zip_bytes=MAX_ZIP_BYTES):
    if type(max_zip_bytes) is not int or not 0 < max_zip_bytes <= MAX_ZIP_BYTES:
        raise ValueError("invalid ZIP bound; the original maximum cannot be raised")
    reference = root / "app/src/screenshotTestDebug/reference"
    report = root / "app/build/reports/d01-preview-manifest.json"
    source_bytes = report.read_bytes()
    source = json.loads(source_bytes)
    expected = sum(count for _, count in GROUPS.values())
    if source.get("count") != expected or len(source.get("pngs", [])) != expected:
        raise ValueError(f"require the actual passing {expected}-image render manifest")
    source_kind = source.get("kind", "unspecified source; no render acceptance")
    if not isinstance(source_kind, str) or len(source_kind) > 256 or any(ord(c) < 32 for c in source_kind):
        raise ValueError("invalid source manifest kind")
    grouped = {group: [] for group in GROUPS}
    seen = set()
    for item in source["pngs"]:
        file = item["file"]
        relative = PurePosixPath(file)
        if relative.is_absolute() or ".." in relative.parts or "\\" in file or file in seen or relative.suffix != ".png":
            raise ValueError(f"unsafe or duplicate PNG path: {file}")
        path = reference / file
        if not path.resolve().is_relative_to(reference.resolve()):
            raise ValueError(f"PNG escapes reference directory: {file}")
        data = read_png(reference, item, max_zip_bytes)
        seen.add(file)
        grouped[group_for(file)].append({**item, "bytes": len(data)})
    plans = []
    for group, (label, count) in GROUPS.items():
        pngs = sorted(grouped[group], key=lambda item: item["file"])
        if len(pngs) != count:
            raise ValueError(f"{group}: expected {count} images, found {len(pngs)}")
        batches = partition(group, label, pngs, max_zip_bytes, source_kind)
        plans.extend((group, label, pngs, part, len(batches)) for part, pngs in enumerate(batches, 1))
    output = root / "app/build/outputs/d01-visual-packs"
    output.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".pack-", dir=output))
    index = {"kind": "unchanged PNG delivery; visual inspection NOT RUN", "count": expected, "source_kind": source_kind,
             "source_manifest_sha256": hashlib.sha256(source_bytes).hexdigest(),
             "pngs": sorted((item for values in grouped.values() for item in values), key=lambda item: item["file"]),
             "max_inner_zip_bytes": max_zip_bytes, "packs": []}
    for group, label, pngs, part, parts in plans:
        name = f"d01-visual-{group}-{part:03}.zip"
        target = staging / name
        with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED, allowZip64=False) as archive:
            for member, data in metadata(group, label, pngs, part, parts, source_kind):
                write_member(archive, member, data)
            for item in pngs:
                write_member(archive, "pngs/" + item["file"], read_png(reference, item, max_zip_bytes))
        size = target.stat().st_size
        if size != zip_size(group, label, pngs, part, parts, source_kind) or size > max_zip_bytes:
            raise ValueError(f"{name}: ZIP size {size} exceeds planned bound; staged bytes preserved")
        with zipfile.ZipFile(target) as archive:
            if archive.testzip() is not None:
                raise ValueError(f"ZIP integrity failed: {name}")
        index["packs"].append({"file": name, "group": group, "part": part, "parts": parts,
            "count": len(pngs), "bytes": size, "sha256": hashlib.sha256(target.read_bytes()).hexdigest(), "pngs": pngs})
    # Publish only after all files validate. Existing different outputs remain available for inspection.
    for entry in index["packs"]:
        target = output / entry["file"]
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() != entry["sha256"]:
            raise ValueError(f"existing ZIP differs: {entry['file']}; preserved original and staging")
    for entry in index["packs"]:
        target = output / entry["file"]
        if target.exists():
            (staging / entry["file"]).unlink()  # Only this run's identical staged copy.
        else:
            (staging / entry["file"]).replace(target)
        print(f"PASS: {entry['file']} bytes={entry['bytes']} PNGs={entry['count']} <= {max_zip_bytes}")
    index_path = staging / "INDEX.json"
    index_path.write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n")
    index_path.replace(output / "INDEX.json")
    staging.rmdir()
    print("D01_VISUAL_PACK_INDEX=" + json.dumps(index, ensure_ascii=False, separators=(",", ":")))
    print("VISUAL INSPECTION / USER ACCEPTANCE / DEVICE: NOT RUN")
    return index


if __name__ == "__main__":
    try:
        package(Path(__file__).resolve().parents[1])
    except (ValueError, OSError, KeyError, json.JSONDecodeError) as error:
        raise SystemExit(f"FAIL: visual delivery packaging: {error}") from error
