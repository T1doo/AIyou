package com.t1doo.aiyou

import android.app.Application
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.t1doo.aiyou.designsystem.AIyouDimensions
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual App -> actual ViewModel -> controlled repository boundary. No native persistence claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
class OriginalEditPresentationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var vm: LocalRecordsViewModel
    private lateinit var callbacks: OriginalReadingCallbacks
    private lateinit var back: OnBackPressedDispatcher
    private val observer = object : OriginalReadingObserver {
        override fun onNavigation(callbacks: OriginalReadingCallbacks) { this@OriginalEditPresentationTest.callbacks = callbacks }
        override fun onClipboard(clipboard: Clipboard) = Unit
        override fun onSelection(selection: SelectionState) = Unit
    }
    @After fun close() { if (::vm.isInitialized) vm.viewModelScope.cancel() }

    private fun start(store: ControlledStore = ControlledStore(), narrow: Boolean = false, restoration: StateRestorationTester? = null,
        destination: Destination = Destination.MEMORIES): ControlledStore {
        vm = LocalRecordsViewModel(ApplicationProvider.getApplicationContext<Application>(), { store },
            Clock.fixed(Instant.ofEpochMilli(1_791_647_400_000), ZoneOffset.UTC))
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            val local by vm.state.collectAsState()
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            val density = LocalDensity.current
            CompositionLocalProvider(LocalOriginalReadingObserver provides observer,
                LocalDensity provides if (narrow) Density(density.density, 2f) else density) {
                Box(if (narrow) Modifier.size(320.dp, 640.dp).testTag("edit-viewport") else Modifier) {
                    AIyouTheme { AIyouApp(previewDestination = destination, local = local,
                        onDraftChange = vm::changeText, onSave = vm::save, onRetry = vm::retry,
                        onBeginOriginalEdit = vm::beginOriginalEdit, onOriginalEditChange = vm::changeOriginalEdit,
                        onOriginalEditSave = vm::saveOriginalEdit, onOriginalEditCancel = vm::cancelOriginalEdit) }
                }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitUntil(5000) { vm.state.value.ready }
        compose.waitForIdle()
        return store
    }
    private fun open() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-original"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-original"))).performClick()
    }
    private fun edit() = compose.onNodeWithText("编辑原文").performClick()
    private fun replace(text: String) = compose.onNodeWithTag("original-edit-text").performTextReplacement(text)
    private fun save() = compose.onNodeWithTag("original-edit-save").performScrollTo().performClick()
    private fun cancel() = compose.onNodeWithTag("original-edit-cancel").performScrollTo().performClick()
    private fun edited() = compose.onNodeWithTag("original-edit-text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun original() = compose.onNodeWithTag("original-text").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun waitFailure() { compose.waitUntil(5000) { vm.state.value.originalEdit?.error != null }; compose.waitForIdle() }
    private fun waitSaved() { compose.waitUntil(5000) { vm.state.value.originalEdit == null && vm.state.value.originalEditNotice != null }; compose.waitForIdle() }

    @Test fun readingEditSaveReturnsToNewOriginalAndPreservesCaptureDraftAndListAnchor() {
        val store = start()
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasTestTag("entry-original"))
        val anchor = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        open(); edit(); replace("合成保存的新版本🌿"); save(); waitSaved()
        assertEquals("合成保存的新版本🌿", original())
        compose.onNodeWithText("新版本已保存在本机").assertIsDisplayed()
        assertEquals(listOf("合成原文", "合成保存的新版本🌿"), store.history.map { it.originalText })
        assertEquals("合成此刻草稿", vm.state.value.text)
        assertEquals("合成此刻草稿", store.draft.text)
        compose.onNodeWithText("返回列表").performClick()
        assertEquals(anchor, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
        assertTrue(compose.onNodeWithTag("entry-original").fetchSemanticsNode().config[SemanticsProperties.Selected])
    }

    @Test fun cancelAndSystemBackRequireDiscardAndKeepOriginalWithoutWrites() {
        val store = start()
        open(); edit(); replace("合成未提交的修改")
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("放弃这次修改？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        assertEquals("合成未提交的修改", edited())
        cancel(); compose.onNodeWithText("放弃修改").performClick()
        assertEquals("合成原文", original())
        assertEquals(0, store.reviseCalls)
        assertEquals(1, store.history.size)
        assertEquals("合成此刻草稿", vm.state.value.text)
        edit(); cancel()
        assertEquals("合成原文", original())
        assertEquals(0, store.reviseCalls)
    }

    @Test fun busySaveRejectsCancelInputAndRetainedReaderCopyUntilCommit() {
        val store = start(ControlledStore(delayRevision = true))
        open()
        val reader = callbacks
        edit(); replace("合成正在提交的版本")
        val active = callbacks
        val session = vm.state.value.originalEdit!!.sessionId
        save()
        compose.waitUntil(5000) { store.revisionStarted.isCompleted }
        compose.onNodeWithTag("original-edit-save").assertIsNotEnabled()
        compose.onNodeWithTag("original-edit-cancel").assertIsNotEnabled()
        compose.onAllNodesWithText("取消编辑")[0].assertIsNotEnabled()
        compose.runOnIdle {
            assertFalse(reader.copy("original", 1))
            reader.beginEdit("original", 1)
            active.changeEdit(session, "合成迟到输入"); active.cancelEdit(session); active.saveEdit(session)
            back.onBackPressed()
        }
        assertEquals("合成正在提交的版本", edited())
        assertEquals("合成原文", vm.state.value.entries.first { it.id == "original" }.originalText)
        assertNull(vm.state.value.originalEditNotice)
        store.releaseRevision.complete(Unit); waitSaved()
        assertEquals("合成正在提交的版本", original())
        assertEquals(1, store.reviseCalls)
    }

    @Test fun failedSaveKeepsEditsAndUserCorrectionRetriesWithoutFalseSuccess() {
        val store = start(ControlledStore(failRevision = true))
        open(); edit(); replace("合成第一次修改"); save(); waitFailure()
        assertEquals("合成第一次修改", edited())
        compose.onNodeWithText("修改尚未保存").performScrollTo().assertIsDisplayed()
        assertNull(vm.state.value.originalEditNotice)
        assertEquals(1, store.history.size)
        replace("合成失败后用户纠正的修改"); save(); waitSaved()
        assertEquals("合成失败后用户纠正的修改", original())
        assertEquals(2, store.reviseCalls)
        assertEquals(2, store.history.size)
    }

    @Test fun newerOrDeletedSourceNeverReplacesUserInputOrCreatesReplacement() {
        val store = start()
        open(); edit(); replace("合成用户保留的修改")
        store.current = store.current.copy(currentRevision = 2, originalText = "合成外部较新版本")
        save(); waitFailure()
        assertEquals("合成用户保留的修改", edited())
        compose.onNodeWithText("原文已变化").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("original-edit-save").performScrollTo().assertIsNotEnabled()
        cancel(); compose.onNodeWithText("放弃修改").performClick()
        assertEquals("合成外部较新版本", original())
        edit(); replace("合成记录消失后的修改")
        store.deleted = true
        save(); waitFailure()
        assertEquals("合成记录消失后的修改", edited())
        assertEquals(2, store.reviseCalls)
        assertEquals(1, store.history.size)
        cancel(); compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithText("这条记录已不可用").assertIsDisplayed()
    }

    @Test fun committedReadbackFailureRetriesReadOnlyWithoutAnotherRevision() {
        val store = start(ControlledStore(failReadback = true))
        open(); edit(); replace("合成已提交待确认版本"); save(); waitFailure()
        compose.onNodeWithText("读取确认需要重试").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("original-edit-cancel").performScrollTo().assertIsNotEnabled()
        compose.onAllNodesWithText("取消编辑")[0].assertIsNotEnabled()
        assertEquals("合成已提交待确认版本", edited())
        assertTrue(vm.state.value.originalEdit!!.awaitingReadback)
        assertNull(vm.state.value.originalEditNotice)
        assertEquals(2, store.history.size)
        save(); waitSaved()
        assertEquals("合成已提交待确认版本", original())
        assertEquals(1, store.reviseCalls)
    }

    @Test fun lateCaptureWriteFailureRetriesStorageWithoutLosingEitherInput() {
        val store = start(ControlledStore(failPriorEdit = true), destination = Destination.NOW)
        compose.onNode(hasSetTextAction()).performTextReplacement("合成排队中的此刻草稿")
        compose.waitUntil(5000) { store.firstEditStarted.isCompleted }
        compose.onNodeWithText("回忆").performClick()
        open()
        val reader = callbacks
        edit(); replace("合成正在编辑的原文修改")
        store.releaseFirstEdit.complete(Unit)
        compose.waitUntil(5000) { vm.state.value.error != null }
        compose.waitForIdle()
        assertEquals("合成正在编辑的原文修改", edited())
        compose.onNodeWithTag("original-edit-save").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { reader.retry() }
        assertNotNull(vm.state.value.error)
        replace("合成存储失败后继续纠正的修改")
        compose.onNodeWithTag("original-edit-storage-retry").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.state.value.error == null && !vm.state.value.opening }
        compose.waitForIdle()
        assertEquals("合成存储失败后继续纠正的修改", edited())
        assertEquals("合成排队中的此刻草稿", vm.state.value.text)
        assertEquals("合成排队中的此刻草稿", store.draft.text)
        assertEquals(0, store.reviseCalls)
        val draft = store.draft
        save(); waitSaved()
        assertEquals("合成存储失败后继续纠正的修改", original())
        assertEquals(draft, store.draft)
        assertEquals(1, store.reviseCalls)
        assertEquals(2, store.history.size)
    }

    @Test fun reopenedAndDisposedEditorRejectsRetainedSessionCallbacks() {
        val restoration = StateRestorationTester(compose)
        val store = start(restoration = restoration)
        open(); edit(); replace("合成第一会话修改")
        val old = callbacks
        val oldSession = vm.state.value.originalEdit!!.sessionId
        cancel(); compose.onNodeWithText("放弃修改").performClick()
        edit(); replace("合成第二会话修改")
        val session = vm.state.value.originalEdit!!.sessionId
        compose.runOnIdle {
            old.changeEdit(oldSession, "合成迟到输入"); old.saveEdit(oldSession); old.cancelEdit(oldSession); old.discardEdit(oldSession)
        }
        assertEquals("合成第二会话修改", edited())
        val beforeDispose = callbacks
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            beforeDispose.changeEdit(session, "合成卸载后输入"); beforeDispose.saveEdit(session)
            beforeDispose.cancelEdit(session); beforeDispose.discardEdit(session)
        }
        assertEquals("合成第二会话修改", edited())
        assertEquals(0, store.reviseCalls)
        save(); waitSaved()
        assertEquals("合成第二会话修改", original())
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun narrowLargeFontBoundaryInputAndPrimaryActionsRemainReachable() {
        val store = start(narrow = true)
        open(); edit()
        val boundary = "叶".repeat(99_998) + "🌿"
        replace(boundary)
        replace(boundary + "🌿")
        assertEquals(boundary, edited())
        assertNotNull(vm.state.value.originalEdit!!.inputNotice)
        replace(boundary)
        val action = compose.onNodeWithTag("original-edit-save").performScrollTo().assertIsDisplayed()
        val bounds = action.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("edit-viewport").getUnclippedBoundsInRoot()
        assertTrue(bounds.height >= AIyouDimensions.minTouchTarget)
        assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        action.performClick(); waitSaved()
        assertEquals(boundary, original())
        assertEquals(boundary, store.history.last().originalText)
        assertEquals("合成此刻草稿", vm.state.value.text)
    }

    private class ControlledStore(
        private val delayRevision: Boolean = false,
        private val failRevision: Boolean = false,
        private val failReadback: Boolean = false,
        private val failPriorEdit: Boolean = false,
    ) : EntryRepository {
        @Volatile var current = StoredEntry("original", 1, "合成原文", 1_791_647_400_000, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY)
        @Volatile var deleted = false
        @Volatile var reviseCalls = 0
        private var readbackFailed = false
        val revisionStarted = CompletableDeferred<Unit>()
        val releaseRevision = CompletableDeferred<Unit>()
        @Volatile var draft = TextDraft("capture", 1, "合成此刻草稿")
        val firstEditStarted = CompletableDeferred<Unit>()
        val releaseFirstEdit = CompletableDeferred<Unit>()
        private var editCalls = 0
        val history = mutableListOf(OriginalRevision(current.id, 1, current.originalText, "synthetic hash", current.capturedAtMillis))
        private val others = (0..22).map { current.copy(id = "other-$it", originalText = "合成其他原文 $it") }
        override suspend fun readDraft() = draft
        override suspend fun updateDraft(text: String): TextDraft {
            check(failPriorEdit) { "Editor must not write capture draft" }
            if (++editCalls == 1) {
                firstEditStarted.complete(Unit); releaseFirstEdit.await(); error("synthetic prior capture write failure")
            }
            return draft.copy(version = draft.version + 1, text = text).also { draft = it }
        }
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Editor must not create another record")
        override suspend fun entries(): List<StoredEntry> {
            if (failReadback && reviseCalls > 0 && current.currentRevision > 1 && !readbackFailed) {
                readbackFailed = true; error("synthetic readback failure")
            }
            return others + if (deleted) emptyList() else listOf(current)
        }
        override suspend fun revisions(entryId: String) = history.toList()
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry {
            reviseCalls++; revisionStarted.complete(Unit)
            if (delayRevision) releaseRevision.await()
            if (failRevision && reviseCalls == 1) error("synthetic precommit failure")
            if (deleted || entryId != current.id || expectedRevision != current.currentRevision) throw RecordConflict()
            current = current.copy(currentRevision = expectedRevision + 1, originalText = text)
            history.add(OriginalRevision(entryId, current.currentRevision, text, "synthetic hash", createdAtMillis))
            return current
        }
    }
}
