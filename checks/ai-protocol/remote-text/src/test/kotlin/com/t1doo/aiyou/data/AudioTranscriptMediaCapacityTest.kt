package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real encrypted files/SQL, synthetic 60-second PCM. No audio device or ASR. */
class AudioTranscriptMediaCapacityTest {
    @Test(timeout=120_000) fun savedAndRestoredTranscriptsRemainSearchableAboveIndependentBackupMediaLimit():Unit=runBlocking {
        val wave=LocalPcmAudio.encode(ShortArray(LocalPcmAudio.MAX_SAMPLES){(it%17).toShort()})
        assertEquals(5_292_044,wave.size)
        FullFixture().use { source -> FullFixture(97).use { target ->
            val sourceMedia=OneClipMedia(source.media)
            val repo=SqlAudioTranscriptRepository(source.gateway,source.key,sourceMedia)
            val targetMedia=OneClipMedia(target.media)
            val destination=SqlAudioTranscriptRepository(target.gateway,target.key,targetMedia)
            try {
                val first=add(source,repo,wave,0)
                // A legal v11 backup restores the transcript and exact original under another key.
                val portable=AudioTranscriptSqlTest.export(source)
                assertEquals("AIYOUF11",String(portable.copyOfRange(0,8),Charsets.US_ASCII))
                val saved=mutableListOf(first)
                repeat(12){saved+=add(source,repo,wave,it+1)}
                assertEquals(13L,source.db.read("SELECT count(*) FROM local_candidate_batches WHERE ruleVersion=4 AND state='READY'").single().single())
                assertTrue(saved.sumOf{it.anchor.intent.byteSize}>RecordMediaPackages.MAX_IMAGE_TOTAL)
                assertTrue((source.db.read("SELECT sum(length(payload)) FROM local_candidate_batches WHERE ruleVersion=4").single().single() as Long) < 4_194_304)
                sourceMedia.reset()
                val page=repo.search("合成跨界稿")
                assertEquals(saved.map{it.id}.toSet(),page.items.map{it.id}.toSet())
                assertEquals(13,sourceMedia.reads);assertEquals(13L*LocalPcmAudio.MAX_WAV_BYTES,sourceMedia.bytes)
                sourceMedia.assertLastWiped()
                val withdrawn=repo.write(AudioTranscriptSqlTest.write(saved.last().anchor,saved.last(),withdraw=true))
                assertTrue(withdrawn.withdrawn)
                // All 13 READY anchors still exist, including the withdrawn transcript.
                assertEquals(12,repo.search("合成跨界稿").items.size)
                assertTrue(repo.search("合成跨界稿 12").items.isEmpty())
                assertFalse(repo.resolve(page,page.items.first()){fail("Changed transcript proof")})
                assertEquals(12,repo.search("").items.size)
                // The existing full-backup bound is independent and unchanged. No import intent
                // or data loss is introduced to make this larger live collection searchable.
                val receiptBefore=source.db.read("SELECT * FROM record_package_receipts ORDER BY id")
                assertThrows(IllegalArgumentException::class.java){runBlocking{source.backup.capture(AudioTranscriptSqlTest.uuid(),200)}}
                assertEquals(receiptBefore,source.db.read("SELECT * FROM record_package_receipts ORDER BY id"))
                assertTrue(repo.read(first.anchor)!!.same(first))
                val pack=target.backup.open({portable.inputStream()},FullBackupTest.password())
                try {
                    assertFalse(target.backup.restore(pack){true}.replayed)
                    assertTrue(destination.read(first.anchor)!!.same(first))
                    assertArrayEquals(wave,target.media.read(first.anchor.intent.id))
                    // Continuing normal saves after a valid restore may cross the same threshold.
                    repeat(12){add(target,destination,wave,it+20)}
                    targetMedia.reset()
                    val restoredPage=destination.search("合成跨界稿")
                    assertEquals(13,restoredPage.items.size);assertEquals(13,targetMedia.reads);targetMedia.assertLastWiped()
                    val restoredFirst=destination.read(first.anchor)!!
                    destination.write(AudioTranscriptSqlTest.write(first.anchor,restoredFirst,withdraw=true))
                    assertEquals(12,destination.search("合成跨界稿").items.size)
                    assertThrows(IllegalArgumentException::class.java){runBlocking{target.backup.capture(AudioTranscriptSqlTest.uuid(),201)}}
                    assertTrue(target.backup.restore(pack){true}.replayed)
                    assertTrue(destination.read(first.anchor)!!.withdrawn)
                    assertEquals(13L,target.db.read("SELECT count(*) FROM local_candidate_batches WHERE ruleVersion=4 AND state='READY'").single().single())
                } finally {pack.release()}
            } finally {repo.wipeKey();destination.wipeKey()}
        } }
        wave.fill(0)
    }
    private suspend fun add(f:FullFixture,repo:SqlAudioTranscriptRepository,wave:ByteArray,index:Int):AudioTranscriptSnapshot {
        val id=f.entry(text="合成原音容量边界记录 $index")
        val intent=AttachmentIntent(AudioTranscriptSqlTest.uuid(),id,1,"audio/wav",FullBackupTest.hash(wave),wave.size.toLong())
        f.media.save(intent){wave.inputStream()}
        val anchor=repo.preview(intent)
        return repo.write(AudioTranscriptSqlTest.write(anchor,text="合成跨界稿 $index",at=10+index.toLong()))
    }
    private class OneClipMedia(private val delegate:AttachmentStore):AttachmentStore by delegate {
        private var returned:ByteArray?=null
        var reads=0;private set
        var bytes=0L;private set
        fun assertLastWiped(){returned?.let{check(it.all{byte->byte==0.toByte()}){"Previous authenticated audio buffer was not wiped"}}}
        fun reset(){assertLastWiped();returned=null;reads=0;bytes=0}
        override suspend fun read(id:String):ByteArray {
            assertLastWiped()
            return delegate.read(id).also{check(it.size<=LocalPcmAudio.MAX_WAV_BYTES);returned=it;reads++;bytes+=it.size}
        }
    }
}
