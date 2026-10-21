package com.t1doo.aiyou.data
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID
class AudioTranscriptSqlTest {
    @Test fun authenticatedOriginalSeparateEncryptedCorrectionWithdrawalAndColdReopen():Unit=runBlocking{FullFixture().use{f->
        val i=audio(f);var r=SqlAudioTranscriptRepository(f.gateway,f.key,f.media,f.capture.mutationGate)
        val a=r.preview(i);val original=f.media.read(i.id);val rawBefore=f.db.read("SELECT originalText,contentHash FROM entry_revisions")
        var s=r.write(write(a,text="合成人工稿：偏好淡茶🙂"));assertEquals(1L,s.version)
        val cipher=f.db.read("SELECT payload FROM local_candidate_batches WHERE ruleVersion=4").single().single() as ByteArray
        assertFalse(String(cipher,Charsets.ISO_8859_1).contains("偏好淡茶"));r.wipeKey();r=SqlAudioTranscriptRepository(f.gateway,f.key,f.media,f.capture.mutationGate)
        assertTrue(r.read(a)!!.same(s));val old=r.search("淡茶");assertEquals(s.id,old.items.single().id)
        s=r.write(write(a,s,"合成人工纠正：清水"));assertEquals(2,s.history.size);assertTrue(r.search("淡茶").items.isEmpty());assertEquals(s.id,r.search("清水").items.single().id)
        assertFalse(r.resolve(old,old.items.single()){fail("Old text proof")})
        val withdrawal=write(a,s,withdraw=true);val withdrawn=r.write(withdrawal);assertTrue(withdrawn.withdrawn);assertTrue(r.search("").items.isEmpty())
        assertThrows(AudioTranscriptConflict::class.java){runBlocking{r.write(write(a,s,"迟到旧稿"))}}
        val again=r.write(write(a,withdrawn,"显式重新填写"));assertEquals(3,again.history.size)
        assertArrayEquals(original,f.media.read(i.id));assertEquals(rawBefore,f.db.read("SELECT originalText,contentHash FROM entry_revisions"));r.wipeKey()
    }}
    @Test fun actualSqlLostCommitAcknowledgementRetriesSameIntentWithoutAppending():Unit=runBlocking{FullFixture().use{f->
        val i=audio(f);val r=SqlAudioTranscriptRepository(f.gateway,f.key,f.media);val a=r.preview(i);val request=write(a,text="合成一次写入")
        var wrote=false;var lost=false;f.db.beforeSql={if(it.startsWith("INSERT INTO local_candidate_batches"))wrote=true};f.db.afterSql={if(it=="COMMIT"&&wrote&&!lost){lost=true;error("Synthetic actual committed ACK loss")}}
        assertThrows(AudioTranscriptUnavailable::class.java){runBlocking{r.write(request)}};f.db.beforeSql=null;f.db.afterSql=null;assertTrue(lost)
        val before=f.db.read("SELECT version,payload FROM local_candidate_batches").single();val result=r.write(request)
        assertEquals(1L,result.version);assertEquals(1,result.history.size);assertArrayEquals(before[1] as ByteArray,f.db.read("SELECT payload FROM local_candidate_batches").single().single() as ByteArray)
        val gate=CaptureDraftGate();val pin=gate.preparePlayback()!!;gate.activatePlayback(pin)
        val locked=SqlAudioTranscriptRepository(f.gateway,f.key,f.media,gate)
        try{assertThrows(AudioTranscriptUnavailable::class.java){runBlocking{locked.write(write(a,result,"禁止播放中保存"))}}}finally{gate.finishPlayback(pin);locked.wipeKey();r.wipeKey()}
    }}
    @Test fun sourceRevisionTrashPolicyAbaAndMissingExactAudioRetireOldReferences():Unit=runBlocking{
        for(mode in 0..3)FullFixture().use{f->val i=audio(f);val r=SqlAudioTranscriptRepository(f.gateway,f.key,f.media);val a=r.preview(i);val s=r.write(write(a,text="合成旧原音稿"));val page=r.search("旧原音")
            when(mode){0->f.revise(i.entryId,"合成新原文");1->f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",i.entryId);2->{val p=SqlLocalProcessingRepository(f.gateway);val off=p.change(LocalProcessingChange(p.read(a.source),LocalProcessingChoice.AI_NONE,uuid()));p.change(LocalProcessingChange(off,LocalProcessingChoice.LOCAL_ONLY,uuid()))};else->f.db.write("DELETE FROM attachments WHERE id=?",i.id)}
            assertThrows(AudioTranscriptConflict::class.java){runBlocking{r.read(a)}}
            assertThrows(AudioTranscriptConflict::class.java){runBlocking{r.write(write(a,s,"迟到回写"))}}
            var opened=false;runCatching{r.resolve(page,page.items.single()){opened=true}};assertFalse(opened)
            if(mode<3){assertTrue(r.search("").items.isEmpty());assertEquals(listOf("INVALIDATED",0L),f.db.read("SELECT state,length(payload) FROM local_candidate_batches WHERE ruleVersion=4").single())}else assertThrows(AudioTranscriptConflict::class.java){runBlocking{r.search("")}}
            r.wipeKey()
        }
    }
    @Test fun v11RekeysHumanVersionsAndWithdrawalWithAudioAndPreservesRestoreReceipts():Unit=runBlocking{FullFixture().use{f->FullFixture(77).use{target->
        val i=audio(f);val r=SqlAudioTranscriptRepository(f.gateway,f.key,f.media);val a=r.preview(i)
        val activeIntent=audio(f);f.db.write("UPDATE entries SET privacyPolicy='REMOTE_ALLOWED' WHERE id=?",activeIntent.entryId);val activeAnchor=r.preview(activeIntent);r.write(write(activeAnchor,text="合成有效稿"))
        val one=r.write(write(a,text="合成第一稿"));val two=r.write(write(a,one,"合成纠正稿"));val withdrawn=r.write(write(a,two,withdraw=true))
        val bytes=export(f);assertEquals("AIYOUF11",String(bytes.copyOfRange(0,8),Charsets.US_ASCII));val pack=target.backup.open({bytes.inputStream()},FullBackupTest.password())
        try{var lost=false;target.checkpoint={if(it=="full-committed"&&!lost){lost=true;error("Synthetic v11 restore ACK")}};assertThrows(Exception::class.java){runBlocking{target.backup.restore(pack){true}}};target.checkpoint={};assertTrue(lost);assertTrue(target.backup.restore(pack){true}.replayed);assertTrue(target.backup.completed(pack))}finally{pack.release()}
        val dest=SqlAudioTranscriptRepository(target.gateway,target.key,target.media)
        assertTrue(dest.read(a)!!.same(withdrawn));assertTrue(dest.search("纠正稿").items.isEmpty());assertEquals(ProcessingPolicy.LOCAL_ONLY,dest.search("有效稿").items.single().anchor.policy);assertArrayEquals(f.media.read(i.id),target.media.read(i.id))
        assertEquals(1L,target.db.read("SELECT count(*) FROM record_package_receipts WHERE operation='FULL_BACKUP_IMPORT_V11'").single().single())
        val next=export(target);assertEquals("AIYOUF11",String(next.copyOfRange(0,8),Charsets.US_ASCII))
        assertThrows(RecordPackageInvalid::class.java){runBlocking{target.backup.open({(bytes+byteArrayOf(0)).inputStream()},FullBackupTest.password())}}
        val replay=target.backup.open({bytes.inputStream()},FullBackupTest.password())
        try{target.revise(i.entryId,"合成恢复后修订");assertTrue(target.backup.restore(replay){true}.replayed);assertEquals(2L,target.db.read("SELECT currentRevision FROM entries WHERE id=?",i.entryId).single().single())
            val bin=SqlRecycleBinRepository(target.gateway,target.tags,SqlErasureFileHooks::fenced);val deleted=bin.apply(RecycleMutation(SourceVersion(i.entryId,2),bin.read(i.entryId).generation,false,uuid()))
            SqlPermanentErasureRepository(target.gateway,target.media,target.edits,target.packages).erase(PermanentErasureIntent(SourceVersion(i.entryId,2),deleted.generation,uuid()));assertTrue(target.backup.restore(replay){true}.replayed);assertTrue(target.db.read("SELECT id FROM entries WHERE id=?",i.entryId).isEmpty())
        }finally{replay.release()};dest.wipeKey();r.wipeKey()
    }}}
    @Test fun queryIsLiteralAuthenticatedBeforePagingAndOldProofCannotOpenNewerText():Unit=runBlocking{FullFixture().use{f->
        val r=SqlAudioTranscriptRepository(f.gateway,f.key,f.media);val entries=mutableListOf<AudioTranscriptSnapshot>()
        repeat(22){val i=audio(f);val a=r.preview(i);entries+=r.write(write(a,text=if(it==0)"合成很早的特别淡茶"else "合成共同稿 $it",at=it.toLong()+10))}
        assertEquals(entries.first().id,r.search("特别淡茶").items.single().id)
        val first=r.search("");val second=r.search("",first.next);assertEquals(20,first.items.size);assertEquals(2,second.items.size);assertNull(second.next)
        assertEquals(22,(first.items+second.items).map{it.id}.distinct().size)
        val old=first.items.first();r.write(write(old.anchor,old,"变更后的稿"));assertFalse(r.resolve(first,old){fail("Stale query page")})
        val p=r.search("不存在");assertTrue(p.items.isEmpty());var changed=false;assertTrue(r.resolve(p){changed=true});assertTrue(changed)
        val bytes=f.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",entries.last().id).single().single() as ByteArray;bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
        f.db.write("UPDATE local_candidate_batches SET version=version+1,payload=? WHERE id=?",bytes,entries.last().id)
        assertThrows(AudioTranscriptUnavailable::class.java){runBlocking{r.search("不存在")}};r.wipeKey()
    }}
    @Test fun authenticatedBackupRejectsWrongAudioBeforeRestoreIntentAndKeepsInvalidatedFormat():Unit=runBlocking{FullFixture().use{f->
        val i=audio(f);val r=SqlAudioTranscriptRepository(f.gateway,f.key,f.media);val a=r.preview(i);r.write(write(a,text="合成关联稿"))
        val base=f.backup.capture(uuid(),100) as FullBackupData
        try{val old=base.tables[8].single{it[5]==4L};val portable=old[10] as ByteArray;val payload=TranscriptEncoding.decode(portable.copyOfRange(1,portable.size));val wrong=payload.copy(anchor=payload.anchor.copy(intent=i.copy(sha256="0".repeat(64))))
            val rebound=old.toMutableList().also{it[10]=byteArrayOf(4)+TranscriptEncoding.encode(wrong)}
            val tables=base.tables.mapIndexed{index,rows->if(index==8)rows.map{if(it===old)rebound else it}else rows}
            val doc=FullBackupData(base.id,base.created,tables,base.images,base.edits,base.capture,base.audios)
            val prepared=RecordMediaPackageCipher().sealFull(doc,FullBackupTest.password(),{f.media.read(it.attachmentId)},{true})
            val bytes=try{ByteArrayOutputStream().also{prepared.writeTo(it){true}}.toByteArray()}finally{prepared.release()}
            FullFixture(88).use{target->var intents=0;target.checkpoint={intents++}
                assertThrows(Exception::class.java){runBlocking{target.backup.open({bytes.inputStream()},FullBackupTest.password())}}
                assertEquals(0,intents);assertTrue(target.db.read("SELECT * FROM entries").isEmpty());assertTrue(target.db.read("SELECT * FROM record_package_receipts").isEmpty());assertTrue(target.mediaRoot.walkTopDown().none{it.isFile})
            }
        }finally{base.release()}
        f.revise(i.entryId,"合成新原文");val invalidated=export(f);assertEquals("AIYOUF11",String(invalidated.copyOfRange(0,8),Charsets.US_ASCII))
        FullFixture(89).use{target->val pack=target.backup.open({invalidated.inputStream()},FullBackupTest.password());try{target.backup.restore(pack){true}}finally{pack.release()};assertEquals(listOf("INVALIDATED",0L),target.db.read("SELECT state,length(payload) FROM local_candidate_batches WHERE ruleVersion=4").single())}
        r.wipeKey()
    }}
    @Test fun maximumHumanHistoryRoundTripsAndStillAllowsWithdrawalWithoutNinthCorrection():Unit=runBlocking{FullFixture().use{f->
        val id=f.entry(id="s".repeat(128));val bytes=CaptureAudioSqlTest.wave();val i=AttachmentIntent(uuid(),id,1,"audio/wav",FullBackupTest.hash(bytes),bytes.size.toLong());f.media.save(i){bytes.inputStream()}
        val r=SqlAudioTranscriptRepository(f.gateway,f.key,f.media);val a=r.preview(i);var current:AudioTranscriptSnapshot?=null
        repeat(8){current=r.write(write(a,current,"🙂".repeat(999)+it.toString()))}
        assertThrows(AudioTranscriptLimit::class.java){runBlocking{r.write(write(a,current,"第九版拒绝"))}}
        val withdrawn=r.write(write(a,current,withdraw=true));assertEquals(8,withdrawn.history.size);val exported=export(f)
        FullFixture(91).use{target->val pack=target.backup.open({exported.inputStream()},FullBackupTest.password());try{target.backup.restore(pack){true}}finally{pack.release()};val dest=SqlAudioTranscriptRepository(target.gateway,target.key,target.media);assertTrue(dest.read(a)!!.same(withdrawn));dest.wipeKey()};r.wipeKey()
    }}
    companion object {
        fun uuid()=UUID.randomUUID().toString()
        internal suspend fun audio(f:FullFixture):AttachmentIntent {val entry=f.entry(text="合成原文，不是转写");val bytes=CaptureAudioSqlTest.wave();val i=AttachmentIntent(uuid(),entry,1,"audio/wav",FullBackupTest.hash(bytes),bytes.size.toLong());f.media.save(i){bytes.inputStream()};return i}
        fun write(a:AudioTranscriptSource,expected:AudioTranscriptSnapshot?=null,text:String?=null,withdraw:Boolean=false,at:Long=10)=AudioTranscriptWrite(expected?.id?:uuid(),a,expected,if(withdraw)null else text,withdraw,uuid(),at)
        internal suspend fun export(f:FullFixture):ByteArray {val s=f.backup.capture(uuid(),100);return try{val p=f.backup.seal(s,FullBackupTest.password());val out=ByteArrayOutputStream();try{p.writeTo(out){true};out.toByteArray()}finally{p.release()}}finally{s.release()}}
    }
}
