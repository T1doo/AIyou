package com.t1doo.aiyou.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.domain.StorageKeyUnavailable
import com.t1doo.aiyou.model.ProcessingPolicy
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on Android against native SQLCipher and generated Room, never a shadow/in-memory DB. */
@RunWith(AndroidJUnit4::class)
class EncryptedRecordsIntegrationTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var secret: ByteArray
    private val stores = mutableListOf<EncryptedEntryRepository>()
    private val aliases = mutableListOf<String>()
    private var trace: AndroidNativeTrace? = null

    @Before fun setUp() {
        trace = AndroidNativeTrace()
        trace!!.step("setup_context") { context = InstrumentationRegistry.getInstrumentation().targetContext }
        trace!!.step("setup_directory") {
            directory = File(context.noBackupFilesDir, "d02-test-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        }
        trace!!.step("setup_secret") { secret = ByteArray(32).also { SecureRandom().nextBytes(it) } }
        trace!!.step("setup_load_library") { System.loadLibrary("sqlcipher") }
    }

    @After fun tearDown() {
        try {
            trace?.step("teardown_close") { stores.forEach { it.close() } }
            aliases.forEach { alias -> KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
            if (::secret.isInitialized) secret.fill(0)
            if (::directory.isInitialized) directory.deleteRecursively() // unique owned directory only
        } finally { trace?.close() }
    }

    private fun open(name: String = "records.db", key: ByteArray = secret) =
        EncryptedEntryRepository.openWithKey(context, File(directory, name), key.copyOf()).also { stores += it }

    @Test fun encryptedDraftAndEntrySurviveCloseAndReopen() = runBlocking {
        val text = "合成记录：今天走了十分钟 🌿，原文不能被摘要覆盖。"
        var store = open()
        store.database.openHelper.writableDatabase.query("PRAGMA cipher_version").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("4.19.0", cursor.getString(0).substringBefore(' '))
        }
        val draft = store.updateDraft(text)
        assertTrue(store.entries().isEmpty())
        store.close()
        store = open()
        assertEquals(draft, store.readDraft())
        val saved = store.saveDraft(draft, 1_792_000_000_000L, "Asia/Shanghai")
        assertEquals(ProcessingPolicy.LOCAL_ONLY, saved.policy)
        assertNull(store.readDraft())
        assertEquals(1L, count(store, "local_jobs"))
        store.close()
        val bytes = File(directory, "records.db").readBytes()
        assertFalse(bytes.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(String(text.toByteArray(), Charsets.ISO_8859_1)))
        fails<android.database.sqlite.SQLiteException> {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                File(directory, "records.db").absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                database.rawQuery("SELECT originalText FROM entry_revisions", null).use { it.moveToFirst() }
            }
        }
        store = open()
        assertEquals(listOf(saved), store.entries())
        assertEquals(text, store.revisions(saved.id).single().originalText)
        assertEquals(contentHash(text), store.revisions(saved.id).single().contentHash)
        val discarded = store.updateDraft("合成明确放弃的草稿")
        // The real encrypted transaction must not delete a changed version, even with identical text.
        val newer = store.updateDraft(discarded.text)
        fails<com.t1doo.aiyou.domain.RecordConflict> { store.discardDraft(discarded) }
        assertEquals(newer, store.readDraft())
        store.discardDraft(newer)
        store.discardDraft(newer) // Idempotent acknowledgement retry after a committed delete.
        store.close()
        store = open()
        assertNull(store.readDraft())
        assertEquals(listOf(saved), store.entries())
        assertEquals(1L, count(store, "local_jobs"))
        assertEquals(1, store.revisions(saved.id).size)
        val replacement = com.t1doo.aiyou.model.TextDraft(java.util.UUID.randomUUID().toString(), 1L, newer.text)
        assertEquals(replacement, store.restoreDiscardedDraft(newer, replacement))
        store.close()
        store = open()
        assertEquals(replacement, store.readDraft())
        assertEquals(replacement, store.restoreDiscardedDraft(newer, replacement))
        fails<com.t1doo.aiyou.domain.RecordConflict> { store.discardDraft(newer) }
        store.discardDraft(replacement)
        val nextDraft = store.updateDraft("合成清空后的新草稿")
        assertNotEquals(newer.id, nextDraft.id)
        fails<com.t1doo.aiyou.domain.RecordConflict> { store.discardDraft(newer) }
        fails<com.t1doo.aiyou.domain.RecordConflict> { store.restoreDiscardedDraft(newer, replacement) }
        assertEquals(nextDraft, store.readDraft())
    }

    @Test fun actualJobInsertFailureRollsBackEntryRevisionAndDraftConsumption() = runBlocking {
        val store = open()
        val draft = store.updateDraft("合成事务失败样本")
        val sql = store.database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_local_job BEFORE INSERT ON local_jobs BEGIN SELECT RAISE(ABORT, 'injected transaction failure'); END")
        fails<Exception> { store.saveDraft(draft, 1000L, "Etc/UTC") }
        assertEquals(draft, store.readDraft())
        assertEquals(0L, count(store, "entries"))
        assertEquals(0L, count(store, "entry_revisions"))
        assertEquals(0L, count(store, "local_jobs"))
        sql.execSQL("DROP TRIGGER fail_local_job")
        assertEquals(draft.text, store.saveDraft(draft, 1000L, "Etc/UTC").originalText)
        assertEquals(1L, count(store, "entries"))
    }

    @Test fun editCreatesHistoryAndRejectsStaleRevision() = runBlocking {
        val store = open()
        val saved = store.saveDraft(store.updateDraft("合成旧原文"), 1000L, "Etc/UTC")
        val updated = store.revise(saved.id, 1, "合成修正版 🌿", 2000L)
        assertEquals(2, updated.currentRevision)
        assertEquals(listOf("合成旧原文", "合成修正版 🌿"), store.revisions(saved.id).map { it.originalText })
        fails<RecordConflict> { store.revise(saved.id, 1, "迟到编辑不能覆盖", 3000L) }
        assertEquals(updated, store.entries().single())
        assertEquals(2L, count(store, "local_jobs"))
        store.close()
        assertEquals(2, open().revisions(saved.id).size)
    }

    @Test fun editTransactionFailureRetainsCurrentAndPreviousOriginal() = runBlocking {
        val store = open()
        val saved = store.saveDraft(store.updateDraft("合成编辑前文本"), 1000L, "Etc/UTC")
        store.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_edit_job BEFORE INSERT ON local_jobs BEGIN SELECT RAISE(ABORT, 'injected edit failure'); END")
        fails<Exception> { store.revise(saved.id, 1, "不能只提交新版本", 2000L) }
        assertEquals(saved, store.entries().single())
        assertEquals(1, store.revisions(saved.id).size)
        assertEquals(1L, count(store, "local_jobs"))
    }

    @Test fun repeatedAndConcurrentSaveHaveOneDurableEntryAndJob() = runBlocking {
        val store = open()
        val draft = store.updateDraft("合成重复提交")
        val results = coroutineScope {
            val a = async { store.saveDraft(draft, 1000L, "Etc/UTC") }
            val b = async { store.saveDraft(draft, 1000L, "Etc/UTC") }
            listOf(a.await(), b.await())
        }
        assertEquals(results[0], results[1])
        store.close()
        val reopened = open()
        assertEquals(results[0], reopened.saveDraft(draft, 9000L, "Asia/Shanghai"))
        assertEquals(1L, count(reopened, "entries"))
        assertEquals(1L, count(reopened, "entry_revisions"))
        assertEquals(1L, count(reopened, "local_jobs"))
    }

    @Test fun changedDraftCannotBeCommittedFromStaleSnapshot() = runBlocking {
        val store = open()
        val old = store.updateDraft("合成草稿一")
        val fresh = store.updateDraft("合成草稿二")
        fails<RecordConflict> { store.saveDraft(old, 1000L, "Etc/UTC") }
        assertEquals(fresh, store.readDraft())
        assertTrue(store.entries().isEmpty())
    }

    @Test fun directRevisionOverwriteAndDuplicateInsertAreRejected() = runBlocking {
        val store = open()
        val saved = store.saveDraft(store.updateDraft("合成不可变证据"), 1000L, "Etc/UTC")
        val sql = store.database.openHelper.writableDatabase
        fails<Exception> { sql.execSQL("UPDATE entry_revisions SET originalText='overwrite' WHERE entryId=?", arrayOf(saved.id)) }
        fails<Exception> { store.database.records().insertRevision(RevisionEntity(saved.id, 1, "replace", contentHash("replace"), 9000L)) }
        assertEquals("合成不可变证据", store.revisions(saved.id).single().originalText)
    }

    @Test fun wrongDatabaseKeyFailsWithoutResettingExistingFile() = runBlocking {
        val store = open()
        val saved = store.saveDraft(store.updateDraft("合成错误密钥保护"), 1000L, "Etc/UTC")
        store.close()
        val file = File(directory, "records.db")
        val before = digest(file)
        fails<Exception> { open(key = ByteArray(32) { 42 }) }
        assertEquals(before, digest(file))
        assertEquals(saved, open().entries().single())
    }

    @Test fun keystoreWrapReopensAndTamperingFailsWithoutReplacement() = runBlocking {
        val alias = "aiyou.synthetic.test.${UUID.randomUUID()}".also { aliases += it }
        val file = File(directory, "wrapped.key")
        val provider = AndroidDatabaseKeyProvider(file, alias)
        val first = provider.load(false)
        assertArrayEquals(first, provider.load(true))
        assertEquals(65, file.length().toInt())
        val corrupted = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(corrupted)
        fails<StorageKeyUnavailable> { provider.load(true) }
        assertArrayEquals(corrupted, file.readBytes())
        first.fill(0)
    }

    @Test fun missingKeystoreAliasOrEnvelopeNeverCreatesReplacementKey() = runBlocking {
        val alias = "aiyou.synthetic.test.${UUID.randomUUID()}".also { aliases += it }
        val file = File(directory, "wrapped.key")
        val provider = AndroidDatabaseKeyProvider(file, alias)
        provider.load(false).fill(0)
        val envelope = file.readBytes()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        fails<StorageKeyUnavailable> { provider.load(true) }
        assertArrayEquals(envelope, file.readBytes())
        assertFalse(keyStore.containsAlias(alias))
        val absent = File(directory, "absent.key")
        fails<StorageKeyUnavailable> { AndroidDatabaseKeyProvider(absent, alias).load(true) }
        assertFalse(absent.exists())
        assertFalse(keyStore.containsAlias(alias))
    }

    @Test fun encryptedVersionOneMigrationPreservesDraftAndOriginalAndAddsLocalJob() = runBlocking {
        createVersionOne("migration.db")
        val store = open("migration.db")
        assertMigratedFixture(store)
        fails<Exception> {
            store.database.openHelper.writableDatabase.execSQL("UPDATE entry_revisions SET originalText='synthetic overwrite'")
        }
        assertMigratedFixture(store)
        store.close()
        assertMigratedFixture(open("migration.db"))
    }

    @Test fun invalidMigrationSchemaIsRejectedWithoutDestructiveFallback() = runBlocking {
        createVersionOne("invalid-migration.db", invalid = true)
        val before = versionOneSnapshot("invalid-migration.db")
        fails<Exception> { open("invalid-migration.db") }
        assertEquals("Rejected migration must preserve the complete logical schema and data", before,
            versionOneSnapshot("invalid-migration.db"))
        SQLiteDatabase.openOrCreateDatabase(File(directory, "invalid-migration.db").absolutePath, secret, null, null).use { db ->
            assertEquals(1, db.version)
            db.rawQuery("SELECT originalText FROM entry_revisions", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("合成迁移原文", cursor.getString(0))
            }
            db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='local_jobs'", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test fun fiveAcknowledgedSavesSurviveSeparateDatabaseReopens() = runBlocking {
        val observer = checkNotNull(trace)
        for (n in 1..5) {
            val store = observer.step("open", n,
                if (n == 1) "FRESH_FILE_FIRST_OPEN" else "REOPEN_SAME_PROCESS") { open() }
            val draft = observer.suspending("update_draft", n) { store.updateDraft("合成重启样本 $n 🌿") }
            observer.suspending("save_draft", n) { store.saveDraft(draft, n.toLong(), "Etc/UTC") }
            observer.step("close", n) { store.close() }
            val restarted = observer.step("open", n, "REOPEN_SAME_PROCESS") { open() }
            observer.suspending("query_count", n) { assertEquals(n, restarted.entries().size) }
            observer.suspending("query_latest", n) {
                assertEquals("合成重启样本 $n 🌿", restarted.entries().first().originalText)
            }
            observer.step("close", n) { restarted.close() }
        }
    }

    private suspend fun assertMigratedFixture(store: EncryptedEntryRepository) {
        val entry = store.entries().single()
        assertEquals("synthetic-v1", entry.id)
        assertEquals(1, entry.currentRevision)
        assertEquals("合成迁移原文", entry.originalText)
        assertEquals(1000L, entry.capturedAtMillis)
        assertEquals("Asia/Shanghai", entry.captureTimeZone)
        assertEquals(ProcessingPolicy.LOCAL_ONLY, entry.policy)
        val revision = store.revisions(entry.id).single()
        assertEquals(entry.id, revision.entryId)
        assertEquals(1, revision.revision)
        assertEquals(entry.originalText, revision.originalText)
        assertEquals(contentHash(entry.originalText), revision.contentHash)
        assertEquals(1000L, revision.createdAtMillis)
        val draft = checkNotNull(store.readDraft())
        assertEquals("synthetic-draft", draft.id)
        assertEquals(3L, draft.version)
        assertEquals("合成迁移草稿", draft.text)
        val sql = store.database.openHelper.writableDatabase
        assertEquals(5, sql.version)
        assertEquals(0L, count(store, "attachments"))
        assertEquals(0L, count(store, "remote_source_access"))
        assertEquals(0L, count(store, "remote_text_jobs"))
        assertEquals(0L, count(store, "text_annotations"))
        sql.query("SELECT id,entryId,sourceRevision,operation,state FROM local_jobs").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("synthetic-v1:1:LOCAL_INDEX", cursor.getString(0))
            assertEquals(entry.id, cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals("LOCAL_INDEX", cursor.getString(3))
            assertEquals("PENDING", cursor.getString(4))
            assertFalse(cursor.moveToNext())
        }
        sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    /** Compare logical rows/schema, not ciphertext bytes that legitimate WAL recovery can change. */
    private fun versionOneSnapshot(name: String): List<List<String?>> =
        SQLiteDatabase.openOrCreateDatabase(File(directory, name).absolutePath, secret, null, null).use { db ->
            buildList {
                add(listOf("version", db.version.toString()))
                for (query in listOf(
                    "SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name",
                    "SELECT * FROM entries ORDER BY id", "SELECT * FROM entry_revisions ORDER BY entryId,revision",
                    "SELECT * FROM capture_draft ORDER BY slot",
                )) {
                    add(listOf(query))
                    db.rawQuery(query, emptyArray()).use { cursor ->
                        while (cursor.moveToNext()) add(List(cursor.columnCount) { cursor.getString(it) })
                    }
                }
            }
        }

    private fun count(store: EncryptedEntryRepository, table: String): Long =
        store.database.openHelper.writableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }

    /** Synthetic initial D02 v1 fixture; no claim that D01 shipped a database. */
    private fun createVersionOne(name: String, invalid: Boolean = false) {
        SQLiteDatabase.openOrCreateDatabase(File(directory, name).absolutePath, secret, null, null).use { db ->
            db.execSQL("CREATE TABLE entries (id TEXT NOT NULL PRIMARY KEY, currentRevision INTEGER NOT NULL, capturedAtMillis INTEGER NOT NULL, captureTimeZone TEXT NOT NULL, privacyPolicy TEXT NOT NULL, FOREIGN KEY(id,currentRevision) REFERENCES entry_revisions(entryId,revision) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)")
            db.execSQL("CREATE INDEX index_entries_id_currentRevision ON entries(id,currentRevision)")
            db.execSQL("CREATE TABLE entry_revisions (entryId TEXT NOT NULL, revision INTEGER NOT NULL, originalText TEXT NOT NULL, contentHash TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, PRIMARY KEY(entryId,revision), FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)")
            db.execSQL("CREATE INDEX index_entry_revisions_entryId ON entry_revisions(entryId)")
            db.execSQL("CREATE TABLE capture_draft (slot INTEGER NOT NULL PRIMARY KEY, id TEXT NOT NULL, version INTEGER NOT NULL, text TEXT NOT NULL)")
            db.beginTransaction()
            try {
                db.execSQL("INSERT INTO entries VALUES ('synthetic-v1',1,1000,'Asia/Shanghai','LOCAL_ONLY')")
                db.execSQL("INSERT INTO entry_revisions VALUES ('synthetic-v1',1,?,?,1000)", arrayOf("合成迁移原文", contentHash("合成迁移原文")))
                db.execSQL("INSERT INTO capture_draft VALUES (1,'synthetic-draft',3,'合成迁移草稿')")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            if (invalid) db.execSQL("ALTER TABLE entries RENAME COLUMN captureTimeZone TO invalidTimeZone")
            db.version = 1
        }
    }

    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private suspend inline fun <reified T : Throwable> fails(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") }
        catch (failure: Throwable) { assertTrue("Unexpected exception type", failure is T) }
    }
}
