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
class RecordMediaPreview(val snapshot: OriginalExportSnapshot, images: List<RecordPackageImage>) {
    val images = Collections.unmodifiableList(images.toList())
    val imageBytes = images.sumOf { it.byteSize }
    init {
        require(images.size <= RecordMediaPackages.MAX_IMAGES && imageBytes <= RecordMediaPackages.MAX_IMAGE_TOTAL)
        require(images.map { it.attachmentId }.distinct().size == images.size)
        images.forEach { image -> require(snapshot.records.any { it.entry.id == image.sourceId && it.entry.currentRevision == image.sourceRevision }) }
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
