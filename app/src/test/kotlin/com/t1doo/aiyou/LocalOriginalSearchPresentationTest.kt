package com.t1doo.aiyou

import android.widget.Magnifier
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.ui.Destination
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Actual AIyouApp/Compose and product lookup, with synthetic current originals.
 * Robolectric is not Android device, Room or SQLCipher execution evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
class LocalOriginalSearchPresentationTest {
    @get:Rule val compose = createComposeRule()
    private data class Observation(val state: OriginalSearchState, val open: (OriginalSearchHit) -> Unit, val serial: Long)
    private val observations = AtomicReference<Observation>()
    private val serials = AtomicLong()
    private val diagnostics = AtomicReference<String>()
    private var context = "initial"
    private lateinit var memories: LazyListState
    private lateinit var back: OnBackPressedDispatcher
    private class ActualInputs(val query: TextFieldState, val from: TextFieldState, val through: TextFieldState) {
        fun field(tag: String) = when (tag) {
            "original-search-query" -> query
            "original-search-from" -> from
            "original-search-through" -> through
            else -> error("Unknown owned lookup field")
        }
        override fun toString() = "ActualInputs(redacted)"
    }
    private val actualInputs = AtomicReference<ActualInputs>()
    private val actualView = AtomicReference<android.view.View>()
    private var draftChanges = 0
    private var saves = 0
    private var rebuilds = 0
    private val refreshCallbacks = AtomicInteger()
    private var saveableRegistry: SaveableStateRegistry? = null
    private val searchObserver = object : OriginalSearchObserver {
        override fun onResults(state: OriginalSearchState, open: (OriginalSearchHit) -> Unit) {
            observations.set(Observation(state, open, serials.incrementAndGet()))
        }
        override fun onDiagnostics(value: String) { diagnostics.set(value) }
        override fun onInputs(query: TextFieldState, from: TextFieldState, through: TextFieldState) {
            actualInputs.set(ActualInputs(query, from, through))
        }
    }
    private val readingObserver = object : OriginalReadingObserver {
        override fun onNavigation(callbacks: OriginalReadingCallbacks) = Unit
        override fun onClipboard(clipboard: Clipboard) = Unit
        override fun onSelection(selection: SelectionState) = Unit
        override fun onRecordList(state: LazyListState, imeBottomPx: Int) { memories = state }
    }

    @Test fun editableKeywordExactAndSavedZoneDateFieldsOpenEveryOriginalCharacterWithoutTouchingDraft() {
        val exact = "MiXeD \t咖啡🙂"
        val full = "合成前文\n" + exact + "\n合成后文，保留空白。"
        val state = local(entry("one", full, captured = "2026-10-13T23:30:00Z", zone = "Asia/Shanghai"),
            entry("utc", "MiXeD 咖啡 原文", captured = "2026-10-13T23:30:00Z", zone = "UTC"), entry("missing", "MiXeD 茶 原文"))
        content(state)
        field("original-search-query", "mixed 咖啡")
        assertEquals(listOf("one", "utc"), ready("mixed 咖啡").state.hits.map { it.entry.id })
        field("original-search-from", "2026-10-14"); field("original-search-through", "2026-10-14")
        val dates = observation { it.state.stage == OriginalSearchStage.READY && it.state.input.query == "mixed 咖啡" &&
            it.state.input.from == "2026-10-14" && it.state.input.through == "2026-10-14" }
        assertEquals(listOf("one"), dates.state.hits.map { it.entry.id })
        compose.onNodeWithTag("original-search-mode").performScrollTo().assertIsEnabled().performClick()
        observation { it.state.stage == OriginalSearchStage.READY && it.state.input.mode == OriginalSearchMode.EXACT }.let { assertTrue(it.state.hits.isEmpty()) }
        field("original-search-query", exact)
        val found = ready(exact)
        assertEquals(OriginalSearchMode.EXACT, found.state.input.mode); assertEquals(listOf("one"), found.state.hits.map { it.entry.id })
        assertEquals(full, found.state.hits.single().quote)
        compose.onNodeWithText(found.state.hits.single().excerpt).performScrollTo().assertIsDisplayed()
        openCard("one"); assertEquals(full, fullText())
        compose.onNodeWithText("返回列表").performClick(); ready(exact)
        assertEquals(exact, editable("original-search-query")); assertEquals("2026-10-14", editable("original-search-from"))
        assertEquals("2026-10-14", editable("original-search-through"))
        compose.runOnIdle {
            assertEquals("synthetic-private-untouched-draft", state.value.text)
            assertEquals(full, state.value.entries.single { it.id == "one" }.originalText)
            assertEquals(0, draftChanges); assertEquals(0, saves)
        }
    }

