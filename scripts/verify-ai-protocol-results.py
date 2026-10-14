#!/usr/bin/env python3
"""Require the actual JVM method inventory, zero failures/errors/skips, and inspectable XML."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent


def input_fingerprint():
    inputs = []
    for base in ("core/ai", "core/model", "core/domain", "core/data", "checks/ai-protocol"):
        for path in (root / base).rglob("*"):
            relative = path.relative_to(root)
            if any(part in ("build", ".gradle", ".kotlin") for part in relative.parts):
                continue
            if path.is_file():
                inputs.append(path)
    inputs.extend(root / path for path in ("gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties",
                                          "gradle/wrapper/gradle-wrapper.jar", "scripts/jvm-gradle.py",
                                          "scripts/verify-ai-http-dependencies.py", "scripts/verify-ai-protocol-results.py"))
    # The host now compiles actual ViewModel/controller sources; bind their upstream inputs too.
    inputs.extend((root / "app/src/main/java/com/t1doo/aiyou/records").glob("*.kt"))
    inputs.extend(root / path for path in ("app/src/main/java/com/t1doo/aiyou/MainActivity.kt",
        "app/src/main/AndroidManifest.xml", "app/build.gradle.kts", "scripts/inspect-record-ai-permissions.py"))
    fingerprint = hashlib.sha256()
    for path in sorted(set(inputs)):
        fingerprint.update(str(path.relative_to(root)).encode() + b"\0")
        fingerprint.update(hashlib.sha256(path.read_bytes()).digest())
    return fingerprint.hexdigest()


modules = {"ai": root / "core/ai", "domain": root / "core/domain",
           "remote-text": root / "checks/ai-protocol/remote-text"}
source_sha256 = input_fingerprint()
if len(sys.argv) == 3 and sys.argv[1] == "--capture" and sys.argv[2] in modules:
    output = modules[sys.argv[2]] / "build/reports/inputs-at-test.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"source_sha256": source_sha256}) + "\n")
    task = "core:" + sys.argv[2] if sys.argv[2] != "remote-text" else "remote-text"
    print(f"Captured inputs before {task}:test")
    raise SystemExit(0)
if len(sys.argv) != 1:
    raise SystemExit("Usage: verify-ai-protocol-results.py [--capture ai|domain|remote-text]")

results = []
for module, directory in modules.items():
    task = "core:" + module if module != "remote-text" else "remote-text"
    capture = directory / "build/reports/inputs-at-test.json"
    if not capture.is_file() or json.loads(capture.read_text()).get("source_sha256") != source_sha256:
        raise SystemExit(f"Missing or stale execution inputs for {task}; rerun actual tests")
    expected = set()
    for source in (directory / "src/test/kotlin").rglob("*.kt"):
        text = source.read_text()
        package = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)
        test_class = re.search(r"\bclass\s+(\w+)", text)
        methods = re.findall(r"@Test(?:\s*\([^)]*\))?\s+fun\s+(\w+)\s*\(", text)
        if methods and (package is None or test_class is None):
            raise SystemExit(f"Cannot collect test inventory: {source.relative_to(root)}")
        for method in methods:
            key = (package[1] + "." + test_class[1], method)
            if key in expected:
                raise SystemExit("Duplicate source test method")
            expected.add(key)
    if not expected:
        raise SystemExit(f"No expected tests in {task}")
    actual = set()
    evidence = []
    xmls = sorted((directory / "build/test-results/test").glob("TEST-*.xml"))
    if not xmls:
        raise SystemExit(f"Missing actual XML for {task}; NO-SOURCE is not PASS")
    for xml in xmls:
        if xml.stat().st_mtime_ns < capture.stat().st_mtime_ns:
            raise SystemExit(f"Stale XML predates execution inputs: {xml.name}")
        suite = ET.parse(xml).getroot()
        cases = suite.findall("testcase")
        if int(suite.get("tests", "-1")) != len(cases) or not cases:
            raise SystemExit(f"Invalid/nonempty test count required: {xml.name}")
        if any(int(suite.get(field, "0")) for field in ("failures", "errors", "skipped")):
            raise SystemExit(f"Failed, errored or skipped suite: {xml.name}")
        for case in cases:
            if any(case.find(field) is not None for field in ("failure", "error", "skipped")):
                raise SystemExit(f"Nonpassing test: {xml.name}")
            key = (case.get("classname"), case.get("name"))
            if key in actual:
                raise SystemExit("Duplicate actual test method")
            actual.add(key)
        evidence.append({"path": str(xml.relative_to(root)), "sha256": hashlib.sha256(xml.read_bytes()).hexdigest(),
                         "tests": len(cases)})
    if actual != expected:
        raise SystemExit(f"Method inventory mismatch for {task}: missing={sorted(expected-actual)}, extra={sorted(actual-expected)}")
    results.append({"module": task, "tests": len(actual), "failures": 0, "errors": 0, "skipped": 0,
                    "methods": [list(key) for key in sorted(actual)], "xml": evidence})

report = {"status": "PASS", "scope": "actual App ViewModel/controller on JVM platform shells, source-bound sending, product remote/tag/task SQL transactions and manual-tag/task AEAD on host SQLite, loopback HTTP and domain; local SQL driver is test-only; no Android/SQLCipher/live provider/device/native App acceptance",
          "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
          "source_sha256": source_sha256, "results": results,
          "d02_native_acceptance": "NOT RUN", "full_d04_acceptance": "OPEN",
          "record_package_scope": "actual platform JCA and independent synthetic Node/OpenSSL fixture plus product limited new-copy SQL restore on host SQLite",
          "full_d08_acceptance": "OPEN", "native_saf_keystore_fresh_install_restore": "NOT RUN"}
output = root / "checks/ai-protocol/build/reports/verified-contracts.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps({"status": report["status"], "source_sha256": report["source_sha256"],
                  "tests": {item["module"]: item["tests"] for item in results}, "report": str(output)}))
