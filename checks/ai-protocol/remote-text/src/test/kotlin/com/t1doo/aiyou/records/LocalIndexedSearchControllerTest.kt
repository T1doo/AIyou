package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Real FTS4 product proof/leases plus actual controller and MutableStateFlow CAS. */
class LocalIndexedSearchControllerTest {
    @Test fun metadataFenceCannotCreateNewEpochOverUncommittedTextAndInputAbaNeedsFreshProof(): Unit = Harness().use { h ->
        h.controller.sync(true); val previous = h.ready()
        assertTrue(h.controller.beforeInputCommit { true })
        h.controller.sync(true) // actual text/ownership deliberately not committed yet
        assertEquals(1, h.matchCount.get())
        assertEquals(OriginalSearchStage.WAITING, h.controller.state.value.stage)
        assertFalse(h.controller.canOpen(previous, previous.hits.single()))
        h.input.set(OriginalSearchInput("别的词", ownership = Any()))
        h.input.set(OriginalSearchInput("合成", ownership = Any()))
        h.controller.sync(true); val current = h.ready()
        assertTrue(current.inputEpoch > previous.inputEpoch)
        assertNotSame(previous.proof, current.proof)
        assertEquals(2, h.matchCount.get())
        assertTrue(h.controller.canOpen(current, current.hits.single()))
    }
    @Test fun actualStateFlowCasRejectsReentrantInputFenceAndLeavesRejectedInputUnchanged(): Unit = Harness().use { h ->
        val rejected = CountDownLatch(1)
        val previous = h.input.get()
        h.flow.beforeCas = { update ->
            if (update.stage == OriginalSearchStage.READY) {
                h.flow.beforeCas = null
                if (h.controller.beforeInputCommit { true }) h.input.set(OriginalSearchInput("unsafe", ownership = Any()))
                assertSame(previous, h.input.get())
                rejected.countDown()
            }
        }
        h.controller.sync(true); assertTrue(rejected.await(5, TimeUnit.SECONDS))
        val current = h.ready()
        assertSame(previous, current.input)
        assertTrue(h.controller.canOpen(current, current.hits.single()))
    }
    @Test fun inputFenceOnIndependentThreadWaitsForActualCasThenRetiresOldResultBeforeCommit(): Unit = Harness().use { h ->
        val pool = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val retiring = AtomicReference<Future<Boolean>>()
        try {
            h.flow.beforeCas = { update ->
                if (update.stage == OriginalSearchStage.READY) {
                    h.flow.beforeCas = null
                    retiring.set(pool.submit<Boolean> { started.countDown(); h.controller.beforeInputCommit { true } })
                    assertTrue(started.await(1, TimeUnit.SECONDS))
                    assertThrows(TimeoutException::class.java) { retiring.get().get(100, TimeUnit.MILLISECONDS) }
                }
            }
            h.controller.sync(true); val previous = h.ready()
            assertTrue(retiring.get().get(2, TimeUnit.SECONDS))
            assertEquals(OriginalSearchStage.WAITING, h.controller.visible(h.source.get(), h.input.get()).stage)
            assertFalse(h.controller.canOpen(previous, previous.hits.single()))
            h.input.set(OriginalSearchInput("新词", ownership = Any()))
            h.controller.sync(true); assertTrue(h.ready().hits.isEmpty())
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }
    @Test fun committedIndependentWriteBetweenMatchingAndFinalLeasePublishesNoOldHits(): Unit = Harness().use { h ->
        val computed = CountDownLatch(1); val release = CountDownLatch(1)
        h.afterMatch = { computed.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        h.controller.sync(true)
        assertTrue(computed.await(5, TimeUnit.SECONDS))
        h.fixture.base.second().use { second ->
            second.write("UPDATE entries SET capturedAtMillis=capturedAtMillis+1 WHERE id=?", LocalSearchSqlTest.ID)
        }
        release.countDown()
        h.await { h.invalidations.get() > 0 }
        assertTrue(h.controller.state.value.hits.isEmpty())
        assertNotEquals(OriginalSearchStage.READY, h.controller.visible(h.source.get(), h.input.get()).stage)
    }
    @Test fun actualCalculatedToStateFlowCasInputAbaRejectsOldHitsAndNewInputCanFinish(): Unit = Harness().use { h ->
        val previous = h.input.get().ownership
        val injected = CountDownLatch(1)
        h.flow.beforeCas = { update ->
            if (update.stage == OriginalSearchStage.READY) {
                h.flow.beforeCas = null
                h.controller.changeInput {
                    h.input.set(OriginalSearchInput("别的条件", ownership = Any()))
                    h.input.set(OriginalSearchInput("合成", ownership = Any()))
                }
                injected.countDown()
            }
        }
        h.controller.sync(true)
        assertTrue(injected.await(5, TimeUnit.SECONDS))
        h.await { h.controller.state.value.stage == OriginalSearchStage.WAITING }
        assertTrue(h.controller.state.value.hits.isEmpty())
        h.controller.sync(true); val current = h.ready()
        assertNotSame(previous, current.input.ownership)
        assertSame(h.input.get().ownership, current.input.ownership)
        assertEquals(listOf(LocalSearchSqlTest.ID), current.hits.map { it.entry.id })
    }
    @Test fun actualCasDisposalCannotPublishReadableDataOrAdoptAnOldProof(): Unit = Harness().use { h ->
        val disposed = CountDownLatch(1)
        h.flow.beforeCas = { update ->
            if (update.stage == OriginalSearchStage.READY) {
                h.flow.beforeCas = null; h.controller.close(); disposed.countDown()
            }
        }
        h.controller.sync(true); assertTrue(disposed.await(5, TimeUnit.SECONDS))
        h.await { h.controller.state.value.hits.isEmpty() }
        h.controller.sync(true)
        assertEquals(OriginalSearchStage.WAITING, h.controller.state.value.stage)
        assertTrue(h.controller.state.value.hits.isEmpty())
    }
    @Test fun independentWriterCannotCommitBetweenFinalEpochReadAndActualStateFlowCas(): Unit = Harness().use { h ->
        val second = h.fixture.base.second(); val pool = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val writer = AtomicReference<Future<*>>()
        try {
            second.beforeSql = { if (it == "BEGIN IMMEDIATE") entered.countDown() }
            h.flow.beforeCas = { update ->
                if (update.stage == OriginalSearchStage.READY) {
                    h.flow.beforeCas = null
                    writer.set(pool.submit { second.write("UPDATE entries SET capturedAtMillis=capturedAtMillis+1 WHERE id=?",
                        LocalSearchSqlTest.ID) })
                    assertTrue(entered.await(1, TimeUnit.SECONDS))
                    assertThrows(TimeoutException::class.java) { writer.get().get(100, TimeUnit.MILLISECONDS) }
                }
            }
            h.controller.sync(true); val published = h.ready()
            writer.get().get(2, TimeUnit.SECONDS)
            val located = AtomicInteger(); val finished = CountDownLatch(1)
            h.afterResolve = { finished.countDown() }
            h.controller.open(published, published.hits.single()) { located.incrementAndGet() }
            assertTrue(finished.await(5, TimeUnit.SECONDS)); h.await { h.invalidations.get() > 0 }
            assertEquals(0, located.get())
        } finally { second.beforeSql = null; second.close(); pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }
    @Test fun realIoResolveRetiredInsideLeaseNeverPublishesLocationOrRunsSqlOnCallerThread(): Unit = Harness().use { h ->
        h.controller.sync(true); val value = h.ready(); val caller = Thread.currentThread()
        val finished = CountDownLatch(1); val locations = AtomicInteger()
        h.beforeLocation = {
            assertNotSame(caller, Thread.currentThread())
            h.controller.invalidateInput()
        }
        h.afterResolve = { finished.countDown() }
        h.controller.open(value, value.hits.single()) { locations.incrementAndGet() }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(0, locations.get())
        assertFalse(h.controller.canOpen(value, value.hits.single()))
    }
    @Test fun successfulLocationReadsActualCurrentOriginalUnderLeaseAndPausedResultIsNotAdopted(): Unit = Harness().use { h ->
        h.controller.sync(true); val value = h.ready(); val caller = Thread.currentThread()
        val opened = CountDownLatch(1); val observed = AtomicReference<Thread>()
        h.controller.open(value, value.hits.single()) {
            observed.set(Thread.currentThread()); assertEquals("合成原文", value.hits.single().entry.originalText)
            h.controller.pause(); opened.countDown()
        }
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        assertNotSame(caller, observed.get())
        assertFalse(h.controller.canOpen(value, value.hits.single()))
        h.controller.sync(true); val again = h.ready()
        assertNotSame(value, again)
        assertTrue(h.controller.canOpen(again, again.hits.single()))
    }
    @Test fun replacementOwnerWithSameEntriesIdentityAndEpochRunsFreshLookup(): Unit = Harness().use { h ->
        h.controller.sync(true); val before = h.ready()
        val replacement = object : LocalSearchRepository by h.actual {}
        val previous = h.source.get()
        h.source.set(previous.copy(localSearch = replacement))
        assertSame(previous.entries, h.source.get().entries)
        assertEquals(previous.localSearchEpoch, h.source.get().localSearchEpoch)
        assertEquals(OriginalSearchStage.WAITING, h.controller.visible(h.source.get(), h.input.get()).stage)
        h.controller.sync(true); val current = h.ready()
        assertNotSame(before, current)
        assertSame(replacement, current.owner)
        assertEquals(2, h.matchCount.get())
        assertFalse(h.controller.canOpen(before, before.hits.single()))
    }
    @Test fun actualDeferredForeignKeyCommitFailureAfterPublicationRevokesCompletedAndCache(): Unit = Harness().use { h ->
        h.fixture.base.db.write("CREATE TABLE synthetic_commit_child(parentId TEXT REFERENCES entries(id) DEFERRABLE INITIALLY DEFERRED)")
        val reachedPublication = AtomicBoolean()
        h.flow.beforeCas = { update ->
            if (update.stage == OriginalSearchStage.READY) { reachedPublication.set(true); h.flow.beforeCas = null }
        }
        h.fixture.base.db.beforeCommit = {
            if (reachedPublication.get()) {
                assertEquals(OriginalSearchStage.READY, h.controller.state.value.stage)
                h.fixture.base.db.beforeCommit = null
                h.fixture.base.db.write("INSERT INTO synthetic_commit_child VALUES('missing-synthetic-parent')")
            }
        }
        try {
            h.controller.sync(true)
            h.await { h.invalidations.get() > 0 }
            assertTrue(reachedPublication.get())
            assertEquals(OriginalSearchStage.WAITING, h.controller.state.value.stage)
            assertTrue(h.controller.state.value.hits.isEmpty())
            assertTrue(h.fixture.base.db.read("SELECT * FROM synthetic_commit_child").isEmpty())
            assertTrue(h.fixture.base.db.read("PRAGMA foreign_key_check").isEmpty())
            h.controller.sync(true); assertEquals(1, h.ready().hits.size)
            assertEquals(2, h.matchCount.get())
        } finally { h.fixture.base.db.beforeCommit = null }
    }
    @Test fun actualDeferredForeignKeyCommitFailureAfterLocationRollsBackOnlyThatLocation(): Unit = Harness().use { h ->
        h.controller.sync(true); val value = h.ready()
        h.fixture.base.db.write("CREATE TABLE synthetic_location_child(parentId TEXT REFERENCES entries(id) DEFERRABLE INITIALLY DEFERRED)")
        val location = AtomicBoolean()
        val commits = AtomicInteger()
        h.fixture.base.db.beforeCommit = {
            if (location.get()) {
                h.fixture.base.db.beforeCommit = null
                h.fixture.base.db.write("INSERT INTO synthetic_location_child VALUES('missing-synthetic-parent')")
            }
        }
        h.fixture.base.db.beforeSql = { if (it == "COMMIT" && location.get()) commits.incrementAndGet() }
        try {
            h.controller.open(value, value.hits.single(), rollback = { assertTrue(location.compareAndSet(true, false)) }) {
                location.set(true)
            }
            h.await { h.invalidations.get() > 0 }
            assertEquals(1, commits.get())
            assertFalse(location.get())
            assertTrue(h.controller.state.value.hits.isEmpty())
            assertTrue(h.fixture.base.db.read("SELECT * FROM synthetic_location_child").isEmpty())
        } finally { h.fixture.base.db.beforeCommit = null; h.fixture.base.db.beforeSql = null }
    }
    @Test fun failedLocationTransactionCannotRollbackANewerNavigationToken(): Unit = Harness().use { h ->
        h.controller.sync(true); val value = h.ready()
        h.fixture.base.db.write("CREATE TABLE synthetic_location_child(parentId TEXT REFERENCES entries(id) DEFERRABLE INITIALLY DEFERRED)")
        val published = AtomicBoolean()
        val navigation = AtomicLong()
        val rollbacks = AtomicInteger()
        h.fixture.base.db.beforeCommit = {
            if (published.get()) {
                h.fixture.base.db.beforeCommit = null
                h.fixture.base.db.write("INSERT INTO synthetic_location_child VALUES('missing-synthetic-parent')")
            }
        }
        try {
            h.controller.open(value, value.hits.single(), rollback = {
                if (navigation.compareAndSet(1, 0)) rollbacks.incrementAndGet()
            }) {
                published.set(true); navigation.set(1)
                h.controller.invalidateInput(); navigation.set(2)
            }
            h.await { h.invalidations.get() > 0 }
            assertEquals(2L, navigation.get())
            assertEquals(0, rollbacks.get())
            assertTrue(h.controller.state.value.hits.isEmpty())
        } finally { h.fixture.base.db.beforeCommit = null }
    }
    @Test fun failedIndexedLookupKeepsOwnerWithoutAutomaticRetryLoop(): Unit = Harness().use { h ->
        h.afterMatch = { throw IllegalStateException("synthetic controlled match failure") }
        h.controller.sync(true)
        h.await { h.controller.state.value.stage == OriginalSearchStage.FAILED }
        val failed = h.controller.state.value
        assertSame(h.port, failed.owner)
        repeat(20) { h.controller.sync(true) }
        assertSame(failed, h.controller.state.value)
        assertEquals(1, h.matchCount.get())
    }

    private class ObservedFlow(private val actual: MutableStateFlow<OriginalSearchState>) :
        MutableStateFlow<OriginalSearchState> by actual {
        @Volatile var beforeCas: ((OriginalSearchState) -> Unit)? = null
        override fun compareAndSet(expect: OriginalSearchState, update: OriginalSearchState): Boolean {
            beforeCas?.invoke(update)
            return actual.compareAndSet(expect, update)
        }
    }
    private class Harness : AutoCloseable {
        val fixture = LocalSearchSqlTest.Fixture()
        val gate = DrainingRecordsSqlDatabase(fixture.base.db)
        val tags = SqlManualTagRepository(gate, ManualTaskSqlTest.KEY)
        val actual = SqlLocalSearchRepository(gate, tags)
        @Volatile var afterMatch: (() -> Unit)? = null
        @Volatile var beforeLocation: (() -> Unit)? = null
        @Volatile var afterResolve: (() -> Unit)? = null
        val port = object : LocalSearchRepository by actual {
            override fun resolve(snapshot: LocalSearchSnapshot, entryId: String, publish: (StoredEntry) -> Unit): Boolean =
                try { actual.resolve(snapshot, entryId) { current -> beforeLocation?.invoke(); publish(current) } }
                finally { afterResolve?.invoke() }
        }
        val source: AtomicReference<LocalRecordUiState>
        val input = AtomicReference(OriginalSearchInput("合成", ownership = Any()))
        val invalidations = AtomicInteger()
        val matchCount = AtomicInteger()
        lateinit var flow: ObservedFlow
        val controller: LocalOriginalSearchController
        init {
            fixture.drain()
            source = AtomicReference(LocalRecordUiState(ready = true, opening = false,
                entries = fixture.entries(), localSearch = port, localSearchEpoch = actual.epoch()))
            controller = LocalOriginalSearchController(source::get, input::get, Dispatchers.IO,
                stateFactory = { ObservedFlow(MutableStateFlow(it)).also { flow = it } },
                onInvalidated = { invalidations.incrementAndGet() },
                matcher = { entries, query ->
                    matchCount.incrementAndGet()
                    LocalOriginalSearchMatcher.indexed(entries, query, checkNotNull(source.get().localSearch))
                        .also { afterMatch?.invoke() }
                })
        }
        fun await(predicate: () -> Boolean) = runBlocking { withTimeout(10_000) {
            while (!predicate()) delay(5)
        } }
        fun ready(): OriginalSearchState { await { controller.state.value.stage == OriginalSearchStage.READY }; return controller.state.value }
        override fun close() {
            afterMatch = null; beforeLocation = null; afterResolve = null; flow.beforeCas = null
            controller.close(); gate.stopAndAwaitIdle(); tags.wipeKey(); fixture.close()
        }
    }
}
