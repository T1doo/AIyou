package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real-file SQL driver for VM wiring only; not the production Room/local transaction proof.
 * Remote authority and candidate rules remain in the actual RemoteTextTransactions instance.
 */
internal class SqliteVmEntryRepository(
    private val primary: PythonSqliteDatabase,
    private val sql: RecordsSqlDatabase,
    override val sourceBoundText: SourceBoundTextRepository,
    private val sealAndDrain: () -> Unit,
) : EntryRepository, AutoCloseable {
    val closingEntered = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val closeFailure = AtomicReference<Throwable>()
    val saveThread = AtomicReference<Thread>()
    private val closing = AtomicBoolean()
    @Volatile var decorateEntries: (List<StoredEntry>) -> List<StoredEntry> = { it }
    @Volatile var beforeClose: () -> Unit = {}

    override suspend fun prepare() {
        sql.transaction { db ->
            check(db.query("PRAGMA user_version").single()[0] == 4L)
            check(db.query("PRAGMA foreign_keys").single()[0] == 1L)
        }
    }

    override suspend fun readDraft(): TextDraft? = sql.transaction(::draft)

    override suspend fun updateDraft(text: String): TextDraft = sql.transaction { db ->
        require(text.length <= 100_000)
        val old = draft(db)
        val next = TextDraft(old?.id ?: UUID.randomUUID().toString(), Math.addExact(old?.version ?: 0L, 1L), text)
        putDraft(db, next)
        next
    }

    override suspend fun discardDraft(expected: TextDraft) {
        sql.transaction { db ->
            val current = draft(db) ?: return@transaction
            if (current != expected) throw RecordConflict()
            check(db.execute("DELETE FROM capture_draft WHERE slot=1 AND id=? AND version=?",
                listOf(expected.id, expected.version)) == 1)
        }
    }

    override suspend fun restoreDiscardedDraft(expected: TextDraft, replacement: TextDraft): TextDraft =
        sql.transaction { db ->
            require(replacement.id != expected.id && replacement.version == 1L && replacement.text == expected.text)
            val current = draft(db)
            if (current == expected || current == replacement) return@transaction checkNotNull(current)
            if (current != null) throw RecordConflict()
            putDraft(db, replacement)
            checkNotNull(draft(db))
        }

    override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry =
        sql.transaction { db ->
            saveThread.set(Thread.currentThread())
            require(expected.text.isNotBlank())
            ZoneId.of(captureTimeZone)
            current(db, expected.id).singleOrNull()?.let { existing ->
                val originalHash = db.query("SELECT contentHash FROM entry_revisions WHERE entryId=? ORDER BY revision LIMIT 1",
                    listOf(expected.id)).single()[0]
                if (originalHash != RemoteTextFixture.hash(expected.text)) throw RecordConflict()
                return@transaction existing
            }
            if (draft(db) != expected) throw RecordConflict()
            db.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,?,?,'LOCAL_ONLY')",
                listOf(expected.id, capturedAtMillis, captureTimeZone))
            db.execute("INSERT INTO entry_revisions(entryId,revision,originalText,contentHash,createdAtMillis) VALUES(?,1,?,?,?)",
                listOf(expected.id, expected.text, RemoteTextFixture.hash(expected.text), capturedAtMillis))
            db.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,1,'LOCAL_INDEX','PENDING')",
                listOf("${expected.id}:1:LOCAL_INDEX", expected.id))
            check(db.execute("DELETE FROM capture_draft WHERE slot=1 AND id=? AND version=?",
                listOf(expected.id, expected.version)) == 1)
            current(db, expected.id).single()
        }

    override suspend fun entries(): List<StoredEntry> = decorateEntries(sql.transaction { current(it) })

    override suspend fun revisions(entryId: String): List<OriginalRevision> = sql.transaction { db ->
        db.query("SELECT entryId,revision,originalText,contentHash,createdAtMillis FROM entry_revisions WHERE entryId=? AND NOT EXISTS(SELECT 1 FROM remote_source_access WHERE entryId=? AND deleted=1) ORDER BY revision",
            listOf(entryId, entryId)).map { row ->
            if (row[3] != RemoteTextFixture.hash(row[2] as String)) throw RecordIntegrityFailure()
            OriginalRevision(row[0] as String, (row[1] as Long).toInt(), row[2] as String, row[3] as String, row[4] as Long)
        }
    }

    override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry =
        sql.transaction { db ->
            require(text.isNotBlank() && text.length <= 100_000)
            val old = current(db, entryId).singleOrNull() ?: throw RecordConflict()
            if (old.currentRevision != expectedRevision) throw RecordConflict()
            val next = Math.addExact(expectedRevision, 1)
            db.execute("INSERT INTO entry_revisions(entryId,revision,originalText,contentHash,createdAtMillis) VALUES(?,?,?,?,?)",
                listOf(entryId, next, text, RemoteTextFixture.hash(text), createdAtMillis))
            db.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,?,'LOCAL_INDEX','PENDING')",
                listOf("$entryId:$next:LOCAL_INDEX", entryId, next))
            if (db.execute("UPDATE entries SET currentRevision=? WHERE id=? AND currentRevision=?",
                    listOf(next, entryId, expectedRevision)) != 1) throw RecordConflict()
            current(db, entryId).single()
        }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        closingEntered.countDown()
        try {
            sealAndDrain()
            beforeClose()
        } catch (failure: Throwable) {
            closeFailure.set(failure)
            throw failure
        } finally {
            try { primary.close() }
            catch (failure: Throwable) { closeFailure.compareAndSet(null, failure); throw failure }
            finally { closed.countDown() }
        }
    }

    private fun draft(db: RecordsSqlConnection): TextDraft? =
        db.query("SELECT id,version,text FROM capture_draft WHERE slot=1").singleOrNull()?.let {
            TextDraft(it[0] as String, it[1] as Long, it[2] as String)
        }

    private fun putDraft(db: RecordsSqlConnection, value: TextDraft) {
        db.execute("INSERT INTO capture_draft(slot,id,version,text) VALUES(1,?,?,?) ON CONFLICT(slot) DO UPDATE SET id=excluded.id,version=excluded.version,text=excluded.text",
            listOf(value.id, value.version, value.text))
    }

    private fun current(db: RecordsSqlConnection, id: String? = null): List<StoredEntry> {
        val condition = if (id == null) "" else " AND e.id=?"
        return db.query("SELECT e.id,e.currentRevision,r.originalText,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy,r.contentHash FROM entries e JOIN entry_revisions r ON e.id=r.entryId AND e.currentRevision=r.revision WHERE NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1)$condition ORDER BY e.capturedAtMillis DESC,e.id",
            if (id == null) emptyList() else listOf(id)).map { row ->
            if (row[6] != RemoteTextFixture.hash(row[2] as String)) throw RecordIntegrityFailure()
            StoredEntry(row[0] as String, Math.toIntExact(row[1] as Long), row[2] as String,
                row[3] as Long, row[4] as String, ProcessingPolicy.valueOf(row[5] as String))
        }
    }
}
