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

/** Production route/controllers; controlled tag receipt port. Actual SQL is tested separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h1000dp")
class SourceTagPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val one=StoredEntry("one",1,"🙂合成原文\n标签：#旅行 #生活",55,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val two=StoredEntry("two",1,"其他合成原文",65,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,entries=listOf(one,two),supportsSourceTags=true))
    private val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
    private val applied=mutableListOf<SourceTagRequest>()
    private var names=emptyList<String>();private var version=0L;private var epoch=10L
    private var last:SourceTagRequest?=null
    private var failRead=false;private var loseAck=false
    private val tags=object:ManualTagRepository {
        override fun read(entryId:String)=ManualTagSnapshot(entryId,version,names)
        override fun snapshots()=if(version==0L) emptyMap() else mapOf("one" to read("one"))
        override fun replace(mutation:ManualTagMutation):ManualTagSnapshot {
            check(mutation.entryId=="one"&&mutation.expectedVersion==version)
            version++;epoch++;names=mutation.names;return read("one")
        }
    }
    private val port=object:SourceTagRepository {
        override fun preview(source:SourceVersion):SourceTagPreview {
            if(failRead) throw SourceTagStorageFailure()
            check(source==SourceVersion("one",1))
            return SourceTagPreview(source,OriginalExports.hash(one.originalText),0,55,"UTC",ProcessingPolicy.LOCAL_ONLY,
                epoch,tags.read("one"),SourceTagRules.extract(source,one.originalText))
        }
        override fun apply(request:SourceTagRequest):ManualTagSnapshot {
            applied+=request
            if(last!==request) {
                check(request.preview.epoch==epoch&&request.preview.tags.version==version)
                tags.replace(ManualTagMutation("one",version,names+request.proposal.name,request.mutationId));last=request
            }
            if(loseAck) { loseAck=false;failRead=true }
            return tags.read("one")
        }
    }
    private val owner=object:EntryRepository {
        override val sourceTags=port
        override val manualTags=tags
        override suspend fun readDraft()=null
        override suspend fun updateDraft(text:String)=error("Not in presentation scope")
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("Not in presentation scope")
        override suspend fun entries()=state.value.entries
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("Not in presentation scope")
    }
    private val controller=SourceTagController({state.value},{state.value=it(state.value)},{queue.add(it);true},Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC))
    private val manager=ManualTagController({state.value},{state.value=it(state.value)},{queue.add(it);true})
    private val actions=AtomicReference<SourceTagDialogActions>()
    @After fun dispose() { controller.dispose();manager.dispose() }
    private var registry:SaveableStateRegistry?=null
    @Composable private fun Screen(large:Boolean=false) {
        val saved=LocalSaveableStateRegistry.current;SideEffect { registry=saved }
        val density=LocalDensity.current
        CompositionLocalProvider(LocalSourceTagDialogObserver provides SourceTagDialogObserver { actions.set(it) },
            LocalDensity provides if(large) Density(density.density,2f) else density) {
            AIyouTheme { Box(if(large) Modifier.size(320.dp,640.dp) else Modifier) {
                AIyouApp(previewDestination=Destination.MEMORIES,local=state.value,currentRecords={state.value},sourceTags=controller,
                    onOpenTags=manager::open,onTagEdit=manager::edit,onTagAdd=manager::add,onTagRemove=manager::remove,
                    onTagRetry=manager::retry,onTagClose=manager::close)
            } }
        }
    }
    private fun click(tag:String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun drain() { compose.runOnIdle { runBlocking { while(true) (queue.poll()?:break)(owner) } };compose.waitForIdle() }
    private fun openReader() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-one"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-one"))).performClick()
    }
    private fun open() { click("original-source-tags");drain() }

    @Test @Config(sdk=[35],qualifiers="w320dp-h640dp")
    fun explicitPreviewConfirmAndManageRemovalAreReachableAtDoubleFontWithoutChangingOriginal() {
        compose.setContent { Screen(true) };openReader();open()
        for(tag in listOf("source-tag-add-0","source-tag-source-0","source-tags-manage","source-tags-close")) {
            val node=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed();assertTrue(node.getUnclippedBoundsInRoot().height>=48.dp)
        }
        click("source-tag-add-0");compose.onNodeWithTag("source-tag-confirm-quote").performScrollTo().assertTextEquals("#旅行")
        click("source-tag-cancel-confirm");assertTrue(applied.isEmpty());assertTrue(names.isEmpty())
        click("source-tag-add-0");click("source-tag-confirm");assertTrue(names.isEmpty());drain()
        compose.onNodeWithTag("source-tag-present-0").performScrollTo().assertExists();assertEquals(listOf("旅行"),state.value.manualTags.getValue("one").names)
        click("source-tags-manage");drain();click("manual-tag-remove-0");drain()
        assertTrue(names.isEmpty());assertEquals(listOf(one,two),state.value.entries);assertTrue(state.value.tasks.items.isEmpty())
        click("manual-tag-close");open();click("source-tag-source-0")
        val text=compose.onNodeWithTag("original-text").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
        assertEquals(one.originalText,text.text);assertTrue(text.spanStyles.isNotEmpty());assertNull(state.value.sourceTags)
    }
    @Test fun lostReadbackHasNoFalseSuccessAndRetriesTheSameMemoryOnlyRequest() {
        compose.setContent { Screen() };openReader();open();click("source-tag-add-0");loseAck=true
        click("source-tag-confirm");drain()
        compose.onNodeWithTag("source-tags-error").assertExists();compose.onAllNodesWithTag("source-tags-notice").assertCountEquals(0)
        assertFalse(state.value.manualTagsAvailable);assertEquals(listOf("旅行"),names)
        failRead=false;click("source-tags-retry");drain();assertSame(applied[0],applied[1]);assertEquals(1L,version)
        compose.onNodeWithTag("source-tag-present-0").performScrollTo().assertExists()
    }
    @Test fun cancelledAndRestoredOrNavigatedConfirmationsCannotReusePrivatePreviewAuthority() {
        val restoration=StateRestorationTester(compose);restoration.setContent { Screen() };openReader();open()
        click("source-tag-add-0");val old=actions.get();click("source-tag-cancel-confirm");click("source-tag-add-0")
        compose.runOnIdle { old.cancel();old.confirm();assertTrue(queue.isEmpty()) }
        val fresh=actions.get().confirm
        compose.runOnIdle { val raw=checkNotNull(registry).performSave().toString();assertFalse(raw.contains(one.originalText));assertFalse(raw.contains(OriginalExports.hash(one.originalText))) }
        restoration.emulateSavedInstanceStateRestore();compose.waitForIdle()
        compose.runOnIdle { old.confirm();fresh();assertTrue(queue.isEmpty()) };assertTrue(applied.isEmpty());assertNull(state.value.sourceTags)
        // The saved reader route survives; only its private confirmation preview is retired.
        compose.onNodeWithTag("original-text").assertExists()
        open();click("source-tag-add-0");val retained=actions.get().confirm
        val back=compose.onNodeWithText("返回列表").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { back();retained();assertTrue(queue.isEmpty()) };assertTrue(applied.isEmpty())
    }
}
