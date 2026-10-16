package com.t1doo.aiyou.model

import java.time.ZoneId
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneOffset

/** A confirmed user-authored task. No inferred deadline, proposal, or system reminder. */
enum class ManualTaskStatus { OPEN, COMPLETED, CANCELLED }

data class ManualTaskSnapshot(
    val id: String, val version: Long, val title: String, val status: ManualTaskStatus,
    val createdAtMillis: Long, val creationTimeZone: String, val updatedAtMillis: Long,
    val schedule: ManualTaskSchedule? = null,
    val sourceReceipt: SourceTaskReceipt? = null,
    val reminder: TaskReminderSnapshot? = null,
) {
    init {
        ManualTags.identifier(id)
        require(version > 0 && title == ManualTasks.title(title))
        require(createdAtMillis >= 0 && updatedAtMillis >= createdAtMillis)
        ManualTasks.timeZone(creationTimeZone)
        reminder?.let { TaskReminders.validate(id, it) }
    }
    override fun toString() = "ManualTaskSnapshot(version=$version, status=$status, redacted)"
}

class ManualTaskCreate(val id: String, title: String, val createdAtMillis: Long,
    val creationTimeZone: String, val mutationId: String, val schedule: ManualTaskSchedule? = null) {
    val title = ManualTasks.title(title)
    init { ManualTags.identifier(id); ManualTags.identifier(mutationId); require(createdAtMillis >= 0); ManualTasks.timeZone(creationTimeZone) }
    override fun toString() = "ManualTaskCreate(redacted)"
}

/** Explicit correction of one confirmed open task; no date or reminder intent is implied. */
class ManualTaskRename(val id: String, val expectedVersion: Long, title: String,
    val requestedAtMillis: Long, val mutationId: String) {
    val title = ManualTasks.title(title)
    init {
        ManualTags.identifier(id); ManualTags.identifier(mutationId)
        require(expectedVersion in 1 until Long.MAX_VALUE && requestedAtMillis >= 0)
    }
    override fun toString() = "ManualTaskRename(redacted)"
}

enum class ManualTaskDateOperation { SET, POSTPONE, CLEAR }
enum class ManualTaskOccurrence { FIRST, SECOND }
enum class ManualTaskDateError { DATE, TIME_WITHOUT_DATE, TIME, ZONE, GAP, OVERLAP, OFFSET, NO_DATE, NOT_LATER, PRECISION }
class ManualTaskDateInvalid(val reason: ManualTaskDateError) : IllegalArgumentException("Invalid manual task date: " + reason.name)

/** A date has no implicit time. A timed value keeps the user's explicit valid offset. */
data class ManualTaskSchedule(val date: LocalDate, val time: LocalTime?, val timeZone: String, val offsetSeconds: Int? = null) {
    init {
        require(date.year in 1..9999 && timeZone.length <= 128 && timeZone in ZoneId.getAvailableZoneIds())
        if (time == null) require(offsetSeconds == null)
        else {
            require(time.second == 0 && time.nano == 0 && offsetSeconds != null)
            require(ZoneId.of(timeZone).rules.getValidOffsets(date.atTime(time)).any { it.totalSeconds == offsetSeconds })
        }
    }
    fun instant(): Instant? = time?.let { date.atTime(it).toInstant(ZoneOffset.ofTotalSeconds(checkNotNull(offsetSeconds))) }
    override fun toString() = "ManualTaskSchedule(redacted)"
}

