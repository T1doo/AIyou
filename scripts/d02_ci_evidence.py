"""Bounded, hash-framed text evidence for CI logs; never exports binary assets."""
import base64
import hashlib
import json
from pathlib import Path

MAX_FILE_BYTES = 4 * 1024 * 1024
CHUNK_BYTES = 3072


def emit_file(path, name):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_FILE_BYTES:
        raise ValueError("Missing/oversized/nonregular text evidence: " + name)
    raw = path.read_bytes()
    raw.decode("utf-8")  # no APK, DB, archive or signing bytes in public logs
    meta = {"name": name, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
            "chunks": (len(raw) + CHUNK_BYTES - 1) // CHUNK_BYTES}
    print("D02_CI_FILE=" + json.dumps(meta), flush=True)
    for index, start in enumerate(range(0, len(raw), CHUNK_BYTES)):
        print("D02_CI_CHUNK=" + json.dumps({"name": name, "index": index,
              "base64": base64.b64encode(raw[start:start + CHUNK_BYTES]).decode("ascii")}), flush=True)
    print("D02_CI_END=" + json.dumps(meta), flush=True)


def decode_files(log):
    """Require paired frames, exact chunk order/bytes/hash; reject evidence loss."""
    files, active, chunks = {}, None, []
    for line in log.splitlines():
        # Official job logs prefix each line with a timestamp.
        tags = [tag for tag in ("D02_CI_FILE=", "D02_CI_CHUNK=", "D02_CI_END=") if tag in line]
        if not tags:
            continue
        if len(tags) != 1:
            raise ValueError("Ambiguous evidence record")
        tag = tags[0]
        obj = json.loads(line.split(tag, 1)[1])
        if tag == "D02_CI_FILE=":
            name = obj["name"]
            parts = Path(name).parts
            if active is not None or name in files or not parts or Path(name).is_absolute() or ".." in parts:
                raise ValueError("Overlapping/duplicate/unsafe evidence frame")
            if not isinstance(obj["bytes"], int) or not 0 <= obj["bytes"] <= MAX_FILE_BYTES or \
                    obj["chunks"] != (obj["bytes"] + CHUNK_BYTES - 1) // CHUNK_BYTES:
                raise ValueError("Invalid evidence size")
            active, chunks = obj, []
        elif tag == "D02_CI_CHUNK=":
            if active is None or obj["name"] != active["name"] or obj["index"] != len(chunks):
                raise ValueError("Unpaired/out-of-order chunk")
            raw = base64.b64decode(obj["base64"], validate=True)
            if not raw or len(raw) > CHUNK_BYTES or len(chunks) >= active["chunks"]:
                raise ValueError("Invalid evidence chunk")
            chunks.append(raw)
        else:
            if active is None or obj != active or len(chunks) != active["chunks"]:
                raise ValueError("Unpaired/incomplete evidence footer")
            raw = b"".join(chunks)
            if len(raw) != active["bytes"] or hashlib.sha256(raw).hexdigest() != active["sha256"]:
                raise ValueError("Evidence bytes/hash differ")
            raw.decode("utf-8")
            files[active["name"]] = raw
            active, chunks = None, []
    if active is not None or not files:
        raise ValueError("Incomplete/empty CI evidence")
    return files
