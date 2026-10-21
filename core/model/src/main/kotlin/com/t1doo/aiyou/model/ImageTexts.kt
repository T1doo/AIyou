package com.t1doo.aiyou.model

/** Exact encrypted original, not a thumbnail, edited bitmap, URI, or a textual guess. */
data class ImageTextSource(val intent:AttachmentIntent,val sourceHash:String,val generation:Long,
    val capturedAtMillis:Long,val zone:String,val policy:ProcessingPolicy) {
    init {
        RecordPackageImage(intent.entryId,intent.revision,intent.id,intent.mediaType,intent.byteSize,intent.sha256)
        SourceVersion(intent.entryId,intent.revision.toLong())
        require(sourceHash.matches(Regex("[0-9a-f]{64}"))&&generation>=0&&capturedAtMillis>=0)
        java.time.ZoneId.of(zone)
    }
    val source get()=SourceVersion(intent.entryId,intent.revision.toLong())
    /** This slice locates the complete original image; it does not invent word bounding boxes. */
    val sourceMap get()="WHOLE_ORIGINAL_IMAGE"
    override fun toString()="ImageTextSource(redacted)"
}
data class ImageTextEngine(val id:String,val version:String,val synthetic:Boolean=false) {
    init { require(id.matches(Regex("[A-Za-z0-9._-]{1,64}"))&&version.matches(Regex("[A-Za-z0-9._-]{1,64}"))) }
    override fun toString()="ImageTextEngine(redacted)"
}
/** The first content is produced by an explicitly invoked engine; later versions are human corrections. */
data class ImageTextVersion(val revision:Int,val text:String,val humanCorrection:Boolean,val at:Long) {
    init {require(revision in 1..8&&at>=0&&humanCorrection==(revision>1));ImageTexts.text(text)}
    override fun toString()="ImageTextVersion(redacted)"
}
class ImageTextSnapshot(val id:String,val anchor:ImageTextSource,val engine:ImageTextEngine,values:List<ImageTextVersion>) {
    val history:List<ImageTextVersion> = java.util.Collections.unmodifiableList(values.toList())
    val version:Long get()=history.size.toLong()
    val current get()=history.last()
    init {PreferenceValues.uuid(id);require(anchor.policy!=ProcessingPolicy.AI_NONE);require(history.isNotEmpty()&&history.map{it.revision}==(1..history.size).toList())}
    fun same(other:ImageTextSnapshot)=id==other.id&&anchor==other.anchor&&engine==other.engine&&history==other.history
    override fun toString()="ImageTextSnapshot(redacted)"
}
data class ImageTextExtraction(val engine:ImageTextEngine,val text:String) {
    init {ImageTexts.text(text)}
    override fun toString()="ImageTextExtraction(redacted)"
}
/** An immutable CAS intent. Deletion removes ciphertext/history; its opaque receipt alone remains. */
class ImageTextWrite(val id:String,val anchor:ImageTextSource,val expected:ImageTextSnapshot?,
    val extraction:ImageTextExtraction?,val text:String?,val delete:Boolean,val mutationId:String,val at:Long) {
    init {
        PreferenceValues.uuid(id);PreferenceValues.uuid(mutationId);require(at>=0)
        require(expected==null||expected.id==id&&expected.anchor==anchor)
        if(delete)require(expected!=null&&extraction==null&&text==null)
        else if(expected==null)require(anchor.policy!=ProcessingPolicy.AI_NONE&&extraction!=null&&text==null)
        else {require(extraction==null);ImageTexts.text(requireNotNull(text))}
    }
    override fun toString()="ImageTextWrite(redacted)"
}

object ImageTexts {
    /** Same bounded Unicode frame: <=1000 code points/4000 UTF-8 bytes, eight content versions. */
    fun text(value:String)=AudioTranscripts.text(value)
}
