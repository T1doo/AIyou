package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

sealed interface SourceMemoryIntent {
    class Create(val request:SourceMemoryCreate):SourceMemoryIntent { override fun toString()="MemoryCreateIntent(redacted)" }
    class Mutate(val request:SourceMemoryMutation):SourceMemoryIntent { override fun toString()="MemoryMutationIntent(redacted)" }
}
data class SourceMemoryConfirmation(val token:Long,val intent:SourceMemoryIntent) { override fun toString()="MemoryConfirmation(redacted)" }
data class SourceMemoryUiState(val session:Long,val source:SourceVersion,val sourceHash:String,
    val preview:SourceMemoryPreview?=null,val memories:List<SourceMemorySnapshot> = emptyList(),
    val quote:String="",val kind:SourceMemoryKind=SourceMemoryKind.EVENT,val assertion:SourceMemoryAssertion=SourceMemoryAssertion.UNCERTAIN,
    val note:String="",val editing:SourceMemorySnapshot?=null,val historyId:String?=null,
    val busy:Boolean=false,val error:String?=null,val notice:String?=null,
    val confirmation:SourceMemoryConfirmation?=null,val pending:SourceMemoryIntent?=null,val request:Long=0) {
    override fun toString()="SourceMemoryUiState(redacted)"
}

