package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual foreground controller + product SQL/AEAD. No Android alarm or notification is installed. */
class TaskReminderControllerTest {
    @Test(timeout = 15_000)
    fun previewAndModeNeverWriteAndExplicitConfirmationPreservesDraftSourcesAndPrivateInput() = Harness().use { h ->
        val task = h.task("one")
        val proposal = h.draft()
        h.initialize()
        val unrelated = h.unrelatedRows()
        val draftBytes = h.blob(TaskProposals.storageId(proposal.id))
        val taskBytes = h.blob(task.id)

        val editor = h.begin(task)
        assertEquals(TaskReminderMode.APPROXIMATE, editor.mode)
        h.controller.mode(editor.session, TaskReminderMode.EXACT)
        assertEquals(TaskReminderMode.EXACT, h.state.reminders.editor!!.mode)
        assertTrue(h.requests.isEmpty())
        assertTrue(h.queued.isEmpty())
        assertArrayEquals(taskBytes, h.blob(task.id))
        assertNull(h.actual.snapshots().single().reminder)

        h.controller.confirm(editor.session)
        assertTrue(h.state.reminders.busy)
        assertNull(h.state.reminders.notice)
        assertArrayEquals(taskBytes, h.blob(task.id))
        h.pump()

        val saved = h.actual.snapshots().single()
        assertEquals(task.title, saved.title)
        assertEquals(task.schedule, saved.schedule)
        assertEquals(ManualTaskStatus.OPEN, saved.status)
        val reminder = checkNotNull(saved.reminder)
        assertEquals(TaskReminderMode.EXACT, reminder.mode)
        assertEquals(1L, reminder.scheduleVersion)
        assertEquals(TaskReminderPhase.PLATFORM_UNAVAILABLE, reminder.phase)
        assertEquals(3L, saved.version) // One explicit confirmation and its persisted dispatch result.
        assertTrue(reminder.commands.isEmpty())
        assertNull(h.state.reminders.editor)
        assertNull(h.state.reminders.pending)
        assertNotNull(h.state.reminders.notice)
        assertFalse(h.state.reminders.notice!!.contains("已安排"))
        assertFalse(h.state.reminders.notice!!.contains("已通知"))
        assertEquals("尚未提交合成输入", h.state.text)
        assertEquals(unrelated, h.unrelatedRows())
        assertEquals(proposal, h.proposals.list().single().proposal)
        assertArrayEquals(draftBytes, h.blob(TaskProposals.storageId(proposal.id)))
        h.assertFreshReader(saved)
    }

    @Test(timeout = 15_000)
    fun undatedAndDateOnlyTasksCannotAcquireAnInferredReminderTime() = Harness().use { h ->
        val undated = h.task("undated", null)
        val dateOnly = h.task("date-only", ManualTaskDateInput("2042-11-19", "", "Asia/Shanghai").parse())
        h.initialize()
        for (task in listOf(undated, dateOnly)) {
            val bytes = h.blob(task.id)
            h.controller.begin(task.id, task.version)
            assertNull(h.state.reminders.editor)
            assertNotNull(h.state.reminders.error)
            h.controller.confirm(1)
            assertTrue(h.queued.isEmpty())
            assertTrue(h.requests.isEmpty())
            assertArrayEquals(bytes, h.blob(task.id))
        }
        assertTrue(h.actual.snapshots().all { it.reminder == null })
    }

    @Test(timeout = 15_000)
    fun actualRollbackRetainsExactMutationAndRetryDoesNotRepeatConfirmationGeneration() = Harness().use { h ->
        val task = h.task("one")
        h.initialize()
        val before = h.blob(task.id)
        val editor = h.begin(task)
        h.f.db.beforeCommit = { error("Synthetic reminder transaction rollback") }
        h.controller.confirm(editor.session)
        h.pump()
        h.f.db.beforeCommit = null

        val pending = checkNotNull(h.state.reminders.pending)
        assertSame(h.requests.single(), pending.request)
        assertNotNull(h.state.reminders.error)
        assertNull(h.state.reminders.notice)
        assertFalse(h.state.reminders.busy)
        assertSame(editor, h.state.reminders.editor)
        assertArrayEquals(before, h.blob(task.id))
        h.controller.mode(editor.session, TaskReminderMode.EXACT)
        h.controller.confirm(editor.session)
        assertTrue(h.queued.isEmpty())
        assertEquals(TaskReminderMode.APPROXIMATE, h.state.reminders.editor!!.mode)

        h.controller.retry()
        h.pump()
        assertEquals(2, h.requests.size)
        assertTrue(h.requests.all { it === pending.request })
        assertEquals(1, h.requests.map { it.mutationId }.distinct().size)
        assertEquals(1L, h.actual.snapshots().single().reminder!!.scheduleVersion)
        assertEquals(3L, h.actual.snapshots().single().version)
        assertNull(h.state.reminders.pending)
        assertNull(h.state.reminders.editor)
    }

