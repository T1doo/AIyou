#!/usr/bin/env python3
"""Preserve the actual Room v7 KSP export; bootstrap is not final exact-head evidence."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
export = root / "core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/7.json"
asset = root / "core/data/src/androidTest/assets/records-schema-v7.json"
receipt = root / ".local-evidence/local-index/schema-generation.json"
head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()

def checkout(path):
    return subprocess.check_output(["git", "show", head + ":" + path.relative_to(root).as_posix()], cwd=root)

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

if sys.argv[1:] == ["--capture"]:
    if export.exists() != asset.exists():
        raise SystemExit("Partial v7 schema/fixture state; preserve and stop")
    if export.exists():
        before = export.read_bytes()
        if before != asset.read_bytes() or before != checkout(export) or before != checkout(asset):
            raise SystemExit("v7 schema/fixture differs from exact checkout")
        mode, expected = "CHECKED_IN", digest(before)
    else:
        tracked = subprocess.check_output(["git", "ls-files", "--", str(export.relative_to(root)),
            str(asset.relative_to(root))], cwd=root)
        if tracked:
            raise SystemExit("Tracked v7 schema/fixture is missing; stop")
        mode, expected = "BOOTSTRAP", None
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps({"head": head, "mode": mode, "expected_sha256": expected}) + "\n")
elif sys.argv[1:] == ["--verify"]:
    state = json.loads(receipt.read_text())
    if state["head"] != head or state["mode"] not in ("BOOTSTRAP", "CHECKED_IN"):
        raise SystemExit("Checkout changed during v7 generation")
    generated = export.read_bytes()
    schema = json.loads(generated)["database"]
    old = json.loads((export.parent / "6.json").read_text())["database"]
    by_name = {e["tableName"]: e for e in schema["entities"]}
    expected_names = {e["tableName"] for e in old["entities"]} | {
        "local_search_fts", "local_search_documents", "local_search_epoch"}
    if schema["version"] != 7 or len(schema["entities"]) != 13 or set(by_name) != expected_names:
        raise SystemExit("Unexpected actual v7 Room entity inventory")
    for prior in old["entities"]:
        if by_name[prior["tableName"]] != prior:
            raise SystemExit("v7 changed an original v6 entity; preserve generated evidence and stop")
    fts = by_name["local_search_fts"]
    if "USING FTS4" not in fts["createSql"].upper() or {f["columnName"] for f in fts["fields"]} != {
        "entryId", "bodyTokens", "tagTokens"}:
        raise SystemExit("v7 lacks the actual expected Room FTS4 entity")
    for table, names in {
        "local_search_documents": {"entryId", "sourceRevision", "contentHash", "tagVersion",
            "visibilityGeneration", "tokenizerVersion", "tokenizerProfile", "indexedEpoch"},
        "local_search_epoch": {"slot", "epoch", "tokenizerVersion", "tokenizerProfile"},
    }.items():
        fields = by_name[table]["fields"]
        if len(fields) != len(names) or {f["columnName"] for f in fields} != names or not any(
                f["columnName"] == "tokenizerProfile" and f["affinity"] == "TEXT" and f["notNull"] is True
                for f in fields):
            raise SystemExit("v7 lacks the persisted tokenizer runtime profile binding: " + table)
    if state["mode"] == "CHECKED_IN":
        if digest(generated) != state["expected_sha256"] or generated != asset.read_bytes() or \
                generated != checkout(export) or generated != checkout(asset):
            raise SystemExit("KSP changed committed v7 bytes; do not overwrite or accept drift")
    else:
        if asset.exists():
            raise SystemExit("Unexpected v7 fixture appeared during bootstrap")
        asset.write_bytes(generated)
    state.update({"generated_sha256": digest(generated), "bytes": len(generated),
        "scope": "exact checkout bytes verified" if state["mode"] == "CHECKED_IN" else
        "actual generated untracked v7 schema/fixture; commit bytes then require another exact-head CI"})
    receipt.write_text(json.dumps(state, indent=2) + "\n")
    print("LOCAL_SEARCH_SCHEMA=" + json.dumps(state, separators=(",", ":")))
else:
    raise SystemExit("Usage: verify-local-search-schema.py --capture|--verify")
