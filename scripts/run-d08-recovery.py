#!/usr/bin/env python3
"""Unarmed by default. One fixed public CI/owned AVD only; no manual device option.

No SDK, network, process, package or KVM operations happen before exact admission.
The production APK is downloaded by the normal pinned Actions artifact action,
not by a replacement credential/CLI/dispatch path.
"""
import argparse
import base64
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
import xml.etree.ElementTree as ET
from d08_kvm_acl import RecoveryKvmAcl
from d08_recovery_contract import (SOURCE, APK_HASH, APP, PROBE, FILES, EXPECTED_REQUEST,
    admit, check_source_changes, validate_device_result, evidence_frame)
from d08_probe_artifact import PROBE_SOURCE, PROBE_SHA, verify_probe, fixed_file

ROOT = Path(__file__).resolve().parents[1]
REQUEST = ROOT / "checks/device-recovery/request.json"
APK = ROOT / ".local-evidence/d08-fixed-apk/app-debug.apk"
PROBE_DIR = ROOT / ".local-evidence/d08-verified-probe"
COLLECT_SECONDS = 120
CLEANUP_SECONDS = 120


def load_driver():
    spec = importlib.util.spec_from_file_location("d08_existing_supervision", ROOT / "scripts/run-d02-kvm-ci.py")
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def digest(path):
    d = hashlib.sha256()
    with Path(path).open("rb") as f:
        while block := f.read(1024 * 1024): d.update(block)
    return d.hexdigest()


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def admission(env=None):
    env = os.environ if env is None else env
    request = json.loads(REQUEST.read_text())
    # The committed request is false. An explicit reviewed approval delta is necessary.
    if request.get("executionApproved") is not True:
        raise RuntimeError("D08 device execution remains unapproved; no action performed")
    event = json.loads(Path(env["GITHUB_EVENT_PATH"]).read_text())
    admit(request, env, event)
    if git("rev-parse", "HEAD") != env["GITHUB_SHA"] or git("status", "--porcelain"):
        raise RuntimeError("Exact clean reviewed harness checkout required")
    subprocess.run(["git", "merge-base", "--is-ancestor", SOURCE, "HEAD"], cwd=ROOT, check=True)
    lines = git("diff", "--name-status", SOURCE, "HEAD").splitlines()
    check_source_changes([tuple(line.split("\t")) for line in lines])
    for line in lines:
        path = line.split("\t")[1]
        if not (ROOT / path).is_file() or (ROOT / path).is_symlink():
            raise RuntimeError("Only regular reviewed harness sources are admitted")
    return {"sha": env["GITHUB_SHA"], "run": env["GITHUB_RUN_ID"], "requestSha256": digest(REQUEST),
            "deadline": time.monotonic() + EXPECTED_REQUEST["driverSeconds"], "uid": os.getuid()}


def receipt_path():
    return Path(os.environ["RUNNER_TEMP"]) / "aiyou-d08-admission.json"


def recheck():
    current = admission(); before = json.loads(receipt_path().read_text())
    for k in ("sha", "run", "requestSha256", "uid"):
        if current[k] != before[k]: raise RuntimeError("Admission identity changed")
    if not time.monotonic() < before["deadline"] <= time.monotonic() + EXPECTED_REQUEST["driverSeconds"]:
        raise RuntimeError("Admission deadline expired/extended")
    return before


def verify_terms(sdk):
    expected = "24333f8a63b6825ea9c5514f83c2829b004d1fee"
    accepted = sdk / "licenses/android-sdk-license"
    if not accepted.is_file() or expected not in accepted.read_text().splitlines():
        raise RuntimeError("Matching existing SDK acceptance missing; never accept/write terms")
    for name, expected_xml in (("emulator-package.xml", "af0a044e5fc70a53f74dd4f2c528972e38b72a49b8e793f1249c903957e306ec"),
                              ("image-package.xml", "9e2a37b1092afc130a5803e968a3e5ee597130eb6134bccb2d12a89f9fb81011")):
        p = ROOT / "checks/sdk-device" / name
        if digest(p) != expected_xml: raise RuntimeError("Frozen SDK metadata differs")
        licenses = [n.text for n in ET.parse(p).getroot().iter() if n.tag.split("}")[-1] == "license"]
        if len(licenses) != 1 or hashlib.sha256(licenses[0].encode()).hexdigest() != "aaf80cd0aee7e569ffa8a4be1b61189c0fefccf23068e38dfafe336289b8c723":
            raise RuntimeError("Frozen complete SDK terms differ")


