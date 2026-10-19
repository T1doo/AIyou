#!/usr/bin/env python3
"""Collect actual @org.junit.Test methods from compiled APK DEX; compilation is never device PASS."""
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import zipfile
import zlib
from d02_device_acceptance import APP_PROBE, NATIVE_CASES, NATIVE_METHOD_COUNT, STORAGE_PROBE, collect_source

ROOT = Path(__file__).resolve().parents[1]


def dex_tests(data):
    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]
    def uleb(offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = data[offset]; offset += 1; value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise ValueError("Invalid DEX uleb")
    if data[:4] != b"dex\n" or data[7] != 0 or u32(32) != len(data) or u32(36) != 112 or u32(40) != 0x12345678:
        raise ValueError("Unsupported or truncated DEX header")
    if u32(8) != zlib.adler32(data[12:]) & 0xffffffff or data[12:32] != hashlib.sha1(data[32:]).digest():
        raise ValueError("DEX checksum/signature mismatch")
    strings = []
    for index in range(u32(56)):
        _, start = uleb(u32(u32(60) + 4 * index))
        strings.append(data[start:data.index(0, start)].decode("utf-8", errors="replace"))
    types = [strings[u32(u32(68) + 4 * index)] for index in range(u32(64))]
    methods = []
    for index in range(u32(88)):
        owner, _, name = struct.unpack_from("<HHI", data, u32(92) + 8 * index)
        methods.append((types[owner][1:-1].replace("/", "."), strings[name]))
    tests = {}
    for index in range(u32(96)):
        definition = u32(100) + 32 * index
        class_name = types[u32(definition)][1:-1].replace("/", ".")
        annotations = u32(definition + 20)
        if not annotations:
            continue
        _, fields, annotated_methods, _ = struct.unpack_from("<IIII", data, annotations)
        found = []
        for item in range(annotated_methods):
            method_index, annotation_set = struct.unpack_from("<II", data, annotations + 16 + fields * 8 + item * 8)
            for entry in range(u32(annotation_set)):
                annotation_item = u32(annotation_set + 4 + entry * 4)
                annotation_type, _ = uleb(annotation_item + 1)
                if types[annotation_type] == "Lorg/junit/Test;":
                    owner, name = methods[method_index]
                    if owner != class_name:
                        raise ValueError("DEX annotation method owner mismatch")
                    found.append(name)
        if found:
            if class_name in tests or len(found) != len(set(found)):
                raise ValueError("Duplicate actual annotated class/method")
            tests[class_name] = found
    return tests


def collect_apk(apk):
    actual = {}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex") and "/" not in name:
                for class_name, methods in dex_tests(archive.read(name)).items():
                    if class_name in actual:
                        raise ValueError("Duplicate test class across DEX files")
                    actual[class_name] = methods
    if not actual:
        raise ValueError("No compiled actual @Test methods")
    return actual


def verify_inventory(actual, expected):
    scoped = {name: tuple(methods) for name, methods in actual.items() if name.startswith("com.t1doo.aiyou.")}
    if set(scoped) != set(expected) or any(set(scoped[name]) != set(methods) or
            len(scoped[name]) != len(methods) for name, methods in expected.items()):
        raise ValueError("Actual DEX @Test inventory differs from exact registered project methods")


def verify_schema_assets(apk, root):
    schemas = {}
    with zipfile.ZipFile(apk) as archive:
        for version in range(2, 14):
            path = f"assets/records-schema-v{version}.json"
            if archive.namelist().count(path) != 1:
                raise ValueError("Missing/duplicate actual native schema asset: " + path)
            content = archive.read(path)
            expected = (Path(root) / f"core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/{version}.json").read_bytes()
            if content != expected:
                raise ValueError("Actual APK native fixture differs from exact Room export: " + path)
            schemas[str(version)] = {"path": path, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}
    return schemas


def main():
    collect_source(ROOT)
    storage = sorted((ROOT / "core/data/build/outputs/apk/androidTest/debug").glob("*.apk"))
    app_test = sorted((ROOT / "app/build/outputs/apk/androidTest/debug").glob("*.apk"))
    if len(storage) != 1 or len(app_test) != 1:
        raise ValueError("Require one actual storage APK and one production application test APK")
    expected_storage = {**NATIVE_CASES, STORAGE_PROBE: ("acknowledgedSaveAndUnsubmittedDraftSurviveProcessRestart",)}
    expected_app = {APP_PROBE: ("formalEditReceiptSurvivesKilledProcessAndActivityConfirmsWithoutAnotherRevision",)}
    for apk, expected in ((storage[0], expected_storage), (app_test[0], expected_app)):
        actual = collect_apk(apk)
        verify_inventory(actual, expected)
    apks = {"app": ROOT / "app/build/outputs/apk/debug/app-debug.apk", "storage_test": storage[0], "app_test": app_test[0]}
    report = {"source_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "result": "PASS: actual compiled APK DEX @Test collection only; all device execution NOT RUN",
              "native_method_count": NATIVE_METHOD_COUNT, "native_cases": NATIVE_CASES, "storage_probe": STORAGE_PROBE,
              "application_probe": APP_PROBE, "storage_schema_assets": verify_schema_assets(storage[0], ROOT), "apks": {}}
    for role, apk in apks.items():
        data = apk.read_bytes()
        report["apks"][role] = {"path": str(apk.relative_to(ROOT)), "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    output = ROOT / "app/build/reports/device-apk-manifest.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print("D02_COMPILED_DEVICE_INVENTORY=" + json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
