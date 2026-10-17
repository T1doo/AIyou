package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual App/dialog/controller; controlled in-memory acknowledgements are not SQL or device proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h1000dp")
class ManualPeoplePresentationTest {
    @get:Rule val compose=createComposeRule()
    private val entries=listOf(entry("one","咖啡完整原文","Asia/Shanghai"),entry("two","咖啡另一原文","UTC"))
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,text="合成独立草稿",entries=entries))
    private val queue=ArrayDeque<suspend(EntryRepository)->Unit>()
    private val persisted=entries.associate{it.id to snapshot(it,1,listOf("小林"))}.toMutableMap()
    private var failWrite=false
    private val people=object:ManualPeopleRepository {
        override fun read(entryId:String)=persisted.getValue(entryId)
        override fun snapshots()=persisted.toMap()
        override fun replace(mutation:ManualPeopleMutation):ManualPeopleSnapshot {
            if(failWrite)throw ManualPeopleStorageFailure()
            if(read(mutation.before.entryId).version!=mutation.before.version)throw ManualPeopleConflict()
            return snapshot(entries.first{it.id==mutation.before.entryId},mutation.before.version+1,mutation.names)
                .also{persisted[it.entryId]=it}
        }
    }
    private val store=object:EntryRepository {
        override val manualPeople=people
        override val localProcessing:LocalProcessingRepository=object:LocalProcessingRepository {
            override fun read(source:SourceVersion):LocalProcessingSnapshot {val entry=entries.single{it.id==source.sourceId};return LocalProcessingSnapshot(source,OriginalExports.hash(entry.originalText),0,entry.policy)}
            override fun versions()=entries.associate{it.id to LocalProcessingVersion(SourceVersion(it.id,it.currentRevision.toLong()),0)}
            override fun change(request:LocalProcessingChange)=error("Outside presentation contract")
        }
        override suspend fun entries()=entries
        override suspend fun readDraft()=TextDraft("capture",1,"合成独立草稿")
        override suspend fun updateDraft(text:String)=error("Outside presentation contract")
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("Outside presentation contract")
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("Outside presentation contract")
    }
    private val controller=ManualPeopleController({state.value},{state.value=it(state.value)},{queue.add(it);true})
    private var result:OriginalSearchState?=null
    private var open:((OriginalSearchHit)->Unit)?=null
    private var registry:SaveableStateRegistry?=null
    private lateinit var navigation:OriginalReadingCallbacks
    private val reader=object:OriginalReadingObserver {
        override fun onNavigation(callbacks:OriginalReadingCallbacks){navigation=callbacks}
        override fun onClipboard(clipboard:androidx.compose.ui.platform.Clipboard)=Unit
        override fun onSelection(selection:androidx.compose.foundation.text.selection.SelectionState)=Unit
    }
    private val observer=object:OriginalSearchObserver {
        override fun onResults(state:OriginalSearchState,open:(OriginalSearchHit)->Unit){result=state;this@ManualPeoplePresentationTest.open=open}
    }
    @Test fun manualEditorWaitsForAckKeepsFailedInputAndOnlyConfirmedRemoveChangesNames(){
        content();openEditor();field("manual-people-input","我的称呼🙂");failWrite=true
        compose.onNodeWithTag("manual-people-add").performScrollTo().performClick()
        assertTrue(state.value.manualPeopleEditor!!.busy);assertEquals(listOf("小林"),persisted.getValue("one").names)
        pump();compose.onNodeWithTag("manual-people-input").assertTextEquals("我的称呼🙂")
        failWrite=false;compose.onNodeWithTag("manual-people-retry").performScrollTo().performClick();pump()
        assertEquals(listOf("小林","我的称呼🙂"),persisted.getValue("one").names)
        compose.onNodeWithTag("manual-people-remove-1").performScrollTo().performClick();pump()
        assertEquals(listOf("小林"),persisted.getValue("one").names);assertEquals("合成独立草稿",state.value.text)
        assertEquals(entries,state.value.entries)
    }
    @Test fun explicitPersonAndKeywordSavedZoneDateOpenOriginalAndRetireStalePersonResult(){
        content();field("original-search-person-input","小林")
        compose.onNodeWithTag("original-search-person-apply").performScrollTo().performClick()
        await{it.stage==OriginalSearchStage.READY&&it.input.person=="小林"};assertEquals(2,result!!.hits.size)
        field("original-search-query","咖啡");field("original-search-from","2026-10-14");field("original-search-through","2026-10-14")
        await{it.stage==OriginalSearchStage.READY&&it.input.through=="2026-10-14"};assertEquals(listOf("one"),result!!.hits.map{it.entry.id})
        val stale=result!!;val staleOpen=open!!
        compose.runOnIdle{state.value=state.value.copy(manualPeople=emptyMap());staleOpen(stale.hits.single())}
        compose.onAllNodes(hasTestTag("original-text")).assertCountEquals(0)
        await{it.stage==OriginalSearchStage.READY&&it.hits.isEmpty()}
        compose.runOnIdle{state.value=state.value.copy(manualPeople=persisted.toMap())}
        await{it.stage==OriginalSearchStage.READY&&it.hits.size==1}
        compose.runOnIdle{open!!(result!!.hits.single())}
        compose.onNodeWithTag("original-text").assertTextEquals(entries.first().originalText)
    }
    @Test fun doubleFontEditorKeepsCloseReachableAndPrivateInputOutOfSavedStateWhenSourceFails(){
        content(large=true);openEditor();field("manual-people-input","未提交私密称呼")
        compose.runOnIdle{assertFalse(registry!!.performSave().toString().contains("未提交私密称呼"));state.value=state.value.copy(error="合成读取失败")}
        compose.onAllNodes(hasText("小林")).assertCountEquals(0)
        compose.onNodeWithTag("manual-people-add").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("manual-people-input").assertTextEquals("未提交私密称呼")
        compose.onNodeWithTag("manual-people-close").performScrollTo().assertIsDisplayed().performClick()
        assertNull(state.value.manualPeopleEditor)
    }
    private fun content(large:Boolean=false){
        state.value=state.value.copy(supportsManualPeople=true,manualPeopleOwner=people,manualPeopleAvailable=true,manualPeople=persisted.toMap(),
            localProcessingAvailable=true,localProcessingVersions=store.localProcessing.versions())
        compose.setContent{
            registry=LocalSaveableStateRegistry.current;val density=LocalDensity.current
            CompositionLocalProvider(LocalOriginalSearchObserver provides observer,LocalOriginalReadingObserver provides reader,LocalDensity provides if(large)Density(density.density,2f)else density){
                Box(if(large)Modifier.size(320.dp,640.dp)else Modifier){AIyouTheme{AIyouApp(previewDestination=Destination.MEMORIES,
                    local=state.value,currentRecords={state.value},people=controller)}}
            }
        };compose.waitForIdle()
    }
    private fun openEditor(){compose.runOnIdle{navigation.openEntry("one")};compose.waitForIdle()
        compose.onNodeWithTag("original-manual-people").performScrollTo().performClick();pump()}
    private fun pump(){compose.runOnIdle{runBlocking{queue.removeFirst()(store)}};compose.waitForIdle()}
    private fun field(tag:String,text:String)=compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(text)
    private fun await(predicate:(OriginalSearchState)->Boolean){compose.waitForIdle();compose.waitUntil(2_000){result?.let(predicate)==true};compose.waitForIdle()}
    companion object {
        private fun entry(id:String,text:String,zone:String)=StoredEntry(id,1,text,Instant.parse("2026-10-13T23:30:00Z").toEpochMilli(),zone,ProcessingPolicy.LOCAL_ONLY)
        private fun snapshot(entry:StoredEntry,version:Long,names:List<String>)=ManualPeopleSnapshot(entry.id,version,names,1,OriginalExports.hash(entry.originalText),0)
    }
}
