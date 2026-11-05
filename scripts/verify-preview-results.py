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
mode = parser.add_mutually_exclusive_group()
mode.add_argument("--production-tasks-only", action="store_true",
                    help="verify only the four actual production task renders; never claim a full-suite pass")
mode.add_argument("--capture-discard-only", action="store_true", help="verify only four capture recovery renders; never claim a full-suite pass")
mode.add_argument("--original-edit-only", action="store_true", help="verify only 32 affected original/history editor renders; never claim a full-suite pass")
mode.add_argument("--skyglass-only", action="store_true", help="verify four native Skyglass component renders only")
args = parser.parse_args()
skyglass_methods = ("SkyglassBlueRender", "SkyglassSunsetRender", "SkyglassNightRender", "SkyglassLargeTextRender")
target_method = "LocalCaptureDiscardRender" if args.capture_discard_only else "LocalTasksRender" if args.production_tasks_only else None
target_methods = ("LocalOriginalEditRender", "LocalOriginalEditSavingRender", "LocalOriginalEditFailedRender",
                  "LocalOriginalEditConflictRender", "LocalOriginalEditStorageFailedRender", "LocalHistoryEditorRender",
                  "LocalHistoryEditorFailedRender", "LocalHistoryEditorUnchangedRender") if args.original_edit_only else ((target_method,) if target_method else ())
if args.skyglass_only:
    target_methods = skyglass_methods
targeted = bool(target_methods)
reports = list((root / "app/build/test-results/validateDebugScreenshotTest").rglob("*.xml"))
cases = []
for report in reports:
    cases.extend(ET.parse(report).getroot().iter("testcase"))
expected = (len(target_methods) if args.skyglass_only else 4 * len(target_methods)) if targeted else 444  # Original 430 + ten existing FrontendDay renders + four Skyglass components.
if len(cases) != expected:
    raise SystemExit(f"FAIL: expected {expected} actual Preview smoke tests, found {len(cases)}")
if any(any(case.find(tag) is not None for tag in ("failure", "error", "skipped")) for case in cases):
    raise SystemExit("FAIL: Preview smoke contains failing/error/skipped cases")
def case_method(case):
    property_value = case.find("./properties/property[@name='PreviewScreenshot.methodName']")
    return property_value.get("value") if property_value is not None else case.get("name", "").split("_")[0]

if targeted and any(case_method(case) not in target_methods for case in cases):
    raise SystemExit("FAIL: targeted render proof includes a different render method")
references = root / "app/src/screenshotTestDebug/reference"
files = sorted(references.rglob("*.png"))
if targeted:
    files = [path for path in files if path.name.startswith(tuple(method + "_" for method in target_methods))]
if len(files) != expected:
    raise SystemExit(f"FAIL: expected {expected} rendered PNGs, found {len(files)}")
manifest = []
for path in files:
    data = path.read_bytes()
    try:
        width, height = verified_dimensions(data)
    except ValueError as error:
        raise SystemExit(f"FAIL: invalid PNG {path.name}: {error}") from error
    manifest.append({"file": path.relative_to(references).as_posix(), "width": width, "height": height,
                     "sha256": hashlib.sha256(data).hexdigest()})
complete = [item for item in manifest if "CompleteRender_" in item["file"]]
if not targeted and (len(complete) != 14 or any(item["height"] <= 2400 for item in complete)):
    raise SystemExit("FAIL: require 14 explicit-height complete-content PNGs taller than the automatic 2400px cap")
review = [item for item in manifest if "/ReviewNowRender_" in item["file"] or "/ReviewMemoriesRender_" in item["file"]]
if not targeted and (len(review) != 4 or any(item["height"] < 2000 for item in review)):
    raise SystemExit("FAIL: require four actual whole-phone-page renders")
