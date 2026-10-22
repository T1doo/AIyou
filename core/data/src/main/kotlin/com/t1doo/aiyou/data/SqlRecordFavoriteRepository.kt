package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Collections
import java.util.concurrent.CancellationException

/** Metadata is inside SQLCipher, counted and sealed with its existing owner. */
internal class SqlRecordFavoriteRepository(private val database: RecordsSqlDatabase,
    private val metadataLimit: Int = MAX_METADATA_ROWS) : RecordFavoriteRepository {
    init { require(metadataLimit in 1..MAX_METADATA_ROWS) }
    private val columns = "e.id,e.currentRevision,e.isFavorite,e.favoriteVersion,e.lastFavoriteMutationId,e.lastFavoriteSourceRevision"
    private val live = "NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1) " +
        "AND NOT EXISTS(SELECT 1 FROM permanent_erasure_receipts p WHERE p.entryId=e.id)"

    override fun read(entryId: String): RecordFavoriteSnapshot = safely {
        RecordFavorites.identifier(entryId)
        database.transaction { sql -> row(sql, entryId).snapshot() }
    }

    override fun snapshots(): Map<String, RecordFavoriteSnapshot> = safely {
        database.transaction { sql -> snapshotsWithin(sql) }
    }
    /** Candidate lookup authenticates coherent metadata in its own existing source transaction. */
    internal fun snapshotsWithin(sql: RecordsSqlConnection): Map<String, RecordFavoriteSnapshot> =
        Collections.unmodifiableMap(LinkedHashMap<String,RecordFavoriteSnapshot>().also { result ->
            forEachWithin(sql) { row -> result[row.id] = row.snapshot() }
        })
    internal fun validateWithin(sql: RecordsSqlConnection) { forEachWithin(sql) {} }
    internal fun readWithin(sql: RecordsSqlConnection, id: String) = row(sql,id).snapshot()

    /** Complete metadata, including receipt fields: public flag/version alone are not proof. */
    internal fun visitWithin(sql:RecordsSqlConnection,checkCurrent:()->Unit,consume:(Row)->Unit) =
        forEachWithin(sql,checkCurrent,20,consume)
    internal fun metadataWithin(sql:RecordsSqlConnection,id:String)=row(sql,id)

    /** Bounded keyset pages validate every metadata row; no truncated READY/EMPTY result. */
    private fun forEachWithin(sql: RecordsSqlConnection, checkCurrent:()->Unit={}, batch:Int=256, consume: (Row) -> Unit) {
        var last: String? = null; var seen = 0
        while (true) {
            checkCurrent()
            val pageSize = minOf(batch, metadataLimit-seen+1)
            val page = sql.query("SELECT " + columns + " FROM entries e WHERE " + live +
                (if(last == null) "" else " AND e.id>?") + " ORDER BY e.id LIMIT ?",
                if(last == null) listOf(pageSize) else listOf(last,pageSize))
            if(page.isEmpty()) return
            for(cells in page) {
                checkCurrent()
                if(++seen>metadataLimit) throw RecordFavoriteTooBroad()
                val value=decode(cells);consume(value);last=value.id;checkCurrent()
            }
            if(page.size<pageSize) return
        }
    }

    override fun set(mutation: RecordFavoriteMutation): RecordFavoriteSnapshot = safely {
        database.transaction { sql ->
            val before = row(sql, mutation.entryId)
            // A known receipt can be acknowledged after later text edits, never after another favorite write.
            if (before.mutation == mutation.mutationId) {
                if (before.version != mutation.expectedVersion + 1 || before.favorite != mutation.isFavorite ||
                    before.sourceRevision != mutation.sourceRevision) throw RecordFavoriteConflict()
                source(sql, mutation)
                before.snapshot()
            } else {
                if (before.currentRevision != mutation.sourceRevision || before.version != mutation.expectedVersion)
                    throw RecordFavoriteConflict()
                // New writes require the source authority captured by the reading button.
                // A known receipt above only acknowledges an existing write; it never writes again.
                val generation = sql.query("SELECT COALESCE(a.generation,0) FROM entries e LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",
                    listOf(mutation.entryId)).single().single() as Long
                if (generation != mutation.sourceGeneration) throw RecordFavoriteConflict()
                source(sql, mutation)
                val next = Math.addExact(before.version, 1)
                val affected = sql.execute("UPDATE entries SET isFavorite=?,favoriteVersion=?,lastFavoriteMutationId=?,lastFavoriteSourceRevision=? " +
                    "WHERE id=? AND currentRevision=? AND favoriteVersion=?",
                    listOf(if (mutation.isFavorite) 1 else 0, next, mutation.mutationId, mutation.sourceRevision,
                        mutation.entryId, mutation.sourceRevision, mutation.expectedVersion))
                if (affected != 1) throw RecordFavoriteConflict()
                row(sql, mutation.entryId).also {
                    check(it.version == next && it.favorite == mutation.isFavorite &&
                        it.mutation == mutation.mutationId && it.sourceRevision == mutation.sourceRevision)
                }.snapshot()
            }
        }
    }

    private fun source(sql: RecordsSqlConnection, mutation: RecordFavoriteMutation) {
        val cells = sql.query("SELECT originalText,contentHash FROM entry_revisions WHERE entryId=? AND revision=?",
            listOf(mutation.entryId, mutation.sourceRevision)).singleOrNull() ?: throw RecordFavoriteUnavailable()
        val text = cells[0] as String; val hash = cells[1] as String
        require(text.length <= 100_000 && OriginalExports.hash(text) == hash)
        require(text.isNotBlank() || sql.query("SELECT id FROM attachments WHERE entryId=? AND revision=? AND mediaType IN ('image/png','image/jpeg','audio/wav') LIMIT 1",listOf(mutation.entryId,mutation.sourceRevision)).isNotEmpty())
        if (hash != mutation.sourceHash) throw RecordFavoriteConflict()
    }

    private fun row(sql: RecordsSqlConnection, id: String): Row = sql.query(
        "SELECT " + columns + " FROM entries e WHERE e.id=? AND " + live, listOf(id)
    ).singleOrNull()?.let(::decode) ?: throw RecordFavoriteUnavailable()

    private fun decode(cells: List<Any?>): Row {
        val id = cells[0] as String; val current = Math.toIntExact(cells[1] as Long)
        val flag = cells[2] as Long; val version = cells[3] as Long
        val mutation = cells[4] as String?; val source = Math.toIntExact(cells[5] as Long)
        RecordFavorites.identifier(id)
        require(current > 0 && flag in 0L..1L && version >= 0)
        if (version == 0L) require(flag == 0L && mutation == null && source == 0)
        else { RecordFavorites.identifier(checkNotNull(mutation)); require(source in 1..current) }
        return Row(id, current, flag == 1L, version, mutation, source)
    }

    internal class Row(val id: String, val currentRevision: Int, val favorite: Boolean,
        val version: Long, val mutation: String?, val sourceRevision: Int) {
        fun snapshot() = RecordFavoriteSnapshot(id, version, favorite)
    }

    private inline fun <T> safely(block: () -> T): T = try { block() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: RecordFavoriteConflict) { throw failure }
        catch (failure: RecordFavoriteUnavailable) { throw failure }
        catch (failure: RecordFavoriteTooBroad) { throw failure }
        catch (_: Exception) { throw RecordFavoriteStorageFailure() }
    companion object { const val MAX_METADATA_ROWS = 65_536 }
}
