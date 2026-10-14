package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.Closeable
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Actual exported v5, production migration/AEAD/SQL and independent owned host SQLite PIDs. */
class ManualTaskSqlTest {
    @Test fun migrationFromExactVersionFiveIsAtomicPreservesNineSeededTablesAndReopens(): Unit = Fixture(false).use { f ->
        val old = f.legacy(); assertEquals(9, f.tables().size); assertEquals(9, old.map { it.first() }.toSet().size)
        assertThrows(IllegalStateException::class.java) { f.db.transaction { sql ->
            ManualTaskSqlSchema.migration5To6.forEach { sql.execute(it) }; error("Synthetic migration abort")
        } }
        assertEquals(9, f.tables().size); assertEquals(old, f.legacy())
        f.migrate(); assertEquals(10, f.tables().size); assertEquals(old, f.legacy())
        assertEquals(listOf("id", "version", "lastMutationId", "ciphertext"), f.db.read("PRAGMA table_info(manual_tasks)").map { it[1] })
        assertTrue(f.db.read("PRAGMA foreign_key_list(manual_tasks)").isEmpty())
        f.reopen(); assertEquals(6L, f.db.read("PRAGMA user_version").single().single())
        assertTrue(f.store.snapshots().isEmpty()); assertEquals(old, f.legacy())
    }
    @Test fun encryptedUnicodeTitlesAndTerminalStatesSurviveColdReopenWithFreshNonces(): Unit = Fixture().use { f ->
        val title = "合成私密待办🙂e\u0301 明天  3点"
        f.store.create(create("one", title)); val first = f.blob("one")
        f.store.create(create("two", "🙂".repeat(240)))
        fun checkMarkers() {
            val marker = title.toByteArray(Charsets.UTF_8)
            assertFalse(contains(f.blob("one"), marker))
            Files.list(f.directory).use { paths -> paths.filter { it.fileName.toString().startsWith("records") }.forEach {
                assertFalse("Private title cannot occur in live host DB/WAL", contains(Files.readAllBytes(it), marker))
            } }
        }
        checkMarkers(); f.reopen(); assertEquals(title, f.store.snapshots().single { it.id == "one" }.title); checkMarkers()
        f.store.change(change("one", 1, ManualTaskStatus.COMPLETED, 1))
        assertFalse(first.copyOfRange(1, 13).contentEquals(f.blob("one").copyOfRange(1, 13)))
        f.store.change(change("two", 1, ManualTaskStatus.CANCELLED))
        f.reopen()
        assertEquals(ManualTaskStatus.COMPLETED, f.store.snapshots().single { it.id == "one" }.status)
        assertEquals(55L, f.store.snapshots().single { it.id == "one" }.updatedAtMillis) // backwards clock never rewrites creation.
        assertEquals(ManualTaskStatus.CANCELLED, f.store.snapshots().single { it.id == "two" }.status)
        assertEquals(240, f.store.snapshots().single { it.id == "two" }.title.codePointCount(0, 480))
        checkMarkers()
    }
    @Test fun authenticationRejectsWrongKeyAndAllMetadataSwapsPreservingCorruptCiphertext(): Unit = Fixture().use { f ->
        f.store.create(create("one", "合成秘密标题")); val original = f.blob("one")
        val wrong = SqlManualTaskRepository(f.db, ByteArray(32) { 99 })
        try { assertThrows(ManualTaskStorageFailure::class.java) { wrong.snapshots() } } finally { wrong.wipeKey() }
        val cipher = ManualTaskCipher(KEY)
        for ((id, version, operation) in listOf(Triple("other", 1L, "create-one"), Triple("one", 2L, "create-one"), Triple("one", 1L, "other")))
            assertThrows(Exception::class.java) { cipher.open(id, version, operation, original) }
        cipher.wipe()
        val corrupt = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        f.db.write("UPDATE manual_tasks SET version=version+1,ciphertext=? WHERE id=?", corrupt, "one")
        assertThrows(ManualTaskStorageFailure::class.java) { f.store.snapshots() }
        assertThrows(ManualTaskStorageFailure::class.java) { f.store.create(create("one", "replacement")) }
        assertThrows(ManualTaskStorageFailure::class.java) { f.store.change(change("one", 2, ManualTaskStatus.CANCELLED)) }
        assertArrayEquals(corrupt, f.blob("one")); f.reopen()
        assertThrows(ManualTaskStorageFailure::class.java) { f.store.snapshots() }; assertArrayEquals(corrupt, f.blob("one"))
    }
    @Test fun independentConnectionsCasAndExactLatestReplayCannotReopenOrTargetSameTitleTask(): Unit = Fixture().use { f ->
        val original = create("one", "同名任务"); f.store.create(original); f.store.create(create("two", "同名任务"))
        f.second().use { second ->
            val other = SqlManualTaskRepository(second, KEY); val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val attempts = listOf(change("one", 1, ManualTaskStatus.COMPLETED), change("one", 1, ManualTaskStatus.CANCELLED))
            try {
                val outcomes = listOf(f.store, other).mapIndexed { i, store -> pool.submit<String> {
                    go.await(); try { store.change(attempts[i]); "saved-$i" } catch (_: ManualTaskConflict) { "conflict" }
                } }; go.countDown()
                val actual = outcomes.map { it.get(2, TimeUnit.SECONDS) }
                assertEquals(1, actual.count { it == "conflict" }); val winner = actual.single { it.startsWith("saved") }.last().digitToInt()
                val intent = attempts[winner]; val bytes = f.blob("one")
                assertEquals(2L, other.change(intent).version); assertArrayEquals(bytes, f.blob("one"))
                assertThrows(ManualTaskConflict::class.java) { other.change(ManualTaskMutation("one", 1, intent.status, intent.requestedAtMillis + 1, intent.mutationId)) }
                assertThrows(ManualTaskConflict::class.java) { other.change(ManualTaskMutation("one", 2, intent.status, 99, intent.mutationId)) }
                assertThrows(ManualTaskConflict::class.java) { other.create(original) }
                assertThrows(ManualTaskConflict::class.java) { other.change(change("one", 2, ManualTaskStatus.CANCELLED, 100)) }
                assertEquals(ManualTaskStatus.OPEN, other.snapshots().single { it.id == "two" }.status)
                assertEquals(1L, other.snapshots().single { it.id == "two" }.version)
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); other.wipeKey() }
        }
    }
    @Test fun actualCreateAndTransitionRollbackAndLostCommitAckRetryExactlyOneDurableVersion(): Unit = Fixture().use { f ->
        val intent = create("one", "合成正式待办")
        f.db.beforeCommit = { error("Synthetic transaction failure") }
        assertThrows(ManualTaskStorageFailure::class.java) { f.store.create(intent) }
        f.db.beforeCommit = null; assertTrue(f.store.snapshots().isEmpty())
        loseCommit(f) { assertThrows(ManualTaskStorageFailure::class.java) { f.store.create(intent) } }
        f.reopen(); val bytes = f.blob("one")
        assertEquals(1L, f.store.create(intent).version); assertArrayEquals(bytes, f.blob("one"))
        assertEquals(1, f.store.snapshots().size)
        val complete = change("one", 1, ManualTaskStatus.COMPLETED)
        f.db.beforeCommit = { error("Synthetic transition abort") }
        assertThrows(ManualTaskStorageFailure::class.java) { f.store.change(complete) }
        f.db.beforeCommit = null; assertEquals(ManualTaskStatus.OPEN, f.store.snapshots().single().status)
        loseCommit(f) { assertThrows(ManualTaskStorageFailure::class.java) { f.store.change(complete) } }
        f.reopen(); val terminal = f.blob("one")
        assertEquals(2L, f.store.change(complete).version); assertArrayEquals(terminal, f.blob("one"))
        assertEquals(1, f.store.snapshots().size)
    }
    @Test fun explicitTasksNeverModifyRecordDraftTagJobsOrAuthorityAndSurviveRecordDeletion(): Unit = Fixture().use { f ->
        val old = f.legacy(); f.store.create(create("one", "原文不是自动任务"))
        f.store.change(change("one", 1, ManualTaskStatus.CANCELLED)); assertEquals(old, f.legacy())
        f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1,connection=NULL,consent=NULL WHERE entryId=?", LEGACY_ID)
        assertEquals(ManualTaskStatus.CANCELLED, f.store.snapshots().single().status)
        f.reopen(); assertEquals("原文不是自动任务", f.store.snapshots().single().title)
    }
    @Test fun titleUnicodeBoundsImmutabilityAndRedactionRejectInvalidCommandsWithoutWriting(): Unit = Fixture().use { f ->
        for (title in listOf("", "　", "\u200b", "\ud800", "x\n", "🙂".repeat(241), "a".repeat(961)))
            assertThrows(IllegalArgumentException::class.java) { create("one", title) }
        assertThrows(IllegalArgumentException::class.java) { ManualTaskCreate("one", "x", 55, "Invalid/Zone", "bad") }
        assertThrows(IllegalArgumentException::class.java) { ManualTaskMutation("one", Long.MAX_VALUE, ManualTaskStatus.COMPLETED, 99, "bad") }
        assertThrows(IllegalArgumentException::class.java) { change("one", 1, ManualTaskStatus.OPEN) }
        assertTrue(f.store.snapshots().isEmpty())
        val intent = create("one", "　合成🙂e\u0301　"); assertEquals("合成🙂e\u0301", intent.title)
        val snapshot = f.store.create(intent); assertFalse(intent.toString().contains("合成")); assertFalse(snapshot.toString().contains("合成"))
        val list = f.store.snapshots(); assertThrows(UnsupportedOperationException::class.java) { (list as MutableList).clear() }
        assertEquals(1, f.store.snapshots().size)
    }
    @Test fun sharedGatewayWaitsForTaskTransactionBeforeWipingKeyAndRejectsLateBorrowers(): Unit = Fixture().use { f ->
        f.store.create(create("one", "合成等待"))
        val gateway = DrainingRecordsSqlDatabase(f.db); val tasks = SqlManualTaskRepository(gateway, KEY)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val wiped = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            f.db.beforeSql = { sql -> if (sql.startsWith("SELECT id,version")) { entered.countDown(); check(release.await(1, TimeUnit.SECONDS)) } }
            val read = pool.submit<List<ManualTaskSnapshot>> { tasks.snapshots() }; assertTrue(entered.await(1, TimeUnit.SECONDS))
            val close = pool.submit { gateway.stopAndAwaitIdle(); tasks.wipeKey(); wiped.countDown() }
            assertFalse(wiped.await(50, TimeUnit.MILLISECONDS)); release.countDown()
            assertEquals(1, read.get(1, TimeUnit.SECONDS).size); close.get(1, TimeUnit.SECONDS); f.db.beforeSql = null
            assertThrows(ManualTaskStorageFailure::class.java) { tasks.snapshots() }
            assertThrows(IllegalStateException::class.java) { gateway.transaction { it.query("SELECT 1") } }
            assertEquals(1, f.store.snapshots().size)
        } finally { release.countDown(); f.db.beforeSql = null; pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }
    internal class Fixture(migrate: Boolean = true) : Closeable {
        val directory = Files.createTempDirectory("aiyou-manual-tasks-")
        private val file = directory.resolve("records.sqlite"); private val bridge = directory.resolve("sqlite_bridge.py")
        var db: PythonSqliteDatabase; private set
        var store: SqlManualTaskRepository; private set
        init {
            javaClass.getResourceAsStream("/sqlite_bridge.py").use { Files.copy(checkNotNull(it), bridge) }
            db = PythonSqliteDatabase(file, bridge, true, 5)
            db.transaction { sql ->
                sql.execute("INSERT INTO entries VALUES(?,?,?,?,?)", listOf(LEGACY_ID, 1, 5, "UTC", ProcessingPolicy.LOCAL_ONLY.name))
                sql.execute("INSERT INTO entry_revisions VALUES(?,?,?,?,?)", listOf(LEGACY_ID, 1, "合成原文", ManualTagSqlTest.hash("合成原文"), 5))
                sql.execute("INSERT INTO capture_draft VALUES(1,?,?,?)", listOf("draft", 1, "合成草稿"))
                sql.execute("INSERT INTO local_jobs VALUES(?,?,?,?,?)", listOf("local", LEGACY_ID, 1, "LOCAL_INDEX", "PENDING"))
                sql.execute("INSERT INTO attachments VALUES(?,?,?,?,?,?,?)", listOf("legacy-attachment", LEGACY_ID, 1, "image/png", "0".repeat(64), 1, "WRITING"))
                sql.execute("INSERT INTO remote_source_access VALUES(?,1,0,?,?)", listOf(LEGACY_ID, byteArrayOf(1, 2), byteArrayOf(3, 4)))
                sql.execute("INSERT INTO remote_text_jobs VALUES(?,?,?,?,?,?,?,?,?,?,?)", listOf("legacy-remote", "legacy-binding", LEGACY_ID, 1, 1, "ORGANIZATION", "synthetic", byteArrayOf(5, 6), byteArrayOf(7, 8), "COMMITTED", ManualTagSqlTest.hash("合成原文")))
                sql.execute("INSERT INTO text_annotations VALUES(?,?,?,?,?,?,?)", listOf("legacy-remote", LEGACY_ID, 1, 1, byteArrayOf(9, 10), "0".repeat(64), 5))
            }
            val tags = SqlManualTagRepository(db, KEY)
            try { tags.replace(ManualTagMutation(LEGACY_ID, 0, listOf("合成旧标签"), "legacy-tag")) } finally { tags.wipeKey() }
            if (migrate) migrate()
            store = SqlManualTaskRepository(db, KEY)
        }
        fun migrate() = db.transaction { sql ->
            ManualTaskSqlSchema.migration5To6.forEach { sql.execute(it) }; sql.execute(ManualTaskSqlSchema.guard); sql.execute("PRAGMA user_version=6")
        }
        fun second() = PythonSqliteDatabase(file, bridge, false, 5)
        fun reopen() { store.wipeKey(); db.close(); db = second(); store = SqlManualTaskRepository(db, KEY) }
        fun blob(id: String) = db.read("SELECT ciphertext FROM manual_tasks WHERE id=?", id).single().single() as ByteArray
        fun tables() = db.read("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('room_master_table','android_metadata') AND name NOT LIKE 'sqlite_%'").map { it.single() as String }
        fun legacy() = listOf("entries", "entry_revisions", "capture_draft", "local_jobs", "attachments", "remote_source_access", "remote_text_jobs", "text_annotations", "entry_manual_tags").flatMap { table ->
            db.read("SELECT * FROM $table ORDER BY 1").map { listOf(table) + it.map { cell -> if (cell is ByteArray) cell.toList() else cell } }
        }
        override fun close() { db.beforeCommit = null; db.afterSql = null; db.beforeSql = null; store.wipeKey(); db.close()
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    companion object {
        const val LEGACY_ID = "task-independent-source"
        val KEY = ByteArray(32) { (it + 41).toByte() }
        fun create(id: String, title: String = "合成待办") = ManualTaskCreate(id, title, 55, "Asia/Shanghai", "create-$id")
        fun change(id: String, version: Long, status: ManualTaskStatus, time: Long = 99) =
            ManualTaskMutation(id, version, status, time, "change-$id-$status-$time")
        private fun contains(bytes: ByteArray, marker: ByteArray) = bytes.indices.any { at ->
            at + marker.size <= bytes.size && marker.indices.all { bytes[at + it] == marker[it] }
        }
        private fun loseCommit(f: Fixture, block: () -> Unit) {
            var lost = false
            f.db.afterSql = { if (it == "COMMIT" && !lost) { lost = true; error("Synthetic lost acknowledgement") } }
            try { block(); assertTrue(lost) } finally { f.db.afterSql = null }
        }
    }
}
