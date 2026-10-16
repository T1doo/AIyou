package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

data class SourceTaskUiState(val session:Long,val binding:TaskSourceBinding,
    val preview:SourceTaskPreview?=null,val title:String="",val date:ManualTaskDateInput=ManualTaskDateInput(),
    val confirmation:SourceTaskCreate?=null,val pending:SourceTaskCreate?=null,val busy:Boolean=false,
    val request:Long=0,val error:String?=null,val notice:String?=null) {
    override fun toString()="SourceTaskUiState(redacted)"
}

/** UI authority is checked against both current plaintext and its processing generation. */
internal fun LocalRecordUiState.canUseTaskSource(binding:TaskSourceBinding):Boolean =
    ready&&!opening&&error==null&&hasLocalProcessingProof(binding.source)&&
        localProcessingVersions[binding.source.sourceId]?.generation==binding.generation&&
        entries.singleOrNull { it.id==binding.source.sourceId }?.let {
            it.currentRevision.toLong()==binding.source.revision&&
                it.policy in listOf(ProcessingPolicy.AI_NONE,ProcessingPolicy.LOCAL_ONLY)&&
                OriginalExports.hash(it.originalText)==binding.sourceHash }==true

/** A manual literal-source form; it never extracts intent, infers a date, calls AI or schedules. */
class SourceTaskController(private val current:()->LocalRecordUiState,
    private val publish:((LocalRecordUiState)->LocalRecordUiState)->Unit,
    private val enqueue:(suspend(EntryRepository)->Unit)->Boolean,private val clock:Clock,
    private val afterClose:()->Unit = {},private val proposalOpen:(TaskSourceBinding)->Unit = {}) {
    private val sessions=AtomicLong()
    @Volatile private var disposed=false
    private fun usable()=!disposed && current().let { it.ready&&!it.opening&&it.error==null }
    private fun owns(session:Long,request:Long?=null)=usable() && current().sourceTask?.let {
        it.session==session&&(request==null||it.request==request) }==true
    private fun update(session:Long,change:(SourceTaskUiState)->SourceTaskUiState)=publish {
        if(disposed||it.sourceTask?.session!=session) it else it.copy(sourceTask=change(it.sourceTask)) }
    fun openOriginal(id:String,revision:Int) {
        val local=current();val entry=local.entries.singleOrNull { it.id==id&&it.currentRevision==revision } ?: return
        val points=minOf(320,entry.originalText.codePointCount(0,entry.originalText.length))
        if(points==0) return
        val version=local.localProcessingVersions[id] ?: return
        open(TaskSourceBinding(SourceVersion(id,revision.toLong()),OriginalExports.hash(entry.originalText),version.generation,0,points))
    }
    fun open(binding:TaskSourceBinding) {
        if(disposed) return
        val local=current()
        if(local.taskProposals.supported) {proposalOpen(binding);return}
        if(!usable()||!local.supportsSourceTasks||local.sourceTask!=null||local.originalEdit!=null||local.originalHistory!=null||
            local.recycleBin!=null||local.recordPackage!=null||local.localProcessing!=null||local.saving||local.savePending||local.tasks.busy||local.tasks.pending!=null) return
        val entry=local.entries.singleOrNull { it.id==binding.source.sourceId&&it.currentRevision.toLong()==binding.source.revision } ?: return
        if(entry.policy !in listOf(ProcessingPolicy.AI_NONE,ProcessingPolicy.LOCAL_ONLY)||OriginalExports.hash(entry.originalText)!=binding.sourceHash||
            !local.hasLocalProcessingProof(binding.source)||local.localProcessingVersions[entry.id]?.generation!=binding.generation) return
        val ui=SourceTaskUiState(sessions.incrementAndGet(),binding,date=ManualTaskDateInput(timeZone=ZoneId.systemDefault().id))
        publish { it.copy(sourceTask=ui,taskSourceNotice=null) };execute(ui,null)
    }
    private fun editForm(session:Long,change:(SourceTaskUiState)->SourceTaskUiState) {
        val ui=current().sourceTask ?: return
        if(!owns(session)||!current().canUseTaskSource(ui.binding)||ui.busy||ui.pending!=null||ui.confirmation!=null||ui.preview?.existing!=null||ui.notice!=null) return
        update(session) { change(it).copy(error=if(it.preview==null) it.error else null,notice=null) }
    }
    fun editTitle(session:Long,title:String) {
        if(title.length<=ManualTasks.MAX_RAW_LENGTH) editForm(session) { it.copy(title=title) }
    }
    fun editDate(session:Long,date:ManualTaskDateInput) = editForm(session) { it.copy(date=date) }
    fun prepare(session:Long) {
        val ui=current().sourceTask ?: return;val p=ui.preview ?: return
        if(!owns(session)||!current().canUseTaskSource(ui.binding)||ui.busy||ui.pending!=null||p.existing!=null) return
        try {
            val task=ManualTaskCreate(SourceTasks.id(ui.binding),ui.title,clock.millis(),ZoneId.systemDefault().id,UUID.randomUUID().toString(),ui.date.parse())
            update(session) { it.copy(confirmation=SourceTaskCreate(p,task),error=null,notice=null) }
        } catch(_:ManualTaskDateInvalid) { update(session) { it.copy(error="请明确填写有效日期、时刻、时区和重复时刻选项；未推断时间。") } }
        catch(_:IllegalArgumentException) { update(session) { it.copy(error="请填写1–240个字符的待办标题，输入已保留。") } }
    }
    fun cancel(session:Long,expected:SourceTaskCreate) {
        if(owns(session)) update(session) { if(it.busy||it.confirmation!==expected) it else it.copy(confirmation=null) }
    }
    fun confirm(session:Long,expected:SourceTaskCreate) {
        val ui=current().sourceTask ?: return
        if(!owns(session)||!current().canUseTaskSource(ui.binding)||ui.busy||ui.pending!=null||ui.confirmation!==expected) return
        execute(ui,expected)
    }
    fun retry(session:Long,request:Long) {
        val ui=current().sourceTask ?: return
        if(owns(session,request)&&!ui.busy) execute(ui,ui.pending)
    }
    fun close(session:Long) {
        if(disposed||current().sourceTask?.session!=session) return
        publish { if(it.sourceTask?.session==session) it.copy(sourceTask=null) else it };afterClose()
    }
    fun invalidateSource(id:String) {
        val ui=current().sourceTask ?: return
        if(ui.binding.source.sourceId==id) update(ui.session) { it.copy(preview=null,confirmation=null,notice=null,request=it.request+1,busy=false,
            error="来源或处理范围已变化；输入已保留。未确认的旧片段不能创建新待办，请关闭后重新选择来源。") }
    }
    private fun execute(ui:SourceTaskUiState,mutation:SourceTaskCreate?) {
        val request=ui.request+1
        update(ui.session) { it.copy(busy=true,request=request,pending=mutation,confirmation=null,error=null,notice=null) }
        if(!enqueue { store ->
            if(!owns(ui.session,request)) return@enqueue
            try {
                val port=checkNotNull(store.sourceTasks)
                if(mutation==null) {
                    val p=port.preview(ui.binding)
                    if(owns(ui.session,request)) update(ui.session) { it.copy(preview=p,busy=false,pending=null,
                        notice=if(p.existing==null) null else "这个来源片段已有正式待办，请在待办页查看；不会重复创建。") }
                } else {
                    val saved=port.create(mutation)
                    val all=checkNotNull(store.manualTasks).snapshots()
                    val actual=all.singleOrNull { it.id==saved.id } ?: throw SourceTaskStorageFailure()
                    if(actual.sourceReceipt!=SourceTasks.receipt(mutation)) throw SourceTaskStorageFailure()
                    val fresh=try { port.preview(ui.binding) } catch(c:CancellationException) { throw c } catch(_:Exception) { null }
                    if(owns(ui.session,request)) publish { local -> if(!owns(ui.session,request)) local else local.copy(
                        tasks=if(local.tasks.pending==null&&!local.tasks.busy&&!local.tasks.loading) local.tasks.copy(items=all,available=true,error=null) else local.tasks,
                        sourceTask=local.sourceTask?.copy(preview=fresh,busy=false,pending=null,
                            error=null,notice=if(fresh==null) "正式待办已保存在本机；来源已变化、已删除或暂不可读取，未展示旧摘录。没有系统提醒。"
                                else "正式待办已保存在本机。没有系统提醒；原文和版本保持原样。")) }
                }
            } catch(c:CancellationException) { throw c }
            catch(f:Exception) {
                if(owns(ui.session,request)&&mutation!=null) publish { local ->
                    if(local.tasks.pending!=null||local.tasks.busy||local.tasks.loading) local else local.copy(tasks=local.tasks.copy(available=false,items=emptyList(),
                        error="来源待办保存状态待确认，请重新读取待办。")) }
                if(owns(ui.session,request)) update(ui.session) { it.copy(busy=false,preview=null,confirmation=null,notice=null,
                    pending=if(f is SourceTaskConflict) null else it.pending,
                    error=if(f is SourceTaskConflict) "来源、版本或确认请求已变化，未创建新的待办。输入已保留，请重新选择来源。"
                        else "待办读取或保存状态待确认。请重试核对；关闭后可从相同片段重新读取，避免重复创建。") }
            }
        }) update(ui.session) { it.copy(busy=false,error="本机读取队列暂不可用，请重试核对。") }
    }
    fun locate(id:String,version:Long,onLocate:(TaskSourceBinding)->Unit) {
        if(!usable()||current().tasks.items.none { it.id==id&&it.version==version&&it.sourceReceipt!=null }) return
        publish { it.copy(taskSourceNotice=null) }
        enqueue { store ->
            if(!usable()) return@enqueue
            try { checkNotNull(store.sourceTasks).locate(id,version) { if(usable()) onLocate(it) } }
            catch(c:CancellationException) { throw c }
            catch(_:Exception) { if(usable()) publish { it.copy(taskSourceNotice="来源版本已变化、已删除或暂不可读取；待办保留，未跳转到其他正文。") } }
        }
    }
    fun dispose() { disposed=true }
}
