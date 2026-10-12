"""CI evidence/ownership guards only; these are never Android device acceptance."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from d02_ci_evidence import decode_files, emit_file
from d02_kvm_acl import effective_others, parse_acl, proposed_acl

spec = importlib.util.spec_from_file_location("owned_ci_driver", SCRIPTS / "run-d02-kvm-ci.py")
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class CiSupervisionGuards(unittest.TestCase):
    def test_frames_preserve_utf8_and_reject_lost_reordered_duplicate_or_corrupt_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.log"
            path.write_text("合成日志\n" * 1000)
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                emit_file(path, "synthetic/trace.log")
            lines = stdout.getvalue().splitlines()
            self.assertEqual(decode_files(stdout.getvalue()), {"synthetic/trace.log": path.read_bytes()})
            invalid = [lines[:-1], [lines[0], lines[2], lines[1], *lines[3:]], lines + lines]
            changed = list(lines)
            obj = json.loads(changed[1].split("=", 1)[1]); obj["base64"] = "Y29ycnVwdA=="
            changed[1] = "D02_CI_CHUNK=" + json.dumps(obj); invalid.append(changed)
            for value in invalid:
                with self.assertRaises(ValueError):
                    decode_files("\n".join(value))

    def test_acl_grant_changes_only_requested_uid_and_rejects_broader_mask_access(self):
        base = parse_acl("user::rw-\ngroup::rw-\nother::---\n")
        grant = proposed_acl(base, 1001)
        self.assertEqual(grant["user:1001"], 6)
        self.assertEqual(effective_others(base, 1001), effective_others(grant, 1001))
        restricted = parse_acl("user::rw-\nuser:1002:rw-\ngroup::rw-\nmask::r--\nother::---\n")
        with self.assertRaises(RuntimeError):
            proposed_acl(restricted, 1001)
        with self.assertRaises(ValueError):
            parse_acl("user::rw-\ngroup::rw-\nother::---\nuser::r--\n")

    def test_actual_child_exit_and_stderr_are_retained_with_exact_frames(self):
        with tempfile.TemporaryDirectory() as directory:
            root, report, out = Path(directory), {}, io.StringIO()
            supervisor = driver.Supervisor(root, report)
            with contextlib.redirect_stdout(out):
                result = supervisor.run([sys.executable, "-c", "import sys; print('synthetic'); print('diagnostic', file=sys.stderr); sys.exit(7)"],
                                        "owned-child-exit", require=False)
            files = decode_files(out.getvalue())
            self.assertEqual(result.returncode, 7)
            self.assertEqual(files["host/owned-child-exit.stderr.log"], b"diagnostic\n")
            self.assertEqual(json.loads(files["host/owned-child-exit.command.json"])["exit_code"], 7)

    def test_actual_owned_child_timeout_is_bounded_and_recorded_as_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root, report, out = Path(directory), {}, io.StringIO()
            supervisor = driver.Supervisor(root, report)
            with contextlib.redirect_stdout(out), self.assertRaises(TimeoutError):
                supervisor.run([sys.executable, "-u", "-c", "import time; print('started'); time.sleep(20)"],
                               "owned-child-deadline", timeout=0.7)
            files = decode_files(out.getvalue())
            command = json.loads(files["host/owned-child-deadline.command.json"])
            self.assertEqual(command["result"], "FAIL")
            self.assertLess(command["elapsed_seconds"], 5)
            self.assertIn(b"started", files["host/owned-child-deadline.stdout.log"])
            self.assertEqual(len(report["owned_process_cleanup"]), 1)
            self.assertIsNotNone(report["owned_process_cleanup"][0]["returncode"])

    def test_command_leader_exit_cleans_actual_orphan_in_another_session(self):
        with tempfile.TemporaryDirectory() as directory:
            root, report, out = Path(directory), {}, io.StringIO()
            supervisor = driver.Supervisor(root, report)
            program = "import os,time; pid=os.fork(); (os.setsid(),time.sleep(20)) if pid==0 else print(pid,flush=True)"
            with contextlib.redirect_stdout(out):
                result = supervisor.run([sys.executable, "-u", "-c", program], "owned-orphan")
            child_pid = int(result.stdout.strip())
            self.assertEqual(result.returncode, 0)
            self.assertFalse(Path("/proc", str(child_pid)).exists())
            self.assertTrue(report["owned_process_cleanup"][0]["cleanup_complete"])

    def test_pidfd_syscall_denial_is_checked_before_any_child_start(self):
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch("d02_owned_process.os.pidfd_open", side_effect=PermissionError("synthetic syscall refusal")), \
                mock.patch("d02_owned_process.subprocess.Popen") as spawn:
            with self.assertRaises(PermissionError):
                driver.OwnedProcess([sys.executable, "-c", "pass"], Path(directory) / "status.json")
            spawn.assert_not_called()

    def test_missing_status_directory_never_launches_unregistered_command(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "command-started"
            with self.assertRaises(RuntimeError):
                driver.OwnedProcess([sys.executable, "-c", "from pathlib import Path; Path(" + repr(str(marker)) + ").touch()"],
                                    Path(directory) / "absent/status.json")
            self.assertFalse(marker.exists())

    def test_postlaunch_status_io_failure_reaps_actual_orphan_before_anchor_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); ipc = root / "ipc"; ipc.mkdir(); marker = root / "child.pid"
            program = ("import os,time,shutil; from pathlib import Path; pid=os.fork(); "
                       "(os.setsid(),Path(" + repr(str(marker)) + ").write_text(str(os.getpid())),time.sleep(20)) "
                       "if pid==0 else (time.sleep(.3),shutil.rmtree(" + repr(str(ipc)) + "))")
            owned = None
            with (root / "stdout.log").open("w") as out, (root / "stderr.log").open("w") as err:
                try:
                    with self.assertRaises(RuntimeError):
                        owned = driver.OwnedProcess([sys.executable, "-u", "-c", program], ipc / "status.json",
                                                    stdout=out, stderr=err)
                        end = time.monotonic() + 4
                        while time.monotonic() < end:
                            owned.poll(); time.sleep(.05)
                        self.fail("Status failure was not detected")
                finally:
                    if owned is not None:
                        try:
                            owned.stop()
                        except RuntimeError:
                            pass  # missing communication is FAIL, not successful cleanup evidence
            self.assertTrue(marker.is_file())
            self.assertFalse(Path("/proc", marker.read_text()).exists())


if __name__ == "__main__":
    unittest.main()
