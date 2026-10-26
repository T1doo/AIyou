package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Real controllers and v11 SQL; metadata read failures occur after acknowledged original writes. */
class RecordEventDateIntegrationControllerTest {
    @Test(timeout=20_000) fun rejectedEventDateCannotCommitRetainedPreviewAndExplicitCorrectionWritesOnlyNewChoice(): Unit = runBlocking {
        for (badDate in listOf(true, false)) Harness().use { h ->
            val id = h.seed(); h.refresh(); val entries = h.local.entries
            val controller = RecordEventDateController({h.local},{h.local=it(h.local)},{h.queue.addLast(it);true})
            try {
                controller.open(id,1); h.drain(); val session = h.local.eventDateEditor!!.session
                controller.edit(session,"2024-01-01","UTC"); controller.preview(session)
                val old = checkNotNull(h.local.eventDateEditor!!.preview).id
                controller.edit(session,if(badDate)"2024-01-010" else "2024-01-01",if(badDate)"UTC" else "x".repeat(129))
                controller.confirm(session,old); h.drain()
                assertNull(h.dates.read(id).date); assertEquals(0L,h.dates.read(id).version)
                controller.preview(session); assertNull(h.local.eventDateEditor!!.preview); assertTrue(h.queue.isEmpty())
                controller.edit(session,date="",zone=""); controller.edit(session,DAY.localDate,DAY.timeZone)
                controller.preview(session); controller.confirm(session,checkNotNull(h.local.eventDateEditor!!.preview).id); h.drain()
                assertEquals(DAY,h.dates.read(id).date); assertEquals(1L,h.dates.read(id).version)
                assertEquals(entries,h.rows()); assertEquals("separate private input",h.local.text)
                assertTrue(h.local.eventDateWrites.isEmpty()); assertNotNull(h.local.eventDateEditor!!.notice)
            } finally {controller.dispose()}
        }
    }

    @Test(timeout=20_000) fun mixedCaptureRefreshesUnknownEventDateAndMetadataReadFailureCannotUndoCommittedSave(): Unit = runBlocking {
        for (failRead in listOf(false,true)) Harness().use { h ->
            val capture=MixedCaptureController({h.local},{h.local=it(h.local)},{h.queue.addLast(it);true},
                {object:AttachmentPicture{override val width=1;override val height=1}},CLOCK)
            try {
                capture.initialize(h.store);capture.edit("合成图文控制器保存的原文");h.drain()
                h.failRead=failRead;capture.save();h.drain()
                val saved=h.local.mixedCapture!!;val entry=h.local.entries.single()
                assertNotNull(saved.notice);assertNull(saved.error);assertFalse(saved.uncertainSave);assertFalse(saved.busy)
                assertEquals("合成图文控制器保存的原文",entry.originalText)
                assertEquals(1,h.f.db.read("SELECT id FROM entries").size)
                assertEquals("separate private input",h.local.text)
                if(failRead){assertFalse(h.local.eventDatesAvailable);assertNotNull(h.local.eventDatesError)}
                else {assertTrue(h.local.eventDatesAvailable);assertEquals(0L,h.local.eventDates.getValue(entry.id).version);assertNull(h.local.eventDates.getValue(entry.id).date)}
            } finally {capture.dispose();capture.disposeOnIo()}
        }
    }

