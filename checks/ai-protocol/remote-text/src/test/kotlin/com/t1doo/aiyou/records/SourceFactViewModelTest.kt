package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SourceFactViewModelTest {
    @Test(timeout=15_000) fun actualActorUnknownRefreshAndOldConfirmationCannotReplaceTheSameWriteIntent():Unit=runBlocking {Harness().use{h->
        h.ready();val u=h.open();h.form(u.session);h.vm.sourceFacts.save(u.session);val token=h.ui().confirmation!!;h.loseAck=true
        h.vm.sourceFacts.confirm(u.session,token);val failed=h.await{it.error!=null&&!it.busy};val intent=failed.pending!!
        h.vm.sourceFacts.refresh(u.session);h.vm.sourceFacts.edit(u.session,FactInput.FROM,"2027-01-01");assertSame(intent,h.ui().pending)
        h.vm.sourceFacts.retry(u.session);h.await{it.notice!=null&&!it.busy};assertEquals(2,h.writes.size);assertSame(h.writes[0],h.writes[1])
        assertEquals(1L,h.store.facts(h.f.source).single().rowVersion)
        h.vm.sourceFacts.close(u.session);val next=h.open();h.vm.sourceFacts.confirm(u.session,token);assertEquals(next.session,h.ui().session);assertEquals(2,h.writes.size)
    }}
    @Test(timeout=15_000) fun actualSerialActorKeepsAdmittedFactEditorWhileUnrelatedDraftCommandSettlesBeforeIt():Unit=runBlocking {Harness().use{h->
        h.ready();val u=h.open();h.form(u.session);h.vm.sourceFacts.save(u.session);val token=h.ui().confirmation!!
        h.blockDraft=true;h.vm.changeText("合成队列屏障")
        assertTrue(h.entered.await(3,TimeUnit.SECONDS))
        try {
            h.vm.sourceFacts.confirm(u.session,token)
            assertEquals(u.session,h.ui().session);assertTrue(h.ui().busy);assertNotNull(h.ui().pending);assertTrue(h.writes.isEmpty())
        }finally{h.release.countDown()}
        h.await{it.notice!=null&&!it.busy};assertEquals(1,h.writes.size);assertEquals(u.session,h.ui().session)
        assertEquals(FactReview.PROPOSED,h.store.facts(h.f.source).single().review!!.review)
        assertEquals(h.f.original,h.vm.state.value.entries.single{it.id==h.f.source.sourceId}.originalText)
    }}
    @Test(timeout=15_000) fun realObservedSourcePolicyAbaRetiresUnknownContentAndCannotReplayCommittedIntent():Unit=runBlocking {Harness().use{h->
        h.ready();val u=h.open();h.form(u.session);h.vm.sourceFacts.save(u.session);h.loseAck=true;h.vm.sourceFacts.confirm(u.session,h.ui().confirmation!!)
        h.await{it.error!=null&&!it.busy};assertNotNull(h.ui().pending)
        val policy=SqlLocalProcessingRepository(h.f.gateway);val off=policy.change(LocalProcessingChange(policy.read(h.f.source),LocalProcessingChoice.AI_NONE,"off-fact"))
        policy.change(LocalProcessingChange(off,LocalProcessingChoice.LOCAL_ONLY,"on-fact"))
        h.vm.refreshLocalSearch();h.vm.changeText("合成来源刷新屏障")
        withTimeout(3_000){h.vm.state.first{it.text=="合成来源刷新屏障"&&it.draftNotice=="草稿已保存在本机"}}
        assertNull(h.vm.state.value.sourceFacts);h.vm.sourceFacts.retry(u.session);assertEquals(1,h.writes.size)
        assertTrue(h.store.facts(h.f.source).isEmpty())
        assertEquals(listOf("INVALIDATED",0L),h.f.db.read("SELECT state,length(payload) FROM local_candidate_batches WHERE ruleVersion=6").single())
    }}
    @Test(timeout=15_000) fun favoriteReadCanObserveTargetGenerationWithoutRetiringTheValidBPageButOldEndpointMustNotNavigate():Unit=runBlocking {Harness().use{h->
        h.ready();val p=h.store.preview(h.f.source);val a0=h.store.create(FactCreate(id(),p,id(),"合成历史主题",FactContent(SourceMemoryRules.reference(p,"我不喝咖啡🙂")),true,id(),10))
        val a=h.store.mutate(FactMutation(a0,FactAction.ACCEPT,null,null,id(),11))
        val bSource=SourceVersion("synthetic-vm-endpoint-b",1);val text="我平时喝淡茶"
        h.f.db.transaction{sql->sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,5,'UTC','LOCAL_ONLY')",listOf(bSource.sourceId))
            sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,5)",listOf(bSource.sourceId,text,OriginalExports.hash(text)))}
        val bp=h.store.preview(bSource);val b0=h.store.create(FactCreate(id(),bp,a.topicId!!,a.topicLabel!!,FactContent(SourceMemoryRules.reference(bp,text)),true,id(),20,a))
        val b=h.store.mutate(FactMutation(b0,FactAction.ACCEPT,null,a,id(),21))
        h.vm.refreshLocalSearch();withTimeout(3_000){h.vm.state.first{it.entries.any{e->e.id==bSource.sourceId}&&it.localProcessingVersions.containsKey(bSource.sourceId)}}
        h.vm.sourceFacts.open(section=FactReview.ACCEPTED);val ui=h.await{it.page!=null&&!it.busy};assertEquals(listOf(b.id),ui.items.map{it.id})
        val deferred=kotlinx.coroutines.CompletableDeferred<UnifiedKeywordHandoff>()
        h.vm.sourceFacts.endpoint(ui.session,ui.items.single(),b.review!!.predecessor!!){_,proof->deferred.complete(proof)}
        val proof=withTimeout(3_000){deferred.await()}
        val policy=SqlLocalProcessingRepository(h.f.gateway);val off=policy.change(LocalProcessingChange(policy.read(a.source),LocalProcessingChoice.AI_NONE,"vm-endpoint-off"))
        policy.change(LocalProcessingChange(off,LocalProcessingChoice.LOCAL_ONLY,"vm-endpoint-on"))
        // Actual production read entry publishes versions without routing through the blanket
        // refreshProcessingVersions retirement. The adapter deliberately retains delivery.
        h.vm.favorites.refresh();withTimeout(3_000){h.vm.state.first{!it.favoritesLoading&&it.localProcessingVersions[a.source.sourceId]?.generation==2L}}
        assertEquals(ui.session,h.ui().session);assertTrue(h.vm.state.value.canRetainFactSources(h.ui()))
        var nav=0;assertFalse("Observed A generation cannot authorize the old private target",proof{nav++});assertEquals(0,nav)
    }}
    private fun id()=java.util.UUID.randomUUID().toString()
    private class Harness:AutoCloseable {
        val f=LocalCandidateFixture();val store=SqlSourceFactRepository(f.gateway,LocalCandidateFixture.KEY)
        val writes=CopyOnWriteArrayList<FactCreate>();@Volatile var loseAck=false;@Volatile var blockDraft=false
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val port=object:SourceFactRepository by store {
            override fun create(request:FactCreate,checkCurrent:()->Unit):SourceFactSnapshot {
                writes+=request;return store.create(request,checkCurrent).also{if(loseAck){loseAck=false;throw FactUnavailable()}}
            }
        }
        val base=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)){f.gateway.stopAndAwaitIdle();store.wipeKey();f.store.wipeKey()}
        val owner:EntryRepository=object:EntryRepository by base,AutoCloseable {
            override val sourceFacts=port
            override val localProcessing=SqlLocalProcessingRepository(f.gateway)
            override val favorites=SqlRecordFavoriteRepository(f.gateway)
            override suspend fun updateDraft(text:String):TextDraft {if(blockDraft){blockDraft=false;entered.countDown();check(release.await(3,TimeUnit.SECONDS))};return base.updateDraft(text)}
            override suspend fun prepare(){check(f.db.read("PRAGMA user_version").single().single()==10L)}
            override fun close()=base.close()
        }
        val vm=LocalRecordsViewModel(Application(),{owner},Clock.fixed(Instant.ofEpochMilli(50),ZoneOffset.UTC),bindAi={error("No model call")})
        suspend fun ready()=withTimeout(3_000){vm.state.first{it.ready&&!it.opening&&it.supportsSourceFacts}}
        fun ui()=checkNotNull(vm.state.value.sourceFacts)
        suspend fun await(test:(FactUiState)->Boolean)=withTimeout(3_000){vm.state.first{it.sourceFacts?.let(test)==true}.sourceFacts!!}
        suspend fun open():FactUiState{vm.sourceFacts.open(f.source);return await{it.sourcePage!=null&&!it.busy}}
        fun form(s:Long){vm.sourceFacts.edit(s,FactInput.QUOTE,"我不喝咖啡🙂");vm.sourceFacts.edit(s,FactInput.TOPIC,"合成饮品自述");vm.sourceFacts.affirm(s,true)}
        override fun close(){release.countDown();vm.clearForHostTest();assertTrue(base.closed.await(3,TimeUnit.SECONDS));base.closeFailure.get()?.let{throw it};f.close()}
    }
}
