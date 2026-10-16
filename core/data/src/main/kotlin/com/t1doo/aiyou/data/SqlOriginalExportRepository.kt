package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.CancellationException

/** Same counted Room/SQLCipher transaction and tag cipher as the records owner. No schema change. */
internal class SqlOriginalExportRepository(private val database: RecordsSqlDatabase,
    private val tags: SqlManualTagRepository, private val activities: SqlRecordPackageActivities? = null) : OriginalExportRepository {
    override fun capture(sources: List<SourceVersion>, snapshotId: String, createdAtMillis: Long, refreshCurrent: Boolean): OriginalExportSnapshot = safely {
        val selected = sources.toList()
        require(selected.size in 1..OriginalExports.MAX_RECORDS && selected.map { it.sourceId }.distinct().size == selected.size)
        database.transaction { sql ->
            val records = selected.map { read(sql, it, refreshCurrent) }
            if (records.sumOf { it.entry.originalText.length.toLong() } > OriginalExports.MAX_TEXT_UNITS) throw OriginalExportTooLarge()
            OriginalExportSnapshot(snapshotId, createdAtMillis, records)
        }
    }
    override fun isCurrent(snapshot: OriginalExportSnapshot): Boolean = safely {
        database.transaction { sql ->
            currentWithin(sql, snapshot)
        }
    }
    override fun captureActivities(snapshot: OriginalExportSnapshot): RecordPackageActivities = safely {
        database.transaction { sql ->
            if (!currentWithin(sql, snapshot)) throw OriginalExportConflict()
            checkNotNull(activities).captureWithin(sql, snapshot).also {
                if (snapshot.records.isEmpty() && it.tasks.isEmpty() && it.drafts.isEmpty()) throw OriginalExportEmptyScope()
            }
        }
    }
    override fun areActivitiesCurrent(snapshot: OriginalExportSnapshot, activities: RecordPackageActivities): Boolean = safely {
        database.transaction { sql ->
            if (!currentWithin(sql, snapshot)) false else {
                val now = checkNotNull(this.activities).captureWithin(sql, snapshot)
                now.tasks == activities.tasks && now.drafts == activities.drafts && now.sourceGenerations == activities.sourceGenerations
            }
        }
    }
    private fun currentWithin(sql: RecordsSqlConnection, snapshot: OriginalExportSnapshot) = snapshot.records.all { record ->
        try { record.same(read(sql, SourceVersion(record.entry.id, record.entry.currentRevision.toLong()))) }
        catch (_: OriginalExportConflict) { false }
    }
    private fun read(sql: RecordsSqlConnection, source: SourceVersion, refreshCurrent: Boolean = false): OriginalExportRecord {
        val row = sql.query("SELECT e.id,e.currentRevision,r.originalText,r.contentHash,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy,COALESCE((SELECT a.generation FROM remote_source_access a WHERE a.entryId=e.id),0) FROM entries e JOIN entry_revisions r ON e.id=r.entryId AND e.currentRevision=r.revision WHERE e.id=? AND NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1)", listOf(source.sourceId)).singleOrNull()
            ?: throw OriginalExportConflict()
        if (!refreshCurrent && row[1] as Long != source.revision) throw OriginalExportConflict()
        val entry = StoredEntry(row[0] as String, Math.toIntExact(row[1] as Long), row[2] as String,
            row[4] as Long, row[5] as String, ProcessingPolicy.valueOf(row[6] as String))
        return OriginalExportRecord(entry, row[3] as String, tags.readWithin(sql, entry.id), row[7] as Long, Math.toIntExact(sql.query("SELECT count(*) FROM attachments WHERE entryId=? AND revision=? AND mediaType IN ('image/png','image/jpeg')",listOf(entry.id,entry.currentRevision)).single().single() as Long))
    }
    private inline fun <T> safely(block: () -> T): T = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (conflict: OriginalExportConflict) { throw conflict }
        catch (large: OriginalExportTooLarge) { throw large }
        catch (limit: RecordPackageActivityLimit) { throw limit }
        catch (empty: OriginalExportEmptyScope) { throw empty }
        catch (_: Exception) { throw OriginalExportStorageFailure() }
}
