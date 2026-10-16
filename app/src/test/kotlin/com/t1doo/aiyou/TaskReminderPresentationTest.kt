package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual App route, dialog and production controller. Controlled ports prove presentation and
 * acknowledgement boundaries only; real SQL/AEAD and device alarms are separate acceptance gates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class TaskReminderPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val capture = "合成独立原始输入：只是引用，不能变成提醒"
    private val clock = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC)
    private val schedule = ManualTaskDateInput("2026-10-20", "15:00", "UTC").parse()!!
    private val persisted = linkedMapOf<String, ManualTaskSnapshot>()
    private val requests = mutableListOf<TaskReminderMutation>()
    private val acknowledgements = mutableListOf<Pair<TaskReminderCommand, TaskReminderPhase>>()
    private val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
    private val state = mutableStateOf(LocalRecordUiState(ready = true, opening = false, text = capture,
        entries = listOf(StoredEntry("synthetic-original", 1, "合成原文保持独立", 1, "UTC", ProcessingPolicy.LOCAL_ONLY))))
    private val actions = AtomicReference<TaskReminderActions>()
    private var saved: SaveableStateRegistry? = null
    private val manualPort = object : ManualTaskRepository {
        override fun snapshots() = persisted.values.toList()
        override fun create(request: ManualTaskCreate): ManualTaskSnapshot = error("Reminder UI must not create tasks")
        override fun change(request: ManualTaskMutation): ManualTaskSnapshot = error("Reminder UI must not change task status")
        override fun rename(request: com.t1doo.aiyou.model.ManualTaskRename): ManualTaskSnapshot = error("Title editing not requested by this test")
        override fun reschedule(request: ManualTaskScheduleMutation): ManualTaskSnapshot = error("Reminder UI must not change task dates")
    }
    private val reminderPort = object : TaskReminderRepository {
        override fun changeReminder(request: TaskReminderMutation): ManualTaskSnapshot {
            val old = persisted[request.task.id] ?: throw ManualTaskConflict()
            if (old != request.task) throw ManualTaskConflict()
            requests += request
            val reminder = request.mode?.let { TaskReminders.confirm(old, it) } ?: TaskReminders.revoke(old)
            return old.copy(version = old.version + 1, updatedAtMillis = request.requestedAtMillis,
                reminder = reminder).also { persisted[it.id] = it }
        }
        override fun reminderCommands() = persisted.values.flatMap { it.reminder?.commands.orEmpty() }
        override fun isReminderCommandCurrent(command: TaskReminderCommand) = TaskReminders.commandCurrent(persisted[command.token.taskId], command)
        override fun acknowledgeReminder(command: TaskReminderCommand, result: TaskReminderPhase, atMillis: Long): Boolean {
            val task = persisted[command.token.taskId] ?: return false
            val reminder = task.reminder ?: return false
            if (command !in reminder.commands) return false
            if (command.kind == TaskReminderCommandKind.CANCEL && result != TaskReminderPhase.REVOKED) return false
            val phase = if (command.kind == TaskReminderCommandKind.SCHEDULE) result else reminder.phase
            persisted[task.id] = task.copy(version = task.version + 1, updatedAtMillis = atMillis,
                reminder = reminder.copy(phase = phase, commands = reminder.commands.filterNot { it == command }))
            acknowledgements += command to result
            return true
        }
        override fun acceptReminderTrigger(token: TaskReminderToken, nowMillis: Long, availability: TaskReminderAvailability): Boolean =
            error("Presentation port must not simulate a device trigger")
    }
    private val store = object : EntryRepository {
        override val manualTasks = manualPort
        override val reminders = reminderPort
        override suspend fun readDraft() = TextDraft("synthetic-draft", 1, capture)
        override suspend fun updateDraft(text: String): TextDraft = error("Reminder UI must not overwrite original input")
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Reminder UI must not save original input")
        override suspend fun entries() = state.value.entries
        override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Reminder UI must not revise originals")
    }
    // Default production driver is disconnected: no injected fake success and no system permissions.
    private val controller = TaskReminderController({ state.value }, { state.value = it(state.value) },
        { queue.add(it); true }, clock)

    @After fun dispose() = controller.dispose()

    @Test fun explicitReminderAndRevocationRequireRealConfirmationWithoutSavingPrivatePlan() {
        val task = task("synthetic-private-task-one", "合成私密提醒标题")
        persisted[task.id] = task
        content()
        open(task.id)
        dialogNode("reminder-confirm-title").assertTextEquals(task.title)
        clickDialog("reminder-mode-EXACT")
        compose.runOnIdle {
            assertTrue(requests.isEmpty()); assertTrue(queue.isEmpty())
            assertEquals(TaskReminderMode.EXACT, state.value.reminders.editor!!.mode)
            assertPrivateSavedState(task)
        }
        clickDialog("reminder-close")
        compose.runOnIdle { assertTrue(requests.isEmpty()); assertTrue(queue.isEmpty()); assertNull(persisted.getValue(task.id).reminder) }
        open(task.id)
        clickDialog("reminder-mode-EXACT")
        clickDialog("reminder-confirm")
        compose.runOnIdle {
            assertTrue(requests.isEmpty()); assertEquals(1, queue.size); assertTrue(state.value.reminders.busy)
            assertNull(persisted.getValue(task.id).reminder)
        }
        pump()
        compose.runOnIdle {
            assertEquals(1, requests.size); assertEquals(TaskReminderMode.EXACT, requests.single().mode)
            val actual = persisted.getValue(task.id)
            assertEquals(TaskReminderPhase.PLATFORM_UNAVAILABLE, actual.reminder!!.phase)
            assertTrue(actual.reminder!!.commands.isEmpty())
            assertEquals(ManualTaskStatus.OPEN, actual.status); assertEquals(task.schedule, actual.schedule)
            assertEquals(TaskReminderPhase.PLATFORM_UNAVAILABLE, acknowledgements.single().second)
            assertNull(state.value.reminders.editor); assertPrivateSavedState(task)
        }
        taskNode("reminder-status-" + task.id).assertTextEquals("精确提醒计划 · 提醒确认已保存 · 系统调度未接入，不会发送通知")
        compose.onAllNodesWithText("调度适配器已确认安排", substring = true).assertCountEquals(0)
        taskNode("reminder-cancel-" + task.id).performClick()
        clickDialog("reminder-close")
        compose.runOnIdle { assertEquals(1, requests.size); assertTrue(queue.isEmpty()) }
        taskNode("reminder-cancel-" + task.id).performClick()
        clickDialog("reminder-confirm")
        compose.runOnIdle { assertEquals(1, requests.size); assertEquals(1, queue.size) }
        pump()
        compose.runOnIdle {
            assertEquals(2, requests.size); assertNull(requests.last().mode)
            val revoked = persisted.getValue(task.id)
            assertEquals(TaskReminderPhase.REVOKED, revoked.reminder!!.phase)
            assertEquals(TaskReminderCommandKind.CANCEL, revoked.reminder!!.commands.single().kind)
            assertEquals(ManualTaskStatus.OPEN, revoked.status); assertEquals(task.schedule, revoked.schedule)
            assertEquals(capture, state.value.text); assertEquals(ProcessingPolicy.LOCAL_ONLY, state.value.entries.single().policy)
            assertPrivateSavedState(task)
        }
        taskNode("reminder-status-" + task.id).assertTextEquals("精确提醒计划 · 提醒计划已撤销 · 系统撤销尚待核对")
    }

    @Test fun dateOnlyAndGlobalReadFailureDisableReminderWithoutChangingOriginalInput() {
        val dateOnly = task("synthetic-date-only", "合成仅日期待办").copy(schedule = schedule.copy(time = null, offsetSeconds = null))
        val timed = task("synthetic-timed", "合成读取失败隐藏标题")
        persisted[dateOnly.id] = dateOnly; persisted[timed.id] = timed
        content()
        taskNode("reminder-open-" + dateOnly.id).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(queue.isEmpty()); assertTrue(requests.isEmpty()) }
        open(timed.id)
        val old = currentActions()
        compose.runOnIdle { state.value = state.value.copy(error = "合成本机读取失败") }
        compose.runOnIdle { assertEquals(timed.id, state.value.reminders.editor!!.task.id) }
        dialogNode("reminder-confirm").assertIsNotEnabled()
        dialogNode("reminder-mode-EXACT").assertIsNotEnabled()
        compose.onAllNodesWithTag("reminder-confirm-title").assertCountEquals(0)
        compose.onAllNodesWithText(timed.title).assertCountEquals(0)
        compose.onAllNodesWithText(dateOnly.title).assertCountEquals(0)
        compose.runOnIdle { old.mode(TaskReminderMode.EXACT); old.confirm() }
        compose.runOnIdle {
            assertTrue(requests.isEmpty()); assertTrue(queue.isEmpty())
            assertEquals(TaskReminderMode.APPROXIMATE, state.value.reminders.editor!!.mode)
            assertEquals(listOf(dateOnly, timed), persisted.values.toList())
            assertEquals(capture, state.value.text); assertEquals(ProcessingPolicy.LOCAL_ONLY, state.value.entries.single().policy)
        }
        clickDialog("reminder-close")
        compose.runOnIdle { assertNull(state.value.reminders.editor); assertTrue(queue.isEmpty()) }
    }

    @Test fun staleDialogAndRouteCallbacksCannotWriteAnotherTaskWithTheSameTitle() {
        val first = task("synthetic-same-title-one", "合成同名待办")
        val second = task("synthetic-same-title-two", first.title)
        persisted[first.id] = first; persisted[second.id] = second
        content(); open(first.id)
        val stale = currentActions()
        clickDialog("reminder-close")
        open(second.id)
        val currentSession = compose.runOnIdle { state.value.reminders.editor!!.session }
        compose.runOnIdle { stale.mode(TaskReminderMode.EXACT); stale.confirm(); stale.close() }
        compose.runOnIdle {
            assertTrue(queue.isEmpty()); assertTrue(requests.isEmpty())
            assertEquals(second.id, state.value.reminders.editor!!.task.id)
            assertEquals(currentSession, state.value.reminders.editor!!.session)
            assertEquals(TaskReminderMode.APPROXIMATE, state.value.reminders.editor!!.mode)
        }
        clickDialog("reminder-confirm"); pump()
        compose.runOnIdle {
            assertEquals(second.id, requests.single().task.id)
            assertNull(persisted.getValue(first.id).reminder)
            assertEquals(TaskReminderPhase.PLATFORM_UNAVAILABLE, persisted.getValue(second.id).reminder!!.phase)
        }
        open(first.id)
        val oldRoute = currentActions()
        clickDialog("reminder-close")
        compose.onNodeWithText("设置").assertIsDisplayed().performClick()
        compose.onNodeWithText("返回").assertIsDisplayed().performClick()
        compose.runOnIdle { oldRoute.mode(TaskReminderMode.EXACT); oldRoute.confirm(); oldRoute.close() }
        compose.runOnIdle { assertEquals(1, requests.size); assertTrue(queue.isEmpty()); assertNull(state.value.reminders.editor) }
        compose.onNode(hasText("此刻") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.runOnIdle { oldRoute.confirm(); stale.confirm() }
        compose.runOnIdle {
            assertEquals(1, requests.size); assertTrue(queue.isEmpty()); assertNull(persisted.getValue(first.id).reminder)
            assertEquals(capture, state.value.text)
        }
        compose.onNodeWithContentDescription("此刻，想记下什么").assertTextEquals(capture)
    }

    private fun task(id: String, title: String) = ManualTaskSnapshot(id, 1, title, ManualTaskStatus.OPEN, 1, "UTC", 1, schedule)
    private fun content() {
        state.value = state.value.copy(tasks = ManualTasksUiState(supported = true, available = true, items = persisted.values.toList()))
        runBlocking { controller.initialize(store) }
        compose.setContent {
            val registry = LocalSaveableStateRegistry.current
            SideEffect { saved = registry }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalTaskReminderObserver provides TaskReminderObserver { actions.set(it) },
                LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.size(320.dp, 640.dp)) {
                    AIyouTheme { AIyouApp(previewDestination = Destination.TASKS, local = state.value,
                        currentRecords = { state.value }, reminders = controller, taskClock = clock) }
                }
            }
        }
        compose.runOnIdle { assertTrue(state.value.reminders.supported) }
    }
    private fun taskNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("manual-task-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun dialogNode(tag: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    private fun clickDialog(tag: String) { dialogNode(tag).assertIsEnabled().performClick() }
    private fun open(id: String) { taskNode("reminder-open-" + id).assertIsEnabled().performClick() }
    private fun currentActions(): TaskReminderActions = compose.runOnIdle { checkNotNull(actions.get()) }
    private fun pump() {
        compose.runOnIdle { runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(store) } }
        // IO publishes a new state inside the first callback. Settle recomposition and all route effects
        // before observing callbacks or any assertion dependent on that publication.
        compose.runOnIdle { assertTrue(queue.isEmpty()) }
    }
    private fun assertPrivateSavedState(task: ManualTaskSnapshot) {
        val raw = checkNotNull(saved).performSave().toString()
        for (marker in listOf(task.id, task.title, "2026-10-20", "15:00", "TaskReminderSnapshot", TaskReminders.id(task.id)))
            assertFalse("Private reminder data entered saved state", raw.contains(marker))
    }
}
