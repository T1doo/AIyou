package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real activity capture/codec, package AEAD and SQLite restore; no platform or permission action. */
class TaskReminderExportTest {
    @Test fun pendingReminderOutboxIsExcludedFromCapturedMarkdownAndJsonlEntitiesWithoutChangingSource(): Unit =
        PermanentErasureFixture().use { source -> Ports(source).use { s ->
            val sourceId = source.entry()
            val reminded = s.remindedTask(sourceId)
            val before = s.taskBytes(reminded.id)
            val snapshot = s.originals.capture(listOf(SourceVersion(sourceId, 1)), uuid(), 100)
            val activities = s.activities.capture(snapshot)
            assertEquals(1, activities.tasks.size)
            assertEquals(reminded.copy(reminder = null), activities.tasks.single().task)
            assertEquals(0, activities.tasks.single().sourceRecord)
            assertTrue(activities.tasks.all { it.task.reminder == null })
            assertTrue(s.originals.areActivitiesCurrent(snapshot, activities))
            assertEquals(activities.tasks, s.originals.captureActivities(snapshot).tasks)

            for (format in listOf(OriginalExportFormat.MARKDOWN, OriginalExportFormat.JSONL)) {
                val payload = OriginalActivityExportCodec.render(snapshot, activities, format)
                val text = try { payload.copyBytes().toString(Charsets.UTF_8) } finally { payload.release() }
                val objects = text.lineSequence().filter { it.startsWith("{") && it.endsWith("}") }
                    .map { Json.parseToJsonElement(it).jsonObject }.toList()
                val manifest = objects.single { it["type"]?.jsonPrimitive?.content == "manifest" }
                // The manifest honestly names reminders in its exclusions; the task entity carries no reminder data.
                assertTrue(manifest.getValue("excluded").jsonArray.any { it.jsonPrimitive.content == "reminders" })
                val task = objects.single { it["type"]?.jsonPrimitive?.content == "manualTask" }
                assertEquals(TASK_FIELDS, task.keys)
                assertEquals(reminded.id, task.getValue("id").jsonPrimitive.content)
                assertEquals(reminded.title, task.getValue("title").jsonPrimitive.content)
                assertEquals(reminded.schedule!!.date.toString(), task.getValue("schedule").jsonObject.getValue("date").jsonPrimitive.content)
                assertNoReminderFields(task)
                assertFalse(text.contains(reminded.reminder!!.id))
                assertFalse(text.contains("\"PENDING\""))
                assertFalse(text.contains("\"EXACT\""))
                assertFalse(text.contains("\"APPROXIMATE\""))
                assertFalse(text.contains("\"SCHEDULE\""))
                assertFalse(text.contains("\"CANCEL\""))
            }
            assertEquals(reminded, s.tasks.snapshots().single())
            assertArrayEquals(before, s.taskBytes(reminded.id))
            assertEquals(reminded.reminder!!.commands, s.tasks.reminderCommands())
            assertEquals("LOCAL_ONLY", source.db.read("SELECT privacyPolicy FROM entries WHERE id=?", sourceId).single().single())
            assertEquals(listOf(null, null), source.db.read("SELECT connection,consent FROM remote_source_access WHERE entryId=?", sourceId).single())
        } }

