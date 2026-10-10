"""Parse test-only logcat timing evidence; never infer OS cache state or a deadlock."""
import json
from pathlib import Path


def summarize(path):
    events, parse_errors = [], []
    for number, line in enumerate(Path(path).read_text(errors="replace").splitlines(), 1):
        if "AIyouD02Trace" not in line:
            continue
        offset = line.find("{")
        if offset < 0:
            parse_errors.append(f"line {number}: tagged trace without JSON")
            continue
        try:
            event = json.loads(line[offset:])
            if isinstance(event, dict):
                events.append(event)
            else:
                parse_errors.append(f"line {number}: non-object trace")
        except json.JSONDecodeError:
            parse_errors.append(f"line {number}: malformed/truncated trace")
    expected = [(name, 0, None) for name in ("setup_context", "setup_directory", "setup_secret", "setup_load_library")]
    for iteration in range(1, 6):
        expected.extend([
            ("open", iteration, "FRESH_FILE_FIRST_OPEN" if iteration == 1 else "REOPEN_SAME_PROCESS"),
            ("update_draft", iteration, None), ("save_draft", iteration, None), ("close", iteration, None),
            ("open", iteration, "REOPEN_SAME_PROCESS"), ("query_count", iteration, None),
            ("query_latest", iteration, None), ("close", iteration, None),
        ])
    expected.append(("teardown_close", 0, None))
    errors = list(parse_errors)
    starts, ends, active, operations = [], [], None, []
    owners, samples, threads = {}, {}, []
    pids = set()
    def integer(value):
        return type(value) is int
    def timing(event):
        return all(integer(event.get(key)) and event[key] >= 0 for key in ("wallMillis", "processCpuMillis"))
    for event in events:
        kind = event.get("event")
        if kind not in ("start", "end", "slow_sample", "thread"):
            errors.append("unknown trace event")
            continue
        if kind == "thread":
            threads.append(event)
            continue
        if not integer(event.get("pid")) or event["pid"] <= 0:
            errors.append("invalid process identity")
        else:
            pids.add(event["pid"])
        if event.get("observerFailures") != 0:
            errors.append("observer emission failed")
        identity = (event.get("step"), event.get("iteration"), event.get("openKind"))
        if kind == "slow_sample":
            key = (event.get("id"), event.get("sampleIndex"))
            if not all(integer(x) for x in key):
                errors.append("invalid sample identity")
                continue
            if key in samples:
                errors.append("duplicate sample")
            samples[key] = event
            owner = owners.get(key[0])
            if owner is None or identity != (owner.get("step"), owner.get("iteration"), owner.get("openKind")) \
                    or event.get("pid") != owner.get("pid") \
                    or event.get("ownerJavaThreadId") != owner.get("ownerJavaThreadId"):
                errors.append("sample not attributed to its original step/process/thread")
            if not 1 <= key[1] <= 3 or not timing(event) or event.get("wallMillis", 0) < 10_000:
                errors.append("invalid slow sample sequence/timing")
            if event.get("sampleError") is not None:
                errors.append("thread capture failed")
            if not integer(event.get("threadCount")) or not 1 <= event["threadCount"] <= 32:
                errors.append("missing/out-of-bounds thread count")
            continue
        if kind == "start":
            starts.append(identity)
            if active is not None or not integer(event.get("id")) or event["id"] != len(starts):
                errors.append("overlap or missing/out-of-order start id")
            else:
                owners[event["id"]] = event
            if not integer(event.get("ownerJavaThreadId")) or event["ownerJavaThreadId"] <= 0:
                errors.append("missing original step thread identity")
            active = event
        else:
            ends.append(identity)
            if active is None or event.get("id") != active.get("id") or identity != (
                active.get("step"), active.get("iteration"), active.get("openKind")
            ):
                errors.append("unmatched end")
            if event.get("failureType") is not None:
                errors.append("step threw a product/test exception")
            if not timing(event):
                errors.append("missing or negative timing")
            operations.append(event)
            active = None
    grouped = {}
    for thread in threads:
        key = (thread.get("id"), thread.get("sampleIndex"))
        if not all(integer(x) for x in key):
            errors.append("invalid thread sample identity")
            continue
        sample = samples.get(key)
        if sample is None or thread.get("pid") != sample.get("pid"):
            errors.append("orphan/wrong-process thread sample")
        frames = thread.get("frames")
        if not isinstance(frames, list) or len(frames) > 16 or any(not isinstance(f, str) or len(f) > 180 for f in frames):
            errors.append("invalid/out-of-bounds frames")
        if not isinstance(thread.get("name"), str) or len(thread["name"]) > 96 \
                or thread.get("state") not in ("NEW", "RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING", "TERMINATED") \
                or not integer(thread.get("javaThreadId")) or thread["javaThreadId"] <= 0:
            errors.append("invalid thread identity/state")
        grouped.setdefault(key, []).append(thread)
    for key, sample in samples.items():
        rows = grouped.get(key, [])
        valid_ids = {r["javaThreadId"] for r in rows if integer(r.get("javaThreadId"))}
        if len(rows) != sample.get("threadCount") or len(valid_ids) != len(rows):
            errors.append("incomplete/duplicate thread snapshot")
        if not any(r.get("javaThreadId") == sample.get("ownerJavaThreadId") for r in rows):
            errors.append("original operation thread missing from snapshot")
    for operation_id in {key[0] for key in samples}:
        indices = sorted(key[1] for key in samples if key[0] == operation_id)
        if indices != list(range(1, len(indices) + 1)):
            errors.append("missing/out-of-order per-step sample sequence")
    if starts != expected or ends != expected:
        errors.append("require setup + original five rounds/eight steps + teardown in exact order")
    if len(pids) != 1:
        errors.append("require one observed instrumentation process")
    if active is not None:
        errors.append("step still active at end of captured log")
    return {
        "trace_complete": not errors, "errors": errors, "started_steps": len(starts),
        "completed_steps": len(ends), "completed_opens": sum(e.get("step") == "open" for e in operations),
        "slow_samples": sum(e.get("event") == "slow_sample" for e in events),
        "thread_samples": sum(e.get("event") == "thread" for e in events),
        "process_ids": sorted(pids), "last_active_step": active, "operations": operations,
        "cache_claim": "File/process history only; OS cold/warm cache NOT MEASURED",
        "interpretation": "Wall minus aggregate process CPU is not an exact wait duration; stacks/health are sampling evidence, not a proven deadlock or sole environment cause",
    }
