package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CancellationException

/** Same drained SQL owner and tag AEAD; FK-free v8 receipts remain authoritative after erasure. */
internal class SqlRecordPackageRepository(private val database: RecordsSqlDatabase,
    private val tags: SqlManualTagRepository, private val maxGroups: Int = 4096,
    private val activities:SqlRecordPackageActivities? = null,
    private val people: SqlManualPeopleRepository? = null) : RecordPackageRepository {
    private val eventDates = SqlRecordEventDateRepository(database)
    init { require(maxGroups in 1..4096) }
    override fun restore(verified: VerifiedRecordPackage, newIds: List<String>, importedAtMillis: Long): RecordPackageRestoreResult {
        if (verified is AuthenticatedMediaPackage) throw RecordPackageInvalid()
        return restoreWithFiles(verified,newIds,importedAtMillis) {}
    }
    internal fun restoreWithFiles(verified: VerifiedRecordPackage, newIds: List<String>, importedAtMillis: Long,
        beforeReceipt: (RecordsSqlConnection) -> Unit): RecordPackageRestoreResult = safely {
        val pack = verified as? AuthenticatedRecordPackage ?: throw RecordPackageInvalid()
        require(pack.activities==null || activities!=null)
        require(pack.records.none { it.sourcePeopleVersion > 0 } || people != null)
        require(!pack.includesHistory || pack.records.isNotEmpty())
        require(newIds.size <= 20)
        val ids = newIds.toList()
        database.transaction { sql ->
            val existing = job(sql, GROUP + pack.manifest.backupId)
            if (existing != null) return@transaction replay(sql, pack, existing)
            val groupCount = sql.query("SELECT count(*) FROM record_package_receipts WHERE operation='PACKAGE_RESTORE'").single().single() as Long
            if (groupCount !in 0 until maxGroups.toLong()) throw RecordPackageConflict()
            // Legacy mirrors are not replay authority, but surviving evidence forbids a fresh uncertain batch.
            val mirrors = sql.query("SELECT id,entryId,sourceRevision,operation,state FROM local_jobs WHERE operation IN ('PACKAGE_RESTORE','PACKAGE_ORIGIN') ORDER BY id LIMIT ?",listOf(maxGroups*21+1))
            require(mirrors.size <= maxGroups*21)
            mirrors.forEach { row ->
                if ((if (row[3] == "PACKAGE_RESTORE") group(row).id else origin(row).backupId) == pack.manifest.backupId)
                    throw RecordPackageConflict()
            }
            // A missing group ACK with surviving origin evidence cannot start an uncertain new batch.
            val origins = sql.query("SELECT id,entryId,sourceRevision,operation,state FROM record_package_receipts WHERE operation='PACKAGE_ORIGIN' ORDER BY id LIMIT ?",listOf(maxGroups*20+1))
            require(origins.size <= maxGroups*20)
            origins.forEach {
                if (origin(it).backupId == pack.manifest.backupId) throw RecordPackageConflict()
            }
            val proofs = sql.query("SELECT entryId,packageProof FROM permanent_erasure_receipts WHERE packageProof!='' ORDER BY entryId LIMIT ?",listOf(maxGroups*20+1))
            require(proofs.size <= maxGroups*20)
            proofs.forEach { if (proofBackupId(it[1] as String,it[0] as String) == pack.manifest.backupId) throw RecordPackageConflict() }
            require(importedAtMillis >= 0 && ids.size == pack.records.size && ids.distinct().size == ids.size)
            ids.forEach { id ->
                RecordPackages.uuid(id)
                if (pack.records.any { it.sourceId == id } || sql.query("SELECT id FROM entries WHERE id=?", listOf(id)).isNotEmpty())
                    throw RecordPackageConflict()
            }
            for (index in ids.indices) {
                val id = ids[index]; val record = pack.records[index]
                val restoredRevision = record.restoredRevision
                sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,?,?,?,'AI_NONE')",
                    listOf(id, restoredRevision, record.capturedAtMillis, record.captureTimeZone))
                if (record.history.isEmpty()) {
                    // Frozen v1/v2/v3 current-only contract: the new copy starts at revision 1.
                    sql.execute("INSERT INTO entry_revisions(entryId,revision,originalText,contentHash,createdAtMillis) VALUES(?,1,?,?,?)",
                        listOf(id, record.originalText, record.contentHash, importedAtMillis))
                } else record.history.forEach { revision ->
                    sql.execute("INSERT INTO entry_revisions(entryId,revision,originalText,contentHash,createdAtMillis) VALUES(?,?,?,?,?)",
                        listOf(id, revision.revision, revision.originalText, revision.contentHash, revision.createdAtMillis))
                }
                sql.execute("INSERT INTO remote_source_access(entryId,generation,deleted,connection,consent) VALUES(?,1,0,NULL,NULL)", listOf(id))
                tags.createWithin(sql, id, record.tags, "PACKAGE_TAG:" + id)
                people?.createWithin(sql, id, record.people, "PACKAGE_PEOPLE:" + id)
                record.eventDate?.let { eventDates.importWithin(sql, id, it, "PACKAGE_EVENT:" + id) }
                sql.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,?,'LOCAL_INDEX','PENDING')",
                    listOf("PACKAGE_INDEX:" + id, id, restoredRevision))
                val provenance = expectedOrigin(pack, index, id, importedAtMillis)
                sql.execute("INSERT INTO record_package_receipts(id,entryId,sourceRevision,operation,state) VALUES(?,?,?,'PACKAGE_ORIGIN',?)",
                    listOf(ORIGIN + id, id, restoredRevision, originState(provenance)))
                sql.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,?,'PACKAGE_ORIGIN',?)",
                    listOf(ORIGIN + id, id, restoredRevision, originState(provenance)))
            }
            activities?.importWithin(sql,pack,ids)
            beforeReceipt(sql)
            val group = Group(pack.manifest.backupId, pack.digest, pack.manifest.createdAtMillis,
                importedAtMillis, ids, pack.recordDigests,pack.activities!=null,pack.includesHistory,
                pack.records.map { it.restoredRevision }, if(pack.includesHistory) pack.records.map { recordHistoryDigest(it.history) } else emptyList())
            sql.execute("INSERT INTO record_package_receipts(id,entryId,sourceRevision,operation,state) VALUES(?,?,1,'PACKAGE_RESTORE',?)",
                listOf(GROUP + group.id, ids.firstOrNull() ?: SqlRecordPackageActivities.anchor(group.id), groupState(group)))
            if(ids.isNotEmpty()) sql.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,1,'PACKAGE_RESTORE',?)",
                listOf(GROUP + group.id, ids.first(), groupState(group)))
            replay(sql, pack, checkNotNull(job(sql, GROUP + group.id)), false)
        }
    }

    override fun origins(): Map<String, RecordPackageOrigin> = safely {
        database.transaction { sql ->
            groups(sql).forEach { validateMembers(sql,it) }
            val originRows = sql.query("SELECT id,entryId,sourceRevision,operation,state FROM record_package_receipts WHERE operation='PACKAGE_ORIGIN' ORDER BY id LIMIT ?",listOf(maxGroups*20+1))
            require(originRows.size <= maxGroups*20)
            val origins = originRows.map(::origin)
            Collections.unmodifiableMap(origins.mapNotNull { value ->
                val group = group(checkNotNull(job(sql, GROUP + value.backupId)))
                val index = group.ids.indexOf(value.newId); require(index >= 0)
                require(group.digest == value.packageDigest && group.created == value.packageCreatedAtMillis &&
                    group.imported == value.importedAtMillis && group.hashes[index] == value.recordDigest && historyMatches(group,index,value))
                immutable(sql, value, null)
                if (SqlErasureFileHooks.fenced(sql, value.newId)) null else value.newId to value
            }.toMap())
        }
    }

    private fun replay(sql: RecordsSqlConnection, pack: AuthenticatedRecordPackage, row: List<Any?>,
        replayed: Boolean = true): RecordPackageRestoreResult {
        val group = group(row)
        if (group.id != pack.manifest.backupId || group.digest != pack.digest || group.created != pack.manifest.createdAtMillis ||
            group.ids.size != pack.records.size || group.hashes != pack.recordDigests || group.activities!=(pack.activities!=null) ||
            group.history != pack.includesHistory || group.revisionCounts != pack.records.map { it.restoredRevision } ||
            group.historyDigests != (if(pack.includesHistory) pack.records.map { recordHistoryDigest(it.history) } else emptyList())) throw RecordPackageConflict()
        var deleted = 0; var erasing = 0
        group.ids.forEachIndexed { index, id ->
            require(pack.records.none { it.sourceId == id })
            val expected = expectedOrigin(pack, index, id, group.imported)
            val erasure = sql.query("SELECT sourceRevision,generation,mutationId,phase,plan,packageProof FROM permanent_erasure_receipts WHERE entryId=?", listOf(id)).singleOrNull()
            if (erasure != null) {
                require((erasure[0] as Long) > 0 && (erasure[1] as Long) >= 0)
                ManualTags.identifier(erasure[2] as String)
                val phase = PermanentErasurePhase.valueOf(erasure[3] as String)
                require(erasure[5] == erasureProof(group, index, id))
                if (phase == PermanentErasurePhase.DONE) {
                    require(erasure[4] == "" && job(sql, ORIGIN + id) == null &&
                        sql.query("SELECT id FROM local_jobs WHERE id=?", listOf(ORIGIN+id)).isEmpty() &&
                        sql.query("SELECT id FROM entries WHERE id=?", listOf(id)).isEmpty() &&
                        sql.query("SELECT entryId FROM entry_revisions WHERE entryId=?", listOf(id)).isEmpty())
                    deleted++
                    return@forEachIndexed
                }
                require(phase != PermanentErasurePhase.PREPARED || erasure[4] == "")
                require(phase != PermanentErasurePhase.PLANNED || (erasure[4] as String).isNotEmpty())
                val originRow = checkNotNull(job(sql, ORIGIN + id))
                require(originRow[4] == originState(expected)); immutable(sql, origin(originRow), pack.records[index])
                erasing++
                return@forEachIndexed
            }
            val originRow = checkNotNull(job(sql, ORIGIN + id))
            val actual = origin(originRow)
            require(originRow[4] == originState(expected))
            immutable(sql, actual, pack.records[index])
        }
        val activityIds=activities?.replayWithin(sql,pack,group.ids) ?: (emptyList<String>() to emptyList())
        return RecordPackageRestoreResult(group.ids, group.imported, replayed, deleted, erasing,activityIds.first,activityIds.second)
    }

    /** Raw SQL includes trashed rows and authenticates the complete immutable imported prefix. */
    private fun immutable(sql: RecordsSqlConnection, origin: RecordPackageOrigin, original: RecordPackageOriginal?) {
        if (origin.historyDigest != null) {
            val entry = sql.query("SELECT currentRevision,capturedAtMillis,captureTimeZone FROM entries WHERE id=?",listOf(origin.newId)).single()
            if ((entry[0] as Long) < origin.restoredRevisionCount || entry[1] != origin.capturedAtMillis || entry[2] != origin.captureTimeZone)
                throw RecordPackageConflict()
            val rows = sql.query("SELECT revision,originalText,contentHash,createdAtMillis FROM entry_revisions WHERE entryId=? AND revision<=? ORDER BY revision",
                listOf(origin.newId,origin.restoredRevisionCount))
            if (rows.size != origin.restoredRevisionCount) throw RecordPackageConflict()
            val history = rows.mapIndexed { index,row ->
                if (row[0] != (index+1).toLong()) throw RecordPackageConflict()
                RecordPackageRevision(index+1,row[1] as String,row[2] as String,row[3] as Long)
            }
            if (recordHistoryDigest(history) != origin.historyDigest ||
                (original != null && (original.restoredRevision != origin.restoredRevisionCount ||
                    recordHistoryDigest(original.history) != origin.historyDigest))) throw RecordPackageConflict()
            // A later valid append is deliberately untouched; the restore receipt owns only its prefix.
            require(sql.query("SELECT revision FROM entry_revisions WHERE entryId=? AND revision=?",listOf(origin.newId,entry[0])).size==1)
            return
        }
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
            r.sourceId, r.sourceRevision, r.sourceTagVersion, pack.recordDigests[index], r.capturedAtMillis, r.captureTimeZone,
            r.restoredRevision,if(pack.includesHistory) recordHistoryDigest(r.history) else null)
    }
    private class Group(val id: String, val digest: String, val created: Long, val imported: Long,
        val ids: List<String>, val hashes: List<String>,val activities:Boolean=false,val history:Boolean=false,
        val revisionCounts:List<Int> = ids.map {1},val historyDigests:List<String> = emptyList())
    private fun groupState(group: Group): String = receipt { out ->
        out.i32(if(group.history) 4 else if(group.activities) 3 else 1); out.i32(1); out.string(group.id, 36); out.raw(packageUnhex(group.digest))
        out.i64(group.created); out.i64(group.imported); out.i32(group.ids.size)
        if(group.history) out.i32(if(group.activities) 1 else 0)
        group.ids.forEachIndexed { index, id ->
            out.string(id, 36); out.raw(packageUnhex(group.hashes[index]))
            if(group.history) {out.i32(group.revisionCounts[index]);out.raw(packageUnhex(group.historyDigests[index]))}
        }
    }
    private fun originState(value: RecordPackageOrigin): String = receipt { out ->
        out.i32(if(value.historyDigest==null) 1 else 4); out.i32(2); out.string(value.backupId, 36); out.raw(packageUnhex(value.packageDigest))
        out.i64(value.packageCreatedAtMillis); out.i64(value.importedAtMillis); out.string(value.newId, 36)
        out.string(value.sourceId, 512); out.i32(value.sourceRevision); out.i64(value.sourceTagVersion)
        out.raw(packageUnhex(value.recordDigest)); out.i64(value.capturedAtMillis); out.string(value.captureTimeZone, 128)
        value.historyDigest?.let {out.i32(value.restoredRevisionCount);out.raw(packageUnhex(it))}
    }
    private fun group(row: List<Any?>): Group = readReceipt(row[4] as String) { input ->
        val version=input.i32();require(version in listOf(1,3,4) && input.i32() == 1)
        val id = input.string(36); RecordPackages.uuid(id); val digest = packageHex(input.raw(32))
        val created = input.i64(); val imported = input.i64(); require(created >= 0 && imported >= 0)
        val count = input.count(20)
        val activities = if(version==4) input.i32().also {require(it in 0..1)}==1 else version==3
        require(count > 0 || activities)
        val ids = mutableListOf<String>(); val hashes = mutableListOf<String>()
        val revisionCounts = mutableListOf<Int>(); val historyDigests=mutableListOf<String>()
        repeat(count) {
            ids += input.string(36).also(RecordPackages::uuid); hashes += packageHex(input.raw(32))
            revisionCounts += if(version==4) input.i32().also {require(it in 1..128)} else 1
            if(version==4) historyDigests+=packageHex(input.raw(32))
        }
        require(revisionCounts.sum() <= 256)
        require(ids.distinct().size == count && row[0] == GROUP + id && row[1] == (ids.firstOrNull() ?: SqlRecordPackageActivities.anchor(id)) &&
            row[2] == 1L && row[3] == "PACKAGE_RESTORE")
        Group(id, digest, created, imported, ids, hashes,activities,version==4,revisionCounts,historyDigests)
    }
    private fun origin(row: List<Any?>): RecordPackageOrigin = readReceipt(row[4] as String) { input ->
        val version=input.i32();require(version in listOf(1,4) && input.i32() == 2)
        val backupId = input.string(36); val digest = packageHex(input.raw(32))
        val created = input.i64(); val imported = input.i64(); val id = input.string(36)
        val source = input.string(512); val revision = input.i32(); val tagVersion = input.i64()
        val recordHash = packageHex(input.raw(32)); val captured = input.i64(); val zone = input.string(128)
        val restoredCount=if(version==4) input.i32() else 1
        val historyDigest=if(version==4) packageHex(input.raw(32)) else null
        require(row[0] == ORIGIN + id && row[1] == id && row[2] == restoredCount.toLong() && row[3] == "PACKAGE_ORIGIN")
        RecordPackageOrigin(id, backupId, digest, created, imported, source, revision, tagVersion, recordHash, captured, zone,restoredCount,historyDigest)
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
        sql.query("SELECT id,entryId,sourceRevision,operation,state FROM record_package_receipts WHERE id=?", listOf(id)).singleOrNull()

    internal fun prepareErasureProof(sql: RecordsSqlConnection, entryId: String): String {
        val row = job(sql, ORIGIN+entryId)
        if (row == null) {
            if (sql.query("SELECT id FROM local_jobs WHERE operation='PACKAGE_ORIGIN' AND entryId=? LIMIT 1",listOf(entryId)).isNotEmpty() ||
                groups(sql).any { entryId in it.ids }) throw RecordPackageConflict()
            return ""
        }
        val value = origin(row); val group = group(checkNotNull(job(sql, GROUP+value.backupId)))
        validateMembers(sql,group)
        val index = group.ids.indexOf(entryId); require(index >= 0)
        require(group.digest == value.packageDigest && group.created == value.packageCreatedAtMillis &&
            group.imported == value.importedAtMillis && group.hashes[index] == value.recordDigest && historyMatches(group,index,value))
        immutable(sql, value, null)
        return erasureProof(group, index, entryId)
    }
    private fun groups(sql: RecordsSqlConnection): List<Group> {
        val rows = sql.query("SELECT id,entryId,sourceRevision,operation,state FROM record_package_receipts WHERE operation='PACKAGE_RESTORE' ORDER BY id LIMIT ?",listOf(maxGroups+1))
        require(rows.size <= maxGroups)
        return rows.map(::group).also { require(it.flatMap { g -> g.ids }.distinct().size == it.sumOf { g -> g.ids.size }) }
    }
    private fun validateMembers(sql: RecordsSqlConnection, group: Group) {
        group.ids.forEachIndexed { index,id ->
            val erasure = sql.query("SELECT sourceRevision,generation,mutationId,phase,plan,packageProof FROM permanent_erasure_receipts WHERE entryId=?",listOf(id)).singleOrNull()
            if (erasure != null) {
                require((erasure[0] as Long) in 1..Int.MAX_VALUE.toLong() && (erasure[1] as Long) >= 0)
                ManualTags.identifier(erasure[2] as String)
                val phase = PermanentErasurePhase.valueOf(erasure[3] as String)
                require(erasure[5] == erasureProof(group,index,id) && (phase == PermanentErasurePhase.PLANNED) == (erasure[4] as String).isNotEmpty())
                if (phase == PermanentErasurePhase.DONE) {
                    require(job(sql,ORIGIN+id) == null && sql.query("SELECT id FROM local_jobs WHERE id=?",listOf(ORIGIN+id)).isEmpty() &&
                        sql.query("SELECT id FROM entries WHERE id=?",listOf(id)).isEmpty() && sql.query("SELECT entryId FROM entry_revisions WHERE entryId=?",listOf(id)).isEmpty())
                    return@forEachIndexed
                }
                require(sql.query("SELECT e.currentRevision,a.generation,a.deleted FROM entries e JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",listOf(id)).single() == listOf(erasure[0],erasure[1],1L))
            }
            val value = origin(checkNotNull(job(sql,ORIGIN+id)))
            require(value.newId == id && value.backupId == group.id && value.packageDigest == group.digest &&
                value.packageCreatedAtMillis == group.created && value.importedAtMillis == group.imported && value.recordDigest == group.hashes[index] &&
                historyMatches(group,index,value))
            immutable(sql,value,null)
        }
    }
    private fun historyMatches(group:Group,index:Int,value:RecordPackageOrigin):Boolean =
        value.restoredRevisionCount==group.revisionCounts[index] &&
            value.historyDigest==(if(group.history) group.historyDigests[index] else null)
    private fun erasureProof(group: Group, index: Int, entryId: String): String = receipt { out ->
        out.i32(if(group.history) 4 else 1); out.i32(3); out.string(group.id,36); out.raw(packageUnhex(group.digest))
        out.i32(index); out.string(entryId,36); out.raw(packageUnhex(group.hashes[index]))
        if(group.history) {out.i32(group.revisionCounts[index]);out.raw(packageUnhex(group.historyDigests[index]))}
    }
    private fun proofBackupId(state: String, entryId: String): String = readReceipt(state) { input ->
        val version=input.i32();require(version in listOf(1,4) && input.i32() == 3)
        val backupId = input.string(36).also(RecordPackages::uuid)
        input.raw(32); require(input.i32() in 0..19)
        require(input.string(36).also(RecordPackages::uuid) == entryId); input.raw(32)
        if(version==4) {require(input.i32() in 1..128);input.raw(32)}
        backupId
    }
    private inline fun <T> safely(block: () -> T): T = try { block() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: RecordPackageConflict) { throw failure }
        catch (failure: RecordPackageInvalid) { throw failure }
        catch (_: Exception) { throw RecordPackageStorageFailure() }
    companion object { private const val GROUP = "PACKAGE_RESTORE:"; private const val ORIGIN = "PACKAGE_ORIGIN:" }
}
