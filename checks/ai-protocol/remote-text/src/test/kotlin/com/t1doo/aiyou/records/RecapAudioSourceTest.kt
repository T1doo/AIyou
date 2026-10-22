package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real SQL recap proof + encrypted media + transcript authentication, no hardware output. */
class RecapAudioSourceTest {
    @Test fun exactAudioIsAuthenticatedWithoutCreatingTranscriptOrChangingOriginal():Unit=runBlocking {
        Harness().use { h->
            h.generate();val snapshot=h.ui.snapshot!!;val card=snapshot.cards.single();var opened:AudioTranscriptSource?=null
            h.c.audioSource(card,card.audios.single()){opened=it};assertNull(opened);h.drain()
            assertEquals(h.intent,opened!!.intent);assertEquals(441,opened!!.samples)
            assertEquals(card.source,opened!!.source);assertSame(snapshot,h.ui.snapshot)
            assertTrue(h.f.db.read("SELECT * FROM local_candidate_batches").isEmpty())
            assertArrayEquals(h.wave,h.f.media.read(h.intent.id));assertEquals("合成回顾原音",h.store.entries().single().originalText)
        }
    }
    @Test fun copiedCardAudioAndNonReadyLedgerCannotRequestMediaOrOpen():Unit=runBlocking {
        Harness().use { h->
            h.generate();val card=h.ui.snapshot!!.cards.single();val audio=card.audios.single();var opens=0
            h.c.audioSource(card.copy(),audio){opens++};h.c.audioSource(card,audio.copy()){opens++};h.drain()
            assertEquals(0,h.reads);assertEquals(0,opens)
            h.f.db.write("UPDATE attachments SET state='FAILED' WHERE id=?",h.intent.id)
            h.c.generate();h.drain();val failed=h.ui.snapshot!!.cards.single()
            h.c.audioSource(failed,failed.audios.single()){opens++};h.drain();assertEquals(0,h.reads);assertEquals(0,opens)
        }
    }
    @Test fun corruptEncryptedOriginalRetiresRecapWithoutPublishingOrReplacingBytes():Unit=runBlocking {
        Harness().use { h->
            h.generate();val card=h.ui.snapshot!!.cards.single();val file=h.f.mediaRoot.walkTopDown().first{it.isFile}
            val corrupt=file.readBytes().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()};file.writeBytes(corrupt)
            var opens=0;h.c.audioSource(card,card.audios.single()){opens++};h.drain()
            assertEquals(0,opens);assertNull(h.ui.snapshot);assertNotNull(h.ui.error);assertArrayEquals(corrupt,file.readBytes())
        }
    }
    @Test fun finalRecapProofRejectsSourceAndOtherRangeMutationsAfterMediaAuthentication():Unit=runBlocking {
        for(change in 0..4) Harness().use { h->
            h.generate();val card=h.ui.snapshot!!.cards.single();var opens=0
            h.afterPreview={when(change){
                0->h.f.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?",h.id)
                1->h.f.db.write("UPDATE remote_source_access SET deleted=1 WHERE entryId=?",h.id)
                2->h.f.revise(h.id,"合成已修订")
                3->h.f.entry(text="合成同范围新记录")
                else->h.f.tags.replace(ManualTagMutation(h.id,0,listOf("合成标签"),UUID.randomUUID().toString()))
            }}
            h.c.audioSource(card,card.audios.single()){opens++};h.drain()
            assertEquals(1,h.reads);assertEquals(0,opens);assertNull(h.ui.snapshot);assertNotNull(h.ui.error)
        }
    }
    @Test fun pauseCancelNewRangeAndRetiredSessionRejectLateAuthenticatedSource():Unit=runBlocking {
        for(change in 0..3) Harness().use {h->
            h.generate();val card=h.ui.snapshot!!.cards.single();var opens=0
            h.afterPreview={when(change){0->h.c.cancel();1->h.c.input("1970-01-02","UTC",FactRecapPeriod.WEEK);2->{h.c.close();h.c.open()};else->h.c.dispose()}}
            h.c.audioSource(card,card.audios.single()){opens++};h.drain()
            assertEquals(0,opens);assertNull(h.local.factRecap?.snapshot)
        }
    }
    @Test fun recapEditorRequiresExactLiveFocusedReaderAndCurrentSource():Unit=runBlocking {
        Harness().use {h->
            val a=h.port.preview(h.intent);val local=h.local
            val p=AttachmentUiState(1,h.id,1,loading=false,items=listOf(LocalAttachment(h.intent,AttachmentState.READY)),focusAudio=h.intent)
            assertTrue(local.canUseRecapTranscript(h.intent,p,a))
            assertFalse(local.canUseRecapTranscript(null,p,a));assertFalse(local.canUseRecapTranscript(h.intent,p,null))
            for(panel in listOf(p.copy(focusAudio=null),p.copy(entryId="other"),p.copy(revision=2),p.copy(historyOwner=AttachmentHistoryOwner(1,1,2)),p.copy(loading=true),p.copy(unavailable=true),p.copy(error="合成错误"),p.copy(items=emptyList())))
                assertFalse(local.canUseRecapTranscript(h.intent,panel,a))
            assertFalse(local.copy(entries=emptyList()).canUseRecapTranscript(h.intent,p,a))
            assertFalse(local.copy(entries=local.entries.map{it.copy(currentRevision=2)}).canUseRecapTranscript(h.intent,p,a))
            assertFalse(local.copy(error="合成读取失败").canUseRecapTranscript(h.intent,p,a))
        }
    }
    private class Harness:AutoCloseable {
        val f=FullFixture();val id=f.entry(text="合成回顾原音")
        val wave=LocalPcmAudio.encode(ShortArray(441){(it%11).toShort()})
        val intent=AttachmentIntent(UUID.randomUUID().toString(),id,1,"audio/wav",java.security.MessageDigest.getInstance("SHA-256").digest(wave).joinToString(""){"%02x".format(it.toInt() and 255)},wave.size.toLong())
        var afterPreview:()->Unit={};var reads=0
        private val sql=SqlAudioTranscriptRepository(f.gateway,f.key,f.media)
        val port=object:AudioTranscriptRepository by sql {override suspend fun preview(intent:AttachmentIntent):AudioTranscriptSource {reads++;return sql.preview(intent).also{afterPreview()}}}
        val store=object:EntryRepository {
            override val factRecaps=SqlFactRecapRepository(f.gateway,f.tags)
            override val audioTranscripts=port
            override val attachments=f.media
            override suspend fun entries()=f.db.read("SELECT e.id,e.currentRevision,r.originalText,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision").map{StoredEntry(it[0] as String,(it[1] as Long).toInt(),it[2] as String,it[3] as Long,it[4] as String,ProcessingPolicy.valueOf(it[5] as String))}
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("No draft")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("No draft")
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("No edit")
        }
        var local=runBlocking{f.media.save(intent){wave.inputStream()};LocalRecordUiState(ready=true,opening=false,supportsFactRecaps=true,supportsAudioTranscripts=true,manualTagsAvailable=true,entries=store.entries())}
        val queue=ArrayDeque<suspend(EntryRepository)->Unit>()
        val c=FactRecapController({local},{local=it(local)},{queue.add(it);true},Clock.fixed(Instant.ofEpochMilli(5),ZoneId.of("UTC")))
        val ui get()=checkNotNull(local.factRecap)
        suspend fun generate(){c.open();c.generate();drain()}
        suspend fun drain(){while(queue.isNotEmpty())queue.removeFirst()(store)}
        override fun close(){c.dispose();sql.wipeKey();f.close()}
    }
}
