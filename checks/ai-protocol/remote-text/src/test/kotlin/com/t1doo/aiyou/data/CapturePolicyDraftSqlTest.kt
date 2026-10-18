package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real encrypted draft framing, SQL/CAS and encrypted image files. No Android/model execution claim. */
class CapturePolicyDraftSqlTest {
    @Test fun frozenV1DraftBytesAndItsSaveReceiptRemainCompatibleWithoutMigration(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            seed(f, LEGACY)
            val capture = MixedCaptureSqlTest.repo(f)
            val draft = capture.current()
            assertEquals(LEGACY_ID, draft.id); assertEquals(7L, draft.version)
            assertEquals("合成旧草稿", draft.text); assertEquals(ProcessingPolicy.LOCAL_ONLY, draft.policy)
            assertEquals(LEGACY, state(f))
            assertEquals(7L, capture.changePolicy(draft, ProcessingPolicy.LOCAL_ONLY).version)
            assertEquals(LEGACY, state(f))
            capture.save(draft, 10, "UTC")
            assertEquals(packageHex(packageHash(LEGACY.toByteArray(Charsets.US_ASCII))),
                f.db.read("SELECT state FROM record_package_receipts WHERE operation='CAPTURE_SAVED'").single().single())
            assertTrue(MixedCaptureSqlTest.repo(f).save(draft, 99, "Asia/Shanghai").replayed)
            assertEquals("LOCAL_ONLY", f.db.read("SELECT privacyPolicy FROM entries WHERE id=?", draft.id).single().single())
        }
        MixedCaptureSqlTest.fixture().use { f ->
            seed(f, LEGACY)
            val c = MixedCaptureSqlTest.repo(f)
            val edited = c.edit(c.current(), "合成修正后的旧草稿")
            assertEquals(8L, edited.version)
            assertEquals(1, ByteBuffer.wrap(Base64.getDecoder().decode(state(f))).int)
        }
    }

    @Test fun explicitNoneSurvivesUnfinishedTextImageMutationsColdReopenAndAtomicSave(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            var c = MixedCaptureSqlTest.repo(f)
            var draft = c.edit(c.current(), "合成未完成\uD83D")
            draft = c.changePolicy(draft, ProcessingPolicy.AI_NONE)
            assertEquals("合成未完成\uD83D", draft.text)
            draft = c.edit(draft, "合成完整😀图文")
            val removed = MixedCaptureSqlTest.intent(draft)
            draft = c.add(draft, removed, MixedCaptureSqlTest.png)
            draft = c.remove(draft.id, removed.id)
            val kept = MixedCaptureSqlTest.intent(draft)
            draft = c.add(draft, kept, MixedCaptureSqlTest.png)
            assertEquals(ProcessingPolicy.AI_NONE, draft.policy)
            val cipher = f.mediaRoot.resolve(kept.id + ".aead").readBytes()
            assertEquals(2, ByteBuffer.wrap(Base64.getDecoder().decode(state(f))).int)
            f.reopen(); c = MixedCaptureSqlTest.repo(f); c.recover(); draft = c.current()
            assertEquals(ProcessingPolicy.AI_NONE, draft.policy); assertEquals("合成完整😀图文", draft.text)
            assertArrayEquals(MixedCaptureSqlTest.png, c.image(draft, kept.id))
            assertArrayEquals(cipher, f.mediaRoot.resolve(kept.id + ".aead").readBytes())
            c.save(draft, 20, "UTC")
            assertEquals(listOf("AI_NONE", "合成完整😀图文"), f.db.read(
                "SELECT e.privacyPolicy,r.originalText FROM entries e JOIN entry_revisions r ON r.entryId=e.id WHERE e.id=?", draft.id).single())
            assertArrayEquals(MixedCaptureSqlTest.png, f.media.read(kept.id))
            assertEquals(ProcessingPolicy.LOCAL_ONLY, c.current().policy)
        }
    }

    @Test fun exactRangeRetryIsIdempotentAndAbaStaleEditOrSaveCannotRebase(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val c = MixedCaptureSqlTest.repo(f)
            val original = c.edit(c.current(), "合成同文不同范围")
            val chosen = c.changePolicy(original, ProcessingPolicy.AI_NONE)
            assertEquals(original.version + 1, chosen.version)
            assertEquals(chosen.version, c.changePolicy(original, ProcessingPolicy.AI_NONE).version)
            fails<CaptureConflict> { c.edit(original, original.text) }
            fails<CaptureConflict> { c.save(original, 10, "UTC") }
            assertTrue(f.db.read("SELECT id FROM entries").isEmpty())
            val returned = c.changePolicy(chosen, ProcessingPolicy.LOCAL_ONLY)
            assertEquals(original.version + 2, returned.version)
            fails<CaptureConflict> { c.changePolicy(original, ProcessingPolicy.AI_NONE) }
            assertEquals(ProcessingPolicy.LOCAL_ONLY, c.current().policy)
            fails<CaptureStorageFailure> { c.changePolicy(returned, ProcessingPolicy.REMOTE_ALLOWED) }
            assertEquals(returned.version, c.current().version)
            assertThrows(IllegalArgumentException::class.java) {
                CaptureDraft(original.id, original.version, original.text, original.images, policy = ProcessingPolicy.REMOTE_ALLOWED)
            }
        }
    }

    @Test fun malformedOrRemoteV2DraftsFailClosedInsteadOfFallingBackToLocalOnly(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            seed(f, LEGACY)
            val c = MixedCaptureSqlTest.repo(f)
            val body = Base64.getDecoder().decode(LEGACY).copyOfRange(4, Base64.getDecoder().decode(LEGACY).size)
            fun v2(policy: String): String = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { out -> out.writeInt(2); val name = policy.toByteArray(Charsets.US_ASCII)
                    out.writeInt(name.size); out.write(name); out.write(body) }
                Base64.getEncoder().encodeToString(bytes.toByteArray())
            }
            val badVersion = Base64.getDecoder().decode(LEGACY).also { ByteBuffer.wrap(it).putInt(3) }
            for (bad in listOf(v2("REMOTE_ALLOWED"), v2("LOCAL_ONLY"), v2("UNKNOWN"),
                Base64.getEncoder().encodeToString(badVersion), Base64.getEncoder().encodeToString(Base64.getDecoder().decode(LEGACY) + byteArrayOf(0)))) {
                f.db.write("UPDATE record_package_receipts SET state=? WHERE id='CAPTURE_CURRENT'", bad)
                fails<CaptureStorageFailure> { c.current() }
                assertTrue(f.db.read("SELECT id FROM entries").isEmpty())
            }
            f.db.write("UPDATE record_package_receipts SET state=? WHERE id='CAPTURE_CURRENT'", v2("AI_NONE"))
            assertEquals(ProcessingPolicy.AI_NONE, c.current().policy)
            assertEquals("合成旧草稿", c.current().text)
        }
    }

    @Test fun chosenNoneTextAndMixedRecordsRetainOriginalsButDenyDerivedAndRemoteWork(): Unit = runBlocking {
        for (kind in listOf("text", "mixed", "image")) MixedCaptureSqlTest.fixture().use { f ->
            f.db.transaction { sql -> LocalCandidateSqlSchema.migration9To10.forEach { sql.execute(it) }
                LocalCandidateSqlSchema.guards.forEach { sql.execute(it) }; sql.execute("PRAGMA user_version=10") }
            val c = MixedCaptureSqlTest.repo(f)
            var draft = c.edit(c.current(), if(kind=="image") "" else "合成只作手账的明确陈述")
            val image=kind!="text"
            if (image) draft = c.add(draft, MixedCaptureSqlTest.intent(draft), MixedCaptureSqlTest.png)
            draft = c.changePolicy(draft, ProcessingPolicy.AI_NONE)
            c.save(draft, 10, "UTC")
            assertEquals("AI_NONE", f.db.read("SELECT privacyPolicy FROM entries WHERE id=?", draft.id).single().single())
            assertEquals(draft.text, f.db.read("SELECT originalText FROM entry_revisions WHERE entryId=?", draft.id).single().single())
            assertTrue(f.db.read("SELECT id FROM local_candidate_batches").isEmpty())
            val candidates = SqlLocalCandidateRepository(f.gateway, PermanentErasureFixture.KEY)
            try { if(draft.text.isNotBlank()) assertThrows(LocalCandidateConflict::class.java) {
                candidates.begin(LocalCandidateStart(UUID.randomUUID().toString(), SourceVersion(draft.id, 1), 20))
            } } finally { candidates.wipeKey() }
            for (purpose in TextPurpose.entries) assertEquals(SourceQueueOutcome.Denied(SourceCommitDenial.POLICY_BLOCKED),
                RemoteTextTransactions(f.gateway).queue(RemoteSendIntent(SourceVersion(draft.id, 1), purpose), "synthetic-policy", { Instant.ofEpochMilli(30) }))
            assertTrue(f.db.read("SELECT id FROM remote_text_jobs").isEmpty())
            assertEquals(if (image) 1 else 0, f.media.list(draft.id, 1).size)
            if (image) assertArrayEquals(MixedCaptureSqlTest.png, f.media.read(draft.images.single().intent.id))
        }
    }

    @Test fun committedSaveReceiptBindsRangeAndReplayNeverOverwritesLaterRecordChoice(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val c = MixedCaptureSqlTest.repo(f)
            val draft = c.changePolicy(c.edit(c.current(), "合成先选范围再保存"), ProcessingPolicy.AI_NONE)
            c.save(draft, 10, "UTC")
            f.db.write("UPDATE entries SET privacyPolicy='LOCAL_ONLY' WHERE id=?", draft.id)
            assertTrue(c.save(draft, 99, "UTC").replayed)
            assertEquals("LOCAL_ONLY", f.db.read("SELECT privacyPolicy FROM entries WHERE id=?", draft.id).single().single())
            val wrongRange = CaptureDraft(draft.id, draft.version, draft.text, draft.images, policy = ProcessingPolicy.LOCAL_ONLY)
            fails<CaptureStorageFailure> { c.save(wrongRange, 99, "UTC") }
            assertEquals(1, f.db.read("SELECT id FROM entries").size)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, c.current().policy)
        }
    }

    private suspend inline fun <reified T : Throwable> fails(crossinline action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, observed ${error?.javaClass?.simpleName}", error is T)
    }
    private fun seed(f: PermanentErasureFixture, encoded: String) {
        f.db.write("INSERT INTO record_package_receipts VALUES(?,?,1,'CAPTURE_CURRENT',?)", "CAPTURE_CURRENT", LEGACY_ID, encoded)
    }
    private fun state(f: PermanentErasureFixture) = f.db.read("SELECT state FROM record_package_receipts WHERE id='CAPTURE_CURRENT'").single().single() as String
    companion object {
        private const val LEGACY_ID = "11111111-1111-4111-8111-111111111111"
        // Frozen v1 encoding from the accepted baseline: UUID, version7, exact UTF-16 text, no images.
        private const val LEGACY = "AAAAAQAAACQxMTExMTExMS0xMTExLTQxMTEtODExMS0xMTExMTExMTExMTEAAAAAAAAABwAAAAAAAAAFAABUCAAAYhAAAGXnAACDSQAAej8AAAAA"
    }
}
