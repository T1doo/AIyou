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

/** Production controller and encrypted SQL, with explicit foreground queue ownership. */
class ManualTaskTitleControllerTest {
    @Test(timeout = 15_000)
    fun editingPreparingAndBackingOutNeverWriteOrReplaceTheOriginalInput() = Harness().use { h ->
        val task = h.task("one")
        h.initialize()
        val before = h.f.blob(task.id)
        val unrelated = h.f.legacy()
        val editor = h.begin(task)
        h.controller.editTitle(editor.session, "　更正后的合成待办🙂　")
        h.controller.prepareTitle(editor.session)
        val prepared = checkNotNull(h.state.tasks.titleEditor)
        val request = checkNotNull(prepared.confirmation)
        assertEquals("更正后的合成待办🙂", request.title)
        assertEquals(task.id, request.id)
        assertEquals(task.version, request.expectedVersion)
        assertEquals("　更正后的合成待办🙂　", prepared.input)
        assertTrue(h.requests.isEmpty())
        assertTrue(h.queued.isEmpty())
        assertNull(h.state.tasks.pending)
        assertArrayEquals(before, h.f.blob(task.id))

        h.controller.cancelTitleConfirmation(editor.session, request)
        assertNull(h.state.tasks.titleEditor!!.confirmation)
        assertEquals(prepared.input, h.state.tasks.titleEditor!!.input)
        h.controller.closeTitle(editor.session)
        h.controller.saveTitle(editor.session, request)
        assertNull(h.state.tasks.titleEditor)
        assertTrue(h.requests.isEmpty())
        assertTrue(h.queued.isEmpty())
        assertArrayEquals(before, h.f.blob(task.id))
        assertEquals(unrelated, h.f.legacy())
        assertEquals(CAPTURE, h.state.text)
    }

    @Test(timeout = 15_000)
    fun invalidAndUnchangedTitlesCannotPrepareAWriteAndUnicodeNormalizationIsExplicit() = Harness().use { h ->
        val task = h.task("one")
        h.initialize()
        val editor = h.begin(task)
        val before = h.f.blob(task.id)
        for (input in listOf("", "　", "\u200b", "\ud800", "合成\n控制", "🙂".repeat(241), "　" + task.title + "　")) {
            h.controller.editTitle(editor.session, input)
            h.controller.prepareTitle(editor.session)
            assertNull(h.state.tasks.titleEditor!!.confirmation)
            assertTrue(h.requests.isEmpty())
            assertTrue(h.queued.isEmpty())
            assertNotNull(h.state.tasks.error ?: h.state.tasks.notice)
            assertArrayEquals(before, h.f.blob(task.id))
        }
        val literal = "　更正🙂e\u0301  明天3点　"
        h.controller.editTitle(editor.session, literal)
        h.controller.prepareTitle(editor.session)
        val request = checkNotNull(h.state.tasks.titleEditor!!.confirmation)
        assertEquals("更正🙂e\u0301  明天3点", request.title)
        assertEquals(literal, h.state.tasks.titleEditor!!.input)
        assertFalse(request.toString().contains("更正"))
        h.controller.saveTitle(editor.session, request)
        h.pump()
        assertEquals(request.title, h.actual.snapshots().single().title)
        assertNull(h.state.tasks.titleEditor)
        assertEquals(CAPTURE, h.state.text)
    }

    @Test(timeout = 15_000)
    fun confirmedCorrectionChangesOnlyTitleVersionAndTimeAndPreservesTheReminderGeneration() = Harness().use { h ->
        var task = h.task("one")
        task = h.actual.changeReminder(TaskReminderMutation(task, TaskReminderMode.EXACT, NOW, "first-reminder"))
        val command = checkNotNull(task.reminder).commands.single()
        assertTrue(h.actual.acknowledgeReminder(command, TaskReminderPhase.SCHEDULED, NOW))
        task = h.actual.snapshots().single()
        h.initialize()
        val old = h.actual.snapshots().single()
        val reminder = checkNotNull(old.reminder)
        val unrelated = h.f.legacy()
        val editor = h.begin(old)
        val request = h.prepare(editor, "明确更正后的合成私密标题")
        h.controller.saveTitle(editor.session, request)
        assertTrue(h.state.tasks.busy)
        assertNull(h.state.tasks.notice)
        assertTrue(h.requests.isEmpty())
        h.pump()

        val saved = h.actual.snapshots().single()
        assertEquals(old.copy(title = request.title, version = old.version + 1, updatedAtMillis = NOW), saved)
        assertEquals(reminder, saved.reminder)
        assertEquals(command.token, TaskReminderToken(saved.id, reminder.id, reminder.scheduleVersion))
        assertEquals(unrelated, h.f.legacy())
        assertEquals(1, h.requests.size)
        assertSame(request, h.requests.single())
        assertNull(h.state.tasks.pending)
        assertNull(h.state.tasks.titleEditor)
        assertNotNull(h.state.tasks.notice)
        h.assertFreshReader(saved)
    }

