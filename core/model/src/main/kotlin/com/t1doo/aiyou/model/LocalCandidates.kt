package com.t1doo.aiyou.model

import java.util.Collections

/** An exact source excerpt, never an inferred fact, a manual label, or an executable task. */
enum class ExcerptReview { PROPOSED, ACCEPTED, REJECTED }
enum class LocalCandidateState { RUNNING, READY, CANCELLED, INVALIDATED }

data class ExcerptProposal(val start: Int, val end: Int, val quote: String) {
    init {
        require(start >= 0 && end > start && quote.isNotBlank() && LocalCandidates.validUnicode(quote))
        require(quote.codePointCount(0, quote.length) <= LocalCandidates.MAX_EXCERPT_POINTS)
    }
    override fun toString() = "ExcerptProposal(redacted)"
}

class ExcerptDocument(val source: SourceVersion, val sourceHash: String, proposals: List<ExcerptProposal>) {
    val proposals: List<ExcerptProposal> = Collections.unmodifiableList(ArrayList(proposals))
    init {
        ManualTags.identifier(source.sourceId)
        require(source.revision in 1..Int.MAX_VALUE.toLong() && sourceHash.matches(Regex("[0-9a-f]{64}")))
        require(proposals.size in 1..LocalCandidates.MAX_ITEMS && proposals.distinct().size == proposals.size)
    }
    override fun toString() = "ExcerptDocument(redacted)"
}

data class ReviewedExcerpt(val id: String, val proposal: ExcerptProposal, val review: ExcerptReview) {
    init { ManualTags.identifier(id) }
    override fun toString() = "ReviewedExcerpt(review=" + review + ", redacted)"
}

class LocalCandidateStart(val id: String, val source: SourceVersion, val requestedAtMillis: Long) {
    init { ManualTags.identifier(id); ManualTags.identifier(source.sourceId); require(source.revision in 1..Int.MAX_VALUE.toLong() && requestedAtMillis >= 0) }
    override fun toString() = "LocalCandidateStart(redacted)"
}

/** Repository-produced immutable capability. The repository rechecks every field at completion. */
class LocalCandidateJob(val start: LocalCandidateStart, val sourceHash: String, val generation: Long,
    val original: String, val capturedAtMillis: Long, val captureTimeZone: String) {
    override fun toString() = "LocalCandidateJob(redacted)"
}

class LocalCandidateBatch(val id: String, val source: SourceVersion, val sourceHash: String,
    val generation: Long, val version: Long, val state: LocalCandidateState, val createdAtMillis: Long,
    items: List<ReviewedExcerpt>) {
    val items: List<ReviewedExcerpt> = Collections.unmodifiableList(ArrayList(items))
    override fun toString() = "LocalCandidateBatch(state=" + state + ", version=" + version + ", redacted)"
}

class ExcerptReviewRequest(val batchId: String, val source: SourceVersion, val sourceHash: String,
    val generation: Long, val expectedVersion: Long, val itemId: String, val decision: ExcerptReview,
    val mutationId: String, val requestedAtMillis: Long) {
    init {
        ManualTags.identifier(batchId); ManualTags.identifier(itemId); ManualTags.identifier(mutationId)
        require(expectedVersion in 1 until Long.MAX_VALUE && generation >= 0 && requestedAtMillis >= 0)
        require(decision != ExcerptReview.PROPOSED)
    }
    override fun toString() = "ExcerptReviewRequest(redacted)"
}

class CandidateCancelRequest(val batchId: String, val source: SourceVersion, val sourceHash: String,
    val generation: Long, val expectedVersion: Long, val mutationId: String, val requestedAtMillis: Long) {
    init {
        ManualTags.identifier(batchId); ManualTags.identifier(mutationId)
        require(expectedVersion in 0 until Long.MAX_VALUE && generation >= 0 && requestedAtMillis >= 0)
    }
    override fun toString() = "CandidateCancelRequest(redacted)"
}

/** Explicit correction of a persisted review; never restores an invalidated source. */
class ExcerptReopenRequest(val batchId: String, val source: SourceVersion, val sourceHash: String,
    val generation: Long, val expectedVersion: Long, val itemId: String, val previousReview: ExcerptReview,
    val mutationId: String, val requestedAtMillis: Long) {
    init {
        ManualTags.identifier(batchId); ManualTags.identifier(itemId); ManualTags.identifier(mutationId)
        require(expectedVersion in 1 until Long.MAX_VALUE && generation >= 0 && requestedAtMillis >= 0)
        require(previousReview != ExcerptReview.PROPOSED)
    }
    override fun toString() = "ExcerptReopenRequest(redacted)"
}

object LocalCandidates {
    const val RULE_VERSION = 1
    const val MAX_ITEMS = 8
    const val MAX_EXCERPT_POINTS = 320
    const val MAX_RESPONSE_CHARS = 16_384
    const val PROVENANCE = "LOCAL_RULE_EXCERPT"
    fun validUnicode(value: String): Boolean {
        var at = 0
        while (at < value.length) {
            val ch = value[at++]
            if (ch.isHighSurrogate()) {
                if (at == value.length || !value[at++].isLowSurrogate()) return false
            } else if (ch.isLowSurrogate()) return false
        }
        return true
    }
}