    @Test(timeout = 15_000)
    fun lostActualCommitAcknowledgementKeepsPendingCommandUntilExactRequestReadback() = Harness().use { h ->
        val task = h.task("one")
        h.initialize()
        val editor = h.begin(task)
        var loseOnce = true
        h.f.db.afterSql = { sql ->
            if (sql == "COMMIT" && loseOnce) {
                loseOnce = false
                error("Synthetic lost reminder COMMIT acknowledgement")
            }
        }
        h.controller.confirm(editor.session)
        h.pump()
        h.f.db.afterSql = null

        val pending = checkNotNull(h.state.reminders.pending)
        val durable = h.actual.snapshots().single()
        val durableReminder = checkNotNull(durable.reminder)
        assertEquals(2L, durable.version)
        assertEquals(TaskReminderPhase.PENDING, durableReminder.phase)
        assertEquals(1L, durableReminder.scheduleVersion)
        assertEquals(1, durableReminder.commands.size)
        assertNotNull(h.state.reminders.error)
        assertNull(h.state.reminders.notice)

        h.controller.close(editor.session)
        assertNull(h.state.reminders.editor)
        h.controller.begin(task.id, task.version)
        assertNull(h.state.reminders.editor)
        h.controller.retry()
        h.pump()
        assertEquals(2, h.requests.size)
        assertTrue(h.requests.all { it === pending.request })
        val saved = h.actual.snapshots().single()
        val reminder = checkNotNull(saved.reminder)
        assertEquals(3L, saved.version)
        assertEquals(1L, reminder.scheduleVersion)
        assertEquals(TaskReminderPhase.PLATFORM_UNAVAILABLE, reminder.phase)
        assertNull(h.state.reminders.pending)
        assertNull(h.state.reminders.editor)
        assertEquals(1, h.actual.snapshots().size)
        h.assertFreshReader(saved)
    }

    @Test(timeout = 15_000)
    fun failedPostCommitReadbackAndCapabilityReinitializationReplayOriginalRequestOnly() = Harness().use { h ->
        val task = h.task("one")
        h.initialize()
        val editor = h.begin(task)
        h.failReadback = true
        h.controller.confirm(editor.session)
        h.pump()
        val pending = checkNotNull(h.state.reminders.pending)
        val committedBytes = h.blob(task.id)
        assertEquals(2L, h.actual.snapshots().single().version)
        assertEquals(TaskReminderPhase.PENDING, h.actual.snapshots().single().reminder!!.phase)
        assertNull(h.state.reminders.notice)

        runBlocking { h.controller.initialize(h.store) }
        h.pump()
        assertArrayEquals(committedBytes, h.blob(task.id))
        assertSame(pending, h.state.reminders.pending)
        assertSame(editor, h.state.reminders.editor)
        assertNotNull(h.state.reminders.error)

        h.failReadback = false
        runBlocking { h.controller.initialize(h.store) }
        h.pump()
        assertEquals(3, h.requests.size)
        assertTrue(h.requests.all { it === pending.request })
        assertEquals(1L, h.actual.snapshots().single().reminder!!.scheduleVersion)
        assertEquals(3L, h.actual.snapshots().single().version)
        assertNull(h.state.reminders.pending)
        assertNull(h.state.reminders.editor)
        assertNotNull(h.state.reminders.notice)
    }

