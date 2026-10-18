package com.t1doo.aiyou.data

import com.t1doo.aiyou.ai.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.time.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual persistent authority and host SQLite; only synthetic records and in-memory protocol wire.
 * This is neither Android SQLCipher/device acceptance nor a provider/network test. */
class SelectedQuestionConsentControllerTest {
    @Test fun localConfirmationAndReadOnlyReviewNeverAuthorizeUntilSeparateExactOneUseConfirmation() = Harness().use { h ->
        val view = h.confirm(); assertTrue(h.jobs().isEmpty()); assertEquals(0, h.wire.opened.get())
        h.ui.review(view); val review = h.await(QuestionConsentStage.REVIEW).review!!
        assertTrue(h.jobs().isEmpty()); assertEquals(0, h.wire.executions.get())
        assertEquals(2, review.sources.size); assertEquals(view.question, review.request.question)
        h.ui.confirm(review); h.ui.confirm(review)
        val ready = h.await(QuestionConsentStage.READY)
        assertEquals(1, h.wire.executions.get()); assertEquals(listOf("COMMITTED", "COMMITTED"), h.jobs())
        assertNull(h.repo.checkReceipt(ready.receipt!!) { NOW })
        val opened = CountDownLatch(1)
        h.ui.openSource(ready, ready.receipt.reply.paragraphs.first().references.first()) {
            h.outsideLocks(); opened.countDown()
        }
        gate(opened); assertEquals(0, h.f.db.read("SELECT * FROM text_annotations").size)
        // The pending-job observer must not interpret its own COMMITTED group as a revocation.
        Thread.sleep(400); assertSame(ready, h.ui.visible())
    }

    @Test fun defaultCapabilityAndMissingGrantCannotTurnLocalSelectionIntoSendAuthority() {
        Harness(approved = false).use { h ->
            val view = h.confirm(); h.ui.review(view); h.await(QuestionConsentStage.BLOCKED)
            assertTrue(h.jobs().isEmpty()); assertEquals(0, h.wire.opened.get())
        }
        Harness(disabled = true).use { h ->
            h.ui.review(h.confirm()); assertEquals(QuestionConsentStage.UNAVAILABLE, h.ui.visible().stage)
            assertTrue(h.jobs().isEmpty()); assertEquals(0, h.wire.opened.get())
        }
    }

    @Test fun retiredReviewCannotConfirmAfterInputSessionOwnerOrServiceChanges() {
        for (kind in listOf("input", "session", "owner", "service")) Harness().use { h ->
            h.ui.review(h.confirm()); val review = h.await(QuestionConsentStage.REVIEW).review!!
            when (kind) {
                "input" -> h.source.set(OriginalSearchState(OriginalSearchInput("changed"), OriginalSearchStage.BROWSE))
                "session" -> h.session.set(false)
                "owner" -> h.currentOwner.set(null)
                "service" -> h.currentFixture.set(null)
            }
            h.ui.confirm(review); h.ui.sync()
            assertEquals(kind, QuestionConsentStage.STALE, h.ui.visible().stage)
            assertTrue(h.jobs().isEmpty()); assertEquals(0, h.wire.opened.get())
        }
    }

    @Test fun failedReadProofCommitBeforeReviewOrBeforeAuthorizationCannotOpenWire() {
        for (beforeConfirm in listOf(false, true)) Harness().use { h ->
            val view = h.confirm()
            val review = if (beforeConfirm) { h.ui.review(view); h.await(QuestionConsentStage.REVIEW).review } else null
            val once = AtomicBoolean()
            h.beforeProof = { if (once.compareAndSet(false, true)) h.f.db.beforeCommit = { error("synthetic proof COMMIT failure") } }
            if (beforeConfirm) h.ui.confirm(review) else h.ui.review(view)
            h.await(QuestionConsentStage.STALE)
            h.f.db.beforeCommit = null; h.beforeProof = {}
            assertTrue(h.jobs().isEmpty()); assertEquals(0, h.wire.opened.get())
        }
    }

