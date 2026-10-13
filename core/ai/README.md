# JVM text protocol adapters

`ProtocolRequests` builds a validated HTTPS operation URI, protocol headers and
JSON request description. It does not open a connection or accept a secret.
Chat Completions places system text in the message list; Anthropic Messages uses
top-level `system` and its version header. A caller can explicitly select the
Chat token budget field (`max_tokens` or `max_completion_tokens`); Anthropic
always uses `max_tokens`. This is not model capability detection.

`ProtocolStream` accepts response byte chunks and emits preview text, cumulative
usage and exactly one terminal event. Only Chat `stop` followed by dispatched
`[DONE]`, or a properly closed Anthropic sequence with `end_turn`/`stop_sequence`
and `message_stop`, can complete. EOF, malformed UTF-8/JSON, resource limits,
refusal, truncated output, unsupported tools/audio and cancellation cannot.
Calls are serialized; terminal streams ignore subsequent input. Preview text
does not constitute accepted memory or a completed task.

`SseDecoder` implements incremental strict UTF-8, a single leading BOM,
CR/LF/CRLF, comments, multiline data and blank-line dispatch. Undispatched EOF
data is discarded. Ordinary empty frames, ping and unknown event names are
ignored by the protocol adapter; known empty control/error events fail.

Default bounds: 256 KiB per push, 16,384 UTF-16 units per line, 65,536 per event,
4,096 dispatched frames, 8 MiB total response bytes, 1,048,576 preview UTF-16
units, JSON nesting depth 64. Request text/model IDs also reject invalid UTF-16.

`FailurePolicy` returns a recommendation, with at most three total attempts and
60 seconds delay by default. It never sends or replays requests. HTTP 401/403,
redirects, invalid/excessive Retry-After, partial output and exhausted attempts
stop retry recommendations. A provider wait is never shortened to fit a cap.
Failure diagnostics and request/event `toString()` exclude payload contents.

This module has no HTTP transport, API key management, App wiring, persistence,
model probing, multimodal or structured extraction integration. Any future
caller must filter current consent before constructing requests and must not
follow authenticated redirects. These remain separate D04/D05 acceptance gates.

The only new library is pinned kotlinx.serialization JSON/core 1.9.0
(Apache-2.0), resolved from Maven Central and recorded in the generated lockfile.
Kotlin, JDK17, Gradle and JUnit versions reuse the existing project pins.

Run actual modules without configuring Android projects:

```sh
python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test --offline --rerun-tasks
python3 scripts/verify-ai-protocol-results.py
```

The verifier matches every source test method to actual nonempty JUnit XML,
rejects failures/errors/skips and checks the source fingerprint captured by
each test task. It rejects stale XML after source/build/lock changes.
