package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual VM queue/controller, actual product AEAD/SQL; Android/Room are outside this host proof. */
class ManualTagViewModelTest {
    @Test(timeout = 6_000) fun failedWriteKeepsActualInputAndRetryUsesSameMutationUntilDurableReadback(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val e = h.open()
        h.failWrite = true; h.vm.tags.add(e.session, 0, "合成输入🙂")
        val failed = h.editor { it.error != null && !it.busy }
        assertEquals("合成输入🙂", failed.input); assertNull(failed.notice); assertEquals(0L, h.f.store.read(ID).version)
        h.failWrite = false; h.vm.tags.retry(e.session)
        val saved = h.editor { it.notice != null && !it.busy }
        assertEquals("", saved.input); assertEquals(listOf("合成输入🙂"), h.f.store.read(ID).names)
        assertEquals(1, h.mutations.distinct().size); assertEquals(2, h.mutations.size)
        assertEquals(1, h.vm.state.value.entries.single().currentRevision)
        assertEquals("合成草稿", h.vm.state.value.text)
    } }
    @Test(timeout = 6_000) fun conflictPreservesInputAndExplicitReloadCombinesWithCurrentTagsWithoutBodyRevision(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val e = h.open()
        h.f.store.replace(ManualTagMutation(ID, 0, listOf("另一操作"), "concurrent"))
        h.vm.tags.add(e.session, 0, "我的输入")
        val conflict = h.editor { it.conflict && !it.busy }; assertEquals("我的输入", conflict.input)
        h.vm.tags.retry(e.session); val refreshed = h.editor { !it.conflict && it.snapshot?.version == 1L }
        assertEquals("我的输入", refreshed.input); assertEquals(listOf("另一操作"), refreshed.snapshot!!.names)
        h.vm.tags.add(e.session, 1, refreshed.input); h.editor { it.notice != null }
        assertEquals(listOf("另一操作", "我的输入"), h.f.store.read(ID).names)
        assertEquals(1, h.vm.state.value.entries.single().currentRevision)
    } }
    @Test(timeout = 6_000) fun lostReadbackAcknowledgementRetainsInputAndCloseStillRefreshesConfirmedFilterSnapshot(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val e = h.open()
        h.failReadback = true; h.vm.tags.add(e.session, 0, "已提交待确认")
        val failed = h.editor { it.error != null && !it.busy }
        assertEquals("已提交待确认", failed.input); assertNull(failed.notice)
        assertEquals(1L, h.f.store.read(ID).version)
        h.failReadback = false; h.vm.tags.retry(e.session); h.editor { it.notice != null }
        assertEquals(1L, h.f.store.read(ID).version)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.holdReadback = { entered.countDown(); check(release.await(1, TimeUnit.SECONDS)) }
        h.vm.tags.remove(e.session, 1, "已提交待确认"); assertTrue(entered.await(1, TimeUnit.SECONDS))
        h.vm.tags.close(e.session); assertNull(h.vm.state.value.manualTagEditor); release.countDown()
        withTimeout(2_000) { h.vm.state.first { it.manualTags[ID]?.version == 2L } }
        assertTrue(h.vm.state.value.manualTags.getValue(ID).names.isEmpty())
        assertNull(h.vm.state.value.manualTagEditor); assertTrue(h.f.store.read(ID).names.isEmpty())
    } }
    @Test(timeout = 6_000) fun staleVersionAndSessionCallbacksCannotOverwriteNewInputWhileCaptureEditsStayImmediate(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val old = h.open()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.holdWrite = { entered.countDown(); check(release.await(1, TimeUnit.SECONDS)) }
        h.vm.tags.add(old.session, 0, "旧标签"); assertTrue(entered.await(1, TimeUnit.SECONDS))
        h.vm.changeText("并行本机输入"); assertEquals("并行本机输入", h.vm.state.value.text)
        release.countDown(); h.editor { it.notice != null }; h.holdWrite = null
        h.vm.tags.edit(old.session, 1, "新标签输入")
        h.vm.tags.edit(old.session, 0, "迟到输入"); h.vm.tags.remove(old.session, 0, "旧标签")
        assertEquals("新标签输入", h.vm.state.value.manualTagEditor!!.input)
        h.vm.tags.close(old.session); val new = h.open()
        h.vm.tags.edit(new.session, 1, "新会话")
        h.vm.tags.add(old.session, 1, "迟到旧操作"); h.vm.tags.close(old.session)
        assertEquals("新会话", h.vm.state.value.manualTagEditor!!.input)
        assertEquals(new.session, h.vm.state.value.manualTagEditor!!.session)
        withTimeout(2_000) { h.vm.state.first { it.draftNotice == "草稿已保存在本机" } }
        assertEquals("并行本机输入", h.f.db.read("SELECT text FROM capture_draft").single().single())
        assertEquals(listOf("旧标签"), h.f.store.read(ID).names)
        h.dispose(); val frozen = h.vm.state.value
        h.vm.tags.edit(new.session, 1, "disposed input"); h.vm.tags.add(new.session, 1, "disposed tag")
        h.vm.tags.close(new.session); h.vm.tags.retry(new.session)
        assertSame(frozen, h.vm.state.value)
    } }
    private class Harness : Closeable {
        val f = ManualTagSqlTest.Fixture()
        val sql = DrainingRecordsSqlDatabase(f.db)
        private val actual = SqlManualTagRepository(sql, ManualTagSqlTest.KEY)
        @Volatile var failWrite = false
        @Volatile var failReadback = false
        @Volatile var holdWrite: (() -> Unit)? = null
        @Volatile var holdReadback: (() -> Unit)? = null
        private var wrote = false
        val mutations = CopyOnWriteArrayList<String>()
        private val tags = object : ManualTagRepository by actual {
            override fun replace(mutation: ManualTagMutation): ManualTagSnapshot {
                mutations.add(mutation.mutationId); holdWrite?.invoke()
                if (failWrite) throw ManualTagStorageFailure()
                return actual.replace(mutation).also { wrote = true }
            }
            override fun snapshots(): Map<String, ManualTagSnapshot> {
                if (wrote) { holdReadback?.invoke(); if (failReadback) throw ManualTagStorageFailure() }
                return actual.snapshots()
            }
        }
        private val base = SqliteVmEntryRepository(f.db, sql, RemoteTextTransactions(sql)) { sql.stopAndAwaitIdle(); actual.wipeKey() }
        private val store = object : EntryRepository by base, AutoCloseable {
            override val manualTags = tags
            override suspend fun prepare() { check(f.db.read("PRAGMA user_version").single().single() == 5L) }
            override fun close() = base.close()
        }
        val vm = LocalRecordsViewModel(Application(), { store })
        suspend fun ready() = withTimeout(2_000) { vm.state.first { it.ready && !it.opening } }
        suspend fun open(): ManualTagEditor { vm.tags.open(ID, 1); return editor { !it.busy && it.snapshot != null } }
        suspend fun editor(predicate: (ManualTagEditor) -> Boolean): ManualTagEditor = withTimeout(2_000) {
            checkNotNull(vm.state.first { it.manualTagEditor?.let(predicate) == true }.manualTagEditor)
        }
        private var disposed = false
        fun dispose() { if (!disposed) { disposed = true; vm.clearForHostTest() }; assertTrue(base.closed.await(2, TimeUnit.SECONDS)); base.closeFailure.get()?.let { throw it } }
        override fun close() { dispose(); f.close() }
    }
    companion object { private const val ID = ManualTagSqlTest.ID }
}
