package com.t1doo.aiyou.ai

import com.t1doo.aiyou.data.PythonSqliteDatabase
import com.t1doo.aiyou.data.RemoteTextFixture
import com.t1doo.aiyou.data.RemoteTextTransactions
import com.t1doo.aiyou.domain.SendDenial
import com.t1doo.aiyou.domain.SourceBoundTextRepository
import com.t1doo.aiyou.model.*
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Persisted authority is changed by a second real SQLite PID; HTTP is the actual transport. */
class SourceBoundRevocationHttpContractTest {
    @Test(timeout = 10_000) fun persistedWithdrawalCancelsAnOwnedSocketWithoutAnyNewResponseBytes() {
        protocols { protocol -> SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
            val other = second(f.records); val repository = f.records.store(other)
            val events = events(); val text = CountDownLatch(1); val held = checkNotNull(f.selectedBody)
            val exchange = f.exchange()
            workers(f) { pool ->
                val result = pool.submit<SourceBoundSendOutcome> { exchange.execute { event ->
                    events.add(event); if (event is StreamEvent.TextDelta) text.countDown()
                } }
                await(text); await(held.prefixWritten)
                revoke(f.records, other, repository)
                val outcome = result.get(2, TimeUnit.SECONDS)
                assertEquals("Cancellation must finish while the server still withholds every tail byte", 1L, held.release.count)
                denied(outcome, SourceCommitDenial.AUTHORIZATION_CHANGED, events)
                f.cancelledSocketReleased(); assertTrue(f.records.annotations().isEmpty())
                held.release.countDown(); await(held.finished)
            }
        } }
    }

    @Test(timeout = 10_000) fun persistedDeletionRevisionConfigurationAndExpirySuppressEveryLaterPreview() {
        protocols { protocol -> repeat(4) { variant ->
            SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
                val other = second(f.records); val repository = f.records.store(other)
                val events = events(); val text = CountDownLatch(1)
                workers(f) { pool ->
                    val result = pool.submit<SourceBoundSendOutcome> { f.exchange().execute { event ->
                        events.add(event); if (event is StreamEvent.TextDelta) text.countDown()
                    } }
                    await(text)
                    val expected = when (variant) {
                        0 -> { assertNull(repository.tombstone(f.records.source, generation(f.records, other))); SourceCommitDenial.SOURCE_DELETED }
                        1 -> { f.records.revise(other); SourceCommitDenial.SOURCE_CHANGED }
                        2 -> {
                            val changed = f.records.connectionB
                            assertNull(repository.updateAuthorization(f.records.source, generation(f.records, other),
                                ProcessingPolicy.REMOTE_ALLOWED, changed, f.records.grant.copy(connection = changed)))
                            SourceCommitDenial.AUTHORIZATION_CHANGED
                        }
                        else -> { f.now.set(f.records.grant.expiresAt); SourceCommitDenial.CONSENT_NOT_CURRENT }
                    }
                    denied(result.get(2, TimeUnit.SECONDS), expected, events,
                        guardRejection = if (variant == 3) SendDenial.CONSENT_EXPIRED else null)
                    f.cancelledSocketReleased(); assertTrue(f.records.annotations().isEmpty())
                    checkNotNull(f.selectedBody).release.countDown()
                }
            }
        } }
    }

    @Test(timeout = 10_000) fun coalescedAndSingleByteReadsHaveIdenticalPreviewsAfterCrossConnectionWithdrawal() {
        protocols { protocol ->
            val summaries = mutableListOf<List<Any>>()
            listOf(8192, 1).forEach { readSize ->
                SourceBoundLoopbackFixture(protocol, heldTail = true, readBufferBytes = readSize,
                    heldResponsePrefix = SourceBoundLoopbackFixture.openPreview(protocol) + SourceBoundLoopbackFixture.continuation(protocol)).use { f ->
                    val other = second(f.records); val repository = f.records.store(other)
                    val events = mutableListOf<StreamEvent>(); var withdrawn = false
                    val outcome = f.exchange().execute { event ->
                        events.add(event)
                        if (event is StreamEvent.TextDelta && !withdrawn) {
                            withdrawn = true; revoke(f.records, other, repository)
                        }
                    }
                    assertTrue(withdrawn); denied(outcome, SourceCommitDenial.AUTHORIZATION_CHANGED, events)
                    summaries.add(events.map { event ->
                        if (event is StreamEvent.TextDelta) Pair("TextDelta", event.text) else event
                    }); f.cancelledSocketReleased()
                    assertTrue(f.records.annotations().isEmpty())
                }
            }
            assertEquals(summaries[0], summaries[1])
        }
    }

    @Test(timeout = 10_000) fun initialCheckAndDelegatePublicationRacesDenyBeforeAnyHttpOrFallback() {
        protocols { protocol -> repeat(3) { variant -> SourceBoundLoopbackFixture(protocol).use { f ->
            val other = second(f.records); val repository = f.records.store(other)
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val intercepted = AtomicBoolean()
            val resolver = if (variant == 2) f.resolver { entered.countDown(); await(release) } else f.resolver()
            val exchange = f.exchange(resolver)
            workers(f) { pool ->
                if (variant == 0) revoke(f.records, other, repository)
                if (variant == 1) f.records.db.afterSql = { sql ->
                    if (sql == "COMMIT" && intercepted.compareAndSet(false, true)) {
                        // Actual initial authority transaction has released SQLite's writer lock,
                        // but its result/handle has not yet returned to the HTTP owner.
                        entered.countDown(); await(release)
                    }
                }
                try {
                    val result = pool.submit<SourceBoundSendOutcome> { exchange.execute() }
                    if (variant != 0) { await(entered); revoke(f.records, other, repository); release.countDown() }
                    val outcome = result.get(2, TimeUnit.SECONDS)
                    assertEquals(StreamEvent.Failed(FailureKind.SEND_DENIED), outcome.terminal)
                    assertEquals(SourceCommitDenial.AUTHORIZATION_CHANGED, outcome.storageDenial)
                    assertNull(outcome.annotation); f.noRequests(); assertTrue(f.records.annotations().isEmpty())
                    if (variant == 2) f.wiped(resolver) else assertTrue(resolver.references.isEmpty())
                } finally { release.countDown(); f.records.db.afterSql = null }
            }
        } } }
    }

    @Test(timeout = 10_000) fun directSubscriptionChecksInitialPersistedInvalidityAndSignalsExactlyOnce() {
        RemoteTextFixture().use { f ->
            val job = f.queued(); val other = second(f); val repository = f.store(other)
            revoke(f, other, repository)
            val signals = mutableListOf<SendDenial>(); val committedCallbacks = AtomicInteger()
            val subscription = f.repository.subscribe(job, f.clock) { denial ->
                // This second connection can immediately acquire the writer transaction:
                // no product SQL transaction encloses the notification.
                assertEquals("CANCELLED", other.read("SELECT state FROM remote_text_jobs WHERE id=?", job.id).single()[0])
                committedCallbacks.incrementAndGet(); signals.add(denial)
            }
            try {
                assertEquals(listOf(SendDenial.QUEUE_INVALIDATED), signals)
                repeat(3) { assertEquals(SendDenial.QUEUE_INVALIDATED, subscription.check()) }
                assertEquals(1, committedCallbacks.get()); assertTrue(f.annotations().isEmpty())
            } finally { subscription.close() }
        }
    }

    @Test(timeout = 10_000) fun triggerFailureAndRealOuterRollbackDoNotInvalidateAValidActiveStream() {
        protocols { protocol -> listOf(false, true).forEach { outerRollback ->
            SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
                val other = second(f.records); val repository = f.records.store(other)
                val text = CountDownLatch(1); val events = events()
                workers(f) { pool ->
                    val result = pool.submit<SourceBoundSendOutcome> { f.exchange().execute { event ->
                        events.add(event); if (event is StreamEvent.TextDelta) text.countDown()
                    } }
                    await(text)
                    val before = authorityRows(other); val beforeJobs = other.read("SELECT id,state FROM remote_text_jobs ORDER BY id")
                    val priorGeneration = generation(f.records, other)
                    if (outerRollback) {
                        val statements = mutableListOf<String>(); other.afterSql = { statements.add(it) }
                        try {
                            other.transaction { sql ->
                                assertNull(repository.updateAuthorization(f.records.source, priorGeneration,
                                    ProcessingPolicy.LOCAL_ONLY, f.records.selected, f.records.grant))
                                assertEquals("LOCAL_ONLY", sql.query("SELECT privacyPolicy FROM entries WHERE id=?",
                                    listOf(f.records.source.sourceId)).single()[0])
                                assertEquals("CANCELLED", sql.query("SELECT state FROM remote_text_jobs").single()[0])
                                throw OwnedRollback()
                            }
                            fail("Outer test transaction must actually roll back")
                        } catch (_: OwnedRollback) { /* expected owned test rollback */ }
                        finally { other.afterSql = null }
                        assertTrue(statements.any { it.startsWith("SAVEPOINT owned_nested_") })
                        assertTrue(statements.any { it.startsWith("RELEASE SAVEPOINT owned_nested_") })
                        assertTrue(statements.contains("ROLLBACK"))
                    } else {
                        other.write("CREATE TRIGGER owned_authority_abort BEFORE UPDATE ON remote_source_access BEGIN SELECT RAISE(ABORT,'synthetic authority rollback'); END")
                        assertEquals(SourceCommitDenial.STORAGE_UNAVAILABLE, repository.updateAuthorization(f.records.source,
                            priorGeneration, ProcessingPolicy.LOCAL_ONLY, f.records.selected, f.records.grant))
                        other.write("DROP TRIGGER owned_authority_abort")
                    }
                    assertEquals(before, authorityRows(other)); assertEquals(beforeJobs, other.read("SELECT id,state FROM remote_text_jobs ORDER BY id"))
                    assertFalse(result.isDone)
                    checkNotNull(f.selectedBody).release.countDown()
                    val outcome = result.get(2, TimeUnit.SECONDS)
                    assertEquals(StreamEvent.Completed, outcome.terminal); assertNotNull(outcome.annotation)
                    assertNull(outcome.storageDenial); assertEquals(1, f.records.annotations().size)
                    assertEquals(SourceBoundLoopbackFixture.TEXT + SourceBoundLoopbackFixture.LATE_TEXT, outcome.annotation!!.candidate.text)
                    assertEquals(1, events.count { it == StreamEvent.Completed }); f.selectedRequestReleased()
                }
            }
        } }
    }

    @Test(timeout = 10_000) fun closingSubscriptionDuringAnInFlightSqlReadReturnsWithoutWaitingOrLateSignal() {
        RemoteTextFixture().use { f ->
            val job = f.queued(); val other = second(f); val repository = f.store(other)
            val caller = Thread.currentThread(); val entered = CountDownLatch(1); val allowRead = CountDownLatch(1)
            val actualReadCommitted = CountDownLatch(1); val returnRead = CountDownLatch(1); val readReturned = CountDownLatch(1)
            val signal = CountDownLatch(1); val intercept = AtomicBoolean(); val armed = AtomicBoolean()
            val subscription = f.repository.subscribe(job, f.clock) { signal.countDown() }
            f.db.beforeSql = { sql ->
                if (armed.get() && Thread.currentThread() !== caller && sql == "BEGIN IMMEDIATE" && intercept.compareAndSet(false, true)) {
                    entered.countDown(); await(allowRead)
                }
            }
            f.db.afterSql = { sql ->
                if (intercept.get() && Thread.currentThread() !== caller && sql == "COMMIT") {
                    actualReadCommitted.countDown(); await(returnRead); readReturned.countDown()
                }
            }
            armed.set(true)
            val pool = Executors.newSingleThreadExecutor()
            try {
                await(entered); revoke(f, other, repository); allowRead.countDown(); await(actualReadCommitted)
                pool.submit { subscription.close() }.get(1, TimeUnit.SECONDS)
                assertEquals(1L, returnRead.count)
                returnRead.countDown(); await(readReturned)
                assertFalse("A SQL read completing after close cannot reserve a notification", signal.await(250, TimeUnit.MILLISECONDS))
                assertEquals(SendDenial.STATE_UNAVAILABLE, subscription.check())
            } finally {
                allowRead.countDown(); returnRead.countDown(); subscription.close()
                f.db.beforeSql = null; f.db.afterSql = null; pool.shutdownNow(); assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test(timeout = 10_000) fun withdrawingOneSourceDoesNotCancelAnotherActiveOwnedHttpOnTheSameDatabase() {
        protocols { protocol -> SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
            val other = second(f.records); val repository = f.records.store(other)
            val source = SourceVersion("synthetic-unrelated-source", 1)
            val grant = f.records.grant.copy(consentId = "synthetic-other-grant", source = source)
            assertNull(repository.updateAuthorization(source, 0, ProcessingPolicy.REMOTE_ALLOWED, f.records.selected, grant))
            val otherBody = f.enqueueHeld(protocol)
            val firstText = CountDownLatch(1); val secondText = CountDownLatch(1)
            val firstEvents = events(); val secondEvents = events()
            workers(f, 2) { pool ->
                val first = pool.submit<SourceBoundSendOutcome> { f.exchange().execute { event ->
                    firstEvents.add(event); if (event is StreamEvent.TextDelta) firstText.countDown()
                } }
                await(firstText)
                val second = pool.submit<SourceBoundSendOutcome> { f.exchange(intent = RemoteSendIntent(source, TextPurpose.ORGANIZATION)).execute { event ->
                    secondEvents.add(event); if (event is StreamEvent.TextDelta) secondText.countDown()
                } }
                await(secondText); revoke(f.records, other, repository)
                denied(first.get(2, TimeUnit.SECONDS), SourceCommitDenial.AUTHORIZATION_CHANGED, firstEvents)
                assertFalse("A different current source remains active", second.isDone)
                assertEquals(1L, otherBody.release.count)
                otherBody.release.countDown()
                val success = second.get(2, TimeUnit.SECONDS)
                assertEquals(StreamEvent.Completed, success.terminal); assertEquals(source, success.annotation!!.source)
                assertEquals(SourceBoundLoopbackFixture.TEXT + SourceBoundLoopbackFixture.LATE_TEXT, success.annotation!!.candidate.text)
                assertEquals(1, secondEvents.count { it == StreamEvent.Completed })
                assertEquals(listOf(source.sourceId), f.records.db.read("SELECT entryId FROM text_annotations").map { it[0] })
                f.requestsReleased(2, 0)
            }
        } }
    }

    @Test(timeout = 10_000) fun actualPersistedReadFailureFailsClosedAndCancelsTheSocketWithoutProviderFallback() {
        protocols { protocol -> SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
            val other = second(f.records); val text = CountDownLatch(1); val events = events()
            workers(f) { pool ->
                val result = pool.submit<SourceBoundSendOutcome> { f.exchange().execute { event ->
                    events.add(event); if (event is StreamEvent.TextDelta) text.countDown()
                } }
                await(text)
                // Real SQL failure, rather than a replacement repository or injected business denial.
                other.write("DROP TABLE remote_source_access")
                denied(result.get(2, TimeUnit.SECONDS), SourceCommitDenial.STORAGE_UNAVAILABLE, events)
                assertTrue(f.records.annotations().isEmpty()); f.cancelledSocketReleased()
            }
        } }
    }

    @Test(timeout = 10_000) fun committedResultSurvivesLateCancelAndDuplicateRecoveryWithoutAnotherHttp() {
        protocols { protocol -> SourceBoundLoopbackFixture(protocol).use { f ->
            val other = second(f.records); val repository = f.records.store(other)
            val annotationInserted = AtomicBoolean(); val commitObserved = AtomicBoolean()
            val committed = CountDownLatch(1); val returnCommit = CountDownLatch(1); val cancelEntered = CountDownLatch(1)
            val actual = f.records.repository
            val observedRepository = object : SourceBoundTextRepository by actual {
                override fun cancel(job: SourceBoundTextJob) {
                    // SourceBoundExchange.cancel reaches this only after its HTTP cancellation.
                    cancelEntered.countDown(); actual.cancel(job)
                }
            }
            f.records.db.afterSql = { sql ->
                if (sql.startsWith("INSERT INTO text_annotations")) annotationInserted.set(true)
                if (sql == "COMMIT" && annotationInserted.get() && commitObserved.compareAndSet(false, true)) {
                    // The real SQLite COMMIT succeeded, while its JVM owner still holds the
                    // adapter monitor and has not returned to SourceBoundExchange.accepted.
                    committed.countDown(); await(returnCommit)
                }
            }
            try {
                val exchange = f.transport.newSourceBoundExchange(observedRepository, f.records.intent,
                    maxOutputTokens = 73, secrets = f.resolver(), clock = f.records.clock)
                workers(f, 2) { pool ->
                    try {
                    val executing = pool.submit<SourceBoundSendOutcome> { exchange.execute() }
                    await(committed)
                    assertEquals("COMMITTED", other.read("SELECT state FROM remote_text_jobs").single()[0])
                    val committedRows = annotationRows(other); assertEquals(1, committedRows.size)
                    assertFalse("COMMIT succeeded but execute has not accepted or returned", executing.isDone)
                    val cancelling = pool.submit { exchange.cancel() }
                    await(cancelEntered)
                    assertFalse("Durable cleanup waits for the actual transaction owner monitor", cancelling.isDone)
                    returnCommit.countDown()
                    val first = executing.get(2, TimeUnit.SECONDS); cancelling.get(2, TimeUnit.SECONDS)
                    assertEquals(StreamEvent.Completed, first.terminal); val annotation = checkNotNull(first.annotation)
                    assertEquals(committedRows, annotationRows(other))
                    val rows = annotationRows(other); exchange.cancel()
                    assertEquals(rows, annotationRows(other)); assertEquals("COMMITTED", f.records.jobs().single()[1])
                    val queued = repository.queue(f.records.intent, "original-organization-v1", f.records.clock)
                    assertTrue(queued is SourceQueueOutcome.AlreadyCommitted)
                    val job = (queued as SourceQueueOutcome.AlreadyCommitted).job
                    val duplicate = repository.commit(job, annotation.candidate, f.records.clock)
                    assertTrue(duplicate is SourceCommitOutcome.Committed); assertTrue((duplicate as SourceCommitOutcome.Committed).alreadyCommitted)
                    assertEquals(SourceCommitOutcome.Denied(SourceCommitDenial.RESULT_CONFLICT),
                        repository.commit(job, f.records.candidate("synthetic different candidate"), f.records.clock))
                    val resolver = f.resolver(); val previews = mutableListOf<StreamEvent>()
                    val cached = f.exchange(resolver).execute(previews::add)
                    assertEquals(StreamEvent.Completed, cached.terminal); assertTrue(cached.alreadyCommitted)
                    assertEquals(annotation.candidate.text, cached.annotation!!.candidate.text)
                    assertEquals(annotation.candidate.references, cached.annotation!!.candidate.references); assertNull(cached.wireOutcome)
                    assertTrue(previews.isEmpty()); assertTrue(resolver.references.isEmpty())
                    assertEquals(rows, annotationRows(other)); f.selectedRequestReleased()
                    } finally { returnCommit.countDown() }
                }
            } finally { returnCommit.countDown(); f.records.db.afterSql = null }
        } }
    }

    @Test(timeout = 10_000) fun subscriptionInvalidationIsStickyAcrossPersistedPermissionAbaAndClosesItsWatch() {
        RemoteTextFixture().use { f ->
            val job = f.queued(); val other = second(f); val repository = f.store(other)
            val signals = Collections.synchronizedList(mutableListOf<SendDenial>()); val signalled = CountDownLatch(1)
            val subscription = f.repository.subscribe(job, f.clock) { signals.add(it); signalled.countDown() }
            try {
                assertNull(repository.updateAuthorization(f.source, generation(f, other), ProcessingPolicy.LOCAL_ONLY, f.selected, f.grant))
                await(signalled)
                assertEquals(listOf(SendDenial.POLICY_BLOCKED), signals)
                assertNull(repository.updateAuthorization(f.source, generation(f, other), ProcessingPolicy.REMOTE_ALLOWED, f.selected, f.grant))
                repeat(3) { assertEquals(SendDenial.POLICY_BLOCKED, subscription.check()) }
                assertEquals(1, signals.size); assertTrue(f.repository.check(job, f.clock) != null)
                assertEquals("CANCELLED", f.jobs().single()[1]); assertTrue(f.annotations().isEmpty())
                subscription.close()
                assertEquals(SendDenial.STATE_UNAVAILABLE, subscription.check())
            } finally { subscription.close() }
        }
    }

    @Test(timeout = 10_000) fun initialSubscriptionReadRacingARealCommittedResultDoesNotInventInvalidation() {
        RemoteTextFixture().use { f ->
            val job = f.admitted(); val other = second(f); val repository = f.store(other)
            val readCommitted = CountDownLatch(1); val release = CountDownLatch(1); val intercepted = AtomicBoolean()
            val signals = AtomicInteger(); val pool = Executors.newSingleThreadExecutor()
            f.db.afterSql = { sql -> if (sql == "COMMIT" && intercepted.compareAndSet(false, true)) {
                readCommitted.countDown(); await(release)
            } }
            try {
                val opening = pool.submit<com.t1doo.aiyou.domain.SourceBoundTextSubscription> {
                    f.repository.subscribe(job, f.clock) { signals.incrementAndGet() }
                }
                await(readCommitted)
                val committed = repository.commit(job, f.candidate(), f.clock)
                assertTrue(committed is SourceCommitOutcome.Committed)
                release.countDown()
                val subscription = opening.get(2, TimeUnit.SECONDS)
                try {
                    assertNull(subscription.check()); assertEquals(0, signals.get())
                    assertEquals("COMMITTED", other.read("SELECT state FROM remote_text_jobs WHERE id=?", job.id).single()[0])
                    assertEquals(1, f.annotations().size)
                } finally { subscription.close() }
            } finally {
                release.countDown(); f.db.afterSql = null; pool.shutdownNow(); assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test(timeout = 10_000) fun backgroundClockCancellationCancelsBlockedSocketAndCleansOnlyOwnedUnfinishedJob() {
        protocols { protocol -> SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
            val other = second(f.records)
            val cancelledClock = AtomicBoolean(); val observed = CountDownLatch(1)
            val observerThread = AtomicReference<Thread>(); val ownerThread = AtomicReference<Thread>()
            val text = CountDownLatch(1); val previews = events()
            workers(f) { pool ->
                val result = pool.submit<SourceBoundSendOutcome> {
                    ownerThread.set(Thread.currentThread())
                    f.exchange(clock = {
                        if (cancelledClock.get()) {
                            observerThread.compareAndSet(null, Thread.currentThread()); observed.countDown()
                            throw java.util.concurrent.CancellationException("synthetic-private-background-clock-marker")
                        }
                        f.now.get()
                    }).execute { event ->
                        previews.add(event); if (event is StreamEvent.TextDelta) text.countDown()
                    }
                }
                await(text); cancelledClock.set(true); await(observed)
                val outcome = result.get(2, TimeUnit.SECONDS)
                assertNotSame("With no new wire bytes the first observation must be off the HTTP owner", ownerThread.get(), observerThread.get())
                assertEquals(StreamEvent.Cancelled, outcome.terminal); assertNull(outcome.storageDenial); assertNull(outcome.annotation)
                assertEquals(StreamEvent.Cancelled, checkNotNull(outcome.wireOutcome).terminal)
                assertEquals(listOf(SourceBoundLoopbackFixture.TEXT), previews.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
                assertFalse(previews.contains(StreamEvent.Completed))
                assertFalse(outcome.toString().contains("synthetic-private-background-clock-marker"))
                assertEquals("CANCELLED", other.read("SELECT state FROM remote_text_jobs").single()[0])
                assertTrue(f.records.annotations().isEmpty()); f.cancelledSocketReleased()
                assertEquals(1L, checkNotNull(f.selectedBody).release.count)
            }
        } }
    }

    private fun denied(outcome: SourceBoundSendOutcome, reason: SourceCommitDenial, previews: List<StreamEvent>,
                       guardRejection: SendDenial? = null) {
        assertEquals(StreamEvent.Failed(FailureKind.SEND_DENIED), outcome.terminal)
        assertEquals(reason, outcome.storageDenial); assertNull(outcome.annotation)
        val guard = checkNotNull(outcome.wireOutcome)
        assertEquals(if (guardRejection == null) StreamEvent.Cancelled else StreamEvent.Failed(FailureKind.SEND_DENIED), guard.terminal)
        assertEquals(guardRejection, guard.resultDenial); assertNull(guard.denial); assertNull(guard.receipt)
        assertEquals(StreamEvent.Cancelled, checkNotNull(guard.wireOutcome).terminal)
        assertEquals(listOf(SourceBoundLoopbackFixture.TEXT), previews.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        assertFalse(previews.contains(StreamEvent.Completed))
        assertFalse(previews.toString().contains(SourceBoundLoopbackFixture.LATE_TEXT))
        assertFalse(outcome.toString().contains("synthetic-private-source-text"))
        assertFalse(outcome.toString().contains("synthetic-private-key"))
    }

    private fun second(f: RemoteTextFixture): PythonSqliteDatabase = f.second().also { assertNotEquals(f.db.pid, it.pid) }
    private fun generation(f: RemoteTextFixture, db: PythonSqliteDatabase) = f.generation(connection = db)
    private fun revoke(f: RemoteTextFixture, db: PythonSqliteDatabase, repository: RemoteTextTransactions) {
        assertNull(repository.updateAuthorization(f.source, generation(f, db), ProcessingPolicy.REMOTE_ALLOWED,
            f.selected, f.grant.copy(revoked = true)))
    }
    private fun events(): MutableList<StreamEvent> = Collections.synchronizedList(mutableListOf())
    private fun await(latch: CountDownLatch) { assertTrue("Owned test barrier must complete", latch.await(2, TimeUnit.SECONDS)) }
    private fun authorityRows(db: PythonSqliteDatabase): List<List<Any?>> =
        normalized(db.read("SELECT * FROM remote_source_access ORDER BY entryId")) +
            db.read("SELECT id,privacyPolicy,currentRevision FROM entries ORDER BY id")
    private fun annotationRows(db: PythonSqliteDatabase) = normalized(db.read("SELECT * FROM text_annotations ORDER BY jobId"))
    private fun normalized(rows: List<List<Any?>>) = rows.map { row -> row.map { if (it is ByteArray) it.toList() else it } }
    private inline fun protocols(action: (ConnectionProtocol) -> Unit) = ConnectionProtocol.entries.forEach(action)
    private inline fun workers(f: SourceBoundLoopbackFixture, count: Int = 1, action: (ExecutorService) -> Unit) {
        val owned = Executors.newFixedThreadPool(count)
        try { action(owned) } finally {
            f.releaseTails(); f.transport.close(); owned.shutdownNow()
            assertTrue("Only owned HTTP workers must terminate", owned.awaitTermination(1, TimeUnit.SECONDS))
        }
    }
    private class OwnedRollback : RuntimeException("Owned synthetic rollback")
}
