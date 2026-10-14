#!/usr/bin/env python3
"""Preserve the actual Room v8 KSP export; bootstrap is not final exact-head evidence."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
export = root / "core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/8.json"
asset = root / "core/data/src/androidTest/assets/records-schema-v8.json"
receipt = root / ".local-evidence/permanent-erasure/schema-generation.json"
head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()

def checkout(path):
    return subprocess.check_output(["git", "show", head + ":" + path.relative_to(root).as_posix()], cwd=root)

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

if sys.argv[1:] == ["--capture"]:
    if export.exists() != asset.exists():
        raise SystemExit("Partial v8 schema/fixture state; preserve and stop")
    if export.exists():
        before = export.read_bytes()
        if before != asset.read_bytes() or before != checkout(export) or before != checkout(asset):
            raise SystemExit("v8 schema/fixture differs from exact checkout")
        mode, expected = "CHECKED_IN", digest(before)
    else:
        tracked = subprocess.check_output(["git", "ls-files", "--", str(export.relative_to(root)),
            str(asset.relative_to(root))], cwd=root)
        if tracked:
            raise SystemExit("Tracked v8 schema/fixture is missing; stop")
        mode, expected = "BOOTSTRAP", None
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps({"head": head, "mode": mode, "expected_sha256": expected}) + "\n")
elif sys.argv[1:] == ["--verify"]:
    state = json.loads(receipt.read_text())
    if state["head"] != head or state["mode"] not in ("BOOTSTRAP", "CHECKED_IN"):
        raise SystemExit("Checkout changed during v8 generation")
    generated = export.read_bytes()
    schema = json.loads(generated)["database"]
    old = json.loads((export.parent / "7.json").read_text())["database"]
    by_name = {e["tableName"]: e for e in schema["entities"]}
    expected_names = {e["tableName"] for e in old["entities"]} | {
        "permanent_erasure_receipts", "record_package_receipts", "owned_file_creations"}
    if schema["version"] != 8 or len(schema["entities"]) != 16 or set(by_name) != expected_names:
        raise SystemExit("Unexpected actual v8 Room entity inventory")
    for prior in old["entities"]:
        if by_name[prior["tableName"]] != prior:
            raise SystemExit("v8 changed an original v7 entity; preserve generated evidence and stop")
    expected = {
        "permanent_erasure_receipts": {
            "entryId": "TEXT", "sourceRevision": "INTEGER", "generation": "INTEGER",
            "mutationId": "TEXT", "phase": "TEXT", "plan": "TEXT", "packageProof": "TEXT"},
        "record_package_receipts": {"id": "TEXT", "entryId": "TEXT", "sourceRevision": "INTEGER",
            "operation": "TEXT", "state": "TEXT"},
        "owned_file_creations": {"bucket": "TEXT", "name": "TEXT", "entryId": "TEXT",
            "state": "TEXT", "identity": "TEXT"},
    }
    for table, columns in expected.items():
        entity = by_name[table]
        fields = entity["fields"]
        if len(fields) != len(columns) or {f["columnName"] for f in fields} != set(columns) or \
                any(f["affinity"] != columns[f["columnName"]] or f["notNull"] is not True for f in fields) or \
                entity.get("foreignKeys"):
            raise SystemExit("v8 receipt/ownership columns or FK inventory changed: " + table)
    for table, keys in {"permanent_erasure_receipts": ["entryId"],
                        "record_package_receipts": ["id"],
                        "owned_file_creations": ["bucket", "name"]}.items():
        if by_name[table]["primaryKey"]["columnNames"] != keys:
            raise SystemExit("v8 receipt/ownership primary key changed: " + table)
    indexes = by_name["permanent_erasure_receipts"]["indices"]
    if len(indexes) != 1 or indexes[0]["columnNames"] != ["mutationId"] or indexes[0]["unique"] is not True:
        raise SystemExit("v8 lacks unique stable erasure intent identity")
    indexes = by_name["owned_file_creations"]["indices"]
    if len(indexes) != 1 or indexes[0]["columnNames"] != ["entryId"] or indexes[0]["unique"] is not False:
        raise SystemExit("v8 lacks exact source ownership index")
    if state["mode"] == "CHECKED_IN":
        if digest(generated) != state["expected_sha256"] or generated != asset.read_bytes() or \
                generated != checkout(export) or generated != checkout(asset):
            raise SystemExit("KSP changed committed v8 bytes; do not overwrite or accept drift")
    else:
        if asset.exists():
            raise SystemExit("Unexpected v8 fixture appeared during bootstrap")
        asset.write_bytes(generated)
    state.update({"generated_sha256": digest(generated), "bytes": len(generated),
        "scope": "exact checkout bytes verified" if state["mode"] == "CHECKED_IN" else
        "actual generated untracked v8 schema/fixture; commit bytes then require another exact-head CI"})
    receipt.write_text(json.dumps(state, indent=2) + "\n")
    print("PERMANENT_ERASURE_SCHEMA=" + json.dumps(state, separators=(",", ":")))
else:
    raise SystemExit("Usage: verify-permanent-erasure-schema.py --capture|--verify")
