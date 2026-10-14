package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.serialization.json.*
import com.t1doo.aiyou.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*

/** Actual app tokenizer, FTS4, SQL, tag AEAD and private proof; synthetic host driver only. */
class LocalSearchSqlTest {
    @Test fun actualFtsCreationAndOptionsMatchRetrievedRoomV7Export(): Unit = Fixture().use { f ->
        val schema = Json.parseToJsonElement(java.nio.file.Files.readString(PythonSqliteDatabase.repositoryRoot().resolve(
            "core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/7.json"))).jsonObject["database"]!!.jsonObject
        val entity = schema["entities"]!!.jsonArray.single { it.jsonObject["tableName"]!!.jsonPrimitive.content == "local_search_fts" }.jsonObject
        val expected = entity["createSql"]!!.jsonPrimitive.content.replace("$" + "{TABLE_NAME}", "local_search_fts")
        val actual = f.base.db.read("SELECT sql FROM sqlite_master WHERE name='local_search_fts'").single().single() as String
        fun normalize(value: String) = value.replace("IF NOT EXISTS ", "").replace(Regex("\\s+"), "").lowercase()
        assertEquals(normalize(expected), normalize(actual))
    }
    @Test fun realFts4RecallsChineseEnglishSubstringsAndDigitsWithNoUserMatchSyntax(): Unit = Fixture().use { f ->
        f.seed("a", "和小林喝咖啡，Meeting42 🙂e\u0301", 100)
        f.seed("b", "其他合成文字", 200)
        f.drain()
        var matchQueries = 0
        f.base.db.beforeSql = { if (it.contains(" MATCH ?")) matchQueries++ }
        for (q in listOf("小林", "咖啡", "eeti", "42")) {
            assertEquals(listOf("a"), f.index.candidates(LocalSearchRequest(q)).entries.map { it.id })
        }
        assertEquals(4, matchQueries)
        assertTrue(f.base.db.read("SELECT bodyTokens FROM local_search_fts WHERE entryId='a'").single().single()
            .let { it is String && !it.contains("小林") && it.contains("w") && it.contains("n") })
        assertTrue(f.index.candidates(LocalSearchRequest("OR NOT * \" % _")).entries.isNotEmpty())
        assertFalse(LocalSearchRequest("synthetic private query").toString().contains("private"))
    }
    @Test fun productionLiteralVerificationRejectsNgramFalseRecallAndQuotesExactCodePointRange(): Unit = Fixture().use { f ->
        f.seed("a", "🙂e\u0301小林咖啡", 100)
        f.seed("b", "小林 林咖 咖啡", 200)
        f.drain()
        assertEquals(setOf("a", "b"), f.index.candidates(LocalSearchRequest("小林咖啡")).entries.map { it.id }.toSet())
        val actual = runBlocking { LocalOriginalSearchMatcher.indexed(f.entries(),
            OriginalSearchInput("小林咖啡"), f.index) }
        assertEquals(listOf("a"), actual.hits.map { it.entry.id })
        val hit = actual.hits.single()
        assertEquals(hit.quote, hit.entry.originalText.substring(hit.entry.originalText.offsetByCodePoints(0, hit.startCodePoint),
            hit.entry.originalText.offsetByCodePoints(0, hit.endCodePoint)))
        assertTrue(f.index.publishCurrent(actual.proof!!) {})
    }
    @Test fun singleCharacterPunctuationAndExactFallbackPreserveLiteralUnicodeAndCaptureZoneDates(): Unit = Fixture().use { f ->
        val time = Instant.parse("2026-10-13T18:00:00Z").toEpochMilli()
        f.seed("a", "小林 🙂MiXeD .* %_[ e\u0301", time, "Asia/Shanghai")
        f.seed("b", "小林 mixed", time, "UTC")
        f.drain()
        val one = runBlocking { LocalOriginalSearchMatcher.indexed(f.entries(), OriginalSearchInput("林"), f.index) }
        assertEquals(setOf("a", "b"), one.hits.map { it.entry.id }.toSet())
        val exact = runBlocking { LocalOriginalSearchMatcher.indexed(f.entries(),
            OriginalSearchInput("🙂MiXeD .* %_[", "2026-10-14", "2026-10-14", OriginalSearchMode.EXACT), f.index) }
        assertEquals(listOf("a"), exact.hits.map { it.entry.id })
        assertThrows(LocalSearchInvalid::class.java) { f.index.candidates(LocalSearchRequest("q", from = "2026-02-30")) }
    }
    @Test fun sourceEditInvalidatesBeforeWorkerAndPendingCurrentOriginalRemainsSearchable(): Unit = Fixture().use { f ->
        f.drain()
        val before = f.index.candidates(LocalSearchRequest("合成"))
        OriginalExportSqlTest.revise(f.base.db, ID, "合成新版本咖啡", 2)
        assertTrue(f.base.db.read("SELECT * FROM local_search_fts WHERE entryId=?", ID).isEmpty())
        assertFalse(f.index.publishCurrent(before) { fail("Old edit result published") })
        val pending = f.index.candidates(LocalSearchRequest("咖啡"))
        assertTrue(pending.pending)
        assertEquals(2, pending.entries.single().currentRevision)
        f.drain()
        assertFalse(f.index.candidates(LocalSearchRequest("咖啡")).pending)
        assertEquals(2L, f.base.db.read("SELECT sourceRevision FROM local_search_documents WHERE entryId=?", ID).single().single())
    }
    @Test fun tagAeadReplacementInvalidatesIndependentVersionAndExactTagFilterUsesNewNames(): Unit = Fixture().use { f ->
        f.drain()
        val before = f.index.candidates(LocalSearchRequest("合成", tag = "合成旧标签"))
        f.tags.replace(ManualTagMutation(ID, 1, listOf("合成新标签"), "synthetic-new-tag"))
        assertEquals(1L, f.base.db.read("SELECT currentRevision FROM entries WHERE id=?", ID).single().single())
        assertFalse(f.index.resolve(before, ID) { fail("Old tag result opened") })
        assertTrue(f.index.candidates(LocalSearchRequest("", tag = "合成旧标签")).entries.isEmpty())
        assertEquals(listOf(ID), f.index.candidates(LocalSearchRequest("", tag = "合成新标签")).entries.map { it.id })
        f.drain()
        assertEquals(2L, f.base.db.read("SELECT tagVersion FROM local_search_documents WHERE entryId=?", ID).single().single())
    }
    @Test fun recycleAndRestoreNeverExposeDeletedOriginalOrReuseOldVisibilityProof(): Unit = Fixture().use { f ->
        f.drain()
        val before = f.index.candidates(LocalSearchRequest("合成"))
        val bin = SqlRecycleBinRepository(f.base.db, f.tags)
        val current = bin.read(ID)
        val deleted = bin.apply(RecycleMutation(SourceVersion(ID, 1), current.generation, false, "synthetic-move"))
        assertTrue(f.index.candidates(LocalSearchRequest("合成")).entries.isEmpty())
        assertFalse(f.index.resolve(before, ID) { fail("Deleted original opened") })
        assertTrue(f.base.db.read("SELECT * FROM local_search_fts WHERE entryId=?", ID).isEmpty())
        bin.apply(RecycleMutation(SourceVersion(ID, 1), deleted.generation, true, "synthetic-restore"))
        val restored = f.index.candidates(LocalSearchRequest("合成"))
        assertEquals(ProcessingPolicy.AI_NONE, restored.entries.single().policy)
        assertTrue(restored.pending)
        assertFalse(f.index.publishCurrent(before) { fail("Visibility ABA reused proof") })
        f.drain()
        assertFalse(f.index.candidates(LocalSearchRequest("合成")).pending)
    }
    @Test fun authenticatedNewCopyRestoreIsIndexedWithoutTouchingPackageReceipts(): Unit = Fixture().use { f ->
        val pack = RecordPackageCipherTest.verified()
        val id = UUID.randomUUID().toString()
        SqlRecordPackageRepository(f.base.db, f.tags).restore(pack, listOf(id), 100)
        val receipts = f.nonIndexJobs()
        f.drain()
        assertEquals(receipts, f.nonIndexJobs())
        assertTrue(f.base.db.read("SELECT entryId FROM local_search_documents WHERE entryId=?", id).isNotEmpty())
        val proof = f.index.candidates(LocalSearchRequest(""))
        assertEquals(ProcessingPolicy.AI_NONE, proof.entries.single { it.id == id }.policy)
        assertTrue(f.index.resolve(proof, id) { assertEquals(pack.records.single().originalText, it.originalText) })
    }
    @Test fun realDocumentInsertAbortRollsBackWholeBatchEpochFtsAndJobs(): Unit = Fixture().use { f ->
        f.seed("a", "合成首条", 10); f.seed("z", "合成末条", 11)
        val epoch = f.index.epoch(); val jobs = f.base.db.read("SELECT * FROM local_jobs ORDER BY id")
        f.base.db.write("CREATE TEMP TRIGGER synthetic_index_abort BEFORE INSERT ON local_search_documents " +
            "WHEN NEW.entryId='z' BEGIN SELECT RAISE(ABORT,'synthetic index failure'); END")
        assertThrows(LocalSearchFailure::class.java) { f.index.batch() }
        assertEquals(epoch, f.index.epoch())
        assertEquals(jobs, f.base.db.read("SELECT * FROM local_jobs ORDER BY id"))
        assertTrue(f.base.db.read("SELECT * FROM local_search_fts").isEmpty())
        assertTrue(f.base.db.read("SELECT * FROM local_search_documents").isEmpty())
    }
    @Test fun boundedBatchConfirmsAllCurrentIndexIdsAndPreservesEveryNonIndexReceipt(): Unit = Fixture().use { f ->
        repeat(10) { f.seed("a-" + it, "合成词元" + it, it.toLong()) }
        f.base.db.write("INSERT INTO local_jobs VALUES('index-second',?,1,'LOCAL_INDEX','PENDING')", ID)
        f.base.db.write("INSERT INTO local_jobs VALUES('synthetic-completed',?,1,'PACKAGE_RESTORE','CONFIRMED:synthetic')", ID)
        val preserved = f.nonIndexJobs()
        val first = f.index.batch()
        assertEquals(8, first.processed); assertTrue(first.remaining)
        val second = f.index.batch()
        assertEquals(3, second.processed); assertFalse(second.remaining)
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED"),
            f.base.db.read("SELECT state FROM local_jobs WHERE entryId=? AND operation='LOCAL_INDEX' ORDER BY id", ID).map { it.single() })
        assertEquals(preserved, f.nonIndexJobs())
        assertEquals(0, f.index.batch().processed)
    }
    @Test fun coldReopenUsesPersistedFtsAndRejectsAnotherOwnersProof(): Unit = Fixture().use { f ->
        f.drain(); val old = f.index.candidates(LocalSearchRequest("合成"))
        val rows = f.base.db.read("SELECT * FROM local_search_documents")
        f.reopen()
        assertEquals(rows, f.base.db.read("SELECT * FROM local_search_documents"))
        assertEquals(0, f.index.batch().processed)
        assertEquals(listOf(ID), f.index.candidates(LocalSearchRequest("合成")).entries.map { it.id })
        assertFalse(f.index.publishCurrent(old) { fail("Foreign owner proof accepted") })
    }
    @Test fun coldReopenChangedUnicodeProfileFallsBackBeforeBoundedRebuildAndInvalidatesOldProofs(): Unit =
        Fixture("v1:" + "a".repeat(64)).use { f ->
            f.drain()
            val before = f.index.candidates(LocalSearchRequest("合成"))
            // Simulate old Unicode tables having emitted no new-runtime bigram.
            // Keep source/hash/tag/generation/job/document binding exactly intact.
            f.base.db.write("UPDATE local_search_fts SET bodyTokens='' WHERE entryId=?", ID)
            assertTrue(f.index.candidates(LocalSearchRequest("合成")).entries.isEmpty())
            val bindings = f.base.db.read("SELECT * FROM local_search_documents")
            f.reopen("v1:" + "b".repeat(64))
            assertEquals(bindings, f.base.db.read("SELECT * FROM local_search_documents"))
            val fallback = f.index.candidates(LocalSearchRequest("合成"))
            assertEquals(listOf(ID), fallback.entries.map { it.id })
            assertTrue(fallback.pending)
            assertTrue(f.index.publishCurrent(fallback) {})
            assertFalse(f.index.publishCurrent(before) { fail("Old runtime owner accepted") })
            f.drain()
            assertTrue(f.index.epoch() > fallback.epoch)
            assertFalse(f.index.resolve(fallback, ID) { fail("Pre-profile rebuild proof accepted") })
            assertFalse(f.index.candidates(LocalSearchRequest("合成")).pending)
            assertEquals("v1:" + "b".repeat(64),
                f.base.db.read("SELECT tokenizerProfile FROM local_search_documents").single().single())
        }
    @Test fun unknownDocumentOrRootProfileCannotExcludeCurrentOriginalFromFallback(): Unit = Fixture().use { f ->
        assertTrue(Regex("v1:[0-9a-f]{64}").matches(LocalSearchTokenizer.profile))
        f.drain()
        f.base.db.write("UPDATE local_search_fts SET bodyTokens='' WHERE entryId=?", ID)
        f.base.db.write("UPDATE local_search_documents SET tokenizerProfile='UNKNOWN' WHERE entryId=?", ID)
        assertEquals(listOf(ID), f.index.candidates(LocalSearchRequest("合成")).entries.map { it.id })
        f.drain()
        val before = f.index.candidates(LocalSearchRequest("合成"))
        f.base.db.write("UPDATE local_search_fts SET bodyTokens='' WHERE entryId=?", ID)
        f.base.db.write("UPDATE local_search_epoch SET epoch=epoch+1,tokenizerProfile='UNKNOWN'")
        val fallback = f.index.candidates(LocalSearchRequest("合成"))
        assertTrue(fallback.pending)
        assertEquals(listOf(ID), fallback.entries.map { it.id })
        assertFalse(f.index.publishCurrent(before) { fail("Root profile changed") })
        f.drain()
        assertFalse(f.index.candidates(LocalSearchRequest("合成")).pending)
    }
    @Test fun interruptedPartialRebuildReopensAndResumesWithoutMissingOriginalsOrNonIndexReceipts(): Unit = Fixture().use { f ->
        repeat(20) { f.seed("a-" + it.toString().padStart(2, '0'), "合成中断重建咖啡" + it, it.toLong()) }
        val receipts = f.nonIndexJobs()
        f.index.rebuild()
        val first = f.index.batch()
        assertEquals(8, first.processed); assertTrue(first.remaining)
        val old = f.index.candidates(LocalSearchRequest("咖啡"))
        assertEquals(21, old.entries.size) // Legacy is a literal fallback candidate.
        f.reopen()
        assertEquals(8, f.base.db.read("SELECT * FROM local_search_documents").size)
        assertFalse(f.index.publishCurrent(old) { fail("Retired partial owner") })
        val pending = f.index.candidates(LocalSearchRequest("咖啡"))
        assertTrue(pending.pending)
        assertEquals(21, pending.entries.size)
        assertTrue(f.index.publishCurrent(pending) {})
        f.drain()
        assertFalse(f.index.publishCurrent(pending) { fail("Earlier partial epoch") })
        assertEquals(receipts, f.nonIndexJobs())
        assertEquals(21, f.base.db.read("SELECT * FROM local_search_documents").size)
        assertFalse(f.index.candidates(LocalSearchRequest("咖啡")).pending)
    }
    @Test fun boundedBatchChecksNextOriginalBudgetBeforeLoadingItsText(): Unit = Fixture().use { f ->
        repeat(6) { f.seed("a-" + it, "合".repeat(100_000), it.toLong()) }
        val readIds = ArrayList<String>()
        f.base.db.afterSql = { query ->
            if (query.startsWith("SELECT e.id,e.currentRevision")) readIds += query
        }
        try {
            val first = f.index.batch()
            assertEquals(1, first.processed)
            assertTrue(first.remaining)
            assertEquals(1, readIds.size)
        } finally { f.base.db.afterSql = null }
        f.drain()
        assertEquals(7, f.base.db.read("SELECT * FROM local_search_documents").size)
    }
    @Test fun missingDuplicatedAndStaleFtsBindingsFallbackEvenWhenJobIsSucceeded(): Unit = Fixture().use { f ->
        f.drain()
        f.base.db.write("DELETE FROM local_search_fts WHERE entryId=?", ID)
        assertTrue(f.index.candidates(LocalSearchRequest("合成")).pending)
        f.drain()
        f.base.db.write("INSERT INTO local_search_fts SELECT * FROM local_search_fts WHERE entryId=?", ID)
        assertEquals(listOf(ID), f.index.candidates(LocalSearchRequest("合成")).entries.map { it.id })
        f.drain()
        assertEquals(1, f.base.db.read("SELECT * FROM local_search_fts WHERE entryId=?", ID).size)
        f.base.db.write("UPDATE local_search_documents SET tokenizerVersion=99 WHERE entryId=?", ID)
        assertTrue(f.index.candidates(LocalSearchRequest("合成")).pending)
        f.drain()
        assertEquals(1L, f.base.db.read("SELECT tokenizerVersion FROM local_search_documents").single().single())
    }
    @Test fun cumulativeCharacterBudgetFailsWholeQueryWithoutPartialResult(): Unit = Fixture().use { f ->
        repeat(11) { f.seed("a-" + it, "合".repeat(100_000), it.toLong()) }
        assertThrows(LocalSearchTooBroad::class.java) { f.index.candidates(LocalSearchRequest("不存在", exact = true)) }
    }
    @Test fun exactDateFilteringPrecedesCandidateLimitAndBroadQueryNeverTruncatesSilently(): Unit = Fixture().use { f ->
        repeat(1100) { f.seed("a-" + it.toString().padStart(4, '0'), "合成范围", if (it == 1099)
            Instant.parse("2026-10-14T12:00:00Z").toEpochMilli() else 100) }
        assertEquals(listOf("a-1099"), f.index.candidates(LocalSearchRequest("合成", from = "2026-10-14", through = "2026-10-14"))
            .entries.map { it.id })
        assertThrows(LocalSearchTooBroad::class.java) { f.index.candidates(LocalSearchRequest("合成")) }
    }
    @Test fun rebuildNeverResetsEpochAndCorruptAeadMissingEpochOrExhaustionFailClosed(): Unit = Fixture().use { f ->
        f.drain(); val before = f.index.candidates(LocalSearchRequest("合成"))
        val next = f.index.rebuild()
        assertTrue(next > before.epoch)
        assertFalse(f.index.publishCurrent(before) { fail("Rebuild reset epoch") })
        f.drain()
        f.base.db.write("UPDATE entry_manual_tags SET version=version+1,lastMutationId='synthetic-bad',ciphertext=? WHERE entryId=?",
            byteArrayOf(1, 2, 3), ID)
        assertThrows(LocalSearchFailure::class.java) { f.index.candidates(LocalSearchRequest("not-a-hit")) }
        assertThrows(LocalSearchFailure::class.java) { f.index.batch() }
        f.base.db.write("DELETE FROM local_search_epoch")
        assertThrows(LocalSearchFailure::class.java) { f.index.rebuild() }
        assertThrows(LocalSearchFailure::class.java) { f.index.publishCurrent(before) {} }
        f.base.db.write("INSERT INTO local_search_epoch VALUES(1,?,1,'UNKNOWN')", Long.MAX_VALUE)
        assertThrows(LocalSearchFailure::class.java) { f.index.epoch() }
        assertThrows(LocalSearchFailure::class.java) { f.index.rebuild() }
    }
    @Test fun realWriteLeaseBlocksIndependentCommitUntilActualPublicationAndDrainsBeforeClose(): Unit = Fixture().use { f ->
        f.drain()
        val gate = DrainingRecordsSqlDatabase(f.base.db)
        val owner = SqlLocalSearchRepository(gate, f.tags)
        val proof = owner.candidates(LocalSearchRequest("合成"))
        val second = f.base.second(); val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        try {
            second.beforeSql = { if (it == "BEGIN IMMEDIATE") entered.countDown() }
            var future: Future<*>? = null
            assertTrue(owner.publishCurrent(proof) {
                future = pool.submit { second.write("UPDATE entries SET capturedAtMillis=capturedAtMillis+1 WHERE id=?", ID) }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { future!!.get(100, TimeUnit.MILLISECONDS) }
                assertEquals(proof.epoch, owner.epoch()) // real nested savepoint, same lease
            })
            future!!.get(2, TimeUnit.SECONDS)
            assertFalse(owner.resolve(proof, ID) { fail("Post-publication update reused proof") })
            val current = owner.candidates(LocalSearchRequest("合成"))
            val publishing = CountDownLatch(1); val release = CountDownLatch(1)
            val active = pool.submit<Boolean> { owner.publishCurrent(current) {
                publishing.countDown(); assertTrue(release.await(1, TimeUnit.SECONDS))
            } }
            assertTrue(publishing.await(1, TimeUnit.SECONDS))
            val closing = pool.submit { gate.stopAndAwaitIdle() }
            assertThrows(TimeoutException::class.java) { closing.get(100, TimeUnit.MILLISECONDS) }
            release.countDown(); assertTrue(active.get(2, TimeUnit.SECONDS)); closing.get(2, TimeUnit.SECONDS)
            assertThrows(LocalSearchFailure::class.java) { owner.epoch() }
        } finally { second.beforeSql = null; second.close(); pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    internal class Fixture(private var profile: String? = null) : AutoCloseable {
        val base = ManualTaskSqlTest.Fixture()
        var tags = SqlManualTagRepository(base.db, ManualTaskSqlTest.KEY); private set
        var index: SqlLocalSearchRepository; private set
        init {
            base.db.transaction { sql ->
                RemoteTextSqlSchema.guards.forEach { sql.execute(it) }
                sql.execute(ManualTagSqlSchema.guard)
                LocalSearchSqlSchema.migration6To7.forEach { sql.execute(it) }
                LocalSearchSqlSchema.guards.forEach { sql.execute(it) }
                sql.execute("PRAGMA user_version=7")
            }
            index = newIndex()
        }
        fun seed(id: String, text: String, captured: Long, zone: String = "UTC") = base.db.transaction { sql ->
            sql.execute("INSERT INTO entries VALUES(?,1,?,?,'LOCAL_ONLY')", listOf(id, captured, zone))
            sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,?)", listOf(id, text, OriginalExports.hash(text), captured))
        }
        fun drain() { var attempts = 0; do { check(++attempts < 2000); val progress = index.batch() } while (progress.remaining) }
        fun entries() = index.candidates(LocalSearchRequest("", exact = true)).entries
        fun nonIndexJobs() = base.db.read("SELECT * FROM local_jobs WHERE operation!='LOCAL_INDEX' ORDER BY id")
        private fun newIndex() = SqlLocalSearchRepository(base.db, tags) { profile ?: LocalSearchTokenizer.profile }
        fun reopen(nextProfile: String? = profile) {
            tags.wipeKey(); base.reopen(); profile = nextProfile
            tags = SqlManualTagRepository(base.db, ManualTaskSqlTest.KEY); index = newIndex()
        }
        override fun close() { tags.wipeKey(); base.close() }
    }
    companion object { const val ID = ManualTaskSqlTest.LEGACY_ID }
}
