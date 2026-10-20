package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual SQL/AEAD and cold file reopen; no Android/process/device claim. */
class LocalCandidateResumeSqlTest {
    @Test fun coldResumeReadsTheSameAuthenticatedBatchWithoutWritingAndCompletesOnlyOnce() = LocalCandidateFixture().use { f ->
        f.store.begin(f.start())
        val before = f.row(); val payload = (before[3] as ByteArray).copyOf(); val originalTables = f.snapshotOld()
        f.reopen()
        val batch = f.store.batches(f.source).single()
        val job = f.store.resume(batch)
        assertEquals(batch.id, job.start.id); assertEquals(batch.createdAtMillis, job.start.requestedAtMillis)
        assertEquals(f.original, job.original); assertEquals(batch.generation, job.generation)
        assertEquals(before.take(3), f.row().take(3)); assertArrayEquals(payload, f.row()[3] as ByteArray)
        val document = LocalExcerptRules.extract(job)
        val ready = f.store.complete(job, document); val committed = (f.row()[3] as ByteArray).copyOf()
        assertEquals(1L, ready.version); assertEquals(ExcerptReview.PROPOSED, ready.items.single().review)
        assertEquals(1L, f.store.complete(job, document).version); assertArrayEquals(committed, f.row()[3] as ByteArray)
        assertEquals(1, f.store.batches(f.source).size); assertEquals(originalTables, f.snapshotOld())
        f.reopen(); assertEquals(ExcerptReview.PROPOSED, f.store.batches(f.source).single().items.single().review)
    }

    @Test fun missingAndForgedResumeSnapshotsCannotCreateRowsOrChangeAnyCiphertext() = LocalCandidateFixture().use { f ->
        f.store.begin(f.start()); val b = f.store.batches(f.source).single()
        val before = f.row(); val bytes = (before[3] as ByteArray).copyOf(); val originalTables = f.snapshotOld()
        val invalid = listOf(copy(b, id = "missing"), copy(b, source = SourceVersion("other", 1)),
            copy(b, source = SourceVersion("source", 2)), copy(b, hash = "0".repeat(64)),
            copy(b, generation = b.generation + 1), copy(b, version = 1), copy(b, state = LocalCandidateState.READY),
            copy(b, created = b.createdAtMillis + 1), copy(b, items = listOf(ReviewedExcerpt("excerpt-1", ExcerptProposal(0, 1, "我"), ExcerptReview.PROPOSED))))
        invalid.forEach { expected -> assertThrows(LocalCandidateConflict::class.java) { f.store.resume(expected) } }
        assertEquals(1, f.db.read("SELECT id FROM local_candidate_batches").size)
        assertEquals(before.take(3), f.row().take(3)); assertArrayEquals(bytes, f.row()[3] as ByteArray)
        assertEquals(originalTables, f.snapshotOld())
    }

    @Test fun readyReviewedCancelledAndCorruptBatchesCannotBecomeFreshProducers() = LocalCandidateFixture().use { f ->
        val job = f.store.begin(f.start()); val running = f.store.batches(f.source).single()
        val ready = f.store.complete(job, LocalExcerptRules.extract(job))
        assertThrows(LocalCandidateConflict::class.java) { f.store.resume(running) }
        assertThrows(LocalCandidateConflict::class.java) { f.store.resume(ready) }
        val accepted = f.store.review(f.review(ready)); val bytes = (f.row()[3] as ByteArray).copyOf()
        assertThrows(LocalCandidateConflict::class.java) { f.store.resume(accepted) }
        assertArrayEquals(bytes, f.row()[3] as ByteArray); assertEquals(2L, f.row()[0])
        f.store.begin(f.start("cancelled")); val cancelled = f.store.batches(f.source).single { it.id == "cancelled" }
        f.store.cancel(f.cancel(cancelled)); assertThrows(LocalCandidateConflict::class.java) { f.store.resume(cancelled) }
        val corrupt = copy(running, id = "corrupt")
        // Adversarial stored bytes, not an UPDATE that bypasses production transition guards.
        f.db.transaction { it.execute("INSERT INTO local_candidate_batches VALUES('corrupt','source',1,?,0,1,0,'RUNNING','corrupt',20,X'00')",
            listOf(running.sourceHash)) }
        assertThrows(LocalCandidateStorageFailure::class.java) { f.store.resume(corrupt) }
        assertEquals(listOf(0L, "RUNNING"), f.db.read("SELECT version,state FROM local_candidate_batches WHERE id='corrupt'").single())
    }

    @Test fun editsPolicyAbaTrashRestoreAndPermanentErasureRejectResumeAndItsLateCompletion() {
        for (revise in listOf(false, true)) LocalCandidateFixture().use { f ->
            val original = f.store.begin(f.start()); val expected = f.store.batches(f.source).single(); val resumed = f.store.resume(expected)
            if (revise) f.revise() else f.db.transaction { sql ->
                sql.execute("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id='source'")
                sql.execute("UPDATE entries SET privacyPolicy='LOCAL_ONLY' WHERE id='source'")
            }
            assertThrows(LocalCandidateConflict::class.java) { f.store.resume(expected) }
            for (job in listOf(original, resumed)) assertThrows(LocalCandidateConflict::class.java) { f.store.complete(job, LocalExcerptRules.extract(job)) }
            assertEquals("INVALIDATED", f.row()[1]); assertEquals(0, (f.row()[3] as ByteArray).size)
        }
        RecordFavoriteFixture().use { f ->
            f.db.transaction { sql -> LocalCandidateSqlSchema.migration9To10.forEach { sql.execute(it) }; LocalCandidateSqlSchema.guards.forEach { sql.execute(it) } }
            val store = SqlLocalCandidateRepository(f.base.gateway, PermanentErasureFixture.KEY)
            try {
                val id = f.entry(); val source = SourceVersion(id, 1)
                store.begin(LocalCandidateStart("before-trash", source, 20)); val old = store.batches(source).single(); val job = store.resume(old)
                val trashed = f.base.intent(id)
                assertThrows(LocalCandidateConflict::class.java) { store.resume(old) }
                val restored = f.base.bin.apply(RecycleMutation(source, trashed.generation, true, "restore-source"))
                assertNull(RemoteTextTransactions(f.base.gateway).updateAuthorization(source, restored.generation, ProcessingPolicy.LOCAL_ONLY, null, null))
                assertThrows(LocalCandidateConflict::class.java) { store.resume(old) }
                store.begin(LocalCandidateStart("before-erase", source, 40)); val fresh = store.batches(source).single { it.id == "before-erase" }
                val delayed = store.resume(fresh); assertTrue(runBlocking { f.base.erase.erase(f.base.intent(id)) }.complete)
                assertTrue(f.db.read("SELECT id FROM local_candidate_batches").isEmpty())
                assertThrows(LocalCandidateConflict::class.java) { store.resume(fresh) }
                for (value in listOf(job, delayed)) assertThrows(LocalCandidateConflict::class.java) { store.complete(value, LocalExcerptRules.extract(value)) }
            } finally { store.wipeKey() }
        }
    }

    private fun copy(b: LocalCandidateBatch, id: String = b.id, source: SourceVersion = b.source, hash: String = b.sourceHash,
        generation: Long = b.generation, version: Long = b.version, state: LocalCandidateState = b.state,
        created: Long = b.createdAtMillis, items: List<ReviewedExcerpt> = b.items) =
        LocalCandidateBatch(id, source, hash, generation, version, state, created, items)
}
