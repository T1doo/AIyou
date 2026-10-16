package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual controller + encrypted files + SQLite. Controlled queue/document are not SAF evidence. */
class RecordHistoryControllerTest {
    @Test fun selectionDefaultsToCurrentOnlyAndOnlyAnExplicitSelectingToggleRequestsCompleteHistory(): Unit = Harness().use { h ->
        h.select(); assertFalse(h.ui.includeHistory)
        h.controller.includeHistory(h.ui.session - 1, true); assertFalse(h.ui.includeHistory)
        h.controller.prepare(h.ui.session); h.drain()
        assertEquals(1, h.currentPreviews); assertEquals(0, h.historyPreviews); assertNull(h.ui.mediaPreview!!.history)
        h.controller.includeHistory(h.ui.session, true); assertFalse(h.ui.includeHistory)
        h.controller.selectExport(h.ui.session); h.controller.toggle(h.ui.session, h.id, 2)
        h.controller.includeHistory(h.ui.session, true); h.controller.prepare(h.ui.session); h.drain()
        assertEquals(1, h.currentPreviews); assertEquals(1, h.historyPreviews)
        assertEquals(listOf(1, 2), h.ui.mediaPreview!!.history!!.single().history.map { it.revision })
        h.controller.includeHistory(h.ui.session, false); assertTrue(h.ui.includeHistory)
        assertFalse(h.ui.scopeAccepted); assertNull(h.ui.requestKey)
    }

    @Test fun historicalPreviewStillRequiresExplicitScopeAndMatchingPasswordBeforeAnyDocumentCanOpen(): Unit = Harness().use { h ->
        h.previewHistory(); val snapshot = h.ui.snapshot!!; val pass = RecordHistoryMediaTest.password()
        h.controller.encrypt(h.ui.session, snapshot.id, pass, RecordHistoryMediaTest.password())
        assertTrue(pass.all { it == '\u0000' }); assertTrue(h.queue.isEmpty()); assertNull(h.ui.requestKey)
        h.controller.acceptScope(h.ui.session, snapshot.id, true)
        h.controller.encrypt(h.ui.session, snapshot.id, RecordHistoryMediaTest.password(), "different synthetic password".toCharArray())
        assertTrue(h.queue.isEmpty()); assertEquals(0, h.seals)
        h.encrypt(); h.drain(); assertEquals(RecordPackagePhase.PICKING_EXPORT, h.ui.phase)
        val key = h.ui.requestKey!!; assertTrue(h.controller.claimPicker(h.ui.session, key)); assertFalse(h.controller.claimPicker(h.ui.session, key))
        val document = RecordPackageControllerTest.Document()
        h.controller.picked(h.ui.session, key, document); assertEquals(0, document.writes); h.drain()
        assertEquals(1, document.writes); assertEquals(RecordPackagePhase.DONE, h.ui.phase)
        val pack = runBlocking { h.actual.open({ document.bytes.inputStream() }, RecordHistoryMediaTest.password()) }
        try { assertTrue(pack.includesHistory); assertEquals(2, pack.records.single().history.size) } finally { pack.release() }
        assertEquals("independent history capture input", h.local.text)
    }

    @Test fun middleVersionEditAfterPreviewFailsBeforePickerAndDoesNotFallBackToCurrentScope(): Unit = Harness().use { h ->
        h.previewHistory(); val final = h.fixture.db.read("SELECT originalText FROM entry_revisions WHERE entryId=? AND revision=2", h.id)
        h.fixture.db.write("UPDATE entry_revisions SET originalText=?,contentHash=? WHERE entryId=? AND revision=1", "合成中间改写", OriginalExports.hash("合成中间改写"), h.id)
        h.encrypt(); h.drain()
        assertEquals(RecordPackagePhase.FAILED, h.ui.phase); assertNull(h.ui.requestKey)
        assertEquals(final, h.fixture.db.read("SELECT originalText FROM entry_revisions WHERE entryId=? AND revision=2", h.id))
        assertEquals(0, h.currentPreviews); assertEquals(1, h.historyPreviews)
        assertTrue(h.fixture.db.read("SELECT id FROM record_package_receipts").isEmpty())
    }

