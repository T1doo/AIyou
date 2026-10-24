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
    @Test(timeout=15000) fun initialIndexEpochCanRetireFavoriteQueryBeforeItsActorCommandBegins():Unit=runBlocking {Harness(withFavorites=true,holdInitialIndex=true).use {h->
        withTimeout(4000){h.vm.state.first{it.ready&&!it.opening&&it.unifiedKeywordOwner===h.port}}
        assertTrue(h.indexEntered.await(4,TimeUnit.SECONDS));val epoch=h.vm.state.value.localSearchEpoch!!
        h.vm.unifiedKeywords.open();val u=h.value();h.vm.unifiedKeywords.keyword(u.session,"淡茶");h.vm.unifiedKeywords.favorites(u.session,true);h.vm.unifiedKeywords.search(u.session)
        assertNotNull(h.vm.state.value.unifiedKeyword);h.indexRelease.countDown()
        val drained=CompletableDeferred<Unit>();assertTrue(h.vm.enqueueTaskOperation{drained.complete(Unit)});withTimeout(4000){drained.await()}
        assertTrue(h.vm.state.value.localSearchEpoch!!>epoch);assertNull(h.vm.state.value.unifiedKeyword)
        assertEquals(0,h.calls) // The expected repository-entry barrier cannot fire for a retired queued request.
    }}
    @Test(timeout=20000) fun rangeOnlyAbaCannotReuseUnifiedProofAndPrivateUiNullHandoffRetiresWithoutNavigation():Unit=runBlocking {Harness().use {h->
        h.ready();h.open("淡茶");var u=h.ui{it.snapshot!=null&&!it.busy}
        val old=u.visibleGroups.single().items.filterIsInstance<UnifiedKeywordEvidence.Audio>().single().value
        val b=h.data.audios.write(AudioTranscriptWrite(old.id,old.anchor,old,old.current.text,false,FullBackupTest.uuid(),70,AudioSampleRange(1,4)))
        h.data.audios.write(AudioTranscriptWrite(b.id,b.anchor,b,b.current.text,false,FullBackupTest.uuid(),71,null))
        var nav=0;h.vm.unifiedKeywords.locate(u.session,u.visibleGroups.single().items.filterIsInstance<UnifiedKeywordEvidence.Audio>().single()){_,_->nav++}
        h.ui{it.error!=null&&!it.busy};assertEquals(0,nav)
        h.vm.unifiedKeywords.search(u.session);u=h.ui{it.snapshot!=null&&!it.busy}
        val item=u.visibleGroups.single().items.filterIsInstance<UnifiedKeywordEvidence.Audio>().single()
        val ready=CompletableDeferred<UnifiedKeywordHandoff>()
        h.vm.unifiedKeywords.locate(u.session,item){found,confirm->assertSame(item,found);assertTrue(h.data.audios.ownsPlaybackReference(confirm.audioPlayback!!));ready.complete(confirm)}
        val offer=withTimeout(4000){ready.await()};assertNull(h.vm.state.value.unifiedKeyword)
        h.vm.unifiedKeywords.retire();assertFalse(offer{nav++});h.vm.unifiedKeywords.resume();assertNull(h.vm.state.value.unifiedKeyword);assertEquals(0,nav)
    }}
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
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,checkCurrent:()->Unit)=actual.search(keyword,scope,captureRange,guard(checkCurrent))
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
                h.controller.from(u.session,"1970-01-01");h.controller.through(u.session,"1970-01-01")
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
    @Test fun invalidDatesAndLegacyFilteredPortsNeverEnqueueOrSilentlyReadUnfiltered():Unit=runBlocking{
        var reads=0
        HandoffFixture{actual->object:UnifiedKeywordRepository{
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope):UnifiedKeywordSnapshot {reads++;return actual.search(keyword,scope)}
            override suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,admit:()->Unit)=actual.resolve(snapshot,item,admit)
        }}.use{h->
            h.controller.open();val session=h.state.get().unifiedKeyword!!.session;h.controller.keyword(session,"淡茶")
            h.controller.from(session,"1970-02-30");h.controller.search(session)
            assertTrue(h.queue.isEmpty());assertTrue(h.state.get().unifiedKeyword!!.error!!.contains("起始捕获日期无效"))
            h.controller.from(session,"1970-01-02");h.controller.through(session,"1970-01-01");h.controller.search(session)
            assertTrue(h.queue.isEmpty());assertEquals(0,reads)
            h.controller.through(session,"1970-01-02");h.controller.search(session);h.queue.removeFirst()(h.owner)
            assertEquals(0,reads);assertNull(h.state.get().unifiedKeyword!!.snapshot)
            assertEquals("当前查询端口不支持捕获日期范围，未显示未过滤内容。",h.state.get().unifiedKeyword!!.error)
            h.controller.unlimitedDates(session);h.controller.search(session);h.queue.removeFirst()(h.owner)
            assertEquals(1,reads);assertNotNull(h.state.get().unifiedKeyword!!.snapshot)
            val longDate="1970-01-01"+"x".repeat(100)
            h.controller.from(session,longDate)
            assertNull("Even an invalid paste must retire the old proof",h.state.get().unifiedKeyword!!.snapshot)
            assertEquals(longDate,h.state.get().unifiedKeyword!!.from)
            h.controller.search(session);assertTrue(h.queue.isEmpty());assertEquals(1,reads)
            assertNotNull(h.state.get().unifiedKeyword!!.error)
            h.controller.clear(session);h.controller.search(session);assertTrue(h.queue.isEmpty())
            assertEquals("",h.state.get().unifiedKeyword!!.from);assertEquals("",h.state.get().unifiedKeyword!!.through)
        }
    }
    @Test fun actualRangeAToBToARejectsFirstLateResultAndOnlyPublishesLastRange():Unit=runBlocking{Harness().use{h->
        h.ready();h.block=true;h.open("淡茶",UnifiedKeywordScope.ORIGINAL,"1970-01-01","1970-01-01")
        withTimeout(4000){h.entered.await()};val old=h.value()
        h.vm.unifiedKeywords.from(old.session,"1970-01-02");h.vm.unifiedKeywords.through(old.session,"1970-01-02");h.vm.unifiedKeywords.search(old.session)
        h.vm.unifiedKeywords.from(old.session,"1970-01-01");h.vm.unifiedKeywords.through(old.session,"1970-01-01");h.vm.unifiedKeywords.search(old.session)
        assertNull(h.value().snapshot);h.release.complete(Unit)
        val found=h.ui{it.snapshot!=null&&!it.busy};assertNotSame(h.first,found.snapshot);assertEquals(2,h.calls)
        assertEquals(UnifiedCaptureRange.parse("1970-01-01","1970-01-01"),found.snapshot!!.captureRange)
    }}
    @Test fun observedNonmatchingSourceChangeRetiresBoundedEmptyOrNonemptyQueryInEveryScope():Unit=runBlocking{
        for(scope in UnifiedKeywordScope.entries)Harness(withMemory=true).use{h->
            h.ready();val other=h.data.f.entry(text="合成完全不命中来源");h.vm.refreshLocalSearch()
            withTimeout(4000){h.vm.state.first{it.entries.any{e->e.id==other}}}
            h.open("淡茶",scope,"1970-01-01","1970-01-01");val old=h.ui{it.snapshot!=null&&!it.busy}
            h.data.f.revise(other,"合成不匹配来源新版本");h.vm.refreshLocalSearch()
            withTimeout(4000){h.vm.state.first{it.entries.any{e->e.id==other&&e.currentRevision==2}}}
            assertNull("Every bounded scope must retire, even an empty set: $scope",h.vm.state.value.unifiedKeyword)
            h.vm.unifiedKeywords.close(old.session);assertNull(h.vm.state.value.unifiedKeyword)
        }
    }
    @Test fun confirmedReturnKeepsDateRangeButReauthenticatesInsteadOfRestoringOldProof():Unit=runBlocking{Harness().use{h->
        h.ready();h.open("淡茶",UnifiedKeywordScope.ORIGINAL,"1970-01-01","1970-01-01");val old=h.ui{it.snapshot!=null&&!it.busy}
        val item=old.visibleGroups.single().items.single();val done=CompletableDeferred<Unit>()
        h.vm.unifiedKeywords.locate(old.session,item){_,confirm->assertTrue(confirm{done.complete(Unit)})};withTimeout(4000){done.await()}
        h.vm.unifiedKeywords.close(old.session);h.vm.unifiedKeywords.resume()
        val fresh=h.ui{it.snapshot!=null&&!it.busy};assertEquals(old.from,fresh.from);assertEquals(old.through,fresh.through)
        assertEquals(old.snapshot!!.captureRange,fresh.snapshot!!.captureRange);assertNotSame(old.snapshot,fresh.snapshot);assertEquals(0,fresh.page)
        h.vm.unifiedKeywords.retire();h.vm.unifiedKeywords.resume();assertNull(h.vm.state.value.unifiedKeyword)
    }}
    @Test fun ownerReturningAnUnfilteredProofForRestrictedRequestCannotPublish():Unit=runBlocking{
        HandoffFixture{actual->object:UnifiedKeywordRepository by actual{
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,checkCurrent:()->Unit)=actual.search(keyword,scope,checkCurrent)
        }}.use{h->
            h.controller.open();val session=h.state.get().unifiedKeyword!!.session
            h.controller.keyword(session,"淡茶");h.controller.from(session,"1970-01-01");h.controller.search(session);h.queue.removeFirst()(h.owner)
            assertNotNull(h.state.get().unifiedKeyword!!.error);assertNull(h.state.get().unifiedKeyword!!.snapshot)
        }
    }
    @Test fun boundedOriginalHandoffRetainsCompleteSourceRetirementEvenAfterUiCasClearsSession():Unit=runBlocking{HandoffFixture().use{h->
        h.controller.open();val session=h.state.get().unifiedKeyword!!.session
        h.controller.keyword(session,"淡茶");h.controller.scope(session,UnifiedKeywordScope.ORIGINAL);h.controller.from(session,"1970-01-01")
        h.controller.search(session);h.queue.removeFirst()(h.owner);val u=h.state.get().unifiedKeyword!!
        var navigations=0;h.block=true;h.controller.locate(session,u.visibleGroups.single().items.single()){_,confirm->confirm{navigations++}}
        val work=async(Dispatchers.IO){h.queue.removeFirst()(h.owner)}
        try{assertTrue(h.cleared.await(4,TimeUnit.SECONDS));val observed=h.controller.observeRetirement()!!
            assertTrue(observed.completeSources);h.controller.retireObserved(observed)
        }finally{h.release.countDown()}
        work.await();assertEquals(0,navigations);h.controller.resume();assertNull(h.state.get().unifiedKeyword)
    }}
    @Test fun openedUnselectedChooserRetiresOnTagOrNonmatchingSourceObservationInEveryScope():Unit=runBlocking{
        for(sourceChange in listOf(false,true))for(scope in UnifiedKeywordScope.entries)Harness().use{h->
            h.ready();val other=h.data.f.entry(text="合成非命中来源");h.vm.refreshLocalSearch()
            withTimeout(4000){h.vm.state.first{it.entries.any{e->e.id==other}}}
            h.vm.unifiedKeywords.open();val u=h.value();h.vm.unifiedKeywords.scope(u.session,scope);h.vm.unifiedKeywords.openTags(u.session)
            h.ui{it.tagPicker&&!it.tagBusy&&it.tagPage!=null};assertNull(h.value().tag)
            if(sourceChange)h.data.f.revise(other,"合成新版本")else UnifiedManualTagSqlTest.set(h.data,other,listOf("新增标签"))
            h.vm.refreshLocalSearch()
            withTimeout(4000){h.vm.state.first{if(sourceChange)it.entries.any{e->e.id==other&&e.currentRevision==2}else it.manualTags[other]?.names==listOf("新增标签")}}
            assertNull("Even no-selection chooser must retire: $scope source=$sourceChange",h.vm.state.value.unifiedKeyword)
            h.vm.unifiedKeywords.closeTags(u.session);h.vm.unifiedKeywords.resume();assertNull(h.vm.state.value.unifiedKeyword)
        }
    }
    @Test fun observedTagSnapshotsCompareMeaningAndRetireSelectedEmptyProofOnNonmatchingChange():Unit=runBlocking{Harness().use{h->
        h.ready();UnifiedManualTagSqlTest.set(h.data,h.id,listOf("合成标签"));h.vm.refreshLocalSearch()
        withTimeout(4000){h.vm.state.first{it.manualTags[h.id]?.version==1L}}
        h.vm.unifiedKeywords.open();val session=h.value().session;h.vm.unifiedKeywords.scope(session,UnifiedKeywordScope.PREFERENCE)
        h.vm.unifiedKeywords.openTags(session);var u=h.ui{it.tagPage!=null&&!it.tagBusy}
        h.vm.unifiedKeywords.selectTag(session,u.tagPage!!.choices.single());h.ui{it.tag!=null&&!it.tagBusy}
        h.vm.unifiedKeywords.keyword(session,"无匹配");h.vm.unifiedKeywords.search(session);u=h.ui{it.snapshot!=null&&!it.busy}
        assertTrue(u.snapshot!!.groups.isEmpty());val oldSnapshot=h.vm.state.value.manualTags[h.id]
        h.vm.refreshLocalSearch();withTimeout(4000){h.vm.state.first{it.manualTags[h.id]!==oldSnapshot}}
        assertEquals(session,h.value().session);assertSame(u.snapshot,h.value().snapshot)
        val other=h.data.f.entry(text="合成非匹配");UnifiedManualTagSqlTest.set(h.data,other,listOf("别的标签"));h.vm.refreshLocalSearch()
        withTimeout(4000){h.vm.state.first{it.manualTags.containsKey(other)}};assertNull(h.vm.state.value.unifiedKeyword)
    }}
    @Test fun lateChooserCompletionAfterClosePauseAndNewSessionNeverRepopulatesOrSelectsOldChoice():Unit=runBlocking{Harness().use{h->
        h.ready();UnifiedManualTagSqlTest.set(h.data,h.id,listOf("合成标签"))
        h.vm.unifiedKeywords.open();val old=h.value();h.block=true;h.vm.unifiedKeywords.openTags(old.session)
        withTimeout(4000){h.entered.await()};assertTrue(h.value().tagBusy);assertNull(h.value().tagPage)
        h.vm.unifiedKeywords.retire();h.vm.unifiedKeywords.open();val fresh=h.value();assertNotEquals(old.session,fresh.session)
        h.release.complete(Unit);h.vm.unifiedKeywords.openTags(fresh.session);h.ui{it.tagPage!=null&&!it.tagBusy}
        assertEquals(fresh.session,h.value().session);assertNull(h.value().tag)
        h.vm.unifiedKeywords.selectTag(old.session,h.value().tagPage!!.choices.single());assertNull(h.value().tag)
        h.vm.unifiedKeywords.closeTags(fresh.session);assertFalse(h.value().tagPicker);assertNull(h.value().tagPage)
    }}
    @Test fun tagSelectionSwitchRejectsOldPageAndAbaRequestsAndNeverSearchesAutomatically():Unit=runBlocking{HandoffFixture().use{h->
        UnifiedManualTagSqlTest.set(h.data,h.id,listOf("A","B"));h.controller.open();val session=h.state.get().unifiedKeyword!!.session
        h.controller.openTags(session);h.queue.removeFirst()(h.owner);val old=h.state.get().unifiedKeyword!!.tagPage!!
        h.controller.selectTag(session,old.choices.first());h.queue.removeFirst()(h.owner)
        assertEquals("A",h.state.get().unifiedKeyword!!.tag!!.name);assertTrue(h.queue.isEmpty())
        h.controller.openTags(session);h.queue.removeFirst()(h.owner);val next=h.state.get().unifiedKeyword!!.tagPage!!
        h.controller.selectTag(session,old.choices.last());assertTrue(h.queue.isEmpty())
        h.controller.selectTag(session,next.choices.last());h.queue.removeFirst()(h.owner);assertEquals("B",h.state.get().unifiedKeyword!!.tag!!.name)
        h.controller.openTags(session);h.queue.removeFirst()(h.owner);val last=h.state.get().unifiedKeyword!!.tagPage!!
        h.controller.selectTag(session,last.choices.first());h.queue.removeFirst()(h.owner)
        assertEquals("A",h.state.get().unifiedKeyword!!.tag!!.name);assertNull(h.state.get().unifiedKeyword!!.snapshot);assertTrue(h.queue.isEmpty())
    }}
    @Test fun confirmedTagReturnGetsFreshSelectionAndProofAndManualEditorAdmissionClearsSeed():Unit=runBlocking{Harness().use{h->
        h.ready();UnifiedManualTagSqlTest.set(h.data,h.id,listOf("合成标签"));h.vm.refreshLocalSearch()
        withTimeout(4000){h.vm.state.first{it.manualTags[h.id]!=null}}
        h.vm.unifiedKeywords.open();val session=h.value().session;h.vm.unifiedKeywords.openTags(session)
        h.vm.unifiedKeywords.selectTag(session,h.ui{it.tagPage!=null&&!it.tagBusy}.tagPage!!.choices.single());h.ui{it.tag!=null&&!it.tagBusy}
        h.vm.unifiedKeywords.keyword(session,"淡茶");h.vm.unifiedKeywords.search(session);val old=h.ui{it.snapshot!=null&&!it.busy}
        val done=CompletableDeferred<Unit>();h.vm.unifiedKeywords.locate(session,old.visibleGroups.single().items.first()){_,confirm->assertTrue(confirm{done.complete(Unit)})}
        withTimeout(4000){done.await()};h.vm.unifiedKeywords.close(session);h.vm.unifiedKeywords.resume()
        val fresh=h.ui{it.snapshot!=null&&!it.busy};assertNotSame(old.tag,fresh.tag);assertNotSame(old.snapshot,fresh.snapshot);assertEquals(old.tag!!.id,fresh.tag!!.id)
        val again=CompletableDeferred<Unit>();h.vm.unifiedKeywords.locate(fresh.session,fresh.visibleGroups.single().items.first()){_,confirm->assertTrue(confirm{again.complete(Unit)})}
        withTimeout(4000){again.await()};h.vm.tags.open(h.id,1);withTimeout(4000){h.vm.state.first{it.manualTagEditor?.snapshot!=null&&!it.manualTagEditor.busy}}
        h.vm.tags.close(h.vm.state.value.manualTagEditor!!.session);h.vm.unifiedKeywords.resume();assertNull(h.vm.state.value.unifiedKeyword)
    }}
    @Test fun taggedQueryRowGuardsNeverReadEntriesOrUiManualTagMapsEvenWithRetainedProof():Unit=runBlocking{
        var entryReads=0;var tagReads=0;var guards=0
        fun guard(actual:()->Unit):()->Unit={val e=entryReads;val t=tagReads;actual();assertEquals(e,entryReads);assertEquals(t,tagReads);guards++}
        HandoffFixture{actual->object:UnifiedKeywordRepository by actual{
            override suspend fun tagChoices(after:UnifiedTagPage?,checkCurrent:()->Unit)=actual.tagChoices(after,guard(checkCurrent))
            override suspend fun selectTag(page:UnifiedTagPage,choice:UnifiedTagChoice,checkCurrent:()->Unit)=actual.selectTag(page,choice,guard(checkCurrent))
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,checkCurrent:()->Unit)=actual.search(keyword,scope,captureRange,tag,guard(checkCurrent))
            override suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,checkCurrent:()->Unit,admit:()->Unit)=actual.resolve(snapshot,item,guard(checkCurrent),admit)
        }}.use{h->
            UnifiedManualTagSqlTest.set(h.data,h.id,listOf("合成标签"));val rows=h.state.get().entries
            val counted=object:AbstractList<StoredEntry>(){override val size:Int get(){entryReads++;return rows.size};override fun get(index:Int):StoredEntry{entryReads++;return rows[index]}}
            val labels=object:AbstractMap<String,ManualTagSnapshot>(){override val entries:Set<Map.Entry<String,ManualTagSnapshot>> get(){tagReads++;return emptySet()}}
            h.state.set(h.state.get().copy(entries=counted,manualTags=labels));h.controller.open();val session=h.state.get().unifiedKeyword!!.session
            h.controller.openTags(session);h.queue.removeFirst()(h.owner);h.controller.selectTag(session,h.state.get().unifiedKeyword!!.tagPage!!.choices.single());h.queue.removeFirst()(h.owner)
            h.controller.keyword(session,"淡茶");h.controller.search(session);h.queue.removeFirst()(h.owner)
            val u=h.state.get().unifiedKeyword!!;assertNotNull(u.snapshot);var navigations=0
            h.controller.locate(session,u.visibleGroups.single().items.single()){_,confirm->assertTrue(confirm{navigations++})};h.queue.removeFirst()(h.owner)
            assertEquals(1,navigations);assertTrue(guards>20);assertTrue(entryReads>0);assertEquals(0,tagReads)
            println("TAG_REQUEST_GUARDS checks=$guards guardEntryReads=0 guardUiTagReads=0 fullBoundaryEntryReads=$entryReads")
        }
    }
    @Test fun clearingTagDuringActualPendingQueryRetiresOldRequestAndKeepsNewUnfilteredQueryUsable():Unit=runBlocking{Harness().use{h->
        h.ready();UnifiedManualTagSqlTest.set(h.data,h.id,listOf("合成标签"))
        h.vm.unifiedKeywords.open();val session=h.value().session;h.vm.unifiedKeywords.openTags(session)
        h.vm.unifiedKeywords.selectTag(session,h.ui{it.tagPage!=null&&!it.tagBusy}.tagPage!!.choices.single());h.ui{it.tag!=null&&!it.tagBusy}
        h.vm.unifiedKeywords.keyword(session,"淡茶");h.block=true;h.vm.unifiedKeywords.search(session)
        withTimeout(4000){h.entered.await()};assertTrue(h.value().busy);assertNotNull(h.value().tag)
        h.vm.unifiedKeywords.clearTag(session)
        assertFalse("Clearing a filter must also retire the old query's busy state",h.value().busy)
        assertNull(h.value().tag);assertNull(h.value().snapshot)
        h.vm.unifiedKeywords.search(session);h.release.complete(Unit)
        val fresh=h.ui{it.snapshot!=null&&!it.busy};assertNotSame(h.first,fresh.snapshot);assertNull(fresh.snapshot!!.tag)
        assertEquals("淡茶",fresh.keyword);assertEquals(2,h.calls)
    }}
    @Test fun unselectedPersonChooserObservesAliasAndSourceChangesInEveryScope():Unit=runBlocking{
        for(sourceChange in listOf(false,true))for(scope in UnifiedKeywordScope.entries)Harness(withPeople=true).use{h->
            h.ready();val other=h.data.f.entry(text="合成不命中来源");UnifiedManualPersonSqlTest.set(h.data,other,listOf("合成他名"),listOf(ManualPersonAlias("合成别称","合成他名")))
            h.vm.refreshLocalSearch();withTimeout(4000){h.vm.state.first{it.manualPeople[other]?.version==1L}}
            h.vm.unifiedKeywords.open();val session=h.value().session;h.vm.unifiedKeywords.scope(session,scope);h.vm.unifiedKeywords.openPeople(session)
            h.ui{it.personPicker&&!it.personBusy&&it.personPage!=null};assertNull(h.value().person)
            if(sourceChange)h.data.f.revise(other,"合成新版不命中")else UnifiedManualPersonSqlTest.set(h.data,other,listOf("合成他名"),listOf(ManualPersonAlias("合成新别称","合成他名")))
            h.vm.refreshLocalSearch();withTimeout(4000){h.vm.state.first{if(sourceChange)it.entries.any{e->e.id==other&&e.currentRevision==2}else it.manualPeople[other]?.version==2L}}
            assertNull("Unselected person chooser must retire: $scope source=$sourceChange",h.vm.state.value.unifiedKeyword)
            h.vm.unifiedKeywords.resume();assertNull(h.vm.state.value.unifiedKeyword)
        }
    }
    @Test fun personSnapshotSemanticEqualityKeepsProofAndAliasOnlyChangeRetiresEvenEmptyResult():Unit=runBlocking{Harness(withPeople=true).use{h->
        h.ready();UnifiedManualPersonSqlTest.set(h.data,h.id,listOf("合成甲"));h.vm.refreshLocalSearch();withTimeout(4000){h.vm.state.first{it.manualPeople[h.id]!=null}}
        choosePerson(h,"合成甲");val session=h.value().session;h.vm.unifiedKeywords.scope(session,UnifiedKeywordScope.PREFERENCE)
        h.vm.unifiedKeywords.keyword(session,"不存在");h.vm.unifiedKeywords.search(session);val old=h.ui{it.snapshot!=null&&!it.busy};assertTrue(old.snapshot!!.groups.isEmpty())
        val labels=h.vm.state.value.manualPeople[h.id];h.vm.refreshLocalSearch();withTimeout(4000){h.vm.state.first{it.manualPeople[h.id]!==labels}}
        assertSame(old.snapshot,h.value().snapshot)
        UnifiedManualPersonSqlTest.set(h.data,h.id,listOf("合成甲"),listOf(ManualPersonAlias("合成别称","合成甲")));h.vm.refreshLocalSearch()
        withTimeout(4000){h.vm.state.first{it.manualPeople[h.id]?.version==2L}};assertNull(h.vm.state.value.unifiedKeyword)
    }}
    @Test fun latePersonPageAfterRetirementCannotPopulateNewSessionOrSelectOldChoice():Unit=runBlocking{Harness(withPeople=true).use{h->
        h.ready();UnifiedManualPersonSqlTest.set(h.data,h.id,listOf("合成甲"));h.vm.unifiedKeywords.open();val old=h.value();h.block=true;h.vm.unifiedKeywords.openPeople(old.session)
        withTimeout(4000){h.entered.await()};assertTrue(h.value().personBusy);h.vm.unifiedKeywords.retire();h.vm.unifiedKeywords.open();val fresh=h.value();h.release.complete(Unit)
        h.vm.unifiedKeywords.openPeople(fresh.session);val page=h.ui{it.personPage!=null&&!it.personBusy}.personPage!!
        assertNotEquals(old.session,h.value().session);h.vm.unifiedKeywords.selectPerson(old.session,page.choices.single());assertNull(h.value().person)
        h.vm.unifiedKeywords.closePeople(fresh.session);assertFalse(h.value().personPicker);assertNull(h.value().personPage)
    }}
    @Test fun confirmedReturnReauthenticatesBothFiltersAndRealPeopleEditorAdmissionRetiresSeed():Unit=runBlocking{Harness(withPeople=true).use{h->
        h.ready();UnifiedManualPersonSqlTest.set(h.data,h.id,listOf("合成甲"));UnifiedManualTagSqlTest.set(h.data,h.id,listOf("合成标签"));h.vm.refreshLocalSearch()
        withTimeout(4000){h.vm.state.first{it.manualPeople[h.id]!=null&&it.manualTags[h.id]!=null}}
        choosePerson(h,"合成甲");var u=h.value();h.vm.unifiedKeywords.openTags(u.session)
        h.vm.unifiedKeywords.selectTag(u.session,h.ui{it.tagPage!=null&&!it.tagBusy}.tagPage!!.choices.single());h.ui{it.tag!=null&&!it.tagBusy}
        h.vm.unifiedKeywords.keyword(u.session,"淡茶");h.vm.unifiedKeywords.search(u.session);u=h.ui{it.snapshot!=null&&!it.busy}
        val first=CompletableDeferred<Unit>();h.vm.unifiedKeywords.locate(u.session,u.visibleGroups.single().items.first()){_,confirm->assertTrue(confirm{first.complete(Unit)})};withTimeout(4000){first.await()}
        h.vm.unifiedKeywords.close(u.session);h.vm.unifiedKeywords.resume();val fresh=h.ui{it.snapshot!=null&&!it.busy}
        assertNotSame(u.person,fresh.person);assertNotSame(u.tag,fresh.tag);assertNotSame(u.snapshot,fresh.snapshot);assertEquals(u.person!!.id,fresh.person!!.id);assertEquals(u.tag!!.id,fresh.tag!!.id)
        val next=CompletableDeferred<Unit>();h.vm.unifiedKeywords.locate(fresh.session,fresh.visibleGroups.single().items.first()){_,confirm->assertTrue(confirm{next.complete(Unit)})};withTimeout(4000){next.await()}
        val source=h.vm.state.value.manualPeopleReadingSource(h.id,1);assertNotNull(source);h.vm.people.open(source!!)
        withTimeout(4000){h.vm.state.first{it.manualPeopleEditor?.snapshot!=null&&!it.manualPeopleEditor.busy}}
        h.vm.people.close(h.vm.state.value.manualPeopleEditor!!.session);h.vm.unifiedKeywords.resume();assertNull(h.vm.state.value.unifiedKeyword)
    }}
    @Test fun clearingEitherFilterDuringPendingPersonQueryKeepsRemainingConditionUsable():Unit=runBlocking{
        for(clearPerson in listOf(false,true))Harness(withPeople=true).use{h->
            h.ready();UnifiedManualPersonSqlTest.set(h.data,h.id,listOf("合成甲"));UnifiedManualTagSqlTest.set(h.data,h.id,listOf("合成标签"));choosePerson(h,"合成甲")
            var u=h.value();h.vm.unifiedKeywords.openTags(u.session);h.vm.unifiedKeywords.selectTag(u.session,h.ui{it.tagPage!=null&&!it.tagBusy}.tagPage!!.choices.single());h.ui{it.tag!=null&&!it.tagBusy}
            h.vm.unifiedKeywords.keyword(u.session,"淡茶");h.block=true;h.vm.unifiedKeywords.search(u.session);withTimeout(4000){h.entered.await()}
            if(clearPerson)h.vm.unifiedKeywords.clearPerson(u.session)else h.vm.unifiedKeywords.clearTag(u.session)
            assertFalse(h.value().busy);assertFalse(h.value().personBusy);assertFalse(h.value().tagBusy);assertNull(h.value().snapshot)
            h.vm.unifiedKeywords.search(u.session);h.release.complete(Unit);u=h.ui{it.snapshot!=null&&!it.busy}
            assertNotSame(h.first,u.snapshot);if(clearPerson){assertNull(u.person);assertNotNull(u.tag)}else{assertNotNull(u.person);assertNull(u.tag)}
        }
    }
    @Test fun personUiNullHandoffStillRetiresOnObservedMetadataChange():Unit=runBlocking{HandoffFixture(withPeople=true).use{h->
        UnifiedManualPersonSqlTest.set(h.data,h.id,listOf("合成甲"));h.controller.open();val session=h.state.get().unifiedKeyword!!.session
        h.controller.openPeople(session);h.queue.removeFirst()(h.owner);h.controller.selectPerson(session,h.state.get().unifiedKeyword!!.personPage!!.choices.single());h.queue.removeFirst()(h.owner)
        h.controller.scope(session,UnifiedKeywordScope.ORIGINAL);h.controller.keyword(session,"淡茶");h.controller.search(session);h.queue.removeFirst()(h.owner)
        val u=h.state.get().unifiedKeyword!!;var navigations=0;h.block=true;h.controller.locate(session,u.visibleGroups.single().items.single()){_,confirm->confirm{navigations++}}
        val work=async(Dispatchers.IO){h.queue.removeFirst()(h.owner)}
        try{assertTrue(h.cleared.await(4,TimeUnit.SECONDS));val observed=h.controller.observeRetirement()!!;assertTrue(observed.people);assertTrue(observed.completeSources);h.controller.retireObserved(observed)}finally{h.release.countDown()}
        work.await();assertEquals(0,navigations);h.controller.resume();assertNull(h.state.get().unifiedKeyword)
    }}
    @Test fun personGuardsKeepEntriesTagsAndPeopleMapsOutsideEveryRowCheck():Unit=runBlocking{
        var entryReads=0;var tagReads=0;var personReads=0;var checks=0
        fun guard(check:()->Unit):()->Unit={val a=entryReads;val b=tagReads;val c=personReads;check();assertEquals(a,entryReads);assertEquals(b,tagReads);assertEquals(c,personReads);checks++}
        HandoffFixture(withPeople=true){actual->object:UnifiedKeywordRepository by actual{
            override suspend fun personChoices(after:UnifiedPersonPage?,checkCurrent:()->Unit)=actual.personChoices(after,guard(checkCurrent))
            override suspend fun selectPerson(page:UnifiedPersonPage,choice:UnifiedPersonChoice,checkCurrent:()->Unit)=actual.selectPerson(page,choice,guard(checkCurrent))
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,person:UnifiedPersonSelection?,checkCurrent:()->Unit)=actual.search(keyword,scope,captureRange,tag,person,guard(checkCurrent))
            override suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,checkCurrent:()->Unit,admit:()->Unit)=actual.resolve(snapshot,item,guard(checkCurrent),admit)
        }}.use{h->
            UnifiedManualPersonSqlTest.set(h.data,h.id,listOf("合成甲"));val rows=h.state.get().entries
            val entries=object:AbstractList<StoredEntry>(){override val size:Int get(){entryReads++;return rows.size};override fun get(index:Int):StoredEntry{entryReads++;return rows[index]}}
            val tags=object:AbstractMap<String,ManualTagSnapshot>(){override val entries:Set<Map.Entry<String,ManualTagSnapshot>> get(){tagReads++;return emptySet()}}
            val people=object:AbstractMap<String,ManualPeopleSnapshot>(){override val entries:Set<Map.Entry<String,ManualPeopleSnapshot>> get(){personReads++;return emptySet()}}
            h.state.set(h.state.get().copy(entries=entries,manualTags=tags,manualPeople=people));h.controller.open();val session=h.state.get().unifiedKeyword!!.session
            h.controller.openPeople(session);h.queue.removeFirst()(h.owner);h.controller.selectPerson(session,h.state.get().unifiedKeyword!!.personPage!!.choices.single());h.queue.removeFirst()(h.owner)
            h.controller.keyword(session,"淡茶");h.controller.search(session);h.queue.removeFirst()(h.owner);val u=h.state.get().unifiedKeyword!!;assertNotNull(u.snapshot)
            h.controller.locate(session,u.visibleGroups.single().items.single()){_,confirm->assertTrue(confirm{})};h.queue.removeFirst()(h.owner)
            assertTrue(checks>20);assertTrue(entryReads>0);assertEquals(0,tagReads);assertEquals(0,personReads)
            println("PERSON_REQUEST_GUARDS checks=$checks guardEntries=0 guardTags=0 guardPeople=0 fullBoundaryEntryReads=$entryReads")
        }
    }
    @Test fun unsupportedOrConditionDroppingPersonPortNeverPublishesUnfilteredContent():Unit=runBlocking{
        for(drop in listOf(false,true)){
            var reads=0
            HandoffFixture(withPeople=true){actual->
                val legacy=object:UnifiedKeywordRepository {
                    override suspend fun search(keyword:String,scope:UnifiedKeywordScope):UnifiedKeywordSnapshot {reads++;return actual.search(keyword,scope)}
                    override suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,admit:()->Unit)=actual.resolve(snapshot,item,admit)
                    override suspend fun personChoices(after:UnifiedPersonPage?,checkCurrent:()->Unit)=actual.personChoices(after,checkCurrent)
                    override suspend fun selectPerson(page:UnifiedPersonPage,choice:UnifiedPersonChoice,checkCurrent:()->Unit)=actual.selectPerson(page,choice,checkCurrent)
                }
                if(!drop)legacy else object:UnifiedKeywordRepository by legacy {
                    override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,person:UnifiedPersonSelection?,checkCurrent:()->Unit):UnifiedKeywordSnapshot {reads++;return actual.search(keyword,scope,captureRange,tag,checkCurrent)}
                }
            }.use{h->
                UnifiedManualPersonSqlTest.set(h.data,h.id,listOf("合成甲"));h.controller.open();val session=h.state.get().unifiedKeyword!!.session
                h.controller.openPeople(session);h.queue.removeFirst()(h.owner);h.controller.selectPerson(session,h.state.get().unifiedKeyword!!.personPage!!.choices.single());h.queue.removeFirst()(h.owner)
                h.controller.keyword(session,"淡茶");h.controller.search(session);h.queue.removeFirst()(h.owner)
                val u=h.state.get().unifiedKeyword!!;assertNull(u.snapshot);assertTrue(u.visibleGroups.isEmpty());assertNotNull(u.error)
                assertEquals(if(drop)1 else 0,reads)
                if(!drop)assertEquals("当前查询端口不支持人工人物标记，未显示未过滤内容。",u.error)
            }
        }
    }
    private suspend fun choosePerson(h:Harness,name:String){
        if(h.vm.state.value.unifiedKeyword==null)h.vm.unifiedKeywords.open()
        val session=h.value().session;h.vm.unifiedKeywords.openPeople(session)
        val page=h.ui{it.personPage!=null&&!it.personBusy}.personPage!!;h.vm.unifiedKeywords.selectPerson(session,page.choices.single{it.name==name});h.ui{it.person!=null&&!it.personBusy}
    }
    @Test fun actualFavoriteQueryReturnsFreshProofThenAcceptedWriteRetiresPrivateReturnSeed():Unit=runBlocking{Harness(withFavorites=true).use{h->
        h.ready();chooseFavorite(h,h.id,true)
        h.open("淡茶");h.ui{!it.busy&&it.snapshot!=null};var u=h.value()
        h.vm.unifiedKeywords.favorites(u.session,true);h.vm.unifiedKeywords.search(u.session)
        u=h.ui{!it.busy&&it.snapshot?.onlyFavorites==true};val proof=u.snapshot!!
        var nav=0;val firstNavigation=CompletableDeferred<Unit>();val secondNavigation=CompletableDeferred<Unit>()
        h.vm.unifiedKeywords.locate(u.session,u.visibleGroups.single().items.first()){_,confirm->assertTrue(confirm{nav++;firstNavigation.complete(Unit)})}
        withTimeout(4000){firstNavigation.await()};assertEquals(1,nav);assertNull(h.vm.state.value.unifiedKeyword)
        h.vm.unifiedKeywords.resume();u=h.ui{!it.busy&&it.snapshot!=null};assertTrue(u.onlyFavorites);assertNotSame(proof,u.snapshot)
        h.vm.unifiedKeywords.locate(u.session,u.visibleGroups.single().items.first()){_,confirm->assertTrue(confirm{nav++;secondNavigation.complete(Unit)})}
        withTimeout(4000){secondNavigation.await()};assertEquals(2,nav);assertNull(h.vm.state.value.unifiedKeyword)
        chooseFavorite(h,h.id,false);h.vm.unifiedKeywords.resume();assertNull(h.vm.state.value.unifiedKeyword)
        assertEquals(2L,SqlRecordFavoriteRepository(h.data.f.gateway).read(h.id).version)
    }}
    @Test fun allScopesAndEmptyResultRetireForUnmatchedSourceFavoriteChanges():Unit=runBlocking{
        for(scope in UnifiedKeywordScope.entries)Harness(withMemory=true,withFavorites=true).use{h->
            h.ready();val hidden=h.data.f.entry(text="合成未命中");h.vm.favorites.refresh()
            withTimeout(4000){h.vm.state.first{!it.favoritesLoading&&it.favorites.containsKey(hidden)}}
            chooseFavorite(h,h.id,true)
            h.open("淡茶",scope);h.ui{!it.busy&&it.snapshot!=null};val session=h.value().session
            h.vm.unifiedKeywords.favorites(session,true);h.vm.unifiedKeywords.search(session);val old=h.ui{!it.busy&&it.snapshot!=null}
            chooseFavorite(h,hidden,true)
            assertNull(h.vm.state.value.unifiedKeyword)
            old.snapshot!!.groups.firstOrNull()?.items?.firstOrNull()?.let{item->h.vm.unifiedKeywords.locate(old.session,item){_,_->fail("Retired source")}}
            h.vm.unifiedKeywords.resume();assertNull(h.vm.state.value.unifiedKeyword)
        }
    }
    @Test fun unknownFavoriteAckAndRefreshCannotPublishUntilSameIntentSettles():Unit=runBlocking{Harness(withFavorites=true).use{h->
        h.ready();h.loseFavoriteAck=true
        val e=h.vm.state.value.entries.single();h.vm.favorites.set(h.id,1,OriginalExports.hash(e.originalText),0,true)
        withTimeout(4000){h.vm.state.first{it.favoriteWrites[h.id]?.busy==false}}
        assertFalse(h.vm.state.value.favoritesAvailable);val intent=h.vm.state.value.favoriteWrites.getValue(h.id).intent
        h.vm.unifiedKeywords.open();var u=h.value();h.vm.unifiedKeywords.keyword(u.session,"淡茶");h.vm.unifiedKeywords.favorites(u.session,true)
        val before=h.calls;h.vm.unifiedKeywords.search(u.session);assertEquals(before,h.calls);assertNull(h.value().snapshot);assertNotNull(h.value().error)
        h.vm.favorites.refresh();withTimeout(4000){h.vm.state.first{!it.favoritesLoading&&it.favoritesAvailable}}
        assertSame(intent,h.vm.state.value.favoriteWrites.getValue(h.id).intent);assertNull(h.vm.state.value.unifiedKeyword)
        h.vm.unifiedKeywords.open();u=h.value();h.vm.unifiedKeywords.keyword(u.session,"淡茶");h.vm.unifiedKeywords.favorites(u.session,true)
        h.vm.unifiedKeywords.search(u.session);assertEquals(before,h.calls);assertNull(h.value().snapshot)
        h.vm.favorites.retry(h.id);withTimeout(4000){h.vm.state.first{it.favoriteWrites.isEmpty()&&it.favoritesAvailable}}
        assertEquals(1L,SqlRecordFavoriteRepository(h.data.f.gateway).read(h.id).version);assertNull(h.vm.state.value.unifiedKeyword)
        h.open("淡茶");h.ui{!it.busy&&it.snapshot!=null};u=h.value();h.vm.unifiedKeywords.favorites(u.session,true);h.vm.unifiedKeywords.search(u.session)
        assertTrue(h.ui{!it.busy&&it.snapshot!=null}.snapshot!!.onlyFavorites)
    }}
    @Test fun oldFavoriteTrueFalseTrueResultCannotReplaceNewRequest():Unit=runBlocking{Harness(withFavorites=true).use{h->
        h.ready();chooseFavorite(h,h.id,true);h.vm.unifiedKeywords.open();var u=h.value();h.vm.unifiedKeywords.keyword(u.session,"淡茶");h.vm.unifiedKeywords.favorites(u.session,true)
        h.block=true;h.vm.unifiedKeywords.search(u.session);withTimeout(4000){h.entered.await()}
        val oldRequest=h.value().request;h.vm.unifiedKeywords.favorites(u.session,false);h.vm.unifiedKeywords.favorites(u.session,true);h.vm.unifiedKeywords.search(u.session)
        assertTrue(h.value().request>oldRequest);assertNull(h.value().snapshot);h.release.complete(Unit)
        u=h.ui{!it.busy&&it.snapshot!=null};assertTrue(u.onlyFavorites);assertNotSame(h.first,u.snapshot)
        h.vm.unifiedKeywords.clear(u.session);assertFalse(h.value().onlyFavorites);assertNull(h.value().snapshot)
    }}
    @Test fun favoriteOldPortAndDroppingConditionNeverPublishUnfilteredResults():Unit=runBlocking{
        for(drop in listOf(false,true))HandoffFixture{actual->
            val legacy=object:UnifiedKeywordRepository {
                override suspend fun search(keyword:String,scope:UnifiedKeywordScope)=actual.search(keyword,scope)
                override suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,admit:()->Unit)=actual.resolve(snapshot,item,admit)
            }
            if(!drop)legacy else object:UnifiedKeywordRepository by legacy {
                override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,person:UnifiedPersonSelection?,onlyFavorites:Boolean,checkCurrent:()->Unit)=actual.search(keyword,scope,captureRange,tag,person,checkCurrent)
            }
        }.use{h->
            UnifiedFavoriteSqlTest.favorite(h.data,h.id,true)
            h.state.set(h.state.get().copy(supportsFavorites=true,favoritesAvailable=true,favorites=SqlRecordFavoriteRepository(h.data.f.gateway).snapshots()))
            h.controller.open();val s=h.state.get().unifiedKeyword!!.session;h.controller.keyword(s,"淡茶");h.controller.favorites(s,true);h.controller.search(s);h.queue.removeFirst()(h.owner)
            assertNull(h.state.get().unifiedKeyword!!.snapshot);assertNotNull(h.state.get().unifiedKeyword!!.error)
        }
    }
    @Test fun favoriteSqlPerRowGuardsDoNotReadUiEntriesFavoritesOrRetainedProof():Unit=runBlocking{
        var entryReads=0;var favoriteReads=0;var checks=0
        fun counted(guard:()->Unit):()->Unit={val e=entryReads;val f=favoriteReads;guard();assertEquals(e,entryReads);assertEquals(f,favoriteReads);checks++}
        HandoffFixture{actual->object:UnifiedKeywordRepository by actual {
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,person:UnifiedPersonSelection?,onlyFavorites:Boolean,checkCurrent:()->Unit)=actual.search(keyword,scope,captureRange,tag,person,onlyFavorites,counted(checkCurrent))
            override suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,checkCurrent:()->Unit,admit:()->Unit)=actual.resolve(snapshot,item,counted(checkCurrent),admit)
        }}.use{h->
            UnifiedFavoriteSqlTest.favorite(h.data,h.id,true);repeat(3){h.data.f.entry(text="合成其他")}
            val rows=h.state.get().entries;val values=SqlRecordFavoriteRepository(h.data.f.gateway).snapshots()
            val entries=object:AbstractList<StoredEntry>(){override val size:Int get(){entryReads++;return rows.size};override fun get(index:Int):StoredEntry{entryReads++;return rows[index]}}
            val favorites=object:AbstractMap<String,RecordFavoriteSnapshot>(){override val entries:Set<Map.Entry<String,RecordFavoriteSnapshot>> get(){favoriteReads++;return values.entries}}
            h.state.set(h.state.get().copy(entries=entries,supportsFavorites=true,favoritesAvailable=true,favorites=favorites))
            h.controller.open();val session=h.state.get().unifiedKeyword!!.session;h.controller.keyword(session,"淡茶");h.controller.favorites(session,true);h.controller.search(session);h.queue.removeFirst()(h.owner)
            val u=h.state.get().unifiedKeyword!!;assertTrue(u.snapshot!!.onlyFavorites);var nav=0
            h.controller.locate(session,u.visibleGroups.single().items.first()){_,confirm->assertTrue(confirm{nav++})};h.queue.removeFirst()(h.owner)
            assertEquals(1,nav);assertTrue(checks>20);assertTrue(entryReads>0);assertEquals(0,favoriteReads)
            println("FAVORITE_REQUEST_GUARDS checks=$checks guardEntries=0 guardFavorites=0 fullBoundaryEntryReads=$entryReads")
        }
    }
    @Test fun favoriteIntentRetiresAfterAcceptedCasBeforeEnqueueAndNeverForRejectedChoice():Unit=runBlocking{HandoffFixture().use{h->
        val actual=SqlRecordFavoriteRepository(h.data.f.gateway);h.state.set(h.state.get().copy(supportsFavorites=true,favoritesAvailable=true,favorites=actual.snapshots(),
            localProcessingAvailable=true,localProcessingVersions=SqlLocalProcessingRepository(h.data.f.gateway).versions()))
        h.load();val old=h.state.get().unifiedKeyword!!;var retired=0
        val writes=RecordFavoriteController({h.state.get()},{change->change(h.state.get());h.state.updateAndGet(change)},
            {action->assertEquals(1,retired);assertNull(h.state.get().unifiedKeyword);h.queue.add(action);true},
            {retired++;h.controller.retire()})
        val e=h.state.get().entries.single();val hash=OriginalExports.hash(e.originalText)
        writes.set(h.id,1,hash,8,true);assertEquals(0,retired);assertTrue(h.queue.isEmpty());assertSame(old,h.state.get().unifiedKeyword)
        writes.set(h.id,1,hash,0,true);assertEquals(1,retired);assertEquals(1,h.queue.size);assertFalse(actual.read(h.id).isFavorite)
        val store=object:EntryRepository by h.owner{override val favorites=actual}
        h.queue.removeFirst()(store);assertTrue(actual.read(h.id).isFavorite);assertTrue(h.state.get().favoriteWrites.isEmpty())
        h.controller.resume();assertNull(h.state.get().unifiedKeyword)
    }}
    @Test fun unconfirmedFavoriteConditionCannotStrandTagPickerOrPublishUnfilteredResults():Unit=runBlocking{HandoffFixture().use{h->
        h.data.f.tags.replace(ManualTagMutation(h.id,0,listOf("合成标签"),FullBackupTest.uuid()))
        h.state.set(h.state.get().copy(supportsFavorites=true,favoritesAvailable=false))
        h.controller.open();val session=h.state.get().unifiedKeyword!!.session;h.controller.keyword(session,"淡茶");h.controller.favorites(session,true)
        h.controller.openTags(session);assertTrue(h.state.get().unifiedKeyword!!.tagBusy);h.queue.removeFirst()(h.owner)
        var u=h.state.get().unifiedKeyword!!;assertFalse(u.tagBusy);assertEquals(listOf("合成标签"),u.tagPage!!.choices.map{it.name})
        h.controller.selectTag(session,u.tagPage!!.choices.single());h.queue.removeFirst()(h.owner);u=h.state.get().unifiedKeyword!!
        assertFalse(u.tagBusy);assertNotNull(u.tag);h.controller.search(session)
        assertTrue(h.queue.isEmpty());assertNull(h.state.get().unifiedKeyword!!.snapshot);assertNotNull(h.state.get().unifiedKeyword!!.error)
        h.controller.favorites(session,false);h.controller.search(session);h.queue.removeFirst()(h.owner)
        assertFalse(h.state.get().unifiedKeyword!!.snapshot!!.onlyFavorites);assertEquals(1,h.state.get().unifiedKeyword!!.snapshot!!.groups.size)
    }}
    private suspend fun loadReady(h:HandoffFixture){
        h.controller.open();val session=h.state.get().unifiedKeyword!!.session;h.controller.keyword(session,"淡茶");h.controller.readyImages(session,true);h.controller.search(session);h.queue.removeFirst()(h.owner)
        assertTrue(h.state.get().unifiedKeyword!!.snapshot!!.onlyReadyImages)
    }
    @Test fun readyImageModeProofReturnAndMutationInvalidateUiNullHandoffWithoutRevivingSeed():Unit=runBlocking{HandoffFixture().use{h->
        h.data.f.image(h.id);loadReady(h);var u=h.state.get().unifiedKeyword!!;var offer:UnifiedKeywordHandoff?=null
        h.controller.locate(u.session,u.visibleGroups.single().items.first()){_,confirm->offer=confirm};h.queue.removeFirst()(h.owner);assertNull(h.state.get().unifiedKeyword)
        h.controller.readyImageMutationAccepted();assertFalse(offer!!.invoke{fail("Retired image handoff")});h.controller.resume();assertNull(h.state.get().unifiedKeyword)
        loadReady(h);u=h.state.get().unifiedKeyword!!;val old=u.snapshot
        h.controller.locate(u.session,u.visibleGroups.single().items.first()){_,confirm->assertTrue(confirm{})};h.queue.removeFirst()(h.owner)
        h.controller.resume();h.queue.removeFirst()(h.owner);u=h.state.get().unifiedKeyword!!;assertTrue(u.onlyReadyImages);assertNotSame(old,u.snapshot)
        h.controller.locate(u.session,u.visibleGroups.single().items.first()){_,confirm->assertTrue(confirm{})};h.queue.removeFirst()(h.owner)
        h.controller.readyImageMutationAccepted();h.controller.resume();assertNull(h.state.get().unifiedKeyword)
        h.controller.open();u=h.state.get().unifiedKeyword!!;assertFalse(u.onlyReadyImages);h.controller.readyImages(u.session,true);h.controller.clear(u.session);assertFalse(h.state.get().unifiedKeyword!!.onlyReadyImages)
    }}
    @Test fun readyImageLegacyPortOrDroppedModeNeverPublishesUnfilteredSnapshot():Unit=runBlocking{
        for(drop in listOf(false,true))HandoffFixture{actual->
            val old=object:UnifiedKeywordRepository{override suspend fun search(keyword:String,scope:UnifiedKeywordScope)=actual.search(keyword,scope)
                override suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,admit:()->Unit)=actual.resolve(snapshot,item,admit)}
            if(!drop)old else object:UnifiedKeywordRepository by old{
                override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,person:UnifiedPersonSelection?,onlyFavorites:Boolean,onlyReadyImages:Boolean,checkCurrent:()->Unit)=actual.search(keyword,scope,captureRange,tag,person,onlyFavorites,checkCurrent)
            }
        }.use{h->h.data.f.image(h.id);h.controller.open();val s=h.state.get().unifiedKeyword!!.session;h.controller.keyword(s,"淡茶");h.controller.readyImages(s,true);h.controller.search(s);h.queue.removeFirst()(h.owner)
            assertNull(h.state.get().unifiedKeyword!!.snapshot);assertNotNull(h.state.get().unifiedKeyword!!.error)
        }
    }
    @Test fun readyImagePerRowGuardDoesNotTraverseEntriesAttachmentsOrOldProof():Unit=runBlocking{
        var reads=0;var checks=0
        HandoffFixture{actual->object:UnifiedKeywordRepository by actual{
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,person:UnifiedPersonSelection?,onlyFavorites:Boolean,onlyReadyImages:Boolean,checkCurrent:()->Unit)=
                actual.search(keyword,scope,captureRange,tag,person,onlyFavorites,onlyReadyImages,{val before=reads;checkCurrent();assertEquals(before,reads);checks++})
        }}.use{h->h.data.f.image(h.id);repeat(22){h.data.f.entry()};val rows=h.state.get().entries
            h.state.set(h.state.get().copy(entries=object:AbstractList<StoredEntry>(){override val size:Int get(){reads++;return rows.size};override fun get(index:Int):StoredEntry{reads++;return rows[index]}}))
            loadReady(h);assertTrue(checks>100);assertTrue(reads>0);println("READY_IMAGE_GUARDS checks=$checks guardEntries=0 boundaryEntryReads=$reads")
        }
    }
    @Test fun acceptedImageSelectionRetiresBeforeQueueButNoopReadCancelAndStaleSelectionDoNot():Unit=runBlocking{HandoffFixture().use{h->
        h.data.f.image(h.id);loadReady(h);var notices=0;val sourceGate=CaptureDraftGate()
        val media=object:AttachmentStore by h.data.f.media{override suspend fun read(id:String)=h.data.f.media.readReadOnly(id);override suspend fun list(entryId:String,revision:Int)=h.data.f.media.listReadOnly(entryId,revision)}
        val owner=object:EntryRepository by h.owner{override val attachments=media}
        val q=java.util.ArrayDeque<suspend(EntryRepository)->Unit>()
        val attachments=LocalAttachmentController({q.add(it);true},{object:AttachmentPicture{override val width=1;override val height=1}},sourceUi={sourceGate.sourceUi(it)},beforeImageMutation={assertFalse(Thread.holdsLock(sourceGate));notices++;h.controller.readyImageMutationAccepted()})
        try{
            attachments.open(h.id,1);q.removeFirst()(owner);assertNotNull(h.state.get().unifiedKeyword);assertEquals(0,notices)
            val panel=attachments.state.value!!;attachments.select(panel.sessionId);val selection=attachments.state.value!!.selection!!
            attachments.selected(selection.requestId+1,null);assertEquals(0,notices)
            attachments.selected(selection.requestId,null);while(q.isNotEmpty())q.removeFirst()(owner);assertEquals(0,notices);assertNotNull(h.state.get().unifiedKeyword)
            attachments.select(panel.sessionId);val next=attachments.state.value!!.selection!!
            attachments.selected(next.requestId,object:SelectedImageSource{override fun mediaType()="image/png";override fun open()=FullBackupTest.PNG.inputStream()})
            assertEquals(1,notices);assertNull(h.state.get().unifiedKeyword);assertEquals(AttachmentImportPhase.PREPARING,attachments.state.value!!.selection!!.phase)
            assertEquals(1,h.data.f.db.read("SELECT * FROM attachments").size)
            while(q.isNotEmpty())q.removeFirst()(owner)
            assertEquals(2,h.data.f.db.read("SELECT * FROM attachments").size);assertNull(attachments.state.value!!.selection)
            loadReady(h);val fresh=h.state.get().unifiedKeyword;attachments.selected(next.requestId,null);assertSame(fresh,h.state.get().unifiedKeyword)
        }finally{attachments.markDisposed();attachments.disposeOnIo()}
    }}
    @Test fun actualVmReadyImageObservationRetiresEveryScopeOnNonmatchingImageChange():Unit=runBlocking{
        for(scope in UnifiedKeywordScope.entries)Harness(withImages=true).use{h->
            h.ready();h.vm.unifiedKeywords.open();val s=h.value().session;h.vm.unifiedKeywords.scope(s,scope);h.vm.unifiedKeywords.keyword(s,"淡茶");h.vm.unifiedKeywords.readyImages(s,true);h.vm.unifiedKeywords.search(s)
            val u=h.ui{!it.busy&&it.snapshot!=null};assertTrue(u.snapshot!!.onlyReadyImages)
            val other=h.data.f.entry(text="合成不命中");h.data.f.image(other);h.vm.refreshLocalSearch()
            withTimeout(4000){h.vm.state.first{it.entries.any{e->e.id==other}}};assertNull(h.vm.state.value.unifiedKeyword)
            h.vm.unifiedKeywords.resume();assertNull(h.vm.state.value.unifiedKeyword)
        }
    }
    @Test fun actualVmOldReadyModeCannotReturnAfterOffOnAndClearWhileReadWasDelayed():Unit=runBlocking{Harness(withImages=true).use{h->
        h.ready();h.vm.unifiedKeywords.open();val s=h.value().session;h.vm.unifiedKeywords.keyword(s,"淡茶");h.vm.unifiedKeywords.readyImages(s,true);h.block=true;h.vm.unifiedKeywords.search(s)
        withTimeout(4000){h.entered.await()};h.vm.unifiedKeywords.readyImages(s,false);h.vm.unifiedKeywords.readyImages(s,true);h.vm.unifiedKeywords.search(s);h.release.complete(Unit)
        val u=h.ui{!it.busy&&it.snapshot!=null};assertTrue(u.onlyReadyImages);assertNotSame(h.first,u.snapshot);assertEquals(2,h.calls)
        h.vm.unifiedKeywords.clear(s);assertFalse(h.value().onlyReadyImages);assertNull(h.value().snapshot)
    }}
    private suspend fun chooseFavorite(h:Harness,id:String,value:Boolean){
        val state=h.vm.state.value;val e=state.entries.single{it.id==id};h.vm.favorites.set(id,e.currentRevision,OriginalExports.hash(e.originalText),state.favorites.getValue(id).version,value)
        withTimeout(4000){h.vm.state.first{it.favoriteWrites[id]==null&&it.favoritesAvailable&&it.favorites[id]?.isFavorite==value}}
    }
    private class Harness(extra:Int=0,withMemory:Boolean=false,withPeople:Boolean=false,private val withFavorites:Boolean=false,private val withImages:Boolean=false,private val holdInitialIndex:Boolean=false):AutoCloseable {
        val data=UnifiedKeywordFixture(withPeople=withPeople);val id=data.f.entry(text="合成淡茶")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var block=false
        var first:UnifiedKeywordSnapshot?=null;var calls=0;var loseFavoriteAck=false
        val indexEntered=java.util.concurrent.CountDownLatch(1);val indexRelease=java.util.concurrent.CountDownLatch(1)
        init{runBlocking{data.audio(id);if(withImages)data.f.image(id);repeat(extra){data.audio(data.f.entry(text="合成淡茶"))};if(withMemory)AcceptedMemoryKeywordSqlTest.memory(data,id,"备注专词");if(holdInitialIndex)UnifiedFavoriteSqlTest.favorite(data,id,true)}}
        val port:UnifiedKeywordRepository=object:UnifiedKeywordRepository by data.repo{
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,person:UnifiedPersonSelection?,onlyFavorites:Boolean,onlyReadyImages:Boolean,checkCurrent:()->Unit):UnifiedKeywordSnapshot {
                calls++;val result=data.repo.search(keyword,scope,captureRange,tag,person,onlyFavorites,onlyReadyImages,checkCurrent)
                if(block){block=false;first=result;entered.complete(Unit);release.await()};return result
            }
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,person:UnifiedPersonSelection?,onlyFavorites:Boolean,checkCurrent:()->Unit):UnifiedKeywordSnapshot {
                calls++;val result=data.repo.search(keyword,scope,captureRange,tag,person,onlyFavorites,checkCurrent)
                if(block){block=false;first=result;entered.complete(Unit);release.await()};return result
            }
            override suspend fun personChoices(after:UnifiedPersonPage?,checkCurrent:()->Unit):UnifiedPersonPage {
                val result=data.repo.personChoices(after,checkCurrent)
                if(block){block=false;entered.complete(Unit);release.await()};return result
            }
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,person:UnifiedPersonSelection?,checkCurrent:()->Unit):UnifiedKeywordSnapshot {
                calls++;val result=data.repo.search(keyword,scope,captureRange,tag,person,checkCurrent)
                if(block){block=false;first=result;entered.complete(Unit);release.await()};return result
            }
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,tag:UnifiedTagSelection?,checkCurrent:()->Unit):UnifiedKeywordSnapshot {
                calls++;val result=data.repo.search(keyword,scope,captureRange,tag,checkCurrent)
                if(block){block=false;first=result;entered.complete(Unit);release.await()};return result
            }
            override suspend fun tagChoices(after:UnifiedTagPage?,checkCurrent:()->Unit):UnifiedTagPage {
                val result=data.repo.tagChoices(after,checkCurrent)
                if(block){block=false;entered.complete(Unit);release.await()};return result
            }
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope)=search(keyword,scope,{})
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope,checkCurrent:()->Unit)=search(keyword,scope,UnifiedCaptureRange(),checkCurrent)
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope,captureRange:UnifiedCaptureRange,checkCurrent:()->Unit):UnifiedKeywordSnapshot {
                calls++;val result=data.repo.search(keyword,scope,captureRange,checkCurrent)
                if(block){block=false;first=result;entered.complete(Unit);release.await()};return result
            }
        }
        private val base=SqliteVmEntryRepository(data.f.db,data.f.gateway,RemoteTextTransactions(data.f.gateway)){data.f.media.close();data.f.media.awaitIdle();data.f.gateway.stopAndAwaitIdle()}
        val owner:EntryRepository=object:EntryRepository by base,AutoCloseable{
            override val unifiedKeywords=port
            override val audioTranscripts=data.audios
            override val manualTags=data.f.tags
            override val manualPeople=if(withPeople)data.people else null
            override val localProcessing=if(withPeople||withFavorites)SqlLocalProcessingRepository(data.f.gateway)else null
            override val favorites=if(withFavorites)object:RecordFavoriteRepository by SqlRecordFavoriteRepository(data.f.gateway){
                override fun set(mutation:RecordFavoriteMutation):RecordFavoriteSnapshot {
                    val saved=SqlRecordFavoriteRepository(data.f.gateway).set(mutation)
                    if(loseFavoriteAck){loseFavoriteAck=false;error("Synthetic committed favorite ACK loss")};return saved
                }
            }else null
            override val localSearch=if(withFavorites||withImages)object:LocalSearchRepository by data.originals {
                override fun batch():LocalSearchProgress {
                    if(holdInitialIndex){indexEntered.countDown();check(indexRelease.await(4,TimeUnit.SECONDS))}
                    return data.originals.batch()
                }
            }else null
            override val sourceMemories=if(withMemory)data.memories else null
            override suspend fun prepare(){check(data.f.db.read("PRAGMA user_version").single().single()==12L)}
            override fun close()=base.close()
        }
        val vm=LocalRecordsViewModel(Application(),{owner},Clock.systemUTC())
        suspend fun ready()=withTimeout(4000){
            vm.state.first{it.ready&&!it.opening&&it.unifiedKeywordOwner===port}
            if(withImages||withFavorites){
                // Initial open publishes before scheduling its index command. Two serial markers
                // prove that the initial (single-source) batch really finished, with no timing sleeps.
                repeat(2){val ack=CompletableDeferred<Unit>();check(vm.enqueueTaskOperation{ack.complete(Unit)});ack.await()}
                vm.state.first{!it.localIndexBusy&&it.localIndexError==null}
            }
        }
        fun value()=checkNotNull(vm.state.value.unifiedKeyword)
        fun open(keyword:String,scope:UnifiedKeywordScope=UnifiedKeywordScope.ALL,from:String="",through:String=""){vm.unifiedKeywords.open();val u=value();vm.unifiedKeywords.scope(u.session,scope);vm.unifiedKeywords.keyword(u.session,keyword);vm.unifiedKeywords.from(u.session,from);vm.unifiedKeywords.through(u.session,through);vm.unifiedKeywords.search(u.session)}
        suspend fun ui(predicate:(UnifiedKeywordUi)->Boolean)=withTimeout(8000){vm.state.first{it.unifiedKeyword?.let(predicate)==true}.unifiedKeyword!!}
        override fun close(){indexRelease.countDown();release.complete(Unit);vm.clearForHostTest();assertTrue(base.closed.await(4,TimeUnit.SECONDS));base.closeFailure.get()?.let{throw it};data.close()}
    }
    /** Real repository/controller, with a publication barrier AFTER the real atomic state CAS.
     * No production-only testing hook, simulated pause is the actual retire/dispose entry. */
    private class HandoffFixture(withPeople:Boolean=false,decorate:(UnifiedKeywordRepository)->UnifiedKeywordRepository={it}):AutoCloseable {
        val data=UnifiedKeywordFixture(withPeople=withPeople);val id=data.f.entry(text="合成淡茶")
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
