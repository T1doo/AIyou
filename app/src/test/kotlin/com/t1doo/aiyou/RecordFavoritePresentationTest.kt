package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual App/Compose/controller; synthetic repository shell, no SQLCipher/device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h1000dp")
class RecordFavoritePresentationTest {
    @get:Rule val compose=createComposeRule()
    private val one=StoredEntry("one",1,"合成 needle 第一条🙂",55,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val two=StoredEntry("two",1,"合成 needle 第二条",65,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,text="synthetic-private-draft",
        entries=listOf(one,two),localProcessingVersions=listOf(one,two).associate{it.id to LocalProcessingVersion(SourceVersion(it.id,it.currentRevision.toLong()),0)},supportsFavorites=true,favoritesAvailable=true,
        favorites=mapOf("one" to RecordFavoriteSnapshot("one",0,false),"two" to RecordFavoriteSnapshot("two",0,false))))
    private val queue=ArrayDeque<suspend(EntryRepository)->Unit>()
    private val durable=state.value.favorites.toMutableMap()
    private val calls=mutableListOf<RecordFavoriteMutation>()
    private var lose=false
    private val port=object:RecordFavoriteRepository {
        override fun read(entryId:String)=durable.getValue(entryId)
        override fun snapshots()=durable.toMap()
        override fun set(mutation:RecordFavoriteMutation):RecordFavoriteSnapshot {
            calls+=mutation
            val old=durable.getValue(mutation.entryId)
            val next=if(old.version==mutation.expectedVersion)RecordFavoriteSnapshot(mutation.entryId,old.version+1,mutation.isFavorite)
                else old
            durable[mutation.entryId]=next
            if(lose){lose=false;throw RecordFavoriteStorageFailure()};return next
        }
    }
    private val store=object:EntryRepository {
        override val favorites=port
        override suspend fun readDraft()=null
        override suspend fun updateDraft(text:String)=error("Outside UI contract")
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("Outside UI contract")
        override suspend fun entries()=state.value.entries
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("Outside UI contract")
    }
    private val controller=RecordFavoriteController({state.value},{state.value=it(state.value)},{queue.addLast(it);true})
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
                AIyouApp(previewDestination=Destination.MEMORIES,local=state.value,currentRecords={state.value},favorites=controller)
            } }
        }
    }
    private fun open(id:String){
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-$id"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-$id"))).performClick()
    }
    private fun drain(){compose.runOnIdle{runBlocking{while(queue.isNotEmpty())queue.removeFirst()(store)}};compose.waitForIdle()}
    private fun onlyFavorites(){compose.onNodeWithTag("original-search-favorites").performScrollTo().performClick();readyFavorites()}
    private fun readyFavorites(){compose.waitForIdle();compose.waitUntil(2_000){results.get()?.let{it.stage==OriginalSearchStage.READY&&it.input.onlyFavorites}==true}}

    @Test fun actualReaderChoiceFavoriteTimelineCombinedLookupAndUnfavoriteReturn(){
        compose.setContent{Screen()};open("one")
        compose.onNodeWithTag("original-favorite-set").assertIsEnabled().performClick()
        assertFalse(state.value.favorites.getValue("one").isFavorite);drain()
        compose.onNodeWithText("取消收藏").assertIsDisplayed();compose.onNodeWithText("返回列表").performClick()
        onlyFavorites();assertEquals(listOf("one"),results.get().hits.map{it.entry.id})
        compose.onNodeWithTag("original-search-query").performScrollTo().performTextReplacement("needle")
        compose.onNodeWithTag("original-search-from").performScrollTo().performTextReplacement("1970-01-01")
        compose.onNodeWithTag("original-search-through").performScrollTo().performTextReplacement("1970-01-01")
        compose.waitForIdle();compose.waitUntil(2_000){results.get()?.let{it.stage==OriginalSearchStage.READY&&it.input.through=="1970-01-01"}==true}
        assertEquals(listOf("one"),results.get().hits.map{it.entry.id});open("one")
        compose.onNodeWithTag("original-favorite-set").performClick();drain()
        compose.onNodeWithText("返回列表").performClick();readyFavorites();assertTrue(results.get().hits.isEmpty())
        compose.runOnIdle{assertEquals("synthetic-private-draft",state.value.text);assertEquals(one,state.value.entries.first());assertEquals(2,calls.size)}
    }

    @Test fun uncertainChoiceHasReachableStableRetryAndNoCrossRecordSuccessNotice(){
        compose.setContent{Screen()};open("one");lose=true
        compose.onNodeWithTag("original-favorite-set").performClick();drain()
        compose.onNodeWithTag("original-favorite-set").assertIsNotEnabled()
        compose.onAllNodesWithTag("original-favorite-notice").assertCountEquals(0)
        compose.runOnIdle{
            val intent=state.value.favoriteWrites.getValue("one").intent
            val raw=checkNotNull(registry).performSave().toString()
            for(secret in listOf(intent.mutationId,intent.sourceHash,one.originalText,"synthetic-private-draft"))assertFalse(raw.contains(secret))
        }
        compose.onNodeWithTag("original-favorite-retry").assertIsDisplayed().performClick();drain()
        compose.onNodeWithTag("original-favorite-notice").assertIsDisplayed()
        assertEquals(1L,durable.getValue("one").version);assertSame(calls[0],calls[1])
        compose.runOnIdle{durable["one"]=RecordFavoriteSnapshot("one",2,false);state.value=state.value.copy(favorites=durable.toMap())}
        compose.onAllNodesWithTag("original-favorite-notice").assertCountEquals(0)
        compose.onNodeWithText("返回列表").performClick();open("two")
        compose.onNodeWithText("收藏",substring=false).assertIsDisplayed()
        compose.onAllNodesWithTag("original-favorite-notice").assertCountEquals(0)
    }

    @Test fun narrowDoubleFontControlIsReachableAndRetainedReaderActionCannotWriteAfterNavigation(){
        compose.setContent{Screen(true)};open("one")
        val node=compose.onNodeWithTag("original-favorite-set").assertIsDisplayed().assertIsEnabled()
        val bounds=node.getUnclippedBoundsInRoot();assertTrue(bounds.height>=48.dp)
        val old=node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val back=compose.onNodeWithText("返回列表").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        // Exercise the navigation fence before recomposition detaches the old clickable node.
        compose.runOnIdle{back();old();assertTrue(queue.isEmpty())}
        open("two")
        compose.onNodeWithTag("original-favorite-set").performClick();drain()
        assertTrue(durable.getValue("two").isFavorite);assertFalse(durable.getValue("one").isFavorite)
    }

    @Test fun restorationKeepsPublicFilterAndDropsPrivateQueryWhileReadFailureCannotShowEmptyFavorites(){
        durable["one"]=RecordFavoriteSnapshot("one",1,true)
        state.value=state.value.copy(favorites=durable.toMap())
        val restoration=StateRestorationTester(compose);restoration.setContent{Screen()}
        onlyFavorites();compose.onNodeWithTag("original-search-query").performScrollTo().performTextReplacement("synthetic-private-query")
        compose.runOnIdle{
            val raw=checkNotNull(registry).performSave().toString()
            for(secret in listOf("synthetic-private-query",one.originalText,"synthetic-private-draft"))assertFalse(raw.contains(secret))
        }
        restoration.emulateSavedInstanceStateRestore();readyFavorites()
        assertEquals("",results.get().input.query);assertEquals(listOf("one"),results.get().hits.map{it.entry.id})
        compose.runOnIdle{state.value=state.value.copy(favoritesAvailable=false,favoritesError="合成读取失败")}
        compose.waitForIdle();compose.waitUntil(2_000){results.get()?.stage==OriginalSearchStage.FAILED}
        assertTrue(results.get().hits.isEmpty())
        compose.onAllNodesWithText("还没有收藏记录").assertCountEquals(0)
        compose.onNodeWithTag("original-search-favorites").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle();compose.waitUntil(2_000){results.get()?.stage==OriginalSearchStage.BROWSE}
    }
}