def prepare_probe():
    recheck()
    verify_probe(PROBE_DIR)
    fixed_file(APK, 27161705, APK_HASH)
    subprocess.run([sys.executable, ROOT / "scripts/check-sdk.py"], cwd=ROOT, check=True)
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    verify_terms(sdk)
    print("D08 fixed SDK probe and production APK authenticated; device execution not performed")


def package_snapshot(path):
    rows = []
    for p in sorted(path.rglob("*")):
        s = p.lstat()
        rows.append((str(p.relative_to(path)), s.st_dev, s.st_ino, s.st_mode, s.st_size, s.st_mtime_ns,
                     os.readlink(p) if p.is_symlink() else ""))
    return rows


def emit_probe(name, raw):
    frame = evidence_frame(name, raw)
    encoded = frame.pop("base64"); frame["chunks"] = (len(encoded) + 4095) // 4096
    print("D08_FILE=" + json.dumps(frame), flush=True)
    for i, at in enumerate(range(0, len(encoded), 4096)):
        print("D08_CHUNK=" + json.dumps({"name": name, "index": i, "base64": encoded[at:at + 4096]}), flush=True)
    print("D08_END=" + json.dumps(frame), flush=True)


def cleanup_package(command, adb, serial, package, expected_hash, prefix):
    """Only after the same owned AVD's pre-install absence check. Recheck bytes
    before even force-stop; a replacement installation is never ours to remove.
    """
    current = command([adb, "-s", serial, "shell", "pm", "path", package], prefix + "-path", require=False)
    path = current.stdout.strip()
    if not path and not current.stderr.strip() and current.returncode in (0, 1): return
    if current.returncode != 0 or current.stderr.strip() or not re.fullmatch(r"package:/data/app/[A-Za-z0-9_~=/.-]+/base\.apk", path) or ".." in path:
        raise RuntimeError("Installed identity unknown; no cleanup mutation")
    proof = command([adb, "-s", serial, "shell", "sha256sum", path[8:]], prefix + "-hash").stdout.strip()
    if proof.split() != [expected_hash, path[8:]]:
        raise RuntimeError("Installed APK changed; no cleanup mutation")
    command([adb, "-s", serial, "shell", "am", "force-stop", package], prefix + "-stop")
    removed = command([adb, "-s", serial, "uninstall", package], prefix + "-uninstall")
    if removed.stdout.strip() != "Success": raise RuntimeError("Owned uninstall ACK absent")
    after = command([adb, "-s", serial, "shell", "pm", "path", package], prefix + "-absent", require=False)
    if after.stdout.strip() or after.stderr.strip() or after.returncode not in (0, 1):
        raise RuntimeError("Owned package removal unconfirmed")


def instrument_and_collect(instrument, read, save, deadline, report, clock=time.monotonic):
    """Collect before caller cleanup, even after a failed/aborted shell command.
    The original exception stays primary; partial evidence can never grant PASS.
    No device retry and no deadline extension. Reader launch/stop gets a 20s margin.
    """
    original_error = None; terminal = ""
    try: terminal = instrument()
    except BaseException as error:
        original_error = error
        report["instrumentationFailure"] = type(error).__name__
    files = {}; missing = []
    collect_end = min(clock() + COLLECT_SECONDS, deadline - CLEANUP_SECONDS)
    for name in FILES:
        try:
            timeout = min(5, collect_end - clock() - 20)
            if timeout <= 0: raise TimeoutError("Evidence budget exhausted; no further read")
            raw = read(name, timeout)
            evidence_frame(name, raw)
            save(name, raw)
            files[name] = raw
        except Exception as error:
            missing.append({"name": name, "failureType": type(error).__name__})
    report["missingEvidence"] = missing
    if original_error is not None: raise original_error
    if missing: raise RuntimeError("Incomplete real evidence; available files retained, no success")
    return terminal, files


