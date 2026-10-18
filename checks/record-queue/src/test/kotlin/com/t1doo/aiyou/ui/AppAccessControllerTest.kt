package com.t1doo.aiyou.ui

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual access controller; no platform authentication, records database or device claim. */
class AppAccessControllerTest {
    private class Storage(enabled: Boolean) : AppAccessPreferenceStorage {
        var value = AppAccessPreference(1, enabled)
        var unreadable = false
        var loseWriteAck = false
        var writes = 0
        @Synchronized override fun read(): AppAccessPreference {
            if (unreadable) throw IOException("synthetic private path")
            return value
        }
        @Synchronized override fun write(expected: AppAccessPreference, enabled: Boolean): AppAccessPreference {
            check(value == expected)
            writes++
            value = AppAccessPreference(value.version + 1, enabled)
            if (loseWriteAck) throw IOException("synthetic lost ACK")
            return value
        }
    }
    private class Fixture(enabled: Boolean = false, initialBarrier: CompletableDeferred<Unit> = CompletableDeferred(Unit)) : AutoCloseable {
        val storage = Storage(enabled)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val retires = AtomicInteger()
        val opens = AtomicInteger()
        @Volatile var mayRetire = true
        @Volatile var closeBarrier = initialBarrier
        val controller = AppAccessController(storage, scope, {
            if (mayRetire) { retires.incrementAndGet(); true } else false
        }, { closeBarrier.await(); opens.incrementAndGet() })
        suspend fun phase(phase: AppAccessPhase) = withTimeout(2_000) { controller.state.first { it.phase == phase } }
        fun accept(): String {
            val key = requireNotNull(controller.state.value.authentication).key
            assertTrue(controller.claimAuthentication(key)); controller.authenticationResult(key, true)
            return key
        }
        override fun close() { controller.dispose(); scope.cancel() }
    }

    @Test(timeout = 5_000) fun disabledFirstStartOpensOnlyAfterConfirmedReadAndCloseBarrier() = runBlocking {
        val barrier = CompletableDeferred<Unit>()
        Fixture(initialBarrier = barrier).use { f ->
            f.phase(AppAccessPhase.OPENING)
            assertEquals(0, f.opens.get()); assertFalse(f.controller.state.value.canReadRecords)
            barrier.complete(Unit)
            val open = f.phase(AppAccessPhase.OPEN)
            assertFalse(requireNotNull(open.preference).enabled); assertTrue(open.canReadRecords)
            assertEquals(1, f.opens.get()); assertEquals(0, f.storage.writes)
            assertNull(open.authentication)
        }
    }

    @Test(timeout = 5_000) fun enabledStartupCancelMissingCredentialAndForeignResultsNeverOpenRecords() = runBlocking {
        Fixture(true).use { f ->
            f.phase(AppAccessPhase.LOCKED); assertEquals(0, f.opens.get())
            f.controller.requestUnlock()
            val key = requireNotNull(f.controller.state.value.authentication).key
            f.controller.authenticationResult(key, true) // An unlaunched request has no result authority.
            assertEquals(AppAccessPhase.AUTHENTICATING, f.controller.state.value.phase)
            assertTrue(f.controller.claimAuthentication(key)); assertFalse(f.controller.claimAuthentication(key))
            f.controller.authenticationResult("foreign", true)
            f.controller.authenticationResult(key, false)
            val cancelled = f.phase(AppAccessPhase.LOCKED); assertFalse(cancelled.mayReturn)
            f.controller.returnToPriorAccess(); assertEquals(0, f.opens.get())
            f.controller.requestUnlock()
            val next = requireNotNull(f.controller.state.value.authentication).key
            assertNotEquals(key, next); assertTrue(f.controller.claimAuthentication(next))
            f.controller.authenticationUnavailable(next)
            f.phase(AppAccessPhase.LOCKED); f.controller.authenticationResult(next, true)
            assertEquals(0, f.opens.get()); assertEquals(0, f.storage.writes)
            assertFalse(f.controller.state.value.canReadRecords)
        }
    }

    @Test(timeout = 5_000) fun enableAndDisableRequireFreshAuthenticationAndExactReadbackBeforeOpeningNewSession() = runBlocking {
        Fixture().use { f ->
            val first = f.phase(AppAccessPhase.OPEN)
            f.controller.requestChange(first.session, true)
            assertEquals(1, f.retires.get()); assertFalse(f.controller.state.value.canReadRecords)
            val used = f.accept(); val enabled = f.phase(AppAccessPhase.OPEN)
            assertTrue(requireNotNull(enabled.preference).enabled); assertTrue(enabled.session > first.session)
            assertEquals(1, f.storage.writes)
            f.controller.requestChange(first.session, false) // Retired settings callback.
            assertEquals(AppAccessPhase.OPEN, f.controller.state.value.phase)
            f.controller.requestChange(enabled.session, false)
            f.controller.authenticationResult(used, true)
            assertEquals(AppAccessPhase.AUTHENTICATING, f.controller.state.value.phase)
            f.accept(); val disabled = f.phase(AppAccessPhase.OPEN)
            assertFalse(requireNotNull(disabled.preference).enabled)
            assertEquals(2, f.storage.writes); assertTrue(disabled.session > enabled.session)
        }
    }

