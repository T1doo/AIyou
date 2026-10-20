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
    private val packages: SqlRecordPackageRepository,
    private val activities:SqlRecordPackageActivities? = null,
    private val edits: EncryptedOriginalEditStore? = null,
    private val captureGate:CaptureDraftGate?=null) : RecordMediaPackageRepository {
    private val cipher = RecordMediaPackageCipher()
    override suspend fun preview(snapshot: OriginalExportSnapshot): RecordMediaPreview = checkedPreview(snapshot) { true }
    override suspend fun previewActivities(snapshot:OriginalExportSnapshot):RecordMediaPreview = safely {
        val base=checkedPreview(snapshot) {true}
        RecordMediaPreview(snapshot,base.images,checkNotNull(activities).capture(snapshot),audios=base.audios).also {
            val content=checkNotNull(it.activities)
            require(snapshot.records.isNotEmpty() || content.tasks.isNotEmpty() || content.drafts.isNotEmpty())
            check(isCurrent(it))
        }
    }
    override suspend fun previewHistory(snapshot: OriginalExportSnapshot, includeActivities: Boolean): RecordMediaPreview = safely {
        require(snapshot.records.isNotEmpty())
        check(originals.isCurrent(snapshot))
        val history = history(snapshot)
        val images = inventory(snapshot, history)
        for (image in images) {
            val bytes = media.read(image.attachmentId)
            try { RecordMediaPackageCipher.checkImage(image, bytes) } finally { bytes.fill(0) }
        }
        val preview = RecordMediaPreview(snapshot, images.filterIsInstance<RecordPackageImage>(),
            if (includeActivities) checkNotNull(activities).capture(snapshot) else null, history,audios=images.filterIsInstance<RecordPackageAudio>())
        check(isCurrent(preview))
        preview
    }
    override suspend fun previewOpenZip(snapshot: OriginalExportSnapshot, active: () -> Boolean): RecordMediaPreview = checkedPreview(snapshot, active)
    override suspend fun previewEditDrafts(snapshot: OriginalExportSnapshot, includeActivities: Boolean): RecordMediaPreview = safely {
        val base = previewHistory(snapshot, includeActivities)
        RecordMediaPreview(snapshot, base.images, base.activities, base.history, captureEdits(snapshot),base.audios).also {
            check(isCurrent(it))
        }
    }
    private suspend fun captureEdits(snapshot: OriginalExportSnapshot): List<OriginalEditDraft> =
        checkNotNull(edits).packageFiles { lease -> snapshot.records.mapNotNull { record ->
            lease.read(record.entry.id)?.takeIf { it.state == OriginalEditDraftState.ACTIVE }
        } }
    private suspend fun checkedPreview(snapshot: OriginalExportSnapshot, active: () -> Boolean): RecordMediaPreview = safely {
        if (!active()) throw OpenRecordZipCancelled()
        check(originals.isCurrent(snapshot))
        val images = inventory(snapshot)
        for (image in images) {
            if (!active()) throw OpenRecordZipCancelled()
            val bytes = media.read(image.attachmentId)
            try { RecordMediaPackageCipher.checkImage(image,bytes) } finally { bytes.fill(0) }
            if (!active()) throw OpenRecordZipCancelled()
        }
        check(originals.isCurrent(snapshot) && images == inventory(snapshot))
        RecordMediaPreview(snapshot,images.filterIsInstance<RecordPackageImage>(),audios=images.filterIsInstance<RecordPackageAudio>())
    }
    override suspend fun isCurrent(preview: RecordMediaPreview): Boolean = try {
        originals.isCurrent(preview.snapshot) &&
            (preview.history?.let { sameHistory(it, history(preview.snapshot)) } ?: true) &&
            preview.media == inventory(preview.snapshot, preview.history) &&
            (preview.editDrafts?.let { it == captureEdits(preview.snapshot) } ?: true) &&
            (preview.activities?.let {checkNotNull(activities).current(preview.snapshot,it)} ?: true)
    } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    override suspend fun seal(preview: RecordMediaPreview, password: CharArray): PreparedRecordMediaPackage {
        try {
            check(isCurrent(preview))
            return cipher.seal(preview,password,{ media.read(it.attachmentId) },{ isCurrent(preview) })
        } finally { password.fill('\u0000') }
    }
    override suspend fun exportOpenZip(preview: RecordMediaPreview): PreparedRecordMediaPackage {
        if (preview.audios.isNotEmpty() || preview.history != null || preview.editDrafts != null || !isCurrent(preview)) throw OriginalExportConflict()
        return OpenRecordZipCodec.prepare(preview, { image ->
            val bytes = media.read(image.attachmentId)
            try { RecordMediaPackageCipher.checkImage(image, bytes); bytes }
            catch (failure: Exception) { bytes.fill(0); throw failure }
        }, { isCurrent(preview) })
    }
    override suspend fun readOpenImage(preview: RecordMediaPreview, image: RecordPackageImage): ByteArray {
        if (preview.history != null || image !in preview.images || !isCurrent(preview)) throw OriginalExportConflict()
        val bytes = media.read(image.attachmentId)
        try {
            RecordMediaPackageCipher.checkImage(image, bytes)
            if (!isCurrent(preview)) throw OriginalExportConflict()
            return bytes
        } catch (failure: Exception) { bytes.fill(0); throw failure }
    }
    override suspend fun open(source: () -> InputStream, password: CharArray): VerifiedRecordPackage = cipher.open(source,password)

    /** Snapshot the entire chain in one SQL transaction. Bounds are checked before reading text rows. */
    private fun history(snapshot: OriginalExportSnapshot): List<RecordPackageOriginal> = database.transaction { sql ->
        var revisions = 0L; var textBytes = 0L
        snapshot.records.map { record ->
            val size = sql.query("SELECT count(*),COALESCE(SUM(length(CAST(originalText AS BLOB))),0) FROM entry_revisions WHERE entryId=?",
                listOf(record.entry.id)).single()
            val count = size[0] as Long
            revisions = Math.addExact(revisions, count); textBytes = Math.addExact(textBytes, size[1] as Long)
            require(count in 1..RecordHistoryPackages.MAX_REVISIONS_PER_RECORD.toLong() && count == record.entry.currentRevision.toLong() &&
                revisions <= RecordHistoryPackages.MAX_REVISIONS && textBytes <= RecordHistoryPackages.MAX_TEXT_BYTES)
            val pointer = sql.query("SELECT currentRevision,capturedAtMillis,captureTimeZone FROM entries WHERE id=?",
                listOf(record.entry.id)).single()
            require(pointer == listOf(record.entry.currentRevision.toLong(), record.entry.capturedAtMillis, record.entry.captureTimeZone))
            val chain = sql.query("SELECT revision,originalText,contentHash,createdAtMillis FROM entry_revisions WHERE entryId=? ORDER BY revision LIMIT ?",
                listOf(record.entry.id, RecordHistoryPackages.MAX_REVISIONS_PER_RECORD + 1)).map {
                RecordPackageRevision(Math.toIntExact(it[0] as Long), it[1] as String, it[2] as String, it[3] as Long)
            }
            RecordPackageOriginal(record.entry.id, record.entry.currentRevision, record.entry.capturedAtMillis,
                record.entry.captureTimeZone, record.entry.originalText, record.contentHash, record.tags.version,
                record.tags.names, true, chain, record.eventDate.date, record.people.version, record.people.names, record.people.aliases)
        }
    }
    private fun sameHistory(a: List<RecordPackageOriginal>, b: List<RecordPackageOriginal>): Boolean = a.size == b.size &&
        a.zip(b).all { (old, now) -> old.sourceId == now.sourceId && old.sourceRevision == now.sourceRevision &&
            old.capturedAtMillis == now.capturedAtMillis && old.captureTimeZone == now.captureTimeZone &&
            old.originalText == now.originalText && old.contentHash == now.contentHash && old.sourceTagVersion == now.sourceTagVersion &&
            old.tags == now.tags && old.eventDate == now.eventDate && old.sourcePeopleVersion == now.sourcePeopleVersion && old.people == now.people && old.peopleAliases == now.peopleAliases && old.history.size == now.history.size && old.history.zip(now.history).all { (a, b) ->
                a.revision == b.revision && a.originalText == b.originalText && a.contentHash == b.contentHash && a.createdAtMillis == b.createdAtMillis } }

    private fun inventory(snapshot: OriginalExportSnapshot, history: List<RecordPackageOriginal>? = null): List<RecordPackageAttachment> = database.transaction { sql ->
        val images = mutableListOf<RecordPackageAttachment>()
        val versions = history?.flatMap { record -> record.history.map { record.sourceId to it.revision } }
            ?: snapshot.records.map { it.entry.id to it.entry.currentRevision }
        versions.forEach { (sourceId, revision) ->
            val rows = sql.query("SELECT id,mediaType,sha256,byteSize,state FROM attachments WHERE entryId=? AND revision=? ORDER BY id LIMIT 22",
                listOf(sourceId,revision))
            require(rows.size <= 21)
            rows.forEach { row ->
                // Every image in the selected current version must be READY. Never silently drop a damaged original.
                if (row[1] in setOf("image/jpeg","image/png","audio/wav")) {
                    require(row[4] == "READY")
                    images += RecordPackageAttachments.descriptor(sourceId,revision,row[0] as String,row[1] as String,row[3] as Long,row[2] as String)
                    require(images.size <= RecordMediaPackages.MAX_IMAGES && images.sumOf { it.byteSize } <= RecordMediaPackages.MAX_IMAGE_TOTAL)
                } else throw RecordPackageConflict() // This batch cannot promise a complete copy of unsupported media.
            }
        }
        images.filterIsInstance<RecordPackageImage>()+images.filterIsInstance<RecordPackageAudio>()
    }

    override suspend fun restore(verified:VerifiedRecordPackage,newIds:List<String>,importedAtMillis:Long):RecordPackageRestoreResult =
        captureGate?.mutation { restoreOwned(verified,newIds,importedAtMillis) } ?: restoreOwned(verified,newIds,importedAtMillis)
    private suspend fun restoreOwned(verified: VerifiedRecordPackage, newIds: List<String>, importedAtMillis: Long): RecordPackageRestoreResult = safely {
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
                val intents = pack.media.map { image ->
                    val index = pack.records.indexOfFirst { it.sourceId == image.sourceId }
                    require(index >= 0)
                    AttachmentIntent(UUID.randomUUID().toString(),newIds[index],if (pack.includesHistory) image.sourceRevision else 1,
                        image.mediaType,image.sha256,image.byteSize)
                }
                val editIntents = pack.editDrafts.orEmpty().map { draft ->
                    val mapped = draft.copy(id = UUID.randomUUID().toString(), entryId = newIds[pack.records.indexOfFirst { it.sourceId == draft.entryId }], version = 1)
                    OriginalEditImportIntent(mapped.id, mapped.entryId, RecordEditDraftPackageCodec.digest(mapped))
                }
                val value = Plan(pack.manifest.backupId,pack.digest,newIds.toList(),importedAtMillis,intents,"PENDING",
                    if (pack.editDrafts != null) 5 else if (pack.includesHistory) 4 else if (newIds.isEmpty()) 3 else 2, editIntents)
                sql.execute("INSERT INTO record_package_receipts(id,entryId,sourceRevision,operation,state) VALUES(?,?,1,?,?)",
                    listOf(PREFIX+value.backupId,value.ids.firstOrNull() ?: SqlRecordPackageActivities.anchor(value.backupId),OP,encode(value)))
                value
            }
        }
        if (plan.phase == "DONE") return@safely replay(pack, plan)
        val committed=database.transaction { sql ->
            val complete=sql.query("SELECT id FROM record_package_receipts WHERE id=?",listOf("PACKAGE_RESTORE:"+plan.backupId)).isNotEmpty()
            if(!complete) plan.ids.forEach { id -> require(sql.query("SELECT id FROM entries WHERE id=?",listOf(id)).isEmpty() && !SqlErasureFileHooks.fenced(sql,id)) }
            complete
        }
        if(committed) return@safely replay(pack, plan)
        val commit: (List<List<Any?>>?) -> RecordPackageRestoreResult = { expectedSources ->
            packages.restoreWithFiles(pack,plan.ids,plan.imported) { sql ->
                require(journal(sql,plan.backupId) == encode(plan))
                if (expectedSources != null) require(sourceRows(sql, pack) == expectedSources)
                plan.intents.forEach { i ->
                    sql.execute("INSERT INTO attachments(id,entryId,revision,mediaType,sha256,byteSize,state) VALUES(?,?,?,?,?,?,'READY')",
                        listOf(i.id,i.entryId,i.revision,i.mediaType,i.sha256,i.byteSize))
                }
                require(sql.execute("UPDATE record_package_receipts SET state=? WHERE id=? AND state=?",
                    listOf(encode(plan.copy(phase="DONE")),PREFIX+plan.backupId,encode(plan))) == 1)
            }
        }
        if (pack.editDrafts != null) {
            // Always take both leases, even for a text-only batch, matching permanent erasure/recovery.
            media.importPackage(plan.intents, { consume -> pack.cipher.consume(pack, consume) }) {
                checkNotNull(edits).packageFiles { lease ->
                    // A competing importer may have committed while we acquired the file locks.
                    val complete = database.transaction { sql ->
                        require(journal(sql, plan.backupId) in listOf(encode(plan), encode(plan.copy(phase = "DONE"))))
                        sql.query("SELECT id FROM record_package_receipts WHERE id=?", listOf("PACKAGE_RESTORE:" + plan.backupId)).isNotEmpty()
                    }
                    if (complete) packages.restoreWithFiles(pack, plan.ids, plan.imported) {} else {
                        val sources = database.transaction { sourceRows(it, pack) }
                        pack.editDrafts.orEmpty().forEach { source ->
                            val local = lease.read(source.entryId)
                            val sourceIndex = pack.records.indexOfFirst { it.sourceId == source.entryId }
                            require(if (sources[sourceIndex].isNotEmpty()) local == source else local == null)
                        }
                        plan.ids.forEach { id -> database.transaction { sql ->
                            require(sql.query("SELECT id FROM entries WHERE id=?", listOf(id)).isEmpty() && !SqlErasureFileHooks.fenced(sql,id))
                        } }
                        pack.editDrafts.orEmpty().forEachIndexed { i, source ->
                            val intent = plan.editIntents[i]
                            lease.install(source.copy(id = intent.id, entryId = intent.entryId, version = 1))
                        }
                        commit(sources)
                    }
                }
            }
        } else if(plan.intents.isEmpty()) {
            // No files exist in this batch. Still authenticate every frame and exact EOF before SQL.
            pack.cipher.consume(pack) {_,_->throw RecordPackageInvalid()}
            commit(null)
        } else media.importPackage(plan.intents,{ consume -> pack.cipher.consume(pack,consume) }) { commit(null) }
    }

    /** Only first import checks local source eligibility. Replays never restore draft bytes. */
    private fun sourceRows(sql: RecordsSqlConnection, pack: AuthenticatedMediaPackage): List<List<Any?>> =
        pack.records.map { record ->
            require(!SqlErasureFileHooks.fenced(sql, record.sourceId))
            val row = sql.query("SELECT e.currentRevision,COALESCE(a.generation,0),COALESCE(a.deleted,0) FROM entries e LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",
                listOf(record.sourceId)).singleOrNull().orEmpty()
            if (row.isNotEmpty()) require(row[0] == record.sourceRevision.toLong() && row[2] == 0L)
            row
        }

    /** History replay verifies the originally imported media without replacing missing files.
     * Erasure receipts remain authoritative; deleted members must never be recreated for this check. */
    private suspend fun replay(pack: AuthenticatedMediaPackage, plan: Plan): RecordPackageRestoreResult {
        val result = packages.restoreWithFiles(pack, plan.ids, plan.imported) {}
        if (!pack.includesHistory) return result
        plan.intents.forEachIndexed { index, intent ->
            val live = database.transaction { sql ->
                if (SqlErasureFileHooks.fenced(sql, intent.entryId)) false else {
                    require(sql.query("SELECT entryId,revision,mediaType,sha256,byteSize,state FROM attachments WHERE id=?",
                        listOf(intent.id)).single() == listOf(intent.entryId, intent.revision.toLong(), intent.mediaType,
                        intent.sha256, intent.byteSize, "READY"))
                    true
                }
            }
            if (live) {
                try {
                    val bytes = media.read(intent.id)
                    try { RecordMediaPackageCipher.checkImage(pack.media[index], bytes) } finally { bytes.fill(0) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (!database.transaction { SqlErasureFileHooks.fenced(it, intent.entryId) }) throw failure
                }
            }
        }
        return packages.restoreWithFiles(pack, plan.ids, plan.imported) {}
    }

    override suspend fun recover(): Unit = safely {
        val plans = database.transaction { sql ->
            val rows = sql.query("SELECT state FROM record_package_receipts WHERE operation=? ORDER BY id LIMIT 4097",listOf(OP))
            require(rows.size <= 4096); rows.map { decode(it.single() as String) }.filter { it.phase == "PENDING" }
        }
        require(plans.size <= 64) // Bounded startup recovery; larger interrupted workloads fail visibly.
        for (plan in plans) {
            if (plan.version >= 5) {
                media.packageFiles { mediaLease -> checkNotNull(edits).packageFiles { editLease ->
                    database.transaction { sql ->
                        require(journal(sql,plan.backupId) == encode(plan))
                        require(sql.query("SELECT id FROM record_package_receipts WHERE id=?",listOf("PACKAGE_RESTORE:"+plan.backupId)).isEmpty())
                        plan.ids.forEach { require(sql.query("SELECT id FROM entries WHERE id=?",listOf(it)).isEmpty()) }
                    }
                    media.cleanupPackageUnderLease(plan.intents, mediaLease)
                    plan.editIntents.forEach(editLease::cleanup)
                    database.transaction { sql ->
                        require(sql.execute("UPDATE record_package_receipts SET state=? WHERE id=? AND state=?",
                            listOf(encode(plan.copy(phase="ABORTED")),PREFIX+plan.backupId,encode(plan))) == 1)
                    }
                } }
                continue
            }
            database.transaction { sql ->
                require(journal(sql,plan.backupId) == encode(plan))
                require(sql.query("SELECT id FROM record_package_receipts WHERE id=?",listOf("PACKAGE_RESTORE:"+plan.backupId)).isEmpty())
                plan.ids.forEach { require(sql.query("SELECT id FROM entries WHERE id=?",listOf(it)).isEmpty()) }
            }
            if(plan.intents.isNotEmpty()) media.cleanupPackage(plan.intents)
            database.transaction { sql ->
                require(sql.execute("UPDATE record_package_receipts SET state=? WHERE id=? AND state=?",
                    listOf(encode(plan.copy(phase="ABORTED")),PREFIX+plan.backupId,encode(plan))) == 1)
            }
        }
    }
    private fun validate(pack: AuthenticatedMediaPackage, plan: Plan) {
        require(plan.backupId == pack.manifest.backupId && plan.digest == pack.digest && plan.ids.size == pack.records.size && plan.intents.size == pack.media.size)
        pack.media.forEachIndexed { index,image ->
            val i = plan.intents[index]; val record = pack.records.indexOfFirst { it.sourceId == image.sourceId }
            require(record >= 0 && i.entryId == plan.ids[record] && i.revision == (if (pack.includesHistory) image.sourceRevision else 1) &&
                i.mediaType == image.mediaType && i.sha256 == image.sha256 && i.byteSize == image.byteSize)
        }
        require((plan.version >= 5) == (pack.editDrafts != null) && plan.editIntents.size == pack.editDrafts.orEmpty().size)
        pack.editDrafts.orEmpty().forEachIndexed { index, draft ->
            val intent = plan.editIntents[index]
            require(intent.entryId == plan.ids[pack.records.indexOfFirst { it.sourceId == draft.entryId }] &&
                intent.digest == RecordEditDraftPackageCodec.digest(draft.copy(id = intent.id, entryId = intent.entryId, version = 1)))
        }
    }
    private data class Plan(val backupId: String, val digest: String, val ids: List<String>, val imported: Long,
        val intents: List<AttachmentIntent>, val phase: String, val version: Int = 4,
        val editIntents: List<OriginalEditImportIntent> = emptyList())
    private fun encode(p: Plan): String = PackageWriter(32768).use { out ->
        out.i32(p.version); out.string(p.backupId,36); out.raw(packageUnhex(p.digest)); out.i64(p.imported)
        out.string(p.phase,16); out.strings(p.ids,20,36); out.i32(p.intents.size)
        p.intents.forEach { i -> out.string(i.id,36); out.string(i.entryId,36)
            if (p.version >= 4) out.i32(i.revision)
            out.string(i.mediaType,32); out.i64(i.byteSize); out.raw(packageUnhex(i.sha256)) }
        if (p.version >= 5) {
            out.i32(p.editIntents.size)
            p.editIntents.forEach { out.string(it.id,36); out.string(it.entryId,36); out.raw(packageUnhex(it.digest)) }
        }
        val raw=out.bytes()
        try {
            if(p.intents.none { it.mediaType=="audio/wav" }) Base64.getEncoder().encodeToString(raw)
            else PackageWriter(32768).use { envelope ->
                envelope.i32(6);envelope.i32(raw.size);envelope.raw(raw)
                val framed=envelope.bytes();try { Base64.getEncoder().encodeToString(framed) } finally { framed.fill(0) }
            }
        } finally { raw.fill(0) }
    }
    internal fun validateLogicalForFullBackup() { packages.origins() }
    internal fun validatePortableLedger(data:FullBackupData) { packages.validateFullBackupLedger(data) }
    internal fun validateCompletedForFullBackup(value:String) { require(decode(value).phase=="DONE") }
    internal fun validateCompletedForFullBackup(row:List<Any?>) {
        val p=decode(row[4] as String)
        require(p.phase=="DONE" && row.take(4)==listOf(PREFIX+p.backupId,p.ids.firstOrNull() ?: SqlRecordPackageActivities.anchor(p.backupId),1L,OP))
    }
    private fun decode(value: String): Plan {
        require(value.length <= 43692)
        val bytes = Base64.getDecoder().decode(value)
        var inner:ByteArray?=null
        try {
            require(bytes.size <= 32768 && Base64.getEncoder().encodeToString(bytes) == value)
            val outer=PackageReader(bytes);val audioEnvelope=outer.i32()==6
            if(audioEnvelope) { inner=outer.raw(outer.i32(),32760);outer.end() }
            val r=PackageReader(inner ?: bytes);val version=r.i32(); require(version in listOf(2,3,4,5))
            val id = r.string(36).also(RecordPackages::uuid); val digest = packageHex(r.raw(32)); val imported = r.i64(); require(imported >= 0)
            val phase = r.string(16); require(phase in setOf("PENDING","ABORTED","DONE"))
            val ids = r.strings(20,36); require((ids.isNotEmpty() || version>=3) && ids.distinct().size == ids.size); ids.forEach(RecordPackages::uuid)
            val intents = List(r.count(RecordMediaPackages.MAX_IMAGES)) {
                val attachment = r.string(36); val entry = r.string(36)
                val revision = if (version >= 4) r.i32() else 1
                require(revision in 1..RecordHistoryPackages.MAX_REVISIONS_PER_RECORD)
                val mime = r.string(32); val size = r.i64(); val hash = packageHex(r.raw(32))
                AttachmentIntent(attachment,entry,revision,mime,hash,size).also { EncryptedAttachmentStore.validate(it); require(entry in ids && (mime.startsWith("image/") || (audioEnvelope && mime=="audio/wav"))) }
            }
            require(intents.map { it.id }.distinct().size == intents.size && intents.sumOf { it.byteSize } <= RecordMediaPackages.MAX_IMAGE_TOTAL)
            val editIntents = if (version >= 5) List(r.count(20)) { OriginalEditImportIntent(r.string(36),r.string(36),packageHex(r.raw(32))) } else emptyList()
            require(editIntents.map { it.id }.distinct().size == editIntents.size &&
                editIntents.map { it.entryId }.distinct().size == editIntents.size && editIntents.all { it.entryId in ids })
            require(!audioEnvelope || intents.any { it.mediaType=="audio/wav" })
            r.end(); return Plan(id,digest,ids,imported,intents,phase,version,editIntents)
        } finally { inner?.fill(0);bytes.fill(0) }
    }
    private fun journal(sql: RecordsSqlConnection, id: String): String? = sql.query(
        "SELECT state FROM record_package_receipts WHERE id=? AND operation=?",listOf(PREFIX+id,OP)).singleOrNull()?.single() as String?
    private suspend inline fun <T> safely(block: () -> T): T = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: OpenRecordZipCancelled) { throw e }
        catch (e: RecordPackageInvalid) { throw e }
        catch (_: Exception) { throw RecordPackageStorageFailure() }
    companion object { private const val PREFIX = "PACKAGE_MEDIA:"; private const val OP = "PACKAGE_MEDIA_INTENT" }
}
