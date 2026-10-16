package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TaskProposalViewModelTest {
    @Test(timeout=15_000) fun otherOwnerUpdateAndConfirmationRevokeOldDraftRecoveryReceiptWithoutChangingInputsOrCas():Unit=Harness().use {h ->runBlocking {
        h.ready();val opened=h.open();h.vm.taskProposals.editTitle(opened.session,"合成当前会话输入");h.saved()
        h.vm.taskProposals.prepare(opened.session);val before=h.editor();val p1=before.saved!!
        assertEquals(TaskProposalSaveState.SAVED,h.vm.state.value.proposalSaveState(before))
        // Same ID and version are insufficient when a complete saved snapshot differs.
        val changedDate=h.vm.state.value.copy(taskProposals=h.vm.state.value.taskProposals.copy(items=listOf(
            TaskProposalItem(p1.copy(dateInput=ManualTaskDateInput("2026-11-20")),true))))
        assertEquals(TaskProposalSaveState.CHANGED,changedDate.proposalSaveState(before))
        val other=SqlTaskProposalRepository(h.f.gateway,LocalCandidateFixture.KEY,h.tasks)
        try {
            val p2=other.write(TaskProposalWrite(p1.id,p1.version,p1.binding,"合成另一owner的新输入",ManualTaskDateInput("2026-10-20"),
                p1.createdAtMillis,p1.creationTimeZone,110,"other-edit"))
            h.vm.taskProposals.refresh();h.barrier()
            val changed=h.editor();assertEquals(TaskProposalSaveState.CHANGED,h.vm.state.value.proposalSaveState(changed))
            assertEquals(before.title,changed.title);assertEquals(before.date,changed.date);assertEquals(p1,changed.saved)
            h.vm.taskProposals.confirm(changed.session,before.confirmation!!);assertTrue(h.confirms.isEmpty())
            val writes=h.writes.size;h.vm.taskProposals.close(changed.session);h.closed();h.barrier()
            assertEquals(writes,h.writes.size);assertEquals(p2,other.list().single().proposal)
            h.vm.taskProposals.openExisting(p2.id,p2.version);h.vm.taskProposals.prepare(h.editor().session)
            val reopened=h.editor();val retained=reopened.confirmation!!
            other.confirm(TaskProposalConfirm(p2,120,"other-confirm"));h.vm.taskProposals.refresh();h.barrier()
            val missing=h.editor();assertEquals(TaskProposalSaveState.MISSING,h.vm.state.value.proposalSaveState(missing))
            assertEquals(reopened.title,missing.title);assertEquals(reopened.date,missing.date)
            assertEquals(missing.epoch,missing.savedEpoch);assertNull(missing.error);assertNull(missing.notice)
            h.vm.taskProposals.confirm(missing.session,retained);assertTrue(h.confirms.isEmpty());assertEquals(1,h.tasks.snapshots().size)
            // A stale explicit rejection still meets the full SQL version/snapshot CAS.
            h.vm.taskProposals.reject(missing.session);h.ui {it.conflict&&!it.busy}
            assertEquals(reopened.title,h.editor().title);assertTrue(other.list().isEmpty());assertEquals(1,h.tasks.snapshots().size);h.noAi()
        } finally {other.wipeKey()}
    }}
    @Test(timeout=15_000) fun actualPermanentErasureRevokesControllerRecoveryReceiptEvenBeforeEntriesCacheRefresh():Unit=PermanentErasureFixture().use {f ->runBlocking {
        val text="合成原文与待确认输入";val id=f.entry(text=text)
        val access=SqlLocalProcessingRepository(f.gateway).read(SourceVersion(id,1))
        val binding=TaskSourceBinding(access.source,access.sourceHash,access.generation,0,5)
        val port=SqlTaskProposalRepository(f.gateway,PermanentErasureFixture.KEY,f.tasks)
        var local=LocalRecordUiState(ready=true,opening=false,entries=listOf(StoredEntry(id,1,text,5,"UTC",ProcessingPolicy.LOCAL_ONLY)),
            supportsLocalProcessing=true,localProcessingVersions=mapOf(id to LocalProcessingVersion(access.source,access.generation)))
        val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
        val owner=object:EntryRepository {
            override val taskProposals=port
            override val manualTasks=f.tasks
            override suspend fun readDraft()=null
            override suspend fun updateDraft(text:String)=error("Not requested")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("Not requested")
            override suspend fun entries()=local.entries
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("Not requested")
        }
        suspend fun drain() {while(true) (queue.poll()?:break)(owner)}
        val controller=TaskProposalController({local},{local=it(local)},{queue.add(it);true},Clock.fixed(Instant.ofEpochMilli(200),ZoneOffset.UTC))
        try {
            controller.initialize(owner);assertTrue(controller.open(binding));drain()
            val session=local.taskProposals.editor!!.session
            controller.editTitle(session,"合成保留在会话的输入");controller.editDate(session,ManualTaskDateInput("2026-10-22"));drain()
            controller.prepare(session);val before=local.taskProposals.editor!!
            assertEquals(TaskProposalSaveState.SAVED,local.proposalSaveState(before))
            val erase=SqlPermanentErasureRepository(f.gateway,f.media,f.edits,f.packages,port::readAllWithin)
            assertTrue(erase.erase(f.intent(id)).complete);controller.refresh();drain()
            val after=local.taskProposals.editor!!
            assertTrue(local.canUseTaskSource(binding));assertEquals(TaskProposalSaveState.MISSING,local.proposalSaveState(after))
            assertEquals(before.title,after.title);assertEquals(before.date,after.date);assertEquals(before.saved,after.saved)
            assertEquals(after.epoch,after.savedEpoch);assertNull(after.error);assertNull(after.notice)
            controller.confirm(session,before.confirmation!!);drain()
            assertTrue(f.tasks.snapshots().isEmpty());assertTrue(port.list().isEmpty())
            assertTrue(f.db.read("SELECT id FROM entries WHERE id=?",id).isEmpty())
        } finally {controller.dispose();port.wipeKey()}
    }}
    @Test(timeout=15_000) fun freshMetadataRevocationAndReadFailureHideAuthorityBeforeEntriesRefreshAndCannotReviveRetiredForm():Unit=Harness().use {h ->runBlocking {
        h.ready();val e=h.open();h.vm.taskProposals.editTitle(e.session,"合成授权待核对草稿");h.saved()
        h.vm.taskProposals.prepare(e.session);val old=h.editor().confirmation!!
        assertTrue(h.vm.state.value.canUseTaskSource(e.binding));assertTrue(h.vm.state.value.canUseProposalSource(h.editor()))
        h.unavailableSource=true;h.vm.taskProposals.refresh();h.barrier()
        assertTrue(h.vm.state.value.canUseTaskSource(e.binding));assertFalse(h.vm.state.value.canUseProposalSource(h.editor()))
        assertEquals(TaskProposalSaveState.SAVED_SOURCE_UNAVAILABLE,h.vm.state.value.proposalSaveState(h.editor()))
        h.vm.taskProposals.confirm(e.session,old);assertTrue(h.confirms.isEmpty())
        h.unavailableSource=false;h.failList=true;h.vm.taskProposals.refresh();h.barrier()
        assertFalse(h.vm.state.value.canUseProposalSource(h.editor()));h.vm.taskProposals.confirm(e.session,old)
        assertEquals(TaskProposalSaveState.UNKNOWN,h.vm.state.value.proposalSaveState(h.editor()))
        assertTrue(h.confirms.isEmpty());h.failList=false
        h.vm.taskProposals.invalidateSource("source");h.vm.taskProposals.refresh();h.barrier()
        assertTrue(h.vm.state.value.taskProposals.available);assertFalse(h.vm.state.value.canUseProposalSource(h.editor()))
        assertNull(h.editor().confirmation);assertEquals("合成授权待核对草稿",h.editor().title);assertTrue(h.tasks.snapshots().isEmpty());h.noAi()
    }}
    @Test(timeout=15_000) fun unfinishedAiNoneDraftAutosavesAndColdRestoresWithoutFormalTask():Unit=Harness().use {h ->runBlocking {
        h.ready();val e=h.open();assertEquals("",e.title);assertTrue(h.tasks.snapshots().isEmpty())
        h.vm.taskProposals.editTitle(e.session,"合成明天三点只是引用");h.vm.taskProposals.editDate(e.session,ManualTaskDateInput("2026-"))
        val saved=h.saved();val id=saved.id;h.cold();h.ready()
        val p=h.vm.state.value.taskProposals.items.single().proposal
        assertEquals(id,p.id);assertEquals("合成明天三点只是引用",p.titleInput);assertEquals("2026-",p.dateInput.date)
        h.vm.taskProposals.openExisting(p.id,p.version);val restored=h.editor();h.vm.taskProposals.prepare(restored.session)
        assertNull(h.editor().confirmation);assertNotNull(h.editor().error);assertTrue(h.tasks.snapshots().isEmpty());h.noAi()
    }}
    @Test(timeout=15_000) fun coalescedEditsAndCloseDrainLatestEncryptedSnapshot():Unit=Harness().use {h ->runBlocking {
        h.ready();val e=h.open();h.block=true
        h.vm.taskProposals.editTitle(e.session,"合成第一输入");assertTrue(h.entered.await(2,TimeUnit.SECONDS))
        h.vm.taskProposals.editTitle(e.session,"合成最新输入");h.vm.taskProposals.editDate(e.session,ManualTaskDateInput("2026-10-20"))
        h.vm.taskProposals.close(e.session);assertTrue(h.editor().closing);assertNotNull(h.vm.state.value.taskProposals.editor)
        h.release.countDown();h.closed();h.cold();h.ready()
        val p=h.vm.state.value.taskProposals.items.single().proposal
        assertEquals("合成最新输入",p.titleInput);assertEquals("2026-10-20",p.dateInput.date)
        assertTrue(h.tasks.snapshots().isEmpty());h.noAi()
    }}
    @Test(timeout=15_000) fun closeWhileSavingReadbackFailureReleasesClosingAndRetriesExactPending():Unit=Harness().use {h ->runBlocking {
        h.ready();val e=h.open();h.block=true
        h.vm.taskProposals.editTitle(e.session,"合成关闭时保存");assertTrue(h.entered.await(2,TimeUnit.SECONDS))
        h.failList=true;h.vm.taskProposals.close(e.session);h.release.countDown()
        val failed=h.ui {it.error!=null&&!it.busy};assertFalse(failed.closing);assertNotNull(failed.pending)
        val pending=failed.pending;h.failList=false;h.vm.taskProposals.retry(e.session);h.saved()
        assertSame((pending as ProposalPending.Write).request,h.writes.last())
        h.vm.taskProposals.close(e.session);h.closed();h.cold();h.ready()
        assertEquals("合成关闭时保存",h.vm.state.value.taskProposals.items.single().proposal.titleInput);h.noAi()
    }}
    @Test(timeout=15_000) fun explicitConfirmationCancelDoubleClickAndSameExcerptIndependentIntents():Unit=Harness().use {h ->runBlocking {
        h.ready();val e=h.open();h.vm.taskProposals.editTitle(e.session,"合成第一人工意图");h.saved()
        h.vm.taskProposals.prepare(e.session);val old=h.editor().confirmation!!
        h.vm.taskProposals.cancel(e.session,old);assertTrue(h.tasks.snapshots().isEmpty())
        h.vm.taskProposals.prepare(e.session);val fresh=h.editor().confirmation!!
        h.vm.taskProposals.confirm(e.session,old);assertTrue(h.confirms.isEmpty())
        h.vm.taskProposals.confirm(e.session,fresh);h.vm.taskProposals.confirm(e.session,fresh);h.ui {it.notice!=null&&!it.busy}
        assertEquals(1,h.confirms.size);assertNull(h.tasks.snapshots().single().schedule)
        h.vm.taskProposals.close(e.session);h.closed();val other=h.open()
        assertNotEquals(e.id,other.id);assertEquals(e.binding,other.binding)
        h.vm.taskProposals.editTitle(other.session,"合成第二人工意图");h.saved();h.vm.taskProposals.prepare(other.session)
        h.vm.taskProposals.confirm(other.session,h.editor().confirmation!!);h.ui {it.notice!=null&&!it.busy}
        assertEquals(2,h.tasks.snapshots().size);assertTrue(h.port.list().isEmpty());h.noAi()
    }}
    @Test(timeout=15_000) fun lostConfirmationAckHasNoSuccessRetriesSameRequestAndColdListDoesNotDuplicate():Unit=Harness().use {h ->runBlocking {
        h.ready();val e=h.open();h.vm.taskProposals.editTitle(e.session,"合成确认ACK丢失");h.saved()
        h.vm.taskProposals.prepare(e.session);h.loseConfirmAck=true
        h.vm.taskProposals.confirm(e.session,h.editor().confirmation!!)
        val failed=h.ui {it.error!=null&&!it.busy};assertNull(failed.notice);assertNotNull(failed.pending)
        assertEquals(1,h.tasks.snapshots().size);assertTrue(h.port.list().isEmpty())
        h.vm.taskProposals.retry(e.session);h.ui {it.notice!=null&&!it.busy}
        assertSame(h.confirms[0],h.confirms[1]);assertEquals(1L,h.tasks.snapshots().single().version)
        h.cold();h.ready();assertTrue(h.vm.state.value.taskProposals.items.isEmpty());assertEquals(1,h.vm.state.value.tasks.items.size);h.noAi()
    }}
    @Test(timeout=15_000) fun sourceChangeRetiresOldConfirmationAndDraftCanBeExplicitlyRejected():Unit=Harness().use {h ->runBlocking {
        h.ready();val e=h.open();h.vm.taskProposals.editTitle(e.session,"合成失效来源草稿");h.saved()
        h.vm.taskProposals.prepare(e.session);val old=h.editor().confirmation!!;h.f.revise()
        h.vm.refreshLocalSearch();h.barrier();assertNull(h.editor().confirmation)
        assertFalse(h.vm.state.value.canUseTaskSource(e.binding));h.vm.taskProposals.confirm(e.session,old)
        assertTrue(h.confirms.isEmpty());assertTrue(h.tasks.snapshots().isEmpty())
        h.vm.taskProposals.reject(e.session);h.ui {it.notice!=null&&!it.busy}
        assertEquals(TaskProposalState.REJECTED,h.port.list().single().proposal.state);h.noAi()
    }}
    @Test(timeout=15_000) fun closeWhileSavingConflictIsRecoverableAndDoesNotOverwriteOtherOwner():Unit=Harness().use {h ->runBlocking {
        h.ready();val e=h.open();h.block=true
        h.vm.taskProposals.editTitle(e.session,"合成当前未保存输入");assertTrue(h.entered.await(2,TimeUnit.SECONDS))
        val p=h.port.list().single().proposal
        h.port.write(TaskProposalWrite(p.id,p.version,p.binding,"合成另一个owner",p.dateInput,p.createdAtMillis,p.creationTimeZone,110,"other"))
        h.vm.taskProposals.close(e.session);h.release.countDown()
        val failed=h.ui {it.conflict&&!it.busy};assertFalse(failed.closing);assertEquals("合成当前未保存输入",failed.title)
        h.vm.taskProposals.close(e.session);h.closed();h.barrier()
        assertEquals("合成另一个owner",h.port.list().single().proposal.titleInput);h.noAi()
    }}
    @Test(timeout=15_000) fun identicalDateDoesNotAutosaveOrPreventPrepareAndOldDisposedCallbacksCannotConfirm():Unit=Harness().use {h ->runBlocking {
        h.ready();val e=h.open();h.vm.taskProposals.editTitle(e.session,"合成可确认草稿");val saved=h.saved()
        val count=h.writes.size;h.vm.taskProposals.editDate(e.session,saved.date);h.vm.taskProposals.prepare(e.session)
        val old=h.editor().confirmation!!;assertEquals(count,h.writes.size)
        var lateOpens=0
        val retired=SourceTaskController({h.vm.state.value},{},{false},Clock.systemUTC(),proposalOpen={lateOpens++})
        retired.dispose();retired.open(e.binding);assertEquals(0,lateOpens)
        val oldVm=h.vm;h.cold();h.ready();oldVm.taskProposals.confirm(e.session,old)
        assertTrue(h.tasks.snapshots().isEmpty());assertTrue(h.confirms.isEmpty());h.noAi()
    }}
    private class Harness:AutoCloseable {
        val f=LocalCandidateFixture();lateinit var tasks:SqlManualTaskRepository;lateinit var port:SqlTaskProposalRepository
        lateinit var vm:LocalRecordsViewModel;lateinit var base:SqliteVmEntryRepository
        val writes=CopyOnWriteArrayList<TaskProposalWrite>();val confirms=CopyOnWriteArrayList<TaskProposalConfirm>()
        @Volatile var block=false;@Volatile var failList=false;@Volatile var loseConfirmAck=false;@Volatile var unavailableSource=false
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        init {val p=SqlLocalProcessingRepository(f.gateway);p.change(LocalProcessingChange(p.read(f.source),LocalProcessingChoice.AI_NONE,"none"));start()}
        private fun start() {
            tasks=SqlManualTaskRepository(f.gateway,LocalCandidateFixture.KEY);port=SqlTaskProposalRepository(f.gateway,LocalCandidateFixture.KEY,tasks)
            val capability=object:TaskProposalRepository by port {
                override fun list():List<TaskProposalItem> {if(failList) throw TaskProposalStorageFailure();return port.list().map {if(unavailableSource) it.copy(sourceAvailable=false) else it}}
                override fun write(request:TaskProposalWrite):TaskProposal {
                    writes.add(request);if(block) {block=false;entered.countDown();check(release.await(3,TimeUnit.SECONDS))};return port.write(request)
                }
                override fun confirm(request:TaskProposalConfirm):ManualTaskSnapshot {
                    confirms.add(request)
                    if(loseConfirmAck) f.db.afterSql={if(it=="COMMIT") {loseConfirmAck=false;throw IllegalStateException("Synthetic lost ACK")}}
                    try {return port.confirm(request)} finally {f.db.afterSql=null}
                }
            }
            base=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)) {f.gateway.stopAndAwaitIdle();port.wipeKey();tasks.wipeKey();f.store.wipeKey()}
            val owner=object:EntryRepository by base,AutoCloseable {
                override val taskProposals=capability
                override val manualTasks=tasks
                override val sourceTasks=SqlSourceTaskRepository(f.gateway,tasks)
                override val localProcessing=SqlLocalProcessingRepository(f.gateway)
                override val sourceBoundText:SourceBoundTextRepository? get()=error("No remote capability")
                override suspend fun prepare() {check(f.db.read("PRAGMA user_version").single().single()==10L)}
                override fun close()=base.close()
            }
            vm=LocalRecordsViewModel(Application(),{owner},Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC),bindAi={error("No AI")})
        }
        suspend fun ready()=withTimeout(3_000) {vm.state.first {it.ready&&!it.opening&&it.taskProposals.available&&it.tasks.available}}
        fun editor()=checkNotNull(vm.state.value.taskProposals.editor)
        suspend fun open():TaskProposalEditor {vm.sourceTasks.openOriginal("source",1);return saved()}
        suspend fun saved()=ui {it.saved!=null&&!it.busy&&it.pending==null&&it.savedEpoch==it.epoch}
        suspend fun ui(test:(TaskProposalEditor)->Boolean)=withTimeout(3_000) {vm.state.first {it.taskProposals.editor?.let(test)==true}.taskProposals.editor!!}
        suspend fun closed()=withTimeout(3_000) {vm.state.first {it.taskProposals.editor==null}}
        suspend fun barrier() {val text="合成屏障"+System.nanoTime();vm.changeText(text);withTimeout(3_000) {vm.state.first {it.text==text&&it.draftNotice=="草稿已保存在本机"}}}
        fun noAi() {assertTrue(f.db.read("SELECT id FROM remote_text_jobs").isEmpty());assertTrue(f.db.read("SELECT id FROM local_candidate_batches").isEmpty())}
        private fun stop() {release.countDown();vm.clearForHostTest();assertTrue(base.closed.await(3,TimeUnit.SECONDS));base.closeFailure.get()?.let {throw it}}
        fun cold() {stop();f.reopen();start()}
        override fun close() {stop();f.close()}
    }
}
