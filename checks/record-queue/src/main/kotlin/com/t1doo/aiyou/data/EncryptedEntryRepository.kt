package com.t1doo.aiyou.data

import android.app.Application
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.TextDraft
import com.t1doo.aiyou.model.OriginalRevision
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.domain.EntryRepository
import kotlinx.coroutines.CompletableDeferred

/** Controlled synthetic persistence boundary, not Room/native/Keystore evidence. */
class EncryptedEntryRepository(
    private val failPriorEdit: Boolean = false,
    private val failFirstSave: Boolean = false,
    private val failFirstReadback: Boolean = false,
    private val failFirstRevision: Boolean = false,
    private val failRevisionReadback: Boolean = false,
    private val delayHistory: Boolean = false,
) : EntryRepository, AutoCloseable {
    val firstEditStarted = CompletableDeferred<Unit>()
    val releaseFirstEdit = CompletableDeferred<Unit>()
    val firstSaveStarted = CompletableDeferred<Unit>()
    val releaseFirstSave = CompletableDeferred<Unit>()
    val submissions = mutableListOf<TextDraft>()
    val revisionStarted = CompletableDeferred<Unit>()
    val releaseRevision = CompletableDeferred<Unit>()
    val historyStarted = CompletableDeferred<Unit>()
    val releaseHistory = CompletableDeferred<Unit>()
    var historyCalls = 0
    var failHistory = false
    var historyOverride: List<OriginalRevision>? = null
    data class RevisionSubmission(val entryId: String, val revision: Int, val text: String)
    val revisionSubmissions = mutableListOf<RevisionSubmission>()
    private val history = mutableMapOf<String, MutableList<OriginalRevision>>()
    var editCalls = 0
    var draft: TextDraft? = null
    private val saved = linkedMapOf<String, StoredEntry>()
    private var readbackFailed = false
    private var revisionReadbackFailed = false

    fun seed(entry: StoredEntry) {
        saved[entry.id] = entry
        history.getOrPut(entry.id) { mutableListOf() }.add(OriginalRevision(entry.id, entry.currentRevision,
            entry.originalText, "synthetic hash", entry.capturedAtMillis))
    }
    fun remove(entryId: String) { saved.remove(entryId) }
    fun removeRevision(entryId: String, revision: Int) { history[entryId]?.removeAll { it.revision == revision } }

    override suspend fun readDraft(): TextDraft? = draft
    override suspend fun updateDraft(text: String): TextDraft {
        if (++editCalls == 1) {
            firstEditStarted.complete(Unit)
            if (failPriorEdit) { releaseFirstEdit.await(); error("synthetic draft write failure") }
        }
        return TextDraft(draft?.id ?: "synthetic-$editCalls", (draft?.version ?: 0) + 1, text).also { draft = it }
    }
    override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry {
        submissions += expected
        require(expected.text.isNotBlank())
        firstSaveStarted.complete(Unit)
        releaseFirstSave.await()
        if (failFirstSave && submissions.size == 1) error("synthetic precommit failure")
        return saved.getOrPut(expected.id) {
            StoredEntry(expected.id, 1, expected.text, capturedAtMillis, captureTimeZone, ProcessingPolicy.LOCAL_ONLY)
        }.also { draft = null }
    }
    override suspend fun entries(): List<StoredEntry> {
        if (failRevisionReadback && revisionSubmissions.isNotEmpty() && saved.values.any { it.currentRevision > 1 } && !revisionReadbackFailed) {
            revisionReadbackFailed = true
            error("synthetic revision readback failure after commit")
        }
        if (failFirstReadback && saved.isNotEmpty() && !readbackFailed) {
            readbackFailed = true
            error("synthetic readback failure after commit")
        }
        return saved.values.toList()
    }
    override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry {
        revisionSubmissions += RevisionSubmission(entryId, expectedRevision, text)
        revisionStarted.complete(Unit)
        releaseRevision.await()
        if (failFirstRevision && revisionSubmissions.size == 1) error("synthetic revision failure before commit")
        val old = saved[entryId] ?: throw RecordConflict()
        if (old.currentRevision != expectedRevision) throw RecordConflict()
        val next = old.copy(currentRevision = expectedRevision + 1, originalText = text)
        history.getOrPut(entryId) { mutableListOf() }.add(OriginalRevision(entryId, next.currentRevision, text,
            "synthetic hash", createdAtMillis))
        saved[entryId] = next
        return next
    }
    override suspend fun revisions(entryId: String): List<OriginalRevision> {
        if (++historyCalls == 1) { historyStarted.complete(Unit); if (delayHistory) releaseHistory.await() }
        if (failHistory) error("synthetic history read failure")
        return historyOverride ?: history[entryId].orEmpty().toList()
    }
    override fun close() {}
    companion object {
        const val MAX_TEXT_LENGTH = 100_000
        lateinit var instance: EncryptedEntryRepository
        @Suppress("UNUSED_PARAMETER")
        fun open(application: Application): EncryptedEntryRepository = instance
    }
}
