# D02 pre-open ciphertext evidence

This slice preserves the record schema, encrypted formats, wrapping alias and
purpose-specific AAD. It does not complete the full D02/device acceptance gate.

The production `open(context)` factory now calls one shared, read-only evidence
inspection before its first key callback. A failed directory listing or stat is
unknown, never an empty store. Symlinks, nonregular obstacles and unstable file
identity fail closed. Both original-edit and attachment stores are inspected;
ordinary non-lock files (including partial and unknown names) require the
corresponding existing key. Only a regular empty `.store.lock` is excluded.
The checker neither takes over nor deletes an existing lock.

Database sidecars and observed `.key`, `.bak` and `.new` envelopes also prevent
fresh-key authorization. The attachment database ledger remains an additional
requirement. Each subsequent store-key stage advances the evidence checkpoint,
so previously observed evidence and directory identities cannot be forgotten.

Cross-stage directory identity uses non-null `fileKey`, not `creationTime`:
[Android permits creationTime to fall back to lastModifiedTime](https://developer.android.com/reference/java/nio/file/attribute/BasicFileAttributes#creationTime()).
Within one read-only scan, stat changes still fail closed. A null fileKey is
rejected conservatively; compatibility on all API 26/OEM filesystems is not
established. Repeated stat is not an atomic filesystem transaction and does not
eliminate every check/use race on a hostile filesystem.

## Verification scope

`RepositoryCiphertextEvidenceTest` compiles and invokes the actual production
opening sequence. Only key/IO/native-database boundaries are injected. All data
is synthetic; tests do not create Android Keystore keys or open native SQLCipher.

- Initial RED: one real failing Gradle test; the old enumeration-null behavior
  invoked three key callbacks and published a synthetic replacement envelope.
- Review RED: two additional real failures demonstrated creation-time fallback
  rejecting legitimate first-open writes and a newly observed directory being
  replaced between key stages without detection.
- The regression suite now has 21 tests, including first-open file creation and
  reopen, inaccessible evidence, links, partials, held empty locks, existing
  envelopes, ledger failure, directory replacement and database-key clearing.

2026-10-13 final slice checks: `:app:checkDebugAarMetadata`,
`:app:assembleDebug`, `:core:domain:test`, `:core:data:testDebugUnitTest`,
`:core:data:lintDebug`, `:app:testDebugUnitTest`, `:app:lintDebug` PASS.
Actual XML: data 21, application 81 and domain 3 tests, zero failures/errors/skips.
Independent read-only review rechecked both fixes and closed its P1/P2 findings.

Failures during initial preflight call no key loader. Ledger failure necessarily
occurs after loading/opening the database: it calls no store-key loader and clears
the database key. Do not conflate these two guarantees.

Real Android NIO, AndroidKeyStore, native DB opening, API 26 device compatibility,
16 KB page-size and physical-device UI/performance for this change are NOT RUN.
Historical device evidence for earlier commits is not evidence for this slice.
