package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

sealed interface ProposalPending {
    data class Write(val request:TaskProposalWrite,val epoch:Long):ProposalPending {override fun toString()="ProposalWrite(redacted)"}
    data class Confirm(val request:TaskProposalConfirm):ProposalPending {override fun toString()="ProposalConfirm(redacted)"}
}
data class TaskProposalEditor(val session:Long,val id:String,val binding:TaskSourceBinding,val created:Long,val zone:String,
    val title:String="",val date:ManualTaskDateInput=ManualTaskDateInput(),val saved:TaskProposal?=null,
    val epoch:Long=0,val savedEpoch:Long = -1,val request:Long=0,val busy:Boolean=false,
    val pending:ProposalPending?=null,val confirmation:TaskProposalConfirm?=null,val closing:Boolean=false,
    val error:String?=null,val conflict:Boolean=false,val notice:String?=null,val retired:Boolean=false) {
    override fun toString()="TaskProposalEditor(redacted)"
}
data class TaskProposalsUiState(val supported:Boolean=false,val available:Boolean=false,val loading:Boolean=false,
    val items:List<TaskProposalItem> = emptyList(),val editor:TaskProposalEditor?=null,val error:String?=null) {
    override fun toString()="TaskProposalsUiState(redacted)"
}

/** A fresh SQL list may revoke a source before the records/proof refresh observes it. */
internal fun LocalRecordUiState.canUseProposalSource(e:TaskProposalEditor):Boolean =
    !e.retired&&canUseTaskSource(e.binding)&&taskProposals.available&&e.saved!=null&&
        taskProposals.items.singleOrNull {it.proposal.id==e.id}?.let {
            it.sourceAvailable&&it.proposal==e.saved&&it.proposal.state==TaskProposalState.DRAFT}==true

internal enum class TaskProposalSaveState { SAVING, UNKNOWN, MISSING, CHANGED, SAVED, SAVED_SOURCE_UNAVAILABLE, PROCESSED }

/** A local write acknowledgement alone cannot promise that another owner kept this draft. */
internal fun LocalRecordUiState.proposalSaveState(e:TaskProposalEditor):TaskProposalSaveState {
    if(e.notice!=null) return TaskProposalSaveState.PROCESSED
    if(!ready||opening||error!=null||!taskProposals.available||taskProposals.loading||taskProposals.error!=null||
        e.pending!=null&&e.error!=null) return TaskProposalSaveState.UNKNOWN
    if(e.busy||e.pending!=null||e.epoch!=e.savedEpoch) return TaskProposalSaveState.SAVING
    val saved=e.saved ?: return TaskProposalSaveState.UNKNOWN
    val matches=taskProposals.items.filter {it.proposal.id==e.id}
    if(matches.isEmpty()) return TaskProposalSaveState.MISSING
    if(matches.size!=1) return TaskProposalSaveState.UNKNOWN
    if(matches.single().proposal!=saved||saved.id!=e.id||saved.binding!=e.binding||
        saved.createdAtMillis!=e.created||saved.creationTimeZone!=e.zone) return TaskProposalSaveState.CHANGED
    if(e.title!=saved.titleInput||e.date!=saved.dateInput) return TaskProposalSaveState.SAVING
    return if(canUseProposalSource(e)) TaskProposalSaveState.SAVED else TaskProposalSaveState.SAVED_SOURCE_UNAVAILABLE
}

