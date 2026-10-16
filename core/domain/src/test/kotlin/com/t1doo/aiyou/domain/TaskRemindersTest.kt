package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TaskRemindersTest {
    private val schedule = ManualTaskSchedule(LocalDate.of(2026, 10, 20), LocalTime.of(15, 0), "UTC", 0)
    private val due = checkNotNull(schedule.instant()).toEpochMilli()
    private val available = TaskReminderAvailability(true, TaskReminderPermission.GRANTED, TaskReminderPermission.GRANTED)
    private fun task() = ManualTaskSnapshot("synthetic-task", 1, "合成私密标题", ManualTaskStatus.OPEN, 1, "UTC", 1, schedule)
    private fun pending(mode: TaskReminderMode = TaskReminderMode.EXACT): ManualTaskSnapshot {
        val task = task()
        return task.copy(reminder = TaskReminders.confirm(task, mode))
    }
    private fun scheduled(mode: TaskReminderMode = TaskReminderMode.EXACT): ManualTaskSnapshot {
        val task = pending(mode)
        return task.copy(reminder = task.reminder!!.copy(phase = TaskReminderPhase.SCHEDULED, commands = emptyList()))
    }
    private fun token(task: ManualTaskSnapshot) = task.reminder!!.let { TaskReminderToken(task.id, it.id, it.scheduleVersion) }

    @Test fun stableAnonymousIdentityAndRedactedValuesNeverExposeTaskTitleOrIdentity() {
        val task = pending()
        val reminder = task.reminder!!
        assertEquals(reminder.id, TaskReminders.id(task.id))
        assertEquals(64, reminder.id.length)
        assertNotEquals(reminder.id, TaskReminders.id("another-synthetic-task"))
        assertEquals(reminder, reminder.copy())
        assertEquals(reminder.hashCode(), reminder.copy().hashCode())
        val values = listOf(reminder, token(task), reminder.commands.single(), TaskReminderMutation(task, null, 2, "mutation"))
        for (value in values) {
            assertFalse(value.toString().contains(task.title))
            assertFalse(value.toString().contains(task.id))
            assertFalse(value.toString().contains(schedule.date.toString()))
        }
    }

    @Test fun confirmationRequiresOpenTaskAndExplicitDateTime() {
        for (task in listOf(task().copy(schedule = null), task().copy(schedule = schedule.copy(time = null, offsetSeconds = null)),
            task().copy(status = ManualTaskStatus.COMPLETED), task().copy(status = ManualTaskStatus.CANCELLED))) {
            assertThrows(IllegalArgumentException::class.java) { TaskReminders.confirm(task, TaskReminderMode.EXACT) }
        }
        assertNull(TaskReminders.revoke(task()))
        assertThrows(IllegalArgumentException::class.java) {
            TaskReminderCommand(TaskReminderToken(task().id, TaskReminders.id(task().id), 1), TaskReminderCommandKind.SCHEDULE,
                schedule.copy(time = null, offsetSeconds = null), TaskReminderMode.EXACT)
        }
    }

    @Test fun reconfirmationKeepsStableIdentityAndCancelsTheExactPreviousGeneration() {
        val first = pending()
        val second = TaskReminders.confirm(first, TaskReminderMode.APPROXIMATE)
        assertEquals(first.reminder!!.id, second.id)
        assertEquals(2L, second.scheduleVersion)
        assertEquals(TaskReminderPhase.PENDING, second.phase)
        assertEquals(listOf(TaskReminderCommandKind.CANCEL, TaskReminderCommandKind.SCHEDULE), second.commands.map { it.kind })
        assertEquals(token(first), second.commands.first().token)
        assertEquals(TaskReminderMode.EXACT, second.commands.first().mode)
        assertEquals(TaskReminderMode.APPROXIMATE, second.commands.last().mode)
        assertEquals(2L, second.commands.last().token.scheduleVersion)
        assertEquals(1, first.reminder!!.commands.size)
    }

    @Test fun revokeAndReconfirmPreservePendingCancellationsWithoutInventingAnotherActiveVersion() {
        val first = scheduled()
        val revoked = TaskReminders.revoke(first)!!
        assertEquals(2L, revoked.scheduleVersion)
        assertEquals(TaskReminderPhase.REVOKED, revoked.phase)
        assertEquals(token(first), revoked.commands.single().token)
        assertEquals(TaskReminderCommandKind.CANCEL, revoked.commands.single().kind)
        val repeated = TaskReminders.revoke(first.copy(reminder = revoked))!!
        assertEquals(3L, repeated.scheduleVersion)
        assertEquals(revoked.commands, repeated.commands)
        val confirmed = TaskReminders.confirm(first.copy(reminder = repeated), TaskReminderMode.APPROXIMATE)
        assertEquals(4L, confirmed.scheduleVersion)
        assertEquals(revoked.commands.single(), confirmed.commands.first())
        assertEquals(2, confirmed.commands.size)
    }

    @Test fun editedDateAndTerminalTaskRemainInvalidUntilASeparateConfirmation() {
        val first = pending()
        val edited = first.copy(schedule = schedule.copy(date = schedule.date.plusDays(1)))
        assertFalse(TaskReminders.commandCurrent(edited, first.reminder!!.commands.single()))
        assertFalse(TaskReminders.canAccept(edited.copy(reminder = first.reminder!!.copy(phase = TaskReminderPhase.SCHEDULED, commands = emptyList())),
            token(first), due + 100, available))
        for (changed in listOf(edited, first.copy(status = ManualTaskStatus.COMPLETED), first.copy(status = ManualTaskStatus.CANCELLED))) {
            val revoked = TaskReminders.revoke(changed)!!
            assertEquals(TaskReminderPhase.REVOKED, revoked.phase)
            assertEquals(schedule, revoked.schedule)
            assertTrue(revoked.commands.all { it.kind == TaskReminderCommandKind.CANCEL })
            assertFalse(TaskReminders.canAccept(changed.copy(reminder = revoked), token(first), due + 100, available))
        }
    }

    @Test fun aPendingScheduleIsReplacedWithItsExactCancellationForAnInFlightDispatch() {
        val first = pending()
        val command = first.reminder!!.commands.single()
        val revoked = TaskReminders.revoke(first)!!
        assertEquals(listOf(command.copy(kind = TaskReminderCommandKind.CANCEL)), revoked.commands)
        val newer = first.copy(reminder = TaskReminders.confirm(first, TaskReminderMode.APPROXIMATE))
        assertFalse(TaskReminders.commandCurrent(newer, command))
        assertTrue(TaskReminders.commandCurrent(newer, newer.reminder!!.commands.first()))
        assertFalse(TaskReminders.canAccept(newer, command.token, due, available))
    }

    @Test fun outboxOverflowRefusesTheWholeMutationAndNeverDropsCancellationHistory() {
        val original = task()
        val id = TaskReminders.id(original.id)
        val cancels = (1L..31L).map { TaskReminderCommand(TaskReminderToken(original.id, id, it), TaskReminderCommandKind.CANCEL, schedule, TaskReminderMode.EXACT) }
        val current = TaskReminderCommand(TaskReminderToken(original.id, id, 32), TaskReminderCommandKind.SCHEDULE, schedule, TaskReminderMode.EXACT)
        val full = TaskReminderSnapshot(id, 32, TaskReminderMode.EXACT, schedule, TaskReminderPhase.PENDING, cancels + current)
        val task = original.copy(reminder = full)
        assertThrows(TaskReminderLimit::class.java) { TaskReminders.confirm(task, TaskReminderMode.EXACT) }
        val revoked = TaskReminders.revoke(task)!!
        assertEquals(cancels + current.copy(kind = TaskReminderCommandKind.CANCEL), revoked.commands)
        assertThrows(TaskReminderLimit::class.java) { TaskReminders.confirm(task.copy(reminder = revoked), TaskReminderMode.EXACT) }
        assertEquals(cancels + current, task.reminder!!.commands)
        assertEquals(32, task.reminder!!.commands.size)
    }

    @Test fun snapshotCopiesOwnTheirImmutableCommandListAndRejectDuplicateOrForeignTokens() {
        val first = pending()
        val command = first.reminder!!.commands.single()
        val mutable = mutableListOf(command)
        val snapshot = first.reminder!!.copy(commands = mutable)
        mutable.clear()
        assertEquals(listOf(command), snapshot.commands)
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.commands as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) { snapshot.copy(commands = listOf(command, command)) }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.copy(commands = listOf(command.copy(token = command.token.copy(reminderId = "a".repeat(64)))))
        }
        val wrongOwner = snapshot.copy(commands = listOf(command.copy(token = command.token.copy(taskId = "other-task"))))
        assertThrows(IllegalArgumentException::class.java) { TaskReminders.validate(first.id, wrongOwner) }
        assertThrows(IllegalArgumentException::class.java) { TaskReminders.validate("other-task", snapshot) }
    }

    @Test fun snapshotRejectsUnreachablePhasesAndReservesCapacityForTerminalRevocation() {
        val task = pending()
        val reminder = task.reminder!!
        val command = reminder.commands.single()
        assertThrows(IllegalArgumentException::class.java) { reminder.copy(commands = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { reminder.copy(phase = TaskReminderPhase.SCHEDULED) }
        assertThrows(IllegalArgumentException::class.java) {
            reminder.copy(phase = TaskReminderPhase.REVOKED, commands = listOf(command.copy(kind = TaskReminderCommandKind.CANCEL)))
        }
        val cancels = (1L..32L).map { command.copy(token = command.token.copy(scheduleVersion = it), kind = TaskReminderCommandKind.CANCEL) }
        assertThrows(IllegalArgumentException::class.java) {
            reminder.copy(scheduleVersion = 33, phase = TaskReminderPhase.SCHEDULED, commands = cancels)
        }
        val active = reminder.copy(scheduleVersion = 33, phase = TaskReminderPhase.SCHEDULED, commands = cancels.take(31))
        assertEquals(32, TaskReminders.revoke(task.copy(reminder = active))!!.commands.size)
    }

    @Test fun oldCancellationAcknowledgedAfterNewScheduleCannotInvalidateTheNewToken() {
        val first = scheduled()
        val next = TaskReminders.confirm(first, TaskReminderMode.APPROXIMATE)
        val oldCancel = next.commands.first()
        val newSchedule = next.commands.last()
        val afterScheduleAck = first.copy(reminder = next.copy(phase = TaskReminderPhase.SCHEDULED, commands = listOf(oldCancel)))
        assertTrue(TaskReminders.commandCurrent(afterScheduleAck, oldCancel))
        assertFalse(TaskReminders.commandCurrent(afterScheduleAck, newSchedule))
        assertFalse(TaskReminders.canAccept(afterScheduleAck, token(first), due, available))
        assertTrue(TaskReminders.canAccept(afterScheduleAck, newSchedule.token, due, available))
        val afterOldCancelAck = afterScheduleAck.copy(reminder = afterScheduleAck.reminder!!.copy(commands = emptyList()))
        assertEquals(TaskReminderPhase.SCHEDULED, afterOldCancelAck.reminder!!.phase)
        assertTrue(TaskReminders.canAccept(afterOldCancelAck, newSchedule.token, due, available))
        val repo = PortRepository(listOf(newSchedule, oldCancel))
        val driver = PortDriver(available)
        assertEquals(2, TaskReminderDispatcher(repo, driver) { 5 }.drain())
        assertEquals(listOf(newSchedule), driver.scheduled)
        assertEquals(listOf(oldCancel), driver.cancelled)
        assertNotEquals(driver.scheduled.single().token, driver.cancelled.single().token)
    }

    @Test fun currentCommandMustStillBelongToTheTaskOutboxAndMatchingSchedule() {
        val first = pending()
        val command = first.reminder!!.commands.single()
        assertTrue(TaskReminders.commandCurrent(first, command))
        assertFalse(TaskReminders.commandCurrent(null, command))
        assertFalse(TaskReminders.commandCurrent(first.copy(reminder = null), command))
        assertFalse(TaskReminders.commandCurrent(first.copy(status = ManualTaskStatus.COMPLETED), command))
        assertFalse(TaskReminders.commandCurrent(first.copy(reminder = first.reminder!!.copy(phase = TaskReminderPhase.FAILED, commands = emptyList())), command))
        assertFalse(TaskReminders.commandCurrent(first, command.copy(mode = TaskReminderMode.APPROXIMATE)))
        val revoked = first.copy(reminder = TaskReminders.revoke(first))
        val cancel = revoked.reminder!!.commands.single()
        assertTrue(TaskReminders.commandCurrent(revoked.copy(status = ManualTaskStatus.CANCELLED, schedule = null), cancel))
    }

    @Test fun triggerAcceptanceRequiresCurrentScheduledOpenTaskAndActualDueInstant() {
        val task = scheduled()
        val token = token(task)
        assertFalse(TaskReminders.canAccept(task, token, due - 1, available))
        assertTrue(TaskReminders.canAccept(task, token, due, available))
        assertTrue(TaskReminders.canAccept(task, token, due + 86400000, available))
        assertFalse(TaskReminders.canAccept(null, token, due, available))
        assertFalse(TaskReminders.canAccept(task.copy(reminder = null), token, due, available))
        assertFalse(TaskReminders.canAccept(task, token.copy(taskId = "missing-task"), due, available))
        assertFalse(TaskReminders.canAccept(task, token.copy(reminderId = "b".repeat(64)), due, available))
        assertFalse(TaskReminders.canAccept(task, token.copy(scheduleVersion = 2), due, available))
        assertFalse(TaskReminders.canAccept(task, token, -1, available))
        for (status in listOf(ManualTaskStatus.COMPLETED, ManualTaskStatus.CANCELLED))
            assertFalse(TaskReminders.canAccept(task.copy(status = status), token, due, available))
        for (phase in TaskReminderPhase.entries.filter { it != TaskReminderPhase.SCHEDULED }) {
            val commands = if (phase == TaskReminderPhase.PENDING) pending().reminder!!.commands else emptyList()
            assertFalse(TaskReminders.canAccept(task.copy(reminder = task.reminder!!.copy(phase = phase, commands = commands)), token, due, available))
        }
    }

    @Test fun permissionStatesAreExplicitAndExactDenialNeverDowngradesTheMode() {
        assertEquals(TaskReminderPhase.PLATFORM_UNAVAILABLE, TaskReminderAvailability().blocked(TaskReminderMode.EXACT))
        assertEquals(TaskReminderPhase.NOTIFICATION_REQUIRED,
            available.copy(notifications = TaskReminderPermission.NOT_REQUESTED).blocked(TaskReminderMode.EXACT))
        assertEquals(TaskReminderPhase.NOTIFICATION_DENIED,
            available.copy(notifications = TaskReminderPermission.DENIED).blocked(TaskReminderMode.EXACT))
        assertEquals(TaskReminderPhase.CHANNEL_DISABLED, available.copy(channelEnabled = false).blocked(TaskReminderMode.EXACT))
        assertEquals(TaskReminderPhase.EXACT_REQUIRED,
            available.copy(exactAlarms = TaskReminderPermission.NOT_REQUESTED).blocked(TaskReminderMode.EXACT))
        assertEquals(TaskReminderPhase.EXACT_DENIED,
            available.copy(exactAlarms = TaskReminderPermission.DENIED).blocked(TaskReminderMode.EXACT))
        assertNull(available.copy(exactAlarms = TaskReminderPermission.DENIED).blocked(TaskReminderMode.APPROXIMATE))
        assertNull(available.blocked(TaskReminderMode.EXACT))
        val task = scheduled()
        for (state in listOf(TaskReminderAvailability(), available.copy(notifications = TaskReminderPermission.DENIED),
            available.copy(channelEnabled = false), available.copy(exactAlarms = TaskReminderPermission.DENIED)))
            assertFalse(TaskReminders.canAccept(task, token(task), due, state))
        val approximate = scheduled(TaskReminderMode.APPROXIMATE)
        assertTrue(TaskReminders.canAccept(approximate, token(approximate), due, available.copy(exactAlarms = TaskReminderPermission.DENIED)))
    }

    @Test fun explicitMutationValidatesIdentityExpectedVersionAndMonotonicRequestTime() {
        assertThrows(IllegalArgumentException::class.java) { TaskReminderMutation(task(), TaskReminderMode.EXACT, 0, "change") }
        assertThrows(IllegalArgumentException::class.java) { TaskReminderMutation(task(), null, 2, "") }
        assertThrows(IllegalArgumentException::class.java) { TaskReminderMutation(task().copy(version = Long.MAX_VALUE), null, 2, "change") }
        val exhausted = pending().let { it.copy(reminder = it.reminder!!.copy(scheduleVersion = Long.MAX_VALUE,
            phase = TaskReminderPhase.REVOKED, commands = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { TaskReminders.confirm(exhausted, TaskReminderMode.EXACT) }
        assertThrows(IllegalArgumentException::class.java) { TaskReminders.revoke(exhausted) }
    }

    @Test fun dispatcherRecordsExplicitCapabilityFailuresWithoutCallingSchedule() {
        val command = pending().reminder!!.commands.single()
        val states = listOf(TaskReminderAvailability(), available.copy(notifications = TaskReminderPermission.NOT_REQUESTED),
            available.copy(notifications = TaskReminderPermission.DENIED), available.copy(channelEnabled = false),
            available.copy(exactAlarms = TaskReminderPermission.NOT_REQUESTED), available.copy(exactAlarms = TaskReminderPermission.DENIED))
        for (state in states) {
            val repo = PortRepository(listOf(command))
            val driver = PortDriver(state)
            assertEquals(1, TaskReminderDispatcher(repo, driver) { 5 }.drain())
            assertTrue(driver.scheduled.isEmpty())
            assertEquals(listOf(command to state.blocked(command.mode)!!), repo.acks)
            assertTrue(repo.queue.isEmpty())
        }
    }

    @Test fun dispatcherKeepsExplicitApproximateModeAndAcknowledgesSuccessfulScheduleOnce() {
        val command = pending(TaskReminderMode.APPROXIMATE).reminder!!.commands.single()
        val repo = PortRepository(listOf(command))
        val driver = PortDriver(available.copy(exactAlarms = TaskReminderPermission.DENIED))
        val dispatcher = TaskReminderDispatcher(repo, driver) { 5 }
        assertEquals(1, dispatcher.drain())
        assertEquals(listOf(command), driver.scheduled)
        assertEquals(listOf(command to TaskReminderPhase.SCHEDULED), repo.acks)
        assertEquals(0, dispatcher.drain())
        assertEquals(1, driver.scheduled.size)
    }

    @Test fun dispatcherObsoleteScheduleIsRetiredWithoutDriverAndObsoleteCancelIsUntouched() {
        val command = pending().reminder!!.commands.single()
        val cancel = command.copy(kind = TaskReminderCommandKind.CANCEL)
        val repo = PortRepository(listOf(command, cancel)).also { it.current = false }
        val driver = PortDriver(available)
        assertEquals(1, TaskReminderDispatcher(repo, driver) { 5 }.drain())
        assertEquals(listOf(command to TaskReminderPhase.REVOKED), repo.acks)
        assertEquals(listOf(cancel), repo.queue)
        assertEquals(0, driver.availabilityCalls)
        assertTrue(driver.scheduled.isEmpty())
        assertTrue(driver.cancelled.isEmpty())
    }

    @Test fun failedCancellationAndUnknownDriverExceptionPreserveExactCommandUntilRetry() {
        val command = pending().reminder!!.commands.single().copy(kind = TaskReminderCommandKind.CANCEL)
        val repo = PortRepository(listOf(command))
        val driver = PortDriver(TaskReminderAvailability()).also { it.cancelResult = TaskReminderPhase.FAILED }
        val dispatcher = TaskReminderDispatcher(repo, driver) { 5 }
        assertEquals(0, dispatcher.drain())
        assertEquals(listOf(command), repo.queue)
        driver.cancelResult = TaskReminderPhase.PLATFORM_UNAVAILABLE
        assertEquals(0, dispatcher.drain())
        driver.throwOnCancel = true
        assertEquals(0, dispatcher.drain())
        assertTrue(repo.acks.isEmpty())
        driver.throwOnCancel = false
        driver.cancelResult = TaskReminderPhase.REVOKED
        assertEquals(1, dispatcher.drain())
        assertEquals(listOf(command to TaskReminderPhase.REVOKED), repo.acks)
        assertEquals(listOf(command, command, command, command), driver.cancelled)
        assertEquals(0, driver.availabilityCalls)
    }

    @Test fun unknownScheduleOutcomeIsNotAcknowledgedButExplicitFailureIs() {
        val command = pending().reminder!!.commands.single()
        val repo = PortRepository(listOf(command))
        val driver = PortDriver(available).also { it.throwOnSchedule = true }
        val dispatcher = TaskReminderDispatcher(repo, driver) { 5 }
        assertEquals(0, dispatcher.drain())
        assertEquals(listOf(command), repo.queue)
        driver.throwOnSchedule = false
        driver.scheduleResult = TaskReminderPhase.REVOKED
        assertEquals(0, dispatcher.drain())
        assertEquals(listOf(command), repo.queue)
        driver.scheduleResult = TaskReminderPhase.FAILED
        assertEquals(1, dispatcher.drain())
        assertEquals(listOf(command to TaskReminderPhase.FAILED), repo.acks)
    }

    @Test fun drainHonorsItsBoundAndPreservesCommandWhenAcknowledgementFails() {
        val command = pending().reminder!!.commands.single()
        val other = command.copy(token = command.token.copy(taskId = "second-task"))
        val repo = PortRepository(listOf(command, other))
        val driver = PortDriver(available)
        val dispatcher = TaskReminderDispatcher(repo, driver) { 5 }
        assertEquals(1, dispatcher.drain(1))
        assertEquals(listOf(other), repo.queue)
        assertEquals(listOf(command), driver.scheduled)
        repo.throwOnAck = true
        assertEquals(0, dispatcher.drain())
        assertEquals(listOf(other), repo.queue)
        assertThrows(IllegalArgumentException::class.java) { dispatcher.drain(0) }
        assertThrows(IllegalArgumentException::class.java) { dispatcher.drain(33) }
    }

    @Test fun dispatcherPropagatesCancellationAndKeepsTheExactCommand() {
        val command = pending().reminder!!.commands.single()
        val repo = PortRepository(listOf(command))
        val driver = object : TaskReminderDriver {
            override fun availability() = available
            override fun schedule(command: TaskReminderCommand): TaskReminderPhase = throw CancellationException("synthetic cancellation")
            override fun cancel(command: TaskReminderCommand): TaskReminderPhase = throw CancellationException("synthetic cancellation")
        }
        assertThrows(CancellationException::class.java) { TaskReminderDispatcher(repo, driver) { 5 }.drain() }
        assertEquals(listOf(command), repo.queue)
        assertTrue(repo.acks.isEmpty())
    }

    @Test fun blockedOldCancellationsCannotStarveTheCapabilityResultForANewConfirmation() {
        val command = pending().reminder!!.commands.single()
        val oldCancels = (1L..16L).map { command.copy(token = command.token.copy(scheduleVersion = it), kind = TaskReminderCommandKind.CANCEL) }
        val newSchedule = command.copy(token = command.token.copy(scheduleVersion = 17))
        val repo = PortRepository(oldCancels + newSchedule)
        val driver = PortDriver(TaskReminderAvailability()).also { it.cancelResult = TaskReminderPhase.PLATFORM_UNAVAILABLE }
        assertEquals(1, TaskReminderDispatcher(repo, driver) { 5 }.drain())
        assertEquals(listOf(newSchedule to TaskReminderPhase.PLATFORM_UNAVAILABLE), repo.acks)
        assertEquals(oldCancels, repo.queue)
        assertEquals(oldCancels.take(15), driver.cancelled)
        assertTrue(driver.scheduled.isEmpty())
    }

    /** These ports record calls only; they do not pretend to emulate device alarms or notifications. */
    private class PortRepository(commands: List<TaskReminderCommand>) : TaskReminderRepository {
        val queue = commands.toMutableList()
        val acks = mutableListOf<Pair<TaskReminderCommand, TaskReminderPhase>>()
        var current = true
        var throwOnAck = false
        override fun reminderCommands() = queue.toList()
        override fun isReminderCommandCurrent(command: TaskReminderCommand) = current
        override fun acknowledgeReminder(command: TaskReminderCommand, result: TaskReminderPhase, atMillis: Long): Boolean {
            if (throwOnAck) throw IllegalStateException("synthetic unavailable port")
            if (!queue.remove(command)) return false
            acks += command to result
            return true
        }
        override fun changeReminder(request: TaskReminderMutation): ManualTaskSnapshot = throw UnsupportedOperationException()
        override fun acceptReminderTrigger(token: TaskReminderToken, nowMillis: Long, availability: TaskReminderAvailability) = false
    }

    private class PortDriver(private val state: TaskReminderAvailability) : TaskReminderDriver {
        var availabilityCalls = 0
        val scheduled = mutableListOf<TaskReminderCommand>()
        val cancelled = mutableListOf<TaskReminderCommand>()
        var scheduleResult = TaskReminderPhase.SCHEDULED
        var cancelResult = TaskReminderPhase.REVOKED
        var throwOnSchedule = false
        var throwOnCancel = false
        override fun availability(): TaskReminderAvailability { availabilityCalls++; return state }
        override fun schedule(command: TaskReminderCommand): TaskReminderPhase {
            scheduled += command
            if (throwOnSchedule) throw IllegalStateException("synthetic unknown schedule outcome")
            return scheduleResult
        }
        override fun cancel(command: TaskReminderCommand): TaskReminderPhase {
            cancelled += command
            if (throwOnCancel) throw IllegalStateException("synthetic unknown cancellation outcome")
            return cancelResult
        }
    }
}
