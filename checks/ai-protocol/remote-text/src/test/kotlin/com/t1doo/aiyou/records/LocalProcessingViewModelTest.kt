package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LocalProcessingViewModelTest {
    @Test(timeout=10_000) fun explicitRecordChoiceCancelAndFreshConfirmationPreserveOriginalAndNeverGenerate():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();val initial=ui.snapshot!!
        h.vm.localProcessing.prepare(ui.session,LocalProcessingChoice.AI_NONE);val old=h.value().confirmation!!
        h.vm.localProcessing.cancel(ui.session,old);assertTrue(h.changes.isEmpty())
        h.vm.localProcessing.prepare(ui.session,LocalProcessingChoice.AI_NONE);val fresh=h.value().confirmation!!
        h.vm.localProcessing.confirm(ui.session,old);assertTrue(h.changes.isEmpty())
        h.vm.localProcessing.confirm(ui.session,fresh);val saved=h.ui { it.notice!=null&&!it.busy }
        assertEquals(ProcessingPolicy.AI_NONE,saved.snapshot!!.policy);assertEquals(initial.generation+1,saved.snapshot.generation)
        assertEquals(ProcessingPolicy.AI_NONE,h.vm.state.value.entries.single { it.id=="source" }.policy)
        h.vm.localProcessing.prepare(ui.session,LocalProcessingChoice.LOCAL_ONLY)
        h.vm.localProcessing.confirm(ui.session,h.value().confirmation!!);h.ui { it.snapshot?.policy==ProcessingPolicy.LOCAL_ONLY&&!it.busy }
        assertEquals(h.f.original,h.vm.state.value.entries.single { it.id=="source" }.originalText)
        assertTrue(h.f.db.read("SELECT * FROM local_candidate_batches").isEmpty());assertTrue(h.f.db.read("SELECT * FROM remote_text_jobs").isEmpty())
        assertEquals(0,h.remoteCalls)
    } }
    @Test(timeout=10_000) fun actualSqlLostAckRetainsIdenticalRequestAndColdReadConfirmsOneGeneration():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();val initial=ui.snapshot!!
        h.vm.localProcessing.prepare(ui.session,LocalProcessingChoice.AI_NONE)
        h.loseAck=true
        h.vm.localProcessing.confirm(ui.session,h.value().confirmation!!)
        val uncertain=h.ui { it.error!=null&&!it.busy }
        assertNull(uncertain.snapshot);assertNull(uncertain.notice);assertNotNull(uncertain.pending)
        assertEquals(initial.generation+1,h.sql.read(h.f.source).generation)
        h.vm.localProcessing.retry(ui.session,uncertain.request);val saved=h.ui { it.notice!=null&&!it.busy }
        assertSame(h.changes[0],h.changes[1]);assertEquals(initial.generation+1,saved.snapshot!!.generation)
        h.dispose();h.f.reopen()
        assertEquals(saved.snapshot,SqlLocalProcessingRepository(h.f.base.gateway).read(h.f.source));assertEquals(0,h.remoteCalls)
    } }
    @Test(timeout=10_000) fun failedReadbackCloseAndRetainedCallbackCannotWriteAnotherRecordOrUndoLaterChoice():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.vm.localProcessing.prepare(ui.session,LocalProcessingChoice.AI_NONE);val old=h.value().confirmation!!
        h.failReadback=true;h.vm.localProcessing.confirm(ui.session,old)
        val failed=h.ui { it.error!=null&&!it.busy };assertNotNull(failed.pending);assertNull(failed.notice)
        h.vm.localProcessing.close(ui.session);h.failReadback=false
        val fresh=h.open();assertEquals(ProcessingPolicy.AI_NONE,fresh.snapshot!!.policy)
        h.vm.localProcessing.prepare(fresh.session,LocalProcessingChoice.LOCAL_ONLY)
        h.vm.localProcessing.confirm(ui.session,old);assertEquals(1,h.changes.size)
        h.vm.localProcessing.confirm(fresh.session,h.value().confirmation!!);h.ui { it.notice!=null&&!it.busy }
        assertThrows(LocalProcessingConflict::class.java) { h.sql.change(old) }
        assertEquals(ProcessingPolicy.LOCAL_ONLY,h.sql.read(h.f.source).policy);assertEquals(0,h.remoteCalls)
    } }
    @Test(timeout=10_000) fun metadataFailureRetiresDerivedAuthorityButKeepsManualOriginalReadable():Unit=Harness().use { h -> runBlocking {
        h.ready();h.vm.sourceMemories.open("source",2)
        withTimeout(2_000) { h.vm.state.first { it.sourceMemories?.preview!=null } }
        h.failVersions=true;h.vm.refreshLocalSearch()
        withTimeout(2_000) { h.vm.state.first { !it.localProcessingAvailable } }
        assertNull(h.vm.state.value.sourceMemories);assertNull(h.vm.state.value.error)
        assertEquals(h.f.original,h.vm.state.value.entries.single { it.id=="source" }.originalText)
        h.vm.sourceMemories.open("source",2);assertNull(h.vm.state.value.sourceMemories)
        h.failVersions=false;h.vm.refreshLocalSearch();withTimeout(2_000) { h.vm.state.first { it.localProcessingAvailable } }
        assertEquals(0,h.remoteCalls)
    } }
    private class Harness:AutoCloseable {
        val f=SourceMemoryFixture();val sql=SqlLocalProcessingRepository(f.base.gateway)
        val changes=CopyOnWriteArrayList<LocalProcessingChange>()
        @Volatile var loseAck=false;@Volatile var failReadback=false;@Volatile var failVersions=false;private var changed=false;var remoteCalls=0
        private val port=object:LocalProcessingRepository by sql {
            override fun change(request:LocalProcessingChange):LocalProcessingSnapshot {
                changes.add(request)
                if(loseAck) f.db.afterSql={ if(it=="COMMIT") { loseAck=false;throw IllegalStateException("Synthetic lost ACK") } }
                try { return sql.change(request).also { changed=true } } finally { f.db.afterSql=null }
            }
            override fun read(source:SourceVersion):LocalProcessingSnapshot { if(changed&&failReadback) throw LocalProcessingStorageFailure();return sql.read(source) }
            override fun versions():Map<String,LocalProcessingVersion> { if(failVersions) throw LocalProcessingStorageFailure();return sql.versions() }
        }
        private val base=SqliteVmEntryRepository(f.db,f.base.gateway,RemoteTextTransactions(f.base.gateway)) { f.base.gateway.stopAndAwaitIdle();f.store.wipeKey();f.base.store.wipeKey() }
        private val owner=object:EntryRepository by base,AutoCloseable {
            override val localProcessing=port
            override val sourceMemories=f.store
            override val sourceBoundText:SourceBoundTextRepository? get() { remoteCalls++;error("No remote authority") }
            override suspend fun prepare() { check(f.db.read("PRAGMA user_version").single().single()==10L) }
            override fun close()=base.close()
        }
        val vm=LocalRecordsViewModel(Application(),{owner},Clock.fixed(Instant.ofEpochMilli(50),ZoneOffset.UTC),bindAi={remoteCalls++;error("No remote exchange")})
        suspend fun ready()=withTimeout(2_000) { vm.state.first { it.ready&&!it.opening&&it.supportsLocalProcessing } }
        fun value()=checkNotNull(vm.state.value.localProcessing)
        suspend fun open():LocalProcessingUiState { vm.localProcessing.open("source",2);return ui { !it.busy&&it.snapshot!=null } }
        suspend fun ui(test:(LocalProcessingUiState)->Boolean)=withTimeout(2_000) { vm.state.first { it.localProcessing?.let(test)==true }.localProcessing!! }
        private var disposed=false
        fun dispose() { if(!disposed) { disposed=true;vm.clearForHostTest() };assertTrue(base.closed.await(2,TimeUnit.SECONDS));base.closeFailure.get()?.let { throw it } }
        override fun close() { dispose();f.close() }
    }
}
