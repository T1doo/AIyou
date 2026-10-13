#!/usr/bin/env python3
"""Require nonempty actual screenshot smoke results, not NO-SOURCE or annotations."""
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from png_integrity import verified_dimensions

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--production-tasks-only", action="store_true",
                    help="verify only the four actual production task renders; never claim a full-suite pass")
args = parser.parse_args()
reports = list((root / "app/build/test-results/validateDebugScreenshotTest").rglob("*.xml"))
cases = []
for report in reports:
    cases.extend(ET.parse(report).getroot().iter("testcase"))
expected = 4 if args.production_tasks_only else 390  # Preserve 386; four production task-route renders.
if len(cases) != expected:
    raise SystemExit(f"FAIL: expected {expected} actual Preview smoke tests, found {len(cases)}")
if any(any(case.find(tag) is not None for tag in ("failure", "error", "skipped")) for case in cases):
    raise SystemExit("FAIL: Preview smoke contains failing/error/skipped cases")
if args.production_tasks_only and any(not case.get("name", "").startswith("LocalTasksRender_") for case in cases):
    raise SystemExit("FAIL: targeted task proof includes a different render method")
references = root / "app/src/screenshotTestDebug/reference"
files = sorted(references.rglob("*.png"))
if args.production_tasks_only:
    files = [path for path in files if path.name.startswith("LocalTasksRender_")]
if len(files) != expected:
    raise SystemExit(f"FAIL: expected {expected} rendered PNGs, found {len(files)}")
manifest = []
for path in files:
    data = path.read_bytes()
    try:
        width, height = verified_dimensions(data)
    except ValueError as error:
        raise SystemExit(f"FAIL: invalid PNG {path.name}: {error}") from error
    manifest.append({"file": str(path.relative_to(references)), "width": width, "height": height,
                     "sha256": hashlib.sha256(data).hexdigest()})
complete = [item for item in manifest if "CompleteRender_" in item["file"]]
if not args.production_tasks_only and (len(complete) != 14 or any(item["height"] <= 2400 for item in complete)):
    raise SystemExit("FAIL: require 14 explicit-height complete-content PNGs taller than the automatic 2400px cap")
review = [item for item in manifest if "/ReviewNowRender_" in item["file"] or "/ReviewMemoriesRender_" in item["file"]]
if not args.production_tasks_only and (len(review) != 4 or any(item["height"] < 2000 for item in review)):
    raise SystemExit("FAIL: require four actual whole-phone-page renders")
local_methods = ("LocalTasksRender", "LocalSettingsRender", "LocalEmptyRender", "LocalRestoredRender", "LocalSavingRender", "LocalSaveFailedRender",
                 "LocalReadFailedRender", "LocalSavedRender", "LocalRejectedInputRender", "LocalMemoriesRender",
                 "LocalLongListRender", "LocalOriginalReadRender", "LocalMissingOriginalRender", "LocalOriginalReadFailureRender",
                 "LocalOriginalEditRender", "LocalOriginalEditSavingRender", "LocalOriginalEditFailedRender", "LocalOriginalEditConflictRender", "LocalOriginalEditStorageFailedRender", "LocalOriginalHistoryRender", "LocalOriginalHistoryReadRender", "LocalOriginalHistoryLoadingRender", "LocalOriginalHistoryFailedRender", "LocalOriginalHistoryUnavailableRender", "LocalHistoryEditorRender", "LocalHistoryEditorFailedRender", "LocalHistoryEditorUnchangedRender", "LocalHistoryReplacementRender")
attachment_methods = ("LocalAttachmentEmptyRender", "LocalAttachmentLoadingRender", "LocalAttachmentSavingRender",
                      "LocalAttachmentConfirmFailedRender", "LocalAttachmentReadyRender", "LocalAttachmentFailedRestartRender",
                      "LocalAttachmentCorruptRender", "LocalAttachmentUnavailableRender",
                      "LocalHistoricalAttachmentReadyRender", "LocalHistoricalAttachmentFailedRender",
                      "LocalHistoricalAttachmentCorruptRender", "LocalHistoricalAttachmentUnavailableRender",
                      "LocalHistoricalAttachmentEntryRender", "LocalAttachmentJpegReadyRender", "LocalAttachmentJpegStrippedRender")
for method in (("LocalTasksRender",) if args.production_tasks_only else local_methods + attachment_methods):
    production = [item for item in manifest if "/" + method + "_" in item["file"]]
    if len(production) != 4:
        raise SystemExit(f"FAIL: require all four actual production sizes for {method}, found {len(production)}")
target = root / ("app/build/reports/d02-production-tasks-preview-manifest.json" if args.production_tasks_only
                 else "app/build/reports/d01-preview-manifest.json")
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps({"kind": "targeted production task render smoke, not full-suite evidence" if args.production_tasks_only
                             else "initial render smoke, not approved goldens", "count": expected,
                             "pngs": manifest}, ensure_ascii=False, indent=2) + "\n")
print(f"PASS: Preview render smoke tests={len(cases)} PNGs={len(files)} failures/errors/skipped=0")
print("VISUAL INSPECTION / DEVICE / TALKBACK / PERFORMANCE: NOT RUN")