/** All form and confirmation authority is memory-only; stored review is authenticated IO. */
class SourceMemoryController(private val current:()->LocalRecordUiState,
    private val publish:((LocalRecordUiState)->LocalRecordUiState)->Unit,
    private val enqueue:(suspend(EntryRepository)->Unit)->Boolean,private val clock:Clock) {
    private val sessions=AtomicLong();private val tokens=AtomicLong()
    @Volatile private var disposed=false
    private fun owns(session:Long,request:Long?=null):Boolean {
        val local=current();val ui=local.sourceMemories ?: return false
        return !disposed && ui.session==session && (request==null || ui.request==request) && local.ready && !local.opening && local.error==null && local.hasLocalProcessingProof(ui.source) &&
            local.entries.any { it.id==ui.source.sourceId && it.currentRevision.toLong()==ui.source.revision && it.policy!=ProcessingPolicy.AI_NONE && OriginalExports.hash(it.originalText)==ui.sourceHash }
    }
    private fun update(session:Long,change:(SourceMemoryUiState)->SourceMemoryUiState)=publish { local ->
        if(!owns(session)) local else local.copy(sourceMemories=change(checkNotNull(local.sourceMemories)))
    }
    fun open(id:String,revision:Int) {
        val local=current()
        if(disposed || !local.ready || local.opening || local.error!=null || !local.supportsSourceMemories || local.saving || local.savePending ||
            local.sourceMemories!=null || local.sourceTags!=null || local.candidates!=null || local.manualTagEditor!=null || local.recordPackage!=null || local.recycleBin!=null) return
        if(!local.hasLocalProcessingProof(SourceVersion(id,revision.toLong())) || local.localProcessing!=null) return
        val entry=local.entries.singleOrNull { it.id==id && it.currentRevision==revision && it.policy!=ProcessingPolicy.AI_NONE } ?: return
        val ui=SourceMemoryUiState(sessions.incrementAndGet(),SourceVersion(id,revision.toLong()),OriginalExports.hash(entry.originalText))
        publish { it.copy(sourceMemories=ui) };execute(ui,null)
    }
    fun close(session:Long) { if(!disposed) publish { if(it.sourceMemories?.session==session) it.copy(sourceMemories=null) else it } }
    fun edit(session:Long,quote:String,kind:SourceMemoryKind,assertion:SourceMemoryAssertion,note:String) {
        val ui=current().sourceMemories ?: return
        if(!owns(session) || ui.busy || ui.pending!=null || ui.confirmation!=null || quote.length>1280 || note.length>800) return
        update(session) { it.copy(quote=quote,kind=kind,assertion=assertion,note=note,error=null,notice=null) }
    }
    fun beginCorrection(session:Long,id:String) {
        val ui=current().sourceMemories ?: return
        if(!owns(session) || ui.busy || ui.pending!=null || ui.confirmation!=null || ui.preview==null) return
        val item=ui.memories.singleOrNull { it.id==id && it.state==SourceMemoryState.ACTIVE } ?: return
        if(item.versions.size>=SourceMemoryRules.MAX_VERSIONS) return
        val content=checkNotNull(item.current).content
        update(session) { it.copy(editing=item,quote=content.reference.quote,kind=content.kind,assertion=content.assertion,note=content.note,error=null,notice=null) }
    }
    fun cancelCorrection(session:Long) {
        val ui=current().sourceMemories ?: return
        if(owns(session) && !ui.busy && ui.pending==null && ui.confirmation==null) update(session) { it.copy(editing=null,note="",error=null,notice=null) }
    }
    fun prepareSave(session:Long) {
        val ui=current().sourceMemories ?: return;val p=ui.preview ?: return
        if(!owns(session) || ui.busy || ui.pending!=null || ui.confirmation!=null) return
        try {
            val content=SourceMemoryContent(SourceMemoryRules.reference(p,ui.quote),ui.kind,ui.assertion,ui.note)
            val editing=ui.editing
            val intent=if(editing==null) SourceMemoryIntent.Create(SourceMemoryCreate(UUID.randomUUID().toString(),p,content,UUID.randomUUID().toString(),clock.millis()))
            else {
                if(ui.memories.singleOrNull { it.id==editing.id }?.version!=editing.version || content==editing.current?.content) throw SourceMemoryConflict()
                SourceMemoryIntent.Mutate(mutation(editing,SourceMemoryAction.CORRECT,content))
            }
            update(session) { it.copy(confirmation=SourceMemoryConfirmation(tokens.incrementAndGet(),intent),error=null,notice=null) }
        } catch(_:Exception) {
            update(session) { it.copy(error="请选择当前原文中唯一匹配、最多320个字符的摘录；备注最多200个字符。纠正需有变化，过期编辑请取消后重新打开；输入已保留。") }
        }
    }
    fun prepareAction(session:Long,id:String,action:SourceMemoryAction) {
        val ui=current().sourceMemories ?: return
        if(!owns(session) || ui.busy || ui.pending!=null || ui.confirmation!=null || ui.editing!=null || action==SourceMemoryAction.CORRECT) return
        val item=ui.memories.singleOrNull { it.id==id && it.state==SourceMemoryState.ACTIVE } ?: return
        try { SourceMemoryRules.decision(checkNotNull(item.current).review,action)
            update(session) { it.copy(confirmation=SourceMemoryConfirmation(tokens.incrementAndGet(),SourceMemoryIntent.Mutate(mutation(item,action,null))),error=null,notice=null) }
        } catch(_:IllegalArgumentException) { }
    }
    private fun mutation(item:SourceMemorySnapshot,action:SourceMemoryAction,content:SourceMemoryContent?)=SourceMemoryMutation(item.id,item.source,item.sourceHash,
        item.generation,item.version,checkNotNull(item.current).revision,item.current!!.review,action,content,UUID.randomUUID().toString(),clock.millis())
    fun cancelConfirmation(session:Long,expected:SourceMemoryConfirmation) {
        if(owns(session)) update(session) { if(it.busy || it.confirmation!=expected) it else it.copy(confirmation=null) }
    }
    fun confirm(session:Long,expected:SourceMemoryConfirmation) {
        val ui=current().sourceMemories ?: return
        if(!owns(session) || ui.busy || ui.pending!=null || ui.confirmation!=expected) return
        execute(ui,expected.intent)
    }
    fun retry(session:Long,request:Long) {
        val ui=current().sourceMemories ?: return
        if(owns(session,request) && !ui.busy) execute(ui,ui.pending)
    }
    fun history(session:Long,id:String) {
        val ui=current().sourceMemories ?: return
        if(owns(session) && !ui.busy && ui.memories.any { it.id==id }) update(session) { it.copy(historyId=if(it.historyId==id) null else id) }
    }
    fun reference(session:Long,id:String):OriginalTextReference? {
        val ui=current().sourceMemories ?: return null
        return if(owns(session) && !ui.busy && ui.pending==null) ui.memories.singleOrNull { it.id==id && it.state==SourceMemoryState.ACTIVE }?.current?.content?.reference else null
    }
    private fun execute(ui:SourceMemoryUiState,intent:SourceMemoryIntent?) {
        val next=ui.request+1;update(ui.session) { it.copy(busy=true,error=null,notice=null,pending=intent,request=next) }
        if(!enqueue { store ->
            if(!owns(ui.session,next)) return@enqueue
            try {
                val repo=checkNotNull(store.sourceMemories)
                val saved=when(intent) { is SourceMemoryIntent.Create -> repo.create(intent.request);is SourceMemoryIntent.Mutate -> repo.mutate(intent.request);null -> null }
                val preview=repo.preview(ui.source);val all=repo.memories(ui.source)
                if(preview.sourceHash!=ui.sourceHash) throw SourceMemoryConflict()
                if(saved!=null) check(all.singleOrNull { it.id==saved.id }?.let { it.version==saved.version && it.versions==saved.versions && it.state==saved.state }==true)
                val savedForm=intent is SourceMemoryIntent.Create || intent is SourceMemoryIntent.Mutate && intent.request.action==SourceMemoryAction.CORRECT
                val quote=if(ui.quote.isEmpty()) preview.original.let { it.substring(0,it.offsetByCodePoints(0,minOf(320,it.codePointCount(0,it.length)))) } else ui.quote
                if(owns(ui.session,next)) update(ui.session) { latest -> if(latest.request!=next) latest else latest.copy(preview=preview,memories=all,busy=false,
                    pending=null,confirmation=null,quote=quote,editing=if(savedForm) null else latest.editing,note=if(savedForm) "" else latest.note,
                    error=null,notice=if(saved==null) null else "候选与人工审阅已保存在本机；分类和备注不代表事实核验或诊断。") }
            } catch(c:CancellationException) { throw c }
            catch(e:Exception) { failed(ui.session,next,e) }
        }) failed(ui.session,next,SourceMemoryStorageFailure())
    }
    private fun failed(session:Long,request:Long,failure:Exception) {
        if(!owns(session,request)) return
        val terminal=failure is SourceMemoryConflict || failure is InvalidSourceMemory || failure is SourceMemoryLimit
        val error=when(failure) {
            is SourceMemoryConflict -> "来源或审阅已变化，请重新读取后操作；旧请求不会覆盖新结果。输入已保留。"
            is InvalidSourceMemory -> "摘录必须逐字来自当前原文。输入已保留，请核对来源。"
            is SourceMemoryLimit -> "当前原文最多20条有效候选、每条最多8个内容版本；仍可拒绝或撤回采纳。"
            else -> "读取或保存状态待确认，请重试核对；输入已保留，尚未显示保存成功。"
        }
        update(session) { if(it.request!=request) it else it.copy(preview=null,memories=emptyList(),busy=false,confirmation=null,notice=null,
            pending=if(terminal) null else it.pending,error=error) }
    }
    fun dispose() { disposed=true }
}