    @Test fun historicalSourceDeletionAfterPickerNeverOpensOutputAndLateCancelledPickerCannotWrite(): Unit = Harness().use { h ->
        h.previewHistory(); h.encrypt(); h.drain(); val oldKey = h.ui.requestKey!!; val session = h.ui.session
        assertTrue(h.controller.claimPicker(session, oldKey)); h.controller.picked(session, oldKey, null)
        assertEquals(RecordPackagePhase.CANCELLED, h.ui.phase)
        val late = RecordPackageControllerTest.Document(); h.controller.picked(session, oldKey, late); h.drain(); assertEquals(0, late.writes)
        h.controller.selectExport(session); h.controller.toggle(session, h.id, 2); h.controller.includeHistory(session, true)
        h.controller.prepare(session); h.drain(); h.encrypt(); h.drain(); val key = h.ui.requestKey!!
        assertNotEquals(oldKey, key); assertTrue(h.controller.claimPicker(session, key))
        h.fixture.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?", h.id)
        val document = RecordPackageControllerTest.Document(); h.controller.picked(session, key, document); h.drain()
        assertEquals(0, document.writes); assertEquals(RecordPackagePhase.FAILED, h.ui.phase)
    }

    @Test fun authenticatedHistoryRequiresSeparateRestoreConfirmationAndUnknownReadbackReusesCommittedMapping(): Unit = Harness().use { h ->
        val bytes = runBlocking { RecordHistoryMediaTest.export(h.actual, RecordHistoryMediaTest.history(h.fixture, h.id, 2)) }
        h.controller.open(); h.controller.chooseRestore(h.ui.session); val key = h.ui.requestKey!!
        assertTrue(h.controller.claimPicker(h.ui.session, key)); h.controller.picked(h.ui.session, key, RecordPackageControllerTest.Document(bytes))
        h.controller.authenticate(h.ui.session, RecordHistoryMediaTest.password()); h.drain()
        assertEquals(RecordPackagePhase.RESTORE_PREVIEW, h.ui.phase); assertTrue(h.ui.verified!!.includesHistory)
        assertEquals(0, h.imports); assertEquals(1, h.fixture.db.read("SELECT id FROM entries").size)
        val verified = h.ui.verified!!
        h.controller.confirmRestore(h.ui.session, verified.manifest.backupId, "0".repeat(64), h.ui.epoch)
        assertTrue(h.queue.isEmpty()); h.failReadback = true; h.confirm(); h.drain()
        assertEquals(RecordPackagePhase.UNCERTAIN, h.ui.phase); assertEquals(2, h.fixture.db.read("SELECT id FROM entries").size)
        val newId = h.fixture.db.read("SELECT id FROM entries WHERE id<>?", h.id).single().single() as String
        RecordHistoryMediaTest.revise(h.fixture, newId, 3, "合成恢复后的独立修改", 300)
        h.confirm(); h.drain()
        assertEquals(RecordPackagePhase.DONE, h.ui.phase); assertEquals(2, h.imports)
        assertEquals(2, h.fixture.db.read("SELECT id FROM entries").size)
        assertEquals(3L, h.fixture.db.read("SELECT currentRevision FROM entries WHERE id=?", newId).single().single())
        assertEquals(3, h.local.entries.single { it.id == newId }.currentRevision)
        assertEquals(2, h.local.packageOrigins.getValue(newId).restoredRevisionCount)
        assertEquals("independent history capture input", h.local.text)
    }

    @Test fun historyPreparationCancelledBeforeQueuedReadCannotExposePreviewOrStartEncryption(): Unit = Harness().use { h ->
        h.select(); h.controller.includeHistory(h.ui.session, true); h.controller.prepare(h.ui.session)
        val session = h.ui.session; h.controller.cancel(session); h.drain()
        assertEquals(RecordPackagePhase.CANCELLED, h.ui.phase); assertEquals(0, h.historyPreviews); assertNull(h.ui.mediaPreview)
        h.controller.selectExport(session); assertFalse(h.ui.includeHistory); assertFalse(h.ui.scopeAccepted)
        assertEquals(0, h.imports); assertEquals(0, h.seals)
    }

