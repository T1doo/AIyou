package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** Real panel/playback controllers and gate; synthetic records and media ports. */
class FocusedAudioPanelLifecycleTest {
    @Test fun retiringFocusedCandidateStopsAcceptedPlaybackWithoutFakingGateReleaseAndAllowsExactB():Unit=runBlocking {
        val f=Fixture()
        try {
            f.attachments.openAudio(f.a);f.drain();val a=f.attachments.state.value!!
            f.attachments.select(a.sessionId);assertNull(f.attachments.state.value!!.selection)
            assertTrue(f.audio.playAttachment(a.sessionId,f.a.id));assertTrue(f.gate.playbackBusy)
            f.attachments.retireFocusedAudio(a.sessionId)
            assertNull(f.attachments.state.value);assertEquals(1,f.stops)
            assertTrue(f.gate.playbackBusy) // Accepted IO must really settle before another owner starts.
            assertFalse(f.audio.playAttachment(a.sessionId,f.a.id))
            f.attachments.openAudio(f.b);assertNull(f.attachments.state.value)
            f.drain();assertFalse(f.gate.playbackBusy);assertEquals(0,f.outputs)
            f.attachments.openAudio(f.b);f.drain();val b=f.attachments.state.value!!
            assertEquals(f.b,b.focusAudio)
            f.attachments.retireFocusedAudio(a.sessionId);assertEquals(b.sessionId,f.attachments.state.value!!.sessionId)
            assertFalse(f.audio.playAttachment(a.sessionId,f.a.id))
            assertFalse(f.audio.playAttachment(b.sessionId,f.a.id))
            assertTrue(f.audio.playAttachment(b.sessionId,f.b.id)) // Positive control: current B really admits.
            f.audio.stop();f.drain();assertFalse(f.gate.playbackBusy)
        } finally {f.close()}
    }
    @Test fun lateFocusedARefreshCannotRefillOrBlockBAndOldRetirementCannotClearB():Unit=runBlocking {
        val f=Fixture();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        f.listA={entered.complete(Unit);release.await()}
        try {
            f.attachments.openAudio(f.a);val a=f.attachments.state.value!!
            val load=f.queue.poll()!!;val worker=async(Dispatchers.Default){load(f.owner)}
            withTimeout(3_000){entered.await()}
            f.attachments.retireFocusedAudio(a.sessionId);assertNull(f.attachments.state.value)
            f.attachments.openAudio(f.b);val b=f.attachments.state.value!!
            release.complete(Unit);withTimeout(3_000){worker.await()};f.drain()
            val current=f.attachments.state.value!!
            assertEquals(b.sessionId,current.sessionId);assertEquals(f.b,current.focusAudio)
            assertEquals(listOf(f.b),current.items.map{it.intent});assertFalse(current.loading);assertNull(current.error)
            f.attachments.retireFocusedAudio(a.sessionId);f.attachments.refresh(a.sessionId);f.drain()
            assertEquals(b.sessionId,f.attachments.state.value!!.sessionId)
            assertFalse(f.audio.playAttachment(a.sessionId,f.a.id));assertTrue(f.audio.playAttachment(b.sessionId,f.b.id))
            f.audio.stop();f.drain();assertFalse(f.gate.playbackBusy)
        } finally {release.complete(Unit);f.close()}
    }
    private class Fixture:AutoCloseable {
        val a=AttachmentIntent(UUID.randomUUID().toString(),"synthetic-a",1,"audio/wav","1".repeat(64),46)
        val b=a.copy(id=UUID.randomUUID().toString(),entryId="synthetic-b",sha256="2".repeat(64))
        private val local=LocalRecordUiState(ready=true,opening=false,entries=listOf(
            StoredEntry(a.entryId,1,"合成A",1,"UTC",ProcessingPolicy.LOCAL_ONLY),StoredEntry(b.entryId,1,"合成B",1,"UTC",ProcessingPolicy.LOCAL_ONLY)))
        val gate=CaptureDraftGate();val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
        var listA:suspend()->Unit={};var stops=0;var outputs=0
        val attachments:LocalAttachmentController
        val audio:OriginalAudioController
        private val media=object:AttachmentStore {
            override suspend fun save(intent:AttachmentIntent,source:()->java.io.InputStream)=error("No save")
            override suspend fun list(entryId:String,revision:Int):List<LocalAttachment> {if(entryId==a.entryId)listA();return listOf(LocalAttachment(if(entryId==a.entryId)a else b,AttachmentState.READY))}
            override suspend fun read(id:String)=error("Retired requests must not read")
            override suspend fun recover(){}
        }
        val owner=object:EntryRepository {
            override val attachments=media
            override suspend fun entries()=local.entries;override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun readDraft():TextDraft?=null;override suspend fun updateDraft(text:String)=error("No draft");override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("No capture")
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("No source write")
        }
        init {
            lateinit var playing:OriginalAudioController
            attachments=LocalAttachmentController({queue.add(it);true},{error("No image decode")},canUsePanel={it.hasRecordAccess(local)},sourceUi={gate.sourceUi(it)},onPanelClose={stops++;playing.stop()})
            playing=OriginalAudioController({local},{attachments.state.value},{gate},{queue.add(it);true},{outputs++;object:OriginalAudioOutput {
                override fun focus()=true;override fun open(){};override fun play(){};override fun write(samples:ShortArray,count:Int)=count;override fun playedFrames()=0L;override fun close(){}
            }},{true})
            audio=playing;audio.foreground(true)
        }
        suspend fun drain(){while(true)(queue.poll()?:break)(owner)}
        override fun close(){audio.stop();runBlocking{drain()};attachments.markDisposed();attachments.disposeOnIo()}
    }
}
