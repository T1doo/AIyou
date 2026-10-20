package com.t1doo.aiyou.model

import java.security.MessageDigest
import java.util.Collections

enum class TaskReminderMode { EXACT, APPROXIMATE }
enum class TaskReminderPhase {
    PENDING, SCHEDULED, REVOKED, TRIGGER_ACCEPTED, PLATFORM_UNAVAILABLE,
    NOTIFICATION_REQUIRED, NOTIFICATION_DENIED, CHANNEL_DISABLED, EXACT_REQUIRED, EXACT_DENIED, FAILED,
    TRIGGER_BLOCKED, DELIVERY_BLOCKED, POSTING, NOTIFICATION_POSTED, POST_FAILED, POST_UNKNOWN,
    TRIGGER_UNDELIVERED, TIME_PASSED,
}
enum class TaskReminderCommandKind { SCHEDULE, CANCEL, DELIVER }
enum class TaskReminderPermission { NOT_REQUESTED, DENIED, GRANTED }

/** Contains identity only, never the task title or original record. */
data class TaskReminderToken(val taskId: String, val reminderId: String, val scheduleVersion: Long) {
    init {
        ManualTags.identifier(taskId)
        require(reminderId.matches(Regex("[a-f0-9]{64}")) && scheduleVersion > 0) { "Invalid reminder token" }
    }
    override fun toString() = "TaskReminderToken(scheduleVersion=$scheduleVersion, redacted)"
}

data class TaskReminderCommand(val token: TaskReminderToken, val kind: TaskReminderCommandKind,
    val schedule: ManualTaskSchedule, val mode: TaskReminderMode) {
    init { require(schedule.instant() != null) { "A reminder needs an explicit time" } }
    override fun toString() = "TaskReminderCommand(kind=$kind, mode=$mode, redacted)"
}

/** An encrypted outbox snapshot. Old cancellations survive a newer confirmation. */
class TaskReminderSnapshot(val id: String, val scheduleVersion: Long, val mode: TaskReminderMode,
    val schedule: ManualTaskSchedule, val phase: TaskReminderPhase, commands: List<TaskReminderCommand>) {
    val commands: List<TaskReminderCommand> = Collections.unmodifiableList(commands.toList())
    init {
        require(id.matches(Regex("[a-f0-9]{64}")) && scheduleVersion > 0) { "Invalid reminder identity" }
        require(schedule.instant() != null) { "A reminder needs an explicit time" }
        if (commands.size > MAX_COMMANDS) throw TaskReminderLimit()
        require(commands.map { it.token }.distinct().size == commands.size) { "Duplicate reminder command" }
        require(commands.all { it.token.reminderId == id && it.token.scheduleVersion <= scheduleVersion }) { "Invalid reminder command" }
        val schedules = commands.filter { it.kind == TaskReminderCommandKind.SCHEDULE }
        if (phase == TaskReminderPhase.PENDING) {
            require(schedules.size == 1 && schedules.single().let {
                it.token.scheduleVersion == scheduleVersion && it.schedule == schedule && it.mode == mode
            }) { "Invalid pending reminder command" }
        } else require(schedules.isEmpty()) { "Only a pending reminder can schedule" }
        val deliveries = commands.filter { it.kind == TaskReminderCommandKind.DELIVER }
        require(deliveries.isEmpty() || phase == TaskReminderPhase.TRIGGER_ACCEPTED && deliveries.size == 1 &&
            deliveries.single().let { it.token.scheduleVersion == scheduleVersion && it.schedule == schedule && it.mode == mode })
        require(commands.filter { it.kind == TaskReminderCommandKind.CANCEL }.all {
            it.token.scheduleVersion < scheduleVersion
        }) { "Only a previous reminder version can be cancelled" }
        // Reserve the current version's cancellation slot, so completing a task can always revoke it.
        if (phase != TaskReminderPhase.PENDING && phase != TaskReminderPhase.REVOKED && deliveries.isEmpty())
            require(commands.size < MAX_COMMANDS) { "Missing reminder cancellation capacity" }
    }
    fun copy(id: String = this.id, scheduleVersion: Long = this.scheduleVersion, mode: TaskReminderMode = this.mode,
        schedule: ManualTaskSchedule = this.schedule, phase: TaskReminderPhase = this.phase,
        commands: List<TaskReminderCommand> = this.commands) = TaskReminderSnapshot(id, scheduleVersion, mode, schedule, phase, commands)
    override fun equals(other: Any?) = other is TaskReminderSnapshot && id == other.id && scheduleVersion == other.scheduleVersion &&
        mode == other.mode && schedule == other.schedule && phase == other.phase && commands == other.commands
    override fun hashCode(): Int = listOf(id, scheduleVersion, mode, schedule, phase, commands).hashCode()
    override fun toString() = "TaskReminderSnapshot(scheduleVersion=$scheduleVersion, mode=$mode, phase=$phase, redacted)"
    companion object { const val MAX_COMMANDS = 32 }
}

class TaskReminderLimit : IllegalStateException("Too many pending reminder cancellations; reconnect reminder service and retry")

