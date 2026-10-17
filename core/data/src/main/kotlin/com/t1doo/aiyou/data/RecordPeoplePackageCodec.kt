package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.util.Collections

/** v6 adds independently versioned manual names around authenticated v2..v5 data. No grants/identities. */
internal object RecordPeoplePackageCodec {
    fun encode(preview: RecordMediaPreview, baseVersion: Int, base: ByteArray): ByteArray {
        require(baseVersion in 2..5 && preview.snapshot.records.any { it.people.version > 0 })
        return PackageWriter(RecordMediaPackages.MAX_METADATA).use { out ->
            out.string("AIyou-manual-people-v6", 32); out.i32(6); out.i32(1); out.i32(baseVersion)
            out.i32(base.size); out.raw(base); out.i32(preview.snapshot.records.size)
            preview.snapshot.records.forEachIndexed { index, record ->
                out.i32(index); out.string(record.entry.id, 512); out.i64(record.people.version)
                out.strings(record.people.names, 12, 160)
            }; out.bytes()
        }
    }
    fun parse(bytes: ByteArray, parseBase: (ByteArray, Int) -> AuthenticatedRecordPackage): AuthenticatedRecordPackage {
        require(bytes.size <= RecordMediaPackages.MAX_METADATA)
        val input = PackageReader(bytes)
        require(input.string(32) == "AIyou-manual-people-v6" && input.i32() == 6 && input.i32() == 1)
        val baseVersion = input.i32(); require(baseVersion in 2..5)
        val raw = input.raw(input.i32(), RecordMediaPackages.MAX_METADATA)
        val base = try { parseBase(raw, baseVersion) } finally { raw.fill(0) }
        val count = input.count(OriginalExports.MAX_RECORDS); require(count == base.records.size)
        val records = List(count) { index ->
            val old = base.records[index]
            require(input.i32() == index && input.string(512) == old.sourceId)
            val version = input.i64(); val names = input.strings(12, 160)
            RecordPackageOriginal(old.sourceId, old.sourceRevision, old.capturedAtMillis, old.captureTimeZone,
                old.originalText, old.contentHash, old.sourceTagVersion, old.tags, true, old.history, old.eventDate, version, names)
        }
        input.end(); require(records.any { it.sourcePeopleVersion > 0 })
        val digests = records.mapIndexed { index, record -> PackageWriter(4096).use { out ->
            out.string("AIyou-people-record-v6", 32); out.raw(packageUnhex(base.recordDigests[index]))
            out.i64(record.sourcePeopleVersion); out.strings(record.people, 12, 160)
            val block = out.bytes(); try { packageHex(packageHash(block)) } finally { block.fill(0) }
        } }
        return PeoplePackage(base, records, digests, packageHex(packageHash(bytes)))
    }
    fun legacyPreview(preview: RecordMediaPreview): RecordMediaPreview {
        val snapshot = OriginalExportSnapshot(preview.snapshot.id, preview.snapshot.createdAtMillis,
            preview.snapshot.records.map { r -> OriginalExportRecord(r.entry, r.contentHash, r.tags,
                r.sourceGeneration, r.imageCount, r.eventDate) }, preview.snapshot.records.isEmpty())
        val history = preview.history?.map { r -> RecordPackageOriginal(r.sourceId, r.sourceRevision,
            r.capturedAtMillis, r.captureTimeZone, r.originalText, r.contentHash, r.sourceTagVersion,
            r.tags, true, r.history, r.eventDate) }
        return RecordMediaPreview(snapshot, preview.images, preview.activities, history)
    }
    private class PeoplePackage(private val base: AuthenticatedRecordPackage, records: List<RecordPackageOriginal>,
        digests: List<String>, override val digest: String) : AuthenticatedRecordPackage {
        override val manifest get() = base.manifest
        override val records = Collections.unmodifiableList(records.toList())
        override val recordDigests = Collections.unmodifiableList(digests.toList())
        override val images get() = base.images
        override val activities get() = base.activities
        override val includesHistory get() = base.includesHistory
        override fun toString() = "VerifiedPeoplePackage(count=${records.size}, redacted)"
    }
}
