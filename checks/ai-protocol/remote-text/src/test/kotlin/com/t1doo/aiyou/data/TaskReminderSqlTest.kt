package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

/** Product SQLite transactions and AEAD, with synthetic data; no device scheduling or notifications. */
class TaskReminderSqlTest {
    @Test fun confirmationIsExplicitCompleteSnapshotCasAndNeedsFutureTimedDate(): Unit = Fixture().use { f ->
        val initial = f.create("one")
        assertNull(initial.reminder); assertTrue(f.tasks.reminderCommands().isEmpty())
        val before = f.blob("one")
        for (bad in listOf(initial.copy(title = "另一个合成标题"), initial.copy(version = 2), initial.copy(updatedAtMillis = 56)))
            assertThrows(ManualTaskConflict::class.java) { f.tasks.changeReminder(mutation(bad)) }
        assertArrayEquals(before, f.blob("one"))
        val undated = f.create("undated", due = null)
        val dateOnly = f.create("date-only", due = ManualTaskDateSqlTest.date("2042-11-19"))
        for (bad in listOf(undated, dateOnly))
            assertThrows(ManualTaskConflict::class.java) { f.tasks.changeReminder(mutation(bad)) }
        assertThrows(ManualTaskConflict::class.java) {
            f.tasks.changeReminder(mutation(initial, at = initial.schedule!!.instant()!!.toEpochMilli()))
        }
        val saved = f.tasks.changeReminder(mutation(initial))
        assertEquals(2L, saved.version); assertEquals(1L, saved.reminder!!.scheduleVersion)
        assertEquals(TaskReminders.id(initial.id), saved.reminder!!.id)
        assertEquals(TaskReminderPhase.PENDING, saved.reminder!!.phase)
        assertEquals(TaskReminderMode.EXACT, saved.reminder!!.mode)
        assertEquals(1, f.tasks.reminderCommands().size)
    }

