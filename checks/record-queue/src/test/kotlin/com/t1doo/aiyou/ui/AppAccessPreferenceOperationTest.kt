package com.t1doo.aiyou.ui

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class AppAccessPreferenceOperationTest {
    private class Store : AppAccessPreferenceStorage {
        @Volatile var value = AppAccessPreference(1, false)
        var failAcknowledgement = false
        var failNextCommittedRead = false
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val finished = CompletableDeferred<Unit>()
        val writes = AtomicInteger()
        @Synchronized override fun read(): AppAccessPreference {
            if (writes.get() > 0 && failNextCommittedRead) {
                failNextCommittedRead = false; throw IOException("synthetic read not confirmed")
            }
            return value
        }
        override fun write(expected: AppAccessPreference, enabled: Boolean): AppAccessPreference {
            entered.complete(Unit)
            try {
                check(release.await(5, TimeUnit.SECONDS))
                return synchronized(this) {
                    check(value == expected); writes.incrementAndGet()
                    value = AppAccessPreference(value.version + 1, enabled)
                    if (failAcknowledgement) throw IOException("synthetic lost acknowledgement")
                    value
                }
            } finally { finished.complete(Unit) }
        }
    }
    private class IoProbe : CoroutineDispatcher() {
        val finishedDispatch = Channel<Unit>(Channel.UNLIMITED)
        override fun dispatch(context: CoroutineContext, block: Runnable) = Dispatchers.IO.dispatch(context, Runnable {
            try { block.run() } finally { finishedDispatch.trySend(Unit) }
        })
    }

    @Test(timeout = 10_000)
    fun retiredOwnerQueuedBehindAnotherOperationCannotWriteAfterItsOwnershipWasRevoked() = runBlocking<Unit> {
        val store = Store(); val operations = AppAccessPreferenceOperations(); val io = IoProbe()
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val old = AppAccessController(store, oldScope, { true }, {}, io = io, preferenceOperations = operations)
        val occupied = CompletableDeferred<Unit>(); val releaseHolder = CountDownLatch(1)
        var next: AppAccessController? = null
        try { withTimeout(6_000) {
            val first = old.state.first { it.phase == AppAccessPhase.OPEN }; io.finishedDispatch.receive()
            val holder = async(Dispatchers.IO) { operations.perform {
                occupied.complete(Unit); check(releaseHolder.await(5, TimeUnit.SECONDS))
            } }
            occupied.await()
            old.requestChange(first.session, true)
            val key = requireNotNull(old.state.value.authentication).key
            assertTrue(old.claimAuthentication(key)); old.authenticationResult(key, true)
            io.finishedDispatch.receive() // Reached the suspended operation boundary.
            assertFalse(store.entered.isCompleted)
            old.dispose(); oldScope.cancel(); releaseHolder.countDown(); holder.await()
            oldScope.coroutineContext[Job]?.join()
            val replacement = AppAccessController(store, newScope, { true }, {}, preferenceOperations = operations)
            next = replacement
            val opened = replacement.state.first { it.phase == AppAccessPhase.OPEN }
            assertFalse(requireNotNull(opened.preference).enabled); assertFalse(store.value.enabled)
            assertEquals(0, store.writes.get()); assertFalse(store.entered.isCompleted)
            old.authenticationResult(key, true); assertEquals(0, store.writes.get())
        } } finally {
            releaseHolder.countDown(); store.release.countDown(); old.dispose(); next?.dispose()
            oldScope.cancel(); newScope.cancel()
            withTimeout(2_000) { oldScope.coroutineContext[Job]?.join(); newScope.coroutineContext[Job]?.join() }
        }
    }

    @Test(timeout = 10_000)
    fun failedAcknowledgementAndReadRemainRecoverableByFreshOwnerWithoutRepeatingTheWrite() = runBlocking<Unit> {
        val store = Store().apply { failAcknowledgement = true; failNextCommittedRead = true }
        val operations = AppAccessPreferenceOperations()
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val old = AppAccessController(store, oldScope, { true }, {}, preferenceOperations = operations)
        val opens = AtomicInteger(); var next: AppAccessController? = null
        try { withTimeout(6_000) {
            val first = old.state.first { it.phase == AppAccessPhase.OPEN }
            old.requestChange(first.session, true)
            val oldKey = requireNotNull(old.state.value.authentication).key
            assertTrue(old.claimAuthentication(oldKey)); old.authenticationResult(oldKey, true)
            store.entered.await(); old.dispose(); oldScope.cancel()
            val io = IoProbe()
            val replacement = AppAccessController(store, newScope, { true }, { opens.incrementAndGet() },
                io = io, preferenceOperations = operations)
            next = replacement
            io.finishedDispatch.receive(); assertFalse(replacement.state.value.canReadRecords)
            store.release.countDown(); store.finished.await()
            replacement.state.first { it.phase == AppAccessPhase.FAILED }
            assertEquals(0, opens.get()); assertTrue(store.value.enabled); assertEquals(1, store.writes.get())
            replacement.retryRead()
            val locked = replacement.state.first { it.phase == AppAccessPhase.LOCKED }
            assertTrue(requireNotNull(locked.preference).enabled); assertEquals(0, opens.get())
            replacement.requestUnlock(); val currentKey = requireNotNull(replacement.state.value.authentication).key
            assertTrue(replacement.claimAuthentication(currentKey)); replacement.authenticationResult(currentKey, true)
            replacement.state.first { it.phase == AppAccessPhase.OPEN }
            assertEquals(1, opens.get()); assertEquals(1, store.writes.get())
            old.authenticationResult(oldKey, true); assertEquals(1, store.writes.get())
        } } finally {
            store.release.countDown(); old.dispose(); next?.dispose(); oldScope.cancel(); newScope.cancel()
            withTimeout(2_000) { oldScope.coroutineContext[Job]?.join(); newScope.coroutineContext[Job]?.join() }
        }
    }
}
