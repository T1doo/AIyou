"""Pure D08 admission and receipt checks; imports perform no I/O or device action."""
import hashlib
import json
import re
import struct
import xml.etree.ElementTree as ET
from d08_probe_artifact import (PROBE_SOURCE, PROBE_RUN, PROBE_ARTIFACT, PROBE_ARCHIVE_SHA,
                                PROBE_SHA, MEMBERS, REPORT_MEMBER, RECEIPT_MEMBER)

SOURCE = "958ab2a08536161d8a0776ef155b6368d7df09c6"
APK_HASH = "dadce271185cc1ff1e8a577f1986d4b4290fbcb60daf7dd187c7088b5d1b693a"
BRANCH = "refs/heads/device/d08-sdk-probe-recovery-20261027"
MESSAGE = "[d08-recovery] 958ab SDK probe e3ac0d9 one isolated SAF round"
APP = "com.t1doo.aiyou"
PROBE = "com.t1doo.aiyou.recoveryprobe"
METHOD = "freshInstallSafRestoreLookupAndDelete"
PHASES = ("create-source", "source-persisted", "manual-transcript", "manual-fact", "saf-backup",
          "backup-acknowledged", "fresh-reinstall-empty", "saf-restore", "restore-acknowledged",
          "verify-restored-byte-content", "lookup-and-exact-source", "delete-propagated")
FILES = ("result.json", "junit.xml", "01-source.png", "02-restored.png", "03-deleted.png")
EXPECTED_REQUEST = {
    "kind": "one-isolated-d08-saf-recovery", "executionApproved": True,
    "productionSha": SOURCE, "artifactRun": 40123738360, "artifactId": 12572840840,
    "artifactDigest": "475285ec3ccd6d16605e6c0fca6051a3cb68ddf9ae29998aa36699d0e22254a4",
    "apkSha256": APK_HASH, "jobMinutes": 35, "driverSeconds": 1920,
    "cleanupSeconds": 120, "attempt": 1, "newTermsAcceptance": False, "realData": False,
    "probeSourceSha": PROBE_SOURCE, "probeArtifactRun": PROBE_RUN, "probeArtifactId": PROBE_ARTIFACT,
    "probeArtifactDigest": PROBE_ARCHIVE_SHA, "probeApkSha256": PROBE_SHA,
    "probeReportSha256": MEMBERS[REPORT_MEMBER][1], "probeReceiptSha256": MEMBERS[RECEIPT_MEMBER][1],
}


def admit(request, env, event):
    if request != EXPECTED_REQUEST or request.get("executionApproved") is not True or \
            any(type(request.get(k)) is not type(v) for k, v in EXPECTED_REQUEST.items()):
        raise ValueError("D08 execution is not approved for the exact fixed request")
    required = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "T1doo/AIyou", "RUNNER_OS": "Linux",
                "GITHUB_EVENT_NAME": "push", "GITHUB_REF": BRANCH, "GITHUB_RUN_ATTEMPT": "1"}
    if any(env.get(k) != v for k, v in required.items()):
        raise ValueError("Exact public-repository push/branch/attempt required")
    if event.get("repository", {}).get("full_name") != "T1doo/AIyou" or event.get("repository", {}).get("private") is not False or \
            event.get("ref") != BRANCH or event.get("head_commit", {}).get("message") != MESSAGE:
        raise ValueError("Exact event tuple required")
    if not re.fullmatch(r"[0-9a-f]{40}", env.get("GITHUB_SHA", "")) or \
            not re.fullmatch(r"[1-9][0-9]*", env.get("GITHUB_RUN_ID", "")):
        raise ValueError("Immutable run/source identity required")


def check_source_changes(changes):
    if not changes:
        raise ValueError("A real reviewed harness commit is required")
    for status, path in changes:
        if path.startswith("/") or ".." in path.split("/") or status != "A" or not (path.startswith("checks/device-recovery/") or
                path.startswith("scripts/d08_") or path == "scripts/run-d08-recovery.py" or
                path.startswith("scripts/tests/test_d08_") or path == ".github/workflows/d08-recovery.yml"):
            raise ValueError("Production/old workflow changes are forbidden: " + path)


