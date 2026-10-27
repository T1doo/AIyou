package com.t1doo.aiyou

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual AIyouApp, Dialog, matcher and tag controller. IO acknowledgements are controlled UI fixtures;
 * persistence is proved separately by product SQL/AEAD tests, not this in-memory fake port. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
class ManualTagPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(LocalRecordUiState(ready = true, opening = false, text = "合成草稿",
        entries = listOf(entry("one", "咖啡🙂完整原文\n保持正文空白", "Asia/Shanghai"), entry("two", "咖啡另一原文", "UTC"), entry("three", "茶原文", "UTC"))))
    private val queued = ArrayDeque<suspend (EntryRepository) -> Unit>()
    private val persisted = linkedMapOf("one" to ManualTagSnapshot("one", 1, listOf(TAG)), "two" to ManualTagSnapshot("two", 1, listOf(TAG)))
    private var failWrite = false
    private val tags = object : ManualTagRepository {
        override fun read(entryId: String) = persisted[entryId] ?: ManualTagSnapshot(entryId, 0, emptyList())
        override fun snapshots() = persisted.mapValues { (id, snapshot) -> ManualTagSnapshot(id, snapshot.version, snapshot.names) }
        override fun replace(mutation: ManualTagMutation): ManualTagSnapshot {
            if (failWrite) throw ManualTagStorageFailure()
            if (read(mutation.entryId).version != mutation.expectedVersion) throw ManualTagConflict()
            return ManualTagSnapshot(mutation.entryId, mutation.expectedVersion + 1, mutation.names).also { persisted[mutation.entryId] = it }
        }
    }
    private val store = object : EntryRepository {
        override val manualTags = tags
        override suspend fun readDraft() = TextDraft("capture", 1, "合成草稿")
        override suspend fun updateDraft(text: String) = error("Outside UI contract")
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String) = error("Outside UI contract")
        override suspend fun entries() = state.value.entries
        override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long) = error("Outside UI contract")
    }
    private val controller = ManualTagController({ state.value }, { state.value = it(state.value) }, { queued.add(it); true })
    private class Result(val state: OriginalSearchState, val open: (OriginalSearchHit) -> Unit)
    private val results = AtomicReference<Result>()
    private val navigation = AtomicReference<OriginalReadingCallbacks>()
    private val returnStage = AtomicReference<String>()
    private val searchDiagnostics = AtomicReference<String>()
    private val inputs = AtomicReference<Triple<TextFieldState, TextFieldState, TextFieldState>>()
    private lateinit var memories: LazyListState
    private var registry: SaveableStateRegistry? = null
    private val searchObserver = object : OriginalSearchObserver {
        override fun onResults(state: OriginalSearchState, open: (OriginalSearchHit) -> Unit) { results.set(Result(state, open)) }
        override fun onInputs(query: TextFieldState, from: TextFieldState, through: TextFieldState) { inputs.set(Triple(query, from, through)) }
        override fun onDiagnostics(value: String) { searchDiagnostics.set(value) }
    }
    private val readerObserver = object : OriginalReadingObserver {
        override fun onNavigation(callbacks: OriginalReadingCallbacks) { navigation.set(callbacks) }
        override fun onClipboard(clipboard: Clipboard) = Unit
        override fun onSelection(selection: SelectionState) = Unit
        override fun onRecordList(state: LazyListState, imeBottomPx: Int) { memories = state }
        override fun onListReturnStage(stage: String, generation: Long?, heightPx: Int, expectedHeightPx: Int?,
            viewportReady: Boolean, state: LazyListState) { returnStage.set("$stage/$generation/$heightPx/expected=$expectedHeightPx/ready=$viewportReady") }
    }

    @Test fun actualTagEditorWaitsForAcknowledgementKeepsFailedInputAndRemovesOnlyAfterConfirmation() {
        content(); openTags()
        field("manual-tag-input", "新标签🙂"); failWrite = true
        compose.onNodeWithTag("manual-tag-add").performScrollTo().performClick()
        assertTrue(state.value.manualTagEditor!!.busy); assertNull(state.value.manualTagEditor!!.notice)
        assertEquals(listOf(TAG), persisted.getValue("one").names)
        pump(); assertEquals("新标签🙂", editable("manual-tag-input")); assertNull(state.value.manualTagEditor!!.notice)
        failWrite = false; compose.onNodeWithTag("manual-tag-retry").performScrollTo().performClick(); pump()
        assertEquals("", editable("manual-tag-input")); assertEquals(listOf(TAG, "新标签🙂"), persisted.getValue("one").names)
        compose.onNodeWithTag("manual-tag-remove-1").performScrollTo().performClick()
        assertEquals(2, persisted.getValue("one").names.size); pump(); assertEquals(listOf(TAG), persisted.getValue("one").names)
        assertEquals("合成草稿", state.value.text); assertEquals(1, state.value.entries.first().currentRevision)
    }
    @Test fun tagOnlyAndKeywordSavedZoneDateCombinationOpenFullOriginalAndReturnActualListPosition() = assertTagSearchReturn(false)
    @Test fun tagSearchReturnKeepsExactPositionWithExportControlVisible() = assertTagSearchReturn(true)
    private fun assertTagSearchReturn(exportVisible: Boolean) {
        content(exportVisible = exportVisible)
        if (exportVisible) { compose.showNotebookToolsFor("export-open");compose.onNodeWithTag("export-open").assertIsEnabled();compose.onNodeWithTag("notebook-tools-close").performClick() }
        chooseTag(); val only = result { it.stage == OriginalSearchStage.READY && it.input.tag == TAG }
        assertEquals(listOf("one", "two"), only.state.hits.map { it.entry.id })
        field("original-search-query", "咖啡"); field("original-search-from", "2026-10-14"); field("original-search-through", "2026-10-14")
        val found = result { it.stage == OriginalSearchStage.READY && it.input.from == "2026-10-14" && it.input.through == "2026-10-14" }
        assertEquals(listOf("one"), found.state.hits.map { it.entry.id })
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-one"))
        val position = compose.runOnIdle { memories.firstVisibleItemIndex to memories.firstVisibleItemScrollOffset }
        compose.runOnIdle { found.open(found.state.hits.single()) }; compose.waitForIdle()
        assertEquals(state.value.entries.first().originalText, compose.onNodeWithTag("original-text").fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
        // A genuine repository read creates fresh snapshot identities even without a mutation.
        compose.originalTool("original-manual-tags").performClick(); pump()
        compose.onNodeWithTag("manual-tag-close").performScrollTo().performClick()
        compose.runOnIdle { navigation.get().returnToList() }
        result { it.stage == OriginalSearchStage.READY && it.input.tag == TAG }
        // READY publishes matches; the following layout/scroll effect completes independently.
        // Use the same 2s positive position barrier as the existing actual reader-return regression.
        try {
            compose.waitUntil(2_000) { memories.firstVisibleItemIndex == position.first && memories.firstVisibleItemScrollOffset == position.second }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Actual tag reader return did not restore position: expected=$position " +
                "actual=${memories.firstVisibleItemIndex to memories.firstVisibleItemScrollOffset}, ${returnStage.get()}, " +
                "${searchDiagnostics.get()}, items=${memories.layoutInfo.totalItemsCount}", failure)
        }
        assertEquals(position, compose.runOnIdle { memories.firstVisibleItemIndex to memories.firstVisibleItemScrollOffset })
    }
    @Test fun changedTagVersionRevokesOldResultBeforeCompositionAndReadFailureIsDistinctFromEmpty() {
        content(); chooseTag(); val old = result { it.stage == OriginalSearchStage.READY && it.input.tag == TAG }
        compose.runOnIdle {
            state.value = state.value.copy(manualTags = mapOf("one" to ManualTagSnapshot("one", 2, emptyList())))
            old.open(old.state.hits.first())
        }
        compose.onAllNodes(hasTestTag("original-text")).assertCountEquals(0)
        assertTrue(result { it.stage == OriginalSearchStage.READY && it !== old.state }.state.hits.isEmpty())
        compose.runOnIdle { state.value = state.value.copy(manualTagsAvailable = false, manualTags = emptyMap(), manualTagsError = "手工标签暂不可读取，请重试。") }
        assertTrue(result { it.stage == OriginalSearchStage.FAILED }.state.hits.isEmpty())
        compose.onNodeWithTag("original-search-clear").performScrollTo().performClick()
        result { it.stage == OriginalSearchStage.BROWSE && it.input.tag == null }
    }
    @Test fun narrowLargeFontEditorKeepsActionsReachableAndHidesPersistedLabelsWhenSourceFails() {
        persisted["one"] = ManualTagSnapshot("one", 1, listOf("🙂".repeat(40), TAG))
        content(large = true); openTags()
        field("manual-tag-input", "未提交私密输入")
        compose.waitForIdle()
        val retained = navigation.get(); val session = state.value.manualTagEditor!!.session
        compose.runOnIdle { retained.beginHistory("one", 1) }; compose.waitForIdle()
        assertNotSame(retained, navigation.get())
        compose.runOnIdle { retained.returnToList() }; assertEquals(session, state.value.manualTagEditor!!.session); compose.waitForIdle(); assertEquals("未提交私密输入", editable("manual-tag-input"))
        compose.runOnIdle { state.value = state.value.copy(error = "合成读取失败") }; compose.waitForIdle()
        compose.onAllNodes(hasText(TAG)).assertCountEquals(0); assertEquals("未提交私密输入", editable("manual-tag-input"))
        compose.onNodeWithTag("manual-tag-add").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("manual-tag-close").performScrollTo().assertIsDisplayed().performClick()
        assertNull(state.value.manualTagEditor)
    }
    @OptIn(ExperimentalFoundationApi::class)
    @Test fun rawSavedStateAndRestorationExcludeTagConditionsInputsAndClearedFieldUndoHistory() {
        val restoration = StateRestorationTester(compose)
        state.value = state.value.copy(manualTags = persisted.toMap(), manualTagsAvailable = true)
        restoration.setContent { TestScreen(false) }
        chooseTag(); val old = result { it.stage == OriginalSearchStage.READY && it.input.tag == TAG }
        assertEquals(2, old.state.hits.size)
        field("original-search-query", "合成敏感查询"); field("original-search-from", "2026-10-14")
        result { it.stage == OriginalSearchStage.READY && it.input.query == "合成敏感查询" }
        compose.runOnIdle { assertPrivate(registry!!.performSave(), listOf(TAG, "合成敏感查询", "2026-10-14")) }
        val oldField = inputs.get().first
        compose.onNodeWithTag("original-search-clear").performScrollTo().performClick(); result { it.stage == OriginalSearchStage.BROWSE }
        compose.runOnIdle { assertNotSame(oldField, inputs.get().first); assertFalse(inputs.get().first.undoState.canUndo); assertFalse(inputs.get().first.undoState.canRedo) }
        chooseTag(); restoration.emulateSavedInstanceStateRestore()
        result { it.stage == OriginalSearchStage.BROWSE && it.input.tag == null && it.input.query.isEmpty() }
        compose.runOnIdle { old.open(old.state.hits.first()) }
        compose.onAllNodes(hasTestTag("original-text")).assertCountEquals(0)
        openTags(); field("manual-tag-input", "合成私密标签输入")
        compose.runOnIdle { assertPrivate(registry!!.performSave(), listOf(TAG, "合成私密标签输入")) }
        assertEquals("合成私密标签输入", editable("manual-tag-input"))
    }
    private fun content(large: Boolean = false, exportVisible: Boolean = false) {
        state.value = state.value.copy(manualTags = persisted.toMap(), manualTagsAvailable = true, supportsOriginalExport = exportVisible)
        compose.setContent { TestScreen(large, exportVisible) }; compose.waitForIdle()
    }
    private val exports = OriginalExportController({ state.value }, { state.value = it(state.value) }, { false }, java.time.Clock.systemUTC())
    @Composable private fun TestScreen(large: Boolean, exportVisible: Boolean = false) {
        val saved = LocalSaveableStateRegistry.current; SideEffect { registry = saved }
        val density = LocalDensity.current
        CompositionLocalProvider(LocalOriginalSearchObserver provides searchObserver, LocalOriginalReadingObserver provides readerObserver,
            LocalDensity provides if (large) Density(density.density, 2f) else density) {
            Box(if (large) Modifier.size(320.dp, 640.dp) else Modifier) {
                AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES, local = state.value, currentRecords = { state.value },
                    exports = exports.takeIf { exportVisible },
                    onOpenTags = controller::open, onTagEdit = controller::edit, onTagAdd = controller::add,
                    onTagRemove = controller::remove, onTagRetry = controller::retry, onTagClose = controller::close) }
            }
        }
    }
    private fun pump() { compose.runOnIdle { runBlocking { queued.removeFirst()(store) } }; compose.waitForIdle() }
    private fun openTags() { compose.runOnIdle { navigation.get().openEntry("one") }; compose.waitForIdle(); compose.originalTool("original-manual-tags").performClick(); pump() }
    private fun chooseTag() {
        compose.showOriginalSearchFilters()
        compose.onNodeWithTag("original-search-tag").performScrollTo().performClick(); compose.waitForIdle()
        val index = persisted.values.flatMap { it.names }.distinct().sorted().indexOf(TAG)
        compose.onNodeWithTag("original-search-tag-option-$index").performClick()
    }
    private fun field(tag: String, text: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performTextReplacement(text)
    private fun editable(tag: String) = compose.onNodeWithTag(tag).performScrollTo().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun result(predicate: (OriginalSearchState) -> Boolean): Result {
        compose.waitForIdle(); compose.waitUntil(2_000) { results.get()?.state?.let(predicate) == true }; compose.waitForIdle()
        return compose.runOnIdle { results.get().also { check(predicate(it.state)) } }
    }
    private fun assertPrivate(value: Any?, markers: List<String>) {
        when (value) {
            is CharSequence -> markers.forEach { assertFalse(value.contains(it)) }
            is Map<*, *> -> value.forEach { (key, item) -> assertPrivate(key, markers); assertPrivate(item, markers) }
            is Iterable<*> -> value.forEach { assertPrivate(it, markers) }
            is Array<*> -> value.forEach { assertPrivate(it, markers) }
        }
    }
    companion object {
        const val TAG = "合成标签🙂"
        private fun entry(id: String, text: String, zone: String) = StoredEntry(id, 1, text, Instant.parse("2026-10-13T23:30:00Z").toEpochMilli(), zone, ProcessingPolicy.LOCAL_ONLY)
    }
}