    @Test fun stoppedAuthorizationCleansJustReturnedPermitOnOriginalCancelledIoOwner() = Harness().use { h ->
        val committed = CountDownLatch(1); val release = CountDownLatch(1); val cleaned = CountDownLatch(1)
        h.delegate = object : SelectedQuestionAuthorizationRepository by h.repo {
            override fun authorizeReviewedSend(review: SelectedQuestionSendReview, clock: () -> Instant): SelectedQuestionSendPermit =
                h.repo.authorizeReviewedSend(review, clock).also { committed.countDown(); gate(release) }
            override fun cancelPending(permit: SelectedQuestionSendPermit) { h.repo.cancelPending(permit); cleaned.countDown() }
        }
        h.ui.review(h.confirm()); h.ui.confirm(h.await(QuestionConsentStage.REVIEW).review)
        gate(committed); h.ui.stop(h.ui.state.value); release.countDown(); gate(cleaned)
        assertEquals(QuestionConsentStage.CANCELLED, h.ui.visible().stage)
        assertEquals(listOf("CANCELLED", "CANCELLED"), h.jobs()); assertEquals(0, h.wire.opened.get())
    }

    @Test fun withdrawalHidesReadyAnswerBeforeCommitAndRetriesSameGroupWithoutAnotherWire() = Harness().use { h ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val permits = CopyOnWriteArrayList<SelectedQuestionSendPermit>()
        h.delegate = object : SelectedQuestionAuthorizationRepository by h.repo {
            override fun withdraw(permit: SelectedQuestionSendPermit) {
                h.outsideLocks(); permits += permit
                if (calls.incrementAndGet() == 1) { entered.countDown(); gate(release); error("synthetic unknown withdrawal ACK") }
                h.repo.withdraw(permit)
            }
        }
        val ready = h.execute(); h.ui.withdraw(ready); gate(entered)
        assertEquals(QuestionConsentStage.WITHDRAWING, h.ui.visible().stage); assertNull(h.ui.visible().receipt)
        release.countDown(); val failed = h.await(QuestionConsentStage.WITHDRAW_FAILED)
        assertNull(failed.receipt); h.ui.withdraw(failed); h.await(QuestionConsentStage.WITHDRAWN)
        assertSame(permits[0], permits[1]); assertEquals(1, h.wire.executions.get())
        assertNotNull(h.repo.checkReceipt(ready.receipt!!) { NOW })
        assertTrue(h.f.db.read("SELECT state FROM local_jobs WHERE operation='SELECTED_QUESTION_SEND_V1'").single().single().toString().startsWith("QS1:CANCELLED:"))
        assertEquals(listOf("COMMITTED", "COMMITTED"), h.jobs()) // Already completed member history is preserved.
    }

    @Test fun noEventGrantRevocationAndExpiryWithdrawBothReadyAndNoEvidenceReceipt() {
        for (kind in listOf("revoke-ready", "expire-no-evidence")) Harness().use { h ->
            if (kind.endsWith("no-evidence")) h.wire.wire = { QuestionProtocolFixture.wire(h.wire.target.protocol, QuestionProtocolFixture.reply(it, true)) }
            val ready = h.execute(if (kind.endsWith("no-evidence")) QuestionConsentStage.NO_EVIDENCE else QuestionConsentStage.READY)
            if (kind.startsWith("revoke")) {
                val records = SqlRecordAuthorizationRepository(h.f.gateway)
                records.changeAuthorization(RecordAuthorizationChange.withdraw(records.readAuthorization(SourceVersion(h.ids[0], 1)),
                    ProcessingPolicy.LOCAL_ONLY, UUID.randomUUID().toString())) { NOW }
            } else h.time.set(NOW.plusSeconds(601))
            h.await(QuestionConsentStage.STALE); assertNull(h.ui.visible().receipt)
            val calls = AtomicInteger()
            ready.receipt!!.reply.paragraphs.firstOrNull()?.references?.firstOrNull()?.let { h.ui.openSource(ready, it) { calls.incrementAndGet() } }
            assertEquals(0, calls.get()); assertEquals(1, h.wire.executions.get())
        }
    }

