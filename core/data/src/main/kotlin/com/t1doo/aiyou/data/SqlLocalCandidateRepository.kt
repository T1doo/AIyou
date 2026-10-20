package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.ZoneId
import java.util.concurrent.CancellationException

/** Source proof, whole-batch CAS and authenticated receipt share one records transaction. */
internal class SqlLocalCandidateRepository(private val database: RecordsSqlDatabase, key: ByteArray) : LocalCandidateRepository {
    private val cipher = LocalCandidateCipher(key)
    private data class Source(val version: SourceVersion, val hash: String, val generation: Long,
        val original: String, val captured: Long, val zone: String)
    private data class Row(val id: String, val source: SourceVersion, val hash: String, val generation: Long,
        val rule: Int, val version: Long, val state: LocalCandidateState, val mutation: String,
        val created: Long, val bytes: ByteArray)

    override fun begin(request: LocalCandidateStart): LocalCandidateJob = safe {
        database.transaction { sql ->
            val source = readSource(sql, request.source)
            val old = row(sql, request.id)
            if (old != null) {
                bind(old, source)
                if (old.created != request.requestedAtMillis || old.state !in setOf(LocalCandidateState.RUNNING, LocalCandidateState.READY))
                    throw LocalCandidateConflict()
                payload(old, source)
            } else {
                val initial = Row(request.id, source.version, source.hash, source.generation, LocalCandidates.RULE_VERSION,
                    0, LocalCandidateState.RUNNING, request.id, request.requestedAtMillis, byteArrayOf())
                val bytes = cipher.seal(aad(initial), LocalCandidateCipher.Payload(emptyList(), "START", 0, "", request.requestedAtMillis))
                sql.execute("INSERT INTO local_candidate_batches(" + COLUMNS + ") VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    listOf(initial.id, initial.source.sourceId, initial.source.revision, initial.hash, initial.generation,
                        initial.rule, initial.version, initial.state.name, initial.mutation, initial.created, bytes))
                payload(checkNotNull(row(sql, request.id)), source)
            }
            LocalCandidateJob(request, source.hash, source.generation, source.original, source.captured, source.zone)
        }
    }

    override fun resume(expected: LocalCandidateBatch): LocalCandidateJob = safe {
        database.transaction { sql ->
            if (expected.state != LocalCandidateState.RUNNING || expected.version != 0L || expected.items.isNotEmpty())
                throw LocalCandidateConflict()
            val source = readSource(sql, expected.source)
            val old = row(sql, expected.id) ?: throw LocalCandidateConflict()
            bindRequest(old, source, expected.sourceHash, expected.generation)
            if (old.state != expected.state || old.version != expected.version || old.created != expected.createdAtMillis)
                throw LocalCandidateConflict()
            payload(old, source)
            LocalCandidateJob(LocalCandidateStart(old.id, old.source, old.created), source.hash, source.generation,
                source.original, source.captured, source.zone)
        }
    }

    override fun complete(job: LocalCandidateJob, document: ExcerptDocument): LocalCandidateBatch = safe {
        database.transaction { sql ->
            val source = readSource(sql, job.start.source)
            val old = row(sql, job.start.id) ?: throw LocalCandidateConflict()
            bind(old, source)
            if (old.created != job.start.requestedAtMillis || job.sourceHash != source.hash || job.generation != source.generation ||
                job.original != source.original || job.capturedAtMillis != source.captured || job.captureTimeZone != source.zone)
                throw LocalCandidateConflict()
            try { LocalExcerptRules.validate(document, source.version, source.hash, source.original) }
            catch (_: IllegalArgumentException) { throw InvalidExcerptResponse() }
            val items = document.proposals.mapIndexed { index, proposal ->
                ReviewedExcerpt("excerpt-" + (index + 1), proposal, ExcerptReview.PROPOSED)
            }
            val expected = LocalCandidateCipher.Payload(items, "COMPLETE", 0, "", old.created)
            if (old.state == LocalCandidateState.READY && old.version == 1L && old.mutation == old.id) {
                if (payload(old, source) != expected) throw LocalCandidateConflict()
                return@transaction snapshot(old, items)
            }
            if (old.state != LocalCandidateState.RUNNING || old.version != 0L) throw LocalCandidateConflict()
            payload(old, source)
            write(sql, old, LocalCandidateState.READY, old.id, expected, source)
        }
    }

    override fun batches(source: SourceVersion): List<LocalCandidateBatch> = safe {
        database.transaction { sql ->
            val current = readSource(sql, source)
            sql.query("SELECT " + COLUMNS + " FROM local_candidate_batches WHERE entryId=? AND ruleVersion=1 ORDER BY createdAtMillis DESC,id LIMIT 20",
                listOf(source.sourceId)).map { cells ->
                val value = decode(cells)
                if (value.state == LocalCandidateState.INVALIDATED || !matches(value, current))
                    snapshot(value, emptyList(), LocalCandidateState.INVALIDATED)
                else snapshot(value, payload(value, current).items)
            }
        }
    }

    override fun review(request: ExcerptReviewRequest): LocalCandidateBatch = safe {
        database.transaction { sql ->
            val source = readSource(sql, request.source)
            val old = row(sql, request.batchId) ?: throw LocalCandidateConflict()
            bindRequest(old, source, request.sourceHash, request.generation)
            if (old.state != LocalCandidateState.READY) throw LocalCandidateConflict()
            val previous = payload(old, source)
            val expectedReceipt = LocalCandidateCipher.Payload(previous.items, request.decision.name, request.expectedVersion,
                request.itemId, request.requestedAtMillis)
            if (old.mutation == request.mutationId) {
                if (old.version != request.expectedVersion + 1 || previous != expectedReceipt ||
                    previous.items.singleOrNull { it.id == request.itemId }?.review != request.decision) throw LocalCandidateConflict()
                return@transaction snapshot(old, previous.items)
            }
            if (old.version != request.expectedVersion ||
                previous.items.singleOrNull { it.id == request.itemId }?.review != ExcerptReview.PROPOSED) throw LocalCandidateConflict()
            val items = previous.items.map { if (it.id == request.itemId) it.copy(review = request.decision) else it }
            write(sql, old, LocalCandidateState.READY, request.mutationId, expectedReceipt.copy(items = items), source)
        }
    }

    override fun reopen(request: ExcerptReopenRequest): LocalCandidateBatch = safe {
        database.transaction { sql ->
            val source = readSource(sql, request.source)
            val old = row(sql, request.batchId) ?: throw LocalCandidateConflict()
            bindRequest(old, source, request.sourceHash, request.generation)
            if (old.state != LocalCandidateState.READY) throw LocalCandidateConflict()
            val previous = payload(old, source)
            val receipt = LocalCandidateCipher.Payload(previous.items, "REOPEN_" + request.previousReview.name,
                request.expectedVersion, request.itemId, request.requestedAtMillis)
            if (old.mutation == request.mutationId) {
                if (old.version != request.expectedVersion + 1 || previous != receipt ||
                    previous.items.singleOrNull { it.id == request.itemId }?.review != ExcerptReview.PROPOSED)
                    throw LocalCandidateConflict()
                return@transaction snapshot(old, previous.items)
            }
            if (old.version != request.expectedVersion ||
                previous.items.singleOrNull { it.id == request.itemId }?.review != request.previousReview)
                throw LocalCandidateConflict()
            val items = previous.items.map { if (it.id == request.itemId) it.copy(review = ExcerptReview.PROPOSED) else it }
            write(sql, old, LocalCandidateState.READY, request.mutationId, receipt.copy(items = items), source)
        }
    }

    override fun cancel(request: CandidateCancelRequest): LocalCandidateBatch = safe {
        database.transaction { sql ->
            val source = readSource(sql, request.source)
            val old = row(sql, request.batchId) ?: throw LocalCandidateConflict()
            bindRequest(old, source, request.sourceHash, request.generation)
            val previous = payload(old, source)
            val receipt = LocalCandidateCipher.Payload(emptyList(), "CANCEL", request.expectedVersion, "", request.requestedAtMillis)
            if (old.mutation == request.mutationId) {
                if (old.version != request.expectedVersion + 1 || old.state != LocalCandidateState.CANCELLED || previous != receipt)
                    throw LocalCandidateConflict()
                return@transaction snapshot(old, emptyList())
            }
            if (old.version != request.expectedVersion || old.state !in setOf(LocalCandidateState.RUNNING, LocalCandidateState.READY) ||
                previous.items.any { it.review != ExcerptReview.PROPOSED }) throw LocalCandidateConflict()
            write(sql, old, LocalCandidateState.CANCELLED, request.mutationId, receipt, source)
        }
    }

    private fun readSource(sql: RecordsSqlConnection, expected: SourceVersion): Source {
        val r = sql.query("SELECT e.currentRevision,r.originalText,r.contentHash,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy,COALESCE(a.generation,0),COALESCE(a.deleted,0) FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",
            listOf(expected.sourceId)).singleOrNull() ?: throw LocalCandidateConflict()
        if (r[0] != expected.revision || r[7] != 0L || r[5] == ProcessingPolicy.AI_NONE.name ||
            sql.query("SELECT entryId FROM permanent_erasure_receipts WHERE entryId=? LIMIT 1", listOf(expected.sourceId)).isNotEmpty())
            throw LocalCandidateConflict()
        require(r[5] in setOf(ProcessingPolicy.LOCAL_ONLY.name, ProcessingPolicy.REMOTE_ALLOWED.name))
        val text = r[1] as String; val hash = r[2] as String; val generation = r[6] as Long
        require(text.isNotBlank() && text.length <= 100_000 && OriginalExports.hash(text) == hash && generation >= 0)
        val captured = r[3] as Long; val zone = r[4] as String; require(captured >= 0); ZoneId.of(zone)
        return Source(expected, hash, generation, text, captured, zone)
    }
    private fun matches(row: Row, source: Source) = row.source == source.version && row.hash == source.hash &&
        row.generation == source.generation && row.rule == LocalCandidates.RULE_VERSION
    private fun bind(row: Row, source: Source) {
        if (!matches(row, source) || row.state == LocalCandidateState.INVALIDATED) throw LocalCandidateConflict()
    }
    private fun bindRequest(row: Row, source: Source, hash: String, generation: Long) {
        bind(row, source)
        if (hash != row.hash || generation != row.generation) throw LocalCandidateConflict()
    }
    private fun payload(row: Row, source: Source): LocalCandidateCipher.Payload {
        bind(row, source)
        val p = cipher.open(aad(row), row.bytes)
        when (row.state) {
            LocalCandidateState.RUNNING -> require(row.version == 0L && row.mutation == row.id && p.operation == "START" &&
                p.items.isEmpty() && p.expectedVersion == 0L && p.itemId.isEmpty() && p.requestedAtMillis == row.created)
            LocalCandidateState.READY -> {
                require(row.version >= 1 && p.expectedVersion == row.version - 1 && p.items.isNotEmpty())
                require(p.items.map { it.id } == p.items.indices.map { "excerpt-" + (it + 1) })
                LocalExcerptRules.validate(ExcerptDocument(row.source, row.hash, p.items.map { it.proposal }), source.version, source.hash, source.original)
                if (p.operation == "COMPLETE") require(row.version == 1L && row.mutation == row.id && p.itemId.isEmpty() &&
                    p.requestedAtMillis == row.created && p.items.all { it.review == ExcerptReview.PROPOSED })
                else if (p.operation in setOf("REOPEN_ACCEPTED", "REOPEN_REJECTED"))
                    require(p.items.singleOrNull { it.id == p.itemId }?.review == ExcerptReview.PROPOSED)
                else require(p.operation in setOf("ACCEPTED", "REJECTED") &&
                    p.items.singleOrNull { it.id == p.itemId }?.review?.name == p.operation)
            }
            LocalCandidateState.CANCELLED -> require(p.items.isEmpty() && p.operation == "CANCEL" &&
                p.itemId.isEmpty() && p.expectedVersion == row.version - 1)
            LocalCandidateState.INVALIDATED -> throw LocalCandidateConflict()
        }
        return p
    }
    private fun write(sql: RecordsSqlConnection, old: Row, state: LocalCandidateState, mutation: String,
        p: LocalCandidateCipher.Payload, source: Source): LocalCandidateBatch {
        if (old.version == Long.MAX_VALUE) throw LocalCandidateConflict()
        val next = old.copy(version = old.version + 1, state = state, mutation = mutation)
        val bytes = cipher.seal(aad(next), p)
        if (sql.execute("UPDATE local_candidate_batches SET version=?,state=?,lastMutationId=?,payload=? WHERE id=? AND version=? AND state=?",
                listOf(next.version, state.name, mutation, bytes, old.id, old.version, old.state.name)) != 1) throw LocalCandidateConflict()
        val read = checkNotNull(row(sql, old.id))
        check(read.version == next.version && read.state == state && read.mutation == mutation)
        val confirmed = payload(read, source); check(confirmed == p)
        return snapshot(read, confirmed.items)
    }
    private fun snapshot(row: Row, items: List<ReviewedExcerpt>, state: LocalCandidateState = row.state) =
        LocalCandidateBatch(row.id, row.source, row.hash, row.generation, row.version, state, row.created, items)
    private fun aad(row: Row): ByteArray = PackageWriter(4096).use { w ->
        w.string("AIyou/local-candidates/1", 64); w.string(row.id, 512); w.string(row.source.sourceId, 512)
        w.i64(row.source.revision); w.string(row.hash, 64); w.i64(row.generation); w.i32(row.rule); w.i64(row.version)
        w.string(row.state.name, 16); w.string(row.mutation, 512); w.i64(row.created); w.bytes()
    }
    private fun row(sql: RecordsSqlConnection, id: String) = sql.query(
        "SELECT " + COLUMNS + " FROM local_candidate_batches WHERE id=?", listOf(id)).singleOrNull()?.let(::decode)
    private fun decode(r: List<Any?>): Row = Row(r[0] as String, SourceVersion(r[1] as String, r[2] as Long),
        r[3] as String, r[4] as Long, Math.toIntExact(r[5] as Long), r[6] as Long, LocalCandidateState.valueOf(r[7] as String),
        r[8] as String, r[9] as Long, r[10] as ByteArray).also {
        ManualTags.identifier(it.id); ManualTags.identifier(it.source.sourceId); ManualTags.identifier(it.mutation)
        require(it.version >= 0 && it.generation >= 0 && it.created >= 0 && it.hash.matches(Regex("[0-9a-f]{64}")))
    }
    private inline fun <T> safe(block: () -> T): T = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (conflict: LocalCandidateConflict) { throw conflict }
        catch (invalid: InvalidExcerptResponse) { throw invalid }
        catch (_: Exception) { throw LocalCandidateStorageFailure() }
    internal fun validateFullPortableRow(cells:List<Any?>,proof:FullBackupSourceProof?) {
        val row=decode(cells)
        when(row.state) {
            LocalCandidateState.INVALIDATED -> require(row.bytes.isEmpty())
            LocalCandidateState.RUNNING -> error("Unfinished local candidate")
            LocalCandidateState.READY -> {
                val p=checkNotNull(proof);require(!p.deleted && p.policy!=ProcessingPolicy.AI_NONE)
                payload(row,Source(p.source,p.hash,p.generation,p.original,p.captured,p.zone))
            }
            LocalCandidateState.CANCELLED -> {
                val p=checkNotNull(proof)
                require(p.source==row.source && p.hash==row.hash && OriginalExports.hash(p.original)==row.hash && row.version>=1)
                val payload=cipher.open(aad(row),row.bytes)
                require(payload.operation=="CANCEL" && payload.items.isEmpty() && payload.itemId.isEmpty() && payload.expectedVersion==row.version-1)
            }
        }
    }
    internal fun validateFullWithin(sql:RecordsSqlConnection) {
        val rows=sql.query("SELECT "+COLUMNS+" FROM local_candidate_batches WHERE ruleVersion=1 ORDER BY id LIMIT 4097")
        require(rows.size<=4096)
        rows.map(::decode).forEach { row -> when(row.state) {
            LocalCandidateState.INVALIDATED -> require(row.bytes.isEmpty())
            LocalCandidateState.RUNNING -> error("Unfinished local candidate")
            LocalCandidateState.READY -> payload(row,readSource(sql,row.source))
            LocalCandidateState.CANCELLED -> {
                // Cancellation can outlive a revision/policy change; validate against its immutable historical original.
                val original=sql.query("SELECT originalText,contentHash FROM entry_revisions WHERE entryId=? AND revision=?",listOf(row.source.sourceId,row.source.revision)).single()
                require(original[1]==row.hash && OriginalExports.hash(original[0] as String)==row.hash && row.version>=1)
                val p=cipher.open(aad(row),row.bytes)
                require(p.operation=="CANCEL" && p.items.isEmpty() && p.itemId.isEmpty() && p.expectedVersion==row.version-1)
            }
        } }
    }
    internal fun portable(cells:List<Any?>,restoring:Boolean):ByteArray {
        val row=decode(cells)
        if(row.state==LocalCandidateState.INVALIDATED) { require(row.bytes.isEmpty());return byteArrayOf() }
        require(row.bytes.isNotEmpty() && row.state!=LocalCandidateState.RUNNING)
        return cipher.portable(aad(row),row.bytes,restoring)
    }
    fun wipeKey() = cipher.wipe()
    companion object { private const val COLUMNS = "id,entryId,sourceRevision,sourceHash,generation,ruleVersion,version,state,lastMutationId,createdAtMillis,payload" }
}