    @Test(timeout = 15_000)
    fun realRollbackRetainsTheFrozenRequestAndRetryWritesOneVersion() = Harness().use { h ->
        val task = h.task("one")
        h.initialize()
        val editor = h.begin(task)
        val request = h.prepare(editor)
        val before = h.f.blob(task.id)
        h.f.db.beforeCommit = { error("Synthetic title transaction rollback") }
        h.controller.saveTitle(editor.session, request)
        h.pump()
        h.f.db.beforeCommit = null
        h.assertUnknown(request, editor.session)
        assertArrayEquals(before, h.f.blob(task.id))
        h.controller.editTitle(editor.session, "不得替换未确认写入")
        h.controller.prepareTitle(editor.session)
        h.controller.saveTitle(editor.session, request)
        assertTrue(h.queued.isEmpty())
        assertSame(request, h.state.tasks.titleEditor!!.confirmation)
        h.controller.retry()
        h.pump()
        assertEquals(2, h.requests.size)
        assertTrue(h.requests.all { it === request })
        assertEquals(2L, h.actual.snapshots().single().version)
        assertEquals(request.title, h.actual.snapshots().single().title)
        assertNull(h.state.tasks.pending)
        assertNull(h.state.tasks.titleEditor)
    }

    @Test(timeout = 15_000)
    fun lostCommitAcknowledgementReplaysTheSameRequestWithoutAnotherVersionOrCiphertext() = Harness().use { h ->
        val task = h.task("one")
        h.initialize()
        val editor = h.begin(task)
        val request = h.prepare(editor)
        var lost = false
        h.f.db.afterSql = { sql -> if (sql == "COMMIT" && !lost) { lost = true; error("Synthetic lost title COMMIT acknowledgement") } }
        h.controller.saveTitle(editor.session, request)
        h.pump()
        h.f.db.afterSql = null
        assertTrue(lost)
        h.assertUnknown(request, editor.session)
        val saved = h.actual.snapshots().single()
        val committedBytes = h.f.blob(task.id)
        assertEquals(2L, saved.version)
        assertEquals(request.title, saved.title)
        h.controller.closeTitle(editor.session)
        h.controller.beginTitle(task.id, saved.version)
        assertEquals(editor.session, h.state.tasks.titleEditor!!.session)
        assertSame(request, h.state.tasks.titleEditor!!.confirmation)
        h.controller.retry()
        h.pump()
        assertEquals(2, h.requests.size)
        assertTrue(h.requests.all { it === request })
        assertEquals(saved, h.actual.snapshots().single())
        assertArrayEquals(committedBytes, h.f.blob(task.id))
        assertNull(h.state.tasks.pending)
        assertNotNull(h.state.tasks.notice)
        h.assertFreshReader(saved)
    }

    @Test(timeout = 15_000)
    fun failedReadbackAndCapabilityRecoveryKeepTheOriginalCorrectionIdentity() = Harness().use { h ->
        val task = h.task("one")
        h.initialize()
        val editor = h.begin(task)
        val request = h.prepare(editor)
        h.failReadback = true
        h.controller.saveTitle(editor.session, request)
        h.pump()
        h.assertUnknown(request, editor.session)
        val committed = h.f.blob(task.id)
        runBlocking { h.controller.initialize(h.store) }
        h.pump()
        h.assertUnknown(request, editor.session)
        assertArrayEquals(committed, h.f.blob(task.id))
        h.failReadback = false
        runBlocking { h.controller.initialize(h.store) }
        h.pump()
        assertEquals(3, h.requests.size)
        assertTrue(h.requests.all { it === request })
        assertArrayEquals(committed, h.f.blob(task.id))
        assertEquals(2L, h.actual.snapshots().single().version)
        assertNull(h.state.tasks.pending)
        assertNull(h.state.tasks.titleEditor)
        assertNotNull(h.state.tasks.notice)
    }

