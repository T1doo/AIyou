package com.t1doo.aiyou.model

import java.util.Collections

/** User classifications of a source fragment; acceptance is not verification of a fact. */
enum class SourceMemoryKind { EVENT, OCCASION_EMOTION }
enum class SourceMemoryAssertion { SELF_REPORT, CONTEXT, UNCERTAIN }
enum class SourceMemoryReview { PROPOSED, ACCEPTED, REJECTED, SUPERSEDED, REVOKED }
enum class SourceMemoryAction { ACCEPT, REJECT, REVOKE, REOPEN, CORRECT }
enum class SourceMemoryState { ACTIVE, INVALIDATED }

data class SourceMemoryContent(val reference: OriginalTextReference, val kind: SourceMemoryKind,
    val assertion: SourceMemoryAssertion, val note: String) {
    init {
        require(reference.quote.isNotBlank() && reference.quote.codePointCount(0, reference.quote.length) <= 320)
        require(LocalCandidates.validUnicode(note) && note.codePointCount(0, note.length) <= 200)
        require(note.codePoints().noneMatch { Character.isISOControl(it) && it != 10 && it != 9 })
    }
    override fun toString() = "SourceMemoryContent(redacted)"
}

class SourceMemoryPreview(val source: SourceVersion, val sourceHash: String, val generation: Long,
    val original: String, val capturedAtMillis: Long, val captureTimeZone: String,
    val policy: ProcessingPolicy, val epoch: Long) {
    init {
        ManualTags.identifier(source.sourceId)
        require(source.revision in 1..Int.MAX_VALUE.toLong() && sourceHash == OriginalExports.hash(original))
        require(original.isNotBlank() && original.length <= 100_000 && LocalCandidates.validUnicode(original))
        require(generation >= 0 && capturedAtMillis >= 0 && epoch >= 0 && policy != ProcessingPolicy.AI_NONE)
    }
    override fun toString() = "SourceMemoryPreview(redacted)"
}

data class SourceMemoryVersion(val revision: Int, val content: SourceMemoryContent,
    val review: SourceMemoryReview, val priorReview: SourceMemoryReview?, val supersedesRevision: Int?,
    val createdAtMillis: Long, val reviewedAtMillis: Long) {
    init {
        require(revision in 1..8 && createdAtMillis >= 0 && reviewedAtMillis >= 0)
        require(supersedesRevision == if (revision == 1) null else revision - 1)
        require((review == SourceMemoryReview.SUPERSEDED) == (priorReview != null))
        require(priorReview != SourceMemoryReview.SUPERSEDED)
    }
    override fun toString() = "SourceMemoryVersion(review=" + review + ", redacted)"
}

class SourceMemorySnapshot(val id: String, val source: SourceVersion, val sourceHash: String,
    val generation: Long, val version: Long, val state: SourceMemoryState, val createdAtMillis: Long,
    val capturedAtMillis: Long?, val captureTimeZone: String?, versions: List<SourceMemoryVersion>) {
    val versions: List<SourceMemoryVersion> = Collections.unmodifiableList(ArrayList(versions))
    val current: SourceMemoryVersion? get() = versions.lastOrNull()
    override fun toString() = "SourceMemorySnapshot(state=" + state + ", version=" + version + ", redacted)"
}

class SourceMemoryCreate(val id: String, val preview: SourceMemoryPreview, val content: SourceMemoryContent,
    val mutationId: String, val requestedAtMillis: Long) {
    init {
        ManualTags.identifier(id); ManualTags.identifier(mutationId)
        require(requestedAtMillis >= 0 && content.reference.source == preview.source)
    }
    override fun toString() = "SourceMemoryCreate(redacted)"
}

class SourceMemoryMutation(val id: String, val source: SourceVersion, val sourceHash: String,
    val generation: Long, val expectedVersion: Long, val expectedContentRevision: Int, val previousReview: SourceMemoryReview,
    val action: SourceMemoryAction, val content: SourceMemoryContent?, val mutationId: String,
    val requestedAtMillis: Long) {
    init {
        ManualTags.identifier(id); ManualTags.identifier(mutationId)
        require(source.revision in 1..Int.MAX_VALUE.toLong() && sourceHash.matches(Regex("[0-9a-f]{64}")))
        require(generation >= 0 && expectedVersion in 1 until Long.MAX_VALUE && requestedAtMillis >= 0)
        require(expectedContentRevision in 1..8)
        require(previousReview != SourceMemoryReview.SUPERSEDED)
        require((action == SourceMemoryAction.CORRECT) == (content != null))
        require(content == null || content.reference.source == source)
    }
    override fun toString() = "SourceMemoryMutation(action=" + action + ", redacted)"
}
