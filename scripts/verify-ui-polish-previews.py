#!/usr/bin/env python3
"""Validate ten bounded Layoutlib renders; never imply device or approved goldens."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from png_integrity import verified_dimensions

root = Path(__file__).resolve().parents[1]
expected = {"ShellRender": 2, "LocalEmptyRender": 4, "LocalSaveFailedRender": 4}
cases = [case for path in (root / "app/build/test-results/validateDebugScreenshotTest").rglob("*.xml")
         for case in ET.parse(path).getroot().iter("testcase")]
assert len(cases) == sum(expected.values()), f"Expected ten actual tests, got {len(cases)}"
assert not any(case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped"))
reference = root / "app/src/screenshotTestDebug/reference"
inventory = []
for method, count in expected.items():
    assert sum(case.get("name", "").startswith(method + "_") for case in cases) == count
    images = [p for p in reference.rglob("*.png") if p.name.startswith(method + "_")]
    assert len(images) == count, f"{method}: got {len(images)} PNGs"
    for path in sorted(images):
        raw = path.read_bytes()
        width, height = verified_dimensions(raw)
        inventory.append({"file": str(path.relative_to(reference)), "width": width, "height": height,
                          "sha256": hashlib.sha256(raw).hexdigest()})
report = root / "app/build/reports/ui-polish-previews.json"
report.parent.mkdir(parents=True, exist_ok=True)
report.write_text(json.dumps({"kind": "targeted Layoutlib smoke; no device or approved golden claim",
                              "tests": len(cases), "pngs": inventory}, indent=2) + "\n")
print("PASS: ten actual native Preview tests and valid PNGs. Manual image inspection remains separate.")
