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
    @Query("UPDATE entries SET currentRevision = :next WHERE id = :id AND currentRevision = :expected")
    suspend fun advance(id: String, expected: Int, next: Int): Int
    @Query("SELECT e.*, r.originalText, r.contentHash FROM entries e JOIN entry_revisions r ON e.id=r.entryId AND e.currentRevision=r.revision ORDER BY e.capturedAtMillis DESC, e.id")
    suspend fun entries(): List<EntryRow>
    @Query("SELECT e.*, r.originalText, r.contentHash FROM entries e JOIN entry_revisions r ON e.id=r.entryId AND e.currentRevision=r.revision WHERE e.id=:id")
    suspend fun entry(id: String): EntryRow?
    @Query("SELECT * FROM entry_revisions WHERE entryId=:id ORDER BY revision")
    suspend fun revisions(id: String): List<RevisionEntity>
    @Query("SELECT * FROM attachments WHERE id=:id") suspend fun attachment(id: String): AttachmentEntity?
    @Query("SELECT * FROM attachments ORDER BY id") suspend fun attachments(): List<AttachmentEntity>
    @Insert suspend fun insertAttachment(value: AttachmentEntity)
    @Query("UPDATE attachments SET state=:next WHERE id=:id AND state=:expected")
    suspend fun advanceAttachment(id: String, expected: String, next: String): Int
}

@Database(entities = [EntryEntity::class, RevisionEntity::class, DraftEntity::class, LocalJobEntity::class, AttachmentEntity::class], version = 3, exportSchema = true)
internal abstract class RecordsDatabase : RoomDatabase() {
    abstract fun records(): RecordsDao
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
    db.execSQL("CREATE TRIGGER IF NOT EXISTS immutable_original_revision BEFORE UPDATE ON entry_revisions BEGIN SELECT RAISE(ABORT, 'immutable original revision'); END")
}

internal fun installAttachmentGuard(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TRIGGER IF NOT EXISTS immutable_attachment_intent BEFORE UPDATE ON attachments WHEN OLD.id IS NOT NEW.id OR OLD.entryId IS NOT NEW.entryId OR OLD.revision IS NOT NEW.revision OR OLD.mediaType IS NOT NEW.mediaType OR OLD.sha256 IS NOT NEW.sha256 OR OLD.byteSize IS NOT NEW.byteSize BEGIN SELECT RAISE(ABORT, 'immutable attachment intent'); END")
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
