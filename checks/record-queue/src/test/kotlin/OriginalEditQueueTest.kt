import android.app.Application
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.records.LocalRecordsViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual production ViewModel; controlled IO boundary, never native/Room evidence. */
class OriginalEditQueueTest {
    private fun entry(revision: Int = 1, text: String = "合成原文") = StoredEntry("original", revision, text,
        1_791_647_400_000, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY)
    private suspend fun exercise(repository: EncryptedEntryRepository, block: suspend (LocalRecordsViewModel) -> Unit) {
        repository.seed(entry())
        EncryptedEntryRepository.instance = repository
        val vm = LocalRecordsViewModel(Application(), { EncryptedEntryRepository.instance })
        try { withTimeout(5000) { vm.state.first { it.ready }; block(vm) } } finally { vm.scope.cancel() }
    }

    @Test fun savesImmutableNewRevisionWithoutConsumingCaptureDraft() = runBlocking {
        val repository = EncryptedEntryRepository()
        repository.releaseRevision.complete(Unit)
        exercise(repository) { vm ->
            vm.changeText("合成此刻草稿")
            vm.state.first { it.draftNotice == "草稿已保存在本机" }
            val draft = repository.draft
            vm.beginOriginalEdit("original", 1)
            val session = vm.state.value.originalEdit!!.sessionId
            vm.changeOriginalEdit(session, "合成修改🌿")
            vm.saveOriginalEdit(session)
            vm.state.first { it.originalEdit == null && it.originalEditNotice != null }
            assertEquals("新版本已保存在本机", vm.state.value.originalEditNotice)
            assertEquals(2, vm.state.value.entries.single().currentRevision)
            assertEquals("合成修改🌿", vm.state.value.entries.single().originalText)
            assertEquals(listOf("合成原文", "合成修改🌿"), repository.revisions("original").map { it.originalText })
            assertEquals(draft, repository.draft)
            assertEquals("合成此刻草稿", vm.state.value.text)
            assertTrue(repository.submissions.isEmpty())
            assertEquals(1, repository.revisionSubmissions.size)
        }
    }

    @Test fun lateCaptureWriteFailureRecoversWithoutDiscardingEditorOrCaptureInput() = runBlocking {
        val repository = EncryptedEntryRepository(failPriorEdit = true)
        repository.releaseRevision.complete(Unit)
        exercise(repository) { vm ->
            vm.changeText("合成排队中的此刻草稿")
            repository.firstEditStarted.await()
            vm.beginOriginalEdit("original", 1)
            val session = vm.state.value.originalEdit!!.sessionId
            vm.changeOriginalEdit(session, "合成编辑中的原文修改")
            repository.releaseFirstEdit.complete(Unit)
            vm.state.first { it.error != null }
            assertEquals("合成编辑中的原文修改", vm.state.value.originalEdit!!.text)
            assertTrue(repository.revisionSubmissions.isEmpty())
            vm.retry()
            vm.state.first { it.error == null && !it.opening }
            assertEquals(session, vm.state.value.originalEdit!!.sessionId)
            assertEquals(1, vm.state.value.originalEdit!!.baseRevision)
            assertEquals("合成编辑中的原文修改", vm.state.value.originalEdit!!.text)
            assertEquals("合成排队中的此刻草稿", repository.draft!!.text)
            val draft = repository.draft
            vm.saveOriginalEdit(session)
            vm.state.first { it.originalEdit == null }
            assertEquals("合成编辑中的原文修改", vm.state.value.entries.single().originalText)
            assertEquals("合成排队中的此刻草稿", vm.state.value.text)
            assertEquals(draft, repository.draft)
            assertEquals(1, repository.revisionSubmissions.size)
            assertEquals(2, repository.revisions("original").size)
        }
    }

    @Test fun failedRevisionKeepsInputAndRetryCommitsExactlyOnce() = runBlocking {
        val repository = EncryptedEntryRepository(failFirstRevision = true)
        repository.releaseRevision.complete(Unit)
        exercise(repository) { vm ->
            vm.beginOriginalEdit("original", 1)
            val session = vm.state.value.originalEdit!!.sessionId
            vm.changeOriginalEdit(session, "合成失败后保留的修改")
            vm.saveOriginalEdit(session)
            vm.state.first { it.originalEdit?.error != null }
            val edit = vm.state.value.originalEdit!!
            assertFalse(edit.saving); assertFalse(edit.awaitingReadback); assertFalse(edit.conflict)
            assertEquals("合成失败后保留的修改", edit.text)
            assertEquals(1, vm.state.value.entries.single().currentRevision)
            assertNull(vm.state.value.originalEditNotice)
            vm.saveOriginalEdit(session)
            vm.state.first { it.originalEdit == null }
            assertEquals(2, repository.revisionSubmissions.size)
            assertEquals(repository.revisionSubmissions[0], repository.revisionSubmissions[1])
            assertEquals(2, repository.revisions("original").size)
        }
    }

