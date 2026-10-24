package com.t1doo.aiyou

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.t1doo.aiyou.designsystem.AIyouTheme
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
import androidx.compose.foundation.text.selection.SelectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual production navigation/VM/Compose; synthetic persistence, never device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
class HistoryEditPresentationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var vm: LocalRecordsViewModel
    private lateinit var callbacks: OriginalReadingCallbacks
    private lateinit var back: OnBackPressedDispatcher
    private var mounted by mutableStateOf(true)
    private val observer = object : OriginalReadingObserver {
        override fun onNavigation(callbacks: OriginalReadingCallbacks) { this@HistoryEditPresentationTest.callbacks = callbacks }
        override fun onClipboard(clipboard: Clipboard) = Unit
        override fun onSelection(selection: SelectionState) = Unit
    }
    @After fun close() { if (::vm.isInitialized) vm.viewModelScope.cancel() }
    private fun start(store: Store = Store(), narrow: Boolean = false): Store {
        vm = LocalRecordsViewModel(ApplicationProvider.getApplicationContext<Application>(), { store })
        compose.setContent {
            val local by vm.state.collectAsState()
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            val density = LocalDensity.current
            CompositionLocalProvider(LocalOriginalReadingObserver provides observer,
                LocalDensity provides if (narrow) Density(density.density, 2f) else density) {
                Box(if (narrow) Modifier.size(320.dp, 640.dp) else Modifier) {
                    if (mounted) AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES, local = local,
                        onDraftChange = vm::changeText, onSave = vm::save, onRetry = vm::retry,
                        onBeginOriginalEdit = vm::beginOriginalEdit, onOriginalEditChange = vm::changeOriginalEdit,
                        onOriginalEditSave = vm::saveOriginalEdit, onOriginalEditCancel = vm::cancelOriginalEdit,
                        onBeginOriginalHistory = vm::beginOriginalHistory, onSelectOriginalRevision = vm::selectOriginalRevision,
                        onBackOriginalHistory = vm::backOriginalHistory, onRetryOriginalHistory = vm::retryOriginalHistory,
                        onEditOriginalHistory = vm::editOriginalHistory, onConfirmHistoryReplacement = vm::confirmHistoryReplacement,
                        onCancelHistoryReplacement = vm::cancelHistoryReplacement) }
                }
            }
        }
        compose.waitUntil(5000) { vm.state.value.ready }; compose.waitForIdle()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-original"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-original"))).performClick()
        return store
    }
    private fun loaded() { compose.waitUntil(5000) { vm.state.value.originalHistory?.loading == false }; compose.waitForIdle() }
    private fun oldVersion(fromEditor: Boolean = false) {
        if (fromEditor) compose.onNodeWithTag("original-edit-history").performScrollTo().performClick()
        else compose.originalToolText("历史版本").performClick()
        loaded()
        compose.onNodeWithTag("history-list").performScrollToNode(hasText("阅读版本 1"))
        compose.onNodeWithText("阅读版本 1").performClick(); loaded()
    }
    private fun use() { compose.originalTool("original-reading-edit").assertIsDisplayed().performClick(); compose.waitForIdle() }
    private fun input() = compose.onNodeWithTag("original-edit-text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun save() { compose.onNodeWithTag("original-edit-save").performScrollTo().performClick() }
    private fun saved() { compose.waitUntil(5000) { vm.state.value.originalEdit == null }; compose.waitForIdle() }
    private fun draftUnchanged(store: Store) {
        assertEquals(TextDraft("capture", 7, "合成独立此刻草稿"), store.draft)
        assertEquals(store.draft.text, vm.state.value.text); assertEquals(0, store.captureWrites)
    }

    @Test fun historicalTextLoadsWithoutWritesAndSaveAppendsOneImmutableVersion() {
        val store = start(Store(delaySave = true)); val old = store.versions.toList(); oldVersion(); use()
        assertEquals("合成旧原文🌿", input()); assertEquals(0, store.reviseCalls)
        compose.onNodeWithText("来源：历史版本 1", substring = true).assertIsDisplayed()
        assertEquals(2, vm.state.value.originalEdit!!.baseRevision); assertEquals(1, vm.state.value.originalEdit!!.sourceRevision)
        draftUnchanged(store); val editorCallbacks = callbacks; val id = vm.state.value.originalEdit!!.sessionId
        save(); compose.waitUntil(5000) { store.saveStarted.isCompleted }
        compose.runOnIdle { editorCallbacks.saveEdit(id); editorCallbacks.cancelEdit(id); editorCallbacks.changeEdit(id, "迟到") }
        assertEquals("合成旧原文🌿", input()); assertEquals(1, store.reviseCalls)
        store.releaseSave.complete(Unit); saved()
        assertEquals(old, store.versions.take(2)); assertEquals(3, store.current.currentRevision)
        compose.onNodeWithTag("original-text").assertIsDisplayed()
        assertEquals("合成旧原文🌿", store.current.originalText); draftUnchanged(store)
    }

    @Test fun replacementCancelBackAndExplicitConfirmationPreserveIndependentDraft() {
        val store = start(); compose.originalToolText("编辑原文").performClick()
        compose.onNodeWithTag("original-edit-text").performTextReplacement("合成未保存修改\n🌿")
        val previous = vm.state.value.originalEdit!!; oldVersion(fromEditor = true); use()
        compose.onNodeWithText("替换未保存的修改？").assertIsDisplayed()
        compose.onNodeWithTag("history-replace-cancel").performClick(); compose.waitForIdle()
        assertEquals(previous, vm.state.value.originalEdit); assertNull(vm.state.value.originalHistory!!.replacement)
        use(); val rejected = callbacks; val pending = vm.state.value.originalHistory!!.replacement!!.id
        compose.runOnIdle { back.onBackPressed() }; compose.waitForIdle()
        assertEquals(previous, vm.state.value.originalEdit); assertNull(vm.state.value.originalHistory!!.replacement)
        compose.runOnIdle { rejected.confirmHistoryReplacement(pending) }
        assertEquals(previous, vm.state.value.originalEdit)
        use(); compose.onNodeWithTag("history-replace-confirm").performClick(); compose.waitForIdle()
        assertNotEquals(previous.sessionId, vm.state.value.originalEdit!!.sessionId)
        assertEquals("合成旧原文🌿", input()); assertEquals(0, store.reviseCalls); draftUnchanged(store)
    }

    @Test fun retainedHistoryActionsRejectChangedSelectionClosedReopenedAndDisposedNavigation() {
        val store = start(); oldVersion(); val old = callbacks
        compose.runOnIdle { back.onBackPressed() }; loaded()
        compose.onNodeWithText("阅读版本 2").performClick(); loaded()
        compose.runOnIdle { old.editHistory(); old.confirmHistoryReplacement(1); old.cancelHistoryReplacement(1) }
        assertNull(vm.state.value.originalEdit)
        compose.runOnIdle { back.onBackPressed() }; loaded()
        compose.onNodeWithText("返回原文").performClick(); oldVersion()
        compose.runOnIdle { old.editHistory() }; assertNull(vm.state.value.originalEdit)
        val beforeDisposal = callbacks; compose.runOnIdle { mounted = false }; compose.waitForIdle()
        compose.runOnIdle { beforeDisposal.editHistory() }; assertNull(vm.state.value.originalEdit)
        assertEquals(0, store.reviseCalls); draftUnchanged(store)
    }

    @Test fun concurrentNewVersionShowsConflictAndRetainsHistorySourceAndUserInput() {
        val store = start(Store(delaySave = true)); oldVersion(); use(); save()
        compose.waitUntil(5000) { store.saveStarted.isCompleted }
        store.advance("合成并发新版本"); store.releaseSave.complete(Unit)
        compose.waitUntil(5000) { vm.state.value.originalEdit?.conflict == true }; compose.waitForIdle()
        assertEquals("合成旧原文🌿", input()); assertEquals(1, vm.state.value.originalEdit!!.sourceRevision)
        compose.onNodeWithText("来源：历史版本 1 · 基于原文版本 2 · 仅本机").assertIsDisplayed()
        compose.onNodeWithText("原文已变化").assertIsDisplayed()
        compose.onNodeWithTag("original-edit-save").performScrollTo().assertIsNotEnabled()
        assertEquals(listOf("合成旧原文🌿", "合成当前原文", "合成并发新版本"), store.versions.map { it.originalText })
        assertEquals(1, store.reviseCalls); draftUnchanged(store)
    }

    @Test fun failedSaveRetainsLoadedHistoryAndUserCorrectionCanRetry() {
        val store = start(Store(failFirstSave = true)); val old = store.versions.toList(); oldVersion(); use(); save()
        compose.waitUntil(5000) { vm.state.value.originalEdit?.error != null }; compose.waitForIdle()
        assertEquals("合成旧原文🌿", input()); assertEquals(old, store.versions)
        compose.onNodeWithTag("original-edit-text").performTextReplacement("合成以旧版本继续补充🌿")
        save(); saved(); assertEquals(2, store.reviseCalls)
        assertEquals(old, store.versions.take(2)); assertEquals("合成以旧版本继续补充🌿", store.current.originalText)
        draftUnchanged(store)
    }

    @Test fun committedHistorySaveReadbackFailureRetriesReadingWithoutAnotherWrite() {
        val store = start(Store(failReadback = true)); val old = store.versions.toList(); oldVersion(); use(); save()
        compose.waitUntil(5000) { vm.state.value.originalEdit?.awaitingReadback == true && vm.state.value.originalEdit?.saving == false }
        compose.waitForIdle(); assertEquals("合成旧原文🌿", input())
        compose.onNodeWithText("重试读取").assertIsDisplayed()
        assertEquals(1, store.reviseCalls); assertEquals(old, store.versions.take(2))
        save(); saved(); assertEquals(1, store.reviseCalls); assertEquals(3, store.versions.size); draftUnchanged(store)
    }

    @Test fun textMatchingCurrentOriginalDisablesSaveAndCreatesNoRevision() {
        val store = start(Store(oldText = "合成当前原文")); oldVersion(); use()
        compose.onNodeWithText("文本与当前原文相同，无需保存新版本。").assertIsDisplayed()
        compose.onNodeWithTag("original-edit-save").performScrollTo().assertIsNotEnabled()
        val id = vm.state.value.originalEdit!!.sessionId
        compose.runOnIdle { callbacks.saveEdit(id) }; assertEquals(0, store.reviseCalls)
        compose.onNodeWithTag("original-edit-cancel").performClick(); compose.waitForIdle()
        assertNull(vm.state.value.originalEdit); draftUnchanged(store)
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun narrowLargeFontFullUnicodeSourceAndReplacementPrimaryActionsRemainReachable() {
        val text = "界🌿\n".repeat(24_999) + "尾🌿终"; assertEquals(100_000, text.length)
        val store = start(Store(oldText = text), narrow = true)
        compose.originalToolText("编辑原文").performClick()
        compose.onNodeWithTag("original-edit-text").performTextReplacement("合成未保存输入")
        oldVersion(fromEditor = true); use()
        compose.onNodeWithTag("history-replace-cancel").assertIsDisplayed()
        compose.onNodeWithTag("history-replace-confirm").assertIsDisplayed().performClick(); compose.waitForIdle()
        assertEquals(text, input()); assertEquals(100_000, vm.state.value.originalEdit!!.text.length)
        compose.onNodeWithTag("original-edit-save").performScrollTo().assertIsDisplayed().performClick(); saved()
        assertEquals(text, store.current.originalText); assertEquals(1, store.reviseCalls)
        assertEquals(text, store.versions.first().originalText); draftUnchanged(store)
    }

    private class Store(oldText: String = "合成旧原文🌿", private val delaySave: Boolean = false,
        private val failFirstSave: Boolean = false, private val failReadback: Boolean = false) : EntryRepository {
        val versions = mutableListOf(OriginalRevision("original", 1, oldText, "synthetic", 1_791_647_400_000),
            OriginalRevision("original", 2, "合成当前原文", "synthetic", 1_791_647_400_001))
        @Volatile var current = StoredEntry("original", 2, "合成当前原文", 1_791_647_400_000, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY)
        val draft = TextDraft("capture", 7, "合成独立此刻草稿")
        @Volatile var reviseCalls = 0
        @Volatile var captureWrites = 0
        private var readbackFailed = false
        val saveStarted = CompletableDeferred<Unit>(); val releaseSave = CompletableDeferred<Unit>()
        fun advance(text: String) {
            current = current.copy(currentRevision = current.currentRevision + 1, originalText = text)
            versions.add(OriginalRevision(current.id, current.currentRevision, text, "synthetic", current.capturedAtMillis))
        }
        override suspend fun readDraft() = draft
        override suspend fun updateDraft(text: String): TextDraft { captureWrites++; error("history editor must not write capture") }
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry {
            captureWrites++; error("history editor must not save capture")
        }
        override suspend fun entries(): List<StoredEntry> {
            if (failReadback && current.currentRevision == 3 && !readbackFailed) { readbackFailed = true; error("synthetic committed readback failure") }
            return listOf(current)
        }
        override suspend fun revisions(entryId: String) = versions.toList()
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry {
            reviseCalls++; saveStarted.complete(Unit); if (delaySave) releaseSave.await()
            if (failFirstSave && reviseCalls == 1) error("synthetic precommit failure")
            if (entryId != current.id || expectedRevision != current.currentRevision) throw RecordConflict()
            advance(text); return current
        }
    }
}
