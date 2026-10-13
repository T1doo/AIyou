# D04 current-consent sending gate

This independent slice builds on bounded HTTP commit
`d8cc0ff377e6154991a90943f9b402dd01646636` on
`codex/d04-jvm-protocol-sse-20261013`. It adds pure configuration/consent versions
and a guarded text send, with fake references, synthetic secrets and two owned
loopback servers. No real secret store, user key, provider connection, App or
native-device integration is part of this acceptance boundary.

## Current authorization and queue binding

`core:model` contains immutable connection, source and consent metadata. Protocol
and budget enums are independent of `core:ai`. A grant binds the entire approved
connection, source revision and purpose, including an issued/expiry interval.
It contains a `secretRef`, never resolved authentication. Diagnostic strings
redact source/provider/model/root/reference/grant identifiers.

`CurrentSendAuthority` owns one coherent in-memory state and a monotonic queue
generation. Queue tickets freeze source/purpose, complete configuration and
grant. Restrictive `LOCAL_ONLY`/`AI_NONE`, deleted or stale source, missing,
revoked, expired/future or mismatched consent, provider/version changes and full
configuration drift all reject. Changing root, protocol, model, secretRef or
capability while retaining a claimed configuration version also rejects. An ABA
switch away and back cannot revive an older ticket.

One state lock controls issuance/consumption, final admission and result checks.
Private weak identity ledgers validate issued tickets and receipts; publicly
accessible JVM metadata constructors cannot forge their identity. Consumption
is authority-owned and cannot be reset through a Java setter. Weak keys avoid
permanently retaining abandoned metadata. Concurrent claims consume a ticket
once. Foreign, forged or copied metadata is rejected; a finished permit cannot
finish again. Issued receipts remain subject to current-state checks.

The trusted nonblocking clock is read under the authority lock for final claim
and finish. Resolver, network, event and invalidation callbacks are outside the
lock. Replacement first publishes invalidation and advances the generation,
then invokes owned cancellation callbacks outside the lock.

## Guarded execution and secret ownership

`OkHttpStreamingTransport.newGuardedExchange` captures a bounded immutable text
request when queued. It checks the ticket before resolving the exact approved
reference, checks again after resolution, constructs the frozen protocol request,
and performs a final atomic check/one-use registration in the private network
interceptor immediately before HTTP headers/body are sent. The first two checks
cannot substitute for the final one. There is no fallback or automatic resend.
Caller model/budget/usage flags cannot override the frozen approved configuration.

Any pre-admission policy/configuration/consent denial, invalid/missing secret or
nonfatal resolver/clock exception yields no HTTP operation on either loopback
server. A cancellation during resolution also sends nothing and wipes any value
returned afterward. `CancellationException` selects cancellation; other nonfatal
errors expose finite categories without raw causes. VM errors, thread death and
linkage errors propagate after owned cleanup. The final interceptor runs after
connect; zero HTTP operations does **not** mean zero sockets or TLS handshakes.

`ResolvedSecret.fromChars` transfers the input array; its caller must not mutate
or reuse that array. Values must be 1–8,192 visible ASCII characters, with no
whitespace/control/non-ASCII characters. The array is wiped before the owned
HTTP operation or on every exit. Only the fixed Bearer or x-api-key header is
added inside the private engine. The existing low-level credential-free entry
continues rejecting authentication/arbitrary headers and does not itself enforce
record consent. No public raw-secret/header transport overload is added.
OkHttp uses immutable header strings, which cannot be zeroed; owned call/request
references are cleared during cleanup. No secret enters a body, configuration,
diagnostic, result or retained exception cause. No real Keystore resolver exists.

## Admission, cancellation and result meaning

The atomic permit registration is the send admission point. Revocation before it
must yield zero HTTP operations. After it, state replacement invalidates the
permit and explicitly selects exchange cancellation before closing the socket.
This stops later frames in the same read and fragmented reads consistently.
If the protocol terminal was already selected, its wire fact remains unchanged.
Cancellation cannot promise to recall bytes already sent.

