package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

sealed interface PreferenceIntent {
    class Create(val request:PreferenceCreate):PreferenceIntent { override fun toString()="PreferenceCreateIntent(redacted)" }
    class Mutate(val request:PreferenceMutation):PreferenceIntent { override fun toString()="PreferenceMutationIntent(redacted)" }
}
data class PreferenceConfirmation(val token:Long,val intent:PreferenceIntent) { override fun toString()="PreferenceConfirmation(redacted)" }
data class PreferenceUiState(val session:Long,val source:SourceVersion?,val sourceHash:String?,val request:Long=0,
    val sourcePage:PreferenceSourcePage?=null,val page:PreferencePage?=null,val items:List<SourcePreferenceSnapshot> = emptyList(),
    val section:PreferenceReview=PreferenceReview.PROPOSED,val cursor:PreferenceCursor?=null,val canPrevious:Boolean=false,
    val quote:String="",val topicId:String=UUID.randomUUID().toString(),val topicLabel:String="",val topicExpected:SourcePreferenceSnapshot?=null,
    val from:String="",val until:String="",val zone:String="",val affirmed:Boolean=false,val editing:SourcePreferenceSnapshot?=null,
    val busy:Boolean=false,val confirmation:PreferenceConfirmation?=null,val pending:PreferenceIntent?=null,
    val historyId:String?=null,val error:String?=null,val notice:String?=null) {
    override fun toString()="PreferenceUiState(redacted)"
}

/** Every retained private proof counts, including intents whose rows are outside the visible page. */
internal fun LocalRecordUiState.canRetainPreferenceSources(ui:PreferenceUiState):Boolean {
    if(!ready || opening || error!=null || !supportsSourcePreferences || supportsLocalProcessing && !localProcessingAvailable)return false
    fun source(version:SourceVersion,hash:String,generation:Long?=null,captured:Long?=null,zone:String?=null,policy:ProcessingPolicy?=null):Boolean =
        hasLocalProcessingProof(version) && entries.any { entry ->
            entry.id==version.sourceId && entry.currentRevision.toLong()==version.revision && entry.policy!=ProcessingPolicy.AI_NONE &&
                OriginalExports.hash(entry.originalText)==hash && (captured==null || entry.capturedAtMillis==captured) &&
                (zone==null || entry.captureTimeZone==zone) && (policy==null || entry.policy==policy) &&
                (generation==null || !supportsLocalProcessing || localProcessingVersions[version.sourceId]?.generation==generation)
        }
    fun preview(p:SourceMemoryPreview)=source(p.source,p.sourceHash,p.generation,p.capturedAtMillis,p.captureTimeZone,p.policy)
    fun snapshot(s:SourcePreferenceSnapshot)=s.availability==PreferenceAvailability.AVAILABLE && s.capturedAtMillis!=null &&
        s.captureTimeZone!=null && s.policy!=null && source(s.source,s.sourceHash,s.generation,s.capturedAtMillis,s.captureTimeZone,s.policy)
    fun intent(value:PreferenceIntent)=when(value) {
        is PreferenceIntent.Create -> preview(value.request.preview) && (value.request.topicExpected?.let(::snapshot) ?: true)
        is PreferenceIntent.Mutate -> snapshot(value.request.expected) && (value.request.replacing?.let(::snapshot) ?: true)
    }
    return (ui.source?.let { version -> ui.sourceHash?.let {source(version,it)} ?: false } ?: true) &&
        (ui.sourcePage?.let {preview(it.preview) && it.items.all(::snapshot) && it.topics.all(::snapshot)} ?: true) &&
        ui.items.all(::snapshot) && (ui.page?.items?.all(::snapshot) ?: true) &&
        (ui.topicExpected?.let(::snapshot) ?: true) && (ui.editing?.let(::snapshot) ?: true) &&
        (ui.confirmation?.intent?.let(::intent) ?: true) && (ui.pending?.let(::intent) ?: true)
}

