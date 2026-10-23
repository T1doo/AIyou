package com.t1doo.aiyou

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual production list/disclosures. Counts are UI callbacks, not SQL/device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h800dp")
class NotebookToolsPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val local=mutableStateOf(LocalRecordUiState(ready=true,opening=false,
        supportsSourcePreferences=true,supportsOriginalExport=true,recycleNotice="合成回收站反馈"))
    private val destination=mutableStateOf(Destination.MEMORIES)
    private val query=TextFieldState("合成关键词🙂")
    private val from=TextFieldState("2026-10-01")
    private val through=TextFieldState("2026-10-23")
    private val search=OriginalSearchState(OriginalSearchInput(),OriginalSearchStage.BROWSE)
    private var actions=0
    private fun content(doubleFont:Boolean=false) { compose.setContent {
        val d=LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density,if(doubleFont) 2f else 1f)) {
            AIyouTheme { LocalRecordsScreen(destination.value,local.value,{actions++},{actions++},{actions++},
                onOpenPreferences={actions++},onBeginExport={actions++},
                searchState=search,searchQuery=query,searchFrom=from,searchThrough=through,
                onSearchMode={actions++},onClearSearch={actions++},imageTextBusy=true) }
        }
    } }
    private fun toggle(tag:String)=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
    private fun unchanged() { compose.runOnIdle {
        assertEquals(0,actions);assertEquals("合成关键词🙂",query.text.toString())
        assertEquals("2026-10-01",from.text.toString());assertEquals("2026-10-23",through.text.toString())
    } }

    @Test fun searchPrecedesCollapsedToolsAndDisclosurePreservesInputsFeedbackAndDisabledActions() {
        content()
        compose.onNodeWithTag("original-search-query").assertIsDisplayed()
        compose.onNodeWithTag("preferences-open").assertDoesNotExist()
        compose.onNodeWithTag("export-open").assertDoesNotExist()
        compose.onNodeWithText("上一图片提取仍在结束，文字入口等待资源释放。").assertExists()
        compose.onNodeWithText("合成回收站反馈").assertExists()
        compose.showNotebookToolsFor("preferences-open");compose.showNotebookToolsFor("export-open");unchanged()
        compose.onNodeWithTag("export-open").performScrollTo().assertIsEnabled()
        compose.runOnIdle {local.value=local.value.copy(saving=true)}
        compose.onNodeWithTag("export-open").assertIsNotEnabled()
        toggle("notebook-manage-tools").performClick();toggle("notebook-review-tools").performClick();unchanged()
        compose.onNodeWithTag("export-open").assertDoesNotExist()
        compose.onNodeWithTag("preferences-open").assertDoesNotExist()
        compose.showNotebookToolsFor("preferences-open")
        compose.runOnIdle {destination.value=Destination.NOW};compose.waitForIdle()
        compose.runOnIdle {destination.value=Destination.MEMORIES};compose.waitForIdle()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithTag("notebook-review-tools").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已收起"))
        unchanged()
    }

    @Test @Config(qualifiers="w640dp-h320dp-land")
    fun doubleFontLandscapeKeyboardDisclosureAndSearchRemainReachableWithoutCallingTools() {
        content(doubleFont=true)
        val button=toggle("notebook-review-tools").assertHeightIsAtLeast(48.dp)
        val view=(button.fetchSemanticsNode().root as ViewRootForTest).view
        compose.runOnIdle {assertTrue(view.requestFocusFromTouch())};compose.waitForIdle()
        button.performSemanticsAction(SemanticsActions.RequestFocus){assertTrue(it())}
        button.assertIsFocused().performKeyInput {pressKey(Key.Enter)}
        compose.onNodeWithTag("preferences-open").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        toggle("notebook-review-tools").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("original-search-query"))
        compose.onNodeWithTag("original-search-query").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.RequestFocus){assertTrue(it())}
        compose.onNodeWithTag("original-search-query").assertIsFocused()
        unchanged()
    }
}
