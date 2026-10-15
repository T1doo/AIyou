package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class LocalCandidateSqlTest {
    @Test fun actualNineToTenMigrationPreservesOldRowsAndAddsOnlyEmptyCandidateStorage() = LocalCandidateFixture(false).use { f ->
        val remote = RemoteTextTransactions(f.db) { "legacy-job" }
        val priorSource = SourceVersion("other", 1)
        val connection = ProviderConnection("synthetic-old-provider", 1, "https://provider.example.test/v1/",
            ConnectionProtocol.CHAT_COMPLETIONS, "synthetic-old-model", SecretReference("synthetic-old-ref"))
        val now = java.time.Instant.ofEpochMilli(20)
        val consent = ConsentGrant("synthetic-old-consent", 1, priorSource, connection, TextPurpose.ORGANIZATION,
            java.time.Instant.EPOCH, java.time.Instant.ofEpochSecond(100))
        assertNull(remote.updateAuthorization(priorSource, 0, ProcessingPolicy.REMOTE_ALLOWED, connection, consent))
        val oldJob = (remote.queue(RemoteSendIntent(priorSource, TextPurpose.ORGANIZATION), "old-v1", { now }) as SourceQueueOutcome.Queued).job
        assertNull(remote.admit(oldJob) { now })
        assertTrue(remote.commit(oldJob, DerivedTextCandidate("旧 opaque 注解", listOf(OriginalTextReference(priorSource, 0, 6, "同名合成来源"))), { now }) is SourceCommitOutcome.Committed)
        val before = f.snapshotOld()
        f.upgrade()
        assertEquals(before, f.snapshotOld())
        assertEquals(10L, f.db.read("PRAGMA user_version").single().single())
        assertTrue(f.store.batches(f.source).isEmpty())
        assertTrue(f.store.batches(priorSource).isEmpty())
        assertEquals(1L, f.db.read("PRAGMA foreign_keys").single().single())
    }
    @Test fun localOnlyLiteralCandidateAndExplicitAcceptanceSurviveColdReopenWithoutSideEffects() = LocalCandidateFixture().use { f ->
        val old = f.snapshotOld(); val batch = f.ready()
        assertEquals(ExcerptReview.PROPOSED, batch.items.single().review)
        assertEquals(f.original, batch.items.single().proposal.quote)
        assertEquals("LOCAL_ONLY", f.db.read("SELECT privacyPolicy FROM entries WHERE id='source'").single().single())
        assertTrue(f.db.read("SELECT * FROM remote_text_jobs").isEmpty())
        f.reopen()
        val saved = f.store.batches(f.source).single()
        val receipt = f.store.review(f.review(saved))
        assertEquals(ExcerptReview.ACCEPTED, receipt.items.single().review)
        f.reopen()
        assertEquals(ExcerptReview.ACCEPTED, f.store.batches(f.source).single().items.single().review)
        assertEquals(old, f.snapshotOld())
    }
    @Test fun foreignSourceRevisionHashBoundsAndQuotesCannotCommitOrOverwriteOriginals(): Unit = LocalCandidateFixture().use { f ->
        val job = f.store.begin(f.start())
        val valid = LocalExcerptRules.extract(job); val proposal = valid.proposals.single()
        val invalid = listOf(
            ExcerptDocument(SourceVersion("other", 1), valid.sourceHash, valid.proposals),
            ExcerptDocument(SourceVersion("source", 2), valid.sourceHash, valid.proposals),
            ExcerptDocument(valid.source, OriginalExports.hash("其他"), valid.proposals),
            ExcerptDocument(valid.source, valid.sourceHash, listOf(ExcerptProposal(0, proposal.end, "伪造摘录"))),
            ExcerptDocument(valid.source, valid.sourceHash, listOf(ExcerptProposal(0, 9999, proposal.quote))))
        val old = f.snapshotOld()
        invalid.forEach { doc -> assertThrows(InvalidExcerptResponse::class.java) { f.store.complete(job, doc) } }
        assertEquals(0L, f.row()[0]); assertEquals("RUNNING", f.row()[1]); assertEquals(old, f.snapshotOld())
        assertThrows(LocalCandidateConflict::class.java) { f.store.begin(LocalCandidateStart("draft", SourceVersion("draft", 1), 20)) }
        assertThrows(LocalCandidateConflict::class.java) { f.store.begin(LocalCandidateStart("stale", SourceVersion("source", 2), 20)) }
    }
    @Test fun forgedCapabilityCaptureMetadataOriginalOrEpochCannotUseARealBatch() = LocalCandidateFixture().use { f ->
        val job = f.store.begin(f.start()); val document = LocalExcerptRules.extract(job)
        val forged = listOf(
            LocalCandidateJob(job.start, job.sourceHash, job.generation + 1, job.original, job.capturedAtMillis, job.captureTimeZone),
            LocalCandidateJob(job.start, job.sourceHash, job.generation, "篡改", job.capturedAtMillis, job.captureTimeZone),
            LocalCandidateJob(job.start, job.sourceHash, job.generation, job.original, job.capturedAtMillis + 1, job.captureTimeZone),
            LocalCandidateJob(job.start, job.sourceHash, job.generation, job.original, job.capturedAtMillis, "UTC"))
        forged.forEach { assertThrows(LocalCandidateConflict::class.java) { f.store.complete(it, document) } }
        assertEquals(0L, f.row()[0])
    }
    @Test fun exactCompletionAndReviewReplayKeepOriginalCiphertextAndChangedTupleConflicts(): Unit = LocalCandidateFixture().use { f ->
        val job = f.store.begin(f.start()); val doc = LocalExcerptRules.extract(job)
        val batch = f.store.complete(job, doc); val bytes = (f.row()[3] as ByteArray).copyOf()
        assertEquals(1L, f.store.complete(job, doc).version); assertArrayEquals(bytes, f.row()[3] as ByteArray)
        val request = f.review(batch); val accepted = f.store.review(request); val reviewedBytes = (f.row()[3] as ByteArray).copyOf()
        assertEquals(accepted.version, f.store.review(request).version); assertArrayEquals(reviewedBytes, f.row()[3] as ByteArray)
        assertThrows(LocalCandidateConflict::class.java) {
            f.store.review(ExcerptReviewRequest(batch.id, batch.source, batch.sourceHash, batch.generation, 1,
                "excerpt-1", ExcerptReview.REJECTED, request.mutationId, request.requestedAtMillis))
        }
        assertThrows(LocalCandidateConflict::class.java) {
            f.store.review(ExcerptReviewRequest(batch.id, batch.source, batch.sourceHash, batch.generation, 1,
                "excerpt-1", ExcerptReview.ACCEPTED, request.mutationId, request.requestedAtMillis + 1))
        }
        assertThrows(LocalCandidateConflict::class.java) { f.store.review(f.review(accepted, mutation = "duplicate-new-id")) }
        assertThrows(LocalCandidateConflict::class.java) { f.store.complete(job, doc) }
    }
    @Test fun rejectedAndCancelledStatesPersistAndLateCompletionCannotReviveThem() = LocalCandidateFixture().use { f ->
        val batch = f.ready()
        f.store.review(f.review(batch, ExcerptReview.REJECTED))
        val other = f.store.begin(f.start("cancelled"))
        val running = f.store.batches(f.source).single { it.id == "cancelled" }
        val cancel = f.cancel(running)
        f.store.cancel(cancel); val before = f.db.read("SELECT payload FROM local_candidate_batches WHERE id='cancelled'").single().single() as ByteArray
        f.store.cancel(cancel)
        assertArrayEquals(before, f.db.read("SELECT payload FROM local_candidate_batches WHERE id='cancelled'").single().single() as ByteArray)
        assertThrows(LocalCandidateConflict::class.java) { f.store.complete(other, LocalExcerptRules.extract(other)) }
        f.reopen()
        assertEquals(ExcerptReview.REJECTED, f.store.batches(f.source).single { it.id == batch.id }.items.single().review)
        assertEquals(LocalCandidateState.CANCELLED, f.store.batches(f.source).single { it.id == "cancelled" }.state)
    }
    @Test fun lostCommitAcknowledgementRecoversOneStableReviewAndOneVersion() = LocalCandidateFixture().use { f ->
        val batch = f.ready(); val request = f.review(batch); var lost = false
        f.db.afterSql = { sql -> if (sql == "COMMIT" && !lost) { lost = true; throw IllegalStateException("Synthetic lost ack") } }
        assertThrows(LocalCandidateStorageFailure::class.java) { f.store.review(request) }
        f.db.afterSql = null
        val stored = f.store.batches(f.source).single(); assertEquals(2L, stored.version)
        val bytes = (f.row()[3] as ByteArray).copyOf()
        assertEquals(2L, f.store.review(request).version); assertArrayEquals(bytes, f.row()[3] as ByteArray)
        assertEquals(1, f.db.read("SELECT id FROM local_candidate_batches").size)
    }
    @Test fun actualSqlAbortRollsBackCiphertextAndReceiptTogether() = LocalCandidateFixture().use { f ->
        val batch = f.ready(); val before = f.row(); val bytes = (before[3] as ByteArray).copyOf()
        f.db.transaction { it.execute("CREATE TRIGGER synthetic_candidate_abort AFTER UPDATE ON local_candidate_batches BEGIN SELECT RAISE(ABORT,'synthetic'); END") }
        assertThrows(LocalCandidateStorageFailure::class.java) { f.store.review(f.review(batch)) }
        f.db.transaction { it.execute("DROP TRIGGER synthetic_candidate_abort") }
        assertEquals(before.take(3), f.row().take(3)); assertArrayEquals(bytes, f.row()[3] as ByteArray)
        f.reopen(); assertEquals(ExcerptReview.PROPOSED, f.store.batches(f.source).single().items.single().review)
    }
    @Test fun independentConnectionsAcceptVersusCancelHaveOnlyOneWholeBatchWinner() = LocalCandidateFixture().use { f ->
        val batch = f.ready(); f.second().use { db2 ->
            val other = SqlLocalCandidateRepository(db2, LocalCandidateFixture.KEY)
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val failure = AtomicReference<Throwable>()
            f.db.beforeCommit = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
            val writer = Thread { try { f.store.review(f.review(batch)) } catch (t: Throwable) { failure.set(t) } }
            writer.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            val loser = AtomicReference<Throwable>()
            val competing = Thread { try { other.cancel(f.cancel(batch)) } catch (t: Throwable) { loser.set(t) } }
            competing.start(); release.countDown(); writer.join(2000); competing.join(2000); f.db.beforeCommit = null
            assertFalse(writer.isAlive); assertFalse(competing.isAlive); assertNull(failure.get()); assertTrue(loser.get() is LocalCandidateConflict)
            assertEquals(2L, f.store.batches(f.source).single().version)
            assertEquals(ExcerptReview.ACCEPTED, f.store.batches(f.source).single().items.single().review); other.wipeKey()
        }
    }
    @Test fun firstAccessInsertAndPolicyAbaInvalidateWithoutReactivatingOldCapabilities() = LocalCandidateFixture().use { f ->
        val job = f.store.begin(f.start()); val doc = LocalExcerptRules.extract(job)
        f.db.transaction { it.execute("INSERT INTO remote_source_access VALUES('source',1,0,NULL,NULL)") }
        assertThrows(LocalCandidateConflict::class.java) { f.store.complete(job, doc) }
        assertEquals("INVALIDATED", f.row()[1]); assertEquals(0, (f.row()[3] as ByteArray).size)
        val fresh = f.ready("fresh")
        f.db.transaction { sql -> sql.execute("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id='source'"); sql.execute("UPDATE entries SET privacyPolicy='LOCAL_ONLY' WHERE id='source'") }
        assertThrows(LocalCandidateConflict::class.java) { f.store.review(f.review(fresh)) }
        assertTrue(f.store.batches(f.source).all { it.state == LocalCandidateState.INVALIDATED && it.items.isEmpty() })
    }
    @Test fun sourceEditInvalidatesBothProposedAndAcceptedExcerptsAndLateResults() = LocalCandidateFixture().use { f ->
        val accepted = f.store.review(f.review(f.ready()))
        val delayed = f.store.begin(f.start("late"))
        f.revise()
        assertThrows(LocalCandidateConflict::class.java) { f.store.review(f.review(accepted)) }
        assertThrows(LocalCandidateConflict::class.java) { f.store.complete(delayed, LocalExcerptRules.extract(delayed)) }
        val current = f.store.batches(SourceVersion("source", 2))
        assertTrue(current.all { it.state == LocalCandidateState.INVALIDATED && it.items.isEmpty() })
        assertTrue(f.db.read("SELECT payload FROM local_candidate_batches").all { (it.single() as ByteArray).isEmpty() })
    }
    @Test fun trashRestoreAndPermanentErasureRemovePrivateCandidateCopiesWithoutResurrection() = RecordFavoriteFixture().use { f ->
        f.db.transaction { sql -> LocalCandidateSqlSchema.migration9To10.forEach { sql.execute(it) }; LocalCandidateSqlSchema.guards.forEach { sql.execute(it) } }
        val store = SqlLocalCandidateRepository(f.base.gateway, PermanentErasureFixture.KEY)
        val id = f.entry(); val source = SourceVersion(id, 1); val job = store.begin(LocalCandidateStart("batch", source, 20))
        val batch = store.complete(job, LocalExcerptRules.extract(job))
        store.review(ExcerptReviewRequest(batch.id, source, batch.sourceHash, batch.generation, 1, "excerpt-1", ExcerptReview.ACCEPTED, "review", 30))
        val trashed = f.base.intent(id)
        assertThrows(LocalCandidateConflict::class.java) { store.complete(job, LocalExcerptRules.extract(job)) }
        assertTrue(f.db.read("SELECT payload FROM local_candidate_batches").all { (it.single() as ByteArray).isEmpty() })
        val restored = f.base.bin.apply(RecycleMutation(source, trashed.generation, true, "restore-source"))
        assertEquals(ProcessingPolicy.AI_NONE, restored.entry.policy)
        assertThrows(LocalCandidateConflict::class.java) { store.batches(source) }
        assertNull(RemoteTextTransactions(f.base.gateway).updateAuthorization(source, restored.generation,
            ProcessingPolicy.LOCAL_ONLY, null, null))
        assertTrue(store.batches(source).all { it.state == LocalCandidateState.INVALIDATED && it.items.isEmpty() })
        assertThrows(LocalCandidateConflict::class.java) { store.review(ExcerptReviewRequest(batch.id, source,
            batch.sourceHash, batch.generation, 1, "excerpt-1", ExcerptReview.ACCEPTED, "old-after-restore", 40)) }
        val delayed = store.begin(LocalCandidateStart("late-after-restore", source, 40))
        val target = f.base.intent(id)
        assertTrue(kotlinx.coroutines.runBlocking { f.base.erase.erase(target) }.complete)
        assertTrue(f.db.read("SELECT * FROM local_candidate_batches").isEmpty())
        assertThrows(LocalCandidateConflict::class.java) { store.complete(job, LocalExcerptRules.extract(job)) }
        assertThrows(LocalCandidateConflict::class.java) { store.complete(delayed, LocalExcerptRules.extract(delayed)) }
        assertTrue(f.db.read("SELECT id FROM entries WHERE id=?", id).isEmpty()); store.wipeKey()
    }
    @Test fun sourceInvalidationAtMaximumVersionNeverBlocksOriginalSaving() = LocalCandidateFixture().use { f ->
        f.db.transaction { sql ->
            sql.execute("INSERT INTO local_candidate_batches VALUES('batch','source',1,?,0,1,9223372036854775807,'READY','synthetic',20,X'00')", listOf(OriginalExports.hash(f.original)))
        }
        f.revise()
        assertEquals("INVALIDATED", f.row()[1]); assertEquals(Long.MAX_VALUE, f.row()[0]); assertEquals(0, (f.row()[3] as ByteArray).size)
        assertEquals(2L, f.db.read("SELECT currentRevision FROM entries WHERE id='source'").single().single())
    }
    @Test fun authenticatedCorruptionAndClosedOwnerAreFailuresRatherThanEmptyCandidates(): Unit = LocalCandidateFixture().use { f ->
        f.ready()
        f.db.transaction { sql -> sql.execute("DROP TRIGGER immutable_candidate_binding"); sql.execute("DROP TRIGGER candidate_state_guard")
            sql.execute("UPDATE local_candidate_batches SET payload=X'00' WHERE id='batch'") }
        assertThrows(LocalCandidateStorageFailure::class.java) { f.store.batches(f.source) }
        f.gateway.stopAndAwaitIdle()
        assertThrows(LocalCandidateStorageFailure::class.java) { f.store.batches(f.source) }
        assertThrows(LocalCandidateStorageFailure::class.java) { f.store.begin(f.start("closed")) }
    }
}
