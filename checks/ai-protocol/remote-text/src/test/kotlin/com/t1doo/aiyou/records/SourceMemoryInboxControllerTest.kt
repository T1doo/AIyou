package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.SourceMemoryFixture
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceMemoryInboxControllerTest {
    private class Harness : AutoCloseable {
        val f = SourceMemoryFixture()
        var local = LocalRecordUiState(ready = true, opening = false, supportsMemoryInbox = true,
            entries = listOf(StoredEntry("source", 2, f.original, 5, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY)))
        val queue = java.util.ArrayDeque<suspend (EntryRepository) -> Unit>()
        val owner = object : EntryRepository {
            override val sourceMemories = f.store
            override suspend fun readDraft(): TextDraft? = null
            override suspend fun updateDraft(text: String): TextDraft = error("Read only")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Read only")
            override suspend fun entries() = local.entries
            override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Read only")
        }
        val controller = SourceMemoryInboxController({ local }, { action -> local = reconcileMemoryInbox(local, action(local)) }, { queue.add(it) })
        fun drain() = runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(owner) }
        fun ui() = checkNotNull(local.memoryInbox)
        override fun close() { controller.dispose(); f.close() }
    }
    @Test fun refreshedClosedAndReopenedPagesRejectRetainedActionsAndUnstartedReads(): Unit = Harness().use { h ->
        h.f.create(); h.controller.open(); h.drain(); val old = h.ui(); var opened = 0
        h.controller.refresh(old.session, old.request)
        h.controller.source(old.session, old.request, old.snapshot!!.items.single()) { opened++; true }
        h.controller.close(old.session); h.drain(); assertNull(h.local.memoryInbox); assertEquals(0, opened)
        h.controller.open(); h.drain(); val fresh = h.ui()
        assertNotEquals(old.session, fresh.session)
        h.controller.refresh(old.session, old.request); assertTrue(h.queue.isEmpty())
        assertEquals(SourceMemoryReview.PROPOSED, h.f.store.memories(h.f.source).single().current!!.review)
        assertTrue(h.f.db.read("SELECT * FROM manual_tasks").isEmpty())
    }
    @Test fun failedReadAndChangedReviewHideUnverifiedPagesAndRefreshReadsFreshEvidence(): Unit = Harness().use { h ->
        val item = h.f.create()
        h.f.db.beforeSql = { sql -> if (sql.contains("FROM local_candidate_batches c")) error("Synthetic read failure") }
        h.controller.open(); h.drain(); val failed = h.ui()
        assertNotNull(failed.error); assertNull(failed.snapshot); assertFalse(failed.busy)
        h.f.db.beforeSql = null; h.controller.refresh(failed.session, failed.request); h.drain()
        val page = h.ui(); assertEquals(1, page.snapshot!!.items.size)
        h.f.store.mutate(h.f.change(item, SourceMemoryAction.ACCEPT)); var opened = false
        h.controller.source(page.session, page.request, page.snapshot!!.items.single()) { opened = true; true }; h.drain()
        assertFalse(opened); assertNull(h.ui().snapshot); assertNotNull(h.ui().error)
        val invalid = h.ui(); h.controller.refresh(invalid.session, invalid.request); h.drain()
        assertNull(h.ui().error); assertTrue(h.ui().snapshot!!.items.isEmpty())
    }
    @Test fun readinessLossAndSourceReplacementSynchronouslyRetirePrivateResultsAndLateCallbacks(): Unit = Harness().use { h ->
        h.f.create(); h.controller.open(); h.drain(); val old = h.ui(); var opened = false
        h.local = reconcileMemoryInbox(h.local, h.local.copy(entries = emptyList()))
        assertNull(h.ui().snapshot); assertNotSame(old.request, h.ui().request)
        h.controller.source(old.session, old.request, old.snapshot!!.items.single()) { opened = true; true }
        assertTrue(h.queue.isEmpty()); assertFalse(opened)
        h.local = reconcileMemoryInbox(h.local, h.local.copy(ready = false)); assertNull(h.local.memoryInbox)
        h.controller.refresh(old.session, old.request); assertTrue(h.queue.isEmpty())
    }
    @Test fun switchingToAcceptedRetiresPendingActionsAndRevocationCannotOpenAnOldAcceptedPage(): Unit = Harness().use { h ->
        h.f.create("pending")
        val accepted = h.f.store.mutate(h.f.change(h.f.create("accepted"), SourceMemoryAction.ACCEPT))
        h.controller.open(); h.drain(); val pending = h.ui()
        assertEquals(listOf("pending"), pending.snapshot!!.items.map { it.id })
        h.controller.selectSection(pending.session, pending.request, SourceMemoryInboxSection.ACCEPTED)
        assertNull(h.ui().snapshot); assertNotSame(pending.request, h.ui().request)
        var opened = false
        h.controller.source(pending.session, pending.request, pending.snapshot.items.single()) { opened = true; true }
        h.controller.selectSection(pending.session, pending.request, SourceMemoryInboxSection.PENDING)
        h.drain(); val page = h.ui()
        assertFalse(opened); assertEquals(SourceMemoryInboxSection.ACCEPTED, page.section)
        assertEquals(listOf("accepted"), page.snapshot!!.items.map { it.id })
        h.f.store.mutate(h.f.change(accepted, SourceMemoryAction.REVOKE))
        h.controller.source(page.session, page.request, page.snapshot.items.single()) { opened = true; true }; h.drain()
        assertFalse(opened); assertNull(h.ui().snapshot); assertNotNull(h.ui().error)
        val failed = h.ui(); h.controller.refresh(failed.session, failed.request); h.drain()
        assertEquals(SourceMemoryInboxSection.ACCEPTED, h.ui().section)
        assertTrue(h.ui().snapshot!!.items.isEmpty()); assertNull(h.ui().error)
    }
}
