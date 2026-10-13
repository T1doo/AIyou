package com.t1doo.aiyou.data

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.domain.AttachmentConflict
import com.t1doo.aiyou.domain.AttachmentIntegrityFailure
import com.t1doo.aiyou.domain.StorageKeyUnavailable
import com.t1doo.aiyou.model.AttachmentIntent
import com.t1doo.aiyou.model.AttachmentState
import com.t1doo.aiyou.model.OriginalRevision
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.TextDraft
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Native Room/SQLCipher/Android fsync/Keystore tests; compilation is not execution. */
@RunWith(AndroidJUnit4::class)
class AttachmentRecordsIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var key: ByteArray
    private val stores = mutableListOf<EncryptedEntryRepository>()
    private val aliases = mutableListOf<String>()
    private val data = "合成附件原始内容 🌿".repeat(300).toByteArray()
    private val databaseFile get() = File(directory, "records.db")

    @Before fun before() {
        directory = File(context.noBackupFilesDir, "d02-attachment-test-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        System.loadLibrary("sqlcipher")
    }
    @After fun after() {
        stores.forEach { it.close() }; key.fill(0)
        aliases.forEach { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(it) } }
        directory.deleteRecursively() // unique owned synthetic directory
    }
    private fun open() = EncryptedEntryRepository.openWithKey(context, databaseFile, key.copyOf()).also { stores += it }
    private fun intent(entry: String, revision: Int = 1) = AttachmentIntent(UUID.randomUUID().toString(), entry, revision,
        "image/png", MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }, data.size.toLong())

    @Test fun encryptedAttachmentIsRevisionBoundAndReopensWithoutChangingTextDraftOrJobs() = runBlocking {
        var store = open()
        val original = store.saveDraft(store.updateDraft("合成原文"), 1000, "Asia/Shanghai")
        val independent = store.updateDraft("合成独立捕获草稿")
        val request = intent(original.id)
        assertEquals(AttachmentState.READY, store.attachments.save(request) { ByteArrayInputStream(data) }.state)
        assertArrayEquals(data, store.attachments.read(request.id))
        val current = store.revise(original.id, 1, "合成新版原文", 2000)
        assertTrue(store.attachments.list(original.id, 2).isEmpty())
        store.close(); store = open(); store.prepare()
        assertEquals(listOf(current), store.entries()); assertEquals(independent, store.readDraft())
        assertEquals(2, store.revisions(original.id).size)
        assertArrayEquals(data, store.attachments.read(request.id))
        store.database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        store.database.openHelper.writableDatabase.query("SELECT count(*) FROM local_jobs").use { it.moveToFirst(); assertEquals(2L, it.getLong(0)) }
        val invalid = intent(original.id, 99)
        var sourceOpened = false
        try {
            store.attachments.save(invalid) { sourceOpened = true; error("invalid revision cannot consume source") }
            fail("missing source revision must produce AttachmentConflict")
        } catch (_: AttachmentConflict) {
            // Catch only the exact domain error; any SQLCipher exception retains
            // its original stack in instrumentation output.
        }
        assertFalse("missing revision must not consume source", sourceOpened)
        assertNull("missing revision must not leave a ledger row", store.database.records().attachment(invalid.id))
        assertFalse(File(directory, "records.db.attachments/${invalid.id}.tmp").exists())
        assertFalse(File(directory, "records.db.attachments/${invalid.id}.aead").exists())
        // Exercise another real transaction on the same open database. Reopening
        // would hide a poisoned native connection and is deliberately avoided.
        store.database.withTransaction {
            assertEquals(independent, store.readDraft())
            store.database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
        assertEquals(1, store.attachments.list(original.id, 1).size)
        fails<Exception> { store.database.openHelper.writableDatabase.execSQL("UPDATE attachments SET revision=2") }
        assertEquals(request, store.attachments.list(original.id, 1).single().intent)
    }

    @Test fun failedFinalRoomTransitionPreservesPublishedBlobAndStartupRecoveryIsIdempotent() = runBlocking {
        var store = open()
        val original = store.saveDraft(store.updateDraft("合成原文"), 1000, "Etc/UTC")
        val request = intent(original.id)
        store.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_attachment_ready BEFORE UPDATE ON attachments WHEN NEW.state='READY' BEGIN SELECT RAISE(ABORT, 'synthetic commit failure'); END")
        fails<Exception> { store.attachments.save(request) { ByteArrayInputStream(data) } }
        val blob = File(directory, "records.db.attachments/${request.id}.aead")
        val preserved = blob.readBytes()
        store.database.openHelper.writableDatabase.query("SELECT state FROM attachments").use { it.moveToFirst(); assertEquals("STAGED", it.getString(0)) }
        store.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_attachment_ready")
        store.close(); store = open(); store.prepare(); store.prepare()
        assertEquals(AttachmentState.READY, store.attachments.list(original.id, 1).single().state)
        assertEquals(AttachmentState.READY, store.attachments.save(request) { error("no repeated copy") }.state)
        assertArrayEquals(preserved, blob.readBytes()); assertArrayEquals(data, store.attachments.read(request.id))
        blob.writeBytes(preserved.apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        fails<AttachmentIntegrityFailure> { store.attachments.read(request.id) }
        assertEquals(AttachmentState.CORRUPT, store.attachments.list(original.id, 1).single().state)
        assertEquals(listOf(original), store.entries())
    }

    @Test fun encryptedVersionTwoFixtureMigratesWithoutChangingOriginalsDraftAndLedger() = runBlocking {
        val id = "synthetic-v2-source"
        val oldText = "合成v2原文"
        val currentText = "合成v2更新原文"
        val updated = StoredEntry(id, 2, currentText, 1000, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY)
        val draft = TextDraft("synthetic-v2-draft", 3, "合成v2草稿")
        val revisions = listOf(OriginalRevision(id, 1, oldText, contentHash(oldText), 1000),
            OriginalRevision(id, 2, currentText, contentHash(currentText), 2000))
        // A native encrypted database from the exact exported v2 schema and v2 Room identity.
        NativeRecordsSchemaFixture.create(databaseFile, key, 2) { db ->
            db.execSQL("INSERT INTO entries VALUES(?,2,1000,'Asia/Shanghai','LOCAL_ONLY')", arrayOf(id))
            revisions.forEach { revision ->
                db.execSQL("INSERT INTO entry_revisions VALUES(?,?,?,?,?)",
                    arrayOf<Any?>(revision.entryId, revision.revision, revision.originalText, revision.contentHash, revision.createdAtMillis))
                db.execSQL("INSERT INTO local_jobs VALUES(?,?,?,'LOCAL_INDEX','PENDING')",
                    arrayOf<Any?>("$id:${revision.revision}:LOCAL_INDEX", id, revision.revision))
            }
            db.execSQL("INSERT INTO capture_draft VALUES(1,?,?,?)", arrayOf<Any?>(draft.id, draft.version, draft.text))
        }
        val migrated = open(); migrated.prepare()
        assertEquals(listOf(updated), migrated.entries()); assertEquals(revisions, migrated.revisions(id))
        assertEquals(draft, migrated.readDraft()); assertTrue(migrated.attachments.list(id, 1).isEmpty())
        NativeRecordsSchemaFixture.assertCurrentSchema(migrated.database.openHelper.writableDatabase)
        migrated.database.openHelper.writableDatabase.query("SELECT count(*) FROM local_jobs").use { it.moveToFirst(); assertEquals(2L, it.getLong(0)) }
        for (table in listOf("remote_source_access", "remote_text_jobs", "text_annotations")) {
            migrated.database.openHelper.writableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
        }
        migrated.close()
        val reopened = open(); reopened.prepare()
        assertEquals(listOf(updated), reopened.entries()); assertEquals(revisions, reopened.revisions(id))
        assertEquals(draft, reopened.readDraft()); assertTrue(reopened.attachments.list(id, 1).isEmpty())
        NativeRecordsSchemaFixture.assertCurrentSchema(reopened.database.openHelper.writableDatabase)
        reopened.database.openHelper.writableDatabase.query("SELECT count(*) FROM local_jobs").use { it.moveToFirst(); assertEquals(2L, it.getLong(0)) }
        for (table in listOf("remote_source_access", "remote_text_jobs", "text_annotations")) {
            reopened.database.openHelper.writableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
        }
        val request = intent(id)
        reopened.attachments.save(request) { ByteArrayInputStream(data) }
        assertArrayEquals(data, reopened.attachments.read(request.id))
    }

    @Test fun wrappedMediaKeyHasSeparatePurposeAndMissingMaterialNeverReplacesIt() {
        val alias = "com.t1doo.aiyou.test.attachments.${UUID.randomUUID()}".also { aliases += it }
        val file = File(directory, "attachment.key")
        val purpose = "AIyou attachment key v1".toByteArray()
        val first = AndroidDatabaseKeyProvider(file, alias, purpose).load(false)
        val encrypted = file.readBytes()
        assertArrayEquals(first, AndroidDatabaseKeyProvider(file, alias, purpose).load(true))
        failsSync<StorageKeyUnavailable> { AndroidDatabaseKeyProvider(file, alias).load(true) }
        assertArrayEquals(encrypted, file.readBytes())
        check(file.delete())
        failsSync<StorageKeyUnavailable> { AndroidDatabaseKeyProvider(file, alias, purpose).load(true) }
        assertFalse(file.exists()); first.fill(0)
    }

    private suspend inline fun <reified T : Throwable> fails(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") }
        catch (failure: Throwable) { assertTrue("Unexpected exception type", failure is T) }
    }
    private inline fun <reified T : Throwable> failsSync(block: () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") }
        catch (failure: Throwable) { assertTrue("Unexpected exception type", failure is T) }
    }
}
