package com.t1doo.aiyou

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.designsystem.CaptureComposer
import com.t1doo.aiyou.designsystem.ComponentState
import com.t1doo.aiyou.records.LocalRecordUiState
import com.t1doo.aiyou.ui.SearchSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Compose interaction/semantics on a JVM Android simulation, not device/TalkBack proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class UnavailableControlsInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unopenedCapabilitiesAreDisabledWhileActualSaveStillWorks() {
        var modes = 0
        var saves = 0
        compose.setContent {
            AIyouTheme {
                CaptureComposer("合成待保存文字", {}, askAvailable = false,
                    onModeChange = { modes++ }, onSubmit = { saves++ })
            }
        }
        for (label in listOf("图片未开放", "录音未开放")) {
            compose.onNodeWithText(label).assertIsNotEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "尚未开放"))
                .performTouchInput { click() }
        }
        compose.onNodeWithText("问一问 · 未开放").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, modes); assertEquals(0, saves) }
        compose.onNodeWithText("保存").assertIsEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun implementedButBusyCapabilitiesRejectTouchesWithoutUnopenedLabels() {
        var invoked = 0
        compose.setContent {
            AIyouTheme {
                CaptureComposer("合成忙碌文字", { invoked++ }, onModeChange = { invoked++ },
                    onSubmit = { invoked++ }, onImage = { invoked++ }, onAudio = { invoked++ },
                    state = ComponentState.LOADING)
            }
        }
        for (label in listOf("图片", "录音")) {
            compose.onNodeWithText(label).assertIsNotEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前不可用"))
                .performTouchInput { click() }
        }
        compose.onNodeWithText("保存").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("问一问").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithContentDescription("此刻，想记下什么").assertIsNotEnabled()
            .performTouchInput { click() }.assertIsNotFocused()
        compose.runOnIdle { assertEquals(0, invoked) }
    }

    @Test fun unopenedSearchRejectsFocusAndEditingAndFiltersHaveNoClickAction() {
        compose.setContent { AIyouTheme { SearchSection(demo = false) } }
        val search = compose.onNodeWithContentDescription("搜索回忆，尚未开放")
        search.assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "尚未开放"))
            .performTouchInput { click() }.assertIsNotFocused()
        // Accessibility editing must be absent or actually reject the request;
        // testing only the disabled visual color would not protect this boundary.
        val edit = search.fetchSemanticsNode().config.getOrNull(SemanticsActions.SetText)?.action
        if (edit != null) compose.runOnIdle { assertFalse(edit(AnnotatedString("不应写入的合成输入"))) }
        compose.onNodeWithText("搜索回忆 · 尚未开放").assertIsDisplayed()
        val filters = compose.onNodeWithContentDescription("日期、人物、标签、图片、收藏筛选尚未开放")
        filters.assertIsNotEnabled().assertHasNoClickAction().performTouchInput { click() }
        compose.runOnIdle { assertNull(filters.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)) }
    }

    @Test fun integratedReadyLocalScreenRetainsRealDraftAndSaveCallbacks() {
        var edited: String? = null
        var saves = 0
        compose.setContent {
            AIyouTheme {
                AIyouApp(local = LocalRecordUiState(ready = true, opening = false, text = "合成草稿"),
                    onDraftChange = { edited = it }, onSave = { saves++ })
            }
        }
        compose.onNodeWithContentDescription("此刻，想记下什么").performTextReplacement("合成编辑后的草稿")
        compose.runOnIdle { assertEquals("合成编辑后的草稿", edited) }
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
        compose.onNodeWithText("最近记录").assertIsDisplayed()
        compose.onNodeWithContentDescription("处理策略：仅本机处理").assertHasNoClickAction()
    }

    @Test fun offlineLicenseRouteAndReturnToLocalMemoriesSurviveIntegration() {
        compose.setContent {
            AIyouTheme { AIyouApp(local = LocalRecordUiState(ready = true, opening = false)) }
        }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("开源许可与声明").performClick()
        compose.onNodeWithText("开源许可与声明 · 离线文本").assertIsDisplayed()
        compose.onNodeWithText("返回设置").performClick()
        compose.onNodeWithText("回忆").performClick()
        compose.onNodeWithText("0 条本机记录").assertIsDisplayed()
        compose.onNodeWithContentDescription("搜索回忆，尚未开放").assertIsNotEnabled()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h1000dp")
    fun narrowLargeFontKeepsFullUnavailableLabelsAndStaticFilterSemantics() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AIyouTheme {
                    Column {
                        CaptureComposer("合成大字文字", {}, askAvailable = false)
                        SearchSection(demo = false)
                    }
                }
            }
        }
        compose.onNodeWithText("图片未开放").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("录音未开放").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("图片").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithContentDescription("日期、人物、标签、图片、收藏筛选尚未开放")
            .assertIsNotEnabled().assertHasNoClickAction()
    }
}
