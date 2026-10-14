package com.t1doo.aiyou.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.model.*
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Formal native Room/SQLCipher contracts. Compilation or host SQLite cannot establish PASS. */
@RunWith(AndroidJUnit4::class)
class SourceBoundTextRecordsIntegrationTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var syntheticKey: ByteArray
    private lateinit var trace: AndroidNativeTrace
    private val stores = mutableListOf<EncryptedEntryRepository>()
    private var opens = 0
    private val file get() = File(directory, "records.db")
    private val clock: () -> Instant = { NOW }

    @Before fun before() {
        trace = AndroidNativeTrace()
        trace.step("source_setup") {
            context = InstrumentationRegistry.getInstrumentation().targetContext
            directory = File(context.noBackupFilesDir, "source-native-${UUID.randomUUID()}").also { check(it.mkdirs()) }
            // Deliberately synthetic test material, independent of Keystore and production keys.
            syntheticKey = ByteArray(32) { (it * 7 + 11).toByte() }
            System.loadLibrary("sqlcipher")
        }
    }

    @After fun after() {
        val cleanup = {
            try { stores.forEach { it.close() } } finally {
                stores.clear()
                if (::syntheticKey.isInitialized) syntheticKey.fill(0)
                if (::directory.isInitialized) directory.deleteRecursively() // Only this test's unique owned fixture.
            }
        }
        try {
            if (::trace.isInitialized) trace.step("source_teardown") { cleanup() } else cleanup()
        } finally { if (::trace.isInitialized) trace.close() }
    }

    @Test fun freshVersionSixUsesGeneratedRoomAndEncryptedNativeStore() = runBlocking {
        val store = open()
        val entry = seed(store, "合成正式原文：需要来源绑定 🌿")
        trace.step("source_fresh_contract") {
            assertVersionAndGuards(store)
            val initial = checkNotNull(store.sourceBoundText.readAccess(entry.id))
            assertEquals(ProcessingPolicy.LOCAL_ONLY, initial.state.policy)
            assertEquals(0L, initial.generation)
            assertEquals(SourceCommitDenial.POLICY_BLOCKED,
                (store.sourceBoundText.queue(RemoteSendIntent(initial.state.source, TextPurpose.ORGANIZATION), "native-v1", clock)
                    as SourceQueueOutcome.Denied).reason)
            val source = approve(store, entry)
            val job = queued(store, source)
            assertNull(store.sourceBoundText.admit(job, clock))
            val result = committed(store.sourceBoundText.commit(job, candidate(job), clock))
            assertFalse(result.alreadyCommitted)
            assertEquals("合成候选批注", result.annotation.candidate.text)
            assertEquals(1L, count(store, "text_annotations"))
        }
        close(store)
        trace.step("source_ciphertext_check") { assertEncrypted(file, entry.originalText) }
        val reopened = open()
        trace.suspending("source_fresh_reopen") {
            assertEquals(entry.originalText, reopened.entries().single().originalText)
            val source = SourceVersion(entry.id, 1)
            assertEquals(1, reopened.sourceBoundText.annotations(source, clock).size)
            assertEquals(1L, count(reopened, "local_jobs"))
            assertVersionAndGuards(reopened)
        }
    }

    @Test fun encryptedVersionThreeMigrationPreservesAllLegacyRowsAcrossReopen() = runBlocking {
        trace.step("source_create_v_three") {
            NativeRecordsSchemaFixture.create(file, syntheticKey, 3) { db ->
                db.execSQL("INSERT INTO entries VALUES(?,1,1000,'Asia/Shanghai','LOCAL_ONLY')", arrayOf(LEGACY_ID))
                db.execSQL("INSERT INTO entry_revisions VALUES(?,1,?,?,1000)", arrayOf(LEGACY_ID, LEGACY_TEXT, contentHash(LEGACY_TEXT)))
                db.execSQL("INSERT INTO capture_draft VALUES(1,'synthetic-v3-draft',3,?)", arrayOf(LEGACY_DRAFT))
                db.execSQL("INSERT INTO local_jobs VALUES(?,?,1,'LOCAL_INDEX','PENDING')", arrayOf("$LEGACY_ID:1:LOCAL_INDEX", LEGACY_ID))
                // A valid WRITING ledger intent, deliberately no published media blob claim.
                db.execSQL("INSERT INTO attachments VALUES(?,?,1,'image/png',?,17,'WRITING')",
                    arrayOf(LEGACY_ATTACHMENT, LEGACY_ID, contentHash("synthetic attachment intent")))
            }
            assertEncrypted(file, LEGACY_TEXT)
        }
        val migrated = open() // Actual factory invokes generated Room and production Migration(3,4).
        trace.suspending("source_v_three_migration") { assertLegacy(migrated); assertVersionAndGuards(migrated) }
        close(migrated)
        val reopened = open()
        trace.suspending("source_v_three_reopen") {
            assertLegacy(reopened)
            assertVersionAndGuards(reopened)
            assertEquals(0L, count(reopened, "remote_source_access"))
            assertEquals(0L, count(reopened, "remote_text_jobs"))
            assertEquals(0L, count(reopened, "text_annotations"))
        }
    }

    @Test fun withdrawFirstAcrossIndependentRoomsRejectsLateCommit() = runBlocking {
        val first = open()
        val entry = seed(first, "合成撤销先获得真实数据库writer")
        val source = approve(first, entry)
        val job = queued(first, source)
        assertNull(first.sourceBoundText.admit(job, clock))
        val generation = checkNotNull(first.sourceBoundText.readAccess(entry.id)).generation
        val second = open()
        trace.step("source_withdraw_first") {
            val results = ordered(first, second, {
                first.sourceBoundText.updateAuthorization(source, generation, ProcessingPolicy.LOCAL_ONLY, null, null)
            }, { second.sourceBoundText.commit(job, candidate(job), clock) })
            assertNull(results.first)
            assertEquals(SourceCommitDenial.POLICY_BLOCKED, (results.second as SourceCommitOutcome.Denied).reason)
            assertEquals(0L, count(first, "text_annotations"))
            assertEquals("CANCELLED", jobState(first, job.id))
        }
        trace.suspending("source_withdraw_original") {
            assertEquals(entry.originalText, first.entries().single().originalText)
            assertEquals(1, first.revisions(entry.id).size)
        }
        close(first); close(second)
        val reopened = open()
        assertTrue(reopened.sourceBoundText.annotations(source, clock).isEmpty())
        assertEquals(ProcessingPolicy.LOCAL_ONLY, checkNotNull(reopened.sourceBoundText.readAccess(entry.id)).state.policy)
    }

    @Test fun commitFirstAcrossIndependentRoomsWithdrawalRemovesAnnotation() = runBlocking {
        val first = open()
        val entry = seed(first, "合成提交先获得真实数据库writer")
        val source = approve(first, entry)
        val job = queued(first, source)
        assertNull(first.sourceBoundText.admit(job, clock))
        val generation = checkNotNull(first.sourceBoundText.readAccess(entry.id)).generation
        val second = open()
        trace.step("source_commit_first") {
            val results = ordered(first, second, {
                val result = first.sourceBoundText.commit(job, candidate(job), clock)
                assertEquals(1L, count(first, "text_annotations"))
                result
            }, { second.sourceBoundText.updateAuthorization(source, generation, ProcessingPolicy.LOCAL_ONLY, null, null) })
            assertFalse(committed(results.first).alreadyCommitted)
            assertNull(results.second)
            assertEquals(0L, count(second, "text_annotations"))
            assertEquals("COMMITTED", jobState(second, job.id))
            assertEquals(generation + 1, checkNotNull(second.sourceBoundText.readAccess(entry.id)).generation)
            assertTrue(second.sourceBoundText.annotations(source, clock).isEmpty())
        }
        close(first); close(second)
        val reopened = open()
        trace.suspending("source_commit_first_reopen") {
            assertEquals(entry.originalText, reopened.entries().single().originalText)
            assertTrue(reopened.sourceBoundText.annotations(source, clock).isEmpty())
            assertTrue(reopened.sourceBoundText.commit(job, candidate(job), clock) is SourceCommitOutcome.Denied)
        }
    }

    @Test fun annotationInsertThenFinishAbortRollsBackAcrossReopen() = runBlocking {
        val store = open()
        val entry = seed(store, "合成插入批注后完成状态失败")
        val independentDraft = trace.suspending("source_fault_fixture") {
            val draft = store.updateDraft("合成回滚不得修改独立草稿")
            store.database.withTransaction {
                store.database.records().insertAttachment(AttachmentEntity(ROLLBACK_ATTACHMENT, entry.id, 1,
                    "image/png", contentHash("synthetic rollback attachment intent"), 17, "WRITING"))
            }
            draft
        }
        val source = approve(store, entry)
        val job = queued(store, source)
        assertNull(store.sourceBoundText.admit(job, clock))
        trace.step("source_finish_fault") {
            store.database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER synthetic_finish_fault BEFORE UPDATE OF state ON remote_text_jobs " +
                    "WHEN NEW.state='COMMITTED' AND EXISTS(SELECT 1 FROM text_annotations WHERE jobId=NEW.id) " +
                    "BEGIN SELECT RAISE(ABORT,'synthetic failure after annotation insert'); END")
            assertEquals(SourceCommitDenial.STORAGE_UNAVAILABLE,
                (store.sourceBoundText.commit(job, candidate(job), clock) as SourceCommitOutcome.Denied).reason)
            assertEquals(0L, count(store, "text_annotations"))
            assertEquals("ADMITTED", jobState(store, job.id))
        }
        trace.suspending("source_fault_preserves_legacy") { assertRollbackLegacy(store, entry, independentDraft) }
        close(store)
        val reopened = open()
        trace.suspending("source_fault_reopen_legacy") { assertRollbackLegacy(reopened, entry, independentDraft) }
        trace.step("source_fault_reopen") {
            assertEquals(0L, count(reopened, "text_annotations"))
            assertEquals("ADMITTED", jobState(reopened, job.id))
            reopened.database.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_finish_fault")
            assertFalse(committed(reopened.sourceBoundText.commit(job, candidate(job), clock)).alreadyCommitted)
            assertEquals("COMMITTED", jobState(reopened, job.id))
            assertEquals(1L, count(reopened, "text_annotations"))
        }
        trace.suspending("source_fault_recovery_preserves_legacy") { assertRollbackLegacy(reopened, entry, independentDraft) }
    }

    @Test fun revisionAndTombstoneRejectLateResults() = runBlocking {
        val store = open()
        val entry = seed(store, "合成旧版本不可晚提交")
        val source = approve(store, entry)
        val oldJob = queued(store, source)
        assertNull(store.sourceBoundText.admit(oldJob, clock))
        val revised = trace.suspending("source_revision_change") { store.revise(entry.id, 1, "合成当前修订🌿", 2000) }
        trace.step("source_old_revision_denied") {
            assertEquals(SourceCommitDenial.SOURCE_CHANGED,
                (store.sourceBoundText.commit(oldJob, candidate(oldJob), clock) as SourceCommitOutcome.Denied).reason)
            assertEquals("CANCELLED", jobState(store, oldJob.id))
        }
        val current = approve(store, revised)
        val nextJob = queued(store, current)
        assertNull(store.sourceBoundText.admit(nextJob, clock))
        trace.step("source_expired_and_deleted") {
            assertEquals(SourceCommitDenial.CONSENT_NOT_CURRENT,
                (store.sourceBoundText.commit(nextJob, candidate(nextJob), { NOW.plusSeconds(7200) }) as SourceCommitOutcome.Denied).reason)
            val access = checkNotNull(store.sourceBoundText.readAccess(entry.id))
            assertNull(store.sourceBoundText.tombstone(current, access.generation))
            assertTrue(checkNotNull(store.sourceBoundText.readAccess(entry.id)).state.deleted)
            assertEquals(SourceCommitDenial.SOURCE_DELETED,
                (store.sourceBoundText.commit(nextJob, candidate(nextJob), clock) as SourceCommitOutcome.Denied).reason)
            assertEquals(0L, count(store, "text_annotations"))
        }
        trace.suspending("source_deleted_visibility") {
            assertTrue(store.entries().isEmpty())
            assertTrue(store.revisions(entry.id).isEmpty())
            store.database.openHelper.writableDatabase.query(
                "SELECT revision,originalText FROM entry_revisions WHERE entryId=? ORDER BY revision", arrayOf(entry.id)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.getInt(0)); assertEquals(entry.originalText, cursor.getString(1))
                assertTrue(cursor.moveToNext()); assertEquals(2, cursor.getInt(0)); assertEquals(revised.originalText, cursor.getString(1))
                assertFalse(cursor.moveToNext())
            }
            try { store.revise(entry.id, 2, "合成迟到编辑不得恢复源", 3000); fail("Expected deleted source conflict") }
            catch (_: RecordConflict) { }
        }
        close(store)
        val reopened = open()
        assertTrue(checkNotNull(reopened.sourceBoundText.readAccess(entry.id)).state.deleted)
        assertTrue(reopened.entries().isEmpty())
        assertTrue(reopened.sourceBoundText.annotations(current, clock).isEmpty())
    }

    @Test fun duplicateConflictAndForeignScopeNeverOverwriteOriginal() = runBlocking {
        val store = open()
        val entry = seed(store, "合成不可覆盖正式原文😀")
        val source = approve(store, entry)
        val job = queued(store, source)
        trace.step("source_full_scope_checks") {
            val forged = SourceBoundTextJob(job.id, job.generation, job.promptVersion, job.intent, job.state,
                "合成caller替换原文", job.contentHash, job.capturedAtMillis, job.captureTimeZone)
            val originalConfig = checkNotNull(job.state.connection)
            val originalGrant = checkNotNull(job.state.consent)
            val forgedFields = listOf(forged,
                copied(job, id = "synthetic-unissued-job"),
                copied(job, generation = job.generation + 1),
                copied(job, promptVersion = "synthetic-other-prompt"),
                copied(job, intent = RemoteSendIntent(source, TextPurpose.CONVERSATION)),
                copied(job, state = job.state.copy(policy = ProcessingPolicy.LOCAL_ONLY)),
                copied(job, state = job.state.copy(connection = originalConfig.copy(modelId = "synthetic-other-model"))),
                copied(job, state = job.state.copy(consent = originalGrant.copy(version = originalGrant.version + 1))),
                copied(job, contentHash = contentHash("synthetic other original")),
                copied(job, capturedAtMillis = job.capturedAtMillis + 1),
                copied(job, captureTimeZone = "Asia/Shanghai"))
            forgedFields.forEach { metadata ->
                assertNotNull(store.sourceBoundText.check(metadata, clock))
                assertNotNull(store.sourceBoundText.admit(metadata, clock))
                store.sourceBoundText.cancel(metadata)
                assertEquals("QUEUED", jobState(store, job.id))
            }
            val foreign = SourceBoundTextJob(job.id, job.generation, job.promptVersion,
                RemoteSendIntent(SourceVersion("synthetic-foreign-source", 1), job.intent.purpose), job.state,
                job.originalText, job.contentHash, job.capturedAtMillis, job.captureTimeZone)
            assertNotNull(store.sourceBoundText.check(foreign, clock))
            assertEquals(SourceCommitDenial.JOB_INVALID,
                (store.sourceBoundText.queue(job.intent, job.promptVersion, clock) as SourceQueueOutcome.Denied).reason)
            assertNull(store.sourceBoundText.admit(job, clock))
            val result = candidate(job)
            val first = committed(store.sourceBoundText.commit(job, result, clock))
            val repeated = committed(store.sourceBoundText.commit(job, result, clock))
            assertFalse(first.alreadyCommitted); assertTrue(repeated.alreadyCommitted)
            assertEquals(first.annotation.jobId, repeated.annotation.jobId)
            assertEquals(first.annotation.createdAtMillis, repeated.annotation.createdAtMillis)
            assertEquals(SourceCommitDenial.RESULT_CONFLICT,
                (store.sourceBoundText.commit(job, candidate(job, "合成不同候选"), clock) as SourceCommitOutcome.Denied).reason)
            assertEquals(SourceCommitDenial.JOB_INVALID,
                (store.sourceBoundText.commit(forged, result, clock) as SourceCommitOutcome.Denied).reason)
            assertTrue(store.sourceBoundText.queue(job.intent, job.promptVersion, clock) is SourceQueueOutcome.AlreadyCommitted)
            assertEquals(1L, count(store, "text_annotations"))
            assertEquals(1L, count(store, "remote_text_jobs"))
            assertEquals(1L, count(store, "local_jobs"))
        }
        assertEquals(entry.originalText, store.entries().single().originalText)
        assertEquals(listOf(entry.originalText), store.revisions(entry.id).map { it.originalText })
        close(store)
        val reopened = open()
        assertTrue(committed(reopened.sourceBoundText.commit(job, candidate(job), clock)).alreadyCommitted)
        assertEquals(entry.originalText, reopened.entries().single().originalText)
    }

    @Test fun codePointReferencesRequireExactUnicodeQuotes() = runBlocking {
        val store = open()
        val entry = seed(store, "A😀B汉")
        val source = approve(store, entry)
        val job = queued(store, source)
        assertNull(store.sourceBoundText.admit(job, clock))
        trace.step("source_code_point_references") {
            val invalid = listOf(
                OriginalTextReference(source, 1, 3, "😀"), // UTF-16 offsets would wrongly accept this.
                OriginalTextReference(source, 0, 5, entry.originalText),
                OriginalTextReference(SourceVersion(source.sourceId, 2), 1, 2, "😀"),
                OriginalTextReference(SourceVersion("synthetic-other", 1), 1, 2, "😀"),
                OriginalTextReference(source, 1, 2, "不同引用"),
            )
            invalid.forEach { reference ->
                assertEquals(SourceCommitDenial.INVALID_REFERENCE,
                    (store.sourceBoundText.commit(job, DerivedTextCandidate("合成候选", listOf(reference)), clock)
                        as SourceCommitOutcome.Denied).reason)
            }
            assertEquals(SourceCommitDenial.INVALID_RESULT,
                (store.sourceBoundText.commit(job, DerivedTextCandidate("\uD800", listOf(OriginalTextReference(source, 1, 2, "😀"))), clock)
                    as SourceCommitOutcome.Denied).reason)
            assertEquals(0L, count(store, "text_annotations"))
            val candidate = DerivedTextCandidate("合成合法Unicode候选", listOf(OriginalTextReference(source, 1, 2, "😀")))
            val result = committed(store.sourceBoundText.commit(job, candidate, clock))
            assertEquals(candidate.references, result.annotation.candidate.references)
            assertEquals("😀", store.sourceBoundText.annotations(source, clock).single().candidate.references.single().quote)
        }
        assertEquals(entry.originalText, store.entries().single().originalText)
    }

    private fun open(): EncryptedEntryRepository = trace.step("source_open", ++opens,
        if (file.exists()) "REOPEN_SAME_PROCESS" else "FRESH_FILE_FIRST_OPEN") {
        EncryptedEntryRepository.openWithKey(context, file, syntheticKey.copyOf()).also { stores += it }
    }
    private fun close(store: EncryptedEntryRepository) = trace.step("source_close") { store.close(); stores.remove(store) }
    private suspend fun seed(store: EncryptedEntryRepository, text: String): StoredEntry = trace.suspending("source_seed") {
        store.saveDraft(store.updateDraft(text), 1000, "Etc/UTC")
    }
    private fun approve(store: EncryptedEntryRepository, entry: StoredEntry): SourceVersion {
        val source = SourceVersion(entry.id, entry.currentRevision.toLong())
        val config = ProviderConnection("synthetic-native-provider", 1, "https://native.invalid/v1",
            ConnectionProtocol.CHAT_COMPLETIONS, "synthetic-native-model", SecretReference("synthetic-native-reference"))
        val grant = ConsentGrant(UUID.randomUUID().toString(), 1, source, config, TextPurpose.ORGANIZATION,
            NOW.minusSeconds(60), NOW.plusSeconds(3600))
        val generation = checkNotNull(store.sourceBoundText.readAccess(entry.id)).generation
        assertNull(store.sourceBoundText.updateAuthorization(source, generation, ProcessingPolicy.REMOTE_ALLOWED, config, grant))
        val authorized = checkNotNull(store.sourceBoundText.readAccess(entry.id))
        assertEquals(generation + 1, authorized.generation)
        assertEquals(RemoteSendState(source, ProcessingPolicy.REMOTE_ALLOWED, false, config, grant), authorized.state)
        return source
    }
    private fun queued(store: EncryptedEntryRepository, source: SourceVersion): SourceBoundTextJob {
        val result = store.sourceBoundText.queue(RemoteSendIntent(source, TextPurpose.ORGANIZATION), "native-v1", clock)
        assertTrue("Expected one newly issued source capability", result is SourceQueueOutcome.Queued)
        val job = (result as SourceQueueOutcome.Queued).job
        val current = checkNotNull(store.sourceBoundText.readAccess(source.sourceId))
        assertEquals(current.generation, job.generation)
        assertEquals(current.state, job.state)
        assertEquals(RemoteSendIntent(source, TextPurpose.ORGANIZATION), job.intent)
        assertEquals("native-v1", job.promptVersion)
        store.database.openHelper.writableDatabase.query(
            "SELECT r.originalText,r.contentHash,e.capturedAtMillis,e.captureTimeZone " +
                "FROM entry_revisions r JOIN entries e ON e.id=r.entryId WHERE r.entryId=? AND r.revision=?",
            arrayOf<Any?>(source.sourceId, source.revision)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(cursor.getString(0), job.originalText)
            assertEquals(cursor.getString(1), job.contentHash)
            assertEquals(contentHash(cursor.getString(0)), job.contentHash)
            assertEquals(cursor.getLong(2), job.capturedAtMillis)
            assertEquals(cursor.getString(3), job.captureTimeZone)
            assertFalse(cursor.moveToNext())
        }
        return job
    }
    private fun candidate(job: SourceBoundTextJob, text: String = "合成候选批注") = DerivedTextCandidate(text,
        listOf(OriginalTextReference(job.intent.source, 0, job.originalText.codePointCount(0, job.originalText.length), job.originalText)))
    private fun copied(job: SourceBoundTextJob, id: String = job.id, generation: Long = job.generation,
                       promptVersion: String = job.promptVersion, intent: RemoteSendIntent = job.intent,
                       state: RemoteSendState = job.state, contentHash: String = job.contentHash,
                       capturedAtMillis: Long = job.capturedAtMillis, captureTimeZone: String = job.captureTimeZone) =
        SourceBoundTextJob(id, generation, promptVersion, intent, state, job.originalText, contentHash, capturedAtMillis, captureTimeZone)
    private fun committed(result: SourceCommitOutcome): SourceCommitOutcome.Committed {
        assertTrue("Expected committed annotation", result is SourceCommitOutcome.Committed)
        return result as SourceCommitOutcome.Committed
    }
    private fun count(store: EncryptedEntryRepository, table: String): Long =
        store.database.openHelper.writableDatabase.query("SELECT count(*) FROM $table").use { check(it.moveToFirst()); it.getLong(0) }
    private fun jobState(store: EncryptedEntryRepository, id: String): String =
        store.database.openHelper.writableDatabase.query("SELECT state FROM remote_text_jobs WHERE id=?", arrayOf(id))
            .use { check(it.moveToFirst()); it.getString(0) }

    /** Both wrappers hold ACTUAL independent Room/native writer transactions on the same file. */
    private fun <A, B> ordered(first: EncryptedEntryRepository, second: EncryptedEntryRepository,
                              winningMutation: () -> A, followingMutation: () -> B): Pair<A, B> {
        assertNotSame(first.database, second.database)
        val winnerChanged = CountDownLatch(1)
        val releaseWinner = CountDownLatch(1)
        val followerAttempted = CountDownLatch(1)
        val followerInsideNative = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val winning = workers.submit(Callable {
                first.database.runInTransaction(Callable {
                    val result = winningMutation()
                    winnerChanged.countDown()
                    check(releaseWinner.await(2, TimeUnit.SECONDS)) { "Owned native writer barrier exceeded budget" }
                    result
                })
            })
            assertTrue(winnerChanged.await(2, TimeUnit.SECONDS))
            val following = workers.submit(Callable {
                followerAttempted.countDown()
                second.database.runInTransaction(Callable {
                    followerInsideNative.countDown()
                    followingMutation()
                })
            })
            assertTrue(followerAttempted.await(2, TimeUnit.SECONDS))
            assertFalse("Second Room writer must wait for native file transaction", followerInsideNative.await(100, TimeUnit.MILLISECONDS))
            releaseWinner.countDown()
            val firstResult = winning.get(2, TimeUnit.SECONDS)
            val secondResult = following.get(2, TimeUnit.SECONDS)
            assertEquals(0L, followerInsideNative.count)
            return firstResult to secondResult
        } finally {
            releaseWinner.countDown()
            workers.shutdownNow() // Only these two owned test workers.
            assertTrue("Owned native workers must finish within budget", workers.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private fun assertVersionAndGuards(store: EncryptedEntryRepository) {
        val sql = store.database.openHelper.writableDatabase
        NativeRecordsSchemaFixture.assertCurrentSchema(sql)
        // The pinned Android 4.19.1 package embeds core version 4.19.0; see D02_STORAGE_CHECKPOINT.
        sql.query("PRAGMA cipher_version").use { check(it.moveToFirst()); assertEquals("4.19.0", it.getString(0).substringBefore(' ')) }
        val expected = setOf("immutable_original_revision", "immutable_attachment_intent", "remote_source_changed",
            "remote_access_changed", "immutable_remote_text_job", "remote_text_job_state", "immutable_text_annotation")
        val names = sql.query("SELECT name FROM sqlite_master WHERE type='trigger'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertTrue("Factory migration/onOpen must install all production guards", names.containsAll(expected))
        sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
    private fun assertEncrypted(database: File, original: String) {
        assertTrue("A real encrypted native database must contain at least one complete page", database.length() >= 4096)
        val bytes = database.readBytes()
        assertFalse(bytes.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(String(original.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)))
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(database.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { plain ->
                plain.rawQuery("SELECT originalText FROM entry_revisions", null).use { it.moveToFirst() }
            }
            fail("Android plaintext SQLite must reject the actual encrypted file")
        } catch (_: android.database.sqlite.SQLiteException) { }
    }
    private suspend fun assertRollbackLegacy(store: EncryptedEntryRepository, entry: StoredEntry, draft: TextDraft) {
        assertEquals(listOf(entry.copy(policy = ProcessingPolicy.REMOTE_ALLOWED)), store.entries())
        assertEquals(listOf(OriginalRevision(entry.id, 1, entry.originalText, contentHash(entry.originalText), 1000)), store.revisions(entry.id))
        assertEquals(draft, store.readDraft())
        assertEquals(1L, count(store, "local_jobs"))
        assertEquals(LocalJobEntity("${entry.id}:1:LOCAL_INDEX", entry.id, 1), store.database.records().job("${entry.id}:1:LOCAL_INDEX"))
        assertEquals(1L, count(store, "attachments"))
        assertEquals(AttachmentEntity(ROLLBACK_ATTACHMENT, entry.id, 1, "image/png",
            contentHash("synthetic rollback attachment intent"), 17, "WRITING"), store.database.records().attachment(ROLLBACK_ATTACHMENT))
    }
    private suspend fun assertLegacy(store: EncryptedEntryRepository) {
        assertEquals(LEGACY_TEXT, store.entries().single().originalText)
        assertEquals(ProcessingPolicy.LOCAL_ONLY, store.entries().single().policy)
        assertEquals(LEGACY_TEXT, store.revisions(LEGACY_ID).single().originalText)
        assertEquals(TextDraft("synthetic-v3-draft", 3, LEGACY_DRAFT), store.readDraft())
        assertEquals(1L, count(store, "local_jobs"))
        assertEquals(LocalJobEntity("$LEGACY_ID:1:LOCAL_INDEX", LEGACY_ID, 1), store.database.records().job("$LEGACY_ID:1:LOCAL_INDEX"))
        assertEquals(AttachmentEntity(LEGACY_ATTACHMENT, LEGACY_ID, 1, "image/png", contentHash("synthetic attachment intent"), 17, "WRITING"),
            store.database.records().attachment(LEGACY_ATTACHMENT))
    }
    companion object {
        private val NOW = Instant.parse("2026-10-13T00:00:00Z")
        private const val LEGACY_ID = "synthetic-v3-source"
        private const val LEGACY_TEXT = "合成v3原文须完整保全🌿"
        private const val LEGACY_DRAFT = "合成v3独立草稿"
        private const val LEGACY_ATTACHMENT = "9c798650-7a1a-4c24-8b3a-86b872f57c88"
        private const val ROLLBACK_ATTACHMENT = "d8693fce-1506-4e20-8c6d-78ae0f05ca0f"
    }
}
