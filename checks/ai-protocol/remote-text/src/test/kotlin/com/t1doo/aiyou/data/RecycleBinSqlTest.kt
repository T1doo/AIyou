package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayInputStream
import java.util.concurrent.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual product SQL/tag/attachment AEAD; owned host SQLite is not native Room/SQLCipher evidence. */
class RecycleBinSqlTest {
    private val id = ManualTaskSqlTest.LEGACY_ID
    @Test fun moveColdReopenAndRestorePreserveAllOriginalVersionsTagsAttachmentsAndIndependentTasks(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        install(f.db)
        OriginalExportSqlTest.revise(f.db, id, "合成第二版🙂", 2)
        f.db.write("INSERT INTO local_jobs VALUES(?,?,?,?,?)", "current-index", id, 2, "LOCAL_INDEX", "PENDING")
        f.db.write("INSERT INTO local_jobs VALUES(?,?,?,?,?)", "edit-receipt", id, 2, "ORIGINAL_EDIT_COMMIT", "CONFIRMED")
        f.store.create(ManualTaskSqlTest.create("independent", "合成独立待办"))
        val preserved = preserved(f.db)
        var tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            var bin = SqlRecycleBinRepository(f.db, tags)
            val original = bin.read(id); val move = mutation(original, "move-cold")
            val deleted = bin.apply(move)
            assertTrue(deleted.deleted); assertEquals(original.generation + 1, deleted.generation)
            assertEquals(preserved, preserved(f.db)); assertTrue(active(f.db).isEmpty())
            assertTrue(tags.snapshots().isEmpty()); assertThrows(ManualTagUnavailable::class.java) { tags.read(id) }
            assertEquals(listOf("CANCELLED", "CANCELLED"), f.db.read("SELECT state FROM local_jobs WHERE operation='LOCAL_INDEX' ORDER BY id").map { it.single() })
            assertEquals("CONFIRMED", f.db.read("SELECT state FROM local_jobs WHERE id='edit-receipt'").single().single())
            tags.wipeKey(); f.reopen(); tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY); bin = SqlRecycleBinRepository(f.db, tags)
            assertEquals("合成第二版🙂", bin.records().single().entry.originalText)
            assertEquals(listOf("合成旧标签"), bin.records().single().tags.names); assertEquals(2, bin.records().single().revisionCount)
            assertEquals(1, bin.records().single().attachmentCount)
            assertEquals(deleted.generation, bin.apply(move).generation)
            val restore = mutation(bin.read(id), "restore-cold"); val restored = bin.apply(restore)
            assertFalse(restored.deleted); assertEquals(original.generation + 2, restored.generation)
            assertEquals(ProcessingPolicy.AI_NONE, restored.entry.policy); assertEquals(2, restored.entry.currentRevision)
            assertEquals(preserved, preserved(f.db)); assertEquals(listOf(id), active(f.db)); assertTrue(bin.records().isEmpty())
            assertEquals(listOf("合成旧标签"), tags.read(id).names)
            assertEquals(listOf("PENDING", "CANCELLED"), f.db.read("SELECT state FROM local_jobs WHERE operation='LOCAL_INDEX' ORDER BY id").map { it.single() })
            assertEquals(ManualTaskStatus.OPEN, f.store.snapshots().single().status)
            assertEquals(listOf(null, null), f.db.read("SELECT connection,consent FROM remote_source_access WHERE entryId=?", id).single())
            assertThrows(RecycleConflict::class.java) { bin.apply(move) }
            assertEquals(restored.generation, bin.apply(restore).generation)
            val movedAgain = bin.apply(mutation(restored, "move-again"))
            assertThrows(RecycleConflict::class.java) { bin.apply(restore) }; assertTrue(movedAgain.deleted)
            assertEquals(preserved, preserved(f.db)); assertEquals(6L, f.db.read("PRAGMA user_version").single().single())
        } finally { tags.wipeKey() }
    }
    @Test fun realEncryptedAttachmentFileSurvivesMoveAndColdRestoreByteForByte(): Unit = ManualTaskSqlTest.Fixture().use { f -> runBlocking {
        install(f.db)
        val bytes = "合成原始附件字节🙂PRIVATE_ATTACHMENT".toByteArray()
        f.db.write("UPDATE attachments SET id=? WHERE id='legacy-attachment'", "e917a576-b1eb-4f85-a2f4-0e180cc0cd00")
        val fileId = "18c9f111-b8f6-49cc-b78b-60f87bc5820c"
        val mediaRoot = f.directory.resolve("media").toFile()
        var media = EncryptedAttachmentStore(mediaRoot, SqlLedger(f.db), ManualTaskSqlTest.KEY.copyOf())
        var tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            media.save(AttachmentIntent(fileId, id, 1, "image/png", RemoteTextCodec.sha256(bytes), bytes.size.toLong())) { ByteArrayInputStream(bytes) }
            val files = mediaRoot.listFiles()!!.filter { it.name.endsWith(".aead") }.associate { it.name to it.readBytes().toList() }
            assertEquals(1, files.size); assertFalse(files.values.single().toByteArray().toString(Charsets.UTF_8).contains("PRIVATE_ATTACHMENT"))
            val preserved = preserved(f.db); val bin = SqlRecycleBinRepository(f.db, tags)
            bin.apply(mutation(bin.read(id), "retain-file")); media.close(); tags.wipeKey(); f.reopen()
            media = EncryptedAttachmentStore(mediaRoot, SqlLedger(f.db), ManualTaskSqlTest.KEY.copyOf())
            tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY); val reopened = SqlRecycleBinRepository(f.db, tags)
            reopened.apply(mutation(reopened.read(id), "restore-file"))
            assertEquals(files, mediaRoot.listFiles()!!.filter { it.name.endsWith(".aead") }.associate { it.name to it.readBytes().toList() })
            assertArrayEquals(bytes, media.read(fileId)); assertEquals(preserved, preserved(f.db))
        } finally { media.close(); tags.wipeKey(); bytes.fill(0) }
    } }
    @Test fun rollbackAndLostCommitAcknowledgementRetryOnlyOneExactDurableReceipt(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        install(f.db); val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            val bin = SqlRecycleBinRepository(f.db, tags); val before = f.legacy(); val move = mutation(bin.read(id), "stable-move")
            f.db.beforeCommit = { error("Synthetic rollback") }
            assertThrows(RecycleStorageFailure::class.java) { bin.apply(move) }; f.db.beforeCommit = null
            assertEquals(before, f.legacy()); assertTrue(bin.records().isEmpty())
            var lost = false
            f.db.afterSql = { if (it == "COMMIT" && !lost) { lost = true; error("Synthetic lost acknowledgement") } }
            assertThrows(RecycleStorageFailure::class.java) { bin.apply(move) }; f.db.afterSql = null; assertTrue(lost)
            assertTrue(bin.read(id).deleted); val exact = f.legacy()
            assertTrue(bin.apply(move).deleted); assertEquals(exact, f.legacy())
            assertEquals(1, f.db.read("SELECT id FROM local_jobs WHERE id='RECYCLE:stable-move'").size)
            assertThrows(RecycleConflict::class.java) { bin.apply(RecycleMutation(move.source, move.expectedGeneration + 1, true, move.mutationId)) }
            val restore = mutation(bin.read(id), "stable-restore")
            lost = false; f.db.afterSql = { if (it == "COMMIT" && !lost) { lost = true; error("Synthetic lost restore acknowledgement") } }
            assertThrows(RecycleStorageFailure::class.java) { bin.apply(restore) }; f.db.afterSql = null
            assertFalse(bin.apply(restore).deleted); assertEquals(1, f.db.read("SELECT id FROM local_jobs WHERE id='RECYCLE:stable-restore'").size)
        } finally { f.db.beforeCommit = null; f.db.afterSql = null; tags.wipeKey() }
    }
    @Test(timeout = 8_000) fun twoIndependentConnectionsPermitOnlyOneCasWinnerAndNeverReplayOverLaterState(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        install(f.db); f.second().use { other ->
            assertNotEquals(f.db.pid, other.pid)
            val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY); val otherTags = SqlManualTagRepository(other, ManualTaskSqlTest.KEY)
            val bins = listOf(SqlRecycleBinRepository(f.db, tags), SqlRecycleBinRepository(other, otherTags))
            val before = bins[0].read(id); val intents = listOf(mutation(before, "race-a"), mutation(before, "race-b"))
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            try {
                val results = bins.mapIndexed { index, bin -> pool.submit<String> { go.await(); try { bin.apply(intents[index]); "winner-$index" } catch (_: RecycleConflict) { "conflict" } } }
                go.countDown(); val outcomes = results.map { it.get(2, TimeUnit.SECONDS) }
                assertEquals(1, outcomes.count { it == "conflict" }); val winner = outcomes.single { it.startsWith("winner") }.last().digitToInt()
                val moved = bins[1].apply(intents[winner]); assertEquals(before.generation + 1, moved.generation)
                val restored = bins[1].apply(mutation(moved, "race-restore")); assertEquals(before.generation + 2, restored.generation)
                assertThrows(RecycleConflict::class.java) { bins[0].apply(intents[winner]) }
                assertFalse(bins[0].read(id).deleted)
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); tags.wipeKey(); otherTags.wipeKey() }
        }
    }
    @Test fun staleOriginalOrAuthorizationGenerationAndOverflowPreserveAllEvidence(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        install(f.db); val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            val bin = SqlRecycleBinRepository(f.db, tags); val old = mutation(bin.read(id), "stale-original")
            OriginalExportSqlTest.revise(f.db, id, "更新后的合成原文", 2)
            assertThrows(RecycleConflict::class.java) { bin.apply(old) }; assertFalse(bin.read(id).deleted)
            val authOld = mutation(bin.read(id), "stale-auth")
            f.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?", id)
            assertThrows(RecycleConflict::class.java) { bin.apply(authOld) }
            f.db.write("UPDATE remote_source_access SET generation=? WHERE entryId=?", Long.MAX_VALUE, id)
            val overflow = mutation(bin.read(id), "overflow"); val before = f.legacy()
            assertThrows(RecycleStorageFailure::class.java) { bin.apply(overflow) }; assertEquals(before, f.legacy())
            assertFalse(bin.read(id).deleted)
        } finally { tags.wipeKey() }
    }
    @Test fun realSourceAuthorityCancelsQueuedAndAdmittedAiAndRejectsOldResultsAfterRestoreAndNewGrant(): Unit = RemoteTextFixture().use { f ->
        f.db.transaction { sql ->
            ManualTagSqlSchema.migration4To5.forEach { sql.execute(it) }; sql.execute(ManualTagSqlSchema.guard)
            ManualTaskSqlSchema.migration5To6.forEach { sql.execute(it) }; sql.execute(ManualTaskSqlSchema.guard); sql.execute("PRAGMA user_version=6")
        }
        val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            val bin = SqlRecycleBinRepository(f.db, tags)
            val committed = f.admitted("committed"); assertTrue(f.repository.commit(committed, f.candidate(), f.clock) is SourceCommitOutcome.Committed)
            val queued = f.queued("queued"); val admitted = f.admitted("admitted")
            val before = bin.read(f.source.sourceId); val moved = bin.apply(mutation(before, "ai-move"))
            assertEquals(2, f.jobs().count { it[1] == "CANCELLED" }); assertTrue(f.annotations().isEmpty())
            assertEquals(SourceCommitDenial.SOURCE_DELETED, (f.repository.commit(admitted, f.candidate(), f.clock) as SourceCommitOutcome.Denied).reason)
            assertEquals(SourceCommitDenial.SOURCE_DELETED, (f.repository.queue(f.intent, "new-while-deleted", f.clock) as SourceQueueOutcome.Denied).reason)
            assertEquals(SourceCommitDenial.SOURCE_DELETED, f.repository.updateAuthorization(f.source, moved.generation, ProcessingPolicy.REMOTE_ALLOWED, f.selected, f.grant))
            val restored = bin.apply(mutation(moved, "ai-restore"))
            assertEquals(ProcessingPolicy.AI_NONE, restored.entry.policy); assertNull(f.repository.readAccess(f.source.sourceId)!!.state.consent)
            assertTrue(f.repository.annotations(f.source, f.clock).isEmpty())
            val newGrant = f.grant.copy(consentId = "new-after-restore", version = f.grant.version + 1)
            assertNull(f.repository.updateAuthorization(f.source, restored.generation, ProcessingPolicy.REMOTE_ALLOWED, f.selected, newGrant))
            for (old in listOf(queued, admitted, committed)) {
                assertNotNull(f.repository.check(old, f.clock)); assertTrue(f.repository.commit(old, f.candidate(), f.clock) is SourceCommitOutcome.Denied)
            }
            assertTrue(f.repository.queue(f.intent, "new-after-explicit-grant", f.clock) is SourceQueueOutcome.Queued)
            assertTrue(f.annotations().isEmpty())
        } finally { tags.wipeKey() }
    }
    @Test fun moveAndRestoreSameBodyAndTagsNeverReviveOldExportConsentAndNewCaptureExcludesTrash(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        install(f.db); f.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?", id)
        val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            val bin = SqlRecycleBinRepository(f.db, tags); val exports = SqlOriginalExportRepository(f.db, tags)
            val old = exports.capture(listOf(SourceVersion(id, 1)), "before-trash", 1)
            val moved = bin.apply(mutation(bin.read(id), "export-move"))
            assertFalse(exports.isCurrent(old)); assertThrows(OriginalExportConflict::class.java) { exports.capture(listOf(SourceVersion(id, 1)), "in-trash", 2, true) }
            assertTrue(active(f.db).isEmpty()); assertTrue(tags.snapshots().isEmpty())
            bin.apply(mutation(moved, "export-restore"))
            val fresh = exports.capture(listOf(SourceVersion(id, 1)), "after-trash", 3)
            assertEquals(old.records.single().entry, fresh.records.single().entry)
            assertEquals(old.records.single().tags.names, fresh.records.single().tags.names)
            assertEquals(old.records.single().tags.version, fresh.records.single().tags.version)
            assertFalse(exports.isCurrent(old)); assertTrue(exports.isCurrent(fresh))
            assertEquals(old.records.single().sourceGeneration + 2, fresh.records.single().sourceGeneration)
        } finally { tags.wipeKey() }
    }
    @Test fun corruptOriginalOrTagsFailClosedWithoutResettingOrReplacingStoredEvidence(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            val bin = SqlRecycleBinRepository(f.db, tags); val mutation = mutation(bin.read(id), "bad-data")
            f.db.write("UPDATE entry_revisions SET contentHash=? WHERE entryId=?", "0".repeat(64), id)
            assertThrows(RecycleStorageFailure::class.java) { bin.apply(mutation) }
            assertEquals("0".repeat(64), f.db.read("SELECT contentHash FROM entry_revisions WHERE entryId=?", id).single().single())
            f.db.write("UPDATE entry_revisions SET contentHash=? WHERE entryId=?", OriginalExports.hash("合成原文"), id)
            val corrupt = (f.db.read("SELECT ciphertext FROM entry_manual_tags WHERE entryId=?", id).single().single() as ByteArray).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            f.db.write("UPDATE entry_manual_tags SET ciphertext=? WHERE entryId=?", corrupt, id)
            assertThrows(RecycleStorageFailure::class.java) { bin.apply(mutation) }
            assertArrayEquals(corrupt, f.db.read("SELECT ciphertext FROM entry_manual_tags WHERE entryId=?", id).single().single() as ByteArray)
            assertEquals(listOf(id), active(f.db)); assertTrue(bin.records().isEmpty())
        } finally { tags.wipeKey() }
    }
    @Test(timeout = 6_000) fun borrowedCloseDrainsRecycleTransactionBeforeErasingTheSharedTagKey(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        install(f.db); val gateway = DrainingRecordsSqlDatabase(f.db); val tags = SqlManualTagRepository(gateway, ManualTaskSqlTest.KEY)
        val bin = SqlRecycleBinRepository(gateway, tags); val intent = mutation(bin.read(id), "drain")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val wiped = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            f.db.beforeSql = { if (it.startsWith("UPDATE entries")) { entered.countDown(); check(release.await(1, TimeUnit.SECONDS)) } }
            val moving = pool.submit<RecycleRecord> { bin.apply(intent) }; assertTrue(entered.await(1, TimeUnit.SECONDS))
            val closing = pool.submit { gateway.stopAndAwaitIdle(); tags.wipeKey(); wiped.countDown() }
            assertFalse(wiped.await(40, TimeUnit.MILLISECONDS)); release.countDown()
            assertTrue(moving.get(1, TimeUnit.SECONDS).deleted); closing.get(1, TimeUnit.SECONDS)
            f.db.beforeSql = null; assertThrows(RecycleStorageFailure::class.java) { bin.read(id) }
        } finally { release.countDown(); f.db.beforeSql = null; pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); tags.wipeKey() }
    }
    private class SqlLedger(private val db: PythonSqliteDatabase) : AttachmentLedger {
        override suspend fun find(id: String) = all().singleOrNull { it.intent.id == id }
        override suspend fun all(): List<LocalAttachment> = db.read("SELECT id,entryId,revision,mediaType,sha256,byteSize,state FROM attachments ORDER BY id").map {
            LocalAttachment(AttachmentIntent(it[0] as String, it[1] as String, Math.toIntExact(it[2] as Long), it[3] as String, it[4] as String, it[5] as Long), AttachmentState.valueOf(it[6] as String))
        }
        override suspend fun insert(value: LocalAttachment) { val i = value.intent
            db.write("INSERT INTO attachments VALUES(?,?,?,?,?,?,?)", i.id, i.entryId, i.revision, i.mediaType, i.sha256, i.byteSize, value.state.name) }
        override suspend fun transition(expected: LocalAttachment, next: AttachmentState): LocalAttachment {
            check(db.write("UPDATE attachments SET state=? WHERE id=? AND state=?", next.name, expected.intent.id, expected.state.name) == 1)
            return expected.copy(state = next)
        }
    }
    companion object {
        internal fun install(db: PythonSqliteDatabase) = db.transaction { sql -> RemoteTextSqlSchema.guards.forEach { sql.execute(it) } }
        internal fun mutation(record: RecycleRecord, id: String) = RecycleMutation(SourceVersion(record.entry.id, record.entry.currentRevision.toLong()), record.generation, record.deleted, id)
        internal fun active(db: PythonSqliteDatabase): List<String> = db.read("SELECT e.id FROM entries e WHERE NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1) ORDER BY e.id").map { it.single() as String }
        private fun preserved(db: PythonSqliteDatabase) = listOf("entry_revisions", "entry_manual_tags", "attachments", "manual_tasks", "capture_draft").flatMap { table ->
            db.read("SELECT * FROM $table ORDER BY 1,2").map { listOf(table) + it.map { cell -> if (cell is ByteArray) cell.toList() else cell } }
        }
    }
}
