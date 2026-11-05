package com.t1doo.aiyou

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Open the actual row disclosure; its compact layout has no separate action label. */
internal fun ComposeTestRule.showManualTaskDetails(id: String) {
    onNodeWithTag("manual-task-list").performScrollToNode(hasTestTag("manual-task-row-" + id))
    val disclosure = onNodeWithTag("manual-task-disclosure-" + id).performScrollTo().assertIsDisplayed().assertIsEnabled()
    if (disclosure.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "已收起") disclosure.performClick()
    disclosure.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
}
