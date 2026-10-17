package com.t1doo.aiyou.model

import java.util.Collections
import java.time.ZoneId
import java.security.MessageDigest
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

enum class OriginalExportFormat(val extension: String, val mimeType: String) {
    MARKDOWN("md", "text/markdown"), JSONL("jsonl", "application/x-ndjson"),
    MEDIA_ZIP("zip", "application/zip"),
}

/** One current original and its independently versioned labels from the SAME SQL snapshot. */
class OriginalExportRecord(val entry: StoredEntry, val contentHash: String, val tags: ManualTagSnapshot,
    val sourceGeneration: Long = 0, val imageCount: Int = 0,
    val eventDate: RecordEventDateSnapshot = RecordEventDateSnapshot(entry.id, 0, null),
    people: ManualPeopleSnapshot? = null) {
    val people = people ?: ManualPeopleSnapshot(entry.id, 0, emptyList(), entry.currentRevision, contentHash, sourceGeneration)
    init {
        ManualTags.identifier(entry.id); require(entry.currentRevision > 0 && tags.entryId == entry.id)
        require(sourceGeneration >= 0 && eventDate.entryId == entry.id)
        require(this.people.entryId == entry.id && this.people.sourceRevision == entry.currentRevision &&
            this.people.sourceHash == contentHash && this.people.generation == sourceGeneration)
        require(imageCount in 0..64 && (entry.originalText.isNotBlank() || imageCount > 0) && entry.originalText.length <= 100_000)
        ZoneId.of(entry.captureTimeZone)
        require(contentHash.matches(Regex("[0-9a-f]{64}")) && OriginalExports.hash(entry.originalText) == contentHash)
    }
    fun same(other: OriginalExportRecord) = entry == other.entry && contentHash == other.contentHash &&
        tags.version == other.tags.version && tags.names == other.tags.names && sourceGeneration == other.sourceGeneration && imageCount == other.imageCount &&
        eventDate.version == other.eventDate.version && eventDate.date == other.eventDate.date &&
        people.version == other.people.version && people.names == other.people.names
    override fun toString() = "OriginalExportRecord(redacted)"
}

class OriginalExportSnapshot(val id: String, val createdAtMillis: Long, records: List<OriginalExportRecord>, allowEmpty: Boolean = false) {
    val records = Collections.unmodifiableList(records.toList())
    init {
        ManualTags.identifier(id); require(createdAtMillis >= 0)
        require(records.size in (if(allowEmpty) 0 else 1)..OriginalExports.MAX_RECORDS && records.map { it.entry.id }.distinct().size == records.size)
        require(records.sumOf { it.entry.originalText.length.toLong() } <= OriginalExports.MAX_TEXT_UNITS)
    }
    override fun toString() = "OriginalExportSnapshot(count=" + records.size + ", redacted)"
}

object OriginalExports {
    const val MAX_RECORDS = 20
    const val MAX_TEXT_UNITS = 250_000L
    const val MAX_BYTES = 2 * 1024 * 1024
    fun utf8(text: String): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        return try { ByteArray(buffer.remaining()).also(buffer::get) }
        finally { if (buffer.hasArray()) buffer.array().fill(0) }
    }
    fun hash(text: String): String {
        val bytes = utf8(text)
        return try { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
        finally { bytes.fill(0) }
    }
}
