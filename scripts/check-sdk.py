#!/usr/bin/env python3
"""Check the required installed SDK; never install or accept any license."""
import os
from pathlib import Path
import sys

sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
if not sdk:
    raise SystemExit("ANDROID_HOME must point to an SDK with developer-accepted licenses.")
root = Path(sdk)
license_file = root / "licenses/android-sdk-license"
if not license_file.is_file() or not license_file.read_text().strip():
    raise SystemExit("No existing Android SDK acceptance record; see docs/BUILD.md.")

required = {
    "platforms/android-36": "2",
    "build-tools/35.0.0": "35.0.0",
}
for relative, revision in required.items():
    path = root / relative / "source.properties"
    if not path.is_file():
        raise SystemExit(f"Missing SDK package: {relative}; see docs/BUILD.md.")
    properties = dict(
        line.strip().split("=", 1) for line in path.read_text().splitlines()
        if "=" in line and not line.startswith("#")
    )
    actual = properties.get("Pkg.Revision", "").strip()
    if actual != revision:
        raise SystemExit(f"{relative}: expected revision {revision}, found {actual}")
    print(f"PASS {relative} revision {actual}")
print("Existing SDK acceptance record found; no new agreement was accepted by this check.")