    @Test(timeout = 15_000)
    fun oldSameTitleSessionAndWithdrawnConfirmationCannotWriteTheCurrentTask() = Harness().use { h ->
        val first = h.task("one")
        val second = h.task("two")
        h.initialize()
        val old = h.begin(first)
        val oldRequest = h.prepare(old)
        h.controller.cancelTitleConfirmation(old.session, oldRequest)
        h.controller.saveTitle(old.session, oldRequest)
        assertTrue(h.queued.isEmpty())
        h.controller.closeTitle(old.session)
        val current = h.begin(second)
        val currentRequest = h.prepare(current, "第二条的明确更正")
        h.controller.editTitle(old.session, "迟到的旧输入")
        h.controller.prepareTitle(old.session)
        h.controller.cancelTitleConfirmation(old.session, oldRequest)
        h.controller.saveTitle(old.session, oldRequest)
        h.controller.closeTitle(old.session)
        assertEquals(current.session, h.state.tasks.titleEditor!!.session)
        assertSame(currentRequest, h.state.tasks.titleEditor!!.confirmation)
        assertTrue(h.requests.isEmpty())
        assertTrue(h.queued.isEmpty())
        h.controller.saveTitle(current.session, currentRequest)
        h.pump()
        assertEquals(second.id, h.requests.single().id)
        assertEquals(first, h.actual.snapshots().single { it.id == first.id })
        assertEquals(currentRequest.title, h.actual.snapshots().single { it.id == second.id }.title)
    }

    @Test(timeout = 20_000)
    fun externalCompletionOrReminderAcknowledgementConflictsWithoutDiscardingTheTypedCorrection() {
        for (acknowledge in listOf(false, true)) Harness().use { h ->
            var task = h.task("one")
            h.initialize()
            if (acknowledge) {
                task = h.actual.changeReminder(TaskReminderMutation(task, TaskReminderMode.EXACT, NOW, "outside-reminder"))
                h.controller.refresh(); h.pump()
            }
            val editor = h.begin(task)
            val input = "未覆盖外部变化的更正输入"
            val request = h.prepare(editor, input)
            if (acknowledge) assertTrue(h.actual.acknowledgeReminder(task.reminder!!.commands.single(), TaskReminderPhase.SCHEDULED, NOW))
            else h.actual.change(ManualTaskMutation(task.id, task.version, ManualTaskStatus.COMPLETED, NOW, "outside-completion"))
            val outside = h.actual.snapshots().single()
            val bytes = h.f.blob(task.id)
            h.controller.saveTitle(editor.session, request)
            h.pump()
            assertTrue(h.state.tasks.conflict)
            assertEquals(input, h.state.tasks.titleEditor!!.input)
            assertNull(h.state.tasks.notice)
            assertArrayEquals(bytes, h.f.blob(task.id))
            assertEquals(outside, h.actual.snapshots().single())
            h.controller.retry(); h.pump()
            val calls = h.requests.size
            h.controller.saveTitle(editor.session, request)
            h.pump()
            assertEquals(calls, h.requests.size)
            assertArrayEquals(bytes, h.f.blob(task.id))
            assertEquals(input, h.state.tasks.titleEditor!!.input)
        }
    }

    @Test(timeout = 15_000)
    fun creationDateAndReminderEditorsRemainMutuallyExclusiveAgainstOldCallbacks() = Harness().use { h ->
        val task = h.task("one")
        h.initialize()
        h.controller.begin()
        val create = checkNotNull(h.state.tasks.editor)
        h.controller.beginTitle(task.id, task.version)
        assertNull(h.state.tasks.titleEditor)
        h.controller.close(create.session)
        h.controller.beginDate(task.id, task.version, ManualTaskDateOperation.SET)
        val date = checkNotNull(h.state.tasks.dateEditor)
        h.controller.beginTitle(task.id, task.version)
        assertNull(h.state.tasks.titleEditor)
        h.controller.closeDate(date.session)
        h.reminders.begin(task.id, task.version)
        val reminder = checkNotNull(h.state.reminders.editor)
        h.controller.beginTitle(task.id, task.version)
        assertNull(h.state.tasks.titleEditor)
        h.reminders.close(reminder.session)

        val editor = h.begin(task)
        val request = h.prepare(editor)
        h.controller.begin()
        h.controller.create(create.session, "旧创建回调")
        h.controller.beginDate(task.id, task.version, ManualTaskDateOperation.SET)
        h.controller.saveDate(date.session, ManualTaskDateInput("2042-11-20", "09:30", "Asia/Shanghai"))
        h.controller.change(task.id, task.version, ManualTaskStatus.COMPLETED)
        h.reminders.begin(task.id, task.version)
        h.reminders.confirm(reminder.session)
        assertSame(request, h.state.tasks.titleEditor!!.confirmation)
        assertNull(h.state.tasks.editor)
        assertNull(h.state.tasks.dateEditor)
        assertNull(h.state.reminders.editor)
        assertTrue(h.requests.isEmpty())
        assertTrue(h.queued.isEmpty())
        assertEquals(task, h.actual.snapshots().single())
    }

