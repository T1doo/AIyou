package com.t1doo.aiyou.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.File
import java.util.UUID
import java.util.Base64
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
        val day = due("2042-11-19")
        val overlap = due("2026-11-01", "01:30", "America/New_York", ManualTaskOccurrence.SECOND)
        store.manualTasks.create(create("one", day)); store.manualTasks.create(create("two", overlap))
        store.manualTasks.change(change("one", ManualTaskStatus.COMPLETED)); store.manualTasks.change(change("two", ManualTaskStatus.CANCELLED))
        store.closeAndAwait(); val reopened = open()
        assertEquals(ManualTaskStatus.COMPLETED, reopened.manualTasks.snapshots().single { it.id == "one" }.status)
        assertEquals(ManualTaskStatus.CANCELLED, reopened.manualTasks.snapshots().single { it.id == "two" }.status)
        assertEquals("合成手工待办🙂", reopened.manualTasks.snapshots().first().title)
        assertEquals(day, reopened.manualTasks.snapshots().single { it.id == "one" }.schedule)
        assertNull(reopened.manualTasks.snapshots().single { it.id == "one" }.schedule!!.instant())
        assertEquals(overlap, reopened.manualTasks.snapshots().single { it.id == "two" }.schedule)
        assertEquals(-18_000, reopened.manualTasks.snapshots().single { it.id == "two" }.schedule!!.offsetSeconds)
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
        val day = due("2042-11-19")
        store.manualTasks.create(create("one", day)); store.closeAndAwait(); val reopened = open()
        assertEquals(1, reopened.manualTasks.snapshots().size); assertEquals(7L, reopened.readDraft()!!.version)
        assertEquals(day, reopened.manualTasks.snapshots().single().schedule)
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
        val date = schedule("one", 1, ManualTaskDateOperation.SET, due("2042-11-19"))
        assertThrows(ManualTaskStorageFailure::class.java) { first.manualTasks.reschedule(date) }
        assertNull(second.manualTasks.snapshots().single().schedule)
        assertEquals(1L, second.manualTasks.snapshots().single().version); sql.execSQL("DROP TRIGGER synthetic_task_abort")
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        try {
            val outcomes = listOf(first, second).mapIndexed { index, store -> pool.submit<String> {
                go.await(); try {
                    if (index == 0) store.manualTasks.reschedule(date)
                    else store.manualTasks.change(change("one", ManualTaskStatus.COMPLETED))
                    "saved"
                } catch (_: ManualTaskConflict) { "conflict" }
            } }; go.countDown()
            assertEquals(listOf("conflict", "saved"), outcomes.map { it.get(2, TimeUnit.SECONDS) }.sorted())
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
        assertEquals(2L, first.manualTasks.snapshots().single().version); assertEquals(1, second.manualTasks.snapshots().size)
        val winner = first.manualTasks.snapshots().single()
        if (winner.status == ManualTaskStatus.OPEN) assertEquals(date.schedule, winner.schedule)
        else { assertEquals(ManualTaskStatus.COMPLETED, winner.status); assertNull(winner.schedule) }
    }
    @Test fun nativeReplayAuthenticationAndOwnerCloseNeverReplaceTaskCiphertext(): Unit = runBlocking {
        var store = open(); val request = create("one"); store.manualTasks.create(request)
        assertEquals(1L, store.manualTasks.create(request).version)
        // Frozen independently with Python cryptography AESGCM and the pre-date v1 serializer.
        val legacy = Base64.getDecoder().decode("AQcICQoLDA0ODxAREj+AQ5fjXQigaqORmrvxsniFI5Uq+lFmJEkiVwViL76VDEiIay58TVr3H8FYul/hQYr7dRPdtKZ8r75916F0aGiynNPQoMxvfw0Tqcgf4Qp5WuTTNXH/1mYXR3vK7OCqXw==")
        store.database.openHelper.writableDatabase.execSQL("INSERT INTO manual_tasks VALUES(?,?,?,?)",
            arrayOf<Any>("legacy", 1, "legacy-create", legacy))
        store.closeAndAwait(); store = open()
        assertNull(store.manualTasks.snapshots().single { it.id == "legacy" }.schedule)
        assertArrayEquals(legacy, ciphertext(store, "legacy"))
        val set = schedule("legacy", 1, ManualTaskDateOperation.SET, due("2042-11-19"))
        store.manualTasks.reschedule(set); val dated = ciphertext(store, "legacy")
        assertEquals(2.toByte(), dated[0]); store.manualTasks.reschedule(set)
        assertArrayEquals(dated, ciphertext(store, "legacy"))
        store.manualTasks.reschedule(schedule("legacy", 2, ManualTaskDateOperation.POSTPONE, due("2042-11-20")))
        store.manualTasks.reschedule(schedule("legacy", 3, ManualTaskDateOperation.CLEAR, null))
        val terminal = change("one", ManualTaskStatus.COMPLETED)
        store.manualTasks.change(terminal); assertEquals(2L, store.manualTasks.change(terminal).version)
        assertThrows(ManualTaskConflict::class.java) { store.manualTasks.create(request) }
        val borrowed = store.manualTasks; store.closeAndAwait()
        assertThrows(ManualTaskStorageFailure::class.java) { borrowed.snapshots() }
        val reopened = open(); val sql = reopened.database.openHelper.writableDatabase
        assertNull(reopened.manualTasks.snapshots().single { it.id == "legacy" }.schedule)
        assertEquals(4L, reopened.manualTasks.snapshots().single { it.id == "legacy" }.version)
        val blob = ciphertext(reopened, "one")
        blob[blob.lastIndex] = (blob.last().toInt() xor 1).toByte()
        sql.execSQL("UPDATE manual_tasks SET version=version+1,ciphertext=? WHERE id='one'", arrayOf(blob))
        assertThrows(ManualTaskStorageFailure::class.java) { reopened.manualTasks.snapshots() }
        assertThrows(ManualTaskStorageFailure::class.java) { reopened.manualTasks.create(request) }
        reopened.closeAndAwait(); assertThrows(ManualTaskStorageFailure::class.java) { open().manualTasks.snapshots() }
    }
    private fun open() = EncryptedEntryRepository.openWithKey(context, file, key.copyOf()).also(stores::add)
    private fun create(id: String, due: ManualTaskSchedule? = null) = ManualTaskCreate(id, "合成手工待办🙂", 55, "Asia/Shanghai", "create-$id", due)
    private fun change(id: String, status: ManualTaskStatus) = ManualTaskMutation(id, 1, status, 99, "change-$id-$status")
    private fun due(day: String, time: String = "", zone: String = "Asia/Shanghai", occurrence: ManualTaskOccurrence? = null) =
        checkNotNull(ManualTaskDateInput(day, time, zone, occurrence).parse())
    private fun schedule(id: String, version: Long, operation: ManualTaskDateOperation, due: ManualTaskSchedule?) =
        ManualTaskScheduleMutation(id, version, operation, due, 99, "date-$id-$version-$operation")
    private fun ciphertext(store: EncryptedEntryRepository, id: String) =
        store.database.openHelper.writableDatabase.query("SELECT ciphertext FROM manual_tasks WHERE id=?", arrayOf(id)).use { cursor ->
            check(cursor.moveToFirst()); cursor.getBlob(0).also { check(!cursor.moveToNext()) }
        }
}
