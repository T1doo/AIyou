package com.t1doo.aiyou.records
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

data class AudioTranscriptUi(val session:Long,val selected:AttachmentIntent?,val initialHash:String?,val initialGeneration:Long?,val request:Long=0,
    val anchor:AudioTranscriptSource?=null,val snapshot:AudioTranscriptSnapshot?=null,val text:String="",val keyword:String="",
    val items:List<AudioTranscriptSnapshot> = emptyList(),val page:AudioTranscriptPage?=null,val cursor:PreferenceCursor?=null,val canPrevious:Boolean=false,
    val busy:Boolean=false,val pending:AudioTranscriptWrite?=null,val withdrawal:AudioTranscriptWrite?=null,val error:String?=null,val notice:String?=null) {override fun toString()="AudioTranscriptUi(redacted)"}
internal fun LocalRecordUiState.currentTranscriptSource(a:AudioTranscriptSource):Boolean = ready&&!opening&&error==null&&
    entries.any{it.id==a.intent.entryId&&it.currentRevision==a.intent.revision&&OriginalExports.hash(it.originalText)==a.sourceHash&&it.policy==a.policy&&it.capturedAtMillis==a.capturedAtMillis&&it.captureTimeZone==a.zone}&&
    (!supportsLocalProcessing||localProcessingAvailable&&localProcessingVersions[a.intent.entryId]?.generation==a.generation)
/** A recap may edit human text only from its still-live, exact original-audio reader. */
internal fun LocalRecordUiState.canUseRecapTranscript(selected:AttachmentIntent?,panel:AttachmentUiState?,anchor:AudioTranscriptSource?):Boolean =
    selected!=null && anchor!=null && anchor.intent==selected && currentTranscriptSource(anchor) &&
        panel?.let { it.focusAudio==selected && it.entryId==selected.entryId && it.revision==selected.revision &&
            !it.busy && !it.readOnly && !it.unavailable && it.error==null && it.hasRecordAccess(this) &&
            it.items.any { row->row.intent==selected && row.state==AttachmentState.READY } }==true
