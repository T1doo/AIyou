package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** Real production panel state machine; encoded bytes/bitmap decoder are synthetic host fixtures. */
class FocusedImagePanelLifecycleTest {
    @Test fun exactFocusedImageRejectsOtherViewsAndImageWritesAndRetiresBeforeOpeningB():Unit=runBlocking {
        val f=Fixture()
        try {
            f.controller.openImage(f.a);f.drain();val a=f.controller.state.value!!
            assertEquals(f.a,a.focusImage);assertEquals(f.a.id,a.pictureId);assertNotNull(a.picture)
            assertFalse(f.audio.playAttachment(a.sessionId,f.audioIntent.id));assertFalse(f.gate.playbackBusy);assertEquals(0,f.outputs)
            f.controller.select(a.sessionId);assertNull(f.controller.state.value!!.selection)
            f.controller.view(a.sessionId,f.other.id);f.drain();assertEquals(f.a.id,f.controller.state.value!!.pictureId)
            f.controller.retireFocusedAudio(a.sessionId);assertNotNull(f.controller.state.value)
            f.controller.retireFocusedImage(a.sessionId);assertNull(f.controller.state.value)
            f.controller.openImage(f.b);f.drain();val b=f.controller.state.value!!
            assertEquals(f.b,b.focusImage);assertEquals(f.b.id,b.pictureId)
            f.controller.retireFocusedImage(a.sessionId);f.controller.view(a.sessionId,f.a.id);f.drain()
            assertEquals(b.sessionId,f.controller.state.value!!.sessionId);assertEquals(f.b.id,f.controller.state.value!!.pictureId)
            f.controller.retireFocusedImage(b.sessionId);f.controller.open(f.a.entryId,1);f.drain()
            assertTrue(f.audio.playAttachment(f.controller.state.value!!.sessionId,f.audioIntent.id))
            f.audio.stop();f.drain();assertFalse(f.gate.playbackBusy)
        }finally{f.close()}
    }
    @Test fun lateFocusedAImageReadCannotPublishOverBAndItsAuthenticatedBytesAreWiped():Unit=runBlocking {
        val f=Fixture();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        f.beforeARead={entered.complete(Unit);release.await()}
        try {
            f.controller.openImage(f.a);f.next() // Real refresh queues its exact-image read.
            val a=f.controller.state.value!!;val read=f.queue.poll()!!;val work=async(Dispatchers.Default){read(f.owner)}
            withTimeout(3_000){entered.await()};f.controller.retireFocusedImage(a.sessionId)
            assertNull(f.controller.state.value);f.controller.openImage(f.b)
            release.complete(Unit);withTimeout(3_000){work.await()};f.drain()
            val b=f.controller.state.value!!;assertEquals(f.b.id,b.pictureId);assertEquals(f.b,b.focusImage)
            assertTrue(f.lastABytes!!.all{it==0.toByte()});assertEquals(0,f.aDecodes)
            f.controller.retireFocusedSource(a.sessionId);f.controller.refresh(a.sessionId);f.drain()
            assertEquals(b.sessionId,f.controller.state.value!!.sessionId);assertEquals(f.b.id,f.controller.state.value!!.pictureId)
            f.controller.retireFocusedImage(b.sessionId);f.controller.open(f.a.entryId,1);f.drain()
            assertTrue(f.audio.playAttachment(f.controller.state.value!!.sessionId,f.audioIntent.id))
            f.audio.stop();f.drain();assertFalse(f.gate.playbackBusy)
        }finally{release.complete(Unit);f.close()}
    }
    private class Fixture:AutoCloseable {
        private val bytesA=byteArrayOf(-119,80,78,71,13,10,26,10,1)
        private val bytesB=bytesA.copyOf().also{it[it.lastIndex]=2}
        val a=intent("synthetic-image-a",bytesA);val b=intent("synthetic-image-b",bytesB)
        val other=a.copy(id=UUID.randomUUID().toString())
        private val wave=LocalPcmAudio.encode(shortArrayOf(1,-2))
        val audioIntent=AttachmentIntent(UUID.randomUUID().toString(),a.entryId,1,"audio/wav",MessageDigest.getInstance("SHA-256").digest(wave).joinToString(""){"%02x".format(it.toInt() and 255)},wave.size.toLong())
        private val local=LocalRecordUiState(ready=true,opening=false,entries=listOf(StoredEntry(a.entryId,1,"合成A",5,"UTC",ProcessingPolicy.LOCAL_ONLY),StoredEntry(b.entryId,1,"合成B",5,"UTC",ProcessingPolicy.LOCAL_ONLY)))
        val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>();val gate=CaptureDraftGate()
        var beforeARead:suspend()->Unit={};var lastABytes:ByteArray?=null;var aDecodes=0
        private val media=object:AttachmentStore {
            override suspend fun save(intent:AttachmentIntent,source:()->java.io.InputStream)=error("No image mutation")
            override suspend fun list(entryId:String,revision:Int)=listOf(a,b,other,audioIntent).filter{it.entryId==entryId&&it.revision==revision}.map{LocalAttachment(it,AttachmentState.READY)}
            override suspend fun read(id:String):ByteArray {
                if(id==a.id){beforeARead();return bytesA.copyOf().also{lastABytes=it}}
                if(id==audioIntent.id)return wave.copyOf()
                check(id==b.id);return bytesB.copyOf()
            }
            override suspend fun recover(){}
        }
        val owner=object:EntryRepository {
            override val attachments=media
            override suspend fun entries()=local.entries;override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun readDraft():TextDraft?=null;override suspend fun updateDraft(text:String)=error("No draft");override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("No capture")
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("No original write")
        }
        val controller=LocalAttachmentController({queue.add(it);true},{bytes->if(bytes.contentEquals(bytesA))aDecodes++;object:AttachmentPicture{override val width=1;override val height=1}},canUsePanel={it.hasRecordAccess(local)},sourceUi={gate.sourceUi(it)})
        var outputs=0
        val audio=OriginalAudioController({local},{controller.state.value},{gate},{queue.add(it);true},{outputs++;object:OriginalAudioOutput {
            override fun focus()=true;override fun open(){};override fun play(){}
            override fun write(samples:ShortArray,count:Int)=count;override fun playedFrames()=0L;override fun close(){}
        }},{true}).also{it.foreground(true)}
        suspend fun next(){queue.poll()!!(owner)}
        suspend fun drain(){while(true)(queue.poll()?:break)(owner)}
        override fun close(){audio.stop();runBlocking{drain()};controller.markDisposed();runBlocking{controller.disposeOnIo()}}
        private fun intent(entry:String,bytes:ByteArray)=AttachmentIntent(UUID.randomUUID().toString(),entry,1,"image/png",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)},bytes.size.toLong())
    }
}
