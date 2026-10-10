package com.t1doo.aiyou.data

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.model.ProcessingPolicy
import java.io.File
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

        AndroidNativeTrace().use { trace -> when (phase) {
            "write" -> {
                assertFalse(directory.exists())
                assertTrue(directory.mkdirs())
                val key = trace.step("load_key") { AndroidDatabaseKeyProvider(keyFile, alias).load(false) }
                val store = trace.step("open", openKind = "FRESH_FILE_FIRST_OPEN") {
                    EncryptedEntryRepository.openWithKey(context, databaseFile, key)
                }
                val acknowledgement = AutoCloseable { trace.step("close") { store.close() } }.use {
                    val committed = trace.suspending("update_draft") { store.updateDraft(original) }
                    val saved = trace.suspending("save_draft") { store.saveDraft(committed, 1000L, "Etc/UTC") }
                    assertEquals(original, saved.originalText)
                    assertEquals(ProcessingPolicy.LOCAL_ONLY, saved.policy)
                    val draft = trace.suspending("update_draft") { store.updateDraft(draftText) }
                    assertEquals(draftText, draft.text)
                    assertNotEquals(committed.id, draft.id)
                    assertEquals(1L, draft.version)
                    JSONObject().put("pid", Process.myPid()).put("entryId", saved.id)
                        .put("draftId", draft.id).put("draftVersion", draft.version)
                }
                trace.step("write_acknowledgement") { marker.writeText(acknowledgement.toString()) }
                assertTrue(databaseFile.isFile)
            }
            "read" -> {
                assertTrue(databaseFile.isFile)
                val acknowledgement = JSONObject(marker.readText())
                val writerPid = acknowledgement.getInt("pid")
                assertNotEquals("Read must execute in a new process", writerPid, Process.myPid())
                val key = trace.step("load_key") { AndroidDatabaseKeyProvider(keyFile, alias).load(true) }
                val store = trace.step("open", openKind = "EXISTING_FILE_FIRST_OPEN_NEW_PROCESS") {
                    EncryptedEntryRepository.openWithKey(context, databaseFile, key)
                }
                AutoCloseable { trace.step("close") { store.close() } }.use {
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
                    trace.step("check_foreign_keys") {
                        store.database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                            assertFalse(it.moveToFirst())
                        }
                    }
                }
            }
            "cleanup" -> {
                // Only this explicit synthetic UUID namespace; never production records or aliases.
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
                assertTrue(directory.deleteRecursively())
            }
            else -> fail("Unknown synthetic restart phase")
        } }
    }
}
