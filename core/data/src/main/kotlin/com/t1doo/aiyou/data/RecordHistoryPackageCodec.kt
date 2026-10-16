package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections

/** v4 metadata only. The existing streaming AEAD envelope authenticates this before parsing. */
internal object RecordHistoryPackageCodec {
    private const val NAME = "AIyou-history-v4"
    private val SCOPE = listOf("fullOriginalHistory", "manualTags", "readyOriginalImages")
    private val EXCLUDED = listOf("tasks", "drafts", "aiDerived", "serviceKeys", "deviceKeys", "reminders", "consents")
    private val ACTIVITY_SCOPE = SCOPE + listOf("manualTasks", "taskProposals")
    private val ACTIVITY_EXCLUDED = listOf("captureDrafts", "editDrafts", "aiDerived", "serviceKeys", "deviceKeys", "reminders", "consents")

    fun encode(preview: RecordMediaPreview): ByteArray {
        val records = requireNotNull(preview.history)
        validate(records, preview.images, preview.activities)
        RecordPackages.uuid(preview.snapshot.id)
        val blocks = mutableListOf<ByteArray>()
        try {
            records.forEach { blocks += encodeRecord(it) }
            return PackageWriter(RecordMediaPackages.MAX_METADATA).use { out ->
                out.string(NAME, 32); out.i32(4); out.i32(1)
                out.i32(if (preview.activities == null) 0 else 1)
                out.strings(if (preview.activities == null) SCOPE else ACTIVITY_SCOPE, 5, 32)
                out.strings(if (preview.activities == null) EXCLUDED else ACTIVITY_EXCLUDED, 8, 32)
                out.string(preview.snapshot.id, 36); out.i64(preview.snapshot.createdAtMillis)
                out.string("PBKDF2-HMAC-SHA256", 32); out.string("AES-256-GCM", 32)
                listOf(RecordPackages.ITERATIONS, 256, 16, 12, 128).forEach(out::i32)
                out.i32(records.size)
                blocks.forEachIndexed { index, block ->
                    out.string(path(index), 64); out.i32(block.size); out.raw(packageHash(block))
                }
                blocks.forEach { block -> out.i32(block.size); out.raw(block) }
                out.i32(preview.images.size)
                preview.images.forEach { image ->
                    out.string(image.sourceId, 512); out.i32(image.sourceRevision); out.string(image.attachmentId, 36)
                    out.string(image.mediaType, 32); out.i64(image.byteSize); out.raw(packageUnhex(image.sha256))
                }
                preview.activities?.let { activities ->
                    val raw = RecordPackageActivityCodec.encode(activities)
                    try { out.i32(raw.size); out.raw(raw) } finally { raw.fill(0) }
                }
                out.bytes()
            }
        } finally { blocks.forEach { it.fill(0) } }
    }

    fun parse(bytes: ByteArray): AuthenticatedRecordPackage {
        require(bytes.size <= RecordMediaPackages.MAX_METADATA)
        val input = PackageReader(bytes)
        require(input.string(32) == NAME && input.i32() == 4 && input.i32() == 1)
        val flag = input.i32(); require(flag in 0..1)
        val withActivities = flag == 1
        require(input.strings(5, 32) == if (withActivities) ACTIVITY_SCOPE else SCOPE)
        require(input.strings(8, 32) == if (withActivities) ACTIVITY_EXCLUDED else EXCLUDED)
        val backupId = input.string(36); val createdAt = input.i64()
        require(input.string(32) == "PBKDF2-HMAC-SHA256" && input.string(32) == "AES-256-GCM")
        listOf(RecordPackages.ITERATIONS, 256, 16, 12, 128).forEach { require(input.i32() == it) }
        val count = input.count(OriginalExports.MAX_RECORDS)
        val manifest = RecordPackageManifest(backupId, createdAt, count)
        val descriptors = List(count) { index ->
            require(input.string(64) == path(index))
            val size = input.i32(); require(size in 1..RecordMediaPackages.MAX_METADATA)
            size to input.raw(32)
        }
        val records = mutableListOf<RecordPackageOriginal>()
        val hashes = mutableListOf<String>()
        var revisionCount = 0
        descriptors.forEach { (size, hash) ->
            require(input.i32() == size)
            val block = input.raw(size, RecordMediaPackages.MAX_METADATA)
            try {
                require(MessageDigest.isEqual(hash, packageHash(block)))
                val record = decodeRecord(block, RecordPackages.MAX_TOTAL_REVISIONS - revisionCount)
                revisionCount += record.history.size
                records += record; hashes += packageHex(hash)
            } finally { block.fill(0) }
        }
        val images = List(input.count(RecordMediaPackages.MAX_IMAGES)) {
            RecordPackageImage(input.string(512), input.i32(), input.string(36), input.string(32), input.i64(), packageHex(input.raw(32)))
        }
        val activities = if (withActivities) {
            val raw = input.raw(input.i32(), RecordMediaPackages.MAX_METADATA)
            try { RecordPackageActivityCodec.decode(raw) } finally { raw.fill(0) }
        } else null
        input.end()
        validate(records, images, activities)
        return HistoryPackage(manifest, records, packageHex(packageHash(bytes)), hashes, images, activities)
    }

