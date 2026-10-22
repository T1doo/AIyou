package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

enum class UnifiedKeywordScope { ALL, ORIGINAL, AUDIO, IMAGE, PREFERENCE, ACCEPTED_MEMORY }
data class UnifiedKeywordBinding(val source:SourceVersion,val hash:String,val generation:Long,
    val captured:Long,val zone:String,val policy:ProcessingPolicy) {
    override fun toString()="UnifiedKeywordBinding(redacted)"
}
sealed class UnifiedKeywordEvidence(val binding:UnifiedKeywordBinding) {
    abstract val key:String
    class Original(binding:UnifiedKeywordBinding,val entry:StoredEntry):UnifiedKeywordEvidence(binding) {override val key="O:${entry.id}:${entry.currentRevision}"}
    class Audio(binding:UnifiedKeywordBinding,val value:AudioTranscriptSnapshot):UnifiedKeywordEvidence(binding) {override val key="A:${value.id}"}
    class Image(binding:UnifiedKeywordBinding,val value:ImageTextSnapshot):UnifiedKeywordEvidence(binding) {override val key="I:${value.id}"}
    class Preference(binding:UnifiedKeywordBinding,val value:SourcePreferenceSnapshot):UnifiedKeywordEvidence(binding) {override val key="P:${value.id}"}
    class AcceptedMemory(val value:AcceptedMemoryKeywordHit):UnifiedKeywordEvidence(value.binding) {override val key="M:${value.id}"}
    override fun toString()="UnifiedKeywordEvidence(redacted)"
}
/** Only a current accepted projection is retained; complete history is authenticated in the owner. */
class AcceptedMemoryKeywordHit(val id:String,val binding:UnifiedKeywordBinding,val rowVersion:Long,
    val contentRevision:Int,val content:SourceMemoryContent,val quoteMatch:Boolean,val noteMatch:Boolean) {
    init {
        ManualTags.identifier(id);require(rowVersion>0 && contentRevision in 1..8)
        require(content.reference.source==binding.source && (quoteMatch || noteMatch))
    }
    override fun toString()="AcceptedMemoryKeywordHit(redacted)"
}
class UnifiedKeywordGroup(val binding:UnifiedKeywordBinding,items:List<UnifiedKeywordEvidence>) {
    val items:List<UnifiedKeywordEvidence> = java.util.Collections.unmodifiableList(items.toList())
    val key get()="${binding.source.sourceId}:${binding.source.revision}"
    override fun toString()="UnifiedKeywordGroup(redacted)"
}
interface UnifiedKeywordSnapshot {
    val keyword:String
    val scope:UnifiedKeywordScope
    val groups:List<UnifiedKeywordGroup>
    /** Includes off-screen and nonmatching sources retained by the authenticated collection proof. */
    val bindings:List<UnifiedKeywordBinding>
}
interface UnifiedKeywordRepository {
    /** Returns only after common SQL validation and transaction commit acknowledgement. */
    suspend fun search(keyword:String,scope:UnifiedKeywordScope):UnifiedKeywordSnapshot
    /** The actual owner checks between bounded reads; callers cannot use cancellation as a SQL ACK. */
    suspend fun search(keyword:String,scope:UnifiedKeywordScope,checkCurrent:()->Unit):UnifiedKeywordSnapshot {
        checkCurrent();return search(keyword,scope).also{checkCurrent()}
    }
    /** Reauthenticates media, then jointly verifies all sets. Callback is a PRIVATE admission
     * ticket only: callers must not render or navigate until this method returns true. */
    suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?=null,admit:()->Unit):Boolean
    suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,checkCurrent:()->Unit,admit:()->Unit):Boolean {
        checkCurrent();return resolve(snapshot,item){checkCurrent();admit()}
    }
}
class UnifiedKeywordConflict:IllegalStateException("Unified local lookup changed")
class UnifiedKeywordTooBroad:IllegalStateException("Accepted memory lookup has too many matches")
