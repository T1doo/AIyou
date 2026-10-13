#!/usr/bin/env python3
"""Inspect actual merged APK permissions with existing licensed build tools; never run a device."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
apk = root / "app/build/outputs/apk/debug/app-debug.apk"
sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
if not sdk:
    raise SystemExit("Existing licensed Android SDK is unavailable; permission inspection NOT RUN")
aapt = Path(sdk) / "build-tools/36.0.0/aapt2"
if not aapt.is_file() or not apk.is_file():
    raise SystemExit("Existing pinned aapt2 or actual app APK is absent; permission inspection NOT RUN")
manifest = ET.parse(root / "app/src/main/AndroidManifest.xml").getroot()
android_name = "{http://schemas.android.com/apk/res/android}name"
source_permissions = [node.get(android_name) for node in manifest if node.tag.startswith("uses-permission")]
if "android.permission.INTERNET" in source_permissions:
    raise SystemExit("This slice must not add INTERNET permission")
# Official syntax: https://developer.android.com/tools/aapt2#dump
actual = subprocess.run([str(aapt), "dump", "permissions", str(apk)],
                        text=True, capture_output=True, check=True, timeout=30).stdout
if not actual.strip() or "com.t1doo.aiyou" not in actual:
    raise SystemExit("Missing actual package permission dump")
permissions = sorted(set(re.findall(r"uses-permission[^\n]*name=['\"]([^'\"]+)['\"]", actual)))
if "android.permission.INTERNET" in actual:
    raise SystemExit("Actual merged APK unexpectedly requests INTERNET permission")
report = {"status": "PASS", "source_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
          "app_apk": str(apk.relative_to(root)), "bytes": apk.stat().st_size,
          "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "permissions": permissions,
          "internet_permission": False, "aapt2": str(aapt), "scope": "compiled APK manifest only; device execution NOT RUN"}
output = root / "app/build/reports/record-ai-permissions.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, indent=2) + "\n")
print("D04_RECORD_AI_PERMISSIONS=" + json.dumps(report))
