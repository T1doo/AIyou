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

/** Actual detail route/dialog/controller. SQL generation and receipts have separate real SQLite tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h1000dp")
class LocalProcessingPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val one=StoredEntry("one",1,"🙂合成原文，不应被修改",55,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val two=StoredEntry("two",1,"其他合成原文",65,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private var actual=one
    private var generation=0L
    private var last:LocalProcessingChange?=null
    private var loseReadback=false;private var failRead=false
    private val changes=mutableListOf<LocalProcessingChange>()
    private val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,entries=listOf(one,two),supportsLocalProcessing=true))
    private fun snapshot()=LocalProcessingSnapshot(SourceVersion("one",1),OriginalExports.hash(actual.originalText),generation,actual.policy)
    private val port=object:LocalProcessingRepository {
        override fun read(source:SourceVersion):LocalProcessingSnapshot {
            if(failRead) throw LocalProcessingStorageFailure()
            check(source==SourceVersion("one",1));return snapshot()
        }
        override fun versions()=mapOf("one" to LocalProcessingVersion(SourceVersion("one",1),generation))
        override fun change(request:LocalProcessingChange):LocalProcessingSnapshot {
            changes+=request
            if(last!==request) {
                if(request.expected!=snapshot()) throw LocalProcessingConflict()
                actual=StoredEntry(actual.id,actual.currentRevision,actual.originalText,actual.capturedAtMillis,actual.captureTimeZone,
                    ProcessingPolicy.valueOf(request.target.name));generation++;last=request
            }
            if(loseReadback) { loseReadback=false;failRead=true }
            return snapshot()
        }
    }
    private val owner=object:EntryRepository {
        override val localProcessing=port
        override suspend fun readDraft()=null
        override suspend fun updateDraft(text:String)=error("Not in presentation scope")
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("Not in presentation scope")
        override suspend fun entries()=listOf(actual,two)
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("Not in presentation scope")
    }
    private val controller=LocalProcessingController({state.value},{state.value=it(state.value)},{queue.add(it);true},{})
    private val actions=AtomicReference<LocalProcessingDialogActions>()
    private var registry:SaveableStateRegistry?=null
    @After fun dispose() { controller.dispose() }
    @Composable private fun Screen(large:Boolean=false) {
        val saved=LocalSaveableStateRegistry.current;SideEffect { registry=saved }
        val density=LocalDensity.current
        CompositionLocalProvider(LocalProcessingDialogObservation provides LocalProcessingDialogObserver { actions.set(it) },
            LocalDensity provides if(large) Density(density.density,2f) else density) {
            AIyouTheme { Box(if(large) Modifier.size(320.dp,640.dp) else Modifier) {
                AIyouApp(previewDestination=Destination.MEMORIES,local=state.value,currentRecords={state.value},localProcessing=controller)
            } }
        }
    }
    private fun click(tag:String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun drain() { compose.runOnIdle { runBlocking { while(true) (queue.poll()?:break)(owner) } };compose.waitForIdle() }
    private fun openReader() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-one"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-one"))).performClick()
    }
    private fun open() { click("original-local-processing");drain() }

    @Test @Config(sdk=[35],qualifiers="w320dp-h640dp")
    fun actualNarrowDoubleFontDetailRequiresSeparateChoiceAndConfirmationForBothLocalScopes() {
        compose.setContent { Screen(true) };openReader();open()
        for(tag in listOf("local-processing-none","local-processing-local","local-processing-close")) {
            val node=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed();assertTrue(node.getUnclippedBoundsInRoot().height>=48.dp)
        }
        click("local-processing-none");assertTrue(changes.isEmpty())
        click("local-processing-cancel");assertEquals(ProcessingPolicy.LOCAL_ONLY,actual.policy)
        click("local-processing-none");click("local-processing-confirm");assertTrue(changes.isEmpty());drain()
        compose.onNodeWithTag("local-processing-current").performScrollTo().assertTextEquals("当前：不要据此推断")
        click("local-processing-local");click("local-processing-confirm");drain()
        compose.onNodeWithTag("local-processing-current").performScrollTo().assertTextEquals("当前：仅本机")
        assertEquals(one.originalText,actual.originalText);assertEquals(2L,generation);assertTrue(state.value.tasks.items.isEmpty())
    }
    @Test fun unknownReadbackHasNoSuccessAndRetryUsesTheIdenticalRequestWithoutAnotherGeneration() {
        compose.setContent { Screen() };openReader();open();click("local-processing-none");loseReadback=true
        click("local-processing-confirm");drain()
        compose.onNodeWithTag("local-processing-error").assertExists();compose.onAllNodesWithTag("local-processing-notice").assertCountEquals(0)
        assertNull(state.value.localProcessing!!.snapshot);assertNotNull(state.value.localProcessing!!.pending)
        assertEquals(ProcessingPolicy.AI_NONE,actual.policy)
        failRead=false;click("local-processing-retry");drain()
        assertSame(changes[0],changes[1]);assertEquals(1L,generation)
        compose.onNodeWithTag("local-processing-notice").performScrollTo().assertIsDisplayed()
    }
    @Test fun cancelRestorationAndNavigationRetirePrivateConfirmationsAndSavedStateContainsNoSource() {
        val restoration=StateRestorationTester(compose);restoration.setContent { Screen() };openReader();open()
        click("local-processing-none");val old=actions.get();click("local-processing-cancel");click("local-processing-none")
        compose.runOnIdle { old.confirm();old.cancel();assertTrue(queue.isEmpty()) }
        val fresh=actions.get().confirm
        compose.runOnIdle { val raw=checkNotNull(registry).performSave().toString();assertFalse(raw.contains(one.originalText));assertFalse(raw.contains(OriginalExports.hash(one.originalText))) }
        restoration.emulateSavedInstanceStateRestore();compose.waitForIdle()
        compose.runOnIdle { old.confirm();fresh();assertTrue(queue.isEmpty()) };assertNull(state.value.localProcessing);assertTrue(changes.isEmpty())
        open();click("local-processing-none");val retained=actions.get().confirm
        val back=compose.onNodeWithText("返回列表").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { back();retained();assertTrue(queue.isEmpty()) };assertTrue(changes.isEmpty())
    }
    @Test fun pictureOnlyOriginalCanChooseNoInferenceWithoutInventingTextOrRemoteOption() {
        actual=StoredEntry("one",1,"",55,"UTC",ProcessingPolicy.LOCAL_ONLY)
        state.value=state.value.copy(entries=listOf(actual,two))
        compose.setContent { Screen() };openReader();open()
        compose.onNodeWithTag("local-processing-source").performScrollTo().assertTextEquals("附件记录 · 无文字正文")
        click("local-processing-none");click("local-processing-confirm");drain()
        assertEquals("",actual.originalText);assertEquals(ProcessingPolicy.AI_NONE,actual.policy)
        compose.onAllNodesWithText("REMOTE_ALLOWED").assertCountEquals(0)
    }
}
