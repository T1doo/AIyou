package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

sealed interface FactIntent {
    class Create(val request:FactCreate):FactIntent { override fun toString()="FactCreateIntent(redacted)" }
    class Mutate(val request:FactMutation):FactIntent { override fun toString()="FactMutationIntent(redacted)" }
}
data class FactConfirmation(val token:Long,val intent:FactIntent) { override fun toString()="FactConfirmation(redacted)" }
enum class FactInput { QUOTE, TOPIC, FROM, UNTIL, ZONE }
data class FactUiState(val session:Long,val owner:SourceFactRepository,val source:SourceVersion?,val sourceHash:String?,val request:Long=0,
    val sourcePage:FactSourcePage?=null,val page:FactPage?=null,val items:List<SourceFactSnapshot> = emptyList(),
    val section:FactReview=FactReview.PROPOSED,val cursor:FactCursor?=null,val canPrevious:Boolean=false,val keyword:String="",
    val quote:String="",val topicId:String=UUID.randomUUID().toString(),val topicLabel:String="",val topicExpected:SourceFactSnapshot?=null,
    val from:String="",val until:String="",val zone:String="",val affirmed:Boolean=false,val editing:SourceFactSnapshot?=null,
    val rejectedInput:Set<FactInput> = emptySet(),
    val busy:Boolean=false,val confirmation:FactConfirmation?=null,val pending:FactIntent?=null,
    val historyId:String?=null,val error:String?=null,val notice:String?=null) {
    override fun toString()="FactUiState(redacted)"
}

/** Only this file can mint a target-bound navigation proof after real SQL acknowledgement. */
private interface FactNavigationProof:UnifiedKeywordHandoff { fun current(local:LocalRecordUiState,location:OriginalReadingLocation):Boolean }
internal fun LocalRecordUiState.canUseFactNavigation(handoff:UnifiedKeywordHandoff,location:OriginalReadingLocation):Boolean =
    (handoff as? FactNavigationProof)?.current(this,location)==true

/** Every retained private proof counts, including intents whose rows are outside the visible page. */
internal fun LocalRecordUiState.canRetainFactSources(ui:FactUiState):Boolean {
    if(!ready || opening || error!=null || !supportsSourceFacts || sourceFactOwner!==ui.owner || supportsLocalProcessing && !localProcessingAvailable)return false
    val byId=entries.associateBy{it.id};if(byId.size!=entries.size)return false
    val hashes=HashMap<SourceVersion,String>()
    fun source(version:SourceVersion,hash:String,generation:Long?=null,captured:Long?=null,zone:String?=null,policy:ProcessingPolicy?=null):Boolean {
        val e=byId[version.sourceId]?:return false
        return hasLocalProcessingProof(version) && e.currentRevision.toLong()==version.revision && e.policy!=ProcessingPolicy.AI_NONE &&
            hashes.getOrPut(version){OriginalExports.hash(e.originalText)}==hash && (captured==null || e.capturedAtMillis==captured) &&
            (zone==null || e.captureTimeZone==zone) && (policy==null || e.policy==policy) &&
            (generation==null || !supportsLocalProcessing || localProcessingVersions[version.sourceId]?.generation==generation)
    }
    fun preview(p:SourceMemoryPreview)=source(p.source,p.sourceHash,p.generation,p.capturedAtMillis,p.captureTimeZone,p.policy)
    fun snapshot(s:SourceFactSnapshot)=s.availability==FactAvailability.AVAILABLE && s.capturedAtMillis!=null &&
        s.captureTimeZone!=null && s.policy!=null && source(s.source,s.sourceHash,s.generation,s.capturedAtMillis,s.captureTimeZone,s.policy)
    fun intent(value:FactIntent)=when(value) {
        is FactIntent.Create -> preview(value.request.preview) && (value.request.topicExpected?.let(::snapshot) ?: true)
        is FactIntent.Mutate -> snapshot(value.request.expected) && (value.request.replacing?.let(::snapshot) ?: true)
    }
    return (ui.source?.let { version -> ui.sourceHash?.let {source(version,it)} ?: false } ?: true) &&
        (ui.sourcePage?.let {preview(it.preview) && it.items.all(::snapshot) && it.topics.all(::snapshot)} ?: true) &&
        ui.items.all(::snapshot) && (ui.page?.items?.all(::snapshot) ?: true) &&
        (ui.topicExpected?.let(::snapshot) ?: true) && (ui.editing?.let(::snapshot) ?: true) &&
        (ui.confirmation?.intent?.let(::intent) ?: true) && (ui.pending?.let(::intent) ?: true)
}

