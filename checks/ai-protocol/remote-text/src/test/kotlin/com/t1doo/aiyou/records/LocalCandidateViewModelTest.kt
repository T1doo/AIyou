package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual VM IO actor, production parser/rules/SQL/AEAD; no Android or device claim. */
class LocalCandidateViewModelTest {
    @Test(timeout = 10_000) fun correctionRequiresFreshConfirmationAndNeverReusesAnOldAcceptOrReopenToken(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val generated = h.generate(h.open().session); val batch = generated.batches.single()
        h.vm.candidates.requestAccept(generated.session, batch.id, batch.version, "excerpt-1")
        val oldAccept = checkNotNull(h.vm.state.value.candidates!!.confirmation)
        h.vm.candidates.confirm(generated.session, oldAccept)
        val accepted = h.ui { it.pending == null && it.notice?.contains("已采纳") == true }.batches.single()
        h.vm.candidates.requestReopen(generated.session, batch.id, accepted.version, "excerpt-1")
        val oldCorrection = checkNotNull(h.vm.state.value.candidates!!.confirmation)
        h.vm.candidates.cancelConfirmation(generated.session, oldCorrection)
        assertTrue(h.reopens.isEmpty()); assertEquals(ExcerptReview.ACCEPTED, h.f.store.batches(h.f.source).single().items.single().review)
        h.vm.candidates.requestReopen(generated.session, batch.id, accepted.version, "excerpt-1")
        val fresh = checkNotNull(h.vm.state.value.candidates!!.confirmation)
        h.vm.candidates.cancelConfirmation(generated.session, oldCorrection)
        h.vm.candidates.confirm(generated.session, oldCorrection); h.vm.candidates.confirm(generated.session, oldAccept)
        assertEquals(fresh, h.vm.state.value.candidates!!.confirmation); assertTrue(h.reopens.isEmpty())
        h.vm.candidates.confirm(generated.session, fresh)
        val pending = h.ui { it.pending == null && it.notice?.contains("重新待确认") == true }.batches.single()
        assertEquals(ExcerptReview.PROPOSED, pending.items.single().review); assertEquals(3L, pending.version)
        h.vm.candidates.confirm(generated.session, oldAccept); assertEquals(1, h.reviews.size)
        h.vm.candidates.reject(generated.session, batch.id, pending.version, "excerpt-1")
        val rejected = h.ui { it.pending == null && it.notice?.contains("已拒绝") == true }.batches.single()
        h.vm.candidates.requestReopen(generated.session, batch.id, rejected.version, "excerpt-1")
        h.vm.candidates.confirm(generated.session, checkNotNull(h.vm.state.value.candidates!!.confirmation))
        h.ui { it.pending == null && it.notice?.contains("重新待确认") == true }
        h.dispose(); h.f.reopen()
        assertEquals(ExcerptReview.PROPOSED, h.f.store.batches(h.f.source).single().items.single().review)
        assertTrue(h.f.db.read("SELECT * FROM manual_tasks").isEmpty()); assertTrue(h.f.db.read("SELECT * FROM entry_manual_tags").isEmpty())
        assertEquals(0, h.remoteReads); assertEquals(0, h.remoteOpens)
    } }
    @Test(timeout = 10_000) fun correctionLostReadbackRetriesTheIdenticalImmutableRequestAndOnlyOneNewVersion(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val generated = h.generate(h.open().session); val batch = generated.batches.single()
        h.vm.candidates.reject(generated.session, batch.id, batch.version, "excerpt-1")
        val rejected = h.ui { it.pending == null && it.notice?.contains("已拒绝") == true }.batches.single()
        h.loseReviewReadback = true
        h.vm.candidates.requestReopen(generated.session, batch.id, rejected.version, "excerpt-1")
        h.vm.candidates.confirm(generated.session, checkNotNull(h.vm.state.value.candidates!!.confirmation))
        val unknown = h.ui { it.error != null && !it.busy }
        assertNull(unknown.notice); assertTrue(unknown.batches.isEmpty()); assertTrue(unknown.pending is LocalCandidateIntent.Reopen)
        val request = h.reopens.single()
        h.loseReviewReadback = false; h.failRead = false
        h.vm.candidates.retry(unknown.session, unknown.request)
        h.ui { it.notice?.contains("重新待确认") == true && it.pending == null }
        assertEquals(2, h.reopens.size); assertSame(request, h.reopens[1])
        assertEquals(3L, h.f.store.batches(h.f.source).single().version)
    } }
    @Test(timeout = 10_000) fun sourceEditRetiresCorrectionConfirmationAndCannotReopenInvalidatedPayload(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val generated = h.generate(h.open().session); val batch = generated.batches.single()
        h.vm.candidates.reject(generated.session, batch.id, batch.version, "excerpt-1")
        val rejected = h.ui { it.pending == null && it.notice?.contains("已拒绝") == true }.batches.single()
        h.vm.candidates.requestReopen(generated.session, batch.id, rejected.version, "excerpt-1")
        val old = checkNotNull(h.vm.state.value.candidates!!.confirmation)
        h.f.revise(); h.vm.refreshLocalSearch()
        withTimeout(2_000) { h.vm.state.first { !it.opening && it.entries.any { e -> e.id == "source" && e.currentRevision == 2 } } }
        assertNull(h.vm.state.value.candidates)
        h.vm.candidates.confirm(generated.session, old); assertTrue(h.reopens.isEmpty())
        assertTrue(h.f.store.batches(SourceVersion("source", 2)).all { it.state == LocalCandidateState.INVALIDATED && it.items.isEmpty() })
    } }
    @Test(timeout = 10_000) fun syntheticJsonUsesActualParserVmSqlAndColdReopenWithoutRemoteAuthority(): Unit = Harness().use { h -> runBlocking {
        h.producer = { job ->
            val raw = buildJsonObject {
                put("schemaVersion",1); put("ruleVersion",1); put("provenance","LOCAL_RULE_EXCERPT")
                put("sourceId",job.start.source.sourceId); put("sourceRevision",job.start.source.revision); put("sourceHash",job.sourceHash)
                putJsonArray("candidates") { add(buildJsonObject {
                    put("kind","EXCERPT"); put("start",0); put("end",job.original.codePointCount(0,job.original.length)); put("quote",job.original)
                }) }
            }.toString()
            ExcerptResponseParser.parse(raw)
        }
        h.ready(); val generated=h.generate(h.open().session); val batch=generated.batches.single()
        h.vm.candidates.requestAccept(generated.session,batch.id,batch.version,"excerpt-1")
        h.vm.candidates.confirm(generated.session,checkNotNull(h.vm.state.value.candidates!!.confirmation))
        h.ui { it.pending==null && it.notice?.contains("已采纳")==true }
        h.dispose(); h.f.reopen()
        val cold=h.f.store.batches(h.f.source).single()
        assertEquals(ExcerptReview.ACCEPTED,cold.items.single().review); assertEquals(h.f.original,cold.items.single().proposal.quote)
        assertEquals(0,h.remoteReads); assertEquals(0,h.remoteOpens)
    } }
    @Test(timeout = 10_000) fun localOnlyProductionPathPreviewsThenExplicitlyAcceptsAndReopensReview(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val opened = h.open()
        assertTrue(opened.batches.isEmpty()); assertEquals("LOCAL_ONLY", h.f.db.read("SELECT privacyPolicy FROM entries WHERE id='source'").single().single())
        val generated = h.generate(opened.session); val batch = generated.batches.single()
        assertEquals(ExcerptReview.PROPOSED, batch.items.single().review); assertTrue(h.reviews.isEmpty())
        h.vm.candidates.requestAccept(opened.session, batch.id, batch.version, batch.items.single().id)
        val confirm = checkNotNull(h.vm.state.value.candidates!!.confirmation)
        h.vm.candidates.cancelConfirmation(opened.session, confirm)
        assertTrue(h.reviews.isEmpty()); assertEquals(ExcerptReview.PROPOSED, h.f.store.batches(h.f.source).single().items.single().review)
        h.vm.candidates.requestAccept(opened.session, batch.id, batch.version, batch.items.single().id)
        h.vm.candidates.confirm(opened.session, checkNotNull(h.vm.state.value.candidates!!.confirmation))
        val accepted = h.ui { it.pending == null && it.notice?.contains("已采纳") == true }
        assertEquals(ExcerptReview.ACCEPTED, accepted.batches.single().items.single().review)
        assertTrue(h.f.db.read("SELECT id FROM manual_tasks").isEmpty())
        assertTrue(h.f.db.read("SELECT * FROM entry_manual_tags").isEmpty())
        h.vm.candidates.close(opened.session); assertNull(h.vm.state.value.candidates)
        val reopened = h.open(); assertEquals(ExcerptReview.ACCEPTED, reopened.batches.single().items.single().review)
        assertEquals(0, h.remoteReads); assertEquals(0, h.remoteOpens)
    } }
    @Test(timeout = 10_000) fun committedReviewWithLostReadbackRetainsExactlyTheSameRequestForRetry(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val generated = h.generate(h.open().session); val batch = generated.batches.single()
        h.loseReviewReadback = true
        h.vm.candidates.requestAccept(generated.session, batch.id, batch.version, "excerpt-1")
        h.vm.candidates.confirm(generated.session, checkNotNull(h.vm.state.value.candidates!!.confirmation))
        val unknown = h.ui { it.error != null && !it.busy }
        assertNull(unknown.notice); assertNotNull(unknown.pending); assertTrue(unknown.batches.isEmpty())
        assertEquals(ExcerptReview.ACCEPTED, h.f.store.batches(h.f.source).single().items.single().review)
        val originalRequest = h.reviews.single()
        h.loseReviewReadback = false; h.failRead = false
        h.vm.candidates.retry(unknown.session, unknown.request)
        h.ui { it.notice?.contains("已采纳") == true && it.pending == null }
        assertEquals(2, h.reviews.size); assertSame(originalRequest, h.reviews[1])
        assertEquals(2L, h.f.store.batches(h.f.source).single().version)
    } }
    @Test(timeout = 10_000) fun oldConfirmationCannotRetargetAnotherItemOrAReopenedSession(): Unit = Harness().use { h -> runBlocking {
        h.producer = { job -> ExcerptDocument(job.start.source, job.sourceHash, listOf(ExcerptProposal(0,1,"我"), ExcerptProposal(1,2,"不"))) }
        h.ready(); val generated = h.generate(h.open().session); val batch = generated.batches.single()
        h.vm.candidates.requestAccept(generated.session, batch.id, 1, "excerpt-1")
        val old = checkNotNull(h.vm.state.value.candidates!!.confirmation)
        h.vm.candidates.cancelConfirmation(generated.session, old)
        h.vm.candidates.requestAccept(generated.session, batch.id, 1, "excerpt-2")
        val fresh = checkNotNull(h.vm.state.value.candidates!!.confirmation)
        h.vm.candidates.confirm(generated.session, old); assertTrue(h.reviews.isEmpty())
        h.vm.candidates.confirm(generated.session, fresh)
        h.ui { it.notice?.contains("已采纳") == true }
        assertEquals(listOf(ExcerptReview.PROPOSED, ExcerptReview.ACCEPTED), h.f.store.batches(h.f.source).single().items.map { it.review })
        h.vm.candidates.close(generated.session); val next = h.open()
        h.vm.candidates.confirm(generated.session, fresh); h.vm.candidates.reject(generated.session, batch.id, 1, "excerpt-1")
        assertEquals(1, h.reviews.size); assertEquals(next.session, h.vm.state.value.candidates!!.session)
    } }
    @Test(timeout = 10_000) fun localSavingDoesNotWaitForCandidateComputationAndCancelRejectsItsLateResult(): Unit = Harness().use { h -> runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.producer = { job -> entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); LocalExcerptRules.extract(job) }
        try {
            h.ready(); val ui = h.open(); h.vm.candidates.generate(ui.session)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val running = h.ui { it.computing && it.batches.isNotEmpty() }
            h.vm.changeText("合成独立本地保存"); h.vm.save()
            withTimeout(2_000) { h.vm.state.first { it.saveNotice != null && !it.saving } }
            assertTrue(h.vm.state.value.entries.any { it.originalText == "合成独立本地保存" })
            val batch = running.batches.single()
            h.vm.candidates.cancelBatch(ui.session, batch.id, batch.version)
            h.ui { it.notice?.contains("已取消") == true }
            release.countDown()
            assertEquals(LocalCandidateState.CANCELLED, h.f.store.batches(h.f.source).single().state)
            assertEquals(0, h.remoteReads); assertEquals(0, h.remoteOpens)
        } finally { release.countDown() }
    } }
    @Test(timeout = 10_000) fun cancelledConfirmationCannotAuthorizeTheSameItemAfterReopeningIt(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val generated = h.generate(h.open().session); val batch = generated.batches.single()
        h.vm.candidates.requestAccept(generated.session, batch.id, batch.version, "excerpt-1")
        val old = checkNotNull(h.vm.state.value.candidates!!.confirmation)
        h.vm.candidates.cancelConfirmation(generated.session, old)
        h.vm.candidates.requestAccept(generated.session, batch.id, batch.version, "excerpt-1")
        val fresh = checkNotNull(h.vm.state.value.candidates!!.confirmation)
        assertNotEquals(old.token, fresh.token)
        h.vm.candidates.cancelConfirmation(generated.session, old)
        assertEquals(fresh, h.vm.state.value.candidates!!.confirmation)
        h.vm.candidates.confirm(generated.session, old); assertTrue(h.reviews.isEmpty())
        h.vm.candidates.confirm(generated.session, fresh)
        h.ui { it.notice?.contains("已采纳") == true }; assertEquals(1, h.reviews.size)
    } }
    @Test(timeout = 10_000) fun sourceChangeDuringComputationRetiresTheSessionAndNeverPublishesLateCandidates(): Unit = Harness().use { h -> runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.producer = { job -> entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); LocalExcerptRules.extract(job) }
        try {
            h.ready(); val ui = h.open(); h.vm.candidates.generate(ui.session); assertTrue(entered.await(2, TimeUnit.SECONDS))
            fRevised(h)
            // retry() resumes capture saving; this production action rereads current records.
            h.vm.refreshLocalSearch()
            withTimeout(2_000) { h.vm.state.first { !it.opening && it.entries.any { e -> e.id=="source" && e.currentRevision==2 } } }
            assertNull(h.vm.state.value.candidates)
            release.countDown()
            assertEquals(LocalCandidateState.INVALIDATED, h.f.store.batches(SourceVersion("source",2)).single().state)
            h.vm.candidates.requestAccept(ui.session,"batch",1,"excerpt-1"); assertTrue(h.reviews.isEmpty())
        } finally { release.countDown() }
    } }
    @Test(timeout = 10_000) fun disposalDuringIndependentComputationClosesOwnerAndRejectsOldBusinessCallbacks(): Unit = Harness().use { h -> runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.producer = { job -> entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); LocalExcerptRules.extract(job) }
        try {
            h.ready(); val ui = h.open(); h.vm.candidates.generate(ui.session); assertTrue(entered.await(2, TimeUnit.SECONDS))
            val running = h.ui { it.computing && it.batches.isNotEmpty() }
            h.dispose()
            h.vm.candidates.generate(ui.session); h.vm.candidates.cancelBatch(ui.session, running.batches.single().id, 0)
            release.countDown(); h.f.reopen()
            assertEquals(LocalCandidateState.RUNNING, h.f.store.batches(h.f.source).single().state)
            assertTrue(h.reviews.isEmpty()); assertNull(h.vm.state.value.candidates?.notice)
        } finally { release.countDown() }
    } }
    private fun fRevised(h: Harness) = h.f.revise()

    private class Harness : AutoCloseable {
        val f = LocalCandidateFixture()
        @Volatile var failRead = false
        @Volatile var loseReviewReadback = false
        @Volatile var producer: (LocalCandidateJob) -> ExcerptDocument = LocalExcerptRules::extract
        val reviews = CopyOnWriteArrayList<ExcerptReviewRequest>()
        val reopens = CopyOnWriteArrayList<ExcerptReopenRequest>()
        var remoteReads = 0; var remoteOpens = 0
        private val port = object : LocalCandidateRepository by f.store {
            override fun reopen(request: ExcerptReopenRequest): LocalCandidateBatch {
                reopens.add(request)
                return f.store.reopen(request).also { if (loseReviewReadback) failRead = true }
            }
            override fun review(request: ExcerptReviewRequest): LocalCandidateBatch {
                reviews.add(request)
                return f.store.review(request).also { if (loseReviewReadback) failRead = true }
            }
            override fun batches(source: SourceVersion): List<LocalCandidateBatch> {
                if (failRead) throw LocalCandidateStorageFailure()
                return f.store.batches(source)
            }
        }
        private val base = SqliteVmEntryRepository(f.db, f.gateway, RemoteTextTransactions(f.gateway)) {
            f.gateway.stopAndAwaitIdle(); f.store.wipeKey()
        }
        private val store: EntryRepository = object : EntryRepository by base, AutoCloseable {
            override val localCandidates = port
            override val sourceBoundText: SourceBoundTextRepository? get() { remoteReads++; error("No remote read expected") }
            override suspend fun prepare() { check(f.db.read("PRAGMA user_version").single().single() == 10L) }
            override fun close() = base.close()
        }
        val vm = LocalRecordsViewModel(Application(), { store }, Clock.fixed(Instant.ofEpochMilli(50), ZoneOffset.UTC),
            bindAi = { remoteOpens++; error("No remote exchange expected") }, extractLocalCandidates = { producer(it) })
        suspend fun ready() = withTimeout(2_000) { vm.state.first { it.ready && !it.opening && it.supportsCandidates } }
        suspend fun open(): LocalCandidateUiState { vm.candidates.open("source",1); return ui { !it.loading } }
        suspend fun generate(session: Long): LocalCandidateUiState {
            vm.candidates.generate(session)
            return ui { !it.busy && !it.computing && it.pending == null && it.batches.any { b -> b.state==LocalCandidateState.READY } }
        }
        suspend fun ui(predicate: (LocalCandidateUiState) -> Boolean): LocalCandidateUiState =
            withTimeout(2_000) { vm.state.first { it.candidates?.let(predicate) == true }.candidates!! }
        private var disposed = false
        fun dispose() {
            if (!disposed) { disposed = true; vm.clearForHostTest() }
            assertTrue(base.closed.await(2, TimeUnit.SECONDS)); base.closeFailure.get()?.let { throw it }
        }
        override fun close() { dispose(); f.close() }
    }
}
