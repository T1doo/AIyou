package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Synthetic WAV/ports, actual SQL/JCA capture and source authentication. No audio device. */
class OriginalAudioControllerTest {
    @Test fun pendingEditPrefixDrainsBeforeFullDraftBindingAndPinRejectsRealStorageWrites():Unit=runBlocking {
        Harness().use { h ->
            h.draft();val old=h.f.capture.peek()!!
            val permit=h.g.admit()!!
            h.local=h.local.copy(mixedCapture=h.local.mixedCapture!!.copy(text="合成排队文字",pendingEdits=1))
            h.queue.add {
                val changed=h.g.context(permit) { h.f.capture.edit(old,"合成排队文字") };permit.close()
                h.local=h.local.copy(mixedCapture=h.local.mixedCapture!!.copy(draft=changed,pendingEdits=0))
            }
            assertTrue(h.player.playDraft())
            var changedUi=false;h.g.uiMutation { changedUi=true };assertFalse(changedUi)
            h.drain();h.await(OriginalAudioPhase.PLAYING)
            assertEquals("合成排队文字",h.f.capture.peek()!!.text)
            assertThrows(CaptureConflict::class.java) { runBlocking { h.f.capture.removeAudio(h.f.capture.peek()!!) } }
            assertTrue(h.g.frozen);assertTrue(h.sink.queued.get()>0)
            h.player.stop();h.await(OriginalAudioPhase.IDLE)
            assertEquals(0,h.sink.queued.get());assertFalse(h.g.frozen)
        }
    }
    @Test fun concurrentWriterAtBarrierRejectsPlayWithoutWaitingForFutureActorAndWithoutPcm():Unit=runBlocking {
        Harness().use { h ->
            h.draft();assertTrue(h.player.playDraft());val lease=h.g.sourceLease()
            try { h.drain();assertEquals(OriginalAudioPhase.IDLE,h.player.state.value.phase);assertEquals(0,h.sink.opened.get()) }
            finally { lease.close() }
            assertFalse(h.g.frozen)
        }
    }
    @Test fun partialAndZeroWritesDoNotSkipSamplesOrCompleteOnEnqueuedFrames():Unit=runBlocking {
        Harness().use { h ->
            h.draft();h.sink.zeroWrites=1;h.sink.limit=1
            assertTrue(h.player.playDraft());h.drain();h.until { h.sink.accepted.size==4 }
            assertEquals(listOf<Short>(0x1234,-2,1000,-1000),h.sink.accepted.toList())
            assertEquals(OriginalAudioPhase.PLAYING,h.player.state.value.phase)
            h.sink.played.set(4);h.await(OriginalAudioPhase.IDLE)
            assertEquals(1,h.sink.closes.get());assertFalse(h.g.frozen)
        }
    }
    @Test fun pauseAndLateFocusGainCannotResumeOrCreateSecondTrack():Unit=runBlocking {
        Harness().use { h ->
            h.draft();assertTrue(h.player.playDraft());h.drain();h.await(OriginalAudioPhase.PLAYING)
            h.player.foreground(false);h.await(OriginalAudioPhase.IDLE)
            assertEquals(0,h.sink.queued.get());h.player.foreground(true);delay(30)
            assertEquals(1,h.sink.opened.get());assertEquals(OriginalAudioPhase.IDLE,h.player.state.value.phase)
        }
    }
    @Test fun closeUnknownKeepsPinUntilExplicitSameHandleRetryAcknowledgesRealRelease():Unit=runBlocking {
        Harness().use { h ->
            h.draft();h.sink.failClose=true
            assertTrue(h.player.playDraft());h.drain();h.await(OriginalAudioPhase.PLAYING)
            h.player.stop();h.await(OriginalAudioPhase.CLOSING_UNKNOWN)
            assertTrue(h.g.frozen);assertFalse(h.player.playDraft());assertTrue(h.sink.queued.get()>0)
            assertThrows(IllegalStateException::class.java) { runBlocking { h.player.closeAndJoin() } }
            h.sink.failClose=false;h.player.stop();h.await(OriginalAudioPhase.IDLE)
            assertEquals(1,h.sink.opened.get());assertEquals(0,h.sink.queued.get());assertFalse(h.g.frozen)
        }
    }
    @Test fun retirementDuringBlockingFocusBuildOrPlayWaitsActualReturnAndNeverAdmitsLaterOperations():Unit=runBlocking {
        for(stage in listOf("focus","build","play")) Harness().use { h ->
            h.draft();val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val block:()->Unit={entered.countDown();check(release.await(4,TimeUnit.SECONDS))}
            when(stage) {
                "focus" -> h.sink.focusAction={block();true}
                "build" -> h.sink.openAction=block
                else -> h.sink.playAction=block
            }
            assertTrue(h.player.playDraft());h.drain()
            try {
                assertTrue(entered.await(4,TimeUnit.SECONDS));h.player.retire()
                assertTrue(h.player.busy);assertTrue(h.g.frozen)
            } finally { release.countDown() }
            h.player.closeAndJoin();assertEquals(if(stage=="focus")0 else 1,h.sink.opened.get())
            assertEquals(if(stage=="play")1 else 0,h.sink.plays.get());assertTrue(h.sink.accepted.isEmpty())
        }
    }
    @Test fun historicalAudioUsesExactOldRevisionButOnlyCurrentLiveHistoryRequestCanKeepPlaying():Unit=runBlocking {
        Harness().use { h ->
            h.saved();val p=h.attachment!!;h.f.revise(p.entryId,"合成新版文字")
            val owner=AttachmentHistoryOwner(7,9,2)
            h.local=h.local.copy(entries=h.store.entries(),localProcessingVersions=checkNotNull(h.store.localProcessing).versions(),
                originalHistory=OriginalHistoryUiState(7,p.entryId,9,loading=false,currentRevision=2,
                    revisions=h.store.revisions(p.entryId),selectedRevision=1))
            h.attachment=p.copy(historyOwner=owner)
            assertTrue(h.player.playAttachment(p.sessionId,p.items.single().intent.id));h.drain();h.await(OriginalAudioPhase.PLAYING)
            h.local=h.local.copy(originalHistory=h.local.originalHistory!!.copy(selectedRevision=null))
            h.await(OriginalAudioPhase.IDLE)
            assertFalse(h.player.playAttachment(p.sessionId,p.items.single().intent.id));assertEquals(1,h.sink.opened.get())
        }
    }
    @Test fun routeOrSourcePanelRetirementStopsAndOldSourceIdCannotStartNewOwner():Unit=runBlocking {
        Harness().use { h ->
            h.saved();val panel=h.attachment!!
            assertTrue(h.player.playAttachment(panel.sessionId,panel.items.single().intent.id));h.drain();h.await(OriginalAudioPhase.PLAYING)
            h.attachment=null;h.await(OriginalAudioPhase.IDLE)
            assertEquals(0,h.sink.queued.get());assertFalse(h.player.playAttachment(panel.sessionId,panel.items.single().intent.id))
            assertEquals(1,h.sink.opened.get())
        }
    }
    @Test fun policyGenerationChangeAndDescriptorSubstitutionBeforeBarrierNeverOutputPcm():Unit=runBlocking {
        for(kind in listOf("generation","descriptor","route")) Harness().use { h ->
            h.saved();val p=h.attachment!!;val id=p.items.single().intent.id
            assertTrue(h.player.playAttachment(p.sessionId,id))
            when(kind) {
                "generation" -> h.f.db.transaction { it.execute("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?",listOf(p.entryId)) }
                "descriptor" -> h.f.db.transaction { it.execute("UPDATE attachments SET state='CORRUPT' WHERE id=?",listOf(id)) }
                else -> h.player.routeChanged()
            }
            h.drain();assertEquals(0,h.sink.opened.get());assertTrue(h.sink.accepted.isEmpty());assertFalse(h.g.frozen)
        }
    }
    @Test fun sourcePreparingPreservesNestedSyncAndCaptureCutoffButActiveDeniesBothWriteKinds():Unit=runBlocking {
        val g=CaptureDraftGate();val old=g.admit()!!;val pin=g.preparePlayback()!!
        g.context(old) { g.mutation { g.syncSourceMutation { g.syncSourceMutation { assertTrue(g.playbackBusy) } } } };old.close()
        g.activatePlayback(pin)
        assertThrows(CaptureConflict::class.java) { g.syncSourceMutation { error("must not enter") } }
        assertNull(g.admit());assertNull(g.freeze(java.util.UUID.randomUUID().toString()))
        g.finishPlayback(pin)
        val accepted=g.admit()!!;val reservation=g.freeze(java.util.UUID.randomUUID().toString())!!
        g.context(accepted) { g.mutation { g.syncSourceMutation { Unit } } };accepted.close()
        g.abandonBeforeReserve(reservation);assertFalse(g.frozen)
    }
    private class Sink:OriginalAudioOutput {
        val opened=AtomicInteger();val plays=AtomicInteger();val closes=AtomicInteger();val queued=AtomicInteger();val played=AtomicLong()
        val accepted=java.util.Collections.synchronizedList(mutableListOf<Short>())
        @Volatile var failClose=false
        @Volatile var limit=Int.MAX_VALUE
        var zeroWrites=0
        var focusAction:()->Boolean={true}
        var openAction:()->Unit={}
        var playAction:()->Unit={}
        override fun focus()=focusAction()
        override fun open() { opened.incrementAndGet();openAction() }
        override fun play() { plays.incrementAndGet();playAction() }
        override fun write(samples:ShortArray,count:Int):Int {
            if(zeroWrites>0) { zeroWrites--;return 0 }
            val n=minOf(count,limit);repeat(n){accepted.add(samples[it])};queued.addAndGet(n);return n
        }
        override fun playedFrames()=played.get()
        override fun close() { closes.incrementAndGet();if(failClose)error("synthetic release ACK unknown");queued.set(0) }
    }
    private class Harness:AutoCloseable {
        val f=FullFixture();val g=f.capture.mutationGate;val sink=Sink()
        @Volatile var local=LocalRecordUiState(ready=true,opening=false)
        @Volatile var attachment:AttachmentUiState?=null
        val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
        val store=object:EntryRepository {
            override val captureRecords=f.capture
            override val attachments=OwnerAttachmentStore(f.media,g)
            override val localProcessing=SqlLocalProcessingRepository(f.gateway,g)
            override val recycleBin=SqlRecycleBinRepository(f.gateway,f.tags,sourceGate=g)
            override suspend fun entries()=f.db.read("SELECT id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy FROM entries").map { row ->
                val id=row[0] as String;val revision=(row[1] as Long).toInt()
                StoredEntry(id,revision,revisions(id).single { it.revision==revision }.originalText,row[2] as Long,row[3] as String,ProcessingPolicy.valueOf(row[4] as String))
            }
            override suspend fun revisions(entryId:String)=f.db.read("SELECT entryId,revision,originalText,contentHash,createdAtMillis FROM entry_revisions WHERE entryId=?",entryId).map {
                OriginalRevision(it[0] as String,(it[1] as Long).toInt(),it[2] as String,it[3] as String,it[4] as Long)
            }
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("unrelated")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("unrelated")
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("unrelated")
        }
        val player=OriginalAudioController({local},{attachment},{g},{queue.add(it);true},{sink},{true}).also { it.foreground(true) }
        suspend fun draft() {
            val base=f.capture.current();val reservation=g.freeze(base.id)!!;g.reserve(reservation,base,base.text,0)
            val bytes=CaptureAudioSqlTest.wave()
            g.bindIntent(reservation,AttachmentIntent(java.util.UUID.randomUUID().toString(),base.id,1,"audio/wav",FullBackupTest.hash(bytes),bytes.size.toLong()))
            val ready=f.capture.addAudio(reservation,bytes);g.finish(reservation,ready)
            local=local.copy(mixedCapture=MixedCaptureUiState(draft=ready,text=ready.text))
        }
        suspend fun saved() {
            draft();val ready=f.capture.peek()!!;f.capture.save(ready,100,"UTC")
            val entries=store.entries();val versions=checkNotNull(store.localProcessing).versions()
            local=local.copy(entries=entries,mixedCapture=null,supportsLocalProcessing=true,localProcessingVersions=versions)
            attachment=AttachmentUiState(1,ready.id,1,loading=false,items=f.media.list(ready.id,1))
        }
        suspend fun drain() { while(true) { val a=queue.poll() ?: return;a(store) } }
        suspend fun until(condition:()->Boolean)=withTimeout(5000) { while(!condition()) { drain();delay(5) } }
        suspend fun await(phase:OriginalAudioPhase)=until { player.state.value.phase==phase }
        override fun close() { runBlocking { player.stop();player.closeAndJoin() };f.close() }
    }
}
