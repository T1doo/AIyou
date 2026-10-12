#!/usr/bin/env python3
"""One authorized, bounded, accelerated GitHub VM; no terms acceptance or artifacts."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
import uuid

from d02_ci_evidence import MAX_FILE_BYTES, decode_files, emit_file
from d02_kvm_acl import OwnedKvmAcl
from d02_owned_process import OwnedProcess, identity, send_bound

ROOT = Path(__file__).resolve().parents[1]
BASE = "b13cdb9995797d3d245f86c39c4d86e0c2c40637"
RESERVE = 120
ALLOWED_CHANGES = {
    ".github/workflows/d02-kvm-acceptance.yml", "scripts/run-d02-kvm-ci.py",
    "scripts/d02_ci_evidence.py", "scripts/d02_kvm_acl.py", "scripts/run-d02-device-acceptance.py",
    "scripts/d02_owned_process.py",
    "scripts/tests/test_d02_ci_supervision.py", "checks/sdk-device/kvm-request-20261012.json",
    "docs/D02_KVM_CI.md",
}
spec = importlib.util.spec_from_file_location("fixed_sdk", ROOT / "scripts/run-d02-device-experiment.py")
fixed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixed)


def terminate_owned(process, name, report):
    try:
        process.stop()
    finally:
        report.setdefault("owned_process_cleanup", []).append({"name": name, "supervisor_pid": process.pid,
                                                              "command_pid": process.command_pid,
                                                              "returncode": process.returncode,
                                                              "cleanup_complete": bool(process.record and process.record.get("cleanup_complete"))})


class Supervisor:
    def __init__(self, output, report):
        self.output, self.report = output, report
        self.deadline = time.monotonic() + 35 * 60
        self.names = set()
        self.background = []

    def run(self, argv, name, timeout=90, env=None, input_text=None, require=True,
            deadline_bound=True, stream=False):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", name) or name in self.names:
            raise ValueError("Unsafe/repeated command evidence name")
        self.names.add(name)
        if deadline_bound:
            timeout = min(timeout, self.deadline - time.monotonic() - RESERVE)
        if timeout <= 0:
            raise RuntimeError("Overall deadline reached; remaining operations NOT RUN")
        prefix = self.output / name
        started, end = time.monotonic(), time.monotonic() + timeout
        command = [str(x) for x in argv]
        meta = {"name": name, "argv": command, "timeout_seconds": timeout, "result": "NOT RUN"}
        print("D02_CI_STEP=" + json.dumps(meta), flush=True)
        position = 0
        try:
            with prefix.with_suffix(".stdout.log").open("wb") as out, \
                    prefix.with_suffix(".stderr.log").open("wb") as err:
                process = OwnedProcess(command, prefix.with_suffix(".process.json"), cwd=ROOT, env=env, stdout=out, stderr=err,
                                       stdin=subprocess.PIPE if input_text is not None else subprocess.DEVNULL)
                meta.update(supervisor_pid=process.pid, command_pid=process.command_pid)
                try:
                    if input_text is not None:
                        process.stdin.write(input_text.encode()); process.stdin.close()
                    while process.poll() is None:
                        if stream:
                            with prefix.with_suffix(".stdout.log").open("rb") as source:
                                source.seek(position); block = source.read(); position += len(block)
                            sys.stdout.buffer.write(block); sys.stdout.buffer.flush()
                        monitored = [prefix.with_suffix(".stdout.log"), prefix.with_suffix(".stderr.log")]
                        monitored.extend(path for _, path in self.background)
                        if any(path.stat().st_size > MAX_FILE_BYTES for path in monitored) or time.monotonic() >= end:
                            raise TimeoutError("Owned command deadline/evidence-size limit reached")
                        time.sleep(0.5)
                    meta["exit_code"] = process.returncode
                finally:
                    terminate_owned(process, name, self.report)
            stdout = prefix.with_suffix(".stdout.log").read_text()
            stderr = prefix.with_suffix(".stderr.log").read_text()
            if stream:
                raw = prefix.with_suffix(".stdout.log").read_bytes()[position:]
                sys.stdout.buffer.write(raw); sys.stdout.buffer.flush()
            meta["result"] = "PASS" if process.returncode == 0 else "FAIL"
            if require and process.returncode != 0:
                raise RuntimeError(name + ": exit " + str(process.returncode))
            return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
        except BaseException as error:
            meta.update(result="FAIL", error=type(error).__name__ + ": " + str(error))
            raise
        finally:
            if "process" in locals():
                meta["exit_code"] = process.returncode
            meta["elapsed_seconds"] = time.monotonic() - started
            prefix.with_suffix(".command.json").write_text(json.dumps(meta, indent=2) + "\n")
            evidence_errors = []
            for suffix in (".command.json", ".process.json", ".stdout.log", ".stderr.log"):
                path = prefix.with_suffix(suffix)
                if path.is_file():
                    # Native stdout contains live child frames; don't duplicate them.
                    if not (stream and suffix == ".stdout.log"):
                        try:
                            emit_file(path, "host/" + path.name)
                        except (OSError, ValueError) as error:
                            evidence_errors.append(path.name + ": " + type(error).__name__)
            print("D02_CI_STEP_END=" + json.dumps(meta), flush=True)
            if evidence_errors:
                self.report.setdefault("evidence_errors", []).extend(evidence_errors)
                raise RuntimeError("Incomplete command text evidence")


def free_ports():
    held = []
    try:
        ports = []
        for candidate in range(5580, 5620, 2):
            pair = []
            try:
                for port in (candidate, candidate + 1):
                    sock = socket.socket(); pair.append(sock); sock.bind(("127.0.0.1", port))
                held.extend(pair); ports.extend((candidate, candidate + 1)); break
            except OSError:
                for sock in pair:
                    sock.close()
        if len(ports) != 2:
            raise RuntimeError("No free emulator port pair; never stop existing listeners")
        server = socket.socket(); held.append(server); server.bind(("127.0.0.1", 0)); ports.append(server.getsockname()[1])
        return ports
    finally:
        for sock in held:
            sock.close()


def verify_loopback_listener(text, port, command_pid):
    """Require ss to attest the sole TCP listener is the live owned adb on IPv4 loopback."""
    lines = text.splitlines()
    if len(lines) != 1:
        raise RuntimeError("Require exactly one owned adb listener")
    columns = lines[0].split()
    if len(columns) < 6 or columns[0] != "LISTEN" or columns[3] != "127.0.0.1:" + str(port) or \
            set(re.findall(r"\bpid=(\d+),", lines[0])) != {str(command_pid)}:
        raise RuntimeError("Actual adb listener address/PID differs; never permit wildcard or remote listening")


def verify_live_owned_command(process):
    """Require the original observed child identity and its bound kernel pidfd to remain live."""
    bindings = [(key, fd) for key, fd in process.bound.items() if key[0] == process.command_pid]
    if len(bindings) != 1:
        raise RuntimeError("Missing/ambiguous bound adb command identity")
    (pid, start), fd = bindings[0]
    current = identity(pid)
    if current is None or current["start"] != start or current["ppid"] != process.pid or \
            current["state"] in ("Z", "X") or not send_bound(fd, 0):
        raise RuntimeError("Bound owned adb command is no longer the same live child")
    return {"pid": pid, "start": start, "ppid": process.pid}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.expected_sha):
        parser.error("Expected full immutable SHA")
    args.output.mkdir(parents=True, exist_ok=False)
    output = args.output.resolve()
    report = {"result": "NOT RUN", "source_sha": args.expected_sha, "base_sha": BASE,
              "native_tests": "NOT RUN", "external_phases": "NOT RUN", "16KB": "NOT RUN",
              "physical_performance": "NOT RUN", "artifact_uploads": 0, "new_agreements": 0}
    supervisor = Supervisor(output, report)
    run = supervisor.run
    fixed.OUTPUT, fixed.command = output, run
    fixed.EXPERIMENT_DEADLINE = supervisor.deadline
    fixed.CLEANUP_RESERVE_SECONDS = RESERVE
    acl = OwnedKvmAcl(run, report)
    processes, handles, failure = [], [], None
    before = fixed.license_snapshot()
    report["license_before"] = before
    try:
        if os.environ.get("GITHUB_REPOSITORY") != "T1doo/AIyou" or os.environ.get("RUNNER_OS") != "Linux" or \
                os.uname().machine != "x86_64":
            raise RuntimeError("Only authorized standard public GitHub Ubuntu x86_64 job")
        request = json.loads((ROOT / "checks/sdk-device/kvm-request-20261012.json").read_text())
        expected = {"base_sha": BASE, "mode": "KVM_ONLY", "attempt": 3, "max_seconds": 2100,
                    "native_methods": 19, "external_phases": 6, "artifact_upload": False,
                    "new_terms_acceptance": False, "kvm_acl": "CURRENT_JOB_UID_ONLY",
                    "correction": "ADB_LOOPBACK_LISTEN_SPEC",
                    "previous_source_sha": "081288232680a77ee120bf0be987d1cb46ad2d8a",
                    "previous_run_id": 38244244711,
                    "previous_job_log_sha256": "de9e14a99c2443ede72fe51c1eb13f25f722a0f0e9eb7f27db81609c8796dcbb"}
        if request != expected:
            raise RuntimeError("Explicit one-attempt authorization request differs")
        if run(["git", "rev-parse", "HEAD"], "source-sha").stdout.strip() != args.expected_sha or \
                run(["git", "status", "--porcelain"], "source-status").stdout.strip():
            raise RuntimeError("Require exact clean reviewed checkout")
        changed = set(run(["git", "diff", "--name-only", BASE, "HEAD"], "baseline-diff").stdout.splitlines())
        if not changed or not changed <= ALLOWED_CHANGES:
            raise RuntimeError("Production/source drift outside reviewed CI-only change")
        run([sys.executable, ROOT / "scripts/check-sdk.py"], "existing-sdk")
        run([sys.executable, ROOT / "scripts/inspect-device-test-environment.py"], "existing-device-inventory")
        for item in fixed.PACKAGES:
            if fixed.digest(fixed.SOURCE / item["xml"]) != item["xml_sha256"]:
                raise RuntimeError("Fixed SDK/license metadata changed")
        sdk = fixed.SDK
        if not (sdk / "cmdline-tools/latest/bin/avdmanager").is_file() or not (sdk / "platform-tools/adb").is_file():
            raise RuntimeError("Required existing SDK tools absent; no upgrade permitted")
        report["sdk_root"] = str(sdk)
        # Check KVM before spending network/build time; restore even if later setup fails.
        acl.grant()
        wrapper = ROOT / "gradle/wrapper/gradle-wrapper.jar"
        if fixed.digest(wrapper) != "3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98":
            raise RuntimeError("Wrapper differs")
        run([ROOT / "scripts/gradle.sh", ":app:checkDebugAarMetadata", ":core:data:checkDebugAarMetadata",
             ":app:assembleDebug", ":core:data:assembleDebugAndroidTest", ":app:assembleDebugAndroidTest",
             ":core:domain:test", ":app:lintDebug"], "actual-build", timeout=900)
        run([sys.executable, ROOT / "scripts/verify-test-results.py"], "nonempty-domain-tests")
        run([sys.executable, ROOT / "scripts/inspect-d02-device-test-apks.py"], "compiled-dex-inventory")
        checker = fixed.compile_checker()
        run(checker, "existing-terms-official-api")
        report["free_disk_bytes_before_install"] = min(shutil.disk_usage(output).free, shutil.disk_usage(sdk).free)
        if report["free_disk_bytes_before_install"] < 4 * 1024 ** 3:
            raise RuntimeError("Insufficient existing SDK/staging disk space; never delete unknown files")
        if output.stat().st_dev != sdk.stat().st_dev:
            raise RuntimeError("SDK and owned staging differ filesystems; fixed atomic promotion cannot proceed")
        for item in fixed.PACKAGES:
            target = sdk.joinpath(*item["path"].split(";"))
            if target.exists() or target.is_symlink():
                if target.is_symlink() or not (target / "package.xml").is_file() or \
                        fixed.digest(target / "package.xml") != item["xml_sha256"]:
                    raise RuntimeError("Existing emulator/image differs; do not overwrite")
            else:
                fixed.install_owned_package(item)
        run([*checker, "discover"], "installed-fixed-sdk-discovery")
        for item in fixed.PACKAGES:
            properties = dict(line.split("=", 1) for line in
                              (sdk.joinpath(*item["path"].split(";")) / "source.properties").read_text().splitlines()
                              if "=" in line and not line.startswith("#"))
            if properties.get("Pkg.Revision", "").strip() != item["revision"]:
                raise RuntimeError("Actual installed SDK payload revision differs")
        report["packages"] = fixed.PACKAGES
        if fixed.license_snapshot() != before:
            raise RuntimeError("SDK acceptance records changed")
        emulator, adb = sdk / "emulator/emulator", sdk / "platform-tools/adb"
        # Match the already-authorized headless boot route. The default launcher
        # selects the GUI engine even for -version, requiring absent libpulse.
        version = run([emulator, "-no-window", "-version"], "fixed-emulator-version")
        if not re.search(r"\b37\.2\.12(?:\.0)?\b", version.stdout + version.stderr):
            raise RuntimeError("Actual fixed emulator version differs")
        acceleration = run([emulator, "-no-window", "-accel-check"], "actual-kvm-acceleration")
        if not re.search(r"KVM.*(?:installed|usable)", acceleration.stdout + acceleration.stderr, re.I):
            raise RuntimeError("Actual emulator acceleration check did not confirm KVM")
        env = {**os.environ, "PYTHONUNBUFFERED": "1"}
        env.pop("ANDROID_I_WANT_MY_TCG", None)
        for variable in ("ANDROID_USER_HOME", "ANDROID_AVD_HOME", "ANDROID_EMULATOR_HOME"):
            directory = output / variable.lower(); directory.mkdir(); env[variable] = str(directory)
        env["ANDROID_HOME"] = env["ANDROID_SDK_ROOT"] = str(sdk)
        manager = sdk / "cmdline-tools/latest/bin/avdmanager"
        profiles = run([manager, "list", "device"], "existing-device-profiles", env=env).stdout
        if '"pixel_2"' not in profiles:
            raise RuntimeError("Existing fixed hardware profile unavailable")
        name, avd = "aiyou_d02_" + uuid.uuid4().hex, output / "owned-avd"
        run([manager, "create", "avd", "-n", name, "-k", fixed.PACKAGES[1]["path"],
             "-d", "pixel_2", "-p", avd], "create-owned-avd", env=env, input_text="no\n")
        config = (avd / "config.ini").read_text()
        image_lines = [line.split("=", 1)[1] for line in config.splitlines() if line.startswith("image.sysdir.1=")]
        image = Path(image_lines[0]) if len(image_lines) == 1 else None
        image = image if image is None or image.is_absolute() else sdk / image
        if image is None or image.resolve() != sdk.joinpath(*fixed.PACKAGES[1]["path"].split(";")).resolve() or \
                not avd.resolve().is_relative_to(output):
            raise RuntimeError("Owned AVD layout incomplete")
        gpu = run([emulator, "-no-window", "-help-gpu"], "fixed-gpu-help", env=env)
        if not re.search(r"\bswiftshader\b", gpu.stdout + gpu.stderr):
            raise RuntimeError("Fixed software GPU unavailable")
        port, _, server_port = free_ports()
        serial = "emulator-" + str(port)
        env["ANDROID_ADB_SERVER_PORT"] = str(server_port)
        env["ADB_SERVER_SOCKET"] = "tcp:127.0.0.1:" + str(server_port)
        for label, argv in (
            # adb's listener grammar differs from its client address grammar.
            # Without -a, tcp:<port> binds loopback; attest actual address/PID below.
            ("owned-adb-server", [adb, "-L", "tcp:" + str(server_port), "nodaemon", "server"]),
            ("owned-emulator", [emulator, "-avd", name, "-port", str(port), "-no-window", "-no-audio",
             "-no-boot-anim", "-no-snapshot", "-accel", "on", "-gpu", "swiftshader",
             "-memory", "2048", "-cores", "2"]),
        ):
            path = output / (label + ".log"); handle = path.open("wb"); handles.append(handle)
            process = OwnedProcess([str(x) for x in argv], output / (label + ".process.json"), cwd=ROOT,
                                   env=env, stdout=handle, stderr=subprocess.STDOUT)
            processes.append((process, label)); supervisor.background.append((process, path))
            report[label] = {"supervisor_pid": process.pid, "command_pid": process.command_pid,
                             "argv": [str(x) for x in argv]}
            if label == "owned-adb-server":
                for _ in range(20):
                    if process.poll() is not None:
                        raise RuntimeError("Owned foreground adb server exited during startup")
                    try:
                        with socket.create_connection(("127.0.0.1", server_port), timeout=0.5):
                            break
                    except OSError:
                        time.sleep(0.5)
                else:
                    raise RuntimeError("Owned adb server did not listen within10s; no duplicate server started")
                bound_identity = verify_live_owned_command(process)
                listener = run(["ss", "-H", "-lntp", "sport = :" + str(server_port)],
                               "actual-owned-adb-listener", env=env)
                verify_loopback_listener(listener.stdout, server_port, process.command_pid)
                if verify_live_owned_command(process) != bound_identity:
                    raise RuntimeError("Owned adb kernel identity changed during listener attestation")
                report["adb_listener"] = {"address": "127.0.0.1", "port": server_port,
                                          "command_pid": process.command_pid,
                                          "kernel_identity": bound_identity, "attested": True}
        report.update(serial=serial, acceleration="KVM_ONLY", adb_server_port=server_port)
        boot_end = min(supervisor.deadline - RESERVE, time.monotonic() + 240)
        for index in range(49):
            if any(process.poll() is not None for process, _ in processes):
                raise RuntimeError("Owned emulator/adb server exited before boot")
            if time.monotonic() >= boot_end:
                raise RuntimeError("Accelerated emulator did not boot within 240s")
            poll = run([adb, "-s", serial, "shell", "getprop", "sys.boot_completed"],
                       "boot-poll-" + str(index), env=env, timeout=min(10, boot_end - time.monotonic()), require=False)
            if time.monotonic() >= boot_end:
                raise RuntimeError("Boot poll exceeded fixed240s deadline")
            if poll.returncode == 0 and poll.stdout.strip() == "1":
                break
            time.sleep(min(5, max(0, boot_end - time.monotonic())))
        else:
            raise RuntimeError("No boot terminal")
        device = {}
        for key, args_tail in (("api", ["getprop", "ro.build.version.sdk"]),
                              ("abi", ["getprop", "ro.product.cpu.abi"]), ("page_size", ["getconf", "PAGE_SIZE"])):
            device[key] = run([adb, "-s", serial, "shell", *args_tail], "actual-" + key, env=env).stdout.strip()
        if device != {"api": "35", "abi": "x86_64", "page_size": "4096"}:
            raise RuntimeError("Actual device differs from pinned API35/x86_64/4KB route")
        report["device"] = device
        manifest = ROOT / "app/build/reports/device-apk-manifest.json"
        emit_file(manifest, "host/device-apk-manifest.json")
        budget = min(2100, int(supervisor.deadline - time.monotonic() - RESERVE))
        if budget < 90:
            raise RuntimeError("Insufficient remaining device budget")
        run([sys.executable, ROOT / "scripts/run-d02-device-acceptance.py", "--serial", serial,
             "--expected-sha", args.expected_sha, "--apk-manifest", manifest, "--output", output / "device",
             "--budget-seconds", str(budget), "--emit-ci-evidence"], "native-acceptance",
            env=env, timeout=budget, stream=True)
        result = json.loads((output / "device/RESULT.json").read_text())
        decode_files((output / "native-acceptance.stdout.log").read_text())
        if not result["result"].startswith("PASS:") or \
                sum(x["tests"] for x in result["native_tests"][:3]) != 19 or \
                len(result["native_tests"]) != 7 or len(result["app_phases"]) != 2 or \
                not result["acceptance_records_unchanged"]:
            raise RuntimeError("Require all actual 19+6 gates and unchanged terms")
        report.update(native_tests=19, external_phases=6, device_result=result,
                      result="PASS: accelerated Android exact 19 native +6 external phases; other device gates OPEN")
    except BaseException as error:
        failure = error
        report["result"] = "FAIL: " + type(error).__name__ + ": " + str(error) + "; uncompleted gates NOT RUN"
    finally:
        cleanup = []
        for process, label in reversed(processes):
            try:
                terminate_owned(process, label, report)
            except Exception as error:
                cleanup.append(label + ": " + str(error))
        for handle in handles:
            handle.close()
        try:
            acl.restore()
        except Exception as error:
            cleanup.append("KVM ACL restore: " + str(error))
        try:
            report["license_after"] = fixed.license_snapshot()
            if report["license_after"] != before:
                cleanup.append("SDK acceptance records changed")
        except OSError as error:
            cleanup.append("SDK acceptance snapshot unavailable: " + type(error).__name__)
        if cleanup:
            report["result"] = "FAIL: cleanup/evidence boundary; device result retained separately"
            report["cleanup_errors"] = cleanup
        child_result = output / "device/RESULT.json"
        if child_result.is_file():
            try:
                if child_result.is_symlink() or child_result.stat().st_size > MAX_FILE_BYTES:
                    raise ValueError("Invalid child RESULT size/type")
                child = json.loads(child_result.read_text())
                native = sum(x["tests"] for x in child["native_tests"] if x["phase"].startswith("native-"))
                phases = sum(x["phase"].startswith("storage-") for x in child["native_tests"]) + len(child["app_phases"])
                if not 0 <= native <= 19 or not 0 <= phases <= 6:
                    raise ValueError("Child outcome count differs")
                report.update(device_result=child, native_tests=native, external_phases=phases)
            except (OSError, ValueError, KeyError, TypeError) as error:
                cleanup.append("Partial/invalid child RESULT: " + type(error).__name__)
        # Stream complete retained child logs even if its Python was killed before its finally.
        console = output / "native-acceptance.stdout.log"
        emitted = {}
        if console.is_file():
            try:
                emitted = decode_files(console.read_text())
            except (OSError, ValueError, KeyError, TypeError) as error:
                report["result"] = "FAIL: incomplete native text evidence; raw partial logs retained"
                report["evidence_error"] = str(error)
                cleanup.append("incomplete native evidence")
        export_errors = []

        def retain(path, name):
            try:
                emit_file(path, name)
            except (OSError, ValueError) as error:
                export_errors.append(name + ": " + type(error).__name__ + "; raw owned bytes retained, not complete evidence")

        print("", flush=True)  # separate a killed child's partial line from final diagnostic records
        try:
            device_paths = sorted((output / "device").iterdir()) if (output / "device").is_dir() else []
            ended = console.read_text() if console.is_file() else ""
        except (OSError, ValueError) as error:
            export_errors.append("Raw child evidence unreadable: " + type(error).__name__)
            device_paths, ended = [], ""
        for path in device_paths:
                if path.suffix in (".log", ".json") and "device/" + path.name not in emitted:
                    # On a failed console, avoid duplicate frames already ended there.
                    if ('D02_CI_END={"name": "device/' + path.name + '"') not in ended:
                        retain(path, "device/" + path.name)
        for _, path in supervisor.background:
            retain(path, "host/" + path.name)
            process_state = path.with_suffix(".process.json")
            retain(process_state, "host/" + process_state.name)
        if export_errors or cleanup or report.get("evidence_errors"):
            report["result"] = "FAIL: cleanup/text evidence incomplete; completed device details retained separately"
            report["cleanup_errors"] = cleanup
            report.setdefault("evidence_errors", []).extend(export_errors)
        (output / "RESULT.json").write_text(json.dumps(report, indent=2) + "\n")
        retain(output / "RESULT.json", "host/RESULT.json")
        print("D02_KVM_RESULT=" + json.dumps(report), flush=True)
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with Path(summary).open("a") as out:
                out.write("D02 accelerated Android: " + report["result"] + "\n\n")
                out.write("Source: " + args.expected_sha + "; complete hash-framed text evidence in job log.\n")
                out.write("19 native/6 phases: " + str(report["native_tests"]) + "/" + str(report["external_phases"]) + ". APK distribution,16KB/physical gates separate.\n")
        if failure is not None or cleanup or export_errors or report.get("evidence_errors"):
            raise SystemExit(1)


if __name__ == "__main__":
    main()
