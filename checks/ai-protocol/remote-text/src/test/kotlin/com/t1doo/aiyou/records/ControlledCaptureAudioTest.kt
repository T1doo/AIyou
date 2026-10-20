package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ControlledCaptureAudioTest {
    @Test fun immediateStartDrainsTypedTextBeforeBindingAndNeverDropsAudioOnOldAck():Unit=runBlocking {
        Harness().use { h ->
            h.start();val version=h.ui.draft!!.version
            h.c.edit("合成开始前文字");assertEquals(1,h.ui.pendingEdits)
            val opened=AtomicInteger()
            assertTrue(h.c.audioCapture.start({opened.incrementAndGet();Input()}))
            h.c.edit("冻结后的迟到输入");assertEquals("合成开始前文字",h.ui.text);assertEquals(0,opened.get())
            h.await(CaptureAudioPhase.READY)
            assertEquals(1,opened.get());val actual=h.repo.peek()!!
            assertEquals(version+3,actual.version);assertEquals("合成开始前文字",actual.text)
            assertArrayEquals(CaptureAudioSqlTest.wave(),h.repo.audio(actual))
        }
    }
    @Test fun failedPreStartEditKeepsTextAndNeverOpensInput():Unit=runBlocking {
        Harness().use { h ->
            h.start();h.failEdit=true;h.c.edit("合成未确认文字")
            val opened=AtomicInteger();assertTrue(h.c.audioCapture.start({opened.incrementAndGet();Input()}))
            h.await(CaptureAudioPhase.FAILED)
            assertEquals(0,opened.get());assertEquals("合成未确认文字",h.ui.text)
            assertEquals("",h.repo.peek()!!.text);assertNull(h.repo.peek()!!.audio)
        }
    }
    @Test fun unknownFreezesAllUiMutationsAndReadbackSettlesSameAudio():Unit=runBlocking {
        Harness().use { h ->
            h.start();h.f.mediaCheckpoint={if(it=="capture-published")error("synthetic ACK loss")}
            assertTrue(h.c.audioCapture.start({Input()}));h.await(CaptureAudioPhase.UNKNOWN)
            val before=h.ui.draft!!;val oldMeta=h.ui.retainJpegMetadata
            h.c.edit("不应接收");h.c.select();h.c.remove("old");h.c.requestDiscard();h.c.confirmDiscard()
            h.c.metadata(!oldMeta);h.c.changePolicy(before.id,before.version,ProcessingPolicy.AI_NONE);h.c.refresh();h.c.save()
            assertEquals(before.text,h.ui.text);assertEquals(oldMeta,h.ui.retainJpegMetadata)
            assertFalse(h.ui.confirmDiscard);assertNull(h.ui.selection);assertTrue(h.queue.isEmpty())
            assertFalse(h.c.audioCapture.start({Input()}))
            h.f.mediaCheckpoint={};h.c.audioCapture.reconcile();h.await(CaptureAudioPhase.READY)
            val actual=h.repo.peek()!!;assertEquals(before.version+2,actual.version)
            assertArrayEquals(CaptureAudioSqlTest.wave(),h.repo.audio(actual))
            h.c.edit("核对后可编辑");h.drain();assertEquals("核对后可编辑",h.repo.peek()!!.text)
        }
    }
    @Test(timeout=10000) fun cancelKeepsGateFrozenUntilActualDeviceReleaseAndDoesNotWriteAudio():Unit=runBlocking {
        Harness().use { h ->
            h.start();val entered=CountDownLatch(1);val release=CountDownLatch(1)
            try {
                assertTrue(h.c.audioCapture.start({Input(waiting=true,closed={entered.countDown();check(release.await(3,TimeUnit.SECONDS))})}))
                h.awaitSamples();h.c.audioCapture.cancel();assertTrue(entered.await(3,TimeUnit.SECONDS))
                h.c.edit("过早编辑");assertEquals("",h.ui.text);assertNull(h.repo.peek()!!.audio)
            } finally { release.countDown() }
            h.await(CaptureAudioPhase.IDLE);assertNull(h.repo.peek()!!.audio)
            assertTrue(h.f.mediaRoot.listFiles().orEmpty().none { it.name.endsWith(".aead") })
        }
    }
    @Test fun retiredRouteBeforeBarrierNeverOpensAndQueueRejectionDoesNotFreezeInput():Unit=runBlocking {
        Harness().use { h ->
            h.start();var current=true;val opened=AtomicInteger()
            assertTrue(h.c.audioCapture.start({opened.incrementAndGet();Input()},{current}));current=false
            h.await(CaptureAudioPhase.IDLE);assertEquals(0,opened.get())
            h.acceptQueue=false;assertFalse(h.c.audioCapture.start({Input()}));assertFalse(h.repo.mutationGate.frozen)
            h.acceptQueue=true;h.c.edit("输入仍可保存");h.drain();assertEquals("输入仍可保存",h.repo.peek()!!.text)
        }
    }
    @Test fun retirementBeforeBarrierDoesNotOpenInputOrLetLateQueuedControlWrite():Unit=runBlocking {
        Harness().use { h ->
            h.start();val base=h.repo.peek()!!;val opened=AtomicInteger()
            assertTrue(h.c.audioCapture.start({opened.incrementAndGet();Input()}))
            h.c.dispose();h.c.drainAudioOnIo(h.store);h.drain()
            assertEquals(0,opened.get());assertTrue(base.sameSnapshot(h.repo.peek()!!));assertNull(h.repo.peek()!!.audio)
        }
    }
    @Test fun forcedRetirementFinalizesWithoutEnqueueingToClosedActor():Unit=runBlocking {
        Harness().use { h ->
            h.start();assertTrue(h.c.audioCapture.start({Input(waiting=true)}));h.awaitSamples()
            h.acceptQueue=false;h.c.dispose()
            withTimeout(4000) { h.c.drainAudioOnIo(h.store) }
            val actual=h.repo.peek()!!;assertEquals(CaptureAudioState.READY,actual.audio!!.state)
            assertArrayEquals(CaptureAudioSqlTest.wave(),h.repo.audio(actual));assertTrue(h.repo.mutationGate.frozen)
        }
    }
    @Test fun releaseFailureQuarantinesInputAndNeverClaimsAudioOrOwnerClosure():Unit=runBlocking {
        Harness().use { h ->
            h.start();h.allowUnconfirmedClose=true;val releases=AtomicInteger()
            assertTrue(h.c.audioCapture.start({Input(closed={releases.incrementAndGet();error("synthetic release unknown")})}))
            h.await(CaptureAudioPhase.UNKNOWN)
            assertEquals(1,releases.get());assertNull(h.repo.peek()!!.audio);assertTrue(h.repo.mutationGate.frozen)
            h.c.audioCapture.retry();h.c.audioCapture.reconcile();assertTrue(h.queue.isEmpty())
            assertFalse(h.c.audioCapture.start({Input()}))
            assertThrows(IllegalStateException::class.java) { runBlocking { h.c.drainAudioOnIo(h.store) } }
            assertEquals(1,releases.get());assertTrue(h.f.mediaRoot.listFiles().orEmpty().none { it.name.endsWith(".aead") })
        }
    }
    @Test fun unexecutedWriteReadbackKeepsOriginalUntilRetryOrExplicitDiscard():Unit=runBlocking {
        Harness().use { h ->
            h.start();h.failAudioBefore=true
            assertTrue(h.c.audioCapture.start({Input()}));h.await(CaptureAudioPhase.UNKNOWN)
            assertNull(h.repo.peek()!!.audio)
            h.c.audioCapture.reconcile();h.drain()
            assertEquals(CaptureAudioPhase.UNKNOWN,h.ui.audio.phase);assertTrue(h.repo.mutationGate.frozen)
            h.failAudioBefore=false;h.c.audioCapture.retry();h.await(CaptureAudioPhase.READY)
            assertArrayEquals(CaptureAudioSqlTest.wave(),h.repo.audio(h.repo.peek()!!))
        }
        Harness().use { h ->
            h.start();h.failAudioBefore=true
            assertTrue(h.c.audioCapture.start({Input()}));h.await(CaptureAudioPhase.UNKNOWN)
            h.c.audioCapture.discardUnwritten();h.await(CaptureAudioPhase.IDLE)
            assertNull(h.repo.peek()!!.audio);assertFalse(h.repo.mutationGate.frozen)
        }
    }
    @Test(timeout=10000) fun actualVmWaitsForDeviceReleaseThenFinalizesBeforeOwnerClose():Unit=runBlocking {
        Harness().use { h ->
            val release=CountDownLatch(1);val releasing=CountDownLatch(1);val closed=CountDownLatch(1)
            var audioAtClose=false
            val store=object:EntryRepository by h.store,AutoCloseable {
                override fun close() { audioAtClose=runBlocking { h.repo.peek()?.audio?.state==CaptureAudioState.READY };closed.countDown() }
            }
            val vm=LocalRecordsViewModel(android.app.Application(),{store})
            try {
                withTimeout(4000) { while(vm.state.value.mixedCapture?.draft==null)delay(5) }
                assertTrue(vm.mixedCapture.audioCapture.start({Input(waiting=true,closed={releasing.countDown();check(release.await(3,TimeUnit.SECONDS))})}))
                withTimeout(4000) { while((vm.state.value.mixedCapture?.audio?.samples ?: 0)<4)delay(5) }
                vm.clearForHostTest();assertTrue(releasing.await(3,TimeUnit.SECONDS))
                assertEquals(1L,closed.count);release.countDown();assertTrue(closed.await(3,TimeUnit.SECONDS));assertTrue(audioAtClose)
            } finally { release.countDown();vm.clearForHostTest() }
        }
    }
    @Test(timeout=10000) fun actualVmPackageCommandBlocksStartEvenAfterItsPanelWasClosed():Unit=runBlocking {
        Harness().use { h ->
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val opened=AtomicInteger()
            val vm=LocalRecordsViewModel(android.app.Application(),{h.store})
            try {
                withTimeout(4000) { while(vm.state.value.mixedCapture?.draft==null)delay(5) }
                assertTrue(vm.enqueuePackageOperation { entered.countDown();withContext(Dispatchers.IO) { check(release.await(3,TimeUnit.SECONDS)) } })
                assertTrue(entered.await(3,TimeUnit.SECONDS));assertNull(vm.state.value.recordPackage)
                assertFalse(vm.mixedCapture.audioCapture.start({opened.incrementAndGet();Input()}))
                assertEquals(0,opened.get());assertEquals(CaptureAudioPhase.IDLE,vm.state.value.mixedCapture!!.audio.phase)
            } finally { release.countDown();vm.clearForHostTest();withTimeout(4000) { vm.awaitAppAccessClosed() } }
        }
    }
    private class Input(val waiting:Boolean=false,val closed:()->Unit={}):ControlledPcmInput {
        private var sent=false
        override fun read(samples:ShortArray):Int {
            if(sent)return if(waiting)0 else -1
            sent=true;shortArrayOf(0x1234,-2,1000,-1000).copyInto(samples);return 4
        }
        override fun close()=closed()
    }
    private class Harness:AutoCloseable {
        val f=FullFixture();val repo=f.capture
        @Volatile var local=LocalRecordUiState(ready=true,opening=false,supportsMixedCapture=true,mixedCapture=MixedCaptureUiState())
        val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
        @Volatile var acceptQueue=true
        var failEdit=false
        var failAudioBefore=false
        var allowUnconfirmedClose=false
        private val uiLock=Any()
        val store=object:EntryRepository {
            override val captureRecords=object:CaptureRepository by repo {
                override suspend fun addAudio(request:CaptureDraftGate.Reservation,bytes:ByteArray):CaptureDraft {
                    if(failAudioBefore)error("synthetic no intent write")
                    return repo.addAudio(request,bytes)
                }
                override suspend fun edit(expected:CaptureDraft,text:String):CaptureDraft {
                    if(failEdit)error("synthetic edit failure")
                    return repo.edit(expected,text)
                }
            }
            override suspend fun entries()=emptyList<StoredEntry>()
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("unrelated")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("unrelated")
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("unrelated")
        }
        val c=MixedCaptureController({local},{change->synchronized(uiLock){local=change(local)}},
            {action->if(acceptQueue){queue.add(action);true}else false},
            {object:AttachmentPicture{override val width=1;override val height=1}},Clock.systemUTC())
        val ui get()=local.mixedCapture!!
        suspend fun start()=c.initialize(store)
        suspend fun drain() { while(true) { val action=queue.poll() ?: break;action(store) } }
        suspend fun await(phase:CaptureAudioPhase)=withTimeout(5000) { while(ui.audio.phase!=phase) { drain();delay(5) } }
        suspend fun awaitSamples()=withTimeout(5000) { while(ui.audio.samples<4) { drain();delay(5) } }
        override fun close() {
            try { runBlocking { c.drainAudioOnIo(store) } }
            catch(e:IllegalStateException) { if(!allowUnconfirmedClose)throw e }
            finally { c.dispose();c.disposeOnIo();f.close() }
        }
    }
}
