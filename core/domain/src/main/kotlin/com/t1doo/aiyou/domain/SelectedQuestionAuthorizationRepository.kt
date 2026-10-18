package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.time.Instant

/** Blocking IO on the original encrypted records owner, with all sources in one transaction.
 * This capability does not configure providers, create ConsentGrants, resolve secrets or send.
 * A local selection confirmation is not an authorizeReviewedSend call.
 */
interface SelectedQuestionAuthorizationRepository {
    /** Read-only, requires current existing grants; show question/excerpts/full destination before authorization. */
    fun review(request: SelectedQuestionRequest, connection: ProviderConnection, promptVersion: String,
        clock: () -> Instant): SelectedQuestionSendReview
    /** Explicit user decision for precisely this reviewed request; atomic and non-replayable. */
    fun authorizeReviewedSend(review: SelectedQuestionSendReview, clock: () -> Instant): SelectedQuestionSendPermit
    fun check(permit: SelectedQuestionSendPermit, clock: () -> Instant): SendDenial?
    fun subscribe(permit: SelectedQuestionSendPermit, clock: () -> Instant,
        onInvalidated: (SendDenial) -> Unit): SourceBoundTextSubscription
    fun admit(permit: SelectedQuestionSendPermit, clock: () -> Instant)
    /** Complete output only; waits for COMMIT. Exact current-result ACK recovery cannot send again.
     * No answer text or permanent fact is stored. */
    fun accept(permit: SelectedQuestionSendPermit, reply: SelectedQuestionReply,
        clock: () -> Instant): SelectedQuestionSendReceipt
    /** Fresh receipt check before display; a receipt is not permanent authority. */
    fun checkReceipt(receipt: SelectedQuestionSendReceipt, clock: () -> Instant): SendDenial?
    /** Explicit request withdrawal, including a previously completed receipt. Does not alter source grants. */
    fun withdraw(permit: SelectedQuestionSendPermit)
    /** Execution-owner cleanup only. Never revokes an already accepted receipt. */
    fun cancelPending(permit: SelectedQuestionSendPermit)
}

class SelectedQuestionSendDenied(val reason: SendDenial) : IllegalStateException("Selected question send denied: $reason")