    @Test(timeout=30_000) fun encryptedRestorePublishesEventDateAndOptionalReadFailureKeepsDoneWithoutDuplicateCopies(): Unit = runBlocking {
        for (failRead in listOf(false,true)) Harness().use { h ->
            val id=h.seed();h.dates.set(h.intent(id,DAY));h.refresh()
            val snapshot=h.originals.capture(listOf(SourceVersion(id,1)),uuid(),30)
            val prepared=h.media.seal(h.media.preview(snapshot),password())
            val bytes=try {ByteArrayOutputStream().use{out->prepared.writeTo(out){true};out.toByteArray()}}finally{prepared.release()}
            val controller=RecordPackageController({h.local},{h.local=it(h.local)},{h.queue.addLast(it);true},JcaRecordPackageCipher(),CLOCK)
            try {
                controller.open();var ui=h.local.recordPackage!!;controller.chooseRestore(ui.session);ui=h.local.recordPackage!!
                val key=ui.requestKey!!;assertTrue(controller.claimPicker(ui.session,key))
                controller.picked(ui.session,key,RecordPackageControllerTest.Document(bytes));controller.authenticate(ui.session,password());h.drain()
                ui=h.local.recordPackage!!;assertEquals(RecordPackagePhase.RESTORE_PREVIEW,ui.phase)
                val verified=ui.verified!!;h.failRead=failRead
                controller.confirmRestore(ui.session,verified.manifest.backupId,verified.digest,ui.epoch);h.drain()
                assertEquals(RecordPackagePhase.DONE,h.local.recordPackage!!.phase)
                assertNull(h.local.recordPackage!!.error);assertEquals(2,h.local.entries.size)
                assertEquals(2,h.f.db.read("SELECT id FROM entries").size)
                val restored=h.local.entries.single{it.id!=id};assertEquals(ProcessingPolicy.AI_NONE,restored.policy)
                assertEquals(DAY,h.dates.read(restored.id).date);assertEquals("separate private input",h.local.text)
                if(failRead){assertFalse(h.local.eventDatesAvailable);assertNotNull(h.local.eventDatesError)}
                else {assertTrue(h.local.eventDatesAvailable);assertEquals(DAY,h.local.eventDates.getValue(restored.id).date)}
                controller.confirmRestore(ui.session,verified.manifest.backupId,verified.digest,ui.epoch);h.drain()
                assertEquals(2,h.f.db.read("SELECT id FROM entries").size)
            } finally {controller.dispose();bytes.fill(0)}
        }
    }

    @Test(timeout=20_000) fun trashRestoreRefreshesDateAndPermanentErasureRemovesOnlyTargetPendingAndEditor(): Unit = runBlocking {
        for (targetEditor in listOf(false,true)) Harness().use { h ->
            val id=h.seed();val other=h.seed();h.dates.set(h.intent(id,DAY));h.dates.set(h.intent(other,DAY));h.refresh()
            val targetPending=RecordEventDateWrite(91,h.intent(id,null),busy=false,error="synthetic unknown",attempted=true)
            val otherPending=RecordEventDateWrite(92,h.intent(other,null),busy=false,error="synthetic unknown",attempted=true)
            h.local=h.local.copy(eventDateWrites=mapOf(id to targetPending,other to otherPending))
            val controller=RecycleBinController({h.local},{h.local=it(h.local)},{h.queue.addLast(it);true})
            try {
                fun move(){controller.requestMove(id,1);h.drain();val ui=h.local.recycleBin!!;controller.confirm(ui.session,ui.confirmation!!.id);h.drain()}
                move();assertSame(targetPending,h.local.eventDateWrites[id]);assertSame(otherPending,h.local.eventDateWrites[other])
                assertFalse(h.local.eventDates.containsKey(id));assertEquals(DAY,h.local.eventDates.getValue(other).date)
                controller.open();h.drain();var ui=h.local.recycleBin!!;controller.select(ui.session,id)
                controller.requestRestore(ui.session,id,ui.records.single{it.entry.id==id}.generation)
                ui=h.local.recycleBin!!;h.failRead=true;controller.confirm(ui.session,ui.confirmation!!.id);h.drain();h.failRead=false
                assertNotNull(h.local.recycleBin!!.notice);assertNull(h.local.recycleBin!!.confirmation)
                assertFalse(h.local.eventDatesAvailable);assertNotNull(h.local.eventDatesError)
                assertTrue(h.local.entries.any{it.id==id});assertEquals(DAY,h.dates.read(id).date)
                assertSame(targetPending,h.local.eventDateWrites[id]);assertSame(otherPending,h.local.eventDateWrites[other])
                controller.close(h.local.recycleBin!!.session);move();controller.open();h.drain()
                ui=h.local.recycleBin!!;controller.select(ui.session,id)
                controller.requestErasure(ui.session,id,ui.records.single{it.entry.id==id}.generation)
                val editorId=if(targetEditor)id else other
                val editorIntent=if(targetEditor)targetPending.intent else otherPending.intent
                val editor=RecordEventDateEditor(81,editorId,1,editorIntent.sourceHash,editorIntent.sourceGeneration,busy=false)
                h.local=h.local.copy(eventDateEditor=editor)
                ui=h.local.recycleBin!!;controller.confirm(ui.session,ui.confirmation!!.id);h.drain()
                assertTrue(h.f.erase.read(id)!!.complete);assertFalse(h.local.eventDateWrites.containsKey(id))
                assertSame(otherPending,h.local.eventDateWrites[other]);assertFalse(h.local.eventDates.containsKey(id))
                assertEquals(DAY,h.local.eventDates.getValue(other).date)
                if(targetEditor)assertNull(h.local.eventDateEditor)else assertSame(editor,h.local.eventDateEditor)
                assertTrue(h.f.db.read("SELECT entryId FROM entry_event_dates WHERE entryId=?",id).isEmpty())
                assertEquals(listOf(other),h.local.entries.map{it.id})
            } finally {controller.dispose()}
        }
    }

