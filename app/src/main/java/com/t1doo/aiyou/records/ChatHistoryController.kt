package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatHistoryQuestionUse internal constructor(internal val text:String) {override fun toString()="ChatHistoryQuestionUse(redacted)"}
class ChatHistoryJump internal constructor(val location:ChatMessageLocation) {override fun toString()="ChatHistoryJump(redacted)"}
class ChatHistoryUi internal constructor(val session:Long,items:List<ChatMessage> = emptyList(),
    val available:Boolean=false,val busy:Boolean=false,val selected:ChatMessage?=null,val view:ChatMessageView?=null,
    val error:String?=null,val stale:Boolean=false,val confirmation:DeleteChatMessage?=null,val pending:DeleteChatMessage?=null,
    val uncertain:Boolean=false,val questionUse:ChatHistoryQuestionUse?=null,val jump:ChatHistoryJump?=null) {
    val items:List<ChatMessage> = Collections.unmodifiableList(ArrayList(items))
    val showEmpty:Boolean get()=available&&!busy&&pending==null&&error==null&&!stale&&items.isEmpty()
    internal fun next(items:List<ChatMessage> = this.items,available:Boolean=this.available,busy:Boolean=this.busy,
        selected:ChatMessage?=this.selected,view:ChatMessageView?=this.view,error:String?=null,stale:Boolean=this.stale,
        confirmation:DeleteChatMessage?=this.confirmation,pending:DeleteChatMessage?=this.pending,uncertain:Boolean=this.uncertain,
        questionUse:ChatHistoryQuestionUse?=null,jump:ChatHistoryJump?=null)=ChatHistoryUi(session,items,available,busy,selected,view,error,stale,confirmation,pending,uncertain,questionUse,jump)
    override fun toString()="ChatHistoryUi(redacted)"
}

