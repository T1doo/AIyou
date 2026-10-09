#!/usr/bin/env python3
"""Require real, successful test XML instead of treating NO-SOURCE as a pass."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
reports = list((root / "core/domain/build/test-results/test").glob("TEST-*.xml"))
if not reports:
    raise SystemExit("FAIL: no domain test reports found")
total = failures = errors = skipped = 0
for report in reports:
    suite = ET.parse(report).getroot()
    total += int(suite.get("tests", 0))
    failures += int(suite.get("failures", 0))
    errors += int(suite.get("errors", 0))
    skipped += int(suite.get("skipped", 0))
if total == 0 or failures or errors or skipped:
    raise SystemExit(f"FAIL: tests={total} failures={failures} errors={errors} skipped={skipped}")
print(f"PASS: tests={total} failures=0 errors=0 skipped=0")