    @Test(timeout = 15_000)
    fun closedSessionCannotChangeModeConfirmOrCloseSameTitleTaskInNewSession() = Harness().use { h ->
        val first = h.task("one")
        val second = h.task("two")
        h.initialize()
        val old = h.begin(first)
        h.controller.close(old.session)
        val current = h.begin(second)
        assertNotEquals(old.session, current.session)

        h.controller.mode(old.session, TaskReminderMode.EXACT)
        h.controller.confirm(old.session)
        h.controller.close(old.session)
        assertSame(current, h.state.reminders.editor)
        assertTrue(h.requests.isEmpty())
        assertTrue(h.queued.isEmpty())
        h.controller.mode(current.session, TaskReminderMode.EXACT)
        h.controller.confirm(current.session)
        h.pump()
        assertEquals(second.id, h.requests.single().task.id)
        assertNull(h.actual.snapshots().single { it.id == first.id }.reminder)
        val changed = h.actual.snapshots().single { it.id == second.id }
        val reminder = checkNotNull(changed.reminder)
        assertEquals(TaskReminderMode.EXACT, reminder.mode)
        assertEquals(TaskReminderPhase.PLATFORM_UNAVAILABLE, reminder.phase)
        assertEquals(1L, h.actual.snapshots().single { it.id == first.id }.version)
    }

    @Test(timeout = 15_000)
    fun concurrentTaskVersionChangeConflictsAndExplicitReloadNeverRetargetsAnotherTask() = Harness().use { h ->
        val first = h.task("one")
        val second = h.task("two")
        h.initialize()
        val editor = h.begin(first)
        val completed = h.actual.change(ManualTaskMutation(first.id, first.version,
            ManualTaskStatus.COMPLETED, NOW, "concurrent-completion"))
        val bytes = h.blob(first.id)

        h.controller.confirm(editor.session)
        h.pump()
        assertTrue(h.state.reminders.conflict)
        assertNotNull(h.state.reminders.error)
        assertNull(h.state.reminders.notice)
        assertArrayEquals(bytes, h.blob(first.id))
        assertEquals(completed, h.actual.snapshots().single { it.id == first.id })
        assertNull(h.actual.snapshots().single { it.id == second.id }.reminder)

        h.controller.retry()
        h.pump()
        assertFalse(h.state.reminders.conflict)
        assertNull(h.state.reminders.pending)
        assertNull(h.state.reminders.editor)
        val requests = h.requests.size
        h.controller.confirm(editor.session)
        h.controller.begin(first.id, completed.version)
        assertNull(h.state.reminders.editor)
        assertEquals(requests, h.requests.size)
        assertArrayEquals(bytes, h.blob(first.id))
        val other = h.begin(second)
        assertEquals(second.id, other.task.id)
        assertEquals(1L, h.actual.snapshots().single { it.id == second.id }.version)
    }

    @Test(timeout = 20_000)
    fun permissionDenialAndDisabledChannelAreDurableAndNeverCallScheduleOrDowngradeExact() {
        val cases = listOf(
            Triple(TaskReminderAvailability(true, TaskReminderPermission.DENIED),
                TaskReminderMode.APPROXIMATE, TaskReminderPhase.NOTIFICATION_DENIED),
            Triple(TaskReminderAvailability(true, TaskReminderPermission.GRANTED, TaskReminderPermission.DENIED),
                TaskReminderMode.EXACT, TaskReminderPhase.EXACT_DENIED),
            Triple(TaskReminderAvailability(true, TaskReminderPermission.GRANTED, channelEnabled = false),
                TaskReminderMode.APPROXIMATE, TaskReminderPhase.CHANNEL_DISABLED),
            Triple(TaskReminderAvailability(true),
                TaskReminderMode.APPROXIMATE, TaskReminderPhase.NOTIFICATION_REQUIRED),
        )
        for ((availability, mode, expected) in cases) {
            val driver = FakeDriver(availability)
            Harness(driver).use { h ->
                val task = h.task("one")
                h.initialize()
                val editor = h.begin(task)
                h.controller.mode(editor.session, mode)
                h.controller.confirm(editor.session)
                h.pump()
                val saved = h.actual.snapshots().single()
                val reminder = checkNotNull(saved.reminder)
                assertEquals(mode, reminder.mode)
                assertEquals(expected, reminder.phase)
                assertTrue(reminder.commands.isEmpty())
                assertTrue(driver.scheduled.isEmpty())
                assertTrue(driver.cancelled.isEmpty())
                assertNull(h.state.reminders.error)
                assertFalse(h.state.reminders.notice!!.contains("已安排"))
                h.assertFreshReader(saved)
            }
        }
    }