    @Test fun readerReturnKeepsActualMemoriesListIndexAndOffsetWhenPaddingShowsPreviousItem() {
        val state = local(*(0..54).map { entry("record-$it", "marker 合成完整原文 $it\n" + "叶".repeat(300)) }.toTypedArray())
        val authoritative = AtomicReference<LocalRecordUiState?>(state.value)
        content(state, source = authoritative::get); field("original-search-query", "marker")
        val first = ready("marker"); assertEquals(55, first.state.hits.size)
        val ownedList = compose.runOnIdle { memories }
        val index = 3 + 20
        val padding = compose.runOnIdle { ownedList.layoutInfo.beforeContentPadding }
        assertTrue(padding > 0)
        var observedAnchor: Triple<Int, Int, Int>? = null
        val candidates = listOf(0, -1, -(padding / 4).coerceAtLeast(1), -(padding / 2).coerceAtLeast(1),
            -padding, -padding * 2, 1, padding / 2)
        for (offset in candidates.distinct()) {
            compose.runOnIdle { runBlocking { ownedList.scrollToItem(index, offset) } }
            compose.waitForIdle()
            val actual = compose.runOnIdle { Triple(ownedList.firstVisibleItemIndex,
                ownedList.firstVisibleItemScrollOffset, ownedList.layoutInfo.visibleItemsInfo.first().index) }
            if (actual.first == index && actual.third != actual.first) {
                compose.runOnIdle { assertEquals("record-20", ownedList.layoutInfo.visibleItemsInfo.single { it.index == actual.first }.key) }
                observedAnchor = actual; break
            }
        }
        assertNotNull("Measured bounded offsets must create the actual padding/index mismatch", observedAnchor)
        val before = checkNotNull(observedAnchor)
        assertEquals(index, before.first); assertNotEquals("The previous item is visible only inside content padding", before.first, before.third)
        repeat(2) { turn ->
            context = "reader-return-$turn"
            val found = ready("marker"); val tail = found.state.hits.single { it.entry.id == "record-54" }
            compose.runOnIdle { found.open(tail) }
            assertEquals(state.value.entries.last().originalText, fullText())
            compose.runOnIdle { found.open(found.state.hits.first()) }
            assertEquals(state.value.entries.last().originalText, fullText())
            if (turn == 0) compose.onNodeWithText("返回列表").performClick() else {
                val fresh = state.value.copy(entries = state.value.entries.map {
                    if (it.id == "record-54") it.copy(currentRevision = it.currentRevision + 1) else it
                })
                compose.runOnIdle { authoritative.set(fresh); back.onBackPressed() }
                observation { it.state.stage == OriginalSearchStage.WAITING }
                compose.runOnIdle { state.value = fresh }
            }
            ready("marker")
            compose.waitUntil(2_000) { ownedList.firstVisibleItemIndex == before.first && ownedList.firstVisibleItemScrollOffset == before.second }
            compose.runOnIdle { assertSame(ownedList, memories); assertEquals(before.first, memories.firstVisibleItemIndex)
                assertEquals(before.second, memories.firstVisibleItemScrollOffset) }
            assertEquals("marker", ready("marker").state.input.query)
        }
        assertEquals("synthetic-private-untouched-draft", state.value.text)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun narrowLargeFontSeparatesNoHitsInvalidAndFailedDatesAndKeepsCorrectionAndClearReachable() {
        val original = entry("one", "needle 合成可找到的原文"); val state = local(original)
        content(state, largeFont = true); field("original-search-query", "未命中合成词")
        assertTrue(ready("未命中合成词").state.hits.isEmpty())
        shownStatus("没有匹配的原文")
        field("original-search-from", "2026-02-30"); observation { it.state.stage == OriginalSearchStage.INVALID }
        shownStatus("请检查查找条件")
        compose.onAllNodes(hasText("没有匹配的原文")).assertCountEquals(0); compose.onAllNodes(hasTestTag("entry-one")).assertCountEquals(0)
        field("original-search-from", "2026-10-13"); field("original-search-through", "2026-10-12")
        observation { it.state.stage == OriginalSearchStage.INVALID && it.state.input.through == "2026-10-12" }
        field("original-search-through", "2026-10-13"); field("original-search-query", "needle")
        assertEquals(listOf("one"), ready("needle").state.hits.map { it.entry.id })
        compose.runOnIdle { state.value = state.value.copy(entries = listOf(original.copy(captureTimeZone = "Invalid/Synthetic"))) }
        observation { it.state.stage == OriginalSearchStage.FAILED }
        shownStatus("本机查找需要处理")
        compose.onAllNodes(hasText("没有匹配的原文")).assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(entries = listOf(original)) }; ready("needle")
        val clear = compose.onNodeWithTag("original-search-clear").performScrollTo().assertIsDisplayed().assertIsEnabled()
        val bounds = clear.getUnclippedBoundsInRoot(); val viewport = compose.onNode(hasScrollToIndexAction()).getUnclippedBoundsInRoot()
        assertTrue(bounds.height >= AIyouDimensions.minTouchTarget)
        assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right); assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        clear.performClick(); observation { it.state.stage == OriginalSearchStage.BROWSE }
        assertEquals("", editable("original-search-query")); assertEquals("", editable("original-search-from")); assertEquals("", editable("original-search-through"))
        compose.onNodeWithTag("original-search-mode").performScrollTo().assertIsEnabled()
        assertEquals(OriginalSearchMode.KEYWORDS, observations.get().state.input.mode); assertNull(state.value.error)
        assertEquals("synthetic-private-untouched-draft", state.value.text); assertEquals(0, draftChanges); assertEquals(0, saves)
    }

    @Test fun retainedResultActionsRejectQueryAbaRevisionDeletionReadFailureAndPageLeave() {
        val state = local(entry("one", "needle 合成当前原文")); val baseline = state.value
        val authoritative = AtomicReference<LocalRecordUiState?>(baseline)
        content(state, source = authoritative::get); field("original-search-query", "needle"); ready("needle")
        for ((variant, changed) in listOf(baseline.copy(entries = listOf(entry("one", "needle 合成新原文", revision = 2))),
            baseline.copy(entries = emptyList()), baseline.copy(error = "synthetic current read failure"), baseline.copy(ready = false)).withIndex()) {
            context = "source-restore-$variant"
            val retained = ready("needle"); val hit = retained.state.hits.single()
            compose.runOnIdle { authoritative.set(changed); retained.open(hit); state.value = changed }
            assertNoReader(); val after = serials.get()
            compose.runOnIdle { authoritative.set(baseline); state.value = baseline }
            observation { it.serial > after && it.state.stage == OriginalSearchStage.READY && it.state.input.query == "needle" &&
                it.state.hits.singleOrNull()?.entry?.currentRevision == 1 }
        }
        val queryOld = ready("needle")
        val edit = compose.onNodeWithTag("original-search-query").performScrollTo().fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        compose.runOnIdle { assertTrue(edit(AnnotatedString("临时合成词"))); assertTrue(edit(AnnotatedString("needle"))); queryOld.open(queryOld.state.hits.single()) }
        assertNoReader()
        observation { it.serial > queryOld.serial && it.state.stage == OriginalSearchStage.READY && it.state.input.query == "needle" }
        val modeOld = ready("needle")
        val toggle = compose.onNodeWithTag("original-search-mode").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { assertTrue(toggle()); assertTrue(toggle()); modeOld.open(modeOld.state.hits.single()) }
        assertNoReader()
        observation { it.serial > modeOld.serial && it.state.stage == OriginalSearchStage.READY && it.state.input.query == "needle" &&
            it.state.input.mode == OriginalSearchMode.KEYWORDS }
        val pageOld = ready("needle"); tab("待办").performClick()
        compose.runOnIdle { pageOld.open(pageOld.state.hits.single()) }; assertNoReader()
        tab("回忆").performClick(); observation { it.state.stage == OriginalSearchStage.BROWSE }
        assertEquals("", editable("original-search-query"))
        compose.runOnIdle { pageOld.open(pageOld.state.hits.single()) }; assertNoReader()
        assertEquals("synthetic-private-untouched-draft", state.value.text); assertEquals(0, draftChanges); assertEquals(0, saves)
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Config(sdk = [35], shadows = [KeyboardHistoryMagnifierShadow::class])
    @Test fun compositionRestorationDropsSensitiveQueryDatesAndResultsAndRevokesOldActions() {
        val marker = "synthetic-private-local-query"; val state = local(entry("one", "合成原文 $marker 完整保留"))
        val sourceSnapshot = state.value.entries; val show = mutableStateOf(true); val restoration = StateRestorationTester(compose)
        restoration.setContent { if (show.value) LookupTestScreen(state, source = { state.value }) else Text("合成查找已销毁") }
        field("original-search-query", marker); field("original-search-from", "2026-10-13"); field("original-search-through", "2026-10-13")
        compose.onNodeWithTag("original-search-mode").performScrollTo().performClick()
        compose.runOnIdle {
            // Semantics replacement is fixture setup; isolate the following actual user edit.
            val actual = checkNotNull(actualInputs.get()).query
            assertEquals(marker, actual.text.toString())
            actual.undoState.clearHistory()
            assertFalse(actual.undoState.canUndo); assertFalse(actual.undoState.canRedo)
            assertEquals("History reset must preserve the prepared query", marker, actual.text.toString())
        }
        val beforeTyping = editable("original-search-query")
        focusedField("original-search-query").performKeyInput { pressKey(Key.X) }
        val typed = editable("original-search-query")
        assertNotEquals("A real editable user key must change the query", beforeTyping, typed)
        compose.runOnIdle {
            val actual = checkNotNull(actualInputs.get()).query
            assertEquals("Observed actual UI object must contain the user edit", typed, actual.text.toString())
            assertTrue("Actual shared editable state must retain real user Undo", actual.undoState.canUndo)
        }
        // Exercise the real editable field's Undo, not programmatic fixture state.
        nativeHistory("original-search-query")
        assertEquals("Actual Undo must restore the previous query", beforeTyping, editable("original-search-query"))
        compose.runOnIdle { assertTrue(checkNotNull(actualInputs.get()).query.undoState.canRedo) }
        nativeHistory("original-search-query", redo = true)
        assertEquals("Actual Redo must restore the real typed edit", typed, editable("original-search-query"))
        nativeHistory("original-search-query")
        assertEquals("Actual Undo must remain available after Redo", beforeTyping, editable("original-search-query"))
        val beforeClear = observation { it.state.stage == OriginalSearchStage.READY && it.state.input.query == marker &&
            it.state.input.mode == OriginalSearchMode.EXACT && it.state.input.from == "2026-10-13" && it.state.input.through == "2026-10-13" }
        assertEquals(listOf("one"), beforeClear.state.hits.map { it.entry.id })
        val beforeClearInputs = compose.runOnIdle { checkNotNull(actualInputs.get()) }
        compose.onNodeWithTag("original-search-clear").performScrollTo().performClick(); observation { it.state.stage == OriginalSearchStage.BROWSE }
        for (tag in listOf("original-search-query", "original-search-from", "original-search-through")) {
            compose.runOnIdle {
                val actual = checkNotNull(actualInputs.get()).field(tag)
                assertNotSame("Clear must replace the actual field owner", beforeClearInputs.field(tag), actual)
                assertEquals("Clear must empty the actual field", "", actual.text.toString())
                assertFalse("The new actual field must have no Undo history", actual.undoState.canUndo)
                assertFalse("The new actual field must have no Redo history", actual.undoState.canRedo)
                actual.undoState.undo()
                actual.undoState.redo()
                assertEquals("No old condition may reappear from history", "", actual.text.toString())
            }
            assertEquals("Clearing must not retain the previous input in Undo", "", editable(tag))
            nativeHistory(tag)
            assertEquals("The actual new editable owner cannot Undo a cleared condition", "", editable(tag))
            nativeHistory(tag, redo = true)
            assertEquals("The actual new editable owner cannot Redo a cleared condition", "", editable(tag))
        }
        compose.runOnIdle { beforeClear.open(beforeClear.state.hits.single()) }; assertNoReader()
        field("original-search-query", marker); field("original-search-from", "2026-10-13"); field("original-search-through", "2026-10-13")
        compose.onNodeWithTag("original-search-mode").performScrollTo().performClick()
        val retained = observation { it.state.stage == OriginalSearchStage.READY && it.state.input.query == marker &&
            it.state.input.mode == OriginalSearchMode.EXACT && it.state.input.from == "2026-10-13" && it.state.input.through == "2026-10-13" }
        assertFalse(retained.state.toString().contains(marker)); assertFalse(retained.state.input.toString().contains(marker))
        compose.runOnIdle { assertNoSavedInput(checkNotNull(saveableRegistry).performSave(), marker, "2026-10-13") }
        restoration.emulateSavedInstanceStateRestore()
        observation { it.serial > retained.serial && it.state.stage == OriginalSearchStage.BROWSE }
        assertEquals("", editable("original-search-query")); assertEquals("", editable("original-search-from")); assertEquals("", editable("original-search-through"))
        assertEquals(OriginalSearchMode.KEYWORDS, observations.get().state.input.mode)
        compose.runOnIdle { retained.open(retained.state.hits.single()) }; assertNoReader()
        field("original-search-query", marker); val disposedAction = ready(marker)
        compose.runOnIdle { show.value = false }; compose.onNodeWithText("合成查找已销毁").assertIsDisplayed()
        compose.runOnIdle { disposedAction.open(disposedAction.state.hits.single()) }
        compose.runOnIdle { show.value = true }
        observation { it.serial > disposedAction.serial && it.state.stage == OriginalSearchStage.BROWSE }
        assertEquals("", editable("original-search-query"))
        compose.runOnIdle { disposedAction.open(disposedAction.state.hits.single()) }; assertNoReader()
        compose.runOnIdle { assertSame(sourceSnapshot, state.value.entries); assertEquals("synthetic-private-untouched-draft", state.value.text)
            assertEquals(0, draftChanges); assertEquals(0, saves) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun longOriginalTailHitScrollsToExactUnicodeRangeAndChangedVersionDropsOldHighlight() {
        val needle = "末尾目标🙂"
        val text = "合成🌿e\u0301前文\n".repeat(600) + needle + "\n合成保留末行"
        val state = local(entry("one", text))
        content(state, largeFont = true)
        field("original-search-query", needle)
        val found = ready(needle)
        val hit = found.state.hits.single()
        val at = text.indexOf(needle)
        assertEquals(text.codePointCount(0, at), hit.matchStartCodePoint)
        assertEquals(needle, text.substring(text.offsetByCodePoints(0, hit.matchStartCodePoint),
            text.offsetByCodePoints(0, hit.matchEndCodePoint)))
        openCard("one")
        val field = compose.onNodeWithTag("original-text")
        val actual = field.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals(text, actual.text)
        assertTrue(actual.spanStyles.any { it.start == at && it.end == at + needle.length })
        compose.runOnIdle {
            assertNoSavedInput(checkNotNull(saveableRegistry).performSave(), needle,
                com.t1doo.aiyou.model.OriginalExports.hash(text))
        }
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        val scrollNode = compose.onNodeWithTag("original-scroll")
        val scroll = scrollNode.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val line = layout.getLineForOffset(at)
        assertTrue("Tail location must actually scroll", scroll.value() > 0f)
        assertTrue(layout.getLineTop(line) - scroll.value() >= -1f)
        assertTrue(layout.getLineBottom(line) - scroll.value() <= scrollNode.fetchSemanticsNode().size.height + 1f)
        compose.runOnIdle { state.value = state.value.copy(entries = listOf(entry("one", "合成当前新版本", revision = 2))) }
        val changed = compose.onNodeWithTag("original-text").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals("合成当前新版本", changed.text)
        assertTrue("An old source range cannot decorate the new version", changed.spanStyles.isEmpty())
        compose.onNodeWithText("返回列表").performClick()
        compose.runOnIdle { found.open(hit) }
        assertNoReader()
        assertEquals("synthetic-private-untouched-draft", state.value.text)
        assertEquals(0, draftChanges); assertEquals(0, saves)
    }

    @Test fun indexPendingAndFailureKeepLiteralResultsAndExplicitRebuildControlHonest() {
        val state = local(entry("one", "合成咖啡当前原文"))
        // UI port shell only; actual tokenizer/FTS/AEAD/leases are tested by host SQL.
        val port = object : LocalSearchRepository {
            override fun epoch() = 7L
            override fun batch() = LocalSearchProgress(0, false, 7)
            override fun rebuild() = 8L
            override fun candidates(request: LocalSearchRequest) = object : LocalSearchSnapshot {
                override val epoch = 7L
                override val entries = state.value.entries
                override val tags = emptyMap<String, com.t1doo.aiyou.model.ManualTagSnapshot>()
                override val pending = true
            }
            override fun publishCurrent(snapshot: LocalSearchSnapshot, publish: () -> Unit): Boolean { publish(); return true }
            override fun resolve(snapshot: LocalSearchSnapshot, entryId: String, publish: (StoredEntry) -> Unit): Boolean {
                state.value.entries.firstOrNull { it.id == entryId }?.let(publish) ?: return false
                return true
            }
        }
        state.value = state.value.copy(localSearch = port, localSearchEpoch = 7, localIndexBusy = true)
        content(state); field("original-search-query", "咖啡")
        assertEquals(listOf("one"), ready("咖啡").state.hits.map { it.entry.id })
        compose.onNodeWithTag("original-search-rebuild").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(localIndexBusy = false, localIndexError = "合成查找整理暂停") }
        compose.onNodeWithTag("original-search-rebuild").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, rebuilds)
        assertEquals("咖啡", editable("original-search-query"))
        assertEquals("synthetic-private-untouched-draft", state.value.text)
        assertEquals(0, draftChanges); assertEquals(0, saves)
    }

    @Test fun indexedLocationExitFailureRetiresActualReaderAndKeepsCurrentSearchInput() {
        val state = local(entry("one", "合成咖啡当前原文"))
        val reached = CountDownLatch(1); val release = CountDownLatch(1); val exited = CountDownLatch(1)
        val port = UiSearchPort { state.value }
        port.afterLocation = {
            reached.countDown()
            try { check(release.await(5, TimeUnit.SECONDS)); throw LocalSearchFailure() }
            finally { exited.countDown() }
        }
        state.value = state.value.copy(localSearch = port, localSearchEpoch = 7)
        content(state); field("original-search-query", "咖啡")
        val found = ready("咖啡")
        compose.runOnIdle { found.open(found.state.hits.single()) }
        try {
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            assertEquals("合成咖啡当前原文", fullText())
        } finally { release.countDown(); assertTrue(exited.await(5, TimeUnit.SECONDS)) }
        ready("咖啡")
        assertNoReader()
        assertEquals("咖啡", editable("original-search-query"))
        assertEquals("synthetic-private-untouched-draft", state.value.text)
    }

    @Test fun indexedLocationExitFailureCannotRevertActualLaterDestinationNavigation() {
        val state = local(entry("one", "合成咖啡当前原文"))
        val reached = CountDownLatch(1); val release = CountDownLatch(1); val exited = CountDownLatch(1)
        val port = UiSearchPort { state.value }
        port.afterLocation = {
            reached.countDown()
            try { check(release.await(5, TimeUnit.SECONDS)); throw LocalSearchFailure() }
            finally { exited.countDown() }
        }
        state.value = state.value.copy(localSearch = port, localSearchEpoch = 7)
        content(state); field("original-search-query", "咖啡")
        val found = ready("咖啡")
        compose.runOnIdle { found.open(found.state.hits.single()) }
        try {
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            assertEquals("合成咖啡当前原文", fullText())
            // The reader hides navigation tabs; return through the real control.
            compose.onNodeWithText("返回列表").performClick()
            tab("待办").performClick()
        } finally { release.countDown(); assertTrue(exited.await(5, TimeUnit.SECONDS)) }
        compose.waitUntil(2_000) { refreshCallbacks.get() > 0 }
        assertNoReader()
        tab("待办").assertIsSelected()
        assertEquals("synthetic-private-untouched-draft", state.value.text)
        assertEquals(0, draftChanges); assertEquals(0, saves)
    }

    /** Compose wiring shell; real after-callback SQL commit failures are separate host tests. */
    private class UiSearchPort(private val source: () -> LocalRecordUiState) : LocalSearchRepository {
        @Volatile var afterLocation: (() -> Unit)? = null
        override fun epoch() = 7L
        override fun batch() = LocalSearchProgress(0, false, 7)
        override fun rebuild() = 8L
        override fun candidates(request: LocalSearchRequest) = object : LocalSearchSnapshot {
            override val epoch = 7L
            override val entries = source().entries
            override val tags = emptyMap<String, com.t1doo.aiyou.model.ManualTagSnapshot>()
            override val pending = false
        }
        override fun publishCurrent(snapshot: LocalSearchSnapshot, publish: () -> Unit): Boolean { publish(); return true }
        override fun resolve(snapshot: LocalSearchSnapshot, entryId: String, publish: (StoredEntry) -> Unit): Boolean {
            val entry = source().entries.firstOrNull { it.id == entryId } ?: return false
            publish(entry); afterLocation?.invoke(); return true
        }
    }

    private fun entry(id: String, text: String, revision: Int = 1, captured: String = "2026-10-13T12:00:00Z", zone: String = "UTC") =
        StoredEntry(id, revision, text, Instant.parse(captured).toEpochMilli(), zone, ProcessingPolicy.LOCAL_ONLY)
    private fun local(vararg entries: StoredEntry) = mutableStateOf(LocalRecordUiState(ready = true, opening = false,
        text = "synthetic-private-untouched-draft", entries = entries.toList()))
    private fun content(state: MutableState<LocalRecordUiState>, largeFont: Boolean = false, source: () -> LocalRecordUiState? = { state.value }) {
        compose.setContent { LookupTestScreen(state, largeFont, source) }
    }
    @Composable private fun LookupTestScreen(state: MutableState<LocalRecordUiState>, largeFont: Boolean = false, source: () -> LocalRecordUiState?) {
        back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
        val registry = LocalSaveableStateRegistry.current
        val view = LocalView.current
        SideEffect { saveableRegistry = registry; actualView.set(view) }
        val density = LocalDensity.current
        CompositionLocalProvider(LocalOriginalSearchObserver provides searchObserver, LocalOriginalReadingObserver provides readingObserver,
            LocalDensity provides if (largeFont) Density(density.density, 2f) else density) {
            if (largeFont) Box(Modifier.size(width = 320.dp, height = 640.dp)) { LookupTestApp(state, source) } else LookupTestApp(state, source)
        }
    }
    @Composable private fun LookupTestApp(state: MutableState<LocalRecordUiState>, source: () -> LocalRecordUiState?) {
        AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES, local = state.value, currentRecords = source,
            onDraftChange = { draftChanges++ }, onSave = { saves++ }, onRebuildLocalSearch = { rebuilds++ },
            onRefreshLocalSearch = { refreshCallbacks.incrementAndGet() }) }
    }
    private fun field(tag: String, text: String) { compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performTextReplacement(text) }
    private fun shownStatus(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }
    private fun focusedField(tag: String) = compose.onNodeWithTag(tag)
        .performScrollTo().assertIsEnabled().performClick().assertIsFocused()
    private fun nativeHistory(tag: String, redo: Boolean = false) {
        focusedField(tag)
        val stable = observation { it.state.stage == OriginalSearchStage.READY ||
            it.state.stage == OriginalSearchStage.BROWSE }
        val before = compose.runOnIdle {
            val field = checkNotNull(actualInputs.get()).field(tag)
            (if (redo) field.undoState.canRedo else field.undoState.canUndo) to
                stable.state.inputEpoch
        }
        // Held modifier simulation has previously delivered plain z in this JVM
        // environment. Dispatch explicit native Ctrl metadata through the actual
        // focused Compose View and the new production history key handler.
        compose.runOnIdle {
            val now = android.os.SystemClock.uptimeMillis()
            val meta = android.view.KeyEvent.META_CTRL_ON or
                (if (redo) android.view.KeyEvent.META_SHIFT_ON else 0)
            for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                assertTrue("Actual focused history handler must consume the native chord",
                    checkNotNull(actualView.get()).dispatchKeyEvent(android.view.KeyEvent(now, now,
                        action, android.view.KeyEvent.KEYCODE_Z, 0, meta)))
            }
        }
        if (before.first) {
            // Foundation can consume the same chord itself. Require the real
            // production ownership fence, not only successful text history.
            observation { it.state.inputEpoch > before.second }
        }
    }
    private fun editable(tag: String): String = compose.onNodeWithTag(tag).performScrollTo().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun openCard(id: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-$id"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-$id"))).performClick()
    }
    private fun fullText() = compose.onNodeWithTag("original-text").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun assertNoReader() {
        compose.waitForIdle(); compose.onAllNodes(hasTestTag("original-text")).assertCountEquals(0); compose.onAllNodes(hasText("记录原文")).assertCountEquals(0)
    }
    private fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
    private fun ready(query: String) = observation { it.state.stage == OriginalSearchStage.READY && it.state.input.query == query }
    private fun observation(predicate: (Observation) -> Boolean): Observation {
        // runOnIdle synchronizes before its action. Commit that action's pending UI frame
        // before polling an external observer; waitUntil does not perform full idling.
        compose.waitForIdle()
        try {
            compose.waitUntil(2_000) { observations.get()?.let(predicate) == true }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val last = observations.get()
            throw AssertionError("Lookup observation timed out: stage=${last?.state?.stage}, " +
                "mode=${last?.state?.input?.mode}, serial=${last?.serial}, hits=${last?.state?.hits?.size}, " +
                "generation=${last?.state?.generation}, context=$context, ${diagnostics.get()}", failure)
        }
        compose.waitForIdle()
        return compose.runOnIdle { checkNotNull(observations.get()).also { check(predicate(it)) } }
    }
    /** The retained JVM teardown log shows a null Magnifier popup Surface.
     * Only the visual Magnifier boundary is shadowed; editing, Undo, ownership,
     * restoration, navigation and their assertions remain real production paths.
     * Magnifier rendering and teardown on a device remain NOT RUN.
     * https://robolectric.org/extending/#custom-shadows
     */
    @Implements(Magnifier::class)
    class KeyboardHistoryMagnifierShadow {
        @Implementation fun show(@Suppress("UNUSED_PARAMETER") x: Float,
            @Suppress("UNUSED_PARAMETER") y: Float) = Unit
        @Implementation fun show(@Suppress("UNUSED_PARAMETER") x: Float,
            @Suppress("UNUSED_PARAMETER") y: Float,
            @Suppress("UNUSED_PARAMETER") windowX: Float,
            @Suppress("UNUSED_PARAMETER") windowY: Float) = Unit
        @Implementation fun update() = Unit
        @Implementation fun dismiss() = Unit
    }

    private fun assertNoSavedInput(value: Any?, marker: String, date: String) {
        when (value) {
            is OriginalReadingLocation, is LocalSearchSnapshot -> fail("Private live lookup/reader binding must never enter SavedState")
            is androidx.compose.runtime.State<*> -> assertNoSavedInput(value.value, marker, date)
            is android.os.Bundle -> value.keySet().forEach { key ->
                assertNoSavedInput(key, marker, date); assertNoSavedInput(value.get(key), marker, date)
            }
            is CharSequence -> { assertFalse("Query must never enter the saved payload", value.contains(marker))
                assertFalse("Date filters must never enter the saved payload", value.contains(date)) }
            is ByteArray -> for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
                assertNoSavedInput(value.toString(encoding), marker, date)
            }
            is android.util.SparseArray<*> -> for (index in 0 until value.size()) {
                assertNoSavedInput(value.keyAt(index), marker, date)
                assertNoSavedInput(value.valueAt(index), marker, date)
            }
            is Map<*, *> -> value.forEach { (key, item) -> assertNoSavedInput(key, marker, date); assertNoSavedInput(item, marker, date) }
            is Iterable<*> -> value.forEach { assertNoSavedInput(it, marker, date) }
            is Array<*> -> value.forEach { assertNoSavedInput(it, marker, date) }
            is android.os.Parcelable -> {
                val parcel = android.os.Parcel.obtain()
                try {
                    parcel.writeParcelable(value, 0)
                    assertNoSavedInput(parcel.marshall(), marker, date)
                } finally { parcel.recycle() }
            }
        }
    }
}
