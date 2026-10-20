package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*

class ReminderOperationBridgeTest {
    @Test(timeout=15_000) fun suspensionKeepsPhysicalWorkerGateUntilRealWorkEndsAndCloseCannotAcknowledgeEarly(): Unit = runBlocking {
        val bridge = ReminderOperationBridge(); val owner = bridge.gate.openOwner(true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val wiped = AtomicBoolean()
        try {
            val operation = async(Dispatchers.Default) { bridge.operation(owner) {
                val thread = Thread.currentThread()
                withContext(Dispatchers.IO) { entered.countDown(); check(release.await(3,TimeUnit.SECONDS)); assertFalse(wiped.get()) }
                assertSame(thread,Thread.currentThread()); assertFalse(wiped.get())
            } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(3,TimeUnit.SECONDS) })
            owner.seal()
            val close = async(Dispatchers.Default) { bridge.closeOwner(owner) { wiped.set(true) } }
            assertNull(withTimeoutOrNull(100) { close.await() }); assertFalse(wiped.get())
            release.countDown(); operation.await(); close.await(); assertTrue(wiped.get())
            val next = bridge.gate.openOwner(true); next.closeAndAwait()
        } finally { release.countDown(); bridge.close() }
    }

    @Test(timeout=15_000) fun childIoAndSynchronousWorkerCallbacksCannotAwaitTheirOwnWorker(): Unit = runBlocking {
        val bridge=ReminderOperationBridge(); val owner=bridge.gate.openOwner(true)
        try {
            bridge.operation(owner) {
                withContext(Dispatchers.IO) {
                    try { bridge.runtime { error("must not run") }; fail("recursive operation accepted") }
                    catch (_:IllegalStateException) { }
                }
                try { bridge.operation(owner) { error("must not run") }; fail("recursive gate accepted") }
                catch (_:IllegalStateException) { }
            }
            bridge.runtime {
                runBlocking {
                    try { bridge.runtime { error("must not run") }; fail("worker self wait accepted") }
                    catch (_:IllegalStateException) { }
                }
            }
            bridge.closeOwner(owner) {}
        } finally { bridge.close() }
    }

    @Test fun admissionNeverLoadsStoreWhenDeniedAndFailedPrepareRemainsSealedUntilCleanup(): Unit {
        val gate=ReminderExecutionGate(); var loads=0;var failed:ReminderRuntimeOwner?=null
        assertNull(gate.admit({false}){loads++;Unit});assertEquals(0,loads)
        assertThrows(IllegalStateException::class.java) { gate.admit({true}){owner->failed=owner;loads++;error("Synthetic prepare failure")} }
        assertEquals(1,loads);assertThrows(IllegalStateException::class.java){gate.openOwner(true)}
        failed!!.closeAndAwait();val ready=gate.admit({true}){it};assertNotNull(ready);ready!!.closeAndAwait()
    }
}
