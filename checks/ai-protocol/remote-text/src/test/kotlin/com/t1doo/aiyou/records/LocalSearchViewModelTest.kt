package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Actual VM actor and index SQL over owned synthetic SQLite; no Android/Keystore claim. */
class LocalSearchViewModelTest {
    @Test fun boundedIndexReturnsToActorTailSoQueuedCaptureCommitPrecedesNextBatch(): Unit = runBlocking {
        Harness().use { h ->
            repeat(20) { h.fixture.seed("a-" + it, "合成队列词元" + it, it.toLong()) }
            val first = CountDownLatch(1); val releaseFirst = CountDownLatch(1)
            val second = CountDownLatch(1); val releaseSecond = CountDownLatch(1)
            h.beforeBatch = { count ->
                if (count == 1) { first.countDown(); assertTrue(releaseFirst.await(5, TimeUnit.SECONDS)) }
                if (count == 2) { second.countDown(); assertTrue(releaseSecond.await(5, TimeUnit.SECONDS)) }
            }
            h.start(); h.ready(); assertTrue(first.await(5, TimeUnit.SECONDS))
            val caller = Thread.currentThread()
            h.vm.changeText("合成排队捕获咖啡"); h.vm.save()
            releaseFirst.countDown()
            assertTrue(second.await(5, TimeUnit.SECONDS))
            assertEquals(1L, h.vm.state.value.saveFeedbackId)
            assertFalse(h.vm.state.value.savePending)
            assertEquals("", h.vm.state.value.text)
            assertTrue(h.events.indexOf("capture-commit") < h.events.indexOf("batch-2"))
            assertNotSame(caller, h.base.saveThread.get())
            releaseSecond.countDown()
            h.settled()
            assertEquals(22, h.fixture.base.db.read("SELECT * FROM local_search_documents").size)
            assertTrue(h.actual.candidates(LocalSearchRequest("咖啡")).entries.any { it.originalText == "合成排队捕获咖啡" })
        }
    }
    @Test fun realIndexSqlFailurePreservesCaptureAndStopsAutomaticRetryUntilExplicitRebuild(): Unit = runBlocking {
        Harness().use { h ->
            h.fixture.base.db.write("CREATE TEMP TRIGGER synthetic_vm_index_abort BEFORE INSERT ON local_search_documents BEGIN SELECT RAISE(ABORT,'synthetic index failure'); END")
            h.start(); h.ready()
            withTimeout(10_000) { h.vm.state.first { it.localIndexError != null } }
            assertNull(h.vm.state.value.error)
            assertTrue(h.vm.state.value.ready)
            h.vm.changeText("合成索引暂停仍可捕获"); h.vm.save()
            withTimeout(10_000) { h.vm.state.first { it.saveFeedbackId == 1L && !it.savePending } }
            assertEquals(1, h.batchCalls.get())
            assertNull(h.vm.state.value.error)
            assertEquals("", h.vm.state.value.text)
            assertTrue(h.actual.candidates(LocalSearchRequest("暂停")).pending)
            h.fixture.base.db.write("DROP TRIGGER synthetic_vm_index_abort")
            val rebuilding = CountDownLatch(1); val releaseRebuild = CountDownLatch(1)
            h.beforeBatch = { rebuilding.countDown(); assertTrue(releaseRebuild.await(5, TimeUnit.SECONDS)) }
            h.vm.rebuildLocalSearch()
            assertTrue(rebuilding.await(5, TimeUnit.SECONDS))
            repeat(50) { h.vm.rebuildLocalSearch() }
            releaseRebuild.countDown(); h.beforeBatch = null
            h.settled()
            val beforeRefresh = h.vm.state.value.entries
            h.vm.refreshLocalSearch()
            withTimeout(10_000) { h.vm.state.first { it.entries !== beforeRefresh } }
            assertEquals(1, h.rebuildCalls.get())
            assertTrue(h.batchCalls.get() >= 2)
            assertNull(h.vm.state.value.localIndexError)
            assertFalse(h.actual.candidates(LocalSearchRequest("暂停")).pending)
        }
    }
    @Test fun refreshIsCoalescedAndNeverReplacesCaptureInputAndDisposeDrainsActiveIndex(): Unit = runBlocking {
        Harness().use { h ->
            h.start(); h.ready(); h.settled()
            h.vm.changeText("synthetic-private-live-capture")
            withTimeout(10_000) { h.vm.state.first { it.draftNotice == "草稿已保存在本机" } }
            val reading = CountDownLatch(1); val release = CountDownLatch(1)
            val refreshingIndex = CountDownLatch(1); val finishRefreshIndex = CountDownLatch(1)
            h.beforeBatch = {
                refreshingIndex.countDown(); assertTrue(finishRefreshIndex.await(5, TimeUnit.SECONDS))
            }
            h.beforeEntries = { reading.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
            val previousReads = h.entryReads.get()
            h.vm.refreshLocalSearch(); assertTrue(reading.await(5, TimeUnit.SECONDS))
            repeat(50) { h.vm.refreshLocalSearch() }
            release.countDown()
            withTimeout(10_000) { h.vm.state.first { it.entries !== h.openedEntries.get() } }
            h.beforeEntries = null
            assertEquals(previousReads + 1, h.entryReads.get())
            assertEquals("synthetic-private-live-capture", h.vm.state.value.text)
            assertTrue(refreshingIndex.await(5, TimeUnit.SECONDS))
            h.beforeBatch = null; finishRefreshIndex.countDown()
            h.settled()
            val indexing = CountDownLatch(1); val finishIndex = CountDownLatch(1)
            h.fixture.base.db.afterSql = { sql ->
                if (sql.startsWith("UPDATE local_search_epoch SET epoch=?")) {
                    h.fixture.base.db.afterSql = null; indexing.countDown()
                    assertTrue(finishIndex.await(5, TimeUnit.SECONDS))
                }
            }
            h.vm.rebuildLocalSearch(); assertTrue(indexing.await(5, TimeUnit.SECONDS))
            h.vm.clearForHostTest()
            val frozen = h.vm.state.value
            assertFalse(h.base.closed.await(100, TimeUnit.MILLISECONDS))
            finishIndex.countDown()
            assertTrue(h.base.closed.await(5, TimeUnit.SECONDS))
            assertSame(frozen, h.vm.state.value)
            assertThrows(LocalSearchFailure::class.java) { h.actual.epoch() }
        }
    }

    private class Harness : AutoCloseable {
        val fixture = LocalSearchSqlTest.Fixture()
        val gate = DrainingRecordsSqlDatabase(fixture.base.db)
        val tags = SqlManualTagRepository(gate, ManualTaskSqlTest.KEY)
        val actual = SqlLocalSearchRepository(gate, tags)
        val batchCalls = AtomicInteger()
        val rebuildCalls = AtomicInteger()
        val entryReads = AtomicInteger()
        val events = CopyOnWriteArrayList<String>()
        val openedEntries = AtomicReference<List<StoredEntry>>()
        @Volatile var beforeBatch: ((Int) -> Unit)? = null
        @Volatile var beforeEntries: (() -> Unit)? = null
        val port = object : LocalSearchRepository by actual {
            override fun rebuild(): Long { rebuildCalls.incrementAndGet(); return actual.rebuild() }
            override fun batch(): LocalSearchProgress {
                val count = batchCalls.incrementAndGet()
                events += "batch-" + count
                beforeBatch?.invoke(count)
                return actual.batch()
            }
        }
        val base = SqliteVmEntryRepository(fixture.base.db, gate, RemoteTextTransactions(gate)) {
            gate.stopAndAwaitIdle(); tags.wipeKey()
        }
        val store: EntryRepository = object : EntryRepository by base, AutoCloseable {
            override val manualTags = tags
            override val localSearch = port
            override suspend fun prepare() { check(fixture.base.db.read("PRAGMA user_version").single().single() == 7L) }
            override suspend fun entries(): List<StoredEntry> {
                entryReads.incrementAndGet(); beforeEntries?.invoke()
                return base.entries().also { openedEntries.compareAndSet(null, it) }
            }
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry =
                base.saveDraft(expected, capturedAtMillis, captureTimeZone).also { events += "capture-commit" }
            override fun close() = base.close()
        }
        lateinit var vm: LocalRecordsViewModel
        fun start() { vm = LocalRecordsViewModel(Application(), { store }) }
        suspend fun ready() = withTimeout(10_000) { vm.state.first { it.ready && !it.opening } }
        suspend fun settled() = withTimeout(10_000) {
            vm.state.first { it.ready && it.localSearchEpoch != null && !it.localIndexBusy && it.localIndexError == null &&
                fixture.base.db.read("SELECT count(*) FROM entries").single().single() ==
                fixture.base.db.read("SELECT count(*) FROM local_search_documents").single().single() }
        }
        override fun close() {
            beforeBatch = null; beforeEntries = null; fixture.base.db.afterSql = null
            if (::vm.isInitialized) {
                vm.clearForHostTest(); assertTrue(base.closed.await(5, TimeUnit.SECONDS))
                base.closeFailure.get()?.let { throw it }
            } else base.close()
            fixture.close()
        }
    }
}
