package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class QuestionInputSwitch internal constructor(val target:SavedQuestion?) {
    override fun toString()="QuestionInputSwitch(redacted)"
}
class SavedQuestionsUi internal constructor(val session:Long,items:List<SavedQuestion> = emptyList(),
    val available:Boolean=false,val busy:Boolean=false,val selected:SavedQuestion?=null,val text:String="",
    val rejectedInput:Boolean=false,val selectionStale:Boolean=false,val confirmation:SaveQuestion?=null,val pending:SaveQuestion?=null,
    val error:String?=null,val pendingUncertain:Boolean=false,val pendingConflict:Boolean=false,
    val replacement:QuestionInputSwitch?=null) {
    val items:List<SavedQuestion> = Collections.unmodifiableList(ArrayList(items))
    internal fun next(items:List<SavedQuestion> = this.items,available:Boolean=this.available,busy:Boolean=this.busy,
        selected:SavedQuestion?=this.selected,text:String=this.text,rejectedInput:Boolean=this.rejectedInput,selectionStale:Boolean=this.selectionStale,
        confirmation:SaveQuestion?=this.confirmation,pending:SaveQuestion?=this.pending,error:String?=null,
        pendingUncertain:Boolean=this.pendingUncertain,pendingConflict:Boolean=this.pendingConflict,replacement:QuestionInputSwitch?=this.replacement)=
        SavedQuestionsUi(session,items,available,busy,selected,text,rejectedInput,selectionStale,confirmation,pending,error,pendingUncertain,pendingConflict,replacement)
    val showEmpty:Boolean get()=available&&!busy&&pending==null&&error==null&&!selectionStale&&items.isEmpty()
    override fun toString()="SavedQuestionsUi(redacted)"
}

