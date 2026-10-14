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

/** Product controller, SQL/tag encryption and IO. Host adapters do not prove native chooser behavior. */
class OriginalExportControllerTest {
    @Test fun selectionAndPlaintextConsentAreRequiredAndPickerCancelRequiresNewConsent(): Unit = Harness().use { h ->
        h.controller.open(); assertTrue(h.ui().selection.isEmpty()); h.controller.prepare(h.ui().session); assertTrue(h.queue.isEmpty())
        h.selectAndPreview(); val s = h.ui(); h.choose(); assertEquals(OriginalExportPhase.PREVIEW, h.ui().phase)
        h.accept(); h.choose(); assertEquals(OriginalExportPhase.CHECKING, h.ui().phase); h.runNext()
        val key = h.ui().requestKey!!; assertTrue(h.controller.claimPicker(s.session, key)); assertFalse(h.controller.claimPicker(s.session, key))
        h.controller.picked(s.session, key, null); assertEquals(OriginalExportPhase.PREVIEW, h.ui().phase)
        assertFalse(h.ui().riskAccepted); h.choose(); assertTrue(h.queue.isEmpty()); assertNull(h.ui().requestKey)
    }
    @Test fun sourceChangesBeforeSelectionAndAfterPickerCannotWriteOrSilentlyRefresh(): Unit = Harness().use { h ->
        h.selectAndPreview(); h.accept(); OriginalExportSqlTest.revise(h.f.db, h.id, "合成更新原文", 2)
        h.choose(); h.runNext(); assertEquals(OriginalExportPhase.CHANGED, h.ui().phase); assertNull(h.ui().requestKey)
        h.controller.prepare(h.ui().session, true); h.runNext(); assertEquals(2, h.ui().snapshot!!.records.single().entry.currentRevision)
        h.accept(); h.choose(); h.runNext(); val key = h.ui().requestKey!!
        h.tags.replace(ManualTagMutation(h.id, 1, listOf("合成修改标签"), "changed-after-picker"))
        val file = Document(); h.controller.picked(h.ui().session, key, file); h.runNext()
        assertEquals(OriginalExportPhase.CHANGED, h.ui().phase); assertEquals(0, file.opens); assertTrue(h.ui().hasFile)
        assertEquals(0, file.deletions); assertFalse(h.ui().riskAccepted)
    }
    @Test fun changesDuringWritingKeepExactConfirmedSnapshotAndReportLaterChanges(): Unit = Harness().use { h ->
        h.readyPicker(); val snapshot = h.ui().snapshot!!; val key = h.ui().requestKey!!
        val expected = OriginalExportCodec.render(snapshot, h.ui().format).let { p -> try { p.copyBytes() } finally { p.release() } }
        val file = Document(); file.onWrite = { file.onWrite = null; OriginalExportSqlTest.revise(h.f.db, h.id, "写入时变化的合成原文", 2) }
        h.controller.picked(h.ui().session, key, file); h.runNext()
        assertEquals(OriginalExportPhase.DONE, h.ui().phase); assertArrayEquals(expected, file.bytes())
        assertTrue(h.ui().notice!!.contains("之后")); assertTrue(file.outputClosed); assertTrue(file.inputClosed); assertEquals(0, file.deletions)
    }
    @Test fun cancelDuringWriteWaitsForStreamCloseAndLeavesPartialFileUntilSeparateConfirmation(): Unit = Harness().use { h ->
        h.readyPicker(); val session = h.ui().session; val key = h.ui().requestKey!!; val file = Document()
        file.onWrite = { h.controller.cancel(session); assertEquals(OriginalExportPhase.CANCELLING, h.ui().phase) }
        h.controller.picked(session, key, file); h.runNext()
        assertEquals(OriginalExportPhase.CANCELLED, h.ui().phase); assertTrue(file.outputClosed); assertTrue(file.bytes().isNotEmpty())
        assertEquals(0, file.deletions); assertEquals(0, file.reads); assertEquals(key, h.ui().requestKey)
        h.controller.deleteSelectedFile(session, key); assertEquals(0, file.deletions); h.runNext()
        assertEquals(1, file.deletions); assertFalse(h.ui().hasFile); assertFalse(h.ui().riskAccepted)
    }
    @Test fun closeReadbackAndPermissionFailuresNeverPublishSuccessAndNeverAutomaticallyDelete(): Unit = Harness().use { h ->
        for (mode in listOf("write", "outputClose", "readMismatch", "read", "inputClose")) {
            if (h.state.get().originalExport != null) h.controller.close(h.ui().session)
            h.readyPicker(); val file = Document(mode); val key = h.ui().requestKey!!
            h.controller.picked(h.ui().session, key, file); h.runNext()
            assertEquals(mode, OriginalExportPhase.FAILED, h.ui().phase); assertNull(h.ui().notice)
            assertEquals(0, file.deletions); assertTrue(h.ui().hasFile)
            assertFalse(h.ui().error!!.contains("sensitive-provider")); assertFalse(h.ui().toString().contains(h.id))
        }
    }
    @Test fun retainedDeletionConfirmationForAIsRejectedWhenSameSessionNowOwnsB(): Unit = Harness().use { h ->
        h.readyPicker(); val session = h.ui().session; val keyA = h.ui().requestKey!!; val a = Document("write")
        h.controller.picked(session, keyA, a); h.runNext(); a.deleteResult = false
        h.controller.deleteSelectedFile(session, keyA); h.runNext(); assertTrue(h.ui().hasFile); assertNotNull(h.ui().error)
        a.deleteResult = true; h.controller.deleteSelectedFile(session, keyA); h.runNext(); assertFalse(h.ui().hasFile)
        h.accept(); h.choose(); h.runNext(); val keyB = h.ui().requestKey!!; assertNotEquals(keyA, keyB)
        val b = Document("write"); h.controller.picked(session, keyB, b); h.runNext()
        h.controller.deleteSelectedFile(session, keyA); assertTrue(h.queue.isEmpty()); assertEquals(0, b.deletions)
        h.controller.deleteSelectedFile(session, keyB); h.runNext(); assertEquals(1, b.deletions)
    }
    @Test fun cancellingBetweenSuccessfulCaptureAndActualStateCasCannotResurrectPreview(): Unit = Harness().use { h ->
        h.controller.open(); val session = h.ui().session; h.controller.toggle(session, h.id, 1); h.controller.prepare(session)
        h.beforeCas = { old, next -> if (old.originalExport?.phase == OriginalExportPhase.PREPARING && next.originalExport?.phase == OriginalExportPhase.PREVIEW) {
            h.beforeCas = null; h.controller.cancel(session)
        } }
        h.runNext(); assertEquals(OriginalExportPhase.CANCELLED, h.ui().phase); assertNull(h.ui().snapshot)
        h.controller.prepare(session); h.runNext(); assertEquals(OriginalExportPhase.PREVIEW, h.ui().phase)
    }
    @Test fun cancelCloseOrDisposeBetweenPayloadAndPickerStateCasCannotPublishOrLeakOldOwner(): Unit = Harness().use { h ->
        for (action in listOf("cancel", "close", "dispose")) {
            if (h.state.get().originalExport != null) h.controller.close(h.ui().session)
            h.readyPreview(); h.accept(); val session = h.ui().session; h.choose()
            h.beforeCas = { old, next -> if (old.originalExport?.phase == OriginalExportPhase.CHECKING && next.originalExport?.phase == OriginalExportPhase.PICKING) {
                h.beforeCas = null
                when (action) { "cancel" -> h.controller.cancel(session); "close" -> h.controller.close(session); else -> h.controller.dispose() }
            } }
            h.runNext(); assertNotEquals(OriginalExportPhase.PICKING, h.state.get().originalExport?.phase)
            val field = h.controller.javaClass.getDeclaredField("work").also { it.isAccessible = true }
            assertNull((field.get(h.controller) as AtomicReference<*>).get())
        }
    }
    @Test fun oldConsentAfterFormatChangeAndOldDestinationAfterNewSessionCannotWrite(): Unit = Harness().use { h ->
        h.readyPreview(); val old = h.ui(); h.controller.format(old.session, OriginalExportFormat.JSONL)
        h.controller.acceptRisk(old.session, old.snapshot!!.id, old.format, old.epoch, true)
        h.controller.choose(old.session, old.snapshot.id, old.format, old.epoch); assertTrue(h.queue.isEmpty()); assertFalse(h.ui().riskAccepted)
        h.accept(); h.choose(); h.runNext(); val key = h.ui().requestKey!!; h.controller.close(old.session)
        h.readyPicker(); val fresh = h.ui(); val file = Document()
        h.controller.picked(old.session, key, file); assertTrue(h.queue.isEmpty()); assertEquals(0, file.opens)
        assertEquals(fresh.requestKey, h.ui().requestKey)
    }
    @Test fun boundedReadbackRejectsExtraBytesTruncationZeroReadsAndCancellationWithoutSensitiveErrors(): Unit = Harness().use { h ->
        h.readyPreview(); val payload = OriginalExportCodec.render(h.ui().snapshot!!, OriginalExportFormat.JSONL)
        try {
            for (mode in listOf("extra", "truncated", "zero")) {
                val doc = Document(mode)
                assertThrows(OriginalExportFileFailure::class.java) { OriginalExportIo.writeAndVerify(doc, payload) { true } }
                assertTrue(doc.inputClosed); assertEquals(0, doc.deletions)
            }
            val untouched = Document(); assertThrows(OriginalExportCancelled::class.java) { OriginalExportIo.writeAndVerify(untouched, payload) { false } }
            assertEquals(0, untouched.opens)
        } finally { payload.release() }
    }
    @Test(timeout = 6_000) fun actualVmQueueExportKeepsCaptureImmediateAndClosesSharedOwnerAfterIo(): Unit = ManualTaskSqlTest.Fixture().use { f -> runBlocking {
        val gateway = DrainingRecordsSqlDatabase(f.db); val tags = SqlManualTagRepository(gateway, ManualTaskSqlTest.KEY)
        val export = SqlOriginalExportRepository(gateway, tags)
        val base = SqliteVmEntryRepository(f.db, gateway, RemoteTextTransactions(gateway)) { gateway.stopAndAwaitIdle(); tags.wipeKey() }
        val store = object : EntryRepository by base, AutoCloseable {
            override val originalExport = export
            override suspend fun prepare() { check(f.db.read("PRAGMA user_version").single().single() == 6L) }
            override fun close() = base.close()
        }
        val vm = LocalRecordsViewModel(Application(), { store }); val release = CountDownLatch(1)
        try {
            withTimeout(2_000) { vm.state.first { it.ready && !it.opening && it.supportsOriginalExport } }
            vm.exports.open(); val session = vm.state.value.originalExport!!.session
            vm.exports.toggle(session, ManualTaskSqlTest.LEGACY_ID, 1); vm.exports.prepare(session)
            val preview = withTimeout(2_000) { vm.state.first { it.originalExport?.phase == OriginalExportPhase.PREVIEW }.originalExport!! }
            vm.exports.acceptRisk(session, preview.snapshot!!.id, preview.format, preview.epoch, true)
            vm.exports.choose(session, preview.snapshot.id, preview.format, preview.epoch)
            val picker = withTimeout(2_000) { vm.state.first { it.originalExport?.phase == OriginalExportPhase.PICKING }.originalExport!! }
            val entered = CountDownLatch(1); val file = Document()
            file.onWrite = { entered.countDown(); check(release.await(1, TimeUnit.SECONDS)) }
            vm.exports.picked(session, picker.requestKey!!, file); assertTrue(entered.await(1, TimeUnit.SECONDS))
            vm.changeText("导出期间的合成立即输入"); assertEquals("导出期间的合成立即输入", vm.state.value.text)
            assertEquals(OriginalExportPhase.WRITING, vm.state.value.originalExport!!.phase); assertNull(vm.state.value.originalExport!!.notice)
            release.countDown(); withTimeout(2_000) { vm.state.first { it.originalExport?.phase == OriginalExportPhase.DONE &&
                it.text == "导出期间的合成立即输入" && it.draftNotice == "草稿已保存在本机" } }
            assertEquals("导出期间的合成立即输入", f.db.read("SELECT text FROM capture_draft").single().single())
            vm.clearForHostTest(); assertTrue(base.closed.await(2, TimeUnit.SECONDS)); base.closeFailure.get()?.let { throw it }
            val frozen = vm.state.value; vm.exports.picked(session, picker.requestKey, Document()); assertSame(frozen, vm.state.value)
        } finally { release.countDown(); vm.clearForHostTest(); assertTrue(base.closed.await(2, TimeUnit.SECONDS)) }
    } }
    private class Harness : Closeable {
        val id = ManualTaskSqlTest.LEGACY_ID; val f = ManualTaskSqlTest.Fixture()
        val tags = SqlManualTagRepository(f.db, ManualTaskSqlTest.KEY)
        private val base = SqliteVmEntryRepository(f.db, f.db, RemoteTextTransactions(f.db)) {}
        private val store = object : EntryRepository by base { override val originalExport = SqlOriginalExportRepository(f.db, tags) }
        val state = AtomicReference(LocalRecordUiState(ready = true, opening = false, supportsOriginalExport = true,
            entries = listOf(StoredEntry(id, 1, "合成原文", 5, "UTC", ProcessingPolicy.LOCAL_ONLY))))
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        var beforeCas: ((LocalRecordUiState, LocalRecordUiState) -> Unit)? = null
        val controller = OriginalExportController({ state.get() }, { change ->
            do { val old = state.get(); val next = change(old); beforeCas?.invoke(old, next) }
            while (!state.compareAndSet(old, next))
        }, { queue.addLast(it); true }, Clock.systemUTC())
        fun ui() = state.get().originalExport!!
        fun runNext() { runBlocking { queue.removeFirst()(store) } }
        fun selectAndPreview() { controller.open(); controller.toggle(ui().session, id, 1); controller.prepare(ui().session); runNext() }
        fun readyPreview() = selectAndPreview()
        fun accept() { val u = ui(); controller.acceptRisk(u.session, u.snapshot!!.id, u.format, u.epoch, true) }
        fun choose() { val u = ui(); controller.choose(u.session, u.snapshot!!.id, u.format, u.epoch) }
        fun readyPicker() { readyPreview(); accept(); choose(); runNext() }
        override fun close() { beforeCas = null; controller.dispose(); tags.wipeKey(); f.close() }
    }
    private class Document(private val mode: String = "") : SelectedExportDocument {
        private var output = ByteArrayOutputStream()
        var opens = 0; var reads = 0; var deletions = 0; var outputClosed = false; var inputClosed = false
        var deleteResult = true; var onWrite: (() -> Unit)? = null
        fun bytes() = output.toByteArray()
        override fun openForWrite(): OutputStream {
            opens++; if (mode == "write") throw SecurityException("sensitive-provider-details")
            output = ByteArrayOutputStream()
            return object : OutputStream() {
                override fun write(value: Int) = output.write(value)
                override fun write(bytes: ByteArray, offset: Int, count: Int) { output.write(bytes, offset, count); onWrite?.invoke() }
                override fun close() { outputClosed = true; if (mode == "outputClose") throw IOException("sensitive-provider-details") }
            }
        }
        override fun openForRead(): InputStream {
            reads++; if (mode == "read") throw SecurityException("sensitive-provider-details")
            val actual = bytes().let { when (mode) { "extra" -> it + byteArrayOf(1); "truncated" -> it.copyOf(it.size - 1)
                "readMismatch" -> it.copyOf().also { copy -> copy[0] = 0 }; else -> it } }
            return object : ByteArrayInputStream(actual) {
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int = if (mode == "zero") 0 else super.read(bytes, offset, count)
                override fun close() { inputClosed = true; if (mode == "inputClose") throw IOException("sensitive-provider-details"); super.close() }
            }
        }
        override fun delete(): Boolean { deletions++; return deleteResult.also { if (it) output.reset() } }
    }
}
