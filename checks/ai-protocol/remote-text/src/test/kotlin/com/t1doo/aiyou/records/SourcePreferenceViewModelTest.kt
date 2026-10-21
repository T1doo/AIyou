package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SourcePreferenceViewModelTest {
    @Test(timeout=15_000) fun realActorNeedsTwoConfirmationsCorrectionReentersPendingAndRevokeCancelWritesNothing():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.form(ui)
        h.vm.sourcePreferences.save(ui.session);val cancelled=h.value().confirmation!!
        h.vm.sourcePreferences.cancel(ui.session,cancelled);assertTrue(h.f.db.read("SELECT * FROM local_candidate_batches").isEmpty())
        h.vm.sourcePreferences.save(ui.session);val fresh=h.value().confirmation!!
        h.vm.sourcePreferences.confirm(ui.session,cancelled);assertSame(fresh,h.value().confirmation)
        h.confirm();var item=h.value().items.single();assertEquals(PreferenceReview.PROPOSED,item.review!!.review)
        h.vm.sourcePreferences.action(ui.session,item,PreferenceAction.ACCEPT);h.ui {it.confirmation!=null};h.confirm()
        item=h.value().items.single();assertEquals(PreferenceReview.ACCEPTED,item.review!!.review)
        h.vm.sourcePreferences.correct(ui.session,item)
        h.vm.sourcePreferences.edit(ui.session,"我不喝咖啡🙂",h.value().topicLabel,"2026-10-21","","UTC",true)
        h.vm.sourcePreferences.save(ui.session);h.confirm();item=h.value().items.single()
        assertEquals(PreferenceReview.PROPOSED,item.review!!.review);assertEquals(PreferenceReview.ACCEPTED,item.reviews.first().priorReview)
        h.vm.sourcePreferences.action(ui.session,item,PreferenceAction.ACCEPT);h.ui {it.confirmation!=null};h.confirm();item=h.value().items.single()
        h.vm.sourcePreferences.action(ui.session,item,PreferenceAction.REVOKE);val before=h.ui {it.confirmation!=null};val version=item.rowVersion
        h.vm.sourcePreferences.cancel(ui.session,before.confirmation!!);assertEquals(version,h.store.preferences(h.f.source).single().rowVersion)
        h.vm.sourcePreferences.action(ui.session,item,PreferenceAction.REVOKE);h.ui {it.confirmation!=null};h.confirm()
        assertEquals(PreferenceReview.REVOKED,h.value().items.single().review!!.review);assertEquals(0,h.remoteCalls)
    } }
    @Test(timeout=15_000) fun unknownCommitUsesSameImmutableIntentAndOldSessionNeverWrites():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.form(ui);h.loseAck=true
        h.vm.sourcePreferences.save(ui.session);val confirmation=h.value().confirmation!!;h.vm.sourcePreferences.confirm(ui.session,confirmation)
        val failed=h.ui {it.error!=null&&!it.busy};assertTrue(failed.items.isEmpty());assertNull(failed.notice);assertNotNull(failed.pending)
        h.vm.sourcePreferences.retry(ui.session);h.ui {it.notice!=null&&!it.busy}
        assertSame(h.creates[0],h.creates[1]);assertEquals(1L,h.store.preferences(h.f.source).single().rowVersion)
        h.vm.sourcePreferences.close(ui.session);val fresh=h.open();h.vm.sourcePreferences.confirm(ui.session,confirmation)
        assertEquals(fresh.session,h.value().session);assertEquals(2,h.creates.size)
    } }
    @Test(timeout=15_000) fun policyAbaRetiresPreviewAndQueuedOldConfirmationCannotPublishOrCommit():Unit=Harness().use { h -> runBlocking {
        h.ready();val ui=h.open();h.form(ui);h.vm.sourcePreferences.save(ui.session);val confirmation=h.value().confirmation!!
        val policy=SqlLocalProcessingRepository(h.f.gateway)
        h.f.db.transaction {
            val none=policy.change(LocalProcessingChange(policy.read(h.f.source),LocalProcessingChoice.AI_NONE,"off"))
            policy.change(LocalProcessingChange(none,LocalProcessingChoice.LOCAL_ONLY,"on"))
        }
        h.vm.refreshLocalSearch();h.vm.changeText("合成 actor 屏障")
        withTimeout(3_000) {h.vm.state.first {it.draftNotice=="草稿已保存在本机"}}
        assertNull(h.vm.state.value.sourcePreferences);h.vm.sourcePreferences.confirm(ui.session,confirmation)
        assertTrue(h.creates.isEmpty());assertEquals(0,h.remoteCalls)
    } }
    @Test(timeout=15_000) fun browseAuthenticatesAcceptedSourceAndExactCodePointLocation():Unit=Harness().use { h -> runBlocking {
        h.ready();val p=h.store.preview(h.f.source)
        val created=h.store.create(PreferenceCreate(java.util.UUID.randomUUID().toString(),p,java.util.UUID.randomUUID().toString(),"合成口味",PreferenceContent(SourceMemoryRules.reference(p,"我不喝咖啡🙂")),true,java.util.UUID.randomUUID().toString(),40))
        h.store.mutate(SourcePreferenceSqlTest.change(created,PreferenceAction.ACCEPT))
        h.vm.sourcePreferences.open(section=PreferenceReview.ACCEPTED);val ui=h.ui {it.page!=null&&!it.busy};var location:OriginalReadingLocation?=null
        h.vm.sourcePreferences.locate(ui.session,ui.items.single()) {location=it}
        withTimeout(3_000) {h.vm.state.first {it.sourcePreferences==null}}
        assertEquals(0,location!!.startCodePoint);assertEquals(6,location!!.endCodePoint);assertEquals(OriginalExports.hash(h.f.original),location!!.contentHash)
        assertEquals(0,h.remoteCalls)
    } }
    private class Harness:AutoCloseable {
        val f=LocalCandidateFixture();val store=SqlSourcePreferenceRepository(f.gateway,LocalCandidateFixture.KEY)
        val creates=CopyOnWriteArrayList<PreferenceCreate>();@Volatile var loseAck=false;var remoteCalls=0
        private val port=object:SourcePreferenceRepository by store {
            override fun create(request:PreferenceCreate)=store.create(request).also {creates+=request;if(loseAck) {loseAck=false;throw PreferenceUnavailable()}}
        }
        private val base=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)) {f.gateway.stopAndAwaitIdle();store.wipeKey();f.store.wipeKey()}
        private val owner=object:EntryRepository by base,AutoCloseable {
            override val sourcePreferences=port
            override val localProcessing=SqlLocalProcessingRepository(f.gateway)
            override val sourceBoundText:SourceBoundTextRepository? get() {remoteCalls++;error("No remote authority")}
            override suspend fun prepare() {check(f.db.read("PRAGMA user_version").single().single()==10L)}
            override fun close()=base.close()
        }
        val vm=LocalRecordsViewModel(Application(),{owner},Clock.fixed(Instant.ofEpochMilli(50),ZoneOffset.UTC),bindAi={remoteCalls++;error("No remote exchange")})
        suspend fun ready()=withTimeout(3_000) {vm.state.first {it.ready&&!it.opening&&it.supportsSourcePreferences}}
        fun value()=checkNotNull(vm.state.value.sourcePreferences)
        suspend fun open():PreferenceUiState {vm.sourcePreferences.open(f.source);return ui {it.sourcePage!=null&&!it.busy}}
        suspend fun ui(test:(PreferenceUiState)->Boolean)=withTimeout(3_000) {vm.state.first {it.sourcePreferences?.let(test)==true}.sourcePreferences!!}
        fun form(u:PreferenceUiState)=vm.sourcePreferences.edit(u.session,"我不喝咖啡🙂","合成口味","","","",true)
        suspend fun confirm() {val u=value();vm.sourcePreferences.confirm(u.session,u.confirmation!!);ui {it.notice!=null&&!it.busy}}
        override fun close() {vm.clearForHostTest();assertTrue(base.closed.await(3,TimeUnit.SECONDS));base.closeFailure.get()?.let {throw it};f.close()}
    }
}
