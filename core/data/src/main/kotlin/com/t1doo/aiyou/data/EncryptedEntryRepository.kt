package com.t1doo.aiyou.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.AttachmentStore
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.domain.RecordIntegrityFailure
import com.t1doo.aiyou.model.OriginalRevision
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.TextDraft
import com.t1doo.aiyou.model.OriginalEditDraft
import com.t1doo.aiyou.model.OriginalEditDraftState
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId
import java.util.UUID
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Only this factory opens production records; no plaintext or destructive fallback. */
class EncryptedEntryRepository internal constructor(
    internal val database: RecordsDatabase,
    private val keyBytes: ByteArray,
    private val attachmentStorage: EncryptedAttachmentStore,
    private val originalEditStorage: EncryptedOriginalEditStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : EntryRepository, AutoCloseable {
    private val dao get() = database.records()
    override val attachments: AttachmentStore get() = attachmentStorage
    override val editDrafts: EncryptedOriginalEditStore get() = originalEditStorage
    /** Blocking IO; shares the existing encrypted database and never enables remote processing. */
    private val borrowedSql = DrainingRecordsSqlDatabase(RoomRecordsSqlDatabase(database))
    override val sourceBoundText: RoomSourceBoundTextRepository = RoomSourceBoundTextRepository(borrowedSql)
    private val tagStorage = SqlManualTagRepository(borrowedSql, keyBytes)
    override val manualTags: com.t1doo.aiyou.domain.ManualTagRepository get() = tagStorage
    private val taskStorage = SqlManualTaskRepository(borrowedSql, keyBytes)
    override val manualTasks: com.t1doo.aiyou.domain.ManualTaskRepository get() = taskStorage
    override val originalExport: com.t1doo.aiyou.domain.OriginalExportRepository =
        SqlOriginalExportRepository(borrowedSql, tagStorage)

    override suspend fun prepare() { attachmentStorage.recover() }

    override suspend fun readDraft(): TextDraft? = dao.draft()?.toModel()

    override suspend fun updateDraft(text: String): TextDraft = database.withTransaction {
        require(text.length <= MAX_TEXT_LENGTH)
        val old = dao.draft()
        DraftEntity(id = old?.id ?: newId(), version = Math.addExact(old?.version ?: 0L, 1L), text = text)
            .also { dao.putDraft(it) }.toModel()
    }

    override suspend fun discardDraft(expected: TextDraft): Unit = database.withTransaction {
        val current = dao.draft()?.toModel() ?: return@withTransaction
        if (current != expected || dao.consumeDraft(expected.id, expected.version) != 1) throw RecordConflict()
    }

    override suspend fun restoreDiscardedDraft(expected: TextDraft, replacement: TextDraft): TextDraft = database.withTransaction {
        require(replacement.id != expected.id && replacement.version == 1L &&
            replacement.text == expected.text && replacement.text.length <= MAX_TEXT_LENGTH)
        val current = dao.draft()?.toModel()
        if (current == expected || current == replacement) return@withTransaction checkNotNull(current)
        if (current != null) throw RecordConflict()
        dao.putDraft(DraftEntity(id = replacement.id, version = replacement.version, text = replacement.text))
        checkNotNull(dao.draft()).toModel()
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

    override suspend fun reviseFromDraft(draft: OriginalEditDraft, createdAtMillis: Long): StoredEntry = database.withTransaction {
        require(draft.state == OriginalEditDraftState.ACTIVE)
        committedEdit(draft)?.let { return@withTransaction it }
        // Require the full independently acknowledged encrypted snapshot, not only its text.
        if (editDrafts.read(draft.entryId) != draft) throw com.t1doo.aiyou.domain.OriginalEditDraftConflict()
        val saved = revise(draft.entryId, draft.baseRevision, draft.text, createdAtMillis)
        // Completed receipt, never runnable work: same encrypted transaction as revision + LOCAL_INDEX.
        dao.insertJob(LocalJobEntity(editReceiptId(draft), draft.entryId, saved.currentRevision,
            "ORIGINAL_EDIT_COMMIT", "CONFIRMED"))
        saved
    }

    override suspend fun committedEdit(draft: OriginalEditDraft): StoredEntry? = database.withTransaction {
        val receipt = dao.job(editReceiptId(draft)) ?: return@withTransaction null
        val next = Math.addExact(draft.baseRevision, 1)
        if (receipt.entryId != draft.entryId || receipt.sourceRevision != next ||
            receipt.operation != "ORIGINAL_EDIT_COMMIT" || receipt.state != "CONFIRMED") throw RecordIntegrityFailure()
        val current = dao.entry(draft.entryId)?.toModel() ?: throw RecordIntegrityFailure()
        val revision = dao.revisions(draft.entryId).singleOrNull { it.revision == next } ?: throw RecordIntegrityFailure()
        if (revision.originalText != draft.text || revision.contentHash != contentHash(draft.text) ||
            current.currentRevision < next) throw RecordIntegrityFailure()
        current.copy(currentRevision = next, originalText = revision.originalText)
    }

    override fun close() {
        borrowedSql.stopAndAwaitIdle()
        try { database.close() } finally { attachmentStorage.close(); originalEditStorage.close(); tagStorage.wipeKey(); taskStorage.wipeKey(); keyBytes.fill(0) }
    }

    companion object {
        const val MAX_TEXT_LENGTH = 100_000

        /** Call on an IO dispatcher after device unlock; an unavailable key is recoverable UI failure. */
        fun open(context: Context): EncryptedEntryRepository = openProtected(
            context.noBackupFilesDir,
            loadKey = { purpose, existing ->
                AndroidDatabaseKeyProvider(File(context.noBackupFilesDir, "keys/${purpose.fileName}"),
                    DATABASE_KEY_ALIAS, purpose.associatedData).load(existing)
            },
        ) { file, key, editKey, attachmentKey ->
            openWithKey(context, file, key, editKey = { editKey() }) { database ->
                val existingRows = database.openHelper.writableDatabase.query("SELECT count(*) FROM attachments").use {
                    check(it.moveToFirst())
                    it.getLong(0) > 0
                }
                attachmentKey(existingRows)
            }
        }

        /** Production opening sequence; JVM tests inject only IO/key/native-DB boundaries. */
        internal fun <T> openProtected(
            root: File,
            listFiles: (File) -> Array<File>? = { it.listFiles() },
            evidence: CiphertextEvidence = CiphertextEvidence(listFiles),
            loadKey: (RepositoryKeyPurpose, Boolean) -> ByteArray,
            openDatabase: (File, ByteArray, () -> ByteArray, (Boolean) -> ByteArray) -> T,
        ): T {
            val file = File(root, "records/records.db")
            val before = evidence.inspect(root)
            var checkpoint = before
            val key = loadKey(RepositoryKeyPurpose.DATABASE, checkNotNull(before.required[RepositoryKeyPurpose.DATABASE]))
            try {
                return openDatabase(file, key, {
                    val current = evidence.inspect(root, checkpoint).also { checkpoint = it }
                    loadKey(RepositoryKeyPurpose.EDITS, checkNotNull(current.required[RepositoryKeyPurpose.EDITS]))
                }, { existingRows ->
                    val current = evidence.inspect(root, checkpoint).also { checkpoint = it }
                    loadKey(RepositoryKeyPurpose.ATTACHMENTS,
                        checkNotNull(current.required[RepositoryKeyPurpose.ATTACHMENTS]) || existingRows)
                })
            } catch (failure: Throwable) {
                key.fill(0)
                throw failure
            }
        }

        internal const val DATABASE_KEY_ALIAS = "com.t1doo.aiyou.database.wrap.v1"

        /** Tests supply synthetic keys; the production factory supplies an independently wrapped media key. */
        internal fun openWithKey(context: Context, file: File, key: ByteArray,
            editKey: (RecordsDatabase) -> ByteArray = {
                MessageDigest.getInstance("SHA-256").digest("AIyou synthetic edit test key v1".toByteArray(Charsets.US_ASCII) + key)
            },
            attachmentKey: (RecordsDatabase) -> ByteArray = { key.copyOf() },
        ): EncryptedEntryRepository {
            var database: RecordsDatabase? = null
            var storage: EncryptedAttachmentStore? = null
            var edits: EncryptedOriginalEditStore? = null
            var opened = false
            try {
                require(key.size == 32)
                System.loadLibrary("sqlcipher")
                check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
                val created = Room.databaseBuilder(context.applicationContext, RecordsDatabase::class.java, file.absolutePath)
                .openHelperFactory(SupportOpenHelperFactory(key))
                .addMigrations(recordsMigration1To2, recordsMigration2To3, recordsMigration3To4, recordsMigration4To5, recordsMigration5To6)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) { installRevisionGuard(db); installAttachmentGuard(db); installRemoteTextGuards(db); installManualTagGuard(db); installManualTaskGuard(db) }
                    override fun onOpen(db: SupportSQLiteDatabase) { installRevisionGuard(db); installAttachmentGuard(db); installRemoteTextGuards(db); installManualTagGuard(db); installManualTaskGuard(db) }
                }).build()
                database = created
                created.openHelper.writableDatabase // authenticate/schema validation before reporting ready
                val mediaKey = attachmentKey(created)
                try {
                    require(mediaKey.size == 32)
                    storage = EncryptedAttachmentStore(File(file.parentFile, file.name + ".attachments"), RoomAttachmentLedger(created), mediaKey)
                } catch (failure: Exception) { mediaKey.fill(0); throw failure }
                val draftKey = editKey(created)
                try { edits = EncryptedOriginalEditStore(File(file.parentFile, file.name + ".original-edits"), draftKey) }
                catch (failure: Exception) { draftKey.fill(0); throw failure }
                return EncryptedEntryRepository(created, key, checkNotNull(storage), checkNotNull(edits)).also { opened = true }
            } finally {
                if (!opened) {
                    try { database?.close() } finally { storage?.close(); edits?.close(); key.fill(0) }
                }
            }
        }
    }
}

