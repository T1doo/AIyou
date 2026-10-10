#!/usr/bin/env python3
"""Actual APK bytes + fixed existing SDK tools; no installer, device or network."""
import argparse
from dataclasses import asdict
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from d02_elf import (ElfError, layout_checks, parse_elf, readelf_selected_headers,
                     selected_headers, PT_LOAD, PT_GNU_RELRO)

ROOT = Path(__file__).resolve().parents[1]
NDK_REVISION = "27.3.13750724"
BUILD_TOOLS_REVISION = "36.0.0"
ACCEPTED_TERMS_HASH = "24333f8a63b6825ea9c5514f83c2829b004d1fee"
ABIS = {"arm64-v8a": (64, 183), "armeabi-v7a": (32, 40),
        "x86": (32, 3), "x86_64": (64, 62)}
MAX_APK_BYTES = 128 * 1024 * 1024
MAX_NATIVE_BYTES = 8 * 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def bounded_read(path, limit):
    with Path(path).open("rb") as source:
        raw = source.read(limit + 1)
    if len(raw) > limit:
        raise ElfError("input exceeds bounded audit size")
    return raw


def apk_members(raw):
    """Whole fixed APK snapshot, four bounded members, no archive path extraction."""
    if len(raw) > MAX_APK_BYTES:
        raise ElfError("APK exceeds bounded audit size")
    expected = {f"lib/{abi}/libsqlcipher.so" for abi in ABIS}
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        infos = archive.infolist()
        names = [i.filename for i in infos]
        if len(names) > 50000 or len(names) != len(set(names)):
            raise ElfError("excessive or duplicate APK ZIP entries")
        actual = {n for n in names if n.endswith("/libsqlcipher.so")}
        if actual != expected:
            raise ElfError("require exactly the four expected SQLCipher ABI paths")
        result = []
        for abi in ABIS:
            name = f"lib/{abi}/libsqlcipher.so"
            info = archive.getinfo(name)
            if not 0 < info.file_size <= MAX_NATIVE_BYTES:
                raise ElfError("native member outside bounded size")
            with archive.open(info) as member:
                data = member.read(MAX_NATIVE_BYTES + 1)
            if len(data) != info.file_size:
                raise ElfError("native member actual size differs")
            pos = info.header_offset
            if pos < 0 or pos + 30 > len(raw) or raw[pos:pos + 4] != b"PK\x03\x04":
                raise ElfError("native ZIP local header missing or truncated")
            name_size, extra_size = struct.unpack_from("<HH", raw, pos + 26)
            payload_offset = pos + 30 + name_size + extra_size
            if payload_offset > len(raw):
                raise ElfError("native ZIP local metadata outside APK")
            result.append((abi, data, {"path": name, "bytes": len(data), "sha256": digest(data),
                                      "compression": info.compress_type, "local_header_offset": pos,
                                      "payload_offset": payload_offset,
                                      "uncompressed_payload_16k_alignment":
                                      ("PASS" if payload_offset % 16384 == 0 else "FAIL")
                                      if info.compress_type == zipfile.ZIP_STORED else "NOT_APPLICABLE_COMPRESSED"}))
        return result


def timeout_output(error):
    """TimeoutExpired streams may be bytes even when the subprocess uses text=True."""
    def text(value):
        return value.decode("utf-8", errors="backslashreplace") if isinstance(value, bytes) else value or ""
    return text(error.stdout) + text(error.stderr)


