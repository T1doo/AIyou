package com.t1doo.aiyou.ai

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class HttpBodyOwnershipTest {
    @Test(timeout = 10_000) fun externalCancelReturnsWithoutClosingReadOwnedBodyAndOwnerClosesExactlyOnce() {
        Protocol.entries.forEach { protocol ->
            HttpBodyOwnershipFixture(protocol, blockRead = true, throwOnClose = false).use { fixture ->
                val workers = Executors.newFixedThreadPool(2)
                val events = Collections.synchronizedList(mutableListOf<StreamEvent>())
                try {
                    val result = workers.submit<HttpOutcome> { fixture.exchange.execute(events::add) }
                    assertTrue("Actual response source must enter the owner read", fixture.probe.readEntered.await(2, TimeUnit.SECONDS))
                    assertEquals(1, fixture.probe.activeReads.get())
                    val cancelled = CountDownLatch(1)
                    val cancelJob = workers.submit { fixture.exchange.cancel(); cancelled.countDown() }
                    assertTrue("External cancel must return while the owner remains in read", cancelled.await(1, TimeUnit.SECONDS))
                    cancelJob.get(1, TimeUnit.SECONDS)
                    assertTrue(checkNotNull(fixture.observer.call.get()).isCanceled())
                    assertTrue("Call.cancel must close the actual socket", checkNotNull(fixture.observer.socket.get()).isClosed)
                    assertEquals("External cancel must leave read-owned body cleanup to owner", 0, fixture.probe.closeCount.get())
                    assertEquals(0, fixture.probe.closeOverlaps.get())
                    fixture.probe.releaseRead.countDown()
                    assertEquals(StreamEvent.Cancelled, result.get(2, TimeUnit.SECONDS).terminal)
                    assertEquals(listOf(StreamEvent.Cancelled), events)
                    assertTrue(fixture.probe.closeAttempted.await(1, TimeUnit.SECONDS))
                    assertEquals(0, fixture.probe.activeReads.get())
                    assertEquals(1, fixture.probe.closeCount.get())
                    assertEquals(0, fixture.probe.closeOverlaps.get())
                    assertSame(fixture.probe.ownerThread.get(), fixture.probe.closeThread.get())
                    fixture.assertActualRequestAndReleased()
                } finally {
                    fixture.probe.releaseRead.countDown()
                    workers.shutdownNow()
                }
            }
        }
    }

    @Test(timeout = 10_000) fun completedWireSurvivesOrdinarySourceCloseAssertionAndReleasesActualConnection() {
        Protocol.entries.forEach { protocol ->
            HttpBodyOwnershipFixture(protocol, blockRead = false, throwOnClose = true).use { fixture ->
                val events = mutableListOf<StreamEvent>()
                val outcome = fixture.exchange.execute(events::add)
                assertEquals(StreamEvent.Completed, outcome.terminal)
                assertEquals(200, outcome.httpStatus)
                assertEquals(StreamEvent.Completed, events.last())
                assertEquals(1, events.count { it == StreamEvent.Completed })
                assertFalse(events.any { it is StreamEvent.Failed || it == StreamEvent.Cancelled })
                assertTrue(fixture.probe.closeAttempted.await(1, TimeUnit.SECONDS))
                assertEquals(1, fixture.probe.closeCount.get())
                assertEquals(0, fixture.probe.closeOverlaps.get())
                assertFalse(outcome.toString().contains(HttpBodyOwnershipFixture.SourceProbe.closeMarker))
                assertFalse(events.toString().contains(HttpBodyOwnershipFixture.SourceProbe.closeMarker))
                fixture.assertActualRequestAndReleased()
            }
        }
    }
}
