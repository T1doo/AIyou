import android.app.Application
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.model.OriginalRevision
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.records.LocalRecordsViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Compiles the production ViewModel. Synthetic persistence boundary, no native execution claim. */
class OriginalHistoryQueueTest {
    private fun entry(revision: Int, text: String) = StoredEntry("original", revision, text,
        1_791_647_400_000, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY)
    private suspend fun exercise(store: EncryptedEntryRepository, block: suspend (LocalRecordsViewModel) -> Unit) {
        store.seed(entry(1, "合成旧原文🌿")); store.seed(entry(2, "合成当前原文"))
        EncryptedEntryRepository.instance = store
        val vm = LocalRecordsViewModel(Application(), { EncryptedEntryRepository.instance })
        try { withTimeout(5000) { vm.state.first { it.ready }; block(vm) } } finally { vm.scope.cancel() }
    }
    private suspend fun loaded(vm: LocalRecordsViewModel) = vm.state.first { it.originalHistory?.loading == false }.originalHistory!!

    @Test fun historyIsReadOnlyAndReturningPreservesBothInputsAndEditorSession() = runBlocking {
        val store = EncryptedEntryRepository()
        exercise(store) { vm ->
            vm.changeText("合成此刻草稿"); vm.state.first { it.draftNotice == "草稿已保存在本机" }
            val draft = store.draft
            vm.beginOriginalEdit("original", 2); val edit = vm.state.value.originalEdit!!.sessionId
            vm.changeOriginalEdit(edit, "合成未保存的修改")
            vm.beginOriginalHistory("original", 2); val history = loaded(vm)
            assertEquals(listOf(2, 1), history.revisions.map { it.revision })
            vm.selectOriginalRevision(history.sessionId, 1); assertEquals("合成旧原文🌿", loaded(vm).revisions.last().originalText)
            vm.saveOriginalEdit(edit); vm.cancelOriginalEdit(edit); vm.changeOriginalEdit(edit, "迟到覆盖")
            vm.changeText("迟到此刻覆盖"); vm.save()
            assertTrue(store.revisionSubmissions.isEmpty()); assertTrue(store.submissions.isEmpty())
            vm.backOriginalHistory(history.sessionId); loaded(vm); vm.backOriginalHistory(history.sessionId)
            assertNull(vm.state.value.originalHistory)
            assertEquals(edit, vm.state.value.originalEdit!!.sessionId)
            assertEquals("合成未保存的修改", vm.state.value.originalEdit!!.text)
            assertEquals("合成此刻草稿", vm.state.value.text); assertEquals(draft, store.draft)
        }
    }

    @Test fun listAndFullReadFailuresHideHistoryAndRetryWithoutWrites() = runBlocking {
        val store = EncryptedEntryRepository(); store.failHistory = true
        exercise(store) { vm ->
            vm.beginOriginalHistory("original", 2); val first = loaded(vm)
            assertNotNull(first.error); assertTrue(first.revisions.isEmpty()); assertNull(vm.state.value.error)
            store.failHistory = false; vm.retryOriginalHistory(first.sessionId); loaded(vm)
            store.failHistory = true; vm.selectOriginalRevision(first.sessionId, 1)
            val failed = loaded(vm); assertEquals(1, failed.selectedRevision); assertTrue(failed.revisions.isEmpty())
            store.failHistory = false; vm.retryOriginalHistory(first.sessionId)
            assertEquals(1, loaded(vm).selectedRevision)
            assertEquals("合成旧原文🌿", vm.state.value.originalHistory!!.revisions.last().originalText)
            assertTrue(store.revisionSubmissions.isEmpty()); assertTrue(store.submissions.isEmpty())
        }
    }

    @Test fun closedAndReopenedHistoryRejectsDelayedReadsAndOldSessions() = runBlocking {
        val store = EncryptedEntryRepository(delayHistory = true)
        exercise(store) { vm ->
            vm.beginOriginalHistory("original", 2); val old = vm.state.value.originalHistory!!.sessionId
            store.historyStarted.await(); vm.backOriginalHistory(old)
            vm.beginOriginalHistory("original", 2); val next = vm.state.value.originalHistory!!.sessionId
            assertNotEquals(old, next)
            vm.backOriginalHistory(old); vm.retryOriginalHistory(old); vm.selectOriginalRevision(old, 1)
            assertEquals(next, vm.state.value.originalHistory!!.sessionId)
            store.releaseHistory.complete(Unit); assertEquals(next, loaded(vm).sessionId)
            assertNull(vm.state.value.originalHistory!!.selectedRevision)
            assertEquals(2, store.historyCalls)
        }
    }

