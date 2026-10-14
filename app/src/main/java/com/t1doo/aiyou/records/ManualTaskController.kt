package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

class ManualTaskEditor(val session: Long, val input: String = "") {
    override fun toString() = "ManualTaskEditor(redacted)"
}
internal sealed interface ManualTaskIntent {
    class Create(val session: Long, val request: ManualTaskCreate) : ManualTaskIntent
    class Change(val request: ManualTaskMutation) : ManualTaskIntent
}
class ManualTasksUiState internal constructor(
    val supported: Boolean = false, val loading: Boolean = false, val available: Boolean = false,
    items: List<ManualTaskSnapshot> = emptyList(), val editor: ManualTaskEditor? = null,
    val busy: Boolean = false, val error: String? = null, val notice: String? = null,
    val conflict: Boolean = false, internal val pending: ManualTaskIntent? = null,
    internal val generation: Long = 0,
) {
    val items = Collections.unmodifiableList(items.toList())
    internal fun copy(supported: Boolean = this.supported, loading: Boolean = this.loading, available: Boolean = this.available,
        items: List<ManualTaskSnapshot> = this.items, editor: ManualTaskEditor? = this.editor, busy: Boolean = this.busy,
        error: String? = this.error, notice: String? = this.notice, conflict: Boolean = this.conflict,
        pending: ManualTaskIntent? = this.pending, generation: Long = this.generation) =
        ManualTasksUiState(supported, loading, available, items, editor, busy, error, notice, conflict, pending, generation)
    override fun toString() = "ManualTasksUiState(available=$available, busy=$busy, redacted)"
}

