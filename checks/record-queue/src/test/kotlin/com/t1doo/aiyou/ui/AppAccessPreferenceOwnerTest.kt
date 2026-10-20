package com.t1doo.aiyou.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Controlled dual-owner scheduling, not a reported user incident or device authentication test. */
class AppAccessPreferenceOwnerTest {
    private class Storage : AppAccessPreferenceStorage {
        private val fileGuard = Any()
        @Volatile var value = AppAccessPreference(1, false)
        val enteredWrite = CompletableDeferred<Unit>()
        val releaseWrite = CountDownLatch(1)
        val finishedWrite = CompletableDeferred<Unit>()
        val writes = AtomicInteger()
        override fun read(): AppAccessPreference = synchronized(fileGuard) { value }
        override fun write(expected: AppAccessPreference, enabled: Boolean): AppAccessPreference {
            // After the controller's ownership check, before the file store monitor.
            enteredWrite.complete(Unit)
            try {
                check(releaseWrite.await(5, TimeUnit.SECONDS))
                return synchronized(fileGuard) {
                    check(value == expected)
                    writes.incrementAndGet()
                    AppAccessPreference(value.version + 1, enabled).also { value = it }
                }
            } finally { finishedWrite.complete(Unit) }
        }
    }
    private class FirstIoDispatch : CoroutineDispatcher() {
        val finished = CompletableDeferred<Unit>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            Dispatchers.IO.dispatch(context, Runnable {
                try { block.run() } finally { finished.complete(Unit) }
            })
        }
    }

    @Test(timeout = 10_000)
    fun retiredBlockedEnableCannotLeaveNewOwnerOpenWithDisabledSnapshot() = runBlocking<Unit> {
        val storage = Storage()
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val records = AppAccessRetirementBarrier()
        val opens = AtomicInteger()
        val old = AppAccessController(storage, oldScope, {
            records.register(CompletableDeferred(Unit)); true
        }, { records.awaitClosed() })
        var next: AppAccessController? = null
        try { withTimeout(6_000) {
            val initial = old.state.first { it.phase == AppAccessPhase.OPEN }
            old.requestChange(initial.session, true)
            val key = requireNotNull(old.state.value.authentication).key
            assertTrue(old.claimAuthentication(key)); old.authenticationResult(key, true)
            storage.enteredWrite.await()
            old.dispose(); oldScope.cancel() // Cancellation does not interrupt the already executing Java write.
            val io = FirstIoDispatch()
            val replacement = AppAccessController(storage, newScope, { true }, {
                records.awaitClosed(); opens.incrementAndGet()
            }, io = io)
            next = replacement
            // In RED this completes the stale read; in GREEN it reaches the shared operation wait.
            io.finished.await()
            storage.releaseWrite.countDown(); storage.finishedWrite.await()
            val settled = replacement.state.first { it.phase in setOf(AppAccessPhase.OPEN, AppAccessPhase.LOCKED, AppAccessPhase.FAILED) }
            println("P2_OWNER_OBSERVATION diskEnabled=${storage.value.enabled} newPhase=${settled.phase} " +
                "newSnapshotEnabled=${settled.preference?.enabled} recordsOpened=${opens.get()}")
            assertTrue(storage.value.enabled)
            assertFalse("A replacement owner must not open with disabled while the retired owner's accepted enable commits", settled.canReadRecords)
            assertEquals(AppAccessPhase.LOCKED, settled.phase)
            assertTrue(requireNotNull(settled.preference).enabled)
            assertEquals(0, opens.get()); assertEquals(1, storage.writes.get())
        } } finally {
            storage.releaseWrite.countDown(); old.dispose(); next?.dispose()
            oldScope.cancel(); newScope.cancel()
            withTimeout(2_000) { oldScope.coroutineContext[Job]?.join(); newScope.coroutineContext[Job]?.join() }
        }
    }
}
