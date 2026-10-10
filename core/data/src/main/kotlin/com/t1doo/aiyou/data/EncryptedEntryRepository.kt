package com.t1doo.aiyou.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.domain.RecordIntegrityFailure
import com.t1doo.aiyou.model.OriginalRevision
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.TextDraft
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId
import java.util.UUID
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Only this factory opens production records; no plaintext or destructive fallback. */
class EncryptedEntryRepository internal constructor(
    internal val database: RecordsDatabase,
    private val keyBytes: ByteArray,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : EntryRepository, AutoCloseable {
    private val dao get() = database.records()

    override suspend fun readDraft(): TextDraft? = dao.draft()?.toModel()

    override suspend fun updateDraft(text: String): TextDraft = database.withTransaction {
        require(text.length <= MAX_TEXT_LENGTH)
        val old = dao.draft()
        DraftEntity(id = old?.id ?: newId(), version = Math.addExact(old?.version ?: 0L, 1L), text = text)
            .also { dao.putDraft(it) }.toModel()
    }

    override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = database.withTransaction {
        require(expected.text.isNotBlank())
        ZoneId.of(captureTimeZone)
        dao.entry(expected.id)?.let { existing ->
            if (dao.revisions(expected.id).firstOrNull()?.contentHash != contentHash(expected.text)) throw RecordConflict()
            return@withTransaction existing.toModel()
        }
        if (dao.draft()?.toModel() != expected) throw RecordConflict()
        dao.insertEntry(EntryEntity(expected.id, 1, capturedAtMillis, captureTimeZone, ProcessingPolicy.LOCAL_ONLY.name))
        dao.insertRevision(RevisionEntity(expected.id, 1, expected.text, contentHash(expected.text), capturedAtMillis))
        dao.insertJob(LocalJobEntity("${expected.id}:1:LOCAL_INDEX", expected.id, 1))
        if (dao.consumeDraft(expected.id, expected.version) != 1) throw RecordConflict()
        checkNotNull(dao.entry(expected.id)).toModel()
    }

    override suspend fun entries(): List<StoredEntry> = dao.entries().map { it.toModel() }

    override suspend fun revisions(entryId: String): List<OriginalRevision> = dao.revisions(entryId).map {
        if (it.contentHash != contentHash(it.originalText)) throw RecordIntegrityFailure()
        OriginalRevision(it.entryId, it.revision, it.originalText, it.contentHash, it.createdAtMillis)
    }

    override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = database.withTransaction {
        require(text.isNotBlank() && text.length <= MAX_TEXT_LENGTH)
        val entry = dao.entry(entryId) ?: throw RecordConflict()
        if (entry.currentRevision != expectedRevision) throw RecordConflict()
        val next = Math.addExact(expectedRevision, 1)
        dao.insertRevision(RevisionEntity(entryId, next, text, contentHash(text), createdAtMillis))
        dao.insertJob(LocalJobEntity("$entryId:$next:LOCAL_INDEX", entryId, next))
        if (dao.advance(entryId, expectedRevision, next) != 1) throw RecordConflict()
        checkNotNull(dao.entry(entryId)).toModel()
    }

    override fun close() {
        try { database.close() } finally { keyBytes.fill(0) }
    }

    companion object {
        const val MAX_TEXT_LENGTH = 100_000

        /** Call on an IO dispatcher after device unlock; an unavailable key is recoverable UI failure. */
        fun open(context: Context): EncryptedEntryRepository {
            val file = File(context.noBackupFilesDir, "records/records.db")
            val keyFile = File(context.noBackupFilesDir, "keys/database.key")
            val existing = file.exists() || File(file.path + "-wal").exists() || File(file.path + "-shm").exists()
            return openWithKey(context, file, AndroidDatabaseKeyProvider(keyFile, DATABASE_KEY_ALIAS).load(existing))
        }

        internal const val DATABASE_KEY_ALIAS = "com.t1doo.aiyou.database.wrap.v1"

        internal fun openWithKey(context: Context, file: File, key: ByteArray): EncryptedEntryRepository {
            var database: RecordsDatabase? = null
            var opened = false
            try {
                require(key.size == 32)
                System.loadLibrary("sqlcipher")
                check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
                val created = Room.databaseBuilder(context.applicationContext, RecordsDatabase::class.java, file.absolutePath)
                .openHelperFactory(SupportOpenHelperFactory(key))
                .addMigrations(recordsMigration1To2)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) { installRevisionGuard(db) }
                    override fun onOpen(db: SupportSQLiteDatabase) { installRevisionGuard(db) }
                }).build()
                database = created
                created.openHelper.writableDatabase // authenticate/schema validation before reporting ready
                return EncryptedEntryRepository(created, key).also { opened = true }
            } finally {
                if (!opened) {
                    try { database?.close() } finally { key.fill(0) }
                }
            }
        }
    }
}

private fun DraftEntity.toModel() = TextDraft(id, version, text)
private fun EntryRow.toModel(): StoredEntry {
    if (contentHash != contentHash(originalText) || privacyPolicy != ProcessingPolicy.LOCAL_ONLY.name) throw RecordIntegrityFailure()
    try { ZoneId.of(captureTimeZone) } catch (_: Exception) { throw RecordIntegrityFailure() }
    if (currentRevision < 1) throw RecordIntegrityFailure()
    return StoredEntry(id, currentRevision, originalText, capturedAtMillis, captureTimeZone, ProcessingPolicy.LOCAL_ONLY)
}
internal fun contentHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
