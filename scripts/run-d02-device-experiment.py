#!/usr/bin/env python3
"""One bounded software-emulator experiment; never accept terms or change KVM."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import stat
import subprocess
import time
import threading
import urllib.request
import uuid
import zipfile
from d02_native_trace import summarize
from d02_device_acceptance import parse_result, run_storage, collect_source
from d02_deadline import archive_download_deadline

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "checks/sdk-device"
OUTPUT = ROOT / "app/build/reports/d02-device-experiment"
SDK = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "/nonexistent-sdk")))
HASH = "24333f8a63b6825ea9c5514f83c2829b004d1fee"
EXPERIMENT_DEADLINE = None
CLEANUP_RESERVE_SECONDS = 75  # bounded observers + logcat + owned emulator + result footer
PACKAGES = [
    {"path": "emulator", "revision": "37.2.12", "xml": "emulator-package.xml",
     "xml_sha256": "af0a044e5fc70a53f74dd4f2c528972e38b72a49b8e793f1249c903957e306ec",
     "url": "https://dl.google.com/android/repository/emulator-linux_x64-16428233.zip",
     "bytes": 349654171, "sha1": "cd7362ea55dfb86a418958138dc396e74165dd01"},
    {"path": "system-images;android-35;default;x86_64", "revision": "2", "xml": "image-package.xml",
     "xml_sha256": "9e2a37b1092afc130a5803e968a3e5ee597130eb6134bccb2d12a89f9fb81011",
     "url": "https://dl.google.com/android/repository/sys-img/android/x86_64-35_r02.zip",
     "bytes": 782404023, "sha1": "2d857d170c0d1b827149565da34b3383e5306f7f"},
]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(args, name, timeout=90, env=None, input_text=None, require=True, deadline_bound=True):
    if deadline_bound and EXPERIMENT_DEADLINE is not None:
        remaining = EXPERIMENT_DEADLINE - time.monotonic() - CLEANUP_RESERVE_SECONDS
        if remaining <= 0:
            (OUTPUT / (name + ".log")).write_text("NOT RUN: overall experiment deadline reached before command.\n")
            raise RuntimeError("Overall experiment deadline reached")
        timeout = min(timeout, remaining)
    try:
        result = subprocess.run([str(x) for x in args], text=True, input=input_text,
                                capture_output=True, timeout=timeout, env=env)
    except subprocess.TimeoutExpired as error:
        def captured(value):
            return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value or ""
        (OUTPUT / (name + ".log")).write_text(captured(error.stdout) + captured(error.stderr)
                                              + f"\nFAIL: command deadline exceeded ({timeout}s).\n")
        raise
    text = result.stdout + result.stderr
    (OUTPUT / (name + ".log")).write_text(text)
    if require and result.returncode:
        raise RuntimeError(f"{name}: exit {result.returncode}; preserved log")
    return result


def license_snapshot():
    directory = SDK / "licenses"
    return {str(p.relative_to(directory)): digest(p) for p in sorted(directory.rglob("*")) if p.is_file()}


def compile_checker():
    cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
    jars = []
    for item in json.loads((SOURCE / "cached-jars.json").read_text()):
        jar = cache / item["cache_path"]
        if not jar.is_file() or jar.stat().st_size != item["bytes"] or digest(jar) != item["sha256"]:
            raise RuntimeError("Required fixed cached SDK helper JAR absent/different; no downloader used: " + item["cache_path"])
        jars.append(str(jar))
    classes = OUTPUT / "checker-classes"
    classes.mkdir()
    java_home = Path(os.environ["JAVA_HOME"])
    classpath = os.pathsep.join(jars)
    command([java_home / "bin/javac", "-cp", classpath, "-d", classes, SOURCE / "ExistingSdkLicense.java"], "checker-compile")
    return [java_home / "bin/java", "-cp", str(classes) + os.pathsep + classpath,
            "ExistingSdkLicense", SDK, SOURCE / "emulator-package.xml", SOURCE / "image-package.xml"]


def install_owned_package(item):
    if EXPERIMENT_DEADLINE is not None and time.monotonic() >= EXPERIMENT_DEADLINE - CLEANUP_RESERVE_SECONDS:
        raise RuntimeError("Overall experiment deadline reached before SDK download")
    target = SDK.joinpath(*item["path"].split(";"))
    if not target.resolve().is_relative_to(SDK.resolve()):
        raise RuntimeError("SDK target escapes the existing host SDK root")
    if target.exists() or target.is_symlink():
        raise RuntimeError("Refuse to overwrite an existing SDK package: " + item["path"])
    archive = OUTPUT / (item["xml"] + ".zip")
    started = time.monotonic()
    size = 0
    sha1, sha256 = hashlib.sha1(), hashlib.sha256()
    # One official URL/attempt only. No terms prompt, mirror, TLS bypass or retry.
    available = min(240, EXPERIMENT_DEADLINE - time.monotonic() - CLEANUP_RESERVE_SECONDS) \
        if EXPERIMENT_DEADLINE is not None else 240
    # An own-process POSIX timer bounds even a blocking read at the deadline.
    # Refuse an existing timer; restore its handler. Ubuntu runner only, no other PID signalled.
    with archive_download_deadline(available), urllib.request.urlopen(item["url"], timeout=min(60, available)) as response, archive.open("xb") as out:
        while block := response.read(1024 * 1024):
            size += len(block)
            if size > item["bytes"] or time.monotonic() - started > 240 \
                    or (EXPERIMENT_DEADLINE is not None and time.monotonic() >= EXPERIMENT_DEADLINE - CLEANUP_RESERVE_SECONDS):
                raise RuntimeError("Bounded SDK archive size/download deadline exceeded")
            sha1.update(block)
            sha256.update(block)
            out.write(block)
    if size != item["bytes"] or sha1.hexdigest() != item["sha1"]:
        raise RuntimeError("Fixed official SDK archive checksum/size mismatch")
    item["download_sha256"] = sha256.hexdigest()
    unpacked = OUTPUT / (item["xml"] + ".unpacked")
    unpacked.mkdir()
    links, seen = [], set()
    with zipfile.ZipFile(archive) as zipped:
        for entry in zipped.infolist():
            if EXPERIMENT_DEADLINE is not None and time.monotonic() >= EXPERIMENT_DEADLINE - CLEANUP_RESERVE_SECONDS:
                raise RuntimeError("Overall deadline reached during owned package extraction; partial bytes retained")
            part = PurePosixPath(entry.filename)
            if part.is_absolute() or ".." in part.parts or "\\" in entry.filename or not part.parts:
                raise RuntimeError("Unsafe archive path")
            key = str(part)
            if key in seen:
                raise RuntimeError("Duplicate archive path")
            seen.add(key)
            dest = unpacked.joinpath(*part.parts)
            mode = entry.external_attr >> 16
            if stat.S_ISLNK(mode):
                link = zipped.read(entry).decode("utf-8")
                if Path(link).is_absolute() or not (dest.parent / link).resolve().is_relative_to(unpacked.resolve()):
                    raise RuntimeError("Unsafe archive symlink")
                links.append((dest, link))
            elif entry.is_dir():
                dest.mkdir(parents=True, exist_ok=True)
            elif stat.S_IFMT(mode) not in (0, stat.S_IFREG):
                raise RuntimeError("Unsupported archive entry type")
            else:
                dest.parent.mkdir(parents=True, exist_ok=True)
                with zipped.open(entry) as source, dest.open("xb") as out:
                    while block := source.read(1024 * 1024):
                        if EXPERIMENT_DEADLINE is not None and time.monotonic() >= EXPERIMENT_DEADLINE - CLEANUP_RESERVE_SECONDS:
                            raise RuntimeError("Overall deadline reached during owned member copy; partial bytes retained")
                        out.write(block)
                dest.chmod(mode & 0o777 if mode & 0o777 else 0o644)
    for dest, link in links:
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.symlink_to(link)
    # Official BasicInstaller promotion rule: unwrap a sole top-level directory.
    children = list(unpacked.iterdir())
    payload = children[0] if len(children) == 1 and children[0].is_dir() else unpacked
    if not (payload / "source.properties").is_file() or (payload / "package.xml").exists():
        raise RuntimeError("Unexpected SDK payload layout/metadata; preserve without overwrite")
    target.parent.mkdir(parents=True, exist_ok=True)
    if EXPERIMENT_DEADLINE is not None and time.monotonic() >= EXPERIMENT_DEADLINE - CLEANUP_RESERVE_SECONDS:
        raise RuntimeError("Overall deadline reached before owned SDK promotion")
    payload.rename(target)
    shutil.copyfile(SOURCE / item["xml"], target / "package.xml")
    print("PASS: verified fixed archive installed in previously absent path:", item["path"], sha256.hexdigest(), flush=True)


def instrumentation(adb, serial, component, class_name, count, name, env, extra=(), *, expected_methods=None, phase=None, token=None):
    args = [adb, "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", class_name]
    args.extend(extra)
    args.append(component)
    result = command(args, name, timeout=240, env=env)
    text = result.stdout + result.stderr
    parsed = parse_result(text, count, expected_methods=expected_methods, phase=phase, token=token)
    print("PASS:", name, count, "actual native cases, no skips", flush=True)
    return {"phase": name, **parsed}



def targeted_fourth(adb, serial, component, env, report):
    """One exact case, unchanged 240s ceiling; continuously preserve bounded diagnostics."""
    stop = threading.Event()
    trace_file = OUTPUT / "targeted-fourth-trace.log"
    health_file = OUTPUT / "targeted-fourth-health.log"
    def health():
        with health_file.open("w") as out:
            for sequence in range(50):
                if stop.is_set():
                    break
                snapshot = {"sample": sequence, "host_monotonic": time.monotonic(), "host": {}}
                for name in ("loadavg", "stat", "pressure/cpu"):
                    try:
                        snapshot["host"][name] = (Path("/proc") / name).read_text()[:16384]
                    except OSError as error:
                        snapshot["host"][name] = "UNAVAILABLE: " + type(error).__name__
                try:
                    result = subprocess.run([str(adb), "-s", serial, "shell", "cat", "/proc/loadavg",
                                             "/proc/stat", "/proc/pressure/cpu"], capture_output=True,
                                            text=True, timeout=5, env=env)
                    snapshot["device"] = {"returncode": result.returncode, "stdout": result.stdout[:16384],
                                          "stderr": result.stderr[:2048]}
                except subprocess.TimeoutExpired:
                    snapshot["device"] = {"result": "TIMEOUT (5s); no process killed except owned adb command"}
                except OSError as error:
                    snapshot["device"] = {"result": "UNAVAILABLE: " + type(error).__name__}
                out.write(json.dumps(snapshot) + "\n")
                out.flush()
                if stop.wait(5):
                    break
    with trace_file.open("w") as out:
        logger = subprocess.Popen([str(adb), "-s", serial, "logcat", "-v", "brief", "AIyouD02Trace:I", "*:S"],
                                  stdout=out, stderr=subprocess.STDOUT, env=env)
        sampler = threading.Thread(target=health, name="owned-native-health-observer", daemon=True)
        sampler.start()
        try:
            report["native_tests"] = [instrumentation(adb, serial, component,
                "com.t1doo.aiyou.data.EncryptedRecordsIntegrationTest#fiveAcknowledgedSavesSurviveSeparateDatabaseReopens",
                1, "targeted-fourth", env, ("-e", "aiyouNativeTrace", "1"))]
        finally:
            stop.set()
            sampler.join(timeout=6)
            report["health_sampler_stopped"] = not sampler.is_alive()
            report["health_samples"] = len(health_file.read_text().splitlines()) if health_file.is_file() else 0
            if logger.poll() is None:
                logger.terminate()
                try:
                    logger.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    logger.kill()
                    logger.wait(timeout=2)
            out.flush()
            diagnosis = summarize(trace_file)
            (OUTPUT / "targeted-fourth-trace-summary.log").write_text(json.dumps(diagnosis, indent=2) + "\n")
            report["trace"] = {key: value for key, value in diagnosis.items() if key != "operations"}
    if not diagnosis["trace_complete"] or not report["health_sampler_stopped"] or not report["health_samples"]:
        raise RuntimeError("Targeted case terminal result exists but diagnostic capture is incomplete; preserve evidence")
    report["result"] = "PASS: one targeted native case and complete ten-open trace; remaining native suite, cross-process,16K,physical performance NOT RUN"


def run_device(report, targeted=False):
    adb, emulator = SDK / "platform-tools/adb", SDK / "emulator/emulator"
    managers = sorted(SDK.glob("cmdline-tools/*/bin/avdmanager"))
    preferred = SDK / "cmdline-tools/latest/bin/avdmanager"
    manager = preferred if preferred.is_file() else managers[0] if len(managers) == 1 else None
    if not adb.is_file() or not emulator.is_file() or manager is None:
        raise RuntimeError("Installed fixed payload or existing command-line tools incomplete")
    env = os.environ.copy()
    for var, name in [("ANDROID_USER_HOME", "android-user"), ("ANDROID_AVD_HOME", "avds"), ("ANDROID_EMULATOR_HOME", "emulator-home")]:
        directory = OUTPUT / name
        directory.mkdir()
        env[var] = str(directory)
    env["ANDROID_HOME"] = env["ANDROID_SDK_ROOT"] = str(SDK)
    devices = command([manager, "list", "device"], "existing-device-definitions", env=env).stdout
    if '"pixel_2"' not in devices:
        raise RuntimeError("Required existing device definition absent; no guessed hardware profile")
    name = "aiyou_d02_" + uuid.uuid4().hex
    command([manager, "create", "avd", "-n", name, "-k", PACKAGES[1]["path"], "-d", "pixel_2",
             "-p", OUTPUT / "owned-avd"], "create-owned-avd", env=env, input_text="no\n")
    # 'no' declines custom hardware; it never accepts a license agreement.
    gpu = command([emulator, "-help-gpu"], "fixed-emulator-gpu-help", env=env).stdout
    if not re.search(r"\bswiftshader\b", gpu):
        raise RuntimeError("Required headless software GPU unsupported by actual fixed emulator")
    port = None
    for candidate in range(5580, 5600, 2):
        sockets = []
        try:
            for p in (candidate, candidate + 1):
                sock = socket.socket()
                sockets.append(sock)
                sock.bind(("127.0.0.1", p))
            port = candidate
            break
        except OSError:
            pass
        finally:
            for sock in sockets:
                sock.close()
    if port is None:
        raise RuntimeError("No free owned emulator port pair; no existing process stopped")
    serial = "emulator-" + str(port)
    # Explicit unsupported software-emulation experiment, never a license yes.
    env["ANDROID_I_WANT_MY_TCG"] = "yes"
    report["software_emulation"] = "unsupported TCG experiment, no KVM permission change"
    report["serial"] = serial
    with (OUTPUT / "emulator.log").open("w") as log:
        process = subprocess.Popen([str(emulator), "-avd", name, "-port", str(port), "-no-window", "-no-audio",
                                    "-no-boot-anim", "-no-snapshot", "-accel", "off", "-gpu", "swiftshader",
                                    "-memory", "2048", "-cores", "2"], stdout=log, stderr=subprocess.STDOUT, env=env)
        report["owned_emulator_pid"] = process.pid
        try:
            deadline = min(time.monotonic() + 720, EXPERIMENT_DEADLINE - CLEANUP_RESERVE_SECONDS)
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("Owned experimental emulator exited before boot")
                boot = command([adb, "-s", serial, "shell", "getprop", "sys.boot_completed"], "boot-poll",
                               timeout=15, env=env, require=False)
                if boot.returncode == 0 and boot.stdout.strip() == "1":
                    break
                time.sleep(5)
            else:
                raise RuntimeError("Unsupported software emulator did not boot within bounded budget (at most 720 seconds)")
            report["api"] = command([adb, "-s", serial, "shell", "getprop", "ro.build.version.sdk"], "device-api", env=env).stdout.strip()
            report["abi"] = command([adb, "-s", serial, "shell", "getprop", "ro.product.cpu.abi"], "device-abi", env=env).stdout.strip()
            report["page_size"] = command([adb, "-s", serial, "shell", "getconf", "PAGE_SIZE"], "device-page-size", env=env).stdout.strip()
            if (report["api"], report["abi"], report["page_size"]) != ("35", "x86_64", "4096"):
                raise RuntimeError("Actual runtime differs from required API35/x86_64/4K experiment")
            app = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
            tests = sorted((ROOT / "core/data/build/outputs/apk/androidTest/debug").glob("*.apk"))
            if not app.is_file() or len(tests) != 1:
                raise RuntimeError("Require actual compiled app and single instrumentation APK")
            aapt = SDK / "build-tools/36.0.0/aapt"
            badging = command([aapt, "dump", "badging", tests[0]], "test-apk-badging").stdout
            manifest = command([aapt, "dump", "xmltree", tests[0], "AndroidManifest.xml"], "test-apk-manifest").stdout
            package = re.search(r"^package: name='([^']+)'", badging, re.M).group(1)
            target = re.search(r'A: android:targetPackage[^\n]*="([^"]+)"', manifest).group(1)
            runner = "androidx.test.runner.AndroidJUnitRunner"
            if runner not in manifest:
                raise RuntimeError("Actual declared instrumentation runner differs")
            report["test_package"], report["target_package"] = package, target
            command([adb, "-s", serial, "install", str(app)], "install-app", env=env)
            command([adb, "-s", serial, "install", "-t", str(tests[0])], "install-storage-test", env=env)
            component = package + "/" + runner
            inventory = command([adb, "-s", serial, "shell", "pm", "list", "instrumentation"], "actual-instrumentation", env=env).stdout
            if component not in inventory or "target=" + target not in inventory:
                raise RuntimeError("Actual installed instrumentation does not match manifest")
            if targeted:
                targeted_fourth(adb, serial, component, env, report)
                return
            collect_source(ROOT)
            run_storage(command, instrumentation, adb, serial, component, (package, target), env, report, str(uuid.uuid4()))
            report["result"] = "PASS: 19 native cases + distinct-process attachment/edit/capture persistence and confirmation; production Activity/16K/physical performance NOT RUN"

        finally:
            try:
                command([adb, "-s", serial, "logcat", "-d"], "device-logcat", timeout=15,
                        env=env, require=False, deadline_bound=False)
            except Exception as error:
                (OUTPUT / "device-logcat-error.log").write_text(str(error) + "; owned process cleanup continues.\n")
            # Only the exact Popen instance created here; never adb kill-server or unknown PID.
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)


def main():
    global EXPERIMENT_DEADLINE
    parser = argparse.ArgumentParser()
    parser.add_argument("--license-check-only", action="store_true")
    parser.add_argument("--targeted-fourth", action="store_true", help="only the original ten-open fourth case, with bounded test-only diagnostics")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=False)
    before = license_snapshot()
    report = {"result": "NOT RUN", "packages": PACKAGES, "license_hash": HASH,
              "license_before": before, "physical_performance": "NOT RUN", "16k_execution": "NOT RUN",
              "scope": "one targeted fourth case" if args.targeted_fourth else "original full native experiment",
              "native_tests": []}
    try:
        for item in PACKAGES:
            if digest(SOURCE / item["xml"]) != item["xml_sha256"]:
                raise RuntimeError("Official generated package metadata changed")
        checker = compile_checker()
        command(checker, "existing-host-license")
        if args.license_check_only:
            report["result"] = "PASS: existing host license only; no installation attempted"
            return
        if not SDK.is_dir():
            raise RuntimeError("Existing licensed host SDK absent")
        EXPERIMENT_DEADLINE = time.monotonic() + 22 * 60
        report["whole_experiment_budget_seconds"] = 22 * 60
        report["cleanup_reserve_seconds"] = CLEANUP_RESERVE_SECONDS
        if shutil.disk_usage(SDK).free < 7 * 1024 ** 3:
            raise RuntimeError("Insufficient free disk for bounded owned SDK experiment")
        for item in PACKAGES:
            install_owned_package(item)
        command(checker + ["discover"], "installed-fixed-package-discovery")
        run_device(report, targeted=args.targeted_fourth)
    except Exception as error:
        report["result"] = "FAIL: " + str(error) + "; native gate NOT RUN unless passing terminal results recorded"
        raise
    finally:
        report["license_after"] = license_snapshot()
        report["acceptance_records_unchanged"] = before == report["license_after"]
        (OUTPUT / "RESULT.json").write_text(json.dumps(report, indent=2) + "\n")
        print("D02_DEVICE_RESULT=" + json.dumps(report), flush=True)
        if not report["acceptance_records_unchanged"]:
            raise RuntimeError("Existing acceptance records changed unexpectedly")


if __name__ == "__main__":
    main()
