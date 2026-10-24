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
    @Test(timeout=20000) fun actualEditorBothQueriesFocusedPanelAndExplicitPlayProduceOnlyAuthenticatedSamples():Unit=runBlocking {
        class Sink:OriginalAudioOutput {
            val samples=java.util.Collections.synchronizedList(mutableListOf<Short>())
            val played=java.util.concurrent.atomic.AtomicLong();var first=true
            override fun focus()=true
            override fun open(){}
            override fun play(){}
            override fun write(values:ShortArray,count:Int):Int {if(first){first=false;return 0};val n=minOf(3,count);repeat(n){samples+=values[it]};return n}
            override fun playedFrames()=played.get()
            override fun close(){}
        }
        val outputs=java.util.Collections.synchronizedList(mutableListOf<Sink>())
        Harness(100,output={Sink().also{outputs+=it}},unified=true).use {h->
            h.ready();h.vm.audioForeground(true);val original=h.f.media.read(h.intent.id)
            val c=h.vm.audioTranscripts;c.open(h.intent);var u=h.ui{it.anchor!=null&&!it.busy}
            c.edit(u.session,"合成音片关键词");c.rangeMode(u.session,AudioTranscriptRangeMode.CUSTOM);c.rangeFrom(u.session,"0.001");c.rangeToEnd(u.session,false);c.rangeThrough(u.session,"0.002");c.save(u.session)
            u=h.ui{it.snapshot!=null&&!it.busy};assertEquals(AudioSampleRange(45,89),u.snapshot!!.current.range);c.close(u.session)
            for(unified in listOf(false,true)) {
                if(!unified) {
                    c.open();u=h.ui{it.page!=null&&!it.busy};c.keyword(u.session,"音片关键词");c.search(u.session);u=h.ui{it.page?.keyword=="音片关键词"&&!it.busy}
                    c.locateReference(u.session,u.items.single()){ref,confirm->assertTrue(confirm{h.vm.attachments.openTranscript(ref)})}
                }else {
                    val queries=h.vm.unifiedKeywords;queries.open();val session=h.vm.state.value.unifiedKeyword!!.session
                    queries.keyword(session,"音片关键词");queries.search(session)
                    val found=withTimeout(4000){h.vm.state.first{it.unifiedKeyword?.snapshot!=null&&it.unifiedKeyword?.busy==false}.unifiedKeyword!!}
                    val item=found.visibleGroups.single().items.filterIsInstance<UnifiedKeywordEvidence.Audio>().single()
                    queries.locate(session,item){same,confirm->assertSame(item,same);assertTrue(confirm{h.vm.attachments.openTranscript(confirm.audioPlayback!!)})}
                }
                val panel=withTimeout(4000){h.vm.attachments.state.first{it?.transcriptSource==true&&!it.busy}!!}
                assertEquals(if(unified)1 else 0,outputs.size) // Opening an authenticated source never starts playback.
                assertEquals(h.intent,panel.focusAudio);assertTrue(h.repo.ownsPlaybackReference(panel.transcriptReference!!))
                assertTrue(h.vm.audioPlayback.playAttachment(panel.sessionId,h.intent.id)) // Same production action as the explicit play button.
                withTimeout(4000){while(outputs.size<(if(unified)2 else 1)||outputs.last().samples.size<44)delay(5)}
                val sink=outputs.last();assertEquals((45 until 89).map{it.toShort()},sink.samples.toList())
                assertEquals(OriginalAudioPhase.PLAYING,h.vm.audioPlayback.state.value.phase)
                sink.played.set(44);withTimeout(4000){h.vm.audioPlayback.state.first{it.phase==OriginalAudioPhase.IDLE}}
                assertFalse(h.f.capture.mutationGate.frozen);h.vm.attachments.close(panel.sessionId)
            }
            assertArrayEquals(original,h.f.media.read(h.intent.id));assertEquals(1,h.writes.size)
        }
    }
    @Test(timeout=20000) fun realVmSourceChangeRetiresAnOfferedPrivateReferenceEvenAfterQueryUiBecameNull():Unit=runBlocking {Harness().use {h->
        h.ready();val a=h.repo.preview(h.intent);h.repo.write(AudioTranscriptWrite(FullBackupTest.uuid(),a,null,"合成待跳转片段",false,FullBackupTest.uuid(),10,AudioSampleRange(1,4)))
        val c=h.vm.audioTranscripts;c.open();val u=h.ui{it.page!=null&&!it.busy}
        val offered=CompletableDeferred<UnifiedKeywordHandoff>()
        c.locateReference(u.session,u.items.single()){_,confirm->offered.complete(confirm)}
        val confirm=withTimeout(4000){offered.await()};assertNull(h.vm.state.value.audioTranscripts)
        h.f.revise(h.intent.entryId,"合成新来源版本");h.vm.refreshLocalSearch()
        withTimeout(4000){h.vm.state.first{it.entries.any{e->e.id==h.intent.entryId&&e.currentRevision==2}}}
        var nav=0;assertFalse(confirm{nav++});assertEquals(0,nav)
        c.open();h.ui{it.page!=null&&!it.busy};assertFalse(confirm{nav++})
    }}
    @Test(timeout=20000) fun realEditorExplicitRangeWholeAndUnknownRetryNeverSilentlyInheritOrReplaceIntent():Unit=runBlocking {Harness(100).use {h->
        h.ready();h.vm.audioTranscripts.open(h.intent);var u=h.ui{it.anchor!=null&&!it.busy};val c=h.vm.audioTranscripts
        c.edit(u.session,"合成人工范围");c.rangeMode(u.session,AudioTranscriptRangeMode.CUSTOM);c.rangeFrom(u.session,"0.001");c.rangeToEnd(u.session,false);c.rangeThrough(u.session,"0.002")
        h.loseAck=true;c.save(u.session);u=h.ui{it.pending!=null&&!it.busy&&it.error!=null}
        val pending=u.pending!!;assertEquals(AudioSampleRange(45,89),pending.range)
        c.rangeFrom(u.session,"0");c.rangeMode(u.session,AudioTranscriptRangeMode.WHOLE)
        assertSame(pending,h.value().pending);assertEquals("0.001",h.value().rangeFrom)
        c.retry(u.session);u=h.ui{it.notice!=null&&!it.busy};assertSame(h.writes[0],h.writes[1]);assertEquals(AudioSampleRange(45,89),u.snapshot!!.current.range)
        c.rangeFrom(u.session,"0");c.rangeToEnd(u.session,true);c.save(u.session);u=h.ui{it.snapshot?.history?.size==2&&!it.busy}
        assertEquals(AudioSampleRange(0,100),u.snapshot!!.current.range)
        c.rangeMode(u.session,AudioTranscriptRangeMode.WHOLE);c.save(u.session);u=h.ui{it.snapshot?.history?.size==3&&!it.busy}
        assertNull(u.snapshot!!.current.range);assertEquals(AudioSampleRange(45,89),u.snapshot!!.history.first().range)
        c.close(u.session);c.open(h.intent);u=h.ui{it.anchor!=null&&!it.busy};assertEquals(AudioTranscriptRangeMode.WHOLE,u.rangeMode)
    }}
    @Test(timeout=20000) fun rejectedEndpointAndEmptyCustomSurviveTextEditsRefreshAndRepeatedModeUntilExplicitCorrection():Unit=runBlocking {
        for(endpoint in listOf("from","through","empty"))Harness(100).use {h->
            h.ready();val a=h.repo.preview(h.intent)
            h.repo.write(AudioTranscriptWrite(FullBackupTest.uuid(),a,null,"合成已有范围",false,FullBackupTest.uuid(),10,AudioSampleRange(45,89)))
            val c=h.vm.audioTranscripts;c.open(h.intent);var u=h.ui{it.anchor!=null&&!it.busy};assertEquals(AudioTranscriptRangeMode.SAVED,u.rangeMode)
            c.rangeMode(u.session,AudioTranscriptRangeMode.CUSTOM);c.rangeFrom(u.session,"0.001");c.rangeToEnd(u.session,false);c.rangeThrough(u.session,"0.002")
            when(endpoint){"from"->c.rangeFrom(u.session,"9999999");"through"->c.rangeThrough(u.session,"9999999");else->c.rangeFrom(u.session,"")}
            c.save(u.session);assertEquals(0,h.writes.size)
            c.edit(u.session,"仅改字不能吞范围错误");c.rangeMode(u.session,AudioTranscriptRangeMode.WHOLE);c.rangeMode(u.session,AudioTranscriptRangeMode.CUSTOM);c.refresh(u.session);u=h.ui{!it.busy}
            assertNotNull(if(endpoint=="through")u.throughError else u.fromError)
            c.save(u.session);assertEquals(0,h.writes.size);assertEquals(AudioSampleRange(45,89),h.repo.read(a)!!.current.range)
            c.rangeFrom(u.session,"0");if(endpoint=="through")c.rangeThrough(u.session,"0.001")
            c.save(u.session);u=h.ui{it.snapshot?.history?.size==2&&!it.busy}
            assertEquals(AudioSampleRange(0,if(endpoint=="through")45 else 89),u.snapshot!!.current.range)
        }
    }
    @Test(timeout=20000) fun privateStandaloneHandoffRetiresWhileUiNullAndFailedResolveCannotPublishResults():Unit=runBlocking {Harness().use {h->
        h.ready();val a=h.repo.preview(h.intent);h.repo.write(AudioTranscriptWrite(FullBackupTest.uuid(),a,null,"合成引用",false,FullBackupTest.uuid(),10,AudioSampleRange(1,4)))
        val state=AtomicReference(h.vm.state.value);val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>();var failResolve=false;var publications=0
        val repo=object:AudioTranscriptRepository by h.repo {override suspend fun resolve(page:AudioTranscriptPage,item:AudioTranscriptSnapshot?,publish:()->Unit):Boolean {
            val actual=h.repo.resolve(page,item,publish);if(failResolve)error("Synthetic lost resolve return");return actual
        }}
        val store=object:EntryRepository by h.owner{override val audioTranscripts=repo}
        val c=AudioTranscriptController({state.get()},{change->val n=state.updateAndGet(change);if(n.audioTranscripts?.page!=null)publications++},{queue.add(it);true},Clock.systemUTC())
        try {
            failResolve=true;c.open();queue.poll()!!(store);assertEquals(0,publications);assertNotNull(state.get().audioTranscripts!!.error)
            failResolve=false;c.refresh(state.get().audioTranscripts!!.session);queue.poll()!!(store)
            val u=state.get().audioTranscripts!!;var offer:UnifiedKeywordHandoff?=null
            c.locateReference(u.session,u.items.single()){ref,confirm->assertTrue(h.repo.ownsPlaybackReference(ref));offer=confirm};queue.poll()!!(store)
            assertNull(state.get().audioTranscripts);c.retire();var nav=0;assertFalse(offer!!{nav++});assertEquals(0,nav)
            c.open();queue.poll()!!(store);val fresh=state.get().audioTranscripts!!
            c.locateReference(fresh.session,fresh.items.single()){ref,confirm->assertEquals(AudioSampleRange(1,4),ref.range);c.releaseUi(fresh.session);assertTrue(confirm{nav++});assertFalse(confirm{nav++})};queue.poll()!!(store)
            assertEquals(1,nav);assertFalse(offer!!{nav++})
        }finally{c.dispose()}
    }}
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
    private class Harness(samples:Int=4,output:((()->Unit)->OriginalAudioOutput)?=null,unified:Boolean=false):AutoCloseable{
        val f=FullFixture();val intent=runBlocking{
            if(samples==4)AudioTranscriptSqlTest.audio(f)else {
                val bytes=LocalPcmAudio.encode(ShortArray(samples){it.toShort()});val id=f.entry()
                AttachmentIntent(FullBackupTest.uuid(),id,1,"audio/wav",FullBackupTest.hash(bytes),bytes.size.toLong()).also{i->f.media.save(i){bytes.inputStream()}}
            }
        };val repo=SqlAudioTranscriptRepository(f.gateway,f.key,f.media,f.capture.mutationGate)
        val writes=mutableListOf<AudioTranscriptWrite>();@Volatile var loseAck=false
        private val port=object:AudioTranscriptRepository by repo{override suspend fun write(request:AudioTranscriptWrite)=repo.write(request).also{writes+=request;if(loseAck){loseAck=false;throw AudioTranscriptUnavailable()}}}
        private val images=if(unified)SqlImageTextRepository(f.gateway,f.key,f.media)else null
        private val preferences=if(unified)SqlSourcePreferenceRepository(f.gateway,f.key)else null
        private val memories=if(unified)SqlSourceMemoryRepository(f.gateway,f.key)else null
        private val people=if(unified)SqlManualPeopleRepository(f.gateway,f.key)else null
        private val combined=if(unified)SqlUnifiedKeywordRepository(f.gateway,SqlLocalSearchRepository(f.gateway,f.tags),repo,images!!,preferences!!,memories!!,f.tags,people!!,f.media)else null
        private val base=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)){f.media.close();f.media.awaitIdle();f.gateway.stopAndAwaitIdle();repo.wipeKey();images?.wipeKey();preferences?.wipeKey();memories?.wipeKey();people?.wipeKey()}
        val owner:EntryRepository=object:EntryRepository by base,AutoCloseable{
            override val unifiedKeywords=combined
            override val audioTranscripts=port;override val attachments=f.media;override val captureRecords=f.capture;override val localProcessing=SqlLocalProcessingRepository(f.gateway)
            override suspend fun prepare(){check(f.db.read("PRAGMA user_version").single().single()==12L)};override fun close()=base.close()
        }
        val vm=LocalRecordsViewModel(Application(),{owner},Clock.fixed(Instant.ofEpochMilli(50),ZoneOffset.UTC),audioOutput=output)
        suspend fun ready()=withTimeout(4_000){vm.state.first{it.ready&&!it.opening&&it.supportsAudioTranscripts}}
        fun value()=vm.state.value.audioTranscripts!!
        suspend fun ui(predicate:(AudioTranscriptUi)->Boolean)=withTimeout(4_000){vm.state.first{it.audioTranscripts?.let(predicate)==true}.audioTranscripts!!}
        override fun close(){vm.clearForHostTest();assertTrue(base.closed.await(4,TimeUnit.SECONDS));base.closeFailure.get()?.let{throw it};f.close()}
    }
}
