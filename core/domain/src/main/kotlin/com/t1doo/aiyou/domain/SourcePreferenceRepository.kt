package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

data class PreferenceCursor(val createdAtMillis: Long, val id: String) {
    init { require(createdAtMillis >= 0); PreferenceValues.uuid(id) }
    override fun toString() = "PreferenceCursor(redacted)"
}
interface PreferencePage {
    val section: PreferenceReview
    /** Null for legacy browsing; otherwise the exact literal keyword, including empty. */
    val keyword: String? get() = null
    val cursor: PreferenceCursor?
    val next: PreferenceCursor?
    val items: List<SourcePreferenceSnapshot>
}
interface PreferenceSourcePage {
    val preview: SourceMemoryPreview
    val items: List<SourcePreferenceSnapshot>
    val topics: List<SourcePreferenceSnapshot>
}
interface SourcePreferenceRepository {
    fun preview(source: SourceVersion): SourceMemoryPreview
    fun preferences(source: SourceVersion): List<SourcePreferenceSnapshot>
    fun captureSource(source: SourceVersion): PreferenceSourcePage
    fun resolveSource(page: PreferenceSourcePage, publish: () -> Unit): Boolean
    fun acceptedForTopic(expected: SourcePreferenceSnapshot): SourcePreferenceSnapshot?
    fun resolveEndpoint(expected: SourcePreferenceSnapshot, endpoint: PreferenceEndpoint, publish: (SourcePreferenceSnapshot?) -> Unit): Boolean
    fun create(request: PreferenceCreate): SourcePreferenceSnapshot
    fun mutate(request: PreferenceMutation): SourcePreferenceSnapshot
    fun capture(section: PreferenceReview, cursor: PreferenceCursor? = null): PreferencePage
    fun searchAccepted(keyword: String, cursor: PreferenceCursor? = null): PreferencePage
    /** Reauthenticate the same bounded page before publishing or opening a source. */
    fun resolve(page: PreferencePage, item: SourcePreferenceSnapshot? = null, publish: () -> Unit): Boolean
}
class PreferenceConflict : IllegalStateException("Preference or source changed")
class PreferenceUnavailable : IllegalStateException("Preference storage unavailable")
class PreferenceLimit : IllegalStateException("Preference capacity exceeded")

object PreferenceRules {
    fun decision(previous: PreferenceReview, action: PreferenceAction): PreferenceReview = when(action) {
        PreferenceAction.ACCEPT -> { require(previous == PreferenceReview.PROPOSED); PreferenceReview.ACCEPTED }
        PreferenceAction.REJECT -> { require(previous == PreferenceReview.PROPOSED); PreferenceReview.REJECTED }
        PreferenceAction.REVOKE -> { require(previous == PreferenceReview.ACCEPTED); PreferenceReview.REVOKED }
        PreferenceAction.CORRECT -> { require(previous != PreferenceReview.SUPERSEDED); PreferenceReview.PROPOSED }
    }
    fun validate(content: PreferenceContent, preview: SourceMemoryPreview) {
        SourceMemoryRules.validate(SourceMemoryContent(content.reference, SourceMemoryKind.EVENT, SourceMemoryAssertion.SELF_REPORT, ""), preview.source, preview.original)
    }
}

/** Bounded, literal input only; no tokenization, inference or persistent query index. */
object PreferenceKeywords {
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
