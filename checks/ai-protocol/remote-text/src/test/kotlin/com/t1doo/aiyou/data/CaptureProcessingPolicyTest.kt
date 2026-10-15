package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real capture SQL, candidate AEAD and package restore; no SDK, remote model or consent grant. */
class CaptureProcessingPolicyTest {
    @Test fun freshlyTypedAndMixedOriginalsSupportExplicitLocalExcerptWhileRemoteProcessingStaysDenied(): Unit = runBlocking {
        for (withImage in listOf(false, true)) fixture().use { f ->
            val capture = MixedCaptureSqlTest.repo(f)
            var draft = capture.edit(capture.current(), "合成自录原文：我今天不想喝咖啡。")
            if (withImage) draft = capture.add(draft, MixedCaptureSqlTest.intent(draft), MixedCaptureSqlTest.png)
            capture.save(draft, 10, "UTC")
            assertTrue(f.db.read("SELECT id FROM local_candidate_batches").isEmpty())
            val candidates = SqlLocalCandidateRepository(f.gateway, PermanentErasureFixture.KEY)
            try {
                // Baseline fails here: production capture incorrectly writes AI_NONE.
                val job = candidates.begin(LocalCandidateStart(uuid(), SourceVersion(draft.id, 1), 20))
                val batch = candidates.complete(job, LocalExcerptRules.extract(job))
                assertEquals(draft.text, batch.items.single().proposal.quote)
                assertEquals(ExcerptReview.PROPOSED, batch.items.single().review)
                val policy = policy(f, draft.id)
                assertEquals(ProcessingPolicy.LOCAL_ONLY, policy)
                assertFalse(mayProcessRemotely(policy, false)); assertFalse(mayProcessRemotely(policy, true))
                assertEquals(listOf(null, null), f.db.read("SELECT connection,consent FROM remote_source_access WHERE entryId=?", draft.id).single())
                for (purpose in TextPurpose.entries) assertEquals(SourceQueueOutcome.Denied(SourceCommitDenial.POLICY_BLOCKED),
                    RemoteTextTransactions(f.gateway).queue(RemoteSendIntent(SourceVersion(draft.id, 1), purpose), "synthetic-policy", { Instant.ofEpochMilli(30) }))
                assertTrue(f.db.read("SELECT id FROM remote_text_jobs").isEmpty())
                assertEquals(if (withImage) 1 else 0, f.media.list(draft.id, 1).size)
            } finally { candidates.wipeKey() }
        }
    }
    @Test fun imageOnlyCaptureKeepsEmptyOriginalAndLocalOnlyDefaultWithoutInventingText(): Unit = runBlocking {
        fixture().use { f ->
            val capture = MixedCaptureSqlTest.repo(f); val empty = capture.current()
            val draft = capture.add(empty, MixedCaptureSqlTest.intent(empty), MixedCaptureSqlTest.png)
            capture.save(draft, 10, "UTC")
            assertEquals(ProcessingPolicy.LOCAL_ONLY, policy(f, draft.id))
            assertEquals("", f.db.read("SELECT originalText FROM entry_revisions WHERE entryId=?", draft.id).single().single())
            assertTrue(f.db.read("SELECT id FROM local_candidate_batches WHERE entryId=?", draft.id).isEmpty())
            val candidates = SqlLocalCandidateRepository(f.gateway, PermanentErasureFixture.KEY)
            try { assertThrows(LocalCandidateStorageFailure::class.java) {
                candidates.begin(LocalCandidateStart(uuid(), SourceVersion(draft.id, 1), 20))
            } } finally { candidates.wipeKey() }
            assertTrue(f.db.read("SELECT id FROM local_candidate_batches WHERE entryId=?", draft.id).isEmpty())
            assertArrayEquals(MixedCaptureSqlTest.png, f.media.read(draft.images.single().intent.id))
        }
    }
    @Test fun saveReplayAndNewCapturePreserveRestrictedOriginalsAndRestoredCopies(): Unit = runBlocking {
        fixture().use { f ->
            val capture = MixedCaptureSqlTest.repo(f)
            val draft = capture.edit(capture.current(), "合成明确禁止处理的旧记录")
            capture.save(draft, 10, "UTC")
            f.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?", draft.id)
            assertTrue(capture.save(draft, 99, "Asia/Shanghai").replayed)
            assertEquals(ProcessingPolicy.AI_NONE, policy(f, draft.id))
            val next = capture.edit(capture.current(), "合成之后自录的新记录")
            capture.save(next, 20, "UTC")
            assertEquals(ProcessingPolicy.LOCAL_ONLY, policy(f, next.id))
            val snapshot = SqlOriginalExportRepository(f.gateway, f.tags).capture(listOf(SourceVersion(next.id, 1)), uuid(), 30)
            val cipher = JcaRecordPackageCipher(); val envelope = cipher.seal(snapshot, PASSWORD.toCharArray())
            val bytes = envelope.copyBytes()
            try {
                val verified = cipher.open(bytes, PASSWORD.toCharArray())
                try {
                    val restored = uuid(); f.packages.restore(verified, listOf(restored), 40)
                    assertEquals(ProcessingPolicy.AI_NONE, policy(f, restored))
                    assertEquals(ProcessingPolicy.AI_NONE, policy(f, draft.id))
                    val candidates = SqlLocalCandidateRepository(f.gateway, PermanentErasureFixture.KEY)
                    try {
                        for (id in listOf(draft.id, restored)) assertThrows(LocalCandidateConflict::class.java) {
                            candidates.begin(LocalCandidateStart(uuid(), SourceVersion(id, 1), 50))
                        }
                    } finally { candidates.wipeKey() }
                } finally { verified.release() }
            } finally { bytes.fill(0); envelope.release() }
        }
    }
    companion object {
        private const val PASSWORD = "synthetic policy recovery passphrase"
        private fun uuid() = UUID.randomUUID().toString()
        private fun fixture() = MixedCaptureSqlTest.fixture().also { f -> f.db.transaction { sql ->
            LocalCandidateSqlSchema.migration9To10.forEach { sql.execute(it) }
            LocalCandidateSqlSchema.guards.forEach { sql.execute(it) }; sql.execute("PRAGMA user_version=10")
        } }
        private fun policy(f: PermanentErasureFixture, id: String) = ProcessingPolicy.valueOf(
            f.db.read("SELECT privacyPolicy FROM entries WHERE id=?", id).single().single() as String)
    }
}
