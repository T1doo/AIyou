package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual current schema and product SQL/tag AEAD, with two independent host SQLite processes. */
class OriginalExportSqlTest {
    private val id = ManualTaskSqlTest.LEGACY_ID
    private val source get() = listOf(SourceVersion(id, 1))
    @Test fun currentOriginalAndAuthenticatedTagsExportExactMetadataWithoutModifyingAnyTable(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            val repo = SqlOriginalExportRepository(f.db, tags); val old = f.legacy()
            f.store.create(ManualTaskSqlTest.create("excluded-task", "禁止进入导出的合成任务"))
            val snapshot = repo.capture(source, "synthetic-snapshot", 10)
            assertEquals(old, f.legacy()); assertTrue(repo.isCurrent(snapshot)); assertEquals(1, snapshot.records.size)
            val record = snapshot.records.single()
            assertEquals("合成原文", record.entry.originalText); assertEquals("UTC", record.entry.captureTimeZone)
            assertEquals(5L, record.entry.capturedAtMillis); assertEquals(1, record.entry.currentRevision)
            assertEquals(listOf("合成旧标签"), record.tags.names); assertEquals(1L, record.tags.version)
            val payload = OriginalExportCodec.render(snapshot, OriginalExportFormat.JSONL)
            val text = payload.copyBytes().toString(Charsets.UTF_8); payload.release()
            assertFalse(text.contains("禁止进入导出")); assertFalse(text.contains("合成草稿")); assertFalse(text.contains("legacy-attachment"))
            assertEquals(old, f.legacy()); assertEquals(6L, f.db.read("PRAGMA user_version").single().single())
        } finally { tags.wipeKey() }
    }
    @Test fun sourceOrTagChangesRequireExplicitRefreshAndDeletionOrMissingNeverUsesCachedList(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            val repo = SqlOriginalExportRepository(f.db, tags); val original = repo.capture(source, "old-snapshot", 10)
            tags.replace(ManualTagMutation(id, 1, listOf("新合成标签"), "new-tag")); assertFalse(repo.isCurrent(original))
            revise(f.db, id, "新合成正文", 2)
            assertThrows(OriginalExportConflict::class.java) { repo.capture(source, "stale", 11) }
            val refreshed = repo.capture(source, "explicit-refresh", 12, true)
            assertEquals(2, refreshed.records.single().entry.currentRevision); assertEquals("新合成正文", refreshed.records.single().entry.originalText)
            assertEquals(listOf("新合成标签"), refreshed.records.single().tags.names)
            f.db.write("UPDATE remote_source_access SET deleted=1 WHERE entryId=?", id)
            assertFalse(repo.isCurrent(refreshed)); assertThrows(OriginalExportConflict::class.java) { repo.capture(source, "deleted", 13, true) }
            assertThrows(OriginalExportConflict::class.java) { repo.capture(listOf(SourceVersion("missing", 1)), "missing", 13) }
        } finally { tags.wipeKey() }
    }
    @Test fun bodyHashAndTagAuthenticationFailuresPreserveActualCorruptEvidence(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            val repo = SqlOriginalExportRepository(f.db, tags)
            f.db.write("UPDATE entry_revisions SET contentHash=? WHERE entryId=?", "0".repeat(64), id)
            assertThrows(OriginalExportStorageFailure::class.java) { repo.capture(source, "bad-body", 1) }
            assertEquals("0".repeat(64), f.db.read("SELECT contentHash FROM entry_revisions WHERE entryId=?", id).single().single())
            f.db.write("UPDATE entry_revisions SET contentHash=? WHERE entryId=?", OriginalExports.hash("合成原文"), id)
            val blob = (f.db.read("SELECT ciphertext FROM entry_manual_tags WHERE entryId=?", id).single().single() as ByteArray).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            f.db.write("UPDATE entry_manual_tags SET ciphertext=? WHERE entryId=?", blob, id)
            assertThrows(OriginalExportStorageFailure::class.java) { repo.capture(source, "bad-tags", 1) }
            assertArrayEquals(blob, f.db.read("SELECT ciphertext FROM entry_manual_tags WHERE entryId=?", id).single().single() as ByteArray)
        } finally { tags.wipeKey() }
    }
    @Test(timeout = 6_000) fun independentWriterCannotMixNewTagsWithOldOriginalInsideOneSnapshot(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        f.second().use { other ->
            assertNotEquals(f.db.pid, other.pid)
            val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY); val otherTags = SqlManualTagRepository(other, ManualTaskSqlTest.KEY)
            val repo = SqlOriginalExportRepository(f.db, tags); val pool = Executors.newFixedThreadPool(2)
            val readBody = CountDownLatch(1); val release = CountDownLatch(1); val attempting = CountDownLatch(1)
            try {
                f.db.afterSql = { sql -> if (sql.startsWith("SELECT e.id")) { readBody.countDown(); check(release.await(1, TimeUnit.SECONDS)) } }
                val reading = pool.submit<OriginalExportSnapshot> { repo.capture(source, "atomic-old", 1) }
                assertTrue(readBody.await(1, TimeUnit.SECONDS))
                val writer = pool.submit { attempting.countDown(); other.transaction {
                    revise(other, id, "同步新正文", 2)
                    otherTags.replace(ManualTagMutation(id, 1, listOf("同步新标签"), "atomic-new-tag"))
                } }
                assertTrue(attempting.await(1, TimeUnit.SECONDS)); assertThrows(TimeoutException::class.java) { writer.get(40, TimeUnit.MILLISECONDS) }
                release.countDown(); val captured = reading.get(1, TimeUnit.SECONDS); writer.get(1, TimeUnit.SECONDS); f.db.afterSql = null
                assertEquals("合成原文", captured.records.single().entry.originalText)
                assertEquals(listOf("合成旧标签"), captured.records.single().tags.names); assertFalse(repo.isCurrent(captured))
                val refreshed = repo.capture(source, "atomic-new", 2, true)
                assertEquals("同步新正文", refreshed.records.single().entry.originalText); assertEquals(listOf("同步新标签"), refreshed.records.single().tags.names)
            } finally { release.countDown(); f.db.afterSql = null; pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); tags.wipeKey(); otherTags.wipeKey() }
        }
    }
    @Test fun absentTagsAreExplicitVersionZeroAndOversizedOrDuplicateSelectionsFail(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        f.db.write("DELETE FROM entry_manual_tags WHERE entryId=?", id)
        val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        try {
            val repo = SqlOriginalExportRepository(f.db, tags); val r = repo.capture(source, "without-tags", 1).records.single()
            assertEquals(0L, r.tags.version); assertTrue(r.tags.names.isEmpty())
            assertThrows(OriginalExportStorageFailure::class.java) { repo.capture(source + source, "duplicate", 1) }
            assertThrows(OriginalExportStorageFailure::class.java) { repo.capture(emptyList(), "empty", 1) }
            for (n in 1..3) insert(f.db, "large-$n", "x".repeat(90_000))
            assertThrows(OriginalExportTooLarge::class.java) { repo.capture((1..3).map { SourceVersion("large-$it", 1) }, "large", 1) }
            assertEquals(4, f.db.read("SELECT id FROM entries").size)
        } finally { tags.wipeKey() }
    }
    @Test(timeout = 6_000) fun sharedDatabaseCloseWaitsForExportTransactionBeforeErasingBorrowedTagKey(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val gateway = DrainingRecordsSqlDatabase(f.db); val tags = SqlManualTagRepository(gateway, ManualTaskSqlTest.KEY)
        val repo = SqlOriginalExportRepository(gateway, tags); val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val wiped = CountDownLatch(1)
        try {
            f.db.beforeSql = { if (it.startsWith("SELECT e.id")) { entered.countDown(); check(release.await(1, TimeUnit.SECONDS)) } }
            val reading = pool.submit<OriginalExportSnapshot> { repo.capture(source, "draining", 1) }; assertTrue(entered.await(1, TimeUnit.SECONDS))
            val closing = pool.submit { gateway.stopAndAwaitIdle(); tags.wipeKey(); wiped.countDown() }
            assertFalse(wiped.await(40, TimeUnit.MILLISECONDS)); release.countDown()
            assertEquals(listOf("合成旧标签"), reading.get(1, TimeUnit.SECONDS).records.single().tags.names); closing.get(1, TimeUnit.SECONDS)
            f.db.beforeSql = null; assertThrows(OriginalExportStorageFailure::class.java) { repo.capture(source, "late", 2) }
        } finally { release.countDown(); f.db.beforeSql = null; pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); tags.wipeKey() }
    }
    companion object {
        internal fun insert(db: PythonSqliteDatabase, id: String, text: String) = db.transaction { sql ->
            sql.execute("INSERT INTO entries VALUES(?,?,?,?,?)", listOf(id, 1, 5, "Asia/Shanghai", "LOCAL_ONLY"))
            sql.execute("INSERT INTO entry_revisions VALUES(?,?,?,?,?)", listOf(id, 1, text, OriginalExports.hash(text), 5))
        }
        internal fun revise(db: PythonSqliteDatabase, id: String, text: String, revision: Int) = db.transaction { sql ->
            sql.execute("INSERT INTO entry_revisions VALUES(?,?,?,?,?)", listOf(id, revision, text, OriginalExports.hash(text), 7))
            sql.execute("UPDATE entries SET currentRevision=? WHERE id=?", listOf(revision, id))
        }
    }
}
