package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.Closeable
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Actual exported v4, product migration, product AES-GCM and real owned SQLite connections. */
class ManualTagSqlTest {
    @Test fun migrationIsAtomicPreservesEightOldTablesAndReopens(): Unit = Fixture(false).use { f ->
        val old = f.legacy()
        assertEquals(8, f.tables().size)
        assertThrows(IllegalStateException::class.java) { f.db.transaction { sql ->
            ManualTagSqlSchema.migration4To5.forEach { sql.execute(it) }; error("Synthetic migration abort")
        } }
        assertEquals(8, f.tables().size); assertEquals(old, f.legacy())
        f.migrate(); assertEquals(9, f.tables().size); assertEquals(old, f.legacy())
        assertEquals(listOf("entryId", "version", "lastMutationId", "ciphertext"), f.db.read("PRAGMA table_info(entry_manual_tags)").map { it[1] })
        assertEquals("entries", f.db.read("PRAGMA foreign_key_list(entry_manual_tags)").single()[2])
        f.reopen(); assertEquals(5L, f.db.read("PRAGMA user_version").single().single())
        assertEquals(old, f.legacy()); assertEquals(0L, f.store.read(ID).version)
    }
    @Test fun encryptedUnicodeAndEmptySetSurviveColdReopenWithoutPlaintextMarker(): Unit = Fixture().use { f ->
        val names = listOf("合成私密标签🙂e\u0301", "🙂".repeat(40), "Case Keep")
        assertEquals(names, f.store.replace(mutation(0, names, "one")).names)
        val first = f.blob()
        fun assertEncryptedMarkers() {
            for (name in listOf(names[0], "Case Keep")) {
                val marker = name.toByteArray(Charsets.UTF_8)
                assertFalse(contains(f.blob(), marker))
                Files.list(f.directory).use { paths -> paths.filter { it.fileName.toString().startsWith("records") }.forEach {
                    assertFalse("Private tag plaintext cannot occur in live host database/WAL", contains(Files.readAllBytes(it), marker))
                } }
            }
        }
        assertEncryptedMarkers()
        f.reopen(); assertEquals(names, f.store.read(ID).names); assertEncryptedMarkers()
        f.store.replace(mutation(1, names, "two")); assertFalse(first.contentEquals(f.blob()))
        assertFalse(first.copyOfRange(1, 13).contentEquals(f.blob().copyOfRange(1, 13)))
        f.store.replace(mutation(2, emptyList(), "empty")); f.reopen()
        assertEquals(3L, f.store.read(ID).version); assertTrue(f.store.read(ID).names.isEmpty())
        assertThrows(ManualTagConflict::class.java) { f.store.replace(mutation(0, names, "stale-zero")) }
        val marker = names[0].toByteArray(Charsets.UTF_8)
        Files.list(f.directory).use { paths -> paths.filter { it.fileName.toString().startsWith("records") }.forEach { file ->
            assertFalse("Private tag plaintext cannot occur in host database/WAL", contains(Files.readAllBytes(file), marker))
        } }
    }
    @Test fun authenticationRejectsTamperWrongKeyAndEntryVersionMutationSwapWithoutOverwrite(): Unit = Fixture().use { f ->
        f.store.replace(mutation(0, listOf("合成秘密标签"), "one"))
        val original = f.blob()
        assertThrows(ManualTagStorageFailure::class.java) { SqlManualTagRepository(f.db, ByteArray(32) { 99 }).read(ID) }
        val cipher = ManualTagCipher(KEY)
        for ((id, version, operation) in listOf(Triple("other", 1L, "one"), Triple(ID, 2L, "one"), Triple(ID, 1L, "other")))
            assertThrows(Exception::class.java) { cipher.open(id, version, operation, original) }
        cipher.wipe()
        val corrupt = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        f.db.write("UPDATE entry_manual_tags SET version=version+1,ciphertext=? WHERE entryId=?", corrupt, ID)
        assertThrows(ManualTagStorageFailure::class.java) { f.store.read(ID) }
        assertThrows(ManualTagStorageFailure::class.java) { f.store.snapshots() }
        assertThrows(ManualTagStorageFailure::class.java) { f.store.replace(mutation(2, listOf("replacement"), "other")) }
        assertArrayEquals(corrupt, f.blob()); f.reopen()
        assertThrows(ManualTagStorageFailure::class.java) { f.store.read(ID) }; assertArrayEquals(corrupt, f.blob())
    }
    @Test fun independentConnectionsCasAndLatestMutationReplayNeverOverwriteNewerTags(): Unit = Fixture().use { f ->
        f.second().use { second ->
            val other = SqlManualTagRepository(second, KEY)
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            try {
                val outcomes = listOf(f.store, other).mapIndexed { i, store -> pool.submit<String> {
                    go.await(); try { store.replace(mutation(0, listOf("tag-$i"), "race-$i")); "saved" }
                    catch (_: ManualTagConflict) { "conflict" }
                } }; go.countDown()
                assertEquals(listOf("conflict", "saved"), outcomes.map { it.get(2, TimeUnit.SECONDS) }.sorted())
                val current = f.store.read(ID)
                val winner = current.names.single().last()
                val replay = mutation(0, current.names, "race-$winner")
                assertEquals(1L, other.replace(replay).version)
                assertThrows(ManualTagConflict::class.java) { other.replace(mutation(0, listOf("different"), replay.mutationId)) }
                assertThrows(ManualTagConflict::class.java) { other.replace(mutation(1, current.names, replay.mutationId)) }
                f.store.replace(mutation(1, listOf("newer"), "next"))
                assertThrows(ManualTagConflict::class.java) { other.replace(replay) }
                assertEquals(listOf("newer"), other.read(ID).names)
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); other.wipeKey() }
        }
    }
    @Test fun actualRollbackAndLostCommitAcknowledgementKeepStableMutation(): Unit = Fixture().use { f ->
        val intent = mutation(0, listOf("合成标签"), "stable")
        f.db.beforeCommit = { error("Synthetic transaction failure") }
        assertThrows(ManualTagStorageFailure::class.java) { f.store.replace(intent) }
        f.db.beforeCommit = null; assertEquals(0L, f.store.read(ID).version)
        var lost = false
        f.db.afterSql = { sql -> if (sql == "COMMIT" && !lost) { lost = true; error("Synthetic lost acknowledgement") } }
        assertThrows(ManualTagStorageFailure::class.java) { f.store.replace(intent) }
        f.db.afterSql = null; f.reopen()
        assertEquals(1L, f.store.replace(intent).version); assertEquals(1, f.db.read("SELECT * FROM entry_manual_tags").size)
    }
    @Test fun tagsRemainIndependentOfOriginalRevisionDraftJobsAndAuthorizationButDeletedSourceRejectsReplay(): Unit = Fixture().use { f ->
        val old = f.legacy(); val intent = mutation(0, listOf("手工"), "one")
        f.store.replace(intent); assertEquals(old, f.legacy())
        f.db.transaction { sql ->
            sql.execute("INSERT INTO entry_revisions VALUES(?,?,?,?,?)", listOf(ID, 2, "新版原文", hash("新版原文"), 99))
            sql.execute("UPDATE entries SET currentRevision=2 WHERE id=?", listOf(ID))
        }
        assertEquals(listOf("手工"), f.store.read(ID).names)
        f.store.replace(mutation(1, listOf("保留"), "two"))
        f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1,connection=NULL,consent=NULL WHERE entryId=?", ID)
        assertThrows(ManualTagUnavailable::class.java) { f.store.replace(mutation(1, listOf("保留"), "two")) }
        assertThrows(ManualTagUnavailable::class.java) { f.store.read(ID) }
        assertTrue(f.store.snapshots().isEmpty())
        assertThrows(ManualTagUnavailable::class.java) { f.store.replace(ManualTagMutation("missing", 0, listOf("x"), "missing")) }
    }
    @Test fun unicodeCountDuplicateAndOverflowBoundariesAreRedactedAndCannotChangeDisk(): Unit = Fixture().use { f ->
        val invalid = listOf(listOf(""), listOf("\u200b"), listOf("a\n"), listOf("\ud800"), listOf("🙂".repeat(41)),
            List(13) { "$it" }, listOf(" same ", "same"))
        for (names in invalid) assertThrows(IllegalArgumentException::class.java) { mutation(0, names, "invalid") }
        assertThrows(IllegalArgumentException::class.java) { mutation(Long.MAX_VALUE, listOf("x"), "overflow") }
        assertTrue(f.db.read("SELECT * FROM entry_manual_tags").isEmpty())
        val names = mutableListOf("　合成🙂e\u0301　")
        val intent = mutation(0, names, "valid"); names.clear()
        assertEquals(listOf("合成🙂e\u0301"), intent.names)
        assertFalse(intent.toString().contains("合成")); assertFalse(f.store.replace(intent).toString().contains("合成"))
    }
    @Test fun sharedGatewayDrainsRealTagTransactionBeforeKeyWipeAndRejectsNewBorrowers(): Unit = Fixture().use { f ->
        val gateway = DrainingRecordsSqlDatabase(f.db); val store = SqlManualTagRepository(gateway, KEY)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val wiped = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            f.db.beforeSql = { sql -> if (sql.startsWith("SELECT id FROM entries")) { entered.countDown(); check(release.await(1, TimeUnit.SECONDS)) } }
            val read = pool.submit<ManualTagSnapshot> { store.read(ID) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val close = pool.submit { gateway.stopAndAwaitIdle(); store.wipeKey(); wiped.countDown() }
            assertFalse(wiped.await(50, TimeUnit.MILLISECONDS)); release.countDown()
            assertEquals(0L, read.get(1, TimeUnit.SECONDS).version); close.get(1, TimeUnit.SECONDS)
            f.db.beforeSql = null; gateway.stopAndAwaitIdle()
            assertThrows(ManualTagStorageFailure::class.java) { store.read(ID) }
            assertThrows(IllegalStateException::class.java) { gateway.transaction { it.query("SELECT 1") } }
            assertEquals(0L, f.store.read(ID).version) // Borrower did not close the owned database.
        } finally { release.countDown(); f.db.beforeSql = null; pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    internal class Fixture(migrate: Boolean = true) : Closeable {
        val directory = Files.createTempDirectory("aiyou-manual-tags-")
        private val file = directory.resolve("records.sqlite")
        private val bridge = directory.resolve("sqlite_bridge.py")
        var db: PythonSqliteDatabase; private set
        var store: SqlManualTagRepository; private set
        init {
            javaClass.getResourceAsStream("/sqlite_bridge.py").use { Files.copy(checkNotNull(it), bridge) }
            db = PythonSqliteDatabase(file, bridge, true, 4)
            db.transaction { sql ->
                sql.execute("INSERT INTO entries VALUES(?,?,?,?,?)", listOf(ID, 1, 5, "UTC", ProcessingPolicy.LOCAL_ONLY.name))
                sql.execute("INSERT INTO entry_revisions VALUES(?,?,?,?,?)", listOf(ID, 1, "合成原文", hash("合成原文"), 5))
                sql.execute("INSERT INTO capture_draft VALUES(1,?,?,?)", listOf("draft", 1, "合成草稿"))
                sql.execute("INSERT INTO local_jobs VALUES(?,?,?,?,?)", listOf("local", ID, 1, "LOCAL_INDEX", "PENDING"))
                sql.execute("INSERT INTO attachments VALUES(?,?,?,?,?,?,?)", listOf("legacy-attachment", ID, 1, "image/png", "0".repeat(64), 1, "WRITING"))
                sql.execute("INSERT INTO remote_source_access VALUES(?,1,0,?,?)", listOf(ID, byteArrayOf(1, 2), byteArrayOf(3, 4)))
                sql.execute("INSERT INTO remote_text_jobs VALUES(?,?,?,?,?,?,?,?,?,?,?)", listOf("legacy-remote", "legacy-binding", ID, 1, 1, "ORGANIZATION", "synthetic", byteArrayOf(5, 6), byteArrayOf(7, 8), "COMMITTED", hash("合成原文")))
                sql.execute("INSERT INTO text_annotations VALUES(?,?,?,?,?,?,?)", listOf("legacy-remote", ID, 1, 1, byteArrayOf(9, 10), "0".repeat(64), 5))
            }
            if (migrate) migrate()
            store = SqlManualTagRepository(db, KEY)
        }
        fun migrate() = db.transaction { sql ->
            ManualTagSqlSchema.migration4To5.forEach { sql.execute(it) }; sql.execute(ManualTagSqlSchema.guard); sql.execute("PRAGMA user_version=5")
        }
        fun second() = PythonSqliteDatabase(file, bridge, false, 4)
        fun reopen() { store.wipeKey(); db.close(); db = second(); store = SqlManualTagRepository(db, KEY) }
        fun blob() = db.read("SELECT ciphertext FROM entry_manual_tags WHERE entryId=?", ID).single().single() as ByteArray
        fun tables() = db.read("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('room_master_table','android_metadata') AND name NOT LIKE 'sqlite_%'").map { it.single() as String }
        fun legacy() = listOf("entries", "entry_revisions", "capture_draft", "local_jobs", "attachments", "remote_source_access", "remote_text_jobs", "text_annotations").flatMap { table ->
            db.read("SELECT * FROM $table ORDER BY 1").map { listOf(table) + it.map { cell -> if (cell is ByteArray) cell.toList() else cell } }
        }
        override fun close() { db.beforeCommit = null; db.afterSql = null; db.beforeSql = null; store.wipeKey(); db.close()
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    companion object {
        const val ID = "manual-source"
        val KEY = ByteArray(32) { (it + 17).toByte() }
        fun mutation(version: Long, names: List<String>, id: String) = ManualTagMutation(ID, version, names, id)
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun contains(bytes: ByteArray, marker: ByteArray) = bytes.indices.any { at ->
            at + marker.size <= bytes.size && marker.indices.all { bytes[at + it] == marker[it] }
        }
    }
}
