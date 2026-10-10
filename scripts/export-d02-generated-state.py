#!/usr/bin/env python3
"""Expose only generated public schema/lock bytes for exact reproducible review."""
import base64
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
paths = sorted((root / "core/data/schemas").glob("**/*.json"))
paths += [root / path for path in (
    "app/gradle.lockfile", "core/data/gradle.lockfile",
    "core/domain/gradle.lockfile", "core/designsystem/gradle.lockfile",
)]
files = []
for path in paths:
    if not path.is_file():
        continue
    content = path.read_bytes()
    files.append({"path": path.relative_to(root).as_posix(), "bytes": len(content),
                  "sha256": hashlib.sha256(content).hexdigest(),
                  "base64": base64.b64encode(content).decode("ascii")})
print("D02_GENERATED_STATE=" + json.dumps({"files": files}, separators=(",", ":")))
