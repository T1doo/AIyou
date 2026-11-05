package com.t1doo.aiyou

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.t1doo.aiyou.designsystem.*
import com.t1doo.aiyou.records.OriginalFilterDraft
import com.t1doo.aiyou.records.OriginalFiltersDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w390dp-h840dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OriginalFilterDraftTest {
    @get:Rule val compose=createComposeRule()
    @Test fun cancelDoesNotApplyAndResetOnlyChangesPanelDraftUntilConfirmed() {
        val open=mutableStateOf(true)
        val applied=mutableStateOf(OriginalFilterDraft(from="2026-11-01",tag="生活"))
        var applications=0
        compose.setContent { SkyglassTheme(SkyglassAppearance.BLUE) {
            if(open.value) OriginalFiltersDialog(applied.value,true,listOf("生活"),true,true,true,true,
                {open.value=false},{applied.value=it;applications++;true},{},{},false,false,{}, {})
        } }
        compose.onNodeWithTag("original-search-from").performTextReplacement("2026-11-03")
        compose.runOnIdle { assertEquals("2026-11-01",applied.value.from);assertEquals(0,applications) }
        compose.onNodeWithTag("original-filter-cancel").performClick()
        compose.runOnIdle { open.value=true }
        compose.onNodeWithTag("original-search-from").assertTextContains("2026-11-01")
        compose.onNodeWithTag("original-filter-reset").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("生活",applied.value.tag);assertEquals(0,applications) }
        compose.onNodeWithTag("original-search-filter-done").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(OriginalFilterDraft(),applied.value);assertEquals(1,applications) }
    }
    @Test fun invalidDateAndRejectedOwnerKeepPanelAndDoNotClaimApply() {
        var submissions=0;var dismissed=false
        compose.setContent { SkyglassTheme(SkyglassAppearance.NIGHT) {
            OriginalFiltersDialog(OriginalFilterDraft(),true,emptyList(),true,false,true,true,
                {dismissed=true},{submissions++;false},{},{},false,false,{}, {})
        } }
        compose.onNodeWithTag("original-search-from").performTextReplacement("2026-02-30")
        compose.onNodeWithTag("original-search-filter-done").performScrollTo().performClick()
        compose.onNodeWithTag("original-filter-error").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0,submissions);assertFalse(dismissed) }
        compose.onNodeWithTag("original-search-from").performScrollTo().performTextReplacement("2026-11-01")
        compose.onNodeWithTag("original-search-filter-done").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,submissions);assertFalse(dismissed) }
        compose.onNodeWithTag("original-filter-error").assertTextContains("页面状态已变化",substring=true)
    }
    @Test fun rawFiltersStayOutOfDiagnosticTextAndDatesAreValidated() {
        assertEquals("OriginalFilterDraft(redacted)",OriginalFilterDraft(person="合成私密称呼").toString())
        assertThrows(IllegalArgumentException::class.java) {OriginalFilterDraft("2026-11-02","2026-11-01").validated()}
        assertThrows(java.time.DateTimeException::class.java) {OriginalFilterDraft("2026-02-30").validated()}
    }
}
