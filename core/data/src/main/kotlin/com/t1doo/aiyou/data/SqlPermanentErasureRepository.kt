package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Base64
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

/** SQL and files are deliberately separate durable phases. Both real file leases cover final SQL. */
internal class SqlPermanentErasureRepository(private val database: RecordsSqlDatabase,
    private val attachments: EncryptedAttachmentStore, private val edits: EncryptedOriginalEditStore,
    private val packages: SqlRecordPackageRepository, private val checkpoint: (String) -> Unit = {}) : PermanentErasureRepository {
    private class Stored(val receipt: PermanentErasureReceipt, val plan: String, val packageProof: String)
    override fun read(entryId: String): PermanentErasureReceipt? = safe {
        ManualTags.identifier(entryId); database.transaction { sql ->
            readWithin(sql,entryId)?.receipt?.also { if(it.complete) absent(sql,entryId) }
        }
    }
    override fun pending(limit: Int, afterEntryId: String?): PermanentErasurePage = safe {
        require(limit in 1..64)
        if (afterEntryId != null) ManualTags.identifier(afterEntryId)
        database.transaction { sql ->
            val rows = sql.query("SELECT entryId FROM permanent_erasure_receipts WHERE phase!='DONE' AND (? IS NULL OR entryId>?) ORDER BY entryId LIMIT ?",listOf(afterEntryId,afterEntryId,limit+1))
            var unreadable = 0
            val receipts = rows.take(limit).mapNotNull { row ->
                try { checkNotNull(readWithin(sql,row[0] as String)).receipt }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { unreadable++; null }
            }
            PermanentErasurePage(receipts,unreadable,if (rows.size > limit) rows[limit-1][0] as String else null)
        }
    }
    override suspend fun erase(intent: PermanentErasureIntent): PermanentErasureReceipt = try {
        val prepared = database.transaction { sql ->
            val existing = readWithin(sql,intent.source.sourceId)
            if (existing != null) {
                same(existing,intent)
                if (existing.receipt.complete) absent(sql,intent.source.sourceId) else target(sql,intent)
                return@transaction existing
            }
            if (sql.query("SELECT entryId FROM permanent_erasure_receipts WHERE mutationId=?",listOf(intent.mutationId)).isNotEmpty())
                throw PermanentErasureConflict()
            target(sql,intent)
            val proof = packages.prepareErasureProof(sql,intent.source.sourceId)
            sql.execute("INSERT INTO permanent_erasure_receipts(entryId,sourceRevision,generation,mutationId,phase,plan,packageProof) VALUES(?,?,?,?,'PREPARED','',?)",
                listOf(intent.source.sourceId,intent.source.revision,intent.generation,intent.mutationId,proof))
            checkNotNull(readWithin(sql,intent.source.sourceId))
        }
        if (prepared.receipt.complete) prepared.receipt else {
            checkpoint("prepared")
            attachments.withErasureLease { mediaLease -> edits.withErasureLease { editLease ->
                var stored = database.transaction { sql -> checkNotNull(readWithin(sql,intent.source.sourceId)).also {
                    same(it,intent); if (it.receipt.complete) absent(sql,intent.source.sourceId) else target(sql,intent)
                } }
                if (stored.receipt.complete) return@withErasureLease stored.receipt
                checkpoint("leased")
                if (stored.receipt.phase == PermanentErasurePhase.PREPARED) {
                    val budget = ErasureScanBudget()
                    val files = attachments.erasurePlan(intent.source.sourceId,mediaLease,budget) + edits.erasurePlan(intent.source.sourceId,editLease,budget)
                    val encoded = encode(intent,mediaLease,editLease,files)
                    database.transaction { sql ->
                        val current = checkNotNull(readWithin(sql,intent.source.sourceId)); same(current,intent); target(sql,intent)
                        if (current.receipt.phase != PermanentErasurePhase.PREPARED || current.plan != "" || current.packageProof != stored.packageProof)
                            throw PermanentErasureConflict()
                        check(sql.execute("UPDATE permanent_erasure_receipts SET phase='PLANNED',plan=? WHERE entryId=? AND phase='PREPARED' AND plan=''",listOf(encoded,intent.source.sourceId)) == 1)
                    }
                    checkpoint("planned-committed")
                    // Only an acknowledged exact readback authorizes unlink; lost commit ACK exits above.
                    stored = database.transaction { sql -> checkNotNull(readWithin(sql,intent.source.sourceId)).also {
                        same(it,intent); target(sql,intent)
                        if (it.receipt.phase != PermanentErasurePhase.PLANNED || it.plan != encoded || it.packageProof != prepared.packageProof)
                            throw PermanentErasureIncomplete()
                    } }
                }
                val files = decode(stored.plan,intent,mediaLease,editLease)
                database.transaction { sql ->
                    val verified = checkNotNull(readWithin(sql,intent.source.sourceId)); same(verified,intent); target(sql,intent)
                    if (verified.receipt.phase != PermanentErasurePhase.PLANNED || verified.plan != stored.plan || verified.packageProof != stored.packageProof)
                        throw PermanentErasureIncomplete()
                    val ids = sql.query("SELECT id FROM attachments WHERE entryId=? ORDER BY id LIMIT 65",listOf(intent.source.sourceId)).map { it.single() as String }
                    require(ids.size <= 64)
                    require(files.filter { it.bucket == EncryptedAttachmentStore.BUCKET }.all { it.name.substringBefore('.') in ids })
                }
                checkpoint("planned-readback")
                for (file in files) {
                    checkpoint("before-unlink")
                    (if (file.bucket == EncryptedAttachmentStore.BUCKET) mediaLease else editLease).erase(file)
                    checkpoint("unlinked")
                }
                mediaLease.sync(); editLease.sync()
                // Any new candidate, including an unknown partial, prevents a false DONE.
                val remaining = attachments.erasurePlan(intent.source.sourceId,mediaLease,ErasureScanBudget()) +
                    edits.erasurePlan(intent.source.sourceId,editLease,ErasureScanBudget())
                if (remaining.isNotEmpty()) throw PermanentErasureIncomplete()
                checkpoint("files-durable")
                database.transaction { sql ->
                    val current = checkNotNull(readWithin(sql,intent.source.sourceId)); same(current,intent); target(sql,intent)
                    if (current.receipt.phase != PermanentErasurePhase.PLANNED || current.plan != stored.plan || current.packageProof != stored.packageProof ||
                        packages.prepareErasureProof(sql,intent.source.sourceId) != current.packageProof) throw PermanentErasureConflict()
                    mediaLease.verify(); editLease.verify()
                    for (table in listOf("text_annotations","remote_text_jobs","entry_manual_tags","remote_source_access","local_jobs","attachments","local_search_fts","local_search_documents"))
                        sql.execute("DELETE FROM $table WHERE entryId=?",listOf(intent.source.sourceId))
                    sql.execute("DELETE FROM record_package_receipts WHERE operation='PACKAGE_ORIGIN' AND entryId=?",listOf(intent.source.sourceId))
                    sql.execute("DELETE FROM owned_file_creations WHERE entryId=?",listOf(intent.source.sourceId))
                    sql.execute("DELETE FROM entry_revisions WHERE entryId=?",listOf(intent.source.sourceId))
                    check(sql.execute("DELETE FROM entries WHERE id=?",listOf(intent.source.sourceId)) == 1)
                    check(sql.execute("UPDATE permanent_erasure_receipts SET phase='DONE',plan='' WHERE entryId=? AND phase='PLANNED' AND plan=?",listOf(intent.source.sourceId,stored.plan)) == 1)
                    absent(sql,intent.source.sourceId)
                    checkpoint("final-sql")
                }
                checkpoint("done-committed")
                database.transaction { sql -> checkNotNull(readWithin(sql,intent.source.sourceId)).also {
                    same(it,intent); check(it.receipt.complete); absent(sql,intent.source.sourceId)
                }.receipt }
            } }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (conflict: PermanentErasureConflict) { throw conflict }
      catch (_: Exception) { throw PermanentErasureIncomplete() }

    private fun same(stored: Stored, intent: PermanentErasureIntent) {
        val actual = stored.receipt.intent
        if (actual.source != intent.source || actual.generation != intent.generation || actual.mutationId != intent.mutationId) throw PermanentErasureConflict()
    }
    private fun target(sql: RecordsSqlConnection, intent: PermanentErasureIntent) {
        val row = sql.query("SELECT e.currentRevision,a.generation,a.deleted FROM entries e JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",listOf(intent.source.sourceId)).singleOrNull()
        if (row != listOf(intent.source.revision,intent.generation,1L)) throw PermanentErasureConflict()
    }
    private fun absent(sql: RecordsSqlConnection, id: String) {
        check(sql.query("SELECT id FROM entries WHERE id=?",listOf(id)).isEmpty())
        for (table in listOf("entry_revisions","attachments","entry_manual_tags","remote_source_access","local_jobs","text_annotations","remote_text_jobs","local_search_fts","local_search_documents","owned_file_creations"))
            check(sql.query("SELECT entryId FROM $table WHERE entryId=? LIMIT 1",listOf(id)).isEmpty())
        check(sql.query("SELECT id FROM record_package_receipts WHERE operation='PACKAGE_ORIGIN' AND entryId=?",listOf(id)).isEmpty())
    }
    private fun readWithin(sql: RecordsSqlConnection, id: String): Stored? {
        val row = sql.query("SELECT sourceRevision,generation,mutationId,phase,plan,packageProof FROM permanent_erasure_receipts WHERE entryId=?",listOf(id)).singleOrNull() ?: return null
        val phase = PermanentErasurePhase.valueOf(row[3] as String); val plan = row[4] as String
        require((phase == PermanentErasurePhase.PLANNED) == plan.isNotEmpty())
        require(plan.length <= 174764 && (row[5] as String).length <= 10934)
        return Stored(PermanentErasureReceipt(PermanentErasureIntent(SourceVersion(id,row[0] as Long),row[1] as Long,row[2] as String),phase),plan,row[5] as String)
    }
    private fun encode(intent: PermanentErasureIntent, media: ErasureDirectoryLease, edits: ErasureDirectoryLease,
        files: List<ErasureFileObservation>): String = PackageWriter(131072).use { out ->
        out.i32(1); out.string(intent.source.sourceId,1024); out.i64(intent.source.revision); out.i64(intent.generation); out.string(intent.mutationId,1024)
        out.string(media.identity,512); out.string(edits.identity,512); out.i32(files.size)
        files.sortedWith(compareBy({ it.bucket },{ it.name })).forEach {
            out.string(it.bucket,16); out.string(it.name,128); out.string(it.identity,512); out.i64(it.size); out.string(it.modified,64); out.raw(packageUnhex(it.digest))
        }
        val bytes = out.bytes(); try { Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
    }
    private fun decode(encoded: String, intent: PermanentErasureIntent, media: ErasureDirectoryLease, edits: ErasureDirectoryLease): List<ErasureFileObservation> {
        val bytes = Base64.getDecoder().decode(encoded)
        try {
            require(bytes.size <= 131072 && Base64.getEncoder().encodeToString(bytes) == encoded)
            val input = PackageReader(bytes); require(input.i32() == 1 && input.string(1024) == intent.source.sourceId &&
                input.i64() == intent.source.revision && input.i64() == intent.generation && input.string(1024) == intent.mutationId &&
                input.string(512) == media.identity && input.string(512) == edits.identity)
            val count = input.count(192)
            val canonical = MessageDigest.getInstance("SHA-256").digest(intent.source.sourceId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".edit"
            val files = List(count) {
                val bucket = input.string(16); val name = input.string(128)
                when (bucket) {
                    EncryptedAttachmentStore.BUCKET -> require(name.matches(Regex("[0-9a-f-]{36}\\.(tmp|aead)")) &&
                        UUID.fromString(name.substringBefore('.')).toString() == name.substringBefore('.'))
                    EncryptedOriginalEditStore.BUCKET -> require(name == canonical || (name.startsWith(canonical+".partial.") &&
                        UUID.fromString(name.removePrefix(canonical+".partial.")).toString() == name.removePrefix(canonical+".partial.")))
                    else -> throw PermanentErasureIncomplete()
                }
                ErasureFileObservation(bucket,name,input.string(512),input.i64().also { require(it in 0..16L*1024*1024+33) },input.string(64),packageHex(input.raw(32)))
            }
            input.end(); require(files.map { it.bucket to it.name }.distinct().size == files.size)
            return files
        } finally { bytes.fill(0) }
    }
    private inline fun <T> safe(action: () -> T): T = try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw PermanentErasureIncomplete() }
}
