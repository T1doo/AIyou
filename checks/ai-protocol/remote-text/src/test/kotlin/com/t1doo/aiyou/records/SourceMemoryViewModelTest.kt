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

class SourceMemoryViewModelTest {
    @Test(timeout=10_000) fun unobservedSameRevisionPolicyAbaRetiresOldMemoryPreviewAndConfirmation():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.vm.sourceMemories.prepareSave(ui.session);val old=h.value().confirmation!!
        val policy=SqlLocalProcessingRepository(h.f.base.gateway)
        h.f.db.transaction {
            val none=policy.change(LocalProcessingChange(policy.read(h.f.source),LocalProcessingChoice.AI_NONE,"aba-off"))
            policy.change(LocalProcessingChange(none,LocalProcessingChoice.LOCAL_ONLY,"aba-on"))
        }
        h.vm.refreshLocalSearch()
        // The serial draft command is a barrier after RefreshSearch, even though entries compare equal.
        h.vm.changeText("合成队列屏障")
        withTimeout(2_000) { h.vm.state.first { it.draftNotice=="草稿已保存在本机" } }
        assertNull(h.vm.state.value.sourceMemories)
        h.vm.sourceMemories.confirm(ui.session,old);assertTrue(h.creates.isEmpty());assertEquals(0,h.remoteCalls)
    } }
    @Test(timeout=10_000) fun actualVmPersistsOnlyAfterSeparateConfirmationThenColdReopensAcceptedContext():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.vm.sourceMemories.prepareSave(ui.session);val old=h.value().confirmation!!
        h.vm.sourceMemories.cancelConfirmation(ui.session,old);assertTrue(h.creates.isEmpty());assertTrue(h.f.store.memories(h.f.source).isEmpty())
        h.vm.sourceMemories.prepareSave(ui.session);val fresh=h.value().confirmation!!
        h.vm.sourceMemories.confirm(ui.session,old);assertTrue(h.creates.isEmpty());assertEquals(fresh,h.value().confirmation)
        h.vm.sourceMemories.confirm(ui.session,fresh);val pending=h.ui { it.notice!=null&&!it.busy }.memories.single()
        assertEquals(SourceMemoryReview.PROPOSED,pending.current!!.review);assertEquals(SourceMemoryAssertion.UNCERTAIN,pending.current!!.content.assertion)
        h.vm.sourceMemories.prepareAction(ui.session,pending.id,SourceMemoryAction.ACCEPT);h.confirm()
        assertEquals(SourceMemoryReview.ACCEPTED,h.value().memories.single().current!!.review)
        assertEquals(h.f.original,h.vm.state.value.entries.single { it.id=="source" }.originalText);assertTrue(h.vm.state.value.tasks.items.isEmpty())
        h.vm.sourceMemories.close(ui.session);assertEquals(SourceMemoryReview.ACCEPTED,h.open().memories.single().current!!.review)
        h.dispose();h.f.reopen();assertEquals(SourceMemoryReview.ACCEPTED,h.f.store.memories(h.f.source).single().current!!.review);assertEquals(0,h.remoteCalls)
    } }
    @Test(timeout=10_000) fun correctionRejectReopenAndRevokeUseFreshRowAndContentVersionsWithoutAutoAcceptance():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.vm.sourceMemories.prepareSave(ui.session);h.confirm();val pending=h.value().memories.single()
        h.vm.sourceMemories.prepareAction(ui.session,pending.id,SourceMemoryAction.REJECT);h.confirm()
        assertEquals(SourceMemoryReview.REJECTED,h.value().memories.single().current!!.review)
        h.vm.sourceMemories.prepareAction(ui.session,pending.id,SourceMemoryAction.REOPEN);h.confirm()
        h.vm.sourceMemories.prepareAction(ui.session,pending.id,SourceMemoryAction.ACCEPT);h.confirm();val accepted=h.value().memories.single()
        h.vm.sourceMemories.beginCorrection(ui.session,accepted.id);val form=h.value()
        h.vm.sourceMemories.edit(ui.session,form.quote,SourceMemoryKind.OCCASION_EMOTION,SourceMemoryAssertion.CONTEXT,"这是引用的人工分类，并非诊断")
        h.vm.sourceMemories.prepareSave(ui.session);assertEquals(SourceMemoryReview.ACCEPTED,h.f.store.memories(h.f.source).single().current!!.review)
        h.confirm();val corrected=h.value().memories.single()
        assertEquals(SourceMemoryReview.PROPOSED,corrected.current!!.review);assertEquals(SourceMemoryReview.ACCEPTED,corrected.versions.first().priorReview)
        assertEquals(SourceMemoryReview.SUPERSEDED,corrected.versions.first().review);assertEquals(2,corrected.current!!.revision)
        h.vm.sourceMemories.prepareAction(ui.session,pending.id,SourceMemoryAction.ACCEPT);h.confirm()
        h.vm.sourceMemories.prepareAction(ui.session,pending.id,SourceMemoryAction.REVOKE);h.confirm()
        h.vm.sourceMemories.close(ui.session);assertEquals(SourceMemoryReview.REVOKED,h.open().memories.single().current!!.review);assertEquals(0,h.remoteCalls)
    } }
    @Test(timeout=10_000) fun unknownCommitAckHidesUnverifiedResultsAndRetriesTheIdenticalImmutableRequest():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.loseAck=true;h.vm.sourceMemories.prepareSave(ui.session);h.vm.sourceMemories.confirm(ui.session,h.value().confirmation!!)
        val failed=h.ui { it.error!=null&&!it.busy };assertNull(failed.preview);assertNull(failed.notice);assertTrue(failed.memories.isEmpty());assertNotNull(failed.pending)
        assertEquals(SourceMemoryReview.PROPOSED,h.f.store.memories(h.f.source).single().current!!.review)
        h.loseAck=false;h.failRead=false;h.vm.sourceMemories.retry(ui.session,failed.request);h.ui { it.notice!=null&&!it.busy }
        assertSame(h.creates[0],h.creates[1]);assertEquals(1L,h.f.store.memories(h.f.source).single().version);assertEquals(0,h.remoteCalls)
    } }
    @Test(timeout=10_000) fun sourceEditAndPolicyRevocationRetireUnconfirmedPrivateAuthorityBeforeItCanWrite():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.vm.sourceMemories.prepareSave(ui.session);val old=h.value().confirmation!!
        h.f.db.transaction { sql -> sql.execute("INSERT INTO entry_revisions VALUES('source',3,?,?,6)",listOf("合成新版本",OriginalExports.hash("合成新版本")));sql.execute("UPDATE entries SET currentRevision=3 WHERE id='source'") }
        h.vm.refreshLocalSearch();withTimeout(2_000) { h.vm.state.first { it.entries.any { e -> e.id=="source"&&e.currentRevision==3 } } }
        assertNull(h.vm.state.value.sourceMemories);h.vm.sourceMemories.confirm(ui.session,old);assertTrue(h.creates.isEmpty())
        h.vm.sourceMemories.open("source",3);val fresh=h.ui { !it.busy&&it.preview!=null };h.vm.sourceMemories.prepareSave(fresh.session);val later=h.value().confirmation!!
        h.f.db.transaction { it.execute("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id='source'") };h.vm.refreshLocalSearch()
        withTimeout(2_000) { h.vm.state.first { it.entries.any { e -> e.id=="source"&&e.policy==ProcessingPolicy.AI_NONE } } }
        assertNull(h.vm.state.value.sourceMemories);h.vm.sourceMemories.confirm(fresh.session,later);assertTrue(h.creates.isEmpty());assertEquals(0,h.remoteCalls)
    } }
    @Test(timeout=10_000) fun actualInboxReaderOpensExistingSourceReviewWithoutAcceptingOrCallingModels():Unit=Harness().use { h -> runBlocking {
        h.ready();h.f.create();h.vm.memoryInbox.open()
        val page=withTimeout(2_000) { h.vm.state.first { it.memoryInbox?.snapshot!=null && it.memoryInbox.busy.not() }.memoryInbox!! }
        val item=page.snapshot!!.items.single()
        h.vm.memoryInbox.source(page.session,page.request,item) {
            h.vm.memoryInbox.close(page.session);h.vm.sourceMemories.open(item.source.sourceId,item.source.revision.toInt());true
        }
        val review=h.ui { !it.busy && it.preview!=null }
        assertNull(h.vm.state.value.memoryInbox);assertEquals(item.id,review.memories.single().id)
        assertEquals(SourceMemoryReview.PROPOSED,review.memories.single().current!!.review)
        assertTrue(h.creates.isEmpty());assertEquals(0,h.remoteCalls)
    } }
    private class Harness:AutoCloseable {
        val f=SourceMemoryFixture();val creates=CopyOnWriteArrayList<SourceMemoryCreate>()
        @Volatile var loseAck=false;@Volatile var failRead=false;var remoteCalls=0
        private val port=object:SourceMemoryRepository by f.store {
            override fun create(request:SourceMemoryCreate)=f.store.create(request).also { creates.add(request);if(loseAck) failRead=true }
            override fun preview(source:SourceVersion):SourceMemoryPreview { if(failRead) throw SourceMemoryStorageFailure();return f.store.preview(source) }
        }
        private val base=SqliteVmEntryRepository(f.db,f.base.gateway,RemoteTextTransactions(f.base.gateway)) { f.base.gateway.stopAndAwaitIdle();f.store.wipeKey();f.base.store.wipeKey() }
        private val owner=object:EntryRepository by base,AutoCloseable {
            override val localProcessing=SqlLocalProcessingRepository(f.base.gateway)
            override val sourceMemories=port
            override val sourceBoundText:SourceBoundTextRepository? get() { remoteCalls++;error("No remote authority") }
            override suspend fun prepare() { check(f.db.read("PRAGMA user_version").single().single()==10L) }
            override fun close()=base.close()
        }
        val vm=LocalRecordsViewModel(Application(),{owner},Clock.fixed(Instant.ofEpochMilli(50),ZoneOffset.UTC),bindAi={ remoteCalls++;error("No remote exchange") })
        suspend fun ready()=withTimeout(2_000) { vm.state.first { it.ready&&!it.opening&&it.supportsSourceMemories } }
        fun value()=checkNotNull(vm.state.value.sourceMemories)
        suspend fun open():SourceMemoryUiState { vm.sourceMemories.open("source",2);return ui { !it.busy&&it.preview!=null } }
        suspend fun ui(test:(SourceMemoryUiState)->Boolean)=withTimeout(2_000) { vm.state.first { it.sourceMemories?.let(test)==true }.sourceMemories!! }
        suspend fun confirm() { val u=value();vm.sourceMemories.confirm(u.session,u.confirmation!!);ui { it.notice!=null&&!it.busy } }
        private var disposed=false
        fun dispose() { if(!disposed) { disposed=true;vm.clearForHostTest() };assertTrue(base.closed.await(2,TimeUnit.SECONDS));base.closeFailure.get()?.let { throw it } }
        override fun close() { dispose();f.close() }
    }
}
