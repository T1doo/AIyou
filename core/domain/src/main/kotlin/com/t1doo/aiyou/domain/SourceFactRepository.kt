package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

data class FactCursor(val createdAtMillis: Long, val id: String) {
    init { require(createdAtMillis >= 0); FactValues.uuid(id) }
    override fun toString() = "FactCursor(redacted)"
}
interface FactPage {
    val section: FactReview
    /** Null for legacy browsing; otherwise the exact literal keyword, including empty. */
    val keyword: String? get() = null
    val cursor: FactCursor?
    val next: FactCursor?
    val items: List<SourceFactSnapshot>
}
interface FactSourcePage {
    val preview: SourceMemoryPreview
    val items: List<SourceFactSnapshot>
    val topics: List<SourceFactSnapshot>
}
interface SourceFactRepository {
    fun preview(source: SourceVersion): SourceMemoryPreview
    fun facts(source: SourceVersion): List<SourceFactSnapshot>
    fun captureSource(source: SourceVersion): FactSourcePage
    fun resolveSource(page: FactSourcePage, publish: () -> Unit): Boolean
    fun acceptedForTopic(expected: SourceFactSnapshot): SourceFactSnapshot?
    fun resolveEndpoint(expected: SourceFactSnapshot, endpoint: FactEndpoint, publish: (SourceFactSnapshot?) -> Unit): Boolean
    fun create(request: FactCreate): SourceFactSnapshot
    fun mutate(request: FactMutation): SourceFactSnapshot
    fun capture(section: FactReview, cursor: FactCursor? = null): FactPage
    fun searchAccepted(keyword: String, cursor: FactCursor? = null): FactPage
    /** Reauthenticate the same bounded page before publishing or opening a source. */
    fun resolve(page: FactPage, item: SourceFactSnapshot? = null, publish: () -> Unit): Boolean
}
class FactConflict : IllegalStateException("Fact or source changed")
class FactUnavailable : IllegalStateException("Fact storage unavailable")
class FactLimit : IllegalStateException("Fact capacity exceeded")

/** No REOPEN transition. CORRECT creates a distinct proposed content version while preserving
 * the old decision; rejected/revoked/superseded contents never regain acceptance implicitly. */
object FactRules {
    fun decision(previous: FactReview, action: FactAction): FactReview = when(action) {
        FactAction.ACCEPT -> { require(previous == FactReview.PROPOSED); FactReview.ACCEPTED }
        FactAction.REJECT -> { require(previous == FactReview.PROPOSED); FactReview.REJECTED }
        FactAction.REVOKE -> { require(previous == FactReview.ACCEPTED); FactReview.REVOKED }
        FactAction.CORRECT -> { require(previous != FactReview.SUPERSEDED); FactReview.PROPOSED }
    }
    fun validate(content: FactContent, preview: SourceMemoryPreview) {
        require(content.reference == SourceMemoryRules.reference(preview, content.reference.quote))
    }
}

/** Bounded, literal input only; no tokenization, inference or persistent query index. */
object FactKeywords {
    fun validate(value: String) {
        require(value.length<=128 && value.codePointCount(0,value.length)<=64)
        var i=0
        while(i<value.length) {
            val c=value[i]
            require(!Character.isISOControl(c) && !Character.isLowSurrogate(c))
            if(Character.isHighSurrogate(c)) { require(i+1<value.length && Character.isLowSurrogate(value[i+1]));i++ }
            i++
        }
    }
}
