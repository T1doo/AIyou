package com.t1doo.aiyou.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.AttachmentStore
import com.t1doo.aiyou.domain.AwaitableRecordStorage
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.domain.RecordIntegrityFailure
import com.t1doo.aiyou.model.OriginalRevision
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.ProcessingDefaults
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
    private val borrowedSql: DrainingRecordsSqlDatabase = DrainingRecordsSqlDatabase(RoomRecordsSqlDatabase(database)),
    private val newCapturePolicy: () -> ProcessingPolicy = { ProcessingDefaults.policy },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : EntryRepository, AwaitableRecordStorage {
    private val sourceGate=com.t1doo.aiyou.domain.CaptureDraftGate().also { attachmentStorage.installSourceGate(it) }
    private val dao get() = database.records()
    private val connectionStorage = SqlModelConnectionCatalog(borrowedSql,keyBytes)
    override val modelConnections: com.t1doo.aiyou.domain.ModelConnectionCatalog = connectionStorage
    override val modelCapabilities: com.t1doo.aiyou.domain.ModelCapabilityRepository = SqlModelCapabilityRepository(borrowedSql,connectionStorage)
    override val localModelConnections = com.t1doo.aiyou.domain.LocalModelConnectionServices(connectionStorage,
        RemoteTextTransactions(borrowedSql,connectionCurrent=connectionStorage::connectionCurrent,captureGate=sourceGate))
    override val localProcessing: com.t1doo.aiyou.domain.LocalProcessingRepository = SqlLocalProcessingRepository(borrowedSql,sourceGate)
    override val sourceTasks: com.t1doo.aiyou.domain.SourceTaskRepository by lazy { SqlSourceTaskRepository(borrowedSql, taskStorage) }
    override val attachments: AttachmentStore = OwnerAttachmentStore(attachmentStorage,sourceGate)
    override val editDrafts: EncryptedOriginalEditStore get() = originalEditStorage
    /** Blocking IO; shares the existing encrypted database and never enables remote processing. */
    override val sourceBoundText: RoomSourceBoundTextRepository = RoomSourceBoundTextRepository(borrowedSql,sourceGate)
    override val recordAuthorizations: com.t1doo.aiyou.domain.RecordAuthorizationRepository = SqlRecordAuthorizationRepository(borrowedSql,connectionStorage,modelCapabilities,sourceGate)
    private val questionAuthorizationStorage = SqlSelectedQuestionAuthorizationRepository(borrowedSql,sourceGate)
    override val selectedQuestionAuthorizations: com.t1doo.aiyou.domain.SelectedQuestionAuthorizationRepository
        get() = questionAuthorizationStorage
    private val candidateStorage = SqlLocalCandidateRepository(borrowedSql, keyBytes)
    override val localCandidates: com.t1doo.aiyou.domain.LocalCandidateRepository get() = candidateStorage
    private val tagStorage = SqlManualTagRepository(borrowedSql, keyBytes)
    private val peopleStorage = SqlManualPeopleRepository(borrowedSql, keyBytes)
    override val manualPeople: com.t1doo.aiyou.domain.ManualPeopleRepository get() = peopleStorage
    override val factRecaps: com.t1doo.aiyou.domain.FactRecapRepository = SqlFactRecapRepository(borrowedSql, tagStorage)
    override val sourceTags: com.t1doo.aiyou.domain.SourceTagRepository = SqlSourceTagRepository(borrowedSql, tagStorage)
    private val sourceMemoryStorage = SqlSourceMemoryRepository(borrowedSql, keyBytes)
    private val transcriptStorage=SqlAudioTranscriptRepository(borrowedSql,keyBytes,attachments,sourceGate)
    private val imageTextStorage=SqlImageTextRepository(borrowedSql,keyBytes,attachments,sourceGate)
    override val imageTexts:com.t1doo.aiyou.domain.ImageTextRepository get()=imageTextStorage
    override val audioTranscripts:com.t1doo.aiyou.domain.AudioTranscriptRepository get()=transcriptStorage
    private val preferenceStorage = SqlSourcePreferenceRepository(borrowedSql, keyBytes)
    override val sourcePreferences: com.t1doo.aiyou.domain.SourcePreferenceRepository get() = preferenceStorage
    private val captureStorage=SqlCaptureRepository(borrowedSql,attachmentStorage,tagStorage,newCapturePolicy,sourceGate)
    override val captureRecords: com.t1doo.aiyou.domain.CaptureRepository get()=captureStorage
    override val sourceMemories: com.t1doo.aiyou.domain.SourceMemoryRepository get() = sourceMemoryStorage
    override val favorites: com.t1doo.aiyou.domain.RecordFavoriteRepository = SqlRecordFavoriteRepository(borrowedSql)
    override val eventDates: com.t1doo.aiyou.domain.RecordEventDateRepository = SqlRecordEventDateRepository(borrowedSql)
    override val manualTags: com.t1doo.aiyou.domain.ManualTagRepository get() = tagStorage
    private val localSearchStorage =
        SqlLocalSearchRepository(borrowedSql, tagStorage, people = peopleStorage)
    override val localSearch:com.t1doo.aiyou.domain.LocalSearchRepository get()=localSearchStorage
    override val unifiedKeywords:com.t1doo.aiyou.domain.UnifiedKeywordRepository by lazy {
        SqlUnifiedKeywordRepository(borrowedSql,localSearchStorage,transcriptStorage,imageTextStorage,preferenceStorage,sourceMemoryStorage,tagStorage)
    }
    private val reminderStores=ReminderRuntimeStores(borrowedSql,keyBytes)
    private val taskStorage = reminderStores.tasks
    override val manualTasks: com.t1doo.aiyou.domain.ManualTaskRepository get() = taskStorage
    override val reminders: com.t1doo.aiyou.domain.TaskReminderRepository get() = taskStorage
    override val reminderRuntime = reminderStores.resources
    private val proposalStorage = SqlTaskProposalRepository(borrowedSql,keyBytes,taskStorage)
    override val taskProposals: com.t1doo.aiyou.domain.TaskProposalRepository get() = proposalStorage
    private val packageActivities=SqlRecordPackageActivities(borrowedSql,taskStorage,proposalStorage)
    override val originalExport: com.t1doo.aiyou.domain.OriginalExportRepository =
        SqlOriginalExportRepository(borrowedSql, tagStorage,packageActivities,peopleStorage)
    private val packageStorage = SqlRecordPackageRepository(borrowedSql, tagStorage,activities=packageActivities, people=peopleStorage,captureGate=captureStorage.mutationGate)
    private val mediaPackageStorage =
        SqlRecordMediaPackageRepository(borrowedSql, originalExport, attachmentStorage, packageStorage,packageActivities, originalEditStorage,captureGate=captureStorage.mutationGate)
    override val mediaPackages: com.t1doo.aiyou.domain.RecordMediaPackageRepository get() = mediaPackageStorage
    override val fullBackups: com.t1doo.aiyou.domain.FullBackupRepository =
        SqlFullBackupRepository(borrowedSql,keyBytes,attachmentStorage,originalEditStorage,mediaPackageStorage,captureGate=captureStorage.mutationGate)
    override val recordPackages: com.t1doo.aiyou.domain.RecordPackageRepository get() = packageStorage
    override val permanentErasure: com.t1doo.aiyou.domain.PermanentErasureRepository =
        SqlPermanentErasureRepository(borrowedSql, attachmentStorage, originalEditStorage, packageStorage, proposalStorage::readAllWithin,captureGate=captureStorage.mutationGate)
    override val recycleBin: com.t1doo.aiyou.domain.RecycleBinRepository =
        SqlRecycleBinRepository(borrowedSql, tagStorage, SqlErasureFileHooks::fenced,sourceGate)

    override suspend fun prepare() = sourceGate.sourceMutation {
        // Bounded resume before ordinary media recovery. A failed deletion remains visible for retry.
        resumePermanentErasure(permanentErasure)
        captureRecords.recover()
        mediaPackages.recover()
        attachmentStorage.recover()
    }

    override suspend fun readDraft(): TextDraft? = dao.draft()?.toModel()

    override suspend fun updateDraft(text: String): TextDraft = captureStorage.mutationGate.mutation { database.withTransaction {
        require(text.length <= MAX_TEXT_LENGTH)
        val old = dao.draft()
        DraftEntity(id = old?.id ?: newId(), version = Math.addExact(old?.version ?: 0L, 1L), text = text)
            .also { dao.putDraft(it) }.toModel()
    } }

    override suspend fun discardDraft(expected: TextDraft): Unit = captureStorage.mutationGate.mutation { database.withTransaction {
        val current = dao.draft()?.toModel() ?: return@withTransaction
        if (current != expected || dao.consumeDraft(expected.id, expected.version) != 1) throw RecordConflict()
    } }

    override suspend fun restoreDiscardedDraft(expected: TextDraft, replacement: TextDraft): TextDraft = captureStorage.mutationGate.mutation { database.withTransaction {
        require(replacement.id != expected.id && replacement.version == 1L &&
            replacement.text == expected.text && replacement.text.length <= MAX_TEXT_LENGTH)
        val current = dao.draft()?.toModel()
        if (current == expected || current == replacement) return@withTransaction checkNotNull(current)
        if (current != null) throw RecordConflict()
        dao.putDraft(DraftEntity(id = replacement.id, version = replacement.version, text = replacement.text))
        checkNotNull(dao.draft()).toModel()
    } }

    override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = captureStorage.mutationGate.mutation { database.withTransaction {
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
    } }

    override suspend fun entries(): List<StoredEntry> = dao.entries().map { it.toModel() }

    override suspend fun revisions(entryId: String): List<OriginalRevision> = dao.revisions(entryId).map {
        if (it.contentHash != contentHash(it.originalText)) throw RecordIntegrityFailure()
        OriginalRevision(it.entryId, it.revision, it.originalText, it.contentHash, it.createdAtMillis)
    }

    override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry =
        sourceGate.sourceMutation { reviseOwned(entryId,expectedRevision,text,createdAtMillis) }
    private suspend fun reviseOwned(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = database.withTransaction {
        require(text.isNotBlank() && text.length <= MAX_TEXT_LENGTH)
        val entry = dao.entry(entryId) ?: throw RecordConflict()
        if (entry.currentRevision != expectedRevision) throw RecordConflict()
        val next = Math.addExact(expectedRevision, 1)
        dao.insertRevision(RevisionEntity(entryId, next, text, contentHash(text), createdAtMillis))
        dao.insertJob(LocalJobEntity("$entryId:$next:LOCAL_INDEX", entryId, next))
        if (dao.advance(entryId, expectedRevision, next) != 1) throw RecordConflict()
        checkNotNull(dao.entry(entryId)).toModel()
    }

    override suspend fun reviseFromDraft(draft: OriginalEditDraft, createdAtMillis: Long): StoredEntry =
        sourceGate.sourceMutation { reviseFromDraftOwned(draft,createdAtMillis) }
    private suspend fun reviseFromDraftOwned(draft: OriginalEditDraft, createdAtMillis: Long): StoredEntry {
        require(draft.state == OriginalEditDraftState.ACTIVE)
        committedEdit(draft)?.let { return it }
        return originalEditStorage.packageFiles { lease ->
            committedEdit(draft)?.let { return@packageFiles it }
            if (lease.read(draft.entryId) != draft) throw com.t1doo.aiyou.domain.OriginalEditDraftConflict()
            database.withTransaction {
                committedEdit(draft)?.let { return@withTransaction it }
                val saved = revise(draft.entryId, draft.baseRevision, draft.text, createdAtMillis)
                // Completed receipt, never runnable work: same encrypted transaction as revision + LOCAL_INDEX.
                dao.insertJob(LocalJobEntity(editReceiptId(draft), draft.entryId, saved.currentRevision,
                    "ORIGINAL_EDIT_COMMIT", "CONFIRMED"))
                saved
            }
        }
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

    private val lifetime = DrainingRecordsStorage(borrowedSql,attachmentStorage,originalEditStorage,
        { database.close() }, { reminderStores.close();tagStorage.wipeKey(); peopleStorage.wipeKey(); proposalStorage.wipeKey(); candidateStorage.wipeKey(); sourceMemoryStorage.wipeKey(); preferenceStorage.wipeKey();transcriptStorage.wipeKey();imageTextStorage.wipeKey(); keyBytes.fill(0) })
    override fun close() { sourceGate.retire();connectionStorage.close(); questionAuthorizationStorage.close(); lifetime.close() }
    override fun awaitClosed() { close(); lifetime.awaitClosed() }
    /** Known external IO owner convenience, including native cold-reopen fixtures. */
    fun closeAndAwait() { close(); awaitClosed() }

    companion object {
        const val MAX_TEXT_LENGTH = 100_000

        /** Call on an IO dispatcher after device unlock; an unavailable key is recoverable UI failure. */
        fun open(context: Context, newCapturePolicy: () -> ProcessingPolicy = { ProcessingDefaults.policy }): EncryptedEntryRepository = openProtected(
            context.noBackupFilesDir,
            loadKey = { purpose, existing ->
                AndroidDatabaseKeyProvider(File(context.noBackupFilesDir, "keys/${purpose.fileName}"),
                    DATABASE_KEY_ALIAS, purpose.associatedData).load(existing)
            },
        ) { file, key, editKey, attachmentKey ->
            openWithKey(context, file, key, editKey = { editKey() }, newCapturePolicy = newCapturePolicy) { database ->
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
                if(failure !is com.t1doo.aiyou.domain.ReminderRuntimeClosureUnconfirmed)key.fill(0)
                throw failure
            }
        }

        internal const val DATABASE_KEY_ALIAS = "com.t1doo.aiyou.database.wrap.v1"

        /** Tests supply synthetic keys; the production factory supplies an independently wrapped media key. */
        internal fun openWithKey(context: Context, file: File, key: ByteArray,
            editKey: (RecordsDatabase) -> ByteArray = {
                MessageDigest.getInstance("SHA-256").digest("AIyou synthetic edit test key v1".toByteArray(Charsets.US_ASCII) + key)
            },
            newCapturePolicy: () -> ProcessingPolicy = { ProcessingDefaults.policy },
            attachmentKey: (RecordsDatabase) -> ByteArray = { key.copyOf() },
        ): EncryptedEntryRepository {
            var database: RecordsDatabase? = null
            var storage: EncryptedAttachmentStore? = null
            var edits: EncryptedOriginalEditStore? = null
            var gateway: DrainingRecordsSqlDatabase? = null
            var opened = false
            try {
                require(key.size == 32)
                SqlCipherPrivacyLog.install()
                System.loadLibrary("sqlcipher")
                check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
                val created = Room.databaseBuilder(context.applicationContext, RecordsDatabase::class.java, file.absolutePath)
                .openHelperFactory(SupportOpenHelperFactory(key))
                .addMigrations(recordsMigration1To2, recordsMigration2To3, recordsMigration3To4, recordsMigration4To5, recordsMigration5To6, recordsMigration6To7, recordsMigration7To8, recordsMigration8To9, recordsMigration9To10, recordsMigration10To11, recordsMigration11To12, recordsMigration12To13, recordsMigration13To14, recordsMigration14To15)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        installRevisionGuard(db); installAttachmentGuard(db); installRemoteTextGuards(db)
                        installManualTagGuard(db); installManualTaskGuard(db)
                        db.execSQL("INSERT INTO local_search_epoch(slot,epoch,tokenizerVersion,tokenizerProfile) VALUES(1,0,1,'UNKNOWN')")
                        installLocalSearchGuards(db); installPermanentErasureGuards(db)
                        installRecordFavoriteGuards(db)
                        installLocalCandidateGuards(db)
                        installRecordEventDateGuards(db); installManualPeopleGuards(db); installModelConnectionGuards(db); installModelCapabilityGuards(db)
                    }
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        installRevisionGuard(db); installAttachmentGuard(db); installRemoteTextGuards(db)
                        installManualTagGuard(db); installManualTaskGuard(db); installLocalSearchGuards(db); installPermanentErasureGuards(db)
                        installRecordFavoriteGuards(db)
                        installLocalCandidateGuards(db)
                        installRecordEventDateGuards(db); installManualPeopleGuards(db); installModelConnectionGuards(db); installModelCapabilityGuards(db)
                    }
                }).build()
                database = created
                created.openHelper.writableDatabase // authenticate/schema validation before reporting ready
                val ownerSql = DrainingRecordsSqlDatabase(RoomRecordsSqlDatabase(created)).also { gateway = it }
                val fileHooks = SqlErasureFileHooks(ownerSql)
                val mediaKey = attachmentKey(created)
                try {
                    require(mediaKey.size == 32)
                    storage = EncryptedAttachmentStore(File(file.parentFile, file.name + ".attachments"), RoomAttachmentLedger(created), mediaKey).also { it.installErasureHooks(fileHooks) }
                } catch (failure: Exception) { mediaKey.fill(0); throw failure }
                val draftKey = editKey(created)
                try { edits = EncryptedOriginalEditStore(File(file.parentFile, file.name + ".original-edits"), draftKey).also { it.installErasureHooks(fileHooks) } }
                catch (failure: Exception) { draftKey.fill(0); throw failure }
                return EncryptedEntryRepository(created, key, checkNotNull(storage), checkNotNull(edits), ownerSql,
                    newCapturePolicy = newCapturePolicy).also { opened = true }
            } finally {
                if (!opened) {
                    RecordOpenFailureProtocol.cleanup(key,arrayOf(database,storage,edits,gateway)){
                        gateway?.seal(); storage?.close(); edits?.close()
                        gateway?.checkExternalWait(); storage?.checkExternalWait(); edits?.checkExternalWait()
                        storage?.awaitIdle(); edits?.awaitIdle(); gateway?.awaitIdle(); database?.close()
                    }
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