    @Test fun activeObserverReadCancellationFailsClosedInsteadOfLeavingUnwatchedReadyAnswer() = Harness().use { h ->
        h.execute()
        h.delegate = object : SelectedQuestionAuthorizationRepository by h.repo {
            override fun checkReceipt(receipt: SelectedQuestionSendReceipt, clock: () -> Instant): SendDenial? {
                throw java.util.concurrent.CancellationException("synthetic authority read cancellation while observer remains active")
            }
        }
        h.await(QuestionConsentStage.STALE); assertNull(h.ui.visible().receipt)
    }

    @Test fun sameRequestRunningWithdrawalStillAppliesAfterNaturalReadyTransition() = Harness().use { h ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.wire.beforeExecute = { entered.countDown(); gate(release) }
        h.ui.review(h.confirm()); h.ui.confirm(h.await(QuestionConsentStage.REVIEW).review); gate(entered)
        val running = h.ui.visible(); assertEquals(QuestionConsentStage.RUNNING, running.stage)
        release.countDown(); val ready = h.await(QuestionConsentStage.READY)
        h.ui.withdraw(running); h.await(QuestionConsentStage.WITHDRAWN)
        assertNotNull(h.repo.checkReceipt(ready.receipt!!) { NOW }); assertEquals(1, h.wire.executions.get())
    }

    @Test fun unexpectedReviewOrAuthorizationCancellationRetiresItsVisiblePhase() {
        for (duringAuthorization in listOf(false, true)) Harness().use { h ->
            val view = h.confirm()
            if (duringAuthorization) { h.ui.review(view); h.await(QuestionConsentStage.REVIEW) }
            h.delegate = object : SelectedQuestionAuthorizationRepository by h.repo {
                override fun review(request: SelectedQuestionRequest, connection: ProviderConnection, promptVersion: String,
                    clock: () -> Instant): SelectedQuestionSendReview = throw java.util.concurrent.CancellationException("synthetic read cancellation")
                override fun authorizeReviewedSend(review: SelectedQuestionSendReview, clock: () -> Instant): SelectedQuestionSendPermit =
                    throw java.util.concurrent.CancellationException("synthetic authorization cancellation")
            }
            if (duringAuthorization) h.ui.confirm(h.ui.state.value.review) else h.ui.review(view)
            h.await(QuestionConsentStage.STALE); assertTrue(h.jobs().isEmpty()); assertEquals(0, h.wire.opened.get())
        }
    }

    @Test fun withdrawDuringNonCooperativeWireCannotPublishLateTextAndDoesNotChangeRecordGrants() = Harness().use { h ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        h.wire.beforeExecute = { entered.countDown(); gate(release) }
        h.wire.onCancel = { finished.countDown() }
        val grants = h.f.db.read("SELECT hex(consent),generation FROM remote_source_access ORDER BY entryId")
        h.ui.review(h.confirm()); h.ui.confirm(h.await(QuestionConsentStage.REVIEW).review); gate(entered)
        h.ui.withdraw(h.ui.state.value); h.await(QuestionConsentStage.WITHDRAWN); gate(finished); release.countDown()
        Thread.sleep(300)
        assertEquals(QuestionConsentStage.WITHDRAWN, h.ui.visible().stage); assertNull(h.ui.visible().receipt)
        assertEquals(grants, h.f.db.read("SELECT hex(consent),generation FROM remote_source_access ORDER BY entryId"))
        assertEquals(listOf("CANCELLED", "CANCELLED"), h.jobs()); assertEquals(1, h.wire.cancels.get())
    }

