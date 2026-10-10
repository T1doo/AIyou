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
expected = 198  # 8 components x 7 states x 2 sizes + 3 pages x 7 x 4 sizes + 2 shells
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
target = root / "app/build/reports/d01-preview-manifest.json"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps({"kind": "initial render smoke, not approved goldens", "count": expected,
                             "pngs": manifest}, ensure_ascii=False, indent=2) + "\n")
print(f"PASS: Preview render smoke tests={len(cases)} PNGs={len(files)} failures/errors/skipped=0")
print("VISUAL INSPECTION / DEVICE / TALKBACK / PERFORMANCE: NOT RUN")
