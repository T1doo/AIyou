package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar dates in each original's saved zone, never the device's current zone. */
data class UnifiedCaptureRange(val from:LocalDate?=null,val through:LocalDate?=null) {
    init {require(from==null||from.year in 0..9999);require(through==null||through.year in 0..9999);require(from==null||through==null||from<=through)}
    val isUnbounded get()=from==null&&through==null
    fun contains(captured:Long,zone:String):Boolean {
        val date=Instant.ofEpochMilli(captured).atZone(ZoneId.of(zone)).toLocalDate()
        return (from==null||date>=from)&&(through==null||date<=through)
    }
    override fun toString()="UnifiedCaptureRange(redacted)"
    companion object {
        fun parse(from:String,through:String):UnifiedCaptureRange {
            fun date(value:String,problem:UnifiedCaptureRangeProblem):LocalDate? {
                if(value.isEmpty())return null
                if(!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))throw UnifiedCaptureRangeInvalid(problem)
                return try{LocalDate.parse(value)}catch(_:Exception){throw UnifiedCaptureRangeInvalid(problem)}
            }
            val a=date(from,UnifiedCaptureRangeProblem.FROM);val b=date(through,UnifiedCaptureRangeProblem.THROUGH)
            if(a!=null&&b!=null&&a>b)throw UnifiedCaptureRangeInvalid(UnifiedCaptureRangeProblem.ORDER)
            return UnifiedCaptureRange(a,b)
        }
    }
}
enum class UnifiedCaptureRangeProblem { FROM, THROUGH, ORDER }
class UnifiedCaptureRangeInvalid(val problem:UnifiedCaptureRangeProblem):IllegalArgumentException("Invalid capture date range")
class UnifiedCaptureRangeUnsupported:IllegalStateException("Capture date range is unavailable")

enum class UnifiedKeywordScope { ALL, ORIGINAL, AUDIO, IMAGE, PREFERENCE, ACCEPTED_MEMORY, FACT }
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
    class Fact(binding:UnifiedKeywordBinding,val value:SourceFactSnapshot):UnifiedKeywordEvidence(binding) {override val key="F:${value.id}"}
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
    val captureRange:UnifiedCaptureRange get()=UnifiedCaptureRange()
    val tag:UnifiedTagSelection? get()=null
    val person:UnifiedPersonSelection? get()=null
    val onlyFavorites:Boolean get()=false
    val onlyReadyImages:Boolean get()=false
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
    /** A legacy port must never silently ignore an explicit date restriction. */
    suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,checkCurrent:()->Unit):UnifiedKeywordSnapshot {
        if(!captureRange.isUnbounded)throw UnifiedCaptureRangeUnsupported()
        return search(keyword,scope,checkCurrent)
    }
    /** Owner-private tag capabilities: display properties alone are never proof. */
    suspend fun tagChoices(after:UnifiedTagPage?=null,checkCurrent:()->Unit):UnifiedTagPage = throw UnifiedTagUnsupported()
    suspend fun selectTag(page:UnifiedTagPage,choice:UnifiedTagChoice,checkCurrent:()->Unit):UnifiedTagSelection = throw UnifiedTagUnsupported()
    /** Explicit return requery obtains a new collection proof, never revives the old query proof. */
    suspend fun refreshTag(selection:UnifiedTagSelection,checkCurrent:()->Unit):UnifiedTagSelection = throw UnifiedTagUnsupported()
    suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,checkCurrent:()->Unit):UnifiedKeywordSnapshot {
        if(tag!=null)throw UnifiedTagUnsupported()
        return search(keyword,scope,captureRange,checkCurrent)
    }
    /** A record-local manual name marker, never a globally resolved person identity. */
    suspend fun personChoices(after:UnifiedPersonPage?=null,checkCurrent:()->Unit):UnifiedPersonPage = throw UnifiedPersonUnsupported()
    suspend fun selectPerson(page:UnifiedPersonPage,choice:UnifiedPersonChoice,checkCurrent:()->Unit):UnifiedPersonSelection = throw UnifiedPersonUnsupported()
    suspend fun refreshPerson(selection:UnifiedPersonSelection,checkCurrent:()->Unit):UnifiedPersonSelection = throw UnifiedPersonUnsupported()
    suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,person:UnifiedPersonSelection?,checkCurrent:()->Unit):UnifiedKeywordSnapshot {
        if(person!=null)throw UnifiedPersonUnsupported()
        return search(keyword,scope,captureRange,tag,checkCurrent)
    }
    /** An old port must never silently drop an explicit original-favorite intersection. */
    suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,person:UnifiedPersonSelection?,onlyFavorites:Boolean,checkCurrent:()->Unit):UnifiedKeywordSnapshot {
        if(onlyFavorites)throw UnifiedFavoriteUnsupported()
        return search(keyword,scope,captureRange,tag,person,checkCurrent)
    }
    /** Explicit READY qualification must never fall back to the old any-state image filter. */
    suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,person:UnifiedPersonSelection?,onlyFavorites:Boolean,onlyReadyImages:Boolean,checkCurrent:()->Unit):UnifiedKeywordSnapshot {
        if(onlyReadyImages)throw UnifiedReadyImagesUnsupported()
        return search(keyword,scope,captureRange,tag,person,onlyFavorites,checkCurrent)
    }
    /** Reauthenticates media, then jointly verifies all sets. Callback is a PRIVATE admission
     * ticket only: callers must not render or navigate until this method returns true. */
    suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?=null,admit:()->Unit):Boolean
    suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,checkCurrent:()->Unit,admit:()->Unit):Boolean {
        checkCurrent();return resolve(snapshot,item){checkCurrent();admit()}
    }
}
class UnifiedKeywordConflict:IllegalStateException("Unified local lookup changed")
class UnifiedFavoriteUnsupported:IllegalStateException("Original favorite filter is unavailable")
class UnifiedKeywordTooBroad:IllegalStateException("Accepted memory lookup has too many matches")

interface UnifiedTagChoice { val id:String; val name:String }
interface UnifiedTagPage { val choices:List<UnifiedTagChoice>; val hasNext:Boolean }
interface UnifiedTagSelection { val id:String; val name:String }
class UnifiedTagUnsupported:IllegalStateException("Manual tag filter is unavailable")

interface UnifiedPersonChoice { val id:String; val name:String }
interface UnifiedPersonPage { val choices:List<UnifiedPersonChoice>; val hasNext:Boolean }
interface UnifiedPersonSelection { val id:String; val name:String }
class UnifiedPersonUnsupported:IllegalStateException("Manual person marker filter is unavailable")

class UnifiedReadyImagesUnsupported:IllegalStateException("Current ready-image filter is unavailable")

class UnifiedReadyImageLimit:IllegalStateException("Current image associations exceed authentication limit")
