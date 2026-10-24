package com.t1doo.aiyou

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Open the production disclosure before interacting with its actual, scrollable controls. */
internal fun ComposeTestRule.showOriginalToolsFor(tag: String) {
    if (tag !in setOf("original-reading-edit", "original-event-date-open", "original-event-date",
            "original-manual-people", "original-manual-tags", "original-recycle-move", "original-source-task",
            "original-local-processing", "original-reading-ai", "original-local-candidates", "original-source-tags",
            "original-source-preferences", "original-source-memories")) return
    if (onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) openOriginalTools()
}

internal fun ComposeTestRule.openOriginalTools() {
    onNodeWithTag("original-tools-open").assertIsDisplayed().assertIsEnabled().performClick()
    onNodeWithTag("original-tools").assertExists()
}

internal fun ComposeTestRule.originalTool(tag: String): SemanticsNodeInteraction {
    showOriginalToolsFor(tag)
    return onNodeWithTag(tag).performScrollTo()
}

internal fun ComposeTestRule.originalToolText(label: String): SemanticsNodeInteraction {
    if (onAllNodesWithText(label).fetchSemanticsNodes().isEmpty()) openOriginalTools()
    return onNodeWithText(label).performScrollTo()
}