def execute():
    admitted = recheck()
    fixed_file(APK, 27161705, APK_HASH)
    harness = verify_probe(PROBE_DIR)
    harness_hash = PROBE_SHA
    if os.uname().machine != "x86_64": raise RuntimeError("Standard Linux x86_64 runner required")
    output = Path(os.environ["RUNNER_TEMP"]) / ("aiyou-d08-" + admitted["run"] + "-1")
    output.mkdir(exist_ok=False)
    module = load_driver()
    # D02 helper emits UTF8 logs only. Screenshot transfer stdout is intentionally
    # omitted there, then decoded and emitted through the five-file D08 allowlist.
    original_emit = module.emit_file
    def emit_text(path, name):
        # Existing Supervisor replaces a command's extension via with_suffix.
        if re.fullmatch(r"host/read-0[123]-(?:source|restored|deleted)\.stdout\.log", name): return
        original_emit(path, name)
    module.emit_file = emit_text
    report = {"result": "NOT RUN", "productionSha": SOURCE, "harnessSha": admitted["sha"], "run": admitted["run"],
              "probeBuildSha": PROBE_SOURCE, "probeApkSha256": PROBE_SHA,
              "attempt": 1, "deviceScope": "one owned API35 x86_64 AVD", "newAgreements": 0, "artifactUploads": 0}
    supervisor = module.Supervisor(output, report)
    supervisor.deadline = admitted["deadline"]
    run = supervisor.run
    fixed = module.fixed
    fixed.OUTPUT, fixed.command, fixed.EXPERIMENT_DEADLINE = output, run, supervisor.deadline
    fixed.CLEANUP_RESERVE_SECONDS = 120
    sdk = fixed.SDK
    before = fixed.license_snapshot()
    acl = RecoveryKvmAcl(run, report)
    processes, handles, installed = [], [], []
    env = os.environ.copy(); adb = sdk / "platform-tools/adb"
    serial = None; probe_install_attempted = False; app_creation_attempted = False; failure = None; avd = None; avd_inode = None
    try:
        # Fail before network/setup if this VM cannot possibly provide the approved route.
        for tool in ("ss", "getfacl", "setfacl"):
            if shutil.which(tool) is None: raise RuntimeError("Required existing tool absent: " + tool)
        acl.preflight()
        verify_terms(sdk)
        run([sys.executable, ROOT / "scripts/check-sdk.py"], "existing-sdk")
        if min(shutil.disk_usage(output).free, shutil.disk_usage(sdk).free) < 8 * 1024 ** 3:
            raise RuntimeError("Require 8GiB free; never remove unknown files")
        for p in (adb, sdk / "cmdline-tools/latest/bin/avdmanager", sdk / "build-tools/36.0.0/aapt"):
            if not p.is_file(): raise RuntimeError("Existing required SDK tool missing")
        report["existingAdb"] = {"sha256": digest(adb), "version": run([adb, "version"], "existing-adb-version").stdout}
        for item in fixed.PACKAGES:
            target = sdk.joinpath(*item["path"].split(";"))
            if target.exists() or target.is_symlink():
                if target.is_symlink() or not (target / "package.xml").is_file() or digest(target / "package.xml") != item["xml_sha256"]:
                    raise RuntimeError("Existing SDK package differs; no overwrite")
            else:
                fixed.install_owned_package(item)
                installed.append((target, target.stat().st_ino, package_snapshot(target)))
        if fixed.license_snapshot() != before: raise RuntimeError("SDK terms changed")
        emulator = sdk / "emulator/emulator"
        version_result = run([emulator, "-no-window", "-version"], "emulator-version")
        version = version_result.stdout + version_result.stderr
        if not re.search(r"\b37\.2\.12(?:\.0)?\b", version): raise RuntimeError("Wrong actual emulator")
        aapt = sdk / "build-tools/36.0.0/aapt"
        for name, path, package in (("production", APK, APP), ("probe", harness, PROBE)):
            badging = run([aapt, "dump", "badging", path], name + "-badging").stdout
            if not re.search(r"^package: name='" + re.escape(package) + r"'", badging, re.M): raise RuntimeError("Actual APK package mismatch")
            if name == "probe":
                manifest = run([aapt, "dump", "xmltree", path, "AndroidManifest.xml"], "probe-manifest").stdout
                if not re.search(r'A: android:targetPackage[^\n]*="' + re.escape(PROBE) + '"', manifest) or \
                        not re.search(r'A: android:name[^\n]*="(?:' + re.escape(PROBE) + r')?\.RecoveryInstrumentation"', manifest) or \
                        "uses-permission" in manifest:
                    raise RuntimeError("Probe manifest/permission boundary differs")
        acl.grant()
        acceleration_result = run([emulator, "-no-window", "-accel-check"], "kvm-check")
        acceleration = acceleration_result.stdout + acceleration_result.stderr
        if not re.search(r"KVM.*(?:installed|usable)", acceleration, re.I): raise RuntimeError("Actual KVM acceleration not confirmed")
        for key in ("ANDROID_USER_HOME", "ANDROID_AVD_HOME", "ANDROID_EMULATOR_HOME"):
            path = output / key.lower(); path.mkdir(); env[key] = str(path)
        env.pop("ANDROID_I_WANT_MY_TCG", None)
        manager = sdk / "cmdline-tools/latest/bin/avdmanager"
        token = uuid.uuid4().hex; avd_name = "aiyou_d08_" + token; avd = output / "owned-avd"
        run([manager, "create", "avd", "-n", avd_name, "-k", fixed.PACKAGES[1]["path"], "-d", "pixel_2", "-p", avd],
            "create-owned-avd", env=env, input_text="no\n")
        if avd.is_symlink(): raise RuntimeError("Own AVD root is unexpectedly a symlink")
        avd_inode = avd.stat().st_ino
        config = (avd / "config.ini").read_text()
        image_paths = re.findall(r"^image\.sysdir\.1=(.+)$", config, re.M)
        if len(image_paths) != 1 or (sdk / image_paths[0]).resolve() != sdk.joinpath(*fixed.PACKAGES[1]["path"].split(";")).resolve():
            raise RuntimeError("AVD actual image differs")
        port, _, server_port = module.free_ports(); serial = "emulator-" + str(port)
        env["ANDROID_ADB_SERVER_PORT"] = str(server_port); env["ADB_SERVER_SOCKET"] = "tcp:127.0.0.1:" + str(server_port)
        for name, argv in (("adb", [adb, "-L", "tcp:" + str(server_port), "nodaemon", "server"]),
                           ("emulator", [emulator, "-avd", avd_name, "-port", str(port), "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-accel", "on", "-gpu", "swiftshader", "-memory", "2048", "-cores", "2"])):
            if name == "emulator":
                listener = run(["ss", "-H", "-lntp", "sport = :" + str(server_port)], "adb-listener").stdout
                module.verify_live_owned_command(processes[0][0]); module.verify_loopback_listener(listener, server_port, processes[0][0].command_pid)
            handle = (output / (name + ".log")).open("wb"); handles.append(handle)
            process = module.OwnedProcess([str(v) for v in argv], output / (name + ".process.json"), cwd=ROOT, env=env, stdout=handle, stderr=subprocess.STDOUT)
            processes.append((process, name)); supervisor.background.append((process, output / (name + ".log")))
            if name == "adb":
                ready = time.monotonic() + 10
                while time.monotonic() < ready:
                    try:
                        with socket.create_connection(("127.0.0.1", server_port), timeout=.3): break
                    except OSError: time.sleep(.1)
                else: raise RuntimeError("Owned adb listener not ready")
        boot_end = min(time.monotonic() + 240, supervisor.deadline - 120)
        poll = 0
        while time.monotonic() < boot_end:
            value = run([adb, "-s", serial, "shell", "getprop", "sys.boot_completed"], "boot-" + str(poll),
                        env=env, require=False, timeout=min(10, boot_end-time.monotonic()))
            if value.returncode == 0 and value.stdout.strip() == "1": break
            poll += 1; time.sleep(min(1, max(0, boot_end-time.monotonic())))
        else: raise RuntimeError("Owned emulator boot deadline")
        if run([adb, "-s", serial, "shell", "getprop", "ro.build.version.sdk"], "actual-api", env=env).stdout.strip() != "35":
            raise RuntimeError("Actual API differs")
        for package in (APP, PROBE):
            p = run([adb, "-s", serial, "shell", "pm", "path", package], "fresh-" + package, env=env, require=False)
            if p.stdout.strip() or p.stderr.strip() or p.returncode not in (0, 1): raise RuntimeError("Existing/unknown package retained")
        remote = "/data/local/tmp/aiyou-d08-" + token
        run([adb, "-s", serial, "shell", "mkdir", remote], "owned-device-directory", env=env)
        # Authenticate again after SDK/AVD preparation, immediately before use.
        fixed_file(APK, 27161705, APK_HASH)
        if verify_probe(PROBE_DIR) != harness: raise RuntimeError("Pinned probe path changed")
        run([adb, "-s", serial, "push", APK, remote + "/app.apk"], "push-fixed-apk", env=env)
        if verify_probe(PROBE_DIR) != harness: raise RuntimeError("Pinned probe changed before install")
        probe_install_attempted = True
        run([adb, "-s", serial, "install", "-t", harness], "install-own-probe", env=env)
        # Keep the evidence window even when the instrumentation shell itself fails.
        remaining = min(960, int(supervisor.deadline - time.monotonic() - CLEANUP_SECONDS - COLLECT_SECONDS - 20))
        if remaining < 60: raise RuntimeError("Insufficient remaining actual-device budget")
        app_creation_attempted = True
        def instrument():
            return run([adb, "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "run_token", token,
                        "-e", "production_sha", SOURCE, "-e", "budget_ms", str(remaining * 1000),
                        PROBE + "/.RecoveryInstrumentation"], "actual-ui-instrumentation", env=env, timeout=remaining).stdout
        def read(name, timeout):
            encoding = "base64" if name.endswith(".png") else "cat"
            value = run([adb, "-s", serial, "exec-out", "run-as", PROBE, encoding, "files/evidence/" + name],
                        "read-" + name, env=env, timeout=timeout, deadline_bound=False).stdout
            return base64.b64decode("".join(value.split()), validate=True) if encoding == "base64" else value.encode()
        def save(name, raw):
            (output / name).write_bytes(raw); emit_probe(name, raw)
        terminal, files = instrument_and_collect(instrument, read, save, supervisor.deadline, report)
        result = json.loads(files["result.json"])
        validate_device_result(terminal, result, files["junit.xml"], token)
        report.update(result="PASS: one actual synthetic SAF/new-install recovery, not D01/device-wide acceptance", probe=result,
                      originalApkSha256=APK_HASH, probeApkSha256=harness_hash)
    except BaseException as error:
        failure = error
        report.update(result="FAIL; unfinished stages NOT RUN", failureType=type(error).__name__, reason=str(error))
    finally:
        cleanup = []
        def cleanup_run(argv, name, **kwargs):
            remaining = admitted["deadline"] - time.monotonic()
            if remaining <= 0: raise RuntimeError("Cleanup deadline reached; no further mutation")
            return run(argv, name, env=env, timeout=min(10, remaining), deadline_bound=False, **kwargs)
        if serial is not None:
            for attempted, package, expected, prefix in ((app_creation_attempted, APP, APK_HASH, "cleanup-app"),
                                                        (probe_install_attempted, PROBE, harness_hash, "cleanup-probe")):
                if attempted:
                    try: cleanup_package(cleanup_run, adb, serial, package, expected, prefix)
                    except Exception as e: cleanup.append(prefix + ": " + type(e).__name__)
        processes_stopped = True
        for process, name in reversed(processes):
            try:
                if admitted["deadline"] - time.monotonic() < 12: raise RuntimeError("Owned stop deadline unavailable")
                module.terminate_owned(process, name, report)
            except Exception as e:
                processes_stopped = False; cleanup.append(name + ": " + type(e).__name__)
        for handle in handles: handle.close()
        for _, name in processes:
            try: module.emit_file(output / (name + ".log"), "host/" + name + ".log")
            except Exception as e: cleanup.append(name + " evidence: " + type(e).__name__)
        # Cleanup commands remain inside the admitted deadline; never extend it.
        acl.command = cleanup_run
        try: acl.restore()
        except Exception as e: cleanup.append("KVM: " + type(e).__name__)
        for path, inode, snapshot in reversed(installed):
            try:
                remaining = min(10, admitted["deadline"] - time.monotonic())
                with fixed.archive_download_deadline(remaining):
                    if not processes_stopped or path.is_symlink() or path.stat().st_ino != inode or package_snapshot(path) != snapshot:
                        raise RuntimeError("Owned SDK directory changed; retained")
                    shutil.rmtree(path)
            except Exception as e: cleanup.append("owned SDK directory: " + type(e).__name__)
        if avd is not None and avd_inode is not None:
            try:
                with fixed.archive_download_deadline(min(10, admitted["deadline"] - time.monotonic())):
                    if not processes_stopped or avd.is_symlink() or avd.stat().st_ino != avd_inode or not avd.resolve().is_relative_to(output.resolve()):
                        raise RuntimeError("Owned AVD identity/stop unconfirmed; retained")
                    shutil.rmtree(avd)
                report["ownedAvdCleanup"] = "PASS: own stopped AVD removed"
            except Exception as e: cleanup.append("owned AVD: " + type(e).__name__)
        if fixed.license_snapshot() != before: cleanup.append("SDK acceptance records changed")
        report["cleanupErrors"] = cleanup
        if cleanup: report["result"] = "FAIL: cleanup unconfirmed; actual device result retained separately"
        target = output / "RESULT.json"; target.write_text(json.dumps(report, indent=2) + "\n")
        module.emit_file(target, "host/RESULT.json")
        print("D08_FINAL=" + json.dumps({"result": report["result"], "source": SOURCE, "harness": admitted["sha"]}), flush=True)
        # The new AVD and evidence live only in this job's RUNNER_TEMP; no cache/artifact upload.
    if failure is not None or report["cleanupErrors"]: raise RuntimeError("D08 failed; original evidence retained") from failure


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("admit", "prepare-probe", "execute"))
    args = parser.parse_args()
    if args.mode == "admit":
        receipt = admission(); p = receipt_path()
        with p.open("x") as f: json.dump(receipt, f)
        print("D08 admitted: exact reviewed request; no device action yet")
    elif args.mode == "prepare-probe": prepare_probe()
    else: execute()


if __name__ == "__main__": main()
