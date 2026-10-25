"""Existing cache only. javac API type check + pure ZIP checks, never Android execution."""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[3]
JAVA = ROOT / ".toolchains/jdk-17.0.17+10/bin"
JAR = ROOT / ".toolchains/gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/15-robolectric-13954326/78f2fb1d753164d153cf9e5bb60003fa8d57b0a1/android-all-15-robolectric-13954326.jar"
ORIGINAL = "【合成恢复】我的常用水杯是绿色的🙂。"
TRANSCRIPT = "合成转写🙂恢复范围"


class JavaProbeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not (JAVA / "javac").is_file() or not JAR.is_file():
            raise unittest.SkipTest("Existing pinned JDK/API35 cache absent; no download attempted")
        cls.temp = tempfile.TemporaryDirectory(); cls.path = Path(cls.temp.name)
        sources = list((ROOT / "checks/device-recovery/android/src/main/java").rglob("*.java"))
        sources += list((ROOT / "checks/device-recovery/host-tests").rglob("*.java"))
        result = subprocess.run([JAVA / "javac", "-encoding", "UTF-8", "-cp", JAR, "-d", cls.path, *sources], text=True, capture_output=True, timeout=30)
        if result.returncode: raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def fixture(self, kind=None):
        image, wave = b"synthetic PNG bytes for hash contract", b"synthetic WAV bytes for hash contract"
        ih, wh = hashlib.sha256(image).hexdigest(), hashlib.sha256(wave).hexdigest()
        record = {"entryId": "a", "revision": 1, "originalText": ORIGINAL, "contentHash": hashlib.sha256(ORIGINAL.encode()).hexdigest()}
        transcript = {"sourceId": "a", "sourceRevision": 1, "sourceHash": record["contentHash"], "text": TRANSCRIPT,
                      "sourceFrames": 44100, "sampleRate": 44100, "range": {"startFrame": 0, "endFrameExclusive": 4410},
                      "audioPath": "audio/b.wav", "audioSha256": wh, "audioByteSize": len(wave)}
        if kind == "range": transcript["range"]["endFrameExclusive"] = 4409
        if kind == "source": transcript["sourceId"] = "different"
        if kind == "revision": transcript["sourceRevision"] = 2
        if kind == "hash": transcript["sourceHash"] = "0" * 64
        entries = {"records.md": b"synthetic", "records.jsonl": (json.dumps(record, ensure_ascii=False) + "\n").encode(),
                   "human-transcripts.jsonl": (json.dumps(transcript, ensure_ascii=False) + "\n").encode(),
                   "images/a.png": image, "audio/b.wav": wave}
        files = [{"path": n, "byteSize": len(b), "sha256": hashlib.sha256(b).hexdigest()} for n, b in entries.items()]
        entries["manifest.json"] = json.dumps({"formatVersion": 5, "humanTranscriptCount": 1, "files": files}).encode()
        if kind == "substitute": entries["audio/b.wav"] = b"changed"
        if kind == "missing": entries.pop("records.md")
        if kind == "unexpected": entries["unknown.txt"] = entries.pop("records.md")
        f = self.path / ((kind or "valid") + ".zip")
        with zipfile.ZipFile(f, "w") as z:
            for n, b in entries.items(): z.writestr(n, b)
            if kind == "duplicate": z.writestr("records.md", b"duplicate")
        return f, ih, wh

    def check(self, kind=None):
        f, ih, wh = self.fixture(kind)
        return subprocess.run([JAVA / "java", "-cp", str(self.path) + ":" + str(JAR),
            "com.t1doo.aiyou.recoveryprobe.ExportHostProbe", f, ih, wh, ORIGINAL, TRANSCRIPT],
            text=True, capture_output=True, timeout=10)

    def test_real_probe_java_compiles_against_cached_android15_api_and_valid_unicode_zip(self):
        result = self.check(); self.assertEqual(result.returncode, 0, result.stderr)

    def test_actual_verifier_rejects_range_source_substitution_missing_and_duplicate_members(self):
        for kind in ("range", "source", "revision", "hash", "substitute", "missing", "unexpected", "duplicate"):
            with self.subTest(kind=kind): self.assertNotEqual(self.check(kind).returncode, 0)


if __name__ == "__main__": unittest.main()
