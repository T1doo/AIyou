package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ControlledCaptureAudioTest {
    @Test(timeout=20000) fun wokenFileBeforeOpenSurvivesPauseAndResumesOnceButCancelRouteOrRetirementNeverOpen():Unit=runBlocking {
        for(disposition in listOf("resume","cancel","route","retire")) Harness().use { h ->
            h.start();val woke=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val paused=CompletableDeferred<Unit>()
            val first=java.util.concurrent.atomic.AtomicBoolean(true);val foreground=java.util.concurrent.atomic.AtomicBoolean(true)
            val opened=AtomicInteger();val closed=AtomicInteger()
            val audio=h.fileAudio {
                if(first.compareAndSet(true,false)) { woke.complete(Unit);release.await() }
            }
            try {
                assertTrue(audio.startFile({true},{foreground.get().also { if(!it)paused.complete(Unit) }}));h.drain()
                val key=checkNotNull(h.ui.audio.filePickerKey);assertTrue(audio.claimFile(key))
                audio.selectedFile(key,object:SelectedWaveSource { override fun open():java.io.InputStream {
                    opened.incrementAndGet();return object:java.io.ByteArrayInputStream(CaptureAudioSqlTest.wave()) {
                        override fun close() { closed.incrementAndGet();super.close() }
                    }
                } })
                withTimeout(4000) { woke.await() };foreground.set(false);release.complete(Unit)
                withTimeout(4000) { paused.await() }
                assertEquals(0,opened.get());assertEquals(CaptureAudioPhase.PICKING,h.ui.audio.phase)
                assertTrue(audio.busy);assertTrue(h.repo.mutationGate.frozen);assertNull(h.repo.peek()!!.audio)
                when(disposition) {
                    "resume" -> { foreground.set(true);audio.resumeFile();audio.resumeFile();h.await(CaptureAudioPhase.READY)
                        assertEquals(1,opened.get());assertEquals(1,closed.get());assertArrayEquals(CaptureAudioSqlTest.wave(),h.repo.audio(h.repo.peek()!!)) }
                    "cancel" -> { audio.cancel();h.await(CaptureAudioPhase.IDLE);assertEquals(0,opened.get()) }
                    "route" -> { audio.fileRouteChanged();foreground.set(true);audio.resumeFile();h.await(CaptureAudioPhase.IDLE);assertEquals(0,opened.get()) }
                    else -> { audio.retire();foreground.set(true);audio.resumeFile();audio.closeAndFinalize(h.store);assertFalse(audio.busy);assertEquals(0,opened.get()) }
                }
            } finally { release.complete(Unit);audio.closeAndFinalize(h.store) }
        }
    }
    @Test fun acceptedFilePickerSurvivesOldCompositionDisposalButRealVmRouteChangeRejectsItsSource():Unit=runBlocking {
        for(navigate in listOf(false,true)) Harness().use { h ->
            h.start();var oldCompositionAlive=true;var foreground=true
            assertTrue(h.c.audioCapture.startFile({oldCompositionAlive},{foreground}));h.drain()
            val key=checkNotNull(h.ui.audio.filePickerKey)
            foreground=false;assertFalse(h.c.audioCapture.claimFile(key))
            foreground=true;assertTrue(h.c.audioCapture.claimFile(key))
            oldCompositionAlive=false // Same VM after rotation; no route event.
            if(navigate)h.c.audioCapture.fileRouteChanged()
            val opened=AtomicInteger()
            h.c.audioCapture.selectedFile(key,object:SelectedWaveSource {
                override fun open():java.io.InputStream { opened.incrementAndGet();return CaptureAudioSqlTest.wave().inputStream() }
            })
            h.c.audioCapture.resumeFile();h.await(if(navigate)CaptureAudioPhase.IDLE else CaptureAudioPhase.READY)
            assertEquals(if(navigate)0 else 1,opened.get())
            assertFalse(h.c.audioCapture.startFile({oldCompositionAlive},{true}))
        }
    }
    @Test(timeout=10000) fun actualVmPlaybackBlocksLegacyOptimisticInputPackageAdmissionAndLockUntilActualClose():Unit=runBlocking {
        Harness().use { h ->
            h.start();assertTrue(h.c.audioCapture.start({Input()}));h.await(CaptureAudioPhase.READY)
            val closing=CountDownLatch(1);val release=CountDownLatch(1)
            val vm=LocalRecordsViewModel(android.app.Application(),{h.store},audioOutput={object:OriginalAudioOutput {
                override fun focus()=true
                override fun open() {}
                override fun play() {}
                override fun write(samples:ShortArray,count:Int)=count
                override fun playedFrames()=0L
                override fun close() { closing.countDown();check(release.await(4,TimeUnit.SECONDS)) }
            }})
            try {
                withTimeout(4000) { while(vm.state.value.mixedCapture?.draft==null)delay(5) }
                vm.audioForeground(true);assertTrue(vm.audioPlayback.playDraft())
                withTimeout(4000) { while(vm.audioPlayback.state.value.phase!=OriginalAudioPhase.PLAYING)delay(5) }
                val before=vm.state.value
                vm.changeText("冻结后的legacy输入");vm.mixedCapture.edit("冻结后的此刻输入")
                assertEquals(before.text,vm.state.value.text);assertEquals(before.mixedCapture!!.text,vm.state.value.mixedCapture!!.text)
                assertFalse(vm.enqueuePackageOperation { error("must not execute while pinned") })
                assertFalse(vm.canRetireForAppAccess());assertFalse(vm.appAccessIdle.value);assertNull(vm.state.value.error)
                vm.audioPlayback.stop();assertTrue(closing.await(4,TimeUnit.SECONDS))
                assertTrue(h.repo.mutationGate.frozen);assertFalse(vm.canRetireForAppAccess())
                release.countDown();withTimeout(4000) { while(vm.audioPlayback.busy || !vm.appAccessIdle.value)delay(5) }
                assertFalse(h.repo.mutationGate.frozen);assertTrue(vm.canRetireForAppAccess());assertNull(vm.state.value.error)
            } finally { release.countDown();vm.clearForHostTest();withTimeout(4000) { vm.awaitAppAccessClosed() } }
        }
    }
    @Test fun explicitSameResourceCloseRetryDoesNotUnlockOrSaveUntilDispositionAndPreIntentAbandonWorks():Unit=runBlocking {
        for(save in listOf(true,false)) Harness().use { h ->
            h.start();val closes=AtomicInteger();val opened=AtomicInteger()
            assertTrue(h.c.audioCapture.startFile({true},{true}));h.drain()
            val key=checkNotNull(h.ui.audio.filePickerKey);h.c.audioCapture.claimFile(key)
            h.c.audioCapture.selectedFile(key,object:SelectedWaveSource {
                override fun open():java.io.InputStream { opened.incrementAndGet();return object:java.io.ByteArrayInputStream(CaptureAudioSqlTest.wave()) {
                    override fun close() { if(closes.incrementAndGet()==1)error("synthetic close ACK lost");super.close() }
                } }
            })
            h.await(CaptureAudioPhase.UNKNOWN);assertTrue(h.ui.audio.resourceClosureUnknown)
            h.c.audioCapture.retryResourceClose()
            withTimeout(5000) { while(h.ui.audio.resourceClosureUnknown)delay(5) }
            assertEquals(2,closes.get());assertEquals(1,opened.get());assertTrue(h.repo.mutationGate.frozen)
            assertNull(h.repo.peek()!!.audio);assertEquals(CaptureAudioPhase.UNKNOWN,h.ui.audio.phase)
            if(save)h.c.audioCapture.retry()else h.c.audioCapture.discardUnwritten()
            h.await(if(save)CaptureAudioPhase.READY else CaptureAudioPhase.IDLE)
            if(save)assertArrayEquals(CaptureAudioSqlTest.wave(),h.repo.audio(h.repo.peek()!!)) else assertNull(h.repo.peek()!!.audio)
            assertFalse(h.repo.mutationGate.frozen)
        }
    }
    @Test fun filePickerDrainsEditsAndResultBeforeResumeCannotOpenUntilSameOwnerResumes():Unit=runBlocking {
        Harness().use { h ->
            h.start();h.c.edit("合成文件导入前文字")
            var foreground=true;val opened=AtomicInteger();val closed=AtomicInteger()
            assertTrue(h.c.audioCapture.startFile({true},{foreground}));h.drain()
            val key=checkNotNull(h.ui.audio.filePickerKey);assertTrue(h.c.audioCapture.claimFile(key))
            assertFalse(h.c.audioCapture.claimFile(key));foreground=false
            h.c.audioCapture.selectedFile(key,object:SelectedWaveSource {
                override fun open():java.io.InputStream { opened.incrementAndGet();return object:java.io.ByteArrayInputStream(CaptureAudioSqlTest.wave()) {
                    override fun close() { closed.incrementAndGet();super.close() }
                } }
            })
            delay(30);assertEquals(0,opened.get());assertTrue(h.repo.mutationGate.frozen)
            h.c.edit("被拒绝的迟到文字");assertEquals("合成文件导入前文字",h.ui.text)
            foreground=true;h.c.audioCapture.resumeFile();h.await(CaptureAudioPhase.READY)
            assertEquals(1,opened.get());assertEquals(1,closed.get())
            assertEquals("合成文件导入前文字",h.repo.peek()!!.text)
            assertArrayEquals(CaptureAudioSqlTest.wave(),h.repo.audio(h.repo.peek()!!))
        }
    }
    @Test fun fileStrictEofCanonicalProfileAndSizeFailuresNeverPublishPartialAudio():Unit=runBlocking {
        val wave=CaptureAudioSqlTest.wave()
        val badRate=wave.copyOf().also { it[24]=0 }
        for(bytes in listOf(wave.copyOf(wave.size-2),wave+byteArrayOf(0),badRate,byteArrayOf(),ByteArray(LocalPcmAudio.MAX_WAV_BYTES+1))) {
            Harness().use { h ->
                h.start();assertTrue(h.c.audioCapture.startFile({true},{true}));h.drain()
                val key=checkNotNull(h.ui.audio.filePickerKey);assertTrue(h.c.audioCapture.claimFile(key))
                h.c.audioCapture.selectedFile(key,object:SelectedWaveSource { override fun open()=bytes.inputStream() })
                h.await(CaptureAudioPhase.IDLE)
                assertNull(h.repo.peek()!!.audio);assertNotNull(h.ui.audio.error)
                assertFalse(h.repo.mutationGate.frozen)
                assertTrue(h.f.db.read("SELECT id FROM attachments").isEmpty())
                assertTrue(h.f.mediaRoot.listFiles().orEmpty().none { it.name.endsWith(".aead") })
            }
        }
    }
    @Test fun cancelWhileActualFileCloseIsBlockedWinsBeforeFinalizationAndCannotUnlockEarly():Unit=runBlocking {
        Harness().use { h ->
            h.start();val closing=CountDownLatch(1);val release=CountDownLatch(1)
            assertTrue(h.c.audioCapture.startFile({true},{true}));h.drain()
            val key=checkNotNull(h.ui.audio.filePickerKey);h.c.audioCapture.claimFile(key)
            h.c.audioCapture.selectedFile(key,object:SelectedWaveSource { override fun open()=object:java.io.ByteArrayInputStream(CaptureAudioSqlTest.wave()) {
                override fun close() { closing.countDown();check(release.await(4,TimeUnit.SECONDS));super.close() }
            } })
            try {
                assertTrue(closing.await(4,TimeUnit.SECONDS));h.c.audioCapture.cancel();h.drain()
                assertTrue(h.c.audioCapture.busy);assertTrue(h.repo.mutationGate.frozen);assertNull(h.repo.peek()!!.audio)
            } finally { release.countDown() }
            h.await(CaptureAudioPhase.IDLE);assertNull(h.repo.peek()!!.audio);assertFalse(h.repo.mutationGate.frozen)
        }
    }
    @Test fun failedActualFileCloseQuarantinesOwnerAndNeverSettlesOrOpensSecondSource():Unit=runBlocking {
        Harness().use { h ->
            h.allowUnconfirmedClose=true;h.start();assertTrue(h.c.audioCapture.startFile({true},{true}));h.drain()
            val key=checkNotNull(h.ui.audio.filePickerKey);h.c.audioCapture.claimFile(key)
            h.c.audioCapture.selectedFile(key,object:SelectedWaveSource { override fun open()=object:java.io.ByteArrayInputStream(CaptureAudioSqlTest.wave()) {
                override fun close() { error("synthetic close outcome unknown") }
            } })
            h.await(CaptureAudioPhase.UNKNOWN);h.c.audioCapture.reconcile();h.c.audioCapture.retry();h.drain()
            assertTrue(h.repo.mutationGate.frozen);assertNull(h.repo.peek()!!.audio)
            assertFalse(h.c.audioCapture.startFile({true},{true}))
            assertThrows(IllegalStateException::class.java) { runBlocking { h.c.drainAudioOnIo(h.store) } }
        }
    }
    @Test fun cancelledPickerKeyCannotDonateItsLateSourceToNextRequest():Unit=runBlocking {
        Harness().use { h ->
            h.start();assertTrue(h.c.audioCapture.startFile({true},{true}));h.drain()
            val old=checkNotNull(h.ui.audio.filePickerKey);h.c.audioCapture.claimFile(old);h.c.audioCapture.cancel()
            assertFalse(h.c.audioCapture.claimFile(old));h.await(CaptureAudioPhase.IDLE)
            assertTrue(h.c.audioCapture.startFile({true},{true}));h.drain()
            val opened=AtomicInteger()
            h.c.audioCapture.selectedFile(old,object:SelectedWaveSource { override fun open():java.io.InputStream { opened.incrementAndGet();return CaptureAudioSqlTest.wave().inputStream() } })
            delay(20);assertEquals(0,opened.get());assertEquals(CaptureAudioPhase.PICKING,h.ui.audio.phase)
            h.c.audioCapture.cancel();h.await(CaptureAudioPhase.IDLE)
        }
    }
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
    @Test fun retirementAfterReadinessObservationStillPreventsUnadmittedOpen():Unit=runBlocking {
        Harness().use { h ->
            h.start();val opened=AtomicInteger();val retired=CountDownLatch(1)
            assertTrue(h.c.audioCapture.start({opened.incrementAndGet();Input()},{
                // Return a readiness observation that was true just before retirement won.
                if(h.local.mixedCapture?.audio?.phase==CaptureAudioPhase.STARTING) { h.c.dispose();retired.countDown() }
                true
            }))
            h.drain();assertTrue(retired.await(3,TimeUnit.SECONDS))
            withTimeout(4000) { h.c.drainAudioOnIo(h.store) }
            assertEquals(0,opened.get());assertNull(h.repo.peek()!!.audio)
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
    @Test fun publishedCompleteWaveReconciliationContinuesAcceptedAbandonmentAfterPreCleanupFailure():Unit=runBlocking {
        Harness().use { h ->
            h.start();val base=h.repo.peek()!!;val encoded=AtomicReference<ByteArray>()
            val audio=h.audio { samples,count -> LocalPcmAudio.encode(samples,count).also { encoded.set(it) } }
            try {
                h.f.mediaCheckpoint={if(it=="capture-published")error("synthetic publication ACK loss before SQL READY")}
                assertTrue(audio.start({Input()}));h.await(CaptureAudioPhase.UNKNOWN)
                val writing=h.repo.peek()!!;assertEquals(CaptureAudioState.WRITING,writing.audio!!.state)
                val intent=writing.audio!!.intent
                assertArrayEquals(CaptureAudioSqlTest.wave(),h.f.media.readCapture(intent))
                val file=h.f.mediaRoot.resolve(intent.id+".aead");assertTrue(file.isFile)
                h.f.mediaCheckpoint={if(it=="locked")error("synthetic failure before cleanup begins")}
                audio.discardUnwritten();h.drain()
                assertEquals(CaptureAudioPhase.UNKNOWN,h.ui.audio.phase);assertTrue(h.repo.mutationGate.frozen)
                assertTrue(writing.sameSnapshot(h.repo.peek()!!));assertTrue(file.isFile)
                assertArrayEquals(CaptureAudioSqlTest.wave(),encoded.get())
                audio.reconcile();h.drain()
                assertEquals(CaptureAudioPhase.UNKNOWN,h.ui.audio.phase);assertTrue(h.repo.mutationGate.frozen)
                assertTrue(writing.sameSnapshot(h.repo.peek()!!));assertTrue(file.isFile)
                assertArrayEquals(CaptureAudioSqlTest.wave(),encoded.get())
                h.f.mediaCheckpoint={};audio.reconcile();h.drain()
                assertEquals(CaptureAudioPhase.FAILED,h.ui.audio.phase)
                val failed=h.repo.peek()!!;assertEquals(CaptureAudioState.FAILED,failed.audio!!.state)
                assertEquals(intent,failed.audio!!.intent);assertEquals(base.version+2,failed.version)
                assertFalse(file.exists());assertFalse(h.repo.mutationGate.frozen)
                assertTrue(encoded.get().all { it==0.toByte() })
            } finally { h.f.mediaCheckpoint={};audio.closeAndFinalize(h.store) }
        }
    }
    @Test fun incompleteRealSqlWriteReconciliationKeepsWaveAndSameIntentRetryable():Unit=runBlocking {
        Harness().use { h ->
            h.start();val base=h.repo.peek()!!
            h.f.mediaCheckpoint={if(it=="created")error("synthetic partial ciphertext")}
            assertTrue(h.c.audioCapture.start({Input()}));h.await(CaptureAudioPhase.UNKNOWN)
            val writing=h.repo.peek()!!;assertEquals(CaptureAudioState.WRITING,writing.audio!!.state)
            h.f.mediaCheckpoint={};h.c.audioCapture.reconcile();h.drain()
            assertEquals(CaptureAudioPhase.UNKNOWN,h.ui.audio.phase);assertTrue(h.repo.mutationGate.frozen)
            h.c.audioCapture.retry();h.await(CaptureAudioPhase.READY)
            val ready=h.repo.peek()!!;assertEquals(writing.audio!!.intent,ready.audio!!.intent)
            assertEquals(base.version+2,ready.version);assertArrayEquals(CaptureAudioSqlTest.wave(),h.repo.audio(ready))
        }
    }
    @Test fun incompleteRealSqlWriteCanOnlyDiscardWaveAfterExplicitDurableAbandonment():Unit=runBlocking {
        Harness().use { h ->
            h.start();h.f.mediaCheckpoint={if(it=="created")error("synthetic partial ciphertext")}
            assertTrue(h.c.audioCapture.start({Input()}));h.await(CaptureAudioPhase.UNKNOWN)
            val writing=h.repo.peek()!!;h.f.mediaCheckpoint={};h.c.audioCapture.reconcile();h.drain()
            assertEquals(CaptureAudioPhase.UNKNOWN,h.ui.audio.phase)
            h.f.mediaCheckpoint={if(it=="package-cleaned")error("synthetic lost cleanup ACK")}
            h.c.audioCapture.discardUnwritten();h.drain()
            assertEquals(CaptureAudioPhase.UNKNOWN,h.ui.audio.phase);assertTrue(h.repo.mutationGate.frozen)
            assertEquals(CaptureAudioState.WRITING,h.repo.peek()!!.audio!!.state)
            h.f.mediaCheckpoint={};h.c.audioCapture.retry();h.await(CaptureAudioPhase.FAILED)
            assertEquals(writing.audio!!.intent,h.repo.peek()!!.audio!!.intent)
            assertFalse(h.repo.mutationGate.frozen)
            assertTrue(h.f.mediaRoot.listFiles().orEmpty().none { it.name.endsWith(".aead") || it.name.endsWith(".tmp") })
        }
    }
    @Test(timeout=10000) fun cancelDuringEncodingDiscardsWaveBeforeAnyAudioIntentOrFileWrite():Unit=runBlocking {
        Harness().use { h ->
            h.start();val base=h.repo.peek()!!;val encoding=CountDownLatch(1);val release=CountDownLatch(1)
            val audio=h.audio { samples,count ->
                encoding.countDown();check(release.await(3,TimeUnit.SECONDS));LocalPcmAudio.encode(samples,count)
            }
            try {
                assertTrue(audio.start({Input()}));h.drain();assertTrue(encoding.await(3,TimeUnit.SECONDS))
                audio.cancel();assertTrue(h.repo.mutationGate.frozen);assertNull(h.repo.peek()!!.audio)
                release.countDown()
                withTimeout(4000) { while(h.queue.isEmpty())delay(5) };h.drain()
                assertEquals(CaptureAudioPhase.IDLE,h.ui.audio.phase)
                assertTrue(base.sameSnapshot(h.repo.peek()!!));assertFalse(h.repo.mutationGate.frozen)
                assertTrue(h.f.mediaRoot.listFiles().orEmpty().none { it.name.endsWith(".aead") })
            } finally { release.countDown();audio.closeAndFinalize(h.store) }
        }
    }
    @Test fun cancelAfterFinalizingAdmissionCannotRetractConfirmedSave():Unit=runBlocking {
        Harness().use { h ->
            h.start();val audio=h.audio(LocalPcmAudio::encode)
            try {
                assertTrue(audio.start({Input()}));h.drain()
                withTimeout(4000) { while(h.ui.audio.phase!=CaptureAudioPhase.FINALIZING || h.queue.isEmpty())delay(5) }
                audio.cancel();h.drain();assertEquals(CaptureAudioPhase.READY,h.ui.audio.phase)
                assertArrayEquals(CaptureAudioSqlTest.wave(),h.repo.audio(h.repo.peek()!!))
            } finally { audio.closeAndFinalize(h.store) }
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
        fun audio(encode:(ShortArray,Int)->ByteArray)=newAudio(encode,{})
        fun fileAudio(before:suspend()->Unit)=newAudio(LocalPcmAudio::encode,before)
        private fun newAudio(encode:(ShortArray,Int)->ByteArray,before:suspend()->Unit)=ControlledCaptureAudio(
            {ui},{true},{action->if(acceptQueue){queue.add(action);true}else false},{ui.draft},
            {draft->synchronized(uiLock){local=local.copy(mixedCapture=ui.copy(draft=draft,text=draft.text))}},
            {audio->synchronized(uiLock){local=local.copy(mixedCapture=ui.copy(audio=audio))}},beforeFileOpen=before,encodeWave=encode
        ).also { it.attach(checkNotNull(store.captureRecords)) }
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
