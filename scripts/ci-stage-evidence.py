#!/usr/bin/env python3
"""Bind an early CI archive to its actual source and producer outcome, never a test PASS."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def capture(root, stage, outcome, env):
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", stage):
        raise ValueError("Invalid stage identity")
    if outcome not in ("success", "failure", "cancelled", "skipped"):
        raise ValueError("Unknown producer outcome")
    source = env.get("GITHUB_SHA", "")
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise ValueError("Require exact workflow source SHA")
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, timeout=5, text=True).strip()
    if source != actual:
        raise ValueError("Checkout differs from workflow source")
    for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
        if not re.fullmatch(r"[1-9][0-9]*", env.get(key, "")):
            raise ValueError("Require actual run/attempt identity")
    job = env.get("GITHUB_JOB", "")
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]*", job):
        raise ValueError("Require job identity")
    receipt = {"kind": "stage archive provenance; not a test or device verdict", "source_sha": source,
               "run_id": int(env["GITHUB_RUN_ID"]), "run_attempt": int(env["GITHUB_RUN_ATTEMPT"]),
               "job": job, "stage": stage, "producer_outcome": outcome,
               "test_counts": "read the archived original XML; missing XML is not PASS"}
    target = root / "build/ci-stage-evidence" / stage / "receipt.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("x", encoding="utf-8") as output:
        json.dump(receipt, output, indent=2)
        output.write("\n")
    return target


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", required=True)
    parser.add_argument("--outcome", required=True)
    args = parser.parse_args()
    print(capture(Path(__file__).resolve().parents[1], args.stage, args.outcome, os.environ))
