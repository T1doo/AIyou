package com.t1doo.aiyou.data

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.domain.AttachmentConflict
import com.t1doo.aiyou.domain.AttachmentIntegrityFailure
import com.t1doo.aiyou.domain.StorageKeyUnavailable
import com.t1doo.aiyou.model.AttachmentIntent
import com.t1doo.aiyou.model.AttachmentState
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
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
        val initial = open()
        val original = initial.saveDraft(initial.updateDraft("合成v2原文"), 1000, "Asia/Shanghai")
        val updated = initial.revise(original.id, 1, "合成v2更新原文", 2000)
        val draft = initial.updateDraft("合成v2草稿")
        val revisions = initial.revisions(original.id)
        initial.close()
        // Synthetic v2 fixture: exact generated v3 four text tables, excluding the new empty table.
        SQLiteDatabase.openOrCreateDatabase(databaseFile.path, key, null, null).use { db ->
            db.execSQL("DROP TABLE attachments")
            db.execSQL("DELETE FROM room_master_table")
            db.version = 2
        }
        val migrated = open(); migrated.prepare()
        assertEquals(listOf(updated), migrated.entries()); assertEquals(revisions, migrated.revisions(original.id))
        assertEquals(draft, migrated.readDraft()); assertTrue(migrated.attachments.list(original.id, 1).isEmpty())
        migrated.database.openHelper.writableDatabase.query("SELECT count(*) FROM local_jobs").use { it.moveToFirst(); assertEquals(2L, it.getLong(0)) }
        assertEquals(3, migrated.database.openHelper.writableDatabase.version)
        val request = intent(original.id)
        migrated.attachments.save(request) { ByteArrayInputStream(data) }
        assertArrayEquals(data, migrated.attachments.read(request.id))
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