    @Test fun v5UserReceiptAndOutboxSurviveColdReopenWithoutTouchingOtherTables(): Unit = Fixture().use { f ->
        val unrelated = f.local.snapshotOld().filterNot { it.startsWith("manual_tasks:") }
        val receipt = SourceTaskReceipt(TaskSourceBinding(f.local.source, OriginalExports.hash(f.local.original), 1, 0, 2), "a".repeat(64))
        val initial = f.local.db.transaction { sql -> f.tasks.createWithin(sql, create("one"), receipt) }
        assertEquals(5.toByte(), f.blob("one")[0])
        val saved = f.tasks.changeReminder(mutation(initial))
        assertEquals(5.toByte(), f.blob("one")[0]); val bytes = f.blob("one")
        val commands = f.tasks.reminderCommands()
        assertThrows(UnsupportedOperationException::class.java) { (commands as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (saved.reminder!!.commands as MutableList).clear() }
        f.reopen()
        assertEquals(saved, f.current("one")); assertEquals(commands, f.tasks.reminderCommands()); assertArrayEquals(bytes, f.blob("one"))
        assertEquals(receipt, f.current("one").sourceReceipt)
        assertEquals(unrelated, f.local.snapshotOld().filterNot { it.startsWith("manual_tasks:") })
        for (marker in listOf("合成私密提醒🙂", "2042-11-19", "EXACT", receipt.requestHash)) {
            Files.list(f.local.directory).use { paths -> paths.filter { it.fileName.toString().startsWith("records") }.forEach {
                assertFalse("Reminder contents must stay encrypted", contains(Files.readAllBytes(it), marker.toByteArray(Charsets.UTF_8)))
            } }
        }
    }

    @Test fun lostCommitAckReplaysExactlyAndSameMutationCannotChangeAnyRequestField(): Unit = Fixture().use { f ->
        val old = f.create("one"); val request = mutation(old)
        var lost = false
        f.local.db.afterSql = { if (it == "COMMIT" && !lost) { lost = true; error("Synthetic lost commit acknowledgement") } }
        try { assertThrows(ManualTaskStorageFailure::class.java) { f.tasks.changeReminder(request) } }
        finally { f.local.db.afterSql = null }
        assertTrue(lost); f.reopen(); val bytes = f.blob("one"); val saved = f.current("one")
        assertEquals(saved, f.tasks.changeReminder(request)); assertArrayEquals(bytes, f.blob("one"))
        val other = f.tasks.create(ManualTaskCreate("other", "另一个明确目标", 55, "UTC", "create-other", DUE))
        for (bad in listOf(
            mutation(old.copy(title = "同ID但不同标题")), mutation(old.copy(creationTimeZone = "Asia/Shanghai")),
            mutation(old.copy(updatedAtMillis = 56)), mutation(old.copy(schedule = LATER)),
            mutation(old, TaskReminderMode.APPROXIMATE), mutation(old, null), mutation(old, at = 100),
            mutation(old.copy(id = other.id)))) {
            assertThrows(ManualTaskConflict::class.java) { f.tasks.changeReminder(bad) }
        }
        assertArrayEquals(bytes, f.blob("one")); assertEquals(1L, f.current("other").version)
        assertEquals(1L, saved.reminder!!.scheduleVersion)
    }

    @Test fun exactReplayBindsOldPhaseAndOutboxRatherThanOnlyVersionAndDate(): Unit = Fixture().use { f ->
        val initial = f.create("one")
        val first = f.tasks.changeReminder(mutation(initial))
        val command = first.reminder!!.commands.single()
        assertTrue(f.tasks.acknowledgeReminder(command, TaskReminderPhase.SCHEDULED, 100))
        val scheduled = f.current("one")
        val request = mutation(scheduled, at = 101, id = "remind-again")
        val saved = f.tasks.changeReminder(request); val bytes = f.blob("one")
        assertEquals(saved, f.tasks.changeReminder(request))
        val forgedOld = scheduled.copy(reminder = scheduled.reminder!!.copy(phase = TaskReminderPhase.FAILED))
        assertThrows(ManualTaskConflict::class.java) { f.tasks.changeReminder(mutation(forgedOld, at = 101, id = request.mutationId)) }
        assertArrayEquals(bytes, f.blob("one"))
    }

    @Test fun realSqlAbortAndReadbackFailureRollbackTaskAndEntireOutbox(): Unit = Fixture().use { f ->
        val initial = f.create("one"); val bytes = f.blob("one")
        f.local.db.write("CREATE TRIGGER synthetic_reminder_abort AFTER UPDATE ON manual_tasks BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertThrows(ManualTaskStorageFailure::class.java) { f.tasks.changeReminder(mutation(initial)) }
        assertArrayEquals(bytes, f.blob("one")); assertTrue(f.tasks.reminderCommands().isEmpty())
        f.local.db.write("DROP TRIGGER synthetic_reminder_abort")
        var updates = 0
        f.local.db.afterSql = { if (it.startsWith("UPDATE manual_tasks") && ++updates == 1) error("Synthetic readback boundary") }
        try { assertThrows(ManualTaskStorageFailure::class.java) { f.tasks.changeReminder(mutation(initial)) } }
        finally { f.local.db.afterSql = null }
        assertArrayEquals(bytes, f.blob("one")); assertEquals(initial, f.current("one"))
    }

    @Test fun independentConnectionsHaveOneReminderCasWinner(): Unit = Fixture().use { f ->
        val old = f.create("one"); val connection = f.local.second(); val other = SqlManualTaskRepository(connection, KEY)
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        try {
            val requests = listOf(mutation(old), mutation(old, TaskReminderMode.APPROXIMATE, id = "other-reminder"))
            val results = listOf(f.tasks, other).mapIndexed { index, store -> pool.submit<String> {
                go.await(); try { store.changeReminder(requests[index]); "saved" } catch (_: ManualTaskConflict) { "conflict" }
            } }
            go.countDown(); assertEquals(listOf("conflict", "saved"), results.map { it.get(3, TimeUnit.SECONDS) }.sorted())
            assertEquals(2L, f.current("one").version); assertEquals(1L, f.current("one").reminder!!.scheduleVersion)
            assertEquals(1, f.tasks.reminderCommands().size)
        } finally { go.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); other.wipeKey(); connection.close() }
    }

    @Test fun cancelDateSetPostponeClearCompleteAndCancelAllInvalidateOldAckAndTrigger(): Unit = Fixture().use { f ->
        for (action in listOf("REVOKE", "SET", "POSTPONE", "CLEAR", "COMPLETE", "CANCEL")) {
            val initial = f.create(action); val confirmed = f.tasks.changeReminder(mutation(initial, id = "confirm-" + action))
            val command = confirmed.reminder!!.commands.single()
            val next = when (action) {
                "REVOKE" -> f.tasks.changeReminder(mutation(confirmed, null, 100, "revoke"))
                "SET", "POSTPONE", "CLEAR" -> f.tasks.reschedule(ManualTaskScheduleMutation(action, confirmed.version,
                    ManualTaskDateOperation.valueOf(action), if (action == "CLEAR") null else LATER, 100, "date-" + action))
                else -> f.tasks.change(ManualTaskMutation(action, confirmed.version,
                    if (action == "COMPLETE") ManualTaskStatus.COMPLETED else ManualTaskStatus.CANCELLED, 100, "status-" + action))
            }
            assertEquals(2L, next.reminder!!.scheduleVersion); assertEquals(TaskReminderPhase.REVOKED, next.reminder!!.phase)
            assertTrue(next.reminder!!.commands.all { it.kind == TaskReminderCommandKind.CANCEL })
            val bytes = f.blob(action)
            assertFalse(f.tasks.isReminderCommandCurrent(command))
            assertFalse(f.tasks.acknowledgeReminder(command, TaskReminderPhase.SCHEDULED, 101))
            assertFalse(f.tasks.acceptReminderTrigger(command.token, DUE_MILLIS, AVAILABLE))
            assertArrayEquals(bytes, f.blob(action))
            assertEquals(next, f.current(action))
        }
    }

    @Test fun oldCancelAcknowledgementOnlyRetiresItsTokenAndNeverChangesNewGeneration(): Unit = Fixture().use { f ->
        val first = f.tasks.changeReminder(mutation(f.create("one")))
        val old = first.reminder!!.commands.single()
        assertTrue(f.tasks.acknowledgeReminder(old, TaskReminderPhase.SCHEDULED, 100))
        val edited = f.tasks.reschedule(ManualTaskScheduleMutation("one", 3, ManualTaskDateOperation.SET, LATER, 101, "edit"))
        val new = f.tasks.changeReminder(mutation(edited, TaskReminderMode.APPROXIMATE, 102, "confirm-new"))
        val cancellation = new.reminder!!.commands.single { it.kind == TaskReminderCommandKind.CANCEL }
        val schedule = new.reminder!!.commands.single { it.kind == TaskReminderCommandKind.SCHEDULE }
        assertEquals(old.token, cancellation.token); assertNotEquals(cancellation.token, schedule.token)
        f.reopen(); assertEquals(2, f.tasks.reminderCommands().size)
        assertTrue(f.tasks.acknowledgeReminder(schedule, TaskReminderPhase.SCHEDULED, 103))
        val before = f.current("one")
        assertFalse(f.tasks.acknowledgeReminder(cancellation, TaskReminderPhase.FAILED, 104))
        assertTrue(f.tasks.isReminderCommandCurrent(cancellation))
        assertTrue(f.tasks.acknowledgeReminder(cancellation, TaskReminderPhase.REVOKED, 104))
        val after = f.current("one")
        assertEquals(before.reminder!!.scheduleVersion, after.reminder!!.scheduleVersion)
        assertEquals(TaskReminderPhase.SCHEDULED, after.reminder!!.phase); assertTrue(after.reminder!!.commands.isEmpty())
        assertFalse(f.tasks.acceptReminderTrigger(old.token, DUE_MILLIS, AVAILABLE))
        assertTrue(f.tasks.acceptReminderTrigger(schedule.token, LATER.instant()!!.toEpochMilli(), AVAILABLE))
    }

    @Test fun permissionOutcomesAreDurableAndNeverSilentlyChangeExactModeOrRetry(): Unit = Fixture().use { f ->
        for (result in listOf(TaskReminderPhase.PLATFORM_UNAVAILABLE, TaskReminderPhase.NOTIFICATION_REQUIRED,
            TaskReminderPhase.NOTIFICATION_DENIED, TaskReminderPhase.CHANNEL_DISABLED, TaskReminderPhase.EXACT_REQUIRED,
            TaskReminderPhase.EXACT_DENIED, TaskReminderPhase.FAILED)) {
            val id = result.name; val confirmed = f.tasks.changeReminder(mutation(f.create(id), id = "confirm-" + id))
            val command = confirmed.reminder!!.commands.single()
            assertTrue(f.tasks.acknowledgeReminder(command, result, 100)); assertFalse(f.tasks.acknowledgeReminder(command, result, 100))
            val saved = f.current(id)
            assertEquals(result, saved.reminder!!.phase); assertEquals(TaskReminderMode.EXACT, saved.reminder!!.mode)
            assertTrue(saved.reminder!!.commands.isEmpty()); assertFalse(f.tasks.acceptReminderTrigger(command.token, DUE_MILLIS, AVAILABLE))
        }
        f.reopen(); assertTrue(f.tasks.reminderCommands().isEmpty())
        val denied = f.current(TaskReminderPhase.EXACT_DENIED.name)
        val approximate = f.tasks.changeReminder(mutation(denied, TaskReminderMode.APPROXIMATE, 101, "explicit-approximate"))
        assertEquals(TaskReminderMode.APPROXIMATE, approximate.reminder!!.mode)
        assertEquals(TaskReminderPhase.PENDING, approximate.reminder!!.phase)
        assertEquals(2L, approximate.reminder!!.scheduleVersion)
    }

    @Test fun triggerRechecksTimeAndPermissionsAndConsumesOnceAcrossColdReopen(): Unit = Fixture().use { f ->
        val confirmed = f.tasks.changeReminder(mutation(f.create("one")))
        val command = confirmed.reminder!!.commands.single()
        assertFalse(f.tasks.acceptReminderTrigger(command.token, DUE_MILLIS, AVAILABLE))
        assertTrue(f.tasks.acknowledgeReminder(command, TaskReminderPhase.SCHEDULED, 100))
        val bytes = f.blob("one")
        for (availability in listOf(TaskReminderAvailability(), AVAILABLE.copy(notifications = TaskReminderPermission.DENIED),
            AVAILABLE.copy(channelEnabled = false), AVAILABLE.copy(exactAlarms = TaskReminderPermission.DENIED))) {
            assertFalse(f.tasks.acceptReminderTrigger(command.token, DUE_MILLIS, availability))
        }
        assertFalse(f.tasks.acceptReminderTrigger(command.token, DUE_MILLIS - 1, AVAILABLE))
        assertFalse(f.tasks.acceptReminderTrigger(command.token.copy(scheduleVersion = 2), DUE_MILLIS, AVAILABLE))
        assertArrayEquals(bytes, f.blob("one"))
        assertTrue(f.tasks.acceptReminderTrigger(command.token, DUE_MILLIS, AVAILABLE))
        assertEquals(TaskReminderPhase.TRIGGER_ACCEPTED, f.current("one").reminder!!.phase)
        val accepted = f.blob("one"); f.reopen()
        assertFalse(f.tasks.acceptReminderTrigger(command.token, DUE_MILLIS + 1, AVAILABLE))
        assertArrayEquals(accepted, f.blob("one")); assertEquals(4L, f.current("one").version)
    }

    @Test fun deletedRowAndTerminalTasksCannotBeResurrectedByCommandsOrTriggers(): Unit = Fixture().use { f ->
        val confirmed = f.tasks.changeReminder(mutation(f.create("one"))); val command = confirmed.reminder!!.commands.single()
        assertTrue(f.tasks.acknowledgeReminder(command, TaskReminderPhase.SCHEDULED, 100))
        f.local.db.write("DELETE FROM manual_tasks WHERE id='one'")
        assertFalse(f.tasks.acceptReminderTrigger(command.token, DUE_MILLIS, AVAILABLE))
        assertFalse(f.tasks.acknowledgeReminder(command, TaskReminderPhase.SCHEDULED, 101))
        assertFalse(f.tasks.isReminderCommandCurrent(command)); assertTrue(f.tasks.snapshots().isEmpty())
        assertEquals(0L, f.local.db.read("SELECT COUNT(*) FROM manual_tasks").single().single())
        val other = f.tasks.changeReminder(mutation(f.create("other"), id = "other-confirm"))
        val token = other.reminder!!.commands.single().token
        f.tasks.change(ManualTaskMutation("other", other.version, ManualTaskStatus.COMPLETED, 100, "complete"))
        assertFalse(f.tasks.acceptReminderTrigger(token, DUE_MILLIS, AVAILABLE))
    }

    @Test fun corruptedCiphertextNeverReturnsFalseSuccessOrLosesTheOriginalBytes(): Unit = Fixture().use { f ->
        val confirmed = f.tasks.changeReminder(mutation(f.create("one"))); val command = confirmed.reminder!!.commands.single()
        val corrupt = f.blob("one").also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        // Preserve the real monotone-version guard while injecting an authenticated-storage failure.
        assertEquals(1, f.local.db.write("UPDATE manual_tasks SET version=version+1,lastMutationId=?,ciphertext=? WHERE id='one'",
            "synthetic-corrupt", corrupt))
        for (read in listOf<() -> Unit>(
            { f.tasks.reminderCommands(); Unit }, { f.tasks.isReminderCommandCurrent(command); Unit },
            { f.tasks.acknowledgeReminder(command, TaskReminderPhase.SCHEDULED, 100); Unit },
            { f.tasks.acceptReminderTrigger(command.token, DUE_MILLIS, AVAILABLE); Unit })) {
            val error = assertThrows(ManualTaskStorageFailure::class.java) { read() }
            assertFalse(error.toString().contains("合成私密")); assertNull(error.cause)
        }
        f.reopen(); assertArrayEquals(corrupt, f.blob("one"))
    }

    @Test fun authenticatedV4StrictEofBoundsMetadataAndLegacyOperationRules(): Unit = Fixture().use { f ->
        val initial = f.create("one"); val confirmed = f.tasks.changeReminder(mutation(initial))
        val bytes = f.blob("one"); val cipher = ManualTaskCipher(KEY)
        try {
            assertThrows(IllegalArgumentException::class.java) { cipher.open("one", 2, "confirm", authenticatedTail(bytes, "one", 2, "confirm")) }
            for ((id, version, mutationId) in listOf(Triple("other", 2L, "confirm"), Triple("one", 3L, "confirm"), Triple("one", 2L, "other")))
                assertThrows(Exception::class.java) { cipher.open(id, version, mutationId, bytes) }
            assertThrows(IllegalArgumentException::class.java) { cipher.open("one", 2, "confirm", ByteArray(65537).also { it[0] = 4 }) }
            assertThrows(IllegalArgumentException::class.java) { cipher.open("one", 1, "create-one", ByteArray(4097).also { it[0] = 2 }) }
            assertThrows(IllegalArgumentException::class.java) { cipher.seal(initial.copy(version = 2), "bad", 99, "RM_ACK") }
            assertThrows(IllegalArgumentException::class.java) { cipher.seal(confirmed, "bad", 99, "UNKNOWN") }
            assertThrows(IllegalArgumentException::class.java) { cipher.seal(confirmed, "bad", 99, "REMIND") }
        } finally { cipher.wipe() }
    }

    @Test fun remindersRemainBoundedAndImportRejectsDeviceOutbox(): Unit = Fixture().use { f ->
        var current = f.tasks.changeReminder(mutation(f.create("one")))
        repeat(TaskReminders.MAX_COMMANDS - 1) { index ->
            current = f.tasks.changeReminder(mutation(current, at = 100L + index, id = "confirm-" + index))
        }
        assertEquals(TaskReminders.MAX_COMMANDS, current.reminder!!.commands.size)
        assertTrue(f.blob("one").size > 4096); assertTrue(f.blob("one").size < 65536)
        f.reopen(); assertEquals(current, f.current("one")); val bytes = f.blob("one")
        assertThrows(TaskReminderLimit::class.java) { f.tasks.changeReminder(mutation(current, at = 200, id = "overflow")) }
        assertArrayEquals(bytes, f.blob("one")); assertEquals(current, f.current("one"))
        val imported = current.copy(version = 1)
        assertThrows(IllegalArgumentException::class.java) { f.local.db.transaction { f.tasks.importWithin(it, imported, "import") } }
        assertArrayEquals(bytes, f.blob("one"))
        val completed = f.tasks.change(ManualTaskMutation("one", current.version, ManualTaskStatus.COMPLETED, 201, "complete-full-outbox"))
        assertEquals(TaskReminderPhase.REVOKED, completed.reminder!!.phase)
        assertEquals(TaskReminders.MAX_COMMANDS, completed.reminder!!.commands.size)
    }

    private class Fixture : AutoCloseable {
        val local = LocalCandidateFixture()
        var tasks = SqlManualTaskRepository(local.gateway, KEY); private set
        fun create(id: String, due: ManualTaskSchedule? = DUE) = tasks.create(TaskReminderSqlTest.create(id, due))
        fun current(id: String) = tasks.snapshots().single { it.id == id }
        fun blob(id: String) = local.db.read("SELECT ciphertext FROM manual_tasks WHERE id=?", id).single().single() as ByteArray
        fun reopen() { tasks.wipeKey(); local.reopen(); tasks = SqlManualTaskRepository(local.gateway, KEY) }
        override fun close() { tasks.wipeKey(); local.close() }
    }
    companion object {
        private val KEY = LocalCandidateFixture.KEY
        private val DUE = ManualTaskDateSqlTest.date("2042-11-19", "09:30", "Pacific/Chatham")
        private val LATER = ManualTaskDateSqlTest.date("2042-11-20", "09:30", "Pacific/Chatham")
        private val DUE_MILLIS = DUE.instant()!!.toEpochMilli()
        private val AVAILABLE = TaskReminderAvailability(true, TaskReminderPermission.GRANTED, TaskReminderPermission.GRANTED)
        private fun create(id: String, due: ManualTaskSchedule? = DUE) = ManualTaskCreate(id, "合成私密提醒🙂", 55, "UTC", "create-" + id, due)
        private fun mutation(task: ManualTaskSnapshot, mode: TaskReminderMode? = TaskReminderMode.EXACT,
            at: Long = 99, id: String = "confirm") = TaskReminderMutation(task, mode, at, id)
        private fun contains(bytes: ByteArray, marker: ByteArray) = bytes.indices.any { start ->
            start + marker.size <= bytes.size && marker.indices.all { bytes[start + it] == marker[it] }
        }
        /** Re-authenticate an invalid payload to prove parser EOF enforcement independently of AEAD rejection. */
        private fun authenticatedTail(raw: ByteArray, id: String, version: Long, mutation: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(KEY, "HmacSHA256"))
            val key = mac.doFinal("com.t1doo.aiyou.manual-tasks.v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
            val aad = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use {
                it.writeUTF("AIyou manual tasks v" + raw[0]); it.writeUTF(id); it.writeLong(version); it.writeUTF(mutation)
            } }.toByteArray()
            try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(1, 13))); c.updateAAD(aad)
                val plain = c.doFinal(raw, 13, raw.size - 13)
                try {
                    val nonce = ByteArray(12) { (it + 79).toByte() }
                    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); c.updateAAD(aad)
                    return byteArrayOf(raw[0]) + nonce + c.doFinal(plain + byteArrayOf(7))
                } finally { plain.fill(0) }
            } finally { key.fill(0) }
        }
    }
}
