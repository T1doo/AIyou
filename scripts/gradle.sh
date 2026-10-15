#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
toolchain_root=${AIYOU_TOOLCHAIN_ROOT:-"$project_root/.toolchains"}
export JAVA_HOME=${JAVA_HOME:-"$toolchain_root/jdk-17.0.17+10"}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$toolchain_root/gradle-home"}
export ANDROID_USER_HOME=${ANDROID_USER_HOME:-"$toolchain_root/android-user-home"}
mkdir -p "$ANDROID_USER_HOME"

if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
    echo 'A complete JDK 17 is required. Set JAVA_HOME; see docs/BUILD.md.' >&2
    exit 2
fi
jdk_version=$("$JAVA_HOME/bin/javac" -version 2>&1)
if [[ ! "$jdk_version" =~ (^|[[:space:]])javac[[:space:]]17\. ]]; then
    echo 'D00 requires JDK 17 for compilation and execution.' >&2
    exit 2
fi

# Reuse this environment's existing proxy and OS Java trust store. No CA import,
# TLS bypass, credential setup or network-policy change is performed.
proxy_url=${HTTPS_PROXY:-${https_proxy:-}}
if [[ -n "$proxy_url" ]]; then
    proxy_flags=$(python3 - "$proxy_url" <<'PY'
import sys
from urllib.parse import urlparse
p = urlparse(sys.argv[1])
if p.scheme != 'http' or not p.hostname or p.username or p.password:
    raise SystemExit('Unsupported proxy configuration; do not expose credentials.')
if not all(c.isalnum() or c in '.-' for c in p.hostname):
    raise SystemExit('Invalid proxy hostname.')
port = p.port or 80
print(f'-Dhttps.proxyHost={p.hostname} -Dhttps.proxyPort={port} '
      f'-Dhttp.proxyHost={p.hostname} -Dhttp.proxyPort={port}')
PY
    )
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} $proxy_flags"
    if [[ -r /etc/ssl/certs/java/cacerts ]]; then
        export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
    fi
fi

cd "$project_root"
# Only the explicitly selected Android phase on the ephemeral CI runner reuses
# its compatible 2 GB daemon. Local runs and earlier host projects stay isolated.
daemon_flag=--no-daemon
if [[ "${GITHUB_ACTIONS:-}" == true && "${AIYOU_CI_ANDROID_DAEMON:-}" == true ]]; then
    daemon_flag=--daemon
fi
exec ./gradlew "$daemon_flag" --console=plain "$@"
