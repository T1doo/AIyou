package com.t1doo.aiyou.model

/** Exact immutable PCM source for human text; never an ASR result. */
data class AudioTranscriptSource(val intent:AttachmentIntent,val sourceHash:String,val generation:Long,
    val capturedAtMillis:Long,val zone:String,val policy:ProcessingPolicy,val samples:Int) {
    init { PreferenceValues.uuid(intent.id);SourceVersion(intent.entryId,intent.revision.toLong());require(intent.sha256.matches(Regex("[0-9a-f]{64}")) && intent.byteSize==44L+samples*2L);require(intent.mediaType=="audio/wav" && intent.revision>0 && sourceHash.matches(Regex("[0-9a-f]{64}")) && generation>=0 && capturedAtMillis>=0 && samples in 1..LocalPcmAudio.MAX_SAMPLES);java.time.ZoneId.of(zone) }
    val source get()=SourceVersion(intent.entryId,intent.revision.toLong())
    override fun toString()="AudioTranscriptSource(redacted)"
}
data class AudioTranscriptVersion(val revision:Int,val text:String,val at:Long,val range:AudioSampleRange?=null) {
    init { require(revision in 1..8 && at>=0);AudioTranscripts.text(text) }
    override fun toString()="AudioTranscriptVersion(redacted)"
}
class AudioTranscriptSnapshot(val id:String,val anchor:AudioTranscriptSource,val version:Long,val withdrawn:Boolean,values:List<AudioTranscriptVersion>) {
    val history:List<AudioTranscriptVersion> = java.util.Collections.unmodifiableList(values.toList())
    init { PreferenceValues.uuid(id);require(version>0 && history.isNotEmpty() && history.map {it.revision}==(1..history.size).toList() && version in (history.size.toLong()+if(withdrawn)1 else 0)..(history.size*2L-if(withdrawn)0 else 1));history.forEach {it.range?.validate(anchor.samples)} }
    val current get()=history.last()
    fun same(other:AudioTranscriptSnapshot)=id==other.id && anchor==other.anchor && version==other.version && withdrawn==other.withdrawn && history==other.history
    override fun toString()="AudioTranscriptSnapshot(redacted)"
}
class AudioTranscriptWrite(val id:String,val anchor:AudioTranscriptSource,val expected:AudioTranscriptSnapshot?,val text:String?,val withdraw:Boolean,val mutationId:String,val at:Long,val range:AudioSampleRange?=if(withdraw)null else expected?.current?.range) {
    init { PreferenceValues.uuid(id);PreferenceValues.uuid(mutationId);require(at>=0 && (expected==null || expected.id==id && expected.anchor==anchor));if(withdraw)require(expected!=null && !expected.withdrawn && text==null && range==null) else {AudioTranscripts.text(requireNotNull(text));range?.validate(anchor.samples)} }
    override fun toString()="AudioTranscriptWrite(redacted)"
}
object AudioTranscripts {
    fun text(value:String) { require(value.isNotBlank() && value.length<=2000 && value.codePointCount(0,value.length)<=1000 && value.toByteArray(Charsets.UTF_8).size<=4000);var i=0;while(i<value.length) {val c=value[i];require(!Character.isLowSurrogate(c));if(Character.isHighSurrogate(c)){require(i+1<value.length && Character.isLowSurrogate(value[i+1]));i++};i++} }
}
