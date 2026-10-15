package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.security.MessageDigest
import java.time.ZoneId
import java.util.concurrent.CancellationException

/** Borrows the existing records transaction and tag cipher; owns no table, key or lifecycle. */
internal class SqlSourceTagRepository(private val database: RecordsSqlDatabase,
    private val tags: SqlManualTagRepository) : SourceTagRepository {
    private data class Source(val hash: String, val generation: Long, val original: String,
        val captured: Long, val zone: String, val policy: ProcessingPolicy)

    override fun preview(source: SourceVersion): SourceTagPreview = safe {
        database.transaction { sql ->
            val current = readSource(sql, source)
            SourceTagPreview(source, current.hash, current.generation, current.captured, current.zone, current.policy,
                epoch(sql), tags.readWithin(sql, source.sourceId), SourceTagRules.extract(source, current.original))
        }
    }
    override fun apply(request: SourceTagRequest): ManualTagSnapshot = safe {
        database.transaction { sql ->
            val p = request.preview
            val source = readSource(sql, p.source)
            if (p.ruleVersion != SourceTagRules.VERSION || source.hash != p.sourceHash || source.generation != p.generation ||
                source.captured != p.capturedAtMillis || source.zone != p.captureTimeZone || source.policy != p.policy ||
                p.proposals != SourceTagRules.extract(p.source, source.original) || request.proposal !in p.proposals ||
                request.proposal.name in p.tags.names) throw SourceTagConflict()
            if (p.tags.names.size == ManualTags.MAX_COUNT) throw SourceTagLimit()
            val desired = p.tags.names + request.proposal.name
            val mutation = ManualTagMutation(p.source.sourceId, p.tags.version, desired, receiptId(request, desired))
            val current = tags.readWithin(sql, p.source.sourceId)
            val before = epoch(sql)
            val postEpoch = Math.addExact(p.epoch, 1)
            if (before == p.epoch) {
                if (current.version != p.tags.version || current.names != p.tags.names) throw SourceTagConflict()
            } else if (before != postEpoch || current.version != p.tags.version + 1 || current.names != desired) {
                throw SourceTagConflict()
            }
            // At postEpoch the tag CAS can only return the authenticated exact last receipt, never write.
            val result = tags.replaceWithin(sql, mutation)
            check(result.version == p.tags.version + 1 && result.names == desired && epoch(sql) == postEpoch)
            result
        }
    }
    private fun epoch(sql: RecordsSqlConnection) = (sql.query("SELECT epoch FROM local_search_epoch WHERE slot=1")
        .single().single() as Long).also { require(it >= 0) }
    private fun readSource(sql: RecordsSqlConnection, expected: SourceVersion): Source {
        val r = sql.query("SELECT e.currentRevision,r.originalText,r.contentHash,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy,COALESCE(a.generation,0),COALESCE(a.deleted,0) FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision LEFT JOIN remote_source_access a ON a.entryId=e.id WHERE e.id=?",
            listOf(expected.sourceId)).singleOrNull() ?: throw SourceTagConflict()
        if (r[0] != expected.revision || r[7] != 0L || r[5] == ProcessingPolicy.AI_NONE.name ||
            sql.query("SELECT entryId FROM permanent_erasure_receipts WHERE entryId=? LIMIT 1", listOf(expected.sourceId)).isNotEmpty())
            throw SourceTagConflict()
        val text = r[1] as String; val hash = r[2] as String; val generation = r[6] as Long
        require(text.isNotBlank() && text.length <= 100_000 && OriginalExports.hash(text) == hash && generation >= 0)
        val captured = r[3] as Long; val zone = r[4] as String; require(captured >= 0); ZoneId.of(zone)
        return Source(hash, generation, text, captured, zone, ProcessingPolicy.valueOf(r[5] as String))
    }
    private fun receiptId(request: SourceTagRequest, desired: List<String>): String {
        val p = request.preview
        val bytes = PackageWriter(16384).use { w ->
            w.string("AIyou/source-tag/1",64); w.string(request.mutationId,512); w.i64(request.requestedAtMillis)
            w.string(p.source.sourceId,512); w.i64(p.source.revision); w.string(p.sourceHash,64); w.i64(p.generation)
            w.i64(p.capturedAtMillis); w.string(p.captureTimeZone,128); w.string(p.policy.name,32)
            w.i32(p.ruleVersion); w.i64(p.epoch); w.i64(p.tags.version)
            w.i32(p.tags.names.size); p.tags.names.forEach { w.string(it,160) }
            w.i32(p.proposals.size); p.proposals.forEach { item ->
                w.string(item.name,160); w.i32(item.reference.start); w.i32(item.reference.end); w.string(item.reference.quote,164)
            }
            w.i32(p.proposals.indexOf(request.proposal)); w.i32(desired.size); desired.forEach { w.string(it,160) }
            w.bytes()
        }
        return try { "source-tag-v1-" + MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) } } finally { bytes.fill(0) }
    }
    private inline fun <T> safe(block: () -> T): T = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (conflict: SourceTagConflict) { throw conflict }
        catch (_: ManualTagConflict) { throw SourceTagConflict() }
        catch (_: ManualTagUnavailable) { throw SourceTagConflict() }
        catch (format: InvalidSourceTagFormat) { throw format }
        catch (limit: SourceTagLimit) { throw limit }
        catch (_: Exception) { throw SourceTagStorageFailure() }
}
