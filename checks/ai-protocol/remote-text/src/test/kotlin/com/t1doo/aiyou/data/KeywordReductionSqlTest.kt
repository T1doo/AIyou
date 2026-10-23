package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual SQL publication/commit barriers and production controllers, synthetic data only. */
class KeywordReductionSqlTest {
    @Test fun originalWordsKeepDuplicatesUnicodeAndExistingMatcherWhitespaceWithoutChangingNormalDomain(){
        val u="蓝绳\u00a0𠀀\t蓝绳";assertEquals(listOf("蓝绳","𠀀","蓝绳"),originalKeywordTerms(u))
        val d=checkNotNull(KeywordReductionDraft.from(u));assertEquals(listOf("蓝绳","𠀀","蓝绳"),d.tokens);assertFalse(d.canConfirm)
        assertEquals(listOf("𠀀","蓝绳"),d.toggle(0)!!.remaining)
        assertNull(KeywordReductionDraft.from("一词"));assertNull(KeywordReductionDraft.from((1..9).joinToString(" ")))
        assertNotNull(KeywordReductionDraft.from("a "+"𠀀".repeat(62)))
        assertNull(KeywordReductionDraft.from("a "+"𠀀".repeat(63)))
        assertNull(KeywordReductionDraft.from("a "+String(charArrayOf(0xd800.toChar()))));assertNull(KeywordReductionDraft.from("a b\u0000"))
        assertNull(KeywordReductionDraft.from("x".repeat(1_000_000)))
    }
    @Test fun confirmedReductionPreservesWholeCapturedAndEventFilterTupleAndExactOriginal():Unit=runBlocking{
        for(basis in RecordDateBasis.values())for(openEnd in listOf("none","from","through"))H(filters=true,basis=basis,auto=false).use{h->
            if(openEnd!="none")h.input.set(h.input.get().copy(from=if(openEnd=="from")""else h.input.get().from,through=if(openEnd=="through")""else h.input.get().through,ownership=Any()))
            h.search.sync(true)
            val old=h.ready();val before=h.input.get();val reads=h.reads.get();assertTrue(old.hits.isEmpty())
            assertTrue(h.helper.openReduction(old,h.search));assertFalse(h.search.admitsEmptyReview(old))
            h.helper.toggleReduction(h.ui(),1);assertEquals(reads,h.reads.get());assertSame(before,h.input.get())
            val commit=h.ui();h.helper.confirm(commit);h.helper.confirm(commit);assertEquals(1,h.commits.get())
            val now=h.input.get();assertNotSame(before.ownership,now.ownership)
            assertEquals(before.copy(query="蓝绳",ownership=now.ownership),now)
            assertEquals(before.tagSnapshot,now.tagSnapshot);assertSame(before.peopleSnapshot,now.peopleSnapshot)
            h.search.sync(true);val result=h.ready();assertEquals((listOf(h.ids[0])+if((basis==RecordDateBasis.CAPTURED&&openEnd=="from")||(basis==RecordDateBasis.EVENT&&openEnd=="through"))listOf(h.ids[1])else emptyList()).toSet(),result.hits.map{it.entry.id}.toSet())
            val opened=CompletableDeferred<Unit>();h.search.open(result,result.hits.first()){opened.complete(Unit)};withTimeout(3000){opened.await()}
            assertEquals("蓝绳放在木箱边。",result.hits.first().entry.originalText)
        }
    }
    @Test fun actorRejectsNoDeletionAllDeletedQuestionAndDateMutatorsAndStaleMasks():Unit=runBlocking{H().use{h->
        val base=h.ready();assertTrue(h.helper.openReduction(base,h.search));val first=h.ui()
        h.helper.question(first,"其他问题");h.helper.terms(first,"其他词");h.helper.from(first,"2020-01-01");h.helper.through(first,"2029-01-01")
        h.helper.basis(first,RecordDateBasis.EVENT);h.helper.dateChoice(first,QuestionDateChoice.UNLIMITED)
        h.helper.choose(first,QuestionKeywordFragments.propose("其他词").values.first());assertSame(first,h.ui())
        h.helper.confirm(first);assertEquals(0,h.commits.get());h.helper.toggleReduction(h.ui(),0);val one=h.ui()
        h.helper.toggleReduction(one,1);h.helper.confirm(h.ui());assertEquals(0,h.commits.get())
        h.helper.toggleReduction(h.ui(),0);val latest=h.ui();h.helper.toggleReduction(first,1);assertSame(latest,h.ui())
        h.helper.confirm(latest);assertEquals(1,h.commits.get());assertEquals("蓝绳",h.input.get().query)
    }}
    @Test(timeout=15000) fun intermediateReadyCannotAdmitBeforeRealCommitAndRetiredAckNeverRegrants():Unit=runBlocking{
        for(retire in listOf(false,true))H(auto=false).use{h->
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            h.duringPublish={entered.countDown();check(release.await(3,TimeUnit.SECONDS))}
            try{
                h.search.sync(true);check(entered.await(3,TimeUnit.SECONDS));val intermediate=h.search.state.value
                assertEquals(OriginalSearchStage.READY,intermediate.stage);assertFalse(h.helper.openReduction(intermediate,h.search))
                assertNull(h.helper.state.value);if(retire)h.search.invalidateInput()
                release.countDown()
                if(retire){withTimeout(3000){h.acknowledged.await()};assertFalse(h.helper.openReduction(intermediate,h.search))}
                else{h.awaitAdmitted(intermediate);assertTrue(h.helper.openReduction(intermediate,h.search))}
            }finally{release.countDown()}
        }
    }
    @Test(timeout=15000) fun failedAndLostCommitAcknowledgementNeverGrantAnEmptyReview():Unit=runBlocking{
        for(after in listOf(false,true))H(auto=false).use{h->
            val seen=CountDownLatch(1);h.duringPublish={seen.countDown()}
            if(after)h.f.f.db.afterSql={if(it=="COMMIT"&&seen.count==0L)error("Synthetic lost commit ACK")}
            else h.f.f.db.beforeSql={if(it=="COMMIT"&&seen.count==0L)error("Synthetic precommit failure")}
            h.search.sync(true);check(h.invalidated.await(3,TimeUnit.SECONDS));val state=h.search.state.value
            assertEquals(OriginalSearchStage.WAITING,state.stage);assertFalse(h.helper.openReduction(state,h.search));assertNull(h.helper.state.value)
            h.f.f.db.beforeSql=null;h.f.f.db.afterSql=null
        }
    }
    @Test fun cancelFreshProofPauseOwnerSourceAbaAndOldCallbacksNeverSubmit():Unit=runBlocking{H().use{h->
        val old=h.ready();h.helper.openReduction(old,h.search);val abandoned=h.ui();h.helper.toggleReduction(abandoned,1)
        h.helper.cancel(h.ui());assertEquals("蓝绳 悬挂",h.input.get().query);h.search.sync(true)
        val fresh=h.ready();assertNotSame(old.proof,fresh.proof);assertFalse(h.helper.openReduction(old,h.search))
        h.helper.openReduction(fresh,h.search);val second=h.ui();h.helper.confirm(abandoned);assertSame(second,h.ui())
        h.helper.foreground(false);h.helper.confirm(second);assertNull(h.helper.state.value);assertEquals(0,h.commits.get())
        h.helper.foreground(true);h.search.sync(true);h.helper.openReduction(h.ready(),h.search);val third=h.ui()
        h.source.set(h.source.get().copy(localSearchEpoch=h.source.get().localSearchEpoch!!+1));h.helper.sync();assertNull(h.helper.state.value)
        h.helper.confirm(third);assertEquals(0,h.commits.get())
    }}
    @Test fun externalDeletionBeforeNewLookupCannotPublishOldSource():Unit=runBlocking{H().use{h->
        h.helper.openReduction(h.ready(),h.search);h.helper.toggleReduction(h.ui(),1)
        for(id in h.ids)h.f.f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",id)
        h.helper.confirm(h.ui());h.search.sync(true)
        val result=withTimeout(3000){h.search.state.first{it.stage==OriginalSearchStage.READY}};assertTrue(result.hits.isEmpty())
    }}
    @Test(timeout=15000) fun confirmationAndConcurrentNavigationUseTheSameOrderAndCannotDoubleCommit():Unit=runBlocking{H().use{h->
        h.helper.openReduction(h.ready(),h.search);h.helper.toggleReduction(h.ui(),1);val selected=h.ui()
        val held=CountDownLatch(1);val contender=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        h.beforeBinding={held.countDown();check(contender.await(3,TimeUnit.SECONDS));h.beforeBinding=null}
        try{
            val confirm=pool.submit{h.helper.confirm(selected)}
            val navigation=pool.submit{check(held.await(3,TimeUnit.SECONDS));contender.countDown();h.gate.advance()}
            confirm.get(4,TimeUnit.SECONDS);navigation.get(4,TimeUnit.SECONDS);h.helper.confirm(selected);assertEquals(1,h.commits.get())
        }finally{pool.shutdownNow();check(pool.awaitTermination(3,TimeUnit.SECONDS))}
    }}
    @Test fun observedPermissionMetadataOwnerAndInputAbaRetireDraftWithoutCommit():Unit=runBlocking{
        for(kind in listOf("permission","tags","people","owner","entries","input","navigation"))H().use{h->
            h.helper.openReduction(h.ready(),h.search);h.helper.toggleReduction(h.ui(),1);val old=h.ui();val before=h.source.get()
            when(kind){
                "permission"->h.source.set(before.copy(localProcessingVersions=HashMap(before.localProcessingVersions)))
                "tags"->h.source.set(before.copy(manualTags=HashMap(before.manualTags)))
                "people"->h.source.set(before.copy(manualPeople=HashMap(before.manualPeople)))
                "owner"->h.source.set(before.copy(localSearch=object:LocalSearchRepository by h.owner{}))
                "entries"->h.source.set(before.copy(entries=ArrayList(before.entries)))
                "input"->{val start=h.input.get();h.search.changeInput{h.input.set(start.copy(query="中间输入",ownership=Any()))};h.search.changeInput{h.input.set(start.copy(ownership=Any()))}}
                "navigation"->h.gate.advance()
            }
            h.helper.sync();h.helper.confirm(old);assertNull(h.helper.state.value);assertEquals(0,h.commits.get())
        }
    }
    @Test fun identicalInputFromForeignAcknowledgedOwnerCannotAdmitTheOtherDraft():Unit=runBlocking{
        H().use{a->H(auto=false,entryIds=a.ids).use{b->
            val own=a.ready();val input=a.input.get()
            // The metadata snapshots are identity objects. Share the same input/snapshot
            // objects over identical synthetic rows, while both SQL owners remain distinct.
            b.source.set(b.source.get().copy(manualTags=checkNotNull(input.tagSnapshot),
                manualPeople=checkNotNull(input.peopleSnapshot),favorites=checkNotNull(input.favoriteSnapshot),
                eventDates=checkNotNull(input.eventDateSnapshot)))
            b.input.set(input);assertSame(a.input.get(),b.input.get());assertNotSame(a.owner,b.owner)
            b.search.sync(true);val foreign=b.ready();assertTrue(b.search.admitsEmptyReview(foreign))
            assertFalse(a.helper.openReduction(foreign,b.search));assertNull(a.helper.state.value)
            assertTrue(a.helper.openReduction(own,a.search))
        }}
    }
    private class H(filters:Boolean=false,basis:RecordDateBasis=RecordDateBasis.CAPTURED,auto:Boolean=true,entryIds:List<String>?=null):AutoCloseable{
        val f=UnifiedKeywordFixture(withPeople=true);val ids=entryIds?:List(if(filters)4 else 1){UUID.randomUUID().toString()}
        val dates=SqlRecordEventDateRepository(f.f.gateway);val favorites=SqlRecordFavoriteRepository(f.f.gateway)
        val reads=AtomicInteger();val commits=AtomicInteger();val invalidated=CountDownLatch(1)
        val acknowledged=CompletableDeferred<Unit>();@Volatile var duringPublish:()->Unit={};@Volatile var beforeBinding:(()->Unit)?=null
        val owner=object:LocalSearchRepository by f.originals{
            override fun publishCurrent(snapshot:LocalSearchSnapshot,publish:()->Unit):Boolean=f.originals.publishCurrent(snapshot){publish();duringPublish()}.also{acknowledged.complete(Unit)}
        }
        val input:AtomicReference<OriginalSearchInput>;val source:AtomicReference<LocalRecordUiState>
        private var changed:(()->Unit)?=null
        val gate=SearchNavigationGate{changed?.invoke()};val search:LocalOriginalSearchController;val helper:QuestionKeywordController
        init{
            runBlocking{ids.forEachIndexed{i,id->
                val time=Instant.parse(if(i==1)"2026-09-28T16:30:00Z"else"2026-09-30T16:30:00Z").toEpochMilli();val text="蓝绳放在木箱边。"
                f.f.db.transaction{it.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,?,'Asia/Shanghai','LOCAL_ONLY')",listOf(id,time));it.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,?)",listOf(id,text,OriginalExports.hash(text),time));it.execute("INSERT INTO remote_source_access VALUES(?,1,0,NULL,NULL)",listOf(id))}
                if(filters){
                    f.f.tags.replace(ManualTagMutation(id,0,listOf(if(i==2)"其他标签"else"绳具"),UUID.randomUUID().toString()))
                    f.people.replace(ManualPeopleMutation(f.people.read(id),listOf(if(i==2)"其他人物"else"合成甲"),UUID.randomUUID().toString()))
                    if(i!=2)favorites.set(RecordFavoriteMutation(id,1,OriginalExports.hash(text),0,true,UUID.randomUUID().toString(),1))
                    dates.set(RecordEventDateMutation(id,1,OriginalExports.hash(text),0,RecordEventDate(if(i==1)"2026-10-03"else"2026-10-02","Asia/Shanghai"),UUID.randomUUID().toString(),1))
                    if(i!=3)f.f.image(id)
                }
            }}
            while(f.originals.batch().remaining){}
            source=AtomicReference(LocalRecordUiState(ready=true,opening=false,entries=f.originals.candidates(LocalSearchRequest("")).entries,localSearch=owner,localSearchEpoch=owner.epoch(),manualTags=f.f.tags.snapshots(),manualTagsAvailable=true,manualPeople=f.people.snapshots(),manualPeopleAvailable=true,favorites=favorites.snapshots(),favoritesAvailable=true,eventDates=dates.snapshots(),eventDatesAvailable=true))
            input=AtomicReference(OriginalSearchInput("蓝绳 悬挂",if(filters)if(basis==RecordDateBasis.CAPTURED)"2026-10-01"else"2026-10-02"else"",if(filters)if(basis==RecordDateBasis.CAPTURED)"2026-10-01"else"2026-10-02"else"",ownership=Any(),tag=if(filters)"绳具"else null,tagSnapshot=source.get().manualTags,person=if(filters)"合成甲"else null,peopleSnapshot=source.get().manualPeople,onlyFavorites=filters,favoriteSnapshot=source.get().favorites,dateBasis=basis,eventDateSnapshot=source.get().eventDates,onlyImages=filters))
            search=LocalOriginalSearchController(source::get,input::get,onInvalidated={invalidated.countDown()},arbitrateNavigation=gate::arbitrate,matcher={e,i->reads.incrementAndGet();LocalOriginalSearchMatcher.indexed(e,i,owner)})
            helper=QuestionKeywordController(gate,currentBinding={beforeBinding?.invoke();QuestionSearchBinding(gate.generation,source.get(),input.get())},retireSearch=search::invalidateInput,commit={r->search.tryChangeInput{commits.incrementAndGet();input.set(input.get().copy(query=r.query,from=r.from,through=r.through,dateBasis=r.basis,mode=OriginalSearchMode.KEYWORDS,ownership=Any()))}})
            changed=helper::retire;helper.foreground(true);if(auto)search.sync(true)
        }
        fun ui()=checkNotNull(helper.state.value)
        suspend fun awaitAdmitted(v:OriginalSearchState)=withTimeout(3000){while(!search.admitsEmptyReview(v)){delay(1)}}
        suspend fun ready():OriginalSearchState{
            val value=withTimeout(3000){search.state.first{it.stage==OriginalSearchStage.READY}}
            if(value.hits.isEmpty())awaitAdmitted(value)
            return value
        }
        override fun close(){helper.dispose();search.close();f.f.db.beforeSql=null;f.f.db.afterSql=null;f.close()}
    }
}
