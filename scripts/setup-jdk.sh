#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
toolchain_root=${AIYOU_TOOLCHAIN_ROOT:-"$project_root/.toolchains"}
if [[ $(uname -s) != Linux || $(uname -m) != x86_64 ]]; then
    echo 'This bootstrap targets Linux x86_64. Install a full JDK 17 and set JAVA_HOME.' >&2
    exit 2
fi
if [[ -x "$toolchain_root/jdk-17.0.17+10/bin/javac" ]]; then
    "$toolchain_root/jdk-17.0.17+10/bin/javac" -version
    exit 0
fi
mkdir -p "$toolchain_root/downloads"
archive="$toolchain_root/downloads/jdk17.tar.gz"
curl --fail --location --retry 1 --max-time 300 \
    'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.17%2B10/OpenJDK17U-jdk_x64_linux_hotspot_17.0.17_10.tar.gz' \
    --output "$archive"
printf '%s  %s\n' \
    992f96e7995075ac7636bb1a8de52b0c61d71ed3137fafc979ab96b4ab78dd75 \
    "$archive" | sha256sum --check -
tar -xzf "$archive" -C "$toolchain_root"
"$toolchain_root/jdk-17.0.17+10/bin/java" -version
"$toolchain_root/jdk-17.0.17+10/bin/javac" -version
