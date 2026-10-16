package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

class TaskReminderEditor(val session: Long, val task: ManualTaskSnapshot,
    val mode: TaskReminderMode = TaskReminderMode.APPROXIMATE, val cancelling: Boolean = false) {
    override fun toString() = "TaskReminderEditor(redacted)"
}
internal class ReminderSubmission(val session: Long, val request: TaskReminderMutation)
class TaskRemindersUiState internal constructor(val supported: Boolean = false,
    val editor: TaskReminderEditor? = null, val busy: Boolean = false,
    val error: String? = null, val notice: String? = null, val conflict: Boolean = false,
    internal val pending: ReminderSubmission? = null, internal val generation: Long = 0) {
    internal fun copy(supported: Boolean = this.supported, editor: TaskReminderEditor? = this.editor,
        busy: Boolean = this.busy, error: String? = this.error, notice: String? = this.notice,
        conflict: Boolean = this.conflict, pending: ReminderSubmission? = this.pending,
        generation: Long = this.generation) = TaskRemindersUiState(supported, editor, busy, error, notice, conflict, pending, generation)
    override fun toString() = "TaskRemindersUiState(busy=$busy, redacted)"
}

/** The production build has no notification/alarm adapter or permission request. */
private object DisconnectedReminderDriver : TaskReminderDriver {
    override fun availability() = TaskReminderAvailability()
    override fun schedule(command: TaskReminderCommand) = TaskReminderPhase.PLATFORM_UNAVAILABLE
    override fun cancel(command: TaskReminderCommand) = TaskReminderPhase.PLATFORM_UNAVAILABLE
}

