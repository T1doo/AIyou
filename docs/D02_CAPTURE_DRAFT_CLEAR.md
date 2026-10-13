# D02 capture draft clear

The original Master Plan (§17, D02; lines 554 and 559) and complete Product
Architecture were reread before this slice. Encrypted records, drafts, versions
and recovery belong to D02. Confirmed tasks and reminders belong to D07, so this
change completes a current draft path without introducing formal tasks.

The production 此刻 screen now offers 清空草稿 for a nonempty capture draft.
It requires confirmation, describes the loss of that draft, and keeps saved
records separate. 保留草稿 performs no delete. While the operation runs, editing,
saving, duplicate confirmation and cancellation are blocked. On failure the
input remains visible; the dialog offers its own acknowledgement retry or a
return to editing. This is draft disposal, not permanent record deletion,
secure erasure, recycle-bin support, or D08 completion.

The request binds its identity, input generation and text. The existing IO queue
finishes prior autosaves before binding the complete acknowledged TextDraft
identity/version/text. A failed autosave cannot authorize deletion of a different
persisted draft. EncryptedEntryRepository compares the entire snapshot and
consumes only capture_draft in one existing Room transaction. An absent draft is
idempotent; a different identity, version or text conflicts. No schema, key,
processing policy, attachment, original revision, job or edit-draft format changes.

Deletion and readback must both return before UI text clears. An uncertain result
invalidates cached-draft reuse for a later explicit save, while retaining the
original acknowledgement snapshot for retry. Retrying never binds another
session's new draft. Clear does not increment the save celebration counter or
claim a record was saved. Request text is memory-only and is not written to a
Bundle or log. Future new input invalidates an old confirmation even if it returns
to identical text.

Validation evidence is kept separately in
`/workspace/aiyou-capture-discard-20261013/`. Initial missing-control RED and a
subsequent test import compilation failure are preserved. Host queue tests use
actual production ViewModel code with controlled persistence boundaries; production
Compose tests use that ViewModel through AIyouApp with a synthetic repository.
File SQLite child-process tests execute the actual DAO DELETE, covering restart
absence, later identity protection and transaction rollback. Those are not
Android Room/SQLCipher/Keystore execution claims.

The existing encryptedDraftAndEntrySurviveCloseAndReopen instrumentation method
now also verifies exact-version conflict, idempotent discard, encrypted reopen
absence, retained originals/jobs and protection of a newly created draft.
Its native execution remains NOT RUN for this revision. Full test/build totals,
exact APK identity and independent review outcome are reported in the delivery
record, not inferred from writing or compiling a test.

The selected workspace has no adb/emulator/system image/KVM. A new ordinary
GitHub Actions read in this slice again returned Forbidden. Same-job checkout,
build, APK hash/signature verification and device execution would remove the
need to transfer an already built local APK, but it would be a separately hashed
CI build, not proof that the local APK ran. Existing runner KVM permission and
license acceptance are still unverified. The old consumed CI request and its
ACL-changing driver are not reused under the current no-ACL constraint. No new
runner, SDK agreement, credential change, Library retry or network-policy change
was started.
