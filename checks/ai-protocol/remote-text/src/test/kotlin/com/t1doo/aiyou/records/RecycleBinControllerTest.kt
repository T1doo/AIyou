package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.*
import java.time.Clock
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class RecycleBinControllerTest {
    @Test fun explicitCancelOldConfirmationAndDuplicateMoveOrRestoreCannotMutateAnotherSession(): Unit = Harness().use { h ->
        h.requestMove(); val cancelled = h.ui(); val old = cancelled.confirmation!!
        assertFalse(h.bin.read(h.id).deleted); h.controller.cancelConfirmation(cancelled.session, old.id)
        assertNull(h.state.get().recycleBin); h.controller.confirm(cancelled.session, old.id); assertTrue(h.queue.isEmpty())
        h.requestMove(); val active = h.ui(); h.controller.confirm(active.session, active.confirmation!!.id)
        h.controller.confirm(active.session, active.confirmation.id); assertEquals(1, h.queue.size); assertEquals(1, h.state.get().entries.size)
        h.runNext(); assertTrue(h.state.get().entries.isEmpty()); assertTrue(h.bin.read(h.id).deleted)
        h.open(); val browse = h.ui(); val record = browse.records.single()
        h.controller.select(browse.session, record.entry.id); h.controller.requestRestore(browse.session, record.entry.id, record.generation)
        val restore = h.ui().confirmation!!; h.controller.confirm(cancelled.session, old.id); assertTrue(h.queue.isEmpty())
        h.controller.cancelConfirmation(browse.session, old.id); assertEquals(restore.id, h.ui().confirmation!!.id)
        h.controller.confirm(browse.session, restore.id); h.controller.confirm(browse.session, restore.id); assertEquals(1, h.queue.size)
        h.runNext(); assertTrue(h.ui().records.isEmpty()); assertEquals(ProcessingPolicy.AI_NONE, h.state.get().entries.single().policy)
        assertEquals(2, h.f.db.read("SELECT id FROM local_jobs WHERE id LIKE 'RECYCLE:%'").size)
    }
    @Test fun acceptedDeleteNotifiesOutsideSourceGateBeforeQueueWhileReadAndStaleConfirmationDoNot():Unit=Harness().use{h->
        var accepted=0;h.beforeMutation={accepted++;assertTrue(h.queue.isEmpty());assertFalse(h.bin.read(h.id).deleted)}
        h.requestMove();assertEquals(0,accepted);val u=h.ui();val id=u.confirmation!!.id
        h.controller.confirm(u.session,id+1);assertEquals(0,accepted);assertTrue(h.queue.isEmpty())
        h.controller.confirm(u.session,id);assertEquals(1,accepted);assertEquals(1,h.queue.size)
        h.controller.confirm(u.session,id);assertEquals(1,accepted);h.runNext();assertTrue(h.bin.read(h.id).deleted)
    }
    @Test fun closingQueuedConfirmationBeforeTransactionCancelsItsOwnerWithoutTouchingNewTarget(): Unit = Harness().use { h ->
        h.requestMove(); val old = h.ui(); h.controller.confirm(old.session, old.confirmation!!.id)
        h.controller.close(old.session); h.controller.requestMove(h.id, 1)
        val fresh = h.ui().session; h.runNext(); assertEquals(fresh, h.ui().session); assertTrue(h.ui().loading)
        assertFalse(h.bin.read(h.id).deleted); h.runNext(); assertNotNull(h.ui().confirmation)
        assertTrue(h.f.db.read("SELECT id FROM local_jobs WHERE id LIKE 'RECYCLE:%'").isEmpty())
    }
    @Test fun delayedLoadBetweenReadAndStateCasCannotResurrectRetiredConfirmation(): Unit = Harness().use { h ->
        h.controller.requestMove(h.id, 1); val session = h.ui().session
        h.beforeCas = { old, next -> if (old.recycleBin?.loading == true && next.recycleBin?.confirmation != null) {
            h.beforeCas = null; h.controller.close(session); h.controller.open()
        } }
        h.runNext(); assertNotEquals(session, h.ui().session); assertTrue(h.ui().browsing); assertNull(h.ui().confirmation)
        h.runNext(); assertTrue(h.ui().records.isEmpty()); assertFalse(h.bin.read(h.id).deleted)
    }
    @Test fun retainedRestoreForAnotherSelectedRecordIsRejectedBeforeRecomposition(): Unit = Harness().use { h ->
        h.requestMove(); val move = h.ui(); h.controller.confirm(move.session, move.confirmation!!.id); h.runNext()
        OriginalExportSqlTest.insert(h.f.db, "second", "另一条合成记录")
        val second = h.bin.apply(RecycleBinSqlTest.mutation(h.bin.read("second"), "move-second"))
        h.open(); val session = h.ui().session
        h.controller.select(session, "second"); h.controller.select(session, h.id)
        h.controller.requestRestore(session, "second", second.generation)
        assertNull(h.ui().confirmation); assertTrue(h.queue.isEmpty()); assertTrue(h.bin.read("second").deleted)
        val original = h.ui().records.single { it.entry.id == h.id }
        h.controller.requestRestore(session, h.id, original.generation)
        assertEquals(h.id, h.ui().confirmation!!.record.entry.id)
    }
    @Test fun failedWriteAndLostReadbackReuseExactMutationUntilDurableResultWithoutFalseSuccess(): Unit = Harness().use { h ->
        h.requestMove(); val request = h.ui(); val id = request.confirmation!!.id
        h.failApply = true; h.controller.confirm(request.session, id); h.runNext()
        assertTrue(h.ui().confirmation!!.uncertain); assertNull(h.state.get().recycleNotice); assertFalse(h.bin.read(h.id).deleted)
        h.failApply = false; h.failReadback = true; h.controller.confirm(request.session, id); h.runNext()
        assertTrue(h.bin.read(h.id).deleted); assertTrue(h.ui().confirmation!!.uncertain); assertNull(h.state.get().recycleNotice)
        assertTrue(h.state.get().entries.isEmpty()); h.failReadback = false
        h.controller.confirm(request.session, id); h.runNext(); assertNull(h.state.get().recycleBin)
        assertNotNull(h.state.get().recycleNotice); assertEquals(3, h.applied.size); assertEquals(1, h.applied.distinct().size)
        assertEquals(1, h.f.db.read("SELECT id FROM local_jobs WHERE id LIKE 'RECYCLE:%'").size)
    }
    @Test fun staleVersionDispositionAndStorageReadFailureRemainDistinctFromEmptyTrash(): Unit = Harness().use { h ->
        h.requestMove(); val old = h.ui(); OriginalExportSqlTest.revise(h.f.db, h.id, "合成新版本", 2)
        h.controller.confirm(old.session, old.confirmation!!.id); h.runNext()
        assertTrue(h.ui().confirmation!!.conflict); assertFalse(h.bin.read(h.id).deleted); assertNull(h.state.get().recycleNotice)
        h.controller.close(old.session); h.failRead = true; h.open(); assertNotNull(h.ui().error); assertTrue(h.ui().records.isEmpty())
        h.failRead = false; h.controller.refresh(h.ui().session); h.runNext(); assertNull(h.ui().error)
        h.controller.close(h.ui().session); h.controller.requestMove(h.id, 2); h.runNext()
        val disposed = h.ui(); h.controller.confirm(disposed.session, disposed.confirmation!!.id); h.controller.dispose(); h.runNext()
        val frozen = h.state.get(); h.controller.confirm(disposed.session, disposed.confirmation.id); h.controller.open()
        assertSame(frozen, h.state.get()); assertFalse(h.bin.read(h.id).deleted)
        assertFalse(disposed.toString().contains(h.id)); assertFalse(disposed.confirmation.toString().contains("原文"))
    }
    @Test fun moveIsUnavailableDuringDirtyEditHistoryOrActiveAttachmentAndKeepsDraftInput(): Unit = Harness().use { h ->
        h.state.set(h.state.get().copy(originalEdit = OriginalEditUiState(9, h.id, 1, "合成原文", text = "未保存修改")))
        h.controller.requestMove(h.id, 1); assertTrue(h.queue.isEmpty()); assertNull(h.state.get().recycleBin)
        assertEquals("未保存修改", h.state.get().originalEdit!!.text)
        h.state.set(h.state.get().copy(originalEdit = null, originalHistory = OriginalHistoryUiState(4, h.id)))
        h.controller.requestMove(h.id, 1); assertTrue(h.queue.isEmpty())
        h.state.set(h.state.get().copy(originalHistory = null)); h.canMove = false
        h.controller.requestMove(h.id, 1); assertTrue(h.queue.isEmpty()); assertEquals("合成草稿", h.state.get().text)
    }
    @Test fun oldPickerUuidAfterSameBodyMoveRestoreCannotWriteAndExistingExportFileIsNeverDeleted(): Unit = Harness().use { h ->
        h.f.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?", h.id)
        h.state.set(h.state.get().copy(entries = listOf(h.bin.read(h.id).entry)))
        val exports = OriginalExportController({ h.state.get() }, { change -> h.state.set(change(h.state.get())) },
            { h.queue.addLast(it); true }, Clock.systemUTC())
        fun picker(): OriginalExportUiState {
            exports.open(); var ui = h.state.get().originalExport!!; exports.toggle(ui.session, h.id, 1); exports.prepare(ui.session); h.runNext()
            ui = h.state.get().originalExport!!; exports.acceptRisk(ui.session, ui.snapshot!!.id, ui.format, ui.epoch, true)
            exports.choose(ui.session, ui.snapshot.id, ui.format, ui.epoch); h.runNext(); return h.state.get().originalExport!!
        }
        try {
            val old = picker(); val moved = h.bin.apply(RecycleBinSqlTest.mutation(h.bin.read(h.id), "picker-move"))
            h.bin.apply(RecycleBinSqlTest.mutation(moved, "picker-restore")); h.state.set(h.state.get().copy(entries = listOf(h.bin.read(h.id).entry)))
            val late = Document(); exports.picked(old.session, old.requestKey!!, late); h.runNext()
            assertEquals(OriginalExportPhase.CHANGED, h.state.get().originalExport!!.phase); assertEquals(0, late.opens); assertEquals(0, late.deletions)
            exports.close(old.session); val fresh = picker(); val completed = Document()
            exports.picked(fresh.session, fresh.requestKey!!, completed); h.runNext(); assertEquals(OriginalExportPhase.DONE, h.state.get().originalExport!!.phase)
            val bytes = completed.bytes.toByteArray(); h.bin.apply(RecycleBinSqlTest.mutation(h.bin.read(h.id), "after-export"))
            assertArrayEquals(bytes, completed.bytes.toByteArray()); assertEquals(0, completed.deletions)
        } finally { exports.dispose() }
    }
    @Test(timeout = 10_000) fun actualVmWaitsForDurableMoveThenColdReopensAndExplicitlyRestoresWithoutChangingIndependentTasks(): Unit = ManualTaskSqlTest.Fixture().use { f -> runBlocking {
        RecycleBinSqlTest.install(f.db); f.store.create(ManualTaskSqlTest.create("independent", "合成独立任务"))
        val first = VmHarness(f); val oldPid = f.db.pid; val release = CountDownLatch(1)
        try {
            first.ready(); first.vm.recycleBin.requestMove(ID, 1)
            val pending = withTimeout(2_000) { first.vm.state.first { it.recycleBin?.confirmation != null }.recycleBin!! }
            first.vm.beginOriginalEdit(ID, 1); first.vm.beginOriginalHistory(ID, 1); first.vm.tags.open(ID, 1)
            assertNull(first.vm.state.value.originalEdit); assertNull(first.vm.state.value.originalHistory); assertNull(first.vm.state.value.manualTagEditor)
            val entered = CountDownLatch(1); f.db.beforeSql = { if (it.startsWith("UPDATE entries")) { entered.countDown(); check(release.await(1, TimeUnit.SECONDS)) } }
            first.vm.recycleBin.confirm(pending.session, pending.confirmation!!.id); assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(1, first.vm.state.value.entries.size); assertNull(first.vm.state.value.recycleNotice)
            first.vm.changeText("回收站提交期间的合成立即输入"); assertEquals("回收站提交期间的合成立即输入", first.vm.state.value.text)
            release.countDown()
            withTimeout(2_000) { first.vm.state.first { it.recycleBin == null && it.entries.isEmpty() && it.recycleNotice != null && it.draftNotice == "草稿已保存在本机" } }
            assertEquals(ManualTaskStatus.OPEN, first.vm.state.value.tasks.items.single().status)
            first.close(); f.db.beforeSql = null; f.reopen(); assertNotEquals(oldPid, f.db.pid)
            VmHarness(f).use { second ->
                second.ready(); assertTrue(second.vm.state.value.entries.isEmpty()); assertEquals("回收站提交期间的合成立即输入", second.vm.state.value.text)
                second.vm.recycleBin.open(); val browse = withTimeout(2_000) { second.vm.state.first { it.recycleBin?.loading == false }.recycleBin!! }
                val record = browse.records.single(); assertEquals("合成原文", record.entry.originalText); assertEquals(listOf("合成旧标签"), record.tags.names)
                second.vm.recycleBin.select(browse.session, ID)
                second.vm.recycleBin.requestRestore(browse.session, ID, record.generation)
                second.vm.recycleBin.confirm(browse.session, second.vm.state.value.recycleBin!!.confirmation!!.id)
                withTimeout(2_000) { second.vm.state.first { it.entries.size == 1 && it.recycleBin?.confirmation == null } }
                assertEquals(ProcessingPolicy.AI_NONE, second.vm.state.value.entries.single().policy)
                assertEquals(ManualTaskStatus.OPEN, second.vm.state.value.tasks.items.single().status)
                assertEquals(listOf("合成旧标签"), second.vm.state.value.manualTags.getValue(ID).names)
            }
        } finally { release.countDown(); f.db.beforeSql = null; first.close() }
    } }
    @Test(timeout = 8_000) fun actualEncryptedEditDraftStaysDiscoverableAfterSameOwnerMoveAndRestoreWithoutRewritingItsFile(): Unit = ManualTaskSqlTest.Fixture().use { f -> runBlocking {
        RecycleBinSqlTest.install(f.db)
        VmHarness(f).use { h ->
            h.ready(); h.vm.beginOriginalEdit(ID, 1)
            val edit = withTimeout(2_000) { h.vm.state.first { it.originalEdit?.loadingDraft == false }.originalEdit!! }
            h.vm.changeOriginalEdit(edit.sessionId, "合成保留的未提交修改🙂")
            withTimeout(2_000) { h.vm.state.first { it.originalEdit?.draftNotice == "编辑草稿已加密保存在本机" } }
            h.vm.keepOriginalEdit(edit.sessionId)
            withTimeout(2_000) { h.vm.state.first { it.originalEdit == null && it.pendingEdits.any { ref -> ref.entryId == ID } } }
            val ref = h.vm.state.value.pendingEdits.single(); val files = h.editFiles()
            assertEquals(1, files.size)
            h.vm.recycleBin.requestMove(ID, 1)
            val move = withTimeout(2_000) { h.vm.state.first { it.recycleBin?.confirmation != null }.recycleBin!! }
            h.vm.recycleBin.confirm(move.session, move.confirmation!!.id)
            withTimeout(2_000) { h.vm.state.first { it.recycleBin == null && it.entries.isEmpty() && it.recycleNotice != null } }
            h.vm.resumeOriginalEdit(ID, ref.id); assertNull(h.vm.state.value.originalEdit)
            assertEquals(files, h.editFiles()); h.vm.recycleBin.open()
            val browse = withTimeout(2_000) { h.vm.state.first { it.recycleBin?.loading == false }.recycleBin!! }
            h.vm.recycleBin.select(browse.session, ID); h.vm.recycleBin.requestRestore(browse.session, ID, browse.records.single().generation)
            h.vm.recycleBin.confirm(browse.session, h.vm.state.value.recycleBin!!.confirmation!!.id)
            withTimeout(2_000) { h.vm.state.first { it.entries.size == 1 && it.recycleBin?.confirmation == null } }
            assertEquals(ref, h.vm.state.value.pendingEdits.single()); assertEquals(files, h.editFiles())
            h.vm.recycleBin.close(browse.session); h.vm.resumeOriginalEdit(ID, ref.id)
            val resumed = withTimeout(2_000) { h.vm.state.first { it.originalEdit?.loadingDraft == false }.originalEdit!! }
            assertEquals("合成保留的未提交修改🙂", resumed.text); assertEquals("合成原文", h.vm.state.value.entries.single().originalText)
        }
    } }
    private class VmHarness(f: ManualTaskSqlTest.Fixture) : Closeable {
        private val gateway = DrainingRecordsSqlDatabase(f.db)
        private val tags = SqlManualTagRepository(gateway, ManualTaskSqlTest.KEY)
        private val tasks = SqlManualTaskRepository(gateway, ManualTaskSqlTest.KEY)
        private val editRoot = f.directory.resolve("original-edits").toFile()
        private val edits = EncryptedOriginalEditStore(editRoot, ManualTaskSqlTest.KEY.copyOf())
        private val base = SqliteVmEntryRepository(f.db, gateway, RemoteTextTransactions(gateway)) { gateway.stopAndAwaitIdle(); tags.wipeKey(); tasks.wipeKey(); edits.close() }
        private val store = object : EntryRepository by base, AutoCloseable {
            override val recycleBin = SqlRecycleBinRepository(gateway, tags)
            override val manualTags = tags
            override val manualTasks = tasks
            override val editDrafts = edits
            override suspend fun prepare() { check(f.db.read("PRAGMA user_version").single().single() == 6L) }
            override fun close() = base.close()
        }
        val vm = LocalRecordsViewModel(Application(), { store })
        fun editFiles() = editRoot.listFiles().orEmpty().filter { it.name.endsWith(".edit") }.associate { it.name to it.readBytes().toList() }
        suspend fun ready() { withTimeout(2_000) { vm.state.first { it.ready && !it.opening && it.tasks.available } } }
        private var closed = false
        override fun close() { if (!closed) { closed = true; vm.clearForHostTest() }; assertTrue(base.closed.await(2, TimeUnit.SECONDS)); base.closeFailure.get()?.let { throw it } }
    }
    private class Harness : Closeable {
        val f = ManualTaskSqlTest.Fixture(); val id = ID
        val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        val bin = SqlRecycleBinRepository(f.db, tags)
        var failApply = false; var failReadback = false; var failRead = false; var canMove = true
        val applied = mutableListOf<String>(); private var appliedOnce = false
        private val wrapper = object : RecycleBinRepository by bin {
            override fun records(): List<RecycleRecord> { if (failRead) throw RecycleStorageFailure(); return bin.records() }
            override fun apply(mutation: RecycleMutation): RecycleRecord {
                applied.add(mutation.mutationId); if (failApply) throw RecycleStorageFailure()
                return bin.apply(mutation).also { appliedOnce = true }
            }
        }
        private val base = SqliteVmEntryRepository(f.db, f.db, RemoteTextTransactions(f.db)) {}
        private val store = object : EntryRepository by base {
            override val recycleBin = wrapper
            override val manualTags = tags
            override val originalExport = SqlOriginalExportRepository(f.db, tags)
            override suspend fun entries(): List<StoredEntry> { if (appliedOnce && failReadback) { failReadback = false; throw RecycleStorageFailure() }; return base.entries() }
        }
        val state = AtomicReference(LocalRecordUiState(ready = true, opening = false, supportsRecycleBin = true,
            supportsOriginalExport = true, entries = listOf(bin.read(id).entry), text = "合成草稿"))
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        val sourceGate=CaptureDraftGate();var beforeMutation:()->Unit={}
        var beforeCas: ((LocalRecordUiState, LocalRecordUiState) -> Unit)? = null
        val controller = RecycleBinController({ state.get() }, { change ->
            do { val old = state.get(); val next = change(old); beforeCas?.invoke(old, next) } while (!state.compareAndSet(old, next))
        }, { queue.addLast(it); true }, canMove = { canMove },sourceUi={sourceGate.sourceUi(it)},beforeMutation={assertFalse(Thread.holdsLock(sourceGate));beforeMutation()})
        init { RecycleBinSqlTest.install(f.db) }
        fun ui() = state.get().recycleBin!!
        fun requestMove() { controller.requestMove(id, 1); runNext() }
        fun open() { controller.open(); runNext() }
        fun runNext() = runBlocking { queue.removeFirst()(store) }
        override fun close() { beforeCas = null; controller.dispose(); tags.wipeKey(); f.close() }
    }
    private class Document : SelectedExportDocument {
        val bytes = ByteArrayOutputStream(); var opens = 0; var deletions = 0
        override fun openForWrite(): OutputStream { opens++; return bytes }
        override fun openForRead(): InputStream = ByteArrayInputStream(bytes.toByteArray())
        override fun delete(): Boolean { deletions++; return true }
    }
    companion object { private const val ID = ManualTaskSqlTest.LEGACY_ID }
}
