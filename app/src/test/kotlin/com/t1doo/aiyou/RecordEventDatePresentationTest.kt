package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real App/Compose/controller, explicit synthetic ports; no device/SQLCipher claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h1000dp")
class RecordEventDatePresentationTest {
    @get:Rule val compose=createComposeRule()
    private val one=StoredEntry("one",1,"合成 coffee needle 原文🙂",0,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val two=one.copy(id="two",originalText="合成 coffee needle 未知日期")
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,text="synthetic-private-draft",
        entries=listOf(one,two),localProcessingVersions=listOf(one,two).associate{it.id to LocalProcessingVersion(SourceVersion(it.id,it.currentRevision.toLong()),0)},supportsEventDates=true,eventDatesAvailable=true,
        eventDates=mapOf("one" to RecordEventDateSnapshot("one",0,null),"two" to RecordEventDateSnapshot("two",0,null)),
        supportsFavorites=true,favoritesAvailable=true,favorites=mapOf("one" to RecordFavoriteSnapshot("one",1,true),"two" to RecordFavoriteSnapshot("two",0,false)),
        manualTagsAvailable=true,manualTags=mapOf("one" to ManualTagSnapshot("one",1,listOf("手工")),"two" to ManualTagSnapshot("two",0,emptyList()))))
    private val durable=state.value.eventDates.toMutableMap()
    private val receipts=mutableMapOf<String,RecordEventDateSnapshot>()
    private val calls=mutableListOf<RecordEventDateMutation>()
    private val queue=ArrayDeque<suspend(EntryRepository)->Unit>()
    private var lose=false
    private val port=object:RecordEventDateRepository {
        override fun read(entryId:String)=durable.getValue(entryId)
        override fun snapshots()=durable.toMap()
        override fun set(mutation:RecordEventDateMutation):RecordEventDateSnapshot {
            calls+=mutation;receipts[mutation.mutationId]?.let{return it}
            val old=durable.getValue(mutation.entryId)
            if(old.version!=mutation.expectedVersion)throw RecordEventDateConflict()
            val next=RecordEventDateSnapshot(mutation.entryId,old.version+1,mutation.date)
            durable[mutation.entryId]=next;receipts[mutation.mutationId]=next
            if(lose){lose=false;throw RecordEventDateStorageFailure()};return next
        }
    }
    private val store=object:EntryRepository {
        override val localProcessing:LocalProcessingRepository=object:LocalProcessingRepository {
            override fun versions()=state.value.entries.associate{it.id to LocalProcessingVersion(SourceVersion(it.id,it.currentRevision.toLong()),0)}
            override fun read(source:SourceVersion):LocalProcessingSnapshot {val e=state.value.entries.single{it.id==source.sourceId};return LocalProcessingSnapshot(source,OriginalExports.hash(e.originalText),0,e.policy)}
            override fun change(request:LocalProcessingChange)=error("Outside presentation source contract")
        }
        override val eventDates=port
        override suspend fun readDraft()=null
        override suspend fun updateDraft(text:String)=error("Outside UI contract")
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("Outside UI contract")
        override suspend fun entries()=state.value.entries
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("Outside UI contract")
    }
    private val controller=RecordEventDateController({state.value},{state.value=it(state.value)},{queue.addLast(it);true})
    private val results=AtomicReference<OriginalSearchState>()
    private val observer=object:OriginalSearchObserver {
        override fun onResults(state:OriginalSearchState,open:(OriginalSearchHit)->Unit){results.set(state)}
    }
    private var registry:SaveableStateRegistry?=null
    @Composable private fun Screen(large:Boolean=false){
        val current=LocalSaveableStateRegistry.current;SideEffect{registry=current}
        val density=LocalDensity.current
        CompositionLocalProvider(LocalOriginalSearchObserver provides observer,
            LocalDensity provides if(large)Density(density.density,2f)else density){
            AIyouTheme { Box(if(large)Modifier.size(320.dp,640.dp)else Modifier){
                AIyouApp(previewDestination=Destination.MEMORIES,local=state.value,currentRecords={state.value},eventDates=controller,
                    onRefreshLocalSearch={state.value=state.value.copy(eventDates=durable.toMap(),eventDatesAvailable=true,eventDatesError=null)})
            } }
        }
    }
    private fun open(){
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-one"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-one"))).performClick()
        val dateOpen = control("original-event-date-open")
        System.err.println("DATE_PHASE before-open-click")
        dateOpen.performClick()
        System.err.println("DATE_PHASE after-open-click")
        drain()
        System.err.println("DATE_PHASE after-open-drain")
    }
    // Test-only geometry before each scroll: identify an oversized target separately
    // from SDK-main-thread Trace retention. No field values or originals are logged.
    private fun control(tag:String):SemanticsNodeInteraction {
        val target=compose.onNodeWithTag(tag)
        val node=target.fetchSemanticsNode()
        val viewport=generateSequence(node.parent){it.parent}.firstOrNull {
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }
        System.err.println("DATE_SCROLL_DIAGNOSTIC tag=$tag targetDp=${target.getUnclippedBoundsInRoot()} viewportPx=${viewport?.boundsInRoot}")
        val scrolled = target.performScrollTo()
        System.err.println("DATE_PHASE after-scroll tag=$tag")
        return scrolled
    }
    private fun drain(){
        // A controlled repository reply must not depend on the busy UI becoming
        // idle first. Release only this fixture's queue on the UI thread, then
        // require the normal Compose idle boundary and all existing assertions.
        compose.runOnUiThread{runBlocking{while(queue.isNotEmpty())queue.removeFirst()(store)}}
        System.err.println("DATE_PHASE after-repository-drain")
        compose.waitForIdle()
    }
    private fun fill(day:String,zone:String){
        control("event-date-input").performTextReplacement(day)
        control("event-date-zone").performTextReplacement(zone)
    }
    private fun preview(){control("event-date-preview-set").performClick()}
    private fun confirm(){control("event-date-confirm").performClick();drain()}

    @Test fun standaloneDoubleFontDateFieldsHaveFiniteLayout(){
        System.err.println("DATE_ISOLATION before-fields-only")
        compose.setContent{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f),LocalSaveableStateRegistry provides null){
                AIyouTheme{Box(Modifier.size(320.dp,640.dp)){
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())){
                        androidx.compose.material3.OutlinedTextField("",{},singleLine=true,
                            label={androidx.compose.material3.Text("事件日期 YYYY-MM-DD（未知留空）")},
                            modifier=Modifier.fillMaxWidth().testTag("date-probe-field"))
                        androidx.compose.material3.OutlinedTextField("",{},singleLine=true,
                            label={androidx.compose.material3.Text("IANA 时区，例如 Asia/Shanghai")},
                            modifier=Modifier.fillMaxWidth().testTag("date-probe-zone"))
                    }
                }}
            }
        }
        compose.onNodeWithTag("date-probe-field").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("date-probe-zone").assertIsDisplayed().assertIsEnabled()
        System.err.println("DATE_ISOLATION after-fields-only")
    }

    @Test fun standaloneLoadedDoubleFontDateDialogHasFiniteLayout(){
        // Dialog creates another Android window: configure platform resources as
        // well as the composition's density, using the existing Robolectric API.
        System.err.println("DATE_CONFIG before-dialog platformFont=${RuntimeEnvironment.getFontScale()}")
        RuntimeEnvironment.setFontScale(2f)
        assertEquals(2f,RuntimeEnvironment.getFontScale(),0f)
        controller.open("one",1)
        runBlocking{while(queue.isNotEmpty())queue.removeFirst()(store)}
        assertFalse(state.value.eventDateEditor!!.busy)
        assertNotNull(state.value.eventDateEditor!!.snapshot)
        System.err.println("DATE_ISOLATION before-dialog-only")
        compose.setContent{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)){
                assertEquals(2f,androidx.compose.ui.platform.LocalContext.current.resources.configuration.fontScale,0f)
                assertEquals(2f,LocalDensity.current.fontScale,0f)
                AIyouTheme{state.value.eventDateEditor?.let{ui->RecordEventDateDialog(ui,null,controller){true}}}
            }
        }
        System.err.println("DATE_ISOLATION after-dialog-setContent")
        control("event-date-input").assertIsEnabled()
        control("event-date-zone").assertIsEnabled()
        compose.onNodeWithTag("event-date-close").assertIsDisplayed().performClick()
        compose.runOnIdle{assertNull(state.value.eventDateEditor)}
        System.err.println("DATE_ISOLATION after-dialog-only")
    }

    @Test fun narrowDoubleFontManualSetCorrectionAndClearEachRequireVisiblePreview(){
        RuntimeEnvironment.setFontScale(2f)
        assertEquals(2f,RuntimeEnvironment.getFontScale(),0f)
        compose.setContent{Screen(true)};open()
        control("event-date-input").assertIsEnabled()
        compose.runOnIdle{assertEquals("",state.value.eventDateEditor!!.dateInput);assertEquals("",state.value.eventDateEditor!!.zoneInput)}
        fill("2024-02-29","Asia/Shanghai");preview()
        control("event-date-preview").assertTextContains("2024-02-29",substring=true)
        assertTrue(calls.isEmpty())
        val button=control("event-date-confirm").assertIsEnabled()
        assertTrue(button.getUnclippedBoundsInRoot().height>=48.dp);confirm()
        control("event-date-notice").assertExists()
        fill("2024-03-10","America/New_York");preview();confirm()
        control("event-date-preview-clear").performClick()
        assertNotNull(durable.getValue("one").date)
        control("event-date-preview").assertTextContains("清除事件日期",substring=true)
        confirm();assertNull(durable.getValue("one").date);assertEquals(3L,durable.getValue("one").version)
        compose.onNodeWithTag("event-date-close").performClick()
        compose.onNodeWithTag("original-event-date").assertTextEquals("事件日期：未补记（未知）")
        compose.onNodeWithText("记录日期：1970-01-01 00:00 · UTC · 原文版本 1").assertExists()
        compose.runOnIdle{assertEquals(one,state.value.entries.first());assertEquals("synthetic-private-draft",state.value.text)}
    }

    @Test fun uncertainSaveRetainsSameIntentAndStaleConfirmCannotWriteAfterNavigation(){
        compose.setContent{Screen()};open();fill("2024-03-10","America/New_York");preview();lose=true;confirm()
        control("event-date-input").assertIsNotEnabled()
        control("event-date-preview-set").assertIsNotEnabled()
        compose.runOnIdle{
            val pending=state.value.eventDateWrites.getValue("one").intent
            val raw=checkNotNull(registry).performSave().toString()
            for(secret in listOf(pending.mutationId,pending.sourceHash,one.originalText,"2024-03-10","America/New_York"))assertFalse(raw.contains(secret))
        }
        control("event-date-retry").performClick();drain()
        assertSame(calls[0],calls[1]);assertEquals(1L,durable.getValue("one").version)
        fill("2024-04-01","UTC");preview()
        val old=control("event-date-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithTag("event-date-close").performClick()
        compose.onNodeWithText("返回列表").performClick()
        compose.runOnIdle{old();assertTrue(queue.isEmpty());assertEquals(2,calls.size)}
    }

    @Test fun eventDateBasisCombinesKeywordTagFavoriteAndNeverMatchesUnknownCaptureDate(){
        durable["one"]=RecordEventDateSnapshot("one",1,RecordEventDate("2024-03-10","America/New_York"))
        state.value=state.value.copy(eventDates=durable.toMap())
        compose.setContent{Screen()}
        control("original-search-date-basis").performClick()
        compose.waitUntil(2_000){results.get()?.let{it.stage==OriginalSearchStage.READY&&it.input.dateBasis==RecordDateBasis.EVENT}==true}
        assertEquals(listOf("one"),results.get().hits.map{it.entry.id})
        control("original-search-query").performTextReplacement("coffee")
        control("original-search-from").performTextReplacement("2024-03-10")
        control("original-search-through").performTextReplacement("2024-03-10")
        control("original-search-tag").performClick()
        compose.onNodeWithTag("original-search-tag-option-0").performClick()
        control("original-search-favorites").performClick()
        compose.waitUntil(2_000){results.get()?.let{it.stage==OriginalSearchStage.READY&&it.input.onlyFavorites&&it.input.tag=="手工"&&it.input.through=="2024-03-10"}==true}
        assertEquals(listOf("one"),results.get().hits.map{it.entry.id})
        control("original-search-date-basis").performClick()
        compose.waitUntil(2_000){results.get()?.let{it.stage==OriginalSearchStage.READY&&it.input.dateBasis==RecordDateBasis.CAPTURED}==true}
        assertTrue(results.get().hits.isEmpty())
        control("original-search-clear").performClick()
        compose.waitUntil(2_000){results.get()?.stage==OriginalSearchStage.BROWSE}
        assertEquals(RecordDateBasis.CAPTURED,results.get().input.dateBasis)
        compose.runOnIdle{state.value=state.value.copy(eventDatesAvailable=false,eventDatesError="合成读取失败")}
        control("original-search-date-basis").performClick()
        compose.waitUntil(2_000){results.get()?.stage==OriginalSearchStage.FAILED}
        control("original-search-event-date-retry").assertIsEnabled().performClick()
        compose.waitUntil(2_000){results.get()?.let{it.stage==OriginalSearchStage.READY&&it.input.dateBasis==RecordDateBasis.EVENT}==true}
        assertEquals(listOf("one"),results.get().hits.map{it.entry.id})
    }
}