/** Actual records actor. Closing a panel retires callbacks but never drops an accepted/uncertain deletion. */
class ChatHistoryController internal constructor(private val current:()->LocalRecordUiState,
    private val enqueue:(suspend(EntryRepository)->Unit)->Boolean,private val clock:Clock=Clock.systemUTC()) {
    private val lock=Any();private val mutable=MutableStateFlow<ChatHistoryUi?>(null);val state=mutable.asStateFlow()
    private data class Mark(val id:String,val revision:Int,val policy:ProcessingPolicy,val captured:Long,val zone:String,val proof:LocalProcessingVersion?)
    private data class Epoch(val ready:Boolean,val processing:Boolean,val marks:List<Mark>)
    private class Session(val owner:ChatHistoryRepository,var ui:ChatHistoryUi,var epoch:Epoch,var serial:Long=0)
    private var session:Session?=null;private var nextSession=0L;private var disposed=false
    private fun epoch():Epoch {val s=current();return Epoch(s.ready&&!s.opening&&s.error==null,s.localProcessingAvailable,
        s.entries.map {Mark(it.id,it.currentRevision,it.policy,it.capturedAtMillis,it.captureTimeZone,s.localProcessingVersions[it.id])})}
    private fun available()=current().let {it.ready&&!it.opening&&it.error==null}
    private fun owns(s:Session)=!disposed&&session===s&&current().chatHistoryOwner===s.owner
    private fun publish(s:Session,u:ChatHistoryUi){s.ui=u;if(mutable.value!=null)mutable.value=u}
    fun visible():ChatHistoryUi?=synchronized(lock){session?.takeIf {owns(it)&&available()}?.let{mutable.value}}
    fun sync()=synchronized(lock){
        val s=session?:return@synchronized
        if(!owns(s)){session=null;mutable.value=null;return@synchronized}
        val now=epoch();if(now!=s.epoch){s.epoch=now;retire(s)}
    }
    private fun retire(s:Session){s.serial++;publish(s,s.ui.next(view=null,stale=true,confirmation=if(s.ui.pending==null)null else s.ui.confirmation,
        error=if(s.ui.pending!=null)s.ui.error else "出处状态已变化；问题仍保留，请重新读取历史。"))}
    /** Called only after a source-changing command is accepted. Memory only; no SQL under an admission lock. */
    fun retireSources()=synchronized(lock){session?.takeIf(::owns)?.let(::retire)}
    fun open(){
        val s=synchronized(lock){
            if(disposed||!available())return
            val owner=current().chatHistoryOwner?:return
            val old=session?.takeIf {it.owner===owner}
            if(old!=null){old.ui=old.ui.next(confirmation=null,error=old.ui.error);mutable.value=old.ui;if(old.ui.busy||old.ui.pending!=null)return;old}
            else Session(owner,ChatHistoryUi(++nextSession),epoch()).also{session=it;mutable.value=it.ui}
        };load(s,null)
    }
    fun refresh(u:ChatHistoryUi){val s=synchronized(lock){session?.takeIf {owns(it)&&available()&&mutable.value===u&&!u.busy&&u.pending==null}?:return};load(s,u.selected?.id)}
    fun select(u:ChatHistoryUi,item:ChatMessage){val s=synchronized(lock){session?.takeIf {owns(it)&&available()&&mutable.value===u&&!u.busy&&u.pending==null&&u.confirmation==null&&u.items.any {it===item}}?:return};load(s,item.id)}
    private fun load(s:Session,selectId:String?){
        val (serial,wanted)=synchronized(lock){if(!owns(s))return;s.epoch=epoch();val wanted=selectId?:s.ui.selected?.id
            publish(s,s.ui.next(busy=true,confirmation=null));s.serial to wanted}
        val queued=enqueue {store->
            if(!synchronized(lock){owns(s)&&store.chatHistory===s.owner})return@enqueue
            try {
                val items=s.owner.list();val selected=items.singleOrNull{it.id==wanted}
                val view=selected?.let{s.owner.open(it.id,it.version)}
                val valid=view==null||s.owner.current(view)
                synchronized(lock){if(owns(s)){
                    val stale=s.serial!=serial||epoch()!=s.epoch||!valid
                    publish(s,s.ui.next(items=items,available=true,busy=false,selected=selected,view=if(stale)null else view,stale=stale,
                        error=if(stale)"出处在读取期间变化；问题仍保留，请重新读取。"else null))
                }}
            }catch(c:CancellationException){throw c}catch(_:Exception){synchronized(lock){if(owns(s))publish(s,s.ui.next(available=false,busy=false,view=null,error="历史未能完整读取，不能据此判断为空；请重试。"))}}
        }
        if(!queued)synchronized(lock){if(owns(s))publish(s,s.ui.next(available=false,busy=false,view=null,error="读取未能入队；现有内容保留。"))}
    }
    fun reviewDelete(u:ChatHistoryUi)=synchronized(lock){
        val s=session?:return@synchronized
        if(!owns(s)||!available()||!u.available||mutable.value!==u||u.busy||u.pending!=null||u.confirmation!=null)return@synchronized
        val selected=u.selected?:return@synchronized
        publish(s,u.next(confirmation=DeleteChatMessage(selected.id,selected.version,UUID.randomUUID().toString(),maxOf(clock.millis(),selected.updatedAtMillis))))
    }
    fun cancelDelete(u:ChatHistoryUi)=synchronized(lock){val s=session?:return@synchronized
        if(owns(s)&&mutable.value===u&&!u.busy&&u.pending==null)publish(s,u.next(confirmation=null))}
    fun confirmDelete(u:ChatHistoryUi,beforeAccepted:()->Unit={}) {
        val s=synchronized(lock){val s=session?:return
            if(!owns(s)||!available()||mutable.value!==u||u.busy||u.pending!=null)return
            val intent=u.confirmation?:return
            publish(s,u.next(busy=true,view=null,confirmation=null,pending=intent,uncertain=false));s
        }
        try {beforeAccepted()}finally{write(s)}
    }
    fun retryDelete(u:ChatHistoryUi){val s=synchronized(lock){val s=session?:return
        if(!owns(s)||!available()||mutable.value!==u||u.busy||u.pending==null)return
        publish(s,u.next(busy=true));s};write(s)}
    private fun write(s:Session){
        val intent=synchronized(lock){if(!owns(s))return;checkNotNull(s.ui.pending)}
        val queued=enqueue {store->
            if(!synchronized(lock){owns(s)&&store.chatHistory===s.owner})return@enqueue
            try {
                s.owner.delete(intent)
                synchronized(lock){if(owns(s)&&s.ui.pending===intent)publish(s,s.ui.next(items=s.ui.items.filter{it.id!=intent.id},available=true,busy=false,
                    selected=null,view=null,pending=null,uncertain=false,stale=false))}
            }catch(c:CancellationException){throw c}
            catch(_:ChatHistoryConflict){synchronized(lock){if(owns(s)&&s.ui.pending===intent)publish(s,s.ui.next(busy=false,view=null,stale=true,
                pending=if(s.ui.uncertain)intent else null,error=if(s.ui.uncertain)"先前删除仍待核对，同一次意图已保留；请确认重试。"else"历史版本已变化；本次未删除，请重新读取后确认。"))}}
            catch(_:Exception){synchronized(lock){if(owns(s)&&s.ui.pending===intent)publish(s,s.ui.next(busy=false,view=null,uncertain=true,
                error="删除结果待确认；已保留同一次操作，关闭重开后可重试，不会另建意图。"))}}
        }
        if(!queued)synchronized(lock){if(owns(s))publish(s,s.ui.next(busy=false,error="删除未能入队，同一次意图保留；可重试。"))}
    }
    private fun usable(s:Session,u:ChatHistoryUi)=owns(s)&&available()&&u.available&&mutable.value===u&&!u.busy&&u.pending==null&&u.confirmation==null&&!u.stale&&u.view!=null
    fun useQuestion(u:ChatHistoryUi)=handoff(u,null)
    fun openSource(u:ChatHistoryUi,reference:OriginalTextReference)=handoff(u,reference)
    private fun handoff(u:ChatHistoryUi,reference:OriginalTextReference?){
        val s=synchronized(lock){session?.takeIf {usable(it,u)}?:return};val view=checkNotNull(u.view)
        val serial=synchronized(lock){if(!usable(s,u))return;publish(s,u.next(busy=true));s.serial}
        val queued=enqueue {store->
            if(!synchronized(lock){owns(s)&&store.chatHistory===s.owner})return@enqueue
            try {
                require(s.owner.current(view))
                val location=reference?.let{checkNotNull(s.owner.locate(view,it))}
                if(location!=null)require(s.owner.locationCurrent(location))
                synchronized(lock){if(owns(s)){
                    val valid=s.serial==serial&&epoch()==s.epoch&&s.ui.view===view
                    publish(s,s.ui.next(busy=false,view=if(valid)view else null,stale=!valid,
                        questionUse=if(valid&&reference==null)ChatHistoryQuestionUse(checkNotNull(view.message.question))else null,
                        jump=if(valid&&location!=null)ChatHistoryJump(location)else null,
                        error=if(valid)null else "出处已变化，本次没有跳转或填入。"))
                }}
            }catch(c:CancellationException){throw c}catch(_:Exception){synchronized(lock){if(owns(s))publish(s,s.ui.next(busy=false,view=null,stale=true,error="历史或出处暂不可核对，本次没有跳转或填入。"))}}
        }
        if(!queued)synchronized(lock){if(owns(s))publish(s,s.ui.next(busy=false,error="操作未能入队；请重试。"))}
    }
    /** Navigation -> controller lifecycle. Consumers are memory-only and never run SQL/device/network work. */
    fun consumeQuestion(u:ChatHistoryUi,token:ChatHistoryQuestionUse,apply:(String)->Boolean):Boolean=synchronized(lock){
        val s=session?:return@synchronized false
        if(!usable(s,u)||u.questionUse!==token||epoch()!=s.epoch)return@synchronized false
        if(!apply(token.text)){publish(s,u.next(error="当前不能填入问题；请回到问一问后重试。"));return@synchronized false}
        s.ui=u.next();mutable.value=null;true
    }
    fun consumeSource(u:ChatHistoryUi,token:ChatHistoryJump,apply:(ChatMessageLocation)->Boolean):Boolean=synchronized(lock){
        val s=session?:return@synchronized false
        if(!usable(s,u)||u.jump!==token||epoch()!=s.epoch)return@synchronized false
        if(!apply(token.location)){publish(s,u.next(view=null,stale=true,error="出处状态已变化，本次未跳转。"));return@synchronized false}
        s.ui=u.next();mutable.value=null;true
    }
    fun close(u:ChatHistoryUi)=synchronized(lock){if(mutable.value===u){session?.let{it.serial++;it.ui=it.ui.next(view=null,stale=true,confirmation=null,error=it.ui.error)};mutable.value=null}}
    fun dispose()=synchronized(lock){disposed=true;session=null;mutable.value=null}
}

/** Final memory-only UI handoff guard over the same authenticated local snapshot; never a send grant. */
internal fun LocalRecordUiState.canUseChatLocation(owner:ChatHistoryRepository?,proof:ChatMessageLocation):Boolean =
    owner!=null&&chatHistoryOwner===owner&&ready&&!opening&&error==null&&localProcessingAvailable&&
        hasLocalProcessingProof(proof.reference.source)&&localProcessingVersions[proof.entry.id]?.generation==proof.generation&&
        entries.any{entry->entry.id==proof.entry.id&&entry.currentRevision==proof.entry.currentRevision&&
            entry.id==proof.reference.source.sourceId&&entry.currentRevision.toLong()==proof.reference.source.revision&&
            entry.originalText==proof.entry.originalText&&entry.capturedAtMillis==proof.entry.capturedAtMillis&&
            entry.captureTimeZone==proof.entry.captureTimeZone&&entry.policy==proof.entry.policy&&entry.policy!=ProcessingPolicy.AI_NONE}
