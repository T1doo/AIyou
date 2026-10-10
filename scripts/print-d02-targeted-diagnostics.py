#!/usr/bin/env python3
"""Bounded excerpts of this new synthetic experiment only; complete originals remain artifacts."""
import hashlib
from pathlib import Path

root = Path(__file__).resolve().parents[1]
directory = root / "app/build/reports/d02-device-experiment"
MAX_BYTES = 512 * 1024
MAX_LINES = 1024
# root is already canonical. Refuse symlinked ancestors before stat/read of any log.
if directory.resolve() != directory:
    raise SystemExit("FAIL: refuse redirected experiment directory or ancestor")
for name in ("targeted-fourth-trace-summary.log", "targeted-fourth-trace.log", "targeted-fourth-health.log"):
    path = directory / name
    if not path.is_file():
        print(f"D02_DIAGNOSTIC_EXCERPT {name}: NOT AVAILABLE; no evidence inferred", flush=True)
        continue
    if path.is_symlink() or not path.resolve().is_relative_to(directory.resolve()):
        raise SystemExit("FAIL: refuse a diagnostic log outside the owned experiment directory")
    data = path.read_bytes()
    lines = data.splitlines(keepends=True)
    truncated = len(data) > MAX_BYTES or len(lines) > MAX_LINES
    print(f"D02_DIAGNOSTIC_EXCERPT {name}: original_bytes={len(data)} original_lines={len(lines)} "
          f"sha256={hashlib.sha256(data).hexdigest()} max_bytes={MAX_BYTES} max_lines={MAX_LINES} "
          f"status={'TRUNCATED; complete original retained as artifact' if truncated else 'COMPLETE'}", flush=True)
    if truncated:
        # Keep both the initial operation and final wait; excerpts are never parsed as a full trace.
        head = b"".join(lines[:MAX_LINES // 2])[:MAX_BYTES // 2]
        tail = b"".join(lines[-MAX_LINES // 2:])[-MAX_BYTES // 2:]
        print(head.decode("utf-8", errors="ignore"), flush=True)
        print("D02_DIAGNOSTIC_EXCERPT OMITTED MIDDLE; this excerpt cannot certify complete capture", flush=True)
        print(tail.decode("utf-8", errors="ignore"), flush=True)
    else:
        print(data.decode("utf-8", errors="ignore"), flush=True)
