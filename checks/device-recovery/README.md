# D08: one isolated production-APK SAF recovery probe

This is a **new, unexecuted device driver**, not a recovered device result.
`request.json` is committed with `executionApproved: false`. Local source review
and mock/type tests do not approve installation, SDK downloads, KVM ACL changes,
or any device operation. Do not flip that field or push the trigger without the
separate execution review. No production source, existing workflow, old device
request, database format, Android permission, or original plan is changed.

## Fixed source and package

- Production commit: `958ab2a08536161d8a0776ef155b6368d7df09c6`.
- Existing run/artifact: `40123738360` / `12572840840` (`aiyou-d01-debug`).
- Artifact ZIP SHA-256: `475285ec3ccd6d16605e6c0fca6051a3cb68ddf9ae29998aa36699d0e22254a4`.
- **Actual APK checked before installation:** 27,161,705 bytes,
  SHA-256 `dadce271185cc1ff1e8a577f1986d4b4290fbcb60daf7dd187c7088b5d1b693a`.
- Production package `com.t1doo.aiyou`; independent Java-only/self-targeted
  test package `com.t1doo.aiyou.recoveryprobe`. No production rebuild or signing
  key is needed. The probe requests no Android permissions. Its local SAF
  provider is protected by `MANAGE_DOCUMENTS` and explicit system URI grants.

## Original-plan scope and actual flow

This implements the executable part missing from master-plan §14.4 / D08's
fresh-install recovery exercise; it prepares part of D09, not D09 acceptance.
All input is created inside **one new owned API35 x86_64 AVD**:

1. Fail if either package already exists. Install only the fixed APK.
2. Via real production UI save one marked synthetic Unicode record, one 2×2 PNG
   through the system photo picker, and one canonical 1-second silent WAV through
   real SAF. Add a manually entered `[0,4410)`-sample transcript and separately
   accept a non-sensitive ordinary self-report.
3. Use the existing full-backup UI, scope confirmation and secure password fields.
   The random independent recovery password exists only in the instrumentation
   process's memory, never arguments, files, logs, result JSON or screenshots.
   `char[]` is cleared; Java immutable string copies cannot promise immediate
   erasure and disappear with the owned process/AVD.
4. The actual system DocumentsUI creates an encrypted backup in the probe's real
   DocumentsProvider. Wait for production's write/close/read-back acknowledgement.
5. Recheck installed APK hash, uninstall **this test installation**, reinstall the
   same APK, and verify an empty UI. The self-targeted provider survives this
   production uninstall; this does not substitute an activity-result callback.
6. Choose the same SAF document, authenticate with the independent password, and
   explicitly restore into the empty library. Wait for its final receipt.
7. Find the original through the real keyword UI and open the exact original;
   verify the accepted self-report and transcript version/range. Explicitly
   confirm a synthetic plaintext media export through the existing UI/SAF so the
   probe can compare actual original PNG/WAV bytes and Unicode text, every member
   manifest hash/length, source ID/revision/hash and exact range. No production
   private directory, key, ciphertext database, or test-only callback is read.
8. Move the source to the recycle bin through real confirmation. Verify timeline
   search is empty, then unified queries for the fact/original and transcript
   are empty. This covers soft-delete propagation, not permanent erase forensics.
9. Remove only same-hash owned package installations, the issued MediaStore image,
   owned emulator/ADB descendants and AVD; restore the exact KVM ACL. Any missing
   acknowledgement, unknown replacement or unconfirmed cleanup fails the run.

All UI/SAF selectors remain **device NOT RUN**. Unexpected/ambiguous nodes fail;
there is no callback injection, automatic permission grant, functional retry,
fake success, or fallback to a user's device.

## Execution bounds (prepared, not approved)

The new workflow matches only public `T1doo/AIyou`, a push on existing
`device/d02-kvm-acceptance-20261012`, the complete fixed message
`[d08-recovery] 958ab one isolated SAF round`, attempt **1**, and a request-path
change. No dispatch, pull-request event, matrix, rerun, cache, upload, or arbitrary
command/serial parameter exists. Normal feature-branch pushes cannot select it.
Original formal and old device workflows are unchanged.

