package com.t1doo.aiyou.data

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.model.ProcessingPolicy
import java.io.File
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import com.t1doo.aiyou.model.AttachmentIntent
import com.t1doo.aiyou.model.AttachmentState
import com.t1doo.aiyou.model.OriginalEditDraft
import com.t1doo.aiyou.model.OriginalEditDraftState
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Two externally separated instrumentation processes, never Activity.recreate or DB reopen. */
@RunWith(AndroidJUnit4::class)
class ProcessRestartPersistenceTest {
    @Test fun acknowledgedSaveAndUnsubmittedDraftSurviveProcessRestart() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("aiyouRestartPhase")
        assumeTrue("Requires external write / force-stop / read phases", phase != null)
        val token = UUID.fromString(checkNotNull(args.getString("aiyouRestartToken"))).toString()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "d02-restart-$token")
        val keyFile = File(directory, "database.key")
        val alias = "com.t1doo.aiyou.test.restart.$token"
        val databaseFile = File(directory, "records.db")
        val marker = File(directory, "writer.json") // synthetic acknowledgement metadata; no key or body
        val original = "合成独立进程原文 🌿 $token"
        val draftText = "合成独立进程未提交草稿 $token"

        val media = "合成跨PID加密附件 $token".repeat(100).toByteArray()
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun openStore(): EncryptedEntryRepository {
            val key = AndroidDatabaseKeyProvider(keyFile, alias).load(databaseFile.exists())
            return EncryptedEntryRepository.openWithKey(context, databaseFile, key,
                editKey = { AndroidDatabaseKeyProvider(File(directory, "edit.key"), "$alias.edit").load(File(directory, "records.db.original-edits").exists()) },
                attachmentKey = { AndroidDatabaseKeyProvider(File(directory, "media.key"), "$alias.media").load(File(directory, "records.db.attachments").exists()) })
        }
        AndroidNativeTrace().use { trace -> when (phase) {
            "write" -> {
                assertFalse(directory.exists())
                assertTrue(directory.mkdirs())
                val store = trace.step("open", openKind = "FRESH_FILE_FIRST_OPEN") { openStore() }
                val acknowledgement = AutoCloseable { trace.step("close") { store.closeAndAwait() } }.use {
                    val committed = trace.suspending("update_draft") { store.updateDraft(original) }
                    val saved = trace.suspending("save_draft") { store.saveDraft(committed, 1000L, "Etc/UTC") }
                    assertEquals(original, saved.originalText)
                    assertEquals(ProcessingPolicy.LOCAL_ONLY, saved.policy)
                    val draft = trace.suspending("update_draft") { store.updateDraft(draftText) }
                    assertEquals(draftText, draft.text)
                    assertNotEquals(committed.id, draft.id)
                    assertEquals(1L, draft.version)
                    val edit = OriginalEditDraft(UUID.randomUUID().toString(), saved.id, 1, 1, 1, original, "合成跨PID待提交修改 $token")
                    store.editDrafts.write(null, edit)
                    val ready = AttachmentIntent(UUID.randomUUID().toString(), saved.id, 1, "image/png", hash(media), media.size.toLong())
                    store.attachments.save(ready) { ByteArrayInputStream(media) }
                    val staged = ready.copy(id = UUID.randomUUID().toString())
                    store.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_restart_ready BEFORE UPDATE ON attachments WHEN NEW.state='READY' BEGIN SELECT RAISE(ABORT, 'synthetic acknowledgement failure'); END")
                    try { store.attachments.save(staged) { ByteArrayInputStream(media) }; fail("Expected actual Room READY transition failure") }
                    catch (failure: Exception) { assertNotNull(failure) }
                    val blob = File(directory, "records.db.attachments/${staged.id}.aead")
                    JSONObject().put("pid", Process.myPid()).put("entryId", saved.id)
                        .put("draftId", draft.id).put("draftVersion", draft.version)
                        .put("editId", edit.id).put("readyId", ready.id).put("stagedId", staged.id).put("blobHash", hash(blob.readBytes()))
                }
                trace.step("write_acknowledgement") { marker.writeText(acknowledgement.toString()) }
                assertTrue(databaseFile.isFile)
            }
            "read" -> {
                assertTrue(databaseFile.isFile)
                val acknowledgement = JSONObject(marker.readText())
                val writerPid = acknowledgement.getInt("pid")
                assertNotEquals("Read must execute in a new process", writerPid, Process.myPid())
                val store = trace.step("open", openKind = "EXISTING_FILE_FIRST_OPEN_NEW_PROCESS") { openStore() }
                AutoCloseable { trace.step("close") { store.closeAndAwait() } }.use {
                    val stagedBlob = File(directory, "records.db.attachments/${acknowledgement.getString("stagedId")}.aead")
                    assertEquals(acknowledgement.getString("blobHash"), hash(stagedBlob.readBytes()))
                    try { store.prepare(); fail("Expected preserved native transition failure on startup") }
                    catch (failure: Exception) { assertNotNull(failure) }
                    assertEquals(acknowledgement.getString("blobHash"), hash(stagedBlob.readBytes()))
                    store.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_restart_ready")
                    store.prepare(); store.prepare()
                    val rows = store.attachments.list(acknowledgement.getString("entryId"), 1)
                    assertEquals(2, rows.size); assertTrue(rows.all { it.state == AttachmentState.READY })
                    for (row in rows) {
                        assertArrayEquals(media, store.attachments.read(row.intent.id))
                        assertEquals(row, store.attachments.save(row.intent) { error("No second copy on confirmation") })
                    }
                    assertEquals(acknowledgement.getString("blobHash"), hash(stagedBlob.readBytes()))
                    val saved = trace.suspending("query_entries") { store.entries().single() }
                    assertEquals(acknowledgement.getString("entryId"), saved.id)
                    assertEquals(original, saved.originalText)
                    assertEquals(1, saved.currentRevision)
                    assertEquals(1000L, saved.capturedAtMillis)
                    assertEquals("Etc/UTC", saved.captureTimeZone)
                    assertEquals(ProcessingPolicy.LOCAL_ONLY, saved.policy)
                    val revision = trace.suspending("query_revisions") { store.revisions(saved.id).single() }
                    assertEquals(saved.id, revision.entryId)
                    assertEquals(1, revision.revision)
                    assertEquals(original, revision.originalText)
                    assertEquals(contentHash(original), revision.contentHash)
                    assertEquals(1000L, revision.createdAtMillis)
                    val draft = trace.suspending("read_draft") { checkNotNull(store.readDraft()) }
                    assertEquals(draftText, draft.text)
                    assertEquals(acknowledgement.getString("draftId"), draft.id)
                    assertEquals(acknowledgement.getLong("draftVersion"), draft.version)
                    trace.step("query_local_job") {
                        store.database.openHelper.writableDatabase.query(
                            "SELECT id,entryId,sourceRevision,operation,state FROM local_jobs",
                        ).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            assertEquals("${saved.id}:1:LOCAL_INDEX", cursor.getString(0))
                            assertEquals(saved.id, cursor.getString(1))
                            assertEquals(1, cursor.getInt(2))
                            assertEquals("LOCAL_INDEX", cursor.getString(3))
                            assertEquals("PENDING", cursor.getString(4))
                            assertFalse(cursor.moveToNext())
                        }
                    }
                    val edit = checkNotNull(store.editDrafts.read(saved.id))
                    assertEquals(acknowledgement.getString("editId"), edit.id)
                    assertEquals("合成跨PID待提交修改 $token", edit.text)
                    assertEquals(listOf(edit.id), store.editDrafts.pending().drafts.map { it.id })
                    // Real SQLite fault rolls back revision, index work AND receipt together.
                    store.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_edit_receipt BEFORE INSERT ON local_jobs WHEN NEW.operation='ORIGINAL_EDIT_COMMIT' BEGIN SELECT RAISE(ABORT, 'synthetic receipt failure'); END")
                    try { store.reviseFromDraft(edit, 2000); fail("Expected receipt transaction rollback") }
                    catch (failure: Exception) { assertNotNull(failure) }
                    assertEquals(1, store.revisions(saved.id).size); assertNull(store.committedEdit(edit))
                    assertEquals(edit, store.editDrafts.read(saved.id))
                    store.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_edit_receipt")
                    val committed = store.reviseFromDraft(edit, 2000)
                    assertEquals(2, committed.currentRevision)
                    assertEquals(committed, store.reviseFromDraft(edit, 9999))
                    assertEquals(2, store.revisions(saved.id).size)
                    assertEquals(edit, store.editDrafts.read(saved.id)) // kill before terminal publication
                    acknowledgement.put("readPid", Process.myPid())
                    marker.writeText(acknowledgement.toString())
                    trace.step("check_foreign_keys") {
                        store.database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                            assertFalse(it.moveToFirst())
                        }
                    }
                }
            }
            "confirm" -> {
                val acknowledgement = JSONObject(marker.readText())
                assertNotEquals(acknowledgement.getInt("pid"), Process.myPid())
                assertNotEquals(acknowledgement.getInt("readPid"), Process.myPid())
                val store = openStore()
                try {
                    store.prepare()
                    val id = acknowledgement.getString("entryId")
                    val edit = checkNotNull(store.editDrafts.read(id))
                    assertEquals(2, checkNotNull(store.committedEdit(edit)).currentRevision)
                    assertNull(store.committedEdit(edit.copy(id = UUID.randomUUID().toString())))
                    assertNull(store.committedEdit(edit.copy(version = 2)))
                    assertEquals(2, store.reviseFromDraft(edit, 9999).currentRevision)
                    val terminal = edit.copy(version = 2, originalText = "", text = "", state = OriginalEditDraftState.COMMITTED)
                    store.editDrafts.write(edit, terminal)
                    assertEquals(terminal, store.editDrafts.read(id)); assertTrue(store.editDrafts.pending().drafts.isEmpty())
                    assertEquals(listOf(original, edit.text), store.revisions(id).map { it.originalText })
                    assertEquals(draftText, store.readDraft()!!.text)
                    assertEquals(2, store.attachments.list(id, 1).size)
                    assertTrue(store.attachments.list(id, 2).isEmpty())
                    store.database.openHelper.writableDatabase.query("SELECT operation,state,count(*) FROM local_jobs GROUP BY operation,state").use {
                        val counts = mutableMapOf<String, Long>()
                        while (it.moveToNext()) counts[it.getString(0) + ":" + it.getString(1)] = it.getLong(2)
                        assertEquals(mapOf("LOCAL_INDEX:PENDING" to 2L, "ORIGINAL_EDIT_COMMIT:CONFIRMED" to 1L), counts)
                    }
                } finally { store.closeAndAwait() }
            }
            "cleanup" -> {
                // Only this explicit synthetic UUID namespace; never production records or aliases.
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); for (item in listOf(alias, "$alias.edit", "$alias.media")) deleteEntry(item) }
                assertTrue(directory.deleteRecursively())
            }
            else -> fail("Unknown synthetic restart phase")
        } }
        val evidence = android.os.Bundle().apply {
            putString("aiyou_phase", phase); putString("aiyou_token", token); putString("aiyou_pid", Process.myPid().toString())
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, evidence)
    }
}
