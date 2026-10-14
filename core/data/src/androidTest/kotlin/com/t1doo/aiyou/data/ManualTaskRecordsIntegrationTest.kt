package com.t1doo.aiyou.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Formal Room/SQLCipher contracts; compilation/DEX inventory is never device acceptance. */
@RunWith(AndroidJUnit4::class)
class ManualTaskRecordsIntegrationTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private val key = ByteArray(32) { (it + 43).toByte() }
    private val stores = mutableListOf<EncryptedEntryRepository>()
    private val file get() = File(directory, "records.db")
    @Before fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.noBackupFilesDir, "manual-tasks-" + UUID.randomUUID()).also { check(it.mkdirs()) }
        System.loadLibrary("sqlcipher")
    }
    @After fun after() { try { stores.forEach { it.closeAndAwait() } } finally { key.fill(0); if (::directory.isInitialized) directory.deleteRecursively() } }
    @Test fun freshVersionNinePersistsExplicitTaskAndTerminalStatesAcrossEncryptedReopen(): Unit = runBlocking {
        val store = open(); NativeRecordsSchemaFixture.assertCurrentSchema(store.database.openHelper.writableDatabase)
        assertTrue(store.manualTasks.snapshots().isEmpty()); assertTrue(store.entries().isEmpty())
        store.manualTasks.create(create("one")); store.manualTasks.create(create("two"))
        store.manualTasks.change(change("one", ManualTaskStatus.COMPLETED)); store.manualTasks.change(change("two", ManualTaskStatus.CANCELLED))
        store.closeAndAwait(); val reopened = open()
        assertEquals(ManualTaskStatus.COMPLETED, reopened.manualTasks.snapshots().single { it.id == "one" }.status)
        assertEquals(ManualTaskStatus.CANCELLED, reopened.manualTasks.snapshots().single { it.id == "two" }.status)
        assertEquals("合成手工待办🙂", reopened.manualTasks.snapshots().first().title)
        assertTrue(reopened.entries().isEmpty()); assertNull(reopened.readDraft())
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("合成手工待办"))
    }
    @Test fun encryptedVersionFiveMigrationPreservesOriginalDraftJobAndManualTag(): Unit = runBlocking {
        val tagCipher = ManualTagCipher(key)
        val blob = try { tagCipher.seal("legacy", 1, "legacy-tag", listOf("合成原标签")) } finally { tagCipher.wipe() }
        NativeRecordsSchemaFixture.create(file, key, 5) { sql ->
            sql.execSQL("INSERT INTO entries VALUES('legacy',1,55,'UTC','LOCAL_ONLY')")
            sql.execSQL("INSERT INTO entry_revisions VALUES('legacy',1,?,?,55)", arrayOf("合成原文", contentHash("合成原文")))
            sql.execSQL("INSERT INTO capture_draft VALUES(1,'draft',7,'合成独立草稿')")
            sql.execSQL("INSERT INTO local_jobs VALUES('job','legacy',1,'LOCAL_INDEX','PENDING')")
            sql.execSQL("INSERT INTO entry_manual_tags VALUES('legacy',1,'legacy-tag',?)", arrayOf(blob))
        }
        val store = open(); NativeRecordsSchemaFixture.assertCurrentSchema(store.database.openHelper.writableDatabase)
        assertTrue(store.manualTasks.snapshots().isEmpty()); assertEquals(listOf("合成原标签"), store.manualTags.read("legacy").names)
        assertEquals("合成独立草稿", store.readDraft()!!.text); assertEquals("合成原文", store.entries().single().originalText)
        store.manualTasks.create(create("one")); store.closeAndAwait(); val reopened = open()
        assertEquals(1, reopened.manualTasks.snapshots().size); assertEquals(7L, reopened.readDraft()!!.version)
        assertEquals(1, reopened.revisions("legacy").size); assertEquals(listOf("合成原标签"), reopened.manualTags.read("legacy").names)
        reopened.database.openHelper.writableDatabase.query("SELECT state FROM local_jobs").use {
            assertTrue(it.moveToFirst()); assertEquals("PENDING", it.getString(0)); assertFalse(it.moveToNext())
        }
    }
    @Test fun actualNativeTransitionAbortAndIndependentRoomsCasKeepSingleVersion(): Unit = runBlocking {
        val first = open(); first.manualTasks.create(create("one")); val second = open()
        val sql = first.database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER synthetic_task_abort AFTER UPDATE ON manual_tasks BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
        assertThrows(ManualTaskStorageFailure::class.java) { first.manualTasks.change(change("one", ManualTaskStatus.COMPLETED)) }
        assertEquals(1L, second.manualTasks.snapshots().single().version); sql.execSQL("DROP TRIGGER synthetic_task_abort")
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        try {
            val attempts = listOf(ManualTaskStatus.COMPLETED, ManualTaskStatus.CANCELLED)
            val outcomes = listOf(first, second).mapIndexed { index, store -> pool.submit<String> {
                go.await(); try { store.manualTasks.change(change("one", attempts[index])); "saved" } catch (_: ManualTaskConflict) { "conflict" }
            } }; go.countDown()
            assertEquals(listOf("conflict", "saved"), outcomes.map { it.get(2, TimeUnit.SECONDS) }.sorted())
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
        assertEquals(2L, first.manualTasks.snapshots().single().version); assertEquals(1, second.manualTasks.snapshots().size)
    }
    @Test fun nativeReplayAuthenticationAndOwnerCloseNeverReplaceTaskCiphertext(): Unit = runBlocking {
        val store = open(); val request = create("one"); store.manualTasks.create(request)
        assertEquals(1L, store.manualTasks.create(request).version)
        val terminal = change("one", ManualTaskStatus.COMPLETED)
        store.manualTasks.change(terminal); assertEquals(2L, store.manualTasks.change(terminal).version)
        assertThrows(ManualTaskConflict::class.java) { store.manualTasks.create(request) }
        val borrowed = store.manualTasks; store.closeAndAwait()
        assertThrows(ManualTaskStorageFailure::class.java) { borrowed.snapshots() }
        val reopened = open(); val sql = reopened.database.openHelper.writableDatabase
        val blob = sql.query("SELECT ciphertext FROM manual_tasks").use { check(it.moveToFirst()); it.getBlob(0) }
        blob[blob.lastIndex] = (blob.last().toInt() xor 1).toByte()
        sql.execSQL("UPDATE manual_tasks SET version=version+1,ciphertext=? WHERE id='one'", arrayOf(blob))
        assertThrows(ManualTaskStorageFailure::class.java) { reopened.manualTasks.snapshots() }
        assertThrows(ManualTaskStorageFailure::class.java) { reopened.manualTasks.create(request) }
        reopened.closeAndAwait(); assertThrows(ManualTaskStorageFailure::class.java) { open().manualTasks.snapshots() }
    }
    private fun open() = EncryptedEntryRepository.openWithKey(context, file, key.copyOf()).also(stores::add)
    private fun create(id: String) = ManualTaskCreate(id, "合成手工待办🙂", 55, "Asia/Shanghai", "create-$id")
    private fun change(id: String, status: ManualTaskStatus) = ManualTaskMutation(id, 1, status, 99, "change-$id-$status")
}