    @Test fun committedRevisionReadbackRetryNeverCreatesAnotherRevision() = runBlocking {
        val repository = EncryptedEntryRepository(failRevisionReadback = true)
        repository.releaseRevision.complete(Unit)
        exercise(repository) { vm ->
            vm.beginOriginalEdit("original", 1)
            val session = vm.state.value.originalEdit!!.sessionId
            vm.changeOriginalEdit(session, "合成已提交的修改")
            vm.saveOriginalEdit(session)
            vm.state.first { it.originalEdit?.error != null }
            assertTrue(vm.state.value.originalEdit!!.awaitingReadback)
            assertFalse(vm.state.value.originalEdit!!.canCancel)
            assertNull(vm.state.value.originalEditNotice)
            vm.changeOriginalEdit(session, "不能覆盖待确认提交")
            vm.cancelOriginalEdit(session)
            assertEquals("合成已提交的修改", vm.state.value.originalEdit!!.text)
            vm.saveOriginalEdit(session)
            vm.state.first { it.originalEdit == null }
            assertEquals(1, repository.revisionSubmissions.size)
            assertEquals(2, repository.revisions("original").size)
            assertEquals("合成已提交的修改", vm.state.value.entries.single().originalText)
        }
    }

    @Test fun sourceVersionConflictRefreshesSourceWithoutOverwritingUserEdit() = runBlocking {
        val repository = EncryptedEntryRepository()
        repository.releaseRevision.complete(Unit)
        exercise(repository) { vm ->
            vm.beginOriginalEdit("original", 1)
            val session = vm.state.value.originalEdit!!.sessionId
            vm.changeOriginalEdit(session, "合成用户正在修改")
            repository.seed(entry(2, "合成较新的原文"))
            vm.saveOriginalEdit(session)
            vm.state.first { it.originalEdit?.conflict == true }
            assertEquals("合成用户正在修改", vm.state.value.originalEdit!!.text)
            assertEquals("合成较新的原文", vm.state.value.entries.single().originalText)
            assertNull(vm.state.value.originalEditNotice)
            vm.saveOriginalEdit(session)
            assertEquals(1, repository.revisionSubmissions.size)
            vm.cancelOriginalEdit(session)
            vm.beginOriginalEdit("original", 1)
            assertNull(vm.state.value.originalEdit)
            vm.beginOriginalEdit("original", 2)
            assertEquals("合成较新的原文", vm.state.value.originalEdit!!.text)
        }
    }

    @Test fun deletedSourceKeepsEditsAndNeverCreatesReplacementRecord() = runBlocking {
        val repository = EncryptedEntryRepository()
        repository.releaseRevision.complete(Unit)
        exercise(repository) { vm ->
            vm.beginOriginalEdit("original", 1)
            val session = vm.state.value.originalEdit!!.sessionId
            vm.changeOriginalEdit(session, "合成待保留修改")
            repository.remove("original")
            vm.saveOriginalEdit(session)
            vm.state.first { it.originalEdit?.conflict == true }
            assertEquals("合成待保留修改", vm.state.value.originalEdit!!.text)
            assertTrue(vm.state.value.entries.isEmpty())
            assertTrue(repository.submissions.isEmpty())
            assertEquals(1, repository.revisions("original").size)
        }
    }

    @Test fun busyRevisionRejectsDuplicateSaveCancelAndReplacementInput() = runBlocking {
        val repository = EncryptedEntryRepository()
        exercise(repository) { vm ->
            vm.beginOriginalEdit("original", 1)
            val session = vm.state.value.originalEdit!!.sessionId
            vm.changeOriginalEdit(session, "合成提交快照")
            vm.saveOriginalEdit(session)
            repository.revisionStarted.await()
            vm.saveOriginalEdit(session); vm.cancelOriginalEdit(session)
            vm.changeOriginalEdit(session, "不能覆盖提交快照")
            vm.changeText("不能覆盖此刻草稿"); vm.save()
            assertEquals("合成提交快照", vm.state.value.originalEdit!!.text)
            assertTrue(vm.state.value.originalEdit!!.saving)
            assertNull(vm.state.value.originalEditNotice)
            assertEquals("合成原文", vm.state.value.entries.single().originalText)
            repository.releaseRevision.complete(Unit)
            vm.state.first { it.originalEdit == null }
            assertEquals(1, repository.revisionSubmissions.size)
            assertEquals("", vm.state.value.text)
            assertTrue(repository.submissions.isEmpty())
        }
    }

    @Test fun staleSessionAndInvalidOrOversizedInputCannotWriteOriginal() = runBlocking {
        val repository = EncryptedEntryRepository()
        repository.releaseRevision.complete(Unit)
        exercise(repository) { vm ->
            vm.beginOriginalEdit("original", 1)
            val old = vm.state.value.originalEdit!!.sessionId
            vm.changeOriginalEdit(old, "合成取消的修改"); vm.cancelOriginalEdit(old)
            vm.beginOriginalEdit("original", 1)
            val current = vm.state.value.originalEdit!!.sessionId
            assertNotEquals(old, current)
            vm.changeOriginalEdit(old, "合成迟到输入"); vm.saveOriginalEdit(old); vm.cancelOriginalEdit(old)
            vm.saveOriginalEdit(current)
            assertEquals("合成原文", vm.state.value.originalEdit!!.text)
            vm.changeOriginalEdit(current, " \n"); vm.saveOriginalEdit(current)
            assertTrue(repository.revisionSubmissions.isEmpty())
            val boundary = "叶".repeat(99_998) + "🌿"
            vm.changeOriginalEdit(current, boundary)
            vm.changeOriginalEdit(current, boundary + "🌿")
            assertEquals(boundary, vm.state.value.originalEdit!!.text)
            assertNotNull(vm.state.value.originalEdit!!.inputNotice)
            vm.changeOriginalEdit(current, boundary)
            assertNull(vm.state.value.originalEdit!!.inputNotice)
            vm.saveOriginalEdit(current)
            vm.state.first { it.originalEdit == null }
            assertEquals(boundary, repository.revisions("original").last().originalText)
            assertEquals(1, repository.revisionSubmissions.size)
        }
    }
}
