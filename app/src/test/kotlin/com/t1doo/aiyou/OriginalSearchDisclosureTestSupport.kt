package com.t1doo.aiyou

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.assertEquals

/** Follow the real disclosure before an existing advanced-filter interaction. */
internal fun ComposeContentTestRule.showOriginalSearchFilters() {
    if (onAllNodesWithTag("original-search-filter-page").fetchSemanticsNodes().isNotEmpty()) return
    onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("original-search-filters"))
    onNodeWithTag("original-search-filters").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
    onNodeWithTag("original-search-filter-page").assertIsDisplayed()
}

/** Full clear is deliberately separate from resetting staged filters. */
internal fun ComposeContentTestRule.clearOriginalSearchIncludingQuery() {
    showOriginalSearchFilters()
    if (onAllNodesWithTag("original-search-clear").fetchSemanticsNodes().isEmpty()) {
        onNodeWithText("更多查找设置").performScrollTo().assertIsEnabled().performClick()
    }
    onNodeWithTag("original-search-clear").performScrollTo().assertIsEnabled().performClick()
}
