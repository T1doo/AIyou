#!/usr/bin/env python3
"""Run the verified project wrapper on JDK17 without initializing Android tools."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlparse

root = Path(__file__).resolve().parent.parent
env = os.environ.copy()
toolchain = Path(env.get("AIYOU_TOOLCHAIN_ROOT", str(root / ".toolchains")))
jdk = Path(env.get("JAVA_HOME", str(toolchain / "jdk-17.0.17+10")))
compiler = jdk / "bin/javac"
if not compiler.is_file():
    raise SystemExit("Complete JDK17 is absent; see docs/BUILD.md")
version = subprocess.run([str(compiler), "-version"], capture_output=True, text=True, check=True)
if (version.stdout + version.stderr).strip() != "javac 17.0.17":
    raise SystemExit("This slice requires the pinned javac 17.0.17")
wrapper = root / "gradle/wrapper/gradle-wrapper.jar"
if hashlib.sha256(wrapper.read_bytes()).hexdigest() != "3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98":
    raise SystemExit("Gradle Wrapper checksum mismatch")
env["JAVA_HOME"] = str(jdk)
env.setdefault("GRADLE_USER_HOME", str(toolchain / "gradle-home"))
proxy = env.get("HTTPS_PROXY") or env.get("https_proxy")
flags = []
if proxy:
    parsed = urlparse(proxy)
    if parsed.scheme != "http" or not parsed.hostname or parsed.username or parsed.password:
        raise SystemExit("Unsupported existing proxy configuration")
    if not all(ch.isalnum() or ch in ".-" for ch in parsed.hostname):
        raise SystemExit("Invalid existing proxy hostname")
    try:
        port = parsed.port or 80
    except ValueError:
        raise SystemExit("Invalid existing proxy port") from None
    flags = [f"-Dhttps.proxyHost={parsed.hostname}", f"-Dhttps.proxyPort={port}",
             f"-Dhttp.proxyHost={parsed.hostname}", f"-Dhttp.proxyPort={port}"]
    trust = Path("/etc/ssl/certs/java/cacerts")
    if trust.is_file():
        flags.append(f"-Djavax.net.ssl.trustStore={trust}")
env["JAVA_OPTS"] = " ".join([env.get("JAVA_OPTS", ""), *flags])
os.chdir(root)
os.execvpe(str(root / "gradlew"), [str(root / "gradlew"), "--no-daemon", "--console=plain", *sys.argv[1:]], env)
