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

/** Actual App/dialog/controller. Persistence port is controlled; real SQL/AEAD is tested separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
class TaskProposalPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val original="合成🙂原文：明天三点只是引用，不代表已决定的待办。"
    private var entry=StoredEntry("one",1,original,50,"UTC",ProcessingPolicy.AI_NONE)
    private val binding=TaskSourceBinding(SourceVersion("one",1),OriginalExports.hash(original),5,0,original.codePointCount(0,original.length))
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,entries=listOf(entry),supportsSourceTasks=true,
        supportsLocalProcessing=true,localProcessingVersions=mapOf("one" to LocalProcessingVersion(binding.source,5)),
        tasks=ManualTasksUiState(supported=true,available=true),taskProposals=TaskProposalsUiState(supported=true)))
    private val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
    private val drafts=linkedMapOf<String,TaskProposal>();private val tasks=linkedMapOf<String,ManualTaskSnapshot>()
    private val writes=mutableListOf<TaskProposalWrite>();private val confirms=mutableListOf<TaskProposalConfirm>()
    private var loseReadback=false;private var failReadback=false;private var sourceMetadataAvailable=true
    private fun sourceValid()=entry.currentRevision==1&&OriginalExports.hash(entry.originalText)==binding.sourceHash
    private val port=object:TaskProposalRepository {
        override fun list():List<TaskProposalItem> {if(failReadback) throw TaskProposalStorageFailure();return drafts.values.map {TaskProposalItem(it,sourceMetadataAvailable&&sourceValid())}}
        override fun write(request:TaskProposalWrite):TaskProposal {
            writes+=request;val old=drafts[request.id]
            if(old?.version!=request.expectedVersion&&!(old==null&&request.expectedVersion==0L)) throw TaskProposalConflict()
            if(request.action==TaskProposalAction.SAVE&&!sourceValid()) throw TaskProposalConflict()
            return TaskProposal(request.id,request.expectedVersion+1,request.binding,request.titleInput,request.dateInput,request.createdAtMillis,
                request.creationTimeZone,request.atMillis,if(request.action==TaskProposalAction.SAVE) TaskProposalState.DRAFT else TaskProposalState.REJECTED)
                .also {drafts[it.id]=it}
        }
        override fun confirm(request:TaskProposalConfirm):ManualTaskSnapshot {
            confirms+=request
            val existing=tasks[request.task.id]
            if(existing!=null) {check(existing.sourceReceipt==TaskProposals.receipt(request));return existing}
            if(!sourceValid()||drafts[request.proposal.id]!=request.proposal) throw TaskProposalConflict()
            val r=request.task;val saved=ManualTaskSnapshot(r.id,1,r.title,ManualTaskStatus.OPEN,r.createdAtMillis,r.creationTimeZone,r.createdAtMillis,r.schedule,TaskProposals.receipt(request))
            tasks[saved.id]=saved;drafts.remove(request.proposal.id)
            if(loseReadback) {loseReadback=false;failReadback=true};return saved
        }
    }
    private val owner=object:EntryRepository {
        override val taskProposals=port
        override val manualTasks=object:ManualTaskRepository {
            override fun snapshots()=tasks.values.toList()
            override fun create(request:ManualTaskCreate)=error("Not requested")
            override fun change(request:ManualTaskMutation)=error("Not requested")
            override fun rename(request: com.t1doo.aiyou.model.ManualTaskRename): ManualTaskSnapshot = error("Title editing not requested by this test")
            override fun reschedule(request:ManualTaskScheduleMutation)=error("Not requested")
        }
        override suspend fun readDraft()=null
        override suspend fun updateDraft(text:String)=error("Not requested")
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("Not requested")
        override suspend fun entries()=state.value.entries
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("Not requested")
    }
    private val clock=Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC)
    private fun newController()=TaskProposalController({state.value},{state.value=it(state.value)},{queue.add(it);true},clock)
    private var proposals by mutableStateOf(newController())
    private val source=SourceTaskController({state.value},{state.value=it(state.value)},{queue.add(it);true},clock,proposalOpen={proposals.open(it)})
    private val actions=AtomicReference<TaskProposalActions>();private var registry:SaveableStateRegistry?=null
    @After fun dispose() {proposals.dispose();source.dispose()}
    @Composable private fun Screen() {
        val saved=LocalSaveableStateRegistry.current;SideEffect {registry=saved}
        val density=LocalDensity.current
        CompositionLocalProvider(LocalTaskProposalObserver provides TaskProposalObserver {actions.set(it)},LocalDensity provides Density(density.density,2f)) {
            AIyouTheme {Box(Modifier.size(320.dp,640.dp)) {
                AIyouApp(previewDestination=Destination.MEMORIES,local=state.value,currentRecords={state.value},sourceTasks=source,taskProposals=proposals)
            }}
        }
    }
    private fun click(tag:String) {compose.onNodeWithTag(tag).performScrollTo().performClick()}
    private fun taskNode(tag:String):SemanticsNodeInteraction {
        // Find and compose offscreen LazyColumn items before looking up their child nodes.
        compose.onNodeWithTag("manual-task-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun drain() {compose.runOnIdle {runBlocking {while(true) (queue.poll()?:break)(owner)}};compose.waitForIdle()}
    private fun reader() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-one"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-one"))).performClick()
    }
    private fun open() {reader();click("original-source-task");drain()}
    private fun title(text:String="合成人工待确认标题") {compose.onNodeWithTag("task-proposal-title").performScrollTo().performTextReplacement(text);drain()}
    @Test fun narrowDraftColdRestoresInPendingGroupThenActualButtonRequiresSeparateConfirmation() {
        compose.setContent {Screen()};compose.runOnIdle {proposals.initialize(owner)};open();title()
        compose.onNodeWithTag("manual-task-date-input").performScrollTo().performTextReplacement("2026-10-20");drain()
        compose.onNodeWithTag("task-proposal-saved").performScrollTo().assertIsDisplayed();assertTrue(tasks.isEmpty())
        val id=state.value.taskProposals.editor!!.id;click("task-proposal-close");drain()
        compose.onNodeWithText("返回列表").performClick();compose.onNodeWithText("待办").performClick()
        compose.runOnIdle {proposals.dispose();proposals=newController();proposals.initialize(owner)}
        taskNode("task-proposal-heading").assertTextEquals("待确认草稿 1")
        taskNode("task-proposal-open-"+id).assertIsEnabled().performClick();compose.onNodeWithTag("task-proposal-title").performScrollTo().assertTextContains("合成人工待确认标题")
        click("task-proposal-prepare");compose.onNodeWithTag("task-proposal-confirm-title").performScrollTo().assertTextEquals("合成人工待确认标题")
        assertTrue(queue.isEmpty());click("task-proposal-cancel-confirm");assertTrue(tasks.isEmpty())
        click("task-proposal-prepare");click("task-proposal-confirm");assertTrue(tasks.isEmpty());drain()
        assertEquals(1,confirms.size);assertEquals("2026-10-20",tasks.values.single().schedule!!.date.toString());assertNull(tasks.values.single().schedule!!.time)
        click("task-proposal-close");drain();taskNode("task-proposal-heading").assertTextEquals("待确认草稿 0")
        taskNode("manual-task-row-"+tasks.values.single().id).assertIsDisplayed()
    }
    @Test fun uncertainReadbackRetainsExactConfirmationAndNeverShowsPrematureSuccess() {
        compose.setContent {Screen()};compose.runOnIdle {proposals.initialize(owner)};open();title();click("task-proposal-prepare")
        loseReadback=true;click("task-proposal-confirm");drain()
        compose.onNodeWithTag("task-proposal-error").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("task-proposal-notice").assertCountEquals(0);assertNotNull(state.value.taskProposals.editor!!.pending)
        assertEquals(1,tasks.size);assertTrue(drafts.isEmpty());failReadback=false;click("task-proposal-retry");drain()
        assertSame(confirms[0],confirms[1]);assertEquals(1,tasks.size);assertEquals(1,state.value.tasks.items.size)
    }
    @Test fun changedSourceHidesQuoteAndOldConfirmationThenAllowsExplicitRejectionWithoutBundleInput() {
        val restoration=StateRestorationTester(compose);restoration.setContent {Screen()}
        compose.runOnIdle {proposals.initialize(owner)};open();title();click("task-proposal-prepare");val old=actions.get().confirm
        compose.runOnIdle {
            val raw=checkNotNull(registry).performSave().toString();assertFalse(raw.contains(original));assertFalse(raw.contains("合成人工待确认标题"));assertFalse(raw.contains(binding.sourceHash))
            sourceMetadataAvailable=false;proposals.refresh()
        }
        drain()
        compose.onAllNodesWithTag("task-proposal-quote").assertCountEquals(0);compose.onAllNodesWithTag("task-proposal-confirm").assertCountEquals(0)
        compose.runOnIdle {old();assertTrue(queue.isEmpty())}
        compose.runOnIdle {
            entry=entry.copy(currentRevision=2,originalText="合成新的原文版本");state.value=state.value.copy(entries=listOf(entry))
        }
        compose.onAllNodesWithTag("task-proposal-quote").assertCountEquals(0);compose.onAllNodesWithTag("task-proposal-confirm").assertCountEquals(0)
        compose.onNodeWithTag("task-proposal-source-conflict").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {old();assertTrue(queue.isEmpty());proposals.invalidateSource("one")}
        click("task-proposal-reject");drain();assertTrue(tasks.isEmpty());assertEquals(TaskProposalState.REJECTED,drafts.values.single().state)
        click("task-proposal-close");drain();restoration.emulateSavedInstanceStateRestore();compose.waitForIdle()
        compose.runOnIdle {old();assertTrue(queue.isEmpty())};assertTrue(tasks.isEmpty())
    }
    @Test fun refreshedSourceReadFailureAndOtherOwnerVersionChangeKeepInputsButRevokeUnqualifiedRecoveryReceipt() {
        compose.setContent {Screen()};compose.runOnIdle {proposals.initialize(owner)};open();title()
        compose.onNodeWithTag("manual-task-date-input").performScrollTo().performTextReplacement("2026-10-20");drain()
        compose.onNodeWithTag("task-proposal-saved").performScrollTo().assertIsDisplayed()
        click("task-proposal-prepare");val old=actions.get().confirm
        val p1=state.value.taskProposals.editor!!.saved!!;val writeCount=writes.size
        compose.runOnIdle {sourceMetadataAvailable=false;proposals.refresh()}
        compose.onNodeWithTag("task-proposal-save-unknown").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("task-proposal-saved").assertCountEquals(0);drain()
        compose.onNodeWithTag("task-proposal-saved-source-unavailable").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("task-proposal-confirm").assertCountEquals(0)
        compose.runOnIdle {failReadback=true;proposals.refresh()};drain()
        compose.onNodeWithTag("task-proposal-save-unknown").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("task-proposal-saved").assertCountEquals(0)
        compose.onAllNodesWithTag("task-proposal-saved-source-unavailable").assertCountEquals(0)
        compose.runOnIdle {
            failReadback=false;sourceMetadataAvailable=true
            drafts[p1.id]=p1.copy(version=p1.version+1,titleInput="合成另一 owner 的标题",dateInput=p1.dateInput.copy(date="2026-10-21"),updatedAtMillis=110)
            proposals.refresh()
        };drain()
        compose.onNodeWithTag("task-proposal-save-changed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("task-proposal-title").performScrollTo().assertTextContains(p1.titleInput)
        compose.onNodeWithTag("manual-task-date-input").performScrollTo().assertTextContains(p1.dateInput.date)
        compose.onAllNodesWithTag("task-proposal-saved").assertCountEquals(0)
        compose.onAllNodesWithTag("task-proposal-saved-source-unavailable").assertCountEquals(0)
        compose.onAllNodesWithText("关闭并保留草稿").assertCountEquals(0)
        compose.runOnIdle {
            val e=state.value.taskProposals.editor!!;assertEquals(p1,e.saved);assertEquals(p1.titleInput,e.title);assertEquals(p1.dateInput,e.date)
            old();assertTrue(queue.isEmpty())
        }
        assertEquals(writeCount,writes.size);assertTrue(confirms.isEmpty());assertTrue(tasks.isEmpty())
    }
    @Test fun otherOwnerConfirmationAndSourceRemovalWithdrawRecoveryReceiptInActualDialogWithoutReplacingInput() {
        compose.setContent {Screen()};compose.runOnIdle {proposals.initialize(owner)};open();title();click("task-proposal-prepare")
        val old=actions.get().confirm;val p1=state.value.taskProposals.editor!!.saved!!
        compose.runOnIdle {port.confirm(TaskProposalConfirm(p1,110,"other-owner-confirm"));proposals.refresh()};drain()
        compose.onNodeWithTag("task-proposal-save-missing").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("task-proposal-title").performScrollTo().assertTextContains(p1.titleInput)
        compose.onAllNodesWithTag("task-proposal-saved").assertCountEquals(0)
        compose.onAllNodesWithTag("task-proposal-saved-source-unavailable").assertCountEquals(0)
        compose.onAllNodesWithText("关闭并保留草稿").assertCountEquals(0)
        compose.runOnIdle {
            val e=state.value.taskProposals.editor!!;assertEquals(e.epoch,e.savedEpoch);assertNull(e.error);assertNull(e.notice)
            assertEquals(p1.titleInput,e.title);assertEquals(p1.dateInput,e.date);old();assertTrue(queue.isEmpty())
        }
        assertEquals(1,confirms.size);assertEquals(1,tasks.size)
        click("task-proposal-close");drain();click("original-source-task");drain();title("合成删除前的当前输入")
        compose.onNodeWithTag("manual-task-date-input").performScrollTo().performTextReplacement("2026-10-22");drain()
        compose.onNodeWithTag("task-proposal-saved").performScrollTo().assertIsDisplayed()
        val p2=state.value.taskProposals.editor!!.saved!!;val writeCount=writes.size
        // This controlled presentation port publishes deletion; the host regression runs actual permanent erasure.
        compose.runOnIdle {drafts.remove(p2.id);state.value=state.value.copy(entries=emptyList());proposals.refresh()};drain()
        compose.onNodeWithTag("task-proposal-save-missing").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("task-proposal-title").performScrollTo().assertTextContains(p2.titleInput)
        compose.onNodeWithTag("manual-task-date-input").performScrollTo().assertTextContains(p2.dateInput.date)
        compose.onAllNodesWithTag("task-proposal-saved").assertCountEquals(0)
        compose.onAllNodesWithTag("task-proposal-saved-source-unavailable").assertCountEquals(0)
        compose.onAllNodesWithText("关闭并保留草稿").assertCountEquals(0)
        compose.runOnIdle {
            val e=state.value.taskProposals.editor!!;assertEquals(p2,e.saved);assertEquals(e.epoch,e.savedEpoch)
            assertNull(e.error);assertNull(e.notice);assertEquals(p2.titleInput,e.title);assertEquals(p2.dateInput,e.date)
        }
        assertEquals(writeCount,writes.size);assertEquals(1,tasks.size)
    }
}
