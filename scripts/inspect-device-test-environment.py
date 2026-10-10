#!/usr/bin/env python3
"""Read-only inventory: never installs SDK packages or accepts agreements."""
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "/nonexistent-sdk")))
packages = []
for manifest in sorted(root.glob("**/package.xml")) if root.is_dir() else []:
    tree = ET.parse(manifest).getroot()
    for package in tree.iter():
        if package.tag.split("}")[-1] != "localPackage":
            continue
        licenses = [item.attrib.get("ref") for item in package if item.tag.split("}")[-1] == "uses-license"]
        packages.append({"package": package.attrib.get("path"), "license_refs": licenses})
print(json.dumps({"kind": "read-only SDK inventory", "packages": packages,
                  "accepted_license_hashes": {name: (root / "licenses" / name).read_text().split()
                      for name in ("android-sdk-license", "android-sdk-preview-license", "android-sdk-arm-dbt-license")
                      if (root / "licenses" / name).is_file()},
                  "kvm_read_write": os.access("/dev/kvm", os.R_OK | os.W_OK),
                  "adb_present": (root / "platform-tools/adb").is_file(),
                  "emulator_present": (root / "emulator/emulator").is_file()}, ensure_ascii=False))
