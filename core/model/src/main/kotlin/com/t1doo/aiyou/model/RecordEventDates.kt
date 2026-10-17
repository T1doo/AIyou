package com.t1doo.aiyou.model

import java.time.LocalDate
import java.time.ZoneId

/** A user supplied calendar day. It never implies an instant, time of day, or inferred date. */
data class RecordEventDate(val localDate: String, val timeZone: String) {
    init {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(localDate)) { "Invalid event date" }
        require(LocalDate.parse(localDate).year in 1..9999) { "Invalid event date" }
        require(timeZone.length <= 128 && (timeZone.contains('/') || timeZone == "UTC") &&
            timeZone in ZoneId.getAvailableZoneIds()) { "An IANA time zone is required" }
    }
    override fun toString() = "RecordEventDate(redacted)"
}

enum class RecordDateBasis { CAPTURED, EVENT }

class RecordEventDateSnapshot(val entryId: String, val version: Long, val date: RecordEventDate?) {
    init {
        RecordFavorites.identifier(entryId)
        require(version >= 0 && (version > 0 || date == null)) { "Invalid event date version" }
    }
    override fun toString() = "RecordEventDateSnapshot(version=$version, redacted)"
}

/** Explicit set/clear intent. Reusing an acknowledged intent never toggles or overwrites a newer choice. */
class RecordEventDateMutation(val entryId: String, val sourceRevision: Int, val sourceHash: String,
    val expectedVersion: Long, val date: RecordEventDate?, val mutationId: String, val sourceGeneration: Long) {
    init {
        RecordFavorites.identifier(entryId); RecordFavorites.identifier(mutationId)
        require(sourceRevision > 0 && expectedVersion in 0 until Long.MAX_VALUE)
        require(sourceGeneration >= 0)
        require(Regex("[0-9a-f]{64}").matches(sourceHash))
    }
    override fun toString() = "RecordEventDateMutation(redacted)"
}
