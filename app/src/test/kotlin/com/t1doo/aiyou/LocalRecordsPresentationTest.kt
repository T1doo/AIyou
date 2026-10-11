package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
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

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun narrowLargeFontSaveFailureRetryScrollsIntoViewAndPreservesPendingDraft() {
        verifyNarrowFailureRetry(saveFailure = true)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun narrowLargeFontReadFailureRetryScrollsIntoViewAndPreservesKnownDraft() {
        verifyNarrowFailureRetry(saveFailure = false)
    }

    private fun verifyNarrowFailureRetry(saveFailure: Boolean) {
        val draft = "合成保留草稿：把沿途的树叶颜色记下来。"
        val message = if (saveFailure) "合成保存失败。输入与原数据已保留，可以重试。"
            else "合成读取失败。请保留原数据并先解锁设备重试。"
        // Synthetic known text tests presentation retention, not database read/retry behavior.
        val state = mutableStateOf(LocalRecordUiState(ready = saveFailure, opening = false,
            savePending = saveFailure, text = draft, error = message,
            draftNotice = if (saveFailure) "草稿保存状态待确认" else "草稿尚未读取"))
        var saveRetries = 0
        var readRetries = 0
        var saves = 0
        var edits = 0
        lateinit var inputModeManager: InputModeManager
        compose.setContent {
            inputModeManager = LocalInputModeManager.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                // Explicit constraints make this a compact viewport, independent of host window sizing.
                Box(Modifier.size(width = 320.dp, height = 640.dp).testTag("failure-viewport")) {
                    AIyouTheme { AIyouApp(local = state.value, onSave = { saves++ },
                    onDraftChange = { edits++ }, onRetry = {
                        if (saveFailure) {
                            saveRetries++
                            state.value = state.value.copy(saving = true, error = null)
                        } else {
                            readRetries++
                            state.value = state.value.copy(opening = true, error = null)
                        }
                    }) }
                }
            }
        }
        val phone = compose.onNodeWithTag("failure-viewport").getUnclippedBoundsInRoot()
        assertEquals(320.dp, phone.width)
        assertEquals(640.dp, phone.height)
        val field = compose.onNodeWithContentDescription("此刻，想记下什么")
        field.assertIsNotEnabled().performTouchInput { click() }
        val fieldMatcher = hasContentDescription("此刻，想记下什么")
        val focusedInput = isFocused() and (fieldMatcher or hasAnyAncestor(fieldMatcher))
        compose.onAllNodes(focusedInput, useUnmergedTree = true).assertCountEquals(0)
        assertEquals(draft, field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertNull(field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error))
        val retry = compose.onNodeWithText("重试")
        retry.assertIsNotDisplayed()
        val list = compose.onNode(hasScrollToIndexAction())
        val scrollBefore = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        list.performScrollToNode(hasText("重试"))
        assertTrue(list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > scrollBefore)
        retry.assertIsDisplayed().assertIsEnabled()
        assertEquals(Role.Button, retry.fetchSemanticsNode().config[SemanticsProperties.Role])
        val bounds = retry.getUnclippedBoundsInRoot()
        val viewport = list.getUnclippedBoundsInRoot()
        assertTrue(bounds.width > 0.dp && bounds.height >= AIyouDimensions.minTouchTarget)
        assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
        assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
            and hasAnyDescendant(hasText(message)))
            .assertIsDisplayed()
        // Keyboard focus has an explicit platform input-mode prerequisite after touch injection.
        compose.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        compose.runOnIdle { assertEquals(InputMode.Keyboard, inputModeManager.inputMode) }
        retry.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        retry.assertIsFocused().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(if (saveFailure) 1 else 0, saveRetries)
            assertEquals(if (saveFailure) 0 else 1, readRetries)
            assertEquals(0, saves)
            assertEquals(0, edits)
            assertEquals(draft, state.value.text)
            assertEquals(saveFailure, state.value.savePending)
            assertEquals(saveFailure, state.value.saving)
            assertEquals(!saveFailure, state.value.opening)
            assertNull(state.value.saveNotice)
        }
        compose.onAllNodesWithText("重试").assertCountEquals(0)
        list.performScrollToNode(hasContentDescription("此刻，想记下什么"))
        field.assertIsDisplayed().assertIsNotEnabled()
        compose.onAllNodes(focusedInput, useUnmergedTree = true).assertCountEquals(0)
        assertEquals(draft, field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onAllNodesWithText("已保存在本机").assertCountEquals(0)
    }
}
