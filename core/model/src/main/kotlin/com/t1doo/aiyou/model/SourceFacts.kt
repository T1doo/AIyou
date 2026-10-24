package com.t1doo.aiyou.model

import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.UUID

/** Explicit ordinary non-sensitive original self-report only; never inference, verification or remote authority. */
enum class FactAssertion { SELF_REPORT }
enum class FactReview { PROPOSED, ACCEPTED, REJECTED, SUPERSEDED, REVOKED }
enum class FactAction { ACCEPT, REJECT, REVOKE, CORRECT }
enum class FactAvailability { AVAILABLE, INVALIDATED }

/** Null bounds mean unknown, not infinity. Capture/review time never fills them in. */
data class FactInterval(val fromDate: String? = null, val untilExclusive: String? = null, val zone: String? = null) {
    init {
        if (fromDate == null && untilExclusive == null) require(zone == null)
        else {
            require(zone != null && zone.length <= 128 && (zone.contains('/') || zone == "UTC") && zone in ZoneId.getAvailableZoneIds())
            fromDate?.let { require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(it) && LocalDate.parse(it).year in 1..9999 && LocalDate.parse(it).toString() == it) }
            untilExclusive?.let { require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(it) && LocalDate.parse(it).year in 1..9999 && LocalDate.parse(it).toString() == it) }
            if (fromDate != null && untilExclusive != null) require(LocalDate.parse(fromDate) < LocalDate.parse(untilExclusive))
        }
    }
    override fun toString() = "FactInterval(redacted)"
}

data class FactContent(val reference: OriginalTextReference, val interval: FactInterval = FactInterval()) {
    val assertionKind: FactAssertion get() = FactAssertion.SELF_REPORT
    init { require(reference.quote.isNotBlank() && LocalCandidates.validUnicode(reference.quote) && reference.quote.codePointCount(0, reference.quote.length) <= 320) }
    override fun toString() = "FactContent(redacted)"
}
data class FactContentVersion(val revision: Int, val content: FactContent, val createdAtMillis: Long) {
    init { require(revision in 1..8 && createdAtMillis >= 0) }
    override fun toString() = "FactContentVersion(revision=$revision, redacted)"
}
data class FactEndpoint(val id: String, val contentRevision: Int, val operationId: String, val digest: String) {
    init { FactValues.uuid(id); FactValues.uuid(operationId); require(contentRevision in 1..8 && digest.matches(Regex("[0-9a-f]{64}"))) }
    override fun toString() = "FactEndpoint(redacted)"
}
/** Each version has independent incoming/outgoing history; neither is the row's last-operation receipt. */
data class FactCheckpoint(val contentRevision: Int, val review: FactReview,
    val priorReview: FactReview?, val reviewedAtMillis: Long,
    val predecessor: FactEndpoint? = null, val successor: FactEndpoint? = null) {
    init {
        require(contentRevision in 1..8 && reviewedAtMillis >= 0)
        require((review == FactReview.SUPERSEDED) == (priorReview != null))
        require(priorReview != FactReview.SUPERSEDED)
        require(successor == null || review == FactReview.SUPERSEDED && priorReview == FactReview.ACCEPTED)
        require(predecessor == null || review in setOf(FactReview.ACCEPTED, FactReview.REVOKED) || priorReview in setOf(FactReview.ACCEPTED, FactReview.REVOKED))
    }
    override fun toString() = "FactCheckpoint(review=$review, redacted)"
}

class SourceFactSnapshot(val id: String, val source: SourceVersion, val sourceHash: String,
    val generation: Long, val rowVersion: Long, val availability: FactAvailability, val createdAtMillis: Long,
    val capturedAtMillis: Long?, val captureTimeZone: String?, val policy: ProcessingPolicy?,
    val topicId: String?, val topicLabel: String?, contents: List<FactContentVersion>, reviews: List<FactCheckpoint>) {
    val contents: List<FactContentVersion> = Collections.unmodifiableList(ArrayList(contents))
    val reviews: List<FactCheckpoint> = Collections.unmodifiableList(ArrayList(reviews))
    val current get() = contents.lastOrNull()
    val review get() = reviews.lastOrNull()
    fun same(other: SourceFactSnapshot): Boolean = id == other.id && source == other.source && sourceHash == other.sourceHash &&
        generation == other.generation && rowVersion == other.rowVersion && availability == other.availability && createdAtMillis == other.createdAtMillis &&
        capturedAtMillis == other.capturedAtMillis && captureTimeZone == other.captureTimeZone && policy == other.policy &&
        topicId == other.topicId && topicLabel == other.topicLabel && contents == other.contents && reviews == other.reviews
    override fun toString() = "SourceFactSnapshot(version=$rowVersion, state=$availability, redacted)"
}

class FactCreate(val id: String, val preview: SourceMemoryPreview, val topicId: String, val topicLabel: String,
    val content: FactContent, val explicitOrdinarySelfReport: Boolean, val mutationId: String, val requestedAtMillis: Long,
    val topicExpected: SourceFactSnapshot? = null) {
    init {
        FactValues.uuid(id); FactValues.uuid(topicId); FactValues.uuid(mutationId); FactValues.label(topicLabel)
        require(explicitOrdinarySelfReport && requestedAtMillis >= 0 && content.reference.source == preview.source)
        require(topicExpected == null || topicExpected.topicId == topicId && topicExpected.topicLabel == topicLabel)
    }
    override fun toString() = "FactCreate(redacted)"
}
class FactMutation(val expected: SourceFactSnapshot, val action: FactAction, val content: FactContent?,
    val replacing: SourceFactSnapshot?, val mutationId: String, val requestedAtMillis: Long,
    val explicitOrdinarySelfReport: Boolean = false) {
    init {
        FactValues.uuid(mutationId); require(requestedAtMillis >= 0 && expected.availability == FactAvailability.AVAILABLE)
        require((action == FactAction.CORRECT) == (content != null))
        require(replacing == null || action == FactAction.ACCEPT && replacing.id != expected.id)
        require(action != FactAction.CORRECT || explicitOrdinarySelfReport)
    }
    override fun toString() = "FactMutation(action=$action, redacted)"
}
object FactValues {
    fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
    fun label(value: String) { require(value.isNotBlank() && value == value.trim() && LocalCandidates.validUnicode(value) &&
        value.codePointCount(0, value.length) <= 40 && value.codePoints().noneMatch(Character::isISOControl)) }
}