private fun DraftEntity.toModel() = TextDraft(id, version, text)
private fun EntryRow.toModel(): StoredEntry {
    if (contentHash != contentHash(originalText)) throw RecordIntegrityFailure()
    val policy = try { ProcessingPolicy.valueOf(privacyPolicy) } catch (_: IllegalArgumentException) { throw RecordIntegrityFailure() }
    try { ZoneId.of(captureTimeZone) } catch (_: Exception) { throw RecordIntegrityFailure() }
    if (currentRevision < 1) throw RecordIntegrityFailure()
    return StoredEntry(id, currentRevision, originalText, capturedAtMillis, captureTimeZone, policy)
}
internal fun contentHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Only a digest enters the encrypted receipt ID; exact UTF-16 snapshot including unfinished IME units. */
private fun editReceiptId(draft: OriginalEditDraft): String {
    val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
        out.writeUTF("AIyou original edit receipt v1")
        out.writeUTF(draft.id); out.writeUTF(draft.entryId)
        out.writeInt(draft.baseRevision); out.writeInt(draft.sourceRevision); out.writeLong(draft.version)
        for (text in listOf(draft.originalText, draft.text)) { out.writeInt(text.length); text.forEach { out.writeChar(it.code) } }
    } }.toByteArray()
    return try { "ORIGINAL_EDIT_COMMIT:" + MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) } } finally { bytes.fill(0) }
}

internal enum class RepositoryKeyPurpose(val fileName: String, private val purpose: String) {
    DATABASE("database.key", "AIyou database key v1"),
    EDITS("original-edits.key", "AIyou original edit key v1"),
    ATTACHMENTS("attachments.key", "AIyou attachment key v1");
    val associatedData: ByteArray get() = purpose.toByteArray(Charsets.US_ASCII)
}
