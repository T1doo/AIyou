# D04 source-bound text and atomic annotation storage

This slice builds on accepted commit
`36a2b911542249af2b0a6d80237c9b442ecd57e4` on
`codex/d04-jvm-protocol-sse-20261013`. It follows the preserved master plan
sections 7, 8.2, 9.2, 10.6, 11.5 and 14.1: the records database owns the original,
permission filtering precedes prompt construction, and a late response cannot
replace a newer source or bypass withdrawal.

## Product integration

The actual `core:data` repository opens the existing Room/SQLCipher records
database. Its new adapter calls the shared product Kotlin transaction logic
through the same database transaction, not a second in-memory authorization
store. A v3-to-v4 migration adds versioned source access, one-use text jobs and
separate annotation candidates. It preserves existing original revisions,
drafts, local jobs and attachment rows. Configurations/grants contain references,
never resolved provider credentials.

Existing record reads now recognize the three valid processing policies rather
than treating any non-`LOCAL_ONLY` policy as corruption. New saves still set
`LOCAL_ONLY`; migration grants no remote access to historical records. Tombstones
exclude a deleted source from normal record reads and new processing. Permanent
erasure, recycle-bin UI and attachment cleanup are separate work.

Policy/source updates invalidate unfinished jobs and clear annotations in the
same records transaction. Explicit access updates use an expected-generation
check and advance that generation. The immutable originals remain separate from
AI candidate annotations; this slice creates no stable fact, task or reminder.
`readAccess` returns a coherent source/policy/tombstone/configuration/grant and
generation snapshot for an explicit CAS change; callers do not guess generation
zero. The snapshot contains no original or resolved secret. The connection here
is the approved connection for this source. Global connection-settings UI and
coordinated changes across multiple sources remain part of future App wiring.

## Input binding and sending

The public authenticated API is `newSourceBoundExchange`. Callers specify one
source revision and purpose, numerical output bounds and the trusted repository;
there is no caller text/messages/system/model parameter. The repository reads
only the requested current original after its policy, deletion, complete
configuration/grant and expiry checks. Other entries, old revisions and
attachments are excluded. The engine combines that text with a fixed versioned
prompt for the selected purpose. The former arbitrary-text authenticated engine
is private; old engine contracts use test-source reflection only.

Job metadata is untrusted at repository boundaries. All supplied binding fields,
original text/hash and capture metadata must match the stored job and immutable
source. A canonical binding includes source/version, purpose, prompt version,
complete connection/grant and generation. A second queue for an unfinished
binding is rejected rather than receiving the first execution's job, so its
cleanup cannot cancel the winner. A currently valid committed binding returns
the existing annotation without another HTTP request. Cancelled work is not
silently retried. Durable leases/crash recovery and explicit user retry remain
open; this is not a finished background worker.

Persisted checks run before secret resolution and after it. The private final
network interceptor atomically consumes the current persisted job before
sending. Resolver, networking and event callbacks run after their database
transaction has ended. Secret handling keeps the previous transferred-array
wiping, fixed authentication headers, redacted diagnostics and no-fallback
behavior. Tests use only fake references and synthetic secrets.

Database admission and the remote HTTP operation cannot be one distributed
transaction. Revocation that wins before admission prevents an HTTP request;
revocation after admission can leave already sent bytes and later previews.
This slice does not implement cross-connection active-call notifications. Explicit
exchange cancellation stops its owned call. The final database transaction
prevents a revoked or stale result from becoming a saved annotation.

## Atomic result boundary

Stream events, including wire completion, are previews. After actual completed
wire output and the internal current-consent receipt, the source-bound layer
submits an annotation candidate with the exact original reference. The product
transaction independently rechecks the current source/version, original content,
policy, tombstone, full connection/grant, generation, one-use job and fresh
trusted clock. It validates bounded text and Unicode code-point references,
checks idempotency, inserts the annotation and completes the job in that same
write transaction. A failed insert or job update rolls both back.

