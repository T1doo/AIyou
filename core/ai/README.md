# JVM text protocols and bounded HTTP transport

`ProtocolRequests` builds a validated HTTPS operation URI, protocol headers and
JSON request description. It does not open a connection or accept a secret.
Chat Completions places system text in the message list; Anthropic Messages uses
top-level `system` and its version header. A caller can explicitly select the
Chat token budget field (`max_tokens` or `max_completion_tokens`); Anthropic
always uses `max_tokens`. Chat `stream_options.include_usage` is sent only when
the caller explicitly opts into `chatUsageSupported`; default requests and
Anthropic requests omit it. This is not model capability detection.

`ProtocolStream` accepts response byte chunks and emits preview text, cumulative
usage and exactly one terminal event. Only Chat `stop` followed by dispatched
`[DONE]`, or a properly closed Anthropic sequence with `end_turn`/`stop_sequence`
and `message_stop`, can complete. EOF, malformed UTF-8/JSON, resource limits,
refusal, truncated output, unsupported tools/audio and cancellation cannot.
The first fully dispatched and validated protocol terminal ends the response
prefix. Its delimiter bytes are validated and counted; bytes/events after it
are never decoded or counted, even when they occur in the same caller read.
Frames are visited in wire order, so an error before the terminal preserves
already dispatched preview events and then fails. Calls are serialized;
terminal streams ignore subsequent input. Preview text
does not constitute accepted memory or a completed task.

`SseDecoder` implements incremental strict UTF-8, a single leading BOM,
CR/LF/CRLF, comments, multiline data and blank-line dispatch. Undispatched EOF
data is discarded. Ordinary empty frames, ping and unknown event names are
ignored by the protocol adapter; known empty control/error events fail.

Default bounds: 16,384 UTF-16 units per line, 65,536 per event,
4,096 dispatched frames, 8 MiB total response bytes, 1,048,576 preview UTF-16
units, JSON nesting depth 64. Request text/model IDs also reject invalid UTF-16.
Protocol reads are normalized into decoder windows of at most 256 KiB, without
copying the input or rejecting a read merely because it is larger. The standalone
raw `SseDecoder.push` retains its 256 KiB batch cap. UTF-8 carry is bounded to
four bytes and decoded output to two UTF-16 units; protocol terminal detection
never depends on decoding the rest of an input window.

`FailurePolicy` returns a recommendation, with at most three total attempts and
60 seconds delay by default. It never sends or replays requests. HTTP 401/403,
redirects, invalid/excessive Retry-After, partial output and exhausted attempts
stop retry recommendations. A provider wait is never shortened to fit a cap.
Failure diagnostics and request/event `toString()` exclude payload contents.

`OkHttpStreamingTransport` runs one synchronous owned exchange through OkHttp.
Its production constructor accepts finite bounds only and validates HTTPS,
platform TLS, operation paths and exact nonsecret protocol headers again.
It sends no credentials, follows no redirects and performs no replay or provider
switch. A network interceptor rejects every non-2xx response before OkHttp's
503/421 follow-up logic; `FailurePolicy` remains recommendation-only.

Defaults are 5 s connect, 30 s total, 10 s idle/read and 10 s write deadlines,
4 KiB error-body disclosure/read accounting, 8 KiB protocol reads and 1 MiB
request bytes. Error content is discarded after at most cap+1 consumed bytes
(the extra byte detects truncation); Okio/socket buffers can read ahead. Protocol
limits count only consumed bytes through the first valid terminal. Each frame
is delivered before the next one is decoded, so callback cancellation/failure
does not depend on network read boundaries. Terminal selection immediately
cancels the owned call and closes its body; cleanup cannot revoke completion.
Callbacks execute on the calling thread and should return promptly; HTTP
deadlines bound network operations, not arbitrary caller callback execution.
Nonfatal consumer throwables become `CONSUMER_ERROR` before a protocol terminal;
a validated terminal remains selected. VM errors, thread death and linkage
errors propagate after owned cleanup.

The test-source fixture reflectively accesses a private client constructor and
uses an application interceptor to rewrite synthetic HTTPS descriptions to a
server bound only to `127.0.0.1`. Release bytecode has no public client-injection
constructor, HTTP mode or TLS setting. Real wire tests inspect both
protocols and verify exactly one request, chunk partition equivalence, timeouts,
EOF, errors, cancellation, callback failures and owned resource closure.

API key management, current-consent enforcement, App wiring, persistence,
model probing, multimodal and structured extraction integration remain separate
D04/D05 gates. `LOCAL_ONLY` is unchanged; D02 device acceptance is NOT RUN and
full D04 remains OPEN. A synthetic server pass is not a provider connection.

Libraries are pinned kotlinx.serialization JSON/core 1.9.0, OkHttp/MockWebServer
5.5.0 and resolved Okio 3.18.1 (Apache-2.0), from Maven Central with generated
dependency locks. The reviewed HTTP JAR hashes and official source addresses
are in `checks/ai-protocol/http-dependencies.json`; a read-only verifier checks
the actual Gradle-resolved JARs against those payloads.
Kotlin, JDK17, Gradle and JUnit versions reuse the existing project pins.

Run actual modules without configuring Android projects:

```sh
python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test --offline --rerun-tasks
python3 scripts/verify-ai-http-dependencies.py
python3 scripts/verify-ai-protocol-results.py
```

The verifier matches every source test method to actual nonempty JUnit XML,
rejects failures/errors/skips and checks the source fingerprint captured by
each test task. It rejects stale XML after source/build/lock changes.