    @Test fun versionChangeDuringReadRequiresFreshReadAndPreservesEditorInput() = runBlocking {
        val store = EncryptedEntryRepository(delayHistory = true)
        exercise(store) { vm ->
            vm.beginOriginalEdit("original", 2); val edit = vm.state.value.originalEdit!!.sessionId
            vm.changeOriginalEdit(edit, "合成用户正在输入")
            vm.beginOriginalHistory("original", 2); store.historyStarted.await()
            store.seed(entry(3, "合成外部新版本")); store.releaseHistory.complete(Unit)
            val failed = loaded(vm); assertNotNull(failed.error); assertTrue(failed.revisions.isEmpty())
            assertEquals(3, vm.state.value.entries.single().currentRevision)
            assertEquals("合成外部新版本", vm.state.value.entries.single().originalText)
            vm.retryOriginalHistory(failed.sessionId)
            assertEquals(3, loaded(vm).currentRevision)
            assertEquals("合成用户正在输入", vm.state.value.originalEdit!!.text)
            assertEquals(2, vm.state.value.originalEdit!!.baseRevision)
            assertEquals(3, vm.state.value.entries.single().currentRevision)
        }
    }

    @Test fun deletedRecordCannotExposeRetainedRevisionsOrReplaceEditorInput() = runBlocking {
        val store = EncryptedEntryRepository(delayHistory = true)
        exercise(store) { vm ->
            vm.beginOriginalEdit("original", 2); val edit = vm.state.value.originalEdit!!.sessionId
            vm.changeOriginalEdit(edit, "合成保留修改")
            vm.beginOriginalHistory("original", 2); store.historyStarted.await()
            store.remove("original"); store.releaseHistory.complete(Unit)
            val missing = loaded(vm); assertTrue(missing.unavailable); assertTrue(missing.revisions.isEmpty())
            assertTrue(vm.state.value.entries.isEmpty()); assertEquals("合成保留修改", vm.state.value.originalEdit!!.text)
            assertTrue(store.revisionSubmissions.isEmpty())
        }
    }

    @Test fun missingSelectedRevisionNeverFallsBackToCurrentText() = runBlocking {
        val store = EncryptedEntryRepository()
        exercise(store) { vm ->
            vm.beginOriginalHistory("original", 2); val history = loaded(vm)
            store.removeRevision("original", 1); vm.selectOriginalRevision(history.sessionId, 1)
            val missing = loaded(vm); assertTrue(missing.unavailable); assertEquals(1, missing.selectedRevision)
            assertEquals(listOf(2), missing.revisions.map { it.revision })
            vm.backOriginalHistory(history.sessionId)
            assertNull(loaded(vm).selectedRevision)
            assertFalse(vm.state.value.originalHistory!!.unavailable)
        }
    }

    @Test fun malformedHistoryAndBusyEditNeverPermitHistoryOrMutation() = runBlocking {
        val store = EncryptedEntryRepository()
        exercise(store) { vm ->
            vm.beginOriginalHistory("original", 1); assertNull(vm.state.value.originalHistory)
            store.historyOverride = listOf(OriginalRevision("other", 2, "合成另一记录", "synthetic", 0))
            vm.beginOriginalHistory("original", 2); val failed = loaded(vm)
            assertNotNull(failed.error); assertTrue(failed.revisions.isEmpty())
            vm.backOriginalHistory(failed.sessionId)
            vm.beginOriginalEdit("original", 2); val edit = vm.state.value.originalEdit!!.sessionId
            vm.changeOriginalEdit(edit, "合成保存中"); vm.saveOriginalEdit(edit); store.revisionStarted.await()
            vm.beginOriginalHistory("original", 2); assertNull(vm.state.value.originalHistory)
            store.releaseRevision.complete(Unit); vm.state.first { it.originalEdit == null }
            assertEquals(1, store.revisionSubmissions.size)
        }
    }
}
