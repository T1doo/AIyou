package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Collections
import java.util.concurrent.CancellationException

/** All encryption/decryption runs inside the records owner's counted SQL transaction. */
internal class SqlManualTagRepository(private val database: RecordsSqlDatabase, databaseKey: ByteArray) : ManualTagRepository {
    private val cipher = ManualTagCipher(databaseKey)
    override fun read(entryId: String): ManualTagSnapshot = safely {
        ManualTags.identifier(entryId)
        database.transaction { sql -> readWithin(sql, entryId) }
    }
    /** Owner's export reader already holds the counted transaction; do not start another snapshot. */
    internal fun readWithin(sql: RecordsSqlConnection, entryId: String, allowDeleted: Boolean = false): ManualTagSnapshot {
        ManualTags.identifier(entryId)
        if (!allowDeleted) live(sql, entryId)
        return row(sql, entryId)?.snapshot() ?: ManualTagSnapshot(entryId, 0, emptyList())
    }
    /** Importer owns this exact transaction; initial tags never borrow a historical tag version. */
    internal fun createWithin(sql: RecordsSqlConnection, entryId: String, names: List<String>, mutationId: String) {
        ManualTags.identifier(entryId); ManualTags.identifier(mutationId)
        require(ManualTags.canonical(names) == names && row(sql, entryId) == null)
        live(sql, entryId)
        if (names.isNotEmpty()) {
            val bytes = cipher.seal(entryId, 1, mutationId, names)
            sql.execute("INSERT INTO entry_manual_tags(entryId,version,lastMutationId,ciphertext) VALUES(?,?,?,?)",
                listOf(entryId, 1, mutationId, bytes))
        }
        val stored = readWithin(sql, entryId)
        require(stored.names == names && stored.version == if (names.isEmpty()) 0L else 1L)
    }
    override fun snapshots(): Map<String, ManualTagSnapshot> = safely {
        database.transaction { sql -> Collections.unmodifiableMap(sql.query(
            "SELECT t.entryId,t.version,t.lastMutationId,t.ciphertext FROM entry_manual_tags t JOIN entries e ON e.id=t.entryId WHERE NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1) ORDER BY t.entryId"
        ).associate { cells -> val row = decode(cells); row.id to row.snapshot() }) }
    }
    override fun replace(mutation: ManualTagMutation): ManualTagSnapshot = safely {
        database.transaction { sql -> replaceWithin(sql, mutation) }
    }
    /** Caller owns the same counted transaction; retains the original manual-tag wire format. */
    internal fun replaceWithin(sql: RecordsSqlConnection, mutation: ManualTagMutation): ManualTagSnapshot {
            live(sql, mutation.entryId)
            val previous = row(sql, mutation.entryId)
            val snapshot = previous?.snapshot() ?: ManualTagSnapshot(mutation.entryId, 0, emptyList())
            if (previous?.mutation == mutation.mutationId) {
                if (snapshot.version == mutation.expectedVersion + 1 && snapshot.names == mutation.names) return snapshot
                else throw ManualTagConflict()
            } else {
                if (snapshot.version != mutation.expectedVersion) throw ManualTagConflict()
                val next = Math.addExact(snapshot.version, 1)
                val bytes = cipher.seal(mutation.entryId, next, mutation.mutationId, mutation.names)
                if (previous == null) sql.execute("INSERT INTO entry_manual_tags(entryId,version,lastMutationId,ciphertext) VALUES(?,?,?,?)",
                    listOf(mutation.entryId, next, mutation.mutationId, bytes))
                else if (sql.execute("UPDATE entry_manual_tags SET version=?,lastMutationId=?,ciphertext=? WHERE entryId=? AND version=?",
                    listOf(next, mutation.mutationId, bytes, mutation.entryId, mutation.expectedVersion)) != 1) throw ManualTagConflict()
                return checkNotNull(row(sql, mutation.entryId)).snapshot().also {
                    check(it.version == next && it.names == mutation.names)
                }
            }
    }
    private fun live(sql: RecordsSqlConnection, id: String) {
        if (sql.query("SELECT id FROM entries e WHERE id=? AND NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1)", listOf(id)).size != 1)
            throw ManualTagUnavailable()
    }
    private fun row(sql: RecordsSqlConnection, id: String): Row? = sql.query(
        "SELECT entryId,version,lastMutationId,ciphertext FROM entry_manual_tags WHERE entryId=?", listOf(id)).singleOrNull()?.let(::decode)
    private fun decode(cells: List<Any?>): Row = Row(cells[0] as String, cells[1] as Long, cells[2] as String, cells[3] as ByteArray)
    private inner class Row(val id: String, val version: Long, val mutation: String, val ciphertext: ByteArray) {
        fun snapshot(): ManualTagSnapshot {
            require(version > 0); ManualTags.identifier(mutation)
            return ManualTagSnapshot(id, version, cipher.open(id, version, mutation, ciphertext))
        }
    }
    private inline fun <T> safely(block: () -> T): T = try { block() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: ManualTagConflict) { throw failure }
        catch (failure: ManualTagUnavailable) { throw failure }
        catch (_: Exception) { throw ManualTagStorageFailure() }
    /** Owner only, after sealing and draining the shared SQL gateway. */
    fun wipeKey() = cipher.wipe()
}
