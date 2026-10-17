package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import org.junit.Assert.*
import org.junit.Test

class LocalProcessingSqlTest {
    @Test fun productPolicyAbaRevokesSearchRecapAndSourceTagProofsButPreservesManualData(): Unit = SourceTagFixture().use { f ->
        val port=SqlLocalProcessingRepository(f.base.gateway)
        val p=f.preview();val adopted=f.store.apply(f.request(p))
        val staleTag=f.request(index=1,mutation="late-tag")
        val favorites=SqlRecordFavoriteRepository(f.base.gateway)
        favorites.set(RecordFavoriteMutation("source",2,OriginalExports.hash(f.original),0,true,"favorite",port.versions().getValue("source").generation))
        val tasks=SqlManualTaskRepository(f.base.gateway,LocalCandidateFixture.KEY)
        try {
            tasks.create(ManualTaskCreate("manual-task","合成人工待办",10,"UTC","create-task"))
            val original=f.db.read("SELECT * FROM entry_revisions ORDER BY entryId,revision")
            val tagBytes=f.bytes().copyOf();val task=tasks.snapshots().single()
            val search=SqlLocalSearchRepository(f.base.gateway,f.tags)
            while(search.batch().remaining) Unit
            val lookup=search.candidates(LocalSearchRequest("合成"))
            val recap=SqlFactRecapRepository(f.base.gateway,f.tags)
            val range=FactRecapRange(java.time.LocalDate.of(1970,1,1),FactRecapPeriod.DAY,"UTC")
            val cards=recap.capture(range)
            val none=port.change(LocalProcessingChange(port.read(f.source),LocalProcessingChoice.AI_NONE,"off"))
            assertEquals(adopted.names,f.tags.read("source").names)
            assertEquals(f.original,f.db.read("SELECT originalText FROM entry_revisions WHERE entryId='source' AND revision=2").single().single())
            while(search.batch().remaining) Unit // AI_NONE still supports manual original lookup.
            assertTrue(search.candidates(LocalSearchRequest("合成")).entries.any { it.id=="source" })
            port.change(LocalProcessingChange(none,LocalProcessingChoice.LOCAL_ONLY,"on"))
            assertThrows(SourceTagConflict::class.java) { f.store.apply(staleTag) }
            assertFalse(search.publishCurrent(lookup) { fail("old lookup") })
            assertFalse(recap.publishCurrent(cards) { fail("old recap") })
            assertEquals(original,f.db.read("SELECT * FROM entry_revisions ORDER BY entryId,revision"))
            assertArrayEquals(tagBytes,f.bytes());assertEquals(task,tasks.snapshots().single())
            assertTrue(favorites.read("source").isFavorite)
        } finally { tasks.wipeKey() }
    }
    @Test fun lostCommitAckColdReopenAndExactReplayAdvanceOnlyOnce(): Unit = LocalCandidateFixture().use { f ->
        var port = SqlLocalProcessingRepository(f.gateway)
        val request = LocalProcessingChange(port.read(f.source), LocalProcessingChoice.AI_NONE, "scope-off")
        var lost = false
        f.db.afterSql = { if (it == "COMMIT" && !lost) { lost = true; throw IllegalStateException("Synthetic lost ACK") } }
        assertThrows(LocalProcessingStorageFailure::class.java) { port.change(request) }
        f.db.afterSql = null
        assertEquals(request.expected.generation + 1, port.read(f.source).generation)
        f.reopen(); port = SqlLocalProcessingRepository(f.gateway)
        val actual = port.change(request)
        assertEquals(ProcessingPolicy.AI_NONE, actual.policy)
        assertEquals(request.expected.generation + 1, actual.generation)
        assertEquals(actual, port.change(request))
        assertThrows(LocalProcessingConflict::class.java) { port.change(request.copy(mutationId = "another-intent")) }
        assertThrows(LocalProcessingConflict::class.java) { port.change(request.copy(expected = request.expected.copy(sourceHash = "0".repeat(64)))) }
        assertEquals(1, f.db.read("SELECT id FROM local_jobs WHERE operation LIKE 'LOCAL_POLICY:%'").size)
    }

    @Test fun productPolicyAbaInvalidatesLateLocalResultsAndOldReceiptsWithoutLosingOriginals(): Unit = LocalCandidateFixture().use { f ->
        val port = SqlLocalProcessingRepository(f.gateway)
        val job = f.store.begin(f.start("running")); val ready = f.ready("ready")
        val originals = f.db.read("SELECT * FROM entry_revisions ORDER BY entryId,revision")
        val draft = f.db.read("SELECT * FROM capture_draft")
        val off = LocalProcessingChange(port.read(f.source), LocalProcessingChoice.AI_NONE, "off")
        // One outer transaction: observers cannot see the intermediate AI_NONE state.
        f.db.transaction {
            val none = port.change(off)
            assertThrows(LocalCandidateConflict::class.java) { f.store.begin(f.start("blocked")) }
            port.change(LocalProcessingChange(none, LocalProcessingChoice.LOCAL_ONLY, "on"))
        }
        assertEquals(off.expected.generation + 2, port.read(f.source).generation)
        assertEquals(ProcessingPolicy.LOCAL_ONLY, port.read(f.source).policy)
        assertThrows(LocalProcessingConflict::class.java) { port.change(off) }
        assertThrows(LocalCandidateConflict::class.java) { f.store.complete(job, LocalExcerptRules.extract(job)) }
        assertThrows(LocalCandidateConflict::class.java) { f.store.review(f.review(ready)) }
        assertTrue(f.store.batches(f.source).all { it.state == LocalCandidateState.INVALIDATED && it.items.isEmpty() })
        assertEquals(originals, f.db.read("SELECT * FROM entry_revisions ORDER BY entryId,revision"))
        assertEquals(draft, f.db.read("SELECT * FROM capture_draft"))
        assertEquals(listOf(null, null), f.db.read("SELECT connection,consent FROM remote_source_access WHERE entryId='source'").single())
        assertTrue(f.db.read("SELECT * FROM remote_text_jobs").isEmpty())
        assertEquals(LocalCandidateState.READY, f.ready("fresh-explicit").state)
    }

