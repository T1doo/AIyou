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
        val marker = File(directory, "writer.pid") // synthetic test metadata; contains no key or body
        val original = "合成独立进程原文 🌿 $token"
        val draftText = "合成独立进程未提交草稿 $token"

        when (phase) {
            "write" -> {
                assertFalse(directory.exists())
                assertTrue(directory.mkdirs())
                val key = AndroidDatabaseKeyProvider(keyFile, alias).load(false)
                EncryptedEntryRepository.openWithKey(context, databaseFile, key).use { store ->
                    val saved = store.saveDraft(store.updateDraft(original), 1000L, "Etc/UTC")
                    assertEquals(original, saved.originalText)
                    assertEquals(ProcessingPolicy.LOCAL_ONLY, saved.policy)
                    assertEquals(draftText, store.updateDraft(draftText).text)
                }
                marker.writeText(Process.myPid().toString())
                assertTrue(databaseFile.isFile)
            }
            "read" -> {
                assertTrue(databaseFile.isFile)
                val writerPid = marker.readText().toInt()
                assertNotEquals("Read must execute in a new process", writerPid, Process.myPid())
                val key = AndroidDatabaseKeyProvider(keyFile, alias).load(true)
                EncryptedEntryRepository.openWithKey(context, databaseFile, key).use { store ->
                    val saved = store.entries().single()
                    assertEquals(original, saved.originalText)
                    assertEquals(1, saved.currentRevision)
                    assertEquals(ProcessingPolicy.LOCAL_ONLY, saved.policy)
                    assertEquals(original, store.revisions(saved.id).single().originalText)
                    assertEquals(draftText, store.readDraft()?.text)
                }
            }
            "cleanup" -> {
                // Only this explicit synthetic UUID namespace; never production records or aliases.
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
                assertTrue(directory.deleteRecursively())
            }
            else -> fail("Unknown synthetic restart phase")
        }
    }
}
