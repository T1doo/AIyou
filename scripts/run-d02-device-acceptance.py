#!/usr/bin/env python3
"""Existing, explicitly selected dedicated device only. No SDK download, terms, AVD or permission changes."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
from d02_device_acceptance import APP_PHASES, APP_PROBE, NATIVE_CASES, NATIVE_METHOD_COUNT, collect_source, stop_packages, run_storage

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("device_experiment", ROOT / "scripts/run-d02-device-experiment.py")
experiment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(experiment)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--collect-source", action="store_true", help="read-only source inventory, never device execution")
    parser.add_argument("--serial", help="existing dedicated API26+ device, unlocked and ADB authorized")
    parser.add_argument("--expected-sha")
    parser.add_argument("--apk-manifest", type=Path, help="actual CI generated device-apk-manifest.json, exact SHA/hashes")
    parser.add_argument("--output", type=Path, help="new owned evidence directory, must not exist")
    parser.add_argument("--budget-seconds", type=int, default=35 * 60,
                        help="overall budget including cleanup reserve; may only reduce the existing ceiling")
    parser.add_argument("--emit-ci-evidence", action="store_true", help="emit bounded, hash-framed text logs")
    args = parser.parse_args()
    if not 90 <= args.budget_seconds <= 35 * 60:
        parser.error("budget must be 90..2100 seconds; cannot extend existing ceiling")
    inventory = collect_source(ROOT)
    if args.collect_source:
        print(json.dumps({"result": f"PASS: {NATIVE_METHOD_COUNT} source methods collected; APK/device collection NOT RUN", "native_cases": inventory}, indent=2))
        return
    if not all((args.serial, args.expected_sha, args.apk_manifest, args.output)):
        parser.error("require --serial --expected-sha --apk-manifest --output; see docs/D02_DEVICE_ACCEPTANCE.md")
    if not re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial) or not re.fullmatch(r"[0-9a-f]{40}", args.expected_sha):
        parser.error("invalid explicit serial/SHA")
    args.output.mkdir(parents=True, exist_ok=False)
    experiment.OUTPUT = args.output.resolve()
    experiment.EXPERIMENT_DEADLINE = time.monotonic() + args.budget_seconds
    if args.emit_ci_evidence:
        from d02_ci_evidence import emit_file
        original_command = experiment.command

        def observed_command(argv, name, **kwargs):
            try:
                return original_command(argv, name, **kwargs)
            finally:
                path = experiment.OUTPUT / (name + ".log")
                if path.is_file():
                    emit_file(path, "device/" + path.name)

        experiment.command = observed_command
    command, instrument = experiment.command, experiment.instrumentation
    env = os.environ.copy()
    sdk = experiment.SDK
    before = experiment.license_snapshot()
    report = {"result": "NOT RUN", "expected_sha": args.expected_sha, "serial": args.serial,
              "source_inventory": inventory, "native_tests": [], "app_phases": [],
              "16k_execution": "NOT RUN", "physical_performance": "NOT RUN", "license_before": before}
    try:
        head = command(["git", "rev-parse", "HEAD"], "source-sha").stdout.strip()
        clean = command(["git", "status", "--porcelain"], "source-status").stdout
        if head != args.expected_sha or clean:
            raise RuntimeError("Require exact clean reviewed checkout")
        # Strict existing license/package verification; this command never accepts or installs.
        command([sys.executable, ROOT / "scripts/check-sdk.py"], "existing-sdk", env=env)
        adb, aapt = sdk / "platform-tools/adb", sdk / "build-tools/36.0.0/aapt"
        if not adb.is_file() or not aapt.is_file():
            raise RuntimeError("Existing SDK adb/aapt unavailable; no installation attempted")
        if command([adb, "-s", args.serial, "get-state"], "device-state", env=env).stdout.strip() != "device":
            raise RuntimeError("Explicit device is not connected and authorized")
        report["api"] = command([adb, "-s", args.serial, "shell", "getprop", "ro.build.version.sdk"], "device-api", env=env).stdout.strip()
        report["abi"] = command([adb, "-s", args.serial, "shell", "getprop", "ro.product.cpu.abi"], "device-abi", env=env).stdout.strip()
        report["page_size"] = command([adb, "-s", args.serial, "shell", "getconf", "PAGE_SIZE"], "device-pages", env=env).stdout.strip()
        if not report["api"].isdigit() or int(report["api"]) < 26:
            raise RuntimeError("Require API26+ device")
        manifest = json.loads(args.apk_manifest.read_text())
        if manifest["source_sha"] != args.expected_sha or manifest["native_method_count"] != NATIVE_METHOD_COUNT or \
                manifest["native_cases"] != {name: list(methods) for name, methods in NATIVE_CASES.items()}:
            raise RuntimeError("APK manifest does not bind exact reviewed SHA and inventory")
        declared = {}
        for role in ("app", "storage_test", "app_test"):
            item = manifest["apks"][role]
            apk = ROOT / item["path"]
            if not apk.resolve().is_relative_to(ROOT.resolve()) or not apk.is_file() or \
                    apk.stat().st_size != item["bytes"] or experiment.digest(apk) != item["sha256"]:
                raise RuntimeError("Exact compiled APK missing/different: " + role)
            badging = command([aapt, "dump", "badging", apk], role + "-badging", env=env).stdout
            xml = command([aapt, "dump", "xmltree", apk, "AndroidManifest.xml"], role + "-manifest", env=env).stdout
            package = re.search(r"^package: name='([^']+)'", badging, re.M).group(1)
            if not re.fullmatch(r"[a-zA-Z0-9_.]+", package):
                raise RuntimeError("Unsafe actual package name")
            if role == "app":
                if package != "com.t1doo.aiyou" or "com.t1doo.aiyou.MainActivity" not in xml:
                    raise RuntimeError("Unexpected production app manifest")
                native = re.search(r"^native-code:\s*(.*)$", badging, re.M)
                if not native or "'" + report["abi"] + "'" not in native.group(1):
                    raise RuntimeError("Actual APK ABI does not match device")
                declared[role] = {"apk": apk, "package": package}
            else:
                target = re.search(r'A: android:targetPackage[^\n]*="([^"]+)"', xml).group(1)
                if "androidx.test.runner.AndroidJUnitRunner" not in xml or not re.fullmatch(r"[a-zA-Z0-9_.]+", target):
                    raise RuntimeError("Unexpected actual test runner/target")
                declared[role] = {"apk": apk, "package": package, "target": target,
                                  "component": package + "/androidx.test.runner.AndroidJUnitRunner"}
        if declared["app_test"]["target"] != declared["app"]["package"]:
            raise RuntimeError("Application instrumentation does not target production app")
        owned = {item["package"] for item in declared.values()} | {declared["storage_test"]["target"]}
        for package in sorted(owned):
            existing = command([adb, "-s", args.serial, "shell", "pm", "path", package], "fresh-" + package, env=env, require=False)
            if existing.stdout.strip() or existing.stderr.strip() or existing.returncode not in (0, 1):
                raise RuntimeError("Require fresh dedicated installation; existing/unknown package retained: " + package)
        for role, item in declared.items():
            flags = [] if role == "app" else ["-t"]
            command([adb, "-s", args.serial, "install", *flags, item["apk"]], "install-" + role, env=env)
        listed = command([adb, "-s", args.serial, "shell", "pm", "list", "instrumentation"], "installed-instrumentation", env=env).stdout
        for role in ("storage_test", "app_test"):
            item = declared[role]
            if not re.search(r"^instrumentation:" + re.escape(item["component"]) + r" \(target=" + re.escape(item["target"]) + r"\)$", listed, re.M):
                raise RuntimeError("Installed instrumentation target differs from APK")
        storage = declared["storage_test"]
        run_storage(command, instrument, adb, args.serial, storage["component"], (storage["package"], storage["target"]), env, report, str(uuid.uuid4()))
        app_test = declared["app_test"]
        token, pids = str(uuid.uuid4()), set()
        for phase in APP_PHASES:
            stop_packages(command, adb, args.serial, (app_test["package"], app_test["target"]), env, "app-" + phase)
            result = instrument(adb, args.serial, app_test["component"], APP_PROBE, 1, "app-" + phase, env,
                ("-e", "aiyouRestartPhase", phase, "-e", "aiyouRestartToken", token), phase=phase, token=token,
                expected_methods=[(APP_PROBE, "formalEditReceiptSurvivesKilledProcessAndActivityConfirmsWithoutAnotherRevision")])
            if result["pid"] in pids:
                raise RuntimeError("Application phases did not prove distinct PIDs")
            pids.add(result["pid"]); report["app_phases"].append(result)
        report["apks"] = manifest["apks"]
        report["result"] = f"PASS: exact {NATIVE_METHOD_COUNT} native methods, 4 external storage phases and 2 production Activity/VM phases; separate 16KB/physical-performance gates remain OPEN"
        # Fresh test installation and synthetic library deliberately retained for review. No uninstall/reset.
    except Exception as error:
        report["result"] = "FAIL: " + str(error) + "; unrecorded device gates NOT RUN; all evidence retained"
        raise
    finally:
        report["license_after"] = experiment.license_snapshot()
        report["acceptance_records_unchanged"] = before == report["license_after"]
        (args.output / "RESULT.json").write_text(json.dumps(report, indent=2) + "\n")
        if args.emit_ci_evidence:
            emit_file(args.output / "RESULT.json", "device/RESULT.json")
        print(json.dumps(report, indent=2))
        if not report["acceptance_records_unchanged"]:
            raise RuntimeError("SDK acceptance records unexpectedly changed")


if __name__ == "__main__":
    main()