    private class Harness : AutoCloseable {
        val fixture = PermanentErasureFixture()
        val id = fixture.entry(text = "合成后来被删掉的原文")
        init { RecordHistoryMediaTest.revise(fixture, id, 2, "合成当前原文", 7) }
        val originals = SqlOriginalExportRepository(fixture.gateway, fixture.tags)
        val actual = RecordHistoryMediaTest.repository(fixture)
        val state = MutableStateFlow(LocalRecordUiState(ready = true, opening = false, supportsRecordPackages = true,
            text = "independent history capture input", entries = listOf(StoredEntry(id, 2, "合成当前原文", 5, "UTC", ProcessingPolicy.LOCAL_ONLY))))
        var local: LocalRecordUiState
            get() = state.value
            set(value) { state.value = value }
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        var currentPreviews = 0; var historyPreviews = 0; var seals = 0; var imports = 0; var failReadback = false
        val store = object : EntryRepository {
            override val originalExport = originals
            override val manualTags = fixture.tags
            override val recordPackages = fixture.packages
            override val mediaPackages = object : RecordMediaPackageRepository by actual {
                override suspend fun preview(snapshot: OriginalExportSnapshot): RecordMediaPreview { currentPreviews++; return actual.preview(snapshot) }
                override suspend fun previewHistory(snapshot: OriginalExportSnapshot, includeActivities: Boolean): RecordMediaPreview {
                    historyPreviews++; return actual.previewHistory(snapshot, includeActivities)
                }
                override suspend fun seal(preview: RecordMediaPreview, password: CharArray): PreparedRecordMediaPackage { seals++; return actual.seal(preview, password) }
                override suspend fun restore(verified: VerifiedRecordPackage, newIds: List<String>, importedAtMillis: Long): RecordPackageRestoreResult {
                    imports++; return actual.restore(verified, newIds, importedAtMillis)
                }
            }
            override suspend fun entries(): List<StoredEntry> {
                if (failReadback) { failReadback = false; error("Synthetic historical restore readback loss") }
                return fixture.db.read("SELECT e.id,e.currentRevision,r.originalText,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy FROM entries e JOIN entry_revisions r ON e.id=r.entryId AND e.currentRevision=r.revision WHERE NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1)").map {
                    StoredEntry(it[0] as String, (it[1] as Long).toInt(), it[2] as String, it[3] as Long, it[4] as String, ProcessingPolicy.valueOf(it[5] as String))
                }
            }
            override suspend fun readDraft(): TextDraft? = null
            override suspend fun updateDraft(text: String): TextDraft = error("Not requested")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Not requested")
            override suspend fun revisions(entryId: String): List<OriginalRevision> = error("History must use authoritative package reader")
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Not requested")
        }
        val controller = RecordPackageController({ local }, { change -> state.update(change) }, { queue.add(it); true },
            JcaRecordPackageCipher(), Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC))
        val ui get() = checkNotNull(local.recordPackage)
        fun select() { controller.open(); controller.selectExport(ui.session); controller.toggle(ui.session, id, 2) }
        fun previewHistory() { select(); controller.includeHistory(ui.session, true); controller.prepare(ui.session); drain() }
        fun encrypt() { val snapshot = ui.snapshot!!; controller.acceptScope(ui.session, snapshot.id, true); controller.encrypt(ui.session, snapshot.id, RecordHistoryMediaTest.password(), RecordHistoryMediaTest.password()) }
        fun confirm() { val verified = ui.verified!!; controller.confirmRestore(ui.session, verified.manifest.backupId, verified.digest, ui.epoch) }
        fun drain() = runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(store) }
        override fun close() { controller.dispose(); fixture.close() }
    }
}
