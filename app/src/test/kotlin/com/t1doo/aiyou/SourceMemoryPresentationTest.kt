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
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production route/controller, controlled durable port; SQL/AEAD/cold reopen tested separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h1000dp")
class SourceMemoryPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val one=StoredEntry("one",1,"🙂合成原文：他说我很开心；我并不开心；如果明天成功会开心；旅行计划还没有完成。",55,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val two=StoredEntry("two",1,"其他合成原文",65,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,entries=listOf(one,two),supportsSourceMemories=true))
    private val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
    private var saved:SourceMemorySnapshot?=null
    private val creates=mutableListOf<SourceMemoryCreate>();private val mutations=mutableListOf<SourceMemoryMutation>()
    private var firstCreate:SourceMemoryCreate?=null
    private var loseAck=false;private var failRead=false
    private val port=object:SourceMemoryRepository {
        override fun preview(source:SourceVersion):SourceMemoryPreview {
            if(failRead) throw SourceMemoryStorageFailure()
            check(source==SourceVersion("one",1))
            return SourceMemoryPreview(source,OriginalExports.hash(one.originalText),0,one.originalText,55,"UTC",ProcessingPolicy.LOCAL_ONLY,10)
        }
        override fun memories(source:SourceVersion):List<SourceMemorySnapshot> {
            if(failRead) throw SourceMemoryStorageFailure()
            return listOfNotNull(saved)
        }
        override fun create(request:SourceMemoryCreate):SourceMemorySnapshot {
            creates+=request;SourceMemoryRules.validate(request.content,request.preview.source,one.originalText)
            if(saved==null) {
                firstCreate=request
                saved=SourceMemorySnapshot(request.id,request.preview.source,request.preview.sourceHash,0,1,SourceMemoryState.ACTIVE,
                    request.requestedAtMillis,55,"UTC",listOf(SourceMemoryVersion(1,request.content,SourceMemoryReview.PROPOSED,null,null,request.requestedAtMillis,request.requestedAtMillis)))
            } else check(firstCreate===request && saved!!.version==1L)
            if(loseAck) { loseAck=false;failRead=true }
            return checkNotNull(saved)
        }
        override fun mutate(request:SourceMemoryMutation):SourceMemorySnapshot {
            mutations+=request;val old=checkNotNull(saved);val current=checkNotNull(old.current)
            check(request.id==old.id && request.expectedVersion==old.version && request.expectedContentRevision==current.revision && request.previousReview==current.review)
            val review=SourceMemoryRules.decision(current.review,request.action)
            val versions=if(request.action==SourceMemoryAction.CORRECT) {
                val content=checkNotNull(request.content);SourceMemoryRules.validate(content,old.source,one.originalText)
                old.versions.dropLast(1)+current.copy(review=SourceMemoryReview.SUPERSEDED,priorReview=current.review)+
                    SourceMemoryVersion(current.revision+1,content,SourceMemoryReview.PROPOSED,null,current.revision,request.requestedAtMillis,request.requestedAtMillis)
            } else old.versions.dropLast(1)+current.copy(review=review,reviewedAtMillis=request.requestedAtMillis)
            saved=SourceMemorySnapshot(old.id,old.source,old.sourceHash,old.generation,old.version+1,old.state,old.createdAtMillis,old.capturedAtMillis,old.captureTimeZone,versions)
            return checkNotNull(saved)
        }
    }
    private val owner=object:EntryRepository {
        override val sourceMemories=port
        override suspend fun readDraft()=null
        override suspend fun updateDraft(text:String)=error("Not in presentation scope")
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("Not in presentation scope")
        override suspend fun entries()=state.value.entries
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("Not in presentation scope")
    }
    private val controller=SourceMemoryController({state.value},{state.value=it(state.value)},{queue.add(it);true},Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC))
    private val actions=AtomicReference<SourceMemoryDialogActions>()
    private var registry:SaveableStateRegistry?=null
    @After fun dispose()=controller.dispose()
    @Composable private fun Screen(large:Boolean=false) {
        val savedRegistry=LocalSaveableStateRegistry.current;SideEffect { registry=savedRegistry }
        val density=LocalDensity.current
        CompositionLocalProvider(LocalSourceMemoryDialogObserver provides SourceMemoryDialogObserver { actions.set(it) },
            LocalDensity provides if(large) Density(density.density,2f) else density) {
            AIyouTheme { Box(if(large) Modifier.size(320.dp,640.dp) else Modifier) {
                AIyouApp(previewDestination=Destination.MEMORIES,local=state.value,currentRecords={state.value},sourceMemories=controller)
            } }
        }
    }
    private fun click(tag:String) { compose.showOriginalToolsFor(tag); compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun drain() { compose.runOnIdle { runBlocking { while(true) (queue.poll()?:break)(owner) } };compose.waitForIdle() }
    private fun openReader() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-one"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-one"))).performClick()
    }
    private fun open() { click("original-source-memories");drain() }
    private fun note(text:String) { compose.onNodeWithTag("source-memory-note").performScrollTo().performTextReplacement(text) }
    private fun save(noteText:String="合成原备注") { note(noteText);click("source-memory-save");click("source-memory-confirm");drain() }
    private fun decide(action:SourceMemoryAction) { click("source-memory-"+action.name+"-0");click("source-memory-confirm");drain() }

    @Test @Config(sdk=[35],qualifiers="w320dp-h640dp")
    fun explicitPendingAndAcceptanceAreReachableAtDoubleFontAndSourceJumpPreservesOriginal() {
        compose.setContent { Screen(true) };openReader();open()
        for(tag in listOf("source-memory-kind-EVENT","source-memory-kind-OCCASION_EMOTION","source-memory-assertion-UNCERTAIN","source-memory-save","source-memories-close")) {
            val node=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed();assertTrue(node.getUnclippedBoundsInRoot().height>=48.dp)
        }
        note("合成原备注");click("source-memory-save");compose.onNodeWithTag("source-memory-confirm-quote").performScrollTo().assertTextEquals(one.originalText)
        click("source-memory-cancel-confirm");assertNull(saved);assertTrue(creates.isEmpty())
        click("source-memory-save");click("source-memory-confirm");assertNull(saved);drain()
        compose.onNodeWithTag("source-memory-review-0").performScrollTo().assertTextEquals("待审候选")
        assertEquals(SourceMemoryAssertion.UNCERTAIN,saved!!.current!!.content.assertion)
        click("source-memory-ACCEPT-0");assertEquals(SourceMemoryReview.PROPOSED,saved!!.current!!.review)
        click("source-memory-confirm");drain();assertEquals(SourceMemoryReview.ACCEPTED,saved!!.current!!.review)
        click("source-memories-close");open();compose.onNodeWithTag("source-memory-review-0").performScrollTo().assertTextEquals("人工已采纳线索")
        assertEquals(SourceMemoryAssertion.UNCERTAIN,saved!!.current!!.content.assertion)
        assertEquals(SourceMemoryKind.EVENT,saved!!.current!!.content.kind);assertEquals(one.originalText,saved!!.current!!.content.reference.quote)
        compose.onNodeWithText("事件线索 · 含义未确认").assertExists()
        for(tag in listOf("source-memory-correct-0","source-memory-source-0","source-memory-REVOKE-0")) {
            val node=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed();assertTrue(node.getUnclippedBoundsInRoot().height>=48.dp)
        }
        click("source-memory-source-0")
        val text=compose.onNodeWithTag("original-text").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
        assertEquals(one.originalText,text.text);assertTrue(text.spanStyles.isNotEmpty());assertNull(state.value.sourceMemories)
        assertEquals(listOf(one,two),state.value.entries);assertTrue(state.value.tasks.items.isEmpty());assertTrue(state.value.manualTags.isEmpty())
    }
    @Test fun rejectionCorrectionHistoryAndRevocationSurviveReopeningWithoutAutomaticAcceptance() {
        compose.setContent { Screen() };openReader();open();save();decide(SourceMemoryAction.REJECT)
        click("source-memories-close");open();compose.onNodeWithTag("source-memory-review-0").performScrollTo().assertTextEquals("已拒绝")
        decide(SourceMemoryAction.REOPEN);decide(SourceMemoryAction.ACCEPT);click("source-memory-correct-0")
        note("纠正备注：只是引用");click("source-memory-assertion-CONTEXT");click("source-memory-save")
        assertEquals(SourceMemoryReview.ACCEPTED,saved!!.current!!.review);click("source-memory-cancel-confirm")
        assertEquals(SourceMemoryReview.ACCEPTED,saved!!.current!!.review);click("source-memory-save");click("source-memory-confirm");drain()
        assertEquals(2,saved!!.current!!.revision);assertEquals(SourceMemoryReview.PROPOSED,saved!!.current!!.review)
        assertEquals(SourceMemoryReview.ACCEPTED,saved!!.versions.first().priorReview)
        click("source-memories-close");open();compose.onNodeWithTag("source-memory-stored-note-0").performScrollTo().assertTextEquals("人工备注：纠正备注：只是引用")
        click("source-memory-history-0");compose.onNodeWithTag("source-memory-old-note-1").performScrollTo().assertTextEquals("原人工备注：合成原备注")
        decide(SourceMemoryAction.ACCEPT);decide(SourceMemoryAction.REVOKE);click("source-memories-close");open()
        compose.onNodeWithTag("source-memory-review-0").performScrollTo().assertTextEquals("已撤回采纳")
        assertEquals(SourceMemoryAssertion.CONTEXT,saved!!.current!!.content.assertion)
    }
    @Test fun unknownAckAndRestorationNavigationCannotReusePrivateConfirmationAuthority() {
        val restoration=StateRestorationTester(compose);restoration.setContent { Screen() };openReader();open();note("私有合成备注")
        click("source-memory-save");val old=actions.get();click("source-memory-cancel-confirm");click("source-memory-save")
        compose.runOnIdle { old.cancel();old.confirm();assertTrue(queue.isEmpty()) };val fresh=actions.get().confirm
        compose.runOnIdle { val raw=checkNotNull(registry).performSave().toString();assertFalse(raw.contains(one.originalText));assertFalse(raw.contains(OriginalExports.hash(one.originalText)));assertFalse(raw.contains("私有合成备注")) }
        restoration.emulateSavedInstanceStateRestore();compose.waitForIdle()
        compose.runOnIdle { old.confirm();fresh();assertTrue(queue.isEmpty()) };assertNull(saved);assertNull(state.value.sourceMemories)
        compose.onNodeWithTag("original-text").assertExists();open();loseAck=true;click("source-memory-save");click("source-memory-confirm");drain()
        compose.onNodeWithTag("source-memories-error").assertExists();compose.onAllNodesWithTag("source-memories-notice").assertCountEquals(0)
        assertEquals(SourceMemoryReview.PROPOSED,saved!!.current!!.review);failRead=false;click("source-memories-retry");drain()
        assertSame(creates[0],creates[1]);assertEquals(1L,saved!!.version)
        click("source-memory-ACCEPT-0");val retained=actions.get().confirm
        val back=compose.onNodeWithText("返回列表").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { back();retained();assertTrue(queue.isEmpty()) }
        assertTrue(mutations.isEmpty());assertEquals(SourceMemoryReview.PROPOSED,saved!!.current!!.review)
    }
}
