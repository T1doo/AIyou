package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.util.Collections

/** v5 extends authenticated v2/v3/v4 metadata with user supplied calendar days, never instants. */
internal object RecordEventDatePackageCodec {
    fun encode(preview: RecordMediaPreview, baseVersion: Int, base: ByteArray): ByteArray {
        require(baseVersion in 2..4 && preview.snapshot.records.any { it.eventDate.date != null })
        return PackageWriter(RecordMediaPackages.MAX_METADATA).use { out ->
            out.string("AIyou-event-dates-v5", 32); out.i32(5); out.i32(1); out.i32(baseVersion)
            out.i32(base.size); out.raw(base)
            out.i32(preview.snapshot.records.size)
            preview.snapshot.records.forEachIndexed { index, record ->
                out.i32(index); out.string(record.entry.id, 512)
                val date = record.eventDate.date
                out.i32(if (date == null) 0 else 1)
                date?.let { out.string(it.localDate, 10); out.string(it.timeZone, 128) }
            }
            out.bytes()
        }
    }

    fun parse(bytes: ByteArray, parseBase: (ByteArray, Int) -> AuthenticatedRecordPackage): AuthenticatedRecordPackage {
        require(bytes.size <= RecordMediaPackages.MAX_METADATA)
        val input = PackageReader(bytes)
        require(input.string(32) == "AIyou-event-dates-v5" && input.i32() == 5 && input.i32() == 1)
        val baseVersion = input.i32(); require(baseVersion in 2..4)
        val raw = input.raw(input.i32(), RecordMediaPackages.MAX_METADATA)
        val base = try { parseBase(raw, baseVersion) } finally { raw.fill(0) }
        val count = input.count(OriginalExports.MAX_RECORDS); require(count == base.records.size)
        val records = List(count) { index ->
            val original = base.records[index]
            require(input.i32() == index && input.string(512) == original.sourceId)
            val flag = input.i32(); require(flag in 0..1)
            val date = if (flag == 1) RecordEventDate(input.string(10), input.string(128)) else null
            RecordPackageOriginal(original.sourceId, original.sourceRevision, original.capturedAtMillis,
                original.captureTimeZone, original.originalText, original.contentHash, original.sourceTagVersion,
                original.tags, true, original.history, date)
        }
        input.end()
        require(records.any { it.eventDate != null }) // no ambiguous v5 encoding of the legacy unknown-only scope
        val digests = records.mapIndexed { index, record ->
            PackageWriter(1024).use { out ->
                out.string("AIyou-event-record-v5", 32); out.raw(packageUnhex(base.recordDigests[index]))
                val date = record.eventDate
                out.i32(if (date == null) 0 else 1)
                date?.let { out.string(it.localDate, 10); out.string(it.timeZone, 128) }
                val block = out.bytes()
                try { packageHex(packageHash(block)) } finally { block.fill(0) }
            }
        }
        return EventDatePackage(base, records, digests, packageHex(packageHash(bytes)))
    }

    /** Remove dates only while generating the declared inner legacy representation of a v5 envelope. */
    fun legacyPreview(preview: RecordMediaPreview): RecordMediaPreview {
        val snapshot = OriginalExportSnapshot(preview.snapshot.id, preview.snapshot.createdAtMillis,
            preview.snapshot.records.map { record -> OriginalExportRecord(record.entry, record.contentHash,
                record.tags, record.sourceGeneration, record.imageCount,audioCount=record.audioCount) }, preview.snapshot.records.isEmpty())
        val history = preview.history?.map { record -> RecordPackageOriginal(record.sourceId, record.sourceRevision,
            record.capturedAtMillis, record.captureTimeZone, record.originalText, record.contentHash,
            record.sourceTagVersion, record.tags, true, record.history) }
        return RecordMediaPreview(snapshot, preview.images, preview.activities, history,audios=preview.audios)
    }

    private class EventDatePackage(private val base: AuthenticatedRecordPackage, records: List<RecordPackageOriginal>,
        digests: List<String>, override val digest: String) : AuthenticatedRecordPackage {
        override val manifest get() = base.manifest
        override val records = Collections.unmodifiableList(records.toList())
        override val recordDigests = Collections.unmodifiableList(digests.toList())
        override val images get() = base.images
        override val activities get() = base.activities
        override val includesHistory get() = base.includesHistory
        override fun toString() = "VerifiedEventDatePackage(count=${records.size}, redacted)"
    }
}
