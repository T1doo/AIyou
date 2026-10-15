package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual VM/controller/queue + product SQL/AEAD. Native Android/SQLCipher is outside this host proof. */
class ManualTaskViewModelTest {
    @Test(timeout = 6_000) fun failedCreateRetainsInputAndStableIdentityUntilActualCommitReadback(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val editor = h.begin(); h.failWrite = true
        h.vm.tasks.create(editor.session, "合成明确待办🙂")
        val failed = h.tasks { !it.busy && it.error != null }
        assertEquals("合成明确待办🙂", failed.editor!!.input); assertNull(failed.notice)
        assertTrue(h.f.store.snapshots().isEmpty())
        h.vm.tasks.edit(editor.session, "不得换未确认请求"); assertEquals("合成明确待办🙂", h.vm.state.value.tasks.editor!!.input)
        h.failWrite = false; h.vm.tasks.retry(); val saved = h.tasks { it.notice != null && !it.busy }
        assertNull(saved.editor); assertEquals(1, saved.items.size); assertEquals(1, h.identities.distinct().size)
        assertEquals(2, h.identities.size); assertEquals("合成草稿", h.vm.state.value.text)
        assertEquals(1, h.vm.state.value.entries.single().currentRevision)
    } }
    @Test(timeout = 6_000) fun lostCreateAckSurvivesGlobalRecordRecoveryAndCapabilityReinitializationWithoutDuplicate(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val editor = h.begin(); h.failRead = true
        h.vm.tasks.create(editor.session, "合成已提交待确认"); h.tasks { it.error != null && !it.busy }
        assertEquals(1, h.f.store.snapshots().size)
        h.failCapture = true; h.vm.changeText("合成独立失败草稿")
        withTimeout(2_000) { h.vm.state.first { it.error != null } }
        h.failCapture = false; h.vm.retry()
        withTimeout(2_000) { h.vm.state.first { it.error == null } }
        // Same owner capability initialization is used by actual open(); unresolved identity must survive it.
        h.vm.tasks.initialize(h.store); h.tasks { it.error != null && !it.busy }
        assertEquals("合成已提交待确认", h.vm.state.value.tasks.editor!!.input)
        assertEquals(1, h.identities.distinct().size); assertEquals(1, h.f.store.snapshots().size)
        h.failRead = false; h.vm.tasks.initialize(h.store)
        val saved = h.tasks { it.notice != null && !it.busy }
        assertNull(saved.editor); assertNull(saved.pending); assertFalse(saved.loading)
        assertEquals(1, saved.items.size); assertEquals(1L, saved.items.single().version)
        assertEquals(1, h.identities.distinct().size); assertEquals(3, h.identities.size)
        assertEquals("合成独立失败草稿", h.vm.state.value.text)
    } }
    @Test(timeout = 6_000) fun conflictingStatusNeedsExplicitReloadAndCannotCancelSameTitleOtherTask(): Unit = Harness().use { h -> runBlocking {
        h.ready(); h.f.store.create(ManualTaskSqlTest.create("one", "同名合成待办")); h.f.store.create(ManualTaskSqlTest.create("two", "同名合成待办"))
        h.vm.tasks.refresh(); h.tasks { it.available && it.items.size == 2 }
        h.f.store.change(ManualTaskSqlTest.change("one", 1, ManualTaskStatus.CANCELLED))
        h.vm.tasks.change("one", 1, ManualTaskStatus.COMPLETED); h.tasks { it.conflict && !it.busy }
        assertEquals(ManualTaskStatus.CANCELLED, h.f.store.snapshots().single { it.id == "one" }.status)
        h.vm.tasks.retry(); val read = h.tasks { !it.conflict && it.available }
        assertEquals(ManualTaskStatus.OPEN, read.items.single { it.id == "two" }.status)
        val count = h.identities.size; h.vm.tasks.change("one", 1, ManualTaskStatus.COMPLETED)
        assertEquals(count, h.identities.size); assertNull(h.vm.state.value.tasks.pending)
        h.vm.tasks.change("two", 1, ManualTaskStatus.COMPLETED); h.tasks { it.notice != null && !it.busy }
        assertEquals(ManualTaskStatus.COMPLETED, h.f.store.snapshots().single { it.id == "two" }.status)
        assertEquals(2L, h.f.store.snapshots().single { it.id == "one" }.version)
    } }
    @Test(timeout = 6_000) fun readFailureIsSeparateFromEmptyAndClosedEditorNeverResurrectsOnStableRetry(): Unit = Harness(true).use { h -> runBlocking {
        h.ready(); val unread = h.tasks { it.error != null && !it.loading }
        assertFalse(unread.available); assertTrue(unread.items.isEmpty()); assertNull(h.vm.state.value.error)
        h.failRead = false; h.vm.tasks.retry(); h.tasks { it.available }
        val editor = h.begin(); h.failRead = true
        h.vm.tasks.create(editor.session, "合成关闭后确认"); h.tasks { it.error != null && !it.busy }
        h.vm.tasks.close(editor.session); assertNull(h.vm.state.value.tasks.editor)
        h.vm.tasks.begin(); assertNull(h.vm.state.value.tasks.editor) // unknown save identity must be confirmed first.
        h.failRead = false; h.vm.tasks.retry(); val saved = h.tasks { it.notice != null && !it.busy }
        assertNull(saved.editor); assertEquals(1, saved.items.size)
        val next = h.begin(); h.vm.tasks.edit(next.session, "新会话输入")
        h.vm.tasks.close(editor.session); h.vm.tasks.create(editor.session, "迟到创建"); h.vm.tasks.edit(editor.session, "迟到输入")
        assertEquals(next.session, h.vm.state.value.tasks.editor!!.session)
        assertEquals("新会话输入", h.vm.state.value.tasks.editor!!.input); assertEquals(1, h.f.store.snapshots().size)
    } }
    @Test(timeout = 6_000) fun actualWriteWaitsForAckCaptureStaysImmediateAndDisposedCallbacksCannotPublish(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val editor = h.begin()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.holdWrite = { entered.countDown(); check(release.await(1, TimeUnit.SECONDS)) }
        h.vm.tasks.create(editor.session, "合成真实等待"); assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertTrue(h.vm.state.value.tasks.busy); assertNull(h.vm.state.value.tasks.notice)
        h.vm.tasks.close(editor.session); assertNotNull(h.vm.state.value.tasks.editor)
        h.vm.changeText("并行本机即时输入"); assertEquals("并行本机即时输入", h.vm.state.value.text)
        release.countDown(); h.tasks { it.notice != null && !it.busy }; h.holdWrite = null
        withTimeout(2_000) { h.vm.state.first { it.draftNotice == "草稿已保存在本机" } }
        assertEquals("并行本机即时输入", h.f.db.read("SELECT text FROM capture_draft").single().single())
        val next = h.begin(); h.vm.tasks.edit(next.session, "停止后的私密输入")
        h.dispose(); val frozen = h.vm.state.value
        h.vm.tasks.create(next.session, "迟到写入"); h.vm.tasks.close(next.session); h.vm.tasks.edit(next.session, "迟到输入")
        h.vm.tasks.retry(); h.vm.tasks.refresh(); h.vm.tasks.change(frozen.tasks.items.single().id, 1, ManualTaskStatus.CANCELLED)
        assertSame(frozen, h.vm.state.value)
        assertThrows(ManualTaskStorageFailure::class.java) { h.f.store.snapshots() }
        h.f.reopen(); assertEquals(1, h.f.store.snapshots().size)
        assertEquals(ManualTaskStatus.OPEN, h.f.store.snapshots().single().status)
    } }
    @Test(timeout = 8_000) fun explicitCreationDateAndActualQueueDateChangesSurviveColdOwner(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val editor = h.begin()
        val input = ManualTaskDateInput("2042-11-19", "", "Asia/Shanghai")
        h.vm.tasks.editCreationDate(editor.session, input); h.vm.tasks.create(editor.session, "明天三点只是标题")
        val first = h.tasks { it.notice != null && !it.busy }.items.single()
        assertEquals(input.parse(), first.schedule); assertNull(first.schedule!!.time)
        h.vm.tasks.beginDate(first.id, 1, ManualTaskDateOperation.SET)
        val due = checkNotNull(h.vm.state.value.tasks.dateEditor)
        h.vm.tasks.saveDate(due.session, ManualTaskDateInput("2042-11-20", "09:30", "Europe/Paris"))
        val saved = h.tasks { !it.busy && it.dateEditor == null && it.items.singleOrNull()?.version == 2L }.items.single()
        assertEquals("Europe/Paris", saved.schedule!!.timeZone); assertEquals(first.title, saved.title)
        h.dispose(); h.f.reopen(); assertEquals(saved, h.f.store.snapshots().single())
    } }
    @Test(timeout = 8_000) fun lostDateAckKeepsOneTupleAfterEditorCloseAndOwnerReinitialization(): Unit = Harness().use { h -> runBlocking {
        h.ready(); h.f.store.create(ManualTaskSqlTest.create("one")); h.vm.tasks.refresh(); h.tasks { it.available && it.items.size == 1 }
        h.vm.tasks.beginDate("one", 1, ManualTaskDateOperation.SET); val editor = checkNotNull(h.vm.state.value.tasks.dateEditor)
        val input = ManualTaskDateInput("2042-11-19", "", "Asia/Shanghai"); h.failRead = true
        h.vm.tasks.saveDate(editor.session, input); h.tasks { !it.busy && it.error != null }
        val request = h.dateRequests.single(); val bytes = h.f.blob("one")
        assertEquals(2L, h.f.store.snapshots().single().version); assertNull(h.vm.state.value.tasks.notice)
        h.vm.tasks.closeDate(editor.session); assertNull(h.vm.state.value.tasks.dateEditor)
        h.vm.tasks.editDate(editor.session, ManualTaskDateInput("2043-01-01")); h.vm.tasks.saveDate(editor.session, input)
        h.vm.tasks.initialize(h.store); h.tasks { !it.busy && it.error != null }
        h.failRead = false; h.vm.tasks.initialize(h.store)
        val saved = h.tasks { !it.busy && it.notice != null }
        assertNull(saved.dateEditor); assertNull(saved.pending); assertFalse(saved.loading)
        assertEquals(3, h.dateRequests.size); assertTrue(h.dateRequests.all { it === request })
        assertEquals(2L, saved.items.single().version); assertArrayEquals(bytes, h.f.blob("one"))
    } }
    @Test(timeout = 8_000) fun closedDateSessionAndTerminalConflictCannotRetargetSameTitleTask(): Unit = Harness().use { h -> runBlocking {
        h.ready(); for (id in listOf("one", "two")) h.f.store.create(ManualTaskSqlTest.create(id, "同名合成待办"))
        h.vm.tasks.refresh(); h.tasks { it.available && it.items.size == 2 }
        h.vm.tasks.beginDate("one", 1, ManualTaskDateOperation.SET); val old = checkNotNull(h.vm.state.value.tasks.dateEditor)
        h.vm.tasks.closeDate(old.session); h.vm.tasks.beginDate("two", 1, ManualTaskDateOperation.SET)
        val current = checkNotNull(h.vm.state.value.tasks.dateEditor); val input = ManualTaskDateInput("2042-11-19")
        h.vm.tasks.editDate(old.session, input); h.vm.tasks.saveDate(old.session, input); h.vm.tasks.closeDate(old.session)
        assertSame(current, h.vm.state.value.tasks.dateEditor); assertTrue(h.dateRequests.isEmpty())
        h.f.store.change(ManualTaskSqlTest.change("two", 1, ManualTaskStatus.CANCELLED))
        h.vm.tasks.saveDate(current.session, input); h.tasks { it.conflict && !it.busy }
        h.vm.tasks.retry(); h.tasks { it.available && !it.conflict }
        val count = h.dateRequests.size
        h.vm.tasks.saveDate(current.session, input); assertEquals(count, h.dateRequests.size)
        h.vm.tasks.closeDate(current.session); h.vm.tasks.beginDate("two", 2, ManualTaskDateOperation.SET)
        assertNull(h.vm.state.value.tasks.dateEditor)
        assertNull(h.f.store.snapshots().single { it.id == "one" }.schedule)
        assertEquals(ManualTaskStatus.CANCELLED, h.f.store.snapshots().single { it.id == "two" }.status)
    } }
    @Test(timeout = 8_000) fun invalidDateGapOverlapAndNonLaterPostponeStayUncommitted(): Unit = Harness().use { h -> runBlocking {
        h.ready(); val due = checkNotNull(ManualTaskDateInput("2026-10-15").parse())
        h.f.store.create(ManualTaskCreate("one", "合成日期", 55, "UTC", "a", due))
        h.vm.tasks.refresh(); h.tasks { it.available && it.items.size == 1 }
        h.vm.tasks.beginDate("one", 1, ManualTaskDateOperation.POSTPONE); val old = checkNotNull(h.vm.state.value.tasks.dateEditor)
        h.vm.tasks.saveDate(old.session, ManualTaskDateInput("2026-10-15"))
        assertNotNull(h.vm.state.value.tasks.error); assertTrue(h.dateRequests.isEmpty())
        h.vm.tasks.closeDate(old.session); h.vm.tasks.beginDate("one", 1, ManualTaskDateOperation.SET)
        val editor = checkNotNull(h.vm.state.value.tasks.dateEditor)
        for (input in listOf(ManualTaskDateInput("", "01:30"), ManualTaskDateInput("2026-03-08", "02:30", "America/New_York"),
            ManualTaskDateInput("2026-11-01", "01:30", "America/New_York"))) {
            h.vm.tasks.saveDate(editor.session, input); assertNotNull(h.vm.state.value.tasks.error)
            assertNull(h.vm.state.value.tasks.pending); assertTrue(h.dateRequests.isEmpty())
        }
        h.vm.tasks.saveDate(editor.session, ManualTaskDateInput("2026-11-01", "01:30", "America/New_York", ManualTaskOccurrence.SECOND))
        val saved = h.tasks { it.notice != null && !it.busy }
        assertEquals(-18_000, saved.items.single().schedule!!.offsetSeconds)
    } }
    @Test(timeout = 8_000) fun retiredDateControllerDropsPostCommitPublicationAndAllLateActions(): Unit = Harness().use { h -> runBlocking {
        h.ready(); h.f.store.create(ManualTaskSqlTest.create("one")); h.vm.tasks.refresh(); h.tasks { it.available && it.items.size == 1 }
        h.vm.tasks.beginDate("one", 1, ManualTaskDateOperation.SET); val editor = checkNotNull(h.vm.state.value.tasks.dateEditor)
        val input = ManualTaskDateInput("2042-11-19"); val committed = CountDownLatch(1)
        var frozen: LocalRecordUiState? = null
        h.afterSchedule = { h.vm.tasks.dispose(); frozen = h.vm.state.value; committed.countDown() }
        h.vm.tasks.saveDate(editor.session, input); assertTrue(committed.await(2, TimeUnit.SECONDS))
        h.vm.tasks.editDate(editor.session, input); h.vm.tasks.saveDate(editor.session, input); h.vm.tasks.closeDate(editor.session)
        h.vm.tasks.beginDate("one", 2, ManualTaskDateOperation.CLEAR); h.vm.tasks.retry(); h.vm.tasks.refresh()
        assertSame(frozen, h.vm.state.value); assertNull(h.vm.state.value.tasks.notice)
        assertEquals(2L, h.f.store.snapshots().single().version); assertEquals(input.parse(), h.f.store.snapshots().single().schedule)
    } }
    @Test fun disposingBeforeQueuedDateCommandMakesNoDurableChange(): Unit = ManualTaskSqlTest.Fixture().use { f ->
        val one = f.store.create(ManualTaskSqlTest.create("one")); var state = LocalRecordUiState(ready = true, opening = false)
        val queued = ArrayDeque<suspend (EntryRepository) -> Unit>()
        val port = object : EntryRepository {
            override val manualTasks = f.store
            override suspend fun readDraft(): TextDraft? = null
            override suspend fun updateDraft(text: String): TextDraft = error("Outside date test")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Outside date test")
            override suspend fun entries() = emptyList<StoredEntry>()
            override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Outside date test")
        }
        val controller = ManualTaskController({ state }, { state = it(state) }, { queued.add(it); true }, java.time.Clock.systemUTC())
        runBlocking { controller.initialize(port) }; controller.beginDate(one.id, 1, ManualTaskDateOperation.SET)
        val editor = checkNotNull(state.tasks.dateEditor); controller.saveDate(editor.session, ManualTaskDateInput("2042-11-19"))
        assertTrue(state.tasks.busy); controller.dispose(); val frozen = state
        runBlocking { while (queued.isNotEmpty()) queued.removeFirst()(port) }
        assertSame(frozen, state); assertEquals(one, f.store.snapshots().single())
    }
    private class Harness(readFailure: Boolean = false) : Closeable {
        val f = ManualTaskSqlTest.Fixture()
        private val sql = DrainingRecordsSqlDatabase(f.db)
        private val actual = SqlManualTaskRepository(sql, ManualTaskSqlTest.KEY)
        @Volatile var failWrite = false
        @Volatile var failRead = readFailure
        @Volatile var failCapture = false
        @Volatile var holdWrite: (() -> Unit)? = null
        val identities = CopyOnWriteArrayList<Pair<String, String>>()
        val dateRequests = CopyOnWriteArrayList<ManualTaskScheduleMutation>()
        @Volatile var afterSchedule: (() -> Unit)? = null
        private val port = object : ManualTaskRepository by actual {
            override fun create(request: ManualTaskCreate): ManualTaskSnapshot {
                identities.add(request.id to request.mutationId); holdWrite?.invoke()
                if (failWrite) throw ManualTaskStorageFailure()
                return actual.create(request)
            }
            override fun change(request: ManualTaskMutation): ManualTaskSnapshot {
                identities.add(request.id to request.mutationId)
                if (failWrite) throw ManualTaskStorageFailure()
                return actual.change(request)
            }
            override fun reschedule(request: ManualTaskScheduleMutation): ManualTaskSnapshot {
                identities.add(request.id to request.mutationId); dateRequests.add(request)
                if (failWrite) throw ManualTaskStorageFailure()
                return actual.reschedule(request).also { afterSchedule?.invoke() }
            }
            override fun snapshots(): List<ManualTaskSnapshot> {
                if (failRead) throw ManualTaskStorageFailure()
                return actual.snapshots()
            }
        }
        private val base = SqliteVmEntryRepository(f.db, sql, RemoteTextTransactions(sql)) { sql.stopAndAwaitIdle(); actual.wipeKey() }
        val store: EntryRepository = object : EntryRepository by base, AutoCloseable {
            override val manualTasks = port
            override suspend fun prepare() { check(f.db.read("PRAGMA user_version").single().single() == 6L) }
            override suspend fun updateDraft(text: String): TextDraft {
                if (failCapture) throw IllegalStateException("Synthetic capture failure")
                return base.updateDraft(text)
            }
            override fun close() = base.close()
        }
        val vm = LocalRecordsViewModel(Application(), { store })
        suspend fun ready() = withTimeout(2_000) { vm.state.first { it.ready && !it.opening && it.tasks.supported &&
            !it.tasks.loading && (it.tasks.available || it.tasks.error != null) } }
        fun begin(): ManualTaskEditor { vm.tasks.begin(); return checkNotNull(vm.state.value.tasks.editor) }
        suspend fun tasks(predicate: (ManualTasksUiState) -> Boolean) = withTimeout(2_000) { vm.state.first { predicate(it.tasks) }.tasks }
        private var disposed = false
        fun dispose() { if (!disposed) { disposed = true; vm.clearForHostTest() }; assertTrue(base.closed.await(2, TimeUnit.SECONDS)); base.closeFailure.get()?.let { throw it } }
        override fun close() { dispose(); f.close() }
    }
}
