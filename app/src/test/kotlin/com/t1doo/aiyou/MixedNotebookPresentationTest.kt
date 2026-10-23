package com.t1doo.aiyou

import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.model.CaptureDraft
import com.t1doo.aiyou.records.LocalRecordUiState
import com.t1doo.aiyou.records.MixedCaptureController
import com.t1doo.aiyou.records.MixedCaptureUiState
import com.t1doo.aiyou.ui.UiPreferences
import com.t1doo.aiyou.ui.UiPreferencesState
import java.time.Clock
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual mixed-capture route, not the legacy text-only preview. No database/media writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MixedNotebookPresentationTest {
    @get:Rule val compose=createComposeRule()

    @Test @Config(qualifiers="w320dp-h1000dp")
    fun realNotebookUsesBundledLuluOnlyWhenPreferenceIsReadyAndVisibleWithoutWritingRecords() {
        val draft=CaptureDraft("f71b097d-6b88-4bde-bae4-637230066ea2",1,"",emptyList())
        val local=LocalRecordUiState(ready=true,opening=false,supportsMixedCapture=true,
            mixedCapture=MixedCaptureUiState(draft=draft,supportsProcessingChoice=true))
        val display=mutableStateOf(UiPreferencesState(UiPreferences(reduceMotion=true),ready=true))
        val fontScale=mutableStateOf(1f)
        var writes=0
        val controller=MixedCaptureController({local},{},{writes++;false},{error("No media input")},Clock.systemUTC())
        val image=BitmapFactory.decodeResource(RuntimeEnvironment.getApplication().resources,
            com.t1doo.aiyou.designsystem.R.drawable.lulu_hello,
            BitmapFactory.Options().apply { inSampleSize=2;inScaled=false })
        assertNotNull(image);assertTrue(image.hasAlpha());assertTrue(image.width in 620..630)
        image.recycle()
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale.value)) {
                AIyouTheme { AIyouApp(local=local,mixedCapture=controller,display=display.value) }
            }
        }
        compose.onNodeWithTag("mixed-list").assertExists()
        compose.onNodeWithText("生活的小片刻").assertIsDisplayed()
        assertCompanionSize(96f)
        compose.onNodeWithTag("companion-entry").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("打个招呼").performClick()
        compose.onNodeWithText("你好，慢慢记录就好。").assertIsDisplayed()
        compose.onNodeWithText("关闭陪伴面板").performClick()
        compose.runOnIdle { display.value=UiPreferencesState(UiPreferences(showCompanion=false,reduceMotion=true),ready=true) }
        compose.onNodeWithTag("companion-entry").assertDoesNotExist()
        compose.runOnIdle { display.value=UiPreferencesState(UiPreferences(showCompanion=true,reduceMotion=true),ready=false) }
        compose.onNodeWithTag("companion-entry").assertDoesNotExist()
        compose.runOnIdle { display.value=UiPreferencesState(UiPreferences(reduceMotion=true),ready=true) }
        compose.onNodeWithTag("companion-entry").assertIsDisplayed()
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(0,writes);assertSame(draft,local.mixedCapture!!.draft);assertTrue(local.entries.isEmpty()) }
        compose.runOnIdle { fontScale.value=2f }
        compose.onNodeWithTag("mixed-list").performScrollToIndex(0)
        compose.onNodeWithText("生活的小片刻").assertDoesNotExist()
        val compactHeading=compose.onNodeWithTag("mixed-notebook-heading").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(compactHeading.bottom-compactHeading.top<=88.dp)
        assertCompanionSize(56f)
        compose.onNodeWithTag("companion-entry").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("关闭陪伴面板").performClick()
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-policy-details"))
        val rangeAction=compose.onNodeWithTag("mixed-policy-details").performScrollTo().assertIsDisplayed()
            .assertIsEnabled().assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val summaryNode=compose.onNodeWithText("仅本机处理 · 不发远端").assertIsDisplayed()
        val rangeSummary=summaryNode.getUnclippedBoundsInRoot()
        assertTrue("At200% the full range summary precedes its wrapped action",rangeSummary.bottom<=rangeAction.top)
        val layouts=mutableListOf<TextLayoutResult>()
        summaryNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout=layouts.single()
        assertFalse("Range summary overflow: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
            "width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, exceededMaxLines=${layout.multiParagraph.didExceedMaxLines}, " +
            "lines=${layout.lineCount}, density=${layout.layoutInput.density}, constraints=${layout.layoutInput.constraints}, " +
            "summary=$rangeSummary, action=$rangeAction",layout.hasVisualOverflow)
        val lastLine=layout.layoutInput.text.text.substring(layout.getLineStart(layout.lineCount-1),layout.getLineEnd(layout.lineCount-1,visibleEnd=true)).trim()
        assertTrue("The narrow200% summary must not leave one character alone",lastLine.codePointCount(0,lastLine.length)>1)
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(0,writes);assertSame(draft,local.mixedCapture!!.draft);assertTrue(local.entries.isEmpty()) }
    }

    @Test @Config(qualifiers="w640dp-h320dp")
    fun emptyLandscapeDoubleFontHasReachableStartAndFocusedComposerWithoutWriting() {
        val draft=CaptureDraft("00c696bf-c102-4c63-aea2-246fd3be015c",1,"",emptyList())
        val local=LocalRecordUiState(ready=true,opening=false,supportsMixedCapture=true,
            mixedCapture=MixedCaptureUiState(draft=draft,supportsProcessingChoice=true))
        var writes=0
        val fontScale=mutableStateOf(2f)
        val controller=MixedCaptureController({local},{},{writes++;false},{error("No media input")},Clock.systemUTC())
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale.value)) {
                AIyouTheme { AIyouApp(local=local,mixedCapture=controller,
                    display=UiPreferencesState(UiPreferences(reduceMotion=true),ready=true)) }
            }
        }
        val compactHeading=compose.onNodeWithTag("mixed-notebook-heading").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(compactHeading.bottom-compactHeading.top<=88.dp)
        compose.onNodeWithTag("mixed-jump-composer").assertDoesNotExist()
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-policy-details"))
        compose.onNodeWithTag("mixed-policy-details").assertIsDisplayed().performClick()
        compose.onNodeWithTag("mixed-jump-composer").assertIsDisplayed().assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).assertTextEquals("回到输入").performClick()
        compose.onNodeWithTag("mixed-text").performScrollTo().assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.onNodeWithTag("mixed-text").assertIsFocused()
        compose.runOnIdle { assertEquals(0,writes);assertSame(draft,local.mixedCapture!!.draft);assertTrue(local.entries.isEmpty()) }
        compose.runOnIdle { fontScale.value=1f }
        compose.onNodeWithTag("mixed-list").performScrollToIndex(0)
        compose.onNodeWithText("生活的小片刻").assertDoesNotExist()
        val regularFontHeading=compose.onNodeWithTag("mixed-notebook-heading").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(regularFontHeading.bottom-regularFontHeading.top<=88.dp)
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(0,writes);assertSame(draft,local.mixedCapture!!.draft);assertTrue(local.entries.isEmpty()) }
    }

    @Test @Config(qualifiers="w800dp-h360dp")
    fun coldRegularFontLandscapeStartsCompactAndRetainsDraftAcrossReadOnlyActions() {
        val text="合成横屏冷启动草稿"
        val draft=CaptureDraft("c5883362-094d-483a-bc55-472076c0c387",1,text,emptyList())
        val local=LocalRecordUiState(ready=true,opening=false,supportsMixedCapture=true,
            mixedCapture=MixedCaptureUiState(draft=draft,text=text,supportsProcessingChoice=true))
        var writes=0
        val controller=MixedCaptureController({local},{},{writes++;false},{error("No media input")},Clock.systemUTC())
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1f)) {
                AIyouTheme { AIyouApp(local=local,mixedCapture=controller,
                    display=UiPreferencesState(UiPreferences(reduceMotion=true),ready=true)) }
            }
        }
        compose.onNodeWithText("生活的小片刻").assertDoesNotExist()
        val heading=compose.onNodeWithTag("mixed-notebook-heading").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(heading.bottom-heading.top<=88.dp)
        assertCompanionSize(56f)
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").performScrollTo().assertIsDisplayed().assertTextEquals(text)
        compose.onNodeWithTag("mixed-list").performScrollToIndex(0)
        compose.onNodeWithTag("companion-entry").performClick()
        compose.onNodeWithText("关闭陪伴面板").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-policy-details"))
        compose.onNodeWithTag("mixed-policy-details").assertIsDisplayed().performClick()
        compose.onNodeWithTag("mixed-jump-composer").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("mixed-text").performScrollTo().assertIsDisplayed().assertTextEquals(text)
            .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.onNodeWithTag("mixed-text").assertIsFocused()
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-save"))
        compose.onNodeWithTag("mixed-save").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { assertEquals(0,writes);assertSame(draft,local.mixedCapture!!.draft);assertTrue(local.entries.isEmpty()) }
    }

    private fun assertCompanionSize(expectedDp:Float) {
        val bounds=compose.onNodeWithTag("companion-entry").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(expectedDp,(bounds.bottom-bounds.top).value,.5f)
        assertEquals(expectedDp,(bounds.right-bounds.left).value,.5f)
    }
}