    private fun encodeRecord(record: RecordPackageOriginal): ByteArray = PackageWriter(RecordMediaPackages.MAX_METADATA).use { out ->
        out.string(record.sourceId, 512); out.i32(record.sourceRevision)
        out.i64(record.capturedAtMillis); out.string(record.captureTimeZone, 128)
        out.raw(packageUnhex(record.contentHash)); out.i64(record.sourceTagVersion); out.strings(record.tags, 12, 640)
        out.i32(record.history.size)
        record.history.forEach { revision ->
            out.i32(revision.revision); out.string(revision.originalText, 400_000)
            out.raw(packageUnhex(revision.contentHash)); out.i64(revision.createdAtMillis)
        }
        out.bytes()
    }

    private fun decodeRecord(bytes: ByteArray, remainingRevisions: Int): RecordPackageOriginal {
        val input = PackageReader(bytes)
        val id = input.string(512); val currentRevision = input.i32()
        val capturedAt = input.i64(); val zone = input.string(128)
        val currentHash = packageHex(input.raw(32)); val tagVersion = input.i64(); val tags = input.strings(12, 640)
        val count = input.count(minOf(RecordPackages.MAX_REVISIONS_PER_RECORD, remainingRevisions))
        require(count > 0)
        val history = List(count) {
            RecordPackageRevision(input.i32(), input.string(400_000), packageHex(input.raw(32)), input.i64())
        }
        input.end()
        return RecordPackageOriginal(id, currentRevision, capturedAt, zone, history.last().originalText,
            currentHash, tagVersion, tags, true, history)
    }

    private fun validate(records: List<RecordPackageOriginal>, images: List<RecordPackageImage>, activities: RecordPackageActivities?) {
        require(records.size in 1..OriginalExports.MAX_RECORDS)
        require(records.map { it.sourceId }.distinct().size == records.size)
        require(records.all { it.history.isNotEmpty() && it.capturedAtMillis >= 0 })
        require(records.sumOf { it.history.size } <= RecordPackages.MAX_TOTAL_REVISIONS)
        require(records.sumOf { RecordPackages.historyTextBytes(it.history) } <= RecordPackages.MAX_PLAINTEXT)
        require(images.size <= RecordMediaPackages.MAX_IMAGES && images.sumOf { it.byteSize } <= RecordMediaPackages.MAX_IMAGE_TOTAL)
        require(images.map { it.attachmentId }.distinct().size == images.size)
        images.forEach { image -> require(records.any { it.sourceId == image.sourceId && image.sourceRevision in 1..it.sourceRevision }) }
        records.forEach { record -> record.history.filter { it.originalText.isBlank() }.forEach { revision ->
            require(images.any { it.sourceId == record.sourceId && it.sourceRevision == revision.revision })
        } }
        activities?.let { RecordPackageActivityCodec.validate(it, records) }
    }

    private fun path(index: Int) = "records/" + index.toString().padStart(2, '0') + ".bin"

    private class HistoryPackage(override val manifest: RecordPackageManifest, records: List<RecordPackageOriginal>,
        override val digest: String, hashes: List<String>, images: List<RecordPackageImage>,
        override val activities: RecordPackageActivities?) : AuthenticatedRecordPackage {
        override val records = Collections.unmodifiableList(records.toList())
        override val recordDigests = Collections.unmodifiableList(hashes.toList())
        override val images = Collections.unmodifiableList(images.toList())
        override val includesHistory = true
        override fun toString() = "VerifiedHistoryPackage(count=${records.size}, redacted)"
    }
}

/** Authenticated immutable-prefix receipt, independent of later current pointers and tag edits. */
internal fun recordHistoryDigest(history: List<RecordPackageRevision>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("AIyou-original-history-prefix-v1".toByteArray(Charsets.US_ASCII))
    digest.update(ByteBuffer.allocate(4).putInt(history.size).array())
    history.forEach { revision ->
        digest.update(ByteBuffer.allocate(4).putInt(revision.revision).array())
        val text = OriginalExports.utf8(revision.originalText)
        try { digest.update(ByteBuffer.allocate(4).putInt(text.size).array()); digest.update(text) } finally { text.fill(0) }
        digest.update(packageUnhex(revision.contentHash))
        digest.update(ByteBuffer.allocate(8).putLong(revision.createdAtMillis).array())
    }
    return packageHex(digest.digest())
}
