package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.*
import java.time.Clock
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecordMediaPackageControllerTest {
    @Test fun explicitEditDraftScopeAuthenticatesThenRefreshesRecoveryListWithoutTouchingUnsavedCaptureInput(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id=source.entry();val draft=source.edits.write(null,source.draft(id));val export=Harness(source);val c=export.controller
            c.open();val session=export.ui.session;c.selectExport(session);c.toggle(session,id,1)
            assertFalse(export.ui.includeEditDrafts);assertFalse(export.ui.includeHistory)
            c.includeEditDrafts(session,true);assertTrue(export.ui.includeHistory);c.prepare(session);export.drain()
            assertEquals(listOf(draft),export.ui.mediaPreview!!.editDrafts);assertFalse(export.ui.scopeAccepted)
            c.includeEditDrafts(session,false);assertTrue(export.ui.includeEditDrafts)
            c.acceptScope(session,export.ui.snapshot!!.id,true);c.encrypt(session,export.ui.snapshot!!.id,PASSWORD.toCharArray(),PASSWORD.toCharArray());export.drain()
            val document=Document();val key=export.ui.requestKey!!;assertTrue(c.claimPicker(session,key));c.picked(session,key,document);export.drain()
            val restore=Harness(target);restore.authenticate(document)
            assertEquals(listOf(draft),restore.ui.verified!!.editDrafts);assertTrue(target.edits.pending().drafts.isEmpty());assertTrue(restore.local.entries.isEmpty())
            restore.confirm();restore.drain();assertEquals(RecordPackagePhase.DONE,restore.ui.phase)
            val ref=restore.local.pendingEdits.single();assertNotEquals(id,ref.entryId);assertNotEquals(draft.id,ref.id)
            assertEquals(ref.id,target.edits.read(ref.entryId)!!.id);assertEquals("合成当前未保存输入",restore.local.text)
            assertEquals("合成私密原文🙂",restore.local.entries.single().originalText)
            assertTrue(restore.ui.notice!!.contains("1 份独立记录编辑草稿"));assertTrue(restore.local.editDraftIndexAvailable)
        } }
    }

    @Test fun editDraftScopeCancelsWithHistoryAndRetiredPreparationCannotPublishOrExportInput(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id=f.entry();f.edits.write(null,f.draft(id));val h=Harness(f);val c=h.controller
            c.open();val session=h.ui.session;c.selectExport(session);c.toggle(session,id,1);c.includeEditDrafts(session,true)
            c.includeHistory(session,false);assertFalse(h.ui.includeEditDrafts);assertFalse(h.ui.includeHistory)
            c.includeEditDrafts(session,true);c.prepare(session);c.cancel(session);h.drain()
            assertEquals(RecordPackagePhase.CANCELLED,h.ui.phase);assertNull(h.ui.snapshot);assertEquals("合成当前未保存输入",h.local.text)
            assertEquals(1,f.edits.pending().drafts.size);assertTrue(f.db.read("SELECT id FROM record_package_receipts").isEmpty())
        }
    }

    @Test fun confirmedRestoreWithUnreadableDraftIndexKeepsImportedFilesAndShowsRetryInsteadOfEmptySuccess(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id=f.entry();f.edits.write(null,f.draft(id));val r=RecordEditDraftPackageTest.repo(f)
            val document=Document().also {it.bytes=RecordEditDraftPackageTest.export(r,r.previewEditDrafts(RecordEditDraftPackageTest.snapshot(f,id)))}
            val h=Harness(f);h.authenticate(document,RecordEditDraftPackageTest.password());h.failIndex=true;h.confirm();h.drain()
            assertEquals(RecordPackagePhase.DONE,h.ui.phase);assertFalse(h.local.editDraftIndexAvailable);assertNotNull(h.local.editDraftError)
            assertEquals(2,f.edits.pending().drafts.size);assertEquals("合成当前未保存输入",h.local.text)
        }
    }
    @Test fun actualControllerStreamsImagesAuthenticatesPreviewsThenCommitsOnlyAfterConfirmation(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
            source.media.save(source.attachment(id,png)) { png.inputStream() }
            val export = Harness(source); val c = export.controller
            c.open(); val session = export.ui.session; c.selectExport(session); c.toggle(session,id,1); c.prepare(session); export.drain()
            assertEquals(RecordPackagePhase.EXPORT_PREVIEW,export.ui.phase); assertEquals(1,export.ui.mediaPreview!!.images.size)
            c.acceptScope(session,export.ui.snapshot!!.id,true)
            c.encrypt(session,export.ui.snapshot!!.id,PASSWORD.toCharArray(),PASSWORD.toCharArray()); export.drain()
            val document = Document(); val key = export.ui.requestKey!!; assertTrue(c.claimPicker(session,key)); c.picked(session,key,document); export.drain()
            assertEquals(RecordPackagePhase.DONE,export.ui.phase); assertEquals("AIYOUPK2",document.bytes.copyOf(8).toString(Charsets.US_ASCII))
            assertTrue(document.maxRead <= 8192)
            val restore = Harness(target); val r = restore.controller
            r.open(); val rs = restore.ui.session; r.chooseRestore(rs); val rk = restore.ui.requestKey!!; assertTrue(r.claimPicker(rs,rk)); r.picked(rs,rk,document)
            r.authenticate(rs,PASSWORD.toCharArray()); restore.drain()
            assertEquals(RecordPackagePhase.RESTORE_PREVIEW,restore.ui.phase); assertEquals(1,restore.ui.verified!!.images.size)
            assertTrue(target.db.read("SELECT id FROM entries").isEmpty())
            val pack = restore.ui.verified!!; r.confirmRestore(rs,pack.manifest.backupId,pack.digest,restore.ui.epoch); restore.drain()
            assertEquals(RecordPackagePhase.DONE,restore.ui.phase)
            val newId = restore.local.entries.single().id; assertNotEquals(id,newId); assertEquals(ProcessingPolicy.AI_NONE,restore.local.entries.single().policy)
            assertArrayEquals(png,target.media.read(target.media.list(newId,1).single().intent.id))
        } }
    }
    private class Harness(val f: PermanentErasureFixture) {
        private fun records() = f.db.read("SELECT e.id,e.currentRevision,r.originalText,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision").map {
            StoredEntry(it[0] as String,(it[1] as Long).toInt(),it[2] as String,it[3] as Long,it[4] as String,ProcessingPolicy.valueOf(it[5] as String))
        }
        var local = LocalRecordUiState(ready=true,opening=false,supportsRecordPackages=true,entries=records(),text="合成当前未保存输入",supportsEditDrafts=true)
        var failIndex=false
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        val store = object : EntryRepository {
            override val originalExport = SqlOriginalExportRepository(f.gateway,f.tags)
            override val mediaPackages = SqlRecordMediaPackageRepository(f.gateway,originalExport,f.media,f.packages,edits=f.edits)
            override val editDrafts = object : OriginalEditDraftStore by f.edits {
                override suspend fun pending():OriginalEditDraftIndex {if(failIndex) error("Synthetic directory read failure");return f.edits.pending()}
            }
            override val recordPackages get() = f.packages
            override val manualTags get() = f.tags
            override val attachments get() = f.media
            override suspend fun entries() = records()
            override suspend fun readDraft(): TextDraft? = null
            override suspend fun updateDraft(text: String): TextDraft = error("Unrelated capture")
            override suspend fun saveDraft(expected: TextDraft,capturedAtMillis: Long,captureTimeZone: String): StoredEntry = error("Unrelated capture")
            override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
            override suspend fun revise(entryId: String,expectedRevision: Int,text: String,createdAtMillis: Long): StoredEntry = error("Unrelated edit")
        }
        val controller = RecordPackageController({ local },{ local=it(local) },{ queue.add(it); true },JcaRecordPackageCipher(),Clock.systemUTC())
        val ui get() = checkNotNull(local.recordPackage)
        suspend fun drain() { while (queue.isNotEmpty()) queue.removeFirst()(store) }
        suspend fun authenticate(document:Document,password:CharArray=PASSWORD.toCharArray()) {
            controller.open();controller.chooseRestore(ui.session);val key=ui.requestKey!!
            assertTrue(controller.claimPicker(ui.session,key));controller.picked(ui.session,key,document)
            controller.authenticate(ui.session,password);drain();assertEquals(RecordPackagePhase.RESTORE_PREVIEW,ui.phase)
        }
        fun confirm() {val pack=ui.verified!!;controller.confirmRestore(ui.session,pack.manifest.backupId,pack.digest,ui.epoch)}
    }
    private class Document : SelectedRecordPackageDocument {
        var bytes = byteArrayOf(); var maxRead = 0
        override fun openForWrite() = object : ByteArrayOutputStream() { override fun close() { bytes = toByteArray() } }
        override fun openForRead() = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray,off: Int,len: Int): Int { maxRead=maxOf(maxRead,len); return super.read(b,off,len) }
        }
    }
    companion object { private const val PASSWORD = "synthetic media controller password" }
}