/** Serialized, coalesced encrypted autosave. Only a separately confirmed request creates a task. */
class TaskProposalController(private val current:()->LocalRecordUiState,
    private val publish:((LocalRecordUiState)->LocalRecordUiState)->Unit,
    private val enqueue:(suspend(EntryRepository)->Unit)->Boolean,private val clock:Clock,
    private val afterClose:()->Unit={}) {
    private val sessions=AtomicLong();private val reads=AtomicLong()
    @Volatile private var disposed=false
    private fun ready()=!disposed&&current().let {it.ready&&!it.opening&&it.error==null}
    private fun owns(session:Long,request:Long?=null)=!disposed&&current().taskProposals.editor?.let {
        it.session==session&&(request==null||it.request==request)}==true
    private fun update(session:Long,change:(TaskProposalEditor)->TaskProposalEditor)=publish {local ->
        val e=local.taskProposals.editor
        if(disposed||e?.session!=session) local else local.copy(taskProposals=local.taskProposals.copy(editor=change(e)))
    }
    fun initialize(store:EntryRepository) {
        if(disposed) return
        val port=store.taskProposals
        if(port==null) {publish {it.copy(taskProposals=TaskProposalsUiState())};return}
        load(port)
    }
    private fun load(port:TaskProposalRepository) {
        try {val items=port.list();if(!disposed) publish {it.copy(taskProposals=it.taskProposals.copy(supported=true,
            available=true,loading=false,items=items,error=null))}}
        catch(c:CancellationException) {throw c}
        catch(_:Exception) {if(!disposed) publish {it.copy(taskProposals=it.taskProposals.copy(supported=true,available=false,
            loading=false,items=emptyList(),error="待确认草稿暂不可读取，已保留加密数据，请重试。"))}}
    }
    fun refresh() {
        if(!ready()||!current().taskProposals.supported) return
        val token=reads.incrementAndGet()
        publish {it.copy(taskProposals=it.taskProposals.copy(loading=true))}
        if(!enqueue {store -> if(!disposed&&reads.get()==token) load(checkNotNull(store.taskProposals))})
            publish {it.copy(taskProposals=it.taskProposals.copy(loading=false,error="本机队列暂不可用，请重试。"))}
    }
    fun open(binding:TaskSourceBinding):Boolean {
        val local=current()
        if(!ready()||!local.taskProposals.supported||local.taskProposals.editor!=null||local.sourceTask!=null||
            local.originalEdit!=null||local.originalHistory!=null||local.recycleBin!=null||local.recordPackage!=null||
            local.localProcessing!=null||!local.canUseTaskSource(binding)) return false
        val now=clock.millis();val zone=ZoneId.systemDefault().id
        val e=TaskProposalEditor(sessions.incrementAndGet(),UUID.randomUUID().toString(),binding,now,zone,date=ManualTaskDateInput(timeZone=zone))
        publish {it.copy(taskProposals=it.taskProposals.copy(editor=e))};save(e.session);return true
    }
    fun openExisting(id:String,version:Long) {
        val local=current();if(!ready()||!local.taskProposals.available||local.taskProposals.editor!=null) return
        val p=local.taskProposals.items.singleOrNull {it.proposal.id==id&&it.proposal.version==version}?.proposal ?: return
        if(p.state!=TaskProposalState.DRAFT) return
        val e=TaskProposalEditor(sessions.incrementAndGet(),p.id,p.binding,p.createdAtMillis,p.creationTimeZone,p.titleInput,p.dateInput,p,0,0)
        publish {it.copy(taskProposals=it.taskProposals.copy(editor=e))}
    }
    private fun edit(session:Long,change:(TaskProposalEditor)->TaskProposalEditor) {
        val e=current().taskProposals.editor ?: return
        if(!owns(session)||!ready()||!current().canUseProposalSource(e)||e.confirmation!=null||e.closing||e.notice!=null||
            e.conflict||e.pending!=null&&!e.busy||e.pending is ProposalPending.Confirm||e.saved?.state==TaskProposalState.REJECTED) return
        if((e.pending as? ProposalPending.Write)?.request?.action==TaskProposalAction.REJECT) return
        val edited=change(e)
        if(edited.title==e.title&&edited.date==e.date) return
        update(session) {change(it).copy(epoch=it.epoch+1,error=null)};save(session)
    }
    fun editTitle(session:Long,title:String) {if(title.length<=ManualTasks.MAX_RAW_LENGTH) edit(session) {it.copy(title=title)}}
    fun editDate(session:Long,date:ManualTaskDateInput)=edit(session) {it.copy(date=date)}
    private fun save(session:Long) {
        val e=current().taskProposals.editor ?: return
        if(!owns(session)||e.pending!=null||e.busy||e.savedEpoch==e.epoch) return
        try {
            val r=TaskProposalWrite(e.id,e.saved?.version ?: 0,e.binding,e.title,e.date,e.created,e.zone,
                maxOf(clock.millis(),e.saved?.updatedAtMillis ?: e.created),UUID.randomUUID().toString())
            execute(session,ProposalPending.Write(r,e.epoch))
        } catch(_:IllegalArgumentException) {update(session) {it.copy(closing=false,error="当前输入尚不能完整加密保存，输入已保留，请修正后重试。")}}
    }
    fun prepare(session:Long) {
        val e=current().taskProposals.editor ?: return;val p=e.saved ?: return
        if(!owns(session)||!ready()||!current().canUseProposalSource(e)||e.busy||e.pending!=null||e.savedEpoch!=e.epoch||
            p.state!=TaskProposalState.DRAFT||e.notice!=null||e.closing||e.conflict) return
        try {val r=TaskProposalConfirm(p,maxOf(clock.millis(),p.updatedAtMillis),UUID.randomUUID().toString())
            update(session) {it.copy(confirmation=r,error=null)}}
        catch(_:IllegalArgumentException) {update(session) {it.copy(error="请填写有效标题与明确的日期、时刻、时区和重复时刻选项；未推断时间。")}}
    }
    fun cancel(session:Long,expected:TaskProposalConfirm) {
        if(owns(session)) update(session) {if(it.confirmation!==expected||it.busy) it else it.copy(confirmation=null)}
    }
    fun confirm(session:Long,expected:TaskProposalConfirm) {
        val e=current().taskProposals.editor ?: return
        if(!owns(session)||!ready()||!current().canUseProposalSource(e)||e.confirmation!==expected||e.pending!=null||e.busy||e.closing) return
        execute(session,ProposalPending.Confirm(expected))
    }
    fun reject(session:Long) {
        val e=current().taskProposals.editor ?: return;val p=e.saved ?: return
        if(!owns(session)||!ready()||e.pending!=null||e.busy||p.state!=TaskProposalState.DRAFT||e.savedEpoch!=e.epoch||e.closing) return
        val r=TaskProposalWrite(p.id,p.version,p.binding,p.titleInput,p.dateInput,p.createdAtMillis,p.creationTimeZone,
            maxOf(clock.millis(),p.updatedAtMillis),UUID.randomUUID().toString(),TaskProposalAction.REJECT)
        execute(session,ProposalPending.Write(r,e.epoch))
    }
    fun retry(session:Long) {
        val e=current().taskProposals.editor ?: return
        if(!owns(session)||!ready()||e.busy) return
        val pending=e.pending
        if(pending!=null) execute(session,pending) else {update(session) {it.copy(error=null)};save(session)}
    }
    fun invalidateSource(id:String) {
        val e=current().taskProposals.editor ?: return
        if(e.binding.source.sourceId==id) update(e.session) {it.copy(confirmation=null,retired=true)}
        // Source validity is also checked directly on every composition and SQL write.
    }
    fun close(session:Long) {
        val e=current().taskProposals.editor ?: return;if(!owns(session)) return
        if(e.busy) {update(session) {it.copy(closing=true,confirmation=null)};return}
        if(e.epoch!=e.savedEpoch&&e.error==null&&e.notice==null) {
            update(session) {it.copy(closing=true,confirmation=null)};save(session);return
        }
        publish {it.copy(taskProposals=it.taskProposals.copy(editor=null))};refresh();afterClose()
    }
    private fun execute(session:Long,op:ProposalPending) {
        val before=current().taskProposals.editor ?: return;val request=before.request+1
        update(session) {it.copy(request=request,pending=op,busy=true,confirmation=null,error=null,conflict=false,notice=null)}
        if(!enqueue {store ->
            if(!owns(session,request)) return@enqueue
            try {
                val port=checkNotNull(store.taskProposals)
                val saved=when(op) {is ProposalPending.Write -> port.write(op.request);is ProposalPending.Confirm -> {port.confirm(op.request);null}}
                val all=port.list()
                val tasks=if(op is ProposalPending.Confirm) checkNotNull(store.manualTasks).snapshots().also {items ->
                    check(items.singleOrNull {it.id==op.request.task.id}?.sourceReceipt==TaskProposals.receipt(op.request))} else null
                if(owns(session,request)) publish {local ->
                    val e=local.taskProposals.editor!!
                    val next=e.copy(saved=saved ?: e.saved,savedEpoch=if(op is ProposalPending.Write) op.epoch else e.savedEpoch,
                        busy=false,pending=null,error=null,notice=when {
                            op is ProposalPending.Confirm -> "已明确确认为正式应用内待办，没有系统提醒。"
                            saved?.state==TaskProposalState.REJECTED -> "草稿已拒绝，没有创建正式待办。"
                            else -> null})
                    local.copy(taskProposals=local.taskProposals.copy(items=all,available=true,loading=false,error=null,editor=next),
                        tasks=if(tasks!=null&&local.tasks.pending==null&&!local.tasks.busy&&!local.tasks.loading)
                            local.tasks.copy(items=tasks,available=true,error=null) else local.tasks)
                }
                if(owns(session,request)) {
                    val e=current().taskProposals.editor!!
                    if(e.closing) close(session) else if(e.notice==null&&e.savedEpoch!=e.epoch) save(session)
                }
            } catch(c:CancellationException) {throw c}
            catch(f:Exception) {if(owns(session,request)) {
                val conflict=f is TaskProposalConflict
                publish {it.copy(taskProposals=it.taskProposals.copy(available=false,items=emptyList()))}
                update(session) {it.copy(busy=false,closing=false,confirmation=null,pending=if(conflict) null else it.pending,conflict=conflict,
                    error=if(conflict) "草稿或来源已变化，未覆盖其他版本。输入已保留，请关闭并从清单重新核对。"
                        else if(f is TaskProposalLimit) "待确认草稿已达256条上限，未新增草稿；可先确认或拒绝已有草稿。"
                        else "保存或读回状态待确认，未显示成功。请重试同一请求，或关闭后从清单重新核对。")}
            }}
        }) update(session) {it.copy(busy=false,closing=false,error="本机队列暂不可用，请重试同一请求。")}
    }
    fun dispose() {disposed=true;reads.incrementAndGet()}
}
