# F06 / D07 manual confirmed tasks — limited slice

This slice implements user-authored formal tasks. Full D07 reminder acceptance remains
OPEN, as do the previous native migration and production process-restart gates.
The original master Markdown and product architecture DOCX remain unchanged.

This page records the initial undated-task slice. The subsequent explicit date/time,
postponement and versioned-ciphertext extension is specified in
[MANUAL_TASK_DATES_CONTRACT.md](MANUAL_TASK_DATES_CONTRACT.md). Its final verification
is separate; it does not close the native restart or full D07 reminder gates.

## User behavior

- The production 待办 page supports explicit 创建待办 → title input → 确认创建.
  Records, capture drafts, chat and AI suggestions never create formal tasks.
- After an actual durable transaction and authenticated readback, a task joins the
  list. Pending, failed and uncertain writes never show success.
- Each OPEN task has explicit 标记完成 and 取消待办 actions, bound to its exact
  ID/version. Same-title tasks remain distinct. COMPLETED/CANCELLED are retained
  terminal states in this slice; editing, deletion and changing them back to OPEN
  are outside scope.
- In this initial slice all tasks were undated. The page says 没有系统提醒 and 未设时间. Words such as
  “明天三点” remain literal title text. There is no time parser, alarm, worker,
  notification, permission request, calendar integration, or AI task creation.
- Creation/update timestamps and the creation zone are audit metadata, not deadlines.
  A backwards clock cannot move the original creation time or lower updatedAt.
- Formal tasks reload from encrypted storage when the repository/app opens.
  Unconfirmed input stays in the active memory session on failure. It is not a
  persisted task draft or an Android Bundle and is lost on process death or explicit
  editor close. Closing an uncertain editor does not discard the stable pending
  request; retry can still confirm it before another creation is allowed.

## Storage and authority

The existing SQLCipher database advances from Room v5 to v6, adding only the
manual_tasks table (id, version, lastMutationId, ciphertext). Existing nine tables
are untouched. Formal manual tasks have no foreign key to originals or AI proposals
and remain independent when a record is edited or tombstoned.

The borrowed task repository uses the same counted SQL gateway and owner lifecycle
as manual tags and source-bound text. All task AEAD work occurs inside a counted
transaction. The owner seals/drains the gateway before closing Room or wiping task
keys. No new key alias, credential, dependency, security permission or network path
is introduced.

Titles, status and audit metadata are AES-GCM ciphertext with fresh 12-byte nonces.
HKDF-Expand's independent com.t1doo.aiyou.manual-tasks.v1 domain derives a task
key from the existing uniformly random 32-byte database key. AAD binds the format,
task ID, version and mutation ID. UTF-8 decoding is strict and bounded; corruption,
wrong keys or swapped metadata fail closed and retain the existing bytes.

Creation has a stable client-generated task ID and mutation ID. Exact latest-create
replay returns the original version without inserting or re-encrypting. State
changes CAS the task version and require OPEN. Exact latest-transition replay checks
expectedVersion+1, target status, mutation ID and the original requested timestamp.
Conflicting payloads, stale versions and old creates against terminal tasks cannot
overwrite or reopen them.

All writes share the actual ViewModel IO command queue. An uncertain write retains
its immutable pending intent. A capability reinitialization confirms that same
intent rather than treating retained input as a fresh creation. Conflicts require
explicit reread. Session, version, generation and navigation ownership reject
retained actions and post-disposal publications.

Titles trim only outer Unicode whitespace and preserve internal spacing, case and
combining marks. They are limited to 240 code points / 960 raw UTF-16 units;
blank/format-only, control characters and malformed UTF-16 are rejected.
Model/UI diagnostic strings redact titles and input. The UI uses directly remembered
TextFieldState with no Saver; the editor's input/undo history never enter SavedState.

## Evidence and limitations

- Eight host tests run the actual task SQL/schema/AEAD on real file-backed SQLite:
  actual exported v5 migration with all nine old tables seeded, atomic rollback,
  authenticated reopen, live database/WAL plaintext-marker checks, Unicode bounds,
  nonce changes, independent-connection CAS, exact replay, lost COMMIT ACK,
  corruption preservation, independent records, and counted drain/key lifetime.
  Legacy remote BLOB rows are synthetic preservation fixtures, not valid AI grants.
- Five actual ViewModel tests compile production controllers on Android/lifecycle
  shells and use the same production task SQL/AEAD. They cover failed input, stable
  identities, global capture recovery plus capability reinitialization, explicit
  conflict reread, same-title targets, unavailable versus empty, late sessions,
  immediate capture input, disposal and authenticated storage reopen.
- Six actual Compose JVM tests exercise the production app route/controller with a
  controlled in-memory IO acknowledgement port. They prove UI semantics, failure,
  status actions, version/navigation rejection, 320 dp / 200% font reachability,
  SavedState privacy and fresh editor undo history. They do not prove encryption.
- Four new native Room/SQLCipher methods are registered in the exact 35-method DEX
  inventory. Their compilation is not execution. Native migration, device UI,
  physical/OEM behavior and production process restart remain NOT RUN.
- The v6 export and fixture must come from actual KSP generation. A bootstrap run
  only obtains real bytes; delivery requires committing those bytes and another
  exact-head CI in CHECKED_IN mode. Ownership gates reject drift without repair.

LOCAL_ONLY remains the default. Full D06 / LOCAL_INDEX, AI proposal confirmation,
permission/status-aware scheduling and full D07 remain OPEN. Explicit manual dates
and postponement are covered by the extension linked above, with their own evidence.