def command(args, timeout=30, log_path=None):
    try:
        r = subprocess.run([str(a) for a in args], capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        if log_path is not None:
            Path(log_path).write_text(timeout_output(error))
        raise
    if log_path is not None:
        Path(log_path).write_text(r.stdout + r.stderr)
    if r.returncode:
        raise ElfError("read-only audit tool failed: " + Path(str(args[0])).name + "; exit=" + str(r.returncode))
    return r.stdout + r.stderr


def properties(path):
    return {k.strip(): v.strip() for line in path.read_text().splitlines()
            if "=" in line and not line.lstrip().startswith("#")
            for k, v in [line.split("=", 1)]}


def existing_tools(sdk):
    sdk = Path(sdk).resolve()
    ndk = sdk / "ndk" / NDK_REVISION
    tool = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    zipalign = sdk / "build-tools" / BUILD_TOOLS_REVISION / "zipalign"
    needed = [tool, zipalign, ndk / "source.properties", ndk / "package.xml",
              sdk / "build-tools" / BUILD_TOOLS_REVISION / "source.properties",
              sdk / "licenses/android-sdk-license"]
    if any(not p.is_file() for p in needed):
        raise ElfError("NOT RUN: fixed existing NDK27.3/BuildTools36/accepted license absent; no installation attempted")
    if not tool.resolve().is_relative_to(ndk.resolve()):
        raise ElfError("fixed LLVM tool resolves outside installed NDK")
    if properties(ndk / "source.properties").get("Pkg.Revision") != NDK_REVISION:
        raise ElfError("installed NDK revision mismatch")
    if properties(zipalign.parent / "source.properties").get("Pkg.Revision") != BUILD_TOOLS_REVISION:
        raise ElfError("installed Build Tools revision mismatch")
    packages = [e for e in ET.parse(ndk / "package.xml").getroot().iter()
                if e.tag.split("}")[-1] == "localPackage"]
    if len(packages) != 1 or packages[0].attrib.get("path") != "ndk;" + NDK_REVISION:
        raise ElfError("installed NDK localPackage metadata mismatch")
    refs = [e.attrib.get("ref") for e in packages[0] if e.tag.split("}")[-1] == "uses-license"]
    license_path = sdk / "licenses/android-sdk-license"
    before = license_path.read_bytes()
    if refs != ["android-sdk-license"] or ACCEPTED_TERMS_HASH not in before.decode().split():
        raise ElfError("existing accepted canonical SDK terms not verified for NDK")
    version = command([tool, "--version"])
    if "LLVM" not in version:
        raise ElfError("selected oracle is not LLVM readelf")
    return tool, zipalign, license_path, before, {
        "ndk_revision": NDK_REVISION,
        "ndk_package_xml_sha256": digest((ndk / "package.xml").read_bytes()),
        "ndk_source_properties_sha256": digest((ndk / "source.properties").read_bytes()),
        "llvm_readelf": {"path": str(tool), "resolved_path": str(tool.resolve()),
                         "sha256": digest(tool.read_bytes()), "version": version},
        "zipalign": {"path": str(zipalign), "sha256": digest(zipalign.read_bytes()),
                     "build_tools_revision": BUILD_TOOLS_REVISION},
        "license_refs": refs, "accepted_terms_hash": ACCEPTED_TERMS_HASH,
        "accepted_license_before_sha256": digest(before),
        "no_installation_or_acceptance": True,
    }


def audit(apk, sdk, output, apk_source_sha):
    if not re.fullmatch("[a-f0-9]{40}", apk_source_sha):
        raise ElfError("require exact declared APK source SHA")
    output = Path(output).absolute()
    if output.resolve() != output or output.exists():
        raise ElfError("require a new canonical owned output directory, no redirected ancestors")
    tool, zipalign, license_path, before, tool_meta = existing_tools(sdk)
    raw = bounded_read(apk, MAX_APK_BYTES)
    members = apk_members(raw)
    audit_source_sha = command(["git", "-C", ROOT, "rev-parse", "HEAD"]).strip()
    output.mkdir(parents=True, exist_ok=False)
    report = {"kind": "D02 actual APK ELF static evidence", "audit_source_sha": audit_source_sha,
              "apk_declared_source_sha": apk_source_sha,
              "apk": {"path": str(Path(apk).absolute()), "bytes": len(raw), "sha256": digest(raw)},
              "tools": tool_meta, "abis": [], "device_runtime": "NOT RUN",
              "apk_source_association": "Caller-declared source SHA; CI supplies its actual checkout/build SHA. "
              "Historical input and rebuilt APK identities must not be conflated.",
              "interpretation": "ZIP, LOAD, Android doc endpoint and expanded writable ranges are independent. "
              "Evidence agreement is not compatibility PASS. Audit reads inputs and does not modify "
              "dependency selection, ELF bytes or KDF configuration."}
    try:
        with tempfile.TemporaryDirectory(prefix="owned-elf-", dir=output) as temp:
            for abi, data, member in members:
                elf = parse_elf(data)
                if (elf.bits, elf.machine) != ABIS[abi] or elf.endian != "little" or elf.file_type != 3:
                    raise ElfError("actual member ABI/class/endian/shared-object identity mismatch: " + abi)
                entry = {**member, "abi": abi, "elf_class": elf.bits, "elf_endian": elf.endian,
                         "e_machine": elf.machine,
                         "program_headers": [asdict(p) for p in elf.program_headers
                                             if p.type in (PT_LOAD, PT_GNU_RELRO)],
                         "llvm_crosscheck": "NOT RUN",
                         "checks": [layout_checks(elf, page) for page in (4096, 16384)]}
                report["abis"].append(entry)  # preserve decoded evidence even if the oracle fails
                file = Path(temp) / (abi + ".so")
                file.write_bytes(data)
                text = command([tool, "-W", "-l", file],
                               log_path=output / (abi + "-llvm-readelf.txt"))
                oracle = readelf_selected_headers(text)
                entry["llvm_selected_headers"] = oracle
                if selected_headers(elf) != oracle:
                    entry["llvm_crosscheck"] = "FAIL"
                    raise ElfError("actual binary parser/independent LLVM program headers differ: " + abi)
                entry["llvm_crosscheck"] = "PASS"
            # Check the exact immutable snapshot, not a possibly changing input path.
            snapshot = Path(temp) / "snapshot.apk"
            snapshot.write_bytes(raw)
            try:
                zipped = subprocess.run([str(zipalign), "-c", "-P", "16", "4", str(snapshot)],
                                        capture_output=True, text=True, timeout=60)
            except subprocess.TimeoutExpired as error:
                (output / "apk-zipalign.txt").write_text(timeout_output(error))
                raise
            (output / "apk-zipalign.txt").write_text(zipped.stdout + zipped.stderr)
            report["zipalign_exit"] = zipped.returncode
            report["zip_alignment"] = "PASS" if zipped.returncode == 0 else "FAIL"
            if zipped.returncode:
                raise ElfError("actual APK zipalign16K check failed")
        report["evidence_validation"] = "PASS"
        checks = [a["checks"][1] for a in report["abis"]]
        report["load_16k_alignment"] = "PASS" if all(c["load_alignment"] == "PASS" for c in checks) else "FAIL"
        report["android_doc_relro_endpoint_16k"] = "PASS" if all(
            c["android_doc_relro_endpoint"] in ("PASS", "NOT_APPLICABLE") for c in checks) else "FAIL"
    except Exception as error:
        report["evidence_validation"] = "FAIL"
        report["failure_type"] = type(error).__name__
        raise
    finally:
        after = license_path.read_bytes()
        report["tools"]["accepted_license_after_sha256"] = digest(after)
        report["tools"]["acceptance_records_unchanged"] = before == after
        (output / "REPORT.json").write_text(json.dumps(report, indent=2) + "\n")
        print("D02_NATIVE_ELF_REPORT=" + json.dumps(report), flush=True)
        if before != after:
            raise ElfError("existing acceptance records changed unexpectedly")
    return report


def enforce_doc_endpoint(report):
    """Strict doc endpoint gate only. Never turn zero writable overlap into PASS."""
    if report.get("kind") != "D02 actual APK ELF static evidence" or report.get("evidence_validation") != "PASS":
        raise ElfError("endpoint gate requires complete actual APK evidence")
    entries = report.get("abis", [])
    if len(entries) != 4 or {a.get("abi") for a in entries} != set(ABIS):
        raise ElfError("endpoint gate requires exactly all four ABI records")
    failures = []
    for entry in entries:
        if entry.get("llvm_crosscheck") != "PASS":
            raise ElfError("endpoint gate requires independent LLVM agreement")
        checks = [c for c in entry.get("checks", []) if c.get("page") == 16384]
        if len(checks) != 1:
            raise ElfError("endpoint gate requires one16K check per ABI")
        if checks[0].get("android_doc_relro_endpoint") not in ("PASS", "NOT_APPLICABLE"):
            failures.append(entry["abi"])
    if failures:
        raise ElfError("DOC_LITERAL_RELRO FAIL: " + ",".join(failures) +
                       "; zero overlaps cannot override;16K runtime NOT RUN")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    run = commands.add_parser("audit")
    run.add_argument("--apk", required=True)
    run.add_argument("--sdk-root", required=True)
    run.add_argument("--output", required=True)
    run.add_argument("--apk-source-sha", required=True)
    gate = commands.add_parser("enforce-doc-endpoint")
    gate.add_argument("--report", required=True)
    args = parser.parse_args()
    if args.action == "audit":
        audit(args.apk, args.sdk_root, args.output, args.apk_source_sha)
    else:
        enforce_doc_endpoint(json.loads(bounded_read(args.report, 2 * 1024 * 1024)))
        print("DOC_LITERAL_RELRO endpoint gate PASS;16K runtime still NOT RUN")


if __name__ == "__main__":
    main()
