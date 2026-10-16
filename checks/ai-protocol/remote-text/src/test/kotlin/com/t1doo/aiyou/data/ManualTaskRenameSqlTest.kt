package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual SQLite/AEAD and package restore. No platform, permission, network or model calls. */
class ManualTaskRenameSqlTest {
    @Test fun explicitRenameNormalizesOnlyOuterWhitespaceAndNeverLogsPrivateInput(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        f.store.create(ManualTaskSqlTest.create("one"))
        val request = rename("one", 1, "　合成纠错🙂e\u0301  明天3点　")
        assertEquals("合成纠错🙂e\u0301  明天3点", request.title)
        assertFalse(request.toString().contains("合成")); assertFalse(request.toString().contains("one"))
        val saved = f.store.rename(request)
        assertEquals(request.title, saved.title); assertNull(saved.schedule); assertNull(saved.reminder)
        assertFalse(saved.toString().contains(request.title))
        val bytes = f.blob("one")
        for (title in listOf("", "　", "\u200b", "\ud800", "x\n", "🙂".repeat(241), "a".repeat(961)))
            assertThrows(IllegalArgumentException::class.java) { rename("one", 2, title) }
        assertThrows(IllegalArgumentException::class.java) { rename("one", Long.MAX_VALUE, "合成边界") }
        assertThrows(IllegalArgumentException::class.java) { rename("one", 0, "合成边界") }
        assertThrows(IllegalArgumentException::class.java) { rename("one", 2, "合成边界", at = -1) }
        assertArrayEquals(bytes, f.blob("one"))
    }

