package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.InputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException

/** Shares the existing SQL/key/file owners. No new schema, credential, permission or plaintext cache. */
internal class SqlRecordMediaPackageRepository(private val database: RecordsSqlDatabase,
    private val originals: OriginalExportRepository, private val media: EncryptedAttachmentStore,
    private val packages: SqlRecordPackageRepository) : RecordMediaPackageRepository {
    private val cipher = RecordMediaPackageCipher()
    override suspend fun preview(snapshot: OriginalExportSnapshot): RecordMediaPreview = safely {
        check(originals.isCurrent(snapshot))
        val images = inventory(snapshot)
        for (image in images) {
            val bytes = media.read(image.attachmentId)
            try { RecordMediaPackageCipher.checkImage(image,bytes) } finally { bytes.fill(0) }
        }
        check(originals.isCurrent(snapshot) && images == inventory(snapshot))
        RecordMediaPreview(snapshot,images)
    }
    override suspend fun isCurrent(preview: RecordMediaPreview): Boolean = try {
        originals.isCurrent(preview.snapshot) && preview.images == inventory(preview.snapshot)
    } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    override suspend fun seal(preview: RecordMediaPreview, password: CharArray): PreparedRecordMediaPackage {
        try {
            check(isCurrent(preview))
            return cipher.seal(preview,password,{ media.read(it.attachmentId) },{ isCurrent(preview) })
        } finally { password.fill('\u0000') }
    }
    override suspend fun open(source: () -> InputStream, password: CharArray): VerifiedRecordPackage = cipher.open(source,password)

    private fun inventory(snapshot: OriginalExportSnapshot): List<RecordPackageImage> = database.transaction { sql ->
        val images = mutableListOf<RecordPackageImage>()
        snapshot.records.forEach { record ->
            val rows = sql.query("SELECT id,mediaType,sha256,byteSize,state FROM attachments WHERE entryId=? AND revision=? ORDER BY id LIMIT 22",
                listOf(record.entry.id,record.entry.currentRevision))
            require(rows.size <= 21)
            rows.forEach { row ->
                // Every image in the selected current version must be READY. Never silently drop a damaged original.
                if (row[1] in setOf("image/jpeg","image/png")) {
                    require(row[4] == "READY")
                    images += RecordPackageImage(record.entry.id,record.entry.currentRevision,row[0] as String,row[1] as String,row[3] as Long,row[2] as String)
                    require(images.size <= RecordMediaPackages.MAX_IMAGES && images.sumOf { it.byteSize } <= RecordMediaPackages.MAX_IMAGE_TOTAL)
                } else throw RecordPackageConflict() // This batch cannot promise a complete copy of unsupported media.
            }
        }
        images
    }

    override suspend fun restore(verified: VerifiedRecordPackage, newIds: List<String>, importedAtMillis: Long): RecordPackageRestoreResult = safely {
        if (verified !is AuthenticatedMediaPackage) return@safely packages.restore(verified,newIds,importedAtMillis)
        val pack = verified
        val plan = database.transaction { sql ->
            val existing = journal(sql,pack.manifest.backupId)
            if (existing != null) {
                val parsed = decode(existing)
                validate(pack,parsed)
                if (parsed.phase == "ABORTED") {
                    val resumed = parsed.copy(phase="PENDING")
                    require(sql.execute("UPDATE record_package_receipts SET state=? WHERE id=? AND state=?",
                        listOf(encode(resumed),PREFIX+parsed.backupId,existing)) == 1)
                    resumed
                } else parsed
            } else {
                require(sql.query("SELECT id FROM record_package_receipts WHERE id=?",listOf("PACKAGE_RESTORE:"+pack.manifest.backupId)).isEmpty())
                require((sql.query("SELECT count(*) FROM record_package_receipts WHERE operation=?",listOf(OP)).single().single() as Long) < 4096)
                require(newIds.size == pack.records.size && newIds.distinct().size == newIds.size && importedAtMillis >= 0)
                newIds.forEach { id -> RecordPackages.uuid(id)
                    require(pack.records.none { it.sourceId == id } && sql.query("SELECT id FROM entries WHERE id=?",listOf(id)).isEmpty() && !SqlErasureFileHooks.fenced(sql,id)) }
                val intents = pack.images.map { image ->
                    val index = pack.records.indexOfFirst { it.sourceId == image.sourceId }
                    AttachmentIntent(UUID.randomUUID().toString(),newIds[index],1,image.mediaType,image.sha256,image.byteSize)
                }
                val value = Plan(pack.manifest.backupId,pack.digest,newIds.toList(),importedAtMillis,intents,"PENDING")
                sql.execute("INSERT INTO record_package_receipts(id,entryId,sourceRevision,operation,state) VALUES(?,?,1,?,?)",
                    listOf(PREFIX+value.backupId,value.ids.first(),OP,encode(value)))
                value
            }
        }
        if (plan.phase == "DONE") return@safely packages.restoreWithFiles(pack,plan.ids,plan.imported) {}
        database.transaction { sql ->
            require(sql.query("SELECT id FROM record_package_receipts WHERE id=?",listOf("PACKAGE_RESTORE:"+plan.backupId)).isEmpty())
            plan.ids.forEach { id -> require(sql.query("SELECT id FROM entries WHERE id=?",listOf(id)).isEmpty() && !SqlErasureFileHooks.fenced(sql,id)) }
        }
        media.importPackage(plan.intents,{ consume -> pack.cipher.consume(pack,consume) }) {
            packages.restoreWithFiles(pack,plan.ids,plan.imported) { sql ->
                require(journal(sql,plan.backupId) == encode(plan))
                plan.intents.forEach { i ->
                    sql.execute("INSERT INTO attachments(id,entryId,revision,mediaType,sha256,byteSize,state) VALUES(?,?,?,?,?,?,'READY')",
                        listOf(i.id,i.entryId,i.revision,i.mediaType,i.sha256,i.byteSize))
                }
                require(sql.execute("UPDATE record_package_receipts SET state=? WHERE id=? AND state=?",
                    listOf(encode(plan.copy(phase="DONE")),PREFIX+plan.backupId,encode(plan))) == 1)
            }
        }
    }

    override suspend fun recover(): Unit = safely {
        val plans = database.transaction { sql ->
            val rows = sql.query("SELECT state FROM record_package_receipts WHERE operation=? ORDER BY id LIMIT 4097",listOf(OP))
            require(rows.size <= 4096); rows.map { decode(it.single() as String) }.filter { it.phase == "PENDING" }
        }
        require(plans.size <= 64) // Bounded startup recovery; larger interrupted workloads fail visibly.
        for (plan in plans) {
            database.transaction { sql ->
                require(journal(sql,plan.backupId) == encode(plan))
                require(sql.query("SELECT id FROM record_package_receipts WHERE id=?",listOf("PACKAGE_RESTORE:"+plan.backupId)).isEmpty())
                plan.ids.forEach { require(sql.query("SELECT id FROM entries WHERE id=?",listOf(it)).isEmpty()) }
            }
            media.cleanupPackage(plan.intents)
            database.transaction { sql ->
                require(sql.execute("UPDATE record_package_receipts SET state=? WHERE id=? AND state=?",
                    listOf(encode(plan.copy(phase="ABORTED")),PREFIX+plan.backupId,encode(plan))) == 1)
            }
        }
    }
    private fun validate(pack: AuthenticatedMediaPackage, plan: Plan) {
        require(plan.backupId == pack.manifest.backupId && plan.digest == pack.digest && plan.ids.size == pack.records.size && plan.intents.size == pack.images.size)
        pack.images.forEachIndexed { index,image ->
            val i = plan.intents[index]; val record = pack.records.indexOfFirst { it.sourceId == image.sourceId }
            require(i.entryId == plan.ids[record] && i.revision == 1 && i.mediaType == image.mediaType && i.sha256 == image.sha256 && i.byteSize == image.byteSize)
        }
    }
    private data class Plan(val backupId: String, val digest: String, val ids: List<String>, val imported: Long,
        val intents: List<AttachmentIntent>, val phase: String)
    private fun encode(p: Plan): String = PackageWriter(32768).use { out ->
        out.i32(2); out.string(p.backupId,36); out.raw(packageUnhex(p.digest)); out.i64(p.imported)
        out.string(p.phase,16); out.strings(p.ids,20,36); out.i32(p.intents.size)
        p.intents.forEach { i -> out.string(i.id,36); out.string(i.entryId,36); out.string(i.mediaType,32); out.i64(i.byteSize); out.raw(packageUnhex(i.sha256)) }
        val raw = out.bytes(); try { Base64.getEncoder().encodeToString(raw) } finally { raw.fill(0) }
    }
    private fun decode(value: String): Plan {
        require(value.length <= 43692)
        val bytes = Base64.getDecoder().decode(value)
        try {
            require(bytes.size <= 32768 && Base64.getEncoder().encodeToString(bytes) == value)
            val r = PackageReader(bytes); require(r.i32() == 2)
            val id = r.string(36).also(RecordPackages::uuid); val digest = packageHex(r.raw(32)); val imported = r.i64(); require(imported >= 0)
            val phase = r.string(16); require(phase in setOf("PENDING","ABORTED","DONE"))
            val ids = r.strings(20,36); require(ids.isNotEmpty() && ids.distinct().size == ids.size); ids.forEach(RecordPackages::uuid)
            val intents = List(r.count(RecordMediaPackages.MAX_IMAGES)) {
                val attachment = r.string(36); val entry = r.string(36); val mime = r.string(32); val size = r.i64(); val hash = packageHex(r.raw(32))
                AttachmentIntent(attachment,entry,1,mime,hash,size).also { EncryptedAttachmentStore.validate(it); require(entry in ids && mime.startsWith("image/")) }
            }
            require(intents.map { it.id }.distinct().size == intents.size && intents.sumOf { it.byteSize } <= RecordMediaPackages.MAX_IMAGE_TOTAL)
            r.end(); return Plan(id,digest,ids,imported,intents,phase)
        } finally { bytes.fill(0) }
    }
    private fun journal(sql: RecordsSqlConnection, id: String): String? = sql.query(
        "SELECT state FROM record_package_receipts WHERE id=? AND operation=?",listOf(PREFIX+id,OP)).singleOrNull()?.single() as String?
    private suspend inline fun <T> safely(block: () -> T): T = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: RecordPackageInvalid) { throw e }
        catch (_: Exception) { throw RecordPackageStorageFailure() }
    companion object { private const val PREFIX = "PACKAGE_MEDIA:"; private const val OP = "PACKAGE_MEDIA_INTENT" }
}
