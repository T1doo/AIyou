package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class ImageTextUi(val session:Long,val selected:AttachmentIntent?,val initialHash:String?,val initialGeneration:Long?,
    val request:Long=0,val anchor:ImageTextSource?=null,val snapshot:ImageTextSnapshot?=null,val text:String="",
    val keyword:String="",val items:List<ImageTextSnapshot> = emptyList(),val page:ImageTextPage?=null,
    val cursor:PreferenceCursor?=null,val canPrevious:Boolean=false,val busy:Boolean=false,val phase:String="IDLE",
    val pending:ImageTextWrite?=null,val deletion:ImageTextWrite?=null,val error:String?=null,val notice:String?=null) {
    override fun toString()="ImageTextUi(redacted)"
}
internal fun LocalRecordUiState.currentImageTextSource(a:ImageTextSource):Boolean = ready&&!opening&&error==null&&
    entries.any{it.id==a.intent.entryId&&it.currentRevision==a.intent.revision&&OriginalExports.hash(it.originalText)==a.sourceHash&&it.policy==a.policy&&it.capturedAtMillis==a.capturedAtMillis&&it.captureTimeZone==a.zone}&&
    (!supportsLocalProcessing||localProcessingAvailable&&localProcessingVersions[a.intent.entryId]?.generation==a.generation)
internal fun LocalRecordUiState.canRetainImageTexts(u:ImageTextUi):Boolean {
    if(!ready||opening||error!=null||!supportsImageTexts||recordPackage!=null||recycleBin!=null||originalEdit!=null||originalHistory!=null||audioTranscripts!=null||localProcessing!=null)return false
    fun anchor(a:ImageTextSource)=currentImageTextSource(a)
    return (u.selected?.let{i->entries.any{it.id==i.entryId&&it.currentRevision==i.revision&&OriginalExports.hash(it.originalText)==u.initialHash}&&
        (u.initialGeneration==null||!supportsLocalProcessing||localProcessingAvailable&&localProcessingVersions[i.entryId]?.generation==u.initialGeneration)}?:true)&&
        (u.anchor?.let(::anchor)?:true)&&(u.snapshot?.let{anchor(it.anchor)}?:true)&&u.items.all{anchor(it.anchor)}&&
        (u.page?.items?.all{anchor(it.anchor)}?:true)&&(u.pending?.let{anchor(it.anchor)&&(it.expected?.let{e->anchor(e.anchor)}?:true)}?:true)&&
        (u.deletion?.let{anchor(it.anchor)&&(it.expected?.let{e->anchor(e.anchor)}?:true)}?:true)
}
/** One existing VM actor owns database operations. A cancelled engine invocation must really return
 * before that actor/owner can close storage or another inference can start; coroutine cancellation
 * alone is never used as a native-resource acknowledgement. Production uses the unavailable port. */
