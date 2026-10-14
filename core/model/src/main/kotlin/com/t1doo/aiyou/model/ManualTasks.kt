package com.t1doo.aiyou.model

import java.time.ZoneId

/** A confirmed user-authored task. No inferred deadline, proposal, or system reminder. */
enum class ManualTaskStatus { OPEN, COMPLETED, CANCELLED }

data class ManualTaskSnapshot(
    val id: String, val version: Long, val title: String, val status: ManualTaskStatus,
    val createdAtMillis: Long, val creationTimeZone: String, val updatedAtMillis: Long,
) {
    init {
        ManualTags.identifier(id)
        require(version > 0 && title == ManualTasks.title(title))
        require(createdAtMillis >= 0 && updatedAtMillis >= createdAtMillis)
        ManualTasks.timeZone(creationTimeZone)
    }
    override fun toString() = "ManualTaskSnapshot(version=$version, status=$status, redacted)"
}

class ManualTaskCreate(val id: String, title: String, val createdAtMillis: Long,
    val creationTimeZone: String, val mutationId: String) {
    val title = ManualTasks.title(title)
    init { ManualTags.identifier(id); ManualTags.identifier(mutationId); require(createdAtMillis >= 0); ManualTasks.timeZone(creationTimeZone) }
    override fun toString() = "ManualTaskCreate(redacted)"
}

class ManualTaskMutation(val id: String, val expectedVersion: Long, val status: ManualTaskStatus,
    val requestedAtMillis: Long, val mutationId: String) {
    init {
        ManualTags.identifier(id); ManualTags.identifier(mutationId)
        require(expectedVersion in 1 until Long.MAX_VALUE && requestedAtMillis >= 0 && status != ManualTaskStatus.OPEN)
    }
    override fun toString() = "ManualTaskMutation(redacted)"
}

object ManualTasks {
    const val MAX_CODE_POINTS = 240
    const val MAX_RAW_LENGTH = 960
    fun timeZone(value: String) {
        require(value.length <= 128) { "Invalid task timezone" }
        try { ZoneId.of(value) } catch (_: java.time.DateTimeException) { throw IllegalArgumentException("Invalid task timezone") }
    }
    /** Trim only outer whitespace. Preserve literal dates, case, accents and internal spacing. */
    fun title(raw: String): String {
        require(raw.length <= MAX_RAW_LENGTH && raw.none { Character.isISOControl(it) }) { "Invalid task title" }
        // Strict UTF-16 scan: unfinished IME surrogates remain UI input, never durable task titles.
        var at = 0
        while (at < raw.length) {
            val ch = raw[at++]
            if (Character.isHighSurrogate(ch)) {
                require(at < raw.length && Character.isLowSurrogate(raw[at++])) { "Invalid task title" }
            } else require(!Character.isLowSurrogate(ch)) { "Invalid task title" }
        }
        val title = raw.trim { it.isWhitespace() || Character.isSpaceChar(it) }
        require(title.isNotEmpty() && title.codePointCount(0, title.length) <= MAX_CODE_POINTS &&
            title.codePoints().anyMatch { !Character.isWhitespace(it) && !Character.isSpaceChar(it) &&
                Character.getType(it) != Character.FORMAT.toInt() }) { "Invalid task title" }
        return title
    }
}
