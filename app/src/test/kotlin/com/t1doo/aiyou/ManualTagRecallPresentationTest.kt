package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real production root/search/reader at 320dp and200% font; SQL/AEAD is verified separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class ManualTagRecallPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val observed = AtomicReference<Observation>()
    private data class Observation(val state: OriginalSearchState, val open: (OriginalSearchHit) -> Unit)
    private val original = StoredEntry("tag", 1, "和小林喝咖啡", 1, "UTC", ProcessingPolicy.LOCAL_ONLY)
    private fun tags(version: Long, present: Boolean = true) = mapOf("tag" to ManualTagSnapshot("tag", version,
        if (present) listOf("旅行") else emptyList()))
    private fun source() = mutableStateOf(LocalRecordUiState(ready = true, opening = false, entries = listOf(original),
        text = "未改动的合成草稿", manualTagsAvailable = true, manualTags = tags(1)))
    private fun show(source: State<LocalRecordUiState>) { compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f),
            LocalOriginalSearchObserver provides object : OriginalSearchObserver {
                override fun onResults(state: OriginalSearchState, open: (OriginalSearchHit) -> Unit) { observed.set(Observation(state, open)) }
            }) { AIyouTheme { Box(Modifier.size(320.dp, 640.dp)) {
                AIyouApp(previewDestination = Destination.MEMORIES, local = source.value, currentRecords = { source.value })
            } } }
    } }
    private fun query(text: String) { compose.onNodeWithTag("original-search-query").performScrollTo().performTextReplacement(text) }
    private fun ready(query: String, exact: Boolean = false, count: Int = 1): Observation {
        compose.waitUntil(10_000) { observed.get()?.state?.let { it.stage == OriginalSearchStage.READY && it.input.query == query &&
            (it.input.mode == OriginalSearchMode.EXACT) == exact && it.hits.size == count } == true }
        compose.waitForIdle(); return observed.get()
    }
    private fun card() { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-tag")) }

    @Test fun keywordTagRecallShowsLabelWithoutBodyHighlightKeepsNavigationAndExactOriginalMode() {
        val source = source(); show(source); query("旅行"); val result = ready("旅行")
        assertFalse(result.state.hits.single().originalMatchesQuery)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("original-search-tag-match-tag"))
        compose.onNodeWithTag("original-search-tag-match-tag").assertIsDisplayed().assertTextEquals("手工标签命中：旅行；不作为原文出处。")
        card()
        assertTrue(compose.onNodeWithText(original.originalText, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().spanStyles.isEmpty())
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-tag"))).performClick()
        compose.onNodeWithTag("original-text").assertTextEquals(original.originalText)
        assertTrue(compose.onNodeWithTag("original-text").fetchSemanticsNode().config[SemanticsProperties.Text].single().spanStyles.isEmpty())
        compose.onNodeWithText("返回列表").performClick(); ready("旅行")
        compose.onNodeWithTag("original-search-mode").performScrollTo().performClick(); ready("旅行", exact = true, count = 0)
        assertEquals("未改动的合成草稿", source.value.text); assertEquals(original, source.value.entries.single())
    }

    @Test fun changedOrRemovedTagAndRevisionRetireRetainedMixedResultBeforeItCanOpenOrHighlight() {
        val source = source(); show(source); query("咖啡 旅行"); val old = ready("咖啡 旅行")
        assertFalse(old.state.hits.single().originalMatchesQuery); card()
        assertTrue(compose.onNodeWithText(original.originalText, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().spanStyles.isEmpty())
        compose.runOnIdle {
            source.value = source.value.copy(manualTags = tags(2, false))
            old.open(old.state.hits.single())
        }
        ready("咖啡 旅行", count = 0); compose.onAllNodesWithTag("original-text").assertCountEquals(0)
        compose.runOnIdle { source.value = source.value.copy(manualTags = tags(3)) }
        val current = ready("咖啡 旅行")
        compose.runOnIdle {
            source.value = source.value.copy(entries = listOf(original.copy(currentRevision = 2, originalText = "修改后的原文")))
            current.open(current.state.hits.single())
        }
        ready("咖啡 旅行", count = 0); compose.onAllNodesWithTag("original-text").assertCountEquals(0)
        assertEquals(2, source.value.entries.single().currentRevision)
    }
}
