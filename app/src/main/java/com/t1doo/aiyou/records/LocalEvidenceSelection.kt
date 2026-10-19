package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.LocalCandidates
import com.t1doo.aiyou.model.OriginalTextReference
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.SourceVersion
import java.time.ZoneId
import java.util.Collections
import java.util.UUID

enum class LocalEvidenceSelectionStage { CHOOSING, CHECKING, CONFIRMED, EXPIRED }

/** Memory-only identity. A local review is never a ConsentGrant or remote sending authority. */
class LocalEvidenceConfirmation internal constructor(internal val selectionId: String) {
    override fun toString() = "LocalEvidenceConfirmation(redacted)"
}

class LocalEvidenceSelection internal constructor(
    val stage: LocalEvidenceSelectionStage = LocalEvidenceSelectionStage.CHOOSING,
    cards: List<LocalEvidenceCard> = emptyList(),
    val id: String = UUID.randomUUID().toString(),
    val confirmation: LocalEvidenceConfirmation? = null,
) {
    val cards: List<LocalEvidenceCard> = Collections.unmodifiableList(ArrayList(cards))
    val quotePoints = cards.sumOf { it.reference.end - it.reference.start }
    val quoteBytes = cards.sumOf { it.reference.quote.toByteArray(Charsets.UTF_8).size }
    override fun toString() = "LocalEvidenceSelection(stage=$stage, redacted)"
}

/** Only selected excerpts, never full originals, search hits, consent or connection configuration. */
class LocalEvidenceCitation internal constructor(val reference: OriginalTextReference,
    val capturedAtMillis: Long, val captureTimeZone: String, val policy: ProcessingPolicy) {
    override fun toString() = "LocalEvidenceCitation(redacted)"
}

/** Local inspection data. No adapter from this type to a network request exists. */
class LocalEvidencePacket private constructor(val selectionId: String, val question: String,
    citations: List<LocalEvidenceCitation>, val textPoints: Int, val textBytes: Int) {
    val citations: List<LocalEvidenceCitation> = Collections.unmodifiableList(ArrayList(citations))
    override fun toString() = "LocalEvidencePacket(redacted)"

    companion object {
        internal fun reviewed(value: LocalEvidenceState): LocalEvidencePacket {
            val question = value.question
            require(question.isNotBlank() && question.length <= 1024 && LocalCandidates.validUnicode(question))
            val questionPoints = question.codePointCount(0, question.length)
            val questionBytes = question.toByteArray(Charsets.UTF_8).size
            require(questionPoints <= LocalEvidenceController.MAX_QUESTION_POINTS &&
                questionBytes <= LocalEvidenceController.MAX_QUESTION_BYTES)
            val selected = value.selection.cards
            require(selected.isNotEmpty() && selected.size <= LocalEvidenceController.MAX_SOURCES &&
                selected.map { it.reference.source.sourceId }.toSet().size == selected.size)
            // Canonical candidate order, irrespective of the order of the user's clicks.
            require(value.cards.filter { candidate -> selected.any { it === candidate } } == selected)
            val citations = selected.map { card ->
                require(card.hit.entry.policy != ProcessingPolicy.AI_NONE &&
                    card.reference == localEvidenceReference(card.hit) &&
                    card.capturedAtMillis == card.hit.entry.capturedAtMillis &&
                    card.captureTimeZone == card.hit.entry.captureTimeZone)
                require(card.captureTimeZone.length <= 256)
                ZoneId.of(card.captureTimeZone)
                LocalEvidenceCitation(card.reference, card.capturedAtMillis, card.captureTimeZone, card.hit.entry.policy)
            }
            val quotePoints = citations.sumOf { it.reference.end - it.reference.start }
            val quoteBytes = citations.sumOf { it.reference.quote.toByteArray(Charsets.UTF_8).size }
            require(quotePoints <= LocalEvidenceController.MAX_QUOTE_POINTS &&
                quoteBytes <= LocalEvidenceController.MAX_QUOTE_BYTES)
            val textPoints = questionPoints + quotePoints
            val textBytes = questionBytes + quoteBytes
            require(textPoints <= MAX_TEXT_POINTS && textBytes <= MAX_TEXT_BYTES)
            return LocalEvidencePacket(value.selection.id, question, citations, textPoints, textBytes)
        }
        // Question plus quoted original text; not a token estimate or a wire/heap-size guarantee.
        const val MAX_TEXT_POINTS = LocalEvidenceController.MAX_QUESTION_POINTS + LocalEvidenceController.MAX_QUOTE_POINTS
        const val MAX_TEXT_BYTES = LocalEvidenceController.MAX_QUESTION_BYTES + LocalEvidenceController.MAX_QUOTE_BYTES
    }
}

/** One shared half-open Unicode-code-point contract for candidate display and confirmed inspection. */
internal fun localEvidenceReference(hit: OriginalSearchHit): OriginalTextReference {
    require(hit.originalMatchesQuery) { "Manual tag recall is not an original quotation match" }
    val entry = hit.entry
    val original = entry.originalText
    require(entry.currentRevision > 0 && LocalCandidates.validUnicode(original))
    val count = original.codePointCount(0, original.length)
    require(hit.startCodePoint >= 0 && hit.endCodePoint in (hit.startCodePoint + 1)..count)
    val size = hit.quote.codePointCount(0, hit.quote.length)
    require(size <= 240 && size == hit.endCodePoint - hit.startCodePoint && hit.quote == original.substring(
        original.offsetByCodePoints(0, hit.startCodePoint), original.offsetByCodePoints(0, hit.endCodePoint)))
    return OriginalTextReference(SourceVersion(entry.id, entry.currentRevision.toLong()), hit.startCodePoint, hit.endCodePoint, hit.quote)
}
