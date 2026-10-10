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
# SDK repository metadata uses android-sdk-license for both pinned packages.
# This is the SHA-1 after Android's TrimStringAdapter normalization, not a
# hash of the raw XML text. Only read an existing record; never create one.
expected_license_hash = "24333f8a63b6825ea9c5514f83c2829b004d1fee"
if expected_license_hash not in license_file.read_text().splitlines():
    raise SystemExit("No matching existing SDK terms acceptance; see docs/BUILD.md.")

required = {
    "platforms/android-37.0": "2",
    "build-tools/36.0.0": "36.0.0",
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
print("Matching existing SDK acceptance record found; no new agreement was accepted by this check.")
