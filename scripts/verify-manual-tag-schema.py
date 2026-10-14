#!/usr/bin/env python3
"""Capture checkout bytes before KSP; never silently repair a committed schema/fixture."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
export = root / "core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/5.json"
asset = root / "core/data/src/androidTest/assets/records-schema-v5.json"
receipt = root / ".local-evidence/manual-tags/schema-generation.json"
head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()

def digest(content):
    return hashlib.sha256(content).hexdigest()

def checkout_bytes(path):
    return subprocess.check_output(["git", "show", f"HEAD:{path.relative_to(root).as_posix()}"], cwd=root)

if sys.argv[1:] == ["--capture"]:
    if export.exists() != asset.exists():
        raise SystemExit("Partial v5 schema/fixture state; preserve it and stop")
    if export.exists():
        before = export.read_bytes()
        if before != asset.read_bytes() or before != checkout_bytes(export) or before != checkout_bytes(asset):
            raise SystemExit("v5 schema/fixture does not match exact checkout bytes")
        mode = "CHECKED_IN"
        expected = digest(before)
    else:
        tracked = subprocess.check_output(["git", "ls-files", "--", str(export.relative_to(root)), str(asset.relative_to(root))], cwd=root)
        if tracked:
            raise SystemExit("Tracked v5 schema/fixture is missing; stop")
        mode, expected = "BOOTSTRAP", None
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps({"head": head, "mode": mode, "expected_sha256": expected}) + "\n")
elif sys.argv[1:] == ["--verify"]:
    state = json.loads(receipt.read_text())
    if state["head"] != head:
        raise SystemExit("Checkout changed during Room generation")
    generated = export.read_bytes()
    schema = json.loads(generated)["database"]
    if schema["version"] != 5 or len(schema["entities"]) != 9:
        raise SystemExit("Actual v5 Room export has unexpected version/entity inventory")
    if state["mode"] == "CHECKED_IN":
        if digest(generated) != state["expected_sha256"] or generated != asset.read_bytes() or generated != checkout_bytes(export) or generated != checkout_bytes(asset):
            raise SystemExit("KSP changed committed v5 schema/fixture; do not overwrite or accept drift")
    else:
        if asset.exists():
            raise SystemExit("Unexpected v5 fixture appeared during bootstrap; preserve and stop")
        asset.write_bytes(generated)
    state.update({"generated_sha256": digest(generated), "bytes": len(generated),
                  "scope": "exact checkout bytes verified" if state["mode"] == "CHECKED_IN" else
                  "actual generated untracked schema/fixture; commit bytes and require another exact-head CI"})
    receipt.write_text(json.dumps(state, indent=2) + "\n")
    print("MANUAL_TAG_SCHEMA=" + json.dumps(state, separators=(",", ":")))
else:
    raise SystemExit("Usage: verify-manual-tag-schema.py --capture|--verify")
