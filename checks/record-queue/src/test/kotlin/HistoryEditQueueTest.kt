import android.app.Application
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.TextDraft
import com.t1doo.aiyou.records.LocalRecordsViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real production VM, controlled repository contract; no native/Room execution claim. */
class HistoryEditQueueTest {
    private fun entry(version: Int, text: String) = StoredEntry("original", version, text,
        1_791_647_400_000, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY)
    private suspend fun exercise(store: EncryptedEntryRepository = EncryptedEntryRepository(),
        source: String = "合成旧原文🌿", current: String = "合成当前原文",
        block: suspend (LocalRecordsViewModel, EncryptedEntryRepository) -> Unit) {
        store.seed(entry(1, source)); store.seed(entry(2, current))
        store.draft = TextDraft("capture", 9, "合成独立此刻草稿")
        EncryptedEntryRepository.instance = store
        val vm = LocalRecordsViewModel(Application())
        try { withTimeout(5000) { vm.state.first { it.ready }; block(vm, store) } } finally { vm.scope.cancel() }
    }
    private suspend fun selected(vm: LocalRecordsViewModel) = run {
        vm.beginOriginalHistory("original", vm.state.value.entries.single().currentRevision)
        val history = vm.state.first { it.originalHistory?.loading == false }.originalHistory!!
        vm.selectOriginalRevision(history.sessionId, 1)
        vm.state.first { it.originalHistory?.loading == false }.originalHistory!!
    }
    private fun use(vm: LocalRecordsViewModel) {
        val h = vm.state.value.originalHistory!!
        vm.editOriginalHistory(h.sessionId, h.requestId, h.selectedRevision!!, h.currentRevision!!)
    }

    @Test fun fullUnicodeHistoryLoadsOnlyInMemoryAndSaveAppendsOnceWithoutChangingOldVersions() = runBlocking {
        val text = "界🌿\n".repeat(24_999) + "尾🌿终"
        assertEquals(100_000, text.length)
        exercise(source = text) { vm, store ->
            val draft = store.draft; val old = store.revisions("original"); selected(vm); use(vm)
            val edit = vm.state.value.originalEdit!!
            assertEquals(text, edit.text); assertEquals("合成当前原文", edit.originalText)
            assertEquals(1, edit.sourceRevision); assertEquals(2, edit.baseRevision); assertTrue(edit.dirty)
            assertNull(vm.state.value.originalHistory); assertTrue(store.revisionSubmissions.isEmpty())
            assertEquals(draft, store.draft); assertEquals(draft!!.text, vm.state.value.text)
            vm.saveOriginalEdit(edit.sessionId); store.revisionStarted.await()
            vm.saveOriginalEdit(edit.sessionId); vm.cancelOriginalEdit(edit.sessionId)
            vm.changeOriginalEdit(edit.sessionId, "迟到覆盖")
            assertEquals(text, vm.state.value.originalEdit!!.text)
            store.releaseRevision.complete(Unit); vm.state.first { it.originalEdit == null }
            assertEquals(1, store.revisionSubmissions.size)
            assertEquals(2, store.revisionSubmissions.single().revision)
            assertEquals(text, vm.state.value.entries.single().originalText)
            assertEquals(3, vm.state.value.entries.single().currentRevision)
            val versions = store.revisions("original")
            assertEquals(old, versions.take(2)); assertEquals(text, versions.last().originalText)
            assertEquals(draft, store.draft); assertEquals(draft.text, vm.state.value.text)
        }
    }

    @Test fun replacementRequiresFreshConfirmationCancelPreservesEverythingAndOldEditorCannotWrite() = runBlocking {
        exercise { vm, store ->
            vm.beginOriginalEdit("original", 2); val id = vm.state.value.originalEdit!!.sessionId
            vm.changeOriginalEdit(id, "合成尚未保存\n🌿")
            val previous = vm.state.value.originalEdit; val draft = store.draft
            val h = selected(vm); use(vm); val first = vm.state.value.originalHistory!!.replacement!!
            assertEquals(previous, vm.state.value.originalEdit)
            vm.cancelHistoryReplacement(h.sessionId, h.requestId, first.id)
            assertEquals(previous, vm.state.value.originalEdit); assertNull(vm.state.value.originalHistory!!.replacement)
            use(vm); val next = vm.state.value.originalHistory!!.replacement!!
            assertNotEquals(first.id, next.id)
            vm.confirmHistoryReplacement(h.sessionId, h.requestId, first.id)
            vm.cancelHistoryReplacement(h.sessionId, h.requestId, first.id)
            assertEquals(next, vm.state.value.originalHistory!!.replacement)
            assertEquals(previous, vm.state.value.originalEdit)
            vm.confirmHistoryReplacement(h.sessionId, h.requestId, next.id)
            assertNull(vm.state.value.originalHistory); assertEquals("合成旧原文🌿", vm.state.value.originalEdit!!.text)
            val newEdit = vm.state.value.originalEdit!!; assertNotEquals(id, newEdit.sessionId)
            vm.changeOriginalEdit(id, "迟到"); vm.saveOriginalEdit(id); vm.cancelOriginalEdit(id)
            assertEquals(newEdit, vm.state.value.originalEdit); assertEquals(draft, store.draft)
            assertTrue(store.revisionSubmissions.isEmpty()); assertTrue(store.submissions.isEmpty())
        }
    }

