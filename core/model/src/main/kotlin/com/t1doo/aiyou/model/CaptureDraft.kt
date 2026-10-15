package com.t1doo.aiyou.model

import java.util.Collections

enum class CaptureImageState { WRITING, READY, FAILED, REMOVING }
data class CaptureDraftImage(val intent: AttachmentIntent,val state: CaptureImageState)
class CaptureDraft(val id: String,val version: Long,val text: String,images: List<CaptureDraftImage>,val discarding: Boolean=false) {
    val images = Collections.unmodifiableList(images.toList())
    init {
        RecordPackages.uuid(id); require(version>0 && text.length<=100_000 && images.size<=20)
        require(images.map { it.intent.id }.distinct().size==images.size && images.sumOf { it.intent.byteSize }<=RecordMediaPackages.MAX_IMAGE_TOTAL)
        images.forEach { val i=it.intent; RecordPackages.uuid(i.id);require(i.entryId==id && i.revision==1 && i.mediaType in setOf("image/png","image/jpeg") && i.byteSize in 1..RecordMediaPackages.MAX_IMAGE_BYTES);RecordPackages.digest(i.sha256) }
    }
    override fun toString()="CaptureDraft(images=${images.size}, redacted)"
}
data class CaptureSaveResult(val id: String,val replayed: Boolean,val deleted: Boolean)