    @Test fun sourceMemoryProofAndLateMutationAreRejectedAcrossProductPolicyAba(): Unit = SourceMemoryFixture().use { f ->
        val port = SqlLocalProcessingRepository(f.base.gateway)
        val preview = f.store.preview(f.source); val create = f.request(p = preview)
        val memory = f.create("existing"); val mutation = f.change(memory, SourceMemoryAction.ACCEPT)
        val none = port.change(LocalProcessingChange(port.read(f.source), LocalProcessingChoice.AI_NONE, "none"))
        port.change(LocalProcessingChange(none, LocalProcessingChoice.LOCAL_ONLY, "local"))
        assertThrows(SourceMemoryConflict::class.java) { f.store.create(create) }
        assertThrows(SourceMemoryConflict::class.java) { f.store.mutate(mutation) }
        assertTrue(f.store.memories(f.source).all { it.current == null && it.versions.isEmpty() })
        assertTrue(f.db.read("SELECT payload FROM local_candidate_batches").all { (it.single() as ByteArray).isEmpty() })
    }

    @Test fun recycleRestoreAndPermanentFenceRejectPendingChangesAndPriorReceipts(): Unit = LocalCandidateFixture().use { f ->
        val port = SqlLocalProcessingRepository(f.gateway)
        val off = LocalProcessingChange(port.read(f.source), LocalProcessingChoice.AI_NONE, "off")
        port.change(off)
        val tags = SqlManualTagRepository(f.gateway, LocalCandidateFixture.KEY)
        try {
            val recycle = SqlRecycleBinRepository(f.gateway, tags, SqlErasureFileHooks::fenced)
            val before = recycle.read("source")
            val moved = recycle.apply(RecycleMutation(f.source, before.generation, false, "move"))
            assertThrows(LocalProcessingConflict::class.java) { port.read(f.source) }
            assertThrows(LocalProcessingConflict::class.java) { port.change(off) }
            recycle.apply(RecycleMutation(f.source, moved.generation, true, "restore"))
            assertEquals(ProcessingPolicy.AI_NONE, port.read(f.source).policy)
            assertThrows(LocalProcessingConflict::class.java) { port.change(off) }
            val pending = LocalProcessingChange(port.read(f.source), LocalProcessingChoice.LOCAL_ONLY, "pending")
            f.db.transaction { it.execute("INSERT INTO permanent_erasure_receipts VALUES('source',1,?,'erase','PREPARED','','')", listOf(pending.expected.generation)) }
            assertThrows(LocalProcessingConflict::class.java) { port.change(pending) }
            assertFalse(port.versions().containsKey("source"))
        } finally { tags.wipeKey() }
    }

    @Test fun competingConnectionAbortAndGenerationOverflowNeverPartiallyChangeScope(): Unit = LocalCandidateFixture().use { f ->
        val port = SqlLocalProcessingRepository(f.gateway)
        val request = LocalProcessingChange(port.read(f.source), LocalProcessingChoice.AI_NONE, "winner")
        f.db.transaction { it.execute("CREATE TRIGGER synthetic_scope_abort BEFORE INSERT ON local_jobs WHEN NEW.operation LIKE 'LOCAL_POLICY:%' BEGIN SELECT RAISE(ABORT,'synthetic'); END") }
        assertThrows(LocalProcessingStorageFailure::class.java) { port.change(request) }
        assertEquals(request.expected, port.read(f.source))
        f.db.transaction { it.execute("DROP TRIGGER synthetic_scope_abort") }
        f.second().use { other ->
            port.change(request)
            assertThrows(LocalProcessingConflict::class.java) { SqlLocalProcessingRepository(other).change(request.copy(mutationId = "loser")) }
        }
        f.db.transaction { it.execute("UPDATE remote_source_access SET generation=? WHERE entryId='source'", listOf(Long.MAX_VALUE)) }
        val exhausted = LocalProcessingChange(port.read(f.source), LocalProcessingChoice.LOCAL_ONLY, "overflow")
        assertThrows(LocalProcessingStorageFailure::class.java) { port.change(exhausted) }
        assertEquals(exhausted.expected, port.read(f.source))
        assertEquals(1, f.db.read("SELECT id FROM local_jobs WHERE operation LIKE 'LOCAL_POLICY:%'").size)
    }
}
