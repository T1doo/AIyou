#!/usr/bin/env python3
"""Require nonempty actual screenshot smoke results, not NO-SOURCE or annotations."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from png_integrity import verified_dimensions

root = Path(__file__).resolve().parents[1]
reports = list((root / "app/build/test-results/validateDebugScreenshotTest").rglob("*.xml"))
cases = []
for report in reports:
    cases.extend(ET.parse(report).getroot().iter("testcase"))
expected = 320  # Original216 + twenty-six production states/routes x four sizes.
if len(cases) != expected:
    raise SystemExit(f"FAIL: expected {expected} actual Preview smoke tests, found {len(cases)}")
if any(any(case.find(tag) is not None for tag in ("failure", "error", "skipped")) for case in cases):
    raise SystemExit("FAIL: Preview smoke contains failing/error/skipped cases")
references = root / "app/src/screenshotTestDebug/reference"
files = sorted(references.rglob("*.png"))
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
if len(complete) != 14 or any(item["height"] <= 2400 for item in complete):
    raise SystemExit("FAIL: require 14 explicit-height complete-content PNGs taller than the automatic 2400px cap")
review = [item for item in manifest if "/ReviewNowRender_" in item["file"] or "/ReviewMemoriesRender_" in item["file"]]
if len(review) != 4 or any(item["height"] < 2000 for item in review):
    raise SystemExit("FAIL: require four actual whole-phone-page renders")
local_methods = ("LocalEmptyRender", "LocalRestoredRender", "LocalSavingRender", "LocalSaveFailedRender",
                 "LocalReadFailedRender", "LocalSavedRender", "LocalRejectedInputRender", "LocalMemoriesRender",
                 "LocalLongListRender", "LocalOriginalReadRender", "LocalMissingOriginalRender", "LocalOriginalReadFailureRender",
                 "LocalOriginalEditRender", "LocalOriginalEditSavingRender", "LocalOriginalEditFailedRender", "LocalOriginalEditConflictRender", "LocalOriginalEditStorageFailedRender", "LocalOriginalHistoryRender", "LocalOriginalHistoryReadRender", "LocalOriginalHistoryLoadingRender", "LocalOriginalHistoryFailedRender", "LocalOriginalHistoryUnavailableRender", "LocalHistoryEditorRender", "LocalHistoryEditorFailedRender", "LocalHistoryEditorUnchangedRender", "LocalHistoryReplacementRender")
for method in local_methods:
    production = [item for item in manifest if "/" + method + "_" in item["file"]]
    if len(production) != 4:
        raise SystemExit(f"FAIL: require all four actual production sizes for {method}, found {len(production)}")
target = root / "app/build/reports/d01-preview-manifest.json"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps({"kind": "initial render smoke, not approved goldens", "count": expected,
                             "pngs": manifest}, ensure_ascii=False, indent=2) + "\n")
print(f"PASS: Preview render smoke tests={len(cases)} PNGs={len(files)} failures/errors/skipped=0")
print("VISUAL INSPECTION / DEVICE / TALKBACK / PERFORMANCE: NOT RUN")