class ImageTextController(private val current:()->LocalRecordUiState,
    private val publish:((LocalRecordUiState)->LocalRecordUiState)->Unit,
    private val enqueue:(suspend(EntryRepository)->Unit)->Boolean,private val clock:Clock,
    private val recognizer:ImageTextRecognizer=UnavailableImageTextRecognizer,
    private val sourceUi:((()->Unit)->Unit)={it()},private val canOpen:(AttachmentIntent?)->Boolean={true}) {
    private val tokens=AtomicLong();private val cursors=mutableListOf<PreferenceCursor?>()
    @Volatile private var disposed=false
    private class Invocation(val session:Long,val request:Long) {
        private enum class Stage { QUEUED,RUNNING,CANCELLED,COMMITTING }
        private var stage=Stage.QUEUED;private var started=false;private var deadline=Long.MAX_VALUE
        @Synchronized fun enter():Boolean {if(stage!=Stage.QUEUED)return false;started=true;stage=Stage.RUNNING;deadline=System.nanoTime()+15_000_000_000L;return true}
        @Synchronized fun cancelled()=stage==Stage.CANCELLED||System.nanoTime()>=deadline
        /** 0=no producer was admitted,1=requested cancellation awaits its return,2=commit won. */
        @Synchronized fun cancel():Int {
            if(stage==Stage.COMMITTING)return 2
            stage=Stage.CANCELLED;return if(started)1 else 0
        }
        @Synchronized fun beginCommit():Boolean {
            if(stage!=Stage.RUNNING||System.nanoTime()>=deadline)return false
            stage=Stage.COMMITTING;return true
        }
    }
    private val invocation=AtomicReference<Invocation?>(null)
    private val mutableInFlight=MutableStateFlow(false)
    val inFlight=mutableInFlight.asStateFlow()
    private fun owns(session:Long,request:Long?=null):Boolean {
        val l=current();val u=l.imageTexts?:return false
        return !disposed&&u.session==session&&(request==null||u.request==request)&&l.canRetainImageTexts(u)
    }
    private fun update(session:Long,request:Long?=null,change:(ImageTextUi)->ImageTextUi)=publish{l->
        val u=l.imageTexts;if(disposed||u==null||u.session!=session||request!=null&&u.request!=request||!l.canRetainImageTexts(u))l
        else l.copy(imageTexts=change(u).takeIf{l.canRetainImageTexts(it)})
    }
    fun open(intent:AttachmentIntent?=null){sourceUi{
        val l=current()
        if(disposed||invocation.get()!=null||!canOpen(intent)||!l.ready||l.opening||l.error!=null||!l.supportsImageTexts||l.imageTexts!=null||l.saving||l.savePending||
            l.recordPackage!=null||l.recycleBin!=null||l.originalEdit!=null||l.originalHistory!=null||l.sourcePreferences!=null || l.sourceFacts!=null||l.sourceMemories!=null||
            l.audioTranscripts!=null||l.memoryInbox!=null||l.localProcessing!=null)return@sourceUi
        if(intent!=null&&intent.mediaType !in setOf("image/png","image/jpeg"))return@sourceUi
        val entry=intent?.let{i->l.entries.singleOrNull{it.id==i.entryId&&it.currentRevision==i.revision}?:return@sourceUi}
        val u=ImageTextUi(tokens.incrementAndGet(),intent,entry?.let{OriginalExports.hash(it.originalText)},intent?.let{l.localProcessingVersions[it.entryId]?.generation})
        cursors.clear();cursors.add(null);publish{it.copy(imageTexts=u)};load(u)
    }}
    private fun retireInvocation(running:Invocation):Int {
        val outcome=running.cancel()
        if(outcome==0&&invocation.compareAndSet(running,null))mutableInFlight.value=false
        return outcome
    }
    fun close(session:Long){invocation.get()?.takeIf{it.session==session}?.let(::retireInvocation);publish{if(it.imageTexts?.session==session)it.copy(imageTexts=null)else it}}
    fun cancelExtraction(session:Long,request:Long){
        val running=invocation.get()?.takeIf{it.session==session&&it.request==request}?:return
        when(retireInvocation(running)){
            0->update(session,request){it.copy(busy=false,phase="IDLE",notice="本次提取已取消，尚未开始处理图片。")}
            1->update(session,request){it.copy(phase="CANCELLING",notice="已请求取消；等待提取资源实际结束，不会保存迟到文字。")}
            else->update(session,request){it.copy(notice="保存已开始，正在核对结果；关闭页面不会撤销已开始的保存。")}
        }
    }
    fun extract(session:Long){sourceUi{
        val u=current().imageTexts?:return@sourceUi
        if(!owns(session)||u.selected==null||u.anchor==null||u.snapshot!=null||u.busy||u.pending!=null||invocation.get()!=null)return@sourceUi
        if(u.anchor.policy==ProcessingPolicy.AI_NONE){update(session){it.copy(error="此记录设置为不使用 AI。请先明确改为仅本机处理，再主动提取；当前未运行 OCR。")};return@sourceUi}
        if(!recognizer.available){update(session){it.copy(error="当前未安装可用的本地中文文字提取能力，原图已保留；未运行 OCR，也未生成文字。")};return@sourceUi}
        val request=tokens.incrementAndGet();val running=Invocation(session,request)
        if(!invocation.compareAndSet(null,running))return@sourceUi
        mutableInFlight.value=true
        update(session){it.copy(request=request,busy=true,phase="QUEUED",error=null,notice=null)}
        if(!enqueue{store->
            var bytes:ByteArray?=null
            try {
                if(!running.enter()||!owns(session,request)||running.cancelled())return@enqueue
                update(session,request){it.copy(phase="PROCESSING")}
                val repo=checkNotNull(store.imageTexts);if(repo.preview(u.selected)!=u.anchor)throw ImageTextConflict()
                bytes=checkNotNull(store.attachments).read(u.selected.id)
                require(bytes.size.toLong()==u.selected.byteSize&&hash(bytes)==u.selected.sha256)
                if(!owns(session,request)||running.cancelled()){update(session,request){it.copy(busy=false,phase="IDLE",notice="本次提取已取消或超时，文字未保存。")};return@enqueue}
                val extraction=withContext(NonCancellable){recognizer.recognize(u.anchor,bytes){running.cancelled()||!owns(session,request)}}
                bytes.fill(0);bytes=null
                if(!owns(session,request))return@enqueue
                if(running.cancelled()){update(session,request){it.copy(busy=false,phase="IDLE",notice="本次提取已结束，取消或超时的文字未保存。")};return@enqueue}
                if(repo.preview(u.selected)!=u.anchor)throw ImageTextConflict()
                if(extraction==null){update(session,request){it.copy(busy=false,phase="IDLE",notice="本次未提取到可保存文字，原图未改。")};return@enqueue}
                if(!owns(session,request))return@enqueue
                if(!running.beginCommit()){update(session,request){it.copy(busy=false,phase="IDLE",notice="本次提取已取消或超时，文字未保存。")};return@enqueue}
                val write=ImageTextWrite(UUID.randomUUID().toString(),u.anchor,null,extraction,null,false,UUID.randomUUID().toString(),clock.millis())
                update(session,request){it.copy(phase="COMMITTING",pending=write)}
                settle(store,session,request,write)
            }catch(c:CancellationException){throw c}catch(_:Exception){failed(session,request,current().imageTexts?.pending)}
            finally{bytes?.fill(0);if(invocation.compareAndSet(running,null))mutableInFlight.value=false}
        }){if(invocation.compareAndSet(running,null))mutableInFlight.value=false;failed(session,request,null)}
    }}
    fun edit(session:Long,text:String){sourceUi{val u=current().imageTexts?:return@sourceUi;if(owns(session)&&u.snapshot!=null&&!u.busy&&u.pending==null&&u.deletion==null&&text.length<=2000)update(session){it.copy(text=text,error=null,notice=null)}}}
    fun save(session:Long){sourceUi{
        val u=current().imageTexts?:return@sourceUi;val expected=u.snapshot?:return@sourceUi
        if(!owns(session)||u.busy||u.pending!=null||u.deletion!=null)return@sourceUi
        try{ImageTexts.text(u.text);require(u.text!=expected.current.text);load(u,ImageTextWrite(expected.id,expected.anchor,expected,null,u.text,false,UUID.randomUUID().toString(),clock.millis()))}
        catch(_:Exception){update(session){it.copy(error="请填写有变化的完整文字，最多1000字；最多保留8个内容版本，仍可删除。")}}
    }}
    fun delete(session:Long,confirmation:ImageTextWrite?=null){sourceUi{
        val u=current().imageTexts?:return@sourceUi;val old=u.snapshot?:return@sourceUi
        if(!owns(session)||u.busy||u.pending!=null)return@sourceUi
        if(confirmation==null){if(u.deletion==null)update(session){it.copy(deletion=ImageTextWrite(old.id,old.anchor,old,null,null,true,UUID.randomUUID().toString(),clock.millis()))}}
        else if(u.deletion===confirmation)load(u,confirmation)
    }}
    fun cancelDelete(session:Long,confirmation:ImageTextWrite){if(owns(session))update(session){if(it.deletion===confirmation)it.copy(deletion=null)else it}}
    fun retry(session:Long){sourceUi{val u=current().imageTexts?:return@sourceUi;if(owns(session)&&!u.busy&&u.pending!=null)load(u,u.pending)}}
    fun refresh(session:Long){val u=current().imageTexts?:return;if(owns(session)&&!u.busy&&invocation.get()==null)load(u)}
    fun keyword(session:Long,text:String){val u=current().imageTexts?:return;if(!owns(session)||u.selected!=null||u.pending!=null||text.length>256||text==u.keyword)return;cursors.clear();cursors.add(null);update(session){it.copy(keyword=text,request=tokens.incrementAndGet(),items=emptyList(),page=null,cursor=null,canPrevious=false,busy=false,error=null,notice=null)}}
    fun search(session:Long){val u=current().imageTexts?:return;if(!owns(session)||u.selected!=null||u.busy||u.pending!=null)return;try{PreferenceKeywords.validate(u.keyword)}catch(_:Exception){update(session){it.copy(error="关键词最多64字，不能含控制字符或不完整字符。")};return};cursors.clear();cursors.add(null);val n=u.copy(cursor=null,canPrevious=false);update(session){n};load(n)}
    fun next(session:Long,previous:Boolean=false){val u=current().imageTexts?:return;if(!owns(session)||u.selected!=null||u.busy)return;if(previous){if(cursors.size<2)return;cursors.removeAt(cursors.lastIndex)}else cursors.add(u.page?.next?:return);val n=u.copy(cursor=cursors.last(),canPrevious=cursors.size>1);update(session){n};load(n)}
    private fun load(before:ImageTextUi,write:ImageTextWrite?=null){
        val request=tokens.incrementAndGet();update(before.session){it.copy(request=request,busy=true,phase=if(write==null)"READING"else"COMMITTING",items=emptyList(),page=null,pending=write,deletion=null,error=null,notice=null)}
        if(!owns(before.session,request))return
        if(!enqueue{store->if(!owns(before.session,request))return@enqueue;try{
            if(write!=null)settle(store,before.session,request,write)
            else{val repo=checkNotNull(store.imageTexts)
                if(before.selected!=null){val anchor=before.anchor?:repo.preview(before.selected);val found=repo.read(anchor);if(repo.preview(before.selected)!=anchor)throw ImageTextConflict();update(before.session,request){it.copy(anchor=anchor,snapshot=found,text=found?.current?.text.orEmpty(),busy=false,phase="IDLE",pending=null)}}
                else{val page=repo.search(before.keyword,before.cursor);if(!repo.resolve(page){if(owns(before.session,request))update(before.session,request){it.copy(page=page,items=page.items,busy=false,phase="IDLE")}})throw ImageTextConflict()}
            }
        }catch(c:CancellationException){throw c}catch(_:Exception){failed(before.session,request,write)}})failed(before.session,request,write)
    }
    private suspend fun settle(store:EntryRepository,session:Long,request:Long,write:ImageTextWrite){
        if(!owns(session,request))return
        val repo=checkNotNull(store.imageTexts);val saved=repo.write(write);val found=if(write.delete)null else repo.read(write.anchor)
        if(write.delete){if(saved!=null||found!=null)throw ImageTextConflict()}else if(saved==null||found?.same(saved)!=true||repo.preview(write.anchor.intent)!=write.anchor)throw ImageTextConflict()
        update(session,request){it.copy(snapshot=found,text=found?.current?.text.orEmpty(),busy=false,phase="IDLE",pending=null,error=null,notice=if(write.delete)"此派生文字及历史已删除；原图与正文未改，已有外部备份仍由你管理。"else"图片派生文字已在本机核对；原图与正文未改。")}
    }
    fun locate(session:Long,item:ImageTextSnapshot,onLocate:(ImageTextSource)->Unit){
        val u=current().imageTexts?:return;if(!owns(session)||u.busy||u.pending!=null||u.items.none{it===item})return
        val request=tokens.incrementAndGet();update(session){it.copy(request=request,busy=true)}
        if(!enqueue{store->if(!owns(session,request))return@enqueue;try{
            val repo=checkNotNull(store.imageTexts);val page=u.page?:throw ImageTextConflict()
            val found=repo.read(item.anchor);if(found==null||!found.same(item))throw ImageTextConflict()
            // Authenticate the retained page AFTER the asynchronous selected-item read. Its
            // final committed proof callback is the only source handoff, with no intervening IO.
            if(!repo.resolve(page,item){
                var admitted=false;publish{l->val v=l.imageTexts;admitted=!disposed&&v?.session==session&&v.request==request&&l.canRetainImageTexts(v);if(admitted)l.copy(imageTexts=null)else l}
                if(admitted)onLocate(item.anchor)
            })throw ImageTextConflict()
        }catch(c:CancellationException){throw c}catch(_:Exception){failed(session,request,null)}})failed(session,request,null)
    }
    private fun failed(session:Long,request:Long,write:ImageTextWrite?){if(owns(session,request))update(session,request){it.copy(busy=false,phase=if(write==null)"IDLE"else"UNKNOWN",items=emptyList(),page=null,pending=write,error="结果尚未核对，未显示成功或空结果。原图保留；保存请求可按同次意图重试，来源变化后失效。")}}
    fun dispose(){disposed=true;invocation.get()?.let(::retireInvocation);publish{it.copy(imageTexts=null)}}
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
}
