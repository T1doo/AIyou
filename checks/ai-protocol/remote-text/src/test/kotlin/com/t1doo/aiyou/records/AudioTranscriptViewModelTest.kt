package com.t1doo.aiyou.records
import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.*
import org.junit.Test
class AudioTranscriptViewModelTest {
    @Test(timeout=20_000) fun actualActorLostAckRetriesOneIntentAndWithdrawalCancellationDoesNotWrite():Unit=runBlocking{Harness().use{h->
        h.ready();h.vm.audioTranscripts.open(h.intent);var u=h.ui{it.anchor!=null&&!it.busy};val gate=h.f.capture.mutationGate;val pin=checkNotNull(gate.preparePlayback());gate.activatePlayback(pin);try{h.vm.audioTranscripts.edit(u.session,"禁止播放中污染输入");assertEquals("",h.value().text)}finally{gate.finishPlayback(pin)};h.vm.audioTranscripts.edit(u.session,"合成人工草稿🙂");h.loseAck=true;h.vm.audioTranscripts.save(u.session)
        u=h.ui{it.error!=null&&!it.busy};assertNotNull(u.pending);assertNull(u.notice);assertEquals(1L,h.repo.read(u.anchor!!)!!.version)
        h.vm.audioTranscripts.retry(u.session);u=h.ui{it.notice!=null&&!it.busy};assertSame(h.writes[0],h.writes[1]);assertEquals(1L,u.snapshot!!.version)
        h.vm.audioTranscripts.withdraw(u.session);val cancelled=h.value().withdrawal!!;h.vm.audioTranscripts.cancelWithdraw(u.session,cancelled);h.vm.audioTranscripts.withdraw(u.session,cancelled);assertNull(h.value().withdrawal);assertEquals(2,h.writes.size)
        h.vm.audioTranscripts.edit(u.session,"合成人工纠正");h.vm.audioTranscripts.save(u.session);u=h.ui{it.snapshot?.history?.size==2&&!it.busy}
        h.vm.audioTranscripts.withdraw(u.session);h.vm.audioTranscripts.withdraw(u.session,h.value().withdrawal);u=h.ui{it.snapshot?.withdrawn==true&&!it.busy};assertEquals("",u.text)
        assertTrue(h.repo.search("").items.isEmpty());val calls=h.writes.size;h.vm.audioTranscripts.close(u.session);h.vm.audioTranscripts.save(u.session);assertEquals(calls,h.writes.size)
    }}
    @Test(timeout=20_000) fun realVmObservedRevisionOrTrashRetiresUnknownSavedTextAndOldCallbacks():Unit=runBlocking{
        for(trash in listOf(false,true))Harness().use{h->h.ready();h.vm.audioTranscripts.open(h.intent);val u=h.ui{it.anchor!=null&&!it.busy};h.vm.audioTranscripts.edit(u.session,"合成迟到文字");h.loseAck=true;h.vm.audioTranscripts.save(u.session);h.ui{it.pending!=null&&it.error!=null&&!it.busy}
            if(trash)h.f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",h.intent.entryId)else h.f.revise(h.intent.entryId,"合成新版本")
            h.vm.refreshLocalSearch();withTimeout(4_000){h.vm.state.first{it.entries.none{e->e.id==h.intent.entryId&&e.currentRevision==1}}}
            assertNull(h.vm.state.value.audioTranscripts);h.vm.audioTranscripts.retry(u.session);assertEquals(1,h.writes.size)
        }
    }
    @Test(timeout=20_000) fun actualControllerDelayedQueryCannotPublishAfterInputChangeOrRouteRetirement():Unit=runBlocking{Harness().use{h->
        h.ready();val a=h.repo.preview(h.intent);h.repo.write(AudioTranscriptSqlTest.write(a,text="合成淡茶"))
        for(close in listOf(false,true)){
            val state=AtomicReference(h.vm.state.value);val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var stalePublications=0
            val port=object:AudioTranscriptRepository by h.repo{override suspend fun search(keyword:String,cursor:PreferenceCursor?):AudioTranscriptPage{val p=h.repo.search(keyword,cursor);if(keyword=="淡茶"){entered.complete(Unit);release.await()};return p}}
            val owner=object:EntryRepository by h.owner{override val audioTranscripts=port}
            val c=AudioTranscriptController({state.get()},{transform->val n=state.updateAndGet(transform);if(n.audioTranscripts?.page?.keyword=="淡茶")stalePublications++},{queue.add(it);true},Clock.systemUTC())
            try{c.open();queue.poll()!!(owner);val session=state.get().audioTranscripts!!.session;c.keyword(session,"淡茶");c.search(session);val action=queue.poll()!!;val work=async(Dispatchers.IO){action(owner)};withTimeout(3_000){entered.await()}
                if(close){c.close(session);c.open()}else{c.keyword(session,"清水");c.search(session)}
                release.complete(Unit);withTimeout(3_000){work.await()};assertEquals(0,stalePublications);queue.poll()!!(owner)
                assertEquals(if(close)""else"清水",state.get().audioTranscripts!!.page!!.keyword)
            }finally{release.complete(Unit);c.dispose()}
        }
    }}
    @Test(timeout=15_000) fun realActorKeywordLocationAuthenticatesExactOriginalAudioBeforeRetiringPanel():Unit=runBlocking{Harness().use{h->
        h.ready();val anchor=h.repo.preview(h.intent);h.repo.write(AudioTranscriptSqlTest.write(anchor,text="合成可定位原音"))
        h.vm.audioTranscripts.open();val initial=h.ui{it.page!=null&&!it.busy};h.vm.audioTranscripts.keyword(initial.session,"可定位");h.vm.audioTranscripts.search(initial.session)
        val found=h.ui{it.page?.keyword=="可定位"&&!it.busy};val location=CompletableDeferred<AudioTranscriptSource>()
        h.vm.audioTranscripts.locate(initial.session,found.items.single()){location.complete(it)}
        assertEquals(anchor,withTimeout(4_000){location.await()});assertNull(h.vm.state.value.audioTranscripts)
    }}
    private class Harness:AutoCloseable{
        val f=FullFixture();val intent=runBlocking{AudioTranscriptSqlTest.audio(f)};val repo=SqlAudioTranscriptRepository(f.gateway,f.key,f.media,f.capture.mutationGate)
        val writes=mutableListOf<AudioTranscriptWrite>();@Volatile var loseAck=false
        private val port=object:AudioTranscriptRepository by repo{override suspend fun write(request:AudioTranscriptWrite)=repo.write(request).also{writes+=request;if(loseAck){loseAck=false;throw AudioTranscriptUnavailable()}}}
        private val base=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)){f.media.close();f.media.awaitIdle();f.gateway.stopAndAwaitIdle();repo.wipeKey()}
        val owner:EntryRepository=object:EntryRepository by base,AutoCloseable{
            override val audioTranscripts=port;override val attachments=f.media;override val captureRecords=f.capture;override val localProcessing=SqlLocalProcessingRepository(f.gateway)
            override suspend fun prepare(){check(f.db.read("PRAGMA user_version").single().single()==12L)};override fun close()=base.close()
        }
        val vm=LocalRecordsViewModel(Application(),{owner},Clock.fixed(Instant.ofEpochMilli(50),ZoneOffset.UTC))
        suspend fun ready()=withTimeout(4_000){vm.state.first{it.ready&&!it.opening&&it.supportsAudioTranscripts}}
        fun value()=vm.state.value.audioTranscripts!!
        suspend fun ui(predicate:(AudioTranscriptUi)->Boolean)=withTimeout(4_000){vm.state.first{it.audioTranscripts?.let(predicate)==true}.audioTranscripts!!}
        override fun close(){vm.clearForHostTest();assertTrue(base.closed.await(4,TimeUnit.SECONDS));base.closeFailure.get()?.let{throw it};f.close()}
    }
}
