package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.OriginalExports
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

data class UnifiedKeywordUi(val session:Long,val owner:UnifiedKeywordRepository,val request:Long=0,
    val keyword:String="",val scope:UnifiedKeywordScope=UnifiedKeywordScope.ALL,
    val snapshot:UnifiedKeywordSnapshot?=null,val page:Int=0,val busy:Boolean=false,val error:String?=null,
    val from:String="",val through:String="",
    val tag:UnifiedTagSelection?=null,val tagPicker:Boolean=false,val tagPage:UnifiedTagPage?=null,
    val tagBusy:Boolean=false,val tagError:String?=null,
    val person:UnifiedPersonSelection?=null,val personPicker:Boolean=false,val personPage:UnifiedPersonPage?=null,
    val personBusy:Boolean=false,val personError:String?=null,val onlyFavorites:Boolean=false,val onlyReadyImages:Boolean=false) {
    val visibleGroups get()=if(busy||error!=null||tagPicker||tagBusy||personPicker||personBusy)emptyList()else snapshot?.groups?.drop(page*20)?.take(20).orEmpty()
    override fun toString()="UnifiedKeywordUi(redacted)"
}
internal fun LocalRecordUiState.unifiedFavoritesReady()=supportsFavorites&&favoritesAvailable&&!favoritesLoading&&
    favoritesError==null&&!favoriteControllerRetired&&favoriteWrites.isEmpty()
