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

/** Actual Room/SQLCipher checks, compiled separately. No device PASS without execution. */
@RunWith(AndroidJUnit4::class)
class LocalSearchRecordsIntegrationTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private val key = ByteArray(32) { (it + 41).toByte() }
    private val stores = mutableListOf<EncryptedEntryRepository>()
    private val file get() = File(directory, "records.db")
    @Before fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.noBackupFilesDir, "local-index-" + UUID.randomUUID()).also { check(it.mkdirs()) }
        System.loadLibrary("sqlcipher")
    }
    @After fun after() {
        try { stores.forEach { it.closeAndAwait() } }
        finally { key.fill(0); if (::directory.isInitialized) directory.deleteRecursively() }
    }
    @Test fun encryptedFtsChineseEnglishAndDigitsPersistAcrossReopenWithCurrentOwnerProof(): Unit = runBlocking {
        val first = open()
        val original = first.saveDraft(first.updateDraft("合成小林咖啡 Meeting42 🙂"), 55, "UTC")
        drain(first)
        NativeRecordsSchemaFixture.assertCurrentSchema(first.database.openHelper.writableDatabase)
        val previous = first.localSearch.candidates(LocalSearchRequest("小林"))
        for (query in listOf("小林", "咖啡", "eeti", "42")) {
            assertEquals(listOf(original.id), first.localSearch.candidates(LocalSearchRequest(query)).entries.map { it.id })
        }
        first.closeAndAwait()
        val second = open()
        assertEquals(0, second.localSearch.batch().processed)
        assertFalse(second.localSearch.publishCurrent(previous) { fail("Foreign owner") })
        val current = second.localSearch.candidates(LocalSearchRequest("咖啡"))
        assertTrue(second.localSearch.resolve(current, original.id) { assertEquals(original, it) })
        for (candidate in directory.listFiles().orEmpty().filter { it.name.startsWith("records.db") }) {
            val bytes = candidate.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(bytes.contains("Meeting42"))
            assertFalse(bytes.contains(LocalSearchTokenizer.document("小林")))
        }
    }
    @Test fun encryptedVersionSixMigrationPreservesOriginalDraftAndNonIndexReceipt(): Unit = runBlocking {
        val text = "合成第六版原文咖啡"
        NativeRecordsSchemaFixture.create(file, key, 6) { sql ->
            sql.execSQL("INSERT INTO entries VALUES('legacy',1,55,'UTC','AI_NONE')")
            sql.execSQL("INSERT INTO entry_revisions VALUES('legacy',1,?,?,55)", arrayOf(text, OriginalExports.hash(text)))
            sql.execSQL("INSERT INTO capture_draft VALUES(1,'draft',7,'合成独立草稿')")
            sql.execSQL("INSERT INTO local_jobs VALUES('index','legacy',1,'LOCAL_INDEX','SUCCEEDED')")
            sql.execSQL("INSERT INTO local_jobs VALUES('receipt','legacy',1,'PACKAGE_ORIGIN','CONFIRMED:synthetic')")
        }
        val store = open()
        NativeRecordsSchemaFixture.assertCurrentSchema(store.database.openHelper.writableDatabase)
        val pending = store.localSearch.candidates(LocalSearchRequest("咖啡"))
        assertTrue(pending.pending)
        assertEquals(text, pending.entries.single().originalText)
        drain(store)
        assertEquals(ProcessingPolicy.AI_NONE, store.entries().single().policy)
        assertEquals(TextDraft("draft", 7, "合成独立草稿"), store.readDraft())
        store.database.openHelper.writableDatabase.query("SELECT state FROM local_jobs WHERE id='receipt'").use {
            assertTrue(it.moveToFirst()); assertEquals("CONFIRMED:synthetic", it.getString(0)); assertFalse(it.moveToNext())
        }
        assertFalse(store.localSearch.candidates(LocalSearchRequest("咖啡")).pending)
    }
    @Test fun actualNativeIndexInsertAbortRollsBackAndEditTagRecycleRestoreInvalidateProof(): Unit = runBlocking {
        val store = open()
        val entry = store.saveDraft(store.updateDraft("合成原文咖啡"), 55, "UTC")
        val epoch = store.localSearch.epoch()
        val sql = store.database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER synthetic_index_abort BEFORE INSERT ON local_search_documents BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
        assertThrows(LocalSearchFailure::class.java) { store.localSearch.batch() }
        assertEquals(epoch, store.localSearch.epoch())
        sql.query("SELECT * FROM local_search_fts").use { assertFalse(it.moveToFirst()) }
        sql.execSQL("DROP TRIGGER synthetic_index_abort")
        drain(store)
        val previous = store.localSearch.candidates(LocalSearchRequest("咖啡"))
        store.revise(entry.id, 1, "合成新版本茶叶", 60)
        assertFalse(store.localSearch.resolve(previous, entry.id) { fail("Old version opened") })
        store.manualTags.replace(ManualTagMutation(entry.id, 0, listOf("合成标签"), "tag"))
        drain(store)
        val tagOnly = store.localSearch.candidates(LocalSearchRequest("合成标签"))
        assertEquals(listOf(entry.id), tagOnly.entries.map { it.id })
        assertFalse(tagOnly.entries.single().originalText.contains("合成标签"))
        store.manualTags.replace(ManualTagMutation(entry.id, 1, listOf("旅行"), "tag-replaced"))
        assertFalse(store.localSearch.resolve(tagOnly, entry.id) { fail("Old manual-tag recall opened") })
        drain(store)
        assertEquals(listOf(entry.id), store.localSearch.candidates(LocalSearchRequest("旅行")).entries.map { it.id })
        store.manualTags.replace(ManualTagMutation(entry.id, 2, listOf("合成标签"), "tag-restored"))
        assertEquals(listOf(entry.id), store.localSearch.candidates(LocalSearchRequest("茶叶", tag = "合成标签")).entries.map { it.id })
        val beforeDelete = store.localSearch.candidates(LocalSearchRequest("茶叶"))
        val access = store.recycleBin.read(entry.id)
        val deleted = store.recycleBin.apply(RecycleMutation(SourceVersion(entry.id, 2), access.generation, false, "move"))
        assertTrue(store.localSearch.candidates(LocalSearchRequest("茶叶")).entries.isEmpty())
        store.recycleBin.apply(RecycleMutation(SourceVersion(entry.id, 2), deleted.generation, true, "restore"))
        assertFalse(store.localSearch.publishCurrent(beforeDelete) { fail("Visibility ABA") })
        drain(store)
        assertEquals(2, store.localSearch.candidates(LocalSearchRequest("茶叶")).entries.single().currentRevision)
    }
    private fun drain(store: EncryptedEntryRepository) {
        var attempts = 0
        do { check(++attempts <= 100); val result = store.localSearch.batch() } while (result.remaining)
    }
    private fun open() = EncryptedEntryRepository.openWithKey(context, file, key.copyOf()).also(stores::add)
}
