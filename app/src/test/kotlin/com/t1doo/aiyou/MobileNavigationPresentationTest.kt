package com.t1doo.aiyou

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.records.LocalRecordUiState
import com.t1doo.aiyou.ui.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileNavigationPresentationTest {
    @get:Rule val compose=createComposeRule()
    private fun tab(destination: Destination)=compose.onNodeWithTag("notebook-tab-${destination.name}")
    private fun frame(name: String) {
        val directory=File("build/reports/tests/testDebugUnitTest/mobile-navigation").apply { mkdirs() }
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory,"$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        File(directory,"$name.json").writeText(JSONObject()
            .put("source_sha",System.getenv("GITHUB_SHA")?:"local-not-CI")
            .put("kind","Synthetic production Compose route; not physical-device evidence")
            .put("viewportPixels","${bitmap.width}x${bitmap.height}")
            .put("navigationBoundsDp",compose.onNodeWithTag("notebook-navigation").getUnclippedBoundsInRoot().toString())
            .toString(2))
    }

    @Test fun productionTabsSwipeOnceAtEdgesAndReturnToUnchangedUnsavedDraftWithoutWrites() {
        val draft="合成未保存草稿🙂，切换页面后仍在。"
        var writes=0
        compose.setContent { AIyouTheme { AIyouApp(
            local=LocalRecordUiState(ready=true,opening=false,text=draft),
            onSave={writes++},onDraftChange={writes++},
            display=UiPreferencesState(UiPreferences(showCompanion=false,reduceMotion=false),ready=true)) } }
        for (destination in Destination.entries) tab(destination).assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        tab(Destination.NOW).assertIsSelected()
        assertTrue(tab(Destination.NOW).getUnclippedBoundsInRoot().height <= 64.dp)
        frame("01-normal-now")
        compose.onNodeWithTag("notebook-navigation").performTouchInput { swipeLeft() }
        tab(Destination.MEMORIES).assertIsSelected()
        compose.onNodeWithTag("notebook-navigation").performTouchInput { swipeLeft() }
        tab(Destination.TASKS).assertIsSelected()
        compose.onNodeWithTag("notebook-navigation").performTouchInput { swipeLeft() }
        tab(Destination.TASKS).assertIsSelected()
        compose.onNodeWithTag("notebook-navigation").performTouchInput { swipeRight() }
        tab(Destination.MEMORIES).assertIsSelected()
        val now=tab(Destination.NOW)
        val view=(now.fetchSemanticsNode().root as ViewRootForTest).view
        compose.runOnIdle { assertTrue(view.requestFocusFromTouch()) }
        now.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        now.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        now.assertIsSelected()
        compose.onNodeWithText(draft).assertExists()
        compose.runOnIdle { assertEquals(0,writes) }
    }

    @Test @Config(qualifiers="w320dp-h640dp")
    fun doubleFontTabsKeepRealLabelsTouchTargetsAndCancelRetiredGesture() {
        val selected=mutableStateOf<Destination?>(Destination.NOW)
        var selections=0
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                AIyouTheme { Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        NotebookNavigation(selected.value,{selected.value=it;selections++},motionAllowed=false)
                    }
                } }
            }
        }
        for (destination in Destination.entries) {
            tab(destination).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            val layouts=mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(destination.label,useUnmergedTree=true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(2f,layouts.single().layoutInput.density.fontScale,0f)
            assertFalse(layouts.single().hasVisualOverflow)
        }
        tab(Destination.NOW).performClick()
        compose.runOnIdle { assertEquals(0,selections) }
        frame("02-double-font-navigation")
        val nav=compose.onNodeWithTag("notebook-navigation")
        nav.performTouchInput { down(center); moveTo(center.copy(x=center.x/2)) }
        compose.runOnIdle { selected.value=null }
        compose.waitForIdle()
        nav.performTouchInput { up() }
        compose.runOnIdle { assertEquals(0,selections) }
        for (destination in Destination.entries) tab(destination).assertIsNotSelected()
    }
}
