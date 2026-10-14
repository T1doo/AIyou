package com.t1doo.aiyou.ai

import com.t1doo.aiyou.data.RemoteTextFixture
import com.t1doo.aiyou.domain.SendDenial
import com.t1doo.aiyou.domain.SourceBoundTextRepository
import com.t1doo.aiyou.domain.SourceBoundTextSubscription
import com.t1doo.aiyou.model.*
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import org.junit.Assert.*
import org.junit.Test
import mockwebserver3.MockResponse
import okio.Buffer

/** Real SQL and sockets establish cancellation order independently of implementation flags. */
class SourceBoundTransportCloseContractTest {
    @Test(timeout = 10_000)
    fun closeCancelsAllOwnedCallsBeforeBlockedPersistentCleanup() {
        ConnectionProtocol.entries.forEach { protocol ->
            SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
                val other = f.records.second()
                assertNotEquals(f.records.db.pid, other.pid)
                val otherRepository = f.records.store(other)
                val otherSource = SourceVersion("synthetic-unrelated-source", 1)
                assertNull(otherRepository.updateAuthorization(otherSource, 0,
                    ProcessingPolicy.REMOTE_ALLOWED, f.records.selected,
                    f.records.grant.copy(consentId = "synthetic-close-other-grant", source = otherSource)))
                val originals = f.records.originals()
                f.enqueueHeld(protocol)
                val closeCaller = AtomicReference<Thread>()
                val cleanupEntered = CountDownLatch(1)
                val repository = ObservedRepository(f.records.repository) {
                    if (Thread.currentThread() === closeCaller.get()) cleanupEntered.countDown()
                }
                val first = f.exchange(repository = repository)
                val second = f.exchange(repository = repository,
                    intent = RemoteSendIntent(otherSource, TextPurpose.ORGANIZATION))
                val firstText = CountDownLatch(1)
                val secondText = CountDownLatch(1)
                val firstEvents = events()
                val secondEvents = events()
                val writerHeld = CountDownLatch(1)
                val releaseWriter = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(5)
                try {
                    val firstResult = pool.submit<SourceBoundSendOutcome> {
                        first.execute {
                            firstEvents.add(it)
                            if (it is StreamEvent.TextDelta) firstText.countDown()
                        }
                    }
                    await(firstText)
                    val secondResult = pool.submit<SourceBoundSendOutcome> {
                        second.execute {
                            secondEvents.add(it)
                            if (it is StreamEvent.TextDelta) secondText.countDown()
                        }
                    }
                    await(secondText)
                    assertEquals(2, f.observer.calls.size)
                    assertEquals(2, f.observer.sockets.size)
                    val writer = pool.submit {
                        other.transaction { sql ->
                            assertEquals(listOf("ADMITTED", "ADMITTED"),
                                sql.query("SELECT state FROM remote_text_jobs ORDER BY id").map { it[0] })
                            writerHeld.countDown()
                            assertTrue("Owned writer barrier must not exceed 500ms",
                                releaseWriter.await(500, TimeUnit.MILLISECONDS))
                        }
                    }
                    await(writerHeld)
                    val fastDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(450)
                    val closing = pool.submit {
                        closeCaller.set(Thread.currentThread())
                        f.transport.close()
                    }
                    awaitBefore(cleanupEntered, fastDeadline)
                    awaitAllCallsCancelled(f, fastDeadline)
                    assertTrue("Both still-owned sockets must close before SQL cleanup can finish",
                        f.observer.sockets.all { it.isClosed })
                    assertEquals(2, repository.closed.size)
                    assertEquals(2, repository.handles.size)
                    repository.handles.values.forEach {
                        assertEquals(SendDenial.STATE_UNAVAILABLE, it.check())
                    }
                    assertEquals("The actual database writer remains held", 1L, releaseWriter.count)
                    assertFalse("The close caller is still waiting for durable cleanup", closing.isDone)
                    assertFalse(firstResult.isDone)
                    assertFalse(secondResult.isDone)
                    val repeated = pool.submit { f.transport.close() }
                    repeated.get(remaining(fastDeadline), TimeUnit.NANOSECONDS)
                    releaseWriter.countDown()
                    writer.get(2, TimeUnit.SECONDS)
                    closing.get(2, TimeUnit.SECONDS)
                    assertCancelled(firstResult.get(2, TimeUnit.SECONDS))
                    assertCancelled(secondResult.get(2, TimeUnit.SECONDS))
                    assertStopped(firstEvents)
                    assertStopped(secondEvents)
                    assertEquals(listOf("CANCELLED", "CANCELLED"), f.records.jobs().map { it[1] })
                    assertTrue(f.records.annotations().isEmpty())
                    assertEquals(originals, f.records.originals())
                    f.requestsReleased(2, 0)
                } finally {
                    releaseWriter.countDown()
                    f.releaseTails()
                    f.transport.close()
                    pool.shutdownNow()
                    assertTrue("Only owned workers must terminate", pool.awaitTermination(2, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test(timeout = 10_000)
    fun alreadyAdmittedCallbackCanFinishButClosePreventsEveryLaterPreviewAndCommit() {
        ConnectionProtocol.entries.forEach { protocol ->
            listOf(true, false).forEach { pauseAtStarted ->
                SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
                    val otherIntent = approveOther(f.records, "late-callback")
                    val originals = f.records.originals()
                    f.enqueueHeld(protocol)
                    val repository = ObservedRepository(f.records.repository) { }
                    val first = f.exchange(repository = repository)
                    val second = f.exchange(repository = repository, intent = otherIntent)
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val secondText = CountDownLatch(1)
                    val paused = AtomicBoolean()
                    val firstEvents = events()
                    val secondEvents = events()
                    val pool = Executors.newFixedThreadPool(3)
                    try {
                        val firstResult = pool.submit<SourceBoundSendOutcome> {
                            first.execute { event ->
                                firstEvents.add(event)
                                val boundary = if (pauseAtStarted) event == StreamEvent.Started
                                    else event is StreamEvent.TextDelta
                                if (boundary && paused.compareAndSet(false, true)) {
                                    entered.countDown()
                                    await(release)
                                }
                            }
                        }
                        if (!entered.await(2, TimeUnit.SECONDS)) {
                            val outcome = if (firstResult.isDone) firstResult.get() else "RUNNING"
                            fail("Callback admission barrier: protocol=$protocol, pauseAtStarted=$pauseAtStarted, " +
                                "outcome=$outcome, calls=${f.observer.calls.size}, requests=${f.serverA.requestCount}")
                        }
                        val secondResult = pool.submit<SourceBoundSendOutcome> {
                            second.execute {
                                secondEvents.add(it)
                                if (it is StreamEvent.TextDelta) secondText.countDown()
                            }
                        }
                        await(secondText)
                        val firstPreview = typedEvents(firstEvents)
                        val secondPreview = typedEvents(secondEvents)
                        val closing = pool.submit { f.transport.close() }
                        closing.get(500, TimeUnit.MILLISECONDS)
                        assertEquals(1L, release.count)
                        assertFalse("The admitted consumer is still finishing", firstResult.isDone)
                        assertEquals(2, repository.closed.size)
                        assertTrue(f.observer.calls.all { it.isCanceled() })
                        assertTrue(f.observer.sockets.all { it.isClosed })
                        assertCancelled(secondResult.get(2, TimeUnit.SECONDS))
                        release.countDown()
                        assertCancelled(firstResult.get(2, TimeUnit.SECONDS))
                        assertEquals(firstPreview, typedEvents(firstEvents))
                        assertEquals(secondPreview, typedEvents(secondEvents))
                        assertStopped(firstEvents, expectedText = !pauseAtStarted)
                        assertStopped(secondEvents)
                        assertEquals(listOf("CANCELLED", "CANCELLED"), f.records.jobs().map { it[1] })
                        assertTrue(f.records.annotations().isEmpty())
                        assertEquals(originals, f.records.originals())
                        f.requestsReleased(2, 0)
                    } finally {
                        release.countDown()
                        f.releaseTails()
                        f.transport.close()
                        pool.shutdownNow()
                        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
                    }
                }
            }
        }
    }

    @Test(timeout = 10_000)
    fun cleanupFailureCannotAbandonOtherOwnedCallsAndFatalPropagatesUnchanged() {
        ConnectionProtocol.entries.forEach { protocol ->
            listOf(false, true).forEach { fatal ->
                SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
                    val otherIntent = approveOther(f.records, "cleanup-failure")
                    val originals = f.records.originals()
                    f.enqueueHeld(protocol)
                    val closeCaller = AtomicReference<Thread>()
                    val closeCleanupFinished = CountDownLatch(1)
                    val closeCancelledJobs = ConcurrentHashMap.newKeySet<String>()
                    val delivered = AtomicBoolean()
                    val marker = "synthetic-private-transport-cleanup-marker"
                    val failure: Throwable = if (fatal) object : VirtualMachineError(marker) { }
                        else AssertionError(marker)
                    val repository = ObservedRepository(f.records.repository) { job ->
                        if (Thread.currentThread() === closeCaller.get()) {
                            closeCancelledJobs.add(job.id)
                        } else {
                            // Hold execution-owner cleanup outside SQL so it cannot mask
                            // an abandoned second-phase cancellation by the close caller.
                            await(closeCleanupFinished)
                        }
                    }
                    repository.afterCancel = {
                        // The actual SQL cancellation succeeds before this close-caller fault.
                        if (Thread.currentThread() === closeCaller.get() &&
                            delivered.compareAndSet(false, true)) throw failure
                    }
                    val first = f.exchange(repository = repository)
                    val second = f.exchange(repository = repository, intent = otherIntent)
                    val firstText = CountDownLatch(1)
                    val secondText = CountDownLatch(1)
                    val firstEvents = events()
                    val secondEvents = events()
                    val pool = Executors.newFixedThreadPool(4)
                    try {
                        val firstResult = pool.submit<SourceBoundSendOutcome> {
                            first.execute {
                                firstEvents.add(it)
                                if (it is StreamEvent.TextDelta) firstText.countDown()
                            }
                        }
                        await(firstText)
                        val secondResult = pool.submit<SourceBoundSendOutcome> {
                            second.execute {
                                secondEvents.add(it)
                                if (it is StreamEvent.TextDelta) secondText.countDown()
                            }
                        }
                        await(secondText)
                        assertEquals(listOf("ADMITTED", "ADMITTED"), f.records.jobs().map { it[1] })
                        val closing = pool.submit {
                            closeCaller.set(Thread.currentThread())
                            try { f.transport.close() } finally { closeCleanupFinished.countDown() }
                        }
                        if (fatal) {
                            try {
                                closing.get(2, TimeUnit.SECONDS)
                                fail("Fatal cleanup failure must propagate to the close caller")
                            } catch (caught: ExecutionException) {
                                assertSame("Fatal propagation must preserve the original object",
                                    failure, caught.cause)
                            }
                        } else {
                            closing.get(2, TimeUnit.SECONDS)
                        }
                        assertTrue("The intended close-caller fault must be exercised", delivered.get())
                        assertEquals("The close caller must attempt durable cancellation for both jobs",
                            repository.handles.keys.toSet(), closeCancelledJobs)
                        val firstOutcome = firstResult.get(2, TimeUnit.SECONDS)
                        val secondOutcome = secondResult.get(2, TimeUnit.SECONDS)
                        assertCancelled(firstOutcome)
                        assertCancelled(secondOutcome)
                        assertStopped(firstEvents)
                        assertStopped(secondEvents)
                        assertFalse(firstOutcome.toString().contains(marker))
                        assertFalse(secondOutcome.toString().contains(marker))
                        assertFalse(firstEvents.toString().contains(marker))
                        assertFalse(secondEvents.toString().contains(marker))
                        assertTrue(f.observer.calls.all { it.isCanceled() })
                        assertTrue(f.observer.sockets.all { it.isClosed })
                        assertEquals(2, repository.closed.size)
                        assertEquals(listOf("CANCELLED", "CANCELLED"), f.records.jobs().map { it[1] })
                        assertTrue(f.records.annotations().isEmpty())
                        assertEquals(originals, f.records.originals())
                        pool.submit { f.transport.close() }.get(500, TimeUnit.MILLISECONDS)
                        f.requestsReleased(2, 0)
                    } finally {
                        closeCleanupFinished.countDown()
                        f.releaseTails()
                        f.transport.close()
                        pool.shutdownNow()
                        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
                    }
                }
            }
        }
    }

    @Test(timeout = 10_000)
    fun transportsSharingInjectedClientKeepOtherActiveCallsNewCallsAndCommittedResults() {
        ConnectionProtocol.entries.forEach { protocol ->
            SourceBoundLoopbackFixture(protocol).use { f ->
                RemoteTextFixture(protocol).use { foreign ->
                    val primaryRepository = ObservedRepository(f.records.repository) { }
                    val first = f.exchange(repository = primaryRepository).execute()
                    assertEquals(StreamEvent.Completed, first.terminal)
                    val accepted = checkNotNull(first.annotation)
                    val committedRows = annotationRows(f.records)
                    val primaryOther = approveOther(f.records, "primary-active")
                    val foreignOther = approveOther(foreign, "foreign-next")
                    val originals = f.records.originals()
                    val foreignOriginals = foreign.originals()
                    val primaryBody = f.enqueueHeld(protocol)
                    // This isolation test keeps a live stream while real SQL and new calls execute.
                    val independentBody = f.enqueueHeld(protocol, keepAlive = true)
                    val independent = f.additionalTransport()
                    val foreignRepository = ObservedRepository(foreign.repository) { }
                    val primaryExchange = f.exchange(repository = primaryRepository, intent = primaryOther)
                    val independentExchange = independent.newSourceBoundExchange(
                        foreignRepository, foreign.intent, maxOutputTokens = 73,
                        secrets = f.resolver(), clock = foreign.clock)
                    val primaryText = CountDownLatch(1)
                    val independentText = CountDownLatch(1)
                    val primaryEvents = events()
                    val independentEvents = events()
                    val pool = Executors.newFixedThreadPool(3)
                    try {
                        val primaryResult = pool.submit<SourceBoundSendOutcome> {
                            primaryExchange.execute {
                                primaryEvents.add(it)
                                if (it is StreamEvent.TextDelta) primaryText.countDown()
                            }
                        }
                        await(primaryText)
                        val independentResult = pool.submit<SourceBoundSendOutcome> {
                            independentExchange.execute {
                                independentEvents.add(it)
                                if (it is StreamEvent.TextDelta) independentText.countDown()
                            }
                        }
                        await(independentText)
                        assertEquals(3, f.observer.calls.size)
                        val survivingCall = f.observer.calls[2]
                        val survivingSocket = f.observer.sockets[2]
                        pool.submit { f.transport.close() }.get(500, TimeUnit.MILLISECONDS)
                        assertCancelled(primaryResult.get(2, TimeUnit.SECONDS))
                        assertStopped(primaryEvents)
                        assertFalse(independentResult.isDone)
                        assertFalse(survivingCall.isCanceled())
                        assertFalse(survivingSocket.isClosed)
                        assertEquals(1L, independentBody.release.count)
                        assertTrue(foreignRepository.closed.isEmpty())
                        foreignRepository.handles.values.forEach { assertNull(it.check()) }
                        assertEquals(committedRows, annotationRows(f.records))
                        f.serverA.enqueue(MockResponse.Builder().code(200)
                            .addHeader("Content-Type", "text/event-stream")
                            .body(Buffer().write(SourceBoundLoopbackFixture.complete(protocol))).build())
                        val next = independent.newSourceBoundExchange(foreignRepository, foreignOther,
                            maxOutputTokens = 73, secrets = f.resolver(), clock = foreign.clock).execute()
                        assertEquals(StreamEvent.Completed, next.terminal)
                        assertEquals(foreignOther.source, checkNotNull(next.annotation).source)
                        assertFalse(independentResult.isDone)
                        assertFalse(survivingCall.isCanceled())
                        val resolver = f.resolver()
                        val cachedEvents = mutableListOf<StreamEvent>()
                        val cached = independent.newSourceBoundExchange(primaryRepository, f.records.intent,
                            maxOutputTokens = 73, secrets = resolver, clock = f.records.clock).execute(cachedEvents::add)
                        assertEquals(StreamEvent.Completed, cached.terminal)
                        assertTrue(cached.alreadyCommitted)
                        assertNull(cached.wireOutcome)
                        assertEquals(accepted.jobId, checkNotNull(cached.annotation).jobId)
                        assertEquals(accepted.candidate.text, cached.annotation!!.candidate.text)
                        assertEquals(accepted.candidate.references, cached.annotation!!.candidate.references)
                        assertTrue(cachedEvents.isEmpty())
                        assertTrue(resolver.references.isEmpty())
                        assertEquals(committedRows, annotationRows(f.records))
                        assertEquals(4, f.serverA.requestCount)
                        assertEquals(0, f.serverB.requestCount)
                        assertTrue("The independent active stream must send real heartbeats", independentBody.heartbeats.get() > 0)
                        independentBody.release.countDown()
                        val completed = independentResult.get(2, TimeUnit.SECONDS)
                        assertEquals(StreamEvent.Completed, completed.terminal)
                        assertEquals(SourceBoundLoopbackFixture.TEXT + SourceBoundLoopbackFixture.LATE_TEXT,
                            checkNotNull(completed.annotation).candidate.text)
                        assertEquals(1, independentEvents.count { it == StreamEvent.Completed })
                        assertEquals(2, foreign.annotations().size)
                        assertEquals(committedRows, annotationRows(f.records))
                        assertEquals(originals, f.records.originals())
                        assertEquals(foreignOriginals, foreign.originals())
                        primaryBody.release.countDown()
                        independent.close()
                        f.requestsReleased(4, 0)
                    } finally {
                        f.releaseTails()
                        f.transport.close()
                        independent.close()
                        pool.shutdownNow()
                        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
                    }
                }
            }
        }
    }

    private fun approveOther(f: RemoteTextFixture, suffix: String): RemoteSendIntent {
        val other = f.second()
        assertNotEquals(f.db.pid, other.pid)
        val source = SourceVersion("synthetic-unrelated-source", 1)
        assertNull(f.store(other).updateAuthorization(source,
            f.generation(source, other), ProcessingPolicy.REMOTE_ALLOWED, f.selected,
            f.grant.copy(consentId = "synthetic-close-$suffix", source = source)))
        return RemoteSendIntent(source, TextPurpose.ORGANIZATION)
    }

    private fun annotationRows(f: RemoteTextFixture): List<List<Any?>> =
        f.db.read("SELECT * FROM text_annotations ORDER BY jobId")
            .map { row -> row.map { if (it is ByteArray) it.toList() else it } }

    private fun typedEvents(events: List<StreamEvent>): List<Any> =
        events.map { if (it is StreamEvent.TextDelta) Pair("TextDelta", it.text) else it }

    /** Observation only: every authority check, admission and mutation still uses product SQL. */
    private class ObservedRepository(
        private val actual: SourceBoundTextRepository,
        private val onCancel: (SourceBoundTextJob) -> Unit,
    ) : SourceBoundTextRepository by actual {
        val closed = ConcurrentHashMap.newKeySet<String>()
        val handles = ConcurrentHashMap<String, SourceBoundTextSubscription>()
        @Volatile var afterCancel: ((SourceBoundTextJob) -> Unit)? = null

        override fun subscribe(
            job: SourceBoundTextJob,
            clock: () -> Instant,
            onInvalidated: (SendDenial) -> Unit,
        ): SourceBoundTextSubscription {
            val owned = actual.subscribe(job, clock, onInvalidated)
            handles[job.id] = owned
            return object : SourceBoundTextSubscription by owned {
                override fun close() {
                    try { owned.close() } finally { closed.add(job.id) }
                }
            }
        }

        override fun cancel(job: SourceBoundTextJob) {
            onCancel(job)
            actual.cancel(job)
            afterCancel?.invoke(job)
        }
    }

    private fun events(): MutableList<StreamEvent> = Collections.synchronizedList(mutableListOf())

    private fun assertCancelled(outcome: SourceBoundSendOutcome) {
        assertEquals(StreamEvent.Cancelled, outcome.terminal)
        val guarded = checkNotNull(outcome.wireOutcome)
        assertEquals(StreamEvent.Cancelled, guarded.terminal)
        assertEquals(StreamEvent.Cancelled, checkNotNull(guarded.wireOutcome).terminal)
        assertNull(outcome.annotation)
    }

    private fun assertStopped(events: List<StreamEvent>, expectedText: Boolean = true) {
        assertEquals(if (expectedText) listOf(SourceBoundLoopbackFixture.TEXT) else emptyList<String>(),
            events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        assertFalse(events.contains(StreamEvent.Completed))
        assertFalse(events.filterIsInstance<StreamEvent.TextDelta>()
            .any { it.text.contains(SourceBoundLoopbackFixture.LATE_TEXT) })
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("Owned test barrier must complete", latch.await(2, TimeUnit.SECONDS))
    }

    private fun remaining(deadline: Long): Long = (deadline - System.nanoTime()).coerceAtLeast(1)

    private fun awaitBefore(latch: CountDownLatch, deadline: Long) {
        assertTrue("Cleanup entry must precede the bounded observation deadline",
            latch.await(remaining(deadline), TimeUnit.NANOSECONDS))
    }

    private fun awaitAllCallsCancelled(f: SourceBoundLoopbackFixture, deadline: Long) {
        while (f.observer.calls.any { !it.isCanceled() } && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        assertEquals(2, f.observer.calls.size)
        assertTrue("Every owned call must cancel before the first durable cleanup can complete",
            f.observer.calls.all { it.isCanceled() })
    }
}