    @Test(timeout = 20_000) fun authenticatedActivityPackageRestoresWithAnotherTaskKeyWithoutArmingOrGrantingAuthority(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            Ports(source).use { s -> Ports(target, TARGET_KEY).use { t ->
                val sourceId = source.entry()
                val reminded = s.remindedTask(sourceId)
                val before = s.taskBytes(reminded.id)
                val snapshot = s.originals.capture(listOf(SourceVersion(sourceId, 1)), uuid(), 100)
                val preview = s.repo.previewActivities(snapshot)
                assertEquals(reminded.copy(reminder = null), preview.activities!!.tasks.single().task)
                val bytes = s.export(preview)
                assertEquals("AIYOUPK3", bytes.copyOfRange(0, 8).toString(Charsets.US_ASCII))
                val verified = t.repo.open({ bytes.inputStream() }, password())
                try {
                    assertNull(verified.activities!!.tasks.single().task.reminder)
                    assertEquals(reminded.schedule, verified.activities!!.tasks.single().task.schedule)
                    assertTrue(t.tasks.snapshots().isEmpty())
                    assertTrue(t.tasks.reminderCommands().isEmpty())
                } finally { verified.release() }
                val restoredSource = uuid()
                val result = t.restore(bytes, listOf(restoredSource))
                assertEquals(1, result.taskIds.size)
                val restored = t.tasks.snapshots().single()
                assertEquals(result.taskIds.single(), restored.id)
                assertNotEquals(reminded.id, restored.id)
                assertEquals(1L, restored.version)
                assertEquals(reminded.title, restored.title)
                assertEquals(reminded.schedule, restored.schedule)
                assertEquals(reminded.status, restored.status)
                assertEquals(SourceVersion(restoredSource, 1), restored.sourceReceipt!!.binding.source)
                assertNull(restored.reminder)
                assertTrue(t.tasks.reminderCommands().isEmpty())
                // A foreground drain also has no driver work to perform after import.
                val platformCalls = AtomicInteger()
                val unusedDriver = object : TaskReminderDriver {
                    override fun availability(): TaskReminderAvailability { platformCalls.incrementAndGet(); error("No platform work expected") }
                    override fun schedule(command: TaskReminderCommand): TaskReminderPhase { platformCalls.incrementAndGet(); error("No schedule expected") }
                    override fun cancel(command: TaskReminderCommand): TaskReminderPhase { platformCalls.incrementAndGet(); error("No cancellation expected") }
                }
                assertEquals(0, TaskReminderDispatcher(t.tasks, unusedDriver) { 200 }.drain())
                assertEquals(0, platformCalls.get())
                assertFalse(t.tasks.acceptReminderTrigger(reminded.reminder!!.commands.single { it.kind == TaskReminderCommandKind.SCHEDULE }.token,
                    DUE.instant()!!.toEpochMilli(), TaskReminderAvailability()))
                assertEquals("AI_NONE", target.db.read("SELECT privacyPolicy FROM entries WHERE id=?", restoredSource).single().single())
                assertEquals(listOf(null, null), target.db.read("SELECT connection,consent FROM remote_source_access WHERE entryId=?", restoredSource).single())
                assertTrue(target.db.read("SELECT id FROM remote_text_jobs").isEmpty())
                val wrongKey = SqlManualTaskRepository(target.gateway, PermanentErasureFixture.KEY)
                try { assertThrows(ManualTaskStorageFailure::class.java) { wrongKey.snapshots() } } finally { wrongKey.wipeKey() }
                assertEquals(reminded, s.tasks.snapshots().single())
                assertArrayEquals(before, s.taskBytes(reminded.id))
                assertEquals(reminded.reminder!!.commands, s.tasks.reminderCommands())
            } }
        } }
    }

    private class Ports(val fixture: PermanentErasureFixture, key: ByteArray = PermanentErasureFixture.KEY) : AutoCloseable {
        val tasks = SqlManualTaskRepository(fixture.gateway, key)
        val proposals = SqlTaskProposalRepository(fixture.gateway, key, tasks)
        val activities = SqlRecordPackageActivities(fixture.gateway, tasks, proposals)
        val packages = SqlRecordPackageRepository(fixture.gateway, fixture.tags, activities = activities)
        val originals = SqlOriginalExportRepository(fixture.gateway, fixture.tags, activities)
        val repo = SqlRecordMediaPackageRepository(fixture.gateway, originals, fixture.media, packages, activities)
        fun remindedTask(sourceId: String): ManualTaskSnapshot {
            val access = SqlLocalProcessingRepository(fixture.gateway).read(SourceVersion(sourceId, 1))
            val binding = TaskSourceBinding(access.source, access.sourceHash, access.generation, 0, 5)
            val initial = fixture.db.transaction { sql -> tasks.createWithin(sql,
                ManualTaskCreate(uuid(), "合成显式提醒任务", 10, "UTC", uuid(), DUE),
                SourceTaskReceipt(binding, OriginalExports.hash("synthetic explicit task receipt"))) }
            val first = tasks.changeReminder(TaskReminderMutation(initial, TaskReminderMode.EXACT, 60, uuid()))
            return tasks.changeReminder(TaskReminderMutation(first, TaskReminderMode.APPROXIMATE, 61, uuid())).also {
                assertEquals(TaskReminderPhase.PENDING, it.reminder!!.phase)
                assertEquals(2, it.reminder!!.commands.size)
                assertEquals(setOf(TaskReminderCommandKind.SCHEDULE, TaskReminderCommandKind.CANCEL), it.reminder!!.commands.map { c -> c.kind }.toSet())
            }
        }
        fun taskBytes(id: String) = fixture.db.read("SELECT ciphertext FROM manual_tasks WHERE id=?", id).single().single() as ByteArray
        suspend fun export(preview: RecordMediaPreview): ByteArray {
            val prepared = repo.seal(preview, password())
            return ByteArrayOutputStream().use { output ->
                try { prepared.writeTo(output) { true }; output.toByteArray() } finally { prepared.release() }
            }
        }
        suspend fun restore(bytes: ByteArray, ids: List<String>): RecordPackageRestoreResult {
            val verified = repo.open({ bytes.inputStream() }, password())
            return try { repo.restore(verified, ids, 200) } finally { verified.release() }
        }
        override fun close() { proposals.wipeKey(); tasks.wipeKey() }
    }
    companion object {
        private val DUE = ManualTaskDateInput("2042-11-19", "09:30", "Pacific/Chatham").parse()!!
        private val TARGET_KEY = ByteArray(32) { (it + 117).toByte() }
        private val TASK_FIELDS = setOf("type", "id", "version", "title", "status", "createdAtMillis", "creationTimeZone", "updatedAtMillis", "schedule", "source", "sourceReceiptHash")
        private val REMINDER_FIELDS = setOf("reminder", "reminders", "reminderId", "scheduleVersion", "mode", "phase", "commands", "token")
        private fun uuid() = UUID.randomUUID().toString()
        private fun password() = "synthetic reminder export recovery 42".toCharArray()
        private fun assertNoReminderFields(value: JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) -> assertFalse("No reminder field in exported task entity", key in REMINDER_FIELDS); assertNoReminderFields(child) }
                is JsonArray -> value.forEach(::assertNoReminderFields)
                else -> Unit
            }
        }
    }
}