/** No authority is saved; the same serial records actor rechecks each queued session before SQL. */
class SourceFactController(private val current:()->LocalRecordUiState,
    private val publish:((LocalRecordUiState)->LocalRecordUiState)->Unit,
    private val enqueue:(suspend(EntryRepository)->Unit)->Boolean,private val clock:Clock,
    private val beforeOpen:()->Unit={},private val beforeWrite:()->Unit={}) {
    private val tokens=AtomicLong();private val cursors=mutableListOf<FactCursor?>()
    private val lifecycle=Any()
    private var lifetime=0L
    private var activeSession=0L
    private var activeRequest=0L
    private data class Run(val session:Long,val request:Long,val lifetime:Long,val owner:SourceFactRepository)
    private class Retired:java.util.concurrent.CancellationException("Fact session retired")
    @Volatile private var disposed=false
    private fun available(local:LocalRecordUiState)=!disposed && local.ready && !local.opening && local.error==null &&
        local.supportsSourceFacts && local.sourceFactOwner!=null && (!local.supportsLocalProcessing || local.localProcessingAvailable)
    private fun sourceCurrent(s:SourceVersion,hash:String,local:LocalRecordUiState)=local.hasLocalProcessingProof(s) &&
        local.entries.any { it.id==s.sourceId && it.currentRevision.toLong()==s.revision && it.policy!=ProcessingPolicy.AI_NONE && OriginalExports.hash(it.originalText)==hash }
    private fun owns(session:Long,request:Long?=null):Boolean {
        val local=current();val ui=local.sourceFacts ?: return false
        return available(local) && local.sourceFactOwner===ui.owner && ui.session==session && (request==null || request==ui.request) && local.canRetainFactSources(ui)
    }
    private fun update(session:Long,request:Long?=null,change:(FactUiState)->FactUiState)=publish { local ->
        if(!available(local) || local.sourceFacts?.session!=session || (request!=null && local.sourceFacts.request!=request)  )local else if(!local.canRetainFactSources(local.sourceFacts))local.copy(sourceFacts=null) else {
            val next=change(checkNotNull(local.sourceFacts))
            // Validate the proposed state in the same publication/CAS as its private content.
            // A delayed comparison must authenticate both sources against the latest observed UI.
            local.copy(sourceFacts=next.takeIf {local.canRetainFactSources(it)})
        }
    }

    private fun begin(before:FactUiState,intent:FactIntent?=before.pending,clear:Boolean=false):Run? {
        val epoch=synchronized(lifecycle){if(disposed)return null;lifetime}
        val req=tokens.incrementAndGet()
        update(before.session){it.copy(request=req,busy=true,pending=intent,confirmation=null,error=null,notice=null,
            items=if(clear)emptyList()else it.items,sourcePage=if(clear)null else it.sourcePage,page=if(clear)null else it.page)}
        if(!owns(before.session,req))return null
        return synchronized(lifecycle){
            if(disposed||lifetime!=epoch||current().sourceFacts?.request!=req)null
            else {activeSession=before.session;activeRequest=req;Run(before.session,req,epoch,before.owner)}
        }
    }
    /** Used for every bounded SQL row: no entries, hashes, bindings or retained histories. */
    private fun guard(run:Run,store:EntryRepository)=synchronized(lifecycle){
        val local=current();val ui=local.sourceFacts
        if(disposed||lifetime!=run.lifetime||activeSession!=run.session||activeRequest!=run.request||
            ui==null||ui.session!=run.session||ui.request!=run.request||ui.owner!==run.owner||
            !available(local)||local.sourceFactOwner!==run.owner||store.sourceFacts!==run.owner)throw Retired()
    }
    fun open(source:SourceVersion?=null,section:FactReview=FactReview.PROPOSED) {
        val local=current();val owner=local.sourceFactOwner?:return
        if(!available(local) || local.saving || local.savePending || local.sourceFacts!=null || local.sourceMemories!=null || local.memoryInbox!=null || local.sourcePreferences!=null || local.audioTranscripts!=null || local.imageTexts!=null || local.localProcessing!=null || local.recordPackage!=null || local.recycleBin!=null || local.originalEdit!=null || local.originalHistory!=null || local.ai!=null || local.sourceTask!=null || local.sourceTags!=null || local.candidates!=null || local.factRecap!=null || local.originalExport!=null || local.manualTagEditor!=null || local.manualPeopleEditor!=null || local.eventDateEditor!=null)return
        val entry=source?.let{s->local.entries.singleOrNull{it.id==s.sourceId&&it.currentRevision.toLong()==s.revision&&it.policy!=ProcessingPolicy.AI_NONE}?:return}
        if(source!=null&&!sourceCurrent(source,OriginalExports.hash(checkNotNull(entry).originalText),local))return
        val ui=FactUiState(tokens.incrementAndGet(),owner,source,entry?.let{OriginalExports.hash(it.originalText)},section=section)
        beforeOpen()
        synchronized(lifecycle){
            if(disposed)return
            lifetime=Math.addExact(lifetime,1);cursors.clear();cursors.add(null)
            publish{if(it.sourceFacts==null&&it.canRetainFactSources(ui))it.copy(sourceFacts=ui)else it}
        }
        if(current().sourceFacts?.session==ui.session)execute(ui,null)
    }
    fun close(session:Long)=synchronized(lifecycle){
        if(current().sourceFacts?.session==session||activeSession==session){
            lifetime=Math.addExact(lifetime,1);activeSession=0;activeRequest=0
            publish{if(it.sourceFacts?.session==session)it.copy(sourceFacts=null)else it};cursors.clear()
        }
    }
    fun retire()=synchronized(lifecycle){
        lifetime=Math.addExact(lifetime,1);activeSession=0;activeRequest=0;cursors.clear();publish{it.copy(sourceFacts=null)}
    }
    fun edit(session:Long,field:FactInput,value:String) {
        val ui=current().sourceFacts?:return
        if(!owns(session)||ui.busy||ui.confirmation!=null||ui.pending!=null)return
        if(field==FactInput.TOPIC&&(ui.topicExpected!=null||ui.editing!=null))return
        val bound=when(field){FactInput.QUOTE->640;FactInput.TOPIC->80;FactInput.FROM,FactInput.UNTIL->10;FactInput.ZONE->128}
        val displayed=when(field){FactInput.QUOTE->ui.quote;FactInput.TOPIC->ui.topicLabel;FactInput.FROM->ui.from;FactInput.UNTIL->ui.until;FactInput.ZONE->ui.zone}
        // String TextField may echo its displayed value when focus/composition changes.
        // Such feedback is not explicit correction of a previously rejected attempt.
        if(field in ui.rejectedInput && value==displayed)return
        val rejected=value.length>bound
        val shown=if(rejected){
            var end=bound+1
            if(Character.isHighSurrogate(value[end-1]))end--
            value.substring(0,end)
        }else value
        update(session){old->
            // Retain only a bounded preview, not the old accepted text. In particular the
            // field must not rebound to a valid old date and acknowledge it on blur.
            val next=when(field){FactInput.QUOTE->old.copy(quote=shown);FactInput.TOPIC->old.copy(topicLabel=shown)
                FactInput.FROM->old.copy(from=shown);FactInput.UNTIL->old.copy(until=shown);FactInput.ZONE->old.copy(zone=shown)}
            next.copy(rejectedInput=if(rejected)next.rejectedInput+field else next.rejectedInput-field,error=null,notice=null)
        }
    }
    fun affirm(session:Long,value:Boolean){val ui=current().sourceFacts?:return
        if(owns(session)&&!ui.busy&&ui.pending==null&&ui.confirmation==null)update(session){it.copy(affirmed=value,error=null)}
    }
    fun topic(session:Long,item:SourceFactSnapshot?) {
        val ui=current().sourceFacts ?: return
        if(!owns(session) || ui.busy || ui.pending!=null || ui.confirmation!=null || ui.editing!=null)return
        if(item!=null && ui.sourcePage?.topics?.none { it===item }!=false)return
        update(session) { it.copy(topicId=item?.topicId ?: UUID.randomUUID().toString(),topicLabel=item?.topicLabel.orEmpty(),topicExpected=item,rejectedInput=it.rejectedInput-FactInput.TOPIC,error=null) }
    }
    fun correct(session:Long,item:SourceFactSnapshot) {
        val ui=current().sourceFacts ?: return
        if(!owns(session) || ui.source==null || ui.busy || ui.confirmation!=null || ui.pending!=null || ui.items.none { it===item } || item.review?.review==FactReview.SUPERSEDED || item.contents.size>=8)return
        val content=checkNotNull(item.current).content
        update(session) { it.copy(editing=item,quote=content.reference.quote,topicId=checkNotNull(item.topicId),topicLabel=checkNotNull(item.topicLabel),
            from=content.interval.fromDate.orEmpty(),until=content.interval.untilExclusive.orEmpty(),zone=content.interval.zone.orEmpty(),affirmed=false,rejectedInput=emptySet(),error=null) }
    }
    fun cancelCorrection(session:Long) { val ui=current().sourceFacts ?: return
        if(owns(session) && !ui.busy && ui.confirmation==null && ui.pending==null)update(session) { it.copy(editing=null,quote="",topicId=UUID.randomUUID().toString(),topicLabel="",topicExpected=null,from="",until="",zone="",affirmed=false,rejectedInput=emptySet()) }
    }
    fun save(session:Long) {
        val ui=current().sourceFacts ?: return;val preview=ui.sourcePage?.preview ?: return
        if(!owns(session) || ui.busy || ui.confirmation!=null || ui.pending!=null)return
        try {
            require(ui.affirmed && ui.rejectedInput.isEmpty())
            val content=FactContent(SourceMemoryRules.reference(preview,ui.quote),FactInterval(ui.from.ifEmpty { null },ui.until.ifEmpty { null },ui.zone.ifEmpty { null }))
            val intent=if(ui.editing==null)FactIntent.Create(FactCreate(UUID.randomUUID().toString(),preview,ui.topicId,ui.topicLabel,content,true,UUID.randomUUID().toString(),clock.millis(),ui.topicExpected))
                else { require(content!=ui.editing.current?.content);FactIntent.Mutate(FactMutation(ui.editing,FactAction.CORRECT,content,null,UUID.randomUUID().toString(),clock.millis(),true)) }
            update(session) { it.copy(confirmation=FactConfirmation(tokens.incrementAndGet(),intent),error=null,notice=null) }
        } catch(_:Exception) { update(session) { it.copy(error="请逐字选择当前原文唯一片段、确认普通本人自述及主题。区间未知请留空；已填日期须合法且明确时区。纠正必须有变化。") } }
    }
    fun action(session:Long,item:SourceFactSnapshot,action:FactAction) {
        val ui=current().sourceFacts?:return
        if(!owns(session)||ui.busy||ui.pending!=null||ui.confirmation!=null||ui.items.none{it===item}||action==FactAction.CORRECT)return
        try{FactRules.decision(checkNotNull(item.review).review,action)}catch(_:Exception){return}
        val run=begin(ui)?:return
        if(!enqueue{store->
            try{
                val check={guard(run,store)};check()
                val other=run.owner.acceptedForTopic(item,check).takeIf{action==FactAction.ACCEPT}
                check()
                val intent=FactIntent.Mutate(FactMutation(item,action,null,other,UUID.randomUUID().toString(),clock.millis()))
                update(session,run.request){it.copy(busy=false,confirmation=FactConfirmation(tokens.incrementAndGet(),intent))}
            }catch(_:Retired){}catch(c:CancellationException){throw c}catch(_:Exception){failed(session,run.request,null)}
        })failed(session,run.request,null)
    }
    fun confirm(session:Long,confirmation:FactConfirmation) {
        val ui=current().sourceFacts ?: return
        if(!owns(session) || ui.busy || ui.pending!=null || ui.confirmation!==confirmation)return
        update(session) { it.copy(confirmation=null) }
        val next=current().sourceFacts?.takeIf {it.session==session} ?: return
        execute(next,confirmation.intent)
    }
    fun cancel(session:Long,confirmation:FactConfirmation) { if(owns(session))update(session) { if(it.confirmation!==confirmation)it else it.copy(confirmation=null) } }
    fun retry(session:Long) { val ui=current().sourceFacts ?: return;if(owns(session) && !ui.busy && ui.confirmation==null)execute(ui,ui.pending) }
    fun refresh(session:Long) { val ui=current().sourceFacts ?: return;if(owns(session) && !ui.busy && ui.pending==null && ui.confirmation==null)execute(ui,null) }
    fun keyword(session:Long,value:String) {
        val ui=current().sourceFacts ?: return
        if(!owns(session) || ui.source!=null || ui.section!=FactReview.ACCEPTED || ui.pending!=null || ui.confirmation!=null || value.length>256 || value==ui.keyword)return
        // Retire the old query immediately. Queued/in-flight reads still release their real
        // lease, but their request generation can no longer publish or open an old source.
        cursors.clear();cursors.add(null)
        update(session) {it.copy(keyword=value,request=tokens.incrementAndGet(),busy=false,page=null,items=emptyList(),cursor=null,canPrevious=false,historyId=null,error=null,notice=null)}
    }
    fun search(session:Long) {
        val ui=current().sourceFacts ?: return
        if(!owns(session) || ui.source!=null || ui.section!=FactReview.ACCEPTED || ui.busy || ui.pending!=null || ui.confirmation!=null)return
        try {FactKeywords.validate(ui.keyword)} catch(_:IllegalArgumentException) {update(session) {it.copy(error="关键词最多64字，不能含控制字符或不完整字符。")};return}
        cursors.clear();cursors.add(null)
        val next=ui.copy(cursor=null,canPrevious=false);update(session) {next};execute(next,null)
    }
    fun section(session:Long,value:FactReview) {
        val ui=current().sourceFacts ?: return
        if(!owns(session) || ui.busy || ui.source!=null || ui.pending!=null || ui.confirmation!=null || value !in setOf(FactReview.PROPOSED,FactReview.ACCEPTED))return
        cursors.clear();cursors.add(null);val next=ui.copy(section=value,cursor=null,canPrevious=false);update(session) { next };execute(next,null)
    }
    fun next(session:Long,previous:Boolean=false) {
        val ui=current().sourceFacts ?: return
        if(!owns(session) || ui.busy || ui.pending!=null || ui.confirmation!=null || ui.source!=null)return
        if(previous) { if(cursors.size<2)return;cursors.removeAt(cursors.lastIndex) }
        else cursors.add(ui.page?.next ?: return)
        val next=ui.copy(cursor=cursors.last(),canPrevious=cursors.size>1);update(session) { next };execute(next,null)
    }
    fun history(session:Long,id:String) { if(owns(session))update(session) { it.copy(historyId=if(it.historyId==id)null else id) } }
    fun endpoint(session:Long,item:SourceFactSnapshot,edge:FactEndpoint,onLocate:(OriginalReadingLocation,UnifiedKeywordHandoff)->Unit) {
        val ui=current().sourceFacts?:return
        if(!owns(session)||ui.busy||ui.pending!=null||ui.confirmation!=null||ui.items.none{it===item})return
        val run=begin(ui)?:return
        if(!enqueue{store->
            try{
                val check={guard(run,store)};check();var target:SourceFactSnapshot?=null
                var admitted=false
                val resolved=run.owner.resolveEndpoint(item,edge,check){target=it;admitted=true}
                check()
                if(!resolved||!admitted)throw FactConflict()
                val result=target
                if(result==null)update(session,run.request){it.copy(busy=false,notice="历史取代原项已不可用，没有恢复旧内容或采纳。")}
                else {
                    val ref=result.contents.single{it.revision==edge.contentRevision}.content.reference
                    // Recheck the off-screen target against the latest observed source too.
                    if(!current().canRetainFactSources(ui.copy(items=listOf(result))))throw FactConflict()
                    publishLocation(run,result,OriginalReadingLocation(result.source.sourceId,result.source.revision.toInt(),result.sourceHash,ref.start,ref.end),onLocate)
                }
            }catch(_:Retired){}catch(c:CancellationException){throw c}catch(_:Exception){failed(session,run.request,null)}
        })failed(session,run.request,null)
    }
    private fun publishLocation(run:Run,target:SourceFactSnapshot,location:OriginalReadingLocation,onLocate:(OriginalReadingLocation,UnifiedKeywordHandoff)->Unit) {
        if(!owns(run.session,run.request))return
        // The resolved historical target may not be present in the current page. Keep the
        // exact SQL-authenticated snapshot private through navigation and the final CAS.
        fun fresh(local:LocalRecordUiState):Boolean {
            val ui=local.sourceFacts?:return false
            if(!local.supportsLocalProcessing || !local.localProcessingAvailable ||
                local.localProcessingVersions[target.source.sourceId]!=LocalProcessingVersion(target.source,target.generation))return false
            return ui.session==run.session && ui.request==run.request && ui.owner===run.owner &&
                local.canRetainFactSources(ui.copy(items=ui.items+target))
        }
        if(!fresh(current())){failed(run.session,run.request,null);return}
        var used=false
        val handoff=object:FactNavigationProof {
            override fun current(local:LocalRecordUiState,requested:OriginalReadingLocation):Boolean=synchronized(lifecycle) {
                if(requested!==location)return@synchronized false
                if(used||disposed||lifetime!=run.lifetime||activeSession!=run.session||activeRequest!=run.request)false
                else if(!fresh(local)||!fresh(this@SourceFactController.current())) {
                    used=true;failed(run.session,run.request,null);false
                }else true
            }
            override fun invoke(action:()->Unit):Boolean=synchronized(lifecycle){
                if(!current(this@SourceFactController.current(),location))false
                else {
                    var accepted=false
                    publish{local->accepted=fresh(local)
                        if(accepted)local.copy(sourceFacts=null)else local}
                    used=true
                    if(!accepted){failed(run.session,run.request,null);false}
                    else {activeSession=0;activeRequest=0;cursors.clear();action();true}
                }
            }
        }
        // No lifecycle lock is held while the caller obtains navigation. Navigation -> lifecycle.
        onLocate(location,handoff)
    }
    fun locate(session:Long,item:SourceFactSnapshot,onLocate:(OriginalReadingLocation,UnifiedKeywordHandoff)->Unit) {
        val ui=current().sourceFacts?:return
        if(!owns(session)||ui.busy||ui.pending!=null||ui.confirmation!=null||ui.items.none{it===item})return
        val run=begin(ui)?:return
        if(!enqueue{store->
            try{
                val check={guard(run,store)};check();var admitted=false
                val publish={check();admitted=true}
                val resolved=ui.sourcePage?.let{run.owner.resolveSource(it,check,publish)}?:ui.page?.let{run.owner.resolve(it,item,check,publish)}?:false
                check();if(!resolved||!admitted)throw FactConflict()
                val ref=checkNotNull(item.current).content.reference
                publishLocation(run,item,OriginalReadingLocation(item.source.sourceId,item.source.revision.toInt(),item.sourceHash,ref.start,ref.end),onLocate)
            }catch(_:Retired){}catch(c:CancellationException){throw c}catch(_:Exception){failed(session,run.request,null)}
        })failed(session,run.request,null)
    }
    private fun execute(before:FactUiState,intent:FactIntent?) {
        val run=begin(before,intent,true)?:return
        // The pending intent is already accepted in UI state. Retire query/handoff proofs only;
        // this hook must not close the editor or discard its same-UUID UNKNOWN retry.
        if(intent!=null)beforeWrite()
        if(!enqueue{store->
            try{
                val check={guard(run,store)};check()
                val saved=when(intent){is FactIntent.Create->run.owner.create(intent.request,check);is FactIntent.Mutate->run.owner.mutate(intent.request,check);null->null}
                var sourcePage:FactSourcePage?=null;var page:FactPage?=null;var admitted=false
                val accept={check();admitted=true}
                val resolved=if(before.source!=null){
                    val p=run.owner.captureSource(before.source,check);require(p.preview.sourceHash==before.sourceHash)
                    if(saved!=null)require(p.items.singleOrNull{it.id==saved.id}?.same(saved)==true)
                    sourcePage=p;run.owner.resolveSource(p,check,accept)
                }else{
                    val p=if(before.section==FactReview.ACCEPTED)run.owner.searchAccepted(before.keyword,before.cursor,check)else run.owner.capture(before.section,before.cursor,check)
                    page=p;run.owner.resolve(p,null,check,accept)
                }
                // Private SQL admission is not publication: wait for the real transaction ACK.
                check();if(!resolved||!admitted)throw FactConflict()
                val form=intent is FactIntent.Create||intent is FactIntent.Mutate&&intent.request.action==FactAction.CORRECT
                update(before.session,run.request){it.copy(sourcePage=sourcePage,page=page,items=sourcePage?.items?:checkNotNull(page).items,busy=false,pending=null,confirmation=null,
                    editing=if(form)null else it.editing,quote=if(form)""else it.quote,affirmed=if(form)false else it.affirmed,
                    topicId=if(form)UUID.randomUUID().toString()else it.topicId,topicLabel=if(form)""else it.topicLabel,topicExpected=if(form)null else it.topicExpected,
                    from=if(form)""else it.from,until=if(form)""else it.until,zone=if(form)""else it.zone,rejectedInput=if(form)emptySet()else it.rejectedInput,
                    notice=if(saved==null)null else "人工自述或审阅已在本机核对；不是事实核验，也没有联网。",error=null)}
            }catch(_:Retired){}catch(c:CancellationException){throw c}catch(_:Exception){failed(before.session,run.request,intent)}
        })failed(before.session,run.request,intent)
    }
    private fun failed(session:Long,request:Long,intent:FactIntent?) {
        if(owns(session,request))update(session,request) { it.copy(busy=false,items=emptyList(),sourcePage=null,page=null,confirmation=null,pending=intent,
            notice=null,error="读取或写入结果尚未核对，未显示成功或空列表。可核对同次请求；写入未结算时保持冻结，不能以刷新替换该意图。来源变化会拒绝旧请求。") }
    }
    fun dispose() { disposed=true;retire() }
}