local_methods = ("FullBackupHomeRender", "MixedNotebookEmptyRender", "MixedNotebookRecentRender", "LocalCaptureDiscardRender", "LocalTasksRender", "LocalSettingsRender", "LocalEmptyRender", "LocalRestoredRender", "LocalSavingRender", "LocalSaveFailedRender",
                 "LocalReadFailedRender", "LocalSavedRender", "LocalRejectedInputRender", "LocalMemoriesRender",
                 "LocalLongListRender", "LocalOriginalReadRender", "LocalMissingOriginalRender", "LocalOriginalReadFailureRender",
                 "LocalOriginalEditRender", "LocalOriginalEditSavingRender", "LocalOriginalEditFailedRender", "LocalOriginalEditConflictRender", "LocalOriginalEditStorageFailedRender", "LocalOriginalHistoryRender", "LocalOriginalHistoryReadRender", "LocalOriginalHistoryLoadingRender", "LocalOriginalHistoryFailedRender", "LocalOriginalHistoryUnavailableRender", "LocalHistoryEditorRender", "LocalHistoryEditorFailedRender", "LocalHistoryEditorUnchangedRender", "LocalHistoryReplacementRender")
attachment_methods = ("LocalAttachmentEmptyRender", "LocalAttachmentLoadingRender", "LocalAttachmentSavingRender",
                      "LocalAttachmentConfirmFailedRender", "LocalAttachmentReadyRender", "LocalAttachmentFailedRestartRender",
                      "LocalAttachmentCorruptRender", "LocalAttachmentUnavailableRender",
                      "LocalHistoricalAttachmentReadyRender", "LocalHistoricalAttachmentFailedRender",
                      "LocalHistoricalAttachmentCorruptRender", "LocalHistoricalAttachmentUnavailableRender",
                      "LocalHistoricalAttachmentEntryRender", "LocalAttachmentJpegReadyRender", "LocalAttachmentJpegStrippedRender")
for method in (target_methods if targeted else local_methods + attachment_methods):
    production = [item for item in manifest if "/" + method + "_" in item["file"]]
    if len(production) != (1 if args.skyglass_only else 4):
        raise SystemExit(f"FAIL: require all four actual production sizes for {method}, found {len(production)}")
if not targeted:
    for method in skyglass_methods:
        assert sum(case_method(case) == method for case in cases) == 1
        assert sum("/" + method + "_" in item["file"] for item in manifest) == 1
    for method in ("FrontendDayNow", "FrontendDayMemories", "FrontendDayTasks", "FrontendDayAudioPlaying", "FrontendDayReminderPending", "UnifiedKeywordHeaderRender", "UnifiedKeywordResultsRender", "UnifiedKeywordAcceptedMemoryRender", "UnifiedKeywordCaptureDateRender", "UnifiedKeywordManualTagsRender", "UnifiedKeywordManualPeopleRender", "UnifiedKeywordFavoritesRender", "UnifiedKeywordReadyImagesRender", "QuestionKeywordInputRender", "QuestionKeywordReviewRender", "KeywordReductionInputRender", "KeywordReductionConfirmRender"):
        actual = [case for case in cases if case_method(case) == method]
        images = [item for item in manifest if "/" + method + "_" in item["file"]]
        if len(actual) != 2 or len(images) != 2:
            raise SystemExit(f"FAIL: require both200% portrait/landscape production lookup renders for {method}")
target = root / ("app/build/reports/skyglass-preview-manifest.json" if args.skyglass_only else "app/build/reports/d02-original-edit-preview-manifest.json" if args.original_edit_only else "app/build/reports/d02-capture-recovery-preview-manifest.json" if args.capture_discard_only else "app/build/reports/d02-production-tasks-preview-manifest.json" if args.production_tasks_only
                 else "app/build/reports/d01-preview-manifest.json")
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps({"kind": "targeted production render smoke, not full-suite evidence" if targeted
                             else "initial render smoke, not approved goldens", "count": expected,
                             "pngs": manifest}, ensure_ascii=False, indent=2) + "\n")
print(f"PASS: Preview render smoke tests={len(cases)} PNGs={len(files)} failures/errors/skipped=0")
print("VISUAL INSPECTION / DEVICE / TALKBACK / PERFORMANCE: NOT RUN")
