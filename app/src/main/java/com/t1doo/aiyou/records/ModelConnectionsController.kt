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
sealed interface ModelConnectionSecretMutation {
    class Put internal constructor(internal val prepared: PreparedConnectionSecret) : ModelConnectionSecretMutation {
        override fun toString() = "PutConnectionSecret(redacted)"
    }
    class Revoke internal constructor(internal val expected: ModelConnectionConfiguration, internal val mutationId: String) : ModelConnectionSecretMutation {
        override fun toString() = "RevokeConnectionSecret(redacted)"
    }
}
data class ModelConnectionSecretEditor(val expected: ModelConnectionConfiguration,
    val busy: Boolean = false, val error: String? = null, val conflict: Boolean = false,
    val confirmRevoke: Boolean = false, internal val pending: ModelConnectionSecretMutation? = null) {
    override fun toString() = "ModelConnectionSecretEditor(version=${expected.version}, redacted)"
}
data class ModelConnectionsUiState(val session: Long = 0, val busy: Boolean = false,
    val configurations: List<ModelConnectionConfiguration> = emptyList(), val editor: ModelConnectionEditor? = null,
    val secretEditor: ModelConnectionSecretEditor? = null, val secretsAvailable: Boolean = false,
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
    @Volatile private var secretOwner: ConnectionSecretVault? = null
    private var retainedOwner: ModelConnectionCatalog? = null
    private var retainedEditor: ModelConnectionEditor? = null
    private var retainedSecretOwner: ConnectionSecretVault? = null
    private var retainedSecretEditor: ModelConnectionSecretEditor? = null
    private val inputLock = Any()
    private var queuedSecretInput: CharArray? = null
    val editingOrBusy: Boolean get() = active && (mutable.value.busy || mutable.value.editor != null || mutable.value.secretEditor != null)
    private fun live(session: Long): Boolean {
        val source=current()
        val owns = !disposed && active && owner!=null && source.modelConnectionOwner===owner && source.modelSecretOwner===secretOwner &&
            source.ready && !source.opening && source.error==null
        if(!owns) clearQueuedSecretInput()
        return owns && epochs.get()==session
    }
    fun visible(): ModelConnectionsUiState = mutable.value.let {
        if(live(it.session)) it else ModelConnectionsUiState(it.session,error="本机连接配置暂不可读取，请先完成本机资料读取。")
    }
    fun open(catalog: ModelConnectionCatalog?): Long {
        if(disposed) return epochs.get()
        val restored=retainedEditor.takeIf { catalog!=null && retainedOwner===catalog }
        val source=current()
        val vault=source.modelSecretOwner.takeIf { source.modelConnectionOwner===catalog }
        val restoredSecret=retainedSecretEditor.takeIf { catalog!=null && retainedOwner===catalog && vault!=null && retainedSecretOwner===vault }
        clearQueuedSecretInput()
        if(catalog!=null) { retainedOwner=null;retainedEditor=null;retainedSecretOwner=null;retainedSecretEditor=null }
        val session=epochs.incrementAndGet();active=true;owner=catalog
        secretOwner=vault
        mutable.value=ModelConnectionsUiState(session,busy=catalog!=null,editor=restored?.copy(),
            secretEditor=restoredSecret?.copy(),secretsAvailable=vault!=null)
        if(live(session)) load(session) else mutable.value=ModelConnectionsUiState(session,error="本机连接配置暂不可读取。")
        return session
    }
    fun close(session: Long, retainEditor: Boolean = false) {
        if(epochs.get()!=session) return
        val editor=mutable.value.editor
        val secret=mutable.value.secretEditor?.takeIf { it.pending!=null }
        retainedOwner=owner.takeIf { retainEditor && !disposed && (editor!=null || secret!=null) }
        retainedEditor=editor?.takeIf { retainEditor && !disposed }?.copy(busy=false,
            error=if(editor.pending!=null) "保存结果尚未确认，输入已保留；重试仅核对这一次修改。" else editor.error)
        retainedSecretOwner=secretOwner.takeIf { retainEditor && !disposed && secret!=null }
        retainedSecretEditor=secret?.takeIf { retainEditor && !disposed }?.copy(busy=false,
            error="写入结果尚未确认，输入已清空；重试只核对同一次密钥操作。")
        active=false;owner=null;secretOwner=null;epochs.incrementAndGet();clearQueuedSecretInput();mutable.value=ModelConnectionsUiState()
    }
    /** Explicit navigation retirement differs from configuration recreation. */
    fun leave() { retainedOwner=null;retainedEditor=null;retainedSecretOwner=null;retainedSecretEditor=null
        if(active)close(epochs.get());clearQueuedSecretInput() }
    fun dispose() { disposed=true;leave();owner=null;epochs.incrementAndGet();mutable.value=ModelConnectionsUiState() }
    private fun publish(session: Long, change: (ModelConnectionsUiState)->ModelConnectionsUiState) {
        mutable.update { if(live(session) && it.session==session) change(it) else it }
    }
    fun reload(session: Long) { if(live(session) && !mutable.value.busy && mutable.value.editor?.busy!=true && mutable.value.secretEditor?.busy!=true) load(session) }
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
        if(!live(session)||ui.busy||ui.error!=null||ui.editor!=null||ui.secretEditor!=null) return
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
    fun beginSecret(session: Long, rendered: ModelConnectionConfiguration) {
        val ui=mutable.value
        if(!live(session)||secretOwner==null||ui.busy||ui.error!=null||ui.editor!=null||ui.secretEditor!=null) return
        if(ui.configurations.none { it==rendered }) return
        publish(session){it.copy(secretEditor=ModelConnectionSecretEditor(rendered),notice=null)}
    }
    /** Also gates password-field callbacks retained by a retired composition. */
    fun canEditSecret(session: Long, editor: ModelConnectionSecretEditor): Boolean = live(session) && secretOwner!=null &&
        mutable.value.secretEditor===editor && !mutable.value.busy && mutable.value.error==null &&
        !editor.busy && editor.pending==null && !editor.conflict
    fun cancelSecret(session: Long, editor: ModelConnectionSecretEditor) {
        if(!live(session)||mutable.value.secretEditor!==editor||editor.busy) return
        publish(session){it.copy(secretEditor=null,notice=null)}
        if(editor.pending!=null||editor.conflict) load(session)
    }
    fun confirmSecretRevoke(session: Long, editor: ModelConnectionSecretEditor) {
        if(canEditSecret(session,editor)&&editor.expected.secretRef!=null)
            secretPublish(session,editor){it.copy(secretEditor=editor.copy(confirmRevoke=true,error=null))}
    }
    fun revokeSecret(session: Long, editor: ModelConnectionSecretEditor) {
        if(canEditSecret(session,editor)&&editor.confirmRevoke&&editor.expected.secretRef!=null)
            submitSecret(session,editor,ModelConnectionSecretMutation.Revoke(editor.expected,UUID.randomUUID().toString()))
    }
    fun retrySecret(session: Long, editor: ModelConnectionSecretEditor) {
        if(!live(session)||mutable.value.secretEditor!==editor||mutable.value.busy||mutable.value.error!=null||editor.busy||editor.conflict) return
        editor.pending?.let { submitSecret(session,editor,it) }
    }
    /** Takes ownership on every exit. Only a sealed intent survives the preparation command. */
    fun saveSecret(session: Long, editor: ModelConnectionSecretEditor, ownedCharacters: CharArray) {
        var tracked=false;var accepted=false
        try {
            if(!canEditSecret(session,editor)||editor.confirmRevoke) return
            if(ownedCharacters.size !in 1..2048 || ownedCharacters.any { it.code !in 33..126 }) {
                secretPublish(session,editor){it.copy(secretEditor=editor.copy(error="请输入不含空白的服务密钥，最多2048个可打印ASCII字符。"))};return
            }
            val preparing=editor.copy(busy=true,error=null)
            secretPublish(session,editor){it.copy(secretEditor=preparing,notice=null)}
            tracked=synchronized(inputLock) {
                if(!live(session)||mutable.value.secretEditor!==preparing) false
                else { check(queuedSecretInput==null);queuedSecretInput=ownedCharacters;true }
            }
            if(!tracked) return
            accepted=enqueue action@{ store ->
                // A short ownership handoff: retirement clears queued input, never a worker's input.
                val input=synchronized(inputLock) {
                    if(queuedSecretInput!==ownedCharacters) null else ownedCharacters.also { queuedSecretInput=null }
                }?:return@action
                val prepared=try {
                    if(!live(session)||mutable.value.secretEditor!==preparing) return@action
                    vault(store).prepare(editor.expected,UUID.randomUUID().toString(),input)
                } catch(cancelled: CancellationException) {
                    secretPreparationFailure(session,preparing,false);throw cancelled
                } catch(_: ModelConnectionConflict) {
                    secretPreparationFailure(session,preparing,true);return@action
                } catch(_: Exception) {
                    secretPreparationFailure(session,preparing,false);return@action
                } finally { input.fill('\u0000') }
                if(!live(session)||mutable.value.secretEditor!==preparing) return@action
                val pending=preparing.copy(pending=ModelConnectionSecretMutation.Put(prepared))
                secretPublish(session,preparing){it.copy(secretEditor=pending)}
                if(live(session)&&mutable.value.secretEditor===pending) performSecret(session,pending,store)
            }
            if(!accepted) secretPublish(session,preparing){it.copy(secretEditor=preparing.copy(busy=false,
                error="本机资料已关闭，密钥输入已清空；请重新打开设置。"))}
        } finally {
            if(!tracked) ownedCharacters.fill('\u0000')
            else if(!accepted) synchronized(inputLock) {
                if(queuedSecretInput===ownedCharacters) { queuedSecretInput=null;ownedCharacters.fill('\u0000') }
            }
        }
    }
    private fun secretPreparationFailure(session: Long, editor: ModelConnectionSecretEditor, conflict: Boolean) {
        secretPublish(session,editor){it.copy(secretEditor=editor.copy(busy=false,conflict=conflict,
            error=if(conflict) "配置版本已变化，密钥输入已清空。请关闭并重新读取，不会覆盖其他修改。"
                else "密钥尚未提交，输入已清空；请重新输入后保存。"))}
    }
    private fun submitSecret(session: Long, editor: ModelConnectionSecretEditor, mutation: ModelConnectionSecretMutation) {
        val pending=editor.copy(busy=true,error=null,pending=mutation)
        secretPublish(session,editor){it.copy(secretEditor=pending,notice=null)}
        if(!enqueue { store -> if(live(session)&&mutable.value.secretEditor===pending) performSecret(session,pending,store) })
            secretPublish(session,pending){it.copy(secretEditor=pending.copy(busy=false,
                error="本机资料已关闭；请重新打开设置后核对同一次密钥操作。"))}
    }
    private fun vault(store: EntryRepository): ConnectionSecretVault {
        val value=checkNotNull(secretOwner)
        check(store.modelConnections===owner && store.localModelConnections?.secrets===value)
        return value
    }
    private fun performSecret(session: Long, editor: ModelConnectionSecretEditor, store: EntryRepository) {
        try {
            val value=vault(store);val mutation=checkNotNull(editor.pending)
            val saved=when(mutation) {
                is ModelConnectionSecretMutation.Put -> value.apply(mutation.prepared)
                is ModelConnectionSecretMutation.Revoke -> value.revoke(mutation.expected,mutation.mutationId)
            }
            val values=checkNotNull(store.modelConnections).configurations()
            if(values.singleOrNull { it.id==editor.expected.id }!=saved) throw ModelConnectionConflict()
            secretPublish(session,editor){it.copy(configurations=values,secretEditor=null,error=null,
                notice=when {
                    mutation is ModelConnectionSecretMutation.Revoke -> "已撤销本机密钥引用；不会删除原始记录。"
                    editor.expected.secretRef!=null -> "已替换本机密钥引用；旧引用已撤销，能力未验证，尚不可调用。"
                    else -> "已加密保存本机密钥引用；能力未验证，尚不可调用。"
                })}
        } catch(cancelled: CancellationException) {
            secretWriteUncertain(session,editor);throw cancelled
        } catch(_: ModelConnectionConflict) {
            secretPublish(session,editor){it.copy(secretEditor=editor.copy(busy=false,conflict=true,
                error="配置版本已变化，本次操作不会覆盖其他修改。请关闭并重新读取。"))}
        } catch(_: Exception) { secretWriteUncertain(session,editor) }
    }
    private fun secretWriteUncertain(session: Long, editor: ModelConnectionSecretEditor) {
        secretPublish(session,editor){it.copy(secretEditor=editor.copy(busy=false,
            error="写入结果尚未确认，输入已清空；重试只核对同一次密钥操作。"))}
    }
    private fun secretPublish(session: Long, editor: ModelConnectionSecretEditor, change: (ModelConnectionsUiState)->ModelConnectionsUiState) {
        publish(session){if(it.secretEditor!==editor) it else change(it)}
    }
    private fun clearQueuedSecretInput() = synchronized(inputLock) {
        queuedSecretInput?.fill('\u0000');queuedSecretInput=null
    }
    private fun queue(session: Long, action: suspend (EntryRepository)->Unit) {
        if(!enqueue { store -> if(live(session)) action(store) }) publish(session){it.copy(busy=false,
            error="本机资料已关闭，请重新打开设置。",editor=it.editor?.copy(busy=false))}
    }
}
