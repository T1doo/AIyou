package com.t1doo.aiyou

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.width
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.records.LocalRecordUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production route with synthetic state on a JVM; no native database/device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
class LocalRecordsPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(sdk = [35], qualifiers = "w840dp-h1000dp")
    fun productionCaptureAndMemoriesStayCenteredAndBoundedOnWideScreens() {
        compose.setContent {
            AIyouTheme { AIyouApp(local = LocalRecordUiState(ready = true, opening = false, text = "合成草稿")) }
        }
        val field = compose.onNodeWithContentDescription("此刻，想记下什么")
        assertTrue(field.getUnclippedBoundsInRoot().width <= AIyouDimensions.contentMaxWidth)
        val rootCenter = compose.onRoot().fetchSemanticsNode().boundsInRoot.center.x
        assertEquals(rootCenter, field.fetchSemanticsNode().boundsInRoot.center.x, 1f)
        compose.onNodeWithText("回忆").performClick()
        val summary = compose.onNodeWithText("0 条本机记录")
        assertTrue(summary.getUnclippedBoundsInRoot().width <= AIyouDimensions.contentMaxWidth)
        assertEquals(rootCenter, summary.fetchSemanticsNode().boundsInRoot.center.x, 1f)
    }

    @Test fun storageFailureUsesItsOwnRetryAndDoesNotMarkValidInputInvalid() {
        var retries = 0
        compose.setContent {
            AIyouTheme { AIyouApp(local = LocalRecordUiState(ready = true, opening = false, text = "合成保留草稿",
                error = "合成存储失败，原草稿已保留。"), onRetry = { retries++ }) }
        }
        val field = compose.onNodeWithContentDescription("此刻，想记下什么")
        field.assertIsEnabled()
        assertNull(field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error))
        val message = compose.onNodeWithText("合成存储失败，原草稿已保留。")
        message.assertIsDisplayed()
        assertTrue(message.fetchSemanticsNode().boundsInRoot.top >= field.fetchSemanticsNode().boundsInRoot.bottom)
        compose.onNodeWithText("重试").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun pendingSaveKeepsDraftLockedAndRetryAvailableWithoutClaimingSuccess() {
        var saves = 0
        var retries = 0
        compose.setContent {
            AIyouTheme { AIyouApp(local = LocalRecordUiState(ready = true, opening = false, savePending = true,
                text = "合成待确认提交", error = "合成保存失败，请重试。"),
                onSave = { saves++ }, onRetry = { retries++ }) }
        }
        compose.onNodeWithContentDescription("此刻，想记下什么").assertIsNotEnabled()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onAllNodesWithText("已保存在本机").assertCountEquals(0)
        compose.onNodeWithText("重试").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, saves); assertEquals(1, retries) }
    }

    @Test fun inputFeedbackIsAnnouncedAndClearsOnAcceptedEditWithoutLosingFocus() {
        val state = mutableStateOf(LocalRecordUiState(ready = true, opening = false, text = "合成原草稿"))
        val rejection = "合成超限拒绝，本次输入未替换原草稿。"
        compose.setContent {
            AIyouTheme { AIyouApp(local = state.value, onDraftChange = {
                state.value = state.value.copy(text = it, inputNotice = null)
            }) }
        }
        val field = compose.onNodeWithContentDescription("此刻，想记下什么")
        field.performClick().assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(inputNotice = rejection) }
        field.assertIsFocused()
        assertEquals(rejection, field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error))
        compose.onNodeWithText(rejection).assertIsDisplayed().assertHasNoClickAction()
        field.performTextReplacement("合成恢复编辑")
        field.assertIsFocused()
        compose.runOnIdle {
            assertEquals("合成恢复编辑", state.value.text)
            assertNull(state.value.inputNotice)
        }
        compose.onAllNodesWithText(rejection).assertCountEquals(0)
        compose.onNodeWithText("输入长度 6 / 100000").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h1000dp")
    fun narrowLargeFontProductionFormKeepsSaveReachableWithoutPlaceholderButtons() {
        var saves = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AIyouTheme { AIyouApp(local = LocalRecordUiState(ready = true, opening = false, text = "合成大字草稿"),
                    onSave = { saves++ }) }
            }
        }
        compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
        compose.onAllNodesWithText("图片未开放").assertCountEquals(0)
        compose.onAllNodesWithText("录音未开放").assertCountEquals(0)
        compose.onAllNodesWithText("问一问 · 未开放").assertCountEquals(0)
    }
}
