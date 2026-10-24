package com.t1doo.aiyou.model

import java.util.Collections

interface RecordPackageAttachment {
    val sourceId:String
    val sourceRevision:Int
    val attachmentId:String
    val mediaType:String
    val byteSize:Long
    val sha256:String
}
/** Exact original image identity and immutable source version; no package-controlled filesystem path. */
data class RecordPackageImage(override val sourceId: String, override val sourceRevision: Int, override val attachmentId: String,
    override val mediaType: String, override val byteSize: Long, override val sha256: String): RecordPackageAttachment {
    init {
        ManualTags.identifier(sourceId); require(sourceRevision > 0); RecordPackages.uuid(attachmentId)
        require(mediaType in setOf("image/jpeg", "image/png"))
        require(byteSize in 1..RecordMediaPackages.MAX_IMAGE_BYTES); RecordPackages.digest(sha256)
    }
}
data class RecordPackageAudio(override val sourceId:String,override val sourceRevision:Int,override val attachmentId:String,
    override val mediaType:String,override val byteSize:Long,override val sha256:String):RecordPackageAttachment {
    init {
        ManualTags.identifier(sourceId);require(sourceRevision>0);RecordPackages.uuid(attachmentId);RecordPackages.digest(sha256)
        require(mediaType=="audio/wav" && byteSize in 46..LocalPcmAudio.MAX_WAV_BYTES.toLong())
    }
}
object RecordPackageAttachments {
    fun descriptor(sourceId:String,revision:Int,id:String,mime:String,size:Long,hash:String):RecordPackageAttachment =
        if(mime=="audio/wav")RecordPackageAudio(sourceId,revision,id,mime,size,hash) else RecordPackageImage(sourceId,revision,id,mime,size,hash)
}
/** Repository-issued scope; null excludes human text, an empty scope explicitly includes none. */
interface OpenTranscriptScope { val items: List<AudioTranscriptSnapshot> }

class RecordMediaPreview(val snapshot: OriginalExportSnapshot, images: List<RecordPackageImage>,
    val activities: RecordPackageActivities? = null, history: List<RecordPackageOriginal>? = null,
    editDrafts: List<OriginalEditDraft>? = null, audios:List<RecordPackageAudio> = emptyList(),
    val openTranscripts: OpenTranscriptScope? = null) {
    /** Null means excluded; empty means the explicitly selected scope contains no active edits. */
    val editDrafts = editDrafts?.let { Collections.unmodifiableList(it.toList()) }
    val images = Collections.unmodifiableList(images.toList())
    val audios=Collections.unmodifiableList(audios.toList())
    val media:List<RecordPackageAttachment> = Collections.unmodifiableList(this.images+this.audios)
    val history = history?.let { Collections.unmodifiableList(it.toList()) }
    val imageBytes = images.sumOf { it.byteSize }
    init {
        openTranscripts?.let { scope ->
            require(history == null && editDrafts == null && activities == null)
            require(scope.items.size <= audios.size && scope.items.map { it.id }.distinct().size == scope.items.size)
            scope.items.forEach { item ->
                val a=item.anchor;val i=a.intent
                require(!item.withdrawn && audios.any { it.attachmentId==i.id && it.sourceId==i.entryId &&
                    it.sourceRevision==i.revision && it.sha256==i.sha256 && it.byteSize==i.byteSize })
                require(snapshot.records.any { it.entry.id==i.entryId && it.entry.currentRevision==i.revision &&
                    it.contentHash==a.sourceHash && it.sourceGeneration==a.generation && it.entry.policy==a.policy })
            }
        }
        require(media.size<=RecordMediaPackages.MAX_IMAGES && media.sumOf { it.byteSize }<=RecordMediaPackages.MAX_IMAGE_TOTAL)
        require(media.map { it.attachmentId }.distinct().size==media.size)
        if (editDrafts != null) RecordEditDraftPackages.validate(editDrafts, checkNotNull(history))
        require(images.size <= RecordMediaPackages.MAX_IMAGES && imageBytes <= RecordMediaPackages.MAX_IMAGE_TOTAL)
        require(images.map { it.attachmentId }.distinct().size == images.size)
        if (history == null) {
            media.forEach { image -> require(snapshot.records.any { it.entry.id == image.sourceId && it.entry.currentRevision == image.sourceRevision }) }
            snapshot.records.filter { it.entry.originalText.isBlank() }.forEach { record ->
                require(media.any { it.sourceId==record.entry.id && it.sourceRevision==record.entry.currentRevision })
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
                    record.sourceTagVersion == current.tags.version && record.tags == current.tags.names && record.eventDate == current.eventDate.date)
                record.history.filter { it.originalText.isBlank() }.forEach { revision ->
                    require(media.any { it.sourceId == record.sourceId && it.sourceRevision == revision.revision })
                }
            }
            media.forEach { image -> require(history.any { it.sourceId == image.sourceId && image.sourceRevision in 1..it.sourceRevision }) }
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
