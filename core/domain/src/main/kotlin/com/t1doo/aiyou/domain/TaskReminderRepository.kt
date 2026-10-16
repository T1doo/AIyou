package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.util.concurrent.CancellationException

/** Encrypted task/outbox mutations and trigger acceptance are atomic in the owning repository. */
interface TaskReminderRepository {
    fun changeReminder(request: TaskReminderMutation): ManualTaskSnapshot
    fun reminderCommands(): List<TaskReminderCommand>
    fun isReminderCommandCurrent(command: TaskReminderCommand): Boolean
    fun acknowledgeReminder(command: TaskReminderCommand, result: TaskReminderPhase, atMillis: Long): Boolean
    fun acceptReminderTrigger(token: TaskReminderToken, nowMillis: Long, availability: TaskReminderAvailability): Boolean
}

/** Schedule and cancel are idempotent for the exact token. Never cancel every version of a task. */
interface TaskReminderDriver {
    fun availability(): TaskReminderAvailability
    fun schedule(command: TaskReminderCommand): TaskReminderPhase
    fun cancel(command: TaskReminderCommand): TaskReminderPhase
}

/** A bounded, explicit foreground drain. No implicit permission request, background worker, or notification. */
class TaskReminderDispatcher(private val repository: TaskReminderRepository, private val driver: TaskReminderDriver,
    private val clock: () -> Long) {
    /** Returns acknowledged commands, including obsolete schedules retired without touching the driver. */
    fun drain(limit: Int = 16): Int {
        require(limit in 1..TaskReminders.MAX_COMMANDS) { "Invalid reminder drain limit" }
        var acknowledged = 0
        // Persistent failed cancellations must not hide the capability result of a new confirmation.
        // Cancellation is scoped to its old token, so ordering cannot revoke a newer schedule.
        for (command in repository.reminderCommands().sortedBy { if (it.kind == TaskReminderCommandKind.SCHEDULE) 0 else 1 }.take(limit)) {
            try {
                if (!repository.isReminderCommandCurrent(command)) {
                    if (command.kind == TaskReminderCommandKind.SCHEDULE &&
                        repository.acknowledgeReminder(command, TaskReminderPhase.REVOKED, clock())) acknowledged++
                    continue
                }
                val result = if (command.kind == TaskReminderCommandKind.CANCEL) driver.cancel(command)
                    else driver.availability().blocked(command.mode) ?: driver.schedule(command)
                val acceptable = if (command.kind == TaskReminderCommandKind.CANCEL) result == TaskReminderPhase.REVOKED
                    else result in scheduleResults
                if (acceptable && repository.acknowledgeReminder(command, result, clock())) acknowledged++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A call may already have reached the platform. Preserve the exact command for an idempotent retry.
            }
        }
        return acknowledged
    }
    private companion object {
        val scheduleResults = setOf(TaskReminderPhase.SCHEDULED, TaskReminderPhase.PLATFORM_UNAVAILABLE,
            TaskReminderPhase.NOTIFICATION_REQUIRED, TaskReminderPhase.NOTIFICATION_DENIED, TaskReminderPhase.CHANNEL_DISABLED,
            TaskReminderPhase.EXACT_REQUIRED, TaskReminderPhase.EXACT_DENIED, TaskReminderPhase.FAILED)
    }
}
