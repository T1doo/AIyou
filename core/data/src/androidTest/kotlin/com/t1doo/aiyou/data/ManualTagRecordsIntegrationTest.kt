package com.t1doo.aiyou.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Formal Room/SQLCipher contracts. DEX compilation does not establish native PASS. */
@RunWith(AndroidJUnit4::class)
class ManualTagRecordsIntegrationTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private val key = ByteArray(32) { (it + 29).toByte() }
    private val stores = mutableListOf<EncryptedEntryRepository>()
    private val file get() = File(directory, "records.db")
    @Before fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.noBackupFilesDir, "manual-tags-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        System.loadLibrary("sqlcipher")
    }
    @After fun after() { try { stores.forEach { it.closeAndAwait() } } finally { key.fill(0); if (::directory.isInitialized) directory.deleteRecursively() } }
    @Test fun freshVersionNineEncryptsManualTagsAndAuthenticatesAcrossReopen(): Unit = runBlocking {
        val store = open(); val entry = seed(store)
        NativeRecordsSchemaFixture.assertCurrentSchema(store.database.openHelper.writableDatabase)
        val tags = store.manualTags
        tags.replace(ManualTagMutation(entry.id, 0, listOf("合成私密手工标签🙂e\u0301"), "one"))
        store.closeAndAwait(); val reopened = open()
        assertEquals(listOf("合成私密手工标签🙂e\u0301"), reopened.manualTags.read(entry.id).names)
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("合成私密手工标签"))
        val sql = reopened.database.openHelper.writableDatabase
        val bytes = sql.query("SELECT ciphertext FROM entry_manual_tags").use { check(it.moveToFirst()); it.getBlob(0) }
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        sql.execSQL("UPDATE entry_manual_tags SET version=version+1,ciphertext=? WHERE entryId=?", arrayOf(bytes, entry.id))
        assertThrows(ManualTagStorageFailure::class.java) { reopened.manualTags.read(entry.id) }
        assertThrows(ManualTagStorageFailure::class.java) { reopened.manualTags.replace(ManualTagMutation(entry.id, 2, listOf("replacement"), "bad")) }
    }
    @Test fun encryptedVersionFourMigrationPreservesOriginalDraftAndJobsWithEmptyTags(): Unit = runBlocking {
        val text = "合成迁移原文🙂"
        NativeRecordsSchemaFixture.create(file, key, 4) { sql ->
            sql.execSQL("INSERT INTO entries VALUES('legacy',1,55,'UTC','LOCAL_ONLY')")
            sql.execSQL("INSERT INTO entry_revisions VALUES('legacy',1,?,?,55)", arrayOf(text, hash(text)))
            sql.execSQL("INSERT INTO capture_draft VALUES(1,'draft',7,'合成迁移草稿')")
            sql.execSQL("INSERT INTO local_jobs VALUES('job','legacy',1,'LOCAL_INDEX','PENDING')")
        }
        val migrated = open(); NativeRecordsSchemaFixture.assertCurrentSchema(migrated.database.openHelper.writableDatabase)
        assertEquals(text, migrated.entries().single().originalText); assertEquals(55L, migrated.entries().single().capturedAtMillis)
        assertEquals("合成迁移草稿", migrated.readDraft()!!.text); assertEquals(7L, migrated.readDraft()!!.version)
        assertEquals(0L, migrated.manualTags.read("legacy").version)
        migrated.manualTags.replace(ManualTagMutation("legacy", 0, listOf("手工"), "migration"))
        migrated.closeAndAwait(); val reopened = open()
        assertEquals(listOf("手工"), reopened.manualTags.read("legacy").names)
        assertEquals(text, reopened.entries().single().originalText); assertEquals(1, reopened.revisions("legacy").size)
        reopened.database.openHelper.writableDatabase.query("SELECT state FROM local_jobs").use { assertTrue(it.moveToFirst()); assertEquals("PENDING", it.getString(0)); assertFalse(it.moveToNext()) }
    }
    @Test fun independentRoomsCasAndActualInsertAbortPreserveOriginal(): Unit = runBlocking {
        val first = open(); val entry = seed(first); val second = open()
        val sql = first.database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER synthetic_tag_abort AFTER INSERT ON entry_manual_tags BEGIN SELECT RAISE(ABORT,'synthetic abort'); END")
        assertThrows(ManualTagStorageFailure::class.java) { first.manualTags.replace(ManualTagMutation(entry.id, 0, listOf("abort"), "abort")) }
        assertEquals(0L, second.manualTags.read(entry.id).version); sql.execSQL("DROP TRIGGER synthetic_tag_abort")
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        try {
            val outcomes = listOf(first, second).mapIndexed { i, store -> pool.submit<String> {
                go.await(); try { store.manualTags.replace(ManualTagMutation(entry.id, 0, listOf("tag-$i"), "race-$i")); "saved" }
                catch (_: ManualTagConflict) { "conflict" }
            } }; go.countDown()
            assertEquals(listOf("conflict", "saved"), outcomes.map { it.get(2, TimeUnit.SECONDS) }.sorted())
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
        assertEquals(1L, first.manualTags.read(entry.id).version); assertEquals(entry.originalText, first.entries().single().originalText)
        assertEquals(1, first.revisions(entry.id).size)
    }
    @Test fun replayEmptySetAndTombstoneRespectIndependentTagVersionAndOwnerClose(): Unit = runBlocking {
        val store = open(); val entry = seed(store); val intent = ManualTagMutation(entry.id, 0, listOf("手工"), "stable")
        store.manualTags.replace(intent); assertEquals(1L, store.manualTags.replace(intent).version)
        val revised = store.revise(entry.id, 1, "合成新版正文", 99)
        assertEquals(listOf("手工"), store.manualTags.read(entry.id).names)
        store.manualTags.replace(ManualTagMutation(entry.id, 1, emptyList(), "empty"))
        assertThrows(ManualTagConflict::class.java) { store.manualTags.replace(intent) }
        assertEquals(2L, store.manualTags.read(entry.id).version)
        val sql = store.database.openHelper.writableDatabase
        sql.execSQL("INSERT INTO remote_source_access(entryId,generation,deleted,connection,consent) VALUES(?,1,1,NULL,NULL) ON CONFLICT(entryId) DO UPDATE SET generation=generation+1,deleted=1", arrayOf(entry.id))
        assertThrows(ManualTagUnavailable::class.java) { store.manualTags.read(entry.id) }
        assertEquals(2, revised.currentRevision); assertTrue(store.manualTags.snapshots().isEmpty())
        store.closeAndAwait(); store.closeAndAwait()
        assertThrows(ManualTagStorageFailure::class.java) { store.manualTags.snapshots() }
        val reopened = open(); assertTrue(reopened.entries().isEmpty()); assertTrue(reopened.manualTags.snapshots().isEmpty())
    }
    private fun open() = EncryptedEntryRepository.openWithKey(context, file, key.copyOf()).also(stores::add)
    private suspend fun seed(store: EncryptedEntryRepository): StoredEntry {
        val draft = store.updateDraft("合成正式原文🙂"); return store.saveDraft(draft, 55, "UTC")
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