internal fun sameUnifiedFavorites(a:Map<String,com.t1doo.aiyou.model.RecordFavoriteSnapshot>,b:Map<String,com.t1doo.aiyou.model.RecordFavoriteSnapshot>):Boolean =
    a.size==b.size&&a.all{(id,x)->b[id]?.let{y->x.entryId==y.entryId&&x.version==y.version&&x.isFavorite==y.isFavorite}==true}

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
    if(proof!=null&&u.onlyFavorites&&!unifiedFavoritesReady())return false
    if(proof!=null&&(proof.keyword!=u.keyword||proof.scope!=u.scope||proof.tag!==u.tag||proof.person!==u.person||proof.onlyFavorites!=u.onlyFavorites||proof.onlyReadyImages!=u.onlyReadyImages||
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
internal class UnifiedKeywordRetirement(val session:Long,val request:Long,val lifetime:Long,val completeSources:Boolean,val tags:Boolean=false,val people:Boolean=false,val favorites:Boolean=false,val images:Boolean=false)

class UnifiedKeywordController(private val current:()->LocalRecordUiState,
    private val publish:((LocalRecordUiState)->LocalRecordUiState)->Unit,
    private val enqueue:(suspend(EntryRepository)->Unit)->Boolean) {
    private val tokens=AtomicLong()
    private val handoffLock=Any()
    private var lifetime=0L
    @Volatile private var disposed=false
    private class ReturnSeed(val keyword:String,val scope:UnifiedKeywordScope,val range:UnifiedCaptureRange,val tag:UnifiedTagSelection?,val person:UnifiedPersonSelection?,val onlyFavorites:Boolean,val onlyReadyImages:Boolean){override fun toString()="UnifiedReturnSeed(redacted)"}
    private var returnSeed:ReturnSeed?=null
    private var activeRequest:Pair<Long,Long>?=null
    private var activeScope=UnifiedKeywordScope.ALL
    private var activeCaptureRange=UnifiedCaptureRange()
    private var activeTags=false
    private var activePeople=false
    private var activeImages=false
    private var activeFavorites=false
    private class Retired:RuntimeException("Unified lookup retired")
    internal fun observeRetirement():UnifiedKeywordRetirement?=synchronized(handoffLock){
        val u=current().unifiedKeyword;val pair=u?.let{it.session to it.request}?:activeRequest?:return@synchronized null
        val scope=u?.scope?:activeScope
        val dates=u?.let{it.from.isNotEmpty()||it.through.isNotEmpty()}?:!activeCaptureRange.isUnbounded
        val tags=u?.let{it.tag!=null||it.tagPicker||it.tagBusy}?:activeTags
        val people=u?.let{it.person!=null||it.personPicker||it.personBusy}?:activePeople
        val favorites=u?.onlyFavorites?:activeFavorites
        val images=u?.onlyReadyImages?:activeImages
        UnifiedKeywordRetirement(pair.first,pair.second,lifetime,scope==UnifiedKeywordScope.ALL||scope==UnifiedKeywordScope.ACCEPTED_MEMORY||dates||tags||people||favorites||images,tags,people,favorites,images)
    }
    internal fun retireObserved(observed:UnifiedKeywordRetirement)=synchronized(handoffLock){
        val u=current().unifiedKeyword;val pair=u?.let{it.session to it.request}?:activeRequest
        if(lifetime==observed.lifetime&&pair==(observed.session to observed.request)){
            lifetime=Math.addExact(lifetime,1);returnSeed=null;activeRequest=null;activeCaptureRange=UnifiedCaptureRange();activeTags=false;activePeople=false;activeFavorites=false;activeImages=false
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
    private fun openWith(keyword:String,scope:UnifiedKeywordScope,range:UnifiedCaptureRange=UnifiedCaptureRange(),tag:UnifiedTagSelection?=null,person:UnifiedPersonSelection?=null,onlyFavorites:Boolean=false,onlyReadyImages:Boolean=false)=synchronized(handoffLock) {
        val l=current();val owner=l.unifiedKeywordOwner?:return@synchronized
        if(disposed||l.unifiedKeyword!=null)return@synchronized
        val u=UnifiedKeywordUi(tokens.incrementAndGet(),owner,keyword=keyword,scope=scope,from=range.from?.toString().orEmpty(),through=range.through?.toString().orEmpty(),tag=tag,person=person,onlyFavorites=onlyFavorites,onlyReadyImages=onlyReadyImages)
        if(!l.canRetainUnified(u))return@synchronized
        lifetime=Math.addExact(lifetime,1)
        activeRequest=u.session to u.request;activeScope=scope;activeCaptureRange=range;activeTags=tag!=null;activePeople=person!=null;activeFavorites=onlyFavorites;activeImages=onlyReadyImages
        publish{if(it.unifiedKeyword==null&&it.canRetainUnified(u))it.copy(unifiedKeyword=u)else it}
        if(tag!=null||person!=null){
            val refresh:suspend(UnifiedKeywordUi,()->Unit)->Unit={before,guard->
                val freshTag=tag?.let{before.owner.refreshTag(it,guard)};guard()
                val freshPerson=person?.let{before.owner.refreshPerson(it,guard)};guard()
                if(tag?.id!=freshTag?.id||tag?.name!=freshTag?.name||person?.id!=freshPerson?.id||person?.name!=freshPerson?.name)throw UnifiedKeywordConflict()
                update(before.session,before.request){it.copy(tag=freshTag,person=freshPerson,tagBusy=false,personBusy=false)}
                if(current().unifiedKeyword?.let{it.request==before.request&&it.tag===freshTag&&it.person===freshPerson}==true)search(before.session)
            }
            if(person!=null)personWork(u,refresh)else tagWork(u,refresh)
        }else if(keyword.isNotBlank())search(u.session)
    }
    fun close(session:Long)=synchronized(handoffLock) {
        var closed=false
        publish{if(it.unifiedKeyword?.session==session){closed=true;it.copy(unifiedKeyword=null)}else it}
        if(closed){lifetime=Math.addExact(lifetime,1);returnSeed=null;activeRequest=null;activeCaptureRange=UnifiedCaptureRange();activeTags=false;activePeople=false;activeFavorites=false;activeImages=false}
    }
    fun retire()=synchronized(handoffLock) {
        lifetime=Math.addExact(lifetime,1);returnSeed=null;activeRequest=null;activeCaptureRange=UnifiedCaptureRange();activeTags=false;activePeople=false;activeFavorites=false;activeImages=false;publish{it.copy(unifiedKeyword=null)}
    }
    fun resume()=synchronized(handoffLock) {
        val seed=returnSeed?:return@synchronized;returnSeed=null;openWith(seed.keyword,seed.scope,seed.range,seed.tag,seed.person,seed.onlyFavorites,seed.onlyReadyImages)
    }
    fun dispose()=synchronized(handoffLock){disposed=true;retire()}
    fun keyword(session:Long,value:String){if(value.length>256)return;update(session){
        if(it.keyword==value)it else it.copy(keyword=value,request=tokens.incrementAndGet(),snapshot=null,page=0,busy=false,error=null,tagBusy=false,personBusy=false)
    }}
    fun scope(session:Long,value:UnifiedKeywordScope){update(session){
        if(it.scope==value)it else it.copy(scope=value,request=tokens.incrementAndGet(),snapshot=null,page=0,busy=false,error=null,tagBusy=false,personBusy=false)
    }}
    fun from(session:Long,value:String){update(session){
        if(it.from==value)it else it.copy(from=value,request=tokens.incrementAndGet(),snapshot=null,page=0,busy=false,error=null,tagBusy=false,personBusy=false)
    }}
    fun through(session:Long,value:String){update(session){
        if(it.through==value)it else it.copy(through=value,request=tokens.incrementAndGet(),snapshot=null,page=0,busy=false,error=null,tagBusy=false,personBusy=false)
    }}
    fun unlimitedDates(session:Long)=update(session){it.copy(from="",through="",request=tokens.incrementAndGet(),snapshot=null,page=0,busy=false,error=null,tagBusy=false,personBusy=false)}
    fun clear(session:Long)=update(session){it.copy(keyword="",from="",through="",onlyFavorites=false,onlyReadyImages=false,tag=null,tagPicker=false,tagPage=null,tagBusy=false,tagError=null,person=null,personPicker=false,personPage=null,personBusy=false,personError=null,request=tokens.incrementAndGet(),snapshot=null,page=0,busy=false,error=null)}
    fun favorites(session:Long,value:Boolean)=update(session){
        if(it.onlyFavorites==value)it else it.copy(onlyFavorites=value,request=tokens.incrementAndGet(),snapshot=null,page=0,busy=false,
            tagPicker=false,tagPage=null,tagBusy=false,tagError=null,personPicker=false,personPage=null,personBusy=false,personError=null,error=if(value&&!current().unifiedFavoritesReady())"收藏状态尚未确认，请先重试读取或确认未结算的收藏操作。"else null)
    }
    /** Mutation was accepted outside state CAS; invalidate even a UI-null handoff/return seed. */
    fun readyImageMutationAccepted(){val observed=observeRetirement()?:return;if(observed.images)retireObserved(observed)}
    fun readyImages(session:Long,value:Boolean)=update(session){
        if(it.onlyReadyImages==value)it else it.copy(onlyReadyImages=value,request=tokens.incrementAndGet(),snapshot=null,page=0,busy=false,error=null,
            tagPicker=false,tagPage=null,tagBusy=false,tagError=null,personPicker=false,personPage=null,personBusy=false,personError=null)
    }
    fun openTags(session:Long){
        val u=current().unifiedKeyword?:return
        if(!owns(session)||u.busy||u.tagBusy||u.personBusy)return
        update(session){it.copy(request=tokens.incrementAndGet(),snapshot=null,page=0,tagPicker=true,tagPage=null,tagError=null,personPicker=false,personPage=null,personBusy=false)}
        loadTags(session)
    }
    fun closeTags(session:Long)=update(session){if(!it.tagPicker)it else it.copy(request=tokens.incrementAndGet(),tagPicker=false,tagPage=null,tagBusy=false,tagError=null)}
    fun clearTag(session:Long)=update(session){it.copy(request=tokens.incrementAndGet(),tag=null,tagPicker=false,tagPage=null,tagBusy=false,personBusy=false,tagError=null,snapshot=null,page=0,busy=false,error=null)}
    fun loadTags(session:Long,next:Boolean=false){
        val u=current().unifiedKeyword?:return
        if(!owns(session)||!u.tagPicker||u.tagBusy||next&&u.tagPage?.hasNext!=true)return
        val after=if(next)u.tagPage else null
        tagWork(u){before,guard->
            val page=before.owner.tagChoices(after,guard);guard()
            update(before.session,before.request){it.copy(tagPage=page,tagBusy=false,tagError=null)}
        }
    }
    fun selectTag(session:Long,choice:UnifiedTagChoice){
        val u=current().unifiedKeyword?:return;val page=u.tagPage?:return
        if(!owns(session)||!u.tagPicker||u.tagBusy||page.choices.none{it===choice})return
        tagWork(u){before,guard->
            val selection=before.owner.selectTag(page,choice,guard);guard()
            if(selection.id!=choice.id||selection.name!=choice.name)throw UnifiedKeywordConflict()
            update(before.session,before.request){it.copy(tag=selection,tagPicker=false,tagPage=null,tagBusy=false,tagError=null,snapshot=null,page=0,error=null)}
        }
    }
    private fun tagWork(before:UnifiedKeywordUi,action:suspend(UnifiedKeywordUi,()->Unit)->Unit){
        val epoch=synchronized(handoffLock){lifetime};val req=tokens.incrementAndGet()
        update(before.session){it.copy(request=req,snapshot=null,page=0,tagBusy=true,tagError=null,busy=false,error=null)}
        if(!owns(before.session,req))return
        synchronized(handoffLock){if(lifetime!=epoch)return;activeRequest=before.session to req;activeScope=before.scope;activeTags=true}
        fun failedTag()=update(before.session,req){it.copy(tagPage=null,tagBusy=false,tagError="人工标签尚未完整核对，请重试或取消；未显示部分标签。",
            error=if(!it.tagPicker)"所选人工标签需要重新核对。请重新选择或明确不限标签，未显示未过滤内容。"else null)}
        if(!enqueue{store->
            try{
                val guard={if(!requestCurrent(before.session,req,epoch,before.owner)||store.unifiedKeywords!==before.owner)throw Retired()}
                guard();action(before.copy(request=req),guard)
            }catch(_:Retired){}catch(c:CancellationException){throw c}catch(_:Exception){failedTag()}
        })failedTag()
    }
    fun openPeople(session:Long){
        val u=current().unifiedKeyword?:return
        if(!owns(session)||u.busy||u.tagBusy||u.personBusy)return
        update(session){it.copy(request=tokens.incrementAndGet(),snapshot=null,page=0,personPicker=true,personPage=null,personError=null,tagPicker=false,tagPage=null,tagBusy=false)}
        loadPeople(session)
    }
    fun closePeople(session:Long)=update(session){if(!it.personPicker)it else it.copy(request=tokens.incrementAndGet(),personPicker=false,personPage=null,personBusy=false,personError=null)}
    fun clearPerson(session:Long)=update(session){it.copy(request=tokens.incrementAndGet(),person=null,personPicker=false,personPage=null,personBusy=false,tagBusy=false,personError=null,snapshot=null,page=0,busy=false,error=null)}
    fun loadPeople(session:Long,next:Boolean=false){
        val u=current().unifiedKeyword?:return
        if(!owns(session)||!u.personPicker||u.personBusy||next&&u.personPage?.hasNext!=true)return
        val after=if(next)u.personPage else null
        personWork(u){before,guard->
            val page=before.owner.personChoices(after,guard);guard()
            update(before.session,before.request){it.copy(personPage=page,personBusy=false,personError=null)}
        }
    }
    fun selectPerson(session:Long,choice:UnifiedPersonChoice){
        val u=current().unifiedKeyword?:return;val page=u.personPage?:return
        if(!owns(session)||!u.personPicker||u.personBusy||page.choices.none{it===choice})return
        personWork(u){before,guard->
            val selection=before.owner.selectPerson(page,choice,guard);guard()
            if(selection.id!=choice.id||selection.name!=choice.name)throw UnifiedKeywordConflict()
            update(before.session,before.request){it.copy(person=selection,personPicker=false,personPage=null,personBusy=false,personError=null,snapshot=null,page=0,error=null)}
        }
    }
    private fun personWork(before:UnifiedKeywordUi,action:suspend(UnifiedKeywordUi,()->Unit)->Unit){
        val epoch=synchronized(handoffLock){lifetime};val req=tokens.incrementAndGet()
        update(before.session){it.copy(request=req,snapshot=null,page=0,personBusy=true,personError=null,busy=false,error=null)}
        if(!owns(before.session,req))return
        synchronized(handoffLock){if(lifetime!=epoch)return;activeRequest=before.session to req;activeScope=before.scope;activePeople=true}
        fun failedPerson()=update(before.session,req){it.copy(personPage=null,personBusy=false,personError="人工人物标记尚未完整核对，请重试或取消；未显示部分人物标记。",
            error=if(!it.personPicker)"所选人工人物标记需要重新核对。请重新选择或明确不限人物标记，未显示未过滤内容。"else null)}
        if(!enqueue{store->
            try{
                val guard={if(!requestCurrent(before.session,req,epoch,before.owner)||store.unifiedKeywords!==before.owner)throw Retired()}
                guard();action(before.copy(request=req),guard)
            }catch(_:Retired){}catch(c:CancellationException){throw c}catch(_:Exception){failedPerson()}
        })failedPerson()
    }
    fun search(session:Long){val u=current().unifiedKeyword?:return;if(!owns(session)||u.busy||u.tagBusy||u.tagPicker||u.personBusy||u.personPicker)return
        if(u.onlyFavorites&&!current().unifiedFavoritesReady()){
            update(session){it.copy(snapshot=null,page=0,busy=false,error="收藏状态尚未确认，请先重试读取或确认未结算的收藏操作。")};return
        }
        val range=try{UnifiedCaptureRange.parse(u.from,u.through)}catch(e:UnifiedCaptureRangeInvalid){
            update(session){it.copy(snapshot=null,page=0,busy=false,error=when(e.problem){
                UnifiedCaptureRangeProblem.FROM->"起始捕获日期无效，请按 YYYY-MM-DD 输入或留空。"
                UnifiedCaptureRangeProblem.THROUGH->"结束捕获日期无效，请按 YYYY-MM-DD 输入或留空。"
                UnifiedCaptureRangeProblem.ORDER->"起始捕获日期不得晚于结束日期。"})};return
        }
        try{PreferenceKeywords.validate(u.keyword)}catch(_:Exception){update(session){it.copy(snapshot=null,busy=false,error="关键词最多64字，不能含控制字符或不完整字符。")};return}
        if(u.keyword.isBlank()){update(session){it.copy(keyword="",snapshot=null,page=0,busy=false,error=null,tagBusy=false,personBusy=false)};return}
        execute(u,null,0,null,null,range)
    }
    fun next(session:Long,previous:Boolean=false){val u=current().unifiedKeyword?:return;val proof=u.snapshot?:return
        if(!owns(session)||u.busy||u.tagBusy||u.tagPicker||u.personBusy||u.personPicker)return
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
        synchronized(handoffLock){if(lifetime!=epoch)return;activeRequest=before.session to req;activeScope=before.scope;activeCaptureRange=range;activeTags=before.tag!=null||before.tagPicker||before.tagBusy;activePeople=before.person!=null||before.personPicker||before.personBusy;activeFavorites=before.onlyFavorites;activeImages=before.onlyReadyImages}
        if(!enqueue{store->
            if(!owns(before.session,req)||store.unifiedKeywords!==before.owner)return@enqueue
            try {
                val checkCurrent={if(!requestCurrent(before.session,req,epoch,before.owner)||store.unifiedKeywords!==before.owner||before.onlyFavorites&&!current().unifiedFavoritesReady())throw Retired()}
                val result=if(proof==null)when {
                    before.onlyReadyImages->before.owner.search(before.keyword,before.scope,range,before.tag,before.person,before.onlyFavorites,true,checkCurrent)
                    before.onlyFavorites->before.owner.search(before.keyword,before.scope,range,before.tag,before.person,true,checkCurrent)
                    before.person!=null->before.owner.search(before.keyword,before.scope,range,before.tag,before.person,checkCurrent)
                    before.tag!=null->before.owner.search(before.keyword,before.scope,range,before.tag,checkCurrent)
                    else->before.owner.search(before.keyword,before.scope,range,checkCurrent)
                }else {
                    var ticket=false
                    if(!before.owner.resolve(proof,item,checkCurrent){ticket=owns(before.session,req)}||!ticket)throw UnifiedKeywordConflict()
                    proof
                }
                if(result.keyword!=before.keyword||result.scope!=before.scope||result.captureRange!=range||result.tag!==before.tag||result.person!==before.person||result.onlyFavorites!=before.onlyFavorites||result.onlyReadyImages!=before.onlyReadyImages)throw UnifiedKeywordConflict()
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
                                used=true;returnSeed=ReturnSeed(before.keyword,before.scope,range,before.tag,before.person,before.onlyFavorites,before.onlyReadyImages)
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
            catch(_:UnifiedReadyImagesUnsupported){update(before.session,req){it.copy(snapshot=null,page=0,busy=false,
                error="当前查询端口不支持就绪图片筛选，未显示未过滤内容。")}}
            catch(_:UnifiedFavoriteUnsupported){update(before.session,req){it.copy(snapshot=null,page=0,busy=false,
                error="当前查询端口不支持原件收藏筛选，未显示未过滤内容。")}}
            catch(_:UnifiedPersonUnsupported){update(before.session,req){it.copy(snapshot=null,page=0,busy=false,
                error="当前查询端口不支持人工人物标记，未显示未过滤内容。")}}
            catch(_:UnifiedReadyImageLimit){update(before.session,req){it.copy(snapshot=null,page=0,busy=false,
                error="图片关联超出本机核对范围。可关闭就绪图片筛选后找回原件并检查附件；未显示部分结果，原件未改。")}}
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
