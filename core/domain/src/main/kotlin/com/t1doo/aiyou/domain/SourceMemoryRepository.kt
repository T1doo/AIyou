package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

/** Manual source review only. There is no model, network or task-execution authority. */
interface SourceMemoryRepository {
    fun preview(source: SourceVersion): SourceMemoryPreview
    fun memories(source: SourceVersion): List<SourceMemorySnapshot>
    fun create(request: SourceMemoryCreate): SourceMemorySnapshot
    fun mutate(request: SourceMemoryMutation): SourceMemorySnapshot
}
class SourceMemoryConflict : IllegalStateException("Source memory or source changed")
class SourceMemoryStorageFailure : IllegalStateException("Source memory unavailable")
class InvalidSourceMemory : IllegalArgumentException("Invalid source memory")
class SourceMemoryLimit : IllegalStateException("Source memory capacity exceeded")

object SourceMemoryRules {
    const val MAX_VERSIONS = 8
    const val MAX_MEMORIES = 20
    fun validate(content: SourceMemoryContent, source: SourceVersion, original: String) {
        val ref = content.reference
        require(ref.source == source && LocalCandidates.validUnicode(original))
        require(ref.end <= original.codePointCount(0, original.length))
        require(original.substring(original.offsetByCodePoints(0, ref.start), original.offsetByCodePoints(0, ref.end)) == ref.quote)
        val at=original.offsetByCodePoints(0,ref.start)
        require(original.indexOf(ref.quote)==at && original.indexOf(ref.quote,at+1)<0)
    }
    /** Exact unique source matching, not language understanding or automatic classification. */
    fun reference(preview: SourceMemoryPreview, quote: String): OriginalTextReference {
        require(quote.isNotBlank() && LocalCandidates.validUnicode(quote) && quote.codePointCount(0, quote.length) <= 320)
        val at = preview.original.indexOf(quote)
        require(at >= 0 && preview.original.indexOf(quote, at + 1) < 0)
        val start = preview.original.codePointCount(0, at)
        return OriginalTextReference(preview.source, start, start + quote.codePointCount(0, quote.length), quote)
    }
    fun decision(previous: SourceMemoryReview, action: SourceMemoryAction): SourceMemoryReview = when (action) {
        SourceMemoryAction.ACCEPT -> { require(previous == SourceMemoryReview.PROPOSED); SourceMemoryReview.ACCEPTED }
        SourceMemoryAction.REJECT -> { require(previous == SourceMemoryReview.PROPOSED); SourceMemoryReview.REJECTED }
        SourceMemoryAction.REVOKE -> { require(previous == SourceMemoryReview.ACCEPTED); SourceMemoryReview.REVOKED }
        SourceMemoryAction.REOPEN -> { require(previous in setOf(SourceMemoryReview.REJECTED, SourceMemoryReview.REVOKED)); SourceMemoryReview.PROPOSED }
        SourceMemoryAction.CORRECT -> { require(previous != SourceMemoryReview.SUPERSEDED); SourceMemoryReview.PROPOSED }
    }
}
