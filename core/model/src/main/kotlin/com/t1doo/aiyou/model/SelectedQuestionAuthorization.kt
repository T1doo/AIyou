package com.t1doo.aiyou.model

import java.util.Collections

/** A separate send review, never created by the local selection-confirmation controller.
 * Existing per-source CONVERSATION grants must already be current. This adds no record permission.
 */
class SelectedQuestionSendReview(val request: SelectedQuestionRequest, val connection: ProviderConnection,
    val promptVersion: String, val sessionId: String, sources: List<RecordAuthorizationSnapshot>) {
    val sources: List<RecordAuthorizationSnapshot> = Collections.unmodifiableList(ArrayList(sources))
    init {
        require(promptVersion.isNotBlank() && promptVersion.length <= 128 && LocalCandidates.validUnicode(promptVersion))
        require(sessionId.length in 1..64)
        require(request.target == SelectedQuestionTarget(connection.protocol, connection.apiRoot, connection.modelId, connection.version))
        require(this.sources.map { it.binding.source } == request.sources.map { it.reference.source })
        require(request.sources.all { it.policy == ProcessingPolicy.REMOTE_ALLOWED })
    }
    override fun toString() = "SelectedQuestionSendReview(redacted)"
}

/** A single persisted group of existing source jobs, bound to an opened records owner. */
class SelectedQuestionSendPermit(val review: SelectedQuestionSendReview, memberIds: List<String>) {
    val memberIds: List<String> = Collections.unmodifiableList(ArrayList(memberIds))
    init { require(memberIds.size == review.sources.size && memberIds.toSet().size == memberIds.size) }
    override fun toString() = "SelectedQuestionSendPermit(redacted)"
}

/** Ephemeral answer plus persisted result digest; neither a fact nor reusable sending permission. */
class SelectedQuestionSendReceipt(val permit: SelectedQuestionSendPermit, val reply: SelectedQuestionReply,
    val resultHash: String) {
    override fun toString() = "SelectedQuestionSendReceipt(redacted)"
}
