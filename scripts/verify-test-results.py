#!/usr/bin/env python3
"""Require real, successful test XML instead of treating NO-SOURCE as a pass."""
from pathlib import Path
import argparse
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--component", action="store_true", help="also require actual component regression XML")
parser.add_argument("--queue", action="store_true", help="also require actual host queue regression XML")
parser.add_argument("--ui", action="store_true", help="also require actual Compose interaction/semantics regression XML on the JVM simulator")
parser.add_argument("--trace", action="store_true", help="also require actual test-only native observer regression XML")
args = parser.parse_args()
targets = [("domain", "core/domain/build/test-results/test", 3)]
if args.component:
    targets.append(("component", "core/designsystem/build/test-results/testDebugUnitTest", 7))
if args.queue:
    targets.append(("host queue", "checks/record-queue/build/test-results/test", 6))
if args.ui:
    targets.append(("Compose JVM interaction", "app/build/test-results/testDebugUnitTest", 13))
if args.trace:
    targets.append(("native trace observer", "checks/native-trace/build/test-results/test", 8))
for label, folder, minimum in targets:
    reports = list((root / folder).glob("TEST-*.xml"))
    if not reports:
        raise SystemExit(f"FAIL: no {label} test reports found")
    total = failures = errors = skipped = cases = 0
    observed_cases = []
    child_failures = False
    for report in reports:
        suite = ET.parse(report).getroot()
        total += int(suite.get("tests", 0))
        failures += int(suite.get("failures", 0))
        errors += int(suite.get("errors", 0))
        skipped += int(suite.get("skipped", 0))
        actual = list(suite.iter("testcase"))
        observed_cases.extend(actual)
        cases += len(actual)
        child_failures |= any(any(case.find(tag) is not None for tag in ("failure", "error", "skipped")) for case in actual)
    if total < minimum or cases != total or failures or errors or skipped or child_failures:
        raise SystemExit(f"FAIL: {label} tests={total} actual={cases} failures={failures} errors={errors} skipped={skipped}")
    print(f"PASS: {label} tests={total} actual={cases} failures=0 errors=0 skipped=0")
    if label == "Compose JVM interaction":
        owner = "com.t1doo.aiyou.LocalRecordsPresentationTest"
        required = (
            "narrowLargeFontSaveFailureRetryScrollsIntoViewAndPreservesPendingDraft",
            "narrowLargeFontReadFailureRetryScrollsIntoViewAndPreservesKnownDraft",
        )
        for name in required:
            matches = [case for case in observed_cases if case.get("classname") == owner
                       and case.get("name", "").split("[", 1)[0].strip().removesuffix("()") == name]
            if len(matches) != 1:
                raise SystemExit(f"FAIL: required Compose case={owner}.{name} actual={len(matches)}")
            print(f"PASS: required Compose case={owner}.{name} actual=1 failures=0 errors=0 skipped=0")