    @Test(timeout=15_000) fun actualReadOnlySearchRefreshPreservesUnknownIntentAndUnconfirmedPreview(): Unit = runBlocking {
        Harness().use { h ->
            val id=h.seed();val closed=CountDownLatch(1)
            val store=object:EntryRepository by h.store,AutoCloseable{override fun close(){closed.countDown()}}
            val vm=LocalRecordsViewModel(android.app.Application(),{store})
            try {
                withTimeout(5000){vm.state.first{it.ready&&it.eventDatesAvailable}}
                vm.eventDates.open(id,1)
                val loaded=withTimeout(5000){vm.state.first{it.eventDateEditor?.snapshot!=null&&it.eventDateEditor?.busy==false}}
                val session=loaded.eventDateEditor!!.session
                vm.eventDates.edit(session,DAY.localDate,DAY.timeZone);vm.eventDates.preview(session)
                h.loseAck=true;vm.eventDates.confirm(session,vm.state.value.eventDateEditor!!.preview!!.id)
                val unknown=withTimeout(5000){vm.state.first{it.eventDateWrites[id]?.busy==false}}
                val intent=unknown.eventDateWrites.getValue(id).intent
                vm.eventDates.close(session);val before=vm.state.value.entries;vm.refreshLocalSearch()
                val reread=withTimeout(5000){vm.state.first{it.entries!==before&&it.eventDatesAvailable}}
                assertSame(intent,reread.eventDateWrites.getValue(id).intent);assertEquals(DAY,reread.eventDates.getValue(id).date)
                vm.eventDates.open(id,1)
                var opened=withTimeout(5000){vm.state.first{it.eventDateEditor?.snapshot!=null&&it.eventDateEditor?.busy==false}}
                val retrySession=opened.eventDateEditor!!.session
                vm.eventDates.edit(retrySession,"2025-01-01","UTC");assertEquals(DAY.localDate,vm.state.value.eventDateEditor!!.dateInput)
                vm.eventDates.retry(retrySession)
                withTimeout(5000){vm.state.first{it.eventDateWrites.isEmpty()&&it.eventDateEditor?.notice!=null}}
                vm.eventDates.edit(retrySession,"2025-01-01","UTC");vm.eventDates.preview(retrySession)
                val preview=vm.state.value.eventDateEditor!!.preview!!;val oldEntries=vm.state.value.entries
                vm.refreshLocalSearch();opened=withTimeout(5000){vm.state.first{it.entries!==oldEntries}}
                assertSame(preview,opened.eventDateEditor!!.preview);assertEquals(DAY,h.dates.read(id).date)
                assertEquals(1L,h.dates.read(id).version)
            } finally {vm.clearForHostTest();assertTrue(closed.await(3,TimeUnit.SECONDS))}
        }
    }

