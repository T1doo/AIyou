#!/usr/bin/env python3
"""Verify same-run schema/host XML evidence; never restore build outputs over sources."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import xml.etree.ElementTree as ET

SCHEMA_METADATA = {
    ".local-evidence/f05-event-date/schema-generation.json": 11,
    ".local-evidence/f05-manual-people/schema-generation.json": 12,
    ".local-evidence/d04-model-configuration/schema-generation.json": 13,
    ".local-evidence/d04-model-capability/schema-generation.json": 14,
}
SCHEMA_EXTRAS = ["core/data/src/androidTest/assets/records-schema-v%d.json" % n for n in range(11, 15)] + [
    "app/gradle.lockfile", "core/data/gradle.lockfile", "core/domain/gradle.lockfile", "core/designsystem/gradle.lockfile"]
BACKUP_XML = {
    "TEST-com.t1doo.aiyou.data.FullBackupTest.xml": ("com.t1doo.aiyou.data.FullBackupTest", 18),
    "TEST-com.t1doo.aiyou.records.FullBackupControllerTest.xml": ("com.t1doo.aiyou.records.FullBackupControllerTest", 8),
}
DOMAIN_TARGET = "core/domain/build/test-results/test"
BACKUP_TARGET = "checks/ai-protocol/remote-text/build/test-results/test"


def require(value, message):
    if not value:
        raise ValueError(message)


def read(folder, relative):
    path = PurePosixPath(relative)
    require(not path.is_absolute() and bool(path.parts) and all(p not in (".", "..") for p in path.parts), "Unsafe evidence path")
    current = folder
    for part in path.parts:
        current = current / part
        require(not current.is_symlink(), "Evidence symlink")
    require(current.resolve().is_relative_to(folder.resolve()) and current.is_file(), "Missing/outside evidence")
    require(current.stat().st_size <= 4 * 1024 * 1024, "Evidence file exceeds transfer bound")
    return current.read_bytes()


def provenance(folder, stage, env):
    receipt = json.loads(read(folder, "build/ci-stage-evidence/%s/receipt.json" % stage))
    require(receipt["source_sha"] == env["GITHUB_SHA"] and receipt["run_id"] == int(env["GITHUB_RUN_ID"]) and
            receipt["run_attempt"] == int(env["GITHUB_RUN_ATTEMPT"]), "Foreign source/run/attempt")
    require(receipt["job"] == "contracts" and receipt["stage"] == stage and receipt["producer_outcome"] == "success", "Unconfirmed producer")
    return receipt


def checkout_files(root):
    paths = subprocess.check_output(["git", "ls-files", "--", "core/data/schemas", *SCHEMA_EXTRAS], cwd=root, text=True).splitlines()
    require(paths and set(SCHEMA_EXTRAS) <= set(paths), "Missing committed schema/lock inputs")
    return paths


def checkout_bytes(root, name):
    return subprocess.check_output(["git", "show", "HEAD:" + name], cwd=root)


def validate_schema(root, folder, env):
    provenance(folder, "schema", env)
    paths = checkout_files(root)
    expected = set(paths) | set(SCHEMA_METADATA) | {"build/ci-stage-evidence/schema/receipt.json"}
    actual = {str(p.relative_to(folder)).replace(os.sep, "/") for p in folder.rglob("*") if p.is_file() or p.is_symlink()}
    require(actual == expected, "Incomplete or unexpected schema artifact inventory")
    hashes = {}
    for name in paths:
        raw = read(folder, name)
        require(raw == checkout_bytes(root, name) == (root / name).read_bytes(), "Generated schema/lock differs from exact checkout")
        hashes[name] = hashlib.sha256(raw).hexdigest()
    for name, version in SCHEMA_METADATA.items():
        metadata = json.loads(read(folder, name))
        digest = hashes["core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/%d.json" % version]
        require(metadata["head"] == env["GITHUB_SHA"] and metadata["mode"] == "CHECKED_IN" and
                metadata["expected_sha256"] == metadata["generated_sha256"] == digest, "Unconfirmed generated schema metadata")
    return hashes


def validate_domain(folder, env):
    provenance(folder, "protocol", env)
    report = json.loads(read(folder, "checks/ai-protocol/build/reports/verified-contracts.json"))
    require(report["head"] == env["GITHUB_SHA"] and report["status"] == "PASS", "Foreign/unverified protocol receipt")
    capture = json.loads(read(folder, "core/domain/build/reports/inputs-at-test.json"))
    require(capture["source_sha256"] == report["source_sha256"], "Protocol execution input mismatch")
    modules = [m for m in report["results"] if m["module"] == "core:domain"]
    require(len(modules) == 1, "Require one verified domain module")
    module = modules[0]
    require(module["tests"] >= 3 and module["xml"], "Missing domain execution evidence")
    require(all(module[k] == 0 for k in ("failures", "errors", "skipped")), "Nonpassing imported domain module")
    outputs = []
    observed = []
    names = set()
    for item in module["xml"]:
        path = PurePosixPath(item["path"])
        require(path.name.startswith("TEST-") and path.suffix == ".xml" and path.name not in names, "Invalid/duplicate domain XML")
        names.add(path.name)
        raw = read(folder, item["path"])
        require(hashlib.sha256(raw).hexdigest() == item["sha256"], "Domain XML digest mismatch")
        suite = ET.fromstring(raw)
        cases = list(suite.iter("testcase"))
        require(int(suite.get("tests", "-1")) == len(cases) == item["tests"] > 0 and
                all(int(suite.get(k, "0")) == 0 for k in ("failures", "errors", "skipped")), "Invalid/nonpassing domain XML")
        for case in cases:
            require(all(case.find(k) is None for k in ("failure", "error", "skipped")), "Nonpassing domain method")
            observed.append([case.get("classname"), case.get("name")])
        outputs.append((path.name, raw))
    require(len(observed) == len(set(map(tuple, observed))) == module["tests"] and
            sorted(observed) == sorted(module["methods"]), "Domain method inventory mismatch")
    return outputs


def validate_backup(folder, env):
    provenance(folder, "protocol", env)
    report = json.loads(read(folder, "checks/ai-protocol/build/reports/verified-contracts.json"))
    require(report["head"] == env["GITHUB_SHA"] and report["status"] == "PASS", "Foreign/unverified protocol receipt")
    capture = json.loads(read(folder, "checks/ai-protocol/remote-text/build/reports/inputs-at-test.json"))
    require(capture["source_sha256"] == report["source_sha256"], "Backup execution input mismatch")
    modules = [m for m in report["results"] if m["module"] == "remote-text"]
    require(len(modules) == 1, "Require one verified remote-text module")
    module = modules[0]
    require(module["tests"] >= sum(count for _, count in BACKUP_XML.values()) and
            all(module[k] == 0 for k in ("failures", "errors", "skipped")), "Nonpassing imported backup module")
    outputs = []
    for name, (cls, count) in BACKUP_XML.items():
        items = [item for item in module["xml"] if PurePosixPath(item["path"]).name == name]
        require(len(items) == 1, "Missing/duplicate required backup XML")
        item = items[0]
        raw = read(folder, item["path"])
        require(hashlib.sha256(raw).hexdigest() == item["sha256"], "Backup XML digest mismatch")
        suite = ET.fromstring(raw)
        cases = list(suite.iter("testcase"))
        require(int(suite.get("tests", "-1")) == len(cases) == item["tests"] == count and
                all(int(suite.get(k, "0")) == 0 for k in ("failures", "errors", "skipped")), "Invalid/nonpassing backup XML")
        observed = []
        for case in cases:
            require(case.get("classname") == cls and all(case.find(k) is None for k in ("failure", "error", "skipped")),
                    "Nonpassing/foreign backup method")
            observed.append([cls, case.get("name")])
        expected = [method for method in module["methods"] if method[0] == cls]
        require(len(observed) == len(set(map(tuple, observed))) == len(expected) == count and
                sorted(observed) == sorted(expected), "Backup method inventory mismatch")
        outputs.append((name, raw))
    return outputs


def transfer(root, schema, domain, env):
    hashes = validate_schema(root, schema, env)
    outputs = []
    if domain is not None:
        outputs.extend((DOMAIN_TARGET, name, raw) for name, raw in validate_domain(domain, env))
        outputs.extend((BACKUP_TARGET, name, raw) for name, raw in validate_backup(domain, env))
    for target in {root / folder for folder, _, _ in outputs}:
        require(not target.exists() or not any(target.iterdir()), "Refuse to overwrite existing host evidence")
    # Publish only after every schema, provenance and XML check has succeeded.
    for folder, name, raw in outputs:
        target = root / folder
        target.mkdir(parents=True, exist_ok=True)
        with (target / name).open("xb") as output:
            output.write(raw)
    result = {"source_sha": env["GITHUB_SHA"], "run_id": int(env["GITHUB_RUN_ID"]),
              "run_attempt": int(env["GITHUB_RUN_ATTEMPT"]), "schema_files": hashes,
              "host_xml": [{"path": folder + "/" + name, "sha256": hashlib.sha256(raw).hexdigest()} for folder, name, raw in outputs],
              "scope": "verified same-run host evidence; domain/backup tests were NOT rerun by this consumer; no source files replaced"}
    destination = root / "build/ci-stage-evidence/transferred-inputs/result.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("x", encoding="utf-8") as output:
        json.dump(result, output, indent=2)
        output.write("\n")
    return destination


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--schema-dir", type=Path, required=True)
    parser.add_argument("--domain-dir", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip() == os.environ["GITHUB_SHA"], "Wrong consumer checkout")
    require(not subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=root, text=True), "Changed consumer sources")
    print(transfer(root, args.schema_dir, args.domain_dir, os.environ))
