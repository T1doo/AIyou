package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/** Actual Compose/root/controller with a synthetic association port, not SQLCipher or device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h1000dp")
class CurrentImageSearchPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val picture=StoredEntry("picture",1,"合成 needle 图片原文",5,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val text=StoredEntry("text",1,"合成 needle 文字原文",6,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private var failRead=false
    private val port=object:LocalSearchRepository {
        override fun batch()=LocalSearchProgress(0,false,1)
        override fun rebuild()=1L
        override fun epoch()=1L
        override fun candidates(request:LocalSearchRequest):LocalSearchSnapshot {
            if(failRead)throw LocalSearchFailure()
            return object:LocalSearchSnapshot {
                override val epoch=1L
                override val entries=if(request.onlyImages)listOf(picture)else listOf(picture,text)
                override val currentImageEntryIds=if(request.onlyImages)setOf(picture.id)else null
                override val tags=entries.associate{it.id to ManualTagSnapshot(it.id,0,emptyList())}
                override val pending=false
            }
        }
        override fun publishCurrent(snapshot:LocalSearchSnapshot,publish:()->Unit):Boolean {publish();return true}
        override fun resolve(snapshot:LocalSearchSnapshot,entryId:String,publish:(StoredEntry)->Unit):Boolean {
            snapshot.entries.find{it.id==entryId}?.let(publish)?:return false;return true
        }
    }
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,entries=listOf(picture,text),
        localSearch=port,localSearchEpoch=1))
    private val results=AtomicReference<OriginalSearchState>()
    private val observer=object:OriginalSearchObserver {
        override fun onResults(state:OriginalSearchState,open:(OriginalSearchHit)->Unit){results.set(state)}
    }
    @Composable private fun Screen(large:Boolean=false){
        val density=LocalDensity.current
        CompositionLocalProvider(LocalOriginalSearchObserver provides observer,
            LocalDensity provides if(large)Density(density.density,2f)else density){
            AIyouTheme{Box(if(large)Modifier.size(320.dp,640.dp)else Modifier){
                AIyouApp(previewDestination=Destination.MEMORIES,local=state.value,currentRecords={state.value})
            }}
        }
    }
    private fun await(predicate:(OriginalSearchState)->Boolean){
        compose.waitForIdle();compose.waitUntil(3_000){results.get()?.let(predicate)==true}
    }
    @Test fun imageFilterStartsOffCombinesLiteralAndDatesAndClearResetsEveryCondition(){
        compose.setContent{Screen()};await{it.stage==OriginalSearchStage.BROWSE}
        assertFalse(results.get().input.onlyImages)
        compose.onNodeWithTag("original-search-images").performScrollTo().performClick()
        await{it.stage==OriginalSearchStage.READY&&it.input.onlyImages}
        assertEquals(listOf(picture.id),results.get().hits.map{it.entry.id})
        compose.onNodeWithTag("original-search-query").performScrollTo().performTextReplacement("needle")
        compose.onNodeWithTag("original-search-from").performScrollTo().performTextReplacement("1970-01-01")
        compose.onNodeWithTag("original-search-through").performScrollTo().performTextReplacement("1970-01-01")
        await{it.stage==OriginalSearchStage.READY&&it.input.through=="1970-01-01"}
        assertTrue(results.get().input.onlyImages);assertEquals(listOf(picture.id),results.get().hits.map{it.entry.id})
        compose.onNodeWithTag("original-search-clear").performScrollTo().performClick()
        await{it.stage==OriginalSearchStage.BROWSE}
        assertFalse(results.get().input.onlyImages);assertEquals("",results.get().input.query)
        assertEquals("",results.get().input.from);assertEquals("",results.get().input.through)
    }
    @Test fun narrowDoubleFontFilterIsReachableAndReadFailureCannotBecomeEmptySuccess(){
        compose.setContent{Screen(true)}
        val control=compose.onNodeWithTag("original-search-images").performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertTrue(control.getUnclippedBoundsInRoot().height>=48.dp)
        compose.runOnIdle{failRead=true};control.performClick()
        await{it.stage==OriginalSearchStage.FAILED&&it.input.onlyImages}
        assertTrue(results.get().hits.isEmpty());assertTrue(results.get().message!!.contains("原文已保留"))
        compose.onAllNodesWithText("没有匹配的原文").assertCountEquals(0)
        compose.onNodeWithTag("original-search-images").performScrollTo().assertIsEnabled().performClick()
        await{it.stage==OriginalSearchStage.BROWSE}
        assertFalse(results.get().input.onlyImages)
    }
}
