"""Linux persistent subreaper: own descendants stay attributable after leader exit."""
import argparse
import ctypes
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid


def identity(pid):
    try:
        fields = Path("/proc" + "/" + str(pid) + "/stat").read_text().rsplit(")", 1)[1].split()
        return {"pid": pid, "ppid": int(fields[1]), "state": fields[0], "start": int(fields[19])}
    except (OSError, ValueError, IndexError):
        return None


def descendants(parent):
    records = {}
    for path in Path("/proc").iterdir():
        if path.name.isdigit():
            record = identity(int(path.name))
            if record:
                records[record["pid"]] = record
    owned = {parent}
    while True:
        extra = {pid for pid, record in records.items() if record["ppid"] in owned}
        if extra <= owned:
            return [records[pid] for pid in owned if pid != parent and pid in records]
        owned |= extra


def open_identity(record):
    try:
        fd = os.pidfd_open(record["pid"])
        current = identity(record["pid"])
        if current is None or current["start"] != record["start"]:
            os.close(fd); return None
        return fd
    except ProcessLookupError:
        return None


def send_bound(fd, value):
    try:
        signal.pidfd_send_signal(fd, value)
        return True
    except ProcessLookupError:
        return False


class OwnedProcess:
    def __init__(self, argv, status, **kwargs):
        if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
            raise RuntimeError("Required Linux bound-process API absent; no child started")
        if signal.getsignal(signal.SIGCHLD) != signal.SIG_DFL:
            raise RuntimeError("Require exclusive parent wait ownership before launch")
        self.status, self.nonce = Path(status), uuid.uuid4().hex
        self.ack = self.status.with_suffix(".start")
        if self.status.exists() or self.ack.exists() or not self.status.parent.is_dir():
            raise RuntimeError("Fresh existing owned communication directory required")
        probe = self.status.with_suffix(".preflight")
        with probe.open("x") as out:
            out.write(self.nonce)
        probe.unlink()
        # Actually exercise both syscalls before starting anything; reserve an FD slot.
        reservation = os.pidfd_open(os.getpid())
        try:
            signal.pidfd_send_signal(reservation, 0)
        except BaseException:
            os.close(reservation); raise
        self.process, self.fd, self.pid = None, None, None
        self.acked = False
        self.bound = {}
        self.record = None
        self.closed = False
        try:
            self.process = subprocess.Popen([sys.executable, "-u", __file__, "--status", str(self.status),
                                             "--nonce", self.nonce, "--", *argv],
                                            start_new_session=True, **kwargs)
            self.pid = self.process.pid
            os.close(reservation); reservation = None
            # Sentinel cannot launch command until this succeeds and we acknowledge READY.
            self.fd = os.pidfd_open(self.pid)
            end = time.monotonic() + 5
            while time.monotonic() < end:
                self.poll()
                if self.record is not None:
                    if not self.acked:
                        temp = self.ack.with_suffix(".start.tmp")
                        temp.write_text(json.dumps({"nonce": self.nonce, "supervisor_pid": self.pid}) + "\n")
                        temp.replace(self.ack); self.acked = True
                    if self.record.get("command_pid") is not None:
                        return
                if self.process.poll() is not None:
                    break
                time.sleep(0.05)
            raise RuntimeError("Owned subreaper did not acknowledge launch")
        except BaseException:
            if reservation is not None:
                os.close(reservation)
            if self.process is not None:
                self.stop()
            raise

    @property
    def stdin(self):
        return self.process.stdin

    @property
    def command_pid(self):
        return self.record.get("command_pid") if self.record else None

    @property
    def returncode(self):
        return self.record.get("exit_code") if self.record else None

    def poll(self):
        if self.process.poll() is None:
            for item in descendants(self.pid):
                key = (item["pid"], item["start"])
                if key not in self.bound:
                    fd = open_identity(item)
                    if fd is not None:
                        self.bound[key] = fd
        if self.status.is_file():
            record = json.loads(self.status.read_text())
            if record.get("nonce") != self.nonce or record.get("supervisor_pid") != self.pid:
                raise RuntimeError("Owned process acknowledgement identity differs")
            self.record = record
        if self.process.returncode is not None and not (self.record and self.record.get("cleanup_complete")):
            raise RuntimeError("Owned subreaper exited without descendant cleanup proof")
        return self.returncode

    def stop(self):
        if self.closed:
            return
        if self.fd is None:
            # Before acknowledgement no command can exist. Popen is our sole,
            # unreaped direct child: PID cannot be reused before parent wait.
            if self.acked:
                raise RuntimeError("Unexpected unbound acknowledged sentinel")
            try:
                self.process.terminate()
                try:
                    self.process.wait(timeout=6)
                except subprocess.TimeoutExpired:
                    self.process.kill(); self.process.wait(timeout=2)
                self.record = {"nonce": self.nonce, "supervisor_pid": self.pid, "command_pid": None,
                               "exit_code": None, "cleanup_complete": True, "aborted_before_ack": True}
            finally:
                self.closed = True
            return
        try:
            send_bound(self.fd, signal.SIGTERM)
            end = time.monotonic() + 6
            while self.process.poll() is None and time.monotonic() < end:
                try:
                    self.poll()
                except RuntimeError:
                    break
                time.sleep(0.05)
            if self.process.poll() is None:
                # Still anchored to our persistent leader; bind all remaining descendants.
                self.poll()
                for fd in self.bound.values():
                    send_bound(fd, signal.SIGKILL)
                end = time.monotonic() + 2
                while self.process.poll() is None and time.monotonic() < end:
                    time.sleep(0.05)
            if self.process.poll() is None:
                send_bound(self.fd, signal.SIGKILL)
            self.process.wait(timeout=2)
            # Even if the sentinel failed, only bound identities can be signalled.
            for fd in self.bound.values():
                send_bound(fd, signal.SIGKILL)
            self.poll()
            if not self.record or not self.record.get("cleanup_complete") or \
                    any(send_bound(fd, 0) for fd in self.bound.values()):
                raise RuntimeError("Owned descendant cleanup not proven; no unknown PID/PGID killed")
        finally:
            for fd in self.bound.values():
                os.close(fd)
            self.bound.clear()
            os.close(self.fd)
            self.closed = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--status", required=True, type=Path)
    parser.add_argument("--nonce", required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    ack = args.status.with_suffix(".start")
    if not command or args.status.exists() or ack.exists() or not args.status.parent.is_dir():
        raise SystemExit("Missing command/existing owned status")
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(36, 1, 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER; own process only, unprivileged
        raise SystemExit("Required owned Linux subreaper unavailable")
    stopping = [None]
    signal.signal(signal.SIGTERM, lambda *_: stopping.__setitem__(0, stopping[0] or time.monotonic()))
    signal.signal(signal.SIGINT, lambda *_: stopping.__setitem__(0, stopping[0] or time.monotonic()))
    process = None
    record = {"nonce": args.nonce, "supervisor_pid": os.getpid(), "command_pid": None,
              "exit_code": None, "cleanup_complete": False}

    def publish():
        temp = args.status.with_suffix(".tmp")
        temp.write_text(json.dumps(record) + "\n"); temp.replace(args.status)

    error = None
    try:
        probe = os.pidfd_open(os.getpid())
        try:
            signal.pidfd_send_signal(probe, 0)
        finally:
            os.close(probe)
        publish()  # complete READY publication before any command is launched; no power-loss claim
        deadline = time.monotonic() + 5
        while not ack.is_file():
            if stopping[0] is not None or time.monotonic() >= deadline:
                raise RuntimeError("No bound parent acknowledgement; command NOT RUN")
            time.sleep(0.05)
        if json.loads(ack.read_text()) != {"nonce": args.nonce, "supervisor_pid": os.getpid()}:
            raise RuntimeError("Parent acknowledgement differs")
        process = subprocess.Popen(command)
        record["command_pid"] = process.pid; publish()
        while stopping[0] is None:
            code = process.poll()
            if code is not None and record["exit_code"] is None:
                record["exit_code"] = code; publish()
            time.sleep(0.05)
    except BaseException as failure:
        error = type(failure).__name__ + ": " + str(failure)
    finally:
        # Publication failures never release an anchor with live orphan commands.
        end, term_end = time.monotonic() + 5, time.monotonic() + 2
        while process is not None:
            process.poll()
            for item in descendants(os.getpid()):
                fd = open_identity(item)
                if fd is not None:
                    try:
                        send_bound(fd, signal.SIGTERM if time.monotonic() < term_end else signal.SIGKILL)
                    finally:
                        os.close(fd)
            if process.poll() is not None:
                no_children = False
                while True:
                    try:
                        pid, _ = os.waitpid(-1, os.WNOHANG)
                        if not pid:
                            break
                    except ChildProcessError:
                        no_children = True
                        break
                if no_children:
                    break
            if time.monotonic() >= end:
                raise RuntimeError("Owned sentinel descendants did not terminate within5s")
            time.sleep(0.05)
        record.update(exit_code=process.returncode if process is not None else None, cleanup_complete=True)
        if error:
            record["error"] = error
        try:
            publish()
        except OSError as failure:
            print("Owned cleanup completed but status publication failed: " + type(failure).__name__, file=sys.stderr, flush=True)
            error = error or "Status publication failed"
    if error:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
