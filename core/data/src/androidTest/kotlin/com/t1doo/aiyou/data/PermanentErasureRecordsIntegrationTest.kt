package com.t1doo.aiyou.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/SQLCipher/file contracts. Compilation is not native execution evidence. */
@RunWith(AndroidJUnit4::class)
class PermanentErasureRecordsIntegrationTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private val key = ByteArray(32) { (it + 61).toByte() }
    private val stores = mutableListOf<EncryptedEntryRepository>()
    private val file get() = File(directory, "records.db")
    @Before fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.noBackupFilesDir, "permanent-erasure-" + UUID.randomUUID()).also { check(it.mkdirs()) }
        System.loadLibrary("sqlcipher")
    }
    @After fun after() {
        try { stores.forEach { it.closeAndAwait() } }
        finally { key.fill(0); if (::directory.isInitialized) directory.deleteRecursively() }
    }
    @Test fun encryptedVersionSevenMigrationPreservesLegacyRowsAndCopiesExactIndependentPackageReceipts(): Unit = runBlocking {
        val text = "合成第七版原文"
        val group: List<Any> = listOf("restore-receipt", "legacy", 1L, "PACKAGE_RESTORE", "CONFIRMED:synthetic-receipt")
        val origin: List<Any> = listOf("origin-receipt", "legacy", 1L, "PACKAGE_ORIGIN", "CONFIRMED:synthetic-origin")
        NativeRecordsSchemaFixture.create(file, key, 7) { sql ->
            sql.execSQL("INSERT INTO entries VALUES('legacy',1,55,'UTC','LOCAL_ONLY')")
            sql.execSQL("INSERT INTO entry_revisions VALUES('legacy',1,?,?,55)", arrayOf(text, OriginalExports.hash(text)))
            sql.execSQL("INSERT INTO capture_draft VALUES(1,'draft',7,'合成独立草稿')")
            sql.execSQL("INSERT INTO local_search_epoch VALUES(1,0,1,'UNKNOWN')")
            for (row in listOf(group, origin)) sql.execSQL("INSERT INTO local_jobs VALUES(?,?,?,?,?)", row.toTypedArray())
        }
        val store = open()
        NativeRecordsSchemaFixture.assertCurrentSchema(store.database.openHelper.writableDatabase)
        assertEquals(text, store.entries().single().originalText)
        assertEquals(TextDraft("draft", 7, "合成独立草稿"), store.readDraft())
        for (table in listOf("local_jobs", "record_package_receipts")) {
            val rows = store.database.openHelper.writableDatabase.query("SELECT id,entryId,sourceRevision,operation,state FROM $table ORDER BY id").use { cursor ->
                buildList { while (cursor.moveToNext()) add(listOf(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3), cursor.getString(4))) }
            }
            assertEquals(listOf(origin, group), rows)
        }
        store.closeAndAwait()
        NativeRecordsSchemaFixture.assertCurrentSchema(open().database.openHelper.writableDatabase)
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(text))
    }
    @Test fun nativeSingleErasureRemovesHistoryTagsEditsAndIndexAcrossColdReopenWithoutChangingOtherRecordsOrTasks(): Unit = runBlocking {
        val store = open()
        val original = store.saveDraft(store.updateDraft("合成应删除的咖啡原文"), 55, "UTC")
        store.revise(original.id, 1, "合成应删除的咖啡第二版", 60)
        val other = store.saveDraft(store.updateDraft("合成独立记录"), 65, "UTC")
        val task = store.manualTasks.create(ManualTaskCreate("independent-task", "合成独立任务", 65, "UTC", "create-task"))
        store.manualTags.replace(ManualTagMutation(original.id, 0, listOf("合成私密标签"), "tag"))
        val edit = OriginalEditDraft(UUID.randomUUID().toString(), original.id, 2, 2, 1,
            "合成应删除的咖啡第二版", "合成未提交编辑")
        store.editDrafts.write(null, edit)
        var batches = 0
        do { check(++batches <= 100); val batch = store.localSearch.batch() } while (batch.remaining)
        val held = store.localSearch.candidates(LocalSearchRequest("咖啡"))
        val intent = intent(store, original.id, 2)
        assertTrue(store.permanentErasure.erase(intent).complete)
        assertFalse(store.localSearch.publishCurrent(held) { fail("Deleted source opened") })
        assertErased(store, original.id)
        assertEquals(listOf(other), store.entries()); assertEquals(listOf(task), store.manualTasks.snapshots())
        store.closeAndAwait()
        val reopened = open(); reopened.prepare()
        assertTrue(reopened.permanentErasure.erase(intent).complete)
        assertErased(reopened, original.id)
        assertEquals(listOf(other), reopened.entries()); assertEquals(listOf(task), reopened.manualTasks.snapshots())
        assertThrows(Exception::class.java) {
            reopened.database.openHelper.writableDatabase.execSQL("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,55,'UTC','LOCAL_ONLY')", arrayOf(original.id))
        }
    }
    @Test fun nativeFinalSqlAbortKeepsStablePendingIntentAndActualStartupResumesAfterColdReopen(): Unit = runBlocking {
        val store = open()
        val original = store.saveDraft(store.updateDraft("合成待续删原文"), 55, "UTC")
        val intent = intent(store, original.id, 1)
        val sql = store.database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER synthetic_erasure_abort BEFORE DELETE ON entry_revisions BEGIN SELECT RAISE(ABORT,'synthetic erasure abort'); END")
        try { store.permanentErasure.erase(intent); fail("Expected incomplete deletion") } catch (_: PermanentErasureIncomplete) { }
        val pending = checkNotNull(store.permanentErasure.read(original.id))
        assertEquals(PermanentErasurePhase.PLANNED, pending.phase)
        assertEquals(intent.mutationId, pending.intent.mutationId)
        sql.query("SELECT count(*) FROM entry_revisions WHERE entryId=?", arrayOf(original.id)).use {
            assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0))
        }
        assertTrue(store.revisions(original.id).isEmpty()) // Trashed history remains hidden.
        assertThrows(RecycleConflict::class.java) {
            store.recycleBin.apply(RecycleMutation(intent.source, intent.generation, true, "restore"))
        }
        store.closeAndAwait()
        val reopened = open()
        reopened.database.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_erasure_abort")
        reopened.prepare()
        val done = checkNotNull(reopened.permanentErasure.read(original.id))
        assertTrue(done.complete); assertEquals(intent.mutationId, done.intent.mutationId)
        assertErased(reopened, original.id)
    }
    private fun intent(store: EncryptedEntryRepository, id: String, revision: Int): PermanentErasureIntent {
        val access = store.recycleBin.read(id)
        val trashed = store.recycleBin.apply(RecycleMutation(SourceVersion(id, revision.toLong()), access.generation, false, UUID.randomUUID().toString()))
        return PermanentErasureIntent(SourceVersion(id, revision.toLong()), trashed.generation, UUID.randomUUID().toString())
    }
    private suspend fun assertErased(store: EncryptedEntryRepository, id: String) {
        assertTrue(checkNotNull(store.permanentErasure.read(id)).complete)
        assertTrue(store.revisions(id).isEmpty()); assertNull(store.editDrafts.read(id))
        assertTrue(store.localSearch.candidates(LocalSearchRequest("咖啡")).entries.none { it.id == id })
        for (table in listOf("entry_revisions", "attachments", "entry_manual_tags", "remote_source_access", "local_jobs",
            "text_annotations", "remote_text_jobs", "local_search_fts", "local_search_documents", "owned_file_creations")) {
            store.database.openHelper.writableDatabase.query("SELECT entryId FROM $table WHERE entryId=?", arrayOf(id)).use { assertFalse(it.moveToFirst()) }
        }
    }
    private fun open() = EncryptedEntryRepository.openWithKey(context, file, key.copyOf()).also(stores::add)
}
