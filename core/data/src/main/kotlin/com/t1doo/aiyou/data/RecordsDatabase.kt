package com.t1doo.aiyou.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Dao
internal interface RecordsDao {
    @Query("SELECT * FROM capture_draft WHERE slot = 1") suspend fun draft(): DraftEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDraft(draft: DraftEntity)
    @Query("DELETE FROM capture_draft WHERE slot = 1 AND id = :id AND version = :version")
    suspend fun consumeDraft(id: String, version: Long): Int
    @Insert suspend fun insertEntry(entry: EntryEntity)
    @Insert suspend fun insertRevision(revision: RevisionEntity)
    @Insert suspend fun insertJob(job: LocalJobEntity)
    @Query("SELECT * FROM local_jobs WHERE id=:id") suspend fun job(id: String): LocalJobEntity?
    @Query("UPDATE entries SET currentRevision = :next WHERE id = :id AND currentRevision = :expected")
    suspend fun advance(id: String, expected: Int, next: Int): Int
    @Query("SELECT e.id,e.currentRevision,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy,r.originalText,r.contentHash FROM entries e JOIN entry_revisions r ON e.id=r.entryId AND e.currentRevision=r.revision WHERE NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1) ORDER BY e.capturedAtMillis DESC, e.id")
    suspend fun entries(): List<EntryRow>
    @Query("SELECT e.id,e.currentRevision,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy,r.originalText,r.contentHash FROM entries e JOIN entry_revisions r ON e.id=r.entryId AND e.currentRevision=r.revision WHERE e.id=:id AND NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1)")
    suspend fun entry(id: String): EntryRow?
    @Query("SELECT * FROM entry_revisions WHERE entryId=:id AND NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=:id AND a.deleted=1) ORDER BY revision")
    suspend fun revisions(id: String): List<RevisionEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM entry_revisions r JOIN entries e ON e.id=r.entryId WHERE r.entryId=:entryId AND r.revision=:revision AND NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1))")
    suspend fun revisionExists(entryId: String, revision: Int): Boolean
    @Query("SELECT * FROM attachments WHERE id=:id") suspend fun attachment(id: String): AttachmentEntity?
    @Query("SELECT * FROM attachments ORDER BY id") suspend fun attachments(): List<AttachmentEntity>
    @Query("SELECT * FROM attachments WHERE entryId=:entryId ORDER BY id LIMIT :limit")
    suspend fun attachmentsForEntry(entryId: String, limit: Int): List<AttachmentEntity>
    @Insert suspend fun insertAttachment(value: AttachmentEntity)
    @Query("UPDATE attachments SET state=:next WHERE id=:id AND state=:expected")
    suspend fun advanceAttachment(id: String, expected: String, next: String): Int
}

@Database(entities = [EntryEntity::class, RevisionEntity::class, DraftEntity::class, LocalJobEntity::class,
    AttachmentEntity::class, RemoteSourceAccessEntity::class, RemoteTextJobEntity::class, TextAnnotationEntity::class, ManualTagEntity::class,
    ManualTaskEntity::class, LocalSearchFtsEntity::class, LocalSearchDocumentEntity::class,
    LocalSearchEpochEntity::class, PermanentErasureEntity::class, RecordPackageReceiptEntity::class,
    OwnedFileCreationEntity::class, LocalCandidateBatchEntity::class, RecordEventDateEntity::class, ManualPeopleEntity::class,
    ModelConfigurationEntity::class, ModelCapabilityEntity::class, ReminderRuntimeMetaEntity::class], version = 15, exportSchema = true)
internal abstract class RecordsDatabase : RoomDatabase() {
    abstract fun records(): RecordsDao
}

internal val recordsMigration14To15 = object : Migration(14,15) {
    override fun migrate(db:SupportSQLiteDatabase) { ReminderRuntimeMetaSqlSchema.migration14To15.forEach(db::execSQL) }
}

/** Existing originals/drafts/jobs are untouched; media capture belongs to D03. */
internal val recordsMigration2To3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE attachments (id TEXT NOT NULL PRIMARY KEY, entryId TEXT NOT NULL, revision INTEGER NOT NULL, mediaType TEXT NOT NULL, sha256 TEXT NOT NULL, byteSize INTEGER NOT NULL, state TEXT NOT NULL, FOREIGN KEY(entryId,revision) REFERENCES entry_revisions(entryId,revision) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)")
        db.execSQL("CREATE INDEX index_attachments_entryId_revision ON attachments(entryId,revision)")
        installAttachmentGuard(db)
    }
}

internal fun installRevisionGuard(db: SupportSQLiteDatabase) {
    db.execSQL(RemoteTextSqlSchema.originalRevisionGuard)
}

