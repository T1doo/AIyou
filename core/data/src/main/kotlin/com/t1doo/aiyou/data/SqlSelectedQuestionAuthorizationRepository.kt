package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Reuses record grants, remote source jobs and local_jobs receipts. No new schema/permission store.
 * Nested product calls share this exact outer SQL transaction. Callbacks never run inside it.
 */
internal class SqlSelectedQuestionAuthorizationRepository(private val database: RecordsSqlDatabase) :
    SelectedQuestionAuthorizationRepository, AutoCloseable {
    private val access = RemoteTextTransactions(database)
    private val records = SqlRecordAuthorizationRepository(database)
    private val sessionId = UUID.randomUUID().toString()
    private val closed = AtomicBoolean()

    override fun close() { closed.set(true) }
    private fun live(clock: () -> Instant): Instant {
        if (closed.get()) deny(SendDenial.QUEUE_INVALIDATED)
        return clock().also { if (closed.get()) deny(SendDenial.QUEUE_INVALIDATED) }
    }
    override fun review(request: SelectedQuestionRequest, connection: ProviderConnection, promptVersion: String,
        clock: () -> Instant): SelectedQuestionSendReview = safely { database.transaction { sql ->
        val snapshots = request.sources.map { records.readAuthorization(it.reference.source) }
        val value = SelectedQuestionSendReview(request, connection, promptVersion, sessionId, snapshots)
        verifySources(sql, value, clock)
        value
    } }

    override fun authorizeReviewedSend(review: SelectedQuestionSendReview, clock: () -> Instant): SelectedQuestionSendPermit =
        safely { database.transaction { sql ->
            verifySources(sql, review, clock)
            val proof = SelectedQuestionSendProof.review(review)
            if (group(sql, review) != null) deny(SendDenial.ALREADY_SENT)
            val ids = review.sources.map { source ->
                // Failure on any member throws past the OUTER transaction: no partial authorization.
                when (val outcome = access.queue(RemoteSendIntent(source.binding.source, TextPurpose.CONVERSATION),
                    "QS1:$proof", { live(clock) })) {
                    is SourceQueueOutcome.Queued -> outcome.job.id
                    else -> deny(SendDenial.QUEUE_INVALIDATED)
                }
            }
            verifySources(sql, review, clock)
            val anchor = review.sources.first().binding.source
            check(sql.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,?,?,?)",
                listOf(id(review), anchor.sourceId, anchor.revision, OPERATION, state("QUEUED", proof))) == 1)
            finalTime(review, clock)
            SelectedQuestionSendPermit(review, ids)
        } }

    override fun check(permit: SelectedQuestionSendPermit, clock: () -> Instant): SendDenial? = rejection {
        database.transaction { sql -> verify(sql, permit, setOf("QUEUED", "ADMITTED"), clock) }
    }
    override fun subscribe(permit: SelectedQuestionSendPermit, clock: () -> Instant,
        onInvalidated: (SendDenial) -> Unit): SourceBoundTextSubscription =
        PersistentSourceBoundTextSubscription.open({ check(permit, clock) }, onInvalidated)

    override fun admit(permit: SelectedQuestionSendPermit, clock: () -> Instant) = safely {
        database.transaction { sql ->
            val proof = verify(sql, permit, setOf("QUEUED"), clock)
            permit.memberIds.forEach { member ->
                check(sql.execute("UPDATE remote_text_jobs SET state='ADMITTED' WHERE id=? AND state='QUEUED'", listOf(member)) == 1)
            }
            transition(sql, permit.review, state("QUEUED", proof), state("ADMITTED", proof))
            finalTime(permit.review, clock)
        }
    }

    override fun accept(permit: SelectedQuestionSendPermit, reply: SelectedQuestionReply,
        clock: () -> Instant): SelectedQuestionSendReceipt = safely { database.transaction { sql ->
        val result = SelectedQuestionSendProof.reply(permit.review.request, reply)
        val expected = SelectedQuestionSendProof.review(permit.review)
        if (group(sql, permit.review) == state("COMMITTED", expected, result)) {
            verify(sql, permit, setOf("COMMITTED"), clock, result)
            return@transaction SelectedQuestionSendReceipt(permit, reply, result)
        }
        val proof = verify(sql, permit, setOf("ADMITTED"), clock)
        verifySources(sql, permit.review, clock)
        permit.memberIds.forEach { member ->
            check(sql.execute("UPDATE remote_text_jobs SET state='COMMITTED' WHERE id=? AND state='ADMITTED'", listOf(member)) == 1)
        }
        transition(sql, permit.review, state("ADMITTED", proof), state("COMMITTED", proof, result))
        finalTime(permit.review, clock)
        SelectedQuestionSendReceipt(permit, reply, result)
    } }
    override fun checkReceipt(receipt: SelectedQuestionSendReceipt, clock: () -> Instant): SendDenial? = rejection {
        database.transaction { sql ->
            val proof = verify(sql, receipt.permit, setOf("COMMITTED"), clock, receipt.resultHash)
            if (SelectedQuestionSendProof.reply(receipt.permit.review.request, receipt.reply) != receipt.resultHash ||
                group(sql, receipt.permit.review) != state("COMMITTED", proof, receipt.resultHash)) deny(SendDenial.QUEUE_INVALIDATED)
            verifySources(sql, receipt.permit.review, clock)
        }
    }
    override fun withdraw(permit: SelectedQuestionSendPermit) = finish(permit, includeCommitted = true)
    override fun cancelPending(permit: SelectedQuestionSendPermit) = finish(permit, includeCommitted = false)
    private fun finish(permit: SelectedQuestionSendPermit, includeCommitted: Boolean) = safely {
        database.transaction { sql ->
            val review = permit.review
            if (review.sessionId != sessionId) deny(SendDenial.QUEUE_INVALIDATED)
            val proof = SelectedQuestionSendProof.review(review)
            val old = group(sql, review) ?: return@transaction
            val allowed = old == state("QUEUED", proof) || old == state("ADMITTED", proof) ||
                (includeCommitted && old.startsWith(state("COMMITTED", proof) + ":"))
            if (!allowed) return@transaction
            // Only this immutable group's members; no source grant change and no sibling cancellation.
            permit.memberIds.forEachIndexed { index, member ->
                member(sql, permit, index, member, proof)
                sql.execute("UPDATE remote_text_jobs SET state='CANCELLED' WHERE id=? AND state IN ('QUEUED','ADMITTED')", listOf(member))
            }
            transition(sql, review, old, state("CANCELLED", proof))
        }
    }

    private fun verify(sql: RecordsSqlConnection, permit: SelectedQuestionSendPermit, statuses: Set<String>,
        clock: () -> Instant, resultHash: String? = null): String {
        val review = permit.review
        verifySources(sql, review, clock)
        val proof = SelectedQuestionSendProof.review(review)
        val stored = group(sql, review)
        if (statuses.none { stored == state(it, proof, if (it == "COMMITTED") resultHash else null) })
            deny(if (stored?.startsWith(state("ADMITTED", proof)) == true || stored?.startsWith(state("COMMITTED", proof)) == true)
                SendDenial.ALREADY_SENT else SendDenial.QUEUE_INVALIDATED)
        permit.memberIds.forEachIndexed { index, member ->
            val status = member(sql, permit, index, member, proof)
            if (status !in statuses) deny(SendDenial.QUEUE_INVALIDATED)
        }
        verifySources(sql, review, clock)
        return proof
    }
    private fun member(sql: RecordsSqlConnection, permit: SelectedQuestionSendPermit, index: Int,
        member: String, proof: String): String {
        val expected = permit.review.sources[index]
        val grant = expected.consent ?: deny(SendDenial.CONSENT_MISSING)
        val connection = permit.review.connection
        val source = expected.binding.source
        val row = sql.query("SELECT bindingKey,entryId,sourceRevision,generation,purpose,promptVersion,connection,consent,state,sourceHash FROM remote_text_jobs WHERE id=?",
            listOf(member)).singleOrNull() ?: deny(SendDenial.QUEUE_INVALIDATED)
        val binding = RemoteTextCodec.binding(source, TextPurpose.CONVERSATION, "QS1:$proof", expected.binding.generation, connection, grant)
        if (row.take(6) != listOf(binding, source.sourceId, source.revision, expected.binding.generation, TextPurpose.CONVERSATION.name, "QS1:$proof") ||
            !(row[6] as? ByteArray).contentEquals(RemoteTextCodec.encodeConnection(connection)) ||
            !(row[7] as? ByteArray).contentEquals(RemoteTextCodec.encodeConsent(grant)) || row[9] != expected.binding.sourceHash)
            deny(SendDenial.QUEUE_INVALIDATED)
        return row[8] as? String ?: deny(SendDenial.QUEUE_INVALIDATED)
    }
    private fun verifySources(sql: RecordsSqlConnection, review: SelectedQuestionSendReview, clock: () -> Instant) {
        if (review.sessionId != sessionId) deny(SendDenial.QUEUE_INVALIDATED)
        review.request.sources.forEachIndexed { index, source ->
            val current = records.readAuthorization(source.reference.source)
            if (current != review.sources[index]) deny(SendDenial.QUEUE_INVALIDATED)
            val grant = current.consent ?: deny(SendDenial.CONSENT_MISSING)
            val now = live(clock)
            if (current.policy != ProcessingPolicy.REMOTE_ALLOWED) deny(SendDenial.POLICY_BLOCKED)
            if (current.connection != review.connection || grant.connection != review.connection || grant.source != current.binding.source ||
                grant.purpose != TextPurpose.CONVERSATION) deny(SendDenial.SCOPE_MISMATCH)
            if (grant.revoked) deny(SendDenial.CONSENT_REVOKED)
            if (now < grant.issuedAt || now >= grant.expiresAt) deny(SendDenial.CONSENT_NOT_CURRENT)
            val row = sql.query("SELECT r.originalText,r.contentHash,e.capturedAtMillis,e.captureTimeZone FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision WHERE e.id=? AND e.currentRevision=?",
                listOf(source.reference.source.sourceId, source.reference.source.revision)).singleOrNull() ?: deny(SendDenial.SOURCE_CHANGED)
            val text = row[0] as String
            val r = source.reference
            if (row[1] != current.binding.sourceHash || RemoteTextCodec.textHash(text) != current.binding.sourceHash ||
                row[2] != source.capturedAtMillis || row[3] != source.captureTimeZone || r.end > text.codePointCount(0, text.length) ||
                text.substring(text.offsetByCodePoints(0, r.start), text.offsetByCodePoints(0, r.end)) != r.quote) deny(SendDenial.SOURCE_CHANGED)
        }
        // One final time applies to ALL intervals, so expiry while walking a multi-source set fails closed.
        finalTime(review, clock)
    }
    private fun finalTime(review: SelectedQuestionSendReview, clock: () -> Instant) {
        val now = live(clock)
        if (review.sources.any { val g = it.consent!!; now < g.issuedAt || now >= g.expiresAt }) deny(SendDenial.CONSENT_NOT_CURRENT)
    }
    private fun group(sql: RecordsSqlConnection, review: SelectedQuestionSendReview): String? {
        val row = sql.query("SELECT entryId,sourceRevision,operation,state FROM local_jobs WHERE id=?", listOf(id(review))).singleOrNull() ?: return null
        val anchor = review.sources.first().binding.source
        if (row.take(3) != listOf(anchor.sourceId, anchor.revision, OPERATION)) deny(SendDenial.QUEUE_INVALIDATED)
        return row[3] as String
    }
    private fun transition(sql: RecordsSqlConnection, review: SelectedQuestionSendReview, old: String, next: String) {
        if (sql.execute("UPDATE local_jobs SET state=? WHERE id=? AND operation=? AND state=?", listOf(next, id(review), OPERATION, old)) != 1)
            deny(SendDenial.QUEUE_INVALIDATED)
    }
    private fun id(review: SelectedQuestionSendReview) = "QUESTION_SEND:" + review.request.id
    private fun state(status: String, proof: String, result: String? = null) = "QS1:$status:$proof" + (result?.let { ":$it" } ?: "")
    private fun deny(reason: SendDenial): Nothing = throw SelectedQuestionSendDenied(reason)
    private fun rejection(block: () -> Unit): SendDenial? = try { safely(block); null } catch (failure: SelectedQuestionSendDenied) { failure.reason }
    private fun <T> safely(block: () -> T): T = try { block() } catch (failure: Throwable) {
        when (failure) {
            is CancellationException, is VirtualMachineError, is ThreadDeath, is LinkageError -> throw failure
            is SelectedQuestionSendDenied -> throw failure
            else -> deny(SendDenial.STATE_UNAVAILABLE)
        }
    }
    companion object { private const val OPERATION = "SELECTED_QUESTION_SEND_V1" }
}
