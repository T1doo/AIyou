package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.OriginalExports
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

data class UnifiedKeywordUi(val session:Long,val owner:UnifiedKeywordRepository,val request:Long=0,
    val keyword:String="",val scope:UnifiedKeywordScope=UnifiedKeywordScope.ALL,
    val snapshot:UnifiedKeywordSnapshot?=null,val page:Int=0,val busy:Boolean=false,val error:String?=null,
    val from:String="",val through:String="") {
    val visibleGroups get()=if(busy||error!=null)emptyList()else snapshot?.groups?.drop(page*20)?.take(20).orEmpty()
    override fun toString()="UnifiedKeywordUi(redacted)"
}
/** Constant-time session admission only. Source collections belong to boundary authentication. */
internal fun LocalRecordUiState.canRetainUnifiedSession(u:UnifiedKeywordUi):Boolean {
    if(!ready||opening||error!=null||unifiedKeywordOwner!==u.owner||saving||savePending||
        originalEdit!=null||originalHistory!=null||recordPackage!=null||recycleBin!=null||
        audioTranscripts!=null||imageTexts!=null||sourcePreferences!=null||sourceMemories!=null||memoryInbox!=null||
        localProcessing!=null||ai!=null||sourceTags!=null||candidates!=null||sourceTask!=null||manualTagEditor!=null||manualPeopleEditor!=null||eventDateEditor!=null)return false
    return true
}
internal fun LocalRecordUiState.canRetainUnified(u:UnifiedKeywordUi):Boolean {
    if(!canRetainUnifiedSession(u))return false
    val proof=u.snapshot
    if(proof!=null&&(proof.keyword!=u.keyword||proof.scope!=u.scope||
        runCatching{UnifiedCaptureRange.parse(u.from,u.through)}.getOrNull()!=proof.captureRange))return false
    val byId=entries.associateBy{it.id}
    if(byId.size!=entries.size)return false
    return u.snapshot?.bindings.orEmpty().all{b->val e=byId[b.source.sourceId]
        e!=null&&e.currentRevision.toLong()==b.source.revision&&OriginalExports.hash(e.originalText)==b.hash&&
        e.policy==b.policy&&e.capturedAtMillis==b.captured&&e.captureTimeZone==b.zone&&
        (!supportsLocalProcessing||localProcessingAvailable&&localProcessingVersions[b.source.sourceId]?.generation==b.generation)}
}

/** Invoke confirmation only while holding the caller's navigation owner. The controller never
 * holds its lifecycle lock while waiting for navigation: lock order is navigation -> lifecycle. */
typealias UnifiedKeywordHandoff = ((()->Unit)->Boolean)
internal class UnifiedKeywordRetirement(val session:Long,val request:Long,val lifetime:Long,val completeSources:Boolean)

