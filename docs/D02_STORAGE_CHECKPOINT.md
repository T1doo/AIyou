# D02 encrypted text checkpoint

This is the first storage slice, pending independent source review and real
Android compilation. It is not a completed D02 acceptance or device verdict.
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
- NOT RUN: Android/KSP compilation, generated Room schema validation, APK/lint
  and native/Keystore instrumentation execution for this new source snapshot.
  CI uses the already installed licensed SDK; no package installer is added.
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