    @Test(timeout = 15_000)
    fun confirmedCancellationIsSeparateFromPreviewAndUsesOnlyThePreviouslyConfirmedToken() {
        val driver = FakeDriver(TaskReminderAvailability(true, TaskReminderPermission.GRANTED,
            TaskReminderPermission.GRANTED))
        Harness(driver).use { h ->
            val task = h.task("one")
            h.initialize()
            h.controller.confirm(h.begin(task).session)
            h.pump()
            val scheduled = h.actual.snapshots().single()
            val token = driver.scheduled.single().token
            val scheduledReminder = checkNotNull(scheduled.reminder)
            assertEquals(TaskReminderPhase.SCHEDULED, scheduledReminder.phase)
            h.controller.begin(task.id, scheduled.version, cancelling = true)
            val editor = checkNotNull(h.state.reminders.editor)
            val before = h.blob(task.id)
            h.controller.mode(editor.session, TaskReminderMode.EXACT)
            assertTrue(editor.cancelling)
            assertEquals(scheduledReminder.mode, h.state.reminders.editor!!.mode)
            assertArrayEquals(before, h.blob(task.id))
            assertTrue(driver.cancelled.isEmpty())

            h.controller.confirm(editor.session)
            h.pump()
            val revoked = h.actual.snapshots().single()
            val revokedReminder = checkNotNull(revoked.reminder)
            assertEquals(TaskReminderPhase.REVOKED, revokedReminder.phase)
            assertEquals(scheduledReminder.id, revokedReminder.id)
            assertEquals(scheduledReminder.scheduleVersion + 1, revokedReminder.scheduleVersion)
            assertEquals(token, driver.cancelled.single().token)
            assertEquals(1, driver.scheduled.size)
            assertEquals(ManualTaskStatus.OPEN, revoked.status)
            assertNull(h.requests.last().mode)
            assertTrue(revokedReminder.commands.isEmpty())
            assertFalse(h.actual.acceptReminderTrigger(token, task.schedule!!.instant()!!.toEpochMilli(), driver.availability()))
        }
    }

    @Test(timeout = 15_000)
    fun disposalBeforeQueuedConfirmationPreventsWriteAndAllRetiredActionsStaySilent() = Harness().use { h ->
        val task = h.task("one")
        h.initialize()
        val editor = h.begin(task)
        h.controller.confirm(editor.session)
        assertTrue(h.state.reminders.busy)
        val before = h.blob(task.id)
        h.controller.dispose()
        val frozen = h.state
        h.pump()
        h.controller.begin(task.id, task.version)
        h.controller.mode(editor.session, TaskReminderMode.EXACT)
        h.controller.confirm(editor.session)
        h.controller.close(editor.session)
        h.controller.retry()
        runBlocking { h.controller.initialize(h.store) }
        assertSame(frozen, h.state)
        assertTrue(h.requests.isEmpty())
        assertTrue(h.queued.isEmpty())
        assertArrayEquals(before, h.blob(task.id))
        assertNull(h.actual.snapshots().single().reminder)
    }

    @Test(timeout = 15_000)
    fun disposalAfterRealCommitDropsReadbackPublicationAndLeavesExactOutboxForNextOwner() = Harness().use { h ->
        val task = h.task("one")
        h.initialize()
        val editor = h.begin(task)
        var frozen: LocalRecordUiState? = null
        h.afterWrite = {
            h.controller.dispose()
            frozen = h.state
        }
        h.controller.confirm(editor.session)
        h.pump()
        assertSame(frozen, h.state)
        assertNull(h.state.reminders.notice)
        assertNull(h.state.reminders.editor)
        assertNull(h.state.reminders.pending)
        val saved = h.actual.snapshots().single()
        val reminder = checkNotNull(saved.reminder)
        assertEquals(2L, saved.version)
        assertEquals(TaskReminderPhase.PENDING, reminder.phase)
        assertEquals(1, reminder.commands.size)
        assertEquals(1L, reminder.scheduleVersion)
        h.controller.confirm(editor.session)
        h.controller.retry()
        assertSame(frozen, h.state)
        assertEquals(1, h.requests.size)
        h.assertFreshReader(saved)
    }

