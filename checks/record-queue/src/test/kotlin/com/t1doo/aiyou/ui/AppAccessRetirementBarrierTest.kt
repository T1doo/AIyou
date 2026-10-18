package com.t1doo.aiyou.ui

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class AppAccessRetirementBarrierTest {
    private val storage = object : AppAccessPreferenceStorage {
        override fun read() = AppAccessPreference(0, false)
        override fun write(expected: AppAccessPreference, enabled: Boolean): AppAccessPreference = error("No setting write requested")
    }
    @Test(timeout = 5_000) fun newAccessOwnerWaitsForPreviousOwnersStorageCloseAcknowledgement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val barrier = AppAccessRetirementBarrier(); val oldClose = CompletableDeferred<Unit>()
            barrier.register(oldClose); barrier.register(oldClose)
            val opens = AtomicInteger()
            val next = AppAccessController(storage, scope, { true }, { barrier.awaitClosed(); opens.incrementAndGet() })
            withTimeout(2_000) { next.state.first { it.phase == AppAccessPhase.OPENING } }
            assertEquals(0, opens.get()); assertFalse(barrier.isClear())
            oldClose.complete(Unit)
            withTimeout(2_000) { next.state.first { it.phase == AppAccessPhase.OPEN } }
            assertEquals(1, opens.get()); assertTrue(barrier.isClear()); next.dispose()
        } finally { scope.cancel() }
    }

    @Test(timeout = 5_000) fun failedCloseSurvivesOwnerDisposalAndCannotBeForgottenByANewController() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val barrier = AppAccessRetirementBarrier(); val failed = CompletableDeferred<Unit>()
            failed.completeExceptionally(IOException("synthetic private closure path")); barrier.register(failed)
            val opens = AtomicInteger()
            repeat(2) {
                val controller = AppAccessController(storage, scope, { true }, { barrier.awaitClosed(); opens.incrementAndGet() })
                withTimeout(2_000) { controller.state.first { it.phase == AppAccessPhase.FAILED } }
                assertFalse(controller.state.value.canReadRecords)
                assertFalse(controller.state.value.message.orEmpty().contains("synthetic"))
                controller.dispose()
            }
            assertEquals(0, opens.get()); assertFalse(barrier.isClear())
        } finally { scope.cancel() }
    }
}
