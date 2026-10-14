package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.ZoneId
import java.util.concurrent.CancellationException

/** Uses v6 tombstone/generation and the existing encrypted operation ledger; no new schema. */
internal class SqlRecycleBinRepository(private val database: RecordsSqlDatabase,
    private val tags: SqlManualTagRepository,
    private val isErasing: (RecordsSqlConnection, String) -> Boolean = { _, _ -> false }) : RecycleBinRepository {
    override fun records(): List<RecycleRecord> = safely {
        database.transaction { sql -> sql.query(
            "SELECT e.id FROM entries e JOIN remote_source_access a ON a.entryId=e.id WHERE a.deleted=1 ORDER BY e.capturedAtMillis DESC,e.id"
        ).map { readWithin(sql, it.single() as String) } }
    }
    override fun read(entryId: String): RecycleRecord = safely {
        ManualTags.identifier(entryId)
        database.transaction { readWithin(it, entryId) }
    }
    override fun apply(mutation: RecycleMutation): RecycleRecord = safely {
        database.transaction { sql ->
            if (isErasing(sql, mutation.source.sourceId)) throw RecycleConflict()
            val current = readWithin(sql, mutation.source.sourceId)
            val receiptId = "RECYCLE:" + mutation.mutationId
            val operation = (if (mutation.expectedDeleted) "RECYCLE_RESTORE:" else "RECYCLE_MOVE:") + mutation.expectedGeneration
            val next = Math.addExact(mutation.expectedGeneration, 1)
            val receipt = sql.query("SELECT entryId,sourceRevision,operation,state FROM local_jobs WHERE id=?", listOf(receiptId)).singleOrNull()
            if (receipt != null) {
                if (receipt != listOf(mutation.source.sourceId, mutation.source.revision, operation, "CONFIRMED:$next") ||
                    current.generation != next || current.deleted == mutation.expectedDeleted ||
                    current.entry.currentRevision.toLong() != mutation.source.revision || current.entry.policy != ProcessingPolicy.AI_NONE ||
                    sql.query("SELECT connection,consent FROM remote_source_access WHERE entryId=?", listOf(mutation.source.sourceId)).single() != listOf(null, null)) throw RecycleConflict()
                return@transaction current
            }
            if (current.entry.currentRevision.toLong() != mutation.source.revision ||
                current.generation != mutation.expectedGeneration || current.deleted != mutation.expectedDeleted) throw RecycleConflict()
            // Neither direction restores any remote grant. Existing triggers cancel pending AI and remove annotations.
            check(sql.execute("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=? AND currentRevision=?",
                listOf(mutation.source.sourceId, mutation.source.revision)) == 1)
            sql.execute("INSERT INTO remote_source_access(entryId,generation,deleted,connection,consent) VALUES(?,?,?,NULL,NULL) ON CONFLICT(entryId) DO UPDATE SET generation=excluded.generation,deleted=excluded.deleted,connection=NULL,consent=NULL",
                listOf(mutation.source.sourceId, next, if (mutation.expectedDeleted) 0 else 1))
            sql.execute("UPDATE remote_text_jobs SET state='CANCELLED' WHERE entryId=? AND state IN ('QUEUED','ADMITTED')", listOf(mutation.source.sourceId))
            sql.execute("DELETE FROM text_annotations WHERE entryId=?", listOf(mutation.source.sourceId))
            if (!mutation.expectedDeleted) sql.execute("UPDATE local_jobs SET state='CANCELLED' WHERE entryId=? AND operation='LOCAL_INDEX' AND state IN ('PENDING','RUNNING','WAITING_CONDITIONS')", listOf(mutation.source.sourceId))
            else sql.execute("UPDATE local_jobs SET state='PENDING' WHERE entryId=? AND sourceRevision=? AND operation='LOCAL_INDEX' AND state='CANCELLED'", listOf(mutation.source.sourceId, mutation.source.revision))
            // Originals, manual labels, attachments and independent manual tasks remain untouched.
            sql.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,?,?,?)",
                listOf(receiptId, mutation.source.sourceId, mutation.source.revision, operation, "CONFIRMED:$next"))
            readWithin(sql, mutation.source.sourceId).also {
                check(it.generation == next && it.deleted != mutation.expectedDeleted && it.entry.policy == ProcessingPolicy.AI_NONE)
            }
        }
    }
    private fun readWithin(sql: RecordsSqlConnection, id: String): RecycleRecord {
        val row = sql.query("SELECT e.id,e.currentRevision,r.originalText,r.contentHash,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy,COALESCE(a.generation,0),COALESCE(a.deleted,0),(SELECT count(*) FROM entry_revisions v WHERE v.entryId=e.id),(SELECT count(*) FROM attachments f WHERE f.entryId=e.id) FROM entries e JOIN entry_revisions r ON e.id=r.entryId AND e.currentRevision=r.revision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?", listOf(id)).singleOrNull()
            ?: throw RecycleConflict()
        val text = row[2] as String
        require(text.isNotBlank() && text.length <= 100_000 && OriginalExports.hash(text) == row[3])
        ZoneId.of(row[5] as String)
        val deleted = row[8] as Long; require(deleted in 0..1)
        val entry = StoredEntry(row[0] as String, Math.toIntExact(row[1] as Long), text, row[4] as Long,
            row[5] as String, ProcessingPolicy.valueOf(row[6] as String))
        return RecycleRecord(entry, row[7] as Long, deleted == 1L, tags.readWithin(sql, id, allowDeleted = true),
            Math.toIntExact(row[9] as Long), Math.toIntExact(row[10] as Long))
    }
    private inline fun <T> safely(block: () -> T): T = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (conflict: RecycleConflict) { throw conflict }
        catch (_: Exception) { throw RecycleStorageFailure() }
}