    @Test(timeout = 15_000)
    fun oldManualTaskCallbacksCannotCrossReminderPreviewQueuedWriteOrClosedUnknownConfirmation() = Harness().use { h ->
        val task = h.task("one")
        h.initialize()
        val manual = ManualTaskController({ h.state }, { h.state = it(h.state) },
            { h.queued.addLast(it); true }, CLOCK, { "UTC" })
        val oldChange = { manual.change(task.id, task.version, ManualTaskStatus.COMPLETED) }
        val oldBeginDate = { manual.beginDate(task.id, task.version, ManualTaskDateOperation.SET) }
        val oldBegin = { manual.begin() }
        fun assertTaskCallbacksBlocked(expectedQueueSize: Int) {
            val bytes = h.blob(task.id)
            oldChange()
            oldBeginDate()
            oldBegin()
            assertEquals(expectedQueueSize, h.queued.size)
            assertNull(h.state.tasks.pending)
            assertNull(h.state.tasks.editor)
            assertNull(h.state.tasks.dateEditor)
            assertFalse(h.state.tasks.busy)
            assertArrayEquals(bytes, h.blob(task.id))
        }

        val editor = h.begin(task)
        assertTaskCallbacksBlocked(0)
        h.failReadback = true
        h.controller.confirm(editor.session)
        assertTrue(h.state.reminders.busy)
        assertTaskCallbacksBlocked(1)
        h.pump()

        assertFalse(h.state.reminders.busy)
        val unknown = checkNotNull(h.state.reminders.pending)
        assertEquals(TaskReminderPhase.PENDING, h.actual.snapshots().single().reminder!!.phase)
        h.controller.close(editor.session)
        assertNull(h.state.reminders.editor)
        assertTaskCallbacksBlocked(0)
        assertSame(unknown, h.state.reminders.pending)
        assertEquals(1, h.requests.size)
        assertEquals(ManualTaskStatus.OPEN, h.actual.snapshots().single().status)

        h.failReadback = false
        h.controller.retry()
        h.pump()
        assertTrue(h.requests.all { it === unknown.request })
        val saved = h.actual.snapshots().single()
        assertEquals(ManualTaskStatus.OPEN, saved.status)
        assertEquals(task.schedule, saved.schedule)
        assertEquals(1L, saved.reminder!!.scheduleVersion)
        assertEquals(TaskReminderPhase.PLATFORM_UNAVAILABLE, saved.reminder!!.phase)
        manual.dispose()
    }

    @Test(timeout = 20_000)
    fun unknownCommittedManualStatusOrDateWriteDefersReminderDrainAndPreservesExactReplayIdentity() {
        for (changeDate in listOf(false, true)) {
            val driver = FakeDriver(TaskReminderAvailability(true, TaskReminderPermission.GRANTED,
                TaskReminderPermission.GRANTED))
            Harness(driver).use { h ->
                val task = h.task("one")
                h.initialize()
                h.controller.confirm(h.begin(task).session)
                h.pump()
                val scheduled = h.actual.snapshots().single()
                val previousToken = driver.scheduled.single().token

                // A previously queued foreground reconciliation must also check again at execution.
                h.controller.retry()
                assertEquals(1, h.queued.size)
                val mutationId = if (changeDate) "unknown-manual-date" else "unknown-manual-status"
                val pending: ManualTaskIntent = if (changeDate) ManualTaskIntent.Schedule(77,
                    ManualTaskScheduleMutation(task.id, scheduled.version, ManualTaskDateOperation.SET,
                        ManualTaskDateInput("2042-11-20", "09:30", "Asia/Shanghai").parse(), NOW + 1, mutationId))
                else ManualTaskIntent.Change(ManualTaskMutation(task.id, scheduled.version,
                    ManualTaskStatus.COMPLETED, NOW + 1, mutationId))
                fun replay(): ManualTaskSnapshot = when (pending) {
                    is ManualTaskIntent.Change -> h.actual.change(pending.request)
                    is ManualTaskIntent.Schedule -> h.actual.reschedule(pending.request)
                    is ManualTaskIntent.Create -> error("This test never creates an implicit task")
                }
                val committed = replay()
                val before = h.blob(task.id)
                assertEquals(TaskReminderPhase.REVOKED, committed.reminder!!.phase)
                assertEquals(1, committed.reminder!!.commands.size)
                h.state = h.state.copy(tasks = h.state.tasks.copy(items = listOf(scheduled),
                    available = false, busy = false, loading = false, pending = pending,
                    error = "Synthetic unknown post-commit manual readback"))

                h.pump()
                assertFalse(h.state.reminders.busy)
                runBlocking { h.controller.initialize(h.store) }
                h.controller.retry()
                h.pump()
                assertTrue(h.queued.isEmpty())
                assertNotNull(h.state.reminders.error)
                assertSame(pending, h.state.tasks.pending)
                assertFalse(h.state.tasks.busy)
                assertTrue(driver.cancelled.isEmpty())
                assertEquals(1, driver.scheduled.size)
                assertEquals(mutationId, h.f.db.read("SELECT lastMutationId FROM manual_tasks WHERE id=?",
                    task.id).single().single())
                assertArrayEquals(before, h.blob(task.id))
                assertEquals(committed, replay())
                assertArrayEquals(before, h.blob(task.id))

                // Once the original manual request is acknowledged, the exact old cancellation can drain.
                h.state = h.state.copy(tasks = h.state.tasks.copy(items = listOf(committed),
                    available = true, busy = false, loading = false, pending = null, error = null))
                h.controller.retry()
                h.pump()
                assertEquals(previousToken, driver.cancelled.single().token)
                assertEquals(1, driver.scheduled.size)
                val drained = h.actual.snapshots().single()
                assertEquals(committed.version + 1, drained.version)
                assertEquals(TaskReminderPhase.REVOKED, drained.reminder!!.phase)
                assertTrue(drained.reminder!!.commands.isEmpty())
                assertNull(h.state.reminders.error)
            }
        }
    }

