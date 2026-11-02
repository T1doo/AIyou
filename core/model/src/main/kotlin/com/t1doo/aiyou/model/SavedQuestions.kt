package com.t1doo.aiyou.model

import java.util.UUID

/** User-authored questions have their own lifetime. No source, consent, answer or sending authority. */
object SavedQuestions {
    const val MAX_RETAINED = 128 // Includes deletion tombstones; deletion cannot reset the limit.
    const val MAX_UTF8 = 2048
    fun id(value: String) { require(UUID.fromString(value).toString() == value) }
    fun question(value: String) {
        require(value.isNotBlank() && value.length <= 1024 && LocalCandidates.validUnicode(value) &&
            value.codePointCount(0, value.length) <= 512 && value.toByteArray(Charsets.UTF_8).size <= MAX_UTF8)
    }
}

/** Null means explicitly deleted, never an empty/default question or a model response. */
class SavedQuestion(val id: String, val version: Long, val createdAtMillis: Long,
    val updatedAtMillis: Long, val question: String?) {
    init {
        SavedQuestions.id(id); require(version > 0 && createdAtMillis >= 0 && updatedAtMillis >= createdAtMillis)
        question?.let(SavedQuestions::question); require(question != null || version >= 2)
    }
    override fun toString() = "SavedQuestion(redacted, version=$version)"
}

/** The exact mutation must be retained on an uncertain acknowledgement; a new UUID is a new intent. */
class SaveQuestion(val id: String, val expectedVersion: Long, val mutationId: String,
    val question: String?, val atMillis: Long) {
    init {
        SavedQuestions.id(id); SavedQuestions.id(mutationId)
        require(expectedVersion in 0 until Long.MAX_VALUE && atMillis >= 0)
        question?.let(SavedQuestions::question)
        require(question != null || expectedVersion > 0)
    }
    override fun toString() = "SaveQuestion(redacted)"
}