    @Test fun lateWireCannotPublishAfterRevisionDeletionInputLockOrServiceChanges() {
        for (kind in listOf("revision", "delete", "input", "lock", "service")) Harness().use { h ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
            h.wire.beforeExecute = { entered.countDown(); gate(release); returned.countDown() }
            h.ui.review(h.confirm()); h.ui.confirm(h.await(QuestionConsentStage.REVIEW).review); gate(entered)
            when (kind) {
                "revision" -> h.f.append(h.ids[0])
                "delete" -> h.f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?", h.ids[0])
                "input" -> h.source.set(OriginalSearchState(OriginalSearchInput("changed"), OriginalSearchStage.BROWSE))
                "lock" -> h.session.set(false)
                "service" -> h.currentFixture.set(null)
            }
            h.ui.sync(); release.countDown(); gate(returned)
            runBlocking { withTimeout(8_000) { h.ui.state.first { it.stage in setOf(QuestionConsentStage.STALE, QuestionConsentStage.FAILED) } } }
            assertNull(kind, h.ui.visible().receipt)
            assertEquals(1, h.wire.executions.get())
        }
    }

    @Test fun unknownAuthorizationAcknowledgementNeverOpensWireOrAutomaticallyRepeatsRequest() = Harness().use { h ->
        val writes = AtomicInteger()
        h.delegate = object : SelectedQuestionAuthorizationRepository by h.repo {
            override fun authorizeReviewedSend(review: SelectedQuestionSendReview, clock: () -> Instant): SelectedQuestionSendPermit {
                writes.incrementAndGet(); h.repo.authorizeReviewedSend(review, clock); error("synthetic lost authorization ACK")
            }
        }
        h.ui.review(h.confirm()); val review = h.await(QuestionConsentStage.REVIEW).review
        h.ui.confirm(review); h.await(QuestionConsentStage.FAILED); h.ui.confirm(review)
        assertEquals(1, writes.get()); assertEquals(0, h.wire.opened.get()); assertNull(h.ui.visible().receipt)
        // No returned capability exists to send or reuse; persisted history is not claimed cancelled.
        assertEquals(listOf("QUEUED", "QUEUED"), h.jobs())
    }

