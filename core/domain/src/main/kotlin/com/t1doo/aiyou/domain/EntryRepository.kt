package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.OriginalRevision
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.TextDraft
import com.t1doo.aiyou.model.OriginalEditDraft

/** Successful completion means a durable transaction, never an optimistic UI insert. */
interface EntryRepository {
    val sourceTags: SourceTagRepository? get() = null
    val localCandidates: LocalCandidateRepository? get() = null
    val favorites: RecordFavoriteRepository? get() = null
    val permanentErasure: PermanentErasureRepository? get() = null
    val localSearch: LocalSearchRepository? get() = null
    val recordPackages: RecordPackageRepository? get() = null
    val recycleBin: RecycleBinRepository? get() = null
    val originalExport: OriginalExportRepository? get() = null
    val manualTasks: ManualTaskRepository? get() = null
    val manualTags: ManualTagRepository? get() = null
    /** Borrowed capability over the same records database; it never enables remote processing. */
    val sourceBoundText: SourceBoundTextRepository? get() = null
    val editDrafts: OriginalEditDraftStore? get() = null
    /** Optional local attachment capability; supplied by the production encrypted repository. */
    val attachments: AttachmentStore? get() = null
    /** Finish local storage recovery before presenting restored records; no remote work. */
    suspend fun prepare() {}
    suspend fun readDraft(): TextDraft?
    suspend fun updateDraft(text: String): TextDraft
    /** Delete only this exact acknowledged snapshot. Missing is idempotent; a different draft conflicts. */
    suspend fun discardDraft(expected: TextDraft) { throw UnsupportedOperationException("Draft discard unavailable") }
    /** Explicit recovery after uncertain discard. Never replace a different draft; replacement identity is stable across retries. */
    suspend fun restoreDiscardedDraft(expected: TextDraft, replacement: TextDraft): TextDraft {
        throw UnsupportedOperationException("Draft recovery unavailable")
    }
    suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry
    suspend fun entries(): List<StoredEntry>
    suspend fun revisions(entryId: String): List<OriginalRevision>
    suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry
    /** Production binds the exact acknowledged edit to its transaction for cold-start confirmation. */
    suspend fun reviseFromDraft(draft: OriginalEditDraft, createdAtMillis: Long): StoredEntry =
        revise(draft.entryId, draft.baseRevision, draft.text, createdAtMillis)
    /** Null means no receipt for this exact identity/version/content; matching text is insufficient. */
    suspend fun committedEdit(draft: OriginalEditDraft): StoredEntry? = null
}

class RecordConflict : IllegalStateException("Record version changed")
class StorageKeyUnavailable : IllegalStateException("Local encryption key unavailable")
class RecordIntegrityFailure : IllegalStateException("Local record integrity check failed")
