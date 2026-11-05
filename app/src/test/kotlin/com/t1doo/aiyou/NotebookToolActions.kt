package com.t1doo.aiyou

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Follow the same explicit disclosure as a user; never act on an uncomposed child. */
internal fun ComposeTestRule.showNotebookToolsFor(tag: String) {
    if(tag=="unified-open") {
        onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        if(onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
            onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("memory-tools-disclosure"))
            onNodeWithTag("memory-tools-disclosure").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        }
        onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
        return
    }
    val group = when (tag) {
        "image-texts-open", "transcripts-open", "preferences-open", "facts-open", "memory-inbox-open",
        "memory-accepted-open", "recap-open" -> "notebook-review-tools"
        "package-open", "export-open", "recycle-open" -> "notebook-manage-tools"
        else -> return
    }
    waitForIdle()
    if (onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
    if (onAllNodesWithTag("notebook-tools-close").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithTag("notebook-tools-close").performClick(); waitForIdle()
    }
    onNode(hasScrollToIndexAction()).performScrollToIndex(0)
    if (onAllNodesWithTag(group).fetchSemanticsNodes().isEmpty()) {
        onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("memory-tools-disclosure"))
        onNodeWithTag("memory-tools-disclosure").performScrollTo().assertIsEnabled().performClick()
        waitForIdle()
    }
    onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(group))
    val toggle = onNodeWithTag(group).performScrollTo().assertIsDisplayed().assertIsEnabled()
    if (toggle.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "已收起") toggle.performClick()
    onNodeWithTag(tag).assertExists()
}