    @Test(timeout = 30_000)
    fun fullCancellationOutboxRejectsWithoutWritingThenRecoversOnlyThroughFreshExplicitConfirmation() {
        val driver = object : TaskReminderDriver {
            var connected = false
            val cancelled = mutableListOf<TaskReminderCommand>()
            val scheduled = mutableListOf<TaskReminderCommand>()
            override fun availability() = if (connected) TaskReminderAvailability(true,
                TaskReminderPermission.GRANTED, TaskReminderPermission.GRANTED) else TaskReminderAvailability()
            override fun schedule(command: TaskReminderCommand): TaskReminderPhase {
                scheduled += command
                return TaskReminderPhase.SCHEDULED
            }
            override fun cancel(command: TaskReminderCommand): TaskReminderPhase {
                if (!connected) return TaskReminderPhase.PLATFORM_UNAVAILABLE
                cancelled += command
                return TaskReminderPhase.REVOKED
            }
        }
        Harness(driver).use { h ->
            var current = h.task("one")
            repeat(TaskReminders.MAX_COMMANDS) { index ->
                current = h.actual.changeReminder(TaskReminderMutation(current, TaskReminderMode.APPROXIMATE,
                    NOW, "fill-cancellation-" + index))
            }
            val full = h.actual.changeReminder(TaskReminderMutation(current, null, NOW, "revoke-full-outbox"))
            val originalReminder = checkNotNull(full.reminder)
            assertEquals(TaskReminderPhase.REVOKED, originalReminder.phase)
            assertEquals(TaskReminders.MAX_COMMANDS, originalReminder.commands.size)
            assertTrue(originalReminder.commands.all { it.kind == TaskReminderCommandKind.CANCEL })
            val oldTokens = originalReminder.commands.map { it.token }
            val fullBytes = h.blob(full.id)
            assertThrows(TaskReminderLimit::class.java) {
                h.actual.changeReminder(TaskReminderMutation(full, TaskReminderMode.APPROXIMATE,
                    NOW, "prove-full-outbox-rejection"))
            }
            assertArrayEquals(fullBytes, h.blob(full.id))

            h.initialize() // The unavailable service cannot acknowledge any of the old cancellations.
            assertArrayEquals(fullBytes, h.blob(full.id))
            val editor = h.begin(full)
            h.controller.confirm(editor.session)
            h.pump()
            assertFalse(h.state.reminders.busy)
            assertNull(h.state.reminders.pending)
            assertSame(editor, h.state.reminders.editor)
            assertNotNull(h.state.reminders.error)
            assertNull(h.state.reminders.notice)
            assertEquals(1, h.requests.size)
            assertArrayEquals(fullBytes, h.blob(full.id))
            assertTrue(driver.cancelled.isEmpty())
            assertTrue(driver.scheduled.isEmpty())

            driver.connected = true
            h.controller.retry()
            h.pump()
            val partiallyDrained = h.actual.snapshots().single()
            val remaining = checkNotNull(partiallyDrained.reminder).commands
            assertEquals(16, driver.cancelled.size)
            assertEquals(oldTokens.take(16), driver.cancelled.map { it.token })
            assertEquals(oldTokens.drop(16), remaining.map { it.token })
            assertEquals(full.version + 16, partiallyDrained.version)
            assertSame(editor, h.state.reminders.editor)

            val partialBytes = h.blob(full.id)
            h.controller.confirm(editor.session)
            assertTrue(h.state.reminders.conflict)
            assertNull(h.state.reminders.pending)
            assertTrue(h.queued.isEmpty())
            assertEquals(1, h.requests.size)
            assertArrayEquals(partialBytes, h.blob(full.id))

            h.controller.close(editor.session)
            val fresh = h.begin(partiallyDrained)
            assertNotEquals(editor.session, fresh.session)
            var confirmedBeforeDispatch: ManualTaskSnapshot? = null
            h.afterWrite = { confirmedBeforeDispatch = h.actual.snapshots().single() }
            h.controller.confirm(fresh.session)
            h.pump()
            val confirmed = checkNotNull(confirmedBeforeDispatch)
            val reminder = checkNotNull(confirmed.reminder)
            val newCommand = reminder.commands.single { it.kind == TaskReminderCommandKind.SCHEDULE }
            assertEquals(remaining, reminder.commands.filter { it.kind == TaskReminderCommandKind.CANCEL })
            assertEquals(originalReminder.id, reminder.id)
            assertEquals(originalReminder.scheduleVersion + 1, reminder.scheduleVersion)
            assertEquals(reminder.scheduleVersion, newCommand.token.scheduleVersion)
            assertEquals(2, h.requests.size)
            assertNotEquals(h.requests.first().mutationId, h.requests.last().mutationId)
            assertEquals(partiallyDrained, h.requests.last().task)
            assertEquals(oldTokens.take(31), driver.cancelled.map { it.token })
            assertTrue(driver.cancelled.none { it.token == newCommand.token })
            assertEquals(listOf(newCommand), driver.scheduled)
            // The second bounded drain schedules first, then retires 15 old cancels within its 16-command budget.
            val afterFreshConfirmation = h.actual.snapshots().single()
            val activeReminder = checkNotNull(afterFreshConfirmation.reminder)
            assertEquals(confirmed.version + 16, afterFreshConfirmation.version)
            assertEquals(TaskReminderPhase.SCHEDULED, activeReminder.phase)
            assertEquals(reminder.id, activeReminder.id)
            assertEquals(reminder.scheduleVersion, activeReminder.scheduleVersion)
            assertEquals(listOf(remaining.last()), activeReminder.commands)
            assertEquals(oldTokens.last(), activeReminder.commands.single().token)
            assertEquals(TaskReminderCommandKind.CANCEL, activeReminder.commands.single().kind)

            h.controller.retry()
            h.pump()
            val scheduled = h.actual.snapshots().single()
            assertEquals(listOf(newCommand), driver.scheduled)
            assertEquals(oldTokens, driver.cancelled.map { it.token })
            assertTrue(driver.cancelled.none { it.token == newCommand.token })
            assertEquals(afterFreshConfirmation.version + 1, scheduled.version)
            assertEquals(TaskReminderPhase.SCHEDULED, scheduled.reminder!!.phase)
            assertEquals(reminder.scheduleVersion, scheduled.reminder!!.scheduleVersion)
            assertTrue(scheduled.reminder!!.commands.isEmpty())
            assertNull(h.state.reminders.pending)
            assertNull(h.state.reminders.editor)
            assertNull(h.state.reminders.error)
            h.assertFreshReader(scheduled)
        }
    }

