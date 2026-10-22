package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.util.concurrent.TimeUnit

class UnifiedKeywordViewModelTest {
    @Test fun originalLocationUsesCodePointsAndTopicMatchLocatesActualQuoteInsteadOfTopicText():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="🙂合成 我偏好淡茶🙂");h.preference(id)
        val original=h.repo.search("淡茶",UnifiedKeywordScope.ORIGINAL).groups.single().items.single()
        val location=unifiedOriginalLocation(original,"淡茶")!!
        val body="🙂合成 我偏好淡茶🙂";val index=body.indexOf("淡茶")
        assertEquals(body.codePointCount(0,index),location.startCodePoint)
        assertEquals(body.codePointCount(0,index+2),location.endCodePoint)
        val preference=h.repo.search("口味",UnifiedKeywordScope.PREFERENCE).groups.single().items.single() as UnifiedKeywordEvidence.Preference
        val reference=preference.value.current!!.content.reference;val target=unifiedOriginalLocation(preference,"口味")!!
        assertEquals(reference.start,target.startCodePoint);assertEquals(reference.end,target.endCodePoint)
        assertEquals(id,target.entryId);assertEquals(com.t1doo.aiyou.model.OriginalExports.hash(body),target.contentHash)
        assertFalse(body.contains("口味"))
    }}
    @Test fun actualQueueRejectsFirstAWhenInputChangesAToBToAAndKeepsOnlyConfirmedLastSnapshot():Unit=runBlocking{Harness().use{h->
        h.ready();h.block=true;h.open("淡茶")
        withTimeout(4000){h.entered.await()}
        val old=h.value();h.vm.unifiedKeywords.keyword(old.session,"其他");h.vm.unifiedKeywords.search(old.session)
        h.vm.unifiedKeywords.keyword(old.session,"淡茶");h.vm.unifiedKeywords.search(old.session)
        assertTrue(h.value().visibleGroups.isEmpty());h.release.complete(Unit)
        val result=h.ui{it.snapshot!=null&&!it.busy}
        assertEquals("淡茶",result.keyword);assertNotSame(h.first,result.snapshot);assertEquals(2,h.calls)
        assertEquals(h.id,result.visibleGroups.single().binding.source.sourceId)
    }}
    @Test fun actualFinalCommitFailureAndLostCommitAckNeverPublishContentOrEmptySuccess():Unit=runBlocking{Harness().use{h->
        h.ready()
        for(lost in listOf(false,true)){
            var joint=false;var tripped=false
            h.data.f.db.beforeSql={if(it.startsWith("SELECT e.currentRevision,r.contentHash,COALESCE(a.generation,0),e.capturedAtMillis"))joint=true}
            if(lost)h.data.f.db.afterSql={if(it=="COMMIT"&&joint&&!tripped){tripped=true;error("Synthetic joint read commit ACK loss")}}
            else h.data.f.db.beforeCommit={if(joint){tripped=true;error("Synthetic joint read commit failure")}}
            try{h.open("淡茶");val failed=h.ui{it.error!=null&&!it.busy};assertNull(failed.snapshot);assertTrue(failed.visibleGroups.isEmpty());assertTrue(tripped)}
            finally{h.data.f.db.beforeSql=null;h.data.f.db.beforeCommit=null;h.data.f.db.afterSql=null;h.vm.unifiedKeywords.retire()}
        }
        h.open("淡茶");assertEquals(1,h.ui{it.snapshot!=null&&!it.busy}.visibleGroups.size)
    }}
    @Test fun retireDuringActualReadRejectsLateResponseAndAClosedSessionCannotNavigate():Unit=runBlocking{Harness().use{h->
        h.ready();h.block=true;h.open("淡茶");withTimeout(4000){h.entered.await()};val old=h.value()
        h.vm.unifiedKeywords.retire();h.release.complete(Unit)
        h.open("淡茶");val next=h.ui{it.snapshot!=null&&!it.busy};assertNotEquals(old.session,next.session)
        h.vm.unifiedKeywords.locate(old.session,next.visibleGroups.single().items.first()){fail("Retired session")}
        assertEquals(next.session,h.value().session)
    }}
    @Test fun lostFinalResolveCommitAckCannotNavigateFromItsPrivateAdmissionTicket():Unit=runBlocking{Harness().use{h->
        h.ready();h.open("淡茶");val u=h.ui{it.snapshot!=null&&!it.busy}
        val item=u.visibleGroups.single().items.first();var resolveCommit=false;var observed=false
        // search() reauthentication performs one aggregate commit first; the following
        // final old-proof binding check belongs to resolve's joint publication transaction.
        var aggregateReads=0
        h.data.f.db.beforeSql={if(it.startsWith("SELECT e.currentRevision,r.contentHash,COALESCE(a.generation,0),e.capturedAtMillis")){aggregateReads++;if(aggregateReads==2)resolveCommit=true}}
        h.data.f.db.afterSql={if(it=="COMMIT"&&resolveCommit&&!observed){observed=true;error("Synthetic final resolve committed ACK loss")}}
        try{
            h.vm.unifiedKeywords.locate(u.session,item){fail("Unconfirmed private ticket cannot navigate")}
            val failed=h.ui{it.error!=null&&!it.busy};assertTrue(observed);assertNull(failed.snapshot)
        }finally{h.data.f.db.beforeSql=null;h.data.f.db.afterSql=null}
    }}
    @Test fun actualOffPageSourceRevisionObservationRetiresAllProofAndDelayedClick():Unit=runBlocking{Harness(extra=22).use{h->
        h.ready();h.open("淡茶");val first=h.ui{it.snapshot!=null&&!it.busy};assertEquals(23,first.snapshot!!.groups.size)
        val offPage=first.snapshot.groups.last().binding.source.sourceId
        val visible=first.visibleGroups.first().items.first();h.data.f.revise(offPage,"合成新版本")
        h.vm.refreshLocalSearch();withTimeout(4000){h.vm.state.first{it.entries.any{e->e.id==offPage&&e.currentRevision==2}}}
        assertNull(h.vm.state.value.unifiedKeyword)
        h.vm.unifiedKeywords.locate(first.session,visible){fail("Off-screen source invalidation")}
    }}
    @Test fun actualPagingRevalidatesWholeSetAndWithdrawalOutsideCurrentPageClearsIt():Unit=runBlocking{Harness(extra=22).use{h->
        h.ready();h.vm.unifiedKeywords.open();var u=h.value();h.vm.unifiedKeywords.scope(u.session,UnifiedKeywordScope.AUDIO)
        h.vm.unifiedKeywords.keyword(u.session,"淡茶");h.vm.unifiedKeywords.search(u.session);u=h.ui{it.snapshot!=null&&!it.busy}
        val first=u.visibleGroups.map{it.key};h.vm.unifiedKeywords.next(u.session);val second=h.ui{it.page==1&&!it.busy}
        assertEquals(3,second.visibleGroups.size);assertEquals(23,(first+second.visibleGroups.map{it.key}).distinct().size)
        val hidden=u.snapshot!!.groups.first().items.single() as UnifiedKeywordEvidence.Audio
        h.data.audios.write(AudioTranscriptSqlTest.write(hidden.value.anchor,hidden.value,withdraw=true))
        h.vm.unifiedKeywords.next(u.session,true);val failed=h.ui{it.error!=null&&!it.busy}
        assertNull(failed.snapshot);assertTrue(failed.visibleGroups.isEmpty())
    }}
    @Test fun confirmedTypedLocationAndReturnRequeryNeverReuseOldProof():Unit=runBlocking{Harness().use{h->
        h.ready();h.open("淡茶");val u=h.ui{it.snapshot!=null&&!it.busy}
        val item=u.visibleGroups.single().items.filterIsInstance<UnifiedKeywordEvidence.Audio>().single()
        val located=CompletableDeferred<UnifiedKeywordEvidence>();h.vm.unifiedKeywords.locate(u.session,item){located.complete(it)}
        assertSame(item,withTimeout(4000){located.await()});assertNull(h.vm.state.value.unifiedKeyword)
        // An old composition's disposal cannot erase the query seed of confirmed navigation.
        h.vm.unifiedKeywords.close(u.session);h.vm.unifiedKeywords.resume()
        val returned=h.ui{it.snapshot!=null&&!it.busy};assertEquals("淡茶",returned.keyword);assertNotSame(u.snapshot,returned.snapshot);assertEquals(0,returned.page)
        h.vm.unifiedKeywords.keyword(returned.session,"");assertNull(h.value().snapshot)
        h.vm.unifiedKeywords.search(returned.session);assertNull(h.value().snapshot)
    }}
    private class Harness(extra:Int=0):AutoCloseable {
        val data=UnifiedKeywordFixture();val id=data.f.entry(text="合成淡茶")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var block=false
        var first:UnifiedKeywordSnapshot?=null;var calls=0
        init{runBlocking{data.audio(id);repeat(extra){data.audio(data.f.entry(text="合成淡茶"))}}}
        val port:UnifiedKeywordRepository=object:UnifiedKeywordRepository by data.repo{
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope):UnifiedKeywordSnapshot {
                calls++;val result=data.repo.search(keyword,scope)
                if(block){block=false;first=result;entered.complete(Unit);release.await()};return result
            }
        }
        private val base=SqliteVmEntryRepository(data.f.db,data.f.gateway,RemoteTextTransactions(data.f.gateway)){data.f.media.close();data.f.media.awaitIdle();data.f.gateway.stopAndAwaitIdle()}
        val owner:EntryRepository=object:EntryRepository by base,AutoCloseable{
            override val unifiedKeywords=port
            override suspend fun prepare(){check(data.f.db.read("PRAGMA user_version").single().single()==12L)}
            override fun close()=base.close()
        }
        val vm=LocalRecordsViewModel(Application(),{owner},Clock.systemUTC())
        suspend fun ready()=withTimeout(4000){vm.state.first{it.ready&&!it.opening&&it.unifiedKeywordOwner===port}}
        fun value()=checkNotNull(vm.state.value.unifiedKeyword)
        fun open(keyword:String){vm.unifiedKeywords.open();val u=value();vm.unifiedKeywords.keyword(u.session,keyword);vm.unifiedKeywords.search(u.session)}
        suspend fun ui(predicate:(UnifiedKeywordUi)->Boolean)=withTimeout(8000){vm.state.first{it.unifiedKeyword?.let(predicate)==true}.unifiedKeyword!!}
        override fun close(){release.complete(Unit);vm.clearForHostTest();assertTrue(base.closed.await(4,TimeUnit.SECONDS));base.closeFailure.get()?.let{throw it};data.close()}
    }
}
