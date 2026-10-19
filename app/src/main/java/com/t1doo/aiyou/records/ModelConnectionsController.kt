package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ModelConnectionForm(val name: String = "", val apiRoot: String = "",
    val protocol: ConnectionProtocol = ConnectionProtocol.CHAT_COMPLETIONS, val modelId: String = "",
    val purposes: Set<ModelConfigurationPurpose> = setOf(ModelConfigurationPurpose.CONVERSATION, ModelConfigurationPurpose.ORGANIZATION)) {
    fun details() = ModelConnectionDetails(name,apiRoot,protocol,modelId,purposes)
    override fun toString() = "ModelConnectionForm(redacted)"
}
data class ModelConnectionEditor(val id: String, val version: Long, val form: ModelConnectionForm,
    val busy: Boolean = false, val error: String? = null, val conflict: Boolean = false,
    val confirmDelete: Boolean = false, internal val pending: ModelConnectionChange? = null) {
    override fun toString() = "ModelConnectionEditor(version=$version, redacted)"
}
data class ModelConnectionsUiState(val session: Long = 0, val busy: Boolean = false,
    val configurations: List<ModelConnectionConfiguration> = emptyList(), val editor: ModelConnectionEditor? = null,
    val error: String? = null, val notice: String? = null) {
    override fun toString() = "ModelConnectionsUiState(busy=$busy, redacted)"
}

