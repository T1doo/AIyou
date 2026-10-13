package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.ai.*
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Actual VM/controller + production remote SQL + actual loopback HTTP.
 * The local SQLite driver proves VM wiring, not Room/SQLCipher/Android lifecycle execution.
 */
class LocalRecordsAiContractTest {
    @Test(timeout = 10_000) fun defaultsNoneAndUnavailableCapabilityNeverBindResolveOrSend() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(PythonSqliteDatabase.repositoryRoot().resolve("app/src/main/AndroidManifest.xml").toFile())
        val nodes = manifest.getElementsByTagName("*")
        val network = (0 until nodes.length).map { nodes.item(it) as Element }.filter {
            it.tagName.startsWith("uses-permission") &&
                it.getAttributeNS("http://schemas.android.com/apk/res/android", "name") == "android.permission.INTERNET"
        }
        assertTrue("Source manifest must not request INTERNET; removal markers deny imported permissions",
            network.all { it.getAttributeNS("http://schemas.android.com/tools", "node") == "remove" })
        protocols { protocol -> repeat(3) { variant ->
            SourceBoundLoopbackFixture(protocol, initiallyApproved = variant == 2).use { f ->
                if (variant == 1) f.records.approve(ProcessingPolicy.AI_NONE)
                val h = Harness(f, enabled = variant != 2)
                try {
                    h.ready()
                    val id = h.open()
                    assertTrue(id > 0)
                    assertEquals(RecordAiPhase.BLOCKED, h.vm.state.value.ai!!.phase)
                    h.vm.startAiSession(id)
                    localSave(h)
                    assertEquals(RecordAiPhase.BLOCKED, h.vm.state.value.ai!!.phase)
                    assertEquals(0, h.factoryCalls.get())
                    assertTrue(h.resolver.references.isEmpty())
                    f.noRequests()
                    assertTrue(f.records.jobs().isEmpty())
                    assertTrue(f.records.annotations().isEmpty())
                } finally { h.finish() }
            }
        } }
    }

    @Test(timeout = 10_000) fun heldLazyFactoryDoesNotBlockLocalSaveAndStoppedLateFactoryNeverSends() {
        protocols { protocol -> SourceBoundLoopbackFixture(protocol).use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val h = Harness(f, beforeFactory = { entered.countDown(); await(release) })
            try {
                h.ready(); val id = h.open(); h.vm.startAiSession(id); await(entered)
                assertEquals(RecordAiPhase.RUNNING, h.vm.state.value.ai!!.phase)
                localSave(h)
                assertNotSame(h.factoryThread.get(), h.store.saveThread.get())
                h.vm.stopAiSession(id)
                assertEquals(RecordAiPhase.CANCELLED, h.vm.state.value.ai!!.phase)
                release.countDown(); await(h.capClosed)
                assertEquals(RecordAiPhase.CANCELLED, h.vm.state.value.ai!!.phase)
                assertTrue(h.vm.state.value.ai!!.preview.isEmpty())
                assertEquals(0, h.executeCount.get())
                f.noRequests(); assertTrue(f.records.jobs().isEmpty())
            } finally { release.countDown(); h.finish() }
        } }
    }

    @Test(timeout = 10_000) fun heldSocketAllowsLocalSaveAndOnlyTheBoundOriginalGetsCommittedCandidate() {
        protocols { protocol -> SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
            val h = Harness(f)
            try {
                h.ready(); val id = h.open(); h.vm.startAiSession(id)
                h.waitState { it.ai?.preview == SourceBoundLoopbackFixture.TEXT }
                assertTrue(f.records.annotations().isEmpty())
                localSave(h)
                assertEquals(RecordAiPhase.RUNNING, h.vm.state.value.ai!!.phase)
                assertEquals(1L, f.selectedBody!!.release.count)
                f.selectedBody!!.release.countDown()
                val ui = h.phase(RecordAiPhase.SUCCEEDED).ai!!
                assertTrue(ui.preview.isEmpty())
                assertEquals(f.records.source, ui.annotation!!.source)
                assertEquals(SourceBoundLoopbackFixture.TEXT + SourceBoundLoopbackFixture.LATE_TEXT, ui.annotation!!.candidate.text)
                assertEquals(listOf(OriginalTextReference(f.records.source, 0,
                    f.records.original.codePointCount(0, f.records.original.length), f.records.original)), ui.annotation!!.candidate.references)
                assertEquals(listOf(f.records.source.sourceId), f.records.db.read("SELECT entryId FROM text_annotations").map { it[0] })
                assertEquals(f.records.original, h.vm.state.value.entries.single { it.id == f.records.source.sourceId }.originalText)
                f.selectedRequestReleased(); f.wiped(h.resolver)
            } finally { f.releaseTails(); h.finish() }
        } }
    }

    @Test(timeout = 10_000) fun stopRejectsAnActuallyAdmittedButLatePreviewAndNeverCommits() {
        protocols { protocol -> SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val held = AtomicBoolean()
            val h = Harness(f, beforePreview = { event ->
                if (event is StreamEvent.TextDelta && held.compareAndSet(false, true)) {
                    entered.countDown(); await(release)
                }
            })
            try {
                h.ready(); val id = h.open(); h.vm.startAiSession(id); await(entered)
                assertTrue(h.vm.state.value.ai!!.preview.isEmpty())
                h.vm.stopAiSession(id)
                val stopped = h.vm.state.value.ai!!
                assertEquals(RecordAiPhase.CANCELLED, stopped.phase)
                release.countDown(); await(h.executeEnded)
                assertEquals(stopped, h.vm.state.value.ai)
                assertEquals(StreamEvent.Cancelled, h.outcomes.single().terminal)
                assertTrue(f.records.annotations().isEmpty())
                f.cancelledSocketReleased()
            } finally { release.countDown(); f.releaseTails(); h.finish() }
        } }
    }

    @Test(timeout = 10_000) fun sourceSwitchAndSameSourceAbaHaveNewTokensRejectOldCallbacksAndDoNotDuplicateStart() {
        protocols { protocol -> listOf(false, true).forEach { sameSource ->
            SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
                val otherSource = approveOther(f.records)
                val entered = CountDownLatch(1); val release = CountDownLatch(1); val held = AtomicBoolean()
                val h = Harness(f, beforePreview = { event ->
                    if (event is StreamEvent.TextDelta && held.compareAndSet(false, true)) {
                        entered.countDown(); await(release)
                    }
                })
                val monitor = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                try {
                    h.ready(); val old = h.open(); h.vm.startAiSession(old); await(entered)
                    val target = if (sameSource) f.records.source else otherSource
                    val next = h.vm.openAiSession(target.sourceId, target.revision.toInt())
                    assertTrue(next > old)
                    val late = AtomicInteger()
                    monitor.launch(start = CoroutineStart.UNDISPATCHED) {
                        h.vm.state.collect { if (it.ai?.sessionId == old) late.incrementAndGet() }
                    }
                    h.vm.stopAiSession(old); h.vm.closeAiSession(old); h.vm.startAiSession(old)
                    assertEquals(next, h.vm.state.value.ai!!.sessionId)
                    if (!sameSource) enqueueComplete(f, protocol)
                    h.vm.startAiSession(next); h.vm.startAiSession(next)
                    release.countDown()
                    val final = h.phase(if (sameSource) RecordAiPhase.FAILED else RecordAiPhase.SUCCEEDED).ai!!
                    assertEquals(next, final.sessionId)
                    assertEquals(0, late.get())
                    assertEquals(1, h.factoryCalls.get())
                    assertEquals(if (sameSource) 1 else 2, f.serverA.requestCount)
                    assertEquals(0, f.serverB.requestCount)
                    if (sameSource) {
                        assertNull(final.annotation)
                        assertEquals(SourceCommitDenial.JOB_INVALID, h.outcomes.last().storageDenial)
                        assertEquals(1, f.records.jobs().size)
                        assertTrue(f.records.annotations().isEmpty())
                    } else {
                        assertEquals(otherSource, final.annotation!!.source)
                        assertEquals(listOf(otherSource.sourceId), f.records.db.read("SELECT entryId FROM text_annotations").map { it[0] })
                    }
                } finally { monitor.cancel(); release.countDown(); f.releaseTails(); h.finish() }
            }
        } }
    }

    @Test(timeout = 10_000) fun realLocalRevisionCancelsOldSessionAndCannotReuseItsConsentOrLateText() {
        protocols { protocol -> SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val held = AtomicBoolean()
            val h = Harness(f, beforePreview = { event ->
                if (event is StreamEvent.TextDelta && held.compareAndSet(false, true)) {
                    entered.countDown(); await(release)
                }
            })
            try {
                h.ready(); val id = h.open(); h.vm.startAiSession(id); await(entered)
                h.vm.beginOriginalEdit(f.records.source.sourceId, 3)
                val edit = h.vm.state.value.originalEdit!!
                h.vm.changeOriginalEdit(edit.sessionId, "synthetic revised original🙂")
                h.vm.saveOriginalEdit(edit.sessionId)
                h.waitState { it.originalEdit == null && it.entries.any { e -> e.id == f.records.source.sourceId && e.currentRevision == 4 } }
                release.countDown(); await(h.executeEnded)
                assertTrue(h.vm.state.value.ai?.preview.isNullOrEmpty())
                assertNull(h.vm.state.value.ai?.annotation)
                assertEquals(0L, h.vm.openAiSession(f.records.source.sourceId, 3))
                val next = h.vm.openAiSession(f.records.source.sourceId, 4)
                assertTrue(next > id); h.vm.startAiSession(next); h.phase(RecordAiPhase.FAILED)
                assertEquals(1, f.serverA.requestCount); assertEquals(0, f.serverB.requestCount)
                assertEquals(1, h.resolver.references.size)
                assertTrue(f.records.annotations().isEmpty())
                val revisions = runBlocking { h.store.revisions(f.records.source.sourceId) }
                assertEquals(f.records.original, revisions.first().originalText)
                assertEquals("synthetic revised original🙂", revisions.last().originalText)
            } finally { release.countDown(); f.releaseTails(); h.finish() }
        } }
    }

    @Test(timeout = 10_000) fun wireCompletionOnlyConfirmsUntilRealCommitAndSqlRollbackNeverShowsSuccess() {
        protocols { protocol -> listOf(false, true).forEach { failCommit ->
            SourceBoundLoopbackFixture(protocol).use { f ->
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                val h = Harness(f, wrapRepository = { actual ->
                    object : SourceBoundTextRepository by actual {
                        override fun commit(job: SourceBoundTextJob, candidate: DerivedTextCandidate,
                                            clock: () -> java.time.Instant): SourceCommitOutcome {
                            entered.countDown(); await(release)
                            return actual.commit(job, candidate, clock)
                        }
                    }
                })
                try {
                    h.ready(); val originals = f.records.originals()
                    val id = h.open(); h.vm.startAiSession(id); await(entered)
                    val confirming = h.phase(RecordAiPhase.CONFIRMING).ai!!
                    assertNull(confirming.annotation)
                    assertTrue(f.records.annotations().isEmpty())
                    assertEquals("ADMITTED", f.records.jobs().single()[1])
                    if (failCommit) f.records.db.write("CREATE TRIGGER owned_vm_commit_abort BEFORE UPDATE ON remote_text_jobs WHEN OLD.state='ADMITTED' AND NEW.state='COMMITTED' BEGIN SELECT RAISE(ABORT,'owned VM commit fault'); END")
                    release.countDown()
                    val final = h.phase(if (failCommit) RecordAiPhase.FAILED else RecordAiPhase.SUCCEEDED).ai!!
                    assertTrue(final.preview.isEmpty())
                    if (failCommit) {
                        assertNull(final.annotation)
                        assertTrue(f.records.annotations().isEmpty())
                        assertEquals("CANCELLED", f.records.jobs().single()[1])
                        assertEquals(SourceCommitDenial.STORAGE_UNAVAILABLE, h.outcomes.single().storageDenial)
                    } else {
                        assertEquals(f.records.source, final.annotation!!.source)
                        assertEquals(SourceBoundLoopbackFixture.TEXT, final.annotation!!.candidate.text)
                        assertEquals(1, f.records.annotations().size)
                        assertEquals("COMMITTED", f.records.jobs().single()[1])
                    }
                    assertEquals(originals, f.records.originals())
                    f.selectedRequestReleased()
                } finally { release.countDown(); h.finish() }
            }
        } }
    }

    @Test(timeout = 10_000) fun missingOrThrowingSecretFailsWithoutHttpAndDoesNotPoisonLocalSave() {
        protocols { protocol -> listOf(false, true).forEach { throwing ->
            SourceBoundLoopbackFixture(protocol).use { f ->
                val calls = AtomicInteger(); val marker = "synthetic-private-vm-secret-marker"
                val secret = SecretResolver {
                    calls.incrementAndGet()
                    if (throwing) throw IllegalStateException(marker)
                    null
                }
                val h = Harness(f, secrets = secret)
                try {
                    h.ready(); val id = h.open(); h.vm.startAiSession(id)
                    val failed = h.phase(RecordAiPhase.FAILED)
                    assertNull(failed.error)
                    assertNull(failed.ai!!.annotation)
                    assertFalse(failed.ai!!.notice.contains(marker))
                    assertFalse(failed.ai!!.toString().contains(marker))
                    assertEquals(1, calls.get())
                    f.noRequests(); assertTrue(f.records.annotations().isEmpty())
                    localSave(h)
                    assertNull(h.vm.state.value.error)
                    assertEquals(RecordAiPhase.FAILED, h.vm.state.value.ai!!.phase)
                    f.noRequests()
                } finally { h.finish() }
            }
        } }
    }

    @Test(timeout = 10_000) fun disposeWaitsForLateFactoryAndClosesItsCapabilityBeforeSharedDatabase() {
        protocols { protocol -> SourceBoundLoopbackFixture(protocol).use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val h = Harness(f, beforeFactory = { entered.countDown(); await(release) })
            try {
                h.ready(); val id = h.open(); h.vm.startAiSession(id); await(entered)
                h.vm.clearForHostTest()
                assertNull(h.vm.state.value.ai)
                assertFalse(h.store.closingEntered.await(100, TimeUnit.MILLISECONDS))
                assertTrue(alive(f.records.db.pid))
                assertEquals(0L, h.vm.openAiSession(f.records.source.sourceId, 3))
                release.countDown(); h.finish()
                assertEquals(1, h.capCloseCalls.get())
                assertEquals(0, h.executeCount.get())
                assertTrue(h.events.indexOf("CAP_CLOSE") < h.events.indexOf("DB_CLOSE"))
                assertFalse(alive(f.records.db.pid))
                assertNull(h.vm.state.value.ai); f.noRequests()
            } finally { release.countDown(); h.finish() }
        } }
    }

    @Test(timeout = 10_000) fun disposeSuppressesLateAdmittedCallbackAndJoinsAiBeforeClosingSharedDatabase() {
        protocols { protocol -> SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
            val witness = f.records.second(); assertNotEquals(f.records.db.pid, witness.pid)
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val held = AtomicBoolean()
            val h = Harness(f, beforePreview = { event ->
                if (event is StreamEvent.TextDelta && held.compareAndSet(false, true)) {
                    entered.countDown(); await(release)
                }
            })
            try {
                h.ready(); val id = h.open(); h.vm.startAiSession(id); await(entered)
                h.vm.clearForHostTest()
                assertNull(h.vm.state.value.ai)
                assertFalse(h.store.closingEntered.await(100, TimeUnit.MILLISECONDS))
                assertTrue(alive(f.records.db.pid))
                release.countDown(); h.finish()
                assertEquals(0, h.activeExecutions.get())
                assertTrue(h.events.indexOf("EXECUTE_EXIT") < h.events.indexOf("DB_CLOSE"))
                assertTrue(h.events.indexOf("CAP_CLOSE") < h.events.indexOf("DB_CLOSE"))
                assertEquals("CANCELLED", witness.read("SELECT state FROM remote_text_jobs").single()[0])
                assertTrue(witness.read("SELECT jobId FROM text_annotations").isEmpty())
                val closedUi = h.vm.state.value
                h.vm.changeText("synthetic forbidden post-dispose input")
                h.vm.startAiSession(id)
                assertEquals(closedUi, h.vm.state.value)
                assertFalse(alive(f.records.db.pid))
                f.cancelledSocketReleased()
            } finally { release.countDown(); f.releaseTails(); h.finish() }
        } }
    }

    /** Offline real-SQL supporting contract: an already-returning watch transaction must drain. */
    @Test(timeout = 10_000) fun storageSealDrainsAnActualSubscribedSqlTransactionAndRejectsNewChecks() {
        RemoteTextFixture().use { f ->
            val port = ReturningSqlBarrier(f.db)
            val sql = DrainingRecordsSqlDatabase(port)
            val actual = RemoteTextTransactions(sql)
            val store = SqliteVmEntryRepository(f.db, sql, actual, sql::stopAndAwaitIdle)
            val job = (actual.queue(f.intent, "original-organization-v1", f.clock) as SourceQueueOutcome.Queued).job
            val signals = AtomicInteger()
            val watch = actual.subscribe(job, f.clock) { signals.incrementAndGet() }
            val pool = Executors.newSingleThreadExecutor()
            try {
                port.armed.set(true); await(port.entered)
                // Actual COMMIT and the Python monitor have already completed; only the
                // RecordsSqlDatabase call return remains held, inside production draining.
                watch.close()
                val closing = pool.submit { store.close() }
                await(store.closingEntered)
                assertFalse(store.closed.await(100, TimeUnit.MILLISECONDS))
                assertTrue(alive(f.db.pid))
                assertEquals(SendDenial.STATE_UNAVAILABLE, actual.check(job, f.clock))
                assertFalse(closing.isDone)
                port.release.countDown(); closing.get(2, TimeUnit.SECONDS)
                assertEquals(0, signals.get())
                assertFalse(alive(f.db.pid))
                assertNull(store.closeFailure.get())
            } finally {
                port.release.countDown(); watch.close(); store.close()
                pool.shutdown(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test(timeout = 10_000) fun everyVmCasRejectsNewRevisionWithOldAiContentForIncomingAndExistingPreview() {
        protocols { protocol -> listOf(false, true).forEach { existingPreview ->
            SourceBoundLoopbackFixture(protocol, heldTail = true).use { f ->
                val snapshot = SnapshotGetBarrier(f.records.source.sourceId)
                val h = Harness(f, entriesDecorator = snapshot::wrap, beforePreview = { event ->
                    if (!existingPreview && event is StreamEvent.TextDelta) snapshot.arm(Thread.currentThread())
                })
                val monitor = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val violations = AtomicInteger()
                try {
                    h.ready()
                    monitor.launch(start = CoroutineStart.UNDISPATCHED) {
                        h.vm.state.collect { ui ->
                            val version = ui.entries.firstOrNull { it.id == f.records.source.sourceId }?.currentRevision
                            val ai = ui.ai
                            if (version != null && version.toLong() != f.records.source.revision &&
                                ai?.source == f.records.source && (ai.preview.isNotEmpty() || ai.annotation != null)) {
                                violations.incrementAndGet()
                            }
                        }
                    }
                    val id = h.open(); h.vm.startAiSession(id)
                    if (existingPreview) {
                        h.waitState { it.ai?.preview == SourceBoundLoopbackFixture.TEXT }
                        h.port.armed.set(true); await(h.port.entered)
                        assertNotSame(h.factoryThread.get(), h.port.heldThread.get())
                    } else await(snapshot.entered)
                    val other = f.records.second(); assertNotEquals(f.records.db.pid, other.pid)
                    f.records.revise(other)
                    // The initial capture draft is already acknowledged, so Save can publish
                    // new entries before its finally/reconcile takes the controller lock.
                    h.vm.save()
                    h.waitState { ui -> ui.saveFeedbackId == 1L && ui.entries.any {
                        it.id == f.records.source.sourceId && it.currentRevision == 4
                    } }
                    assertEquals("No local CAS may publish new revision with old AI content", 0, violations.get())
                    snapshot.release.countDown(); h.port.release.countDown(); await(h.executeEnded)
                    h.waitState { it.ai == null || it.ai!!.preview.isEmpty() }
                    assertEquals("No incoming AI CAS may publish the stale snapshot", 0, violations.get())
                    assertNull(h.vm.state.value.ai?.annotation)
                    assertTrue(f.records.annotations().isEmpty())
                    f.cancelledSocketReleased()
                } finally {
                    snapshot.release.countDown(); h.port.release.countDown(); monitor.cancel()
                    f.releaseTails(); h.finish()
                }
            }
        } }
    }

    private class Harness(
        val f: SourceBoundLoopbackFixture,
        enabled: Boolean = true,
        secrets: SecretResolver? = null,
        wrapRepository: (SourceBoundTextRepository) -> SourceBoundTextRepository = { it },
        private val beforeFactory: () -> Unit = {},
        private val beforePreview: (StreamEvent) -> Unit = {},
        entriesDecorator: (List<StoredEntry>) -> List<StoredEntry> = { it },
    ) {
        val port = ReturningSqlBarrier(f.records.db)
        val sql = DrainingRecordsSqlDatabase(port)
        val actual = RemoteTextTransactions(sql)
        val repository = wrapRepository(actual)
        val store = SqliteVmEntryRepository(f.records.db, sql, repository, sql::stopAndAwaitIdle)
        val resolver = f.resolver()
        private val chosenSecrets = secrets ?: resolver
        private val clock = Clock.fixed(f.records.now, ZoneOffset.UTC)
        val factoryCalls = AtomicInteger(); val factoryThread = AtomicReference<Thread>()
        val executeCount = AtomicInteger(); val activeExecutions = AtomicInteger()
        val executeEnded = CountDownLatch(1); val capClosed = CountDownLatch(1)
        val capCloseCalls = AtomicInteger()
        val outcomes = CopyOnWriteArrayList<SourceBoundSendOutcome>()
        val events = CopyOnWriteArrayList<String>()
        val vm: LocalRecordsViewModel

        init {
            store.decorateEntries = entriesDecorator
            store.beforeClose = {
                assertEquals("AI execute must end before the shared records close", 0, activeExecutions.get())
                if (factoryCalls.get() > 0) assertTrue("Every created capability must close first", capCloseCalls.get() > 0)
                events.add("DB_CLOSE")
            }
            vm = LocalRecordsViewModel(Application(), { store }, clock,
                decodePng = { error("Images are outside this contract") },
                bindAi = if (enabled) ({ supplied: EntryRepository -> bindCapability(supplied) }) else null)
        }

        private fun bindCapability(supplied: EntryRepository): RecordAiCapability {
            assertSame(store, supplied)
            factoryCalls.incrementAndGet(); factoryThread.set(Thread.currentThread())
            beforeFactory()
            val actualCapability = SourceBoundRecordAiCapability(f.transport, repository, chosenSecrets, clock, 73)
            return object : RecordAiCapability {
                override fun newExchange(intent: RemoteSendIntent): SourceBoundStreamingExchange {
                    val owned = actualCapability.newExchange(intent)
                    return object : SourceBoundStreamingExchange {
                        override fun execute(onEvent: (StreamEvent) -> Unit): SourceBoundSendOutcome {
                            executeCount.incrementAndGet(); activeExecutions.incrementAndGet()
                            try {
                                val result = owned.execute { event -> beforePreview(event); onEvent(event) }
                                outcomes.add(result)
                                return result
                            } finally {
                                activeExecutions.decrementAndGet(); events.add("EXECUTE_EXIT"); executeEnded.countDown()
                            }
                        }
                        override fun cancel() = owned.cancel()
                    }
                }
                override fun close() {
                    try { actualCapability.close() }
                    finally { capCloseCalls.incrementAndGet(); events.add("CAP_CLOSE"); capClosed.countDown() }
                }
            }
        }

        fun ready() = waitState { it.ready && !it.opening }
        fun open() = vm.openAiSession(f.records.source.sourceId, f.records.source.revision.toInt())
        fun phase(phase: RecordAiPhase) = waitState { it.ai?.phase == phase }
        fun waitState(predicate: (LocalRecordUiState) -> Boolean): LocalRecordUiState = runBlocking {
            withTimeout(2_000) { vm.state.first(predicate) }
        }
        fun finish() {
            vm.clearForHostTest()
            assertTrue("Owned AI and local IO must join before records close", store.closed.await(2, TimeUnit.SECONDS))
            store.closeFailure.get()?.let { throw AssertionError("Shared SQL owner close failed", it) }
            assertEquals(0, activeExecutions.get())
        }
    }

    /** Delay only an actual transaction's return, after COMMIT and after the Python monitor exits. */
    private class ReturningSqlBarrier(private val actual: RecordsSqlDatabase) : RecordsSqlDatabase {
        val armed = AtomicBoolean()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val heldThread = AtomicReference<Thread>()
        override fun <T> transaction(block: (RecordsSqlConnection) -> T): T {
            val result = actual.transaction(block)
            if (armed.compareAndSet(true, false)) {
                heldThread.set(Thread.currentThread()); entered.countDown(); await(release)
            }
            return result
        }
    }

    /** Scheduling only: returns the original immutable values from the real SQL list. */
    private class SnapshotGetBarrier(private val sourceId: String) {
        private val armed = AtomicBoolean(); private val used = AtomicBoolean()
        private val owner = AtomicReference<Thread>()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fun arm(thread: Thread) {
            if (used.compareAndSet(false, true)) { owner.set(thread); armed.set(true) }
        }
        fun wrap(actual: List<StoredEntry>): List<StoredEntry> = object : AbstractList<StoredEntry>() {
            override val size get() = actual.size
            override fun get(index: Int): StoredEntry {
                val value = actual[index]
                if (value.id == sourceId && Thread.currentThread() === owner.get() && armed.compareAndSet(true, false)) {
                    entered.countDown(); await(release)
                }
                return value
            }
        }
    }

    private fun localSave(h: Harness) {
        val feedback = h.vm.state.value.saveFeedbackId
        h.vm.save()
        val ui = h.waitState { it.saveFeedbackId == feedback + 1 && !it.saving && !it.savePending }
        val saved = ui.entries.single { it.id == "synthetic-draft" }
        assertEquals(ProcessingPolicy.LOCAL_ONLY, saved.policy)
        assertEquals("synthetic-private-capture-draft", saved.originalText)
        assertEquals(listOf("LOCAL_ONLY"), h.f.records.db.read("SELECT privacyPolicy FROM entries WHERE id=?", saved.id).map { it[0] })
        assertEquals(listOf("PENDING"), h.f.records.db.read("SELECT state FROM local_jobs WHERE entryId=?", saved.id).map { it[0] })
    }

    private fun approveOther(f: RemoteTextFixture): SourceVersion {
        val other = f.second(); assertNotEquals(f.db.pid, other.pid)
        val source = SourceVersion("synthetic-unrelated-source", 1)
        assertNull(f.store(other).updateAuthorization(source, f.generation(source, other), ProcessingPolicy.REMOTE_ALLOWED,
            f.selected, f.grant.copy(consentId = "synthetic-vm-other-grant", source = source)))
        return source
    }
    private fun enqueueComplete(f: SourceBoundLoopbackFixture, protocol: ConnectionProtocol) {
        f.serverA.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
            .body(Buffer().write(SourceBoundLoopbackFixture.complete(protocol))).build())
    }
    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    private inline fun protocols(action: (ConnectionProtocol) -> Unit) = ConnectionProtocol.entries.forEach(action)
    private companion object {
        fun await(latch: CountDownLatch) {
            assertTrue("Owned scheduling barrier must complete", latch.await(2, TimeUnit.SECONDS))
        }
    }
}