    private class FakeDriver(private val available: TaskReminderAvailability) : TaskReminderDriver {
        val scheduled = mutableListOf<TaskReminderCommand>()
        val cancelled = mutableListOf<TaskReminderCommand>()
        override fun availability() = available
        override fun schedule(command: TaskReminderCommand): TaskReminderPhase {
            scheduled += command
            return TaskReminderPhase.SCHEDULED
        }
        override fun cancel(command: TaskReminderCommand): TaskReminderPhase {
            cancelled += command
            return TaskReminderPhase.REVOKED
        }
    }

    private class Harness(driver: TaskReminderDriver? = null) : AutoCloseable {
        val f = LocalCandidateFixture()
        val actual = SqlManualTaskRepository(f.gateway, LocalCandidateFixture.KEY)
        val proposals = SqlTaskProposalRepository(f.gateway, LocalCandidateFixture.KEY, actual)
        val requests = mutableListOf<TaskReminderMutation>()
        val queued = ArrayDeque<suspend (EntryRepository) -> Unit>()
        var failReadback = false
        var afterWrite: (() -> Unit)? = null
        var state = LocalRecordUiState(ready = true, opening = false, text = "尚未提交合成输入")
        private val taskPort = object : ManualTaskRepository by actual {
            override fun snapshots(): List<ManualTaskSnapshot> {
                if (failReadback) throw ManualTaskStorageFailure()
                return actual.snapshots()
            }
        }
        private val reminderPort = object : TaskReminderRepository by actual {
            override fun changeReminder(request: TaskReminderMutation): ManualTaskSnapshot {
                requests += request
                return actual.changeReminder(request).also { afterWrite?.invoke() }
            }
        }
        val store = object : EntryRepository {
            override val manualTasks = taskPort
            override val reminders = reminderPort
            override val taskProposals = proposals
            override suspend fun readDraft(): TextDraft? = error("Reminder actions must not read capture input")
            override suspend fun updateDraft(text: String): TextDraft = error("Reminder actions must not write capture input")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry =
                error("Reminder actions must not save records")
            override suspend fun entries(): List<StoredEntry> = error("Reminder actions must not rewrite source records")
            override suspend fun revisions(entryId: String): List<OriginalRevision> = error("Outside reminder test")
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry =
                error("Reminder actions must not revise sources")
        }
        val controller = if (driver == null) TaskReminderController({ state }, { state = it(state) },
            { queued.addLast(it); true }, CLOCK)
        else TaskReminderController({ state }, { state = it(state) }, { queued.addLast(it); true }, CLOCK, driver)

