package com.t1doo.aiyou.data

import android.app.Application
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.TextDraft
import kotlinx.coroutines.CompletableDeferred

/** Controlled synthetic persistence boundary, not Room/native/Keystore evidence. */
class EncryptedEntryRepository(
    private val failPriorEdit: Boolean = false,
    private val failFirstSave: Boolean = false,
    private val failFirstReadback: Boolean = false,
) {
    val firstEditStarted = CompletableDeferred<Unit>()
    val releaseFirstEdit = CompletableDeferred<Unit>()
    val firstSaveStarted = CompletableDeferred<Unit>()
    val releaseFirstSave = CompletableDeferred<Unit>()
    val submissions = mutableListOf<TextDraft>()
    var editCalls = 0
    var draft: TextDraft? = null
    private val saved = linkedMapOf<String, StoredEntry>()
    private var readbackFailed = false

    suspend fun readDraft(): TextDraft? = draft
    suspend fun updateDraft(text: String): TextDraft {
        if (++editCalls == 1) {
            firstEditStarted.complete(Unit)
            if (failPriorEdit) { releaseFirstEdit.await(); error("synthetic draft write failure") }
        }
        return TextDraft(draft?.id ?: "synthetic-$editCalls", (draft?.version ?: 0) + 1, text).also { draft = it }
    }
    suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry {
        submissions += expected
        require(expected.text.isNotBlank())
        firstSaveStarted.complete(Unit)
        releaseFirstSave.await()
        if (failFirstSave && submissions.size == 1) error("synthetic precommit failure")
        return saved.getOrPut(expected.id) {
            StoredEntry(expected.id, 1, expected.text, capturedAtMillis, captureTimeZone, ProcessingPolicy.LOCAL_ONLY)
        }.also { draft = null }
    }
    suspend fun entries(): List<StoredEntry> {
        if (failFirstReadback && saved.isNotEmpty() && !readbackFailed) {
            readbackFailed = true
            error("synthetic readback failure after commit")
        }
        return saved.values.toList()
    }
    fun close() {}
    companion object {
        const val MAX_TEXT_LENGTH = 100_000
        lateinit var instance: EncryptedEntryRepository
        @Suppress("UNUSED_PARAMETER")
        fun open(application: Application): EncryptedEntryRepository = instance
    }
}
