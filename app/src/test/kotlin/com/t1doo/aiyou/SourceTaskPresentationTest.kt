package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual App route, literal-source dialog and controller. Controlled persistence port; real SQL tested separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
class SourceTaskPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val original="合成🙂原文：‘明天三点’只是引用，尚未决定做什么。"
    private var entry=StoredEntry("one",1,original,50,"UTC",ProcessingPolicy.AI_NONE)
    private val binding=TaskSourceBinding(SourceVersion("one",1),OriginalExports.hash(original),5,0,original.codePointCount(0,original.length))
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,entries=listOf(entry),supportsSourceTasks=true,
        supportsLocalProcessing=true,localProcessingVersions=mapOf("one" to LocalProcessingVersion(binding.source,5)),
        tasks=ManualTasksUiState(supported=true,available=true)))
    private val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
    private val creates=mutableListOf<SourceTaskCreate>()
    private var task:ManualTaskSnapshot?=null
    private var loseReadback=false;private var failReadback=false
    private val tasks=object:ManualTaskRepository {
        override fun snapshots():List<ManualTaskSnapshot> {if(failReadback) throw ManualTaskStorageFailure();return listOfNotNull(task)}
        override fun create(request:ManualTaskCreate)=error("Manual creation not requested")
        override fun change(request:ManualTaskMutation)=error("Status not requested")
        override fun reschedule(request:ManualTaskScheduleMutation)=error("Date mutation not requested")
    }
    private fun checkSource() {if(entry.currentRevision!=1||OriginalExports.hash(entry.originalText)!=binding.sourceHash) throw SourceTaskConflict()}
    private val port=object:SourceTaskRepository {
        override fun preview(binding:TaskSourceBinding):SourceTaskPreview {checkSource();check(binding==this@SourceTaskPresentationTest.binding);return SourceTaskPreview(binding,original,task)}
        override fun create(request:SourceTaskCreate):ManualTaskSnapshot {
            creates+=request
            if(task==null) {checkSource();task=ManualTaskSnapshot(request.task.id,1,request.task.title,ManualTaskStatus.OPEN,request.task.createdAtMillis,request.task.creationTimeZone,request.task.createdAtMillis,
                request.task.schedule,SourceTasks.receipt(request))}
            else if(task!!.sourceReceipt!=SourceTasks.receipt(request)) throw SourceTaskConflict()
            if(loseReadback) {loseReadback=false;failReadback=true}
            return task!!
        }
        override fun locate(taskId:String,version:Long,publish:(TaskSourceBinding)->Unit):Boolean {checkSource();check(task?.id==taskId&&task?.version==version);publish(binding);return true}
    }
    private val owner=object:EntryRepository {
        override val sourceTasks=port
        override val manualTasks=tasks
        override suspend fun readDraft()=null
        override suspend fun updateDraft(text:String)=error("Capture not requested")
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("Capture not requested")
        override suspend fun entries()=state.value.entries
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("Revision not requested")
    }
    private val controller=SourceTaskController({state.value},{state.value=it(state.value)},{queue.add(it);true},Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC))
    private val actions=AtomicReference<SourceTaskDialogActions>()
    private var registry:SaveableStateRegistry?=null
    @After fun dispose() {controller.dispose()}
    @Composable private fun Screen() {
        val saved=LocalSaveableStateRegistry.current;SideEffect {registry=saved}
        val density=LocalDensity.current
        CompositionLocalProvider(LocalSourceTaskDialogObserver provides SourceTaskDialogObserver {actions.set(it)},
            LocalDensity provides Density(density.density,2f)) {
            AIyouTheme {Box(Modifier.size(320.dp,640.dp)) {
                AIyouApp(previewDestination=Destination.MEMORIES,local=state.value,currentRecords={state.value},sourceTasks=controller)
            }}
        }
    }
    private fun click(tag:String) {compose.onNodeWithTag(tag).performScrollTo().performClick()}
    private fun drain() {compose.runOnIdle {runBlocking {while(true) (queue.poll()?:break)(owner)}};compose.waitForIdle()}
    private fun reader() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-one"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-one"))).performClick()
    }
    private fun open() {click("original-source-task");drain()}
    private fun title() {compose.onNodeWithTag("source-task-title").performScrollTo().performTextReplacement("合成人工确认的标题")}

    @Test fun narrowDoubleFontLiteralFlowRequiresSeparateConfirmationAndJumpsToExactOriginal() {
        compose.setContent {Screen()};reader();open();title()
        assertNull(state.value.sourceTask!!.date.parse());assertTrue(creates.isEmpty())
        compose.onNodeWithTag("manual-task-date-input").performScrollTo().performTextReplacement("2026-10-20")
        click("source-task-prepare");compose.onNodeWithTag("source-task-confirm-title").performScrollTo().assertTextEquals("合成人工确认的标题")
        click("source-task-cancel-confirm");assertTrue(creates.isEmpty());click("source-task-prepare")
        val confirm=compose.onNodeWithTag("source-task-confirm").performScrollTo().assertIsDisplayed()
        assertTrue(confirm.getUnclippedBoundsInRoot().height>=48.dp);confirm.performClick();assertNull(task);drain()
        assertEquals(1,creates.size);assertEquals("2026-10-20",task!!.schedule!!.date.toString());assertNull(task!!.schedule!!.time)
        assertEquals(original,state.value.entries.single().originalText);assertEquals(ProcessingPolicy.AI_NONE,state.value.entries.single().policy)
        click("source-task-close")
        compose.onAllNodesWithTag("source-task-dialog").assertCountEquals(0)
        compose.onNodeWithTag("original-text").assertTextEquals(original)
        // Closing the source form keeps the reader open; its bottom navigation is intentionally absent.
        compose.onNodeWithText("返回列表").performClick()
        compose.onNodeWithText("待办").assertIsDisplayed().performClick()
        click("manual-task-source-"+task!!.id);drain()
        compose.onNodeWithTag("original-text").assertTextEquals(original)
        compose.runOnIdle {entry=entry.copy(currentRevision=2,originalText="合成不同新版本");state.value=state.value.copy(entries=listOf(entry))}
        compose.onNodeWithText("待办来源版本已变化").assertIsDisplayed();compose.onAllNodesWithTag("original-text").assertCountEquals(0)
    }
    @Test fun unknownReadbackHasNoSuccessAndRetriesSameRequestWithoutDuplicatingTask() {
        compose.setContent {Screen()};reader();open();title();click("source-task-prepare");loseReadback=true
        click("source-task-confirm");drain()
        compose.onNodeWithTag("source-task-error").performScrollTo().assertIsDisplayed();compose.onAllNodesWithTag("source-task-notice").assertCountEquals(0)
        assertNotNull(state.value.sourceTask!!.pending);assertFalse(state.value.tasks.available);assertNotNull(task)
        failReadback=false;click("source-task-retry");drain()
        assertSame(creates[0],creates[1]);assertEquals(1L,task!!.version);assertEquals(1,state.value.tasks.items.size)
    }
    @Test fun sourceReadFailureAndRevisionRefreshHideQuoteAndConfirmationButRetainInput() {
        compose.setContent {Screen()};reader();open();title();click("source-task-prepare")
        val retained=actions.get().confirm;val valid=state.value
        val unavailable=listOf(valid.copy(error="合成读取失败"),valid.copy(opening=true),valid.copy(localProcessingAvailable=false),
            valid.copy(entries=listOf(entry.copy(currentRevision=2))),
            valid.copy(entries=listOf(entry.copy(originalText="合成修改后的原文"))),valid.copy(entries=emptyList()),
            valid.copy(localProcessingVersions=mapOf("one" to LocalProcessingVersion(binding.source,6))))
        for(value in unavailable) {
            compose.runOnIdle {state.value=value}
            compose.onAllNodesWithTag("source-task-quote").assertCountEquals(0)
            compose.onAllNodesWithTag("source-task-confirm-title").assertCountEquals(0)
            compose.onAllNodesWithTag("source-task-confirm").assertCountEquals(0)
            compose.onNodeWithTag("source-task-unavailable").performScrollTo().assertIsDisplayed()
            compose.runOnIdle {retained();assertTrue(queue.isEmpty());assertEquals("合成人工确认的标题",state.value.sourceTask!!.title)}
        }
        assertNull(task)
    }
    @Test fun cancelledRestoredAndNavigatedConfirmationsCannotReusePrivateSourceOrInput() {
        val restoration=StateRestorationTester(compose);restoration.setContent {Screen()};reader();open();title();click("source-task-prepare")
        val old=actions.get();click("source-task-cancel-confirm");click("source-task-prepare")
        compose.runOnIdle {old.confirm();old.cancel();assertTrue(queue.isEmpty())}
        val fresh=actions.get().confirm
        compose.runOnIdle {val raw=checkNotNull(registry).performSave().toString();assertFalse(raw.contains(original));assertFalse(raw.contains("合成人工确认的标题"));assertFalse(raw.contains(binding.sourceHash))}
        restoration.emulateSavedInstanceStateRestore();compose.waitForIdle()
        compose.runOnIdle {old.confirm();fresh();assertTrue(queue.isEmpty())};assertNull(state.value.sourceTask);assertNull(task)
        open();title();click("source-task-prepare");val retained=actions.get().confirm
        val back=compose.onNodeWithText("返回列表").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle {back();retained();assertTrue(queue.isEmpty())};assertNull(task)
    }
}