internal fun LocalRecordUiState.canRetainTranscripts(u:AudioTranscriptUi):Boolean {
    if(!ready||opening||error!=null||!supportsAudioTranscripts)return false
    fun source(i:AttachmentIntent,hash:String,generation:Long?=null,policy:ProcessingPolicy?=null,captured:Long?=null,zone:String?=null)=entries.any{it.id==i.entryId&&it.currentRevision==i.revision&&OriginalExports.hash(it.originalText)==hash&&(policy==null||it.policy==policy)&&(captured==null||it.capturedAtMillis==captured)&&(zone==null||it.captureTimeZone==zone)} && (generation==null||!supportsLocalProcessing||localProcessingAvailable&&localProcessingVersions[i.entryId]?.generation==generation)
    fun anchor(a:AudioTranscriptSource)=currentTranscriptSource(a)
    return (u.selected?.let{source(it,checkNotNull(u.initialHash),u.initialGeneration)}?:true)&&(u.anchor?.let(::anchor)?:true)&&(u.snapshot?.let{anchor(it.anchor)}?:true)&&(u.withdrawal?.let{anchor(it.anchor)&&(it.expected?.let{e->anchor(e.anchor)}?:true)}?:true)&&u.items.all{anchor(it.anchor)}&&(u.page?.items?.all{anchor(it.anchor)}?:true)&&(u.pending?.let{anchor(it.anchor)&&(it.expected?.let{e->anchor(e.anchor)}?:true)}?:true)
}
class AudioTranscriptController(private val current:()->LocalRecordUiState,private val publish:((LocalRecordUiState)->LocalRecordUiState)->Unit,
    private val enqueue:(suspend(EntryRepository)->Unit)->Boolean,private val clock:Clock,private val sourceUi:((()->Unit)->Unit)={it()}) {
    private val tokens=AtomicLong();private val cursors=mutableListOf<PreferenceCursor?>();@Volatile private var disposed=false
    private fun owns(session:Long,request:Long?=null):Boolean {val local=current();val u=local.audioTranscripts?:return false;return !disposed&&u.session==session&&(request==null||request==u.request)&&local.canRetainTranscripts(u)}
    private fun update(session:Long,request:Long?=null,change:(AudioTranscriptUi)->AudioTranscriptUi)=publish{local->val u=local.audioTranscripts;if(disposed||u==null||u.session!=session||request!=null&&u.request!=request||!local.canRetainTranscripts(u))local else local.copy(audioTranscripts=change(u).takeIf{local.canRetainTranscripts(it)})}
    fun open(intent:AttachmentIntent?=null) {sourceUi {
        val l=current();if(disposed||!l.ready||l.opening||l.error!=null||!l.supportsAudioTranscripts||l.audioTranscripts!=null||l.imageTexts!=null||l.saving||l.savePending||l.recordPackage!=null||l.recycleBin!=null||l.originalEdit!=null||l.originalHistory!=null||l.sourcePreferences!=null||l.sourceMemories!=null||l.memoryInbox!=null||l.localProcessing!=null)return@sourceUi
        val entry=intent?.let{i->l.entries.singleOrNull{it.id==i.entryId&&it.currentRevision==i.revision}?:return@sourceUi}
        val u=AudioTranscriptUi(tokens.incrementAndGet(),intent,entry?.let{OriginalExports.hash(it.originalText)},intent?.let{l.localProcessingVersions[it.entryId]?.generation})
        cursors.clear();cursors.add(null);publish{it.copy(audioTranscripts=u)};load(u)
    }}
    fun close(session:Long){publish{if(it.audioTranscripts?.session==session)it.copy(audioTranscripts=null)else it}}
    fun edit(session:Long,value:String){sourceUi{val u=current().audioTranscripts?:return@sourceUi;if(owns(session)&&u.selected!=null&&!u.busy&&u.pending==null&&u.withdrawal==null&&u.anchor!=null&&value.length<=2000)update(session){it.copy(text=value,error=null,notice=null)}}}
    fun keyword(session:Long,value:String){val u=current().audioTranscripts?:return;if(!owns(session)||u.selected!=null||u.pending!=null||value.length>256||value==u.keyword)return;cursors.clear();cursors.add(null);update(session){it.copy(keyword=value,request=tokens.incrementAndGet(),items=emptyList(),page=null,cursor=null,canPrevious=false,busy=false,error=null,notice=null)}}
    fun search(session:Long){val u=current().audioTranscripts?:return;if(!owns(session)||u.selected!=null||u.busy||u.pending!=null)return;try{PreferenceKeywords.validate(u.keyword)}catch(_:Exception){update(session){it.copy(error="关键词最多64字，不能含控制字符或不完整字符。")};return};cursors.clear();cursors.add(null);val n=u.copy(cursor=null,canPrevious=false);update(session){n};load(n)}
    fun next(session:Long,previous:Boolean=false){val u=current().audioTranscripts?:return;if(!owns(session)||u.selected!=null||u.busy)return;if(previous){if(cursors.size<2)return;cursors.removeAt(cursors.lastIndex)}else cursors.add(u.page?.next?:return);val n=u.copy(cursor=cursors.last(),canPrevious=cursors.size>1);update(session){n};load(n)}
    fun refresh(session:Long){val u=current().audioTranscripts?:return;if(owns(session)&&!u.busy)load(u)}
    fun withdraw(session:Long,confirmation:AudioTranscriptWrite?=null){sourceUi{val u=current().audioTranscripts?:return@sourceUi;if(!owns(session)||u.busy||u.pending!=null||u.snapshot==null||u.snapshot.withdrawn||u.anchor==null)return@sourceUi
        if(confirmation!=null){if(u.withdrawal===confirmation)load(u,confirmation)}else if(u.withdrawal==null)update(session){it.copy(withdrawal=AudioTranscriptWrite(u.snapshot.id,u.anchor,u.snapshot,null,true,UUID.randomUUID().toString(),clock.millis()))}
    }}
    fun cancelWithdraw(session:Long,confirmation:AudioTranscriptWrite){if(owns(session))update(session){if(it.withdrawal===confirmation)it.copy(withdrawal=null)else it}}
    fun save(session:Long){sourceUi{saveOwned(session,false)}}
    private fun saveOwned(session:Long,withdraw:Boolean){val u=current().audioTranscripts?:return;if(!owns(session)||u.busy||u.pending!=null||u.anchor==null||!withdraw&&u.withdrawal!=null)return
        try{if(!withdraw){AudioTranscripts.text(u.text);require(u.snapshot==null||u.snapshot.withdrawn||u.snapshot.current.text!=u.text)};val r=AudioTranscriptWrite(u.snapshot?.id?:UUID.randomUUID().toString(),u.anchor,u.snapshot,if(withdraw)null else u.text,withdraw,UUID.randomUUID().toString(),clock.millis());load(u,r)}catch(_:Exception){update(session){it.copy(error="请填写最多1000字的完整人工文字；纠正需有变化。最多保留8个内容版本，仍可撤回。")}}
    }
    fun retry(session:Long){sourceUi{val u=current().audioTranscripts?:return@sourceUi;if(owns(session)&&!u.busy&&u.pending!=null)load(u,u.pending)}}
    private fun load(before:AudioTranscriptUi,write:AudioTranscriptWrite?=null){val req=tokens.incrementAndGet();update(before.session){it.copy(request=req,busy=true,items=emptyList(),page=null,pending=write,withdrawal=null,error=null,notice=null)}
        if(!owns(before.session,req))return
        if(!enqueue{store->if(!owns(before.session,req))return@enqueue;try{val repo=checkNotNull(store.audioTranscripts)
            if(before.selected!=null){val anchor=before.anchor?:repo.preview(before.selected);val saved=write?.let{repo.write(it)};val found=repo.read(anchor);if(saved!=null&&found?.same(saved)!=true)throw AudioTranscriptConflict();if(!owns(before.session,req))return@enqueue;update(before.session,req){it.copy(anchor=anchor,snapshot=found,text=if(found?.withdrawn==true)"" else found?.current?.text.orEmpty(),busy=false,pending=null,error=null,notice=if(write!=null)"人工转写已在本机核对；原音与原文未改。"else null)}}
            else{val p=repo.search(before.keyword,before.cursor);if(!repo.resolve(p){if(owns(before.session,req))update(before.session,req){it.copy(page=p,items=p.items,busy=false,error=null)}})throw AudioTranscriptConflict()}
        }catch(c:CancellationException){throw c}catch(_:Exception){failed(before.session,req,write)}})failed(before.session,req,write)
    }
    fun locate(session:Long,item:AudioTranscriptSnapshot,onLocate:(AudioTranscriptSource)->Unit){val u=current().audioTranscripts?:return;if(!owns(session)||u.busy||u.pending!=null||u.items.none{it===item})return;val req=tokens.incrementAndGet();update(session){it.copy(request=req,busy=true)}
        if(!enqueue{store->if(!owns(session,req))return@enqueue;try{val repo=checkNotNull(store.audioTranscripts);val page=u.page?:throw AudioTranscriptConflict();var valid=false;if(!repo.resolve(page,item){valid=true}||!valid)throw AudioTranscriptConflict();val found=repo.read(item.anchor);if(found==null||!found.same(item)||found.withdrawn)throw AudioTranscriptConflict();var admitted=false;publish{l->val v=l.audioTranscripts;admitted=!disposed&&v?.session==session&&v.request==req&&l.canRetainTranscripts(v);if(admitted)l.copy(audioTranscripts=null)else l};if(admitted)onLocate(item.anchor)}catch(c:CancellationException){throw c}catch(_:Exception){failed(session,req,null)}})failed(session,req,null)
    }
    private fun failed(session:Long,req:Long,write:AudioTranscriptWrite?){if(owns(session,req))update(session,req){it.copy(busy=false,items=emptyList(),page=null,pending=write,error="结果尚未核对，未显示成功或空结果。保留同次请求核对；来源变化后旧请求失效。",notice=null)}}
    fun dispose(){disposed=true;publish{it.copy(audioTranscripts=null)}}
}
