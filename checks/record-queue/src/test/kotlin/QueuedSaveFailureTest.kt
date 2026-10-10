import android.app.Application
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.records.LocalRecordsViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class QueuedSaveFailureTest {
    @Test fun failedEarlierEditCannotDuplicateQueuedSaveAndLaterInputHasItsOwnIntent() = runBlocking {
        val repository = EncryptedEntryRepository(failPriorEdit = true)
        EncryptedEntryRepository.instance = repository
        val vm = LocalRecordsViewModel(Application())
        try { withTimeout(5000) {
            vm.state.first { it.ready }
            vm.changeText("synthetic first submitted text")
            repository.firstEditStarted.await()
            vm.save()
            repository.releaseFirstEdit.complete(Unit)
            repository.firstSaveStarted.await()
            assertTrue("An earlier Edit error must keep the queued save busy", vm.state.value.saving)
            vm.retry()
            repository.releaseFirstSave.complete(Unit)
            vm.state.first { it.entries.size == 1 && !it.saving && it.error == null }
            vm.changeText("synthetic later input")
            vm.state.first { it.draftNotice == "草稿已保存在本机" }
            assertEquals("synthetic later input", repository.draft?.text)
            assertEquals(1, repository.submissions.size)
            vm.save()
            vm.state.first { it.entries.size == 2 && !it.saving && it.error == null }
            assertEquals(listOf("synthetic first submitted text", "synthetic later input"),
                repository.submissions.map { it.text })
            assertEquals(2, repository.submissions.map { it.id }.distinct().size)
            assertNull(repository.draft)
        } } finally { vm.scope.cancel() }
    }

    @Test fun genuineSaveFailureRetriesSameIntentAndRejectsInterveningInput() = runBlocking {
        val repository = EncryptedEntryRepository(failFirstSave = true)
        EncryptedEntryRepository.instance = repository
        repository.releaseFirstSave.complete(Unit)
        val vm = LocalRecordsViewModel(Application())
        try { withTimeout(5000) {
            vm.state.first { it.ready }
            vm.changeText("synthetic retry text")
            vm.state.first { it.draftNotice == "草稿已保存在本机" }
            vm.save()
            vm.state.first { it.error != null && !it.saving && it.savePending }
            vm.changeText("must not change pending submission")
            assertEquals("synthetic retry text", vm.state.value.text)
            vm.retry()
            vm.state.first { it.entries.size == 1 && !it.saving && !it.savePending && it.error == null }
            assertEquals(2, repository.submissions.size)
            assertEquals(repository.submissions[0], repository.submissions[1])
            assertEquals("synthetic retry text", vm.state.value.entries.single().originalText)
        } } finally { vm.scope.cancel() }
    }

    @Test fun committedSaveWithFailedReadbackRetriesOriginalIdWithoutCreatingAnotherDraft() = runBlocking {
        val repository = EncryptedEntryRepository(failFirstReadback = true)
        EncryptedEntryRepository.instance = repository
        repository.releaseFirstSave.complete(Unit)
        val vm = LocalRecordsViewModel(Application())
        try { withTimeout(5000) {
            vm.state.first { it.ready }
            vm.changeText("synthetic committed text")
            vm.state.first { it.draftNotice == "草稿已保存在本机" }
            vm.save()
            vm.state.first { it.error != null && !it.saving && it.savePending }
            assertTrue("No success list before confirmed database readback", vm.state.value.entries.isEmpty())
            vm.retry()
            vm.state.first { it.entries.size == 1 && !it.saving && !it.savePending && it.error == null }
            assertEquals(2, repository.submissions.size)
            assertEquals(repository.submissions[0], repository.submissions[1])
            assertEquals(1, repository.editCalls)
            assertNull(repository.draft)
        } } finally { vm.scope.cancel() }
    }
}