References use half-open code-point offsets; source ID/revision, bounds and
quoted text must match the immutable original exactly. Malformed UTF-16 is
rejected. The fixed sender attaches the whole approved original; this establishes
provenance, not semantic support for every generated sentence. Structured
extraction, negation/fact validation and task confirmation remain D05 work.

Duplicate identical candidates return the stored result; a different candidate
cannot overwrite it. Authorization checks precede duplicate recovery. Source
deletion/change, withdrawal, configuration changes, expiry and ABA cannot turn a
late result into a new accepted row. A successful commit that precedes withdrawal
is subsequently invalidated by withdrawal in its own transaction. Results are
not written into original revisions or confirmed objects.

`SourceBoundSendOutcome.Completed` requires a persisted annotation, including
explicit duplicate recovery. It preserves the guarded wire outcome separately
from storage denial. Application presentation must use that final returned
annotation rather than a completed wire callback. App integration remains open.
The repository interface is trusted application dependency injection; it does
not sandbox a malicious host replacing the repository or executing arbitrary SQL.

## Verification and remaining gates

The headless check directly compiles the product transaction/codec/schema.
Its test-only Python bridge executes the actual parameterized SQL against real
synthetic file databases through independent owned processes/connections. It
contains no authorization, source-selection or result-validation rules. Each
connection enables foreign keys and WAL; real write transactions cover commit,
rollback, reopen and ordered revoke/submit races. No SQLite JDBC, SDK, dependency
upgrade, Keystore, real provider or real key is introduced.

The frozen actual full command was:

```text
python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --offline --rerun-tasks
```

It passed in 5m24s with all 16 actionable tasks executed. The 12 fresh XML suites
contain **233 unique JUnit methods: AI 178, domain 13 and remote text 42**,
with zero failures, errors or skips. The new 42 comprise 28 product SQL/file
SQLite methods and 14 real loopback HTTP methods. Zero-request controls and
provider/race matrix iterations count only as their unique methods; they are
not added again to these totals.

Before/after source SHA-256 and all three test-task captures match
`e15646bb10f05b24e9dc2b46fe958153d328d2d40eead9c87c7afe15c29a93b8`.
The test-time full HEAD was
`36a2b911542249af2b0a6d80237c9b442ecd57e4`, with this slice still uncommitted.
The delivery commit is mapped to those tested inputs by fingerprint and file
hashes rather than relabelling the test-time HEAD. Both standard protocol/XML
inventory and dependency verifiers passed. Original execution, captures,
12 XML files and reports are retained in
`.local-evidence/d04/source-bound-first-full/`; independent evidence verification
and the delivery record are retained alongside it. The compiler's test-only
stderr-drain unused-expression warning and pinned Gradle deprecation notice are
retained in the actual log; they are not claimed absent or hidden by an upgrade.

The existing 90 repository Python regressions passed. The earlier pure-main
compile passed eight actual tasks in 2m54s, before the final cancellation/access
snapshot changes; the complete 233-method run compiles the final production
Kotlin. The old 189-method HTTP cancellation failure and earlier 191-method
acceptance remain separate, unchanged historical evidence. Expected injected
SQL/migration failures are assertions within passing methods, not failed builds.
An independent evidence helper's first attempt stopped because it treated the
verifier's nested status objects as exit-code integers. That auxiliary format
error is preserved in `source-bound-independent-verification.json`; corrected
read-only verification uses a separate `source-bound-independent-verification-v2.json`.
It does not turn the successful 233-method execution into a failed build or a
second execution.

These local checks can establish production Kotlin and host SQLite semantics.
They do not establish Room/KSP generated-schema validation, SQLCipher native
encryption/migration behavior or Android process/device behavior. Android build,
lint and native acceptance are reported separately. D02 device and App end-to-end
remain **OPEN / NOT RUN**, and full D04 remains **OPEN**.

The parent reported baseline normal CI run38402552049/job115313607705 D04/domain
success at 13:09:23 UTC; the overall run was not terminal at that receipt. That
result covers the accepted baseline only. No manual rerun/dispatch is requested,
and rejected network/SDK/directory actions remain stopped. `main` is unchanged;
delivery uses a normal development-branch commit and push.
