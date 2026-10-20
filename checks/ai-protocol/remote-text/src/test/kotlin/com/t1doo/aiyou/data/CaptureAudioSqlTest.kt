package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CaptureAudioSqlTest {
    @Test fun canonicalWaveMatchesIndependentRiffFixtureAndRejectsMalformedFrames() {
        val expected="524946462800000057415645666d7420100000000100010044ac0000885801000200100064617461040000003412feff"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected,LocalPcmAudio.encode(shortArrayOf(0x1234,-2)))
        assertEquals(2,LocalPcmAudio.inspect(expected).samples)
        listOf(0,4,8,12,16,20,22,24,28,32,34,36,40).forEach { at ->
            val bad=expected.copyOf();bad[at]=(bad[at].toInt() xor 1).toByte()
            assertThrows(IllegalArgumentException::class.java) { LocalPcmAudio.inspect(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { LocalPcmAudio.inspect(expected+byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { LocalPcmAudio.encode(shortArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { LocalPcmAudio.encode(ShortArray(LocalPcmAudio.MAX_SAMPLES+1)) }
    }
    @Test fun strictPeekNeverCreatesAndAudioIsEncryptedBeforePureAudioRecordCommit():Unit=runBlocking {
        FullFixture().use { f ->
            val repo=f.capture;assertNull(repo.peek());assertTrue(f.db.read("SELECT id FROM record_package_receipts").isEmpty())
            val base=repo.current();val wave=wave();val r=reserve(repo,base,wave)
            val ready=repo.addAudio(r,wave)
            assertEquals(base.version+2,ready.version);assertEquals(CaptureAudioState.READY,ready.audio!!.state)
            assertArrayEquals(wave,repo.audio(ready));assertTrue(f.db.read("SELECT id FROM entries").isEmpty())
            assertTrue(f.db.read("SELECT id FROM attachments").isEmpty())
            val ciphertext=f.mediaRoot.resolve(ready.audio!!.intent.id+".aead").readBytes()
            assertFalse(ciphertext.toList().windowed(wave.size).any { it==wave.toList() })
            assertTrue(CaptureDraftCodec.decode(CaptureDraftCodec.encode(ready)).sameSnapshot(ready))
            repo.mutationGate.finish(r,ready)
            val result=repo.save(ready,100,"UTC");assertEquals(base.id,result.id)
            assertTrue(repo.save(ready,200,"UTC").replayed)
            assertEquals(1,f.db.read("SELECT id FROM entries").size)
            assertEquals("audio/wav",f.db.read("SELECT mediaType FROM attachments").single().single())
            assertArrayEquals(wave,f.media.read(ready.audio!!.intent.id))
        }
    }
    @Test fun publishedUnknownReadbackUsesOnlyExactSuccessorAndPreservesFrozenFields():Unit=runBlocking {
        FullFixture().use { f ->
            val repo=f.capture;val base=repo.edit(repo.current(),"合成原始文字")
            val r=reserve(repo,base,wave());f.mediaCheckpoint={if(it=="capture-published")error("synthetic publication ACK loss")}
            fails { repo.addAudio(r,wave()) }
            val writing=repo.peek()!!;assertEquals(CaptureAudioState.WRITING,writing.audio!!.state)
            fails { repo.edit(writing,"绕过 UI") };fails { repo.discard(base.id) };fails { repo.recover() }
            f.mediaCheckpoint={};val ready=repo.reconcileAudio(r)
            assertEquals(base.text,ready.text);assertEquals(base.version+2,ready.version)
            assertArrayEquals(wave(),repo.audio(ready));repo.mutationGate.finish(r,ready)
            assertEquals(ready.version+1,repo.edit(ready,"确认后编辑").version)
        }
    }
    @Test fun otherFieldMutationCannotMasqueradeAsOwnAudioSuccessor():Unit=runBlocking {
        FullFixture().use { f ->
            val repo=f.capture;val base=repo.current();val r=reserve(repo,base,wave())
            f.mediaCheckpoint={if(it=="capture-published")error("synthetic lost ACK")}
            fails { repo.addAudio(r,wave()) };f.mediaCheckpoint={}
            val writing=repo.peek()!!
            val foreign=CaptureDraft(writing.id,writing.version,"外部合成改动",writing.images,writing.discarding,writing.policy,writing.audio)
            f.db.transaction { it.execute("UPDATE record_package_receipts SET state=? WHERE id='CAPTURE_CURRENT'",listOf(CaptureDraftCodec.encode(foreign))) }
            fails { repo.addAudio(r,wave()) };fails { repo.reconcileAudio(r) }
            assertTrue(repo.mutationGate.frozen);assertEquals(foreign.text,repo.peek()!!.text)
            repo.mutationGate.retire()
        }
    }
    @Test fun interruptedCiphertextBecomesKnownFailedThenExplicitRemovalIsReplaySafe():Unit=runBlocking {
        FullFixture().use { f ->
            val repo=f.capture;val base=repo.current();val r=reserve(repo,base,wave())
            f.mediaCheckpoint={if(it=="created")error("synthetic incomplete ciphertext")}
            fails { repo.addAudio(r,wave()) };f.mediaCheckpoint={}
            val failed=repo.reconcileAudio(r);assertEquals(CaptureAudioState.FAILED,failed.audio!!.state)
            repo.mutationGate.finish(r,failed);fails { repo.audio(failed) }
            val removed=repo.removeAudio(failed);assertNull(removed.audio)
            assertTrue(repo.removeAudio(failed).sameSnapshot(removed))
            assertTrue(f.mediaRoot.listFiles().orEmpty().none { it.name.endsWith(".aead") })
        }
    }
    @Test fun preFreezePermitDrainsButLaterAndClosedPermitsCannotWrite():Unit=runBlocking {
        FullFixture().use { f ->
            val repo=f.capture;val base=repo.current();val gate=repo.mutationGate
            val permit=gate.admit()!!;val request=gate.freeze(base.id)!!
            fails { gate.reserve(request,base,"合成排队文字",1) }
            val edited=gate.context(permit) { repo.edit(base,"合成排队文字") };permit.close()
            gate.reserve(request,edited,edited.text,0)
            var callbackEntered=false
            fails { gate.context(permit) { callbackEntered=true;repo.edit(edited,"旧回调") } }
            assertFalse(callbackEntered)
            fails { repo.changePolicy(edited,ProcessingPolicy.AI_NONE) }
            gate.finish(request,repo.peek()!!);assertEquals(edited.text,repo.peek()!!.text)
        }
    }
    @Test fun retiredQueuedPermitCannotEnterAnotherWriteButExistingDraftCanBeRead():Unit=runBlocking {
        FullFixture().use { f ->
            val repo=f.capture;val base=repo.current();val permit=repo.mutationGate.admit()!!
            repo.mutationGate.retire();var entered=false
            fails { repo.mutationGate.context(permit) { entered=true;repo.edit(base,"迟到") } }
            assertFalse(entered);assertTrue(base.sameSnapshot(repo.current()));permit.close()
        }
    }
    private suspend fun fails(action:suspend()->Any?) { var thrown=false;try { action() } catch(_:Exception) { thrown=true };assertTrue("Expected rejection",thrown) }
    companion object {
        fun wave()=LocalPcmAudio.encode(shortArrayOf(0x1234,-2,1000,-1000))
        internal fun reserve(repo:SqlCaptureRepository,base:CaptureDraft,wave:ByteArray):CaptureDraftGate.Reservation {
            val gate=repo.mutationGate;val r=gate.freeze(base.id)!!;gate.reserve(r,base,base.text,0)
            gate.bindIntent(r,AttachmentIntent(UUID.randomUUID().toString(),base.id,1,"audio/wav",
                MessageDigest.getInstance("SHA-256").digest(wave).joinToString(""){"%02x".format(it)},wave.size.toLong()))
            return r
        }
    }
}
