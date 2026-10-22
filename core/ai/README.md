# JVM text protocols, source-bound sending and current-consent gate

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
The credential-free `newExchange` sends no credentials. The transport follows
no redirects and performs no replay or provider
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
Cancellation closes the owned socket immediately. If a body read is active,
its owner closes the retained response after the read exits; external cancellation
cannot concurrently drain/close the same source. Read admission and response
ownership changes share the state lock, which is never held across network reads
or event callbacks. Nonfatal cleanup errors cannot change a selected terminal.
Nonfatal consumer throwables become `CONSUMER_ERROR` before a protocol terminal;
a validated terminal remains selected. VM errors, thread death and linkage
errors propagate after owned cleanup.

The test-source fixture reflectively accesses a private client constructor and
uses an application interceptor to rewrite synthetic HTTPS descriptions to a
server bound only to `127.0.0.1`. Release bytecode has no public client-injection
constructor, HTTP mode or TLS setting. Real wire tests inspect both
protocols and verify exactly one request, chunk partition equivalence, timeouts,
EOF, errors, cancellation, callback failures and owned resource closure.

`newSourceBoundExchange` is the public authenticated sending boundary. It accepts
a source/version/purpose, numerical output bounds and a trusted records repository;
it accepts no caller text, messages, system prompt or model. The repository reads
only the current original with explicit remote permission. Fixed versioned prompts
and that original form the request; attachments, other records and historical
revisions are excluded. Dependency injection is trusted application code, not a
security sandbox for malicious replacement repositories.

The former arbitrary-`TextRequest` `newGuardedExchange` is now private. Its
test-source reflection fixture verifies the internal engine without exposing
that authenticated entry in production. Queue metadata binds
the source revision, purpose, complete connection and grant, plus an authority
generation. `CurrentSendAuthority` rejects restrictive policies, deleted/stale
sources, revoked/expired/mismatched consent, configuration drift (even at the
same claimed version) and a switch-away/switch-back. Its private identity ledgers
control ticket consumption and receipt issuance; Kotlin `internal` visibility is
not trusted to prevent JVM metadata forgery.

Execution checks persisted state before resolving a secret, checks again after
resolution, then atomically consumes the persisted job in the final network
interceptor. Database operations finish before resolver/network/event callbacks;
no external callback runs inside a database transaction. The internal engine also
atomically rechecks and registers a one-use permit in the network interceptor.
The trusted nonblocking clock is read under the authority lock at final admission
and result validation. Resolver, network and event callbacks run outside that
lock. Pre-admission denial or resolution failure produces zero HTTP operations,
no fallback and no receipt. The interceptor runs after connect, so this does not
promise zero sockets or TLS handshakes. An internal
`CurrentSendAuthority.replaceState` invalidates its permit before cancelling the
owned call; this is distinct from persisted changes through another connection.
Sent bytes cannot be recalled.

`SecretResolver` only receives the frozen `SecretReference`. `ResolvedSecret`
owns a transferred character array, validates 1–8,192 visible ASCII characters,
redacts its diagnostics and wipes the array before HTTP execution or on exit.
Callers must not mutate/reuse an array after transfer. Only the fixed protocol
authentication header is injected inside the private engine. OkHttp requires
immutable header strings, which cannot be zeroed; owned call/request references
are cleared on cleanup. No secret goes into configuration, body, exception cause,
result or logs. No real secret-store implementation is included.

Wire events are previews. A high-level result invalidation sets `resultDenial`,
omits the receipt and returns `SEND_DENIED`; `wireOutcome` retains the protocol
fact, including a completed stream. Pre-admission failures use `denial` and have
no wire outcome. Only actual wire completion plus a current final authorization
check yields a receipt. A receipt is a version-bound authorization witness, not
permanent permission, HTTP-success proof by itself or a database transaction.
The source-bound layer buffers previews and submits an annotation candidate only
after completed wire output with that receipt. The product records transaction
independently checks the current original, full configuration/grant, policy,
generation, deletion and expiry, validates code-point references, inserts the
annotation and marks the job complete atomically. `SourceBoundSendOutcome.Completed`
therefore requires a persisted annotation. A duplicate of an already committed
binding returns the stored annotation without a new HTTP call; an unfinished
binding is never signed out to a second exchange. Local result idempotency does
not promise that a provider can never charge twice.

The database admission is a linearization point before sending; it is not a
transaction with the remote server. Persisted revocation after admission can
leave already sent bytes and later wire previews. This slice rechecks and rejects
their result in the database; it does not implement cross-connection active-call
notifications. `cancel()` explicitly stops its owned call and cancels unfinished
owned work. Returned outcomes retain wire facts separately from storage denial.
The stored annotation is a rebuildable candidate view with an exact original
reference, not a validated stable fact, confirmed task or proof of semantic
support. Full structured extraction and App presentation remain separate.
The existing credential-free `newExchange` remains the low-level wire API; it
accepts no authentication headers and does not itself enforce record consent.

The production Room/SQLCipher records repository supplies the new transaction
adapter, migration and persistent configuration/grant/job/annotation tables.
Host tests compile the actual product transaction/codec/schema and execute them
through independent Python SQLite connections on synthetic files. This proves
product SQL logic, not Android Room/KSP generation or SQLCipher runtime behavior.
Real Keystore/secret-store integration, persistent worker lease/crash recovery,
App wiring, model probing, multimodal and structured extraction remain separate
D04/D05 gates. `LOCAL_ONLY` is unchanged; D02 device acceptance is NOT RUN and
full D04 remains OPEN. Synthetic loopback success is not a provider connection.

Libraries are pinned kotlinx.serialization JSON/core 1.9.0, OkHttp/MockWebServer
5.5.1 and resolved Okio 3.18.1 (Apache-2.0), from Maven Central with generated
dependency locks. The reviewed HTTP JAR hashes and official source addresses
are in `checks/ai-protocol/http-dependencies.json`; a read-only verifier checks
the actual Gradle-resolved JARs against those payloads.
Kotlin, JDK17, Gradle and JUnit versions reuse the existing project pins.

Run actual modules without configuring Android projects:

```sh
python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --offline --rerun-tasks
python3 scripts/verify-ai-http-dependencies.py
python3 scripts/verify-ai-protocol-results.py
```

The verifier matches every source test method to actual nonempty JUnit XML,
rejects failures/errors/skips and checks the source fingerprint captured by
each test task. It rejects stale XML after source/build/lock changes.