/** Private explicit input; durable proposal drafts encrypt it, ordinary forms keep it in memory. Never an Android Bundle. */
data class ManualTaskDateInput(val date: String = "", val time: String = "", val timeZone: String = "UTC",
    val occurrence: ManualTaskOccurrence? = null) {
    init { require(date.length <= 10 && time.length <= 5 && timeZone.length <= 128) }
    fun parse(): ManualTaskSchedule? {
        if (date.isEmpty()) {
            if (time.isNotEmpty()) throw ManualTaskDateInvalid(ManualTaskDateError.TIME_WITHOUT_DATE)
            return null
        }
        val day = try {
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(date))
            LocalDate.parse(date).also { require(it.year in 1..9999) }
        } catch (_: Exception) { throw ManualTaskDateInvalid(ManualTaskDateError.DATE) }
        val zone = try {
            require(timeZone.length <= 128 && timeZone in ZoneId.getAvailableZoneIds()); ZoneId.of(timeZone)
        } catch (_: Exception) { throw ManualTaskDateInvalid(ManualTaskDateError.ZONE) }
        if (time.isEmpty()) return ManualTaskSchedule(day, null, timeZone)
        val clock = try {
            require(Regex("[0-9]{2}:[0-9]{2}").matches(time)); LocalTime.parse(time)
        } catch (_: Exception) { throw ManualTaskDateInvalid(ManualTaskDateError.TIME) }
        val offsets = zone.rules.getValidOffsets(day.atTime(clock))
        if (offsets.isEmpty()) throw ManualTaskDateInvalid(ManualTaskDateError.GAP)
        val offset = if (offsets.size == 1) offsets.single() else when (occurrence) {
            ManualTaskOccurrence.FIRST -> offsets.first()
            ManualTaskOccurrence.SECOND -> offsets.last()
            null -> throw ManualTaskDateInvalid(ManualTaskDateError.OVERLAP)
        }
        return ManualTaskSchedule(day, clock, timeZone, offset.totalSeconds)
    }
    /** Offset options are presentation metadata, not an automatic ambiguity decision. */
    fun overlapOffsets(): List<String> = try {
        val offsets = ZoneId.of(timeZone).rules.getValidOffsets(LocalDate.parse(date).atTime(LocalTime.parse(time)))
        if (offsets.size == 2) offsets.map { it.id } else emptyList()
    } catch (_: Exception) { emptyList() }
    override fun toString() = "ManualTaskDateInput(redacted)"
    companion object {
        fun from(value: ManualTaskSchedule?, zone: String) = if (value == null) ManualTaskDateInput(timeZone = zone) else {
            val offsets = value.time?.let { ZoneId.of(value.timeZone).rules.getValidOffsets(value.date.atTime(it)) }.orEmpty()
            ManualTaskDateInput(value.date.toString(), value.time?.toString().orEmpty(), value.timeZone,
                if (offsets.size != 2) null else if (offsets.first().totalSeconds == value.offsetSeconds) ManualTaskOccurrence.FIRST else ManualTaskOccurrence.SECOND)
        }
    }
}

class ManualTaskScheduleMutation(val id: String, val expectedVersion: Long, val operation: ManualTaskDateOperation,
    val schedule: ManualTaskSchedule?, val requestedAtMillis: Long, val mutationId: String) {
    init {
        ManualTags.identifier(id); ManualTags.identifier(mutationId)
        require(expectedVersion in 1 until Long.MAX_VALUE && requestedAtMillis >= 0)
        require((operation == ManualTaskDateOperation.CLEAR) == (schedule == null))
    }
    override fun toString() = "ManualTaskScheduleMutation(redacted)"
}

enum class ManualTaskGroup { UNDATED, PAST, TODAY, UPCOMING, COMPLETED, CANCELLED }
object ManualTaskDates {
    fun group(task: ManualTaskSnapshot, now: Instant): ManualTaskGroup {
        if (task.status == ManualTaskStatus.COMPLETED) return ManualTaskGroup.COMPLETED
        if (task.status == ManualTaskStatus.CANCELLED) return ManualTaskGroup.CANCELLED
        val due = task.schedule ?: return ManualTaskGroup.UNDATED
        val today = now.atZone(ZoneId.of(due.timeZone)).toLocalDate()
        if (due.instant()?.isBefore(now) == true || due.time == null && due.date < today) return ManualTaskGroup.PAST
        return if (due.date == today) ManualTaskGroup.TODAY else ManualTaskGroup.UPCOMING
    }
    fun validate(operation: ManualTaskDateOperation, old: ManualTaskSchedule?, desired: ManualTaskSchedule?) {
        if (operation == ManualTaskDateOperation.CLEAR && old == null) throw ManualTaskDateInvalid(ManualTaskDateError.NO_DATE)
        if (operation != ManualTaskDateOperation.POSTPONE) return
        if (old == null || desired == null) throw ManualTaskDateInvalid(ManualTaskDateError.NO_DATE)
        if (old.timeZone != desired.timeZone || (old.time == null) != (desired.time == null))
            throw ManualTaskDateInvalid(ManualTaskDateError.PRECISION)
        val later = if (old.time == null) desired.date > old.date else checkNotNull(desired.instant()) > checkNotNull(old.instant())
        if (!later) throw ManualTaskDateInvalid(ManualTaskDateError.NOT_LATER)
    }
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
