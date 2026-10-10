# D02 encrypted text checkpoint

This is the first storage slice. Independent source review and Android
compilation have run; native execution is pending. It is not a completed D02
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
- NOT RUN: native/Keystore instrumentation execution. Test APK compilation does
  not certify encryption, migration, process restart or device behavior.
- 13 actual encrypted integration test methods are written; writing or compiling
  them does not mean execution. The separate process-restart probe requires
  external write/read phases and a different process PID; reopening a database
  in one process is not claimed as a process restart.
- NOT RUN: device UI, IME, TalkBack, performance, real 4 KB/16 KB SQLCipher load.

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
16384. Actual APK zip alignment, Android linker behavior on a real test device
and successful encrypted Room operations remain NOT RUN. x86_64 simulated
16 KB testing would not certify arm64 hardware.

No new SDK terms were accepted. The public metadata's matching canonical
android-sdk-license hash is not acceptance on this local SDK-less machine;
any future installer must verify the existing accepted record on its own host
before installing pinned packages. Unaccepted arm DBT terms remain excluded.

The separate [bounded device experiment](../checks/sdk-device/README.md) verifies
existing installer-host acceptance using the official API before fixed payload
installation. It explicitly uses the unsupported software-emulation path with
strict deadlines; its native outcome is pending its own CI. Actual packaged
license equality and APK ZIP checks are separate from runtime and RELRO gates.
