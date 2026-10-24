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
    @Test(timeout=30000) fun admittedWriteSurvivesProductionRouteReconciliationAfterFocusedPanelRetirement():Unit=runBlocking {
        for(withdraw in listOf(false,true))Harness(100).use {h->
            h.ready();val a=h.repo.preview(h.intent);h.repo.write(AudioTranscriptWrite(FullBackupTest.uuid(),a,null,"合成路由纠正",false,FullBackupTest.uuid(),10,AudioSampleRange(1,100)))
            val c=h.vm.audioTranscripts;c.open();val found=h.ui{it.page!=null&&!it.busy}
            c.locateReference(found.session,found.items.single()){ref,confirm->assertTrue(confirm{h.vm.attachments.openTranscript(ref)})}
            withTimeout(4000){h.vm.attachments.state.first{it?.transcriptSource==true&&!it.busy}}
            c.open(h.intent);val editor=h.ui{it.anchor!=null&&!it.busy};c.rangeMode(editor.session,AudioTranscriptRangeMode.CUSTOM);c.rangeFrom(editor.session,"0.001")
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();assertTrue(h.vm.enqueueTaskOperation{entered.complete(Unit);release.await()});entered.await()
            try {
                if(withdraw){c.withdraw(editor.session);c.withdraw(editor.session,h.value().withdrawal!!)}else c.save(editor.session)
                assertNull(h.vm.attachments.state.value)
                val local=h.vm.state.value;val active=local.audioTranscripts!!
                // Exactly the production selected-reader predicate, then the two automatic disposal callbacks.
                if(!local.canShowTranscriptEditor(active,h.vm.attachments.state.value,h.intent.entryId,false,a)){c.close(active.session);c.releaseUi(active.session)}
            }finally{release.complete(Unit)}
            val drained=CompletableDeferred<Unit>();assertTrue(h.vm.enqueueTaskOperation{drained.complete(Unit)});drained.await()
            assertEquals(1,h.writes.size);assertEquals(withdraw,h.repo.read(a)!!.withdrawn)
            assertEquals(editor.session,h.value().session);assertNull(h.value().pending)
        }
    }
    @Test(timeout=20000) fun admittedEditorStillHonorsExplicitCloseAndOwnerReplacementWithoutRevivingOldWrite():Unit=runBlocking {Harness().use {h->
        h.ready()
        for(replaceOwner in listOf(false,true)) {
            val state=AtomicReference(h.vm.state.value);val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
            val c=AudioTranscriptController({state.get()},{change->state.updateAndGet(change)},{queue.add(it);true},Clock.systemUTC())
            try {
                c.open(h.intent);queue.poll()!!(h.owner);val old=state.get().audioTranscripts!!;c.edit(old.session,"合成待处理但可退出");c.save(old.session)
                assertNotNull(state.get().audioTranscripts!!.pending)
                if(replaceOwner) {
                    val next=object:RecordAuthorizationRepository {
                        override fun readAuthorization(source:SourceVersion):RecordAuthorizationSnapshot=error("No authority IO")
                        override fun previewAuthorization(expected:RecordAuthorizationSource,purpose:TextPurpose,clock:()->java.time.Instant):RecordAuthorizationPreview=error("No authority IO")
                        override fun changeAuthorization(request:RecordAuthorizationChange,clock:()->java.time.Instant):RecordAuthorizationReceipt=error("No authority IO")
                    }
                    state.set(state.get().copy(recordAuthorizationOwner=next))
                    assertFalse(state.get().canShowTranscriptEditor(state.get().audioTranscripts!!,null,h.intent.entryId,false,null))
                    c.releaseUi(old.session) // Actual dialog-disposal contract on owner loss.
                }else c.close(old.session) // Explicit user close remains effective, even after admission.
                c.open(h.intent);val fresh=state.get().audioTranscripts!!.session;assertNotEquals(old.session,fresh)
                c.releaseUi(old.session);assertEquals(fresh,state.get().audioTranscripts!!.session)
                while(true)(queue.poll()?:break)(h.owner)
                assertEquals(0,h.writes.size);assertEquals(fresh,state.get().audioTranscripts!!.session);assertEquals("",state.get().audioTranscripts!!.text)
            }finally{c.dispose()}
        }
    }}
    @Test(timeout=20000) fun unknownRefreshCannotLoseOriginalIntentEvenWhenReadsFail():Unit=runBlocking {Harness(100).use {h->
        h.ready();val c=h.vm.audioTranscripts;c.open(h.intent);val u=h.ui{it.anchor!=null&&!it.busy}
        c.edit(u.session,"合成未知保存");c.rangeMode(u.session,AudioTranscriptRangeMode.CUSTOM);c.rangeFrom(u.session,"0.001")
        h.loseAck=true;c.save(u.session);val pending=h.ui{it.pending!=null&&!it.busy}.pending!!
        assertTrue(h.vm.state.value.canShowTranscriptEditor(h.value(),null,h.intent.entryId,false,null))
        h.failRead=true;val reads=h.reads.get();c.refresh(u.session)
        val drained=CompletableDeferred<Unit>();assertTrue(h.vm.enqueueTaskOperation{drained.complete(Unit)});withTimeout(4000){drained.await()}
        assertSame(pending,h.value().pending);assertEquals(reads,h.reads.get())
        c.edit(u.session,"不得替换未知文字");c.rangeMode(u.session,AudioTranscriptRangeMode.WHOLE);c.rangeFrom(u.session,"0");c.save(u.session)
        assertSame(pending,h.value().pending);assertEquals("合成未知保存",h.value().text);assertEquals("0.001",h.value().rangeFrom)
        c.retry(u.session);h.ui{!it.busy};assertSame(pending,h.value().pending)
        h.failRead=false;c.retry(u.session);val done=h.ui{it.pending==null&&it.notice!=null&&!it.busy}
        assertEquals(pending.range,done.snapshot!!.current.range);assertTrue(h.writes.all{it===pending});assertEquals(1L,done.snapshot!!.version)
    }}
    @Test(timeout=30000) fun admittedRangeCorrectionAndWithdrawalRetireFocusedProofAndBothUiNullOffersBeforeActorRuns():Unit=runBlocking {
        for(kind in listOf("focused","standalone","unified"))for(withdraw in listOf(false,true))Harness(100,unified=true).use {h->
            h.ready();val anchor=h.repo.preview(h.intent)
            h.repo.write(AudioTranscriptWrite(FullBackupTest.uuid(),anchor,null,"合成退休范围",false,FullBackupTest.uuid(),10,AudioSampleRange(1,100)))
            val c=h.vm.audioTranscripts;var offer:UnifiedKeywordHandoff?=null
            if(kind=="unified") {
                val q=h.vm.unifiedKeywords;q.open();val session=h.vm.state.value.unifiedKeyword!!.session;q.keyword(session,"退休范围");q.search(session)
                val found=withTimeout(4000){h.vm.state.first{it.unifiedKeyword?.snapshot!=null&&it.unifiedKeyword?.busy==false}.unifiedKeyword!!}
                q.locate(session,found.visibleGroups.single().items.single()){_,confirm->offer=confirm}
                withTimeout(4000){while(offer==null)delay(5)}
            }else {
                c.open();val found=h.ui{it.page!=null&&!it.busy}
                c.locateReference(found.session,found.items.single()){ref,confirm->if(kind=="focused")assertTrue(confirm{h.vm.attachments.openTranscript(ref)})else offer=confirm}
                if(kind=="focused")withTimeout(4000){h.vm.attachments.state.first{it?.transcriptSource==true&&!it.busy}}
                else withTimeout(4000){while(offer==null)delay(5)}
            }
            c.open(h.intent);val editor=h.ui{it.anchor!=null&&!it.busy};val panel=h.vm.attachments.state.value
            c.rangeMode(editor.session,AudioTranscriptRangeMode.CUSTOM);c.rangeFrom(editor.session,"");c.save(editor.session)
            assertEquals(0,h.writes.size);assertSame(panel,h.vm.attachments.state.value) // Rejected input cannot retire a reader.
            c.rangeFrom(editor.session,"0.001")
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            assertTrue(h.vm.enqueueTaskOperation{entered.complete(Unit);release.await()});withTimeout(4000){entered.await()}
            try {
                if(withdraw){c.withdraw(editor.session);assertSame(panel,h.vm.attachments.state.value);c.withdraw(editor.session,h.value().withdrawal!!)}else c.save(editor.session)
                assertNotNull(h.value().pending);assertEquals(0,h.writes.size)
                if(kind=="focused")assertNull(h.vm.attachments.state.value)
                else {var navigations=0;assertFalse(offer!!{navigations++});assertEquals(0,navigations)}
            }finally{release.complete(Unit)}
            h.ui{!it.busy&&it.pending==null};assertEquals(1,h.writes.size)
            c.close(editor.session);c.open();val fresh=h.ui{it.page!=null&&!it.busy}
            offer?.let{assertFalse(it{error("Old offer must not navigate into the new owner session")});assertEquals(fresh.session,h.value().session)}
        }
    }
    @Test(timeout=20000) fun mutationRetirementRunsOutsideCaptureMonitorBeforeQueueAndRejectedInputDoesNotRetire():Unit=runBlocking {Harness().use {h->
        h.ready();val state=AtomicReference(h.vm.state.value);val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
        val gate=h.f.capture.mutationGate;var retired=0;var reject=false
        val c=AudioTranscriptController({state.get()},{change->state.updateAndGet(change)},
            {action->if(state.get().audioTranscripts?.pending!=null){assertEquals(1,retired);assertFalse(Thread.holdsLock(gate))};if(reject)false else{queue.add(action);true}},Clock.systemUTC(),
            sourceUi={gate.sourceUi(it)},beforeWrite={assertFalse(Thread.holdsLock(gate));assertSame(it,state.get().audioTranscripts!!.pending);retired++})
        try {
            c.open(h.intent);queue.poll()!!(h.owner);val session=state.get().audioTranscripts!!.session
            c.save(session);assertEquals(0,retired);assertTrue(queue.isEmpty())
            c.edit(session,"合成有效保存");reject=true;c.save(session)
            val pending=state.get().audioTranscripts!!.pending;assertNotNull(pending);assertEquals(1,retired);assertFalse(state.get().audioTranscripts!!.busy)
            c.refresh(session);c.save(session);assertEquals(1,retired);assertSame(pending,state.get().audioTranscripts!!.pending)
        }finally{c.dispose()}
    }}
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
        val writes=mutableListOf<AudioTranscriptWrite>();@Volatile var loseAck=false;@Volatile var failRead=false;val reads=java.util.concurrent.atomic.AtomicInteger()
        private val port=object:AudioTranscriptRepository by repo{override suspend fun read(anchor:AudioTranscriptSource):AudioTranscriptSnapshot?{reads.incrementAndGet();if(failRead)throw AudioTranscriptUnavailable();return repo.read(anchor)};override suspend fun write(request:AudioTranscriptWrite)=repo.write(request).also{writes+=request;if(loseAck){loseAck=false;throw AudioTranscriptUnavailable()}}}
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
