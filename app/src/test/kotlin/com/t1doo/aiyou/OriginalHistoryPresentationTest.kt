package com.t1doo.aiyou

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.model.OriginalRevision
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.TextDraft
import com.t1doo.aiyou.records.LocalOriginalReadingObserver
import com.t1doo.aiyou.records.LocalRecordsViewModel
import com.t1doo.aiyou.records.OriginalReadingCallbacks
import com.t1doo.aiyou.records.OriginalReadingObserver
import com.t1doo.aiyou.ui.Destination
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real App + ViewModel + UI actions, synthetic repository IO. Native/device execution NOT RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
class OriginalHistoryPresentationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var vm: LocalRecordsViewModel
    private lateinit var callbacks: OriginalReadingCallbacks
    private lateinit var nativeClipboard: Clipboard
    private lateinit var selection: SelectionState
    private lateinit var back: OnBackPressedDispatcher
    private val clipboard get() = ApplicationProvider.getApplicationContext<Application>().getSystemService(ClipboardManager::class.java)
    private val observer = object : OriginalReadingObserver {
        override fun onNavigation(callbacks: OriginalReadingCallbacks) { this@OriginalHistoryPresentationTest.callbacks = callbacks }
        override fun onClipboard(clipboard: Clipboard) { nativeClipboard = clipboard }
        override fun onSelection(selection: SelectionState) { this@OriginalHistoryPresentationTest.selection = selection }
    }
    @After fun close() { if (::vm.isInitialized) vm.viewModelScope.cancel() }
    private fun start(store: Store = Store(), narrow: Boolean = false, restoration: StateRestorationTester? = null,
        destination: Destination = Destination.MEMORIES, wide: Boolean = false): Store {
        vm = LocalRecordsViewModel(ApplicationProvider.getApplicationContext<Application>(), { store })
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            val local by vm.state.collectAsState()
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            val density = LocalDensity.current
            CompositionLocalProvider(LocalOriginalReadingObserver provides observer,
                LocalDensity provides if (narrow) Density(density.density, 2f) else density) {
                Box(if (narrow) Modifier.size(320.dp, 640.dp) else if (wide) Modifier.size(840.dp, 1000.dp) else Modifier) {
                    AIyouTheme { AIyouApp(previewDestination = destination, local = local, onDraftChange = vm::changeText,
                        onSave = vm::save, onRetry = vm::retry, onBeginOriginalEdit = vm::beginOriginalEdit,
                        onOriginalEditChange = vm::changeOriginalEdit, onOriginalEditSave = vm::saveOriginalEdit,
                        onOriginalEditCancel = vm::cancelOriginalEdit, onBeginOriginalHistory = vm::beginOriginalHistory,
                        onSelectOriginalRevision = vm::selectOriginalRevision, onBackOriginalHistory = vm::backOriginalHistory,
                        onRetryOriginalHistory = vm::retryOriginalHistory) }
                }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitUntil(5000) { vm.state.value.ready }; compose.waitForIdle()
        return store
    }
    private fun open() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-original"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-original"))).performClick()
    }
    private fun history() { compose.onNodeWithText("历史版本").performClick(); loaded() }
    private fun loaded() { compose.waitUntil(5000) { vm.state.value.originalHistory?.loading == false }; compose.waitForIdle() }
    private fun revision(number: Int) {
        compose.onNodeWithTag("history-list").performScrollToNode(hasText("阅读版本 $number"))
        compose.onNodeWithText("阅读版本 $number").performClick(); loaded()
    }
    private fun full() = compose.onNodeWithTag("original-text").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun input() = compose.onNodeWithTag("original-edit-text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun sentinel() = compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("合成", "哨兵")) }
    private fun copied() = clipboard.primaryClip!!.getItemAt(0).text.toString()
    private fun editHistory() { compose.onNodeWithTag("original-edit-history").performScrollTo().performClick(); loaded() }

    private fun assertCenteredHistory(contentTag: String, expectedViewportWidth: Float) {
        val viewport = compose.onNodeWithTag("history-viewport").getUnclippedBoundsInRoot()
        val panel = compose.onNodeWithTag("history-content").getUnclippedBoundsInRoot()
        val content = compose.onNodeWithTag(contentTag).getUnclippedBoundsInRoot()
        val width = minOf(viewport.width.value, AIyouDimensions.contentMaxWidth.value)
        assertEquals(expectedViewportWidth, viewport.width.value, 0.01f)
        assertEquals(width, panel.width.value, 0.01f)
        assertEquals((viewport.left.value + viewport.right.value) / 2f,
            (panel.left.value + panel.right.value) / 2f, 0.01f)
        assertEquals(panel.left.value + AIyouDimensions.pageNarrow.value, content.left.value, 0.01f)
        assertEquals(panel.right.value - AIyouDimensions.pageNarrow.value, content.right.value, 0.01f)
        assertTrue(content.height.value > 0f)
    }

    @Test @Config(sdk = [35], qualifiers = "w840dp-h1000dp")
    fun wideHistoryListAndReadFailureKeepCenteredMaximumContentWidth() {
        val store = start(wide = true); open(); history()
        assertCenteredHistory("history-list", 840f)
        assertEquals(0, store.reviseCalls)
        store.failHistory = true
        compose.onNodeWithText("阅读版本 1").performClick(); loaded()
        assertCenteredHistory("history-status", 840f)
        compose.onAllNodesWithTag("history-list").assertCountEquals(0)
        compose.onAllNodesWithTag("original-text").assertCountEquals(0)
        store.failHistory = false
        compose.onNodeWithTag("history-retry").performClick(); loaded()
        assertEquals("合成旧原文🌿", full())
        compose.runOnIdle { back.onBackPressed() }; loaded()
        assertCenteredHistory("history-list", 840f)
        assertEquals(0, store.reviseCalls); assertEquals("合成此刻草稿", store.draft.text)
    }

    @Test fun currentAndOldVersionsOpenFullUnicodeAndNativeCopyOnlySelectedHistory() {
        val text = "🌿".repeat(120) + "界".repeat(99_756) + "末端原文"
        assertEquals(100_000, text.length)
        val store = start(Store(oldText = text)); open(); history()
        compose.onNodeWithText("当前版本 2").assertIsDisplayed()
        revision(1); assertEquals(text, full())
        compose.onNodeWithText("复制全文").performClick(); assertEquals(text, copied())
        assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        sentinel(); compose.runOnIdle { selection.select(TextRange(0, 2)) }
        compose.onNodeWithTag("original-text").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.C); keyUp(Key.CtrlLeft) }
        compose.waitForIdle(); assertEquals("🌿", copied())
        val retained = nativeClipboard
        compose.runOnIdle { back.onBackPressed() }; loaded(); sentinel()
        compose.runOnIdle { runBlocking { retained.setClipEntry(ClipEntry(ClipData.newPlainText("合成", "迟到复制"))) } }
        assertEquals("哨兵", copied())
        revision(2); assertEquals("合成当前原文", full())
        assertEquals(0, store.reviseCalls); assertEquals("合成此刻草稿", store.draft.text)
    }

    @Test fun historyReturnPreservesEditorInputCaptureDraftAndRecordListAnchorThenSaveShowsVersions() {
        val store = start(); val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasTestTag("entry-original"))
        val anchor = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(); assertTrue(anchor > 0f)
        open(); compose.onNodeWithText("编辑原文").performClick()
        compose.onNodeWithTag("original-edit-text").performTextReplacement("合成保留中的用户修改")
        val editor = callbacks; val editSession = vm.state.value.originalEdit!!.sessionId
        editHistory(); revision(1)
        compose.runOnIdle { editor.changeEdit(editSession, "迟到覆盖"); editor.saveEdit(editSession); editor.cancelEdit(editSession) }
        assertEquals("合成保留中的用户修改", vm.state.value.originalEdit!!.text)
        compose.runOnIdle { back.onBackPressed() }; loaded()
        compose.onNodeWithText("返回编辑").performClick(); assertEquals("合成保留中的用户修改", input())
        assertEquals(editSession, vm.state.value.originalEdit!!.sessionId)
        compose.onNodeWithTag("original-edit-save").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.state.value.originalEdit == null }; compose.waitForIdle()
        assertEquals("合成保留中的用户修改", full()); history()
        compose.onNodeWithText("当前版本 3").assertIsDisplayed(); revision(1); assertEquals("合成旧原文🌿", full())
        compose.runOnIdle { back.onBackPressed() }; loaded(); compose.onNodeWithText("返回原文").performClick()
        compose.onNodeWithText("返回列表").performClick()
        assertEquals(anchor, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
        assertTrue(compose.onNodeWithTag("entry-original").fetchSemanticsNode().config[SemanticsProperties.Selected])
        assertEquals("合成此刻草稿", vm.state.value.text); assertEquals("合成此刻草稿", store.draft.text)
        assertEquals(listOf("合成旧原文🌿", "合成当前原文", "合成保留中的用户修改"), store.versions.map { it.originalText })
        assertEquals(1, store.reviseCalls)
    }

    @Test fun failedListAndSelectedOriginalReadsHidePlaintextAndRetryWithoutDiscardingEdits() {
        val store = start(); open(); compose.onNodeWithText("编辑原文").performClick()
        compose.onNodeWithTag("original-edit-text").performTextReplacement("合成失败期间的修改")
        store.failHistory = true; editHistory()
        compose.onAllNodesWithTag("history-list").assertCountEquals(0)
        compose.onAllNodesWithTag("original-text").assertCountEquals(0)
        compose.onNodeWithTag("history-retry").performScrollTo().assertIsDisplayed()
        store.failHistory = false; compose.onNodeWithTag("history-retry").performClick(); loaded()
        store.failHistory = true; revision(1)
        compose.onAllNodesWithTag("original-text").assertCountEquals(0)
        assertEquals(1, vm.state.value.originalHistory!!.selectedRevision)
        store.failHistory = false; compose.onNodeWithTag("history-retry").performScrollTo().performClick(); loaded()
        assertEquals("合成旧原文🌿", full())
        compose.runOnIdle { back.onBackPressed() }; loaded(); compose.onNodeWithText("返回编辑").performClick()
        assertEquals("合成失败期间的修改", input()); assertEquals(0, store.reviseCalls)
    }

    @Test fun versionChangeAndDeletionDuringReadsNeverReplaceUserInputOrShowStaleHistory() {
        val store = start(Store(delayFirstHistory = true)); open(); compose.onNodeWithText("编辑原文").performClick()
        compose.onNodeWithTag("original-edit-text").performTextReplacement("合成当前用户输入")
        compose.onNodeWithTag("original-edit-history").performScrollTo().performClick()
        compose.waitUntil(5000) { store.historyStarted.isCompleted }
        store.advance("合成外部新原文"); store.releaseHistory.complete(Unit); loaded()
        assertNotNull(vm.state.value.originalHistory!!.error); compose.onAllNodesWithTag("history-list").assertCountEquals(0)
        compose.onNodeWithTag("history-retry").performScrollTo().performClick(); loaded(); revision(1)
        assertEquals("合成旧原文🌿", full()); assertEquals(3, vm.state.value.originalHistory!!.currentRevision)
        compose.runOnIdle { back.onBackPressed() }; loaded()
        store.deleted = true; revision(1)
        compose.onAllNodesWithTag("original-text").assertCountEquals(0)
        compose.onNodeWithText("这个原文版本已不可用").assertIsDisplayed()
        compose.runOnIdle { back.onBackPressed() }; loaded(); compose.onNodeWithText("返回编辑").performClick()
        assertEquals("合成当前用户输入", input()); compose.onNodeWithTag("original-edit-save").performScrollTo().assertIsNotEnabled()
        assertEquals(0, store.reviseCalls); assertEquals("合成此刻草稿", vm.state.value.text)
    }

    @Test fun closedReopenedAndDisposedHistoryRejectsRetainedReadsCopiesAndNavigation() {
        val restoration = StateRestorationTester(compose)
        val store = start(Store(delayFirstHistory = true), restoration = restoration); open()
        compose.onNodeWithText("历史版本").performClick(); compose.waitUntil(5000) { store.historyStarted.isCompleted }
        val old = callbacks; val oldSession = vm.state.value.originalHistory!!.sessionId
        compose.onNodeWithText("返回原文").performClick(); compose.onNodeWithText("历史版本").performClick()
        val next = vm.state.value.originalHistory!!.sessionId; assertNotEquals(oldSession, next)
        compose.runOnIdle { old.backHistory(oldSession); old.retryHistory(oldSession); old.selectHistory(oldSession, 1) }
        store.releaseHistory.complete(Unit); loaded(); assertEquals(next, vm.state.value.originalHistory!!.sessionId)
        revision(1); val beforeDispose = callbacks; val retainedClipboard = nativeClipboard
        restoration.emulateSavedInstanceStateRestore(); compose.waitForIdle(); sentinel()
        compose.runOnIdle {
            beforeDispose.backHistory(next); beforeDispose.retryHistory(next); beforeDispose.selectHistory(next, 2)
            assertFalse(beforeDispose.copyHistory("original", 1))
            runBlocking { retainedClipboard.setClipEntry(ClipEntry(ClipData.newPlainText("合成", "卸载后的复制"))) }
        }
        assertEquals("哨兵", copied()); assertEquals("合成旧原文🌿", full())
        assertEquals(1, vm.state.value.originalHistory!!.selectedRevision); assertEquals(0, store.reviseCalls)
    }

    @Test fun knownVersionChangeReturningWithoutRetryShowsCurrentAndRejectsOldCopy() {
        val store = start(Store(delayFirstHistory = true)); open()
        val oldReader = callbacks
        compose.onNodeWithText("历史版本").performClick()
        compose.waitUntil(5000) { store.historyStarted.isCompleted }
        store.advance("合成已知最新原文"); store.releaseHistory.complete(Unit); loaded()
        assertNotNull(vm.state.value.originalHistory!!.error)
        assertEquals(3, vm.state.value.entries.first { it.id == "original" }.currentRevision)
        compose.onNodeWithText("返回原文").performClick()
        assertEquals("合成已知最新原文", full())
        compose.onNodeWithText("原文版本 3", substring = true).assertIsDisplayed()
        sentinel(); compose.runOnIdle { assertFalse(oldReader.copy("original", 2)) }
        assertEquals("哨兵", copied())
        compose.onNodeWithText("复制全文").performClick(); assertEquals("合成已知最新原文", copied())
        assertEquals(0, store.reviseCalls); assertEquals("合成此刻草稿", vm.state.value.text)
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun narrowLargeFontHistoryListReturnsToAnchorAndFullMultilineOriginalReachesEnd() {
        val text = "界🌿\n".repeat(24_999) + "尾🌿终"
        assertEquals(100_000, text.length)
        val store = start(Store(oldText = text, count = 30), narrow = true); open(); history()
        assertCenteredHistory("history-list", 320f)
        val list = compose.onNodeWithTag("history-list")
        list.performScrollToNode(hasText("阅读版本 15"))
        val anchor = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(); assertTrue(anchor > 0f)
        compose.onNodeWithText("阅读版本 15").performClick(); loaded()
        compose.runOnIdle { back.onBackPressed() }; loaded()
        assertEquals(anchor, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
        revision(1); assertEquals(text, full())
        val scroll = compose.onNodeWithTag("original-scroll")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("original-text").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single(); assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue(layout.lineCount >= 25_000)
        val before = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        scroll.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { assertTrue(it(0f, before.maxValue())) }
        compose.waitForIdle()
        val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.value() > 0f); assertEquals(range.maxValue(), range.value(), 1f)
        val lastTop = layout.getLineTop(layout.lineCount - 1) - range.value()
        val lastBottom = layout.getLineBottom(layout.lineCount - 1) - range.value()
        val viewportHeight = scroll.fetchSemanticsNode().boundsInRoot.height
        assertTrue(lastTop >= -1f && lastBottom <= viewportHeight + 1f)
        compose.onNodeWithText("复制全文").performClick(); assertEquals(text, copied())
        store.failHistory = true
        compose.runOnIdle { back.onBackPressed() }; loaded()
        assertCenteredHistory("history-status", 320f)
        compose.onNodeWithTag("history-retry").performScrollTo().assertIsDisplayed()
        store.failHistory = false
        compose.onNodeWithTag("history-retry").performClick(); loaded()
        assertCenteredHistory("history-list", 320f)
        assertEquals(0, store.reviseCalls)
    }

    @Test fun lateCaptureFailureCanRecoverWhileViewingHistoryWithoutLosingEitherInput() {
        val store = start(Store(delayCaptureFailure = true), destination = Destination.NOW)
        compose.onNode(hasSetTextAction()).performTextReplacement("合成排队的此刻草稿")
        compose.waitUntil(5000) { store.captureStarted.isCompleted }
        compose.onNodeWithText("回忆").performClick(); open(); compose.onNodeWithText("编辑原文").performClick()
        compose.onNodeWithTag("original-edit-text").performTextReplacement("合成原文修改")
        compose.onNodeWithTag("original-edit-history").performScrollTo().performClick()
        store.releaseCapture.complete(Unit); loaded(); compose.waitUntil(5000) { vm.state.value.error != null }; compose.waitForIdle()
        compose.onAllNodesWithTag("history-list").assertCountEquals(0)
        compose.onNodeWithTag("history-retry").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.state.value.error == null && !vm.state.value.opening }; loaded()
        assertEquals(2, store.historyReadCalls)
        compose.onNodeWithText("返回编辑").performClick(); assertEquals("合成原文修改", input())
        assertEquals("合成排队的此刻草稿", vm.state.value.text); assertEquals("合成排队的此刻草稿", store.draft.text)
        assertEquals(0, store.reviseCalls)
    }

    @Test fun missingSelectedRevisionAndBusySaveNeverOfferWrongOriginalOrHistory() {
        val store = start(Store(delayRevision = true)); open(); val reader = callbacks
        history(); store.versions.removeAt(0); revision(1)
        compose.onAllNodesWithTag("original-text").assertCountEquals(0)
        assertTrue(vm.state.value.originalHistory!!.unavailable)
        compose.runOnIdle { back.onBackPressed() }; loaded(); compose.onNodeWithText("返回原文").performClick()
        compose.onNodeWithText("编辑原文").performClick()
        compose.onNodeWithTag("original-edit-text").performTextReplacement("合成保存中修改")
        compose.onNodeWithTag("original-edit-save").performScrollTo().performClick()
        compose.waitUntil(5000) { store.revisionStarted.isCompleted }
        compose.onNodeWithTag("original-edit-history").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { reader.beginHistory("original", 2) }
        assertNull(vm.state.value.originalHistory)
        store.releaseRevision.complete(Unit); compose.waitUntil(5000) { vm.state.value.originalEdit == null }; compose.waitForIdle()
        assertEquals("合成保存中修改", full()); assertEquals(1, store.reviseCalls)
    }

    private class Store(oldText: String = "合成旧原文🌿", count: Int = 2,
        private val delayFirstHistory: Boolean = false, private val delayRevision: Boolean = false,
        private val delayCaptureFailure: Boolean = false) : EntryRepository {
        val versions = (1..count).map { OriginalRevision("original", it, if (it == 1) oldText else if (it == count) "合成当前原文" else "合成原文 $it",
            "synthetic", 1_791_647_400_000 + it) }.toMutableList()
        @Volatile var current = StoredEntry("original", count, versions.last().originalText, 1_791_647_400_000, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY)
        @Volatile var draft = TextDraft("capture", 1, "合成此刻草稿")
        @Volatile var failHistory = false
        @Volatile var deleted = false
        @Volatile var reviseCalls = 0
        @Volatile var historyReadCalls = 0
        private var captureCalls = 0
        val historyStarted = CompletableDeferred<Unit>(); val releaseHistory = CompletableDeferred<Unit>()
        val revisionStarted = CompletableDeferred<Unit>(); val releaseRevision = CompletableDeferred<Unit>()
        val captureStarted = CompletableDeferred<Unit>(); val releaseCapture = CompletableDeferred<Unit>()
        private val others = (0..22).map { current.copy(id = "other-$it", currentRevision = 1, originalText = "合成记录 $it " + "叶".repeat(240)) }
        fun advance(text: String) {
            current = current.copy(currentRevision = current.currentRevision + 1, originalText = text)
            versions.add(OriginalRevision(current.id, current.currentRevision, text, "synthetic", current.capturedAtMillis))
        }
        override suspend fun readDraft() = draft
        override suspend fun updateDraft(text: String): TextDraft {
            if (++captureCalls == 1 && delayCaptureFailure) { captureStarted.complete(Unit); releaseCapture.await(); error("synthetic draft failure") }
            return draft.copy(version = draft.version + 1, text = text).also { draft = it }
        }
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("history must not save capture")
        override suspend fun entries() = others.take(15) + (if (deleted) emptyList() else listOf(current)) + others.drop(15)
        override suspend fun revisions(entryId: String): List<OriginalRevision> {
            if (++historyReadCalls == 1 && delayFirstHistory) { historyStarted.complete(Unit); releaseHistory.await() }
            if (failHistory) error("synthetic history read failure")
            return versions.toList()
        }
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry {
            reviseCalls++; revisionStarted.complete(Unit); if (delayRevision) releaseRevision.await()
            if (deleted || entryId != current.id || expectedRevision != current.currentRevision) throw RecordConflict()
            advance(text); return current
        }
    }
}
