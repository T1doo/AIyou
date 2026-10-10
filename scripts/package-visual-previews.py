#!/usr/bin/env python3
"""Deliver unchanged rendered PNGs in bounded ordinary ZIPs, never visual approval."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import zipfile

GROUPS = {"now": ("此刻", 28), "memories": ("回忆", 28), "tasks": ("待办", 28),
          "components": ("八种组件", 112), "shell": ("主导航", 2),
          "complete": ("完整长文本", 14), "review": ("白蓝两页手机壳改稿", 4)}
METHODS = {"NowRender": "now", "MemoriesRender": "memories", "TasksRender": "tasks",
           "ShellRender": "shell", **dict.fromkeys(
               ("MemoryRender", "ComposerRender", "PrivacyRender", "SourceRender",
                "ProposalRender", "ProviderRender", "NoticeRender", "MascotRender"), "components"),
           **dict.fromkeys(("PrivacyCompleteRender", "MemoryCompleteRender", "NoticeCompleteRender",
                            "ProposalCompleteRender", "SourceCompleteRender", "MascotCompleteRender",
                            "ProviderCompleteRender"), "complete"),
           "ReviewNowRender": "review", "ReviewMemoriesRender": "review"}
MAX_ZIP_BYTES = 17_000_000  # Decimal MB; allow margin for the download artifact's outer ZIP.


def group_for(file):
    matches = [group for method, group in METHODS.items()
               if re.search(r"(?<![A-Za-z0-9])" + method + r"(?![A-Za-z0-9])", file)]
    if len(matches) != 1:
        raise ValueError(f"unrecognized/ambiguous Preview filename: {file}")
    return matches[0]


def package(root, max_zip_bytes=MAX_ZIP_BYTES):
    reference = root / "app/src/screenshotTestDebug/reference"
    report = root / "app/build/reports/d01-preview-manifest.json"
    source = json.loads(report.read_text())
    expected = sum(count for _, count in GROUPS.values())
    if source.get("count") != expected or len(source.get("pngs", [])) != expected:
        raise ValueError(f"require the actual passing {expected}-image render manifest")
    grouped = {group: [] for group in GROUPS}
    seen = set()
    for item in source["pngs"]:
        file = item["file"]
        relative = PurePosixPath(file)
        if relative.is_absolute() or ".." in relative.parts or "\\" in file or file in seen:
            raise ValueError(f"unsafe or duplicate PNG path: {file}")
        path = reference / file
        if not path.resolve().is_relative_to(reference.resolve()):
            raise ValueError(f"PNG escapes reference directory: {file}")
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != item["sha256"]:
            raise ValueError(f"PNG changed since render verification: {file}")
        seen.add(file)
        grouped[group_for(file)].append({**item, "bytes": len(data)})
    for group, (_, count) in GROUPS.items():
        if len(grouped[group]) != count:
            raise ValueError(f"{group}: expected {count} images, found {len(grouped[group])}")
    output = root / "app/build/outputs/d01-visual-packs"
    output.mkdir(parents=True, exist_ok=True)
    index = {"kind": "unchanged PNG delivery; visual inspection NOT RUN",
             "max_inner_zip_bytes": max_zip_bytes, "packs": []}
    for group, (label, _) in GROUPS.items():
        pngs = sorted(grouped[group], key=lambda item: item["file"])
        name = f"d01-visual-{group}.zip"
        target = output / name
        manifest = {"group": group, "label": label, "count": len(pngs), "pngs": pngs,
                    "visual_inspection": "NOT RUN", "approved_goldens": False}
        readme = (f"D01 {label}：{len(pngs)} 张原始 PNG，无缩放、重编码或降质。\n"
                  "这是初始渲染结果，尚未视觉验收，也不是已批准 golden。\n"
                  "页面包含七种状态 × 四种尺寸；组件包含八组件 × 七状态 × 两尺寸。\n"
                  "七状态：正常、禁用、加载、错误、长文本、空、离线。\n"
                  "页面尺寸：360dp、320dp/fontScale2、800dp横屏、840dp大屏。\n"
                  "组件尺寸：360dp、320dp/fontScale2；主导航包仅两个尺寸。\n"
                  "完整长文本包：七组件 × 常规1600dp高/窄屏大字体4000dp高，保留完整内容结束标记。\n"
                  "请核查 PNG 截断、重叠、可读性、原文/派生/提案/正式任务分区。\n"
                  "MANIFEST.json 列出精确原始文件名、尺寸、字节数和 SHA-256。\n"
                  "从 GitHub 正常下载；若外层 ZIP 内还有此 ZIP，可直接上传内层 ZIP。\n")
        with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED) as archive:
            archive.writestr("README.txt", readme)
            archive.writestr("MANIFEST.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
            for item in pngs:
                data = (reference / item["file"]).read_bytes()
                if hashlib.sha256(data).hexdigest() != item["sha256"]:
                    raise ValueError("PNG changed during packaging")
                archive.writestr("pngs/" + item["file"], data)
        size = target.stat().st_size
        if size > max_zip_bytes:
            target.unlink()  # Remove only the just-created oversized output, never input images.
            raise ValueError(f"{name}: {size} bytes exceeds {max_zip_bytes}; do not upload")
        with zipfile.ZipFile(target) as archive:
            if archive.testzip() is not None:
                raise ValueError(f"ZIP integrity failed: {name}")
        entry = {"file": name, "group": group, "count": len(pngs), "bytes": size,
                 "sha256": hashlib.sha256(target.read_bytes()).hexdigest(), "pngs": pngs}
        index["packs"].append(entry)
        print(f"PASS: {name} bytes={size} PNGs={len(pngs)} <= {max_zip_bytes}")
    (output / "INDEX.json").write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n")
    print("D01_VISUAL_PACK_INDEX=" + json.dumps(index, ensure_ascii=False, separators=(",", ":")))
    print("VISUAL INSPECTION / USER ACCEPTANCE / DEVICE: NOT RUN")
    return index


if __name__ == "__main__":
    try:
        package(Path(__file__).resolve().parents[1])
    except (ValueError, OSError, KeyError, json.JSONDecodeError) as error:
        raise SystemExit(f"FAIL: visual delivery packaging: {error}") from error