- One standard `ubuntu-24.04` job, hard limit **35 minutes**. The admission receipt
  gives setup/build/download/execution/cleanup a shared **1,920-second** monotonic
  deadline with 120 seconds reserved for cleanup; checkout/action startup is also
  bounded by the job limit. Device instrumentation receives at most 960 seconds
  of the remaining budget. No budget extension, retry or TCG fallback.
- Existing JDK17, AGP9.4.1, wrapper/verification metadata, SDK Platform37.0 r2,
  Build Tools36.0.0, adb and avdmanager are required. No `sdkmanager --licenses` or
  license-file write. Missing tools or matching existing acceptance stops the run.
- The only newly provisionable packages, after separate execution approval, are
  emulator37.2.12 (`emulator-linux_x64-16428233.zip`, 349,654,171 bytes,
  SHA-1 `cd7362ea55dfb86a418958138dc396e74165dd01`) and default API35 x86_64 r2
  (`x86_64-35_r02.zip`, 782,404,023 bytes,
  SHA-1 `2d857d170c0d1b827149565da34b3383e5306f7f`), from the fixed official
  `https://dl.google.com/android/repository/` paths already defined in the old
  package installer. Strict length/hash, safe extraction and frozen metadata
  checks are reused; SHA-256 is recorded during a future actual download.
  Existing different packages are never overwritten.
- Both full license texts must still match
  SHA-256 `aaf80cd0aee7e569ffa8a4be1b61189c0fefccf23068e38dfafe336289b8c723`
  and the runner must already have acceptance record
  `24333f8a63b6825ea9c5514f83c2829b004d1fee`. Reusing an existing record is not a
  new acceptance. No packages have been downloaded for this candidate.
- At least 8 GiB free on the SDK/temp volumes, 2 GiB AVD RAM, 2 virtual CPUs.
  Only loopback-owned ADB ports and pidfd/subreaper-owned process identities.

## KVM branch and cleanup contract

Read `/dev/kvm` character-device identity and numeric ACL before network setup.
If already read/write accessible, make **no** permission change. Otherwise:

- Refuse a preexisting named ACL for this job UID, owner-access mismatch, changed
  device identity, or an existing mask without `rw`. Never widen an old mask.
- Permit only an exact named-UID `rw` grant with unchanged existing mask, or a
  new `rw` mask from an original trivial `group::rw-` ACL. Prove every other
  principal's effective access unchanged before the command.
- Commands are `sudo -n setfacl --no-mask -m u:UID:rw- /dev/kvm` (append
  `,m::rw-` only for that owned new mask). No chmod, group membership, persistent
  udev, security setting or personal-device change.
- Before cleanup recheck same dev/inode/rdev/uid/gid and exact post-grant ACL;
  withdraw only `u:UID` (and `m:` only if created). Verify exact original ACL.
  If identity/ACL changed externally, make **no further mutation**, report failure
  and rely on the isolated job VM teardown; never claim exact cleanup succeeded.

Packages are force-stopped/uninstalled only after installed-path/hash checks;
the host also covers a lost probe/installation ACK. Process stop, ACL and owned
SDK/AVD deletion stay bounded; cleanup failure is a failed run, not a warning.
New SDK directories are removed only with matching owned inode/metadata tree.
Logs/archives and any unconfirmed partial setup stay in this ephemeral runner's
private temporary area until VM teardown. No persistent cache or artifact upload.

## Evidence and local verification

Future device output: one original instrumentation XML case, 12 ordered real UI
checkpoints, exact production/run-token binding, fixture/export hashes, and three
real screenshots. A separate five-file allowlist uses bounded hash-framed job-log
records; it cannot publish APKs, databases, archives or secrets. Truncated/missing
evidence fails. `d08_recovery_contract.decode_log` reads already-downloaded job
logs (including timestamp prefixes); it never fetches or executes anything.
Early failure preserves any available files and never fabricates missing frames.

Local checks use only Python stdlib/mock and the **existing** pinned JDK plus
cached Android15 API JAR. The Java test type-compiles the real probe sources and
executes the same pure ZIP verifier on controlled Unicode/hash fixtures. Those
host ZIP payloads test verification, not Android decoding or SAF. No AGP build,
APK install, Android runtime, KVM, SAF round trip, actual audio, physical device,
OEM reminders, TalkBack, performance, OCR or complete D09 result is claimed.
