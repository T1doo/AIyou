package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SourceTaskViewModelTest {
    @Test(timeout=10_000) fun readFailureAndRevisionProofWindowsRetainFormButBlockRetainedConfirmation():Unit=Harness().use { h -> runBlocking {
        h.ready();val source=h.open();h.vm.sourceTasks.editTitle(source.session,"合成保留的人工输入")
        h.vm.sourceTasks.prepare(source.session);val confirmed=h.value().confirmation!!
        val valid=h.vm.state.value;assertTrue(valid.canUseTaskSource(source.binding))
        var local=valid;val queued=mutableListOf<suspend(EntryRepository)->Unit>()
        val controller=SourceTaskController({local},{local=it(local)},{queued.add(it);true},Clock.systemUTC())
        val entry=valid.entries.single { it.id==source.binding.source.sourceId }
        val unavailable=listOf(valid.copy(error="合成读取失败"),valid.copy(opening=true),valid.copy(ready=false),
            valid.copy(entries=emptyList()),valid.copy(entries=listOf(entry.copy(currentRevision=2))),
            valid.copy(entries=listOf(entry.copy(originalText="合成改写正文"))),valid.copy(localProcessingAvailable=false),
            valid.copy(localProcessingVersions=emptyMap()),valid.copy(localProcessingVersions=mapOf(entry.id to
                LocalProcessingVersion(source.binding.source,source.binding.generation+1))))
        for(state in unavailable) {
            local=state;assertFalse(local.canUseTaskSource(source.binding))
            controller.confirm(source.session,confirmed);assertTrue(queued.isEmpty())
            local=local.copy(sourceTask=local.sourceTask!!.copy(confirmation=null))
            controller.prepare(source.session);assertNull(local.sourceTask!!.confirmation)
            assertEquals("合成保留的人工输入",local.sourceTask!!.title)
            local=local.copy(sourceTask=local.sourceTask!!.copy(pending=confirmed))
            controller.editTitle(source.session,"不应覆盖");assertSame(confirmed,local.sourceTask!!.pending)
        }
        controller.dispose();assertTrue(h.tasks.snapshots().isEmpty());h.noAi()
    } }
    @Test(timeout=10_000) fun confirmedReceiptRetryAfterErasureFenceNeverRepublishesTheOldQuote():Unit=Harness().use { h -> runBlocking {
        h.ready();val source=h.open();h.vm.sourceTasks.editTitle(source.session,"合成独立人工任务")
        h.vm.sourceTasks.prepare(source.session);h.failReadback=true
        h.vm.sourceTasks.confirm(source.session,h.value().confirmation!!);h.ui { it.error!=null&&!it.busy }
        h.f.db.transaction { it.execute("INSERT INTO permanent_erasure_receipts VALUES('source',1,0,'erase','PREPARED','','')") }
        h.vm.refreshLocalSearch();h.barrier();h.failReadback=false
        h.vm.sourceTasks.retry(source.session,h.value().request)
        val saved=h.ui { it.notice!=null&&!it.busy }
        assertNull(saved.preview);assertNull(saved.pending);assertNull(saved.confirmation)
        assertEquals(1,h.tasks.snapshots().size);assertTrue(saved.notice!!.contains("未展示旧摘录"));h.noAi()
    } }
    @Test(timeout=10_000) fun anotherManualTaskUnknownAckKeepsItsPendingIdentityAndRetryAfterSourceCreation():Unit=Harness().use { h -> runBlocking {
        h.ready();val source=h.open();h.vm.sourceTasks.editTitle(source.session,"合成有来源待办")
        h.vm.sourceTasks.prepare(source.session);val confirm=h.value().confirmation!!
        h.vm.tasks.begin();val editor=h.vm.state.value.tasks.editor!!
        h.failReadback=true;h.vm.tasks.create(editor.session,"合成另一人工待办")
        withTimeout(2_000) { h.vm.state.first { it.tasks.error!=null&&!it.tasks.busy } }
        val pending=h.vm.state.value.tasks.pending;assertNotNull(pending)
        h.failReadback=false;h.vm.sourceTasks.confirm(source.session,confirm);h.ui { it.notice!=null&&!it.busy }
        assertSame(pending,h.vm.state.value.tasks.pending);assertNotNull(h.vm.state.value.tasks.error)
        h.vm.sourceTasks.close(source.session);h.barrier()
        assertSame(pending,h.vm.state.value.tasks.pending);h.vm.tasks.retry()
        withTimeout(2_000) { h.vm.state.first { it.tasks.pending==null&&it.tasks.available } }
        assertEquals(2,h.tasks.snapshots().size);h.noAi()
    } }
    @Test(timeout=10_000) fun aiNoneLiteralPreviewEditableFieldsSeparateConfirmationAndSourceLocation():Unit=Harness(aiNone=true).use { h -> runBlocking {
        h.ready();val ui=h.open();assertTrue(h.tasks.snapshots().isEmpty())
        h.vm.sourceTasks.editTitle(ui.session,"合成人工待办")
        val date=ManualTaskDateInput("2026-10-20",timeZone="UTC")
        h.vm.sourceTasks.editDate(ui.session,date);h.vm.sourceTasks.editTitle(ui.session,"合成人工纠正标题")
        assertEquals(date,h.value().date);h.vm.sourceTasks.prepare(ui.session);val old=h.value().confirmation!!
        h.vm.sourceTasks.cancel(ui.session,old);assertTrue(h.tasks.snapshots().isEmpty())
        h.vm.sourceTasks.prepare(ui.session);val fresh=h.value().confirmation!!
        h.vm.sourceTasks.confirm(ui.session,old);assertTrue(h.requests.isEmpty())
        h.vm.sourceTasks.confirm(ui.session,fresh);h.vm.sourceTasks.confirm(ui.session,fresh)
        h.ui { it.notice!=null&&!it.busy };assertEquals(1,h.requests.size)
        val task=h.tasks.snapshots().single();assertEquals("合成人工纠正标题",task.title);assertEquals("2026-10-20",task.schedule!!.date.toString())
        assertEquals(ProcessingPolicy.AI_NONE,SqlLocalProcessingRepository(h.f.gateway).read(h.f.source).policy)
        var location:TaskSourceBinding?=null;h.vm.sourceTasks.locate(task.id,task.version) { location=it }
        h.barrier();assertEquals(ui.binding,location);h.noAi()
    } }
    @Test(timeout=10_000) fun actualLostCommitAckRetriesIdenticalRequestAndColdReopenRecognizesExistingTask():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.vm.sourceTasks.editTitle(ui.session,"合成未知ACK标题");h.vm.sourceTasks.prepare(ui.session)
        h.loseAck=true;h.vm.sourceTasks.confirm(ui.session,h.value().confirmation!!)
        val failed=h.ui { it.error!=null&&!it.busy };assertNull(failed.notice);assertNull(failed.preview);assertNotNull(failed.pending)
        assertFalse(h.vm.state.value.tasks.available);assertEquals(1,h.tasks.snapshots().size)
        h.vm.sourceTasks.retry(ui.session,failed.request);h.ui { it.notice!=null&&!it.busy }
        assertSame(h.requests[0],h.requests[1]);assertEquals(1L,h.tasks.snapshots().single().version)
        h.cold();h.ready();val reopened=h.open();assertNotNull(reopened.preview!!.existing)
        h.vm.sourceTasks.prepare(reopened.session);assertNull(h.value().confirmation);assertEquals(1,h.tasks.snapshots().size);h.noAi()
    } }
    @Test(timeout=10_000) fun readbackFailureCloseAndStaleConfirmationCannotCreateAnotherTask():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.vm.sourceTasks.editTitle(ui.session,"合成待核对标题");h.vm.sourceTasks.prepare(ui.session)
        val old=h.value().confirmation!!;h.failReadback=true;h.vm.sourceTasks.confirm(ui.session,old)
        h.ui { it.error!=null&&!it.busy };h.failReadback=false;h.vm.sourceTasks.close(ui.session)
        h.vm.sourceTasks.confirm(ui.session,old);h.barrier();assertEquals(1,h.requests.size)
        val fresh=h.open();assertNotNull(fresh.preview!!.existing);assertEquals(1,h.tasks.snapshots().size);h.noAi()
    } }
    @Test(timeout=10_000) fun invalidatedBlockedPreviewCannotRepublishOldQuoteOrConfirmation():Unit=Harness().use { h -> runBlocking {
        h.ready();h.blockPreview=true;h.vm.sourceTasks.openOriginal("source",1)
        assertTrue(h.previewEntered.await(2,TimeUnit.SECONDS));val old=h.value()
        h.vm.sourceTasks.invalidateSource("source");val retired=h.value();assertTrue(retired.request>old.request)
        h.releasePreview.countDown();h.barrier()
        assertNull(h.value().preview);assertNull(h.value().confirmation);assertNotNull(h.value().error)
        h.vm.sourceTasks.editTitle(old.session,"合成保留输入");h.vm.sourceTasks.prepare(old.session)
        assertNull(h.value().confirmation);assertTrue(h.tasks.snapshots().isEmpty());h.noAi()
    } }
    @Test(timeout=10_000) fun unobservedPolicyAbaRetiresConfirmationAndChangedSourceNeverRedirectsTask():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.vm.sourceTasks.editTitle(ui.session,"合成旧来源标题");h.vm.sourceTasks.prepare(ui.session);val old=h.value().confirmation!!
        val policy=SqlLocalProcessingRepository(h.f.gateway)
        h.f.db.transaction {
            val none=policy.change(LocalProcessingChange(policy.read(h.f.source),LocalProcessingChoice.AI_NONE,"off"))
            policy.change(LocalProcessingChange(none,LocalProcessingChoice.LOCAL_ONLY,"on"))
        }
        h.vm.refreshLocalSearch();h.barrier();assertNull(h.value().confirmation);assertNull(h.value().preview)
        h.vm.sourceTasks.confirm(ui.session,old);assertTrue(h.tasks.snapshots().isEmpty())
        h.vm.sourceTasks.close(ui.session);h.barrier();val fresh=h.open();h.vm.sourceTasks.editTitle(fresh.session,"合成新确认")
        h.vm.sourceTasks.prepare(fresh.session);h.vm.sourceTasks.confirm(fresh.session,h.value().confirmation!!);h.ui { it.notice!=null&&!it.busy }
        val task=h.tasks.snapshots().single();h.f.revise()
        var located=false;h.vm.sourceTasks.locate(task.id,task.version) { located=true };h.barrier()
        assertFalse(located);assertNotNull(h.vm.state.value.taskSourceNotice);assertEquals(1,h.tasks.snapshots().size);h.noAi()
    } }
    private class Harness(aiNone:Boolean=false):AutoCloseable {
        val f=LocalCandidateFixture();lateinit var tasks:SqlManualTaskRepository;lateinit var base:SqliteVmEntryRepository;lateinit var vm:LocalRecordsViewModel
        val requests=CopyOnWriteArrayList<SourceTaskCreate>()
        @Volatile var loseAck=false;@Volatile var failReadback=false;@Volatile var blockPreview=false
        val previewEntered=CountDownLatch(1);val releasePreview=CountDownLatch(1)
        init {
            if(aiNone) { val p=SqlLocalProcessingRepository(f.gateway);p.change(LocalProcessingChange(p.read(f.source),LocalProcessingChoice.AI_NONE,"none")) }
            start()
        }
        private fun start() {
            tasks=SqlManualTaskRepository(f.gateway,LocalCandidateFixture.KEY)
            val sql=SqlSourceTaskRepository(f.gateway,tasks)
            val sourcePort=object:SourceTaskRepository by sql {
                override fun preview(binding:TaskSourceBinding):SourceTaskPreview {
                    val actual=sql.preview(binding)
                    if(blockPreview) { blockPreview=false;previewEntered.countDown();check(releasePreview.await(2,TimeUnit.SECONDS)) }
                    return actual
                }
                override fun create(request:SourceTaskCreate):ManualTaskSnapshot {
                    requests.add(request)
                    if(loseAck) f.db.afterSql={if(it=="COMMIT") {loseAck=false;throw IllegalStateException("Synthetic lost ACK")}}
                    try {return sql.create(request)} finally {f.db.afterSql=null}
                }
            }
            base=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)) { f.gateway.stopAndAwaitIdle();tasks.wipeKey();f.store.wipeKey() }
            val owner=object:EntryRepository by base,AutoCloseable {
                override val sourceTasks=sourcePort
                override val localProcessing=SqlLocalProcessingRepository(f.gateway)
                override val manualTasks=object:ManualTaskRepository by tasks {
                    override fun snapshots():List<ManualTaskSnapshot> {if(failReadback) throw ManualTaskStorageFailure();return tasks.snapshots()}
                }
                override val sourceBoundText:SourceBoundTextRepository? get()=error("No remote capability")
                override suspend fun prepare() {check(f.db.read("PRAGMA user_version").single().single()==10L)}
                override fun close()=base.close()
            }
            vm=LocalRecordsViewModel(Application(),{owner},Clock.fixed(Instant.ofEpochMilli(50),ZoneOffset.UTC),bindAi={error("No remote exchange")})
        }
        suspend fun ready()=withTimeout(2_000) {vm.state.first {it.ready&&!it.opening&&it.tasks.available&&it.supportsSourceTasks}}
        fun value()=checkNotNull(vm.state.value.sourceTask)
        suspend fun open():SourceTaskUiState {vm.sourceTasks.openOriginal("source",1);return ui {it.preview!=null&&!it.busy}}
        suspend fun ui(test:(SourceTaskUiState)->Boolean)=withTimeout(2_000) {vm.state.first {it.sourceTask?.let(test)==true}.sourceTask!!}
        suspend fun barrier() {val text="合成屏障"+System.nanoTime();vm.changeText(text);withTimeout(2_000) {vm.state.first {it.text==text&&it.draftNotice=="草稿已保存在本机"}}}
        fun noAi() {assertTrue(f.db.read("SELECT * FROM remote_text_jobs").isEmpty());assertTrue(f.db.read("SELECT * FROM local_candidate_batches").isEmpty())}
        private var disposed=false
        private fun stop() {releasePreview.countDown();vm.clearForHostTest();assertTrue(base.closed.await(2,TimeUnit.SECONDS));base.closeFailure.get()?.let {throw it}}
        fun cold() {stop();f.reopen();start()}
        override fun close() {if(!disposed) {disposed=true;stop();f.close()}}
    }
}
