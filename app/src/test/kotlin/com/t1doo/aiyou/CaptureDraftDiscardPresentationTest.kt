package com.t1doo.aiyou

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.lifecycle.ViewModelStore
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.LocalRecordsViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Production app + ViewModel and real Compose interactions. Repository is synthetic; no native claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
class CaptureDraftDiscardPresentationTest {
    @get:Rule val compose = createComposeRule()
    private class Store : EntryRepository {
        var draft: TextDraft? = TextDraft("synthetic-restored", 1, "合成恢复草稿")
        var deletes = 0
        var failOnce = false
        var failAfterDelete = false
        var failRecovery = false
        var recoveries = 0
        override suspend fun readDraft() = draft
        override suspend fun updateDraft(text: String) = TextDraft(draft?.id ?: "synthetic-new", (draft?.version ?: 0) + 1, text).also { draft = it }
        override suspend fun discardDraft(expected: TextDraft) {
            deletes++
            if (failOnce) { failOnce = false; error("synthetic IO failure") }
            if (draft != null && draft != expected) throw RecordConflict()
            draft = null
            if (failAfterDelete) { failAfterDelete = false; error("synthetic lost acknowledgement") }
        }
        override suspend fun restoreDiscardedDraft(expected: TextDraft, replacement: TextDraft): TextDraft {
            recoveries++
            if (failRecovery) { failRecovery = false; error("synthetic recovery unavailable") }
            if (draft == expected || draft == replacement) return checkNotNull(draft)
            if (draft != null) throw RecordConflict()
            draft = replacement
            return replacement
        }
        override suspend fun entries() = emptyList<StoredEntry>()
        override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Unexpected save")
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Unexpected revision")
    }
    private fun runPath(large: Boolean = false, fail: Boolean = false) {
        val store = Store().also { it.failOnce = fail }
        val lifecycle = ViewModelStore()
        val app = RuntimeEnvironment.getApplication() as Application
        val vm = LocalRecordsViewModel(app, { store })
        lifecycle.put("capture", vm)
        try {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 2f else 1f)) {
                    AIyouTheme { AIyouApp(local = vm.state.collectAsState().value,
                        onDraftChange = vm::changeText, onSave = vm::save, onRetry = vm::retry,
                        onRequestCaptureDiscard = vm::requestCaptureDiscard,
                        onConfirmCaptureDiscard = vm::confirmCaptureDiscard,
                        onCancelCaptureDiscard = vm::cancelCaptureDiscard) }
                }
            }
            compose.waitUntil(5000) { vm.state.value.ready }
            val list = compose.onNode(hasScrollToIndexAction())
            list.performScrollToNode(hasText("清空草稿"))
            compose.onNodeWithText("清空草稿").assertIsDisplayed().performClick()
            compose.onNodeWithText("保留草稿").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(0, store.deletes); assertEquals("合成恢复草稿", vm.state.value.text) }
            compose.onNodeWithText("清空草稿").performClick()
            val confirm = compose.onNodeWithText("确认清空")
            confirm.assertIsDisplayed().assertIsEnabled()
            assertTrue(confirm.getUnclippedBoundsInRoot().height >= 48.dp)
            confirm.performClick()
            if (fail) {
                compose.waitUntil(5000) { vm.state.value.captureDiscard?.error != null }
                compose.runOnIdle { assertEquals("合成恢复草稿", vm.state.value.text); assertNotNull(store.draft) }
                compose.onNodeWithText("重试确认").assertIsDisplayed().assertIsEnabled().performClick()
            }
            compose.waitUntil(5000) { vm.state.value.captureDiscard == null && vm.state.value.text.isEmpty() }
            compose.runOnIdle { assertNull(store.draft); assertEquals(if (fail) 2 else 1, store.deletes) }
            list.performScrollToNode(hasContentDescription("此刻，想记下什么"))
            compose.onNodeWithContentDescription("此刻，想记下什么").assertIsEnabled().performTextReplacement("合成清空后的新输入")
            compose.waitUntil(5000) { vm.state.value.draftNotice == "草稿已保存在本机" }
            compose.runOnIdle { assertEquals("synthetic-new", store.draft!!.id); assertEquals("合成清空后的新输入", store.draft!!.text) }
        } finally { compose.runOnIdle { lifecycle.clear() } }
    }
    @Test fun restoredCaptureDraftExposesExplicitDiscard() = runPath()
    @Test @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun narrowLargeFontConfirmCancelAndFailedDeleteRetryRemainReachable() = runPath(large = true, fail = true)
    @Test @Config(sdk = [35], qualifiers = "w800dp-h360dp")
    fun landscapeConfirmCancelAndFreshInputRemainReachable() = runPath()

    @Test @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun returningFromUnacknowledgedDeleteRetainsInputBeforeClosingAtLargeFont() = runReturnPath(true, false)

    @Test @Config(sdk = [35], qualifiers = "w800dp-h360dp")
    fun failedRetentionIsExplicitAndRetryKeepsInputWithoutAnotherDelete() = runReturnPath(false, true)

    private fun runReturnPath(large: Boolean, recoveryFailure: Boolean) {
        val store = Store().also { it.failAfterDelete = true; it.failRecovery = recoveryFailure }
        val lifecycle = ViewModelStore()
        val vm = LocalRecordsViewModel(RuntimeEnvironment.getApplication(), { store })
        lifecycle.put("return", vm)
        try {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 2f else 1f)) {
                    AIyouTheme { AIyouApp(local = vm.state.collectAsState().value,
                        onRequestCaptureDiscard = vm::requestCaptureDiscard,
                        onConfirmCaptureDiscard = vm::confirmCaptureDiscard,
                        onCancelCaptureDiscard = vm::cancelCaptureDiscard) }
                }
            }
            compose.waitUntil(5000) { vm.state.value.ready }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("清空草稿"))
            compose.onNodeWithText("清空草稿").performClick()
            compose.onNodeWithText("确认清空").performClick()
            compose.waitUntil(5000) { vm.state.value.captureDiscard?.error != null }
            compose.runOnIdle { assertNull(store.draft); assertTrue(vm.state.value.draftNotice.contains("未确认")) }
            val keep = compose.onNodeWithText("保留输入并返回")
            keep.assertIsDisplayed().assertIsEnabled()
            val bounds = keep.getUnclippedBoundsInRoot()
            val dialog = compose.onNode(isDialog()).getUnclippedBoundsInRoot()
            assertTrue(bounds.height >= 48.dp && bounds.left >= dialog.left && bounds.right <= dialog.right && bounds.top >= dialog.top && bounds.bottom <= dialog.bottom)
            keep.performClick()
            if (recoveryFailure) {
                compose.waitUntil(5000) { vm.state.value.captureDiscard?.recoveryFailed == true }
                compose.onNodeWithText("返回编辑（未保存）").assertIsDisplayed()
                compose.runOnIdle { assertNull(store.draft); assertTrue(vm.state.value.draftNotice.contains("退出应用可能丢失")) }
                compose.onNodeWithText("重试保留").assertIsDisplayed().performClick()
            }
            compose.waitUntil(5000) { vm.state.value.captureDiscard == null }
            compose.runOnIdle {
                assertEquals("合成恢复草稿", vm.state.value.text)
                assertEquals(vm.state.value.text, store.draft!!.text)
                assertNotEquals("synthetic-restored", store.draft!!.id)
                assertEquals("草稿已保存在本机", vm.state.value.draftNotice)
                assertEquals(1, store.deletes)
                assertEquals(if (recoveryFailure) 2 else 1, store.recoveries)
                assertEquals(0L, vm.state.value.saveFeedbackId)
            }
        } finally { compose.runOnIdle { lifecycle.clear() } }
    }
}
