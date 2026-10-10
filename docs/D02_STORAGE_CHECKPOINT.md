# D02 encrypted text checkpoint

This is the first storage slice. Independent source review and Android
compilation have run; the bounded native attempt ended without a complete suite.
It is not a completed D02
acceptance or device verdict.
The original master plan and architecture document remain unchanged.

The app now wires a real Room repository with SQLCipher 4.19.0, persisted text
drafts and complete immutable original revisions. Save inserts the record,
original revision and durable pending LOCAL_INDEX job and consumes the expected
draft in one transaction. A job ledger is not an implemented background worker.
The UI reports success only after commit and database readback; original text,
AI-derived content and task proposals remain distinct. No model is called.

The random database key is wrapped with device-bound Android Keystore AES-GCM,
stored with AtomicFile under noBackupFilesDir, and never printed. Missing or
invalid wrapping material for existing data fails closed without replacing the
key or database. No plaintext or destructive migration fallback is provided.
Existing backup exclusions and absence of INTERNET permission are retained.
Full bounded license materials are bundled in the offline Settings viewer;
the upstream JNIString ASF NOTICE ancestry is still unverified.

## Verification at this checkpoint

- PASS: actual local `:core:domain:test` (3 tests, no failures/errors/skips),
  explicit pinned dependency graph resolution and genuine generated locks.
- The first independent source review reproduced a prior Edit failure releasing
  an already queued Save's busy state. Save now carries an immutable intent,
  rejects stale command identities, preserves that intent and draft ID on retry,
  and retains the busy state across earlier Edit failures. Three real host JVM
  regressions compile the actual ViewModel against bounded lifecycle/repository
  stand-ins and test that sequence, later input, genuine save failure and failed
  readback after commit. They passed with no failures/errors/skips; they do not
  certify Android lifecycle or native persistence. The unused domain coroutine
  dependency was removed to retain the pure model-only production boundary.
