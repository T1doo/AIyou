package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual VM actor and encrypted repository. Decoder/engine are explicitly synthetic host ports. */
class ImageTextViewModelTest {
    @Test(timeout=20_000) fun productionUnavailableAndAiNoneDoNotInferOrAllowManualCreationAndRequireExactViewedImage():Unit=runBlocking{
        for(none in listOf(false,true))Harness(available=none,initialPolicy=if(none)ProcessingPolicy.AI_NONE else ProcessingPolicy.LOCAL_ONLY).use{h->
            h.ready();h.vm.imageTexts.open(h.intent);assertNull(h.vm.state.value.imageTexts)
            h.openViewed();val u=h.ui{it.anchor!=null&&!it.busy}
            h.vm.imageTexts.edit(u.session,"不得以手工填写冒充提取");h.vm.imageTexts.save(u.session);assertEquals("",h.value().text)
            h.vm.imageTexts.extract(u.session);assertTrue(h.value().error!!.contains(if(none)"不使用 AI"else"未运行 OCR"))
            assertEquals(0,h.engineCalls.get());assertTrue(h.writes.isEmpty());assertTrue(h.repo.search("").items.isEmpty())
        }
    }
    @Test(timeout=20_000) fun actualActorOneExtractionLostAckRetryCorrectionAndDeleteCancellationPreserveOriginal():Unit=runBlocking{Harness().use{h->
        h.ready();h.openViewed();val first=h.ui{it.anchor!=null&&!it.busy};val original=h.f.media.read(h.intent.id)
        h.loseAck=true;h.vm.imageTexts.extract(first.session);val unknown=h.ui{it.pending!=null&&it.phase=="UNKNOWN"&&!it.busy}
        assertEquals(1,h.engineCalls.get());assertEquals(1L,h.repo.read(unknown.anchor!!)!!.version)
        h.vm.imageTexts.retry(first.session);var u=h.ui{it.snapshot!=null&&it.pending==null&&!it.busy};assertSame(h.writes[0],h.writes[1]);assertTrue(u.snapshot!!.engine.synthetic)
        h.vm.imageTexts.edit(first.session,"合成人工纠正");h.vm.imageTexts.save(first.session);u=h.ui{it.snapshot?.history?.size==2&&!it.busy}
        h.vm.imageTexts.delete(first.session);val cancelled=h.value().deletion!!;h.vm.imageTexts.cancelDelete(first.session,cancelled);h.vm.imageTexts.delete(first.session,cancelled)
        assertNull(h.value().deletion);assertEquals(3,h.writes.size)
        h.vm.imageTexts.delete(first.session);h.vm.imageTexts.delete(first.session,h.value().deletion);h.ui{it.snapshot==null&&it.notice!=null&&!it.busy}
        assertTrue(h.repo.search("").items.isEmpty());assertArrayEquals(original,h.f.media.read(h.intent.id));assertEquals(1,h.engineCalls.get())
        val count=h.writes.size;h.vm.imageTexts.close(first.session);h.vm.imageTexts.save(first.session);h.vm.imageTexts.retry(first.session);assertEquals(count,h.writes.size)
    }}
    @Test(timeout=20_000) fun cancellationAndOwnerRetirementWaitForActualProducerReturnWipeBytesAndNeverCommitLateText():Unit=runBlocking{Harness().use{h->
        h.ready();h.openViewed();val u=h.ui{it.anchor!=null&&!it.busy};h.block=true;h.vm.imageTexts.extract(u.session)
        withTimeout(4_000){h.entered.await()};assertFalse(h.vm.appAccessIdle.value);assertTrue(h.vm.imageTexts.inFlight.value)
        h.vm.imageTexts.cancelExtraction(u.session,h.value().request);assertEquals("CANCELLING",h.value().phase);assertTrue(h.value().busy)
        h.vm.imageTexts.close(u.session);h.vm.imageTexts.open(h.intent);assertNull(h.vm.state.value.imageTexts)
        h.vm.clearForHostTest();assertFalse(h.closed.await(100,TimeUnit.MILLISECONDS));assertEquals(0,h.writes.size)
        assertTrue(h.cancelled!!.invoke());h.release.complete(Unit)
        assertTrue(h.closed.await(4,TimeUnit.SECONDS));assertFalse(h.vm.imageTexts.inFlight.value);assertTrue(h.lastBytes!!.all{it==0.toByte()});assertEquals(0,h.writes.size)
    }}
    @Test(timeout=20_000) fun observedRevisionRetiresUnknownSavedContentAndPreCommitSourceChangeRejectsLateExtraction():Unit=runBlocking{
        for(unknown in listOf(false,true))Harness().use{h->
            h.ready();h.openViewed();val u=h.ui{it.anchor!=null&&!it.busy}
            if(unknown){h.loseAck=true;h.vm.imageTexts.extract(u.session);h.ui{it.phase=="UNKNOWN"&&!it.busy}}
            else{h.block=true;h.vm.imageTexts.extract(u.session);withTimeout(4_000){h.entered.await()}}
            h.f.revise(h.intent.entryId,"合成新版本");h.vm.refreshLocalSearch();h.release.complete(Unit)
            withTimeout(4_000){h.vm.state.first{it.entries.any{e->e.id==h.intent.entryId&&e.currentRevision==2}}}
            assertNull(h.vm.state.value.imageTexts);h.vm.imageTexts.retry(u.session);assertEquals(if(unknown)1 else 0,h.writes.size)
        }
    }
    @Test(timeout=20_000) fun actualActorQuerySwitchAndDeletedSourceDuringDelayedReadNeverPublishAnOldPageOrLocation():Unit=runBlocking{Harness().use{h->
        h.ready();val a=h.repo.preview(h.intent);h.repo.write(ImageTextSqlTest.create(a,"合成甲查询"))
        val b=h.f.image(h.f.entry(text="合成乙原文"));h.repo.write(ImageTextSqlTest.create(h.repo.preview(b),"合成乙查询"))
        h.vm.refreshLocalSearch();withTimeout(4_000){h.vm.state.first{it.entries.any{e->e.id==b.entryId}}}
        h.vm.imageTexts.open();val initial=h.ui{it.page!=null&&!it.busy};h.blockedKeyword="甲查询"
        h.vm.imageTexts.keyword(initial.session,"甲查询");h.vm.imageTexts.search(initial.session);withTimeout(4_000){h.searchEntered.await()}
        h.vm.imageTexts.keyword(initial.session,"乙查询");assertTrue(h.value().items.isEmpty());assertNull(h.value().page)
        h.vm.imageTexts.search(initial.session);h.searchRelease.complete(Unit)
        val found=h.ui{it.page?.keyword=="乙查询"&&!it.busy};assertEquals(b.id,found.items.single().anchor.intent.id);assertEquals(0,h.stalePublications.get())
        h.blockRead=true;val location=CompletableDeferred<ImageTextSource>()
        h.vm.imageTexts.locate(initial.session,found.items.single()){location.complete(it)};withTimeout(4_000){h.readEntered.await()}
        h.f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",b.entryId)
        h.vm.refreshLocalSearch();h.readRelease.complete(Unit)
        withTimeout(4_000){h.vm.state.first{it.entries.none{e->e.id==b.entryId}}}
        assertFalse(location.isCompleted);assertTrue(h.vm.state.value.imageTexts?.items.isNullOrEmpty());assertEquals(0,h.stalePublications.get())
    }}
    @Test(timeout=15_000) fun queuedCancellationNeverStartsProducerAndOldRequestCannotCancelItsSuccessor():Unit=runBlocking{Harness().use{h->
        h.ready();val state=java.util.concurrent.atomic.AtomicReference(h.vm.state.value)
        val queue=java.util.concurrent.ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>();val calls=AtomicInteger()
        val engine=object:ImageTextRecognizer {override val available=true;override suspend fun recognize(source:ImageTextSource,encoded:ByteArray,cancelled:()->Boolean):ImageTextExtraction?{check(!cancelled());calls.incrementAndGet();return ImageTextExtraction(ImageTextEngine("synthetic-queue-port","1",true),"合成后继结果")}}
        val c=ImageTextController({state.get()},{t->state.updateAndGet(t)},{queue.add(it);true},Clock.systemUTC(),engine)
        try{c.open(h.intent);queue.poll()!!(h.owner);val session=state.get().imageTexts!!.session
            c.extract(session);val first=state.get().imageTexts!!.request;assertTrue(c.inFlight.value)
            c.cancelExtraction(session,first);assertFalse(c.inFlight.value);assertFalse(state.get().imageTexts!!.busy)
            c.extract(session);val second=state.get().imageTexts!!.request;assertNotEquals(first,second)
            c.cancelExtraction(session,first);assertTrue(c.inFlight.value);assertEquals(second,state.get().imageTexts!!.request)
            queue.poll()!!(h.owner);assertEquals(0,calls.get());assertTrue(c.inFlight.value)
            queue.poll()!!(h.owner);assertEquals(1,calls.get());assertFalse(c.inFlight.value);assertNotNull(state.get().imageTexts!!.snapshot);assertEquals(1,h.writes.size)
        }finally{c.dispose()}
    }}
    private class Harness(available:Boolean=true,initialPolicy:ProcessingPolicy=ProcessingPolicy.LOCAL_ONLY):AutoCloseable {
        val f=FullFixture();val intent=runBlocking{f.image(f.entry())}.also{if(initialPolicy!=ProcessingPolicy.LOCAL_ONLY)f.db.write("UPDATE entries SET privacyPolicy=? WHERE id=?",initialPolicy.name,it.entryId)};val repo=SqlImageTextRepository(f.gateway,f.key,f.media,f.capture.mutationGate)
        val engineCalls=AtomicInteger();val writes=java.util.Collections.synchronizedList(mutableListOf<ImageTextWrite>())
        @Volatile var loseAck=false;@Volatile var block=false;@Volatile var lastBytes:ByteArray?=null;@Volatile var cancelled:(()->Boolean)?=null
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        @Volatile var blockedKeyword:String?=null;@Volatile var blockRead=false
        val searchEntered=CompletableDeferred<Unit>();val searchRelease=CompletableDeferred<Unit>();val readEntered=CompletableDeferred<Unit>();val readRelease=CompletableDeferred<Unit>();val stalePublications=AtomicInteger()
        private val engine=if(!available)UnavailableImageTextRecognizer else object:ImageTextRecognizer {
            override val available=true
            override suspend fun recognize(source:ImageTextSource,encoded:ByteArray,cancelled:()->Boolean):ImageTextExtraction? {
                engineCalls.incrementAndGet();lastBytes=encoded;this@Harness.cancelled=cancelled
                if(block){entered.complete(Unit);release.await()}
                return ImageTextExtraction(ImageTextEngine("synthetic-test-port","1",true),"合成测试提取文字")
            }
        }
        private val port=object:ImageTextRepository by repo {
            override suspend fun write(request:ImageTextWrite)=repo.write(request).also{writes+=request;if(loseAck){loseAck=false;throw ImageTextUnavailable()}}
            override suspend fun search(keyword:String,cursor:PreferenceCursor?):ImageTextPage {val page=repo.search(keyword,cursor);if(keyword==blockedKeyword){searchEntered.complete(Unit);searchRelease.await()};return page}
            override suspend fun read(anchor:ImageTextSource):ImageTextSnapshot? {val found=repo.read(anchor);if(blockRead){readEntered.complete(Unit);readRelease.await()};return found}
            override suspend fun resolve(page:ImageTextPage,item:ImageTextSnapshot?,publish:()->Unit)=repo.resolve(page,item){publish();if(blockedKeyword!=null&&vm.state.value.imageTexts?.page?.keyword==blockedKeyword)stalePublications.incrementAndGet()}
        }
        private val base=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)){f.media.close();f.media.awaitIdle();f.gateway.stopAndAwaitIdle();repo.wipeKey()}
        val closed get()=base.closed
        val owner:EntryRepository=object:EntryRepository by base,AutoCloseable {
            override val imageTexts=port;override val attachments=f.media;override val captureRecords=f.capture;override val localProcessing=SqlLocalProcessingRepository(f.gateway)
            override suspend fun prepare(){check(f.db.read("PRAGMA user_version").single().single()==12L)}
            override fun close()=base.close()
        }
        val vm=LocalRecordsViewModel(Application(),{owner},Clock.fixed(Instant.ofEpochMilli(50),ZoneOffset.UTC),decodePng={object:AttachmentPicture{override val width=1;override val height=1}},imageTextRecognizer=engine)
        suspend fun ready()=withTimeout(4_000){vm.state.first{it.ready&&!it.opening&&it.supportsImageTexts}}
        suspend fun openViewed(){
            vm.attachments.open(intent.entryId,intent.revision)
            val panel=withTimeout(4_000){vm.attachments.state.first{it!=null&&!it.loading}}!!
            vm.attachments.view(panel.sessionId,intent.id)
            withTimeout(4_000){vm.attachments.state.first{it?.pictureId==intent.id&&it.picture!=null}}
            vm.imageTexts.open(intent)
        }
        fun value()=vm.state.value.imageTexts!!
        suspend fun ui(predicate:(ImageTextUi)->Boolean)=withTimeout(4_000){vm.state.first{it.imageTexts?.let(predicate)==true}.imageTexts!!}
        override fun close(){release.complete(Unit);searchRelease.complete(Unit);readRelease.complete(Unit);vm.clearForHostTest();assertTrue(base.closed.await(4,TimeUnit.SECONDS));base.closeFailure.get()?.let{throw it};f.close()}
    }
}
