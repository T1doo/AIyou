#!/usr/bin/env python3
"""Read actual APK bytes; packaging evidence is not native/device validation."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
sqlcipher_sha256 = {
    "arm64-v8a": "32d4c1bb89a90b59a3d6be950bbc36c133f8762f627b333a3512205c8037a48b",
    "armeabi-v7a": "c2084efe2469e69ffafa09425800de4bf62fb34cb246b645ac7951294f3e28ba",
    "x86": "d26f8ccde7bde6b743ab110874a322b7d8de29123b1675d0ec9508b931c9f7b1",
    "x86_64": "81b98e79ea7176482daf40497f34fc7eb701c661d13c09dada66a1a51e8d9561",
}
license_names = {
    "Apache-2.0.txt", "DEPENDENCIES.txt", "LibTomCrypt-1.18.2.txt",
    "SQLite-core.txt", "SQLCipher-source-attributions.txt",
    "sqlcipher-android-4.19.1.txt", "sqlcipher-4.19.1.txt",
}
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
            for abi, digest in sqlcipher_sha256.items():
                member = f"lib/{abi}/libsqlcipher.so"
                if hashlib.sha256(archive.read(member)).hexdigest() != digest:
                    raise RuntimeError("SQLCipher 4.19.1 AAR bytes differ: " + member)
            if apk == app:
                materials = sorted((root / "app/src/main/assets/licenses").glob("*.txt"))
                if {material.name for material in materials} != license_names:
                    raise RuntimeError("Expected seven pinned 4.19.1 license materials")
                if {n for n in names if n.startswith("assets/licenses/")} != {
                    "assets/licenses/" + name for name in license_names
                }:
                    raise RuntimeError("APK license inventory differs from pinned materials")
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
