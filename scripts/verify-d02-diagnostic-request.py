#!/usr/bin/env python3
"""Require the exact reviewed one-shot scope; no generic input/credential mechanism."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
expected = {
    "request": "d02-fourth-diagnostic-20261010",
    "baseline": "dbb48ed1feebe7491c14f7dd1bb3cf03fe230944",
    "scope": "fiveAcknowledgedSavesSurviveSeparateDatabaseReopens",
    "instrumentation_timeout_seconds": 240,
    "whole_experiment_budget_seconds": 1320,
    "attempts": 1,
    "new_sdk_terms": False,
    "full_suite": False,
}
actual = json.loads((root / "checks/sdk-device/targeted-fourth-request-20261010.json").read_text())
if actual != expected:
    raise SystemExit("FAIL: request differs from the reviewed one-case/budget/license scope")
print("PASS: exact single-case diagnostic request; unchanged deadlines; existing terms only")
