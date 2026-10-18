package com.t1doo.aiyou.model

import java.time.ZoneId
import java.util.Collections

/** No remote mode exists yet: local source review is not multi-source sending authority. */
enum class SelectedQuestionMode { LOCAL_PROTOCOL_FIXTURE }

data class SelectedQuestionTarget(val protocol: ConnectionProtocol, val apiRoot: String,
    val modelId: String, val version: Long = 1) {
    init { require(version > 0 && apiRoot.length in 1..4096 && modelId.length in 1..256) }
    override fun toString() = "SelectedQuestionTarget(redacted, version=$version)"
}

data class SelectedQuestionSource(val reference: OriginalTextReference, val capturedAtMillis: Long,
    val captureTimeZone: String, val policy: ProcessingPolicy) {
    override fun toString() = "SelectedQuestionSource(redacted)"
}

/** Exact reviewed excerpts only; no full originals, attachments, credentials or consent grant. */
class SelectedQuestionRequest(val id: String, val selectionId: String, val question: String,
    val target: SelectedQuestionTarget, val askedAtMillis: Long, val questionTimeZone: String,
    sources: List<SelectedQuestionSource>) {
    val sources: List<SelectedQuestionSource> = Collections.unmodifiableList(ArrayList(sources))
    init {
        require(id.length in 1..64 && selectionId.length in 1..64)
        require(question.isNotBlank() && question.length <= 1024 && LocalCandidates.validUnicode(question) &&
            question.codePointCount(0, question.length) <= 512 && question.toByteArray(Charsets.UTF_8).size <= 2048)
        require(questionTimeZone.length <= 256); ZoneId.of(questionTimeZone)
        require(this.sources.size in 1..5 && this.sources.map { it.reference.source.sourceId }.toSet().size == this.sources.size)
        this.sources.forEach {
            val r = it.reference
            require(it.policy != ProcessingPolicy.AI_NONE && it.captureTimeZone.length <= 256)
            ZoneId.of(it.captureTimeZone)
            require(r.start >= 0 && r.end > r.start && r.end.toLong() - r.start <= 240 &&
                LocalCandidates.validUnicode(r.quote) && r.quote.codePointCount(0, r.quote.length) == r.end - r.start)
        }
        require(this.sources.sumOf { it.reference.end - it.reference.start } <= 1200 &&
            this.sources.sumOf { it.reference.quote.toByteArray(Charsets.UTF_8).size } <= 4800)
    }
    override fun toString() = "SelectedQuestionRequest(redacted)"
}

class SelectedQuestionParagraph(val text: String, references: List<OriginalTextReference>) {
    val references: List<OriginalTextReference> = Collections.unmodifiableList(ArrayList(references))
    init { require(text.length <= 2400 && references.size <= 5) }
    override fun toString() = "SelectedQuestionParagraph(redacted)"
}

/** References can be checked mechanically; semantic support still requires review. Never a fact. */
class SelectedQuestionReply(val requestId: String, val selectionId: String, val noEvidence: Boolean,
    paragraphs: List<SelectedQuestionParagraph>) {
    val paragraphs: List<SelectedQuestionParagraph> = Collections.unmodifiableList(ArrayList(paragraphs))
    init { require(requestId.length <= 64 && selectionId.length <= 64 && paragraphs.size <= 5) }
    override fun toString() = "SelectedQuestionReply(redacted)"
}