/** Explicit foreground actions only; shares the records owner's command queue and immutable retry identity. */
class TaskReminderController internal constructor(private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean, private val clock: Clock,
    private val driver: TaskReminderDriver = DisconnectedReminderDriver) {
    private val sessions = AtomicLong()
    @Volatile private var disposed = false
    private fun available(local: LocalRecordUiState = current()) = !disposed && local.ready && !local.opening && local.error == null
    private fun taskWritePending() = current().tasks.let { it.busy || it.loading || it.pending != null }
    private fun update(change: (TaskRemindersUiState) -> TaskRemindersUiState) =
        publish { local -> if (disposed) local else local.copy(reminders = change(local.reminders)) }
    suspend fun initialize(store: EntryRepository) {
        update { it.copy(supported = store.reminders != null) }
        if (store.reminders == null || disposed) return
        if (taskWritePending()) { deferred(); return }
        val pending = current().reminders.pending
        if (pending != null && !current().reminders.conflict) submit(pending) else reconcile(store)
    }
    fun begin(id: String, version: Long, cancelling: Boolean = false) {
        val local = current(); val state = local.reminders; val tasks = local.tasks
        if (!available(local) || !state.supported || state.busy || state.pending != null || state.editor != null ||
            !tasks.available || tasks.loading || tasks.busy || tasks.pending != null || tasks.editor != null || tasks.dateEditor != null) return
        val task = tasks.items.singleOrNull { it.id == id && it.version == version && it.status == ManualTaskStatus.OPEN } ?: return
        val reminder = task.reminder
        if (cancelling && (reminder == null || reminder.phase == TaskReminderPhase.REVOKED)) return
        if (!cancelling && task.schedule?.instant() == null) {
            update { it.copy(error = "请先明确保存日期、时刻和时区；仅日期不能自动补提醒时刻。", notice = null) }; return
        }
        update { it.copy(editor = TaskReminderEditor(sessions.incrementAndGet(), task,
            task.reminder?.mode ?: TaskReminderMode.APPROXIMATE, cancelling), error = null, notice = null, conflict = false) }
    }
    fun mode(session: Long, value: TaskReminderMode) {
        if (!available()) return
        update { state -> val editor = state.editor
            if (editor?.session != session || editor.cancelling || state.busy || state.pending != null) state
            else state.copy(editor = TaskReminderEditor(session, editor.task, value), error = null) }
    }
    fun confirm(session: Long) {
        val local = current(); val state = local.reminders; val editor = state.editor
        if (!available(local) || editor?.session != session || state.busy || state.pending != null ||
            !local.tasks.available || local.tasks.loading || local.tasks.busy || local.tasks.pending != null) return
        if (local.tasks.items.singleOrNull { it.id == editor.task.id } != editor.task) {
            update { it.copy(error = "待办已变化，请关闭后重新读取，再确认提醒。", conflict = true) }; return
        }
        val requestedAt = clock.millis()
        if (!editor.cancelling && (editor.task.schedule?.instant()?.toEpochMilli()?.let { it <= requestedAt } != false)) {
            update { it.copy(error = "提醒需要未来的明确日期、时刻和时区；请关闭后修改待办时间，再单独确认。") }; return
        }
        val request = try { TaskReminderMutation(editor.task, if (editor.cancelling) null else editor.mode,
            requestedAt, UUID.randomUUID().toString()) }
        catch (_: IllegalArgumentException) { update { it.copy(error = "时间或待办版本已变化，请重新读取后确认。") }; return }
        submit(ReminderSubmission(session, request))
    }
    fun close(session: Long) {
        update { if (it.editor?.session == session && !it.busy) it.copy(editor = null) else it }
    }
    fun retry() {
        val state = current().reminders
        if (!available() || !state.supported || state.busy) return
        if (taskWritePending()) { deferred(); return }
        if (state.pending != null && !state.conflict) { submit(state.pending); return }
        update { it.copy(editor = if (it.conflict) null else it.editor, pending = null, conflict = false, busy = true, error = null) }
        if (!enqueue { reconcile(it) }) update { it.copy(busy = false, error = "提醒状态暂不能读取，请重试。") }
    }
    private fun submit(pending: ReminderSubmission) {
        val generation = current().reminders.generation + 1
        update { it.copy(busy = true, pending = pending, generation = generation, error = null, notice = null, conflict = false) }
        if (!enqueue { store ->
            if (!available() || current().reminders.generation != generation || current().reminders.pending !== pending) return@enqueue
            if (taskWritePending()) { deferred(); return@enqueue }
            try {
                val saved = checkNotNull(store.reminders).changeReminder(pending.request)
                val all = checkNotNull(store.manualTasks).snapshots()
                check(all.singleOrNull { it.id == saved.id } == saved)
                publish { local -> val state = local.reminders
                    if (!available(local) || state.generation != generation || state.pending !== pending) local
                    else local.copy(tasks = local.tasks.copy(items = all), reminders = state.copy(busy = false, pending = null,
                        editor = if (state.editor?.session == pending.session) null else state.editor,
                        notice = if (pending.request.mode == null) "撤销提醒计划已保存在本机；系统撤销状态以清单为准。"
                            else "提醒确认已保存在本机；系统调度状态以清单为准。")) }
                // Do not dispatch before the immutable mutation has a verified readback. An unknown
                // confirmation must remain replayable as that exact request, without an intervening ACK.
                if (available() && current().reminders.pending == null) reconcile(store)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                update { if (it.generation != generation || it.pending !== pending) it else it.copy(busy = false,
                    pending = if (failure is TaskReminderLimit) null else it.pending,
                    conflict = failure is ManualTaskConflict,
                    error = when (failure) {
                        is ManualTaskConflict -> "待办或提醒已由另一操作变化，未自动改用新版本。请关闭后重新读取。"
                        is TaskReminderLimit -> "待核对的系统撤销较多，本次未新增提醒。请保留待办，待调度服务可用后重试核对。"
                        is IllegalArgumentException -> "提醒需要未来的明确日期、时刻和时区；请关闭后重新确认。"
                        else -> "提醒保存或读取确认失败。原请求已保留，请重试确认，不会自动新建任务。"
                    }) }
            }
        }) update { if (it.generation == generation) it.copy(busy = false, error = "提醒操作暂未完成，原请求已保留，请重试确认。") else it }
    }
    private fun reconcile(store: EntryRepository) {
        if (!available()) return
        if (taskWritePending()) { deferred(); return }
        try {
            TaskReminderDispatcher(checkNotNull(store.reminders), driver, clock::millis).drain()
            val all = checkNotNull(store.manualTasks).snapshots()
            publish { local -> if (!available(local) || local.reminders.pending != null) local else
                local.copy(tasks = if (local.tasks.available && !local.tasks.loading && !local.tasks.busy && local.tasks.pending == null)
                    local.tasks.copy(items = all) else local.tasks,
                    reminders = local.reminders.copy(busy = false, error = null)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { update { it.copy(busy = false, error = "提醒计划已保留；当前调度状态暂不能核对，请重试读取。") } }
    }
    private fun deferred() = update { it.copy(busy = false,
        error = "待办保存尚未确认。请先核对待办保存结果，再重试提醒状态；未推进调度命令。") }
    @Synchronized fun dispose() {
        if (disposed) return
        disposed = true
        publish { it.copy(reminders = TaskRemindersUiState(supported = it.reminders.supported)) }
    }
}