    private class Harness:AutoCloseable {
        val f=PermanentErasureFixture()
        init {f.db.transaction{sql->
            RecordFavoriteSqlSchema.migration8To9.forEach{sql.execute(it)}
            LocalCandidateSqlSchema.migration9To10.forEach{sql.execute(it)}
            RecordEventDateSqlSchema.migration10To11.forEach{sql.execute(it)};sql.execute("PRAGMA user_version=11")
        }}
        val dates=SqlRecordEventDateRepository(f.gateway)
        val originals=SqlOriginalExportRepository(f.gateway,f.tags)
        val media=SqlRecordMediaPackageRepository(f.gateway,originals,f.media,f.packages)
        val search=SqlLocalSearchRepository(f.gateway,f.tags)
        var failRead=false;var loseAck=false
        var local=LocalRecordUiState(ready=true,opening=false,text="separate private input",supportsEventDates=true,
            eventDatesAvailable=true,supportsMixedCapture=true,mixedCapture=MixedCaptureUiState(),
            supportsRecordPackages=true,supportsRecycleBin=true,supportsPermanentErasure=true)
        val queue=ArrayDeque<suspend(EntryRepository)->Unit>()
        val store=object:EntryRepository {
            override val localProcessing:LocalProcessingRepository=SqlLocalProcessingRepository(f.gateway)
            override val eventDates=object:RecordEventDateRepository by dates {
                override fun snapshots():Map<String,RecordEventDateSnapshot>{if(failRead)throw RecordEventDateStorageFailure();return dates.snapshots()}
                override fun set(mutation:RecordEventDateMutation):RecordEventDateSnapshot {
                    val saved=dates.set(mutation);if(loseAck){loseAck=false;throw RecordEventDateStorageFailure()};return saved
                }
            }
            override val captureRecords=SqlCaptureRepository(f.gateway,f.media,f.tags)
            override val manualTags=f.tags
            override val originalExport=originals
            override val recordPackages=f.packages
            override val mediaPackages=media
            override val recycleBin=f.bin
            override val permanentErasure=f.erase
            override val editDrafts=f.edits
            override val localSearch=search
            override suspend fun entries()=rows()
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("Outside integration contract")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("Outside integration contract")
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("Outside integration contract")
        }
        fun rows()=f.db.read("SELECT e.id,e.currentRevision,r.originalText,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision WHERE NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1) ORDER BY e.id").map {
            StoredEntry(it[0] as String,(it[1] as Long).toInt(),it[2] as String,it[3] as Long,it[4] as String,ProcessingPolicy.valueOf(it[5] as String))
        }
        fun seed():String {val id=uuid();val text="合成事件日期集成原文"
            f.db.transaction{sql->
                sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,5,'UTC','LOCAL_ONLY')",listOf(id))
                sql.execute("INSERT INTO entry_revisions(entryId,revision,originalText,contentHash,createdAtMillis) VALUES(?,1,?,?,5)",listOf(id,text,OriginalExports.hash(text)))
                sql.execute("INSERT INTO remote_source_access(entryId,generation,deleted,connection,consent) VALUES(?,1,0,NULL,NULL)",listOf(id))
                sql.execute("INSERT INTO local_jobs(id,entryId,sourceRevision,operation,state) VALUES(?,?,1,'LOCAL_INDEX','PENDING')",listOf("INDEX:"+id,id))
            };return id
        }
        fun intent(id:String,date:RecordEventDate?):RecordEventDateMutation {
            val row=f.db.read("SELECT r.contentHash FROM entry_revisions r WHERE r.entryId=? AND r.revision=1",id).single().single() as String
            val version=f.db.read("SELECT version FROM entry_event_dates WHERE entryId=?",id).singleOrNull()?.single() as Long? ?: 0L
            return RecordEventDateMutation(id,1,row,version,date,uuid(),SqlLocalProcessingRepository(f.gateway).versions().getValue(id).generation)
        }
        fun refresh(){local=local.copy(entries=rows(),eventDates=dates.snapshots(),eventDatesAvailable=true,localSearch=search,localSearchEpoch=search.epoch(),localProcessingVersions=SqlLocalProcessingRepository(f.gateway).versions())}
        fun drain()=runBlocking{while(queue.isNotEmpty())queue.removeFirst()(store)}
        override fun close(){f.close()}
    }
    companion object {
        private val DAY=RecordEventDate("2024-02-29","Asia/Shanghai")
        private val CLOCK=Clock.fixed(Instant.ofEpochMilli(50),ZoneOffset.UTC)
        private fun uuid()=UUID.randomUUID().toString()
        private fun password()="synthetic integration password 42".toCharArray()
    }
}
