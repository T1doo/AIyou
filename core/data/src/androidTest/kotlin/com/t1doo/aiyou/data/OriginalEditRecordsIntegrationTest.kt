package com.t1doo.aiyou.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.domain.OriginalEditDraftConflict
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.domain.LocalProcessingChange
import com.t1doo.aiyou.domain.LocalProcessingChoice
import com.t1doo.aiyou.model.OriginalEditDraft
import com.t1doo.aiyou.model.OriginalEditDraftState
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.SourceVersion
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native Room/SQLCipher + actual edit cipher adapter. Compile does not establish device PASS. */
@RunWith(AndroidJUnit4::class)
class OriginalEditRecordsIntegrationTest {
    @Test fun aiNoneManualAndHistoricalDraftReceiptsPreservePolicyAcrossNativeColdReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "edit-ai-none-native-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        var owner: EncryptedEntryRepository? = null
        try {
            val file = File(directory, "records.db")
            fun open() = EncryptedEntryRepository.openWithKey(context, file, secret.copyOf()).also { owner = it }
            var store = open()
            val first = store.saveDraft(store.updateDraft("合成第一版原文"), 1000, "Etc/UTC")
            val source = SourceVersion(first.id, 1)
            val policy = store.localProcessing.change(LocalProcessingChange(store.localProcessing.read(source), LocalProcessingChoice.AI_NONE, UUID.randomUUID().toString()))
            val capture = store.updateDraft("合成独立此刻草稿")
            val edit = OriginalEditDraft(UUID.randomUUID().toString(), first.id, 1, 1, 1, first.originalText, "合成人工第二版")
            store.editDrafts.write(null, edit)
            store.closeAndAwait(); store = open()
            assertEquals(edit, store.editDrafts.read(first.id)); assertEquals(capture, store.readDraft())
            val saved = store.reviseFromDraft(edit, 2000)
            assertEquals(ProcessingPolicy.AI_NONE, saved.policy)
            assertEquals(policy.generation + 1, store.localProcessing.read(SourceVersion(first.id, 2)).generation)
            store.closeAndAwait(); store = open()
            assertEquals(saved, store.committedEdit(edit))
            assertEquals(saved, store.reviseFromDraft(edit, 3000))
            assertEquals(2, store.revisions(first.id).size)
            assertEquals(policy.generation + 1, store.localProcessing.read(SourceVersion(first.id, 2)).generation)
            try { store.revise(first.id, 1, "合成过期写入", 3000); fail("Expected version CAS conflict") } catch (_: RecordConflict) { }
            val historical = OriginalEditDraft(UUID.randomUUID().toString(), first.id, 2, 1, 1, saved.originalText, first.originalText)
            store.editDrafts.write(edit, historical)
            assertEquals(2, store.revisions(first.id).size)
            val restored = store.reviseFromDraft(historical, 4000)
            assertEquals(ProcessingPolicy.AI_NONE, restored.policy); assertEquals(3, restored.currentRevision)
            assertEquals(listOf(first.originalText, saved.originalText, first.originalText), store.revisions(first.id).map { it.originalText })
            assertEquals(policy.generation + 2, store.localProcessing.read(SourceVersion(first.id, 3)).generation)
            assertEquals(capture, store.readDraft())
            store.database.openHelper.writableDatabase.query("SELECT count(*) FROM local_candidate_batches UNION ALL SELECT count(*) FROM remote_text_jobs").use { rows ->
                assertTrue(rows.moveToFirst()); assertEquals(0L, rows.getLong(0)); assertTrue(rows.moveToNext()); assertEquals(0L, rows.getLong(0)); assertFalse(rows.moveToNext())
            }
        } finally { owner?.closeAndAwait(); secret.fill(0); directory.deleteRecursively() }
    }
    @Test fun encryptedEditAndCaptureSurviveReopenWithoutChangingFormalOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "edit-native-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        var store: EncryptedEntryRepository? = null
        try {
            val file = File(directory, "records.db")
            store = EncryptedEntryRepository.openWithKey(context, file, secret.copyOf())
            val entry = store.saveDraft(store.updateDraft("合成正式原文"), 1000, "Etc/UTC")
            val capture = store.updateDraft("合成独立此刻草稿")
            val edit = OriginalEditDraft(UUID.randomUUID().toString(), entry.id, 1, 1, 1, entry.originalText, "合成尚未提交的编辑🌿")
            store.editDrafts.write(null, edit)
            store.closeAndAwait(); store = EncryptedEntryRepository.openWithKey(context, file, secret.copyOf())
            assertEquals(capture, store.readDraft())
            assertEquals(entry, store.entries().single())
            assertEquals(edit, store.editDrafts.read(entry.id))
            assertEquals(1, store.revisions(entry.id).size)
            val encrypted = File(directory, "records.db.original-edits").listFiles()!!.single { it.name.endsWith(".edit") }.readBytes()
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains(String(edit.text.toByteArray(Charsets.UTF_16BE), Charsets.ISO_8859_1)))
        } finally { store?.closeAndAwait(); secret.fill(0); directory.deleteRecursively() }
    }

    @Test fun changedNativeOriginalRejectsStaleEditAndDiscardTombstoneSurvivesReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "edit-conflict-native-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        var store: EncryptedEntryRepository? = null
        try {
            val file = File(directory, "records.db")
            store = EncryptedEntryRepository.openWithKey(context, file, secret.copyOf())
            val entry = store.saveDraft(store.updateDraft("合成旧原文"), 1000, "Etc/UTC")
            val edit = OriginalEditDraft(UUID.randomUUID().toString(), entry.id, 1, 1, 1, entry.originalText, "合成待保留编辑")
            store.editDrafts.write(null, edit)
            store.revise(entry.id, 1, "合成新的正式原文", 2000)
            try { store.revise(entry.id, 1, edit.text, 3000); fail("Expected conflict") } catch (_: RecordConflict) { }
            assertEquals(edit, store.editDrafts.read(entry.id))
            val terminal = edit.copy(version = 2, originalText = "", text = "", state = OriginalEditDraftState.DISCARDED)
            store.editDrafts.write(edit, terminal)
            store.closeAndAwait(); store = EncryptedEntryRepository.openWithKey(context, file, secret.copyOf())
            try { store.editDrafts.write(edit, edit.copy(version = 2, text = "合成迟到输入")); fail("Expected conflict") }
            catch (_: OriginalEditDraftConflict) { }
            assertEquals(terminal, store.editDrafts.read(entry.id)); assertTrue(store.editDrafts.pending().drafts.isEmpty())
            assertEquals(listOf("合成旧原文", "合成新的正式原文"), store.revisions(entry.id).map { it.originalText })
        } finally { store?.closeAndAwait(); secret.fill(0); directory.deleteRecursively() }
    }
}
