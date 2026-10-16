package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.CancellationException

/** Reuses source generations, existing invalidation triggers and operation receipts. No schema change. */
internal class SqlLocalProcessingRepository(private val database: RecordsSqlDatabase) : LocalProcessingRepository {
    override fun read(source: SourceVersion): LocalProcessingSnapshot = safely {
        database.transaction { readWithin(it, source) }
    }
    override fun versions(): Map<String, LocalProcessingVersion> = safely { database.transaction { sql ->
        sql.query("SELECT e.id,e.currentRevision,COALESCE(a.generation,0) FROM entries e LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE COALESCE(a.deleted,0)=0 AND NOT EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=e.id)")
            .associate { row ->
                val source = SourceVersion(row[0] as String, row[1] as Long)
                val generation = row[2] as Long; require(generation >= 0)
                source.sourceId to LocalProcessingVersion(source, generation)
            }
    } }
    override fun change(request: LocalProcessingChange): LocalProcessingSnapshot = safely { database.transaction { sql ->
        val before = request.expected
        val current = readWithin(sql, before.source) // Reject deletion/fences even for an old receipt.
        val next = Math.addExact(before.generation, 1)
        val receiptId = "LOCAL_POLICY:" + request.mutationId
        val operation = "LOCAL_POLICY:" + before.policy.name + ":" + request.target.name + ":" + before.generation
        val state = "CONFIRMED:" + next + ":" + before.sourceHash
        val receipt = sql.query("SELECT entryId,sourceRevision,operation,state FROM local_jobs WHERE id=?", listOf(receiptId)).singleOrNull()
        val desired = before.copy(generation = next, policy = ProcessingPolicy.valueOf(request.target.name))
        if (receipt != null) {
            if (receipt != listOf(before.source.sourceId, before.source.revision, operation, state) || current != desired ||
                sql.query("SELECT connection,consent FROM remote_source_access WHERE entryId=?", listOf(before.source.sourceId)).single() != listOf(null,null))
                throw LocalProcessingConflict()
            return@transaction current
        }
        if (current != before) throw LocalProcessingConflict()
        require(sql.execute("UPDATE entries SET privacyPolicy=? WHERE id=? AND currentRevision=? AND privacyPolicy=?",
            listOf(request.target.name, before.source.sourceId, before.source.revision, before.policy.name)) == 1)
        // The entries trigger already advances this same generation and erases grants/derived data.
        // Explicitly binding the intended value also handles old records with no access row.
        sql.execute("INSERT INTO remote_source_access(entryId,generation,deleted,connection,consent) VALUES(?,?,0,NULL,NULL) ON CONFLICT(entryId) DO UPDATE SET generation=excluded.generation,connection=NULL,consent=NULL",
            listOf(before.source.sourceId, next))
        sql.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,?,?,?)",
            listOf(receiptId,before.source.sourceId,before.source.revision,operation,state))
        readWithin(sql,before.source).also { check(it == desired) }
    } }
    private fun readWithin(sql: RecordsSqlConnection, source: SourceVersion): LocalProcessingSnapshot {
        if (SqlErasureFileHooks.fenced(sql,source.sourceId)) throw LocalProcessingConflict()
        val r = sql.query("SELECT e.currentRevision,r.originalText,r.contentHash,e.privacyPolicy,COALESCE(a.generation,0),COALESCE(a.deleted,0) FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",
            listOf(source.sourceId)).singleOrNull() ?: throw LocalProcessingConflict()
        if (r[0] != source.revision || r[5] != 0L) throw LocalProcessingConflict()
        val text = r[1] as String; require(text.length <= 100_000 && OriginalExports.hash(text) == r[2])
        return LocalProcessingSnapshot(source,r[2] as String,r[4] as Long,ProcessingPolicy.valueOf(r[3] as String))
    }
    private inline fun <T> safely(block: () -> T): T = try { block() }
        catch (c: CancellationException) { throw c }
        catch (c: LocalProcessingConflict) { throw c }
        catch (_: Exception) { throw LocalProcessingStorageFailure() }
}
