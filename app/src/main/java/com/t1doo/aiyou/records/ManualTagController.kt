package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

class ManualTagEditor(val session: Long, val entryId: String, val revision: Int,
    val snapshot: ManualTagSnapshot? = null, val input: String = "", val busy: Boolean = false,
    val error: String? = null, val notice: String? = null, val conflict: Boolean = false,
    internal val pending: ManualTagMutation? = null, internal val adding: Boolean = false) {
    fun copy(snapshot: ManualTagSnapshot? = this.snapshot, input: String = this.input, busy: Boolean = this.busy,
        error: String? = this.error, notice: String? = this.notice, conflict: Boolean = this.conflict,
        pending: ManualTagMutation? = this.pending, adding: Boolean = this.adding) =
        ManualTagEditor(session, entryId, revision, snapshot, input, busy, error, notice, conflict, pending, adding)
    override fun toString() = "ManualTagEditor(busy=$busy, redacted)"
}

/** UI memory only; durable labels live exclusively in the borrowed encrypted repository. */
class ManualTagController internal constructor(
    private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean,
) {
    private val sessions = AtomicLong()
    @Volatile private var disposed = false
    private fun live(editor: ManualTagEditor, local: LocalRecordUiState = current()) = !disposed &&
        local.ready && !local.opening && local.error == null && local.manualTagEditor?.session == editor.session &&
        local.entries.any { it.id == editor.entryId && it.currentRevision == editor.revision }
    private fun update(editor: ManualTagEditor, change: (ManualTagEditor) -> ManualTagEditor) = publish { local ->
        if (live(editor, local)) local.copy(manualTagEditor = change(checkNotNull(local.manualTagEditor))) else local
    }
    fun open(id: String, revision: Int) {
        val local = current()
        if (disposed || !local.ready || local.opening || local.error != null || local.recycleBin != null || local.saving || local.savePending ||
            local.entries.none { it.id == id && it.currentRevision == revision }) return
        val editor = ManualTagEditor(sessions.incrementAndGet(), id, revision, busy = true)
        publish { latest -> if (!disposed && latest.ready && !latest.opening && latest.error == null &&
            latest.entries.any { it.id == id && it.currentRevision == revision }) latest.copy(manualTagEditor = editor) else latest }
        if (current().manualTagEditor?.session == editor.session) enqueue { store -> load(editor, store) }
    }
    fun edit(session: Long, version: Long, input: String) {
        val editor = current().manualTagEditor?.takeIf { it.session == session && it.snapshot?.version == version && !it.busy && live(it) } ?: return
        if (input == editor.input) return
        update(editor) { it.copy(input = input, notice = null, pending = if (input == it.input) it.pending else null) }
    }
    fun close(session: Long) {
        if (disposed) return
        publish { local -> if (local.manualTagEditor?.session == session) local.copy(manualTagEditor = null) else local }
    }
    fun add(session: Long, version: Long, input: String) {
        edit(session, version, input)
        val editor = current().manualTagEditor?.takeIf { it.session == session && it.snapshot?.version == version && !it.busy && live(it) } ?: return
        val snapshot = editor.snapshot ?: return
        if (editor.pending != null || editor.conflict) return
        try { submit(editor, ManualTagMutation(editor.entryId, snapshot.version, snapshot.names + input, UUID.randomUUID().toString()), true) }
        catch (_: IllegalArgumentException) { update(editor) { it.copy(error = "标签需为 1–40 个字符，最多 12 个，不可重复或包含控制字符。") } }
    }
    fun remove(session: Long, version: Long, name: String) = remove(session, version, name, current().manualTagEditor?.input.orEmpty())
    fun remove(session: Long, version: Long, name: String, input: String) {
        edit(session, version, input)
        val editor = current().manualTagEditor?.takeIf { it.session == session && it.snapshot?.version == version && !it.busy && live(it) && it.pending == null && !it.conflict } ?: return
        val snapshot = editor.snapshot ?: return
        if (name !in snapshot.names) return
        submit(editor, ManualTagMutation(editor.entryId, snapshot.version, snapshot.names - name, UUID.randomUUID().toString()), false)
    }
    fun retry(session: Long) {
        val editor = current().manualTagEditor?.takeIf { it.session == session && !it.busy && live(it) } ?: return
        if (editor.pending != null && !editor.conflict) submit(editor, editor.pending, editor.adding)
        else { update(editor) { it.copy(busy = true, notice = null) }; enqueue { store -> load(editor, store) } }
    }
    private suspend fun load(editor: ManualTagEditor, store: EntryRepository) {
        if (!live(editor)) return
        try {
            val tags = checkNotNull(store.manualTags)
            val snapshot = tags.read(editor.entryId)
            val all = tags.snapshots()
            publish { local -> if (!live(editor, local)) local else local.copy(manualTags = all,
                manualTagsAvailable = true, manualTagsError = null, manualTagEditor = local.manualTagEditor?.copy(
                    snapshot = snapshot, busy = false, pending = null, conflict = false, error = null, notice = null)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed(editor, conflict = false, reading = true) }
    }
    private fun submit(editor: ManualTagEditor, mutation: ManualTagMutation, adding: Boolean) {
        update(editor) { it.copy(busy = true, pending = mutation, adding = adding, error = null, notice = null) }
        enqueue { store ->
            if (!live(editor)) {
                publish { local -> if (disposed || local.manualTagEditor?.session != editor.session) local else local.copy(
                    manualTagEditor = local.manualTagEditor?.copy(busy = false, conflict = true,
                        error = "原文已变化或不可用，输入已保留，请关闭后重新打开。")) }; return@enqueue
            }
            try {
                val tags = checkNotNull(store.manualTags)
                val saved = tags.replace(mutation)
                val all = tags.snapshots()
                check(all[editor.entryId]?.let { it.version == saved.version && it.names == saved.names } == true)
                publish { local ->
                    if (disposed) local else {
                        val confirmed = local.copy(manualTags = all, manualTagsAvailable = true, manualTagsError = null)
                        if (!live(editor, local)) confirmed else confirmed.copy(manualTagEditor = local.manualTagEditor?.copy(
                            snapshot = saved, input = if (adding) "" else local.manualTagEditor.input,
                            busy = false, pending = null, conflict = false, error = null, notice = "手工标签已保存在本机"))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(editor, failure is ManualTagConflict || failure is ManualTagUnavailable) }
        }
    }
    private fun failed(editor: ManualTagEditor, conflict: Boolean, reading: Boolean = false) = publish { local ->
        if (disposed) local else local.copy(manualTags = emptyMap(), manualTagsAvailable = false,
            manualTagsError = "手工标签暂不可读取，请重试。", manualTagEditor = if (!live(editor, local)) local.manualTagEditor else local.manualTagEditor?.copy(
                busy = false, conflict = conflict, notice = null,
                error = if (conflict) "标签已由另一操作变化。输入已保留，请重新读取后再保存。"
                    else if (reading) "手工标签读取失败，输入与加密数据已保留，请重试。"
                    else "标签保存状态待确认，输入已保留，请重试确认。"))
    }
    fun dispose() { disposed = true }
}