    @Test(timeout = 5_000) fun manualLockWaitsForRetiredStorageAndOldSessionCannotLockReopenedRecords() = runBlocking {
        Fixture(true).use { f ->
            f.phase(AppAccessPhase.LOCKED); f.controller.requestUnlock(); val oldKey = f.accept()
            val first = f.phase(AppAccessPhase.OPEN)
            f.closeBarrier = CompletableDeferred()
            f.controller.lock(first.session); f.phase(AppAccessPhase.LOCKED)
            assertEquals(1, f.retires.get()); f.controller.requestUnlock(); f.accept()
            f.phase(AppAccessPhase.OPENING)
            assertEquals(1, f.opens.get()); assertFalse(f.controller.state.value.canReadRecords)
            f.controller.authenticationResult(oldKey, true); assertEquals(AppAccessPhase.OPENING, f.controller.state.value.phase)
            f.closeBarrier.complete(Unit); val second = f.phase(AppAccessPhase.OPEN)
            f.controller.lock(first.session)
            assertEquals(second.session, f.controller.state.value.session); assertEquals(1, f.retires.get())
        }
    }

    @Test(timeout = 5_000) fun busyDurableBoundaryRejectsManualLockAndPreferenceChangeWithoutLaunchingAuthentication() = runBlocking {
        Fixture(true).use { f ->
            f.phase(AppAccessPhase.LOCKED); f.controller.requestUnlock(); f.accept()
            val first = f.phase(AppAccessPhase.OPEN); f.mayRetire = false
            f.controller.lock(first.session); f.controller.requestChange(first.session, false)
            assertEquals(first.session, f.controller.state.value.session)
            assertTrue(f.controller.state.value.canReadRecords); assertNull(f.controller.state.value.authentication)
            assertEquals(0, f.retires.get()); assertEquals(0, f.storage.writes)
        }
    }

    @Test(timeout = 5_000) fun cancelledSettingsAuthenticationCanExplicitlyReturnToPriorAccessWithoutChangingPreference() = runBlocking {
        Fixture().use { f ->
            val first = f.phase(AppAccessPhase.OPEN)
            f.controller.requestChange(first.session, true)
            val key = requireNotNull(f.controller.state.value.authentication).key
            assertTrue(f.controller.claimAuthentication(key)); f.controller.authenticationResult(key, false)
            val cancelled = f.phase(AppAccessPhase.LOCKED); assertTrue(cancelled.mayReturn)
            assertEquals(1, f.opens.get()); assertEquals(0, f.storage.writes)
            f.controller.returnToPriorAccess(); val next = f.phase(AppAccessPhase.OPEN)
            assertFalse(requireNotNull(next.preference).enabled); assertTrue(next.session > first.session)
            f.controller.authenticationResult(key, true); assertEquals(0, f.storage.writes)
        }
    }

    @Test(timeout = 5_000) fun lostEnableAcknowledgementFailsClosedAndRetryReadsCommittedPolicyWithoutRepeatingWrite() = runBlocking<Unit> {
        Fixture().use { f ->
            val first = f.phase(AppAccessPhase.OPEN); f.storage.loseWriteAck = true
            f.controller.requestChange(first.session, true); f.accept()
            f.phase(AppAccessPhase.FAILED)
            assertTrue(f.storage.value.enabled); assertEquals(1, f.storage.writes)
            assertFalse(f.controller.state.value.canReadRecords)
            f.controller.retryRead(); f.phase(AppAccessPhase.LOCKED)
            assertEquals(1, f.storage.writes); assertEquals(1, f.opens.get())
            f.controller.requestUnlock(); f.accept(); f.phase(AppAccessPhase.OPEN)
        }
    }

    @Test(timeout = 5_000) fun readFailureAndDisposedOwnerCannotReopenOrAcceptLateAuthentication() = runBlocking {
        Fixture(true).use { f ->
            f.phase(AppAccessPhase.LOCKED); f.controller.requestUnlock()
            val key = requireNotNull(f.controller.state.value.authentication).key
            assertTrue(f.controller.claimAuthentication(key)); f.controller.dispose()
            f.controller.authenticationResult(key, true); f.controller.requestUnlock(); f.controller.retryRead()
            assertEquals(0, f.opens.get()); assertEquals(0, f.storage.writes)
            assertFalse(f.controller.state.value.canReadRecords)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(false).apply { unreadable = true }; val opens = AtomicInteger()
            val c = AppAccessController(storage, scope, { true }, { opens.incrementAndGet() })
            withTimeout(2_000) { c.state.first { it.phase == AppAccessPhase.FAILED } }
            assertEquals(0, opens.get()); assertNull(c.state.value.preference)
            assertFalse(c.state.value.canReadRecords); assertFalse(c.state.value.message.orEmpty().contains("synthetic"))
            c.dispose()
        } finally { scope.cancel() }
    }
}
