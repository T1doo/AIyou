#!/usr/bin/env python3
"""Read actual APK bytes; packaging evidence is not native/device validation."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "/nonexistent-sdk")))
zipalign = sdk / "build-tools/36.0.0/zipalign"
if not zipalign.is_file():
    raise SystemExit("NOT RUN: required existing Build Tools 36.0.0 zipalign is absent; no installation attempted")

app = root / "app/build/outputs/apk/debug/app-debug.apk"
tests = sorted((root / "core/data/build/outputs/apk/androidTest/debug").glob("*.apk"))
if not app.is_file() or len(tests) != 1:
    raise SystemExit("FAIL: require the actual app APK and exactly one compiled storage-test APK")

report = {"scope": "actual APK packaging only; native execution NOT RUN", "apks": []}
output = root / "app/build/reports/d02-apk-audit.json"
output.parent.mkdir(parents=True, exist_ok=True)
try:
    for apk in [app, tests[0]]:
        raw = apk.read_bytes()
        item = {"path": str(apk.relative_to(root)), "bytes": len(raw),
                "sha256": hashlib.sha256(raw).hexdigest()}
        report["apks"].append(item)
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise RuntimeError("Duplicate APK entries")
            natives = sorted(n for n in names if n.startswith("lib/") and n.endswith(".so"))
            item["native_entries"] = [{"path": n, "sha256": hashlib.sha256(archive.read(n)).hexdigest()}
                                      for n in natives]
            expected = {f"lib/{abi}/libsqlcipher.so" for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")}
            if not expected.issubset(natives):
                raise RuntimeError("Expected pinned SQLCipher native ABIs absent")
            if apk == app:
                materials = sorted((root / "app/src/main/assets/licenses").glob("*.txt"))
                if len(materials) != 7:
                    raise RuntimeError("Expected seven bounded license materials")
                item["license_assets"] = []
                for material in materials:
                    name = "assets/licenses/" + material.name
                    content = archive.read(name)
                    if content != material.read_bytes():
                        raise RuntimeError("Bundled license bytes differ: " + material.name)
                    item["license_assets"].append({"path": name, "bytes": len(content),
                                                  "sha256": hashlib.sha256(content).hexdigest()})
        result = subprocess.run([str(zipalign), "-c", "-P", "16", "4", str(apk)],
                                capture_output=True, text=True, timeout=60)
        apk.with_suffix(".zipalign.txt").write_text(result.stdout + result.stderr)
        item["zipalign_16k_check_exit"] = result.returncode
        if result.returncode:
            raise RuntimeError("APK zip alignment check failed; preserved .zipalign.txt log")
        print("PASS:", item["path"], item["sha256"], "ZIP alignment; native execution NOT RUN")
except Exception as error:
    report["failure"] = str(error)
    raise
finally:
    output.write_text(json.dumps(report, indent=2) + "\n")
