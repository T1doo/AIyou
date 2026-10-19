package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.CaptureStorageFailure
import com.t1doo.aiyou.model.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual SQL/file owner, with only the device preference reader injected. */
class CaptureDefaultPolicyTest {
    @Test fun newSlotReadsLiveDefaultButColdReopenAndPerDraftChoiceRemainIndependent(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            var chosen = ProcessingPolicy.AI_NONE
            var reads = 0
            fun owner() = SqlCaptureRepository(f.gateway, f.media, f.tags) { reads++; chosen }
            var c = owner()
            var draft = c.current()
            assertEquals(1L, draft.version); assertEquals(ProcessingPolicy.AI_NONE, draft.policy); assertEquals(1, reads)
            draft = c.edit(draft, "合成默认不参与 AI 的记录")
            chosen = ProcessingPolicy.LOCAL_ONLY
            f.reopen(); c = owner()
            assertCaptureDraftEquals(draft, c.current()); assertEquals(1, reads)
            val image = MixedCaptureSqlTest.intent(draft)
            draft = c.add(draft, image, MixedCaptureSqlTest.png)
            c.save(draft, 10, "UTC")
            assertEquals("AI_NONE", f.db.read("SELECT privacyPolicy FROM entries WHERE id=?", draft.id).single().single())
            var next = c.current()
            assertNotEquals(draft.id, next.id); assertEquals(ProcessingPolicy.LOCAL_ONLY, next.policy); assertEquals(2, reads)
            next = c.changePolicy(next, ProcessingPolicy.AI_NONE)
            assertCaptureDraftEquals(next, c.current()); assertEquals(2, reads)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, chosen)
            assertArrayEquals(MixedCaptureSqlTest.png, f.media.read(image.id))
            assertTrue(f.db.read("SELECT id FROM remote_text_jobs").isEmpty())
        }
    }

    @Test fun existingAndLegacyDraftsNeverReadOrAdoptTheNewDefault(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val old = MixedCaptureSqlTest.repo(f).current()
            val bytes = f.db.read("SELECT state FROM record_package_receipts WHERE id='CAPTURE_CURRENT'").single().single()
            val c = SqlCaptureRepository(f.gateway, f.media, f.tags) { error("Existing slot must not read device settings") }
            assertCaptureDraftEquals(old, c.current())
            assertEquals(bytes, f.db.read("SELECT state FROM record_package_receipts WHERE id='CAPTURE_CURRENT'").single().single())
        }
        MixedCaptureSqlTest.fixture().use { f ->
            val id = MixedCaptureSqlTest.uuid()
            f.db.write("INSERT INTO capture_draft VALUES(1,?,7,?)", id, "合成旧文字草稿")
            val c = SqlCaptureRepository(f.gateway, f.media, f.tags) { error("Legacy draft must not read device settings") }
            val migrated = c.current()
            assertEquals(id, migrated.id); assertEquals("合成旧文字草稿", migrated.text)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, migrated.policy)
            assertTrue(f.db.read("SELECT id FROM capture_draft").isEmpty())
        }
    }

    @Test fun discardCreatesLatestDefaultOnceAndLostAcknowledgementCannotReplaceNewInput(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            var chosen = ProcessingPolicy.AI_NONE
            val c = SqlCaptureRepository(f.gateway, f.media, f.tags) { chosen }
            var old = c.edit(c.current(), "合成待清空草稿")
            val image = MixedCaptureSqlTest.intent(old)
            old = c.add(old, image, MixedCaptureSqlTest.png)
            chosen = ProcessingPolicy.LOCAL_ONLY
            var inserted = false
            f.db.afterSql = { sql ->
                if (sql.startsWith("INSERT INTO record_package_receipts") && sql.contains("'CAPTURE_CURRENT'")) inserted = true
                if (sql == "COMMIT" && inserted) { inserted = false; throw IOException("Synthetic new-slot ACK loss") }
            }
            fails { c.discard(old.id) }
            f.db.afterSql = null
            val fresh = c.current()
            assertNotEquals(old.id, fresh.id); assertEquals(ProcessingPolicy.LOCAL_ONLY, fresh.policy)
            val changed = c.edit(fresh, "合成之后输入")
            chosen = ProcessingPolicy.AI_NONE
            assertCaptureDraftEquals(changed, c.discard(old.id))
            assertCaptureDraftEquals(changed, c.current())
            assertFalse(f.mediaRoot.resolve(image.id + ".aead").exists())
            assertTrue(f.db.read("SELECT id FROM entries").isEmpty())
        }
    }

    @Test fun invalidDefaultCannotCreateRemoteSlotOrUndoAnAlreadySavedRecord(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            var chosen = ProcessingPolicy.REMOTE_ALLOWED
            val c = SqlCaptureRepository(f.gateway, f.media, f.tags) { chosen }
            fails { c.current() }
            assertTrue(f.db.read("SELECT * FROM record_package_receipts").isEmpty())
            chosen = ProcessingPolicy.AI_NONE
            val draft = c.edit(c.current(), "合成已提交记录")
            chosen = ProcessingPolicy.REMOTE_ALLOWED
            assertCaptureDraftEquals(draft, c.current())
            c.save(draft, 10, "UTC")
            fails { c.current() }
            assertTrue(c.save(draft, 99, "Asia/Shanghai").replayed)
            assertEquals(listOf("AI_NONE", 10L), f.db.read("SELECT privacyPolicy,capturedAtMillis FROM entries WHERE id=?", draft.id).single())
            chosen = ProcessingPolicy.LOCAL_ONLY
            assertEquals(ProcessingPolicy.LOCAL_ONLY, c.current().policy)
            assertEquals(1, f.db.read("SELECT id FROM entries").size)
        }
    }

    private suspend fun fails(block: suspend () -> Any?) {
        var failure: Exception? = null
        try { block() } catch (caught: Exception) { failure = caught }
        assertTrue("Expected a bounded storage failure", failure is CaptureStorageFailure)
    }
}

internal fun assertCaptureDraftEquals(expected: CaptureDraft, actual: CaptureDraft) {
    assertEquals(expected.id, actual.id)
    assertEquals(expected.version, actual.version)
    assertEquals(expected.text, actual.text)
    assertEquals(expected.images, actual.images)
    assertEquals(expected.discarding, actual.discarding)
    assertEquals(expected.policy, actual.policy)
}
