package com.t1doo.aiyou

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.t1doo.aiyou.designsystem.*
import com.t1doo.aiyou.model.RecordDateBasis
import com.t1doo.aiyou.records.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual search section and staged dialog; controlled retry ports, not storage/device proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class OriginalFilterRecoveryPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun metadataRetriesRemainReachableWithoutApplyingDraftAndLoadingDisablesDuplicateFavoriteRetry() {
        var favoriteReads = 0; var peopleReads = 0; var tagReads = 0; var rebuilds = 0; var applications = 0
        val loading = mutableStateOf(false)
        val query = TextFieldState("合成未改查询")
        val activeFrom = TextFieldState()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                SkyglassTheme(SkyglassAppearance.NIGHT) {
                    LocalOriginalSearchSection(query, activeFrom, remember { TextFieldState() }, OriginalSearchMode.KEYWORDS,
                        true, {}, {}, indexSupported = true, indexError = "合成索引读取失败", onRebuild = { rebuilds++ },
                        favoritesSupported = true, favoritesAvailable = false, favoritesLoading = loading.value,
                        favoritesError = "合成收藏读取失败", onRefreshFavorites = { favoriteReads++ },
                        peopleSupported = true, peopleAvailable = false, peopleError = "合成人物读取失败",
                        tagsAvailable = false, tagsError = "合成标签读取失败", onRefreshTags = { tagReads++ },
                        onRefreshPeople = { peopleReads++ }, onApplyFilters = { applications++; true }, onCategory = {})
                }
            }
        }
        compose.onNodeWithTag("original-search-filters").performClick()
        compose.onNodeWithTag("original-search-from").performScrollTo().performTextReplacement("2026-11-05")
        compose.onNodeWithTag("original-search-favorites-retry").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { loading.value = true }
        compose.onNodeWithTag("original-search-favorites-retry").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("original-search-person-retry").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("original-search-tag-retry").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("更多查找设置").performScrollTo().performClick()
        compose.onNodeWithTag("original-search-rebuild").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("original-search-from").performScrollTo().assertTextContains("2026-11-05")
        compose.onNodeWithText("合成人物读取失败").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, favoriteReads); assertEquals(1, peopleReads); assertEquals(1, tagReads); assertEquals(1, rebuilds)
            assertEquals(0, applications); assertEquals("", activeFrom.text.toString()); assertEquals("合成未改查询", query.text.toString())
        }
    }

    @Test fun recoveryPreservesPrivatePersonAndDateDraftAndNeverAppliesUnavailableEventSource() {
        val available = mutableStateOf(true)
        var personReads = 0; var eventReads = 0
        var applied: OriginalFilterDraft? = null
        compose.setContent {
            SkyglassTheme(SkyglassAppearance.BLUE) {
                LocalOriginalSearchSection(remember { TextFieldState() }, remember { TextFieldState() }, remember { TextFieldState() },
                    OriginalSearchMode.KEYWORDS, true, {}, {}, peopleSupported = true, peopleAvailable = available.value,
                    peopleError = if (available.value) null else "合成人物数据不可读取", onRefreshPeople = { personReads++ },
                    eventDatesAvailable = available.value, onRefreshEventDates = { eventReads++ },
                    onApplyFilters = { applied = it; true }, onCategory = {})
            }
        }
        compose.onNodeWithTag("original-search-filters").performClick()
        compose.onNodeWithText("事件日期").performClick()
        compose.onNodeWithTag("original-search-person-input").performScrollTo().performTextReplacement("合成草稿人名")
        compose.onNodeWithTag("original-search-from").performScrollTo().performTextReplacement("2026-11-05")
        compose.runOnIdle { available.value = false }
        compose.onNodeWithTag("original-search-filter-done").performScrollTo().performClick()
        compose.runOnIdle { assertNull(applied) }
        compose.onNodeWithTag("original-filter-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("original-search-person-retry").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("original-search-event-date-retry").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, personReads); assertEquals(1, eventReads); assertNull(applied); available.value = true }
        compose.onNodeWithTag("original-search-person-input").performScrollTo().assertTextContains("合成草稿人名")
        compose.onNodeWithTag("original-search-from").performScrollTo().assertTextContains("2026-11-05")
        compose.onNodeWithTag("original-search-filter-done").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(OriginalFilterDraft(from = "2026-11-05", basis = RecordDateBasis.EVENT, person = "合成草稿人名"), applied) }
    }
}
