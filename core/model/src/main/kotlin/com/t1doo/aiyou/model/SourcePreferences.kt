package com.t1doo.aiyou.model

import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.UUID

/** A user's explicit classification, never a machine conclusion or a grant to send anything. */
enum class PreferenceReview { PROPOSED, ACCEPTED, REJECTED, SUPERSEDED, REVOKED }
enum class PreferenceAction { ACCEPT, REJECT, REVOKE, CORRECT }
enum class PreferenceAvailability { AVAILABLE, INVALIDATED }

/** Null bounds mean unknown, not infinity. Capture/review time never fills them in. */
data class PreferenceInterval(val fromDate: String? = null, val untilExclusive: String? = null, val zone: String? = null) {
    init {
        if (fromDate == null && untilExclusive == null) require(zone == null)
        else {
            require(zone != null && zone.length <= 128 && (zone.contains('/') || zone == "UTC") && zone in ZoneId.getAvailableZoneIds())
            fromDate?.let { require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(it) && LocalDate.parse(it).year in 1..9999 && LocalDate.parse(it).toString() == it) }
            untilExclusive?.let { require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(it) && LocalDate.parse(it).year in 1..9999 && LocalDate.parse(it).toString() == it) }
            if (fromDate != null && untilExclusive != null) require(LocalDate.parse(fromDate) < LocalDate.parse(untilExclusive))
        }
    }
    override fun toString() = "PreferenceInterval(redacted)"
}

data class PreferenceContent(val reference: OriginalTextReference, val interval: PreferenceInterval = PreferenceInterval()) {
    init { require(reference.quote.isNotBlank() && LocalCandidates.validUnicode(reference.quote) && reference.quote.codePointCount(0, reference.quote.length) <= 320) }
    override fun toString() = "PreferenceContent(redacted)"
}
data class PreferenceContentVersion(val revision: Int, val content: PreferenceContent, val createdAtMillis: Long) {
    init { require(revision in 1..8 && createdAtMillis >= 0) }
    override fun toString() = "PreferenceContentVersion(revision=$revision, redacted)"
}
data class PreferenceEndpoint(val id: String, val contentRevision: Int, val operationId: String, val digest: String) {
    init { PreferenceValues.uuid(id); PreferenceValues.uuid(operationId); require(contentRevision in 1..8 && digest.matches(Regex("[0-9a-f]{64}"))) }
    override fun toString() = "PreferenceEndpoint(redacted)"
}
/** Each version has independent incoming/outgoing history; neither is the row's last-operation receipt. */
data class PreferenceCheckpoint(val contentRevision: Int, val review: PreferenceReview,
    val priorReview: PreferenceReview?, val reviewedAtMillis: Long,
    val predecessor: PreferenceEndpoint? = null, val successor: PreferenceEndpoint? = null) {
    init {
        require(contentRevision in 1..8 && reviewedAtMillis >= 0)
        require((review == PreferenceReview.SUPERSEDED) == (priorReview != null))
        require(priorReview != PreferenceReview.SUPERSEDED)
        require(successor == null || review == PreferenceReview.SUPERSEDED && priorReview == PreferenceReview.ACCEPTED)
        require(predecessor == null || review in setOf(PreferenceReview.ACCEPTED, PreferenceReview.REVOKED) || priorReview in setOf(PreferenceReview.ACCEPTED, PreferenceReview.REVOKED))
    }
    override fun toString() = "PreferenceCheckpoint(review=$review, redacted)"
}

class SourcePreferenceSnapshot(val id: String, val source: SourceVersion, val sourceHash: String,
    val generation: Long, val rowVersion: Long, val availability: PreferenceAvailability, val createdAtMillis: Long,
    val capturedAtMillis: Long?, val captureTimeZone: String?, val policy: ProcessingPolicy?,
    val topicId: String?, val topicLabel: String?, contents: List<PreferenceContentVersion>, reviews: List<PreferenceCheckpoint>) {
    val contents: List<PreferenceContentVersion> = Collections.unmodifiableList(ArrayList(contents))
    val reviews: List<PreferenceCheckpoint> = Collections.unmodifiableList(ArrayList(reviews))
    val current get() = contents.lastOrNull()
    val review get() = reviews.lastOrNull()
    fun same(other: SourcePreferenceSnapshot): Boolean = id == other.id && source == other.source && sourceHash == other.sourceHash &&
        generation == other.generation && rowVersion == other.rowVersion && availability == other.availability && createdAtMillis == other.createdAtMillis &&
        capturedAtMillis == other.capturedAtMillis && captureTimeZone == other.captureTimeZone && policy == other.policy &&
        topicId == other.topicId && topicLabel == other.topicLabel && contents == other.contents && reviews == other.reviews
    override fun toString() = "SourcePreferenceSnapshot(version=$rowVersion, state=$availability, redacted)"
}

class PreferenceCreate(val id: String, val preview: SourceMemoryPreview, val topicId: String, val topicLabel: String,
    val content: PreferenceContent, val explicitOrdinarySelfReport: Boolean, val mutationId: String, val requestedAtMillis: Long,
    val topicExpected: SourcePreferenceSnapshot? = null) {
    init {
        PreferenceValues.uuid(id); PreferenceValues.uuid(topicId); PreferenceValues.uuid(mutationId); PreferenceValues.label(topicLabel)
        require(explicitOrdinarySelfReport && requestedAtMillis >= 0 && content.reference.source == preview.source)
        require(topicExpected == null || topicExpected.topicId == topicId && topicExpected.topicLabel == topicLabel)
    }
    override fun toString() = "PreferenceCreate(redacted)"
}
class PreferenceMutation(val expected: SourcePreferenceSnapshot, val action: PreferenceAction, val content: PreferenceContent?,
    val replacing: SourcePreferenceSnapshot?, val mutationId: String, val requestedAtMillis: Long,
    val explicitOrdinarySelfReport: Boolean = false) {
    init {
        PreferenceValues.uuid(mutationId); require(requestedAtMillis >= 0 && expected.availability == PreferenceAvailability.AVAILABLE)
        require((action == PreferenceAction.CORRECT) == (content != null))
        require(replacing == null || action == PreferenceAction.ACCEPT && replacing.id != expected.id)
        require(action != PreferenceAction.CORRECT || explicitOrdinarySelfReport)
    }
    override fun toString() = "PreferenceMutation(action=$action, redacted)"
}
object PreferenceValues {
    fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
    fun label(value: String) { require(value.isNotBlank() && value == value.trim() && LocalCandidates.validUnicode(value) &&
        value.codePointCount(0, value.length) <= 40 && value.codePoints().noneMatch(Character::isISOControl)) }
}