        fun task(id: String, schedule: ManualTaskSchedule? = TIMED) = actual.create(
            ManualTaskCreate(id, "同名合成私密待办🙂", 55, "UTC", "create-" + id, schedule))

        fun draft(): TaskProposal {
            val source = SqlLocalProcessingRepository(f.gateway).read(f.source)
            val binding = TaskSourceBinding(source.source, source.sourceHash, source.generation, 0, 5)
            return proposals.write(TaskProposalWrite("unconfirmed", 0, binding, "合成尚待确认草稿",
                ManualTaskDateInput("2026-"), 50, "UTC", 50, "save-unconfirmed"))
        }

        fun initialize() {
            state = state.copy(tasks = ManualTasksUiState(supported = true, available = true, items = actual.snapshots()))
            runBlocking { controller.initialize(store) }
        }

        fun begin(task: ManualTaskSnapshot): TaskReminderEditor {
            controller.begin(task.id, task.version)
            return checkNotNull(state.reminders.editor)
        }

        fun pump() = runBlocking {
            var remaining = 16
            while (queued.isNotEmpty()) {
                check(remaining-- > 0) { "Reminder controller unexpectedly loops its foreground queue" }
                queued.removeFirst()(store)
            }
        }

        fun blob(id: String) = f.db.read("SELECT ciphertext FROM manual_tasks WHERE id=?", id).single().single() as ByteArray
        fun unrelatedRows() = f.snapshotOld().filterNot { it.startsWith("manual_tasks:") }

        fun assertFreshReader(expected: ManualTaskSnapshot) {
            f.second().use { db ->
                val reader = SqlManualTaskRepository(db, LocalCandidateFixture.KEY)
                try { assertEquals(expected, reader.snapshots().single { it.id == expected.id }) }
                finally { reader.wipeKey() }
            }
        }

        override fun close() {
            f.db.beforeCommit = null
            f.db.afterSql = null
            controller.dispose()
            proposals.wipeKey()
            actual.wipeKey()
            f.close()
        }
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-16T13:00:00Z"), ZoneOffset.UTC)
        val NOW: Long = CLOCK.millis()
        val TIMED: ManualTaskSchedule = checkNotNull(ManualTaskDateInput("2042-11-19", "09:30", "Asia/Shanghai").parse())
    }
}