/** One owner and the existing record actor. SQL/queue callbacks are never invoked while holding our lock. */
class SavedQuestionsController internal constructor(private val currentOwner:()->SavedQuestionRepository?,
    private val enqueue:(suspend (EntryRepository)->Unit)->Boolean,private val clock:Clock=Clock.systemUTC(),
    private val uiAvailable:()->Boolean={true}) {
    private val lock=Any()
    private val mutable=MutableStateFlow<SavedQuestionsUi?>(null)
    val state=mutable.asStateFlow()
    private class Session(val owner:SavedQuestionRepository,var ui:SavedQuestionsUi)
    private var session:Session?=null
    private var serial=0L
    private var disposed=false
    fun visible():SavedQuestionsUi?=synchronized(lock) { session?.takeIf {owns(it)&&uiAvailable()}?.let { mutable.value } }
    fun sync()=synchronized(lock) { if(session?.let { !owns(it) }==true) {session=null;mutable.value=null} }
    private fun owns(s:Session)=!disposed && session===s && currentOwner()===s.owner
    private fun publish(s:Session,u:SavedQuestionsUi) {
        s.ui=u
        if(mutable.value!=null)mutable.value=u // Closing never gets undone by a late SQL completion.
    }
    private fun editable(s:Session,u:SavedQuestionsUi)=owns(s)&&uiAvailable()&&mutable.value===u&&!u.busy&&u.pending==null&&u.confirmation==null&&u.replacement==null
    /** Plain user input only; confers no source, review, connection or network authority. */
    fun questionForUse(u:SavedQuestionsUi):String?=synchronized(lock) {
        val s=session?:return@synchronized null
        if(!editable(s,u)||!u.available||u.rejectedInput||u.selectionStale)return@synchronized null
        u.text.takeIf {runCatching {SavedQuestions.question(it)}.isSuccess}
    }
    fun open(seed:String?=null) {
        val load=synchronized(lock) {
            if(disposed||!uiAvailable())return
            val owner=currentOwner()?:return
            val old=session?.takeIf { it.owner===owner }
            if(old!=null) {
                if(mutable.value==null&&seed!=null&&!old.ui.busy&&old.ui.pending==null&&
                    (old.ui.text.isBlank()||old.ui.selected?.question==old.ui.text)&&runCatching { SavedQuestions.question(seed) }.isSuccess)
                    old.ui=old.ui.next(selected=null,text=seed,rejectedInput=false,selectionStale=false,confirmation=null,replacement=null)
                else old.ui=old.ui.next(confirmation=null,replacement=null,error=old.ui.error)
                val reload=!old.ui.busy&&old.ui.pending==null
                if(reload)old.ui=old.ui.next(busy=true,error=old.ui.error)
                mutable.value=old.ui
                if(!reload)return
                return@synchronized old
            }
            val initial=seed?.takeIf { runCatching { SavedQuestions.question(it) }.isSuccess }.orEmpty()
            val s=Session(owner,SavedQuestionsUi(++serial,text=initial,busy=true));session=s;mutable.value=s.ui;s
        }
        queue(load,false)
    }
    fun refresh(u:SavedQuestionsUi) {
        val s=synchronized(lock) {
            val s=session?:return
            if(!editable(s,u)&&!(owns(s)&&uiAvailable()&&mutable.value===u&&!u.busy&&u.pendingConflict&&u.pending!=null))return
            publish(s,u.next(busy=true));s
        };queue(s,false)
    }
    fun select(u:SavedQuestionsUi,item:SavedQuestion?)=synchronized(lock) {
        val s=session?:return@synchronized
        if(!editable(s,u)||!u.available||item!=null&&u.items.none { it===item })return@synchronized
        if(u.text!=(u.selected?.question.orEmpty()))publish(s,u.next(replacement=QuestionInputSwitch(item)))
        else publish(s,u.next(selected=item,text=item?.question.orEmpty(),rejectedInput=false,selectionStale=false))
    }
    fun confirmSwitch(u:SavedQuestionsUi)=synchronized(lock) {
        val s=session?:return@synchronized
        if(!owns(s)||!uiAvailable()||mutable.value!==u||u.busy||u.pending!=null)return@synchronized
        val target=u.replacement?.target
        if(u.replacement==null||target!=null&&u.items.none {it===target})return@synchronized
        publish(s,u.next(selected=target,text=target?.question.orEmpty(),rejectedInput=false,selectionStale=false,replacement=null))
    }
    fun cancelSwitch(u:SavedQuestionsUi)=synchronized(lock) {
        val s=session?:return@synchronized
        if(owns(s)&&uiAvailable()&&mutable.value===u&&!u.busy&&u.pending==null)publish(s,u.next(replacement=null))
    }
    fun edit(u:SavedQuestionsUi,text:String)=synchronized(lock) {
        val s=session?:return@synchronized
        if(!editable(s,u)||!u.available)return@synchronized
        val valid=text.length<=1024&&LocalCandidates.validUnicode(text)&&text.codePointCount(0,text.length)<=512&&text.toByteArray(Charsets.UTF_8).size<=2048
        publish(s,if(valid)u.next(text=text,rejectedInput=false) else u.next(rejectedInput=true,
            error="问题最多512码点／2048字节，不能含不完整字符；本次输入没有截断或替换已确认内容。"))
    }
    fun review(u:SavedQuestionsUi,delete:Boolean=false)=synchronized(lock) {
        val s=session?:return@synchronized
        if(!editable(s,u)||!u.available||u.rejectedInput||u.selectionStale||delete&&u.selected==null)return@synchronized
        val intent=try { SaveQuestion(u.selected?.id?:UUID.randomUUID().toString(),u.selected?.version?:0,
            UUID.randomUUID().toString(),if(delete)null else u.text,maxOf(clock.millis(),u.selected?.updatedAtMillis?:0)) }
        catch(_:IllegalArgumentException) { publish(s,u.next(error="请先填写有效的问题。"));return@synchronized }
        publish(s,u.next(confirmation=intent))
    }
    fun cancelReview(u:SavedQuestionsUi)=synchronized(lock) {
        val s=session?:return@synchronized
        if(owns(s)&&mutable.value===u&&!u.busy&&u.pending==null)publish(s,u.next(confirmation=null))
    }
    fun confirm(u:SavedQuestionsUi) {
        val s=synchronized(lock) {
            val s=session?:return
            if(!owns(s)||!uiAvailable()||mutable.value!==u||u.busy||u.pending!=null)return
            val intent=u.confirmation?:return
            publish(s,u.next(busy=true,pending=intent,confirmation=null,pendingUncertain=false,pendingConflict=false));s
        };queue(s,true)
    }
    fun retry(u:SavedQuestionsUi) {
        val s=synchronized(lock) {
            val s=session?:return
            if(!owns(s)||!uiAvailable()||mutable.value!==u||u.busy||u.pending==null)return
            publish(s,u.next(busy=true));s
        };queue(s,true)
    }
    private fun queue(s:Session,write:Boolean) {
        val intent=synchronized(lock) { if(!owns(s))return;s.ui.pending }
        val queued=enqueue { store ->
            if(!synchronized(lock) { owns(s)&&store.savedQuestions===s.owner })return@enqueue
            try {
                if(write) {
                    val result=s.owner.save(checkNotNull(intent))
                    synchronized(lock) { if(owns(s)&&s.ui.pending===intent) {
                        val items=s.ui.items.filter { it.id!=result.id }+listOfNotNull(result.takeIf { it.question!=null })
                        publish(s,s.ui.next(items=items.sortedWith(compareByDescending<SavedQuestion> { it.updatedAtMillis }.thenBy { it.id }),
                            available=true,busy=false,selected=result.takeIf { it.question!=null },text=result.question.orEmpty(),pending=null,rejectedInput=false,selectionStale=false,pendingUncertain=false,pendingConflict=false))
                    } }
                } else {
                    val items=s.owner.list()
                    synchronized(lock) { if(owns(s)) {
                        val selected=s.ui.selected?.let { old -> items.singleOrNull { it.id==old.id&&it.version==old.version } }
                        val stale=s.ui.selectionStale||s.ui.selected!=null&&selected==null
                        publish(s,s.ui.next(items=items,available=true,busy=false,selected=selected,selectionStale=stale,
                            error=if(s.ui.pending!=null)"当前列表已读取；先前操作的结果仍待确认，输入和原意图继续保留。"
                                else if(stale)"已保存版本已变化，当前输入仍保留；请重新选择当前问题，或明确点击新问题后再保存。"else null))
                    } }
                }
            } catch(c:CancellationException) { throw c }
            catch(_:SavedQuestionLimit) { synchronized(lock) {if(owns(s))publish(s,s.ui.next(busy=false,pending=null,
                pendingUncertain=false,pendingConflict=false,error="已达到128条问题与删除标记的保留上限；这次没有写入。输入已保留，仍可编辑已有问题。"))} }
            catch(_:SavedQuestionConflict) {synchronized(lock) {if(owns(s)) {
                val unknown=s.ui.pendingUncertain
                publish(s,s.ui.next(busy=false,pending=if(unknown)intent else null,pendingConflict=unknown,
                    selectionStale=true,error=if(unknown)"版本已变化，本次重试没有覆盖。先前结果仍待确认；可读取当前列表，原意图不会替换。"
                    else "版本已变化，这次没有写入。输入保留；请重新读取并选择当前版本，或明确选择新问题。"))
            }} }
            catch(_:Exception) { synchronized(lock) { if(owns(s))publish(s,s.ui.next(busy=false,
                available=if(write)s.ui.available else false,pendingUncertain=if(write)true else s.ui.pendingUncertain,error=if(write)
                    "保存结果待确认；输入与同一次操作已保留。请重试确认，当前不能改写或另建操作。"
                else "已保存的问题未能完整读取，不能据此判断为空。请重试读取。")) } }
        }
        if(!queued)synchronized(lock) { if(owns(s))publish(s,s.ui.next(busy=false,available=if(write)s.ui.available else false,
            error=if(write)"操作未能入队，已保留同一次意图；可重试。" else "读取未能入队，请重试。")) }
    }
    /** Closing keeps pending intent and buffer. A real owner replacement/disposal retires all callbacks. */
    fun close(u:SavedQuestionsUi)=synchronized(lock) {
        if(mutable.value===u) {session?.let {it.ui=it.ui.next(confirmation=null,replacement=null,error=it.ui.error)};mutable.value=null}
    }
    fun dispose()=synchronized(lock) { disposed=true;session=null;mutable.value=null }
}
