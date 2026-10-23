package com.t1doo.aiyou

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production AIyouApp/controls/lifecycle, controlled storage port; no device/native SQL claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h1000dp")
class KeywordReductionPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val observed=AtomicReference<OriginalSearchState>()
    private val inputs=AtomicReference<List<TextFieldState>>()
    private val reads=AtomicInteger()
    private data class Request(val query:String,val from:String,val through:String,val basis:RecordDateBasis,val exact:Boolean)
    private val requests=java.util.Collections.synchronizedList(mutableListOf<Request>())
    private var writes=0
    private var registry:SaveableStateRegistry?=null
    private val source=mutableStateOf(LocalRecordUiState(ready=true,opening=false,text="合成未提交草稿",entries=listOf(
        entry("positive","蓝绳放在木箱旁。"),entry("negative","我没有把蓝绳系在树上。"),entry("quotation","合成乙说：“蓝绳在箱里。”这是引用。"))))
    private inner class Port:LocalSearchRepository {
        private inner class Proof(override val entries:List<StoredEntry>):LocalSearchSnapshot {
            override val epoch=7L;override val tags=emptyMap<String,ManualTagSnapshot>();override val pending=false
        }
        override fun epoch()=7L
        override fun batch()=LocalSearchProgress(0,false,7)
        override fun rebuild()=7L
        override fun candidates(request:LocalSearchRequest):LocalSearchSnapshot {
            requests+=Request(request.query,request.from,request.through,request.dateBasis,request.exact)
            reads.incrementAndGet();return Proof(source.value.entries)
        }
        override fun publishCurrent(snapshot:LocalSearchSnapshot,publish:()->Unit):Boolean {
            if(snapshot !is Proof||snapshot.entries!==source.value.entries)return false
            publish();return true
        }
        override fun resolve(snapshot:LocalSearchSnapshot,entryId:String,publish:(StoredEntry)->Unit):Boolean {
            if(snapshot !is Proof||snapshot.entries!==source.value.entries)return false
            val entry=source.value.entries.singleOrNull{it.id==entryId}?:return false;publish(entry);return true
        }
    }
    private fun entry(id:String,text:String)=StoredEntry(id,1,text,Instant.parse("2026-10-10T12:00:00Z").toEpochMilli(),"UTC",ProcessingPolicy.LOCAL_ONLY)
    private fun content(restoration:StateRestorationTester?=null,doubleFont:Boolean=false){
        source.value=source.value.copy(localSearch=Port(),localSearchEpoch=7)
        val body:@Composable ()->Unit={
            val density=LocalDensity.current;val saveable=LocalSaveableStateRegistry.current
            SideEffect{registry=saveable}
            CompositionLocalProvider(LocalDensity provides if(doubleFont)Density(density.density,2f)else density,
                LocalOriginalSearchObserver provides object:OriginalSearchObserver{
                    override fun onResults(state:OriginalSearchState,open:(OriginalSearchHit)->Unit){observed.set(state)}
                    override fun onInputs(query:TextFieldState,from:TextFieldState,through:TextFieldState){inputs.set(listOf(query,from,through))}
                }){
                Box(Modifier.size(if(doubleFont)640.dp else 360.dp,if(doubleFont)320.dp else 1000.dp)){
                    AIyouTheme{AIyouApp(previewDestination=Destination.MEMORIES,local=source.value,currentRecords={source.value},onDraftChange={writes++},onSave={writes++})}
                }
            }
        }
        if(restoration==null)compose.setContent(body)else restoration.setContent(body)
    }
    private fun field(tag:String,value:String){compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)}
    private fun click(tag:String){compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()}
    private fun ready(query:String,previous:OriginalSearchState?=null){compose.waitUntil(10000){observed.get()?.let{it!==previous&&it.stage==OriginalSearchStage.READY&&it.input.query==query}==true}}
    private fun openReduction(){compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("keyword-reduction-open"));click("keyword-reduction-open");compose.onNodeWithTag("keyword-reduction-original").assertExists()}

    @Test fun realAppRequiresRemovalAndConfirmationPreservesDatesThenOpensExactOriginal(){
        content();field("original-search-from","2026-10-10");field("original-search-through","2026-10-10");field("original-search-query","蓝绳 悬挂");ready("蓝绳 悬挂")
        assertTrue(observed.get().hits.isEmpty());val before=reads.get();openReduction()
        compose.onNodeWithTag("keyword-reduction-original").assertTextEquals("蓝绳 悬挂")
        compose.onNodeWithTag("keyword-reduction-confirm").performScrollTo().assertIsNotEnabled();assertEquals(before,reads.get())
        click("keyword-reduction-term-1");compose.onNodeWithTag("keyword-reduction-remaining").assertTextEquals("蓝绳")
        compose.waitForIdle();assertEquals(before,reads.get());assertEquals("蓝绳 悬挂",inputs.get()[0].text.toString())
        click("keyword-reduction-confirm");ready("蓝绳");assertEquals(before+1,reads.get())
        assertEquals("2026-10-10",inputs.get()[1].text.toString());assertEquals("2026-10-10",inputs.get()[2].text.toString())
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-negative"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-negative"))).performScrollTo().performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("original-text",useUnmergedTree=true).fetchSemanticsNodes().size==1}
        compose.onNodeWithTag("original-text",useUnmergedTree=true).assertTextEquals("我没有把蓝绳系在树上。")
        assertEquals(0,writes);assertEquals("合成未提交草稿",source.value.text)
    }
    @Test fun cancelRequeriesWholeOldTupleAndRestorationRetiresWithoutSavingWords(){
        val restore=StateRestorationTester(compose);content(restore);field("original-search-query","蓝绳 悬挂");ready("蓝绳 悬挂")
        val old=observed.get();openReduction();click("keyword-reduction-term-1");click("keyword-reduction-cancel")
        ready("蓝绳 悬挂",old);assertTrue(observed.get().hits.isEmpty());val second=observed.get();assertNotSame(old,second)
        openReduction();click("keyword-reduction-term-1");compose.runOnIdle{assertFalse(registry?.performSave().toString().contains("蓝绳"))}
        restore.emulateSavedInstanceStateRestore();compose.waitForIdle();compose.onNodeWithTag("keyword-reduction-original").assertDoesNotExist()
        assertNotEquals("蓝绳",inputs.get()[0].text.toString());assertEquals(0,writes)
    }
    @Test @Config(qualifiers="w640dp-h320dp-land") fun landscapeDoubleFontScrollFocusAndEnterConfirmExplicitRemainingTerms(){
        content(doubleFont=true);field("original-search-query","蓝绳 悬挂");ready("蓝绳 悬挂");openReduction()
        click("keyword-reduction-term-1")
        val confirm=compose.onNodeWithTag("keyword-reduction-confirm").performScrollTo().assertIsDisplayed()
        confirm.assertHeightIsAtLeast(48.dp)
        val dialogView=(confirm.fetchSemanticsNode().root as ViewRootForTest).view
        compose.runOnIdle{assertTrue(dialogView.requestFocusFromTouch())};compose.waitForIdle();compose.runOnIdle{assertFalse(dialogView.isInTouchMode)}
        confirm.performSemanticsAction(SemanticsActions.RequestFocus){assertTrue(it())};confirm.assertIsFocused().performKeyInput{pressKey(Key.Enter)}
        ready("蓝绳");assertEquals(0,writes)
    }
}
