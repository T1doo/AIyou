package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual App matcher/controller with real people SQL/proof; no Android or semantic inference. */
class ManualPeopleSearchControllerTest {
    @Test(timeout=12_000) fun personOnlyActuallyFiltersAndSameTextAbaRevokesOldResultBeforeRecomposition():Unit=Harness().use{h->runBlocking{
        h.controller.sync(true);val first=h.ready();assertEquals(listOf(h.id),first.hits.map{it.entry.id})
        assertTrue(h.controller.canOpen(first,first.hits.single()))
        h.f.people.replace(h.f.mutation(h.id,emptyList()));h.refresh()
        assertFalse(h.controller.canOpen(first,first.hits.single()));h.controller.sync(true)
        assertTrue(h.ready().hits.isEmpty())
        h.f.people.replace(h.f.mutation(h.id,listOf("小林")));h.refresh();h.controller.sync(true)
        val restored=h.ready();assertEquals(listOf(h.id),restored.hits.map{it.entry.id})
        assertFalse(h.controller.canOpen(first,first.hits.single()))
        val opened=java.util.concurrent.atomic.AtomicBoolean();h.controller.open(restored,restored.hits.single()){opened.set(true)}
        withTimeout(5_000){while(!opened.get())delay(5)}
    }}
    @Test(timeout=12_000) fun failedPeopleReadIsExplicitAndOriginalVersionRefreshReusesNamesWithFreshPrivateProof():Unit=Harness().use{h->runBlocking{
        h.input.set(h.input.get().copy(query="合成"));h.controller.sync(true);val before=h.ready()
        h.f.base.append(h.id);h.refresh(keepPeople=true);h.controller.sync(true)
        val revised=h.ready();assertEquals(2,revised.hits.single().entry.currentRevision)
        assertFalse(h.controller.canOpen(before,before.hits.single()))
        h.source.set(h.source.get().copy(manualPeopleAvailable=false,manualPeople=emptyMap()))
        h.input.set(h.input.get().copy(peopleSnapshot=null));h.controller.sync(true)
        val failure=withTimeout(5_000){h.controller.state.first{it.stage==OriginalSearchStage.FAILED}}
        assertTrue(failure.hits.isEmpty());assertFalse(h.controller.canOpen(revised,revised.hits.single()))
    }}
    private class Harness:AutoCloseable {
        val f=ManualPeopleFixture();val id=f.base.entry();private val other=f.base.entry(text="合成另一条记录")
        val source:AtomicReference<LocalRecordUiState>
        val input:AtomicReference<OriginalSearchInput>
        val controller:LocalOriginalSearchController
        init{
            f.people.replace(f.mutation(id,listOf("小林")));f.base.drain()
            source=AtomicReference(LocalRecordUiState(ready=true,opening=false,entries=entries(),localSearch=f.search,
                localSearchEpoch=f.search.epoch(),manualPeople=f.people.snapshots(),manualPeopleAvailable=true))
            input=AtomicReference(OriginalSearchInput(person="小林",peopleSnapshot=source.get().manualPeople))
            controller=LocalOriginalSearchController(source::get,input::get,Dispatchers.IO,
                matcher={entries,query->LocalOriginalSearchMatcher.indexed(entries,query,f.search)})
        }
        private fun entries()=f.search.candidates(com.t1doo.aiyou.domain.LocalSearchRequest("")).entries
        fun refresh(keepPeople:Boolean=false){
            val map=if(keepPeople)source.get().manualPeople else f.people.snapshots()
            source.set(source.get().copy(entries=entries(),localSearchEpoch=f.search.epoch(),manualPeople=map))
            input.set(input.get().copy(peopleSnapshot=map))
        }
        suspend fun ready()=withTimeout(5_000){controller.state.first{it.stage==OriginalSearchStage.READY}}
        override fun close(){controller.close();f.base.gateway.stopAndAwaitIdle();f.close()}
    }
}