/** Settings session only; all durable operations borrow the existing records IO command owner. */
class ModelConnectionsController internal constructor(private val current: () -> LocalRecordUiState,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean) {
    private val epochs = AtomicLong()
    private val mutable = MutableStateFlow(ModelConnectionsUiState())
    val state = mutable.asStateFlow()
    @Volatile private var active = false
    @Volatile private var disposed = false
    @Volatile private var owner: ModelConnectionCatalog? = null
    private var retainedOwner: ModelConnectionCatalog? = null
    private var retainedEditor: ModelConnectionEditor? = null
    val editingOrBusy: Boolean get() = active && (mutable.value.busy || mutable.value.editor != null)
    private fun live(session: Long): Boolean {
        val source=current()
        return !disposed && active && epochs.get()==session && owner!=null && source.modelConnectionOwner===owner &&
            source.ready && !source.opening && source.error==null
    }
    fun visible(): ModelConnectionsUiState = mutable.value.let {
        if(live(it.session)) it else ModelConnectionsUiState(it.session,error="本机连接配置暂不可读取，请先完成本机资料读取。")
    }
    fun open(catalog: ModelConnectionCatalog?): Long {
        if(disposed) return epochs.get()
        val restored=retainedEditor.takeIf { catalog!=null && retainedOwner===catalog }
        if(catalog!=null) { retainedOwner=null;retainedEditor=null }
        val session=epochs.incrementAndGet();active=true;owner=catalog
        mutable.value=ModelConnectionsUiState(session,busy=catalog!=null,editor=restored?.copy())
        if(live(session)) load(session) else mutable.value=ModelConnectionsUiState(session,error="本机连接配置暂不可读取。")
        return session
    }
    fun close(session: Long, retainEditor: Boolean = false) {
        if(epochs.get()!=session) return
        val editor=mutable.value.editor
        retainedOwner=owner.takeIf { retainEditor && !disposed && editor!=null }
        retainedEditor=editor?.takeIf { retainEditor && !disposed }?.copy(busy=false,
            error=if(editor.pending!=null) "保存结果尚未确认，输入已保留；重试仅核对这一次修改。" else editor.error)
        active=false;owner=null;epochs.incrementAndGet();mutable.value=ModelConnectionsUiState()
    }
    /** Explicit navigation retirement differs from configuration recreation. */
    fun leave() { retainedOwner=null;retainedEditor=null;if(active)close(epochs.get()) }
    fun dispose() { disposed=true;leave();owner=null;epochs.incrementAndGet();mutable.value=ModelConnectionsUiState() }
    private fun publish(session: Long, change: (ModelConnectionsUiState)->ModelConnectionsUiState) {
        mutable.update { if(live(session) && it.session==session) change(it) else it }
    }
    fun reload(session: Long) { if(live(session) && !mutable.value.busy && mutable.value.editor?.busy!=true) load(session) }
    private fun load(session: Long) {
        publish(session){it.copy(busy=true,error=null,notice=null)}
        queue(session) { store ->
            try { val catalog=checkNotNull(store.modelConnections);check(catalog===owner)
                val values=catalog.configurations()
                publish(session){it.copy(busy=false,configurations=values,error=null)}
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { publish(session){it.copy(busy=false,error="连接配置读取失败，原数据保留，请重试读取。",configurations=emptyList())} }
        }
    }
    fun begin(session: Long, rendered: ModelConnectionConfiguration? = null) {
        val ui=mutable.value
        if(!live(session)||ui.busy||ui.error!=null||ui.editor!=null) return
        if(rendered!=null && ui.configurations.none { it==rendered }) return
        if(rendered==null && ui.configurations.size>=32) { publish(session){it.copy(notice="最多保存32个使用中的连接配置。")} ;return }
        val d=rendered?.details
        publish(session){it.copy(editor=ModelConnectionEditor(rendered?.id?:UUID.randomUUID().toString(),rendered?.version?:0,
            if(d==null) ModelConnectionForm() else ModelConnectionForm(d.name,d.apiRoot,d.protocol,d.modelId,d.purposes)),notice=null)}
    }
    private fun editable(session: Long, editor: ModelConnectionEditor) = live(session) && mutable.value.editor===editor &&
        !mutable.value.busy && mutable.value.error==null && !editor.busy && editor.pending==null && !editor.conflict
    fun edit(session: Long, editor: ModelConnectionEditor, form: ModelConnectionForm) {
        if(!editable(session,editor)) return
        if(form.name.length>160||form.apiRoot.length>4096||form.modelId.length>512) {
            publish(session){it.copy(editor=editor.copy(error="输入过长，请缩短后再保存。"))};return
        }
        publish(session){it.copy(editor=editor.copy(form=form,error=null,confirmDelete=false))}
    }
    fun cancel(session: Long, editor: ModelConnectionEditor) {
        if(live(session)&&mutable.value.editor===editor&&!editor.busy) {
            publish(session){it.copy(editor=null,notice=null)}
            if(editor.pending!=null||editor.conflict) load(session)
        }
    }
    fun confirmDelete(session: Long, editor: ModelConnectionEditor) {
        if(editable(session,editor)&&editor.version>0) publish(session){it.copy(editor=editor.copy(confirmDelete=true,error=null))}
    }
    fun save(session: Long, editor: ModelConnectionEditor) {
        if(!editable(session,editor)||editor.confirmDelete) return
        val details=try { editor.form.details() } catch(_: Exception) {
            publish(session){it.copy(editor=editor.copy(error="请填写名称、HTTPS API根路径、模型ID及计划用途；根路径不能含凭据、查询参数或最终操作名。"))};return
        }
        submit(session,editor,ModelConnectionChange(editor.id,editor.version,UUID.randomUUID().toString(),details))
    }
    fun delete(session: Long, editor: ModelConnectionEditor) {
        if(editable(session,editor)&&editor.confirmDelete&&editor.version>0)
            submit(session,editor,ModelConnectionChange(editor.id,editor.version,UUID.randomUUID().toString(),null))
    }
    fun retry(session: Long, editor: ModelConnectionEditor) {
        if(!live(session)||mutable.value.busy||mutable.value.error!=null||mutable.value.editor!==editor||editor.busy||editor.conflict) return
        editor.pending?.let { submit(session,editor,it) }
    }
    private fun submit(session: Long, editor: ModelConnectionEditor, request: ModelConnectionChange) {
        val pending=editor.copy(busy=true,error=null,pending=request)
        publish(session){it.copy(editor=pending,notice=null)}
        queue(session) { store ->
            if(mutable.value.editor!==pending) return@queue
            try {
                val catalog=checkNotNull(store.modelConnections);check(catalog===owner)
                val saved=catalog.change(request);val values=catalog.configurations()
                if(saved==null) { if(values.any { it.id==request.id }) throw ModelConnectionConflict() }
                else if(values.singleOrNull { it.id==saved.id }!=saved) throw ModelConnectionConflict()
                publish(session){if(it.editor!==pending) it else it.copy(configurations=values,editor=null,error=null,
                    notice=if(saved==null) "已删除本机配置。" else "已保存本机配置；未设置密钥，尚不可调用。")}
            } catch(e: CancellationException) { throw e }
            catch(_: ModelConnectionConflict) { publish(session){if(it.editor!==pending) it else it.copy(editor=pending.copy(busy=false,conflict=true,
                error="配置版本已变化，输入已保留。请关闭编辑并重新读取，不会覆盖其他修改。"))} }
            catch(_: ModelConnectionLimit) { publish(session){if(it.editor!==pending) it else it.copy(editor=pending.copy(busy=false,conflict=true,
                error="连接配置或保留的删除身份已达上限；没有清理历史数据，输入已保留。"))} }
            catch(_: Exception) { publish(session){if(it.editor!==pending) it else it.copy(editor=pending.copy(busy=false,
                error="保存结果尚未确认，输入已保留；重试仅核对这一次修改。"))} }
        }
    }
    private fun queue(session: Long, action: suspend (EntryRepository)->Unit) {
        if(!enqueue { store -> if(live(session)) action(store) }) publish(session){it.copy(busy=false,
            error="本机资料已关闭，请重新打开设置。",editor=it.editor?.copy(busy=false))}
    }
}
