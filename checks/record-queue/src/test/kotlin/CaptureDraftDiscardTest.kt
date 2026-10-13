import android.app.Application
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.model.TextDraft
import com.t1doo.aiyou.records.LocalRecordsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual ViewModel queue; controlled persistence boundary, never a native durability claim. */
class CaptureDraftDiscardTest {
    private class Store(val base: EncryptedEntryRepository = EncryptedEntryRepository()) : EntryRepository by base {
        val calls = mutableListOf<TextDraft>()
        var beforeFailure = false
        var afterFailure = false
        var readFailure = false
        var hold: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        override suspend fun discardDraft(expected: TextDraft) {
            calls += expected
            started.complete(Unit)
            hold?.await()
            if (beforeFailure) { beforeFailure = false; error("synthetic before delete") }
            if (base.draft != null && base.draft != expected) throw RecordConflict()
            base.draft = null
            if (afterFailure) { afterFailure = false; error("synthetic after delete") }
        }
        override suspend fun readDraft(): TextDraft? {
            if (readFailure && calls.isNotEmpty()) { readFailure = false; error("synthetic readback") }
            return base.draft
        }
        override suspend fun restoreDiscardedDraft(expected: TextDraft, replacement: TextDraft): TextDraft {
            val actual = base.draft
            if (actual == expected || actual == replacement) return checkNotNull(actual)
            if (actual != null) throw RecordConflict()
            base.draft = replacement
            return replacement
        }
    }
    private suspend fun ready(store: Store): LocalRecordsViewModel = LocalRecordsViewModel(Application(), { store }).also {
        it.state.first { s -> s.ready }
        it.changeText("合成待清空草稿")
        it.state.first { s -> s.draftNotice == "草稿已保存在本机" }
    }
    private fun request(vm: LocalRecordsViewModel): Long {
        vm.requestCaptureDiscard()
        return checkNotNull(vm.state.value.captureDiscard).requestId
    }
    @Test fun cancelAndOldConfirmationCannotDeleteNewInputEvenIfTextReturnsToSameValue() = runBlocking {
        withTimeout(5000) {
            val store = Store(); val vm = ready(store)
            try {
                val old = request(vm)
                vm.cancelCaptureDiscard(old)
                vm.confirmCaptureDiscard(old)
                val next = request(vm)
                vm.changeText("合成后来输入")
                vm.changeText("合成待清空草稿")
                vm.confirmCaptureDiscard(next)
                vm.state.first { it.draftNotice == "草稿已保存在本机" }
                assertNull(vm.state.value.captureDiscard)
                assertTrue(store.calls.isEmpty())
                assertEquals(3L, store.base.draft!!.version)
            } finally { vm.scope.cancel() }
        }
    }
    @Test fun clearConfirmsAbsenceAndFreshViewModelRestoresNoDraftThenSavesNewIdentity() = runBlocking {
        withTimeout(5000) {
            val store = Store(); val vm = ready(store)
            val old = store.base.draft!!
            try {
                vm.confirmCaptureDiscard(request(vm))
                vm.state.first { it.captureDiscard == null && it.text.isEmpty() }
                assertNull(store.base.draft)
                assertNull(vm.state.value.saveNotice)
                assertEquals(0L, vm.state.value.saveFeedbackId)
                assertTrue(vm.state.value.entries.isEmpty())
                val reopened = LocalRecordsViewModel(Application(), { store })
                try {
                    reopened.state.first { it.ready }
                    assertEquals("", reopened.state.value.text)
                    reopened.changeText("合成新的独立草稿")
                    reopened.state.first { it.draftNotice == "草稿已保存在本机" }
                    assertNotEquals(old.id, store.base.draft!!.id)
                } finally { reopened.scope.cancel() }
            } finally { vm.scope.cancel() }
        }
    }
    @Test fun runningClearRejectsDuplicateConfirmationEditingSavingAndCancel() = runBlocking {
        withTimeout(5000) {
            val store = Store(); val vm = ready(store)
            store.hold = CompletableDeferred()
            try {
                val id = request(vm); vm.confirmCaptureDiscard(id); store.started.await()
                vm.confirmCaptureDiscard(id); vm.cancelCaptureDiscard(id)
                vm.changeText("must not replace"); vm.save(); vm.retry()
                assertTrue(vm.state.value.captureDiscard!!.busy)
                assertEquals("合成待清空草稿", vm.state.value.text)
                assertTrue(store.base.submissions.isEmpty())
                store.hold!!.complete(Unit)
                vm.state.first { it.captureDiscard == null }
                assertEquals(1, store.calls.size)
            } finally { vm.scope.cancel() }
        }
    }
    @Test fun precommitFailureRetainsInputAndRetriesSameSnapshot() = runBlocking {
        verifyAcknowledgementFailure(before = true)
    }
    @Test fun committedDeleteWithLostAcknowledgementRetriesSameSnapshotWithoutRecreatingDraft() = runBlocking {
        verifyAcknowledgementFailure(after = true)
    }
    @Test fun committedDeleteWithReadbackFailureRetriesSameSnapshotWithoutRecreatingDraft() = runBlocking {
        verifyAcknowledgementFailure(read = true)
    }
    private suspend fun verifyAcknowledgementFailure(before: Boolean = false, after: Boolean = false, read: Boolean = false) {
        withTimeout(5000) {
            val store = Store(); val vm = ready(store)
            store.beforeFailure = before; store.afterFailure = after; store.readFailure = read
            try {
                val expected = store.base.draft!!; val id = request(vm); vm.confirmCaptureDiscard(id)
                vm.state.first { it.captureDiscard?.error != null }
                assertEquals(expected.text, vm.state.value.text)
                assertEquals(if (before) expected else null, store.base.draft)
                vm.retry() // Ordinary retry must not write the displayed text back.
                assertEquals(1, store.base.editCalls)
                vm.confirmCaptureDiscard(id)
                vm.state.first { it.captureDiscard == null }
                assertEquals(listOf(expected, expected), store.calls)
                assertNull(store.base.draft)
            } finally { vm.scope.cancel() }
        }
    }
    @Test fun retryNeverRebindsToAnotherSessionsNewDraft() = runBlocking {
        withTimeout(5000) {
            val store = Store(); val vm = ready(store); store.afterFailure = true
            try {
                val id = request(vm); vm.confirmCaptureDiscard(id)
                vm.state.first { it.captureDiscard?.error != null }
                val newer = store.base.updateDraft("合成另一会话草稿")
                vm.confirmCaptureDiscard(id)
                vm.state.first { it.captureDiscard?.conflict == true }
                assertEquals(newer, store.base.draft)
                assertEquals("合成待清空草稿", vm.state.value.text)
                assertEquals(store.calls[0], store.calls[1])
            } finally { vm.scope.cancel() }
        }
    }
    @Test fun failedPriorAutosaveCannotAuthorizeDeletionOfPreviouslyPersistedDraft() = runBlocking {
        withTimeout(5000) {
            val base = EncryptedEntryRepository(failPriorEdit = true)
            base.draft = TextDraft("synthetic-old", 1, "旧的持久草稿")
            val store = Store(base); val vm = LocalRecordsViewModel(Application(), { store })
            try {
                vm.state.first { it.ready }; vm.changeText("尚未写入的新输入"); base.firstEditStarted.await()
                vm.confirmCaptureDiscard(request(vm)); base.releaseFirstEdit.complete(Unit)
                vm.state.first { it.captureDiscard?.conflict == true }
                assertTrue(store.calls.isEmpty())
                assertEquals("旧的持久草稿", base.draft!!.text)
                assertEquals("尚未写入的新输入", vm.state.value.text)
            } finally { vm.scope.cancel() }
        }
    }
    @Test fun returningAfterAmbiguousDeleteCanExplicitlySaveRetainedInput() = runBlocking {
        withTimeout(5000) {
            val store = Store(); val vm = ready(store); store.afterFailure = true
            store.base.releaseFirstSave.complete(Unit)
            try {
                val id = request(vm); vm.confirmCaptureDiscard(id)
                vm.state.first { it.captureDiscard?.error != null }
                vm.cancelCaptureDiscard(id)
                vm.state.first { it.captureDiscard == null }
                vm.save()
                vm.state.first { it.entries.size == 1 && !it.saving }
                assertEquals("合成待清空草稿", vm.state.value.entries.single().originalText)
                assertEquals(1, store.base.editCalls)
            } finally { vm.scope.cancel() }
        }
    }
}