An actual independent rerun exposed a response-body cancellation race in the
existing HTTP contract: external `Response.close()` could drain/read while the
owner was already reading, causing Okio's timeout state to assert. The complete
189-method failed epoch is preserved at
`.local-evidence/d04/send-gate-final-independent/` with source fingerprint
`7d9ee77e4fda6d3da7608f1da6e17d78be33d8dac168f4ea83a89b67e55140a9`.
The correction atomically admits a read under the state lock, performs it outside
the lock and defers body close to its owner while that read is active. Cancellation
still closes the socket immediately. Ordinary cleanup throwables cannot replace
the chosen terminal; fatal errors retain the documented propagation behavior.
Deterministic real-HTTP read/close-ownership and cleanup-AssertionError regressions
are included in the subsequent source epoch. The failed run is not relabelled or
replaced by the earlier passing 189-method development run.

Guarded outcomes separate pre-admission `denial` from post-admission
`resultDenial`. Pre-admission denial has no `wireOutcome` or receipt. A later
state change returns guarded `SEND_DENIED`, retains the low-level `wireOutcome`
fact and omits the receipt. Wire callbacks are previews, including a completed
stream; they are not proof of a currently submittable result. Only actual wire
completion and a valid final authorization check yield a receipt.

A receipt is a version-bound authorization witness. `checkReceipt` can reject
later changes, but cannot independently prove an HTTP success, filter the source
of arbitrary supplied text or perform a future database transaction. App/data
integration must supply filtered source content and atomically recheck current
source/configuration/consent with result persistence. This slice writes no
record, derived memory or task.

## Verification boundary

New tests cover both protocol/provider targets, restrictive/missing/stale grants,
source deletion/revision/purpose, queued switches/version changes, same-version
drift and ABA, changes/expiry during secret resolution, resolver failures and
invalid values, final preflight changes/clock failures, blocked-resolver cancel,
authentication separation, failed providers without receipts/fallback, frozen
payloads, secret wiping, diagnostics, synchronous and external revocation with
coalesced/one-byte HTTP chunks, stale wire completion and current receipt checks.
Pure-domain tests cover time boundaries, issued identity/JVM constructor forgery,
one-use concurrent claims and atomic invalidation/result checks.

The initial development compile exposed the new direct model dependency missing
from the AI compile classpath. `core:ai` now declares `implementation(core:model)`
explicitly rather than depending on domain's implementation transitivity; its
failure log is preserved. No library/tool version or dependency lock is upgraded.
The corrected frozen source passed both full actual reruns through the tracked
checksum-verified JVM wrapper. The independent final command was:

```text
python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test --offline --rerun-tasks
```

It completed successfully in 2m43s, with all 10 actionable tasks executed.
The 10 fresh XML suites contain **191 unique methods: AI 178 and domain 13**,
with zero failures, errors or skips. These include 35 guarded HTTP methods,
two body-ownership regressions and 10 authority methods; they are subsets of
191. Zero-request controls count once as methods within that total;
protocol/provider matrix iterations are not added as separate tests.

Before/after input SHA-256 and both test captures match
`66fafc20be1c485c32ff47a2b141c316ccb083717ce4a2b7b825037aa7dcef61`.
Execution occurred against this uncommitted source epoch over full baseline HEAD
`d8cc0ff377e6154991a90943f9b402dd01646636`; delivery is bound to the tested inputs
by that fingerprint, rather than relabelling the test-time HEAD. The standard
protocol inventory and dependency verifiers passed, as did independent checks
of XML freshness/completeness, the three resolved JAR hashes, actual JVM
constructor-forgery tests, the bytecode API and 22 synthetic private markers in
stdout/all XML. The injected-client constructor remains private; metadata has no
public reset setter. Final execution, XML and verification evidence are retained
under `.local-evidence/d04/send-gate-owner-final-independent/`. The earlier
189-method failure evidence remains separate and unchanged.

The parent supplied the verified final outcome of existing normal
[CI38391964281](https://github.com/T1doo/AIyou/actions/runs/38391964281),
job115277681750, for the d8cc0ff baseline: completed/success at 11:57:53 UTC,
including Android lint, JVM contracts, APK, Preview and artifact preservation.
That green result covers d8cc0ff, not this gate slice or device execution. No
manual CI rerun is requested; rejected read paths remain stopped. Local Android
lint/assemble/device acceptance are NOT RUN.

`LOCAL_ONLY` remains the product default. Persistent configuration/grant/queue
storage, real Keystore/user secrets, App consent/settings, live provider/billing,
multimodal/structured extraction and atomic record-result submission are separate
gates. D02 production restart/native acceptance is **NOT RUN** and full D04 is
**OPEN**. `main` remains unchanged. No ADB/device is started, credential installed,
force push, deployment or paid service used.
