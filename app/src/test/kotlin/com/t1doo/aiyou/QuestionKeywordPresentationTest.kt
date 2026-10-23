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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class QuestionKeywordPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val observed=AtomicReference<OriginalSearchState>()
    private val inputs=AtomicReference<List<TextFieldState>>()
    private val reads=AtomicInteger()
    private data class Request(val query:String,val from:String,val through:String,val basis:RecordDateBasis,val exact:Boolean)
    private val requests=java.util.Collections.synchronizedList(mutableListOf<Request>())
    private var writes=0
    private var registry:SaveableStateRegistry?=null
    private val source=mutableStateOf(LocalRecordUiState(ready=true,opening=false,text="合成未提交草稿",entries=listOf(
        entry("positive","我种了薄荷。"),entry("negative","我没有种薄荷。"),entry("quotation","小舟说：“种薄荷。”这不是我的经历。"))))
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
    private fun open(){compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("question-keyword-open"));compose.onNodeWithTag("question-keyword-open").performScrollTo().performClick();compose.onNodeWithTag("question-keyword-question").assertExists()}
    private fun field(tag:String,value:String){compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)}
    private fun click(tag:String){compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()}
    private fun ready(){compose.waitUntil(10000){observed.get()?.let{it.stage==OriginalSearchStage.READY&&it.input.query=="薄荷"}==true}}

    @Test fun realAppRequiresExplicitDateAndQueriesOnceThenOpensUnchangedNegativeOriginal(){
        content();open();field("question-keyword-question","我种薄荷了吗？");field("question-keyword-terms","薄荷")
        compose.waitForIdle();assertEquals(0,reads.get());assertEquals("",inputs.get()[0].text.toString())
        click("question-keyword-confirm");compose.onNodeWithTag("question-keyword-error").assertExists();assertEquals(0,reads.get())
        click("question-keyword-date:UNLIMITED");click("question-keyword-confirm");ready()
        compose.onNodeWithTag("question-keyword-question").assertDoesNotExist();assertEquals(1,reads.get())
        val oldProofState=observed.get()
        assertEquals(setOf("positive","negative","quotation"),observed.get().hits.map{it.entry.id}.toSet())
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-negative"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-negative"))).performScrollTo().performClick()
        compose.onNodeWithTag("original-text",useUnmergedTree=true).assertTextEquals("我没有种薄荷。")
        val beforeReturn=reads.get();compose.onNodeWithText("返回列表").assertIsDisplayed().performClick()
        compose.waitUntil(10000){reads.get()>beforeReturn&&observed.get()?.let{it!==oldProofState&&it.stage==OriginalSearchStage.READY&&it.input.query=="薄荷"}==true}
        val beforeCancel=observed.get();val callsBeforeQuestion=reads.get()
        open();field("question-keyword-question","取消不应复用旧出处");compose.waitForIdle();assertEquals(callsBeforeQuestion,reads.get())
        click("question-keyword-cancel")
        compose.waitUntil(10000){reads.get()>callsBeforeQuestion&&observed.get()?.let{it!==beforeCancel&&it.stage==OriginalSearchStage.READY&&it.input.query=="薄荷"}==true}
        assertEquals(0,writes);assertEquals("合成未提交草稿",source.value.text)
    }
    @Test fun restorationAndActualActivityPauseRetireQuestionWithoutSavingOrQuerying(){
        val restore=StateRestorationTester(compose);content(restore);open()
        val full=String(IntArray(512){0x20000+it},0,512)
        field("question-keyword-question",full)
        assertEquals(full,compose.onNodeWithTag("question-keyword-question").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(0,reads.get())
        val marker="synthetic-question-private-v3";field("question-keyword-question",marker);field("question-keyword-terms","薄荷")
        compose.runOnIdle{assertFalse(registry?.performSave().toString().contains(marker))}
        restore.emulateSavedInstanceStateRestore();compose.waitForIdle()
        compose.onNodeWithTag("question-keyword-question").assertDoesNotExist();assertEquals(0,reads.get())
        open();field("question-keyword-question","后台前的合成问题");field("question-keyword-terms","薄荷")
        click("question-keyword-date:UNLIMITED")
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);compose.waitForIdle()
        compose.onNodeWithTag("question-keyword-question").assertDoesNotExist();assertEquals(0,reads.get())
        open();assertEquals("",compose.onNodeWithTag("question-keyword-question").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        click("question-keyword-cancel");assertEquals(0,reads.get());assertEquals(0,writes)
    }
    @Test @Config(qualifiers="w640dp-h320dp-land")
    fun landscapeDoubleFontScrollFocusAndEnterConfirmOnlyAfterValidWholeDateTuple(){
        content(doubleFont=true);open();val full="我没有种薄荷吗？小舟说“种了薄荷”。"
        field("question-keyword-question",full);field("question-keyword-terms","薄荷")
        assertEquals(full,compose.onNodeWithTag("question-keyword-question").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        click("question-keyword-date:RANGE");field("question-keyword-from","2026-02-30");field("question-keyword-through","2026-10-31")
        click("question-keyword-confirm");assertEquals(0,reads.get());compose.onNodeWithTag("question-keyword-error").assertExists()
        field("question-keyword-from","2026-10-01")
        val confirm=compose.onNodeWithTag("question-keyword-confirm").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val view=(confirm.fetchSemanticsNode().root as ViewRootForTest).view
        compose.runOnIdle{assertTrue(view.requestFocusFromTouch())};compose.waitForIdle();compose.runOnIdle{assertFalse(view.isInTouchMode)}
        confirm.performSemanticsAction(SemanticsActions.RequestFocus){assertTrue(it())}
        confirm.assertIsFocused().performKeyInput{pressKey(Key.Enter)};ready()
        compose.runOnIdle{assertEquals(listOf("薄荷","2026-10-01","2026-10-31"),inputs.get().map{it.text.toString()});assertEquals(OriginalSearchMode.KEYWORDS,observed.get().input.mode)}
        assertEquals(1,reads.get());assertEquals(0,writes)
        assertEquals(listOf(Request("薄荷","2026-10-01","2026-10-31",RecordDateBasis.CAPTURED,false)),requests.toList())
    }
}
