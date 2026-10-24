package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic PCM, real SQL/AEAD. No device, microphone, SAF or recognizer. */
class AudioTranscriptRangeSqlTest {
    @Test fun savedRangeAndRangeOnlyCorrectionAreVersionedAndRetireOldQuery(): Unit = runBlocking {
        FullFixture().use { f ->
            val intent = AudioTranscriptSqlTest.audio(f)
            val repo = SqlAudioTranscriptRepository(f.gateway, f.key, f.media)
            try {
                val a = repo.preview(intent)
                val request = AudioTranscriptWrite(AudioTranscriptSqlTest.uuid(),a,null,"合成片段",false,AudioTranscriptSqlTest.uuid(),10,AudioSampleRange(1,3))
                val saved = repo.write(request)
                assertEquals(request.range, saved.current.range)
                val sealed=f.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",saved.id).single().single() as ByteArray
                assertTrue(repo.read(a)!!.same(saved))
                val page = repo.search("片段")
                assertArrayEquals(sealed,f.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",saved.id).single().single() as ByteArray)
                val corrected = repo.write(AudioTranscriptWrite(saved.id,a,saved,saved.current.text,false,AudioTranscriptSqlTest.uuid(),11,AudioSampleRange(2,4)))
                assertEquals(2,corrected.history.size);assertEquals(AudioSampleRange(1,3),corrected.history.first().range)
                assertEquals(AudioSampleRange(2,4),corrected.current.range)
                assertFalse(repo.resolve(page,page.items.single()) { fail("Old range proof") })
                val inherited=repo.write(AudioTranscriptSqlTest.write(a,corrected,"改字仍保留人工范围"))
                assertEquals(corrected.current.range,inherited.current.range)
                val whole=repo.write(AudioTranscriptWrite(inherited.id,a,inherited,inherited.current.text,false,AudioTranscriptSqlTest.uuid(),12,null))
                assertNull(whole.current.range);assertEquals(4,whole.history.size)
                val doc=f.backup.capture(AudioTranscriptSqlTest.uuid(),100) as FullBackupData
                try {assertEquals(13,doc.version);assertEquals(2,TranscriptEncoding.portableVersion(doc.tables[8].single()[10] as ByteArray))}finally{doc.release()}
                val cold=SqlAudioTranscriptRepository(f.gateway,f.key,f.media)
                try {assertTrue(cold.read(a)!!.same(whole))}finally{cold.wipeKey()}
            } finally { repo.wipeKey() }
        }
    }
    @Test fun actualLostCommitReceiptsBindRangeAndEveryExpectedHistoricalVersion():Unit=runBlocking {
        FullFixture().use {f->
            val repo=SqlAudioTranscriptRepository(f.gateway,f.key,f.media)
            try {
                val a=repo.preview(AudioTranscriptSqlTest.audio(f))
                suspend fun lost(request:AudioTranscriptWrite):AudioTranscriptSnapshot {
                    var wrote=false;var dropped=false
                    f.db.beforeSql={if(it.startsWith("INSERT INTO local_candidate_batches")||it.startsWith("UPDATE local_candidate_batches SET version="))wrote=true}
                    f.db.afterSql={if(it=="COMMIT"&&wrote&&!dropped){dropped=true;error("Synthetic committed range ACK loss")}}
                    try {assertThrows(AudioTranscriptUnavailable::class.java){runBlocking{repo.write(request)}}}finally{f.db.beforeSql=null;f.db.afterSql=null}
                    assertTrue(dropped)
                    val before=f.db.read("SELECT version,lastMutationId,payload FROM local_candidate_batches").single()
                    val result=repo.write(request)
                    val after=f.db.read("SELECT version,lastMutationId,payload FROM local_candidate_batches").single()
                    assertEquals(before.take(2),after.take(2));assertArrayEquals(before[2] as ByteArray,after[2] as ByteArray)
                    return result
                }
                val create=AudioTranscriptWrite(AudioTranscriptSqlTest.uuid(),a,null,"合成同意图",false,AudioTranscriptSqlTest.uuid(),10,AudioSampleRange(0,1))
                val one=lost(create)
                assertThrows(AudioTranscriptConflict::class.java){runBlocking{repo.write(AudioTranscriptWrite(create.id,a,null,create.text,false,create.mutationId,10,AudioSampleRange(0,2)))}}
                val correction=AudioTranscriptWrite(one.id,a,one,one.current.text,false,AudioTranscriptSqlTest.uuid(),11,AudioSampleRange(1,4))
                val two=lost(correction);assertEquals(2,two.history.size)
                assertThrows(AudioTranscriptConflict::class.java){runBlocking{repo.write(AudioTranscriptWrite(two.id,a,one,one.current.text,false,correction.mutationId,11,AudioSampleRange(2,4)))}}
                val withdrawal=AudioTranscriptSqlTest.write(a,two,withdraw=true)
                val withdrawn=lost(withdrawal);assertTrue(withdrawn.withdrawn);assertEquals(two.history,withdrawn.history)
                // A forged old history cannot replay the same withdrawal receipt.
                val forged=AudioTranscriptSnapshot(two.id,a,two.version,false,two.history.mapIndexed{n,v->if(n==0)v.copy(range=AudioSampleRange(0,2))else v})
                assertThrows(AudioTranscriptConflict::class.java){runBlocking{repo.write(AudioTranscriptWrite(two.id,a,forged,null,true,withdrawal.mutationId,withdrawal.at))}}
                assertTrue(repo.search("").items.isEmpty())
                val reentered=repo.write(AudioTranscriptSqlTest.write(a,withdrawn,"显式重新填写"))
                assertEquals(AudioSampleRange(1,4),reentered.current.range)
                assertThrows(IllegalArgumentException::class.java){AudioTranscriptWrite(one.id,a,one,"越源",false,AudioTranscriptSqlTest.uuid(),12,AudioSampleRange(0,5))}
            }finally{repo.wipeKey()}
        }
    }
    @Test fun rangedHistoryStillRetiresForRevisionTrashPolicyAbaAndExactAttachmentLoss():Unit=runBlocking {
        for(mode in 0..3)FullFixture().use {f->
            val repo=SqlAudioTranscriptRepository(f.gateway,f.key,f.media)
            try {
                val i=AudioTranscriptSqlTest.audio(f);val a=repo.preview(i)
                val one=repo.write(AudioTranscriptWrite(AudioTranscriptSqlTest.uuid(),a,null,"合成片段",false,AudioTranscriptSqlTest.uuid(),10,AudioSampleRange(1,4)))
                val page=repo.search("片段")
                when(mode) {
                    0->f.revise(i.entryId,"新原文")
                    1->f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",i.entryId)
                    2->{val policies=SqlLocalProcessingRepository(f.gateway);val off=policies.change(LocalProcessingChange(policies.read(a.source),LocalProcessingChoice.AI_NONE,AudioTranscriptSqlTest.uuid()));policies.change(LocalProcessingChange(off,LocalProcessingChoice.LOCAL_ONLY,AudioTranscriptSqlTest.uuid()))}
                    else->f.db.write("DELETE FROM attachments WHERE id=?",i.id)
                }
                assertThrows(AudioTranscriptConflict::class.java){runBlocking{repo.read(a)}}
                assertThrows(AudioTranscriptConflict::class.java){runBlocking{repo.write(AudioTranscriptSqlTest.write(a,one,"迟到修改"))}}
                var published=false;runCatching{repo.resolve(page,page.items.single()){published=true}};assertFalse(published)
            }finally{repo.wipeKey()}
        }
    }
    @Test fun legacyWholePayloadAndReceiptKeepFrozenBytesWithoutReadRewrites(): Unit = runBlocking {
        FullFixture().use { f ->
            val id = f.entry("legacy-transcript-source", "合成旧原文")
            val wave = CaptureAudioSqlTest.wave()
            val intent = AttachmentIntent("00000000-0000-4000-8000-000000000001", id, 1,
                "audio/wav", FullBackupTest.hash(wave), wave.size.toLong())
            f.media.save(intent) { wave.inputStream() }
            val repo = SqlAudioTranscriptRepository(f.gateway, f.key, f.media)
            try {
                val anchor = repo.preview(intent)
                val request = AudioTranscriptWrite("00000000-0000-4000-8000-000000000002", anchor, null,
                    "合成旧稿🙂", false, "00000000-0000-4000-8000-000000000003", 10)
                val saved = repo.write(request)
                val row = f.db.read("SELECT lastMutationId,payload FROM local_candidate_batches WHERE id=?", saved.id).single()
                assertEquals("84e7f131a3d8214c39307deb485eeb393a2e44353e8948ff40370638f7c63491", row[0])
                val plain = TranscriptEncoding.encode(TranscriptPayload(anchor, saved.history, false, 0, 10))
                try { assertEquals("31a4d3d2768b776b03742576aa509fca5b58b46d76c91d68060e67c049a2d6bc", FullBackupTest.hash(plain)) }
                finally { plain.fill(0) }
                assertTrue(repo.read(anchor)!!.same(saved)); assertEquals(saved.id, repo.search("旧稿").items.single().id)
                assertArrayEquals(row[1] as ByteArray, f.db.read("SELECT payload FROM local_candidate_batches WHERE id=?", saved.id).single().single() as ByteArray)
            } finally { repo.wipeKey() }
        }
    }
}
