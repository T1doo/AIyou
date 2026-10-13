package com.t1doo.aiyou

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.records.LocalRecordUiState
import com.t1doo.aiyou.ui.Destination
import com.t1doo.aiyou.ui.UiPreferences
import com.t1doo.aiyou.ui.UiPreferencesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual non-demo AIyouApp route. Task storage/creation/completion is not implemented. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class ProductionTasksPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val draft = "合成独立草稿：明天整理照片"
    private val local = LocalRecordUiState(ready = true, opening = false, text = draft,
        entries = listOf(StoredEntry("synthetic-task-boundary", 1, "合成原文：想做旅行册，不是正式任务",
            1_791_647_400_000, "Etc/UTC", ProcessingPolicy.LOCAL_ONLY)))
    private var saves = 0
    private var edits = 0

    private fun show(fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AIyouTheme { AIyouApp(previewDestination = Destination.TASKS, local = local,
                    display = UiPreferencesState(UiPreferences(reduceMotion = true), ready = true),
                    onSave = { saves++ }, onDraftChange = { edits++ }) }
            }
        }
    }

    private fun tab(destination: Destination) = compose.onNode(hasText(destination.label) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    private fun assertUnavailableWithoutFakeActions() {
        // Short viewports do not compose every LazyColumn item up front.
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("手工创建与完成待办尚未开放。"))
        compose.onNodeWithText("手工创建与完成待办尚未开放。").assertIsDisplayed().assertHasNoClickAction()
        list.performScrollToNode(hasText("提醒功能尚未开放"))
        compose.onNodeWithText("提醒功能尚未开放").assertIsDisplayed()
        val reminderDetails = "以后会分别显示任务确认、系统调度和通知权限状态。"
        list.performScrollToNode(hasText(reminderDetails))
        val detailsBounds = compose.onNodeWithText(reminderDetails).assertIsDisplayed().getUnclippedBoundsInRoot()
        val viewport = list.getUnclippedBoundsInRoot()
        assertTrue("Entire reminder explanation can be scrolled inside the viewport",
            detailsBounds.top >= viewport.top && detailsBounds.bottom <= viewport.bottom)
        for (text in listOf("把旅行照片整理成小册子", "确认创建", "创建待办", "标记完成", "完成")) {
            compose.onAllNodesWithText(text).assertCountEquals(0)
        }
        compose.onAllNodesWithText("虚构示例", substring = true).assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, saves); assertEquals(0, edits) }
    }

    @Test fun localRecordsNeverBecomeTasksAndUnopenedCompletionIsExplicit() {
        show()
        tab(Destination.TASKS).assertIsSelected()
        compose.onNodeWithContentDescription("打开陪伴伙伴").assertIsDisplayed()
        compose.onNodeWithText("没有待确认建议").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("今天 · 正式任务 0").performScrollTo().assertIsDisplayed()
        assertUnavailableWithoutFakeActions()
        tab(Destination.MEMORIES).performClick()
        compose.onNodeWithText("1 条本机记录").assertIsDisplayed()
        tab(Destination.NOW).performClick()
        compose.onNodeWithContentDescription("此刻，想记下什么").assertTextEquals(draft)
        compose.runOnIdle { assertEquals(0, saves); assertEquals(0, edits) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun shortNarrowDoubleFontTasksScrollAndSettingsReturnWithoutChangingDraft() {
        show(fontScale = 2f)
        assertUnavailableWithoutFakeActions()
        val settings = compose.onNodeWithText("设置").assertIsDisplayed().assertIsEnabled()
        val bounds = settings.getUnclippedBoundsInRoot()
        assertTrue(bounds.width >= 48.dp && bounds.height >= 48.dp)
        settings.performClick()
        compose.onNodeWithTag("motion-switch").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("返回").assertIsDisplayed().performClick()
        tab(Destination.TASKS).assertIsSelected().assertIsDisplayed()
        assertUnavailableWithoutFakeActions()
        tab(Destination.NOW).performClick()
        compose.onNodeWithContentDescription("此刻，想记下什么").assertTextEquals(draft)
        compose.runOnIdle { assertEquals(0, saves); assertEquals(0, edits) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h360dp")
    fun shortLandscapeKeepsTaskStatusNavigationAndSettingsReachable() {
        show()
        assertUnavailableWithoutFakeActions()
        compose.onNodeWithText("设置").assertIsDisplayed().performClick()
        compose.onNodeWithText("返回").assertIsDisplayed().performClick()
        tab(Destination.TASKS).assertIsSelected().assertIsDisplayed()
        assertUnavailableWithoutFakeActions()
    }
}
