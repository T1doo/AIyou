package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.SendDenial
import com.t1doo.aiyou.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** Product Kotlin and product SQL on real disk/WAL, never a ledger implemented by the test. */
class RemoteTextTransactionsTest {
    @Test(timeout = 10_000) fun actualExportedV3MigratesWithoutChangingOriginalLedgerAndReopensV4() {
        RemoteTextFixture(initiallyApproved = false).use { f ->
            assertEquals(f.beforeMigration, f.originals())
            assertEquals(4L, f.db.read("PRAGMA user_version").single().single())
            assertEquals("wal", f.db.read("PRAGMA journal_mode").single().single())
            assertEquals(1L, f.db.read("PRAGMA foreign_keys").single().single())
            assertEquals("ok", f.db.read("PRAGMA integrity_check").single().single())
            val beforePid = f.db.pid
            f.reopen()
            assertNotEquals(beforePid, f.db.pid)
            assertEquals(f.beforeMigration, f.originals())
            assertEquals(4L, f.db.read("PRAGMA user_version").single().single())
        }
    }

    @Test(timeout = 10_000) fun actualSharedMigrationDdlAndVersionRollBackWithoutPartialTablesIndicesOrGuards() {
        RemoteTextFixture(initiallyApproved = false, migrate = false).use { f ->
            val catalog = f.db.read("SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name")
            assertEquals(3L, f.db.read("PRAGMA user_version").single().single())
            repeat(2) { variant ->
                fails<IllegalStateException> {
                    f.db.transaction { sql ->
                        val statements = RemoteTextSqlSchema.migration3To4 + RemoteTextSqlSchema.guards
                        statements.forEachIndexed { index, statement ->
                            sql.execute(statement)
                            if (variant == 0 && index == RemoteTextSqlSchema.migration3To4.size / 2)
                                throw IllegalStateException("Synthetic migration interruption")
                        }
                        sql.execute("PRAGMA user_version=4")
                        throw IllegalStateException("Synthetic migration interruption after version update")
                    }
                }
                assertEquals(catalog, f.db.read("SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name"))
                assertEquals(3L, f.db.read("PRAGMA user_version").single().single())
                assertEquals(f.beforeMigration, f.originals())
            }
            f.reopen(); assertEquals(catalog, f.db.read("SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name"))
            f.migrateNow(); assertEquals(4L, f.db.read("PRAGMA user_version").single().single())
            assertEquals(f.beforeMigration, f.originals()); f.approve(); assertNotNull(f.queued())
        }
    }

    @Test(timeout = 10_000) fun missingApprovalAndBothRestrictivePoliciesNeverIssueJobs() {
        listOf<ProcessingPolicy?>(null, ProcessingPolicy.LOCAL_ONLY, ProcessingPolicy.AI_NONE).forEach { policy ->
            RemoteTextFixture(initiallyApproved = false).use { f ->
                if (policy != null) f.approve(policy)
                deniedQueue(f.repository.queue(f.intent, RemoteTextFixture.PROMPT, f.clock), SourceCommitDenial.POLICY_BLOCKED)
                assertTrue(f.jobs().isEmpty()); assertTrue(f.annotations().isEmpty())
            }
        }
    }

    @Test(timeout = 10_000) fun missingRevokedExpiredFutureAndWrongPurposeConsentNeverIssueJobs() {
        repeat(5) { variant ->
            RemoteTextFixture().use { f ->
                val grant = when (variant) {
                    0 -> null
                    1 -> f.grant.copy(revoked = true)
                    2 -> f.grant.copy(expiresAt = f.now)
                    3 -> f.grant.copy(issuedAt = f.now.plusSeconds(1))
                    else -> f.grant.copy(purpose = TextPurpose.CONVERSATION)
                }
                f.approve(consent = grant)
                assertTrue(f.repository.queue(f.intent, RemoteTextFixture.PROMPT, f.clock) is SourceQueueOutcome.Denied)
                assertTrue(f.jobs().isEmpty()); assertTrue(f.annotations().isEmpty())
            }
        }
    }

