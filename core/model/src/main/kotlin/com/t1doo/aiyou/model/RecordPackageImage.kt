package com.t1doo.aiyou.model

import java.util.Collections

/** Exact original image identity and immutable source version; no package-controlled filesystem path. */
data class RecordPackageImage(val sourceId: String, val sourceRevision: Int, val attachmentId: String,
    val mediaType: String, val byteSize: Long, val sha256: String) {
    init {
        ManualTags.identifier(sourceId); require(sourceRevision > 0); RecordPackages.uuid(attachmentId)
        require(mediaType in setOf("image/jpeg", "image/png"))
        require(byteSize in 1..RecordMediaPackages.MAX_IMAGE_BYTES); RecordPackages.digest(sha256)
    }
}
class RecordMediaPreview(val snapshot: OriginalExportSnapshot, images: List<RecordPackageImage>,
    val activities: RecordPackageActivities? = null, history: List<RecordPackageOriginal>? = null) {
    val images = Collections.unmodifiableList(images.toList())
    val history = history?.let { Collections.unmodifiableList(it.toList()) }
    val imageBytes = images.sumOf { it.byteSize }
    init {
        require(images.size <= RecordMediaPackages.MAX_IMAGES && imageBytes <= RecordMediaPackages.MAX_IMAGE_TOTAL)
        require(images.map { it.attachmentId }.distinct().size == images.size)
        if (history == null) {
            images.forEach { image -> require(snapshot.records.any { it.entry.id == image.sourceId && it.entry.currentRevision == image.sourceRevision }) }
            snapshot.records.filter { it.entry.originalText.isBlank() }.forEach { record ->
                require(images.any { it.sourceId==record.entry.id && it.sourceRevision==record.entry.currentRevision })
            }
        } else {
            require(history.isNotEmpty() && history.size == snapshot.records.size)
            require(history.sumOf { it.history.size } <= RecordPackages.MAX_TOTAL_REVISIONS)
            require(history.sumOf { RecordPackages.historyTextBytes(it.history) } <= RecordPackages.MAX_PLAINTEXT)
            history.forEachIndexed { index, record ->
                val current = snapshot.records[index]
                require(record.history.isNotEmpty() && record.sourceId == current.entry.id && record.sourceRevision == current.entry.currentRevision &&
                    record.originalText == current.entry.originalText && record.contentHash == current.contentHash &&
                    record.capturedAtMillis == current.entry.capturedAtMillis && record.captureTimeZone == current.entry.captureTimeZone &&
                    record.sourceTagVersion == current.tags.version && record.tags == current.tags.names)
                record.history.filter { it.originalText.isBlank() }.forEach { revision ->
                    require(images.any { it.sourceId == record.sourceId && it.sourceRevision == revision.revision })
                }
            }
            images.forEach { image -> require(history.any { it.sourceId == image.sourceId && image.sourceRevision in 1..it.sourceRevision }) }
        }
    }
    override fun toString() = "RecordMediaPreview(images=${images.size}, redacted)"
}
object RecordMediaPackages {
    const val MAX_IMAGES = 20
    const val MAX_IMAGE_BYTES = 16L * 1024 * 1024
    const val MAX_IMAGE_TOTAL = 64L * 1024 * 1024
    const val MAX_METADATA = RecordPackages.MAX_PLAINTEXT + 32768
    const val MAX_FILE_BYTES = MAX_IMAGE_TOTAL + MAX_METADATA + 4096L
}
