package com.t1doo.aiyou.data

import com.t1doo.aiyou.ai.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Product transactions on real host SQLite, exact existing grants; only memory protocol transport.
 * This is not Android Room/SQLCipher/device or a real model/external request test.
 */
class SelectedQuestionAuthorizationTest {
    @Test fun explicitSendReviewRequiresExistingConversationGrantsAndNeverElevatesLocalSelection(): Unit = Harness(false).use { h ->
        denied { h.repo.review(h.request(), h.connection, PROMPT, h.clock) }
        assertTrue(h.jobs().isEmpty()); assertTrue(h.groups().isEmpty())
        h.approve(0, TextPurpose.ORGANIZATION); h.approve(1)
        denied { h.review() }
        assertTrue(h.jobs().isEmpty())
        h.approve(0)
        val before = h.authority()
        h.review() // Read-only review still creates no send jobs/authorization and changes no grants.
        assertTrue(h.jobs().isEmpty()); assertTrue(h.groups().isEmpty()); assertEquals(before, h.authority())
    }

    @Test fun exactQuestionAndExcerptsReachOnlyMemoryWireAndAcceptedReceiptStoresOnlyDigests(): Unit = Harness().use { h ->
        val before = h.authority(); val review = h.review(); val permit = h.repo.authorizeReviewedSend(review, h.clock)
        val calls = AtomicInteger(); val cancels = AtomicInteger(); val body = AtomicReference<String>()
        val service = h.service { request ->
            body.set(request.bodyJson)
            wire(cancels) { sink -> calls.incrementAndGet(); sink(h.sse(h.reply(review.request))) }
        }
        val receipt = PersistedLocalQuestionExchange(h.repo, permit, service, h.clock).execute()
        assertEquals(1, calls.get()); assertEquals(1, cancels.get()); assertNull(h.repo.checkReceipt(receipt, h.clock))
        val outer = Json.parseToJsonElement(body.get()).jsonObject
        val payload = Json.parseToJsonElement(outer["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content).jsonObject
        assertEquals(review.request.question, payload["question"]!!.jsonPrimitive.content)
        assertEquals(review.request.sources.map { it.reference.quote }, payload["sources"]!!.jsonArray.map { it.jsonObject["quote"]!!.jsonPrimitive.content })
        assertFalse(body.get().contains("UNSELECTED_PRIVATE_TAIL")); assertFalse(body.get().contains("UNSELECTED_OTHER_RECORD"))
        assertEquals(listOf("COMMITTED", "COMMITTED"), h.jobs().map { it[1] })
        assertTrue(h.f.db.read("SELECT * FROM text_annotations").isEmpty()); assertEquals(before, h.authority())
        assertFalse(h.groups().toString().contains(review.request.question)); assertFalse(receipt.toString().contains(review.request.question))
        denied { h.repo.admit(permit, h.clock) }; denied { h.repo.authorizeReviewedSend(review, h.clock) }
    }

    @Test fun anyQuestionTimeRangeSelectionTargetOrGrantProofMutationCannotReusePersistedGroup(): Unit = Harness().use { h ->
        val review = h.review(); val permit = h.repo.authorizeReviewedSend(review, h.clock)
        val r = review.request
        val variants = listOf(
            h.request(id = r.id, question = "另一问题"), h.request(id = r.id, selection = "changed-selection"),
            h.request(id = r.id, asked = NOW.plusSeconds(1).toEpochMilli()), h.request(id = r.id, zone = "Asia/Shanghai"),
            h.request(id = r.id, sources = r.sources.reversed()),
            h.request(id = r.id, sources = r.sources.mapIndexed { i, s -> if (i == 0) s.copy(reference = s.reference.copy(quote = "伪".repeat(s.reference.end))) else s }),
            h.request(id = r.id, sources = r.sources.mapIndexed { i, s -> if (i == 0) s.copy(capturedAtMillis = 6) else s }),
        )
        for (request in variants) {
            val snapshots = request.sources.map { s -> review.sources.single { it.binding.source == s.reference.source } }
            val altered = SelectedQuestionSendPermit(SelectedQuestionSendReview(request, h.connection, PROMPT, review.sessionId, snapshots), permit.memberIds)
            assertNotNull(h.repo.check(altered, h.clock)); denied { h.repo.admit(altered, h.clock) }
        }
        val configs = listOf(h.connection.copy(providerId = "other-provider"), h.connection.copy(version = 2),
            h.connection.copy(apiRoot = "https://other.invalid/v1/"), h.connection.copy(protocol = ConnectionProtocol.ANTHROPIC_MESSAGES),
            h.connection.copy(modelId = "other-model"), h.connection.copy(secretRef = SecretReference("other-ref")),
            h.connection.copy(chatBudgetField = ChatBudgetField.MAX_COMPLETION_TOKENS), h.connection.copy(chatUsageSupported = true))
        for (config in configs) {
            val altered = SelectedQuestionSendPermit(SelectedQuestionSendReview(h.request(id = r.id, connection = config), config, PROMPT,
                review.sessionId, review.sources), permit.memberIds)
            assertNotNull(h.repo.check(altered, h.clock)); denied { h.repo.admit(altered, h.clock) }
        }
        val alteredPrompt = SelectedQuestionSendPermit(SelectedQuestionSendReview(r, h.connection, "changed-prompt", review.sessionId, review.sources), permit.memberIds)
        assertNotNull(h.repo.check(alteredPrompt, h.clock)); assertEquals(listOf("QUEUED", "QUEUED"), h.jobs().map { it[1] })
        assertNull(h.repo.check(permit, h.clock))
    }

    @Test fun secondMemberQueueFailureRollsBackAllJobsAndRequestAuthorization(): Unit = Harness().use { h ->
        val review = h.review(); val writes = AtomicInteger()
        h.f.db.beforeSql = { sql -> if (sql.startsWith("INSERT INTO remote_text_jobs") && writes.incrementAndGet() == 2) error("synthetic member failure") }
        denied { h.repo.authorizeReviewedSend(review, h.clock) }
        h.f.db.beforeSql = null
        assertEquals(2, writes.get()); assertTrue(h.jobs().isEmpty()); assertTrue(h.groups().isEmpty())
        val permit = h.repo.authorizeReviewedSend(review, h.clock); assertNull(h.repo.check(permit, h.clock))
    }

    @Test fun admissionIsAtomicOneUseAndFinalClockRechecksEveryGrant(): Unit = Harness().use { h ->
        val permit = h.authorize(); val writes = AtomicInteger()
        h.f.db.beforeSql = { sql -> if (sql.startsWith("UPDATE remote_text_jobs SET state='ADMITTED'") && writes.incrementAndGet() == 2) error("synthetic admission failure") }
        denied { h.repo.admit(permit, h.clock) }; h.f.db.beforeSql = null
        assertEquals(listOf("QUEUED", "QUEUED"), h.jobs().map { it[1] }); assertNull(h.repo.check(permit, h.clock))
        // Expire the FIRST grant only after the SECOND member's validation read.
        var reads = 0
        h.f.db.afterSql = { sql -> if (sql.startsWith("SELECT r.originalText")) reads++ }
        denied { h.repo.admit(permit) { if (reads >= 2) NOW.plusSeconds(601) else NOW } }
        h.f.db.afterSql = null
        assertEquals(listOf("QUEUED", "QUEUED"), h.jobs().map { it[1] })
        h.repo.admit(permit, h.clock); denied { h.repo.admit(permit, h.clock) }
        assertEquals(listOf("ADMITTED", "ADMITTED"), h.jobs().map { it[1] })
    }

    @Test fun withdrawalRollsBackAsAGroupAndNeverCancelsASiblingRequestOrChangesRecordGrants(): Unit = Harness().use { h ->
        val first = h.authorize(); val second = h.authorize(); h.repo.admit(first, h.clock)
        val before = h.authority(); val writes = AtomicInteger()
        h.f.db.beforeSql = { sql -> if (sql.startsWith("UPDATE remote_text_jobs SET state='CANCELLED'") && writes.incrementAndGet() == 2) error("synthetic withdraw failure") }
        denied { h.repo.withdraw(first) }; h.f.db.beforeSql = null
        assertNull(h.repo.check(first, h.clock)); assertNull(h.repo.check(second, h.clock))
        h.repo.withdraw(first)
        assertNotNull(h.repo.check(first, h.clock)); assertNull(h.repo.check(second, h.clock)); assertEquals(before, h.authority())
        denied { h.repo.accept(first, h.reply(first.review.request), h.clock) }
        h.repo.admit(second, h.clock); val receipt = h.repo.accept(second, h.reply(second.review.request), h.clock)
        h.repo.cancelPending(second); assertNull(h.repo.checkReceipt(receipt, h.clock))
        h.repo.withdraw(second); assertNotNull(h.repo.checkReceipt(receipt, h.clock))
    }

    @Test fun nonPrimarySourceWithdrawalVersionDeletionAndAuthorizationAbaInvalidateWholeReceipt(): Unit {
        for (change in listOf("withdraw", "revise", "delete", "aba", "expired", "erased")) Harness().use { h ->
            val permit = h.authorize(); h.repo.admit(permit, h.clock)
            val receipt = h.repo.accept(permit, h.reply(permit.review.request), h.clock)
            when (change) {
                "withdraw" -> h.withdrawSource(1)
                "revise" -> h.revise(1)
                "delete" -> { val access = h.raw.readAccess(h.ids[1])!!; assertNull(h.raw.tombstone(h.sources[1], access.generation)) }
                "aba" -> { h.withdrawSource(1); h.approve(1) }
                "expired" -> Unit
                "erased" -> runBlocking { h.f.erase.erase(h.f.intent(h.ids[1])) }
            }
            assertNotNull("Entire result must be invalid after $change", h.repo.checkReceipt(receipt) { if (change == "expired") NOW.plusSeconds(601) else NOW })
            denied { h.repo.accept(permit, receipt.reply, { if (change == "expired") NOW.plusSeconds(601) else NOW }) }
        }
    }

    @Test fun noEventWireIsCancelledByPersistedSecondSourceWithdrawalAndLateAnswerCannotBeAccepted(): Unit = Harness().use { h ->
        val permit = h.authorize(); val started = CountDownLatch(1); val cancelled = CountDownLatch(1); val release = CountDownLatch(1)
        val cancels = AtomicInteger(); val result = AtomicReference<SelectedQuestionSendReceipt?>(); val failure = AtomicReference<Throwable?>()
        val service = h.service { wire(cancels, { cancelled.countDown() }) { sink -> started.countDown(); await(release); sink(h.sse(h.reply(permit.review.request))) } }
        val worker = thread { try { result.set(PersistedLocalQuestionExchange(h.repo, permit, service, h.clock).execute()) } catch (t: Throwable) { failure.set(t) } }
        try {
            await(started); h.f.second().use { other -> h.withdrawSource(1, other) }
            await(cancelled); assertNull(result.get()); release.countDown(); join(worker)
            assertNull(result.get()); assertNotNull(failure.get()); assertEquals(1, cancels.get())
        } finally { release.countDown(); join(worker) }
    }

    @Test fun stopDuringExchangeCreationCancelsReturnedWireBeforeExecutionAndCannotReturnLateReceipt(): Unit = Harness().use { h ->
        val permit = h.authorize(); val creating = CountDownLatch(1); val release = CountDownLatch(1)
        val executed = AtomicInteger(); val cancelled = AtomicInteger(); val failure = AtomicReference<Throwable?>()
        val service = h.service { creating.countDown(); await(release); wire(cancelled) { executed.incrementAndGet() } }
        val exchange = PersistedLocalQuestionExchange(h.repo, permit, service, h.clock)
        val worker = thread { try { exchange.execute(); fail("No late result") } catch (t: Throwable) { failure.set(t) } }
        try { await(creating); exchange.cancel(); release.countDown(); join(worker)
            assertEquals(0, executed.get()); assertEquals(1, cancelled.get()); assertTrue(failure.get() is CancellationException)
        } finally { release.countDown(); join(worker) }
    }

    @Test fun closedOwnerAndColdReopenCannotReuseOldReviewPermitOrReceipt(): Unit = Harness().use { h ->
        val review = h.review(); val permit = h.repo.authorizeReviewedSend(review, h.clock); h.repo.admit(permit, h.clock)
        val receipt = h.repo.accept(permit, h.reply(review.request), h.clock)
        h.repo.close(); assertNotNull(h.repo.checkReceipt(receipt, h.clock)); denied { h.repo.authorizeReviewedSend(review, h.clock) }
        h.f.reopen()
        val reopened = SqlSelectedQuestionAuthorizationRepository(h.f.gateway)
        try {
            assertNotNull(reopened.checkReceipt(receipt, h.clock)); assertNotNull(reopened.check(permit, h.clock))
            denied { reopened.authorizeReviewedSend(review, h.clock) }; denied { reopened.withdraw(permit) }
            val fresh = reopened.review(h.request(), h.connection, PROMPT, h.clock)
            val freshPermit = reopened.authorizeReviewedSend(fresh, h.clock); assertNull(reopened.check(freshPermit, h.clock))
            denied { h.repo.withdraw(freshPermit) }; assertNull(reopened.check(freshPermit, h.clock))
        } finally { reopened.close() }
    }

    @Test fun resultWaitsForCommitAndFailedCommitRollsBackAllMembersAndRejectsInvalidReferences(): Unit = Harness().use { h ->
        val permit = h.authorize(); h.repo.admit(permit, h.clock); val reply = h.reply(permit.review.request)
        val bad = SelectedQuestionReply(reply.requestId, reply.selectionId, false,
            listOf(SelectedQuestionParagraph("unsupported", listOf(reply.paragraphs[0].references[0].copy(quote = "forged")))))
        denied { h.repo.accept(permit, bad, h.clock) }; assertEquals(listOf("ADMITTED", "ADMITTED"), h.jobs().map { it[1] })
        val committedStateWritten = AtomicBoolean(); val atCommit = CountDownLatch(1); val release = CountDownLatch(1)
        val result = AtomicReference<SelectedQuestionSendReceipt?>(); val failure = AtomicReference<Throwable?>()
        h.f.db.afterSql = { sql -> if (sql.startsWith("UPDATE local_jobs SET state=")) committedStateWritten.set(true) }
        h.f.db.beforeCommit = { if (committedStateWritten.get()) { atCommit.countDown(); await(release); error("synthetic commit failure") } }
        val worker = thread { try { result.set(h.repo.accept(permit, reply, h.clock)) } catch (t: Throwable) { failure.set(t) } }
        try { await(atCommit); assertNull(result.get()); release.countDown(); join(worker) } finally { release.countDown(); join(worker); h.f.db.beforeCommit = null; h.f.db.afterSql = null }
        assertNull(result.get()); assertNotNull(failure.get()); assertEquals(listOf("ADMITTED", "ADMITTED"), h.jobs().map { it[1] })
        val receipt = h.repo.accept(permit, reply, h.clock); assertNull(h.repo.checkReceipt(receipt, h.clock))
    }

    @Test fun lostAcknowledgementsNeverGrantSecondSendAndOnlyExactCurrentResultCanRecover(): Unit = Harness().use { h ->
        val lose = AtomicBoolean()
        val uncertain = object : RecordsSqlDatabase {
            private val depth = ThreadLocal.withInitial { 0 }
            override fun <T> transaction(block: (RecordsSqlConnection) -> T): T {
                val outermost = depth.get() == 0
                depth.set(depth.get() + 1)
                val result = try { h.f.gateway.transaction(block) } finally { depth.set(depth.get() - 1) }
                if (outermost && lose.compareAndSet(true, false)) error("synthetic acknowledgement lost after actual COMMIT")
                return result
            }
        }
        val repo = SqlSelectedQuestionAuthorizationRepository(uncertain)
        try {
            val first = repo.review(h.request(), h.connection, PROMPT, h.clock)
            lose.set(true); denied { repo.authorizeReviewedSend(first, h.clock) }
            assertEquals(2, h.jobs().size); denied { repo.authorizeReviewedSend(first, h.clock) }; assertEquals(2, h.jobs().size)
            val second = repo.authorizeReviewedSend(repo.review(h.request(), h.connection, PROMPT, h.clock), h.clock)
            lose.set(true); denied { repo.admit(second, h.clock) }; denied { repo.admit(second, h.clock) }
            val reply = h.reply(second.review.request)
            lose.set(true); denied { repo.accept(second, reply, h.clock) }
            val recovered = repo.accept(second, reply, h.clock); assertNull(repo.checkReceipt(recovered, h.clock))
            denied { repo.accept(second, SelectedQuestionReply(reply.requestId, reply.selectionId, true, emptyList()), h.clock) }
            h.withdrawSource(1); denied { repo.accept(second, reply, h.clock) }
        } finally { repo.close() }
    }

    @Test fun finalObservedExpiryAfterAllMemberWritesRollsBackAuthorizationAdmissionAndAcceptance(): Unit {
        val wronglyAccepted = mutableListOf<String>()
        for (stage in listOf("authorize", "admit", "accept")) Harness().use { h ->
            val review = h.review()
            val permit = if (stage != "authorize") h.repo.authorizeReviewedSend(review, h.clock) else null
            if (stage == "accept") h.repo.admit(permit!!, h.clock)
            var late = false; var writes = 0
            h.f.db.afterSql = { sql ->
                if (stage == "authorize" && sql.startsWith("INSERT INTO local_jobs")) late = true
                if (stage == "admit" && sql.startsWith("UPDATE remote_text_jobs SET state='ADMITTED'") && ++writes == 2) late = true
                if (stage == "accept" && sql.startsWith("UPDATE remote_text_jobs SET state='COMMITTED'") && ++writes == 2) late = true
            }
            val clock = { if (late) NOW.plusSeconds(600) else NOW }
            val failure = runCatching { when (stage) {
                "authorize" -> h.repo.authorizeReviewedSend(review, clock)
                "admit" -> h.repo.admit(permit!!, clock)
                else -> h.repo.accept(permit!!, h.reply(review.request), clock)
            } }.exceptionOrNull()
            h.f.db.afterSql = null
            if (failure !is SelectedQuestionSendDenied) wronglyAccepted += stage
            else {
                if (stage == "authorize") { assertTrue(h.jobs().isEmpty()); assertTrue(h.groups().isEmpty()) }
                else assertEquals(List(2) { if (stage == "admit") "QUEUED" else "ADMITTED" }, h.jobs().map { it[1] })
            }
        }
        assertEquals("Observed expiry must roll back every stage", emptyList<String>(), wronglyAccepted)
    }

    @Test fun successfulCommitCannotInvalidateItselfThroughPendingSubscription(): Unit = Harness().use { h ->
        val permit = h.authorize(); val pending = AtomicReference<SourceBoundTextSubscription?>()
        val wrapper = object : SelectedQuestionAuthorizationRepository by h.repo {
            override fun subscribe(p: SelectedQuestionSendPermit, clock: () -> Instant, onInvalidated: (SendDenial) -> Unit): SourceBoundTextSubscription =
                h.repo.subscribe(p, clock, onInvalidated).also { pending.set(it) }
            override fun accept(p: SelectedQuestionSendPermit, reply: SelectedQuestionReply, clock: () -> Instant): SelectedQuestionSendReceipt {
                val receipt = h.repo.accept(p, reply, clock)
                pending.get()!!.check() // Deterministic observer turn after COMMIT, before returning to executor.
                return receipt
            }
        }
        val service = h.service { wire(AtomicInteger()) { sink -> sink(h.sse(h.reply(permit.review.request))) } }
        val receipt = PersistedLocalQuestionExchange(wrapper, permit, service, h.clock).execute()
        assertNull(h.repo.checkReceipt(receipt, h.clock))
    }

    private class Harness(approved: Boolean = true) : AutoCloseable {
        val f = PermanentErasureFixture()
        val ids = listOf(f.entry(text = "选取甲🙂片段 UNSELECTED_PRIVATE_TAIL_A"), f.entry(text = "选取乙🙂片段 UNSELECTED_PRIVATE_TAIL_B"))
        val sources = ids.map { SourceVersion(it, 1) }
        val connection = ProviderConnection("synthetic-profile", 1, "https://fixture.invalid/custom/v1/", ConnectionProtocol.CHAT_COMPLETIONS,
            "synthetic-model", SecretReference("synthetic-unresolved-ref"))
        val raw = RemoteTextTransactions(f.gateway)
        val repo = SqlSelectedQuestionAuthorizationRepository(f.gateway)
        val clock = { NOW }
        init { f.entry(text = "UNSELECTED_OTHER_RECORD"); if (approved) ids.indices.forEach { approve(it) } }
        fun approve(index: Int, purpose: TextPurpose = TextPurpose.CONVERSATION) {
            val id = ids[index]; val before = raw.readAccess(id)!!
            assertNull(raw.updateAuthorization(sources[index], before.generation, ProcessingPolicy.LOCAL_ONLY, connection, null))
            val records = SqlRecordAuthorizationRepository(f.gateway); val snapshot = records.readAuthorization(sources[index])
            val preview = records.previewAuthorization(snapshot.binding, purpose, clock)
            records.changeAuthorization(RecordAuthorizationChange.confirm(preview, NOW.minusSeconds(1), NOW.plusSeconds(600), UUID.randomUUID().toString()), clock)
        }
        fun request(id: String = UUID.randomUUID().toString(), question: String = "本次选取里提到了什么？", selection: String = "synthetic-selection",
            asked: Long = NOW.toEpochMilli(), zone: String = "UTC", connection: ProviderConnection = this.connection,
            sources: List<SelectedQuestionSource> = this.sources.mapIndexed { i, s ->
                val quote = if (i == 0) "选取甲🙂片段" else "选取乙🙂片段"
                SelectedQuestionSource(OriginalTextReference(s, 0, quote.codePointCount(0, quote.length), quote), 5, "UTC", ProcessingPolicy.REMOTE_ALLOWED)
            }) = SelectedQuestionRequest(id, selection, question, SelectedQuestionTarget(connection.protocol, connection.apiRoot, connection.modelId, connection.version), asked, zone, sources)
        fun review() = repo.review(request(), connection, PROMPT, clock)
        fun authorize() = repo.authorizeReviewedSend(review(), clock)
        fun jobs() = f.db.read("SELECT id,state FROM remote_text_jobs ORDER BY entryId,id")
        fun groups() = f.db.read("SELECT id,state FROM local_jobs WHERE operation='SELECTED_QUESTION_SEND_V1' ORDER BY id")
        fun authority() = f.db.read("SELECT e.id,e.privacyPolicy,a.generation,hex(a.connection),hex(a.consent) FROM entries e LEFT JOIN remote_source_access a ON a.entryId=e.id ORDER BY e.id")
        fun withdrawSource(index: Int, db: RecordsSqlDatabase = f.gateway) {
            val records = SqlRecordAuthorizationRepository(db); val current = records.readAuthorization(sources[index])
            records.changeAuthorization(RecordAuthorizationChange.withdraw(current, ProcessingPolicy.LOCAL_ONLY, UUID.randomUUID().toString()), clock)
        }
        fun revise(index: Int) = f.db.transaction { sql ->
            val text = "合成新版本"
            sql.execute("INSERT INTO entry_revisions VALUES(?,2,?,?,5)", listOf(ids[index], text, OriginalExports.hash(text)))
            sql.execute("UPDATE entries SET currentRevision=2 WHERE id=?", listOf(ids[index]))
        }
        fun reply(request: SelectedQuestionRequest) = SelectedQuestionReply(request.id, request.selectionId, false,
            request.sources.map { SelectedQuestionParagraph("生成内容待核对：" + it.reference.quote, listOf(it.reference)) })
        fun service(factory: (PreparedRequest) -> LocalQuestionWireExchange) = LocalProtocolQuestionService(request().target, LocalQuestionProtocolFixture(factory))
        fun sse(reply: SelectedQuestionReply): ByteArray {
            val answer = buildJsonObject {
                put("requestId", reply.requestId); put("selectionId", reply.selectionId); put("noEvidence", reply.noEvidence)
                putJsonArray("paragraphs") { reply.paragraphs.forEach { p -> add(buildJsonObject {
                    put("text", p.text); putJsonArray("references") { p.references.forEach { r -> add(buildJsonObject {
                        put("sourceId", r.source.sourceId); put("sourceRevision", r.source.revision); put("start", r.start); put("end", r.end); put("quote", r.quote)
                    }) } }
                }) } }
            }.toString()
            val content = buildJsonObject { putJsonArray("choices") { add(buildJsonObject { put("index", 0); putJsonObject("delta") { put("role", "assistant"); put("content", answer) } }) } }
            return ("data: $content\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").toByteArray()
        }
        override fun close() { repo.close(); f.close() }
    }
    companion object {
        private const val PROMPT = "selected-question-protocol-v1"
        private val NOW = Instant.parse("2026-10-18T20:00:00Z")
        private fun denied(block: () -> Unit) { assertThrows(SelectedQuestionSendDenied::class.java, block) }
        private fun await(latch: CountDownLatch) { check(latch.await(5, TimeUnit.SECONDS)) { "Owned test gate timed out" } }
        private fun join(worker: Thread) { worker.join(6000); check(!worker.isAlive) { "Owned test worker did not finish" } }
        private fun wire(cancels: AtomicInteger, cancel: () -> Unit = {}, execute: ((ByteArray) -> Unit) -> Unit) = object : LocalQuestionWireExchange {
            override fun execute(onChunk: (ByteArray) -> Unit) = execute(onChunk)
            override fun cancel() { cancels.incrementAndGet(); cancel() }
        }
    }
}