    @Test fun correctedTitleColdReopensAndLatestExactReplayDoesNotRewriteCiphertextOrCreationAudit(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val due = ManualTaskDateSqlTest.date("2042-11-19", "09:30", "Pacific/Chatham")
        val initial = f.store.create(ManualTaskCreate("one", "合成原始标题", 55, "Asia/Shanghai", "create-one", due))
        val unrelated = f.legacy(); val before = f.blob("one")
        val request = rename("one", 1, "合成私密纠正标题🙂", at = 1)
        val saved = f.store.rename(request)
        assertEquals(initial.copy(version = 2, title = request.title), saved)
        assertFalse(before.contentEquals(f.blob("one")))
        assertFalse(before.copyOfRange(1, 13).contentEquals(f.blob("one").copyOfRange(1, 13)))
        assertEquals(2.toByte(), f.blob("one")[0]); val ciphertext = f.blob("one")
        f.reopen(); assertEquals(saved, f.store.snapshots().single())
        assertEquals(saved, f.store.rename(request)); assertArrayEquals(ciphertext, f.blob("one"))
        assertEquals(unrelated, f.legacy())
        val marker = request.title.toByteArray(Charsets.UTF_8)
        Files.list(f.directory).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach {
            assertFalse("Corrected task title remains encrypted in owned host files", contains(Files.readAllBytes(it), marker))
        } }
    }

    @Test fun exactReplayRejectsChangedTitleTimestampVersionAndAnotherOperationWithoutWriting(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        f.store.create(ManualTaskSqlTest.create("one"))
        val request = rename("one", 1, "合成已纠正标题")
        val saved = f.store.rename(request); val before = f.blob("one")
        for (bad in listOf(rename("one", 1, "合成其他标题", request.mutationId),
            rename("one", 2, request.title, request.mutationId), rename("one", 1, request.title, request.mutationId, 100)))
            assertThrows(ManualTaskConflict::class.java) { f.store.rename(bad) }
        assertThrows(ManualTaskConflict::class.java) {
            f.store.reschedule(ManualTaskScheduleMutation("one", 1, ManualTaskDateOperation.CLEAR, null, 99, request.mutationId))
        }
        assertThrows(ManualTaskConflict::class.java) {
            f.store.change(ManualTaskMutation("one", 1, ManualTaskStatus.CANCELLED, 99, request.mutationId))
        }
        assertEquals(saved, f.store.rename(rename("one", 1, "　" + request.title + "　", request.mutationId)))
        assertArrayEquals(before, f.blob("one"))
    }

    @Test fun collidingCreateAndDateMutationIdsCannotMasqueradeAsARenameReceipt(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val initial = f.store.create(ManualTaskSqlTest.create("one")); val before = f.blob("one")
        assertThrows(ManualTaskConflict::class.java) { f.store.rename(rename("one", 1, "合成新标题", "create-one", 55)) }
        assertArrayEquals(before, f.blob("one"))
        val dated = f.store.reschedule(ManualTaskScheduleMutation("one", 1, ManualTaskDateOperation.SET, DUE, 99, "date"))
        val bytes = f.blob("one")
        assertThrows(ManualTaskConflict::class.java) { f.store.rename(rename("one", 1, initial.title, "date")) }
        assertArrayEquals(bytes, f.blob("one")); assertEquals(dated, f.store.snapshots().single())
    }

    @Test fun normalizedNoOpMissingReservedStaleAndTerminalTargetsRejectWithoutUpsert(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val initial = f.store.create(ManualTaskSqlTest.create("one", "同名合成任务")); val before = f.blob("one")
        assertThrows(ManualTaskConflict::class.java) { f.store.rename(rename("one", 1, "　同名合成任务　")) }
        assertThrows(ManualTaskConflict::class.java) { f.store.rename(rename("missing", 1, "合成新标题")) }
        assertThrows(ManualTaskConflict::class.java) { f.store.rename(rename("TASK_PROPOSAL:reserved", 1, "合成新标题")) }
        assertThrows(ManualTaskConflict::class.java) { f.store.rename(rename("one", 2, "合成新标题")) }
        assertArrayEquals(before, f.blob("one")); assertEquals(listOf(initial), f.store.snapshots())
        for (status in listOf(ManualTaskStatus.COMPLETED, ManualTaskStatus.CANCELLED)) {
            val id = status.name; f.store.create(ManualTaskSqlTest.create(id))
            val terminal = f.store.change(ManualTaskSqlTest.change(id, 1, status)); val bytes = f.blob(id)
            assertThrows(ManualTaskConflict::class.java) { f.store.rename(rename(id, 2, "不能复活的合成任务")) }
            assertArrayEquals(bytes, f.blob(id)); assertEquals(terminal, f.store.snapshots().single { it.id == id })
        }
        assertEquals(3L, f.db.read("SELECT COUNT(*) FROM manual_tasks").single().single())
    }

    @Test fun actualSqlAbortReadbackFailureAndLostCommitAckHaveOneDurableCorrection(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val initial = f.store.create(ManualTaskSqlTest.create("one")); val before = f.blob("one")
        val request = rename("one", 1, "合成事务纠正")
        f.db.write("CREATE TRIGGER synthetic_rename_abort AFTER UPDATE ON manual_tasks BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertThrows(ManualTaskStorageFailure::class.java) { f.store.rename(request) }; assertArrayEquals(before, f.blob("one"))
        f.db.write("DROP TRIGGER synthetic_rename_abort")
        var updated = false
        f.db.afterSql = { if (it.startsWith("UPDATE manual_tasks")) updated = true }
        f.db.beforeSql = { if (updated && it.startsWith("SELECT id,version")) error("Synthetic rename readback failure") }
        try { assertThrows(ManualTaskStorageFailure::class.java) { f.store.rename(request) }; assertTrue(updated) }
        finally { f.db.afterSql = null; f.db.beforeSql = null }
        assertArrayEquals(before, f.blob("one")); assertEquals(initial, f.store.snapshots().single())
        var lost = false
        f.db.afterSql = { if (it == "COMMIT" && !lost) { lost = true; error("Synthetic rename commit ACK loss") } }
        try { assertThrows(ManualTaskStorageFailure::class.java) { f.store.rename(request) } } finally { f.db.afterSql = null }
        assertTrue(lost); f.reopen(); val committed = f.blob("one")
        assertEquals(2L, f.store.rename(request).version); assertEquals(request.title, f.store.snapshots().single().title)
        assertArrayEquals(committed, f.blob("one")); assertEquals(1, f.store.snapshots().size)
    }

    @Test(timeout = 20_000) fun independentRenameDateAndTerminalCasHaveOneWinnerAndOldRequestsCannotOverwrite(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        for (action in listOf("RENAME", "DATE", "COMPLETED", "CANCELLED")) {
            val initial = f.store.create(ManualTaskCreate(action, "合成并发原始标题", 55, "UTC", "create-" + action, DUE))
            val connection = f.second(); val other = SqlManualTaskRepository(connection, ManualTaskSqlTest.KEY)
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val request = rename(action, 1, "合成并发标题纠正")
            try {
                val actions = listOf<() -> Unit>({ f.store.rename(request); Unit }, {
                    when (action) {
                        "RENAME" -> other.rename(rename(action, 1, "合成另一标题纠正", "other"))
                        "DATE" -> other.reschedule(ManualTaskScheduleMutation(action, 1, ManualTaskDateOperation.POSTPONE, LATER, 100, "other"))
                        else -> other.change(ManualTaskMutation(action, 1, ManualTaskStatus.valueOf(action), 100, "other"))
                    }; Unit
                })
                val outcomes = actions.map { work -> pool.submit<String> {
                    go.await(); try { work(); "saved" } catch (_: ManualTaskConflict) { "conflict" }
                } }
                go.countDown(); assertEquals(listOf("conflict", "saved"), outcomes.map { it.get(3, TimeUnit.SECONDS) }.sorted())
                val current = f.store.snapshots().single { it.id == action }; val bytes = f.blob(action)
                assertEquals(2L, current.version); assertEquals(initial.createdAtMillis, current.createdAtMillis)
                if (current.title == request.title) assertEquals(current, f.store.rename(request))
                else assertThrows(ManualTaskConflict::class.java) { f.store.rename(request) }
                assertArrayEquals(bytes, f.blob(action))
                if (current.status == ManualTaskStatus.OPEN && action in listOf("COMPLETED", "CANCELLED")) {
                    f.store.change(ManualTaskMutation(action, 2, ManualTaskStatus.valueOf(action), 101, "terminal"))
                    assertThrows(ManualTaskConflict::class.java) { f.store.rename(rename(action, 3, "不得重开")) }
                }
            } finally { go.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); other.wipeKey(); connection.close() }
        }
    }

    @Test fun frozenIndependentV1BytesStayUntouchedUntilAnExplicitCorrectionAndKeepOldTables(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val legacy = Base64.getDecoder().decode(LEGACY)
        f.db.write("INSERT INTO manual_tasks VALUES(?,?,?,?)", "legacy", 1, "legacy-create", legacy)
        val oldTables = f.legacy(); f.reopen()
        val old = f.store.snapshots().single(); assertArrayEquals(legacy, f.blob("legacy"))
        assertThrows(ManualTaskConflict::class.java) { f.store.rename(rename("legacy", 1, "　" + old.title + "　")) }
        assertArrayEquals(legacy, f.blob("legacy"))
        val request = rename("legacy", 1, "合成明确纠正旧版任务")
        val saved = f.store.rename(request); val bytes = f.blob("legacy")
        assertEquals(old.copy(version = 2, title = request.title, updatedAtMillis = 99), saved)
        assertEquals(2.toByte(), bytes[0]); f.reopen()
        assertEquals(saved, f.store.rename(request)); assertArrayEquals(bytes, f.blob("legacy")); assertEquals(oldTables, f.legacy())
    }

    @Test fun corruptAuthenticatedBytesCannotBeReplacedByAValidRenameOrWrongKeyAndEvidenceSurvives(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        f.store.create(ManualTaskSqlTest.create("one")); f.store.rename(rename("one", 1, "合成已纠正密文"))
        val before = f.blob("one"); val wrong = SqlManualTaskRepository(f.db, ByteArray(32) { 7 })
        try { assertThrows(ManualTaskStorageFailure::class.java) { wrong.rename(rename("one", 2, "合成非法替换")) } }
        finally { wrong.wipeKey() }
        assertArrayEquals(before, f.blob("one"))
        val corrupt = before.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        f.db.write("UPDATE manual_tasks SET version=version+1,lastMutationId='synthetic-corrupt',ciphertext=? WHERE id=?", corrupt, "one")
        assertThrows(ManualTaskStorageFailure::class.java) { f.store.rename(rename("one", 3, "合成非法替换")) }
        assertArrayEquals(corrupt, f.blob("one")); f.reopen()
        assertThrows(ManualTaskStorageFailure::class.java) { f.store.snapshots() }; assertArrayEquals(corrupt, f.blob("one"))
    }

    @Test fun sameTitleAndMutationInAnotherTaskStillTargetsOnlyItsStableId(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val one = f.store.create(ManualTaskSqlTest.create("one", "同名合成任务"))
        val two = f.store.create(ManualTaskSqlTest.create("two", "同名合成任务")); val otherBytes = f.blob("two")
        val corrected = f.store.rename(rename("one", 1, "合成只有一个被纠正", "shared"))
        assertEquals(one.copy(version = 2, title = corrected.title, updatedAtMillis = 99), corrected)
        assertEquals(two, f.store.snapshots().single { it.id == "two" }); assertArrayEquals(otherBytes, f.blob("two"))
        val second = f.store.rename(rename("two", 1, corrected.title, "shared"))
        assertEquals("two", second.id); assertNotEquals(corrected.id, second.id); assertEquals(2L, second.version)
    }

    @Test fun renamedProposalKeepsImmutableConfirmationReceiptAndExactSourceLocationAcrossColdReplay(): Unit = LocalCandidateFixture().use { f ->
        var tasks = SqlManualTaskRepository(f.gateway, LocalCandidateFixture.KEY)
        var proposals = SqlTaskProposalRepository(f.gateway, LocalCandidateFixture.KEY, tasks)
        try {
            val access = SqlLocalProcessingRepository(f.gateway).read(f.source)
            val binding = TaskSourceBinding(access.source, access.sourceHash, access.generation, 0, 5)
            val proposal = proposals.write(TaskProposalWrite("one", 0, binding, "合成已确认原始标题", ManualTaskDateInput(), 50, "UTC", 50, "save"))
            val confirmation = TaskProposalConfirm(proposal, 60, "confirm"); val initial = proposals.confirm(confirmation)
            val request = rename(initial.id, initial.version, "合成用户纠正的正式任务")
            val corrected = tasks.rename(request); val receipt = initial.sourceReceipt
            assertEquals(receipt, corrected.sourceReceipt); assertTrue(proposals.list().isEmpty())
            assertEquals(3.toByte(), taskBytes(f.db, initial.id)[0])
            proposals.wipeKey(); tasks.wipeKey(); f.reopen()
            tasks = SqlManualTaskRepository(f.gateway, LocalCandidateFixture.KEY)
            proposals = SqlTaskProposalRepository(f.gateway, LocalCandidateFixture.KEY, tasks)
            val bytes = taskBytes(f.db, initial.id)
            assertEquals(corrected, proposals.confirm(confirmation)); assertEquals(corrected, tasks.rename(request))
            assertArrayEquals(bytes, taskBytes(f.db, initial.id)); assertTrue(proposals.list().isEmpty())
            val source = SqlSourceTaskRepository(f.gateway, tasks); val located = mutableListOf<TaskSourceBinding>()
            assertThrows(SourceTaskConflict::class.java) { source.locate(initial.id, initial.version) { located += it } }
            assertTrue(located.isEmpty()); assertTrue(source.locate(initial.id, corrected.version) { located += it })
            assertEquals(listOf(binding), located)
            assertThrows(TaskProposalConflict::class.java) { proposals.confirm(TaskProposalConfirm(proposal, 60, "changed-confirmation")) }
            f.revise(); assertThrows(SourceTaskConflict::class.java) { source.locate(initial.id, corrected.version) { located += it } }
            assertEquals(listOf(binding), located); assertEquals(corrected, proposals.confirm(confirmation))
            assertArrayEquals(bytes, taskBytes(f.db, initial.id))
        } finally { proposals.wipeKey(); tasks.wipeKey() }
    }

    @Test fun pendingReminderIdentityVersionAndMixedOutboxRemainExactlyEqualThroughRenameAndColdReopen(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val initial = f.store.create(ManualTaskCreate("one", "合成提醒原始标题", 55, "UTC", "create-one", DUE))
        val first = f.store.changeReminder(TaskReminderMutation(initial, TaskReminderMode.EXACT, 60, "first"))
        val pending = f.store.changeReminder(TaskReminderMutation(first, TaskReminderMode.APPROXIMATE, 61, "second"))
        val request = rename("one", pending.version, "合成提醒纠正标题")
        val corrected = f.store.rename(request); val bytes = f.blob("one")
        assertEquals(pending.copy(version = pending.version + 1, title = request.title, updatedAtMillis = 99), corrected)
        assertEquals(pending.reminder, corrected.reminder); assertEquals(2, corrected.reminder!!.commands.size)
        assertEquals(4.toByte(), bytes[0]); f.reopen()
        assertEquals(corrected, f.store.rename(request)); assertArrayEquals(bytes, f.blob("one"))
        assertEquals(pending.reminder!!.commands, f.store.reminderCommands())
        val schedule = corrected.reminder!!.commands.single { it.kind == TaskReminderCommandKind.SCHEDULE }
        val cancel = corrected.reminder!!.commands.single { it.kind == TaskReminderCommandKind.CANCEL }
        assertTrue(f.store.isReminderCommandCurrent(schedule)); assertTrue(f.store.isReminderCommandCurrent(cancel))
        assertTrue(f.store.acknowledgeReminder(cancel, TaskReminderPhase.REVOKED, 100))
        assertTrue(f.store.acknowledgeReminder(schedule, TaskReminderPhase.SCHEDULED, 101))
        assertTrue(f.store.acceptReminderTrigger(schedule.token, DUE.instant()!!.toEpochMilli(), AVAILABLE))
        val fired = f.store.snapshots().single()
        assertEquals(request.title, fired.title); assertEquals(corrected.reminder!!.scheduleVersion, fired.reminder!!.scheduleVersion)
        assertFalse(f.store.acceptReminderTrigger(cancel.token, DUE.instant()!!.toEpochMilli(), AVAILABLE))
    }

    @Test fun renamePreservesRevokedAndDeniedReminderStatesWithoutImplicitReconfirmation(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        for (state in listOf(TaskReminderPhase.REVOKED, TaskReminderPhase.EXACT_DENIED)) {
            val id = state.name
            val initial = f.store.create(ManualTaskCreate(id, "合成授权状态", 55, "UTC", "create-" + id, DUE))
            val confirmed = f.store.changeReminder(TaskReminderMutation(initial, TaskReminderMode.EXACT, 60, "confirm-" + id))
            val token = confirmed.reminder!!.commands.single().token
            val before = if (state == TaskReminderPhase.REVOKED)
                f.store.changeReminder(TaskReminderMutation(confirmed, null, 61, "revoke"))
            else {
                assertTrue(f.store.acknowledgeReminder(confirmed.reminder!!.commands.single(), state, 61))
                f.store.snapshots().single { it.id == id }
            }
            val renamed = f.store.rename(rename(id, before.version, "合成授权状态纠正"))
            assertEquals(before.reminder, renamed.reminder); assertEquals(state, renamed.reminder!!.phase)
            assertFalse(f.store.acceptReminderTrigger(token, DUE.instant()!!.toEpochMilli(), AVAILABLE))
        }
    }

    @Test(timeout = 20_000) fun openExportsAndEncryptedRestoreCarryCurrentCorrectedTitleWithoutRestoringReminder(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            PackagePorts(source).use { s -> PackagePorts(target, TARGET_KEY).use { t ->
                val sourceId = source.entry(); val access = SqlLocalProcessingRepository(source.gateway).read(SourceVersion(sourceId, 1))
                val binding = TaskSourceBinding(access.source, access.sourceHash, access.generation, 0, 5)
                val initial = source.db.transaction { sql -> s.tasks.createWithin(sql,
                    ManualTaskCreate(uuid(), "合成导出原始标题", 10, "UTC", uuid(), DUE),
                    SourceTaskReceipt(binding, OriginalExports.hash("synthetic rename source receipt"))) }
                val pending = s.tasks.changeReminder(TaskReminderMutation(initial, TaskReminderMode.EXACT, 60, uuid()))
                val oldSnapshot = s.originals.capture(listOf(SourceVersion(sourceId, 1)), uuid(), 100)
                val stale = s.repo.previewActivities(oldSnapshot)
                val corrected = s.tasks.rename(rename(initial.id, pending.version, "合成导出当前纠正标题🙂"))
                val before = taskBytes(source.db, initial.id)
                assertFalse(s.repo.isCurrent(stale))
                val snapshot = s.originals.capture(listOf(SourceVersion(sourceId, 1)), uuid(), 101)
                val preview = s.repo.previewActivities(snapshot)
                assertEquals(corrected.copy(reminder = null), preview.activities!!.tasks.single().task)
                for (format in listOf(OriginalExportFormat.MARKDOWN, OriginalExportFormat.JSONL)) {
                    val rendered = OriginalActivityExportCodec.render(snapshot, preview.activities!!, format)
                    val text = try { rendered.copyBytes().toString(Charsets.UTF_8) } finally { rendered.release() }
                    val task = text.lineSequence().filter { it.startsWith("{") && it.endsWith("}") }
                        .map { Json.parseToJsonElement(it).jsonObject }.single { it["type"]?.jsonPrimitive?.content == "manualTask" }
                    assertEquals(corrected.title, task.getValue("title").jsonPrimitive.content)
                    assertFalse(text.contains(initial.title)); assertFalse(text.contains(corrected.reminder!!.id)); assertFalse(task.containsKey("reminder"))
                }
                val prepared = s.repo.seal(preview, password())
                val bytes = ByteArrayOutputStream().use { output ->
                    try { prepared.writeTo(output) { true }; output.toByteArray() } finally { prepared.release() }
                }
                assertEquals("AIYOUPK3", bytes.copyOfRange(0, 8).toString(Charsets.US_ASCII))
                val verified = t.repo.open({ bytes.inputStream() }, password())
                val restoredSource = uuid()
                val result = try {
                    assertEquals(corrected.title, verified.activities!!.tasks.single().task.title)
                    assertNull(verified.activities!!.tasks.single().task.reminder)
                    t.repo.restore(verified, listOf(restoredSource), 200)
                } finally { verified.release() }
                val restored = t.tasks.snapshots().single()
                assertEquals(result.taskIds.single(), restored.id); assertNotEquals(corrected.id, restored.id)
                assertEquals(corrected.title, restored.title); assertEquals(corrected.schedule, restored.schedule)
                assertEquals(SourceVersion(restoredSource, 1), restored.sourceReceipt!!.binding.source)
                assertNull(restored.reminder); assertTrue(t.tasks.reminderCommands().isEmpty())
                assertFalse(t.tasks.acceptReminderTrigger(corrected.reminder!!.commands.single().token, DUE.instant()!!.toEpochMilli(), AVAILABLE))
                assertEquals(corrected, s.tasks.snapshots().single()); assertArrayEquals(before, taskBytes(source.db, initial.id))
                assertEquals("LOCAL_ONLY", source.db.read("SELECT privacyPolicy FROM entries WHERE id=?", sourceId).single().single())
                assertEquals("AI_NONE", target.db.read("SELECT privacyPolicy FROM entries WHERE id=?", restoredSource).single().single())
                assertEquals(listOf(null, null), target.db.read("SELECT connection,consent FROM remote_source_access WHERE entryId=?", restoredSource).single())
                assertTrue(target.db.read("SELECT id FROM remote_text_jobs").isEmpty())
            } }
        } }
    }

    private class PackagePorts(f: PermanentErasureFixture, key: ByteArray = PermanentErasureFixture.KEY) : AutoCloseable {
        val tasks = SqlManualTaskRepository(f.gateway, key)
        private val proposals = SqlTaskProposalRepository(f.gateway, key, tasks)
        private val activities = SqlRecordPackageActivities(f.gateway, tasks, proposals)
        private val packages = SqlRecordPackageRepository(f.gateway, f.tags, activities = activities)
        val originals = SqlOriginalExportRepository(f.gateway, f.tags, activities)
        val repo = SqlRecordMediaPackageRepository(f.gateway, originals, f.media, packages, activities)
        override fun close() { proposals.wipeKey(); tasks.wipeKey() }
    }

    companion object {
        private const val LEGACY = "AQcICQoLDA0ODxAREu8vPI4ALiRlSX43LPnuDZ9oqck/nC10hvoNtn/L3IrytSFCEJsE3a6tKu8HJ7NoKlnXgS3Q8vdz1/adt/XrnuWUbzbJgCv/MyXnthhd+fXlO+8EuJXM14Xm0rUVwgIyXw=="
        private val DUE = ManualTaskDateSqlTest.date("2042-11-19", "09:30", "Pacific/Chatham")
        private val LATER = ManualTaskDateSqlTest.date("2042-11-20", "09:30", "Pacific/Chatham")
        private val AVAILABLE = TaskReminderAvailability(platformConnected = true, notifications = TaskReminderPermission.GRANTED,
            exactAlarms = TaskReminderPermission.GRANTED, channelEnabled = true)
        private val TARGET_KEY = ByteArray(32) { (it + 119).toByte() }
        private fun rename(id: String, version: Long, title: String, mutation: String = "rename-" + id, at: Long = 99) =
            ManualTaskRename(id, version, title, at, mutation)
        private fun taskBytes(database: PythonSqliteDatabase, id: String) =
            database.read("SELECT ciphertext FROM manual_tasks WHERE id=?", id).single().single() as ByteArray
        private fun contains(bytes: ByteArray, marker: ByteArray) = bytes.indices.any { at ->
            at + marker.size <= bytes.size && marker.indices.all { bytes[at + it] == marker[it] }
        }
        private fun uuid() = UUID.randomUUID().toString()
        private fun password() = "synthetic task rename package recovery 42".toCharArray()
    }
}
