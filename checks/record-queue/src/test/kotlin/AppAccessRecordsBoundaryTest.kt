import android.app.Application
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.domain.AwaitableRecordStorage
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.records.LocalRecordsViewModel
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual production records actor with controlled IO; not native database/lifecycle evidence. */
class AppAccessRecordsBoundaryTest {
    private class Storage(val base: EncryptedEntryRepository, val failClose: Boolean = false) :
        EntryRepository by base, AwaitableRecordStorage {
        val closing = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet() }
        override fun awaitClosed() {
            closing.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
            if (failClose) throw IOException("synthetic private closure failure")
        }
    }

    @Test(timeout = 10_000) fun idleRetirementSealsAllAdmissionAndWaitsForActualStorageClosure() = runBlocking {
        val store = Storage(EncryptedEntryRepository())
        val vm = LocalRecordsViewModel(Application(), { store })
        try { withTimeout(5_000) {
            vm.state.first { it.ready }; vm.appAccessIdle.first { it }
            assertTrue(vm.canRetireForAppAccess()); assertTrue(vm.retireForAppAccess())
            store.closing.await()
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { vm.awaitAppAccessClosed() }
            assertFalse(stopped.isCompleted)
            vm.changeText("synthetic stale edit"); vm.save(); vm.retry()
            assertEquals(0, store.base.editCalls); assertTrue(store.base.submissions.isEmpty())
            assertFalse(vm.retireForAppAccess())
            store.release.countDown(); stopped.await(); assertEquals(1, store.closes.get())
        } } finally { store.release.countDown(); vm.scope.cancel() }
    }

    @Test(timeout = 10_000) fun queuedAndFailedDraftCannotRetireUntilTheSameInputIsConfirmedDurable() = runBlocking {
        val store = Storage(EncryptedEntryRepository(failPriorEdit = true))
        val vm = LocalRecordsViewModel(Application(), { store })
        try { withTimeout(5_000) {
            vm.state.first { it.ready }; vm.appAccessIdle.first { it }
            vm.changeText("synthetic retained draft")
            store.base.firstEditStarted.await()
            assertFalse(vm.canRetireForAppAccess()); assertFalse(vm.retireForAppAccess())
            store.base.releaseFirstEdit.complete(Unit)
            vm.state.first { it.error != null }; vm.appAccessIdle.first { it }
            assertFalse(vm.retireForAppAccess()); assertEquals("synthetic retained draft", vm.state.value.text)
            vm.retry()
            vm.state.first { it.error == null && it.draftNotice == "草稿已保存在本机" }; vm.appAccessIdle.first { it }
            assertEquals("synthetic retained draft", store.base.draft?.text)
            assertTrue(vm.retireForAppAccess()); store.closing.await()
            store.release.countDown(); vm.awaitAppAccessClosed()
            assertEquals("synthetic retained draft", store.base.draft?.text)
            assertEquals(0, store.base.submissions.size)
        } } finally { store.base.releaseFirstEdit.complete(Unit); store.release.countDown(); vm.scope.cancel() }
    }

    @Test(timeout = 10_000) fun failedStorageClosureCannotBecomeAnAcknowledgedRetiredSession() = runBlocking {
        val store = Storage(EncryptedEntryRepository(), failClose = true)
        val vm = LocalRecordsViewModel(Application(), { store })
        try { withTimeout(5_000) {
            vm.state.first { it.ready }; vm.appAccessIdle.first { it }
            assertTrue(vm.retireForAppAccess()); store.closing.await(); store.release.countDown()
            try { vm.awaitAppAccessClosed(); fail("Unconfirmed storage closure must not pass") }
            catch (failure: IllegalStateException) { assertEquals("Records session closure unconfirmed", failure.message) }
            assertFalse(vm.canRetireForAppAccess()); assertEquals(1, store.closes.get())
        } } finally { store.release.countDown(); vm.scope.cancel() }
    }
}
