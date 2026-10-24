package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real production root/search/reader at 320dp and200% font; SQL/AEAD is verified separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class ManualAliasRecallPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val observed = AtomicReference<Observation>()
    private data class Observation(val state: OriginalSearchState, val open: (OriginalSearchHit) -> Unit)
    private val original = StoredEntry("tag", 1, "和朋友喝咖啡", 1, "UTC", ProcessingPolicy.LOCAL_ONLY)
    private fun people(version: Long, present: Boolean = true) = mapOf("tag" to ManualPeopleSnapshot("tag", version, listOf("小林"), 1, OriginalExports.hash(original.originalText), 0,
        if (present) listOf(ManualPersonAlias("阿林", "小林")) else emptyList()))
    private fun source() = mutableStateOf(LocalRecordUiState(ready = true, opening = false, entries = listOf(original),
        text = "未改动的合成草稿", manualPeopleAvailable = true, manualPeople = people(1)))
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

    @Test fun keywordAliasRecallShowsLabelWithoutBodyHighlightKeepsNavigationAndExactOriginalMode() {
        val source = source(); show(source); query("阿林"); val result = ready("阿林")
        assertFalse(result.state.hits.single().originalMatchesQuery)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("original-search-alias-match-tag"))
        compose.onNodeWithTag("original-search-alias-match-tag").assertIsDisplayed().assertTextEquals("别名命中：阿林 → 小林（你为本条记录确认的称呼关系）；不表示原文出现该称呼，不作为原文出处。")
        card()
        assertTrue(compose.onNodeWithText(original.originalText, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().spanStyles.isEmpty())
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-tag"))).performClick()
        compose.onNodeWithTag("original-text").assertTextEquals(original.originalText)
        assertTrue(compose.onNodeWithTag("original-text").fetchSemanticsNode().config[SemanticsProperties.Text].single().spanStyles.isEmpty())
        compose.onNodeWithText("返回列表").performClick(); ready("阿林")
        compose.onNodeWithTag("original-search-mode").performScrollTo().performClick(); ready("阿林", exact = true, count = 0)
        assertEquals("未改动的合成草稿", source.value.text); assertEquals(original, source.value.entries.single())
    }

    @Test fun changedOrRevokedAliasAndRevisionRetireRetainedMixedResultBeforeItCanOpenOrHighlight() {
        val source = source(); show(source); query("咖啡 阿林"); val old = ready("咖啡 阿林")
        assertFalse(old.state.hits.single().originalMatchesQuery); card()
        assertTrue(compose.onNodeWithText(original.originalText, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().spanStyles.isEmpty())
        compose.runOnIdle {
            source.value = source.value.copy(manualPeople = people(2, false))
            old.open(old.state.hits.single())
        }
        ready("咖啡 阿林", count = 0); compose.onAllNodesWithTag("original-text").assertCountEquals(0)
        compose.runOnIdle { source.value = source.value.copy(manualPeople = people(3)) }
        val current = ready("咖啡 阿林")
        compose.runOnIdle {
            source.value = source.value.copy(entries = listOf(original.copy(currentRevision = 2, originalText = "修改后的原文")))
            current.open(current.state.hits.single())
        }
        ready("咖啡 阿林", count = 0); compose.onAllNodesWithTag("original-text").assertCountEquals(0)
        assertEquals(2, source.value.entries.single().currentRevision)
    }

    @Test fun doubleFontExplicitAliasEditorCreatesChangesAndRevokesWithoutSavingPrivateInputOrChangingNames() {
        var saved = ManualPeopleSnapshot(original.id, 1, listOf("小林", "小王"), 1, OriginalExports.hash(original.originalText), 0)
        val people = object : ManualPeopleRepository {
            override fun read(entryId: String) = saved
            override fun snapshots() = mapOf(original.id to saved)
            override fun replace(mutation: ManualPeopleMutation): ManualPeopleSnapshot {
                check(mutation.before.version == saved.version && mutation.before.aliases == saved.aliases)
                saved = ManualPeopleSnapshot(original.id, saved.version + 1, mutation.names, 1, saved.sourceHash, 0, mutation.aliases)
                return saved
            }
        }
        val processing = object : LocalProcessingRepository {
            override fun read(source: SourceVersion) = LocalProcessingSnapshot(source, saved.sourceHash, 0, ProcessingPolicy.LOCAL_ONLY)
            override fun versions() = mapOf(original.id to LocalProcessingVersion(SourceVersion(original.id, 1), 0))
            override fun change(request: LocalProcessingChange) = error("No policy write")
        }
        val store = object : EntryRepository {
            override val manualPeople = people
            override val localProcessing = processing
            override suspend fun entries() = listOf(original)
            override suspend fun readDraft() = TextDraft("capture", 1, "合成独立草稿")
            override suspend fun updateDraft(text: String) = error("No draft write")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String) = error("No save")
            override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long) = error("No original write")
        }
        val source = mutableStateOf(LocalRecordUiState(ready = true, opening = false, entries = listOf(original), text = "合成独立草稿",
            supportsManualPeople = true, manualPeopleOwner = people, manualPeople = people.snapshots(), manualPeopleAvailable = true,
            localProcessingAvailable = true, localProcessingVersions = processing.versions()))
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        val controller = ManualPeopleController({ source.value }, { source.value = it(source.value) }, { queue.add(it); true })
        lateinit var navigation: OriginalReadingCallbacks
        var registry: SaveableStateRegistry? = null
        val reader = object : OriginalReadingObserver {
            override fun onNavigation(callbacks: OriginalReadingCallbacks) { navigation = callbacks }
            override fun onClipboard(clipboard: androidx.compose.ui.platform.Clipboard) = Unit
            override fun onSelection(selection: androidx.compose.foundation.text.selection.SelectionState) = Unit
        }
        compose.setContent {
            registry = LocalSaveableStateRegistry.current; val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalOriginalReadingObserver provides reader) {
                AIyouTheme { Box(Modifier.size(320.dp, 640.dp)) {
                    AIyouApp(previewDestination = Destination.MEMORIES, local = source.value, currentRecords = { source.value }, people = controller)
                } }
            }
        }
        fun field(tag: String, value: String) { compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value) }
        fun pump() { compose.runOnIdle { runBlocking { queue.removeFirst()(store) } }; compose.waitForIdle() }
        compose.runOnIdle { navigation.openEntry(original.id) }; compose.waitForIdle()
        compose.originalTool("original-manual-people").performScrollTo().performClick(); pump()
        compose.onNodeWithTag("manual-alias-toggle").performScrollTo().performClick()
        field("manual-alias-input", "未提交私密别名"); field("manual-alias-target", "小林")
        compose.runOnIdle { assertFalse(containsPrivateInput(registry!!.performSave(), "未提交私密别名")) }
        field("manual-alias-input", "阿林")
        compose.onNodeWithTag("manual-alias-save").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(saved.aliases.isEmpty()); pump(); assertEquals(listOf(ManualPersonAlias("阿林", "小林")), saved.aliases)
        field("manual-alias-input", "阿林"); field("manual-alias-target", "小王")
        compose.onNodeWithTag("manual-alias-save").performScrollTo().performClick(); pump()
        assertEquals(listOf(ManualPersonAlias("阿林", "小王")), saved.aliases)
        compose.onNodeWithTag("manual-alias-remove-0").performScrollTo().assertIsDisplayed().performClick(); pump()
        assertTrue(saved.aliases.isEmpty()); assertEquals(listOf("小林", "小王"), saved.names)
        assertEquals(listOf(original), source.value.entries); assertEquals("合成独立草稿", source.value.text)
        compose.onNodeWithTag("manual-people-close").performScrollTo().performClick(); assertNull(source.value.manualPeopleEditor)
        controller.dispose()
    }
    private fun containsPrivateInput(value: Any?, privateText: String): Boolean = when (value) {
        is CharSequence -> privateText in value
        is CharArray -> privateText in String(value)
        is ByteArray -> privateText in value.toString(Charsets.UTF_8)
        is android.os.Bundle -> value.keySet().any { containsPrivateInput(it, privateText) || containsPrivateInput(value.get(it), privateText) }
        is Map<*, *> -> value.any { containsPrivateInput(it.key, privateText) || containsPrivateInput(it.value, privateText) }
        is Iterable<*> -> value.any { containsPrivateInput(it, privateText) }
        is Array<*> -> value.any { containsPrivateInput(it, privateText) }
        else -> false
    }
}
