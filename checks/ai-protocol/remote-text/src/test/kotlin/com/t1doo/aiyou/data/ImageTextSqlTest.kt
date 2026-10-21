package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Real SQL/AEAD/files; text is explicitly a synthetic engine fixture, never OCR evidence. */
class ImageTextSqlTest {
    @Test fun independentEncryptedCorrectionColdReadDeletionAndNewExtractionKeepOriginalUntouched():Unit=runBlocking{FullFixture().use{f->
        val image=f.image(f.entry());var repo=SqlImageTextRepository(f.gateway,f.key,f.media,f.capture.mutationGate)
        val anchor=repo.preview(image);val bytes=f.media.read(image.id);val originals=f.db.read("SELECT * FROM entry_revisions")
        val initial=repo.write(create(anchor,"合成提取：淡茶"))!!;assertTrue(initial.engine.synthetic)
        assertFalse(String(f.db.read("SELECT payload FROM local_candidate_batches WHERE ruleVersion=5").single().single() as ByteArray).contains("淡茶"))
        val corrected=repo.write(change(initial,"人工纠正：清水"))!!
        assertEquals(listOf(false,true),corrected.history.map{it.humanCorrection});assertEquals(2L,corrected.version)
        assertTrue(repo.search("淡茶").items.isEmpty());assertEquals(corrected.id,repo.search("清水").items.single().id)
        val stale=repo.search("清水");repo.wipeKey();repo=SqlImageTextRepository(f.gateway,f.key,f.media)
        assertTrue(repo.read(anchor)!!.same(corrected));assertFalse(repo.resolve(stale,stale.items.single()){fail("Foreign owner proof")})
        val removal=change(corrected,delete=true);assertNull(repo.write(removal));assertNull(repo.write(removal))
        assertEquals(listOf("CANCELLED",0L),f.db.read("SELECT state,length(payload) FROM local_candidate_batches WHERE ruleVersion=5").single())
        assertNull(repo.read(anchor));assertTrue(repo.search("").items.isEmpty())
        val replacement=repo.write(create(anchor,"另一次合成提取"))!!;assertNotEquals(initial.id,replacement.id)
        assertNull(repo.write(removal));assertTrue(repo.read(anchor)!!.same(replacement))
        assertArrayEquals(bytes,f.media.read(image.id));assertEquals(originals,f.db.read("SELECT * FROM entry_revisions"));repo.wipeKey()
    }}
    @Test fun actualSqlAcknowledgementLossReplaysCreateCorrectionAndDeletionExactlyOnce():Unit=runBlocking{FullFixture().use{f->
        val image=f.image(f.entry());val repo=SqlImageTextRepository(f.gateway,f.key,f.media);val anchor=repo.preview(image)
        suspend fun lost(request:ImageTextWrite):ImageTextSnapshot? {
            var touched=false;var dropped=false
            f.db.beforeSql={if(it.startsWith("INSERT INTO local_candidate_batches")||it.startsWith("UPDATE local_candidate_batches SET"))touched=true}
            f.db.afterSql={if(it=="COMMIT"&&touched&&!dropped){dropped=true;error("Synthetic committed image-text ACK loss")}}
            try{assertThrows(ImageTextUnavailable::class.java){runBlocking{repo.write(request)}}}finally{f.db.beforeSql=null;f.db.afterSql=null}
            assertTrue(dropped);val before=f.db.read("SELECT version,state,payload FROM local_candidate_batches WHERE id=?",request.id).single()
            val result=repo.write(request);val after=f.db.read("SELECT version,state,payload FROM local_candidate_batches WHERE id=?",request.id).single()
            assertEquals(before.take(2),after.take(2));assertArrayEquals(before[2] as ByteArray,after[2] as ByteArray);return result
        }
        val first=lost(create(anchor,"合成一次提取"))!!;val second=lost(change(first,"合成一次纠正"))!!
        assertEquals(2,second.history.size);assertNull(lost(change(second,delete=true)));assertTrue(repo.search("").items.isEmpty());repo.wipeKey()
    }}
    @Test fun sourceRevisionTrashPolicyAbaAndWrongExactImageInvalidateReferencesAndBlockLateWrites():Unit=runBlocking{
        for(mode in 0..3)FullFixture().use{f->
            val image=f.image(f.entry());val repo=SqlImageTextRepository(f.gateway,f.key,f.media);val anchor=repo.preview(image);val saved=repo.write(create(anchor,"合成来源文字"))!!;val page=repo.search("")
            when(mode){0->f.revise(image.entryId,"合成修订原文");1->f.db.write("UPDATE remote_source_access SET generation=generation+1,deleted=1 WHERE entryId=?",image.entryId);2->{f.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?",image.entryId);f.db.write("UPDATE entries SET privacyPolicy='LOCAL_ONLY' WHERE id=?",image.entryId)};3->f.db.write("UPDATE attachments SET sha256=? WHERE id=?","0".repeat(64),image.id)}
            assertThrows(ImageTextConflict::class.java){runBlocking{repo.write(change(saved,"不得落地迟到纠正"))}}
            assertThrows(ImageTextConflict::class.java){runBlocking{repo.read(anchor)}}
            if(mode!=3){assertEquals(listOf("INVALIDATED",0L),f.db.read("SELECT state,length(payload) FROM local_candidate_batches WHERE ruleVersion=5").single());assertFalse(repo.resolve(page,page.items.single()){fail("Invalidated proof")})}
            repo.wipeKey()
        }
        FullFixture().use{f->val image=f.image(f.entry());f.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?",image.entryId);val repo=SqlImageTextRepository(f.gateway,f.key,f.media);val anchor=repo.preview(image)
            assertThrows(IllegalArgumentException::class.java){create(anchor,"禁止 AI_NONE 下 OCR")};assertTrue(repo.search("").items.isEmpty());repo.wipeKey()}
    }
    @Test fun v12FullBackupCrossKeyRestoreReplayReexportAndInvalidatedOrDeletedHistoryRemainCompatible():Unit=runBlocking{FullFixture().use{source->FullFixture(92).use{target->
        val image=source.image(source.entry());val repo=SqlImageTextRepository(source.gateway,source.key,source.media);val anchor=repo.preview(image)
        var saved=repo.write(create(anchor,"合成原始机器文字"))!!;saved=repo.write(change(saved,"人工核对版本"))!!
        val exported=AudioTranscriptSqlTest.export(source);assertEquals("AIYOUF12",String(exported.copyOfRange(0,8),Charsets.US_ASCII))
        val pack=target.backup.open({exported.inputStream()},FullBackupTest.password())
        try{var dropped=false;target.checkpoint={if(it=="full-committed"&&!dropped){dropped=true;error("Synthetic v12 ACK loss")}}
            assertThrows(Exception::class.java){runBlocking{target.backup.restore(pack){true}}};target.checkpoint={};assertTrue(dropped);assertTrue(target.backup.restore(pack){true}.replayed);assertTrue(target.backup.completed(pack))
        }finally{pack.release()}
        val restored=SqlImageTextRepository(target.gateway,target.key,target.media);assertTrue(restored.read(anchor)!!.same(saved));assertArrayEquals(source.media.read(image.id),target.media.read(image.id))
        val reexported=AudioTranscriptSqlTest.export(target);assertEquals("AIYOUF12",String(reexported.copyOfRange(0,8),Charsets.US_ASCII))
        restored.write(change(saved,delete=true));val deleted=AudioTranscriptSqlTest.export(target)
        FullFixture(93).use{empty->val p=empty.backup.open({deleted.inputStream()},FullBackupTest.password());try{empty.backup.restore(p){true}}finally{p.release()};assertEquals(listOf("CANCELLED",0L),empty.db.read("SELECT state,length(payload) FROM local_candidate_batches WHERE ruleVersion=5").single())}
        source.revise(image.entryId,"合成原文变化");val invalidated=AudioTranscriptSqlTest.export(source)
        FullFixture(94).use{empty->val p=empty.backup.open({invalidated.inputStream()},FullBackupTest.password());try{empty.backup.restore(p){true}}finally{p.release()};assertEquals(listOf("INVALIDATED",0L),empty.db.read("SELECT state,length(payload) FROM local_candidate_batches WHERE ruleVersion=5").single())}
        repo.wipeKey();restored.wipeKey()
    }}}
    @Test fun twentyPerSourceRestoresButAuthenticatedTwentyOneRejectsBeforeIntentWithoutChangingTarget():Unit=runBlocking{FullFixture().use{f->
        val id=f.entry();val repo=SqlImageTextRepository(f.gateway,f.key,f.media);val images=List(21){f.image(id)}
        for((n,image)in images.take(20).withIndex())repo.write(create(repo.preview(image),"合成容量条目 $n"))
        assertThrows(ImageTextLimit::class.java){runBlocking{repo.write(create(repo.preview(images.last()),"合法来源但超过派生条目容量"))}}
        assertEquals(20,repo.search("").items.size)
        val base=f.backup.capture(FullBackupTest.uuid(),100) as FullBackupData
        try {
            val old=base.tables[8].first{it[5]==5L};val portable=old[10] as ByteArray;val p=ImageTextEncoding.decode(portable.copyOfRange(1,portable.size))
            val extra=old.toMutableList().also{it[0]=FullBackupTest.uuid();it[10]=byteArrayOf(5)+ImageTextEncoding.encode(p.copy(anchor=p.anchor.copy(intent=images.last())))}
            val forged=FullBackupData(base.id,base.created,base.tables.mapIndexed{n,rows->if(n==8)rows+listOf(extra)else rows},base.images,base.edits,base.capture,base.audios)
            val prepared=RecordMediaPackageCipher().sealFull(forged,FullBackupTest.password(),{f.media.read(it.attachmentId)},{true})
            val bytes=try{ByteArrayOutputStream().also{prepared.writeTo(it){true}}.toByteArray()}finally{prepared.release()}
            FullFixture(95).use{target->var intents=0;target.checkpoint={intents++};val before=target.db.read("SELECT * FROM record_package_receipts")
                assertThrows(Exception::class.java){runBlocking{target.backup.open({bytes.inputStream()},FullBackupTest.password())}}
                assertEquals(0,intents);assertEquals(before,target.db.read("SELECT * FROM record_package_receipts"));assertTrue(target.db.read("SELECT * FROM entries").isEmpty());assertTrue(target.mediaRoot.walkTopDown().none{it.isFile})}
        }finally{base.release()}
        val good=AudioTranscriptSqlTest.export(f)
        FullFixture(96).use{target->val pack=target.backup.open({good.inputStream()},FullBackupTest.password());try{target.backup.restore(pack){true}}finally{pack.release()};val read=SqlImageTextRepository(target.gateway,target.key,target.media);assertEquals(20,read.search("").items.size);read.wipeKey()}
        repo.wipeKey()
    }}
    @Test fun maximumHistoryStillAllowsActualCiphertextDeletionWhileSharedPlaybackGateBlocksWrites():Unit=runBlocking{FullFixture().use{f->
        val image=f.image(f.entry());val gate=CaptureDraftGate();val repo=SqlImageTextRepository(f.gateway,f.key,f.media,gate);val anchor=repo.preview(image)
        var saved=repo.write(create(anchor,"合成首版"))!!;repeat(7){saved=repo.write(change(saved,"🙂".repeat(999)+it.toString()))!!}
        assertThrows(ImageTextLimit::class.java){runBlocking{repo.write(change(saved,"第九版不保存"))}}
        val pin=gate.preparePlayback()!!;gate.activatePlayback(pin)
        try{assertThrows(ImageTextUnavailable::class.java){runBlocking{repo.write(change(saved,delete=true))}}}finally{gate.finishPlayback(pin)}
        // Deletion does not need a second plaintext read of an already authenticated source.
        val broken=object:AttachmentStore by f.media {override suspend fun read(id:String):ByteArray=error("Synthetic media read unavailable")}
        val deletion=SqlImageTextRepository(f.gateway,f.key,broken,gate);assertNull(deletion.write(change(saved,delete=true)))
        assertEquals(listOf("CANCELLED",0L),f.db.read("SELECT state,length(payload) FROM local_candidate_batches WHERE ruleVersion=5").single());deletion.wipeKey();repo.wipeKey()
    }}
    @Test fun keywordFindsOlderPagesAndChangedOrDeletedResultsRetireRetainedProofs():Unit=runBlocking{FullFixture().use{f->
        val repo=SqlImageTextRepository(f.gateway,f.key,f.media);val saved=mutableListOf<ImageTextSnapshot>()
        repeat(22){n->val image=f.image(f.entry(text="合成图片来源 $n"));saved+=repo.write(create(repo.preview(image),if(n==0)"旧页唯一合成词"else "合成可分页内容 $n"))!!}
        val first=repo.search("");assertEquals(20,first.items.size);assertNotNull(first.next)
        val second=repo.search("",first.next);assertEquals(2,second.items.size);assertNull(second.next);assertEquals(22,(first.items+second.items).map{it.id}.distinct().size)
        val hit=repo.search("旧页唯一");assertEquals(saved.first().id,hit.items.single().id)
        var source:ImageTextSource?=null;assertTrue(repo.resolve(hit,hit.items.single()){source=hit.items.single().anchor});assertEquals(saved.first().anchor,source)
        val corrected=repo.write(change(saved.first(),"替换后的合成文字"))!!;assertFalse(repo.resolve(hit,hit.items.single()){fail("Corrected stale excerpt")})
        val current=repo.search("替换后的");repo.write(change(corrected,delete=true));assertFalse(repo.resolve(current,current.items.single()){fail("Deleted derived text")})
        val remaining=repo.search("");val tail=repo.search("",remaining.next);assertEquals(20,remaining.items.size);assertNotNull(remaining.next);assertEquals(1,tail.items.size);assertNull(tail.next)
        val remainingIds=(remaining.items+tail.items).map{it.id};assertEquals(21,remainingIds.distinct().size);assertFalse(remainingIds.contains(corrected.id));assertTrue(repo.search("旧页唯一").items.isEmpty());repo.wipeKey()
    }}
    @Test fun authenticatedBackupSubstitutedImageOrAiNonePayloadRejectsBeforeRestoreIntent():Unit=runBlocking{FullFixture().use{f->
        val image=f.image(f.entry());val repo=SqlImageTextRepository(f.gateway,f.key,f.media);repo.write(create(repo.preview(image),"合成备份来源校验"))
        for(none in listOf(false,true)){
            val base=f.backup.capture(FullBackupTest.uuid(),100) as FullBackupData
            try{val old=base.tables[8].single{it[5]==5L};val portable=old[10] as ByteArray
                val changed=if(!none){val p=ImageTextEncoding.decode(portable.copyOfRange(1,portable.size));byteArrayOf(5)+ImageTextEncoding.encode(p.copy(anchor=p.anchor.copy(intent=image.copy(sha256="0".repeat(64)))))}else{
                    val marker="LOCAL_ONLY".toByteArray();val index=(4..portable.size-marker.size).single{i->portable.copyOfRange(i,i+marker.size).contentEquals(marker)}
                    require(java.nio.ByteBuffer.wrap(portable,index-4,4).int==marker.size)
                    portable.copyOfRange(0,index-4)+java.nio.ByteBuffer.allocate(4).putInt(7).array()+"AI_NONE".toByteArray()+portable.copyOfRange(index+marker.size,portable.size)
                }
                val extra=old.toMutableList().also{it[10]=changed}
                val tables=base.tables.mapIndexed{n,rows->when(n){8->rows.map{if(it===old)extra else it};0->if(none)rows.map{row->row.toMutableList().also{it[4]="AI_NONE"}}else rows;else->rows}}
                val doc=FullBackupData(base.id,base.created,tables,base.images,base.edits,base.capture,base.audios)
                val prepared=RecordMediaPackageCipher().sealFull(doc,FullBackupTest.password(),{f.media.read(it.attachmentId)},{true})
                val bytes=try{ByteArrayOutputStream().also{prepared.writeTo(it){true}}.toByteArray()}finally{prepared.release()}
                FullFixture(98).use{target->var intents=0;target.checkpoint={intents++}
                    assertThrows(Exception::class.java){runBlocking{target.backup.open({bytes.inputStream()},FullBackupTest.password())}}
                    assertEquals(0,intents);assertTrue(target.db.read("SELECT * FROM record_package_receipts").isEmpty());assertTrue(target.db.read("SELECT * FROM entries").isEmpty());assertTrue(target.mediaRoot.walkTopDown().none{it.isFile})}
            }finally{base.release()}
        };repo.wipeKey()
    }}
    companion object {
        fun create(anchor:ImageTextSource,text:String)=ImageTextWrite(FullBackupTest.uuid(),anchor,null,ImageTextExtraction(ImageTextEngine("synthetic-test-port","1",true),text),null,false,FullBackupTest.uuid(),10)
        fun change(old:ImageTextSnapshot,text:String?=null,delete:Boolean=false)=ImageTextWrite(old.id,old.anchor,old,null,if(delete)null else text,delete,FullBackupTest.uuid(),20+old.version)
    }
}
