package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AudioTranscriptPlaybackSqlTest {
    @Test(timeout=15000) fun mediaReadDoesNotHoldGateMonitorAndFinalSqlFailureWipesBorrowedWave():Unit=runBlocking {
        FullFixture().use {f->
            val g=f.capture.mutationGate;var armed=false;var borrowed:ByteArray?=null
            val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
            val media=object:AttachmentStore by f.media {override suspend fun read(id:String):ByteArray {
                val b=f.media.read(id)
                if(armed){borrowed=b;entered.complete(Unit);check(release.await(4,TimeUnit.SECONDS))}
                return b
            }}
            val repo=SqlAudioTranscriptRepository(f.gateway,f.key,media,g)
            try {
                val a=repo.preview(AudioTranscriptSqlTest.audio(f));val saved=repo.write(AudioTranscriptWrite(FullBackupTest.uuid(),a,null,"合成原音",false,FullBackupTest.uuid(),10,AudioSampleRange(1,4)))
                val ref=repo.playbackReference(saved);val pin=g.preparePlayback()!!;g.activatePlayback(pin);armed=true
                try {
                    val work=async(Dispatchers.IO){runCatching{repo.playback(ref,pin)}}
                    try {
                        withTimeout(3000){entered.await()}
                        assertTrue(withTimeout(1000){async(Dispatchers.Default){g.isPlaybackActive(pin)}.await()})
                        // Adversarial external SQL corruption, not a permitted in-app source mutation.
                        f.db.write("UPDATE attachments SET state='CORRUPT' WHERE id=?",a.intent.id)
                    }finally{release.countDown()}
                    assertTrue(withTimeout(3000){work.await()}.isFailure)
                    assertTrue(borrowed!!.all{it==0.toByte()})
                }finally{g.finishPlayback(pin)}
            }finally{release.countDown();repo.wipeKey()}
        }
    }
    @Test fun privateReferenceRequiresItsActualRepositoryAndActiveOwnerPinAndReturnsOnlyAuthenticatedRange():Unit=runBlocking {
        FullFixture().use {f->
            val gate=f.capture.mutationGate
            val repo=SqlAudioTranscriptRepository(f.gateway,f.key,OwnerAttachmentStore(f.media,gate),gate)
            val other=SqlAudioTranscriptRepository(f.gateway,f.key,f.media,gate)
            try {
                val a=repo.preview(AudioTranscriptSqlTest.audio(f))
                val saved=repo.write(AudioTranscriptWrite(FullBackupTest.uuid(),a,null,"合成片段",false,FullBackupTest.uuid(),10,AudioSampleRange(1,3)))
                val ref=repo.playbackReference(saved)
                assertTrue(repo.ownsPlaybackReference(ref));assertFalse(other.ownsPlaybackReference(ref))
                val pin=gate.preparePlayback()!!
                assertThrows(AudioTranscriptUnavailable::class.java){runBlocking{repo.playback(ref,pin)}}
                gate.activatePlayback(pin)
                val forged=object:AudioTranscriptPlaybackReference {override val anchor=a;override val range=AudioSampleRange(0,4)}
                assertFalse(repo.ownsPlaybackReference(forged))
                assertThrows(AudioTranscriptConflict::class.java){runBlocking{repo.playback(forged,pin)}}
                assertThrows(AudioTranscriptUnavailable::class.java){runBlocking{other.playback(ref,pin)}}
                val foreign=CaptureDraftGate();val foreignPin=foreign.preparePlayback()!!;foreign.activatePlayback(foreignPin)
                try{assertThrows(AudioTranscriptUnavailable::class.java){runBlocking{repo.playback(ref,foreignPin)}}}finally{foreign.finishPlayback(foreignPin)}
                val read=repo.playback(ref,pin)
                try {assertEquals(AudioSampleRange(1,3),read.range);assertArrayEquals(CaptureAudioSqlTest.wave(),read.wave)}finally{read.wave.fill(0)}
                assertThrows(AudioTranscriptUnavailable::class.java){runBlocking{repo.write(AudioTranscriptSqlTest.write(a,saved,"播放中不可改"))}}
                gate.finishPlayback(pin)
                assertThrows(AudioTranscriptUnavailable::class.java){runBlocking{repo.playback(ref,pin)}}
                assertTrue(repo.read(a)!!.same(saved))
                repo.wipeKey();assertFalse(repo.ownsPlaybackReference(ref))
            }finally{repo.wipeKey();other.wipeKey()}
        }
    }
    @Test fun rangeOnlyAbaWithdrawalSourceChangesAndCorruptionCannotReviveAnIssuedReference():Unit=runBlocking {
        for(kind in listOf("range-aba","withdraw","revision","policy-aba","attachment","corrupt"))FullFixture().use {f->
            val g=f.capture.mutationGate;val repo=SqlAudioTranscriptRepository(f.gateway,f.key,f.media,g)
            try {
                val a=repo.preview(AudioTranscriptSqlTest.audio(f))
                val saved=repo.write(AudioTranscriptWrite(FullBackupTest.uuid(),a,null,"合成片段",false,FullBackupTest.uuid(),10,AudioSampleRange(1,4)))
                val ref=repo.playbackReference(saved)
                when(kind) {
                    "range-aba"->{val b=repo.write(AudioTranscriptWrite(saved.id,a,saved,saved.current.text,false,FullBackupTest.uuid(),11,AudioSampleRange(2,4)));repo.write(AudioTranscriptWrite(b.id,a,b,b.current.text,false,FullBackupTest.uuid(),12,saved.current.range))}
                    "withdraw"->repo.write(AudioTranscriptSqlTest.write(a,saved,withdraw=true))
                    "revision"->f.revise(a.intent.entryId,"修订原文")
                    "policy-aba"->{val policies=SqlLocalProcessingRepository(f.gateway,g);val off=policies.change(LocalProcessingChange(policies.read(a.source),LocalProcessingChoice.AI_NONE,FullBackupTest.uuid()));policies.change(LocalProcessingChange(off,LocalProcessingChoice.LOCAL_ONLY,FullBackupTest.uuid()))}
                    "attachment"->f.db.write("DELETE FROM attachments WHERE id=?",a.intent.id)
                    else->{val b=f.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",saved.id).single().single() as ByteArray;b[b.lastIndex]=(b.last().toInt() xor 1).toByte();f.db.write("UPDATE local_candidate_batches SET version=version+1,payload=? WHERE id=?",b,saved.id)}
                }
                val pin=g.preparePlayback()!!;g.activatePlayback(pin)
                try{assertThrows(Exception::class.java){runBlocking{repo.playback(ref,pin)}}}finally{g.finishPlayback(pin)}
                assertThrows(Exception::class.java){runBlocking{repo.playbackReference(saved)}}
            }finally{repo.wipeKey()}
        }
    }
}