class UnifiedKeywordController(private val current:()->LocalRecordUiState,
    private val publish:((LocalRecordUiState)->LocalRecordUiState)->Unit,
    private val enqueue:(suspend(EntryRepository)->Unit)->Boolean) {
    private val tokens=AtomicLong()
    private val handoffLock=Any()
    private var lifetime=0L
    @Volatile private var disposed=false
    private class ReturnSeed(val keyword:String,val scope:UnifiedKeywordScope,val range:UnifiedCaptureRange){override fun toString()="UnifiedReturnSeed(redacted)"}
    private var returnSeed:ReturnSeed?=null
    private var activeRequest:Pair<Long,Long>?=null
    private var activeScope=UnifiedKeywordScope.ALL
    private var activeCaptureRange=UnifiedCaptureRange()
    private class Retired:RuntimeException("Unified lookup retired")
    internal fun observeRetirement():UnifiedKeywordRetirement?=synchronized(handoffLock){
        val u=current().unifiedKeyword;val pair=u?.let{it.session to it.request}?:activeRequest?:return@synchronized null
        val scope=u?.scope?:activeScope
        val dates=u?.let{it.from.isNotEmpty()||it.through.isNotEmpty()}?:!activeCaptureRange.isUnbounded
        UnifiedKeywordRetirement(pair.first,pair.second,lifetime,scope==UnifiedKeywordScope.ALL||scope==UnifiedKeywordScope.ACCEPTED_MEMORY||dates)
    }
    internal fun retireObserved(observed:UnifiedKeywordRetirement)=synchronized(handoffLock){
        val u=current().unifiedKeyword;val pair=u?.let{it.session to it.request}?:activeRequest
        if(lifetime==observed.lifetime&&pair==(observed.session to observed.request)){
            lifetime=Math.addExact(lifetime,1);returnSeed=null;activeRequest=null;activeCaptureRange=UnifiedCaptureRange()
            publish{l->if(l.unifiedKeyword?.let{it.session==observed.session&&it.request==observed.request}==true)l.copy(unifiedKeyword=null)else l}
        }
    }
    private fun owns(session:Long,request:Long?=null):Boolean {
        val l=current();val u=l.unifiedKeyword?:return false
        return !disposed&&u.session==session&&(request==null||u.request==request)&&l.canRetainUnified(u)
    }
    /** Used only during bounded SQL reads. Never iterate entries or a retained proof here. */
    private fun requestCurrent(session:Long,request:Long,epoch:Long,owner:UnifiedKeywordRepository):Boolean=synchronized(handoffLock){
        val l=current();val u=l.unifiedKeyword
        !disposed&&lifetime==epoch&&activeRequest?.first==session&&activeRequest?.second==request&&
            u!=null&&u.session==session&&u.request==request&&u.owner===owner&&l.canRetainUnifiedSession(u)
    }
    private fun update(session:Long,request:Long?=null,change:(UnifiedKeywordUi)->UnifiedKeywordUi)=publish{l->
        val u=l.unifiedKeyword
        if(disposed||u==null||u.session!=session||request!=null&&u.request!=request||!l.canRetainUnified(u))l
        else l.copy(unifiedKeyword=change(u).takeIf{l.canRetainUnified(it)})
    }
    fun open(){openWith("",UnifiedKeywordScope.ALL)}
    private fun openWith(keyword:String,scope:UnifiedKeywordScope,range:UnifiedCaptureRange=UnifiedCaptureRange())=synchronized(handoffLock) {
        val l=current();val owner=l.unifiedKeywordOwner?:return@synchronized
        if(disposed||l.unifiedKeyword!=null)return@synchronized
        val u=UnifiedKeywordUi(tokens.incrementAndGet(),owner,keyword=keyword,scope=scope,from=range.from?.toString().orEmpty(),through=range.through?.toString().orEmpty())
        if(!l.canRetainUnified(u))return@synchronized
        lifetime=Math.addExact(lifetime,1)
        activeRequest=u.session to u.request;activeScope=scope;activeCaptureRange=range
        publish{if(it.unifiedKeyword==null&&it.canRetainUnified(u))it.copy(unifiedKeyword=u)else it}
        if(keyword.isNotBlank())search(u.session)
    }
    fun close(session:Long)=synchronized(handoffLock) {
        var closed=false
        publish{if(it.unifiedKeyword?.session==session){closed=true;it.copy(unifiedKeyword=null)}else it}
        if(closed){lifetime=Math.addExact(lifetime,1);returnSeed=null;activeRequest=null;activeCaptureRange=UnifiedCaptureRange()}
    }
    fun retire()=synchronized(handoffLock) {
        lifetime=Math.addExact(lifetime,1);returnSeed=null;activeRequest=null;activeCaptureRange=UnifiedCaptureRange();publish{it.copy(unifiedKeyword=null)}
    }
    fun resume()=synchronized(handoffLock) {
        val seed=returnSeed?:return@synchronized;returnSeed=null;openWith(seed.keyword,seed.scope,seed.range)
    }
    fun dispose()=synchronized(handoffLock){disposed=true;retire()}
    fun keyword(session:Long,value:String){if(value.length>256)return;update(session){
        if(it.keyword==value)it else it.copy(keyword=value,request=tokens.incrementAndGet(),snapshot=null,page=0,busy=false,error=null)
    }}
    fun scope(session:Long,value:UnifiedKeywordScope){update(session){
        if(it.scope==value)it else it.copy(scope=value,request=tokens.incrementAndGet(),snapshot=null,page=0,busy=false,error=null)
    }}
    fun from(session:Long,value:String){if(value.length>64)return;update(session){
        if(it.from==value)it else it.copy(from=value,request=tokens.incrementAndGet(),snapshot=null,page=0,busy=false,error=null)
    }}
    fun through(session:Long,value:String){if(value.length>64)return;update(session){
        if(it.through==value)it else it.copy(through=value,request=tokens.incrementAndGet(),snapshot=null,page=0,busy=false,error=null)
    }}
    fun unlimitedDates(session:Long)=update(session){it.copy(from="",through="",request=tokens.incrementAndGet(),snapshot=null,page=0,busy=false,error=null)}
    fun clear(session:Long)=update(session){it.copy(keyword="",from="",through="",request=tokens.incrementAndGet(),snapshot=null,page=0,busy=false,error=null)}
    fun search(session:Long){val u=current().unifiedKeyword?:return;if(!owns(session)||u.busy)return
        val range=try{UnifiedCaptureRange.parse(u.from,u.through)}catch(e:UnifiedCaptureRangeInvalid){
            update(session){it.copy(snapshot=null,page=0,busy=false,error=when(e.problem){
                UnifiedCaptureRangeProblem.FROM->"起始捕获日期无效，请按 YYYY-MM-DD 输入或留空。"
                UnifiedCaptureRangeProblem.THROUGH->"结束捕获日期无效，请按 YYYY-MM-DD 输入或留空。"
                UnifiedCaptureRangeProblem.ORDER->"起始捕获日期不得晚于结束日期。"})};return
        }
        try{PreferenceKeywords.validate(u.keyword)}catch(_:Exception){update(session){it.copy(snapshot=null,busy=false,error="关键词最多64字，不能含控制字符或不完整字符。")};return}
        if(u.keyword.isBlank()){update(session){it.copy(keyword="",snapshot=null,page=0,busy=false,error=null)};return}
        execute(u,null,0,null,null,range)
    }
    fun next(session:Long,previous:Boolean=false){val u=current().unifiedKeyword?:return;val proof=u.snapshot?:return
        if(!owns(session)||u.busy)return
        val page=u.page+if(previous)-1 else 1
        if(page<0||page*20>=proof.groups.size)return
        execute(u,proof,page,null,null,proof.captureRange)
    }
    fun locate(session:Long,item:UnifiedKeywordEvidence,onLocate:(UnifiedKeywordEvidence,UnifiedKeywordHandoff)->Unit){
        val u=current().unifiedKeyword?:return;val proof=u.snapshot?:return
        if(!owns(session)||u.busy||u.visibleGroups.none{g->g.items.any{it===item}})return
        execute(u,proof,u.page,item,onLocate,proof.captureRange)
    }
    private fun execute(before:UnifiedKeywordUi,proof:UnifiedKeywordSnapshot?,page:Int,item:UnifiedKeywordEvidence?,onLocate:((UnifiedKeywordEvidence,UnifiedKeywordHandoff)->Unit)?,range:UnifiedCaptureRange) {
        val epoch=synchronized(handoffLock){lifetime}
        val req=tokens.incrementAndGet();update(before.session){it.copy(request=req,busy=true,error=null)}
        if(!owns(before.session,req))return
        synchronized(handoffLock){if(lifetime!=epoch)return;activeRequest=before.session to req;activeScope=before.scope;activeCaptureRange=range}
        if(!enqueue{store->
            if(!owns(before.session,req)||store.unifiedKeywords!==before.owner)return@enqueue
            try {
                val checkCurrent={if(!requestCurrent(before.session,req,epoch,before.owner)||store.unifiedKeywords!==before.owner)throw Retired()}
                val result=if(proof==null)before.owner.search(before.keyword,before.scope,range,checkCurrent)else {
                    var ticket=false
                    if(!before.owner.resolve(proof,item,checkCurrent){ticket=owns(before.session,req)}||!ticket)throw UnifiedKeywordConflict()
                    proof
                }
                if(result.keyword!=before.keyword||result.scope!=before.scope||result.captureRange!=range)throw UnifiedKeywordConflict()
                // No UI content or route is published from the transaction's private ticket.
                // A thrown/unknown commit acknowledgement reaches only the failure branch.
                if(!owns(before.session,req))return@enqueue
                if(item==null)update(before.session,req){it.copy(snapshot=result,page=page,busy=false,error=null)}
                else {
                    var admitted=false
                    publish{l->val u=l.unifiedKeyword;admitted=!disposed&&u?.session==before.session&&u.request==req&&l.canRetainUnified(u)
                        if(admitted)l.copy(unifiedKeyword=null)else l}
                    if(admitted){
                        var used=false
                        val confirm:UnifiedKeywordHandoff={action->synchronized(handoffLock){
                            val l=current()
                            if(used||disposed||lifetime!=epoch||l.unifiedKeyword!=null||!l.canRetainUnified(before.copy(snapshot=result)))false
                            else {
                                used=true;returnSeed=ReturnSeed(before.keyword,before.scope,range)
                                try{action();true}catch(t:Throwable){returnSeed=null;throw t}
                            }
                        }}
                        // UI is already null here. Retirement must still invalidate this offer.
                        // Never hold handoffLock while a caller obtains its navigation lock.
                        if(synchronized(handoffLock){!disposed&&lifetime==epoch})onLocate?.invoke(item,confirm)
                    }
                }
            }catch(_:Retired){ /* The real transaction has unwound; do not cancel the shared actor. */ }
            catch(c:CancellationException){throw c}
            catch(_:UnifiedCaptureRangeUnsupported){update(before.session,req){it.copy(snapshot=null,page=0,busy=false,
                error="当前查询端口不支持捕获日期范围，未显示未过滤内容。")}}
            catch(_:UnifiedKeywordTooBroad){update(before.session,req){it.copy(snapshot=null,page=0,busy=false,
                error="已采纳线索命中过多，请使用更具体的关键词或切换范围；未显示部分结果，原件未改。")}}
            catch(_:Exception){failed(before.session,req)}
        })failed(before.session,req)
    }
    private fun failed(session:Long,request:Long)=update(session,request){it.copy(snapshot=null,page=0,busy=false,
        error="所选内容尚未完整核对，未显示部分结果或空结果。请重试或切换内容范围；原件未改。")}
}

