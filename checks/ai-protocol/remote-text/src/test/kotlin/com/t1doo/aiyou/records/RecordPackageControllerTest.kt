package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Test

/** Actual production controller + JCA + SQL; only selected document and queue/lifecycle shells. */
class RecordPackageControllerTest {
    @Test fun explicitSelectionFullPreviewScopeAndMatchingPassphrasePrecedeAnyFileWrite(): Unit = Harness().use { h ->
        h.controller.open(); val session = h.ui.session
        h.controller.selectExport(session); h.controller.toggle(session, ManualTaskSqlTest.LEGACY_ID, 1)
        h.controller.prepare(session); h.drain()
        assertEquals(RecordPackagePhase.EXPORT_PREVIEW, h.ui.phase)
        assertEquals("合成原文", h.ui.snapshot!!.records.single().entry.originalText)
        val password = PASSWORD.toCharArray()
        h.controller.encrypt(session, h.ui.snapshot!!.id, password, PASSWORD.toCharArray())
        assertTrue(password.all { it == '\u0000' }); assertTrue(h.queue.isEmpty()); assertNull(h.ui.requestKey)
        h.controller.acceptScope(session, h.ui.snapshot!!.id, true)
        h.controller.encrypt(session, h.ui.snapshot!!.id, PASSWORD.toCharArray(), "different synthetic password".toCharArray())
        assertTrue(h.queue.isEmpty()); assertNotNull(h.ui.error)
        h.controller.encrypt(session, h.ui.snapshot!!.id, PASSWORD.toCharArray(), PASSWORD.toCharArray()); h.drain()
        assertEquals(RecordPackagePhase.PICKING_EXPORT, h.ui.phase)
        val document = Document(); val key = h.ui.requestKey!!
        assertTrue(h.controller.claimPicker(session, key)); assertFalse(h.controller.claimPicker(session, key))
        h.controller.picked(session, key, document); assertEquals(0, document.writes); h.drain()
        assertEquals(RecordPackagePhase.DONE, h.ui.phase); assertEquals(1, document.writes)
        assertFalse(document.bytes.toString(Charsets.UTF_8).contains("合成原文"))
        assertEquals("合成原文", JcaRecordPackageCipher().open(document.bytes, PASSWORD.toCharArray()).records.single().originalText)
        assertEquals("independent capture input", h.local.text)
    }
    @Test fun wrongPassphraseNeverPublishesPreviewOrTouchesActualSql(): Unit = Harness().use { h ->
        val before = h.fixture.legacy(); h.restoreSelection()
        val password = " definitely wrong password ".toCharArray(); h.controller.authenticate(h.ui.session, password); h.drain()
        assertTrue(password.all { it == '\u0000' }); assertEquals(RecordPackagePhase.FAILED, h.ui.phase)
        assertNull(h.ui.verified); assertEquals(before, h.fixture.legacy()); assertEquals(0, h.imports)
    }
    @Test fun completeAuthenticationPreviewRequiresSeparateConfirmationThenReadsRealNewCopies(): Unit = Harness().use { h ->
        h.restorePreview(); assertEquals(RecordPackagePhase.RESTORE_PREVIEW, h.ui.phase); assertEquals(0, h.imports)
        assertEquals(1, h.fixture.db.read("SELECT id FROM entries").size)
        val verified = h.ui.verified!!; h.controller.confirmRestore(h.ui.session, verified.manifest.backupId, "0".repeat(64), h.ui.epoch)
        assertTrue(h.queue.isEmpty()); h.confirm(); h.drain()
        assertEquals(RecordPackagePhase.DONE, h.ui.phase); assertEquals(1, h.imports)
        assertEquals(2, h.local.entries.size); assertTrue(h.local.packageOriginsAvailable); assertEquals(1, h.local.packageOrigins.size)
        assertEquals(ProcessingPolicy.AI_NONE, h.local.entries.single { it.id != ManualTaskSqlTest.LEGACY_ID }.policy)
        assertEquals("independent capture input", h.local.text)
        assertEquals("合成草稿", h.fixture.db.read("SELECT text FROM capture_draft").single().single())
        assertFalse(h.ui.toString().contains("合成")); assertFalse(h.ui.toString().contains(PASSWORD))
    }
    @Test fun lostReadbackKeepsAuthenticatedIdentityAndRetryCreatesNoSecondBatch(): Unit = Harness().use { h ->
        h.restorePreview(); h.failReadback = true; h.confirm(); h.drain()
        assertEquals(RecordPackagePhase.UNCERTAIN, h.ui.phase); val digest = h.ui.verified!!.digest
        assertEquals(2, h.fixture.db.read("SELECT id FROM entries").size)
        h.confirm(); h.drain()
        assertEquals(RecordPackagePhase.DONE, h.ui.phase); assertEquals(2, h.imports)
        assertEquals(2, h.fixture.db.read("SELECT id FROM entries").size)
        assertEquals(digest, h.local.packageOrigins.values.single().packageDigest)
        assertTrue(h.ui.notice!!.contains("没有新增"))
    }
    @Test fun queuedImportCancelledOrOldPickerOwnerRetiredNeverWrites(): Unit = Harness().use { h ->
        h.restorePreview(); h.confirm(); h.controller.cancel(h.ui.session); h.drain()
        assertEquals(0, h.imports); assertEquals(1, h.fixture.db.read("SELECT id FROM entries").size)
        val first = Any(); h.controller.attachPickerOwner(first); h.controller.chooseRestore(h.ui.session)
        val key = h.ui.requestKey!!; assertTrue(h.controller.claimPicker(h.ui.session, key))
        h.controller.attachPickerOwner(Any())
        val stale = Document(RecordPackageCipherTest.fixtureBytes()); h.controller.picked(h.ui.session, key, stale)
        assertEquals(0, stale.reads); assertEquals(RecordPackagePhase.CANCELLED, h.ui.phase)
    }
    @Test fun closeInsideActualImportStillFinishesAtomicallyAndRefreshesRecordsWithoutFalsePanelAck(): Unit = Harness().use { h ->
        h.restorePreview(); val session = h.ui.session
        h.fixture.db.beforeSql = { if (it.startsWith("INSERT INTO entries")) h.controller.close(session) }
        h.confirm(); h.drain(); h.fixture.db.beforeSql = null
        assertNull(h.local.recordPackage); assertEquals(2, h.fixture.db.read("SELECT id FROM entries").size)
        assertEquals(2, h.local.entries.size); assertEquals("independent capture input", h.local.text)
        assertEquals(1, h.local.packageOrigins.size)
    }
    @Test fun cancellationAtSuccessStateCasCannotPublishStaleDoneOrReuseOldScope(): Unit = Harness().use { h ->
        h.restorePreview()
        h.beforePublish = { candidate ->
            if (candidate.recordPackage?.phase == RecordPackagePhase.DONE) {
                h.beforePublish = null; h.controller.cancel(h.ui.session)
            }
        }
        h.confirm(); h.drain()
        assertEquals(RecordPackagePhase.CANCELLED, h.ui.phase); assertNull(h.ui.verified)
        assertEquals(2, h.fixture.db.read("SELECT id FROM entries").size)
        assertEquals(2, h.local.entries.size) // Durable data refresh remains truthful after a cancelled ACK.
    }
    @Test fun changedSourceAfterExportPreviewFailsBeforeOpeningSelectedOutput(): Unit = Harness().use { h ->
        h.exportSelection(); val snapshot = h.ui.snapshot!!
        h.controller.acceptScope(h.ui.session, snapshot.id, true)
        h.controller.encrypt(h.ui.session, snapshot.id, PASSWORD.toCharArray(), PASSWORD.toCharArray()); h.drain()
        val key = h.ui.requestKey!!; h.controller.claimPicker(h.ui.session, key)
        OriginalExportSqlTest.revise(h.fixture.db, ManualTaskSqlTest.LEGACY_ID, "changed synthetic original", 2)
        val document = Document(); h.controller.picked(h.ui.session, key, document); h.drain()
        assertEquals(0, document.writes); assertEquals(RecordPackagePhase.FAILED, h.ui.phase)
    }
    @Test fun ioBoundsZeroReadsCloseReadbackFailureAndCancelPreserveEncryptedResidualFiles() {
        val encrypted = JcaRecordPackageCipher().seal(RecordPackageCipherTest.snapshot(), PASSWORD.toCharArray())
        val partial = Document().also { it.failClose = true }
        assertThrows(IOException::class.java) { RecordPackageIo.writeAndVerify(partial, encrypted) { true } }
        assertTrue(partial.bytes.isNotEmpty()); assertFalse(partial.bytes.toString(Charsets.UTF_8).contains("合成原文"))
        val mismatch = Document().also { it.badReadback = true }
        assertThrows(IllegalStateException::class.java) { RecordPackageIo.writeAndVerify(mismatch, encrypted) { true } }
        val zeros = object : SelectedRecordPackageDocument {
            override fun openForRead() = object : InputStream() { override fun read() = 0; override fun read(b: ByteArray, off: Int, len: Int) = 0 }
            override fun openForWrite(): OutputStream = error("unused")
        }
        assertThrows(IllegalStateException::class.java) { RecordPackageIo.read(zeros) { true } }
        assertThrows(IllegalArgumentException::class.java) { RecordPackageIo.read(Document(ByteArray(RecordPackages.MAX_ENVELOPE + 1))) { true } }
        val cancelled = Document(); assertThrows(RecordPackageCancelled::class.java) { RecordPackageIo.writeAndVerify(cancelled, encrypted) { false } }
        assertEquals(0, cancelled.writes)
        assertThrows(RecordPackageCancelled::class.java) { RecordPackageIo.read(cancelled) { false } }; assertEquals(0, cancelled.reads)
        var active = true
        val closeCancels = object : SelectedRecordPackageDocument {
            override fun openForRead(): InputStream { fail("Cancelled readback must not open"); return ByteArrayInputStream(byteArrayOf()) }
            override fun openForWrite(): OutputStream = object : ByteArrayOutputStream() { override fun close() { active = false; super.close() } }
        }
        assertThrows(RecordPackageCancelled::class.java) { RecordPackageIo.writeAndVerify(closeCancels, encrypted) { active } }
        encrypted.release()
    }
    @Test fun failureAtActualStateFlowCasCannotFailANewSameSessionPicker(): Unit = Harness().use { h ->
        h.restoreSelection()
        h.beforePublish = { candidate ->
            if (candidate.recordPackage?.phase == RecordPackagePhase.FAILED) {
                h.beforePublish = null
                val session = h.ui.session; h.controller.cancel(session); h.controller.chooseRestore(session)
            }
        }
        h.controller.authenticate(h.ui.session, "wrong independent password".toCharArray()); h.drain()
        assertEquals(RecordPackagePhase.PICKING_RESTORE, h.ui.phase)
        assertTrue(h.controller.claimPicker(h.ui.session, h.ui.requestKey!!)); assertEquals(0, h.imports)
    }
    @Test fun disposalBetweenCalculatedPreviewAndActualStateFlowCasRemovesPanelAndSecretScope(): Unit = Harness().use { h ->
        h.controller.open(); h.controller.selectExport(h.ui.session); h.controller.toggle(h.ui.session, ManualTaskSqlTest.LEGACY_ID, 1)
        h.beforePublish = { candidate ->
            if (candidate.recordPackage?.phase == RecordPackagePhase.EXPORT_PREVIEW) {
                h.beforePublish = null; h.controller.dispose()
            }
        }
        h.controller.prepare(h.ui.session); h.drain()
        assertNull(h.local.recordPackage); assertEquals(0, h.imports)
    }
    @Test fun disposalAfterClosedImportGlobalCandidateCannotPublishReadableDataThroughActualCas(): Unit = Harness().use { h ->
        h.restorePreview(); val session = h.ui.session
        h.fixture.db.beforeSql = { if (it.startsWith("INSERT INTO entries")) h.controller.close(session) }
        h.beforePublish = { candidate ->
            if (candidate.recordPackage == null && candidate.entries.size == 2 && candidate.ready) {
                h.beforePublish = null; h.controller.dispose()
            }
        }
        h.confirm(); h.drain(); h.fixture.db.beforeSql = null
        assertNull(h.local.recordPackage); assertFalse(h.local.ready); assertEquals(1, h.local.entries.size)
        assertEquals(2, h.fixture.db.read("SELECT id FROM entries").size) // Committed data is retained for a fresh owner.
    }
    internal class Document(initial: ByteArray = byteArrayOf()) : SelectedRecordPackageDocument {
        var bytes = initial.copyOf(); var reads = 0; var writes = 0
        var failClose = false; var badReadback = false
        override fun openForRead(): InputStream { reads++; return ByteArrayInputStream(if (badReadback) bytes + byteArrayOf(1) else bytes) }
        override fun openForWrite(): OutputStream {
            writes++; bytes = byteArrayOf()
            return object : ByteArrayOutputStream() {
                override fun close() { bytes = toByteArray(); super.close(); if (failClose) throw IOException("Synthetic document close failed") }
            }
        }
        override fun toString() = "SyntheticSelectedDocument(redacted)"
    }
    internal class Harness : AutoCloseable {
        val fixture = ManualTaskSqlTest.Fixture()
        val tags = SqlManualTagRepository(fixture.db, ManualTaskSqlTest.KEY)
        val packages = SqlRecordPackageRepository(fixture.db, tags)
        private val state = MutableStateFlow(LocalRecordUiState(ready = true, opening = false, text = "independent capture input",
            entries = listOf(StoredEntry(ManualTaskSqlTest.LEGACY_ID, 1, "合成原文", 5, "UTC", ProcessingPolicy.LOCAL_ONLY)),
            supportsRecordPackages = true))
        var local: LocalRecordUiState
            get() = state.value
            set(value) { state.value = value }
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        var imports = 0; var failReadback = false
        var beforePublish: ((LocalRecordUiState) -> Unit)? = null
        val store = object : EntryRepository {
            override val manualTags = tags
            override val originalExport = SqlOriginalExportRepository(fixture.db, tags)
            override val recordPackages = object : RecordPackageRepository {
                override fun restore(verified: VerifiedRecordPackage, newIds: List<String>, importedAtMillis: Long): RecordPackageRestoreResult {
                    imports++; return packages.restore(verified, newIds, importedAtMillis)
                }
                override fun origins() = packages.origins()
            }
            override suspend fun entries(): List<StoredEntry> {
                if (failReadback) { failReadback = false; error("Synthetic readback failure") }
                return fixture.db.read("SELECT e.id,e.currentRevision,r.originalText,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy FROM entries e JOIN entry_revisions r ON e.id=r.entryId AND e.currentRevision=r.revision WHERE NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1)").map {
                    StoredEntry(it[0] as String, (it[1] as Long).toInt(), it[2] as String, it[3] as Long, it[4] as String, ProcessingPolicy.valueOf(it[5] as String))
                }
            }
            override suspend fun readDraft(): TextDraft? = null
            override suspend fun updateDraft(text: String): TextDraft = error("unused")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("unused")
            override suspend fun revisions(entryId: String): List<OriginalRevision> = error("unused")
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("unused")
        }
        val controller = RecordPackageController({ local }, { change -> state.update { latest ->
            val candidate = change(latest); beforePublish?.invoke(candidate); candidate }
        }, { queue.add(it); true }, JcaRecordPackageCipher(), Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC))
        val ui get() = checkNotNull(local.recordPackage)
        fun drain() = runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(store) }
        fun exportSelection() {
            controller.open(); controller.selectExport(ui.session); controller.toggle(ui.session, ManualTaskSqlTest.LEGACY_ID, 1)
            controller.prepare(ui.session); drain()
        }
        fun restoreSelection() {
            controller.open(); controller.chooseRestore(ui.session)
            val key = ui.requestKey!!; assertTrue(controller.claimPicker(ui.session, key))
            controller.picked(ui.session, key, Document(RecordPackageCipherTest.fixtureBytes()))
        }
        fun restorePreview() { restoreSelection(); controller.authenticate(ui.session, RecordPackageCipherTest.fixturePassword()); drain() }
        fun confirm() { val v = ui.verified!!; controller.confirmRestore(ui.session, v.manifest.backupId, v.digest, ui.epoch) }
        override fun close() { controller.dispose(); tags.wipeKey(); fixture.close() }
    }
    companion object { const val PASSWORD = RecordPackageCipherTest.PASSWORD }
}