/** No authority is saved; the same serial records actor rechecks each queued session before SQL. */
class SourcePreferenceController(private val current:()->LocalRecordUiState,
    private val publish:((LocalRecordUiState)->LocalRecordUiState)->Unit,
    private val enqueue:(suspend(EntryRepository)->Unit)->Boolean,private val clock:Clock) {
    private val tokens=AtomicLong();private val cursors=mutableListOf<PreferenceCursor?>()
    @Volatile private var disposed=false
    private fun available(local:LocalRecordUiState)=!disposed && local.ready && !local.opening && local.error==null &&
        local.supportsSourcePreferences && (!local.supportsLocalProcessing || local.localProcessingAvailable)
    private fun sourceCurrent(s:SourceVersion,hash:String,local:LocalRecordUiState)=local.hasLocalProcessingProof(s) &&
        local.entries.any { it.id==s.sourceId && it.currentRevision.toLong()==s.revision && it.policy!=ProcessingPolicy.AI_NONE && OriginalExports.hash(it.originalText)==hash }
    private fun owns(session:Long,request:Long?=null):Boolean {
        val local=current();val ui=local.sourcePreferences ?: return false
        return available(local) && ui.session==session && (request==null || request==ui.request) && local.canRetainPreferenceSources(ui)
    }
    private fun update(session:Long,change:(PreferenceUiState)->PreferenceUiState)=publish { local ->
        if(!owns(session))local else {
            val next=change(checkNotNull(local.sourcePreferences))
            // Validate the proposed state in the same publication/CAS as its private content.
            // A delayed comparison must authenticate both sources against the latest observed UI.
            local.copy(sourcePreferences=next.takeIf {local.canRetainPreferenceSources(it)})
        }
    }

    fun open(source:SourceVersion?=null,section:PreferenceReview=PreferenceReview.PROPOSED) {
        val local=current()
        if(!available(local) || local.saving || local.savePending || local.sourcePreferences!=null || local.sourceMemories!=null || local.memoryInbox!=null || local.localProcessing!=null || local.recordPackage!=null || local.recycleBin!=null || local.originalEdit!=null || local.originalHistory!=null || local.ai!=null || local.sourceTask!=null || local.sourceTags!=null || local.candidates!=null || local.factRecap!=null || local.originalExport!=null || local.manualTagEditor!=null || local.manualPeopleEditor!=null || local.eventDateEditor!=null)return
        val entry=source?.let { s -> local.entries.singleOrNull { it.id==s.sourceId && it.currentRevision.toLong()==s.revision && it.policy!=ProcessingPolicy.AI_NONE } ?: return }
        if(source!=null && !sourceCurrent(source,OriginalExports.hash(checkNotNull(entry).originalText),local))return
        val ui=PreferenceUiState(tokens.incrementAndGet(),source,entry?.let { OriginalExports.hash(it.originalText) },section=section)
        cursors.clear();cursors.add(null);publish { it.copy(sourcePreferences=ui) };execute(ui,null)
    }
    fun close(session:Long) { publish { if(it.sourcePreferences?.session==session)it.copy(sourcePreferences=null) else it };if(current().sourcePreferences==null)cursors.clear() }
    fun edit(session:Long,quote:String,topicLabel:String,from:String,until:String,zone:String,affirmed:Boolean) {
        val ui=current().sourcePreferences ?: return
        if(!owns(session) || ui.busy || ui.confirmation!=null || ui.pending!=null || quote.length>1280 || topicLabel.length>160 || from.length>16 || until.length>16 || zone.length>128)return
        update(session) { it.copy(quote=quote,topicLabel=if(it.topicExpected!=null || it.editing!=null)it.topicLabel else topicLabel,from=from,until=until,zone=zone,affirmed=affirmed,error=null,notice=null) }
    }
    fun topic(session:Long,item:SourcePreferenceSnapshot?) {
        val ui=current().sourcePreferences ?: return
        if(!owns(session) || ui.busy || ui.pending!=null || ui.confirmation!=null || ui.editing!=null)return
        if(item!=null && ui.sourcePage?.topics?.none { it===item }!=false)return
        update(session) { it.copy(topicId=item?.topicId ?: UUID.randomUUID().toString(),topicLabel=item?.topicLabel.orEmpty(),topicExpected=item,error=null) }
    }
    fun correct(session:Long,item:SourcePreferenceSnapshot) {
        val ui=current().sourcePreferences ?: return
        if(!owns(session) || ui.source==null || ui.busy || ui.confirmation!=null || ui.pending!=null || ui.items.none { it===item } || item.review?.review==PreferenceReview.SUPERSEDED || item.contents.size>=8)return
        val content=checkNotNull(item.current).content
        update(session) { it.copy(editing=item,quote=content.reference.quote,topicId=checkNotNull(item.topicId),topicLabel=checkNotNull(item.topicLabel),
            from=content.interval.fromDate.orEmpty(),until=content.interval.untilExclusive.orEmpty(),zone=content.interval.zone.orEmpty(),affirmed=false,error=null) }
    }
    fun cancelCorrection(session:Long) { val ui=current().sourcePreferences ?: return
        if(owns(session) && !ui.busy && ui.confirmation==null && ui.pending==null)update(session) { it.copy(editing=null,quote="",topicId=UUID.randomUUID().toString(),topicLabel="",topicExpected=null,from="",until="",zone="",affirmed=false) }
    }
    fun save(session:Long) {
        val ui=current().sourcePreferences ?: return;val preview=ui.sourcePage?.preview ?: return
        if(!owns(session) || ui.busy || ui.confirmation!=null || ui.pending!=null)return
        try {
            require(ui.affirmed)
            val content=PreferenceContent(SourceMemoryRules.reference(preview,ui.quote),PreferenceInterval(ui.from.ifEmpty { null },ui.until.ifEmpty { null },ui.zone.ifEmpty { null }))
            val intent=if(ui.editing==null)PreferenceIntent.Create(PreferenceCreate(UUID.randomUUID().toString(),preview,ui.topicId,ui.topicLabel,content,true,UUID.randomUUID().toString(),clock.millis(),ui.topicExpected))
                else { require(content!=ui.editing.current?.content);PreferenceIntent.Mutate(PreferenceMutation(ui.editing,PreferenceAction.CORRECT,content,null,UUID.randomUUID().toString(),clock.millis(),true)) }
            update(session) { it.copy(confirmation=PreferenceConfirmation(tokens.incrementAndGet(),intent),error=null,notice=null) }
        } catch(_:Exception) { update(session) { it.copy(error="请逐字选择当前原文唯一片段、确认普通本人自述及主题。区间未知请留空；已填日期须合法且明确时区。纠正必须有变化。") } }
    }
    fun action(session:Long,item:SourcePreferenceSnapshot,action:PreferenceAction) {
        val ui=current().sourcePreferences ?: return
        if(!owns(session) || ui.busy || ui.pending!=null || ui.confirmation!=null || ui.items.none { it===item } || action==PreferenceAction.CORRECT)return
        try { PreferenceRules.decision(checkNotNull(item.review).review,action) } catch(_:Exception) { return }
        val req=tokens.incrementAndGet();update(session) { it.copy(busy=true,request=req,error=null,notice=null) }
        if(!enqueue { store ->
            if(!owns(session,req))return@enqueue
            try {
                val other=checkNotNull(store.sourcePreferences).acceptedForTopic(item).takeIf { action==PreferenceAction.ACCEPT }
                if(!owns(session,req))return@enqueue
                val intent=PreferenceIntent.Mutate(PreferenceMutation(item,action,null,other,UUID.randomUUID().toString(),clock.millis()))
                update(session) { it.copy(busy=false,confirmation=PreferenceConfirmation(tokens.incrementAndGet(),intent)) }
            } catch(c:CancellationException) { throw c } catch(_:Exception) { failed(session,req,null) }
        })failed(session,req,null)
    }
    fun confirm(session:Long,confirmation:PreferenceConfirmation) {
        val ui=current().sourcePreferences ?: return
        if(!owns(session) || ui.busy || ui.pending!=null || ui.confirmation!==confirmation)return
        update(session) { it.copy(confirmation=null) }
        val next=current().sourcePreferences?.takeIf {it.session==session} ?: return
        execute(next,confirmation.intent)
    }
    fun cancel(session:Long,confirmation:PreferenceConfirmation) { if(owns(session))update(session) { if(it.confirmation!==confirmation)it else it.copy(confirmation=null) } }
    fun retry(session:Long) { val ui=current().sourcePreferences ?: return;if(owns(session) && !ui.busy)execute(ui,ui.pending) }
    fun refresh(session:Long) { val ui=current().sourcePreferences ?: return;if(owns(session) && !ui.busy)execute(ui,null) }
    fun section(session:Long,value:PreferenceReview) {
        val ui=current().sourcePreferences ?: return
        if(!owns(session) || ui.busy || ui.source!=null || ui.pending!=null || ui.confirmation!=null)return
        cursors.clear();cursors.add(null);val next=ui.copy(section=value,cursor=null,canPrevious=false);update(session) { next };execute(next,null)
    }
    fun next(session:Long,previous:Boolean=false) {
        val ui=current().sourcePreferences ?: return
        if(!owns(session) || ui.busy || ui.pending!=null || ui.confirmation!=null || ui.source!=null)return
        if(previous) { if(cursors.size<2)return;cursors.removeAt(cursors.lastIndex) }
        else cursors.add(ui.page?.next ?: return)
        val next=ui.copy(cursor=cursors.last(),canPrevious=cursors.size>1);update(session) { next };execute(next,null)
    }
    fun history(session:Long,id:String) { if(owns(session))update(session) { it.copy(historyId=if(it.historyId==id)null else id) } }
    fun endpoint(session:Long,item:SourcePreferenceSnapshot,edge:PreferenceEndpoint,onLocate:(OriginalReadingLocation)->Unit) {
        val ui=current().sourcePreferences ?: return
        if(!owns(session) || ui.busy || ui.pending!=null || ui.confirmation!=null || ui.items.none { it===item })return
        val req=tokens.incrementAndGet();update(session) { it.copy(busy=true,request=req) }
        if(!enqueue { store ->
            if(!owns(session,req))return@enqueue
            try { checkNotNull(store.sourcePreferences).resolveEndpoint(item,edge) { target ->
                if(owns(session,req)) {
                    if(target==null)update(session) { it.copy(busy=false,notice="历史取代关系的原项已不可用；没有恢复旧内容或旧采纳。") }
                    else {
                        val ref=target.contents.single { it.revision==edge.contentRevision }.content.reference
                        close(session);onLocate(OriginalReadingLocation(target.source.sourceId,target.source.revision.toInt(),target.sourceHash,ref.start,ref.end))
                    }
                }
            } } catch(c:CancellationException) { throw c } catch(_:Exception) { failed(session,req,null) }
        })failed(session,req,null)
    }
    fun locate(session:Long,item:SourcePreferenceSnapshot,onLocate:(OriginalReadingLocation)->Unit) {
        val ui=current().sourcePreferences ?: return
        if(!owns(session) || ui.busy || ui.pending!=null || ui.confirmation!=null || ui.items.none { it===item })return
        val req=tokens.incrementAndGet();update(session) { it.copy(busy=true,request=req) }
        if(!enqueue { store ->
            if(!owns(session,req))return@enqueue
            try {
                val repo=checkNotNull(store.sourcePreferences)
                val publish={ if(owns(session,req)) {
                    val ref=checkNotNull(item.current).content.reference
                    close(session);onLocate(OriginalReadingLocation(item.source.sourceId,item.source.revision.toInt(),item.sourceHash,ref.start,ref.end))
                } }
                val accepted=ui.sourcePage?.let { repo.resolveSource(it,publish) } ?: ui.page?.let { repo.resolve(it,item,publish) } ?: false
                if(!accepted)failed(session,req,null)
            } catch(c:CancellationException) { throw c } catch(_:Exception) { failed(session,req,null) }
        })failed(session,req,null)
    }
    private fun execute(before:PreferenceUiState,intent:PreferenceIntent?) {
        val req=tokens.incrementAndGet();update(before.session) { it.copy(request=req,busy=true,items=emptyList(),sourcePage=null,page=null,pending=intent,confirmation=null,error=null,notice=null) }
        if(!owns(before.session,req))return
        if(!enqueue { store ->
            if(!owns(before.session,req))return@enqueue
            try {
                val repo=checkNotNull(store.sourcePreferences)
                val saved=when(intent) { is PreferenceIntent.Create -> repo.create(intent.request);is PreferenceIntent.Mutate -> repo.mutate(intent.request);null -> null }
                fun result(page:PreferenceSourcePage?,browse:PreferencePage?) {
                    if(!owns(before.session,req))return
                    val form=intent is PreferenceIntent.Create || intent is PreferenceIntent.Mutate && intent.request.action==PreferenceAction.CORRECT
                    update(before.session) { it.copy(sourcePage=page,page=browse,items=page?.items ?: checkNotNull(browse).items,busy=false,pending=null,confirmation=null,
                        editing=if(form)null else it.editing,quote=if(form)"" else it.quote,affirmed=if(form)false else it.affirmed,
                        topicId=if(form)UUID.randomUUID().toString() else it.topicId,topicLabel=if(form)"" else it.topicLabel,topicExpected=if(form)null else it.topicExpected,
                        from=if(form)"" else it.from,until=if(form)"" else it.until,zone=if(form)"" else it.zone,
                        notice=if(saved==null)null else "人工候选或审阅已在本机核对；不是事实核验，也没有联网。",error=null) }
                }
                if(before.source!=null) {
                    val page=repo.captureSource(before.source);require(page.preview.sourceHash==before.sourceHash)
                    if(saved!=null)require(page.items.singleOrNull { it.id==saved.id }?.same(saved)==true)
                    if(!repo.resolveSource(page) { result(page,null) })throw PreferenceConflict()
                } else {
                    val page=repo.capture(before.section,before.cursor)
                    if(!repo.resolve(page) { result(null,page) })throw PreferenceConflict()
                }
            } catch(c:CancellationException) { throw c } catch(_:Exception) { failed(before.session,req,intent) }
        })failed(before.session,req,intent)
    }
    private fun failed(session:Long,request:Long,intent:PreferenceIntent?) {
        if(owns(session,request))update(session) { it.copy(busy=false,items=emptyList(),sourcePage=null,page=null,confirmation=null,pending=intent,
            notice=null,error="读取或写入结果尚未核对，未显示成功或空列表。可核对同次请求，或仅刷新实际状态；来源变化时旧请求会被拒绝。") }
    }
    fun dispose() { disposed=true;publish { it.copy(sourcePreferences=null) };cursors.clear() }
}