    private class Harness(approved: Boolean = true, disabled: Boolean = false) : AutoCloseable {
        val f = RecordEventDateFixture()
        val ids = (1..2).map { "61000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
        val source = AtomicReference<OriginalSearchState>()
        val session = AtomicBoolean(true); val time = AtomicReference(NOW)
        val repo = SqlSelectedQuestionAuthorizationRepository(f.gateway)
        @Volatile var delegate: SelectedQuestionAuthorizationRepository = repo
        @Volatile var beforeProof: () -> Unit = {}
        val owner = object : LocalSearchRepository by f.search {
            override fun publishCurrent(snapshot: LocalSearchSnapshot, publish: () -> Unit): Boolean {
                beforeProof(); return f.search.publishCurrent(snapshot, publish)
            }
        }
        val evidence = LocalEvidenceController(source::get)
        val wire = QuestionProtocolFixture()
        val connection = ProviderConnection("synthetic-profile", 1, wire.target.apiRoot, wire.target.protocol, wire.target.modelId,
            SecretReference("synthetic-unresolved-ref"))
        val fixture = SelectedQuestionConsentFixture(connection, wire.service, object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = time.get()
        }, "UTC")
        val proxy = object : SelectedQuestionAuthorizationRepository {
            override fun review(request: SelectedQuestionRequest, connection: ProviderConnection, promptVersion: String, clock: () -> Instant): SelectedQuestionSendReview {
                outsideLocks(); return delegate.review(request, connection, promptVersion, clock)
            }
            override fun authorizeReviewedSend(review: SelectedQuestionSendReview, clock: () -> Instant): SelectedQuestionSendPermit { outsideLocks(); return delegate.authorizeReviewedSend(review, clock) }
            override fun check(permit: SelectedQuestionSendPermit, clock: () -> Instant) = delegate.check(permit, clock)
            override fun subscribe(permit: SelectedQuestionSendPermit, clock: () -> Instant, onInvalidated: (SendDenial) -> Unit) = delegate.subscribe(permit, clock, onInvalidated)
            override fun admit(permit: SelectedQuestionSendPermit, clock: () -> Instant) = delegate.admit(permit, clock)
            override fun accept(permit: SelectedQuestionSendPermit, reply: SelectedQuestionReply, clock: () -> Instant) = delegate.accept(permit, reply, clock)
            override fun checkReceipt(receipt: SelectedQuestionSendReceipt, clock: () -> Instant) = delegate.checkReceipt(receipt, clock)
            override fun withdraw(permit: SelectedQuestionSendPermit) { outsideLocks(); delegate.withdraw(permit) }
            override fun cancelPending(permit: SelectedQuestionSendPermit) = delegate.cancelPending(permit)
        }
        val currentOwner = AtomicReference<SelectedQuestionAuthorizationRepository?>(if (disabled) null else proxy)
        val currentFixture = AtomicReference<SelectedQuestionConsentFixture?>(if (disabled) null else fixture)
        val ui = SelectedQuestionConsentController(evidence, ::current, session::get, currentOwner.get(), currentOwner::get,
            currentFixture.get(), currentFixture::get, uiDispatcher = Dispatchers.Unconfined)
        init {
            ids.forEach { f.entry(it, "🙂".repeat(90) + "needle 合成记录\n" + "中".repeat(300)) }
            if (approved) ids.forEach { id ->
                val raw = RemoteTextTransactions(f.gateway); val source = SourceVersion(id, 1); val access = raw.readAccess(id)!!
                assertNull(raw.updateAuthorization(source, access.generation, ProcessingPolicy.LOCAL_ONLY, connection, null))
                val records = SqlRecordAuthorizationRepository(f.gateway)
                val preview = records.previewAuthorization(records.readAuthorization(source).binding, TextPurpose.CONVERSATION) { NOW }
                records.changeAuthorization(RecordAuthorizationChange.confirm(preview, NOW.minusSeconds(1), NOW.plusSeconds(600), UUID.randomUUID().toString())) { NOW }
            }
            f.drain(); val input = OriginalSearchInput("needle"); val entries = f.search.candidates(LocalSearchRequest("")).entries
            val result = runBlocking { LocalOriginalSearchMatcher.indexed(entries, input, f.search) }
            source.set(OriginalSearchState(input, result.stage, result.hits, entries = entries, proof = result.proof, owner = owner, indexEpoch = f.search.epoch()))
            wire.onOpen = ::outsideLocks
        }
        fun outsideLocks() {
            val sqlMonitor = PythonSqliteDatabase::class.java.getDeclaredField("monitor").apply { isAccessible = true }.get(f.db)
            assertFalse("consumer must run outside its SQL transaction", Thread.holdsLock(sqlMonitor))
            listOf(evidence, ui).forEach { controller ->
                assertFalse(Thread.holdsLock(controller.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(controller)))
            }
        }
        fun current() = evidence.visible(source.get())
        fun confirm(): LocalEvidenceState {
            evidence.sync(true, source.get())
            runBlocking { withTimeout(8_000) { evidence.state.first { it.stage == LocalEvidenceStage.READY } } }
            evidence.state.value.cards.forEach { evidence.toggle(evidence.state.value, it) }
            evidence.confirm(evidence.state.value)
            return runBlocking { withTimeout(8_000) { evidence.state.first { it.selection.stage == LocalEvidenceSelectionStage.CONFIRMED } } }
        }
        fun await(stage: QuestionConsentStage) = runBlocking { withTimeout(8_000) { ui.state.first { it.stage == stage } } }
        fun execute(stage: QuestionConsentStage = QuestionConsentStage.READY): QuestionConsentState {
            ui.review(confirm()); ui.confirm(await(QuestionConsentStage.REVIEW).review); return await(stage)
        }
        fun jobs() = f.db.read("SELECT state FROM remote_text_jobs ORDER BY entryId").map { it.single() }
        override fun close() { ui.close(); evidence.close(); f.db.beforeCommit = null; repo.close(); f.gateway.awaitIdle(); f.close() }
    }
    companion object {
        private val NOW = Instant.parse("2026-10-18T20:00:00Z")
        private fun gate(latch: CountDownLatch) { check(latch.await(6, TimeUnit.SECONDS)) { "Owned test gate timed out" } }
    }
}
