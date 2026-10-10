import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / "d02_native_trace.py"
spec = importlib.util.spec_from_file_location("d02_native_trace", MODULE)
trace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trace)


class NativeTraceParserTest(unittest.TestCase):
    def events(self):
        sequence = [(name, 0, None) for name in ("setup_context", "setup_directory", "setup_secret", "setup_load_library")]
        for n in range(1, 6):
            sequence.extend([
                ("open", n, "FRESH_FILE_FIRST_OPEN" if n == 1 else "REOPEN_SAME_PROCESS"),
                ("update_draft", n, None), ("save_draft", n, None), ("close", n, None),
                ("open", n, "REOPEN_SAME_PROCESS"), ("query_count", n, None),
                ("query_latest", n, None), ("close", n, None),
            ])
        sequence.append(("teardown_close", 0, None))
        return [dict(event=kind, id=i, step=step, iteration=n, openKind=opened, pid=123,
                     wallMillis=20 if kind == "end" else 0, processCpuMillis=2 if kind == "end" else 0,
                     failureType=None, observerFailures=0, ownerJavaThreadId=456)
                for i, (step, n, opened) in enumerate(sequence, 1) for kind in ("start", "end")]

    def summarize(self, events, extra=""):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trace.log"
            path.write_text("\n".join("I/AIyouD02Trace(123): " + json.dumps(e) for e in events) + extra)
            return trace.summarize(path)

    def test_complete_ten_open_trace_includes_setup_teardown_and_cpu_wall(self):
        result = self.summarize(self.events())
        self.assertTrue(result["trace_complete"], result["errors"])
        self.assertEqual(10, result["completed_opens"])
        self.assertEqual(45, result["completed_steps"])
        self.assertEqual(20, result["operations"][0]["wallMillis"])
        self.assertIn("NOT MEASURED", result["cache_claim"])

    def test_timeout_keeps_active_step_and_never_certifies_partial_trace(self):
        result = self.summarize(self.events()[:11])
        self.assertFalse(result["trace_complete"])
        self.assertEqual("update_draft", result["last_active_step"]["step"])
        self.assertEqual(1, result["completed_opens"])

    def test_missing_duplicate_wrong_pid_failure_and_negative_times_reject(self):
        for mutation in ("missing", "duplicate", "pid", "failure", "negative", "observer"):
            with self.subTest(mutation=mutation):
                events = self.events()
                if mutation == "missing": events.pop(9)
                if mutation == "duplicate": events.insert(10, dict(events[8]))
                if mutation == "pid": events[9]["pid"] = 456
                if mutation == "failure": events[9]["failureType"] = "java.lang.Exception"
                if mutation == "negative": events[9]["wallMillis"] = -1
                if mutation == "observer": events[9]["observerFailures"] = 1
                self.assertFalse(self.summarize(events)["trace_complete"])

    def test_truncated_json_rejects_even_with_other_complete_results(self):
        self.assertFalse(self.summarize(self.events(), '\nI/AIyouD02Trace(123): {"event":')["trace_complete"])
        self.assertFalse(self.summarize(self.events(), '\nI/AIyouD02Trace(123): truncated')["trace_complete"])

    def sampled_events(self):
        events = self.events()
        sample = dict(events[0], event="slow_sample", sampleIndex=1, sampleError=None,
                      threadCount=1, wallMillis=10_000)
        thread = dict(event="thread", id=1, sampleIndex=1, pid=123, javaThreadId=456,
                      name="synthetic thread", state="WAITING", frames=["synthetic frame"])
        events[1]["wallMillis"] = 10_000
        events.insert(2, sample)
        events.insert(3, thread)
        return events

    def test_samples_can_arrive_after_step_end_without_becoming_next_step(self):
        events = self.sampled_events()
        result = self.summarize(events)
        self.assertTrue(result["trace_complete"], result["errors"])
        self.assertEqual(1, result["slow_samples"])
        self.assertEqual(1, result["thread_samples"])

    def test_missing_failed_duplicate_wrong_owner_or_oversize_thread_capture_rejects(self):
        for mutation in ("missing", "error", "duplicate", "wrongpid", "wrongowner", "frames", "samplegap", "orphan"):
            with self.subTest(mutation=mutation):
                events = self.sampled_events()
                if mutation == "missing": events.pop(3)
                if mutation == "error": events[2]["sampleError"] = "java.lang.SecurityException"
                if mutation == "duplicate": events.insert(4, dict(events[3])); events[2]["threadCount"] = 2
                if mutation == "wrongpid": events[3]["pid"] = 999
                if mutation == "wrongowner": events[3]["javaThreadId"] = 999
                if mutation == "frames": events[3]["frames"] = ["frame"] * 17
                if mutation == "samplegap": events[2]["sampleIndex"] = 2; events[3]["sampleIndex"] = 2
                if mutation == "orphan": events.pop(2)
                self.assertFalse(self.summarize(events)["trace_complete"])


if __name__ == "__main__":
    unittest.main()