- PASS at `c4c124adeee4fb2cc42fcc0992d4587d29bd5765`: Android metadata checks,
  KSP/Room generation, app debug APK and native-storage instrumentation APK
  compilation in [CI 38041140287](https://github.com/T1doo/AIyou/actions/runs/38041140287).
  The runner app APK SHA256 is `5ffa7e8fe52334f3f3687acb61c7839587f1ce4a4caf277f65edb19ba483d0fd`.
  Schema 2 is the exact 7,873-byte KSP output, SHA256
  `c00d55e4ca1aa3c730f9cebc6f40045435195cecb1880ec17d9aac4017c49e67`.
  All four exported module locks match the checked-in bytes.
- FAIL in that CI: lint emitted five available-new-version warnings for the
  four intentionally fixed D02 dependency entries. No API/correctness finding
  was emitted. The catalog now suppresses only `GradleDependency` for SQLite
  2.7.0 / Lifecycle 2.9.4 and `NewerVersionAvailable` for SQLCipher 4.19.0 /
  coroutines 1.9.0, directly at those four entries. Reproduction at
  `09ec79f3ebcbc3a35ce4d829b647630b91dfa06e` in
  [CI 38042082070](https://github.com/T1doo/AIyou/actions/runs/38042082070) passed:
  all three modules report `No issues found`, with actual builds/tests/rendering
  also passing. Its app APK SHA256 is
  `299becb05700ca74ea0a8ccb8e0ae2c82a40b9ea8dc313a8e043d3f33740fae5`.
  SQLCipher is the independently inspected binary; an upgrade requires new
  native/license evidence. The resolved runtime graph stays frozen for review.
  `abortOnError` and `warningsAsErrors` remain enabled for all source checks;
  no blanket disable, generated baseline or warning-policy downgrade is used.
  This is a deliberate pin, not a claim that newer releases are unsafe or that
  these pins have passed a security audit. The official lint
  [GradleDependency documentation](https://googlesamples.github.io/android-custom-lint-rules/checks/GradleDependency.md.html)
  permits deliberately retaining older versions; its TOML test example
  demonstrates entry suppression. CI uses the already installed licensed SDK;
  no package installer is added.
- The actual bounded attempt at `b42597ad38014fc93ccdf0388e43fe1b9a043e6f`
  in [CI 38043348456](https://github.com/T1doo/AIyou/actions/runs/38043348456)
  booted an owned API35/x86_64/4 KB software emulator and installed the real app
  and test APKs. The entire 13-case instrumentation command timed out after
  240 seconds. Independent reading of its
  [preserved evidence](https://github.com/T1doo/AIyou/actions/runs/38043348456/artifacts/11666961473)
  found three passing case terminal statuses and the fourth
  `fiveAcknowledgedSavesSurviveSeparateDatabaseReopens` starting before the
  shared command deadline; the remaining nine did not start. The fourth did
  not run alone for 240 seconds. This is a partial native result, not 13/13
  acceptance or evidence that all Keystore/migration cases passed.
  Software TCG warnings and system-service ANRs make environment starvation a
  supported candidate, but the fourth case's exact wait still needs diagnosis;
  neither a source deadlock nor a successful suite is inferred. Original failure
  evidence is retained. No larger timeout, KDF change or blind rerun is used.
- 13 actual encrypted integration test methods are written; writing or compiling
  them does not mean execution. The separate process-restart probe requires
  external write/read phases and a different process PID; reopening a database
  in one process is not claimed as a process restart.
- NOT RUN: separate cross-process write/read phases, production device UI,
  IME, TalkBack, physical performance and 16 KB SQLCipher runtime. Device
  PAGE_SIZE=4096 alone is not proof that SQLCipher loaded or every case passed.

Room schema version 1 is a synthetic initial D02 fixture, never a shipped D01
database; version 2 adds the transactional local job ledger. Migration tests
exercise encrypted synthetic v1 input and failed migration preservation.
Generated schema and lock bytes are exported from CI verbatim with SHA256 for
review; schema files are not hand-written substitutes for KSP output.

## SQLCipher 16 KB investigation

Official literal RELRO arithmetic remains **FAIL** for checked 4.19.0 and 4.18.0
prebuilt ELF files: `(VirtAddr + MemSiz) % 0x4000 != 0`. All three LOAD segments
per file are independently 16 KB aligned with matching offset/address residue.
The rounded 16 KB RELRO protection interval has zero overlap with non-RELRO
writable LOAD bytes and allocated writable sections in all eight checked files.
Consequently the literal failure alone does not prove a runtime crash: the
assumed writable bytes immediately after RELRO are padding in these files.
Neither the arithmetic gate nor runtime validation is weakened or marked PASS.
No blind version downgrade, native rebuild or ELF rewriting is performed.

Sources: [Android 16 KB checks](https://developer.android.com/guide/practices/page-sizes#check-relro),
[Bionic protection implementation](https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker_phdr.cpp),
[publisher fixed build](https://github.com/sqlcipher/sqlcipher-android/tree/9a5d685404489cbff14d4c46da81555de1a38787).
The publisher build uses NDK r25c with max-page-size 16384 but no common-page-size
16384. Android linker behavior on a real test device and complete encrypted Room
acceptance remain unverified. Actual app and test APK ZIP alignment checks
passed in the bounded attempt; they do not change the
literal ELF RELRO FAIL or prove 16 KB runtime. x86_64 simulated
16 KB testing would not certify arm64 hardware.

No new SDK terms were accepted. The public metadata's matching canonical
android-sdk-license hash is not acceptance on this local SDK-less machine;
any future installer must verify the existing accepted record on its own host
before installing pinned packages. Unaccepted arm DBT terms remain excluded.

The separate [bounded device experiment](../checks/sdk-device/README.md) verifies
existing installer-host acceptance using the official API before fixed payload
installation. It explicitly uses the unsupported software-emulation path with
strict deadlines; its 13-case command timed out as recorded above. It is now
manual-only for the original full-suite mode so normal UI integration cannot
silently repeat the complete experiment. The separately authorized single-case
follow-up below uses its unique request-file trigger. Actual packaged
license equality and APK ZIP checks are separate from runtime and RELRO gates.

## Accepted visual source integration and interaction checks

The exact `0c3c23ae4b4daa4bbfab8f29502cf9d51f79e0fd` visual source passed its
independent source review and [actual CI](https://github.com/T1doo/AIyou/actions/runs/38043449879)
with 216 real render results. Its four current PNGs were independently viewed
and the static changes accepted: readable unopened attachment labels, genuinely
disabled search, static unavailable filters, plain local-processing status and
retained pale-blue hierarchy. This does not certify device disabled behavior or
TalkBack. Integration preserves the actual ViewModel draft/save/retry callbacks,
recent complete originals, internal search interface and offline-license route.
Static filter words wrap as whole items so `图片` need not split across lines.

Six actual Compose interaction/semantics regressions are added on a JVM Android
simulation, covering disabled touch handling, accessibility editing, unavailable
versus busy capability semantics, real supplied draft/save callbacks, the license
route, and narrow large-font controls. The initial integrated revision
`c5a9828b2eaad06072da23e7b940bd720eab7f40` in
[CI 38045679941](https://github.com/T1doo/AIyou/actions/runs/38045679941)
compiled and executed all six tests: four passed and two failed at the
`assertIsNotFocused` checks. Its APK and 216 render checks passed; lint and the
native test APK step were skipped after that failure. The corrected tests check
the entire unmerged semantics tree for actual focused nodes and positively
verify enabled input focus, rather than require a disabled node to expose
`Focused=false`. At `7977a7bc9d2b6f0e065f421cab1e5ca14f6323ae`,
[CI 38046489263](https://github.com/T1doo/AIyou/actions/runs/38046489263)
actually executed all six with zero failures, errors or skips. APK builds,
seven packaged-license byte comparisons, both APK ZIP checks and 216 renders
also passed. That run remained FAIL because lint model generation encountered
an incomplete `debugAndroidTestCompileClasspath` lock for the fixed debug-only
`ui-test-manifest:1.12.1`; no lint source diagnosis was reached. The resolver now
explicitly resolves both app debug instrumentation classpaths, and the lock is
generated from that real graph. Versions and existing production graphs are
retained. Subsequent source verification requires its ordinary CI's actual
test XML, lint and render results; source review alone is not a passing result.
Both original failures remain preserved. These UI tests do not open production
databases or stand in for native tests. Test-only Robolectric 4.17 and its API35
preinstrumented `15-robolectric-13954326-i7` Maven framework are fixed and Gradle
locked; the runner uses that local artifact in offline mode, without its own
downloader or SDK installer. API35's Java17 requirement and fixed framework
coordinate follow the official
[4.17 SDK provider](https://github.com/robolectric/robolectric/blob/robolectric-4.17/robolectric/src/main/java/org/robolectric/plugins/DefaultSdkProvider.java);
the [Robolectric setup](https://robolectric.org/getting-started/) and
[Compose test setup](https://developer.android.com/develop/ui/compose/testing)
describe the test runner and Compose rule infrastructure. The debug-only test
manifest is kept out of the release configuration.

At `dbb48ed1feebe7491c14f7dd1bb3cf03fe230944`,
[CI 38047321570](https://github.com/T1doo/AIyou/actions/runs/38047321570) passed
the actual 3 domain, 3 host queue, 7 component and 6 Compose tests, all three
strict lint tasks, both APK builds, all seven packaged-license byte checks,
both ZIP checks and 216 actual render cases. Exact schema and all four module
locks matched. This is a verified engineering checkpoint, not full D02 native
acceptance; the historical partial device result remains unchanged.

## Targeted fourth-case diagnostic follow-up

The new test-only observer preserves the fourth case's original five rounds,
ten opens, ten explicit closes, ten entry queries and assertions. Opt-in logs
locate setup, open, draft update, save, query and close wall/process-CPU time.
Slow-step Java thread samples are limited to the instrumentation process and
bounded in frequency/count/size. No keys, record bodies, SQL parameters or
private production records enter the trace. Host regressions compile the
actual observer source and verify failure preservation, bounded samples and
suspended-operation timing. Parser regressions reject partial/truncated,
duplicate or failed traces. These host checks do not run SQLCipher.
The first independent follow-up review rejected a parser false-complete path
for truncated or missing/failed thread snapshots and insufficient cleanup
reserve. These failures are retained as regressions: tagged malformed lines
fail, each bounded snapshot carries explicit identity/count/error information,
and original operation threads are kept within the cap. The execution window
now reserves 75 seconds for owned cleanup; blocking archive reads are bounded
by a tested Linux process timer. Neither the 240-second instrumentation nor
1,320-second overall budget is extended.

The single-case follow-up retains the 240-second command deadline and 22-minute
overall experiment budget, unchanged fixed emulator/image and installer-host
existing-acceptance check. A unique reviewed request-file push starts this
authorized attempt; normal source integration does not repeat it. Continuous
trace and load/CPU-pressure evidence are preserved even on timeout. Before its
actual CI result, execution remains NOT RUN; a passing targeted case cannot
erase the original timeout or certify the nine previously unstarted cases.
File/process history labels are not OS cold-cache/warm-cache measurements, and
aggregate process CPU is not exact per-thread wait time or sole-cause proof.

Encrypted migration assertions now verify all entry/revision metadata and hash,
exact draft identity/version, local-job metadata/state, FK consistency,
immutability and reopen. The rejected-migration case compares complete logical
schema/rows before/after, avoiding false ciphertext-byte expectations under WAL
recovery. Cross-process tests retain different-PID and externally separated
phases, adding acknowledged entry/draft identities, time/revision/hash, local
job and FK checks. Their new assertions remain native NOT RUN until executed.

A fresh independent fixed-4.19 four-ABI recheck retains literal RELRO FAIL,
zero checked rounded-page writable overlap, and 16KB runtime NOT RUN. Exact
JNIString publisher/introduction bytes are verified. Official archived Harmony
NOTICE texts are available as research references, but the exact adapted AOSP
revision and its relevant distributed NOTICE remain UNKNOWN. No unrelated
Harmony component notice is copied into the APK or claimed to close the gap.
