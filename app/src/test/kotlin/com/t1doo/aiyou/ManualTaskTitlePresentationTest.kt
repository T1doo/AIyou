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

/** Real App route, private field, confirmation dialog and production controller. Controlled ports
 * prove presentation/acknowledgement boundaries; encrypted SQL and device UI are separate gates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class ManualTaskTitlePresentationTest {
    @get:Rule val compose = createComposeRule()
    private val capture = "合成独立输入：编辑待办不能覆盖原文"
    private val corrected = "合成明确更正后的私密标题🙂"
    private val clock = Clock.fixed(Instant.parse("2026-10-16T15:00:00Z"), ZoneOffset.UTC)
    private val schedule = ManualTaskDateInput("2042-11-19", "09:30", "Asia/Shanghai").parse()!!
    private val persisted = linkedMapOf<String, ManualTaskSnapshot>()
    private val accepted = mutableMapOf<String, ManualTaskRename>()
    private val requests = mutableListOf<ManualTaskRename>()
    private val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
    private val state = mutableStateOf(LocalRecordUiState(ready = true, opening = false, text = capture,
        entries = listOf(StoredEntry("synthetic-independent-original", 1, "合成独立原文", 1, "UTC", ProcessingPolicy.LOCAL_ONLY))))
    private val actions = AtomicReference<ManualTaskTitleActions>()
    private var saved: SaveableStateRegistry? = null
    private var failReadback = false
    private val port = object : ManualTaskRepository {
        override fun snapshots(): List<ManualTaskSnapshot> {
            if (failReadback) throw ManualTaskStorageFailure()
            return persisted.values.toList()
        }
        override fun create(request: ManualTaskCreate): ManualTaskSnapshot = error("Title UI must not create tasks")
        override fun change(request: ManualTaskMutation): ManualTaskSnapshot = error("Title UI must not change task status")
        override fun reschedule(request: ManualTaskScheduleMutation): ManualTaskSnapshot = error("Title UI must not change dates")
        override fun rename(request: ManualTaskRename): ManualTaskSnapshot {
            requests += request
            val old = persisted[request.id] ?: throw ManualTaskConflict()
            // A controlled lost-readback replay returns the same durable version. Actual SQL/AEAD
            // replay, rollback and request proof are independently exercised by host SQL tests.
            if (accepted[request.id] === request && old.version == request.expectedVersion + 1) return old
            if (old.version != request.expectedVersion || old.status != ManualTaskStatus.OPEN) throw ManualTaskConflict()
            accepted[request.id] = request
            return old.copy(title = request.title, version = old.version + 1,
                updatedAtMillis = request.requestedAtMillis).also { persisted[it.id] = it }
        }
    }
    private val store = object : EntryRepository {
        override val manualTasks = port
        override suspend fun readDraft() = TextDraft("synthetic-draft", 1, capture)
        override suspend fun updateDraft(text: String): TextDraft = error("Title UI must not overwrite original input")
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Title UI must not save original input")
        override suspend fun entries() = state.value.entries
        override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Title UI must not revise originals")
    }
    private val controller = ManualTaskController({ state.value }, { state.value = it(state.value) },
        { queue.addLast(it); true }, clock) { "UTC" }

    @After fun dispose() = controller.dispose()

    @Test fun explicitTitleCorrectionRequiresReviewAndConfirmationWithoutSavingPrivateInput() {
        val original = task("synthetic-private-title-one", "合成原有私密标题")
        val task = original.copy(reminder = TaskReminders.confirm(original, TaskReminderMode.EXACT))
        persisted[task.id] = task
        content()
        open(task.id)
        dialogNode("manual-task-title-original").assertTextEquals(task.title)
        input("　" + corrected + "　")
        compose.runOnIdle {
            assertTrue(requests.isEmpty()); assertTrue(queue.isEmpty())
            assertNull(state.value.tasks.titleEditor!!.confirmation)
            assertPrivateSavedState(task, corrected)
        }
        clickDialog("manual-task-title-prepare")
        dialogNode("manual-task-title-original").assertTextEquals(task.title)
        dialogNode("manual-task-title-new").assertTextEquals(corrected)
        compose.runOnIdle {
            assertTrue(requests.isEmpty()); assertTrue(queue.isEmpty())
            assertEquals(task, persisted.getValue(task.id))
            assertEquals(corrected, state.value.tasks.titleEditor!!.confirmation!!.title)
            assertPrivateSavedState(task, corrected)
        }
        clickDialog("manual-task-title-confirm")
        compose.runOnIdle {
            assertTrue(requests.isEmpty()); assertEquals(1, queue.size)
            assertTrue(state.value.tasks.busy); assertNull(state.value.tasks.notice)
            assertEquals(task, persisted.getValue(task.id))
        }
        pump()
        compose.runOnIdle {
            val savedTask = persisted.getValue(task.id)
            assertEquals(task.copy(title = corrected, version = 2, updatedAtMillis = clock.millis()), savedTask)
            assertEquals(task.reminder, savedTask.reminder)
            assertEquals(task.schedule, savedTask.schedule)
            assertEquals(task.id, requests.single().id)
            assertNull(state.value.tasks.titleEditor); assertNull(state.value.tasks.pending)
            assertEquals("标题修改已保存在本机", state.value.tasks.notice)
            assertEquals(capture, state.value.text)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, state.value.entries.single().policy)
            assertPrivateSavedState(task, corrected)
        }
        taskNode("manual-task-row-" + task.id).assert(hasAnyDescendant(hasText(corrected)))
        compose.onAllNodesWithTag("manual-task-title-confirm").assertCountEquals(0)
    }

    @Test fun unconfirmedTitleCanBeCancelledAndUnknownCommitHasNoSuccessUntilExactRetry() {
        val task = task("synthetic-private-title-two", "合成仍然保持的原标题")
        persisted[task.id] = task
        content(); open(task.id); input(corrected)
        clickDialog("manual-task-title-prepare")
        clickDialog("manual-task-title-back")
        dialogNode("manual-task-title-input").assertTextContains(corrected)
        compose.runOnIdle { assertTrue(requests.isEmpty()); assertTrue(queue.isEmpty()); assertEquals(task, persisted.getValue(task.id)) }
        clickDialog("manual-task-title-prepare")
        clickDialog("manual-task-title-close")
        compose.runOnIdle {
            assertTrue(requests.isEmpty()); assertTrue(queue.isEmpty())
            assertNull(state.value.tasks.titleEditor); assertEquals(task, persisted.getValue(task.id))
            assertPrivateSavedState(task, corrected)
        }

        open(task.id); input(corrected); clickDialog("manual-task-title-prepare")
        val frozen = compose.runOnIdle { checkNotNull(state.value.tasks.titleEditor!!.confirmation) }
        compose.runOnIdle { failReadback = true }
        clickDialog("manual-task-title-confirm"); pump()
        dialogNode("manual-task-title-confirm").assertIsNotEnabled()
        dialogNode("manual-task-title-back").assertIsNotEnabled()
        dialogNode("manual-task-title-close").assertIsNotEnabled()
        compose.onAllNodesWithText("标题修改已保存在本机").assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(1, requests.size); assertSame(frozen, requests.single())
            assertEquals(2L, persisted.getValue(task.id).version)
            assertEquals(corrected, persisted.getValue(task.id).title)
            assertNull(state.value.tasks.notice); assertNotNull(state.value.tasks.error)
            assertSame(frozen, state.value.tasks.titleEditor!!.confirmation)
            assertEquals(capture, state.value.text); assertPrivateSavedState(task, corrected)
            failReadback = false
        }
        clickDialog("manual-task-title-retry"); pump()
        compose.runOnIdle {
            assertEquals(2, requests.size); assertTrue(requests.all { it === frozen })
            assertEquals(2L, persisted.getValue(task.id).version)
            assertEquals(corrected, persisted.getValue(task.id).title)
            assertNull(state.value.tasks.pending); assertNull(state.value.tasks.titleEditor)
            assertEquals("标题修改已保存在本机", state.value.tasks.notice)
            assertEquals(task.schedule, persisted.getValue(task.id).schedule)
            assertEquals(capture, state.value.text); assertPrivateSavedState(task, corrected)
        }
    }

    @Test fun sameTitleTasksRejectRetiredDialogAndRouteCallbacks() {
        val first = task("synthetic-same-title-one", "合成两个同名待办")
        val second = task("synthetic-same-title-two", first.title)
        persisted[first.id] = first; persisted[second.id] = second
        content(); open(first.id); input("第一条不会写入的合成更正")
        clickDialog("manual-task-title-prepare")
        val stale = currentActions()
        clickDialog("manual-task-title-close")
        open(second.id); input(corrected); clickDialog("manual-task-title-prepare")
        val current = compose.runOnIdle { checkNotNull(state.value.tasks.titleEditor) }
        compose.runOnIdle { stale.confirm(); stale.back(); stale.close() }
        compose.runOnIdle {
            assertTrue(queue.isEmpty()); assertTrue(requests.isEmpty())
            assertEquals(second.id, state.value.tasks.titleEditor!!.task.id)
            assertEquals(current.session, state.value.tasks.titleEditor!!.session)
            assertSame(current.confirmation, state.value.tasks.titleEditor!!.confirmation)
        }
        clickDialog("manual-task-title-confirm"); pump()
        compose.runOnIdle {
            assertEquals(second.id, requests.single().id)
            assertEquals(first, persisted.getValue(first.id))
            assertEquals(corrected, persisted.getValue(second.id).title)
        }

        open(first.id); input("路由失效前的合成输入"); clickDialog("manual-task-title-prepare")
        val oldRoute = currentActions()
        clickDialog("manual-task-title-close")
        compose.onNodeWithText("设置").assertIsDisplayed().performClick()
        compose.onNodeWithText("返回").assertIsDisplayed().performClick()
        open(first.id); input("新会话的合成输入"); clickDialog("manual-task-title-prepare")
        val fresh = compose.runOnIdle { checkNotNull(state.value.tasks.titleEditor) }
        compose.runOnIdle { oldRoute.confirm(); oldRoute.back(); oldRoute.close(); stale.confirm() }
        compose.runOnIdle {
            assertEquals(1, requests.size); assertTrue(queue.isEmpty())
            assertEquals(fresh.session, state.value.tasks.titleEditor!!.session)
            assertSame(fresh.confirmation, state.value.tasks.titleEditor!!.confirmation)
            assertEquals(first, persisted.getValue(first.id)); assertEquals(capture, state.value.text)
        }
        clickDialog("manual-task-title-close")
        compose.onNode(hasText("此刻") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.runOnIdle { oldRoute.confirm(); stale.confirm(); assertEquals(1, requests.size); assertTrue(queue.isEmpty()) }
        compose.onNodeWithContentDescription("此刻，想记下什么").assertTextEquals(capture)
    }

    private fun task(id: String, title: String) = ManualTaskSnapshot(id, 1, title, ManualTaskStatus.OPEN, 1, "UTC", 1, schedule)
    private fun content() {
        runBlocking { controller.initialize(store) }
        compose.setContent {
            val registry = LocalSaveableStateRegistry.current
            SideEffect { saved = registry }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalManualTaskTitleObserver provides ManualTaskTitleObserver { actions.set(it) },
                LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.size(320.dp, 640.dp)) {
                    AIyouTheme { AIyouApp(previewDestination = Destination.TASKS, local = state.value,
                        currentRecords = { state.value }, manualTasks = controller, taskClock = clock) }
                }
            }
        }
        compose.runOnIdle { assertTrue(state.value.tasks.available) }
    }
    private fun taskNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("manual-task-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun dialogNode(tag: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    private fun clickDialog(tag: String) { dialogNode(tag).assertIsEnabled().performClick() }
    private fun open(id: String) { taskNode("manual-task-title-open-" + id).assertIsEnabled().performClick() }
    private fun input(text: String) { dialogNode("manual-task-title-input").performTextReplacement(text) }
    private fun currentActions(): ManualTaskTitleActions = compose.runOnIdle { checkNotNull(actions.get()) }
    private fun pump() {
        compose.runOnIdle { runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(store) } }
        compose.runOnIdle { assertTrue(queue.isEmpty()) }
    }
    private fun assertPrivateSavedState(task: ManualTaskSnapshot, input: String) {
        val raw = checkNotNull(saved).performSave().toString()
        for (marker in listOf(task.id, task.title, input, "ManualTaskRename", "ManualTaskTitleEditor", "2042-11-19", "09:30"))
            assertFalse("Private task title or confirmation entered saved state", raw.contains(marker))
    }
}
