package com.t1doo.aiyou.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.domain.OriginalEditDraftConflict
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.model.OriginalEditDraft
import com.t1doo.aiyou.model.OriginalEditDraftState
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
            store.close(); store = EncryptedEntryRepository.openWithKey(context, file, secret.copyOf())
            assertEquals(capture, store.readDraft())
            assertEquals(entry, store.entries().single())
            assertEquals(edit, store.editDrafts.read(entry.id))
            assertEquals(1, store.revisions(entry.id).size)
            val encrypted = File(directory, "records.db.original-edits").listFiles()!!.single { it.name.endsWith(".edit") }.readBytes()
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains(String(edit.text.toByteArray(Charsets.UTF_16BE), Charsets.ISO_8859_1)))
        } finally { store?.close(); secret.fill(0); directory.deleteRecursively() }
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
            store.close(); store = EncryptedEntryRepository.openWithKey(context, file, secret.copyOf())
            try { store.editDrafts.write(edit, edit.copy(version = 2, text = "合成迟到输入")); fail("Expected conflict") }
            catch (_: OriginalEditDraftConflict) { }
            assertEquals(terminal, store.editDrafts.read(entry.id)); assertTrue(store.editDrafts.pending().isEmpty())
            assertEquals(listOf("合成旧原文", "合成新的正式原文"), store.revisions(entry.id).map { it.originalText })
        } finally { store?.close(); secret.fill(0); directory.deleteRecursively() }
    }
}