    @Test(timeout = 10_000) fun onlyRequestedCurrentOriginalAndCompleteStoredMetadataEnterIssuedJob() {
        RemoteTextFixture().use { f ->
            val originalLedger = f.originals()
            val accessRows = f.db.read("SELECT entryId,generation,deleted,hex(connection),hex(consent) FROM remote_source_access ORDER BY entryId")
            val current = checkNotNull(f.repository.readAccess(f.source.sourceId))
            assertEquals(f.source, current.state.source); assertEquals(ProcessingPolicy.REMOTE_ALLOWED, current.state.policy)
            assertEquals(1L, current.generation); assertFalse(current.state.deleted)
            assertEquals(f.selected, current.state.connection); assertEquals(f.grant, current.state.consent)
            val local = checkNotNull(f.repository.readAccess("synthetic-unrelated-source"))
            assertEquals(SourceVersion("synthetic-unrelated-source", 1), local.state.source)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, local.state.policy); assertEquals(0L, local.generation)
            assertNull(local.state.connection); assertNull(local.state.consent)
            assertNull(f.repository.readAccess("synthetic-missing-source"))
            assertEquals(accessRows, f.db.read("SELECT entryId,generation,deleted,hex(connection),hex(consent) FROM remote_source_access ORDER BY entryId"))
            assertEquals(originalLedger, f.originals()); assertTrue(f.jobs().isEmpty()); assertTrue(f.annotations().isEmpty())
            assertFalse(current.toString().contains("synthetic-private")); assertFalse(current.toString().contains(f.original))
            val job = f.queued()
            assertEquals(f.original, job.originalText)
            assertEquals(RemoteTextFixture.hash(f.original), job.contentHash)
            assertEquals(f.capturedAt, job.capturedAtMillis); assertEquals(f.zone, job.captureTimeZone)
            assertEquals(f.intent, job.intent); assertEquals(f.selected, job.state.connection)
            assertEquals(f.grant, job.state.consent); assertEquals(1L, job.generation)
            assertEquals(originalLedger, f.originals())
            assertEquals(listOf(listOf(job.id, "QUEUED")), f.jobs())
        }
    }

    @Test(timeout = 10_000) fun staleSourceAndCorruptedOriginalHashFailBeforeJobIssuance() {
        RemoteTextFixture().use { f ->
            deniedQueue(f.repository.queue(f.intent.copy(source = f.source.copy(revision = 2)), RemoteTextFixture.PROMPT, f.clock),
                SourceCommitDenial.SOURCE_CHANGED)
            assertTrue(f.jobs().isEmpty())
        }
        RemoteTextFixture(initiallyApproved = false, seedHash = "0".repeat(64)).use { f ->
            deniedQueue(f.repository.queue(f.intent, RemoteTextFixture.PROMPT, f.clock), SourceCommitDenial.STORAGE_UNAVAILABLE)
            assertTrue(f.jobs().isEmpty())
        }
        RemoteTextFixture(initiallyApproved = false, original = "x".repeat(100_001)).use { f ->
            deniedQueue(f.repository.queue(f.intent, RemoteTextFixture.PROMPT, f.clock), SourceCommitDenial.STORAGE_UNAVAILABLE)
            assertTrue(f.jobs().isEmpty())
        }
        RemoteTextFixture().use { f ->
            f.db.transaction { sql ->
                sql.execute("INSERT INTO entry_revisions(entryId,revision,originalText,contentHash,createdAtMillis) VALUES(?,?,CAST(? AS TEXT),?,?)",
                    listOf(f.source.sourceId, 4, byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), "0".repeat(64), f.now.toEpochMilli()))
                sql.execute("UPDATE entries SET currentRevision=4 WHERE id=?", listOf(f.source.sourceId))
            }
            deniedQueue(f.repository.queue(f.intent.copy(source = f.source.copy(revision = 4)), RemoteTextFixture.PROMPT, f.clock),
                SourceCommitDenial.STORAGE_UNAVAILABLE)
            assertTrue(f.jobs().isEmpty()); assertTrue(f.annotations().isEmpty())
        }
    }

    @Test(timeout = 10_000) fun duplicateQueueCannotAcquireOrCancelFirstOwnedJob() {
        RemoteTextFixture().use { f ->
            val job = f.queued()
            deniedQueue(f.repository.queue(f.intent, RemoteTextFixture.PROMPT, f.clock), SourceCommitDenial.JOB_INVALID)
            assertNull(f.repository.check(job, f.clock)); assertNull(f.repository.admit(job, f.clock))
            deniedQueue(f.repository.queue(f.intent, RemoteTextFixture.PROMPT, f.clock), SourceCommitDenial.JOB_INVALID)
            assertEquals(listOf(listOf(job.id, "ADMITTED")), f.jobs())
        }
    }

    @Test(timeout = 10_000) fun twoIndependentConnectionsIssueExactlyOneJobForConcurrentQueues() {
        RemoteTextFixture().use { f ->
            val second = f.second(); assertNotEquals(f.db.pid, second.pid)
            val other = f.store(second); val start = CountDownLatch(1); val workers = Executors.newFixedThreadPool(2)
            try {
                val first = workers.submit<SourceQueueOutcome> { check(start.await(1, TimeUnit.SECONDS)); f.repository.queue(f.intent, RemoteTextFixture.PROMPT, f.clock) }
                val next = workers.submit<SourceQueueOutcome> { check(start.await(1, TimeUnit.SECONDS)); other.queue(f.intent, RemoteTextFixture.PROMPT, f.clock) }
                start.countDown()
                val results = listOf(first.get(2, TimeUnit.SECONDS), next.get(2, TimeUnit.SECONDS))
                assertEquals(1, results.count { it is SourceQueueOutcome.Queued })
                assertEquals(1, results.count { it == SourceQueueOutcome.Denied(SourceCommitDenial.JOB_INVALID) })
                val issued = (results.single { it is SourceQueueOutcome.Queued } as SourceQueueOutcome.Queued).job
                assertEquals(listOf(listOf(issued.id, "QUEUED")), f.jobs())
            } finally { start.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(1, TimeUnit.SECONDS)) }
        }
    }

    @Test(timeout = 10_000) fun forgedMetadataCannotAdmitCommitOrCancelLegitimateJob() {
        RemoteTextFixture().use { f ->
            val job = f.queued()
            val forged = listOf(
                copyJob(job, text = job.originalText + "forged"),
                copyJob(job, hash = "0".repeat(64)), copyJob(job, generation = job.generation + 1),
                copyJob(job, prompt = job.promptVersion + "forged"), copyJob(job, captured = job.capturedAtMillis + 1),
                copyJob(job, zone = "UTC"), copyJob(job, intent = job.intent.copy(purpose = TextPurpose.CONVERSATION)),
                copyJob(job, state = job.state.copy(connection = f.connectionB)),
            )
            forged.forEach { fake ->
                assertNotNull(f.repository.check(fake, f.clock)); assertNotNull(f.repository.admit(fake, f.clock))
                assertTrue(f.repository.commit(fake, f.candidate(), f.clock) is SourceCommitOutcome.Denied)
                f.repository.cancel(fake)
                assertEquals(listOf(listOf(job.id, "QUEUED")), f.jobs()); assertTrue(f.annotations().isEmpty())
            }
            assertNull(f.repository.admit(job, f.clock))
        }
    }

    @Test(timeout = 10_000) fun foreignDatabaseTicketCannotAuthorizeOrCancelThisStore() {
        RemoteTextFixture().use { first -> RemoteTextFixture().use { second ->
            val issued = first.queued()
            val own = second.queued(); assertNotEquals(issued.id, own.id)
            assertNotNull(second.repository.check(issued, second.clock))
            assertNotNull(second.repository.admit(issued, second.clock))
            assertTrue(second.repository.commit(issued, second.candidate(), second.clock) is SourceCommitOutcome.Denied)
            second.repository.cancel(issued)
            assertEquals(listOf(listOf(own.id, "QUEUED")), second.jobs()); assertTrue(second.annotations().isEmpty())
            assertEquals(listOf(listOf(issued.id, "QUEUED")), first.jobs())
        } }
    }

    @Test(timeout = 10_000) fun admitIsOneUseAndUnadmittedOrCancelledResultsCannotCommit() {
        RemoteTextFixture().use { f ->
            val job = f.queued()
            deniedCommit(f.repository.commit(job, f.candidate(), f.clock), SourceCommitDenial.JOB_NOT_ADMITTED)
            assertNull(f.repository.admit(job, f.clock))
            assertEquals(SendDenial.ALREADY_SENT, f.repository.admit(job, f.clock))
            f.repository.cancel(job)
            assertNotNull(f.repository.admit(job, f.clock))
            deniedCommit(f.repository.commit(job, f.candidate(), f.clock), SourceCommitDenial.JOB_NOT_ADMITTED)
            assertTrue(f.annotations().isEmpty())
        }
    }

    @Test(timeout = 10_000) fun emojiAndCombiningCharacterReferencesUseHalfOpenCodePoints() {
        RemoteTextFixture().use { f ->
            val originalLedger = f.originals(); val job = f.admitted()
            val value = f.candidate(references = listOf(OriginalTextReference(f.source, 2, 3, "🙂"),
                OriginalTextReference(f.source, 3, 5, "e\u0301")))
            val outcome = f.repository.commit(job, value, f.clock) as SourceCommitOutcome.Committed
            assertFalse(outcome.alreadyCommitted); assertEquals(value.references, outcome.annotation.candidate.references)
            val row = f.annotations().single()
            val bytes = row[2] as ByteArray
            val expectedHash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(expectedHash, row[1]); assertTrue(bytes.contentEquals(RemoteTextCodec.encodeCandidate(value)))
            assertEquals(originalLedger, f.originals())
            f.reopen()
            assertEquals(value.references, f.repository.annotations(f.source, f.clock).single().candidate.references)
        }
    }

    @Test(timeout = 10_000) fun wrongVersionRangeQuoteAndMalformedReferenceRejectWithoutChangingAdmittedJob() {
        RemoteTextFixture().use { f ->
            val job = f.admitted(); val end = f.original.codePointCount(0, f.original.length)
            val invalid = listOf(
                OriginalTextReference(f.source.copy(revision = 2), 0, end, f.original),
                OriginalTextReference(f.source, -1, 1, "原"), OriginalTextReference(f.source, 0, 0, ""),
                OriginalTextReference(f.source, 3, 2, ""), OriginalTextReference(f.source, 0, end + 1, f.original),
                OriginalTextReference(f.source, 0, Int.MAX_VALUE, f.original),
                OriginalTextReference(f.source, 2, 3, "wrong"), OriginalTextReference(f.source, 2, 4, "🙂"),
                OriginalTextReference(f.source, 0, 1, "\uD800"),
            )
            invalid.forEach { ref ->
                deniedCommit(f.repository.commit(job, f.candidate(references = listOf(ref)), f.clock), SourceCommitDenial.INVALID_REFERENCE)
                assertEquals(listOf(listOf(job.id, "ADMITTED")), f.jobs()); assertTrue(f.annotations().isEmpty())
            }
        }
    }

    @Test(timeout = 10_000) fun invalidCandidateTextAndReferenceCountsCannotCreateAnnotations() {
        RemoteTextFixture().use { f ->
            val job = f.admitted()
            listOf("", " \n", "\uD800", "x".repeat(16_385)).forEach { text ->
                deniedCommit(f.repository.commit(job, f.candidate(text), f.clock), SourceCommitDenial.INVALID_RESULT)
            }
            listOf(emptyList(), List(33) { OriginalTextReference(f.source, 0, 1, "原") }).forEach { refs ->
                deniedCommit(f.repository.commit(job, f.candidate(references = refs), f.clock), SourceCommitDenial.INVALID_REFERENCE)
            }
            assertTrue(f.annotations().isEmpty()); assertEquals(listOf(listOf(job.id, "ADMITTED")), f.jobs())
        }
    }

    @Test(timeout = 10_000) fun equalDuplicateCommitsAreIdempotentDifferentResultsConflictAndQueueReturnsStoredResult() {
        RemoteTextFixture().use { f ->
            val job = f.admitted(); val candidate = f.candidate()
            assertFalse((f.repository.commit(job, candidate, f.clock) as SourceCommitOutcome.Committed).alreadyCommitted)
            assertTrue((f.repository.commit(job, f.candidate(), f.clock) as SourceCommitOutcome.Committed).alreadyCommitted)
            deniedCommit(f.repository.commit(job, f.candidate("different"), f.clock), SourceCommitDenial.RESULT_CONFLICT)
            val again = f.repository.queue(f.intent, RemoteTextFixture.PROMPT, f.clock) as SourceQueueOutcome.AlreadyCommitted
            assertEquals(job.id, again.annotation.jobId); assertEquals(candidate.text, again.annotation.candidate.text)
            assertEquals(1, f.annotations().size); assertEquals(listOf(listOf(job.id, "COMMITTED")), f.jobs())
            f.repository.cancel(job); assertEquals(1, f.annotations().size)
        }
    }

    @Test(timeout = 10_000) fun allSameVersionConfigurationDriftAndProviderSwitchInvalidateQueuedJob() {
        repeat(8) { variant -> RemoteTextFixture().use { f ->
            val job = f.queued()
            val next = when (variant) {
                0 -> f.selected.copy(apiRoot = "https://provider-a.example.test/other/v1/")
                1 -> f.selected.copy(modelId = "synthetic-model-changed")
                2 -> f.selected.copy(protocol = ConnectionProtocol.ANTHROPIC_MESSAGES)
                3 -> f.selected.copy(secretRef = SecretReference("synthetic-private-ref-changed"))
                4 -> f.selected.copy(chatBudgetField = ChatBudgetField.MAX_COMPLETION_TOKENS)
                5 -> f.selected.copy(chatUsageSupported = true)
                6 -> f.selected.copy(version = f.selected.version + 1)
                else -> f.connectionB
            }
            f.approve(connection = next, consent = f.grant.copy(connection = next))
            assertNotNull(f.repository.check(job, f.clock)); assertNotNull(f.repository.admit(job, f.clock))
            deniedCommit(f.repository.commit(job, f.candidate(), f.clock), SourceCommitDenial.AUTHORIZATION_CHANGED)
            assertEquals(listOf(listOf(job.id, "CANCELLED")), f.jobs()); assertTrue(f.annotations().isEmpty())
        } }
    }

    @Test(timeout = 10_000) fun consentVersionIdentityScopeAndRevocationChangesInvalidateQueuedJobs() {
        repeat(5) { variant -> RemoteTextFixture().use { f ->
            val job = f.queued()
            val next = when (variant) {
                0 -> f.grant.copy(version = f.grant.version + 1)
                1 -> f.grant.copy(consentId = "synthetic-private-new-grant")
                2 -> f.grant.copy(purpose = TextPurpose.CONVERSATION)
                3 -> f.grant.copy(expiresAt = f.now)
                else -> f.grant.copy(revoked = true)
            }
            f.approve(consent = next)
            assertNotNull(f.repository.admit(job, f.clock))
            deniedCommit(f.repository.commit(job, f.candidate(), f.clock), SourceCommitDenial.AUTHORIZATION_CHANGED)
            assertTrue(f.annotations().isEmpty())
        } }
    }

    @Test(timeout = 10_000) fun switchingAwayAndBackCannotRestoreOldGenerationOrItsResult() {
        RemoteTextFixture().use { f ->
            val job = f.admitted()
            f.approve(connection = f.connectionB, consent = f.grant.copy(connection = f.connectionB)); f.approve()
            assertEquals(3L, f.generation()); assertNotNull(f.repository.check(job, f.clock))
            deniedCommit(f.repository.commit(job, f.candidate(), f.clock), SourceCommitDenial.AUTHORIZATION_CHANGED)
            assertTrue(f.annotations().isEmpty()); assertEquals("CANCELLED", f.jobs().single()[1])
        }
    }

    @Test(timeout = 10_000) fun currentRevisionChangeCancelsOldJobAndRejectsLateResponse() {
        RemoteTextFixture().use { f ->
            val job = f.admitted(); val originalVersion = f.db.read("SELECT * FROM entry_revisions WHERE entryId=? AND revision=?", f.source.sourceId, f.source.revision)
            f.revise()
            val rows = f.jobs()
            val current = checkNotNull(f.repository.readAccess(f.source.sourceId))
            assertEquals(f.source.copy(revision = 4), current.state.source)
            assertEquals(ProcessingPolicy.REMOTE_ALLOWED, current.state.policy); assertEquals(2L, current.generation)
            assertNull(current.state.connection); assertNull(current.state.consent); assertEquals(rows, f.jobs())
            assertEquals(SendDenial.SOURCE_CHANGED, f.repository.check(job, f.clock))
            deniedCommit(f.repository.commit(job, f.candidate(), f.clock), SourceCommitDenial.SOURCE_CHANGED)
            assertEquals(originalVersion, f.db.read("SELECT * FROM entry_revisions WHERE entryId=? AND revision=?", f.source.sourceId, f.source.revision))
            assertTrue(f.annotations().isEmpty()); assertEquals("CANCELLED", f.jobs().single()[1])
        }
    }

    @Test(timeout = 10_000) fun tombstoneAndPhysicalMissingSourceCannotCommitOrRequeueOldText() {
        repeat(2) { variant -> RemoteTextFixture().use { f ->
            val job = f.admitted()
            if (variant == 0) assertNull(f.repository.tombstone(f.source, f.generation())) else f.physicallyErase()
            val reason = if (variant == 0) SourceCommitDenial.SOURCE_DELETED else SourceCommitDenial.SOURCE_CHANGED
            deniedCommit(f.repository.commit(job, f.candidate(), f.clock), reason)
            deniedQueue(f.repository.queue(f.intent, RemoteTextFixture.PROMPT, f.clock), reason)
            assertTrue(f.annotations().isEmpty())
        } }
    }

    @Test(timeout = 10_000) fun expiryAtFinalCommitAndClockExceptionCannotAdvanceAdmittedJob() {
        RemoteTextFixture().use { f ->
            val job = f.admitted()
            deniedCommit(f.repository.commit(job, f.candidate()) { f.grant.expiresAt }, SourceCommitDenial.CONSENT_NOT_CURRENT)
            deniedCommit(f.repository.commit(job, f.candidate()) { throw IllegalStateException("synthetic-private-clock-error") },
                SourceCommitDenial.STORAGE_UNAVAILABLE)
            assertTrue(f.annotations().isEmpty()); assertEquals(listOf(listOf(job.id, "ADMITTED")), f.jobs())
        }
    }

    @Test(timeout = 10_000) fun realSqlInsertAndLaterJobUpdateFailuresRollBackWritesAndAllowReopenRetry() {
        repeat(2) { variant -> RemoteTextFixture().use { f ->
            val originalLedger = f.originals(); val job = f.admitted()
            val insertObserved = AtomicBoolean(false)
            f.db.afterSql = { if (it.startsWith("INSERT INTO text_annotations")) insertObserved.set(true) }
            val fault = if (variant == 0)
                "CREATE TRIGGER test_annotation_failure BEFORE INSERT ON text_annotations BEGIN SELECT RAISE(ABORT,'synthetic injected SQL failure'); END"
            else "CREATE TRIGGER test_annotation_failure BEFORE UPDATE OF state ON remote_text_jobs WHEN OLD.state='ADMITTED' AND NEW.state='COMMITTED' BEGIN SELECT RAISE(ABORT,'synthetic injected SQL failure'); END"
            f.db.write(fault)
            deniedCommit(f.repository.commit(job, f.candidate(), f.clock), SourceCommitDenial.STORAGE_UNAVAILABLE)
            assertEquals(variant == 1, insertObserved.get()); f.db.afterSql = null
            assertTrue(f.annotations().isEmpty()); assertEquals(listOf(listOf(job.id, "ADMITTED")), f.jobs())
            assertEquals(originalLedger, f.originals()); f.reopen()
            assertTrue(f.annotations().isEmpty()); assertEquals(listOf(listOf(job.id, "ADMITTED")), f.jobs())
            f.db.write("DROP TRIGGER test_annotation_failure")
            assertTrue(f.repository.commit(job, f.candidate(), f.clock) is SourceCommitOutcome.Committed)
            assertEquals(originalLedger, f.originals()); assertEquals(1, f.annotations().size)
        } }
    }

    @Test(timeout = 10_000) fun trustedClockCancellationPropagatesAfterRollbackForQueueCheckAdmitAndCommit() {
        repeat(4) { stage -> RemoteTextFixture().use { f ->
            val job = if (stage == 0) null else if (stage == 3) f.admitted() else f.queued()
            var calls = 0
            val clock = {
                calls++
                if ((stage == 0 || stage == 2) && calls == 1) f.now
                else throw CancellationException("synthetic-private-clock-cancel-marker")
            }
            val originals = f.originals()
            fails<CancellationException> {
                when (stage) {
                    0 -> f.repository.queue(f.intent, RemoteTextFixture.PROMPT, clock)
                    1 -> f.repository.check(checkNotNull(job), clock)
                    2 -> f.repository.admit(checkNotNull(job), clock)
                    else -> f.repository.commit(checkNotNull(job), f.candidate(), clock)
                }
            }
            assertTrue(f.annotations().isEmpty()); assertEquals(originals, f.originals())
            if (job == null) assertTrue(f.jobs().isEmpty())
            else assertEquals(listOf(listOf(job.id, if (stage == 3) "ADMITTED" else "QUEUED")), f.jobs())
            f.reopen(); assertTrue(f.annotations().isEmpty())
        } }
    }

    @Test(timeout = 10_000) fun revokeTransactionHoldsRealWriteLockAndWinsBeforeWaitingCommit() = orderedTransactions(revokeFirst = true)

    @Test(timeout = 10_000) fun commitTransactionWinsBeforeWaitingRevokeWhichDeletesDerivedRows() = orderedTransactions(revokeFirst = false)

    @Test(timeout = 10_000) fun revocationClearsDerivedRowsAndExpiredPermissionCannotExposeStoredAnnotation() {
        RemoteTextFixture().use { f ->
            val job = f.admitted(); assertTrue(f.repository.commit(job, f.candidate(), f.clock) is SourceCommitOutcome.Committed)
            assertEquals(1, f.repository.annotations(f.source, f.clock).size)
            assertTrue(f.repository.annotations(f.source) { f.grant.expiresAt }.isEmpty())
            val originals = f.originals(); f.approve(consent = f.grant.copy(revoked = true))
            assertTrue(f.annotations().isEmpty()); assertTrue(f.repository.annotations(f.source, f.clock).isEmpty())
            assertEquals(originals, f.originals())
        }
    }

    @Test(timeout = 10_000) fun databaseGuardsEnforceOriginalJobAndAnnotationImmutabilityAndValidStateTransitions() {
        RemoteTextFixture().use { f ->
            val originals = f.originals(); val job = f.queued()
            failsSql { f.db.write("UPDATE entry_revisions SET originalText=? WHERE entryId=? AND revision=?", "forged", f.source.sourceId, f.source.revision) }
            failsSql { f.db.write("UPDATE remote_text_jobs SET sourceHash=? WHERE id=?", "0".repeat(64), job.id) }
            failsSql { f.db.write("UPDATE remote_text_jobs SET state='COMMITTED' WHERE id=?", job.id) }
            assertNull(f.repository.admit(job, f.clock)); assertTrue(f.repository.commit(job, f.candidate(), f.clock) is SourceCommitOutcome.Committed)
            failsSql { f.db.write("UPDATE text_annotations SET resultHash=? WHERE jobId=?", "0".repeat(64), job.id) }
            assertEquals(originals, f.originals()); assertEquals(1, f.repository.annotations(f.source, f.clock).size)
            assertTrue(f.db.read("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test(timeout = 10_000) fun productionCodecPreservesUtf8AndRejectsMalformedTruncatedAndNoncanonicalEnvelopes() {
        RemoteTextFixture().use { f ->
            assertEquals(f.selected, RemoteTextCodec.decodeConnection(RemoteTextCodec.encodeConnection(f.selected)))
            assertEquals(f.grant, RemoteTextCodec.decodeConsent(RemoteTextCodec.encodeConsent(f.grant)))
            val value = f.candidate("合成🙂e\u0301")
            val encoded = RemoteTextCodec.encodeCandidate(value)
            assertEquals(value.text, RemoteTextCodec.decodeCandidate(encoded).text)
            assertEquals(value.references, RemoteTextCodec.decodeCandidate(encoded).references)
            listOf(encoded.copyOf(4), encoded + byteArrayOf(1), ByteArray(1_048_577)).forEach { bad ->
                fails<IllegalArgumentException> { RemoteTextCodec.decodeCandidate(bad) }
            }
            fails<IllegalArgumentException> { RemoteTextCodec.encodeCandidate(f.candidate("\uD800")) }
            assertFalse(RemoteTextCodec.validUnicode("\uDC00")); assertTrue(RemoteTextCodec.validUnicode("🙂e\u0301"))
        }
    }

    private fun orderedTransactions(revokeFirst: Boolean) {
        RemoteTextFixture().use { f ->
            val job = f.admitted(); val second = f.second(); val other = f.store(second)
            assertNotEquals(f.db.pid, second.pid)
            val firstDb = if (revokeFirst) second else f.db
            val waitingDb = if (revokeFirst) f.db else second
            val held = CountDownLatch(1); val release = CountDownLatch(1)
            val waitingBegin = CountDownLatch(1); val waitingAcquired = CountDownLatch(1)
            val firstTime = AtomicBoolean(true); val workers = Executors.newFixedThreadPool(2)
            firstDb.beforeCommit = {
                if (firstTime.compareAndSet(true, false)) {
                    held.countDown(); check(release.await(2, TimeUnit.SECONDS)) { "Owned write-lock barrier timed out" }
                }
            }
            waitingDb.beforeSql = { if (it == "BEGIN IMMEDIATE") waitingBegin.countDown() }
            waitingDb.afterSql = { if (it == "BEGIN IMMEDIATE") waitingAcquired.countDown() }
            try {
                val first = workers.submit<Any?> {
                    if (revokeFirst) other.updateAuthorization(f.source, job.generation, ProcessingPolicy.REMOTE_ALLOWED, f.selected, f.grant.copy(revoked = true))
                    else f.repository.commit(job, f.candidate(), f.clock)
                }
                assertTrue(held.await(1, TimeUnit.SECONDS))
                val waiting = workers.submit<Any?> {
                    if (revokeFirst) f.repository.commit(job, f.candidate(), f.clock)
                    else other.updateAuthorization(f.source, job.generation, ProcessingPolicy.REMOTE_ALLOWED, f.selected, f.grant.copy(revoked = true))
                }
                assertTrue(waitingBegin.await(1, TimeUnit.SECONDS))
                assertFalse("Other SQLite PID must wait for actual write lock", waitingAcquired.await(100, TimeUnit.MILLISECONDS))
                assertFalse(waiting.isDone); release.countDown()
                val firstResult = first.get(2, TimeUnit.SECONDS); val waitingResult = waiting.get(2, TimeUnit.SECONDS)
                assertTrue(waitingAcquired.await(1, TimeUnit.SECONDS))
                if (revokeFirst) {
                    assertNull(firstResult); deniedCommit(waitingResult as SourceCommitOutcome, SourceCommitDenial.AUTHORIZATION_CHANGED)
                    assertEquals("CANCELLED", f.jobs().single()[1])
                } else {
                    assertTrue(firstResult is SourceCommitOutcome.Committed); assertNull(waitingResult)
                    assertEquals("COMMITTED", f.jobs().single()[1])
                }
                assertTrue(f.annotations().isEmpty()); f.reopen(); assertTrue(f.annotations().isEmpty())
            } finally {
                release.countDown(); firstDb.beforeCommit = null
                waitingDb.beforeSql = null; waitingDb.afterSql = null
                workers.shutdownNow(); assertTrue(workers.awaitTermination(1, TimeUnit.SECONDS))
            }
        }
    }

    private fun deniedQueue(outcome: SourceQueueOutcome, expected: SourceCommitDenial) =
        assertEquals(SourceQueueOutcome.Denied(expected), outcome)
    private fun deniedCommit(outcome: SourceCommitOutcome, expected: SourceCommitDenial) =
        assertEquals(SourceCommitOutcome.Denied(expected), outcome)
    private fun failsSql(action: () -> Unit) = fails<SqliteBridgeFailure>(action)
    private inline fun <reified T : Throwable> fails(action: () -> Unit) {
        try { action(); fail("Expected safe typed rejection") } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }
    private fun copyJob(j: SourceBoundTextJob, text: String = j.originalText, hash: String = j.contentHash,
                        generation: Long = j.generation, prompt: String = j.promptVersion,
                        captured: Long = j.capturedAtMillis, zone: String = j.captureTimeZone,
                        state: RemoteSendState = j.state, intent: RemoteSendIntent = j.intent) =
        SourceBoundTextJob(j.id, generation, prompt, intent, state, text, hash, captured, zone)
}
