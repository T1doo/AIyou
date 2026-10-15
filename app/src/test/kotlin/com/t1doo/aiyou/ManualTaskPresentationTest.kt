package com.t1doo.aiyou

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real app route/controller/Compose. This controlled IO port proves UI acknowledgements only;
 * production SQL/AEAD persistence and migration are tested separately on real host SQLite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
class ManualTaskPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val capture = "合成独立记录草稿"
    private val state = mutableStateOf(LocalRecordUiState(ready = true, opening = false, text = capture,
        entries = listOf(StoredEntry("original", 1, "合成原文：明天买礼物，只是想法", 55, "UTC", ProcessingPolicy.LOCAL_ONLY))))
    private val queued = ArrayDeque<suspend (EntryRepository) -> Unit>()
    private val persisted = linkedMapOf<String, ManualTaskSnapshot>()
    private val lastMutation = mutableMapOf<String, String>()
    private val identities = mutableListOf<Pair<String, String>>()
    private var failWrite = false
    private var failRead = false
    private val dateRequests = mutableListOf<ManualTaskScheduleMutation>()
    private val port = object : ManualTaskRepository {
        override fun snapshots(): List<ManualTaskSnapshot> {
            if (failRead) throw ManualTaskStorageFailure()
            return persisted.values.toList()
        }
        override fun create(request: ManualTaskCreate): ManualTaskSnapshot {
            identities.add(request.id to request.mutationId)
            if (failWrite) throw ManualTaskStorageFailure()
            val expected = ManualTaskSnapshot(request.id, 1, request.title, ManualTaskStatus.OPEN,
                request.createdAtMillis, request.creationTimeZone, request.createdAtMillis, request.schedule)
            persisted[request.id]?.let {
                if (it != expected || lastMutation[request.id] != request.mutationId) throw ManualTaskConflict()
                return it
            }
            lastMutation[request.id] = request.mutationId
            return expected.also { persisted[it.id] = it }
        }
        override fun change(request: ManualTaskMutation): ManualTaskSnapshot {
            identities.add(request.id to request.mutationId)
            if (failWrite) throw ManualTaskStorageFailure()
            val old = checkNotNull(persisted[request.id])
            if (old.version == request.expectedVersion + 1 && old.status == request.status && lastMutation[request.id] == request.mutationId) return old
            if (old.version != request.expectedVersion || old.status != ManualTaskStatus.OPEN) throw ManualTaskConflict()
            lastMutation[request.id] = request.mutationId
            return old.copy(version = old.version + 1, status = request.status,
                updatedAtMillis = maxOf(old.updatedAtMillis, request.requestedAtMillis)).also { persisted[it.id] = it }
        }
        override fun reschedule(request: ManualTaskScheduleMutation): ManualTaskSnapshot {
            identities.add(request.id to request.mutationId); dateRequests.add(request)
            if (failWrite) throw ManualTaskStorageFailure()
            val old = checkNotNull(persisted[request.id])
            if (old.version == request.expectedVersion + 1 && lastMutation[request.id] == request.mutationId &&
                old.status == ManualTaskStatus.OPEN && old.schedule == request.schedule) return old
            if (old.version != request.expectedVersion || old.status != ManualTaskStatus.OPEN) throw ManualTaskConflict()
            ManualTaskDates.validate(request.operation, old.schedule, request.schedule)
            lastMutation[request.id] = request.mutationId
            return old.copy(version = old.version + 1, schedule = request.schedule,
                updatedAtMillis = maxOf(old.updatedAtMillis, request.requestedAtMillis)).also { persisted[it.id] = it }
        }
    }
    private val store: EntryRepository = object : EntryRepository {
        override val manualTasks = port
        override suspend fun readDraft() = TextDraft("draft", 1, capture)
        override suspend fun updateDraft(text: String) = error("Task UI must not write capture")
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String) = error("Task UI must not save capture")
        override suspend fun entries() = state.value.entries
        override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long) = error("Task UI must not revise originals")
    }
    private val controller = ManualTaskController({ state.value }, { state.value = it(state.value) },
        { queued.add(it); true }, Clock.fixed(Instant.parse("2026-10-14T01:30:00Z"), ZoneOffset.UTC), { "Asia/Shanghai" })
    private val callbacks = AtomicReference<ManualTaskCallbacks>()
    private val input = AtomicReference<TextFieldState>()
    private var registry: SaveableStateRegistry? = null
    private val observer = object : ManualTasksObserver {
        override fun callbacks(actions: ManualTaskCallbacks) { callbacks.set(actions) }
        override fun input(state: TextFieldState) { input.set(state) }
    }
    @Test fun explicitCreateWaitsForAckKeepsFailedInputThenCompletesAndCancelsOnlyConfirmedTasks() {
        content()
        compose.onNodeWithText("还没有正式待办").performScrollTo().assertIsDisplayed()
        assertTrue(persisted.isEmpty()); assertEquals(capture, state.value.text)
        begin("明天三点买礼物🙂"); failWrite = true
        compose.onNodeWithTag("manual-task-create").performScrollTo().performClick()
        assertTrue(state.value.tasks.busy); assertNull(state.value.tasks.notice); assertTrue(persisted.isEmpty())
        compose.onNodeWithTag("manual-task-close").performScrollTo().assertIsNotEnabled()
        pump(); assertEquals("明天三点买礼物🙂", editable()); assertNull(state.value.tasks.notice)
        compose.onNodeWithTag("manual-task-input").assertIsNotEnabled()
        failWrite = false; compose.onNodeWithTag("manual-task-dialog-retry").performScrollTo().performClick(); pump()
        assertNull(state.value.tasks.editor); assertEquals(1, persisted.size); assertEquals(1, identities.distinct().size)
        val first = persisted.values.single()
        compose.onNodeWithTag("manual-task-complete-" + first.id).performScrollTo().performClick()
        assertEquals(ManualTaskStatus.OPEN, persisted.getValue(first.id).status); pump()
        assertEquals(ManualTaskStatus.COMPLETED, persisted.getValue(first.id).status)
        begin("第二个合成待办"); compose.onNodeWithTag("manual-task-create").performScrollTo().performClick(); pump()
        val second = persisted.values.single { it.id != first.id }
        compose.onNodeWithTag("manual-task-cancel-" + second.id).performScrollTo().performClick(); pump()
        assertEquals(ManualTaskStatus.CANCELLED, persisted.getValue(second.id).status)
        compose.onNodeWithText("没有系统提醒").performScrollTo().assertIsDisplayed()
        assertEquals(capture, state.value.text); assertEquals(1, state.value.entries.single().currentRevision)
        assertEquals("明天三点买礼物🙂", first.title) // literal expression, no date inferred.
    }
    @Test fun sameTitleTasksUseExactIdentityAndRetainedVersionActionsRejectBeforeRecomposition() {
        persisted["one"] = snapshot("one", "合成同名待办"); persisted["two"] = snapshot("two", "合成同名待办")
        content(); val old = callbacks.get()
        compose.runOnIdle {
            persisted["one"] = persisted.getValue("one").copy(version = 2, status = ManualTaskStatus.CANCELLED, updatedAtMillis = 99)
            state.value = state.value.copy(tasks = state.value.tasks.copy(items = persisted.values.toList()))
            old.change("one", 1, ManualTaskStatus.COMPLETED)
        }
        assertTrue(queued.isEmpty()); assertEquals(ManualTaskStatus.CANCELLED, persisted.getValue("one").status)
        compose.onNodeWithTag("manual-task-complete-two").performScrollTo().performClick(); pump()
        assertEquals(ManualTaskStatus.COMPLETED, persisted.getValue("two").status)
        assertEquals(ManualTaskStatus.CANCELLED, persisted.getValue("one").status)
        compose.runOnIdle { state.value = state.value.copy(error = "合成本机读取失败"); old.begin(); old.change("two", 1, ManualTaskStatus.CANCELLED) }
        compose.waitForIdle(); compose.onAllNodesWithText("合成同名待办").assertCountEquals(0)
        compose.onAllNodesWithText("还没有正式待办").assertCountEquals(0)
        assertTrue(queued.isEmpty()); assertNull(state.value.tasks.editor)
    }
    @Test fun lostReadbackAckReinitializationPreservesOneIdentityAndNeverPublishesFalseEmpty() {
        content(); begin("合成保存待确认"); failRead = true
        compose.onNodeWithTag("manual-task-create").performScrollTo().performClick(); pump()
        assertEquals(1, persisted.size); assertEquals("合成保存待确认", editable()); assertNull(state.value.tasks.notice)
        compose.runOnIdle { runBlocking { controller.initialize(store) } }; pump()
        assertEquals(1, persisted.size); assertEquals(1, identities.distinct().size)
        failRead = false; compose.onNodeWithTag("manual-task-dialog-retry").performScrollTo().performClick(); pump()
        assertNull(state.value.tasks.editor); assertEquals(1, state.value.tasks.items.size)
        failRead = true; compose.onNodeWithTag("manual-task-refresh").performScrollTo().performClick(); pump()
        compose.onAllNodesWithText("合成保存待确认").assertCountEquals(0)
        compose.onAllNodesWithText("还没有正式待办").assertCountEquals(0)
        compose.onNodeWithTag("manual-task-retry").performScrollTo().assertIsEnabled()
        assertEquals(1, persisted.size)
    }
    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun narrowDoubleFontKeepsCreateCloseAndNavigationReachableAndRevokesOldPageCallbacks() {
        content(large = true); begin("合成私密输入")
        compose.onNodeWithTag("manual-task-create").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("manual-task-close").performScrollTo().assertIsDisplayed().performClick()
        val old = callbacks.get()
        compose.onNodeWithText("设置").assertIsDisplayed().performClick()
        compose.onNodeWithText("返回").assertIsDisplayed().performClick()
        compose.runOnIdle { old.begin(); old.create(1, "迟到页面创建") }; assertNull(state.value.tasks.editor); assertTrue(queued.isEmpty())
        compose.onNodeWithTag("manual-task-begin").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("此刻") and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        compose.runOnIdle { old.begin() }; assertNull(state.value.tasks.editor); assertTrue(queued.isEmpty())
        compose.onNodeWithContentDescription("此刻，想记下什么").assertTextEquals(capture)
    }
    @OptIn(ExperimentalFoundationApi::class)
    @Test fun rawSavedStateExcludesTitlesAndEditorUndoAndRestorationRevokesOldNavigation() {
        val restoration = StateRestorationTester(compose)
        runBlocking { controller.initialize(store) }; restoration.setContent { TestScreen(false) }
        begin("合成未确认私密标题")
        val oldActions = callbacks.get(); val oldInput = input.get()
        compose.runOnIdle { assertPrivate(registry!!.performSave(), listOf("合成未确认私密标题", capture, "合成原文")) }
        compose.onNodeWithTag("manual-task-close").performScrollTo().performClick(); begin("")
        compose.runOnIdle { assertNotSame(oldInput, input.get()); assertFalse(input.get().undoState.canUndo); assertFalse(input.get().undoState.canRedo) }
        compose.onNodeWithTag("manual-task-close").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { oldActions.begin(); oldActions.create(1, "恢复后迟到请求") }
        assertNull(state.value.tasks.editor); assertTrue(queued.isEmpty()); assertTrue(persisted.isEmpty())
        compose.runOnIdle { assertPrivate(registry!!.performSave(), listOf("合成未确认私密标题", capture, "合成原文")) }
    }
    @Test fun failedCompletionRetainsStableTargetAndInputWithoutClaimingTaskSuccess() {
        persisted["one"] = snapshot("one", "合成待完成任务"); content(); failWrite = true
        compose.onNodeWithTag("manual-task-complete-one").performScrollTo().performClick(); pump()
        assertEquals(ManualTaskStatus.OPEN, persisted.getValue("one").status); assertNull(state.value.tasks.notice)
        compose.onAllNodesWithText("合成待完成任务").assertCountEquals(0)
        failWrite = false; compose.onNodeWithTag("manual-task-retry").performScrollTo().performClick(); pump()
        assertEquals(ManualTaskStatus.COMPLETED, persisted.getValue("one").status); assertEquals(2L, persisted.getValue("one").version)
        assertEquals(1, identities.distinct().size); assertEquals(capture, state.value.text)
    }
    @Test fun explicitDateOnlyCreateModifyPostponeAndClearWaitForAcknowledgement() {
        content(); begin("明天三点只是标题")
        dateFields("2042-11-19", "", "Asia/Shanghai")
        compose.onNodeWithTag("manual-task-create").performScrollTo().performClick()
        assertTrue(persisted.isEmpty()); assertNull(state.value.tasks.notice); pump()
        val id = persisted.keys.single(); assertNull(persisted.getValue(id).schedule!!.time)
        taskNode("manual-task-date-" + id).assertTextContains("仅日期", substring = true)
        openDate(id); dateFields("2042-11-20", "09:30", "Europe/Paris"); saveDate()
        assertEquals("2042-11-19", persisted.getValue(id).schedule!!.date.toString()); pump()
        assertEquals("Europe/Paris", persisted.getValue(id).schedule!!.timeZone)
        taskNode("manual-task-postpone-" + id).performClick()
        dateFields("2042-11-21", "09:30", "Europe/Paris"); saveDate(); pump()
        assertEquals("2042-11-21", persisted.getValue(id).schedule!!.date.toString())
        taskNode("manual-task-clear-" + id).performClick()
        compose.onNodeWithText("确认清除").performScrollTo().assertIsDisplayed(); saveDate(); pump()
        assertNull(persisted.getValue(id).schedule); assertEquals(4L, persisted.getValue(id).version)
        assertEquals(ManualTaskStatus.OPEN, persisted.getValue(id).status)
        assertEquals("明天三点只是标题", persisted.getValue(id).title); assertEquals(capture, state.value.text)
    }
    @Test fun nonexistentAndRepeatedLocalTimesRequireExplicitCorrectionsAndOffsetChoice() {
        persisted["one"] = snapshot("one", "明天三点"); content()
        assertNull(persisted.getValue("one").schedule); openDate("one")
        dateFields("2026-03-08", "02:30", "America/New_York"); saveDate()
        assertTrue(queued.isEmpty()); assertNull(persisted.getValue("one").schedule)
        dialogError("这个本地时刻因夏令时跳转不存在，请明确选择其他时刻。")
        dateFields("2026-11-01", "01:30", "America/New_York"); saveDate()
        assertTrue(queued.isEmpty())
        dialogError("这个本地时刻出现两次，请明确选择第一次或第二次。")
        compose.onNodeWithTag("manual-task-offset-SECOND").performScrollTo().performClick()
        saveDate(); assertEquals(1L, persisted.getValue("one").version); pump()
        assertEquals(-18_000, persisted.getValue("one").schedule!!.offsetSeconds)
        taskNode("manual-task-date-one").assertTextContains("偏移 -05:00", substring = true)
    }
    @Test fun lostDateReadbackKeepsStableRequestAfterCloseAndNeverShowsFalseEmpty() {
        persisted["one"] = snapshot("one", "合成日期待确认"); content(); openDate("one")
        dateFields("2042-11-19", "", "Asia/Shanghai"); failRead = true; saveDate(); pump()
        assertEquals(2L, persisted.getValue("one").version); assertNull(state.value.tasks.notice)
        compose.onNodeWithTag("manual-task-date-save").performScrollTo().assertIsNotEnabled()
        val request = dateRequests.single()
        compose.onNodeWithTag("manual-task-date-close").performScrollTo().performClick()
        compose.runOnIdle { callbacks.get().beginDate("one", 2, ManualTaskDateOperation.CLEAR) }
        assertNull(state.value.tasks.dateEditor)
        compose.onAllNodesWithText("还没有正式待办").assertCountEquals(0)
        failRead = false; compose.onNodeWithTag("manual-task-retry").performScrollTo().performClick(); pump()
        assertSame(request, dateRequests[1]); assertEquals(2L, persisted.getValue("one").version)
        assertTrue(state.value.tasks.available); assertNull(state.value.tasks.pending)
    }
    @Test fun dateGroupsUseChosenZoneAndTerminalStateRetainsItsPlan() {
        fun due(day: String, zone: String = "Asia/Shanghai") = checkNotNull(ManualTaskDateInput(day, "", zone).parse())
        persisted["none"] = snapshot("none", "明天三点")
        persisted["today"] = snapshot("today", "合成今天").copy(schedule = due("2026-10-14"))
        persisted["next"] = snapshot("next", "合成接下来").copy(schedule = due("2026-10-15"))
        persisted["past"] = snapshot("past", "合成过去").copy(schedule = due("2026-10-13"))
        persisted["done"] = snapshot("done", "合成完成").copy(version = 2, status = ManualTaskStatus.COMPLETED, schedule = due("2026-10-13"))
        persisted["cancel"] = snapshot("cancel", "合成取消").copy(version = 2, status = ManualTaskStatus.CANCELLED, schedule = due("2026-10-15"))
        content()
        for ((group, title) in listOf(ManualTaskGroup.UNDATED to "未设时间 · 待办", ManualTaskGroup.TODAY to "今天",
            ManualTaskGroup.UPCOMING to "接下来", ManualTaskGroup.PAST to "过去 · 未完成",
            ManualTaskGroup.COMPLETED to "已完成", ManualTaskGroup.CANCELLED to "已取消")) {
            taskNode("manual-task-heading-" + group).assertTextEquals(title + " 1")
        }
        taskNode("manual-task-date-today").assertTextContains("仅日期", substring = true).assertTextContains("Asia/Shanghai", substring = true)
        taskNode("manual-task-date-done").assertTextContains("2026-10-13", substring = true)
        compose.onAllNodesWithTag("manual-task-date-open-done").assertCountEquals(0)
        compose.onAllNodesWithTag("manual-task-complete-cancel").assertCountEquals(0)
        assertNull(persisted.getValue("none").schedule)
    }
    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun narrowDoubleFontDateActionsAndOldBusinessCallbacksRespectCloseAndNavigation() {
        persisted["one"] = snapshot("one", "同名合成计划"); persisted["two"] = snapshot("two", "同名合成计划")
        content(true); openDate("one")
        val old = callbacks.get(); val session = checkNotNull(state.value.tasks.dateEditor).session
        dateFields("2042-11-19", "", "Asia/Shanghai")
        for (tag in listOf("manual-task-date-save", "manual-task-date-close")) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            assertTrue(node.getUnclippedBoundsInRoot().height >= 48.dp)
        }
        compose.onNodeWithTag("manual-task-date-close").performClick()
        compose.runOnIdle { old.saveDate(session, ManualTaskDateInput("2042-11-19")) }
        assertTrue(queued.isEmpty()); assertNull(state.value.tasks.dateEditor)
        compose.onNodeWithText("设置").performClick(); compose.onNodeWithText("返回").performClick()
        compose.runOnIdle { old.beginDate("one", 1, ManualTaskDateOperation.SET); old.saveDate(session, ManualTaskDateInput("2042-11-19")) }
        assertTrue(queued.isEmpty()); assertNull(state.value.tasks.dateEditor)
        openDate("two"); val next = checkNotNull(state.value.tasks.dateEditor)
        assertEquals("", next.input.date)
        compose.runOnIdle { old.closeDate(session); old.saveDate(session, ManualTaskDateInput("2042-11-19")) }
        assertEquals(next.session, state.value.tasks.dateEditor!!.session)
        dateFields("2043-01-02", "", "Asia/Shanghai"); saveDate(); pump()
        assertNull(persisted.getValue("one").schedule); assertNotNull(persisted.getValue("two").schedule)
    }
    @Test fun rawSavedStateExcludesPlanFieldsAndRecreationRetiresOldBusinessCallbacks() {
        persisted["one"] = snapshot("one", "合成私密计划标题")
        val restoration = StateRestorationTester(compose)
        runBlocking { controller.initialize(store) }; restoration.setContent { TestScreen(false) }
        openDate("one"); dateFields("2042-11-19", "07:35", "Pacific/Chatham")
        val old = callbacks.get(); val session = checkNotNull(state.value.tasks.dateEditor).session
        compose.runOnIdle { assertPrivate(registry!!.performSave(),
            listOf("2042-11-19", "07:35", "Pacific/Chatham", "合成私密计划标题", capture)) }
        compose.onNodeWithTag("manual-task-date-close").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            old.beginDate("one", 1, ManualTaskDateOperation.SET)
            old.editDate(session, ManualTaskDateInput("2042-11-19")); old.saveDate(session, ManualTaskDateInput("2042-11-19"))
            assertNull(state.value.tasks.dateEditor); assertTrue(queued.isEmpty())
            assertPrivate(registry!!.performSave(), listOf("2042-11-19", "07:35", "Pacific/Chatham", capture))
            controller.dispose(); val frozen = state.value
            callbacks.get().beginDate("one", 1, ManualTaskDateOperation.SET)
            callbacks.get().saveDate(session, ManualTaskDateInput("2042-11-19")); callbacks.get().closeDate(session)
            assertSame(frozen, state.value)
        }
    }
    private fun dateFields(date: String, time: String, zone: String) {
        compose.onNodeWithTag("manual-task-date-input").performScrollTo().performTextReplacement(date)
        compose.onNodeWithTag("manual-task-time-input").performScrollTo().performTextReplacement(time)
        compose.onNodeWithTag("manual-task-zone-input").performScrollTo().performTextReplacement(zone)
        compose.waitForIdle()
    }
    private fun taskNode(tag: String): SemanticsNodeInteraction {
        // Lazy items outside the viewport have no node until the list finds and composes them.
        compose.onNodeWithTag("manual-task-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun dialogError(message: String) {
        compose.onNode(hasText(message) and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
    }
    private fun openDate(id: String) { taskNode("manual-task-date-open-" + id).performClick(); compose.waitForIdle() }
    private fun saveDate() { compose.onNodeWithTag("manual-task-date-save").performScrollTo().performClick() }
    private fun content(large: Boolean = false) { runBlocking { controller.initialize(store) }; compose.setContent { TestScreen(large) }; compose.waitForIdle() }
    @Composable private fun TestScreen(large: Boolean) {
        val saved = LocalSaveableStateRegistry.current; SideEffect { registry = saved }
        val density = LocalDensity.current
        CompositionLocalProvider(LocalManualTasksObserver provides observer,
            LocalDensity provides if (large) Density(density.density, 2f) else density) {
            Box(if (large) Modifier.size(320.dp, 640.dp) else Modifier) {
                AIyouTheme { AIyouApp(previewDestination = Destination.TASKS, local = state.value, currentRecords = { state.value },
                    onTaskBegin = controller::begin, onTaskEdit = controller::edit, onTaskCreate = controller::create,
                    onTaskChange = controller::change, onTaskRetry = controller::retry, onTaskRefresh = controller::refresh,
                    onTaskClose = controller::close, onTaskCreationDate = controller::editCreationDate,
                    onTaskBeginDate = controller::beginDate, onTaskEditDate = controller::editDate,
                    onTaskSaveDate = controller::saveDate, onTaskCloseDate = controller::closeDate,
                    taskClock = Clock.fixed(Instant.parse("2026-10-14T01:30:00Z"), ZoneOffset.UTC)) }
            }
        }
    }
    private fun begin(title: String) {
        compose.onNodeWithTag("manual-task-begin").performScrollTo().performClick()
        if (title.isNotEmpty()) compose.onNodeWithTag("manual-task-input").performScrollTo().performTextReplacement(title)
        compose.waitForIdle()
    }
    private fun pump() { compose.runOnIdle { runBlocking { queued.removeFirst()(store) } }; compose.waitForIdle() }
    private fun editable() = compose.onNodeWithTag("manual-task-input").performScrollTo().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun snapshot(id: String, title: String) = ManualTaskSnapshot(id, 1, title, ManualTaskStatus.OPEN, 55, "UTC", 55)
    private fun assertPrivate(value: Any?, markers: List<String>) {
        when (value) {
            is CharSequence -> markers.forEach { assertFalse(value.contains(it)) }
            is Map<*, *> -> value.forEach { (key, item) -> assertPrivate(key, markers); assertPrivate(item, markers) }
            is Iterable<*> -> value.forEach { assertPrivate(it, markers) }
            is Array<*> -> value.forEach { assertPrivate(it, markers) }
        }
    }
}
