package com.t1doo.aiyou

import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateOf
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

    @Test fun realNotebookUsesBundledLuluOnlyWhenPreferenceIsReadyAndVisibleWithoutWritingRecords() {
        val draft=CaptureDraft("f71b097d-6b88-4bde-bae4-637230066ea2",1,"",emptyList())
        val local=LocalRecordUiState(ready=true,opening=false,supportsMixedCapture=true,
            mixedCapture=MixedCaptureUiState(draft=draft,supportsProcessingChoice=true))
        val display=mutableStateOf(UiPreferencesState(UiPreferences(reduceMotion=true),ready=true))
        var writes=0
        val controller=MixedCaptureController({local},{},{writes++;false},{error("No media input")},Clock.systemUTC())
        val image=BitmapFactory.decodeResource(RuntimeEnvironment.getApplication().resources,
            com.t1doo.aiyou.designsystem.R.drawable.lulu_hello,
            BitmapFactory.Options().apply { inSampleSize=2;inScaled=false })
        assertNotNull(image);assertTrue(image.hasAlpha());assertTrue(image.width in 620..630)
        image.recycle()
        compose.setContent { AIyouTheme { AIyouApp(local=local,mixedCapture=controller,display=display.value) } }
        compose.onNodeWithTag("mixed-list").assertExists()
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
    }
}
