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

class SourceTagViewModelTest {
    @Test(timeout=10_000) fun actualVmRequiresFreshConfirmationPublishesIndependentTagAndColdReopens(): Unit = Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();val item=ui.preview!!.proposals.first()
        h.vm.sourceTags.requestConfirmation(ui.session,item);val old=h.vm.state.value.sourceTags!!.confirmation!!
        h.vm.sourceTags.cancelConfirmation(ui.session,old);assertTrue(h.applies.isEmpty());assertTrue(h.f.tags.read("source").names.isEmpty())
        h.vm.sourceTags.requestConfirmation(ui.session,item);val fresh=h.vm.state.value.sourceTags!!.confirmation!!
        h.vm.sourceTags.cancelConfirmation(ui.session,old);h.vm.sourceTags.confirm(ui.session,old)
        assertEquals(fresh,h.vm.state.value.sourceTags!!.confirmation)
        h.vm.sourceTags.confirm(ui.session,fresh)
        val saved=h.ui { it.notice!=null&&it.pending==null };assertEquals(listOf("旅行"),saved.preview!!.tags.names)
        assertEquals(listOf("旅行"),h.vm.state.value.manualTags.getValue("source").names)
        assertEquals(h.f.original,h.vm.state.value.entries.single { it.id=="source" }.originalText)
        h.vm.sourceTags.close(ui.session);val reopened=h.open();assertEquals(listOf("旅行"),reopened.preview!!.tags.names)
        h.vm.sourceTags.confirm(ui.session,fresh);assertEquals(1,h.applies.size)
        h.dispose();h.f.reopen();assertEquals(listOf("旅行"),h.f.tags.read("source").names)
        assertEquals(0,h.remoteCalls)
    } }
    @Test(timeout=10_000) fun unknownAckHidesUnverifiedLabelsAndRetriesIdenticalRequestWithoutDuplicateVersion(): Unit = Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.loseAck=true
        h.vm.sourceTags.requestConfirmation(ui.session,ui.preview!!.proposals.first())
        h.vm.sourceTags.confirm(ui.session,h.vm.state.value.sourceTags!!.confirmation!!)
        val failed=h.ui { it.error!=null&&!it.busy };assertNull(failed.notice);assertNull(failed.preview);assertNotNull(failed.pending)
        assertFalse(h.vm.state.value.manualTagsAvailable);assertEquals(listOf("旅行"),h.f.tags.read("source").names)
        val original=h.applies.single();h.loseAck=false;h.failRead=false
        h.vm.sourceTags.retry(failed.session,failed.request)
        h.ui { it.notice!=null&&it.pending==null };assertSame(original,h.applies[1]);assertEquals(1L,h.f.tags.read("source").version)
        assertEquals(0,h.remoteCalls)
    } }
    @Test(timeout=10_000) fun sourceEditRetiresUnconfirmedPreviewWithoutApplyingStaleConfirmation(): Unit = Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.vm.sourceTags.requestConfirmation(ui.session,ui.preview!!.proposals.first())
        val old=h.vm.state.value.sourceTags!!.confirmation!!
        h.f.db.transaction { sql -> sql.execute("INSERT INTO entry_revisions VALUES('source',3,?,?,6)",listOf("合成改写，无标签行",OriginalExports.hash("合成改写，无标签行")));sql.execute("UPDATE entries SET currentRevision=3 WHERE id='source'") }
        h.vm.refreshLocalSearch();withTimeout(2_000) { h.vm.state.first { !it.opening&&it.entries.any { e -> e.id=="source"&&e.currentRevision==3 } } }
        assertNull(h.vm.state.value.sourceTags);h.vm.sourceTags.confirm(ui.session,old);assertTrue(h.applies.isEmpty())
        h.vm.sourceTags.open("source",3);val fresh=h.ui { !it.busy&&it.preview!=null };assertTrue(fresh.preview!!.proposals.isEmpty())
        assertEquals(0,h.remoteCalls)
    } }
    private class Harness : AutoCloseable {
        val f=SourceTagFixture();val applies=CopyOnWriteArrayList<SourceTagRequest>()
        @Volatile var failRead=false
        @Volatile var loseAck=false
        var remoteCalls=0
        private val port=object:SourceTagRepository by f.store {
            override fun apply(request:SourceTagRequest):ManualTagSnapshot { applies.add(request);return f.store.apply(request).also { if(loseAck) failRead=true } }
            override fun preview(source:SourceVersion):SourceTagPreview { if(failRead) throw SourceTagStorageFailure();return f.store.preview(source) }
        }
        private val base=SqliteVmEntryRepository(f.db,f.base.gateway,RemoteTextTransactions(f.base.gateway)) { f.base.gateway.stopAndAwaitIdle();f.tags.wipeKey();f.base.store.wipeKey() }
        private val owner=object:EntryRepository by base,AutoCloseable {
            override val sourceTags=port
            override val manualTags get()=f.tags
            override val sourceBoundText:SourceBoundTextRepository? get() { remoteCalls++;error("No remote access") }
            override suspend fun prepare() { check(f.db.read("PRAGMA user_version").single().single()==10L) }
            override fun close()=base.close()
        }
        val vm=LocalRecordsViewModel(Application(),{owner},Clock.fixed(Instant.ofEpochMilli(50),ZoneOffset.UTC),bindAi={ remoteCalls++;error("No remote exchange") })
        suspend fun ready()=withTimeout(2_000) { vm.state.first { it.ready&&!it.opening&&it.supportsSourceTags } }
        suspend fun open():SourceTagUiState { vm.sourceTags.open("source",2);return ui { !it.busy&&it.preview!=null } }
        suspend fun ui(test:(SourceTagUiState)->Boolean)=withTimeout(2_000) { vm.state.first { it.sourceTags?.let(test)==true }.sourceTags!! }
        private var disposed=false
        fun dispose() { if(!disposed) { disposed=true;vm.clearForHostTest() };assertTrue(base.closed.await(2,TimeUnit.SECONDS));base.closeFailure.get()?.let { throw it } }
        override fun close() { dispose();f.close() }
    }
}
