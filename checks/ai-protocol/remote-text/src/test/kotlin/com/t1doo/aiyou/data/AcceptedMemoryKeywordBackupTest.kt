package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class AcceptedMemoryKeywordBackupTest {
    @Test fun allFiveActualFullRestoreVersionsRejectAuthenticatedTwentyFirstBeforeAnyIntentWithoutTouchingTarget():Unit=runBlocking{
        UnifiedKeywordFixture().use{h->
            val first=h.f.entry(text="合成相同原文")
            repeat(20){AcceptedMemoryKeywordSqlTest.memory(h,first,"合成备注 $it")}
            val last=AcceptedMemoryKeywordSqlTest.memory(h,h.f.entry(text="合成相同原文"),"合成备注21")
            val data=h.f.backup.capture(FullBackupTest.uuid(),100) as FullBackupData
            try {
                val rebound=data.tables[8].single{it[0]==last.id}.toMutableList().also{it[1]=first}
                val bad=FullBackupData(data.id,data.created,data.tables.mapIndexed{n,rows->if(n==8)rows.map{if(it[0]==last.id)rebound else it}else rows},data.images,data.edits,data.capture,data.audios)
                // Product encoder must reject too, while this pre-existing adversarial encoder
                // bypasses semantic validation only to exercise the actual authenticated reader.
                assertThrows(SourceMemoryLimit::class.java){FullBackupCodec.encode(bad)}
                for(version in 8..12)FullFixture(90+version).use{target->
                    val bytes=FullBackupTest().uncheckedAuthenticatedNoImages(bad,version)
                    val before=target.db.read("SELECT * FROM entries");val steps=mutableListOf<String>();target.checkpoint={steps+=it}
                    assertThrows(RecordPackageInvalid::class.java){runBlocking{target.backup.open({bytes.inputStream()},FullBackupTest.password())}}
                    assertEquals(before,target.db.read("SELECT * FROM entries"));assertTrue(steps.isEmpty())
                    assertTrue(target.db.read("SELECT * FROM record_package_receipts").isEmpty())
                    assertTrue(target.mediaRoot.walkTopDown().none{it.isFile})
                }
                // Install an individually AEAD-valid 21st row, bypassing only public create quota.
                val installed=rebound.toMutableList().also{it[10]=h.memories.portable(rebound,true)}
                try{
                    h.f.db.transaction{sql->sql.execute("DELETE FROM local_candidate_batches WHERE id=?",listOf(last.id));sql.execute("INSERT INTO local_candidate_batches(id,entryId,sourceRevision,sourceHash,generation,ruleVersion,version,state,lastMutationId,createdAtMillis,payload) VALUES(?,?,?,?,?,?,?,?,?,?,?)",installed)}
                    val corruptState=state(h.f)
                    assertThrows(SourceMemoryLimit::class.java){h.memories.memories(SourceVersion(first,1))}
                    assertThrows(SourceMemoryLimit::class.java){runBlocking{h.repo.search("合成备注",UnifiedKeywordScope.ACCEPTED_MEMORY)}}
                    assertThrows(SourceMemoryLimit::class.java){runBlocking{h.f.backup.capture(FullBackupTest.uuid(),101)}}
                    assertEquals(corruptState,state(h.f))
                }finally{(installed[10] as ByteArray).fill(0)}
            }finally{data.release()}
        }
    }
    @Test fun twentyReadyPlusInvalidatedDoNotConsumeNewQuotaAndRoundTripAcrossAllRestoreRoutes():Unit=runBlocking{
        UnifiedKeywordFixture().use{h->
            val first=h.f.entry();repeat(20){AcceptedMemoryKeywordSqlTest.memory(h,first,"合成旧备注 $it")}
            h.f.revise(first,"合成新原文")
            val source=SourceVersion(first,2);val p=h.memories.preview(source)
            repeat(20){i->val row=h.memories.create(SourceMemoryCreate(FullBackupTest.uuid(),p,SourceMemoryContent(SourceMemoryRules.reference(p,p.original),SourceMemoryKind.EVENT,SourceMemoryAssertion.SELF_REPORT,"新备注专词$i"),FullBackupTest.uuid(),50));h.memories.mutate(AcceptedMemoryKeywordSqlTest.mutation(row,SourceMemoryAction.ACCEPT))}
            assertEquals(20,h.repo.search("新备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY).groups.single().items.size)
            val data=h.f.backup.capture(FullBackupTest.uuid(),100) as FullBackupData
            try{for(version in 8..12)UnifiedKeywordFixture(50+version).use{target->
                val bytes=FullBackupTest().uncheckedAuthenticatedNoImages(data,version)
                val pack=target.f.backup.open({bytes.inputStream()},FullBackupTest.password())
                try{target.f.backup.restore(pack){true}}finally{pack.release()}
                val found=target.repo.search("新备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY)
                assertEquals(20,found.groups.single().items.size)
                assertEquals(20L,target.f.db.read("SELECT count(*) FROM local_candidate_batches WHERE ruleVersion=2 AND state='INVALIDATED'").single().single())
                assertEquals(20,target.memories.memories(source).size)
            }}finally{data.release()}
        }
    }
    @Test fun v12IndependentEncryptedOwnersPreserveEightVersionsButOnlyCurrentAcceptedNoteCanBeFound():Unit=runBlocking{
        UnifiedKeywordFixture().use{h->UnifiedKeywordFixture(83).use{target->
            val id=h.f.entry(text="🙂合成唯一依据，引用并不证明事件。")
            var row=AcceptedMemoryKeywordSqlTest.memory(h,id,"备注第1版")
            repeat(7){i->row=AcceptedMemoryKeywordSqlTest.change(h,row,SourceMemoryAction.CORRECT,row.current!!.content.copy(note="备注第${i+2}版",kind=SourceMemoryKind.OCCASION_EMOTION));row=AcceptedMemoryKeywordSqlTest.change(h,row,SourceMemoryAction.ACCEPT)}
            val old=h.repo.search("备注第8版",UnifiedKeywordScope.ACCEPTED_MEMORY)
            // Presence of this already supported empty receipt chooses real v12 encoding, no schema change.
            val marker=FullBackupTest.uuid()
            h.f.db.write("INSERT INTO record_package_receipts VALUES(?, ?, ?, 'FULL_BACKUP_IMPORT_V12',?)","FULL_BACKUP:"+marker,marker,12L,"DONE:"+"0".repeat(64))
            val captured=h.f.backup.capture(FullBackupTest.uuid(),100)
            val sealed=h.f.backup.seal(captured,FullBackupTest.password());val bytes=try{ByteArrayOutputStream().also{sealed.writeTo(it){true}}.toByteArray()}finally{sealed.release();captured.release()}
            assertTrue(String(bytes.copyOfRange(0,8),Charsets.US_ASCII)=="AIYOUF12")
            val pack=target.f.backup.open({bytes.inputStream()},FullBackupTest.password())
            try{target.f.backup.restore(pack){true}}finally{pack.release()}
            assertFalse(target.repo.resolve(old){fail("Old owner proof cannot survive restore")})
            assertEquals(row.versions,target.memories.memories(SourceVersion(id,1)).single().versions)
            assertTrue(target.repo.search("备注第1版",UnifiedKeywordScope.ACCEPTED_MEMORY).groups.isEmpty())
            val hit=target.repo.search("备注第8版",UnifiedKeywordScope.ACCEPTED_MEMORY).groups.single().items.single() as UnifiedKeywordEvidence.AcceptedMemory
            assertEquals(8,hit.value.contentRevision);assertFalse(hit.value.quoteMatch);assertTrue(hit.value.noteMatch)
        }}
    }
    private fun state(f:FullFixture)=f.db.read("SELECT * FROM local_candidate_batches ORDER BY id").map{row->row.map{if(it is ByteArray)FullBackupTest.hash(it)else it}}
}