    @Test fun staleHistoryRequestAndClosedReopenedSessionCannotLoadOrConfirm() = runBlocking {
        exercise { vm, store ->
            val old = selected(vm)
            vm.selectOriginalRevision(old.sessionId, 2)
            vm.state.first { it.originalHistory?.loading == false }
            vm.editOriginalHistory(old.sessionId, old.requestId, 1, 2)
            assertNull(vm.state.value.originalEdit)
            vm.backOriginalHistory(old.sessionId); vm.state.first { it.originalHistory?.loading == false }
            vm.backOriginalHistory(old.sessionId)
            val next = selected(vm); assertNotEquals(old.sessionId, next.sessionId)
            vm.editOriginalHistory(old.sessionId, old.requestId, 1, 2)
            vm.editOriginalHistory(next.sessionId, next.requestId, 2, 2)
            vm.editOriginalHistory(next.sessionId, next.requestId, 1, 3)
            assertNull(vm.state.value.originalEdit); assertEquals(next, vm.state.value.originalHistory)
            assertTrue(store.revisionSubmissions.isEmpty())
        }
    }

    @Test fun concurrentNewVersionConflictsWithoutOverwritingHistoryOrLosingLoadedText() = runBlocking {
        exercise { vm, store ->
            selected(vm); use(vm); val edit = vm.state.value.originalEdit!!
            store.seed(entry(3, "合成并发新版本"))
            vm.saveOriginalEdit(edit.sessionId); store.revisionStarted.await(); store.releaseRevision.complete(Unit)
            vm.state.first { it.originalEdit?.conflict == true }
            assertEquals(edit.text, vm.state.value.originalEdit!!.text)
            assertEquals(1, vm.state.value.originalEdit!!.sourceRevision)
            assertEquals(3, vm.state.value.entries.single().currentRevision)
            assertEquals("合成并发新版本", store.entries().single().originalText)
            assertEquals(listOf("合成旧原文🌿", "合成当前原文", "合成并发新版本"), store.revisions("original").map { it.originalText })
            vm.saveOriginalEdit(edit.sessionId); assertEquals(1, store.revisionSubmissions.size)
            assertEquals("合成独立此刻草稿", vm.state.value.text)
        }
    }

    @Test fun saveFailureRetainsSourceAndReadbackRetryNeverRevisesTwice() = runBlocking {
        exercise(EncryptedEntryRepository(failFirstRevision = true, failRevisionReadback = true)) { vm, store ->
            val old = store.revisions("original"); selected(vm); use(vm); val id = vm.state.value.originalEdit!!.sessionId
            vm.saveOriginalEdit(id); store.revisionStarted.await(); store.releaseRevision.complete(Unit)
            vm.state.first { it.originalEdit?.error != null && it.originalEdit.awaitingReadback == false }
            assertEquals(old, store.revisions("original")); assertEquals("合成旧原文🌿", vm.state.value.originalEdit!!.text)
            vm.saveOriginalEdit(id)
            vm.state.first { it.originalEdit?.awaitingReadback == true && it.originalEdit.saving == false }
            assertEquals(2, store.revisionSubmissions.size); assertEquals(3, store.revisions("original").size)
            assertEquals(1, vm.state.value.originalEdit!!.sourceRevision)
            vm.saveOriginalEdit(id); vm.state.first { it.originalEdit == null }
            assertEquals(2, store.revisionSubmissions.size); assertEquals(old, store.revisions("original").take(2))
            assertEquals("合成独立此刻草稿", vm.state.value.text)
        }
    }

    @Test fun textIdenticalToCurrentVersionCreatesNoMeaninglessRevision() = runBlocking {
        exercise(source = "合成相同原文🌿", current = "合成相同原文🌿") { vm, store ->
            selected(vm); use(vm); val edit = vm.state.value.originalEdit!!
            assertEquals(1, edit.sourceRevision); assertFalse(edit.dirty)
            vm.saveOriginalEdit(edit.sessionId); assertTrue(store.revisionSubmissions.isEmpty())
            vm.changeOriginalEdit(edit.sessionId, "合成暂时修改")
            vm.changeOriginalEdit(edit.sessionId, edit.originalText)
            vm.saveOriginalEdit(edit.sessionId); assertTrue(store.revisionSubmissions.isEmpty())
            vm.cancelOriginalEdit(edit.sessionId); assertNull(vm.state.value.originalEdit)
        }
    }

    @Test fun failedOrChangedHistoryAndDismissedConfirmationNeverReplaceUnsavedEditor() = runBlocking {
        exercise { vm, store ->
            vm.beginOriginalEdit("original", 2); val id = vm.state.value.originalEdit!!.sessionId
            vm.changeOriginalEdit(id, "合成保留输入"); val previous = vm.state.value.originalEdit
            val old = selected(vm); use(vm); val pending = vm.state.value.originalHistory!!.replacement!!
            vm.backOriginalHistory(old.sessionId)
            assertEquals(previous, vm.state.value.originalEdit); assertNull(vm.state.value.originalHistory!!.replacement)
            vm.confirmHistoryReplacement(old.sessionId, old.requestId, pending.id)
            assertEquals(previous, vm.state.value.originalEdit)
            store.failHistory = true; vm.retryOriginalHistory(old.sessionId)
            val failed = vm.state.first { it.originalHistory?.loading == false }.originalHistory!!
            vm.editOriginalHistory(failed.sessionId, failed.requestId, 1, 2)
            assertEquals(previous, vm.state.value.originalEdit)
            store.failHistory = false; store.seed(entry(3, "合成最新版本")); vm.retryOriginalHistory(failed.sessionId)
            vm.state.first { it.originalHistory?.loading == false }
            vm.editOriginalHistory(old.sessionId, old.requestId, 1, 2)
            assertEquals(previous, vm.state.value.originalEdit); assertTrue(store.revisionSubmissions.isEmpty())
        }
    }
}
