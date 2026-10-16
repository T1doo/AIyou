package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

class ManualTaskEditor(val session: Long, val input: String = "", val dateInput: ManualTaskDateInput = ManualTaskDateInput()) {
    override fun toString() = "ManualTaskEditor(redacted)"
}
class ManualTaskDateEditor(val session: Long, val taskId: String, val version: Long,
    val operation: ManualTaskDateOperation, val input: ManualTaskDateInput) {
    override fun toString() = "ManualTaskDateEditor(redacted)"
}
class ManualTaskTitleEditor(val session: Long, val task: ManualTaskSnapshot, val input: String,
    val confirmation: ManualTaskRename? = null) {
    override fun toString() = "ManualTaskTitleEditor(redacted)"
}
internal sealed interface ManualTaskIntent {
    class Create(val session: Long, val request: ManualTaskCreate) : ManualTaskIntent
    class Change(val request: ManualTaskMutation) : ManualTaskIntent
    class Schedule(val session: Long, val request: ManualTaskScheduleMutation) : ManualTaskIntent
    class Rename(val session: Long, val request: ManualTaskRename) : ManualTaskIntent
}
class ManualTasksUiState internal constructor(
    val supported: Boolean = false, val loading: Boolean = false, val available: Boolean = false,
    items: List<ManualTaskSnapshot> = emptyList(), val editor: ManualTaskEditor? = null,
    val busy: Boolean = false, val error: String? = null, val notice: String? = null,
    val conflict: Boolean = false, internal val pending: ManualTaskIntent? = null,
    internal val generation: Long = 0,
    val dateEditor: ManualTaskDateEditor? = null,
    val titleEditor: ManualTaskTitleEditor? = null,
) {
    val items = Collections.unmodifiableList(items.toList())
    internal fun copy(supported: Boolean = this.supported, loading: Boolean = this.loading, available: Boolean = this.available,
        items: List<ManualTaskSnapshot> = this.items, editor: ManualTaskEditor? = this.editor, busy: Boolean = this.busy,
        error: String? = this.error, notice: String? = this.notice, conflict: Boolean = this.conflict,
        pending: ManualTaskIntent? = this.pending, generation: Long = this.generation,
        dateEditor: ManualTaskDateEditor? = this.dateEditor, titleEditor: ManualTaskTitleEditor? = this.titleEditor) =
        ManualTasksUiState(supported, loading, available, items, editor, busy, error, notice, conflict, pending, generation, dateEditor, titleEditor)
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
    private fun reminderBlocksWrite() = current().reminders.let { it.editor != null || it.busy || it.pending != null }
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
        if (!usable() || reminderBlocksWrite() || !tasks.supported || !tasks.available || tasks.loading || tasks.busy || tasks.pending != null || tasks.editor != null || tasks.dateEditor != null || tasks.titleEditor != null) return
        val editor = ManualTaskEditor(sessions.incrementAndGet(), dateInput = ManualTaskDateInput(timeZone = timeZone()))
        update { it.copy(editor = editor, error = null, notice = null, conflict = false) }
    }
    fun edit(session: Long, input: String) {
        val tasks = current().tasks
        if (!usable() || tasks.editor?.session != session || tasks.busy || tasks.pending != null || input.length > ManualTasks.MAX_RAW_LENGTH) return
        update { latest -> if (latest.editor?.session == session && !latest.busy && latest.pending == null)
            latest.copy(editor = ManualTaskEditor(session, input, checkNotNull(latest.editor).dateInput), error = null, notice = null) else latest }
    }
    fun editCreationDate(session: Long, input: ManualTaskDateInput) {
        if (!usable()) return
        update { state -> val editor = state.editor
            if (editor?.session != session || state.busy || state.pending != null) state else state.copy(
                editor = ManualTaskEditor(session, editor.input, input), error = null, notice = null) }
    }
    fun close(session: Long) {
        if (disposed) return
        update { if (it.editor?.session == session && !it.busy) it.copy(editor = null) else it }
    }
    fun create(session: Long, raw: String) {
        edit(session, raw)
        val tasks = current().tasks
        if (!usable() || reminderBlocksWrite() || tasks.editor?.session != session || !tasks.available || tasks.busy || tasks.loading || tasks.pending != null) return
        val schedule = try { tasks.editor.dateInput.parse() }
        catch (failure: ManualTaskDateInvalid) { update { it.copy(error = dateError(failure.reason)) }; return }
        val request = try { ManualTaskCreate(UUID.randomUUID().toString(), raw, clock.millis(), timeZone(), UUID.randomUUID().toString(), schedule) }
        catch (_: IllegalArgumentException) { update { it.copy(error = "待办标题需为 1–240 个字符，不可包含控制字符。输入已保留。") }; return }
        submit(ManualTaskIntent.Create(session, request))
    }
    fun beginDate(id: String, version: Long, operation: ManualTaskDateOperation) {
        val tasks = current().tasks
        if (!usable() || reminderBlocksWrite() || !tasks.supported || !tasks.available || tasks.loading || tasks.busy ||
            tasks.pending != null || tasks.editor != null || tasks.dateEditor != null || tasks.titleEditor != null) return
        val task = tasks.items.singleOrNull { it.id == id && it.version == version && it.status == ManualTaskStatus.OPEN } ?: return
        if (operation != ManualTaskDateOperation.SET && task.schedule == null) return
        update { it.copy(dateEditor = ManualTaskDateEditor(sessions.incrementAndGet(), id, version, operation,
            ManualTaskDateInput.from(task.schedule, timeZone())), error = null, notice = null) }
    }
    fun editDate(session: Long, input: ManualTaskDateInput) {
        if (!usable()) return
        update { state -> val editor = state.dateEditor
            if (editor?.session != session || editor.operation == ManualTaskDateOperation.CLEAR || state.busy || state.pending != null) state
            else state.copy(dateEditor = ManualTaskDateEditor(session, editor.taskId, editor.version, editor.operation, input),
                error = null, notice = null) }
    }
    fun closeDate(session: Long) {
        if (disposed) return
        update { if (it.dateEditor?.session == session && !it.busy) it.copy(dateEditor = null) else it }
    }
    fun saveDate(session: Long, input: ManualTaskDateInput) {
        editDate(session, input)
        val tasks = current().tasks; val editor = tasks.dateEditor
        if (!usable() || reminderBlocksWrite() || editor?.session != session || !tasks.available || tasks.loading || tasks.busy || tasks.pending != null) return
        val target = tasks.items.singleOrNull { it.id == editor.taskId && it.version == editor.version && it.status == ManualTaskStatus.OPEN }
        if (target == null) { update { it.copy(error = "待办已变化，请关闭编辑，重新读取后再选择。", conflict = true) }; return }
        val due = try {
            val desired = if (editor.operation == ManualTaskDateOperation.CLEAR) null else
                (input.parse() ?: throw ManualTaskDateInvalid(ManualTaskDateError.NO_DATE))
            ManualTaskDates.validate(editor.operation, target.schedule, desired); desired
        } catch (failure: ManualTaskDateInvalid) { update { it.copy(error = dateError(failure.reason)) }; return }
        submit(ManualTaskIntent.Schedule(session, ManualTaskScheduleMutation(target.id, target.version,
            editor.operation, due, clock.millis(), UUID.randomUUID().toString())))
    }
    fun change(id: String, version: Long, status: ManualTaskStatus) {
        val tasks = current().tasks
        if (!usable() || reminderBlocksWrite() || !tasks.available || tasks.busy || tasks.loading || tasks.pending != null || status == ManualTaskStatus.OPEN ||
            tasks.editor != null || tasks.dateEditor != null || tasks.titleEditor != null ||
            tasks.items.none { it.id == id && it.version == version && it.status == ManualTaskStatus.OPEN }) return
        submit(ManualTaskIntent.Change(ManualTaskMutation(id, version, status, clock.millis(), UUID.randomUUID().toString())))
    }
    fun beginTitle(id: String, version: Long) {
        val tasks = current().tasks
        if (!usable() || reminderBlocksWrite() || !tasks.supported || !tasks.available || tasks.loading || tasks.busy ||
            tasks.pending != null || tasks.editor != null || tasks.dateEditor != null || tasks.titleEditor != null) return
        val task = tasks.items.singleOrNull { it.id == id && it.version == version && it.version < Long.MAX_VALUE && it.status == ManualTaskStatus.OPEN } ?: return
        update { it.copy(titleEditor = ManualTaskTitleEditor(sessions.incrementAndGet(), task, task.title),
            error = null, notice = null, conflict = false) }
    }
    fun editTitle(session: Long, input: String) {
        if (!usable() || input.length > ManualTasks.MAX_RAW_LENGTH) return
        update { state -> val editor = state.titleEditor
            if (editor?.session != session || editor.confirmation != null || state.busy || state.pending != null) state
            else state.copy(titleEditor = ManualTaskTitleEditor(session, editor.task, input), error = null, notice = null) }
    }
    fun prepareTitle(session: Long) {
        val tasks = current().tasks; val editor = tasks.titleEditor
        if (!usable() || reminderBlocksWrite() || editor?.session != session || editor.confirmation != null ||
            !tasks.available || tasks.loading || tasks.busy || tasks.pending != null) return
        if (tasks.items.singleOrNull { it.id == editor.task.id } != editor.task) {
            titleConflict(); return
        }
        val title = try { ManualTasks.title(editor.input) }
        catch (_: IllegalArgumentException) {
            update { it.copy(error = "待办标题需为 1–240 个字符，不可包含控制字符。输入已保留。") }; return
        }
        if (title == editor.task.title) {
            update { it.copy(error = null, notice = "标题没有变化，无需保存。") }; return
        }
        val request = ManualTaskRename(editor.task.id, editor.task.version, title, clock.millis(), UUID.randomUUID().toString())
        update { it.copy(titleEditor = ManualTaskTitleEditor(session, editor.task, editor.input, request), error = null, notice = null) }
    }
    fun cancelTitleConfirmation(session: Long, request: ManualTaskRename) {
        if (!usable()) return
        update { state -> val editor = state.titleEditor
            if (editor?.session != session || editor.confirmation !== request || state.busy || state.pending != null) state
            else state.copy(titleEditor = ManualTaskTitleEditor(session, editor.task, editor.input), error = null, notice = null) }
    }
    fun saveTitle(session: Long, request: ManualTaskRename) {
        val tasks = current().tasks; val editor = tasks.titleEditor
        if (!usable() || reminderBlocksWrite() || editor?.session != session || editor.confirmation !== request ||
            !tasks.available || tasks.loading || tasks.busy || tasks.pending != null) return
        if (tasks.items.singleOrNull { it.id == editor.task.id } != editor.task) {
            titleConflict(); return
        }
        submit(ManualTaskIntent.Rename(session, request))
    }
    fun closeTitle(session: Long) {
        if (disposed) return
        update { if (it.titleEditor?.session == session && !it.busy && it.pending == null) it.copy(titleEditor = null) else it }
    }
    private fun titleConflict() = update { it.copy(conflict = true,
        error = "待办或提醒状态已变化。输入已保留，请关闭编辑，重新读取后再选择。", notice = null) }
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
                    is ManualTaskIntent.Schedule -> tasks.reschedule(intent.request)
                    is ManualTaskIntent.Rename -> tasks.rename(intent.request)
                }
                val all = tasks.snapshots()
                check(all.singleOrNull { it.id == saved.id } == saved)
                update { state -> if (state.generation != generation || state.pending !== intent) state else {
                    val editor = state.editor
                    state.copy(items = all, available = true, busy = false, pending = null, conflict = false, error = null,
                        editor = if (intent is ManualTaskIntent.Create && editor?.session == intent.session) null else editor,
                        dateEditor = if (intent is ManualTaskIntent.Schedule && state.dateEditor?.session == intent.session) null else state.dateEditor,
                        titleEditor = if (intent is ManualTaskIntent.Rename && state.titleEditor?.session == intent.session) null else state.titleEditor,
                        notice = if (intent is ManualTaskIntent.Rename) "标题修改已保存在本机" else when (saved.status) {
                            ManualTaskStatus.OPEN -> "待办已保存在本机"
                            ManualTaskStatus.COMPLETED -> "待办已完成，状态已保存在本机"
                            ManualTaskStatus.CANCELLED -> "待办已取消，状态已保存在本机"
                        })
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(intent, generation, failure is ManualTaskConflict || failure is ManualTaskDateInvalid) }
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
    private fun dateError(reason: ManualTaskDateError) = when (reason) {
        ManualTaskDateError.DATE -> "日期请用有效的 YYYY-MM-DD，输入已保留。"
        ManualTaskDateError.TIME_WITHOUT_DATE -> "请先填写日期；没有日期不能设置时刻。"
        ManualTaskDateError.TIME -> "时刻请用有效的 HH:mm；留空表示仅日期。"
        ManualTaskDateError.ZONE -> "请填写明确的 IANA 时区，如 Asia/Shanghai。"
        ManualTaskDateError.GAP -> "这个本地时刻因夏令时跳转不存在，请明确选择其他时刻。"
        ManualTaskDateError.OVERLAP -> "这个本地时刻出现两次，请明确选择第一次或第二次。"
        ManualTaskDateError.OFFSET -> "所选时区偏移已失效，请重新选择。"
        ManualTaskDateError.NO_DATE -> "请明确设置日期；清除日期请使用清除操作。"
        ManualTaskDateError.NOT_LATER -> "延后必须比原计划更晚，原待办未改变。"
        ManualTaskDateError.PRECISION -> "延后需保留原时区及日期／时刻精度；更改请使用修改日期时间。"
    }
}
