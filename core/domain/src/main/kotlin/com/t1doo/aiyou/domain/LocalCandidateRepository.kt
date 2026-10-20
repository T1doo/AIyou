package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

/** Blocking IO over the same encrypted records owner. No credentials or remote authority. */
interface LocalCandidateRepository {
    fun begin(request: LocalCandidateStart): LocalCandidateJob
    /** Read an existing authenticated RUNNING/0 batch; never create or restart a terminal batch. */
    fun resume(expected: LocalCandidateBatch): LocalCandidateJob
    fun complete(job: LocalCandidateJob, document: ExcerptDocument): LocalCandidateBatch
    fun batches(source: SourceVersion): List<LocalCandidateBatch>
    fun review(request: ExcerptReviewRequest): LocalCandidateBatch
    fun reopen(request: ExcerptReopenRequest): LocalCandidateBatch
    fun cancel(request: CandidateCancelRequest): LocalCandidateBatch
}

class LocalCandidateConflict : IllegalStateException("Local candidate or source changed")
class LocalCandidateStorageFailure : IllegalStateException("Local candidates unavailable")
class InvalidExcerptResponse : IllegalArgumentException("Invalid local excerpt response")

/** Exact provenance only. It deliberately makes no semantic entailment or fact inference claim. */
object LocalExcerptRules {
    fun validate(document: ExcerptDocument, source: SourceVersion, hash: String, original: String) {
        require(document.source == source && document.sourceHash == hash && OriginalExports.hash(original) == hash)
        val points = original.codePointCount(0, original.length)
        document.proposals.forEach { item ->
            require(item.end <= points)
            val start = original.offsetByCodePoints(0, item.start)
            val end = original.offsetByCodePoints(0, item.end)
            require(original.substring(start, end) == item.quote)
        }
    }

    /** One bounded literal excerpt. Negation, quotation and hypothetical words are preserved. */
    fun extract(job: LocalCandidateJob): ExcerptDocument {
        val startUtf = job.original.indexOfFirst { !it.isWhitespace() }.also { require(it >= 0) }
        val start = job.original.codePointCount(0, startUtf)
        val count = minOf(LocalCandidates.MAX_EXCERPT_POINTS, job.original.codePointCount(startUtf, job.original.length))
        val quote = job.original.substring(startUtf, job.original.offsetByCodePoints(startUtf, count))
        return ExcerptDocument(job.start.source, job.sourceHash, listOf(ExcerptProposal(start, start + count, quote)))
    }
}