def validate_device_result(terminal, result, junit, token):
    pairs = re.findall(r"^INSTRUMENTATION_RESULT: (d08_[a-z]+)=(.*)$", terminal, re.M)
    if len(pairs) != 3 or dict(pairs) != {"d08_result": "PASS", "d08_source": SOURCE, "d08_token": token} or \
            re.findall(r"^INSTRUMENTATION_CODE: (-?[0-9]+)\s*$", terminal, re.M) != ["-1"] or \
            "INSTRUMENTATION_FAILED" in terminal:
        raise ValueError("One successful actual platform instrumentation terminal required")
    if result.get("source_sha") != SOURCE or result.get("run_token") != token or result.get("result") != "PASS" or \
            result.get("lastPhase") != PHASES[-1] or "appCleanup" in result or "fixtureCleanup" in result:
        raise ValueError("Device success/cleanup/source receipt invalid")
    trace = result.get("trace", [])
    if tuple(item.get("phase") for item in trace) != PHASES:
        raise ValueError("Missing, duplicate or out-of-order real UI checkpoint")
    times = [item.get("elapsedRealtimeMs") for item in trace]
    if any(type(t) is not int for t in times) or times != sorted(times):
        raise ValueError("Invalid actual checkpoint clock")
    exported = result.get("restoredExport", {})
    if (exported.get("rangeStart"), exported.get("rangeEndExclusive"), exported.get("sourceFrames"), exported.get("members")) != (0, 4410, 44100, 6):
        raise ValueError("Actual restored export proof absent")
    fixtures = result.get("fixtures", {})
    if fixtures.get("token") != token:
        raise ValueError("Fixture namespace does not belong to this run")
    if exported.get("actualPngSha256") != fixtures.get("fixture.png", {}).get("sha256") or \
            exported.get("actualWavSha256") != fixtures.get("fixture.wav", {}).get("sha256"):
        raise ValueError("Restored bytes differ from issued fixtures")
    for name in ("fixture.png", "fixture.wav", "recovery.aiyoupk"):
        f = fixtures.get(name, {})
        if type(f.get("bytes")) is not int or not 0 < f["bytes"] <= 80 * 1024 * 1024 + 16384 or not re.fullmatch(r"[0-9a-f]{64}", f.get("sha256", "")):
            raise ValueError("Incomplete actual fixture inventory")
    root = ET.fromstring(junit)
    if root.tag != "testsuite" or any(root.get(k) != v for k, v in {"tests": "1", "failures": "0", "errors": "0", "skipped": "0"}.items()):
        raise ValueError("Nonempty one-case zero-FES original XML required")
    cases = root.findall("testcase")
    if len(cases) != 1 or cases[0].get("name") != METHOD or cases[0].get("classname") != PROBE + ".RecoveryInstrumentation" or list(cases[0]):
        raise ValueError("Exact original device method required")


def evidence_frame(name, raw):
    """Only the five fixed synthetic probe outputs; never SDK/APK/DB/ZIP/key bytes."""
    import base64
    if name not in FILES or len(raw) > (512 * 1024 if name.endswith(".png") else 128 * 1024):
        raise ValueError("Unissued or oversized device evidence")
    if name.endswith(".png"):
        if len(raw) < 24 or raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR":
            raise ValueError("Invalid screenshot")
        width, height = struct.unpack(">II", raw[16:24])
        if not 1 <= width <= 2560 or not 1 <= height <= 2560:
            raise ValueError("Screenshot dimensions exceed bound")
    else:
        raw.decode("utf-8")
    return {"name": name, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
            "base64": base64.b64encode(raw).decode("ascii")}


def decode_evidence(frames):
    import base64
    decoded = {}
    for frame in frames:
        name = frame["name"]
        if name in decoded:
            raise ValueError("Duplicate evidence")
        raw = base64.b64decode(frame["base64"], validate=True)
        if evidence_frame(name, raw) != frame:
            raise ValueError("Evidence bytes/hash mismatch")
        decoded[name] = raw
    if set(decoded) != set(FILES):
        raise ValueError("Missing actual device evidence; no success from partial output")
    return decoded


def decode_log(lines):
    """Recover only five fixed evidence files from an already downloaded job log.
    Missing/duplicate/reordered chunks fail; unrelated UTF8 host logs are ignored.
    """
    frames = []; current = None; chunks = []; total = 0
    for line in lines:
        tags = [tag for tag in ("D08_FILE=", "D08_CHUNK=", "D08_END=") if tag in line]
        if not tags: continue
        if len(tags) != 1: raise ValueError("Ambiguous evidence record")
        prefix = tags[0][:-1]
        payload = line.split(tags[0], 1)[1].rstrip("\r\n")
        if len(payload) > 8192: raise ValueError("Evidence line bound")
        total += len(payload)
        if total > 3 * 1024 * 1024: raise ValueError("Evidence stream bound")
        value = json.loads(payload)
        if prefix == "D08_FILE":
            if current is not None or set(value) != {"name", "bytes", "sha256", "chunks"} or value["name"] not in FILES or \
                    type(value["chunks"]) is not int or not 1 <= value["chunks"] <= 171:
                raise ValueError("Unissued or interleaved evidence")
            current = value; chunks = []
        elif prefix == "D08_CHUNK":
            if current is None or set(value) != {"name", "index", "base64"} or value["name"] != current["name"] or \
                    type(value["index"]) is not int or value["index"] != len(chunks) or \
                    not isinstance(value["base64"], str) or not 1 <= len(value["base64"]) <= 4096 or len(chunks) >= current["chunks"]:
                raise ValueError("Missing/duplicate/reordered chunk")
            chunks.append(value["base64"])
        else:
            if current is None or value != current or len(chunks) != current["chunks"]:
                raise ValueError("Incomplete evidence")
            frames.append({k: v for k, v in current.items() if k != "chunks"} | {"base64": "".join(chunks)})
            current = None
    if current is not None: raise ValueError("Truncated evidence")
    return decode_evidence(frames)
