package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Actual controller and production attachment proof with synthetic host SQL transport. */
class CurrentImageSearchControllerTest {
    @Test fun imageOnlyStartsLookupWithoutWordsAndCombinedClearRetiresOldHits(): Unit = Harness().use { h ->
        h.controller.sync(true);h.await{h.controller.state.value.stage==OriginalSearchStage.BROWSE}
        h.change(OriginalSearchInput("needle",from="1970-01-01",through="1970-01-01",onlyImages=true))
        val combined=h.ready();assertEquals(listOf(h.id),combined.hits.map{it.entry.id})
        h.controller.clear();h.input.set(OriginalSearchInput());h.controller.sync(true)
        h.await{h.controller.state.value.stage==OriginalSearchStage.BROWSE}
        assertFalse(h.controller.canOpen(combined,combined.hits.single()))
        assertFalse(h.controller.state.value.input.onlyImages)
        h.change(OriginalSearchInput(onlyImages=true));val images=h.ready()
        assertEquals(listOf(h.id),images.hits.map{it.entry.id})
        h.controller.changeInput{h.input.set(OriginalSearchInput());h.input.set(OriginalSearchInput(onlyImages=true,ownership=Any()))}
        assertFalse(h.controller.canOpen(images,images.hits.single()))
        h.controller.sync(true);assertNotSame(images.proof,h.ready().proof)
    }

    @Test fun attachmentRemovedBetweenMatchAndPublicationCannotPublishOldHits(): Unit = Harness().use { h ->
        val computed=CountDownLatch(1);val release=CountDownLatch(1)
        h.afterMatch={computed.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}
        h.change(OriginalSearchInput(onlyImages=true));assertTrue(computed.await(5,TimeUnit.SECONDS))
        h.fixture.second().use{it.write("DELETE FROM attachments WHERE id=?",h.pic)}
        release.countDown();h.await{h.invalidations.get()>0}
        assertTrue(h.controller.state.value.hits.isEmpty())
        assertNotEquals(OriginalSearchStage.READY,h.controller.state.value.stage)
    }

    @Test fun changedAttachmentAfterReadyCannotOpenOldResultBeforeSourceRefresh(): Unit = Harness().use { h ->
        h.change(OriginalSearchInput(onlyImages=true));val old=h.ready()
        h.fixture.db.write("DELETE FROM attachments WHERE id=?",h.pic)
        val opened=AtomicInteger();h.controller.open(old,old.hits.single()){opened.incrementAndGet()}
        h.await{h.invalidations.get()>0};assertEquals(0,opened.get())
        assertTrue(h.controller.state.value.hits.isEmpty())
        assertEquals(OriginalSearchStage.WAITING,h.controller.state.value.stage)
    }

    @Test fun imageReadFailureIsStableExplicitFailureAndSwitchingOffAllowsBrowse(): Unit = Harness().use { h ->
        h.fixture.db.beforeSql={if(it.startsWith("SELECT id,revision,mediaType"))error("Synthetic read failure")}
        h.change(OriginalSearchInput(onlyImages=true));h.await{h.controller.state.value.stage==OriginalSearchStage.FAILED}
        val failed=h.controller.state.value
        assertTrue(failed.hits.isEmpty());assertTrue(failed.message!!.contains("原文已保留"))
        repeat(5){h.controller.sync(true)};assertSame(failed,h.controller.state.value)
        h.fixture.db.beforeSql=null;h.change(OriginalSearchInput())
        h.await{h.controller.state.value.stage==OriginalSearchStage.BROWSE}
    }

    private class Harness:AutoCloseable {
        val fixture=RecordEventDateFixture()
        val id=fixture.entry()
        val pic=UUID.randomUUID().toString()
        val input=AtomicReference(OriginalSearchInput())
        val invalidations=AtomicInteger()
        @Volatile var afterMatch:(()->Unit)?=null
        val source:LocalRecordUiState
        val controller:LocalOriginalSearchController
        init {
            fixture.db.write("INSERT INTO attachments VALUES(?,?,1,'image/png',?,9,'READY')",pic,id,"a".repeat(64))
            fixture.entry(text="合成 needle 纯文字");fixture.drain()
            source=LocalRecordUiState(ready=true,opening=false,
                entries=fixture.search.candidates(LocalSearchRequest("")).entries,
                localSearch=fixture.search,localSearchEpoch=fixture.search.epoch())
            controller=LocalOriginalSearchController({source},input::get,Dispatchers.IO,
                onInvalidated={invalidations.incrementAndGet()},matcher={entries,request ->
                    LocalOriginalSearchMatcher.indexed(entries,request,fixture.search).also{afterMatch?.invoke()}
                })
        }
        fun change(value:OriginalSearchInput){controller.changeInput{input.set(value)};controller.sync(true)}
        fun await(predicate:()->Boolean)=runBlocking{withTimeout(10_000){while(!predicate())delay(5)}}
        fun ready():OriginalSearchState{await{controller.state.value.stage==OriginalSearchStage.READY};return controller.state.value}
        override fun close(){afterMatch=null;fixture.db.beforeSql=null;controller.close();fixture.close()}
    }
}
