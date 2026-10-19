package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Collections
import java.util.concurrent.CancellationException

/** Borrowed records transaction and existing AEAD primitive, with a separate key/AAD purpose. */
internal class SqlManualPeopleRepository(private val database: RecordsSqlDatabase, key: ByteArray) : ManualPeopleRepository {
    private val cipher = ManualTagCipher(key, ManualLabelPurpose.PEOPLE)
    override fun read(entryId: String): ManualPeopleSnapshot = safe { database.transaction { readWithin(it, entryId) } }
    internal fun readWithin(sql: RecordsSqlConnection, id: String): ManualPeopleSnapshot {
        ManualTags.identifier(id)
        val source = sql.query("SELECT e.currentRevision,r.contentHash,COALESCE(a.generation,0) FROM entries e " +
            "JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id " +
            "WHERE e.id=? AND COALESCE(a.deleted,0)=0", listOf(id)).singleOrNull() ?: throw ManualPeopleUnavailable()
        val row = sql.query("SELECT version,lastMutationId,ciphertext FROM entry_manual_people WHERE entryId=?", listOf(id)).singleOrNull()
        val version = row?.get(0) as? Long ?: 0L
        val labels = if (row == null) ManualPeopleLabels(emptyList(), emptyList()) else {
            require(version > 0); val mutation = row[1] as String; ManualTags.identifier(mutation)
            cipher.openPeople(id, version, mutation, row[2] as ByteArray)
        }
        return ManualPeopleSnapshot(id, version, labels.names, Math.toIntExact(source[0] as Long), source[1] as String, source[2] as Long, labels.aliases)
    }
    override fun snapshots(): Map<String, ManualPeopleSnapshot> = safe { database.transaction { sql ->
        val ids = sql.query("SELECT p.entryId FROM entry_manual_people p JOIN entries e ON e.id=p.entryId " +
            "WHERE NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1) ORDER BY p.entryId LIMIT 4097")
        if (ids.size > 4096) throw ManualPeopleTooBroad()
        Collections.unmodifiableMap(ids.associate { (it[0] as String).let { id -> id to readWithin(sql, id) } })
    } }
    override fun replace(mutation: ManualPeopleMutation): ManualPeopleSnapshot = safe { database.transaction { sql ->
        val before = mutation.before; val now = readWithin(sql, before.entryId)
        if (before.sourceRevision != now.sourceRevision || before.sourceHash != now.sourceHash || before.generation != now.generation)
            throw ManualPeopleConflict()
        val receipt = sql.query("SELECT lastMutationId FROM entry_manual_people WHERE entryId=?", listOf(before.entryId)).singleOrNull()?.single()
        if (receipt == mutation.mutationId) {
            if (now.version != before.version + 1 || now.names != mutation.names || now.aliases != mutation.aliases) throw ManualPeopleConflict()
            return@transaction now
        }
        if (now.version != before.version || now.names != before.names || now.aliases != before.aliases) throw ManualPeopleConflict()
        val next = Math.addExact(now.version, 1)
        val bytes = cipher.sealPeople(before.entryId, next, mutation.mutationId, mutation.names, mutation.aliases)
        if (now.version == 0L) sql.execute("INSERT INTO entry_manual_people VALUES(?,?,?,?)", listOf(before.entryId, next, mutation.mutationId, bytes))
        else if (sql.execute("UPDATE entry_manual_people SET version=?,lastMutationId=?,ciphertext=? WHERE entryId=? AND version=?",
                listOf(next, mutation.mutationId, bytes, before.entryId, before.version)) != 1) throw ManualPeopleConflict()
        readWithin(sql, before.entryId).also { check(it.version == next && it.names == mutation.names && it.aliases == mutation.aliases) }
    } }
    /** New-copy restore, same transaction. Old source versions/permissions never become local authority. */
    internal fun createWithin(sql: RecordsSqlConnection, id: String, names: List<String>, mutation: String,
        aliases: List<ManualPersonAlias> = emptyList()) {
        require(ManualTags.canonical(names) == names); ManualTags.identifier(mutation)
        ManualPeopleAliases.canonical(names, aliases)
        val before = readWithin(sql, id); require(before.version == 0L)
        if (names.isNotEmpty()) sql.execute("INSERT INTO entry_manual_people VALUES(?,1,?,?)",
            listOf(id, mutation, cipher.sealPeople(id, 1, mutation, names, aliases)))
        check(readWithin(sql, id).let { it.names == names && it.aliases == aliases })
    }
    fun wipeKey() = cipher.wipe()
    private inline fun <T> safe(block: () -> T): T = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: ManualPeopleConflict) { throw e }
        catch (e: ManualPeopleUnavailable) { throw e }
        catch (e: ManualPeopleTooBroad) { throw e }
        catch (_: Exception) { throw ManualPeopleStorageFailure() }
}
