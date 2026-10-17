package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Collections
import java.util.concurrent.CancellationException

/** Borrowed IO is counted by the same encrypted records owner. Clear retains the versioned receipt. */
internal class SqlRecordEventDateRepository(private val database: RecordsSqlDatabase,
    private val metadataLimit: Int = MAX_METADATA_ROWS) : RecordEventDateRepository {
    init { require(metadataLimit in 1..MAX_METADATA_ROWS) }
    private val columns = "e.id,e.currentRevision,d.version,d.lastMutationId,d.lastSourceRevision,d.localDate,d.timeZone"
    private val from = " FROM entries e LEFT JOIN entry_event_dates d ON d.entryId=e.id "
    private val live = "NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1) " +
        "AND NOT EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=e.id)"

    override fun read(entryId: String): RecordEventDateSnapshot = safely {
        RecordFavorites.identifier(entryId)
        database.transaction { readWithin(it, entryId) }
    }
    override fun snapshots(): Map<String, RecordEventDateSnapshot> = safely {
        database.transaction { snapshotsWithin(it) }
    }
    internal fun readWithin(sql: RecordsSqlConnection, id: String): RecordEventDateSnapshot {
        RecordFavorites.identifier(id)
        if (!hasTable(sql)) {
            // Pre-v11 schemas cannot contain this field. Never manufacture a date from capture time.
            legacyVersion(sql)
            val available = sql.query("SELECT id FROM entries e WHERE e.id=?" + legacyLive(sql),listOf(id))
            if (available.size != 1) throw RecordEventDateUnavailable()
            return RecordEventDateSnapshot(id,0,null)
        }
        return row(sql, id).snapshot()
    }
    internal fun snapshotsWithin(sql: RecordsSqlConnection): Map<String, RecordEventDateSnapshot> =
        Collections.unmodifiableMap(LinkedHashMap<String,RecordEventDateSnapshot>().also { result ->
            forEachWithin(sql) { result[it.id] = it.snapshot() }
        })
    internal fun validateWithin(sql: RecordsSqlConnection) { forEachWithin(sql) {} }

    private fun forEachWithin(sql: RecordsSqlConnection, consume: (Row) -> Unit) {
        val legacy = !hasTable(sql)
        if (legacy) legacyVersion(sql)
        var last: String? = null; var seen = 0
        while (true) {
            val size = minOf(256, metadataLimit-seen+1)
            val select = if (legacy) "SELECT e.id,e.currentRevision,NULL,NULL,NULL,NULL,NULL FROM entries e WHERE 1=1" + legacyLive(sql)
                else "SELECT " + columns + from + "WHERE " + live
            val page = sql.query(select +
                (if (last == null) "" else " AND e.id>?") + " ORDER BY e.id LIMIT ?",
                if (last == null) listOf(size) else listOf(last,size))
            if (page.isEmpty()) return
            for (cells in page) {
                if (++seen > metadataLimit) throw RecordEventDateTooBroad()
                val value = decode(cells); consume(value); last = value.id
            }
            if (page.size < size) return
        }
    }

    override fun set(mutation: RecordEventDateMutation): RecordEventDateSnapshot = safely {
        database.transaction { sql ->
            val before = row(sql,mutation.entryId)
            if (before.mutation == mutation.mutationId) {
                // A committed but unacknowledged intent may be retried after a text edit, never after a newer metadata write.
                if (before.version != mutation.expectedVersion+1 || before.date != mutation.date ||
                    before.sourceRevision != mutation.sourceRevision) throw RecordEventDateConflict()
                source(sql,mutation)
                before.snapshot()
            } else {
                if (before.current != mutation.sourceRevision || before.version != mutation.expectedVersion)
                    throw RecordEventDateConflict()
                // Compare inside the same transaction as the metadata CAS; separate reads leave an ABA window.
                // The receipt branch above can acknowledge an already committed intent without a new write.
                val generation = sql.query("SELECT COALESCE(a.generation,0) FROM entries e LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",
                    listOf(mutation.entryId)).single().single() as Long
                if (generation != mutation.sourceGeneration) throw RecordEventDateConflict()
                source(sql,mutation)
                val next = Math.addExact(before.version,1)
                if (before.version == 0L) sql.execute("INSERT INTO entry_event_dates(entryId,version,lastMutationId,lastSourceRevision,localDate,timeZone) VALUES(?,?,?,?,?,?)",
                    listOf(mutation.entryId,next,mutation.mutationId,mutation.sourceRevision,mutation.date?.localDate,mutation.date?.timeZone))
                else if (sql.execute("UPDATE entry_event_dates SET version=?,lastMutationId=?,lastSourceRevision=?,localDate=?,timeZone=? WHERE entryId=? AND version=?",
                    listOf(next,mutation.mutationId,mutation.sourceRevision,mutation.date?.localDate,mutation.date?.timeZone,mutation.entryId,before.version)) != 1)
                    throw RecordEventDateConflict()
                row(sql,mutation.entryId).also {
                    check(it.version == next && it.date == mutation.date && it.mutation == mutation.mutationId && it.sourceRevision == mutation.sourceRevision)
                }.snapshot()
            }
        }
    }

    /** Only the already confirmed restore transaction calls this for a newly allocated copy. */
    internal fun importWithin(sql: RecordsSqlConnection, id: String, date: RecordEventDate, mutationId: String) {
        RecordFavorites.identifier(mutationId)
        val before = row(sql,id)
        if (before.version != 0L) throw RecordEventDateConflict()
        check(sql.execute("INSERT INTO entry_event_dates(entryId,version,lastMutationId,lastSourceRevision,localDate,timeZone) VALUES(?,1,?,?,?,?)",
            listOf(id,mutationId,before.current,date.localDate,date.timeZone)) == 1)
        check(row(sql,id).date == date)
    }

    private fun source(sql: RecordsSqlConnection, mutation: RecordEventDateMutation) {
        val size = sql.query("SELECT length(CAST(originalText AS BLOB)) FROM entry_revisions WHERE entryId=? AND revision=?",
            listOf(mutation.entryId,mutation.sourceRevision)).singleOrNull()?.single() as Long? ?: throw RecordEventDateUnavailable()
        require(size in 0..400_000)
        val cells = sql.query("SELECT originalText,contentHash FROM entry_revisions WHERE entryId=? AND revision=?",
            listOf(mutation.entryId,mutation.sourceRevision)).singleOrNull() ?: throw RecordEventDateUnavailable()
        val text = cells[0] as String; val hash = cells[1] as String
        require(text.length <= 100_000 && OriginalExports.hash(text) == hash)
        require(text.isNotBlank() || sql.query("SELECT id FROM attachments WHERE entryId=? AND revision=? AND mediaType IN ('image/png','image/jpeg') LIMIT 1",
            listOf(mutation.entryId,mutation.sourceRevision)).isNotEmpty())
        if (hash != mutation.sourceHash) throw RecordEventDateConflict()
    }
    private fun row(sql: RecordsSqlConnection, id: String): Row = sql.query(
        "SELECT " + columns + from + "WHERE e.id=? AND " + live,listOf(id)
    ).singleOrNull()?.let(::decode) ?: throw RecordEventDateUnavailable()
    private fun hasTable(sql: RecordsSqlConnection) = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name='entry_event_dates'").isNotEmpty()
    private fun legacyVersion(sql: RecordsSqlConnection) {
        require(sql.query("PRAGMA user_version").single().single() as Long in 1L..10L)
    }
    private fun legacyLive(sql: RecordsSqlConnection): String {
        fun exists(table: String) = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?",listOf(table)).isNotEmpty()
        return (if (exists("remote_source_access")) " AND NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1)" else "") +
            (if (exists("permanent_erasure_receipts")) " AND NOT EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=e.id)" else "")
    }
    private fun decode(cells: List<Any?>): Row {
        val id = cells[0] as String; val current = Math.toIntExact(cells[1] as Long)
        RecordFavorites.identifier(id); require(current > 0)
        if (cells[2] == null) {
            require(cells.drop(3).all { it == null })
            return Row(id,current,0,null,0,null)
        }
        val version = cells[2] as Long; val mutation = cells[3] as String
        val source = Math.toIntExact(cells[4] as Long)
        require(version > 0 && source in 1..current); RecordFavorites.identifier(mutation)
        val date = if (cells[5] == null && cells[6] == null) null
            else RecordEventDate(cells[5] as String,cells[6] as String)
        return Row(id,current,version,mutation,source,date)
    }
    private class Row(val id: String,val current: Int,val version: Long,val mutation: String?,val sourceRevision: Int,val date: RecordEventDate?) {
        fun snapshot() = RecordEventDateSnapshot(id,version,date)
    }
    private inline fun <T> safely(block: () -> T): T = try { block() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: RecordEventDateConflict) { throw failure }
        catch (failure: RecordEventDateUnavailable) { throw failure }
        catch (failure: RecordEventDateTooBroad) { throw failure }
        catch (_: Exception) { throw RecordEventDateStorageFailure() }
    companion object { const val MAX_METADATA_ROWS = 65_536 }
}
