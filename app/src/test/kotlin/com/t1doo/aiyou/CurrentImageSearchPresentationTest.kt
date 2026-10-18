package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
    private val grid=CurrentImageGridController({ false }, { error("No Android decode in this presentation fixture") }, { state.value })
    private val results=AtomicReference<OriginalSearchState>()
    private val diagnostics=AtomicReference("")
    private val observer=object:OriginalSearchObserver {
        override fun onResults(state:OriginalSearchState,open:(OriginalSearchHit)->Unit){results.set(state)}
        override fun onDiagnostics(value:String){diagnostics.set(value)}
    }
    @Composable private fun Screen(large:Boolean=false,fixture:SyntheticEvidenceAnswerProvider?=null,observe:Boolean=true){
        val density=LocalDensity.current
        CompositionLocalProvider(LocalOriginalSearchObserver provides if(observe)observer else null,
            LocalDensity provides if(large)Density(density.density,2f)else density){
            AIyouTheme{Box(if(large)Modifier.size(320.dp,640.dp)else Modifier){
                AIyouApp(previewDestination=Destination.MEMORIES,local=state.value,currentRecords={state.value},imageGrid=grid,evidenceAnswerFixture=fixture)
            }}
        }
    }
    // Searching existing semantics cannot discover an uncomposed lazy item. Scroll the
    // actual list while retaining the existing bounded asynchronous publication wait.
    private fun reveal(tag: String) = reveal(hasTestTag(tag))
    private fun reveal(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        var missing: AssertionError? = null
        try {
            compose.waitUntil(3_000) {
                try {
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
                    true
                } catch (failure: AssertionError) {
                    missing = failure
                    false
                }
            }
        } catch (timeout: ComposeTimeoutException) {
            val tags = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
            val messages = compose.onAllNodes(hasAnyAncestor(hasTestTag("local-evidence-status")), useUnmergedTree = true)
                .fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            System.err.println("UI_LAZY_DIAGNOSTIC stage=${results.get()?.stage} hits=${results.get()?.hits?.size} tags=$tags status=$messages owners=${diagnostics.get()}")
            missing?.let(timeout::addSuppressed)
            throw timeout
        }
        return compose.onNode(matcher).assertIsDisplayed()
    }
    private fun await(predicate:(OriginalSearchState)->Boolean){
        compose.waitForIdle();compose.waitUntil(3_000){results.get()?.let(predicate)==true}
    }
    @Test fun imageFilterStartsOffCombinesLiteralAndDatesAndClearResetsEveryCondition(){
        compose.setContent{Screen()};await{it.stage==OriginalSearchStage.BROWSE}
        assertFalse(results.get().input.onlyImages)
        reveal("original-search-images").performClick()
        await{it.stage==OriginalSearchStage.READY&&it.input.onlyImages}
        assertEquals(listOf(picture.id),results.get().hits.map{it.entry.id})
        reveal("original-search-query").performTextReplacement("needle")
        reveal("original-search-from").performTextReplacement("1970-01-01")
        reveal("original-search-through").performTextReplacement("1970-01-01")
        await{it.stage==OriginalSearchStage.READY&&it.input.through=="1970-01-01"}
        assertTrue(results.get().input.onlyImages);assertEquals(listOf(picture.id),results.get().hits.map{it.entry.id})
        reveal("original-search-clear").performClick()
        await{it.stage==OriginalSearchStage.BROWSE}
        assertFalse(results.get().input.onlyImages);assertEquals("",results.get().input.query)
        assertEquals("",results.get().input.from);assertEquals("",results.get().input.through)
    }
    @Test fun narrowDoubleFontFilterIsReachableAndReadFailureCannotBecomeEmptySuccess(){
        compose.setContent{Screen(true)}
        val control=reveal("original-search-images").assertIsDisplayed().assertIsEnabled()
        assertTrue(control.getUnclippedBoundsInRoot().height>=48.dp)
        compose.runOnIdle{failRead=true};control.performClick()
        await{it.stage==OriginalSearchStage.FAILED&&it.input.onlyImages}
        assertTrue(results.get().hits.isEmpty());assertTrue(results.get().message!!.contains("原文已保留"))
        compose.onAllNodesWithText("没有匹配的原文").assertCountEquals(0)
        reveal("original-search-images").assertIsEnabled().performClick()
        await{it.stage==OriginalSearchStage.BROWSE}
        assertFalse(results.get().input.onlyImages)
    }
    @Test fun gridRequiresExplicitToggleKeepsFailureCardAndOpensOriginalSource(){
        compose.setContent{Screen()};await{it.stage==OriginalSearchStage.BROWSE}
        compose.onAllNodesWithTag("original-search-grid").assertCountEquals(0)
        reveal("original-search-images").performClick()
        await{it.stage==OriginalSearchStage.READY&&it.input.onlyImages}
        compose.onAllNodesWithTag("image-grid-entry-picture").assertCountEquals(0)
        reveal("original-search-grid").performClick()
        reveal("image-grid-entry-picture").assertIsDisplayed()
        compose.waitUntil(3_000){grid.state.value.tiles[picture.id]?.failed==true}
        compose.onNodeWithTag("image-grid-failed-picture",useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("image-grid-entry-picture").performClick()
        awaitOriginalPicture()
    }
    @Test fun narrowDoubleFontGridToggleAndFailureCardRemainReachableAndClearReturnsToList(){
        compose.setContent{Screen(true)}
        reveal("original-search-images").performClick()
        await{it.stage==OriginalSearchStage.READY&&it.input.onlyImages}
        val toggle=reveal("original-search-grid").assertIsDisplayed().assertIsEnabled()
        assertTrue(toggle.getUnclippedBoundsInRoot().height>=48.dp);toggle.performClick()
        reveal("image-grid-entry-picture").assertIsDisplayed()
        reveal("original-search-clear").performClick()
        await{it.stage==OriginalSearchStage.BROWSE}
        compose.onAllNodesWithTag("original-search-grid").assertCountEquals(0)
        compose.onAllNodesWithTag("image-grid-entry-picture").assertCountEquals(0)
        assertTrue(grid.state.value.tiles.isEmpty())
    }
    @Test fun localEvidenceCardsUseCurrentSourceAndReturnWithoutChangingQuestion(){
        compose.setContent{Screen()}
        reveal("original-search-query").performTextReplacement("needle")
        await{it.stage==OriginalSearchStage.READY}
        reveal("local-evidence-mode").performClick()
        reveal("local-evidence-mode").assertTextContains("本机出处候选 · 返回原文列表")
        reveal("local-evidence-picture").assertExists()
        compose.onNode(hasText("查看原文") and hasAnyAncestor(hasTestTag("local-evidence-picture")))
            .performScrollTo().performClick()
        awaitOriginalPicture()
        compose.onNodeWithText("返回列表").performClick()
        await{it.stage==OriginalSearchStage.READY&&it.input.query=="needle"}
        reveal("local-evidence-picture")
        compose.onNodeWithTag("local-evidence-picture").assertExists()
    }
    @Test fun evidenceModeStartsAfterReadySearchWithoutAnyTestObserver(){
        compose.setContent{Screen(observe=false)}
        reveal("original-search-query").performTextReplacement("needle")
        reveal(hasText("找到 2 条原文")).assertIsDisplayed()
        reveal("local-evidence-mode").performClick()
        reveal("local-evidence-picture").assertIsDisplayed()
        reveal("local-evidence-mode").performClick()
        reveal("local-evidence-mode").assertTextContains("为当前问题选取本机出处候选")
        reveal("original-search-query").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("needle")))
        compose.onAllNodesWithTag("local-evidence-status").assertCountEquals(0)
    }
    @Test fun narrowDoubleFontEvidenceShowsExplicitNoEvidenceAndKeepsManualListReachable(){
        compose.setContent{Screen(true)}
        reveal("original-search-query").performTextReplacement("missing")
        await{it.stage==OriginalSearchStage.READY&&it.hits.isEmpty()}
        val toggle=reveal("local-evidence-mode").assertIsEnabled()
        assertTrue(toggle.getUnclippedBoundsInRoot().height>=48.dp);toggle.performClick()
        reveal(hasText("没有可引用的证据")).assertIsDisplayed()
        reveal("local-evidence-mode").performClick()
        compose.onAllNodesWithTag("local-evidence-status").assertCountEquals(0)
        assertEquals("missing",results.get().input.query)
    }
    @Test fun localEvidenceRequiresExplicitSelectionAndReconfirmationAfterExclusion(){
        compose.setContent{Screen()}
        reveal("original-search-query").performTextReplacement("needle")
        await{it.stage==OriginalSearchStage.READY}
        reveal("local-evidence-mode").performClick()
        reveal("local-evidence-confirm").assertIsNotEnabled()
        reveal("local-evidence-select-picture").assertIsOff().performClick().assertIsOn()
        reveal("local-evidence-confirm").assertIsEnabled().performClick()
        reveal("local-evidence-confirmed")
        reveal("local-evidence-select-picture").performClick().assertIsOff()
        reveal("local-evidence-status")
        compose.onAllNodesWithTag("local-evidence-confirmed").assertCountEquals(0)
        reveal("local-evidence-confirm").assertIsNotEnabled()
    }
    @Test fun narrowDoubleFontLocalEvidenceSelectionAndConfirmationRemainReachable(){
        compose.setContent{Screen(true)}
        reveal("original-search-query").performTextReplacement("needle")
        await{it.stage==OriginalSearchStage.READY}
        reveal("local-evidence-mode").performClick()
        val checkbox=reveal("local-evidence-select-picture").assertIsDisplayed()
        assertTrue(checkbox.getUnclippedBoundsInRoot().height>=48.dp);checkbox.performClick()
        val confirm=reveal("local-evidence-confirm").assertIsDisplayed().assertIsEnabled()
        assertTrue(confirm.getUnclippedBoundsInRoot().height>=48.dp);confirm.performClick()
        reveal("local-evidence-confirmed").assertIsDisplayed()
    }

    private fun selectPictureForAnswer(){
        reveal("original-search-query").performTextReplacement("needle")
        await{it.stage==OriginalSearchStage.READY}
        reveal("local-evidence-mode").performClick()
        reveal("local-evidence-select-picture").performClick()
        reveal("local-evidence-confirm").performClick()
        reveal("local-evidence-confirmed")
    }
    private fun awaitOriginalPicture(){
        // search.open publishes owned navigation asynchronously. Wait for
        // its actual unmerged Text, then retain the complete original assertion.
        compose.waitUntil(3_000){compose.onAllNodesWithTag("original-text",useUnmergedTree=true).fetchSemanticsNodes().size==1}
        compose.onNodeWithTag("original-text",useUnmergedTree=true).assertTextContains(picture.originalText)
    }
    @Test fun syntheticSelectedAnswerCitationsOpenOriginalAndReturningRequiresNewConfirmation(){
        val fixture=SyntheticEvidenceAnswerProvider { r -> LocalEvidenceAnswerResponse(r.id,r.packet.selectionId,
            r.packet.citations.map { LocalEvidenceAnswerParagraph("合成引用："+it.reference.quote,listOf(it.reference)) }) }
        compose.setContent{Screen(fixture=fixture)};selectPictureForAnswer()
        reveal("local-answer-ask").performClick()
        reveal("local-answer-paragraph-0").assertTextEquals("合成引用："+picture.originalText)
        compose.onNode(hasText("查看原文") and hasAnyAncestor(hasTestTag("local-answer-source-0-picture")))
            .performScrollTo().performClick()
        awaitOriginalPicture()
        compose.onNodeWithText("返回列表").performClick();await{it.stage==OriginalSearchStage.READY}
        compose.onAllNodesWithTag("local-answer-paragraph-0").assertCountEquals(0)
    }
    @Test fun narrowDoubleFontSyntheticNoEvidenceKeepsOriginalCardsAndNeverInventsAnswer(){
        val fixture=SyntheticEvidenceAnswerProvider { r -> LocalEvidenceAnswerResponse(r.id,r.packet.selectionId,noEvidence=true) }
        compose.setContent{Screen(true,fixture)};selectPictureForAnswer()
        val ask=reveal("local-answer-ask").assertIsDisplayed().assertIsEnabled()
        assertTrue(ask.getUnclippedBoundsInRoot().height>=48.dp);ask.performClick()
        reveal(hasText("选定出处不足以回答")).assertIsDisplayed()
        compose.onAllNodesWithTag("local-answer-paragraph-0").assertCountEquals(0)
        reveal("local-evidence-picture").assertIsDisplayed()
    }
    @Test fun syntheticAnswerStopCancelsRequestAndSuppressesLateText(){
        val request=AtomicReference<LocalEvidenceAnswerRequest>()
        val reply=kotlinx.coroutines.CompletableDeferred<LocalEvidenceAnswerResponse>()
        val fixture=SyntheticEvidenceAnswerProvider { r -> request.set(r);reply.await() }
        compose.setContent{Screen(fixture=fixture)};selectPictureForAnswer()
        reveal("local-answer-ask").performClick()
        compose.waitUntil(3_000){request.get()!=null}
        reveal("local-answer-stop").performClick()
        compose.waitUntil(3_000){request.get().cancelled}
        val r=request.get();reply.complete(LocalEvidenceAnswerResponse(r.id,r.packet.selectionId,
            listOf(LocalEvidenceAnswerParagraph("合成迟到响应",listOf(r.packet.citations[0].reference)))))
        reveal(hasText("回答已停止")).assertIsDisplayed()
        compose.onAllNodesWithTag("local-answer-paragraph-0").assertCountEquals(0)
    }

}