internal val recordsMigration3To4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        RemoteTextSqlSchema.migration3To4.forEach(db::execSQL)
        installRemoteTextGuards(db)
    }
}
internal fun installRemoteTextGuards(db: SupportSQLiteDatabase) { RemoteTextSqlSchema.guards.forEach(db::execSQL) }

internal fun installAttachmentGuard(db: SupportSQLiteDatabase) {
    AttachmentIntentSqlSchema.guards.forEach(db::execSQL)
}

/** v1 is the initial D02 text schema; v2 adds the durable local work ledger. */
internal val recordsMigration1To2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_jobs (id TEXT NOT NULL PRIMARY KEY, entryId TEXT NOT NULL, sourceRevision INTEGER NOT NULL, operation TEXT NOT NULL, state TEXT NOT NULL, FOREIGN KEY(entryId, sourceRevision) REFERENCES entry_revisions(entryId, revision) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_jobs_entryId_sourceRevision ON local_jobs(entryId, sourceRevision)")
        db.execSQL("INSERT INTO local_jobs (id, entryId, sourceRevision, operation, state) SELECT id || ':' || currentRevision || ':LOCAL_INDEX', id, currentRevision, 'LOCAL_INDEX', 'PENDING' FROM entries")
        installRevisionGuard(db)
    }
}

internal val recordsMigration4To5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ManualTagSqlSchema.migration4To5.forEach(db::execSQL)
        installManualTagGuard(db)
    }
}
internal fun installManualTagGuard(db: SupportSQLiteDatabase) { db.execSQL(ManualTagSqlSchema.guard) }

internal val recordsMigration5To6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ManualTaskSqlSchema.migration5To6.forEach(db::execSQL)
        installManualTaskGuard(db)
    }
}
internal fun installManualTaskGuard(db: SupportSQLiteDatabase) { db.execSQL(ManualTaskSqlSchema.guard) }

internal val recordsMigration6To7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        LocalSearchSqlSchema.migration6To7.forEach(db::execSQL)
        installAttachmentGuard(db)
        installLocalSearchGuards(db)
    }
}
internal fun installLocalSearchGuards(db: SupportSQLiteDatabase) { LocalSearchSqlSchema.guards.forEach(db::execSQL) }

internal val recordsMigration7To8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        PermanentErasureSqlSchema.migration7To8.forEach(db::execSQL)
        installPermanentErasureGuards(db)
    }
}
internal fun installPermanentErasureGuards(db: SupportSQLiteDatabase) { PermanentErasureSqlSchema.guards.forEach(db::execSQL) }

internal val recordsMigration8To9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) { RecordFavoriteSqlSchema.migration8To9.forEach(db::execSQL) }
}

internal val recordsMigration9To10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) { LocalCandidateSqlSchema.migration9To10.forEach(db::execSQL) }
}
internal fun installLocalCandidateGuards(db: SupportSQLiteDatabase) { LocalCandidateSqlSchema.guards.forEach(db::execSQL) }

internal val recordsMigration10To11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) { RecordEventDateSqlSchema.migration10To11.forEach(db::execSQL) }
}
internal fun installRecordEventDateGuards(db: SupportSQLiteDatabase) { RecordEventDateSqlSchema.guards.forEach(db::execSQL) }

/** Atomically replace the legacy update trigger; another owner cannot write in a DDL gap. */
internal fun installRecordFavoriteGuards(db: SupportSQLiteDatabase) {
    val ownsTransaction = !db.inTransaction()
    if (ownsTransaction) db.beginTransaction()
    try {
        RecordFavoriteSqlSchema.guards.forEach(db::execSQL)
        db.execSQL("DROP TRIGGER IF EXISTS local_search_entries_update")
        db.execSQL(RecordFavoriteSqlSchema.sourceIndexUpdate)
        if (ownsTransaction) db.setTransactionSuccessful()
    } finally { if (ownsTransaction) db.endTransaction() }
}

internal val recordsMigration11To12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) { ManualPeopleSqlSchema.migration11To12.forEach(db::execSQL) }
}
internal fun installManualPeopleGuards(db: SupportSQLiteDatabase) { ManualPeopleSqlSchema.guards.forEach(db::execSQL) }

internal val recordsMigration12To13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) { ModelConnectionSqlSchema.migration12To13.forEach(db::execSQL) }
}
internal fun installModelConnectionGuards(db: SupportSQLiteDatabase) { ModelConnectionSqlSchema.guards.forEach(db::execSQL) }

internal val recordsMigration13To14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) { ModelCapabilitySqlSchema.migration13To14.forEach(db::execSQL) }
}
internal fun installModelCapabilityGuards(db: SupportSQLiteDatabase) { ModelCapabilitySqlSchema.guards.forEach(db::execSQL) }
