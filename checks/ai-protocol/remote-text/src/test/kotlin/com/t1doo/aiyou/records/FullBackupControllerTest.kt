package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.*
import java.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Actual controller, serial queue, independently keyed SQL owners, cipher and streaming document IO. */
class FullBackupControllerTest {
    @Test fun fullExportAndDistinctAuthenticatedRestoreConfirmationUseActualProductionStorage():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(99).use { target ->
            source.image(source.entry());val export=Harness(source);export.controller.open();export.controller.fullHome(export.ui.session)
            export.controller.prepareFull(export.ui.session);export.drain();assertEquals(RecordPackagePhase.EXPORT_PREVIEW,export.ui.phase)
            val id=export.ui.fullSummary!!.backupId
            export.controller.encryptFull(export.ui.session,id,password(),password());assertTrue(export.queue.isEmpty())
            export.controller.acceptFullScope(export.ui.session,id,true)
            val password=password();export.controller.encryptFull(export.ui.session,id,password,password());assertTrue(password.all { it=='\u0000' })
            export.drain();val document=Document();export.pick(document);export.drain()
            assertEquals(RecordPackagePhase.DONE,export.ui.phase);assertTrue(document.maximumRead<=8192)
            val restore=Harness(target);restore.authenticate(document)
            assertTrue(target.db.read("SELECT * FROM entries").isEmpty());assertEquals(RecordPackagePhase.RESTORE_PREVIEW,restore.ui.phase)
            restore.confirm();assertTrue(target.db.read("SELECT * FROM entries").isEmpty());restore.drain()
            assertEquals(RecordPackagePhase.DONE,restore.ui.phase);assertEquals(1,restore.local.entries.size)
        } }
    }
    @Test fun closingDuringFinalCommitRefreshesActualRecordsWithoutReopeningRetiredPanel():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(99).use { target ->
            val id=source.entry();val restore=Harness(target);restore.authenticate(document(source));restore.confirm()
            target.checkpoint={ if(it=="full-before-commit") restore.controller.close(restore.ui.session) }
            restore.drain();assertNull(restore.local.recordPackage)
            assertEquals(listOf(id),target.db.read("SELECT id FROM entries").map { it[0] })
            assertEquals(listOf(id),restore.local.entries.map { it.id })
        } }
    }
    @Test fun failedImageSelectionAndUncertainComposerNeverLoseRetryIdentityToRestore():Unit=runBlocking {
        FullFixture().use { source ->
            source.entry();val document=document(source)
            for(kind in listOf("selection","uncertain","discard","confirmation","policy")) FullFixture(99).use { target ->
                val h=Harness(target);h.authenticate(document)
                val draft=target.capture.current()
                val selection=AttachmentSelection(1,FullBackupTest.uuid(),phase=AttachmentImportPhase.FAILED,sourcePrepared=true)
                h.local=h.local.copy(mixedCapture=MixedCaptureUiState(draft=draft,selection=if(kind=="selection") selection else null,
                    uncertainSave=kind=="uncertain",discardPending=kind=="discard",confirmDiscard=kind=="confirmation",
                    policyChange=if(kind=="policy") CapturePolicyChange(1,draft,ProcessingPolicy.AI_NONE) else null))
                val before=h.local.mixedCapture;h.confirm();assertTrue(kind,h.queue.isEmpty());assertSame(before,h.local.mixedCapture)
                assertTrue(target.db.read("SELECT * FROM entries").isEmpty())
            }
        }
    }
    @Test fun commitAcknowledgementFailureRetriesSameFullBackupAndRefreshesCommittedRecords():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(99).use { target ->
            val id=source.entry();val h=Harness(target);h.authenticate(document(source));h.confirm()
            target.checkpoint={if(it=="full-committed") error("synthetic lost ACK")}
            h.drain();assertEquals(RecordPackagePhase.UNCERTAIN,h.ui.phase)
            assertEquals(listOf(id),h.local.entries.map { it.id })
            target.checkpoint={};h.confirm();h.drain();assertEquals(RecordPackagePhase.DONE,h.ui.phase)
            assertEquals(1L,target.db.read("SELECT count(*) FROM entries").single().single())
        } }
    }
    @Test fun closedSessionDropsRetainedPickerAndConfirmationWithoutReadingOrWriting():Unit=runBlocking {
        FullFixture().use { source -> FullFixture(99).use { target ->
            source.entry();val h=Harness(target);h.controller.open();h.controller.fullHome(h.ui.session);h.controller.chooseRestore(h.ui.session)
            val session=h.ui.session;val key=h.ui.requestKey!!;assertTrue(h.controller.claimPicker(session,key))
            h.controller.close(session);val document=document(source);h.controller.picked(session,key,document)
            assertEquals(0,document.reads);assertTrue(h.queue.isEmpty())
            h.authenticate(document);val id=h.ui.fullSummary!!.backupId;val digest=h.ui.fullDigest!!;val epoch=h.ui.epoch;val next=h.ui.session
            h.controller.close(next);h.controller.restoreFull(next,id,digest,epoch)
            assertTrue(h.queue.isEmpty());assertTrue(target.db.read("SELECT * FROM entries").isEmpty())
        } }
    }
    @Test(timeout=20000) fun actualVmCloseDuringCommitAdoptsRestoredComposerButPreservesNewInput():Unit=runBlocking {
        for(newInput in listOf(false,true)) FullFixture().use { source -> FullFixture(99).use { target ->
            val id=source.entry();source.capture.edit(source.capture.current(),"合成恢复草稿")
            val tasks=SqlManualTaskRepository(source.gateway,source.key)
            tasks.create(ManualTaskCreate("task","合成恢复待办",20,"UTC","task-create"));tasks.wipeKey()
            val h=Harness(target);val closed=CountDownLatch(1)
            val owner=object:EntryRepository by h.store,AutoCloseable { override fun close() { closed.countDown() } }
            val vm=LocalRecordsViewModel(android.app.Application(),{owner},decodeCapture={object:AttachmentPicture {override val width=1;override val height=1}})
            try {
                withTimeout(5000) { vm.state.first { it.ready && it.supportsRecordPackages && it.mixedCapture?.draft!=null } }
                val c=vm.recordPackages;c.open();c.fullHome(vm.state.value.recordPackage!!.session);c.chooseRestore(vm.state.value.recordPackage!!.session)
                val picker=vm.state.value.recordPackage!!;assertTrue(c.claimPicker(picker.session,picker.requestKey!!));c.picked(picker.session,picker.requestKey,document(source))
                c.authenticate(picker.session,password())
                val preview=withTimeout(5000) { vm.state.first { it.recordPackage?.phase==RecordPackagePhase.RESTORE_PREVIEW } }.recordPackage!!
                target.checkpoint={ if(it=="full-before-commit") {
                    c.close(preview.session)
                    if(newInput) vm.mixedCapture.edit("返回后的新输入")
                } }
                c.restoreFull(preview.session,preview.fullSummary!!.backupId,preview.fullDigest!!,preview.epoch)
                withTimeout(5000) { vm.state.first { it.recordPackage==null && it.entries.any { e -> e.id==id } &&
                    (if(newInput) it.mixedCapture?.text=="返回后的新输入" else it.mixedCapture?.text=="合成恢复草稿") } }
                assertEquals("合成恢复草稿",target.capture.current().text)
                if(!newInput) {
                    withTimeout(5000) { vm.state.first { it.tasks.items.any { task -> task.id=="task" } } }
                    vm.mixedCapture.edit("合成恢复草稿继续编辑")
                    withTimeout(5000) { vm.state.first { it.mixedCapture?.let { u -> u.text=="合成恢复草稿继续编辑" && u.pendingEdits==0 }==true } }
                    assertEquals("合成恢复草稿继续编辑",target.capture.current().text)
                }
            } finally { vm.clearForHostTest();assertTrue(closed.await(3,TimeUnit.SECONDS)) }
        } }
    }
    private suspend fun document(source:FullFixture):Document {
        val snapshot=source.backup.capture(FullBackupTest.uuid(),100);val payload=source.backup.seal(snapshot,password());val out=ByteArrayOutputStream()
        try { payload.writeTo(out) { true };return Document(out.toByteArray()) } finally { payload.release();snapshot.release() }
    }
    private class Harness(val f:FullFixture) {
        var local=LocalRecordUiState(ready=true,opening=false,supportsRecordPackages=true,supportsFullBackup=true)
        val queue=ArrayDeque<suspend (EntryRepository)->Unit>()
        val store=object:EntryRepository {
            override val fullBackups get()=f.backup
            override val originalExport=SqlOriginalExportRepository(f.gateway,f.tags)
            override val manualTasks=SqlManualTaskRepository(f.gateway,f.key)
            override val recordPackages get()=f.packages
            override val manualTags get()=f.tags
            override val captureRecords get()=f.capture
            override val editDrafts get()=f.edits
            override suspend fun entries()=f.db.read("SELECT e.id,e.currentRevision,r.originalText,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision WHERE NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1)").map {
                StoredEntry(it[0] as String,(it[1] as Long).toInt(),it[2] as String,it[3] as Long,it[4] as String,ProcessingPolicy.valueOf(it[5] as String)) }
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("not used")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("not used")
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("not used")
        }
        val controller=RecordPackageController({local},{local=it(local)},{queue.add(it);true},JcaRecordPackageCipher(),Clock.systemUTC())
        val ui get()=checkNotNull(local.recordPackage)
        suspend fun drain() { while(queue.isNotEmpty()) queue.removeFirst()(store) }
        fun pick(document:Document) { val key=ui.requestKey!!;assertTrue(controller.claimPicker(ui.session,key));controller.picked(ui.session,key,document) }
        suspend fun authenticate(document:Document) {
            controller.open();controller.fullHome(ui.session);controller.chooseRestore(ui.session);pick(document)
            controller.authenticate(ui.session,password());drain();assertEquals(RecordPackagePhase.RESTORE_PREVIEW,ui.phase)
        }
        fun confirm() { controller.restoreFull(ui.session,ui.fullSummary!!.backupId,ui.fullDigest!!,ui.epoch) }
    }
    private class Document(var bytes:ByteArray=byteArrayOf()):SelectedRecordPackageDocument {
        var reads=0;var maximumRead=0
        override fun openForWrite()=object:ByteArrayOutputStream() { override fun close() { bytes=toByteArray() } }
        override fun openForRead():InputStream { reads++;return object:ByteArrayInputStream(bytes) {
            override fun read(b:ByteArray,off:Int,len:Int):Int { maximumRead=maxOf(maximumRead,len);return super.read(b,off,len) }
        } }
    }
    companion object { private fun password()=FullBackupTest.password() }
}
