package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CancellationException

/** Same drained SQL owner and tag AEAD as normal records; no schema migration or raw import port. */
internal class SqlRecordPackageRepository(private val database: RecordsSqlDatabase,
    private val tags: SqlManualTagRepository) : RecordPackageRepository {
    override fun restore(verified: VerifiedRecordPackage, newIds: List<String>, importedAtMillis: Long): RecordPackageRestoreResult = safely {
        val pack = verified as? AuthenticatedRecordPackage ?: throw RecordPackageInvalid()
        require(newIds.size <= 20)
        val ids = newIds.toList()
        database.transaction { sql ->
            val existing = job(sql, GROUP + pack.manifest.backupId)
            if (existing != null) return@transaction replay(sql, pack, existing)
            // A missing group ACK with surviving origin evidence cannot start an uncertain new batch.
            sql.query("SELECT id,entryId,sourceRevision,operation,state FROM local_jobs WHERE operation='PACKAGE_ORIGIN'").forEach {
                if (origin(it).backupId == pack.manifest.backupId) throw RecordPackageConflict()
            }
            require(importedAtMillis >= 0 && ids.size == pack.records.size && ids.distinct().size == ids.size)
            ids.forEach { id ->
                RecordPackages.uuid(id)
                if (pack.records.any { it.sourceId == id } || sql.query("SELECT id FROM entries WHERE id=?", listOf(id)).isNotEmpty())
                    throw RecordPackageConflict()
            }
            for (index in ids.indices) {
                val id = ids[index]; val record = pack.records[index]
                sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,?,?,'AI_NONE')",
                    listOf(id, record.capturedAtMillis, record.captureTimeZone))
                sql.execute("INSERT INTO entry_revisions(entryId,revision,originalText,contentHash,createdAtMillis) VALUES(?,1,?,?,?)",
                    listOf(id, record.originalText, record.contentHash, importedAtMillis))
                sql.execute("INSERT INTO remote_source_access(entryId,generation,deleted,connection,consent) VALUES(?,1,0,NULL,NULL)", listOf(id))
                tags.createWithin(sql, id, record.tags, "PACKAGE_TAG:" + id)
                sql.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,1,'LOCAL_INDEX','PENDING')",
                    listOf("PACKAGE_INDEX:" + id, id))
                val provenance = expectedOrigin(pack, index, id, importedAtMillis)
                sql.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,1,'PACKAGE_ORIGIN',?)",
                    listOf(ORIGIN + id, id, originState(provenance)))
            }
            val group = Group(pack.manifest.backupId, pack.digest, pack.manifest.createdAtMillis,
                importedAtMillis, ids, pack.recordDigests)
            sql.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,1,'PACKAGE_RESTORE',?)",
                listOf(GROUP + group.id, ids.first(), groupState(group)))
            replay(sql, pack, checkNotNull(job(sql, GROUP + group.id)), false)
        }
    }

    override fun origins(): Map<String, RecordPackageOrigin> = safely {
        database.transaction { sql ->
            val origins = sql.query("SELECT id,entryId,sourceRevision,operation,state FROM local_jobs WHERE operation='PACKAGE_ORIGIN'").map(::origin)
            Collections.unmodifiableMap(origins.associate { value ->
                val group = group(checkNotNull(job(sql, GROUP + value.backupId)))
                val index = group.ids.indexOf(value.newId); require(index >= 0)
                require(group.digest == value.packageDigest && group.created == value.packageCreatedAtMillis &&
                    group.imported == value.importedAtMillis && group.hashes[index] == value.recordDigest)
                immutable(sql, value, null)
                value.newId to value
            })
        }
    }

    private fun replay(sql: RecordsSqlConnection, pack: AuthenticatedRecordPackage, row: List<Any?>,
        replayed: Boolean = true): RecordPackageRestoreResult {
        val group = group(row)
        if (group.id != pack.manifest.backupId || group.digest != pack.digest || group.created != pack.manifest.createdAtMillis ||
            group.ids.size != pack.records.size || group.hashes != pack.recordDigests) throw RecordPackageConflict()
        group.ids.forEachIndexed { index, id ->
            require(pack.records.none { it.sourceId == id })
            val expected = expectedOrigin(pack, index, id, group.imported)
            val originRow = checkNotNull(job(sql, ORIGIN + id))
            val actual = origin(originRow)
            require(originRow[4] == originState(expected))
            immutable(sql, actual, pack.records[index])
        }
        return RecordPackageRestoreResult(group.ids, group.imported, replayed)
    }

    /** Raw SQL deliberately includes trashed rows and the immutable first imported revision. */
    private fun immutable(sql: RecordsSqlConnection, origin: RecordPackageOrigin, original: RecordPackageOriginal?) {
        val row = sql.query("SELECT e.currentRevision,e.capturedAtMillis,e.captureTimeZone,r.originalText,r.contentHash,r.createdAtMillis FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=1 WHERE e.id=?",
            listOf(origin.newId)).single()
        require((row[0] as Long) >= 1 && row[1] == origin.capturedAtMillis && row[2] == origin.captureTimeZone &&
            row[4] == OriginalExports.hash(row[3] as String) && row[5] == origin.importedAtMillis)
        if (original != null) require(row[3] == original.originalText && row[4] == original.contentHash)
        // Never inspect/reset the current revision, policy, consent, deletion or mutable tags on replay.
    }

    private fun expectedOrigin(pack: AuthenticatedRecordPackage, index: Int, id: String, imported: Long): RecordPackageOrigin {
        val r = pack.records[index]
        return RecordPackageOrigin(id, pack.manifest.backupId, pack.digest, pack.manifest.createdAtMillis, imported,
            r.sourceId, r.sourceRevision, r.sourceTagVersion, pack.recordDigests[index], r.capturedAtMillis, r.captureTimeZone)
    }
    private class Group(val id: String, val digest: String, val created: Long, val imported: Long,
        val ids: List<String>, val hashes: List<String>)
    private fun groupState(group: Group): String = receipt { out ->
        out.i32(1); out.i32(1); out.string(group.id, 36); out.raw(packageUnhex(group.digest))
        out.i64(group.created); out.i64(group.imported); out.i32(group.ids.size)
        group.ids.forEachIndexed { index, id -> out.string(id, 36); out.raw(packageUnhex(group.hashes[index])) }
    }
    private fun originState(value: RecordPackageOrigin): String = receipt { out ->
        out.i32(1); out.i32(2); out.string(value.backupId, 36); out.raw(packageUnhex(value.packageDigest))
        out.i64(value.packageCreatedAtMillis); out.i64(value.importedAtMillis); out.string(value.newId, 36)
        out.string(value.sourceId, 512); out.i32(value.sourceRevision); out.i64(value.sourceTagVersion)
        out.raw(packageUnhex(value.recordDigest)); out.i64(value.capturedAtMillis); out.string(value.captureTimeZone, 128)
    }
    private fun group(row: List<Any?>): Group = readReceipt(row[4] as String) { input ->
        require(input.i32() == 1 && input.i32() == 1)
        val id = input.string(36); RecordPackages.uuid(id); val digest = packageHex(input.raw(32))
        val created = input.i64(); val imported = input.i64(); require(created >= 0 && imported >= 0)
        val count = input.count(20); require(count > 0)
        val ids = mutableListOf<String>(); val hashes = mutableListOf<String>()
        repeat(count) { ids += input.string(36).also(RecordPackages::uuid); hashes += packageHex(input.raw(32)) }
        require(ids.distinct().size == count && row[0] == GROUP + id && row[1] == ids.first() &&
            row[2] == 1L && row[3] == "PACKAGE_RESTORE")
        Group(id, digest, created, imported, ids, hashes)
    }
    private fun origin(row: List<Any?>): RecordPackageOrigin = readReceipt(row[4] as String) { input ->
        require(input.i32() == 1 && input.i32() == 2)
        val backupId = input.string(36); val digest = packageHex(input.raw(32))
        val created = input.i64(); val imported = input.i64(); val id = input.string(36)
        val source = input.string(512); val revision = input.i32(); val tagVersion = input.i64()
        val recordHash = packageHex(input.raw(32)); val captured = input.i64(); val zone = input.string(128)
        require(row[0] == ORIGIN + id && row[1] == id && row[2] == 1L && row[3] == "PACKAGE_ORIGIN")
        RecordPackageOrigin(id, backupId, digest, created, imported, source, revision, tagVersion, recordHash, captured, zone)
    }
    private fun receipt(write: (PackageWriter) -> Unit): String = PackageWriter(8192).use { out ->
        write(out); val raw = out.bytes()
        try { "CONFIRMED:" + Base64.getEncoder().encodeToString(raw) } finally { raw.fill(0) }
    }
    private fun <T> readReceipt(state: String, read: (PackageReader) -> T): T {
        require(state.length <= 10934 && state.startsWith("CONFIRMED:") && state.all { it.code <= 127 })
        val encoded = state.substring(10); val raw = Base64.getDecoder().decode(encoded)
        try {
            require(raw.size <= 8192 && Base64.getEncoder().encodeToString(raw) == encoded)
            val input = PackageReader(raw); return read(input).also { input.end() }
        } finally { raw.fill(0) }
    }
    private fun job(sql: RecordsSqlConnection, id: String): List<Any?>? =
        sql.query("SELECT id,entryId,sourceRevision,operation,state FROM local_jobs WHERE id=?", listOf(id)).singleOrNull()
    private inline fun <T> safely(block: () -> T): T = try { block() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: RecordPackageConflict) { throw failure }
        catch (failure: RecordPackageInvalid) { throw failure }
        catch (_: Exception) { throw RecordPackageStorageFailure() }
    companion object { private const val GROUP = "PACKAGE_RESTORE:"; private const val ORIGIN = "PACKAGE_ORIGIN:" }
}