    @Test(timeout = 20_000)
    fun unknownCommittedTitleCorrectionDefersReminderAcknowledgementsAndKeepsExactReplay() = Harness().use { h ->
        val task = h.task("one")
        h.initialize()
        val pendingReminder = h.actual.changeReminder(TaskReminderMutation(task, TaskReminderMode.EXACT, NOW, "pending-before-title"))
        h.controller.refresh(); h.pump()
        val originalCommand = pendingReminder.reminder!!.commands.single()
        val editor = h.begin(pendingReminder)
        val request = h.prepare(editor)
        h.failReadback = true
        h.controller.saveTitle(editor.session, request)
        h.pump()
        h.assertUnknown(request, editor.session)
        val committed = h.actual.snapshots().single()
        val bytes = h.f.blob(task.id)
        assertEquals(pendingReminder.reminder, committed.reminder)
        assertEquals(0, h.acknowledgements)
        h.reminders.retry()
        runBlocking { h.reminders.initialize(h.store) }
        h.pump()
        assertEquals(0, h.acknowledgements)
        assertArrayEquals(bytes, h.f.blob(task.id))
        assertSame(request, (h.state.tasks.pending as ManualTaskIntent.Rename).request)
        assertEquals(request.mutationId, h.f.db.read("SELECT lastMutationId FROM manual_tasks WHERE id=?", task.id).single().single())
        h.failReadback = false
        h.controller.retry(); h.pump()
        assertEquals(2, h.requests.size)
        assertTrue(h.requests.all { it === request })
        assertArrayEquals(bytes, h.f.blob(task.id))
        h.reminders.retry(); h.pump()
        assertEquals(1, h.acknowledgements)
        assertEquals(originalCommand.token.scheduleVersion, h.actual.snapshots().single().reminder!!.scheduleVersion)
        assertEquals(TaskReminderPhase.PLATFORM_UNAVAILABLE, h.actual.snapshots().single().reminder!!.phase)
        assertEquals(request.title, h.actual.snapshots().single().title)
    }

    @Test(timeout = 15_000)
    fun disposedOwnerCannotExecuteQueuedCorrectionOrAcceptRetiredEditorActions() = Harness().use { h ->
        val task = h.task("one")
        h.initialize()
        val editor = h.begin(task)
        val request = h.prepare(editor)
        val bytes = h.f.blob(task.id)
        h.controller.saveTitle(editor.session, request)
        h.controller.dispose()
        val frozen = h.state
        h.pump()
        h.controller.beginTitle(task.id, task.version)
        h.controller.editTitle(editor.session, "迟到输入")
        h.controller.prepareTitle(editor.session)
        h.controller.saveTitle(editor.session, request)
        h.controller.cancelTitleConfirmation(editor.session, request)
        h.controller.closeTitle(editor.session)
        h.controller.retry()
        assertSame(frozen, h.state)
        assertTrue(h.requests.isEmpty())
        assertTrue(h.queued.isEmpty())
        assertArrayEquals(bytes, h.f.blob(task.id))
    }

