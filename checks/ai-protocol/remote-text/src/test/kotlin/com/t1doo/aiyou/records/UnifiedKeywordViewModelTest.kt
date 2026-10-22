package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.util.concurrent.TimeUnit

class UnifiedKeywordViewModelTest {
    @Test fun postCasRetirementOrDisposalCannotNavigateOrResurrectReturnSeed():Unit=runBlocking{
        for(dispose in listOf(false,true))HandoffFixture().use{h->
            h.load();val u=h.state.get().unifiedKeyword!!;val item=u.visibleGroups.single().items.first()
            var navigations=0;h.block=true;h.controller.locate(u.session,item){_,confirm->confirm{navigations++}}
            val work=async(Dispatchers.IO){h.queue.removeFirst()(h.owner)}
            try {
                assertTrue("Actual UI CAS must finish before retirement",h.cleared.await(4,TimeUnit.SECONDS))
                assertNull(h.state.get().unifiedKeyword)
                if(dispose)h.controller.dispose()else h.controller.retire()
            }finally{h.release.countDown()}
            work.await();assertEquals("Retirement must win the post-CAS window",0,navigations)
            h.controller.resume();assertNull(h.state.get().unifiedKeyword);assertTrue(h.queue.isEmpty())
        }
    }
    @Test fun retainedHandoffOfferCannotConfirmAfterRetirementOrDisposal():Unit=runBlocking{
        for(dispose in listOf(false,true))HandoffFixture().use{h->
            h.load();val u=h.state.get().unifiedKeyword!!;val item=u.visibleGroups.single().items.first()
            var offer:UnifiedKeywordHandoff?=null
            h.controller.locate(u.session,item){_,confirm->offer=confirm}
            h.queue.removeFirst()(h.owner);assertNotNull(offer);assertNull(h.state.get().unifiedKeyword)
            if(dispose)h.controller.dispose()else h.controller.retire()
            assertFalse(offer!!.invoke{fail("Retained offer must not navigate")})
            h.controller.resume();assertNull(h.state.get().unifiedKeyword);assertTrue(h.queue.isEmpty())
        }
    }
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
    @Test fun actualFinalCommitFailureAndLostCommitAckNeverPublishContentOrEmptySuccess():Unit=runBlocking{Harness(withMemory=true).use{h->
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
        h.vm.unifiedKeywords.locate(old.session,next.visibleGroups.single().items.first()){_,_->fail("Retired session")}
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
            h.vm.unifiedKeywords.locate(u.session,item){_,_->fail("Unconfirmed private ticket cannot navigate")}
            val failed=h.ui{it.error!=null&&!it.busy};assertTrue(observed);assertNull(failed.snapshot)
        }finally{h.data.f.db.beforeSql=null;h.data.f.db.afterSql=null}
    }}
    @Test fun actualOffPageSourceRevisionObservationRetiresAllProofAndDelayedClick():Unit=runBlocking{Harness(extra=22).use{h->
        h.ready();h.open("淡茶");val first=h.ui{it.snapshot!=null&&!it.busy};assertEquals(23,first.snapshot!!.groups.size)
        val offPage=first.snapshot.groups.last().binding.source.sourceId
        val visible=first.visibleGroups.first().items.first();h.data.f.revise(offPage,"合成新版本")
        h.vm.refreshLocalSearch();withTimeout(4000){h.vm.state.first{it.entries.any{e->e.id==offPage&&e.currentRevision==2}}}
        assertNull(h.vm.state.value.unifiedKeyword)
        h.vm.unifiedKeywords.locate(first.session,visible){_,_->fail("Off-screen source invalidation")}
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
        val located=CompletableDeferred<UnifiedKeywordEvidence>();h.vm.unifiedKeywords.locate(u.session,item){found,confirm->assertTrue(confirm{located.complete(found)});assertFalse(confirm{fail("One-shot source handoff")})}
        assertSame(item,withTimeout(4000){located.await()});assertNull(h.vm.state.value.unifiedKeyword)
        // An old composition's disposal cannot erase the query seed of confirmed navigation.
        h.vm.unifiedKeywords.close(u.session);h.vm.unifiedKeywords.resume()
        val returned=h.ui{it.snapshot!=null&&!it.busy};assertEquals("淡茶",returned.keyword);assertNotSame(u.snapshot,returned.snapshot);assertEquals(0,returned.page)
        h.vm.unifiedKeywords.keyword(returned.session,"");assertNull(h.value().snapshot)
        h.vm.unifiedKeywords.search(returned.session);assertNull(h.value().snapshot)
    }}
    @Test fun noteOnlyLocationUsesVerifiedQuoteAndQuoteHitUsesUnicodeCodePoints():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val body="🙂合成原文：我并不开心。";val id=h.f.entry(text=body)
        val row=AcceptedMemoryKeywordSqlTest.memory(h,id,"人工备注专词",quote="我并不开心")
        val p=h.repo.search("备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY)
        val note=p.groups.single().items.single();val n=unifiedOriginalLocation(note,"备注专词")!!
        assertFalse(body.contains("备注专词"));assertEquals(row.current!!.content.reference.start,n.startCodePoint)
        assertEquals(row.current!!.content.reference.end,n.endCodePoint)
        val q=unifiedOriginalLocation(h.repo.search("开心",UnifiedKeywordScope.ACCEPTED_MEMORY).groups.single().items.single(),"开心")!!
        assertEquals(body.codePointCount(0,body.indexOf("开心")),q.startCodePoint)
        assertEquals(q.startCodePoint+2,q.endCodePoint);assertEquals(id,q.entryId)
    }}
    @Test fun observedRetirementAfterCasCancelsSameOfferButCannotEraseANewerSession():Unit=runBlocking{HandoffFixture().use{h->
        h.load();val old=h.state.get().unifiedKeyword!!;val item=old.visibleGroups.single().items.first();var navigated=0
        h.block=true;h.controller.locate(old.session,item){_,confirm->if(confirm{navigated++})fail("Retired offer")}
        val action=h.queue.removeFirst();val job=async(Dispatchers.IO){action(h.owner)}
        assertTrue(h.cleared.await(4,TimeUnit.SECONDS));val observation=h.controller.observeRetirement()!!
        assertNull(h.state.get().unifiedKeyword);h.controller.retireObserved(observation);h.release.countDown();job.await()
        assertEquals(0,navigated);h.controller.resume();assertNull(h.state.get().unifiedKeyword)
        h.load();val fresh=h.state.get().unifiedKeyword!!
        h.controller.retireObserved(observation);assertSame(fresh,h.state.get().unifiedKeyword)
    }}
    @Test fun actualNonmatchingSourceObservationRetiresCompleteMemoryProofAndRejectsOldClick():Unit=runBlocking{Harness(withMemory=true).use{h->
        h.ready();val other=h.data.f.entry(text="合成不匹配来源");AcceptedMemoryKeywordSqlTest.memory(h.data,other,"无关备注")
        h.vm.refreshLocalSearch();withTimeout(4000){h.vm.state.first{it.entries.any{e->e.id==other}}}
        h.open("备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY);val u=h.ui{it.snapshot!=null&&!it.busy}
        assertTrue(u.snapshot!!.bindings.none{it.source.sourceId==other});val item=u.visibleGroups.single().items.single()
        h.data.f.revise(other,"合成来源已修订");h.vm.refreshLocalSearch()
        withTimeout(4000){h.vm.state.first{it.entries.any{e->e.id==other&&e.currentRevision==2}}}
        assertNull(h.vm.state.value.unifiedKeyword)
        h.vm.unifiedKeywords.locate(u.session,item){_,_->fail("Nonmatching source changed")}
    }}
    @Test fun actualScanRetirementUnwindsLeaseAndLeavesSharedActorAliveForNextQuery():Unit=runBlocking{Harness(withMemory=true).use{h->
        h.ready();val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val once=java.util.concurrent.atomic.AtomicBoolean();var rolledBack=false
        h.data.f.db.afterSql={sql->if(sql.startsWith("SELECT c.id,c.entryId,c.sourceRevision,length(c.payload)")&&once.compareAndSet(false,true)){
            entered.countDown();check(release.await(4,TimeUnit.SECONDS))
        };if(sql=="ROLLBACK")rolledBack=true}
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        try{
            h.open("备注专词");assertTrue(entered.await(4,TimeUnit.SECONDS));val old=h.value()
            val waiting=java.util.concurrent.CountDownLatch(1)
            val wait=pool.submit{waiting.countDown();h.data.f.gateway.awaitIdle()};assertTrue(waiting.await(4,TimeUnit.SECONDS));assertThrows(java.util.concurrent.TimeoutException::class.java){wait.get(50,TimeUnit.MILLISECONDS)}
            h.vm.unifiedKeywords.keyword(old.session,"合成");h.vm.unifiedKeywords.search(old.session);release.countDown()
            wait.get(4,TimeUnit.SECONDS);val fresh=h.ui{it.snapshot!=null&&!it.busy&&it.keyword=="合成"}
            assertTrue(rolledBack);assertNull(h.vm.state.value.error);assertTrue(fresh.visibleGroups.isNotEmpty())
        }finally{release.countDown();h.data.f.db.afterSql=null;pool.shutdown();assertTrue(pool.awaitTermination(4,TimeUnit.SECONDS))}
    }}
    @Test fun actualReviewOpenRetiresConfirmedQuerySeedBeforeAnyCorrection():Unit=runBlocking{Harness(withMemory=true).use{h->
        h.ready();h.open("备注专词");val u=h.ui{it.snapshot!=null&&!it.busy}
        val item=u.visibleGroups.single().items.single();val done=CompletableDeferred<Unit>()
        h.vm.unifiedKeywords.locate(u.session,item){_,confirm->assertTrue(confirm{done.complete(Unit)})};withTimeout(4000){done.await()}
        h.vm.sourceMemories.open(h.id,1);withTimeout(4000){h.vm.state.first{it.sourceMemories?.preview!=null}}
        h.vm.sourceMemories.close(h.vm.state.value.sourceMemories!!.session);h.vm.unifiedKeywords.resume()
        assertNull(h.vm.state.value.unifiedKeyword)
    }}
    @Test fun actualPerRowRequestChecksNeverTraverseEntriesOrHashRetainedBindings():Unit=runBlocking{
        var entryReads=0;var checks=0;var retainedChecks=0
        fun guard(checkCurrent:()->Unit):()->Unit={
            val before=entryReads;checkCurrent()
            assertEquals("A per-row request guard must not read any entry or hash a retained binding",before,entryReads)
            checks++
        }
        HandoffFixture{actual->object:UnifiedKeywordRepository by actual{
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope,checkCurrent:()->Unit)=actual.search(keyword,scope,guard(checkCurrent))
            override suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,checkCurrent:()->Unit,admit:()->Unit):Boolean {
                assertEquals(21,snapshot.bindings.size)
                return actual.resolve(snapshot,item,{retainedChecks++;guard(checkCurrent)()},admit)
            }
        }}.use{h->
            val body="合成依据"+"x".repeat(99_996)
            val rows=(0 until 21).map{
                val id=h.data.f.entry(text=body);AcceptedMemoryKeywordSqlTest.memory(h.data,id,"备注专词",quote="合成依据")
                StoredEntry(id,1,body,5,"UTC",ProcessingPolicy.LOCAL_ONLY)
            }
            val counted=object:AbstractList<StoredEntry>(){
                override val size:Int get(){entryReads++;return rows.size}
                override fun get(index:Int):StoredEntry{entryReads++;return rows[index]}
            }
            h.state.set(h.state.get().copy(entries=counted))
            var fullRows=0
            h.data.f.db.beforeSql={sql->if(sql.startsWith("SELECT id,entryId,sourceRevision,sourceHash,generation,ruleVersion"))fullRows++}
            try{
                h.controller.open();var u=h.state.get().unifiedKeyword!!
                h.controller.scope(u.session,UnifiedKeywordScope.ACCEPTED_MEMORY);h.controller.keyword(u.session,"备注专词")
                h.controller.search(u.session);h.queue.removeFirst()(h.owner)
                u=h.state.get().unifiedKeyword!!;assertEquals(21,u.snapshot!!.groups.size)
                assertTrue(entryReads>0) // Full source validation still occurs outside the row guard.
                h.controller.next(u.session);h.queue.removeFirst()(h.owner)
                u=h.state.get().unifiedKeyword!!;assertEquals(1,u.page)
                val item=u.visibleGroups.single().items.single();var navigations=0
                h.controller.locate(u.session,item){found,confirm->assertSame(item,found);assertTrue(confirm{navigations++})}
                h.queue.removeFirst()(h.owner)
                assertEquals(1,navigations);assertNull(h.state.get().unifiedKeyword)
                assertEquals(21*8,fullRows) // Search two scans; paging and locate three scans each.
                assertTrue(checks>=fullRows*4);assertTrue(retainedChecks>0)
                println("REQUEST_GUARD_COUNT checks=$checks retained=$retainedChecks fullRows=$fullRows entryReadsOutsideGuard=$entryReads guardEntryReads=0")
                h.controller.resume();assertNotNull(h.state.get().unifiedKeyword)
            }finally{h.data.f.db.beforeSql=null}
        }
    }
    private class Harness(extra:Int=0,withMemory:Boolean=false):AutoCloseable {
        val data=UnifiedKeywordFixture();val id=data.f.entry(text="合成淡茶")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var block=false
        var first:UnifiedKeywordSnapshot?=null;var calls=0
        init{runBlocking{data.audio(id);repeat(extra){data.audio(data.f.entry(text="合成淡茶"))};if(withMemory)AcceptedMemoryKeywordSqlTest.memory(data,id,"备注专词")}}
        val port:UnifiedKeywordRepository=object:UnifiedKeywordRepository by data.repo{
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope)=search(keyword,scope,{})
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope,checkCurrent:()->Unit):UnifiedKeywordSnapshot {
                calls++;val result=data.repo.search(keyword,scope,checkCurrent)
                if(block){block=false;first=result;entered.complete(Unit);release.await()};return result
            }
        }
        private val base=SqliteVmEntryRepository(data.f.db,data.f.gateway,RemoteTextTransactions(data.f.gateway)){data.f.media.close();data.f.media.awaitIdle();data.f.gateway.stopAndAwaitIdle()}
        val owner:EntryRepository=object:EntryRepository by base,AutoCloseable{
            override val unifiedKeywords=port
            override val sourceMemories=if(withMemory)data.memories else null
            override suspend fun prepare(){check(data.f.db.read("PRAGMA user_version").single().single()==12L)}
            override fun close()=base.close()
        }
        val vm=LocalRecordsViewModel(Application(),{owner},Clock.systemUTC())
        suspend fun ready()=withTimeout(4000){vm.state.first{it.ready&&!it.opening&&it.unifiedKeywordOwner===port}}
        fun value()=checkNotNull(vm.state.value.unifiedKeyword)
        fun open(keyword:String,scope:UnifiedKeywordScope=UnifiedKeywordScope.ALL){vm.unifiedKeywords.open();val u=value();vm.unifiedKeywords.scope(u.session,scope);vm.unifiedKeywords.keyword(u.session,keyword);vm.unifiedKeywords.search(u.session)}
        suspend fun ui(predicate:(UnifiedKeywordUi)->Boolean)=withTimeout(8000){vm.state.first{it.unifiedKeyword?.let(predicate)==true}.unifiedKeyword!!}
        override fun close(){release.complete(Unit);vm.clearForHostTest();assertTrue(base.closed.await(4,TimeUnit.SECONDS));base.closeFailure.get()?.let{throw it};data.close()}
    }
    /** Real repository/controller, with a publication barrier AFTER the real atomic state CAS.
     * No production-only testing hook, simulated pause is the actual retire/dispose entry. */
    private class HandoffFixture(decorate:(UnifiedKeywordRepository)->UnifiedKeywordRepository={it}):AutoCloseable {
        val data=UnifiedKeywordFixture();val id=data.f.entry(text="合成淡茶")
        val port=decorate(data.repo)
        val state=java.util.concurrent.atomic.AtomicReference(LocalRecordUiState(ready=true,opening=false,
            entries=listOf(com.t1doo.aiyou.model.StoredEntry(id,1,"合成淡茶",5,"UTC",com.t1doo.aiyou.model.ProcessingPolicy.LOCAL_ONLY)),unifiedKeywordOwner=port))
        val queue=java.util.ArrayDeque<suspend(EntryRepository)->Unit>()
        val cleared=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        var block=false;private val blocked=java.util.concurrent.atomic.AtomicBoolean()
        val owner:EntryRepository=object:EntryRepository {
            override val unifiedKeywords=port
            override suspend fun readDraft():com.t1doo.aiyou.model.TextDraft?=null
            override suspend fun updateDraft(text:String):com.t1doo.aiyou.model.TextDraft=error("Unrelated")
            override suspend fun saveDraft(expected:com.t1doo.aiyou.model.TextDraft,capturedAtMillis:Long,captureTimeZone:String):com.t1doo.aiyou.model.StoredEntry=error("Unrelated")
            override suspend fun entries()=state.get().entries
            override suspend fun revisions(entryId:String):List<com.t1doo.aiyou.model.OriginalRevision> = error("Unrelated")
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):com.t1doo.aiyou.model.StoredEntry=error("Unrelated")
        }
        val controller=UnifiedKeywordController({state.get()},{change->
            val before=state.get();val after=state.updateAndGet(change)
            if(block&&before.unifiedKeyword!=null&&after.unifiedKeyword==null&&blocked.compareAndSet(false,true)){
                cleared.countDown();check(release.await(4,TimeUnit.SECONDS))
            }
        },{queue.add(it);true})
        suspend fun load(){controller.open();val u=state.get().unifiedKeyword!!;controller.keyword(u.session,"淡茶");controller.search(u.session);queue.removeFirst()(owner)}
        override fun close(){release.countDown();controller.dispose();data.close()}
    }
}
