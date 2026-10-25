package com.t1doo.aiyou

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.assertEquals

/** Follow the real disclosure before an existing advanced-filter interaction. */
internal fun ComposeContentTestRule.showOriginalSearchFilters() {
    onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("original-search-filters"))
    val control = onNodeWithTag("original-search-filters").performScrollTo().assertIsDisplayed()
    when (control.fetchSemanticsNode().config[SemanticsProperties.StateDescription]) {
        "已收起" -> control.assertIsEnabled().performClick()
        "已展开" -> Unit
        else -> error("Unknown disclosure state")
    }
    assertEquals("已展开", control.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
}
