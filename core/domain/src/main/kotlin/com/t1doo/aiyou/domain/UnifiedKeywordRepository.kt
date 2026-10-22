package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

enum class UnifiedKeywordScope { ALL, ORIGINAL, AUDIO, IMAGE, PREFERENCE }
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
    override fun toString()="UnifiedKeywordEvidence(redacted)"
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
    /** Reauthenticates media, then jointly verifies all sets. Callback is a PRIVATE admission
     * ticket only: callers must not render or navigate until this method returns true. */
    suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?=null,admit:()->Unit):Boolean
}
class UnifiedKeywordConflict:IllegalStateException("Unified local lookup changed")
