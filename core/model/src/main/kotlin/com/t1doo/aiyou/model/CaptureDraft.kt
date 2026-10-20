package com.t1doo.aiyou.model

import java.util.Collections

enum class CaptureImageState { WRITING, READY, FAILED, REMOVING }
data class CaptureDraftImage(val intent: AttachmentIntent,val state: CaptureImageState)
enum class CaptureAudioState { WRITING, READY, FAILED, REMOVING }
data class CaptureDraftAudio(val intent: AttachmentIntent, val state: CaptureAudioState)
class CaptureDraft(val id: String,val version: Long,val text: String,images: List<CaptureDraftImage>,val discarding: Boolean=false,
    val policy: ProcessingPolicy = ProcessingDefaults.policy, val audio: CaptureDraftAudio? = null) {
    val images = Collections.unmodifiableList(images.toList())
    init {
        RecordPackages.uuid(id); require(version>0 && text.length<=100_000 && images.size<=20)
        require(policy == ProcessingPolicy.LOCAL_ONLY || policy == ProcessingPolicy.AI_NONE)
        require(images.map { it.intent.id }.distinct().size==images.size && images.sumOf { it.intent.byteSize }<=RecordMediaPackages.MAX_IMAGE_TOTAL)
        images.forEach { val i=it.intent; RecordPackages.uuid(i.id);require(i.entryId==id && i.revision==1 && i.mediaType in setOf("image/png","image/jpeg") && i.byteSize in 1..RecordMediaPackages.MAX_IMAGE_BYTES);RecordPackages.digest(i.sha256) }
        audio?.let { a ->
            val i=a.intent;RecordPackages.uuid(i.id);RecordPackages.digest(i.sha256)
            require(i.entryId==id && i.revision==1 && i.mediaType=="audio/wav" && i.byteSize in 46..LocalPcmAudio.MAX_WAV_BYTES.toLong())
            require(images.none { it.intent.id==i.id } && images.size+1<=20)
            require(images.sumOf { it.intent.byteSize }+i.byteSize<=RecordMediaPackages.MAX_IMAGE_TOTAL)
        }
    }
    fun sameSnapshot(other: CaptureDraft): Boolean = id==other.id && version==other.version && text==other.text &&
        images==other.images && discarding==other.discarding && policy==other.policy && audio==other.audio
    override fun toString()="CaptureDraft(images=${images.size}, audio=${audio!=null}, redacted)"
}
data class CaptureSaveResult(val id: String,val replayed: Boolean,val deleted: Boolean)