/** Memory-only UI sessions; all IO runs through the existing records owner's one command queue. */
class ManualTaskController internal constructor(
    private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean,
    private val clock: Clock,
    private val timeZone: () -> String = { ZoneId.systemDefault().id },
) {
    private val sessions = AtomicLong()
    @Volatile private var disposed = false
    private fun usable(local: LocalRecordUiState = current()) = !disposed && local.ready && !local.opening && local.error == null
    private fun update(change: (ManualTasksUiState) -> ManualTasksUiState) =
        publish { local -> if (disposed) local else local.copy(tasks = change(local.tasks)) }
    suspend fun initialize(store: EntryRepository) {
        if (disposed) return
        update { it.copy(supported = store.manualTasks != null) }
        if (store.manualTasks == null) return
        val pending = current().tasks.pending
        if (pending != null && !current().tasks.conflict) {
            // A global records retry must confirm the original identity, never turn retained input
            // into a fresh task merely because a list read found the previous commit.
            update { it.copy(loading = false, available = false, items = emptyList()) }
            submit(pending)
            return
        }
        val generation = current().tasks.generation + 1
        update { it.copy(loading = true, available = false, items = emptyList(), busy = false, generation = generation) }
        load(store, generation)
    }
    fun begin() {
        val tasks = current().tasks
        if (!usable() || !tasks.supported || !tasks.available || tasks.loading || tasks.busy || tasks.pending != null || tasks.editor != null) return
        val editor = ManualTaskEditor(sessions.incrementAndGet())
        update { it.copy(editor = editor, error = null, notice = null, conflict = false) }
    }
    fun edit(session: Long, input: String) {
        val tasks = current().tasks
        if (!usable() || tasks.editor?.session != session || tasks.busy || tasks.pending != null || input.length > ManualTasks.MAX_RAW_LENGTH) return
        update { latest -> if (latest.editor?.session == session && !latest.busy && latest.pending == null)
            latest.copy(editor = ManualTaskEditor(session, input), error = null, notice = null) else latest }
    }
    fun close(session: Long) {
        if (disposed) return
        update { if (it.editor?.session == session && !it.busy) it.copy(editor = null) else it }
    }
    fun create(session: Long, raw: String) {
        edit(session, raw)
        val tasks = current().tasks
        if (!usable() || tasks.editor?.session != session || !tasks.available || tasks.busy || tasks.loading || tasks.pending != null) return
        val request = try { ManualTaskCreate(UUID.randomUUID().toString(), raw, clock.millis(), timeZone(), UUID.randomUUID().toString()) }
        catch (_: IllegalArgumentException) { update { it.copy(error = "待办标题需为 1–240 个字符，不可包含控制字符。输入已保留。") }; return }
        submit(ManualTaskIntent.Create(session, request))
    }
    fun change(id: String, version: Long, status: ManualTaskStatus) {
        val tasks = current().tasks
        if (!usable() || !tasks.available || tasks.busy || tasks.loading || tasks.pending != null || status == ManualTaskStatus.OPEN ||
            tasks.items.none { it.id == id && it.version == version && it.status == ManualTaskStatus.OPEN }) return
        submit(ManualTaskIntent.Change(ManualTaskMutation(id, version, status, clock.millis(), UUID.randomUUID().toString())))
    }
    fun retry() {
        val tasks = current().tasks
        if (!usable() || !tasks.supported || tasks.loading || tasks.busy) return
        if (tasks.pending != null && !tasks.conflict) submit(tasks.pending)
        else refresh()
    }
    fun refresh() {
        val tasks = current().tasks
        if (!usable() || !tasks.supported || tasks.loading || tasks.busy || tasks.pending != null && !tasks.conflict) return
        val generation = tasks.generation + 1
        update { it.copy(loading = true, available = false, items = emptyList(), notice = null, generation = generation) }
        if (!enqueue { load(it, generation) }) failed(null, generation, false)
    }
    private suspend fun load(store: EntryRepository, generation: Long) {
        if (disposed || current().tasks.generation != generation || !usable()) {
            update { if (it.generation == generation) it.copy(loading = false) else it }; return
        }
        try {
            val all = checkNotNull(store.manualTasks).snapshots()
            update { if (it.generation == generation && usable()) it.copy(items = all, loading = false,
                available = true, error = null, conflict = false, pending = null) else it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed(null, generation, false) }
    }
    private fun submit(intent: ManualTaskIntent) {
        val generation = current().tasks.generation + 1
        update { it.copy(busy = true, pending = intent, generation = generation, error = null, notice = null, conflict = false) }
        if (!enqueue { store ->
            if (disposed || !usable() || current().tasks.generation != generation || current().tasks.pending !== intent) {
                failed(intent, generation, false); return@enqueue
            }
            try {
                val tasks = checkNotNull(store.manualTasks)
                val saved = when (intent) {
                    is ManualTaskIntent.Create -> tasks.create(intent.request)
                    is ManualTaskIntent.Change -> tasks.change(intent.request)
                }
                val all = tasks.snapshots()
                check(all.singleOrNull { it.id == saved.id } == saved)
                update { state -> if (state.generation != generation || state.pending !== intent) state else {
                    val editor = state.editor
                    state.copy(items = all, available = true, busy = false, pending = null, conflict = false, error = null,
                        editor = if (intent is ManualTaskIntent.Create && editor?.session == intent.session) null else editor,
                        notice = when (saved.status) {
                            ManualTaskStatus.OPEN -> "待办已保存在本机"
                            ManualTaskStatus.COMPLETED -> "待办已完成，状态已保存在本机"
                            ManualTaskStatus.CANCELLED -> "待办已取消，状态已保存在本机"
                        })
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(intent, generation, failure is ManualTaskConflict) }
        }) failed(intent, generation, false)
    }
    private fun failed(intent: ManualTaskIntent?, generation: Long, conflict: Boolean) = update { state ->
        if (state.generation != generation || intent != null && state.pending !== intent) state
        else state.copy(items = emptyList(), available = false, loading = false, busy = false, notice = null, conflict = conflict,
            error = if (conflict) "待办已由另一操作变化。输入已保留，请重新读取后再操作。"
                else if (intent == null) "待办读取失败，加密数据与输入已保留，请重试读取。"
                else "待办保存状态待确认，输入已保留，请重试确认。")
    }
    fun dispose() { disposed = true }
}
