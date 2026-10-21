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
import kotlinx.coroutines.async
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
        h.vm.sourcePreferences.open(section=PreferenceReview.ACCEPTED);val ui=h.ui {it.page!=null&&!it.busy};val located=kotlinx.coroutines.CompletableDeferred<OriginalReadingLocation>()
        h.vm.sourcePreferences.locate(ui.session,ui.items.single()) {located.complete(it)}
        withTimeout(3_000) {h.vm.state.first {it.sourcePreferences==null}}
        val location=withTimeout(3_000) {located.await()}
        assertEquals(0,location.startCodePoint);assertEquals(6,location.endCodePoint);assertEquals(OriginalExports.hash(h.f.original),location.contentHash)
        assertEquals(0,h.remoteCalls)
    } }
    @Test(timeout=20_000) fun crossPageReplacementConfirmationRetiresWhenItsUndisplayedOldSourceChanges():Unit=runBlocking {
        for(mode in 0..2) Harness().use {h ->
            h.ready();val (old,candidate)=h.seedComparison();h.observeSources()
            h.vm.sourcePreferences.open();val ui=h.ui {it.page!=null&&!it.busy}
            assertEquals(20,ui.items.size);assertTrue(ui.items.none {it.source==old.source})
            h.vm.sourcePreferences.action(ui.session,ui.items.single {it.id==candidate.id},PreferenceAction.ACCEPT)
            val comparison=h.ui {it.confirmation!=null}.confirmation!!
            assertEquals(old.id,(comparison.intent as PreferenceIntent.Mutate).request.replacing!!.id)
            h.invalidate(old.source,mode);h.observeSources()
            assertNull(h.vm.state.value.sourcePreferences)
            h.vm.sourcePreferences.confirm(ui.session,comparison);assertTrue(h.mutations.isEmpty())
            assertEquals(PreferenceReview.PROPOSED,h.store.preferences(candidate.source).single {it.id==candidate.id}.review!!.review)
        }
    }
    @Test(timeout=20_000) fun unknownReplacementIntentRetiresBothRetainedSourcesWithoutReplayingACommittedWrite():Unit=runBlocking {
        for(oldSource in listOf(true,false)) Harness().use {h ->
            h.ready();val (old,candidate)=h.seedComparison();h.observeSources()
            h.vm.sourcePreferences.open();val ui=h.ui {it.page!=null&&!it.busy}
            h.vm.sourcePreferences.action(ui.session,ui.items.single {it.id==candidate.id},PreferenceAction.ACCEPT)
            val comparison=h.ui {it.confirmation!=null}.confirmation!!;h.loseMutationAck=true
            h.vm.sourcePreferences.confirm(ui.session,comparison)
            val unknown=h.ui {it.error!=null&&!it.busy};assertNotNull(unknown.pending);assertNull(unknown.page);assertNull(unknown.sourcePage);assertTrue(unknown.items.isEmpty())
            assertEquals(1,h.mutations.size)
            h.invalidate(if(oldSource)old.source else candidate.source,0);h.observeSources()
            assertNull(h.vm.state.value.sourcePreferences);h.vm.sourcePreferences.retry(ui.session);assertEquals(1,h.mutations.size)
        }
    }
    @Test(timeout=15_000) fun unknownCreateIntentCannotRetainQuoteAcrossObservedSameVersionPolicyAba():Unit=Harness().use {h ->runBlocking {
        h.ready();val ui=h.open();h.form(ui);h.loseAck=true
        h.vm.sourcePreferences.save(ui.session);h.vm.sourcePreferences.confirm(ui.session,h.value().confirmation!!)
        val unknown=h.ui {it.error!=null&&!it.busy};assertTrue(unknown.pending is PreferenceIntent.Create);assertNull(unknown.sourcePage)
        val policy=SqlLocalProcessingRepository(h.f.gateway)
        val none=policy.change(LocalProcessingChange(policy.read(h.f.source),LocalProcessingChoice.AI_NONE,"pending-off"))
        policy.change(LocalProcessingChange(none,LocalProcessingChoice.LOCAL_ONLY,"pending-on"));h.observeSources()
        assertNull(h.vm.state.value.sourcePreferences);h.vm.sourcePreferences.retry(ui.session);assertEquals(1,h.creates.size)
    }}
    @Test(timeout=15_000) fun unknownCreateRetiresWhenOnlyItsChosenTopicSnapshotSourceBecomesUnavailable():Unit=Harness().use {h ->runBlocking {
        h.ready();val original=h.store.preview(h.f.source)
        val old=h.store.create(PreferenceCreate(SourcePreferenceSqlTest.id(),original,SourcePreferenceSqlTest.id(),"合成口味",PreferenceContent(SourceMemoryRules.reference(original,"我不喝咖啡🙂")),true,SourcePreferenceSqlTest.id(),10))
        val source=SourceVersion("synthetic-new-create-source",1);val text="合成本人自述：我平时更喜欢淡茶。"
        h.f.db.transaction {sql ->
            sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,5,'UTC','LOCAL_ONLY')",listOf(source.sourceId))
            sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,5)",listOf(source.sourceId,text,OriginalExports.hash(text)))
        }
        h.observeSources();h.vm.sourcePreferences.open(source);val ui=h.ui {it.sourcePage!=null&&!it.busy}
        h.vm.sourcePreferences.topic(ui.session,ui.sourcePage!!.topics.single {it.id==old.id})
        h.vm.sourcePreferences.edit(ui.session,"我平时更喜欢淡茶","合成口味","","","",true);h.loseAck=true
        h.vm.sourcePreferences.save(ui.session);h.vm.sourcePreferences.confirm(ui.session,h.value().confirmation!!)
        val unknown=h.ui {it.error!=null&&!it.busy};val intent=(unknown.pending as PreferenceIntent.Create).request
        assertEquals(source,intent.preview.source);assertEquals(old.source,intent.topicExpected!!.source);assertNull(unknown.sourcePage);assertTrue(unknown.items.isEmpty())
        h.invalidate(old.source,0);h.observeSources();assertNull(h.vm.state.value.sourcePreferences)
        h.vm.sourcePreferences.retry(ui.session);assertEquals(1,h.creates.size)
        assertEquals(1,h.store.preferences(source).size)
    }}
    @Test(timeout=15_000) fun lateComparisonChecksBothActualSourceSnapshotsBeforePublishingPrivatePreview():Unit=Harness().use {h ->runBlocking {
        h.ready();val (old,candidate)=h.seedComparison();h.observeSources()
        val local=java.util.concurrent.atomic.AtomicReference(h.vm.state.value)
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val queue=java.util.concurrent.ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
        val confirmations=java.util.concurrent.atomic.AtomicInteger()
        val delayed=object:SourcePreferenceRepository by h.store {
            override fun acceptedForTopic(expected:SourcePreferenceSnapshot):SourcePreferenceSnapshot? {
                val found=h.store.acceptedForTopic(expected);entered.countDown();check(release.await(3,TimeUnit.SECONDS));return found
            }
        }
        val owner=object:EntryRepository by h.owner {override val sourcePreferences=delayed}
        // Controlled publisher observes real SQL source changes while the actual controller's
        // comparison is in flight. Real VM reconciliation is exercised by the preceding cases.
        val controller=SourcePreferenceController({local.get()},{change ->
            val next=local.updateAndGet(change);if(next.sourcePreferences?.confirmation!=null)confirmations.incrementAndGet()
        },{queue.add(it);true},Clock.fixed(Instant.ofEpochMilli(50),ZoneOffset.UTC))
        try {
            controller.open();checkNotNull(queue.poll())(owner)
            val ui=local.get().sourcePreferences!!;controller.action(ui.session,ui.items.single {it.id==candidate.id},PreferenceAction.ACCEPT)
            val action=checkNotNull(queue.poll());val job=async(kotlinx.coroutines.Dispatchers.IO) {action(owner)}
            try {
                assertTrue(entered.await(3,TimeUnit.SECONDS));h.invalidate(old.source,0)
                val entries=h.owner.entries();val versions=SqlLocalProcessingRepository(h.f.gateway).versions()
                local.updateAndGet {it.copy(entries=entries,localProcessingVersions=versions)}
            } finally {release.countDown()}
            withTimeout(3_000) {job.await()}
            assertNull(local.get().sourcePreferences);assertEquals(0,confirmations.get());assertTrue(h.mutations.isEmpty())
        } finally {release.countDown();controller.dispose()}
    }}
    private class Harness:AutoCloseable {
        val f=LocalCandidateFixture();val store=SqlSourcePreferenceRepository(f.gateway,LocalCandidateFixture.KEY)
        val creates=CopyOnWriteArrayList<PreferenceCreate>();val mutations=CopyOnWriteArrayList<PreferenceMutation>()
        @Volatile var loseAck=false;@Volatile var loseMutationAck=false;var remoteCalls=0;private var barriers=0
        private val port=object:SourcePreferenceRepository by store {
            override fun create(request:PreferenceCreate)=store.create(request).also {creates+=request;if(loseAck) {loseAck=false;throw PreferenceUnavailable()}}
            override fun mutate(request:PreferenceMutation):SourcePreferenceSnapshot {mutations+=request;return store.mutate(request).also {if(loseMutationAck) {loseMutationAck=false;throw PreferenceUnavailable()}}}
        }
        private val base=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)) {f.gateway.stopAndAwaitIdle();store.wipeKey();f.store.wipeKey()}
        val owner:EntryRepository=object:EntryRepository by base,AutoCloseable {
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
        fun seedComparison():Pair<SourcePreferenceSnapshot,SourcePreferenceSnapshot> {
            fun create(source:SourceVersion,quote:String,at:Long,topic:SourcePreferenceSnapshot?=null):SourcePreferenceSnapshot {
                val p=store.preview(source)
                return store.create(PreferenceCreate(SourcePreferenceSqlTest.id(),p,topic?.topicId ?: SourcePreferenceSqlTest.id(),topic?.topicLabel ?: "合成口味",PreferenceContent(SourceMemoryRules.reference(p,quote)),true,SourcePreferenceSqlTest.id(),at,topic))
            }
            val old=store.mutate(SourcePreferenceSqlTest.change(create(f.source,"我不喝咖啡🙂",10),PreferenceAction.ACCEPT))
            val source=SourceVersion("synthetic-preference-next-source",1);val text="合成本人自述：我平时更喜欢淡茶。"
            f.db.transaction {sql ->
                sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,5,'UTC','LOCAL_ONLY')",listOf(source.sourceId))
                sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,5)",listOf(source.sourceId,text,OriginalExports.hash(text)))
            }
            val candidate=create(source,"我平时更喜欢淡茶",100,old)
            repeat(19) {create(source,"我平时更喜欢淡茶",101+it.toLong())}
            return old to candidate
        }
        fun invalidate(source:SourceVersion,mode:Int) {
            when(mode) {
                0 -> {val policy=SqlLocalProcessingRepository(f.gateway);policy.change(LocalProcessingChange(policy.read(source),LocalProcessingChoice.AI_NONE,SourcePreferenceSqlTest.id()))}
                1 -> f.db.transaction {sql ->
                    val text="合成修订后的原文";sql.execute("INSERT INTO entry_revisions VALUES(?,2,?,?,6)",listOf(source.sourceId,text,OriginalExports.hash(text)))
                    sql.execute("UPDATE entries SET currentRevision=2 WHERE id=?",listOf(source.sourceId))
                }
                else -> if(f.db.read("SELECT entryId FROM remote_source_access WHERE entryId=?",source.sourceId).isEmpty())
                    f.db.write("INSERT INTO remote_source_access VALUES(?,1,1,NULL,NULL)",source.sourceId)
                    else f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",source.sourceId)
            }
        }
        suspend fun observeSources() {
            val barrier="合成来源队列屏障"+(++barriers);vm.refreshLocalSearch();vm.changeText(barrier)
            withTimeout(3_000) {vm.state.first {it.text==barrier&&it.draftNotice=="草稿已保存在本机"}}
        }
        override fun close() {vm.clearForHostTest();assertTrue(base.closed.await(3,TimeUnit.SECONDS));base.closeFailure.get()?.let {throw it};f.close()}
    }
}
