import com.t1doo.aiyou.data.AttachmentLedger
import com.t1doo.aiyou.data.EncryptedAttachmentStore
import com.t1doo.aiyou.domain.AttachmentConflict
import com.t1doo.aiyou.domain.AttachmentStore
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import android.app.Application
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual controller + AES/file store. Synthetic ledger/record and decoder boundaries, not Android evidence. */
class AttachmentControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + "synthetic host source".toByteArray()
    private val picture = object : AttachmentPicture { override val width = 1; override val height = 1 }
    private val original = StoredEntry("synthetic-original", 1, "合成原文", 1, "Etc/UTC", ProcessingPolicy.LOCAL_ONLY)
    private val ledger = object : AttachmentLedger {
        val rows = linkedMapOf<String, LocalAttachment>()
        override suspend fun all() = rows.values.toList()
        override suspend fun find(id: String) = rows[id]
        override suspend fun insert(value: LocalAttachment) {
            if (value.intent.entryId != original.id || value.intent.revision != 1 || rows.containsKey(value.intent.id)) throw AttachmentConflict()
            rows[value.intent.id] = value
        }
        override suspend fun transition(expected: LocalAttachment, next: AttachmentState): LocalAttachment {
            if (rows[expected.intent.id] != expected) throw AttachmentConflict()
            return expected.copy(state = next).also { rows[expected.intent.id] = it }
        }
    }
    private val directory get() = File(temporary.root, "blobs")
    private fun store(checkpoint: (String) -> Unit = {}) =
        EncryptedAttachmentStore(directory, ledger, ByteArray(32) { (it + 1).toByte() }, checkpoint)
    private class Records(override val attachments: AttachmentStore, var original: StoredEntry) : EntryRepository {
        val draft = TextDraft("capture", 1, "合成此刻草稿")
        override suspend fun entries() = listOf(original)
        override suspend fun readDraft() = draft
        override suspend fun updateDraft(text: String): TextDraft = error("attachment must not edit capture")
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("must not save capture")
        override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("must not edit original")
    }
    private inner class Fixture(storage: AttachmentStore = store(), decode: (ByteArray) -> AttachmentPicture = { picture },
        usable: () -> Boolean = { true }) {
        val records = Records(storage, original)
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        val controller = LocalAttachmentController({ queue.addLast(it); true }, decode, usable)
        fun drain() = runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(records) }
        fun open() { controller.open(original.id, 1); drain(); assertFalse(controller.state.value!!.loading) }
        fun select(failedId: String? = null): Long {
            controller.select(controller.state.value!!.sessionId, failedId)
            val request = controller.state.value!!.selection!!.requestId
            assertTrue(controller.claimPicker(request)); assertFalse(controller.claimPicker(request))
            return request
        }
        fun import(source: SelectedImageSource = Source(png)) { controller.selected(select(), source); drain() }
    }
    private class Source(private val bytes: ByteArray, private val mime: String = "image/png") : SelectedImageSource {
        var opens = 0; var closed = false
        override fun mediaType() = mime
        override fun open(): InputStream {
            opens++
            return object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
        }
    }
    private fun blob(id: String) = File(directory, "$id.aead")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun actualEncryptedSaveReadbackAndReopenKeepOriginalRevisionAndCapture() = runBlocking {
        val f = Fixture(); f.open()
        val source = Source(png); f.import(source)
        val panel = f.controller.state.value!!
        assertNull(panel.selection); assertNull(panel.error); assertNotNull(panel.notice)
        val row = panel.items.single()
        assertEquals(AttachmentState.READY, row.state)
        assertEquals(original.id, row.intent.entryId); assertEquals(1, row.intent.revision)
        assertEquals(hash(png), row.intent.sha256); assertEquals(png.size.toLong(), row.intent.byteSize)
        assertEquals(1, source.opens); assertTrue(source.closed)
        assertArrayEquals(png, f.records.attachments.read(row.intent.id))
        assertFalse(blob(row.intent.id).readBytes().contentEquals(png))
        assertEquals(setOf(".store.lock", "${row.intent.id}.aead"), directory.list()!!.toSet())
        assertEquals(original, f.records.original); assertEquals("合成此刻草稿", f.records.draft.text)
        f.controller.close(panel.sessionId); f.drain(); f.open()
        f.controller.view(f.controller.state.value!!.sessionId, row.intent.id); f.drain()
        assertSame(picture, f.controller.state.value!!.picture)
    }

    @Test fun pickerCancelAndOldCallbackCannotWriteOrReplaceNewOwner() {
        val f = Fixture(); f.open(); val old = f.select()
        f.controller.selected(old, null); f.drain()
        assertTrue(ledger.rows.isEmpty()); assertNull(f.controller.state.value!!.selection)
        val next = f.select(); val source = Source(png)
        f.controller.selected(old, source); f.drain()
        assertEquals(next, f.controller.state.value!!.selection!!.requestId); assertEquals(0, source.opens)
        f.controller.close(f.controller.state.value!!.sessionId); f.drain(); f.open()
        f.controller.selected(next, source); f.drain()
        assertNull(f.controller.state.value!!.selection); assertEquals(0, source.opens); assertTrue(ledger.rows.isEmpty())
    }

    @Test fun cancellationDuringSourceReadClosesStreamWithoutDurableIntent() {
        val f = Fixture(); f.open(); val request = f.select(); var closed = false
        f.controller.selected(request, object : SelectedImageSource {
            override fun mediaType() = "image/png"
            override fun open() = object : ByteArrayInputStream(png) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    f.controller.cancel(f.controller.state.value!!.sessionId)
                    return super.read(b, off, len)
                }
                override fun close() { closed = true; super.close() }
            }
        }); f.drain()
        assertTrue(closed); assertTrue(ledger.rows.isEmpty()); assertNull(f.controller.state.value!!.selection)
        assertEquals(original, f.records.original)
    }

    @Test fun lateDecodedPictureCannotAttachToReopenedSameRecord() {
        lateinit var f: Fixture
        f = Fixture(decode = {
            f.controller.close(f.controller.state.value!!.sessionId)
            f.controller.open(original.id, 1)
            picture
        }); f.open(); f.import()
        assertTrue(ledger.rows.isEmpty()); assertNull(f.controller.state.value!!.picture)
        assertNull(f.controller.state.value!!.selection); assertNull(f.controller.state.value!!.notice)
    }

    @Test fun revisionChangeBeforeCommitPreservesSourceAndNeverRebinds() {
        lateinit var f: Fixture
        f = Fixture(decode = { f.records.original = original.copy(currentRevision = 2); picture })
        f.open(); val request = f.select(); val id = f.controller.state.value!!.selection!!.attachmentId
        f.controller.selected(request, Source(png)); f.drain()
        val panel = f.controller.state.value!!
        assertTrue(panel.unavailable); assertEquals(1, panel.revision)
        assertEquals(id, panel.selection!!.attachmentId); assertTrue(panel.selection.sourcePrepared)
        assertTrue(ledger.rows.isEmpty()); assertNull(panel.notice)
        f.controller.retrySave(panel.sessionId); f.drain(); assertTrue(ledger.rows.isEmpty())
        f.controller.close(panel.sessionId); f.drain()
    }

    @Test fun readyReadbackFailureRetriesSameIdWithoutSourceOrCipherRewrite() = runBlocking {
        val real = store(); var reads = 0; var saves = 0
        val boundary = object : AttachmentStore by real {
            override suspend fun save(intent: AttachmentIntent, source: () -> InputStream): LocalAttachment {
                saves++; return real.save(intent, source)
            }
            override suspend fun read(id: String): ByteArray {
                if (++reads == 1) throw IOException("synthetic acknowledgement loss")
                return real.read(id)
            }
        }
        val f = Fixture(boundary); f.open(); val source = Source(png); f.import(source)
        val failed = f.controller.state.value!!; val id = failed.selection!!.attachmentId
        assertEquals(AttachmentState.READY, failed.items.single().state)
        assertNull(failed.notice); assertTrue(failed.selection.sourcePrepared)
        val ciphertext = blob(id).readBytes(); val modified = blob(id).lastModified()
        f.controller.retrySave(failed.sessionId); f.drain()
        assertNull(f.controller.state.value!!.selection); assertNotNull(f.controller.state.value!!.notice)
        assertEquals(1, saves); assertEquals(2, reads); assertEquals(1, source.opens)
        assertArrayEquals(ciphertext, blob(id).readBytes()); assertEquals(modified, blob(id).lastModified())
        assertEquals(1, ledger.rows.size)
    }

    @Test fun restartingFailedImportRequiresSameBytesAndStableIntent() = runBlocking {
        val f = Fixture(store { if (it == "encrypted") throw IOException("synthetic import interruption") })
        f.open(); f.import(); val failed = f.controller.state.value!!; val id = failed.selection!!.attachmentId
        assertEquals(AttachmentState.FAILED, ledger.rows[id]!!.state)
        val expected = ledger.rows[id]!!.intent
        f.controller.close(failed.sessionId); f.drain()
        val reopened = Fixture(); reopened.open()
        assertEquals(AttachmentState.FAILED, reopened.controller.state.value!!.items.single().state)
        val wrongRequest = reopened.select(id)
        reopened.controller.selected(wrongRequest, Source(png + 1)); reopened.drain()
        assertEquals(expected, ledger.rows[id]!!.intent); assertEquals(AttachmentState.FAILED, ledger.rows[id]!!.state)
        assertFalse(blob(id).exists()); assertNull(reopened.controller.state.value!!.notice)
        reopened.controller.retrySave(reopened.controller.state.value!!.sessionId); reopened.drain()
        val correctRequest = reopened.controller.state.value!!.selection!!.requestId
        reopened.controller.selected(correctRequest, Source(png)); reopened.drain()
        assertNull(reopened.controller.state.value!!.error); assertEquals(1, ledger.rows.size)
        assertEquals(expected, ledger.rows[id]!!.intent); assertEquals(AttachmentState.READY, ledger.rows[id]!!.state)
        assertArrayEquals(png, reopened.records.attachments.read(id))
    }

    @Test fun corruptCiphertextIsHiddenPreservedAndHasNoOverwriteRetry() {
        val f = Fixture(); f.open(); f.import(); val row = f.controller.state.value!!.items.single()
        val changed = blob(row.intent.id).readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        blob(row.intent.id).writeBytes(changed)
        f.controller.view(f.controller.state.value!!.sessionId, row.intent.id); f.drain()
        val panel = f.controller.state.value!!
        assertNull(panel.picture); assertNotNull(panel.error); assertEquals(AttachmentState.CORRUPT, panel.items.single().state)
        f.controller.select(panel.sessionId, row.intent.id); f.drain()
        assertNull(f.controller.state.value!!.selection); assertArrayEquals(changed, blob(row.intent.id).readBytes())
    }

    @Test fun invalidMimeHeaderPermissionZeroReadAndOversizeCreateNoIntent() {
        val f = Fixture(); f.open()
        val invalid = listOf<SelectedImageSource>(Source(png, "image/jpeg"), Source(byteArrayOf(1, 2, 3)),
            object : SelectedImageSource {
                override fun mediaType() = "image/png"
                override fun open(): InputStream = throw SecurityException("synthetic revoked permission")
            }, object : SelectedImageSource {
                override fun mediaType() = "image/png"
                override fun open() = object : InputStream() {
                    override fun read() = 0
                    override fun read(b: ByteArray, off: Int, len: Int) = 0
                }
            }, Source(ByteArray(LocalAttachmentController.MAX_SOURCE_BYTES + 1)))
        invalid.forEach { source ->
            f.import(source)
            assertTrue(ledger.rows.isEmpty()); assertNotNull(f.controller.state.value!!.error)
            assertNull(f.controller.state.value!!.notice); assertEquals(original, f.records.original)
            f.controller.cancel(f.controller.state.value!!.sessionId); f.drain()
        }
    }

    @Test fun unavailableRecordPreservesPreparedChoiceUntilRecoveryWithoutAnyStoreOperation() {
        var usable = true; var operations = 0; val real = store()
        val boundary = object : AttachmentStore by real {
            override suspend fun recover() { operations++; real.recover() }
            override suspend fun list(entryId: String, revision: Int): List<LocalAttachment> { operations++; return real.list(entryId, revision) }
        }
        lateinit var f: Fixture
        f = Fixture(boundary, decode = { usable = false; picture }, usable = { usable })
        f.open(); val initial = operations; val source = Source(png); f.import(source)
        val panel = f.controller.state.value!!; val selected = panel.selection!!
        assertTrue(selected.sourcePrepared); assertFalse(panel.unavailable); assertNotNull(panel.error)
        assertEquals(initial, operations); assertTrue(ledger.rows.isEmpty())
        usable = true; f.controller.retrySave(panel.sessionId); f.drain()
        assertEquals(selected.attachmentId, f.controller.state.value!!.items.single().intent.id)
        assertEquals(1, source.opens); assertNotNull(f.controller.state.value!!.notice)
    }

    @Test fun committingDisablesCancelCloseAndKeepsActiveSourceUntilAuthentication() = runBlocking {
        lateinit var f: Fixture
        f = Fixture(store { phase -> if (phase == "intent") {
            val panel = f.controller.state.value!!; assertTrue(panel.saving)
            f.controller.cancel(panel.sessionId); f.controller.close(panel.sessionId)
            assertEquals(panel, f.controller.state.value)
        } }); f.open(); f.import()
        val panel = f.controller.state.value!!
        assertNotNull(panel.notice); assertNull(panel.selection)
        assertArrayEquals(png, f.records.attachments.read(panel.items.single().intent.id))
    }

    @Test fun disposingDuringEncryptionNeverErasesActiveSourceOrPublishesLateResult() = runBlocking {
        lateinit var f: Fixture
        f = Fixture(store { phase -> if (phase == "intent") f.controller.markDisposed() })
        f.open(); f.import(); f.controller.disposeOnIo()
        assertNull(f.controller.state.value); assertEquals(AttachmentState.READY, ledger.rows.values.single().state)
        assertArrayEquals(png, store().read(ledger.rows.keys.single()))
    }

    @Test fun actualViewModelRecoversLateCaptureFailureAndKeepsCaptureWhileImporting() = runBlocking {
        val capture = com.t1doo.aiyou.data.EncryptedEntryRepository(failPriorEdit = true).apply { seed(original) }
        val real = store()
        val repository = object : EntryRepository by capture { override val attachments: AttachmentStore = real }
        val vm = LocalRecordsViewModel(Application(), { repository }, decodePng = { picture })
        try { withTimeout(5000) {
            vm.state.first { it.ready }
            vm.changeText("合成迟到的此刻草稿")
            capture.firstEditStarted.await()
            vm.attachments.open(original.id, 1)
            val nextSession = vm.attachments.state.value!!.sessionId
            capture.releaseFirstEdit.complete(Unit)
            vm.state.first { it.error != null }
            vm.attachments.state.first { it?.loading == false }
            vm.retry()
            vm.state.first { it.error == null && !it.opening }
            assertEquals(nextSession, vm.attachments.state.value!!.sessionId)
            vm.attachments.refresh(nextSession)
            vm.attachments.state.first { it?.loading == false && it.error == null }
            vm.attachments.select(nextSession)
            val selection = vm.attachments.state.value!!.selection!!
            val source = Source(png)
            // Prove actual shared-queue guards: hidden capture changes cannot replace either input.
            vm.changeText("不能替换草稿"); vm.save(); vm.beginOriginalEdit(original.id, 1); vm.beginOriginalHistory(original.id, 1)
            vm.attachments.selected(selection.requestId, source)
            vm.attachments.state.first { it?.notice?.startsWith("图片已保存在本机") == true }
            assertEquals(selection.attachmentId, vm.attachments.state.value!!.items.single().intent.id)
            assertEquals("合成迟到的此刻草稿", vm.state.value.text)
            assertEquals("合成迟到的此刻草稿", capture.draft!!.text)
            assertEquals(original, vm.state.value.entries.single()); assertEquals(1, source.opens)
            assertNull(vm.state.value.originalEdit); assertNull(vm.state.value.originalHistory)
            assertTrue(capture.submissions.isEmpty())
        } } finally { vm.scope.cancel() }
    }
}
