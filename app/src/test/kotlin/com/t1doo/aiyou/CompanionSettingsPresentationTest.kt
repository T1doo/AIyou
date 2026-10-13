package com.t1doo.aiyou

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.designsystem.MascotState
import com.t1doo.aiyou.ui.*
import com.t1doo.aiyou.records.LocalRecordUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production routes with synthetic record state, actual Compose semantics; not TalkBack/device proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class CompanionSettingsPresentationTest {
    @get:Rule val compose = createComposeRule(effectContext = object : androidx.compose.ui.MotionDurationScale {
        override val scaleFactor = 1f
    })

    @Test fun companionIsLocalOptionalAndSettingsReturnPreservesCapture() {
        val display = mutableStateOf(UiPreferencesState(UiPreferences(), ready = true))
        val local = mutableStateOf(LocalRecordUiState(ready = true, opening = false, text = "合成保留草稿"))
        var changes = 0
        var saves = 0
        compose.setContent { AIyouTheme { AIyouApp(local = local.value, display = display.value,
            onDisplayChange = { changes++; display.value = display.value.copy(preferences = it) }, onSave = { saves++ }) } }
        compose.onNodeWithContentDescription("打开陪伴伙伴").assertIsDisplayed().performClick()
        compose.onNodeWithText("打个招呼").performClick()
        compose.onNodeWithText("你好，慢慢记录就好。").assertIsDisplayed()
        compose.onNodeWithText("关闭陪伴面板").performClick()
        compose.runOnIdle { assertEquals(0, saves); assertEquals("合成保留草稿", local.value.text) }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("companion-switch").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, changes); assertFalse(display.value.preferences.showCompanion) }
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithContentDescription("打开陪伴伙伴").assertDoesNotExist()
        compose.onNodeWithContentDescription("此刻，想记下什么").assertTextEquals("合成保留草稿")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun narrowDoubleFontSettingsHaveOneSwitchTargetAndReachableLicenseAndRetry() {
        var changes = 0
        var retries = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AIyouTheme { AIyouApp(previewSettings = true,
                    display = UiPreferencesState(UiPreferences(), ready = true, error = "合成设置失败"),
                    onDisplayChange = { changes++ }, onRetryDisplay = { retries++ }) }
            }
        }
        val toggle = compose.onNodeWithTag("motion-switch").performScrollTo().assertIsDisplayed()
        val bounds = toggle.getUnclippedBoundsInRoot()
        assertTrue(bounds.height >= 48.dp && bounds.width >= 48.dp)
        assertEquals(ToggleableState.Off, toggle.fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
        // Child Switch has no independent click action; the entire labeled row owns the toggle.
        compose.onAllNodes(hasAnyAncestor(hasTestTag("motion-switch")) and hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
        toggle.performClick()
        compose.runOnIdle { assertEquals(1, changes) }
        compose.onNodeWithText("重试设置").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("开源许可与声明").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("开源许可与声明 · 离线文本").assertExists()
        compose.onNodeWithText("返回设置").performClick()
        compose.onNodeWithTag("settings-scroll").assertExists()
    }

    @Test fun eachProductionDestinationReturnsFromSettingsWithoutChangingSelection() {
        compose.setContent { AIyouTheme { AIyouApp(local = LocalRecordUiState(ready = true, opening = false),
            display = UiPreferencesState(UiPreferences(), ready = true)) } }
        for (destination in Destination.entries) {
            compose.onNode(hasText(destination.label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("返回").performClick()
            compose.onNode(hasText(destination.label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).assertIsSelected()
            compose.onNodeWithContentDescription("打开陪伴伙伴").assertIsDisplayed()
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun shortDoubleFontViewportKeepsDialogCloseAndProductionSaveReachable() {
        var saves = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AIyouTheme { AIyouApp(local = LocalRecordUiState(ready = true, opening = false, text = "合成短屏草稿"),
                    display = UiPreferencesState(UiPreferences(reduceMotion = true), ready = true), onSave = { saves++ }) }
            }
        }
        compose.onNodeWithContentDescription("打开陪伴伙伴").performClick()
        compose.onNodeWithText("打个招呼").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("关闭陪伴面板").assertIsDisplayed().performClick()
        val save = compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed().assertIsEnabled()
        val bounds = save.getUnclippedBoundsInRoot()
        assertTrue(bounds.width >= 48.dp && bounds.height >= 48.dp)
        save.performClick()
        compose.runOnIdle { assertEquals(1, saves) }
        compose.onNodeWithContentDescription("此刻，想记下什么").assertTextEquals("合成短屏草稿")
    }

    @Test fun loadingPreferencesAndFailedSaveDoNotPublishCompanionSuccess() {
        val display = mutableStateOf(UiPreferencesState())
        val local = mutableStateOf(LocalRecordUiState(ready = true, opening = false, error = "合成保存失败"))
        compose.setContent { AIyouTheme { AIyouApp(local = local.value, display = display.value) } }
        compose.onNodeWithContentDescription("打开陪伴伙伴").assertDoesNotExist()
        compose.runOnIdle { display.value = UiPreferencesState(UiPreferences(reduceMotion = true), ready = true) }
        compose.onNodeWithContentDescription("打开陪伴伙伴").assertIsDisplayed()
        compose.onAllNodesWithText("已保存在本机").assertCountEquals(0)
        compose.onNodeWithText("合成保存失败").assertExists()
    }

    @Test fun oneNodEndsAndReducedMotionOrRemountCannotReplayIt() {
        val pulse = mutableStateOf(0L)
        val allowed = mutableStateOf(true)
        val visible = mutableStateOf(true)
        var observed = 0f
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalCompanionObserver provides { _, value -> observed = value }) {
                AIyouTheme { if (visible.value) CompanionEntry(MascotState.Saved, pulse.value, allowed.value, {}) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { pulse.value++ }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertTrue("One finite nod begins", observed > 0f) }
        repeat(30) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { assertEquals(0f, observed, .001f); allowed.value = false; pulse.value++ }
        repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { assertEquals(0f, observed, .001f); allowed.value = true }
        repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { assertEquals(0f, observed, .001f); visible.value = false }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { visible.value = true }
        repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { assertEquals(0f, observed, .001f) }
    }

    @Test fun successfulReadbackAcknowledgementDoesNotReplayOnSettingsReturn() {
        val local = mutableStateOf(LocalRecordUiState(ready = true, opening = false, text = "合成草稿"))
        var observed = MascotState.Idle
        var acknowledgements = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalCompanionObserver provides { state, _ ->
                if (state == MascotState.Saved && observed != state) acknowledgements++
                observed = state
            }) {
                AIyouTheme { AIyouApp(local = local.value,
                    display = UiPreferencesState(UiPreferences(reduceMotion = true), ready = true)) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(MascotState.Idle, observed)
            local.value = local.value.copy(text = "", saveNotice = "已保存在本机", saveFeedbackId = 1)
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertEquals(MascotState.Saved, observed) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("返回").performClick()
        compose.runOnIdle { assertEquals(MascotState.Idle, observed); assertEquals(1, acknowledgements) }
    }

    @Test fun actualMotionGateTracksLifecycleSystemScalePowerSaverAndUserPreference() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        val reduced = mutableStateOf(false)
        var allowed = false
        fun scale(value: Float) {
            org.robolectric.util.ReflectionHelpers.callStaticMethod<Any>(android.animation.ValueAnimator::class.java,
                "setDurationScale", org.robolectric.util.ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, value))
            android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, value)
        }
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED; scale(1f) }
        compose.setContent {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                allowed = companionMotionAllowed(reduced.value)
            }
        }
        compose.runOnIdle { assertTrue(allowed); owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.runOnIdle { assertFalse(allowed); owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.runOnIdle { assertTrue(allowed); scale(0f) }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(allowed); scale(1f) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(allowed)
            org.robolectric.Shadows.shadowOf(power).setIsPowerSaveMode(true)
            context.sendBroadcast(android.content.Intent(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(allowed)
            org.robolectric.Shadows.shadowOf(power).setIsPowerSaveMode(false)
            context.sendBroadcast(android.content.Intent(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(allowed); reduced.value = true }
        compose.runOnIdle { assertFalse(allowed) }
    }
}
