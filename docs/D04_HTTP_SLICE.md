# D04 bounded HTTP slice

This independent slice adds the real HTTP implementation and loopback contracts
on top of partition correction
`1579445108de02fb1e2eb8e1f762db6050b43b49`, which descends from saved source
`74c78326e51f6497306f032730b413c7c94a4b01`. The development branch remains
`codex/d04-jvm-protocol-sse-20261013`; main is not changed.

## Delivered behavior

- `StreamingHttpTransport`/`StreamingHttpExchange` define one owned synchronous
  execution. `OkHttpStreamingTransport` revalidates public prepared requests,
  preserves proxy/version paths and allows only exact nonsecret protocol headers.
  Production construction accepts finite bounds and uses HTTPS/platform TLS.
- Default connect/total/idle/write deadlines are 5/30/10/10 seconds, validated
  as positive and at most 300 seconds. Error content is discarded after at most
  4,097 consumed bytes by default; 4,096 bytes are reported as the cap and one
  extra byte detects truncation. Socket/Okio buffering may read ahead.
- No redirect, authentication resend, provider switch or implicit retry occurs.
  Every non-2xx response is intercepted before OkHttp's 503/421 follow-up logic.
  Retry decisions are bounded recommendations, never a second request.
- Frame-level delivery prevents callback cancellation/failure from depending on
  read partitions. The first valid terminal closes the owned call/body before
  terminal notification. Cleanup, late cancellation and a failing completion
  callback cannot revoke completion. Nonfatal callback throwables select a
  sanitized `CONSUMER_ERROR` before completion; VM errors, thread death and
  linkage errors propagate after owned cleanup. EOF before completion fails. Callbacks run
  on the execute caller's thread; network deadlines cannot bound arbitrary user
  callback work.
- Chat requests include `stream_options.include_usage` only under the explicit
  `chatUsageSupported` capability opt-in. Defaults and Anthropic requests omit it.
- Both protocols use actual OkHttp and MockWebServer on ephemeral `127.0.0.1`
  ports. A fixture entirely in test sources reflectively accesses a private
  client constructor and rewrites synthetic HTTPS descriptions before connect.
  Actual release bytecode has no public client-injection constructor, release
  HTTP mode or TLS setting. No certificates, keys or external provider are used.

## Dependency and lint decisions

OkHttp/MockWebServer are fixed at 5.5.0; resolved Okio is fixed at 3.18.1 in the
generated lockfile. Their official Maven Central SHA-1 payload checks were
verified before pinning SHA-256 values. The actual Gradle JARs match the reviewed
payloads. `checks/ai-protocol/http-dependencies.json` records URLs, sizes and
hashes; `scripts/verify-ai-http-dependencies.py` verifies resolved JARs read-only.
Official release source:
[OkHttp 5.5.0 changelog](https://github.com/square/okhttp/blob/parent-5.5.0/CHANGELOG.md).
JDK17.0.17+10, Gradle9.8.1, Kotlin2.4.21 and JUnit4.13.2 reuse frozen tools.

The parent task supplied the final outcome of existing CI
[38387843560](https://github.com/T1doo/AIyou/actions/runs/38387843560) for the
1579445 baseline: completed/failure at 2026-10-13 11:23:47 UTC; job115263919660
reported one lint error at 11:17:18, serialization1.9.0 `NewerVersionAvailable`
(suggesting1.11.0), plus one hint. Contract tests were not the failure.
The rejected shell read of that run (proxy CONNECT403 Forbidden) was stopped;
the parent receipt is the evidence used, without retry or manual CI rerun.

The catalog suppresses only that single version's `NewerVersionAvailable`
advisory. Runtime1.9.0 and dependency locks remain fixed; other lint checks remain
enabled. The official
[serialization1.9.0 changelog](https://raw.githubusercontent.com/Kotlin/kotlinx.serialization/v1.9.0/CHANGELOG.md)
states it was built with Kotlin2.2.0. This module uses Json-tree APIs without a
serialization compiler plugin. Actual contracts compile and run with the pinned
Kotlin2.4.21/JDK17 toolchain; this is evidence for the current usage, not an
official guarantee for every Kotlin/Android combination. A runtime upgrade needs
its own compatibility, lock and contract review. Local Android lint remains
NOT RUN because the selected environment has no Android SDK; the normal branch
push will exercise the existing CI checks.

## Verification and remaining gates

Final independent verification ran:

```sh
python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test --offline --rerun-tasks
python3 scripts/verify-ai-http-dependencies.py
python3 scripts/verify-ai-protocol-results.py
```

All commands passed. The full rerun was BUILD SUCCESSFUL in 1m45s, with 10
tasks actually executed. Seven nonempty XML suites contain **141 AI + 3 domain
= 144 unique methods**, with zero failures/errors/skips. HTTP accounts for
29 methods and 6.102 seconds of actual suite execution. Source method inventory,
execution captures, XML freshness/duplicates, resolved JAR checksums and private
constructor bytecode were independently checked.

Before/after execution fingerprints are identical:
`3456a35c50dc6e4d39065cec09e6c5b9b3231fa1044bfc8ec7df11c3c8e95e6c`.
The test-time Git HEAD is the 1579445 baseline above with this slice in its work
tree; the delivery commit SHA is reported in the handoff together with the
unchanged test-input fingerprint. Original logs, seven XML copies, captures,
dependency output and independent verification are retained under ignored
`.local-evidence/d04/http-consumer-final-independent/`. Earlier 142-method epochs
and the development private-constructor compile failure are also retained and
are not summed into the final count or substituted for final-source evidence.

The 29 HTTP methods cover actual method/path/body/nonsecret headers, exactly one
request, coalesced and one-byte HTTP chunks, UTF-8, valid terminal followed by
malformed/oversized suffix, strict prefix quotas, held-tail completion/resource
release, before-terminal errors/EOF/truncation, cancel/close, idle/total deadlines,
redirect/auth/408/421/429/503 handling, bounded Retry-After and error content,
MIME rejection, callback cancel/failure/AssertionError partition equivalence and production
HTTPS/header/request-size rejection. The verifier matches source method inventory
to real nonempty JUnit XML and rejects duplicates, stale inputs and skipped tests.

App/provider-settings/Keystore/consent wiring, live provider connections,
multimodal/structured extraction and native acceptance are outside this slice.
`LOCAL_ONLY` is unchanged. D02 production restart/device acceptance is **NOT RUN**;
full D04 remains **OPEN**. No APK is built locally, no ADB/device is started,
no credential is created and no deployment or paid service is used.