data class TaskReminderAvailability(val platformConnected: Boolean = false,
    val notifications: TaskReminderPermission = TaskReminderPermission.NOT_REQUESTED,
    val exactAlarms: TaskReminderPermission = TaskReminderPermission.NOT_REQUESTED,
    val channelEnabled: Boolean = true) {
    /** Permission denial never silently changes an exact request into an approximate one. */
    fun blocked(mode: TaskReminderMode): TaskReminderPhase? = when {
        !platformConnected -> TaskReminderPhase.PLATFORM_UNAVAILABLE
        notifications == TaskReminderPermission.NOT_REQUESTED -> TaskReminderPhase.NOTIFICATION_REQUIRED
        notifications == TaskReminderPermission.DENIED -> TaskReminderPhase.NOTIFICATION_DENIED
        !channelEnabled -> TaskReminderPhase.CHANNEL_DISABLED
        mode == TaskReminderMode.EXACT && exactAlarms == TaskReminderPermission.NOT_REQUESTED -> TaskReminderPhase.EXACT_REQUIRED
        mode == TaskReminderMode.EXACT && exactAlarms == TaskReminderPermission.DENIED -> TaskReminderPhase.EXACT_DENIED
        else -> null
    }
}

/** A user-confirmed action against the complete task version; null mode explicitly revokes. */
class TaskReminderMutation(val task: ManualTaskSnapshot, val mode: TaskReminderMode?, val requestedAtMillis: Long,
    val mutationId: String) {
    init {
        ManualTags.identifier(mutationId)
        require(task.version in 1 until Long.MAX_VALUE && requestedAtMillis >= task.updatedAtMillis) { "Invalid reminder mutation" }
    }
    override fun toString() = "TaskReminderMutation(redacted)"
}

object TaskReminders {
    const val MAX_COMMANDS = TaskReminderSnapshot.MAX_COMMANDS
    fun id(taskId: String): String {
        ManualTags.identifier(taskId)
        return MessageDigest.getInstance("SHA-256").digest(("AIyou:task-reminder:v1:" + taskId).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun validate(taskId: String, reminder: TaskReminderSnapshot) {
        require(reminder.id == id(taskId) && reminder.commands.all { it.token.taskId == taskId }) { "Reminder belongs to another task" }
    }

    fun confirm(task: ManualTaskSnapshot, mode: TaskReminderMode): TaskReminderSnapshot {
        require(task.status == ManualTaskStatus.OPEN) { "Only an open task can have a reminder" }
        val schedule = requireNotNull(task.schedule) { "A reminder needs a date and explicit time" }
        require(schedule.instant() != null) { "A reminder needs an explicit time" }
        val old = task.reminder
        old?.let { validate(task.id, it) }
        val version = nextVersion(old)
        val token = TaskReminderToken(task.id, id(task.id), version)
        val commands = cancellations(task.id, old) + TaskReminderCommand(token, TaskReminderCommandKind.SCHEDULE, schedule, mode)
        return TaskReminderSnapshot(token.reminderId, version, mode, schedule, TaskReminderPhase.PENDING, commands)
    }

    /** Invalidation never schedules the edited date. A later explicit confirmation is required. */
    fun revoke(task: ManualTaskSnapshot): TaskReminderSnapshot? {
        val old = task.reminder ?: return null
        validate(task.id, old)
        return TaskReminderSnapshot(old.id, nextVersion(old), old.mode, old.schedule, TaskReminderPhase.REVOKED,
            cancellations(task.id, old))
    }

    fun commandCurrent(task: ManualTaskSnapshot?, command: TaskReminderCommand): Boolean {
        val reminder = task?.reminder ?: return false
        if (task.id != command.token.taskId || reminder.id != id(task.id) || command !in reminder.commands) return false
        if (command.kind == TaskReminderCommandKind.CANCEL) return true
        if (command.kind == TaskReminderCommandKind.DELIVER) return task.status == ManualTaskStatus.OPEN &&
            reminder.phase == TaskReminderPhase.TRIGGER_ACCEPTED && command.token.scheduleVersion == reminder.scheduleVersion &&
            command.schedule == reminder.schedule && command.mode == reminder.mode && task.schedule == reminder.schedule
        return task.status == ManualTaskStatus.OPEN && reminder.phase == TaskReminderPhase.PENDING &&
            command.token.scheduleVersion == reminder.scheduleVersion && command.token.reminderId == reminder.id &&
            command.schedule == reminder.schedule && command.mode == reminder.mode && task.schedule == reminder.schedule
    }

    /** Rechecked inside the repository transaction immediately before consuming the one-shot trigger. */
    fun canAccept(task: ManualTaskSnapshot?, token: TaskReminderToken, nowMillis: Long,
        availability: TaskReminderAvailability): Boolean {
        val reminder = task?.reminder ?: return false
        return nowMillis >= 0 && task.id == token.taskId && reminder.id == id(task.id) && reminder.id == token.reminderId &&
            reminder.scheduleVersion == token.scheduleVersion && task.status == ManualTaskStatus.OPEN &&
            reminder.phase == TaskReminderPhase.SCHEDULED && task.schedule == reminder.schedule &&
            requireNotNull(reminder.schedule.instant()).toEpochMilli() <= nowMillis && availability.blocked(reminder.mode) == null
    }

    private fun nextVersion(old: TaskReminderSnapshot?): Long {
        require(old == null || old.scheduleVersion < Long.MAX_VALUE) { "Reminder schedule version exhausted" }
        return (old?.scheduleVersion ?: 0) + 1
    }

    private fun cancellations(taskId: String, old: TaskReminderSnapshot?): List<TaskReminderCommand> {
        if (old == null) return emptyList()
        val commands = old.commands.map { if (it.kind != TaskReminderCommandKind.CANCEL) it.copy(kind = TaskReminderCommandKind.CANCEL) else it }
            .toMutableList()
        if (old.phase != TaskReminderPhase.REVOKED) {
            val token = TaskReminderToken(taskId, old.id, old.scheduleVersion)
            if (commands.none { it.token == token }) commands += TaskReminderCommand(token, TaskReminderCommandKind.CANCEL, old.schedule, old.mode)
        }
        return commands
    }
}