    private class Harness : AutoCloseable {
        val f = ManualTaskSqlTest.Fixture()
        val actual get() = f.store
        val requests = mutableListOf<ManualTaskRename>()
        val queued = ArrayDeque<suspend (EntryRepository) -> Unit>()
        var failReadback = false
        var acknowledgements = 0
        var state = LocalRecordUiState(ready = true, opening = false, text = CAPTURE)
        private val taskPort = object : ManualTaskRepository {
            override fun snapshots(): List<ManualTaskSnapshot> {
                if (failReadback) throw ManualTaskStorageFailure()
                return actual.snapshots()
            }
            override fun create(request: ManualTaskCreate) = actual.create(request)
            override fun change(request: ManualTaskMutation) = actual.change(request)
            override fun reschedule(request: ManualTaskScheduleMutation) = actual.reschedule(request)
            override fun rename(request: ManualTaskRename): ManualTaskSnapshot {
                requests += request
                return actual.rename(request)
            }
        }
        private val reminderPort = object : TaskReminderRepository {
            override fun changeReminder(request: TaskReminderMutation) = actual.changeReminder(request)
            override fun reminderCommands() = actual.reminderCommands()
            override fun isReminderCommandCurrent(command: TaskReminderCommand) = actual.isReminderCommandCurrent(command)
            override fun acknowledgeReminder(command: TaskReminderCommand, result: TaskReminderPhase, atMillis: Long): Boolean {
                acknowledgements++
                return actual.acknowledgeReminder(command, result, atMillis)
            }
            override fun acceptReminderTrigger(token: TaskReminderToken, nowMillis: Long, availability: TaskReminderAvailability) =
                error("No native reminder trigger in a title correction test")
        }
        val store = object : EntryRepository {
            override val manualTasks = taskPort
            override val reminders = reminderPort
            override suspend fun readDraft(): TextDraft? = error("Title correction must not read original input")
            override suspend fun updateDraft(text: String): TextDraft = error("Title correction must not overwrite original input")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Title correction must not save original input")
            override suspend fun entries(): List<StoredEntry> = error("Title correction must not rewrite source records")
            override suspend fun revisions(entryId: String): List<OriginalRevision> = error("Outside task title correction")
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Title correction must not revise originals")
        }
        val controller = ManualTaskController({ state }, { state = it(state) }, { queued.addLast(it); true }, CLOCK) { "UTC" }
        private val controlledReminderDriver=object:TaskReminderDriver {
            override fun availability()=TaskReminderAvailability()
            override fun schedule(command:TaskReminderCommand)=TaskReminderPhase.PLATFORM_UNAVAILABLE
            override fun cancel(command:TaskReminderCommand)=TaskReminderPhase.PLATFORM_UNAVAILABLE
        }
        val reminders:TaskReminderController=TaskReminderController({ state }, { state = it(state) }, { queued.addLast(it); true }, CLOCK,requestRuntime={
            if(actual.reminderCommands().isNotEmpty())queued.addLast{entry->
                TaskReminderDispatcher(checkNotNull(entry.reminders),controlledReminderDriver,CLOCK::millis).drain()
                reminders.refreshAfterRuntime(entry)
            }
        })
        fun task(id: String) = actual.create(ManualTaskCreate(id, "同名合成待办🙂", 55, "UTC", "create-$id", TIMED))
        fun initialize() = runBlocking { controller.initialize(store); reminders.initialize(store);pump() }
        fun begin(task: ManualTaskSnapshot): ManualTaskTitleEditor {
            controller.beginTitle(task.id, task.version)
            return checkNotNull(state.tasks.titleEditor)
        }
        fun prepare(editor: ManualTaskTitleEditor, input: String = "明确更正后的合成待办"): ManualTaskRename {
            controller.editTitle(editor.session, input)
            controller.prepareTitle(editor.session)
            return checkNotNull(state.tasks.titleEditor!!.confirmation)
        }
        fun pump() = runBlocking {
            var budget = 16
            while (queued.isNotEmpty()) {
                check(budget-- > 0) { "Title correction unexpectedly loops the foreground queue" }
                queued.removeFirst()(store)
            }
        }
        fun assertUnknown(request: ManualTaskRename, session: Long) {
            assertFalse(state.tasks.busy)
            assertNotNull(state.tasks.error)
            assertNull(state.tasks.notice)
            assertEquals(session, state.tasks.titleEditor!!.session)
            assertSame(request, state.tasks.titleEditor!!.confirmation)
            assertSame(request, (state.tasks.pending as ManualTaskIntent.Rename).request)
        }
        fun assertFreshReader(expected: ManualTaskSnapshot) = f.second().use { db ->
            val reader = SqlManualTaskRepository(db, ManualTaskSqlTest.KEY)
            try { assertEquals(expected, reader.snapshots().single { it.id == expected.id }) }
            finally { reader.wipeKey() }
        }
        override fun close() { controller.dispose(); reminders.dispose(); f.close() }
    }

    private companion object {
        const val CAPTURE = "合成独立输入，不从待办编辑覆写"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-16T15:00:00Z"), ZoneOffset.UTC)
        val NOW = CLOCK.millis()
        val TIMED = checkNotNull(ManualTaskDateInput("2042-11-19", "09:30", "Asia/Shanghai").parse())
    }
}