internal fun unifiedOriginalHit(item:UnifiedKeywordEvidence.Original,keyword:String):OriginalSearchHit? {
    val e=item.entry;val at=e.originalText.indexOf(keyword)
    if(at<0||keyword.isEmpty())return null
    val total=e.originalText.codePointCount(0,e.originalText.length)
    val match=e.originalText.codePointCount(0,at);val matchEnd=e.originalText.codePointCount(0,at+keyword.length)
    val start=(match-24).coerceAtLeast(0);val end=(start+240).coerceAtMost(total)
    val quote=e.originalText.substring(e.originalText.offsetByCodePoints(0,start),e.originalText.offsetByCodePoints(0,end))
    return OriginalSearchHit(e,quote,start,end,start>0,end<total,match,matchEnd)
}

internal fun unifiedOriginalLocation(item:UnifiedKeywordEvidence,keyword:String):OriginalReadingLocation? {
    val b=item.binding
    val range=when(item){
        is UnifiedKeywordEvidence.Original->{val hit=unifiedOriginalHit(item,keyword)?:return null
            if(originalSearchPreview(item.entry,hit)==null)return null
            hit.matchStartCodePoint to hit.matchEndCodePoint}
        is UnifiedKeywordEvidence.Preference->item.value.current?.content?.reference?.let{it.start to it.end}?:return null
        is UnifiedKeywordEvidence.AcceptedMemory->{val m=item.value;val ref=m.content.reference
            if(m.quoteMatch){val at=ref.quote.indexOf(keyword);if(at<0||keyword.isEmpty())return null
                val start=ref.start+ref.quote.codePointCount(0,at);start to start+keyword.codePointCount(0,keyword.length)
            }else ref.start to ref.end}
        else->return null
    }
    return OriginalReadingLocation(b.source.sourceId,b.source.revision.toInt(),b.hash,range.first,range.second)
}
