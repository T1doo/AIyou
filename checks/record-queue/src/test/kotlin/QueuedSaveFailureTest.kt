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
    @Test fun oversizedEditPreservesDraftAndValidBoundaryEditClearsNotice() = runBlocking {
        val repository = EncryptedEntryRepository()
        EncryptedEntryRepository.instance = repository
        val vm = LocalRecordsViewModel(Application(), { EncryptedEntryRepository.instance })
        try { withTimeout(5000) {
            vm.state.first { it.ready }
            val original = "a".repeat(99_999)
            vm.changeText(original)
            vm.state.first { it.draftNotice == "草稿已保存在本机" }
            vm.changeText(original + "ab")
            assertEquals(original, vm.state.value.text)
            assertEquals(original, repository.draft?.text)
            assertEquals(1, repository.editCalls)
            assertTrue(vm.state.value.inputNotice.orEmpty().contains("100000"))
            assertNull(vm.state.value.saveNotice)
            val boundary = original + "b"
            vm.changeText(boundary)
            vm.state.first { it.draftNotice == "草稿已保存在本机" && it.text == boundary }
            assertNull(vm.state.value.inputNotice)
            assertEquals(boundary, repository.draft?.text)
            assertEquals(2, repository.editCalls)
        } } finally { vm.scope.cancel() }
    }

    @Test fun oversizedPasteIntoEmptyDraftDoesNotPersistOrClaimSuccessAndEditingRecovers() = runBlocking {
        val repository = EncryptedEntryRepository()
        EncryptedEntryRepository.instance = repository
        repository.releaseFirstSave.complete(Unit)
        val vm = LocalRecordsViewModel(Application(), { EncryptedEntryRepository.instance })
        try { withTimeout(5000) {
            vm.state.first { it.ready }
            vm.changeText("a".repeat(100_001))
            assertEquals("", vm.state.value.text)
            assertNotNull(vm.state.value.inputNotice)
            assertNull(vm.state.value.saveNotice)
            assertNull(repository.draft)
            assertEquals(0, repository.editCalls)
            vm.changeText("合成的较短输入")
            vm.state.first { it.draftNotice == "草稿已保存在本机" }
            assertNull(vm.state.value.inputNotice)
            vm.save()
            vm.state.first { it.entries.size == 1 && !it.saving }
            assertEquals("合成的较短输入", vm.state.value.entries.single().originalText)
            assertEquals("已保存在本机", vm.state.value.saveNotice)
        } } finally { vm.scope.cancel() }
    }

    @Test fun supplementaryCharactersKeepTheExistingUtf16LimitWithoutPartialTruncation() = runBlocking {
        val repository = EncryptedEntryRepository()
        EncryptedEntryRepository.instance = repository
        val vm = LocalRecordsViewModel(Application(), { EncryptedEntryRepository.instance })
        try { withTimeout(5000) {
            vm.state.first { it.ready }
            val boundary = "a".repeat(99_998) + "📝"
            assertEquals(100_000, boundary.length)
            vm.changeText(boundary)
            vm.state.first { it.draftNotice == "草稿已保存在本机" }
            vm.changeText(boundary + "📝")
            assertEquals(boundary, vm.state.value.text)
            assertEquals(boundary, repository.draft?.text)
            assertNotNull(vm.state.value.inputNotice)
            assertEquals(1, repository.editCalls)
        } } finally { vm.scope.cancel() }
    }

    @Test fun failedEarlierEditCannotDuplicateQueuedSaveAndLaterInputHasItsOwnIntent() = runBlocking {
        val repository = EncryptedEntryRepository(failPriorEdit = true)
        EncryptedEntryRepository.instance = repository
        val vm = LocalRecordsViewModel(Application(), { EncryptedEntryRepository.instance })
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
        val vm = LocalRecordsViewModel(Application(), { EncryptedEntryRepository.instance })
        try { withTimeout(5000) {
            vm.state.first { it.ready }
            vm.changeText("synthetic retry text")
            vm.state.first { it.draftNotice == "草稿已保存在本机" }
            vm.save()
            vm.state.first { it.error != null && !it.saving && it.savePending }
            assertEquals(0L, vm.state.value.saveFeedbackId)
            vm.changeText("must not change pending submission")
            assertEquals("synthetic retry text", vm.state.value.text)
            vm.retry()
            vm.state.first { it.entries.size == 1 && !it.saving && !it.savePending && it.error == null }
            assertEquals(2, repository.submissions.size)
            assertEquals(repository.submissions[0], repository.submissions[1])
            assertEquals("synthetic retry text", vm.state.value.entries.single().originalText)
            assertEquals(1L, vm.state.value.saveFeedbackId)
        } } finally { vm.scope.cancel() }
    }

    @Test fun committedSaveWithFailedReadbackRetriesOriginalIdWithoutCreatingAnotherDraft() = runBlocking {
        val repository = EncryptedEntryRepository(failFirstReadback = true)
        EncryptedEntryRepository.instance = repository
        repository.releaseFirstSave.complete(Unit)
        val vm = LocalRecordsViewModel(Application(), { EncryptedEntryRepository.instance })
        try { withTimeout(5000) {
            vm.state.first { it.ready }
            vm.changeText("synthetic committed text")
            vm.state.first { it.draftNotice == "草稿已保存在本机" }
            vm.save()
            vm.state.first { it.error != null && !it.saving && it.savePending }
            assertTrue("No success list before confirmed database readback", vm.state.value.entries.isEmpty())
            assertEquals(0L, vm.state.value.saveFeedbackId)
            vm.retry()
            vm.state.first { it.entries.size == 1 && !it.saving && !it.savePending && it.error == null }
            assertEquals(2, repository.submissions.size)
            assertEquals(repository.submissions[0], repository.submissions[1])
            assertEquals(1, repository.editCalls)
            assertEquals(1L, vm.state.value.saveFeedbackId)
            assertNull(repository.draft)
        } } finally { vm.scope.cancel() }
    }
}
