import android.app.Application
import com.t1doo.aiyou.data.AttachmentLedger
import com.t1doo.aiyou.data.EncryptedAttachmentStore
import com.t1doo.aiyou.domain.AttachmentStore
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real production controller/VM and AES file store; synthetic record ledger/decoder, not Android. */
class HistoricalAttachmentTest {
    @get:Rule val temporary = TemporaryFolder()
    private val oldPng = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + byteArrayOf(1)
    private val newPng = oldPng.dropLast(1).toByteArray() + byteArrayOf(2)
    private val original = StoredEntry("synthetic-original", 1, "合成旧原文", 1, "Etc/UTC", ProcessingPolicy.LOCAL_ONLY)
    private val ledger = object : AttachmentLedger {
        val rows = linkedMapOf<String, LocalAttachment>()
        override suspend fun all() = rows.values.toList()
        override suspend fun find(id: String) = rows[id]
        override suspend fun insert(value: LocalAttachment) { check(!rows.containsKey(value.intent.id)); rows[value.intent.id] = value }
        override suspend fun transition(expected: LocalAttachment, next: AttachmentState): LocalAttachment {
            check(rows[expected.intent.id] == expected)
            return expected.copy(state = next).also { rows[expected.intent.id] = it }
        }
    }
    private fun store() = EncryptedAttachmentStore(File(temporary.root, "blobs"), ledger, ByteArray(32) { (it + 1).toByte() })
    private fun blob(id: String) = File(temporary.root, "blobs/$id.aead")
    private fun picture(bytes: ByteArray) = object : AttachmentPicture {
        override val width = bytes.last().toInt(); override val height = 1
    }
    private class Source(val bytes: ByteArray) : SelectedImageSource {
        var opens = 0
        override fun mediaType() = "image/png"
        override fun open(): InputStream { opens++; return ByteArrayInputStream(bytes) }
    }
    private inner class Records : EntryRepository {
        override val attachments = store()
        var entry: StoredEntry? = original
        var versions = listOf(OriginalRevision(original.id, 1, original.originalText, "synthetic", 1))
        override suspend fun entries() = listOfNotNull(entry)
        override suspend fun revisions(entryId: String) = versions
        override suspend fun readDraft() = TextDraft("capture", 1, "合成此刻草稿")
        override suspend fun updateDraft(text: String): TextDraft = error("not a capture test")
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("not a capture test")
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry {
            val before = entry ?: throw RecordConflict()
            if (before.id != entryId || before.currentRevision != expectedRevision) throw RecordConflict()
            return before.copy(currentRevision = expectedRevision + 1, originalText = text).also {
                versions = versions + OriginalRevision(entryId, it.currentRevision, text, "synthetic", createdAtMillis); entry = it
            }
        }
    }
    private inner class Fixture(val records: Records = Records(), val storage: AttachmentStore = records.attachments,
        decode: (ByteArray) -> AttachmentPicture = ::picture) {
        val repository = object : EntryRepository by records { override val attachments = storage }
        var local = LocalRecordUiState(ready = true, opening = false, entries = listOfNotNull(records.entry), text = "合成此刻草稿")
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        val controller = LocalAttachmentController({ queue.addLast(it); true }, decode,
            canUsePanel = { it.hasRecordAccess(local) })
        fun drain() = runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(repository) }
        fun current(revision: Int) { local = local.copy(entries = listOfNotNull(records.entry), originalHistory = null); controller.open(original.id, revision); drain() }
        fun historical(revision: Int) {
            val history = OriginalHistoryUiState(7, original.id, requestId = 3, loading = false,
                currentRevision = records.entry!!.currentRevision, revisions = records.versions, selectedRevision = revision)
            local = local.copy(entries = listOfNotNull(records.entry), originalHistory = history)
            controller.openHistorical(original.id, revision, AttachmentHistoryOwner(history.sessionId, history.requestId, history.currentRevision!!)); drain()
        }
        fun close() { controller.close(controller.state.value!!.sessionId); drain() }
        fun import(bytes: ByteArray = oldPng): LocalAttachment {
            val session = controller.state.value!!.sessionId; controller.select(session)
            controller.selected(controller.state.value!!.selection!!.requestId, Source(bytes)); drain()
            assertNull(controller.state.value!!.error); return controller.state.value!!.items.single()
        }
    }

    @Test fun encryptedAttachmentSurvivesEditAndColdHistoricalReadWithoutMigratingToNewVersion() = runBlocking {
        val first = Fixture(); first.current(1); val old = first.import(); first.close()
        val cipher = blob(old.intent.id).readBytes(); val modified = blob(old.intent.id).lastModified()
        first.records.revise(original.id, 1, "合成新原文", 2)
        val cold = Fixture(first.records, store()); cold.historical(1)
        val panel = cold.controller.state.value!!; assertTrue(panel.readOnly); assertEquals(listOf(old), panel.items)
        cold.controller.view(panel.sessionId, old.intent.id); cold.drain()
        assertEquals(1, cold.controller.state.value!!.picture!!.width)
        assertArrayEquals(oldPng, cold.storage.read(old.intent.id)); assertArrayEquals(cipher, blob(old.intent.id).readBytes())
        assertEquals(modified, blob(old.intent.id).lastModified()); assertEquals(1, ledger.rows.size)
        cold.close(); cold.current(2); assertTrue(cold.controller.state.value!!.items.isEmpty())
        val next = cold.import(newPng); assertEquals(2, next.intent.revision); assertNotEquals(old.intent.id, next.intent.id)
        cold.close(); cold.historical(1); assertEquals(listOf(old), cold.controller.state.value!!.items)
        cold.close(); cold.historical(2); assertEquals(listOf(next), cold.controller.state.value!!.items)
        cold.controller.view(cold.controller.state.value!!.sessionId, next.intent.id); cold.drain()
        assertEquals(2, cold.controller.state.value!!.picture!!.width)
        assertEquals("合成此刻草稿", cold.local.text); assertEquals(original.originalText, first.records.versions.first().originalText)
    }

    @Test fun historicalModeRejectsAllImportAndRetryEntrypointsEvenForFailedAttachment() = runBlocking {
        val f = Fixture(); f.current(1); val row = f.import(); f.close()
        ledger.rows[row.intent.id] = row.copy(state = AttachmentState.FAILED)
        f.records.revise(original.id, 1, "合成新原文", 2); f.historical(1)
        val before = f.controller.state.value!!; val source = Source(oldPng); val cipher = blob(row.intent.id).readBytes()
        f.controller.select(before.sessionId); f.controller.select(before.sessionId, row.intent.id)
        assertFalse(f.controller.claimPicker(2)); f.controller.selected(2, source); f.controller.pickerFailed(2)
        f.controller.retrySave(before.sessionId); f.controller.cancel(before.sessionId); f.drain()
        assertEquals(before, f.controller.state.value); assertEquals(0, source.opens)
        assertArrayEquals(cipher, blob(row.intent.id).readBytes()); assertEquals(1, ledger.rows.size)
    }

    @Test fun missingWrongDuplicateRevisionOrMissingParentCannotAuthorizeAnyAttachmentOperation() = runBlocking {
        for (mode in 0..4) {
            val records = Records(); records.revise(original.id, 1, "合成新版", 2)
            when (mode) {
                0 -> records.versions = records.versions.filter { it.revision != 1 }
                1 -> records.versions = records.versions.map { it.copy(entryId = "another-record") }
                2 -> records.versions = records.versions + records.versions.first()
                3 -> records.entry = null
                4 -> records.entry = records.entry!!.copy(currentRevision = 3)
            }
            var operations = 0
            val boundary = object : AttachmentStore by store() {
                override suspend fun recover() { operations++; error("must not recover an unauthorized history") }
                override suspend fun list(entryId: String, revision: Int): List<LocalAttachment> { operations++; error("must not list") }
                override suspend fun read(id: String): ByteArray { operations++; error("must not read") }
            }
            val f = Fixture(records, boundary)
            val history = OriginalHistoryUiState(7, original.id, requestId = 3, loading = false, currentRevision = 2,
                selectedRevision = 1, revisions = listOf(OriginalRevision(original.id, 1, original.originalText, "synthetic", 1)))
            f.local = f.local.copy(entries = listOf(original.copy(currentRevision = 2)), originalHistory = history)
            f.controller.openHistorical(original.id, 1, AttachmentHistoryOwner(7, 3, 2)); f.drain()
            assertEquals(0, operations); assertTrue(f.controller.state.value!!.unavailable); assertNull(f.controller.state.value!!.picture)
        }
    }

    @Test fun crossRecordOrRevisionRowsAreRejectedBeforeCipherReadAndNeverDisplayed() = runBlocking {
        val first = Fixture(); first.current(1); val row = first.import(); first.close()
        first.records.revise(original.id, 1, "合成新版", 2)
        for (intent in listOf(row.intent.copy(entryId = "another-record"), row.intent.copy(revision = 2))) {
            var reads = 0
            val boundary = object : AttachmentStore by store() {
                override suspend fun list(entryId: String, revision: Int) = listOf(row.copy(intent = intent))
                override suspend fun read(id: String): ByteArray { reads++; return oldPng }
            }
            val f = Fixture(first.records, boundary); f.historical(1)
            assertTrue(f.controller.state.value!!.items.isEmpty()); assertNotNull(f.controller.state.value!!.error)
            f.controller.view(f.controller.state.value!!.sessionId, row.intent.id); f.drain()
            assertEquals(0, reads); assertNull(f.controller.state.value!!.picture)
        }
    }

    @Test fun oldDecodedReadCannotPublishAfterSameRecordChangesVersionAndReopensOldVersion() = runBlocking {
        val first = Fixture(); first.current(1); val row = first.import(); first.close()
        first.records.revise(original.id, 1, "合成新版", 2)
        lateinit var f: Fixture
        f = Fixture(first.records, store(), decode = { bytes ->
            val oldSession = f.controller.state.value!!.sessionId
            f.controller.close(oldSession)
            f.local = f.local.copy(originalHistory = f.local.originalHistory!!.copy(requestId = 4, selectedRevision = 2))
            f.controller.openHistorical(original.id, 2, AttachmentHistoryOwner(7, 4, 2))
            picture(bytes)
        }); f.historical(1)
        val old = f.controller.state.value!!.sessionId; f.controller.view(old, row.intent.id); f.drain()
        val next = f.controller.state.value!!; assertNotEquals(old, next.sessionId); assertEquals(2, next.revision)
        assertTrue(next.items.isEmpty()); assertNull(next.picture); assertNull(next.error)
        f.close(); f.historical(1); val reopened = f.controller.state.value!!
        f.controller.view(old, row.intent.id); f.controller.closePicture(old); f.drain()
        assertEquals(reopened, f.controller.state.value); assertNotEquals(old, reopened.sessionId)
    }

    @Test fun revokedHistoryRequestDuringAuthenticatedReadClearsBytesAndPublishesNoPicture() = runBlocking {
        val first = Fixture(); first.current(1); val row = first.import(); first.close()
        first.records.revise(original.id, 1, "合成新版", 2)
        lateinit var f: Fixture; var returned: ByteArray? = null; var decodes = 0
        val real = store()
        val boundary = object : AttachmentStore by real {
            override suspend fun read(id: String) = real.read(id).also {
                returned = it; f.local = f.local.copy(originalHistory = f.local.originalHistory!!.copy(requestId = 4))
            }
        }
        f = Fixture(first.records, boundary, decode = { decodes++; picture(it) }); f.historical(1)
        f.controller.view(f.controller.state.value!!.sessionId, row.intent.id); f.drain()
        assertEquals(0, decodes); assertTrue(returned!!.all { it == 0.toByte() })
        assertTrue(f.controller.state.value!!.unavailable); assertNull(f.controller.state.value!!.picture)
        assertArrayEquals(oldPng, real.read(row.intent.id)); assertEquals(AttachmentState.READY, ledger.rows[row.intent.id]!!.state)
    }

    @Test fun historicalReadFailureAndCorruptionPreserveCiphertextAndNeverEnableRepairImport() = runBlocking {
        val first = Fixture(); first.current(1); val row = first.import(); first.close()
        first.records.revise(original.id, 1, "合成新版", 2)
        val real = store(); var fail = true
        val boundary = object : AttachmentStore by real {
            override suspend fun read(id: String): ByteArray { if (fail) throw IOException("synthetic authentication access failure"); return real.read(id) }
        }
        val f = Fixture(first.records, boundary); f.historical(1); val cipher = blob(row.intent.id).readBytes()
        f.controller.view(f.controller.state.value!!.sessionId, row.intent.id); f.drain()
        assertNotNull(f.controller.state.value!!.error); assertNull(f.controller.state.value!!.picture)
        assertArrayEquals(cipher, blob(row.intent.id).readBytes()); assertEquals(AttachmentState.READY, ledger.rows[row.intent.id]!!.state)
        fail = false; f.controller.refresh(f.controller.state.value!!.sessionId); f.drain()
        f.controller.view(f.controller.state.value!!.sessionId, row.intent.id); f.drain(); assertNotNull(f.controller.state.value!!.picture)
        val bad = cipher.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }; blob(row.intent.id).writeBytes(bad)
        f.controller.view(f.controller.state.value!!.sessionId, row.intent.id); f.drain()
        assertNull(f.controller.state.value!!.picture); assertEquals(AttachmentState.CORRUPT, f.controller.state.value!!.items.single().state)
        f.controller.select(f.controller.state.value!!.sessionId, row.intent.id); f.drain()
        assertNull(f.controller.state.value!!.selection); assertArrayEquals(bad, blob(row.intent.id).readBytes())
    }

    @Test fun actualViewModelSaveEditColdHistoryReadKeepsCurrentWriteGateAndBothInputs() = runBlocking {
        val records = com.t1doo.aiyou.data.EncryptedEntryRepository().apply { seed(original); releaseRevision.complete(Unit) }
        val real = store(); val repository = object : EntryRepository by records { override val attachments: AttachmentStore = real }
        val vm = LocalRecordsViewModel(Application(), { repository }, decodePng = ::picture)
        try { withTimeout(5000) {
            vm.state.first { it.ready }; vm.changeText("合成此刻草稿"); vm.state.first { it.draftNotice == "草稿已保存在本机" }
            vm.attachments.open(original.id, 1); vm.attachments.state.first { it?.loading == false }
            val session = vm.attachments.state.value!!.sessionId; vm.attachments.select(session)
            vm.attachments.selected(vm.attachments.state.value!!.selection!!.requestId, Source(oldPng))
            val saved = vm.attachments.state.first { it?.notice != null }!!.items.single()
            vm.attachments.close(session)
            vm.beginOriginalEdit(original.id, 1); val edit = vm.state.value.originalEdit!!
            vm.changeOriginalEdit(edit.sessionId, "合成新版原文"); vm.saveOriginalEdit(edit.sessionId)
            vm.state.first { it.originalEdit == null && it.entries.single().currentRevision == 2 }
            vm.beginOriginalHistory(original.id, 2); vm.state.first { it.originalHistory?.loading == false }
            vm.selectOriginalRevision(vm.state.value.originalHistory!!.sessionId, 1)
            val h = vm.state.first { it.originalHistory?.let { h -> !h.loading && h.selectedRevision == 1 } == true }.originalHistory!!
            vm.attachments.openHistorical(original.id, 1, AttachmentHistoryOwner(h.sessionId, h.requestId, 2))
            val panel = vm.attachments.state.first { it?.loading == false }!!; assertNull(panel.error); assertEquals(listOf(saved), panel.items)
            vm.selectOriginalRevision(h.sessionId, 2); vm.backOriginalHistory(h.sessionId)
            vm.editOriginalHistory(h.sessionId, h.requestId, 1, 2)
            assertEquals(h, vm.state.value.originalHistory); assertNull(vm.state.value.originalEdit)
            vm.attachments.view(panel.sessionId, saved.intent.id); vm.attachments.state.first { it?.picture != null }
            vm.attachments.close(panel.sessionId); assertEquals(h, vm.state.value.originalHistory)
            assertEquals("合成此刻草稿", vm.state.value.text); assertEquals("合成此刻草稿", records.draft!!.text)
            assertArrayEquals(oldPng, real.read(saved.intent.id)); assertEquals(1, saved.intent.revision)
            vm.backOriginalHistory(h.sessionId); vm.state.first { it.originalHistory?.loading == false }
            vm.backOriginalHistory(h.sessionId); assertNull(vm.state.value.originalHistory)
            vm.attachments.open(original.id, 2); vm.attachments.state.first { it?.loading == false }
            assertTrue(vm.attachments.state.value!!.items.isEmpty()); assertFalse(vm.attachments.state.value!!.readOnly)
        } } finally { vm.scope.cancel() }
    }
}
