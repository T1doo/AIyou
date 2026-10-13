#!/usr/bin/env python3
"""Verify the locked HTTP JARs actually resolved by Gradle against reviewed payloads."""
import hashlib
import json
import os
from pathlib import Path

root = Path(__file__).resolve().parent.parent
toolchain = Path(os.environ.get("AIYOU_TOOLCHAIN_ROOT", str(root / ".toolchains")))
cache = Path(os.environ.get("GRADLE_USER_HOME", str(toolchain / "gradle-home")))
cache = cache / "caches/modules-2/files-2.1"
manifest = json.loads((root / "checks/ai-protocol/http-dependencies.json").read_text())
verified = []
for artifact in manifest["artifacts"]:
    group, module, version = artifact["coordinate"].split(":")
    jars = list((cache / group / module / version).glob(f"*/{module}-{version}.jar"))
    if not jars:
        raise SystemExit(f"Missing resolved JAR: {artifact['coordinate']}; run the actual JVM contracts first")
    for jar in jars:
        payload = jar.read_bytes()
        if (len(payload) != artifact["bytes"] or
                hashlib.sha1(payload).hexdigest() != artifact["sha1"] or
                hashlib.sha256(payload).hexdigest() != artifact["sha256"]):
            raise SystemExit(f"HTTP dependency checksum mismatch: {artifact['coordinate']}")
    verified.append({"coordinate": artifact["coordinate"], "sha256": artifact["sha256"]})
print(json.dumps({"status": "PASS", "resolved_http_jars": verified}))
