package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Real SQLite/AEAD/media, synthetic source metadata and literal text only. */
class UnifiedCaptureDateSqlTest {
    private val day=UnifiedCaptureRange.parse("2026-10-22","2026-10-22")
    @Test fun fiveTypesShareOriginalSavedZoneNotTheirMutationOrEventDate():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val instant=Instant.parse("2026-10-21T16:30:00Z").toEpochMilli()
        val sh=entry(h,instant,"Asia/Shanghai");all(h,sh)
        val utc=entry(h,instant,"UTC");all(h,utc)
        val found=h.repo.search("淡茶",UnifiedKeywordScope.ALL,day,{})
        assertEquals(day,found.captureRange);assertEquals(listOf(sh),found.groups.map{it.binding.source.sourceId})
        assertEquals(setOf("O:","A:","I:","P:","M:"),found.groups.single().items.map{it.key.take(2)}.toSet())
        assertTrue(found.groups.single().items.all{it.binding.captured==instant&&it.binding.zone=="Asia/Shanghai"})
        var admitted=false;assertTrue(h.repo.resolve(found,found.groups.single().items.first()){admitted=true});assertTrue(admitted)
        assertEquals(2,h.repo.search("淡茶",UnifiedKeywordScope.ALL).groups.size)
    }}
    @Test fun outsideThousandAndFirstAcceptedMatchesNeverConsumeInsideBudget():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        h.f.gateway.transaction {var source="";repeat(1001){n->
            if(n%20==0)source=entry(h,0,"UTC",text="合成依据")
            AcceptedMemoryKeywordSqlTest.memory(h,source,"日期预算专词",quote="合成依据",id="outside-$n")
        }}
        val inside=entry(h,Instant.parse("2026-10-22T00:00:00Z").toEpochMilli(),"UTC",text="合成依据")
        AcceptedMemoryKeywordSqlTest.memory(h,inside,"日期预算专词",quote="合成依据")
        val result=h.repo.search("日期预算专词",UnifiedKeywordScope.ACCEPTED_MEMORY,day,{})
        assertEquals(inside,result.groups.single().binding.source.sourceId)
        assertThrows(UnifiedKeywordTooBroad::class.java){runBlocking{h.repo.search("日期预算专词",UnifiedKeywordScope.ACCEPTED_MEMORY)}}
    }}
    @Test fun dateOutsideNonmatchStillAuthenticatesAndKeepsCorruptCipherBytes():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val row=AcceptedMemoryKeywordSqlTest.memory(h,entry(h,0,"UTC"),"完全不命中",accept=false)
        val bytes=h.f.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",row.id).single().single() as ByteArray
        bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
        h.f.db.write("UPDATE local_candidate_batches SET version=version+1,payload=? WHERE id=?",bytes,row.id)
        assertThrows(Exception::class.java){runBlocking{h.repo.search("专词",UnifiedKeywordScope.ACCEPTED_MEMORY,day,{})}}
        assertArrayEquals(bytes,h.f.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",row.id).single().single() as ByteArray)
    }}
    @Test fun emptyToNonemptyInAnyOfFiveScopesCannotPublishOldEmptyJointProof():Unit=runBlocking{
        for(scope in UnifiedKeywordScope.entries.filter{it!=UnifiedKeywordScope.ALL})UnifiedKeywordFixture().use{h->UnifiedKeywordFixture().use{donor->
            assertArrayEquals(h.f.key,donor.f.key)
            val id=entry(donor,Instant.parse("2026-10-22T08:00:00Z").toEpochMilli(),"UTC");all(donor,id)
            val tables=listOf("entries","entry_revisions","remote_source_access","attachments","local_candidate_batches")
            val rows=tables.associateWith{donor.f.db.read("SELECT * FROM $it")}
            donor.f.mediaRoot.listFiles()!!.filter{it.isFile}.forEach{it.copyTo(h.f.mediaRoot.resolve(it.name))}
            PythonSqliteDatabase(h.f.root.resolve("records.sqlite"),h.f.root.resolve("sqlite_bridge.py"),false,12).use{peer->
                var inserted=false
                h.f.db.afterSql={sql->if(sql=="COMMIT"&&!inserted){inserted=true;peer.transaction{connection->
                    for(table in tables)for(row in rows.getValue(table))connection.execute("INSERT INTO $table VALUES("+row.joinToString(","){"?"}+")",row)
                }}}
                try{assertThrows(Exception::class.java){runBlocking{h.repo.search("淡茶",scope,day,{})}}}
                finally{h.f.db.afterSql=null}
                assertTrue(inserted)
            }
            assertEquals(id,h.repo.search("淡茶",scope,day,{}).groups.single().binding.source.sourceId)
        }}
    }
    @Test fun savedZoneCorruptionAndSourceDateChangesRejectRatherThanReturnFalseEmpty():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=entry(h,Instant.parse("2026-10-22T08:00:00Z").toEpochMilli(),"UTC");all(h,id)
        val proof=h.repo.search("淡茶",UnifiedKeywordScope.ALL,day,{})
        h.f.db.write("UPDATE entries SET captureTimeZone='Not/A_Time_Zone' WHERE id=?",id)
        assertThrows(Exception::class.java){runBlocking{h.repo.resolve(proof){fail("Changed date proof")}}}
        assertThrows(Exception::class.java){runBlocking{h.repo.search("淡茶",UnifiedKeywordScope.ORIGINAL,day,{})}}
    }}
    @Test fun originalDateFilterPrecedesThousandCandidateBudgetAndPaginationNeverUsesOldDateProof():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        h.f.gateway.transaction{repeat(1001){entry(h,0,"UTC")}}
        val ids=(0 until 23).map{entry(h,Instant.parse("2026-10-22T08:00:00Z").toEpochMilli(),"UTC")}
        val p=h.repo.search("淡茶",UnifiedKeywordScope.ORIGINAL,day,{})
        assertEquals(ids.toSet(),p.groups.map{it.binding.source.sourceId}.toSet());assertEquals(3,p.groups.drop(20).size)
        assertTrue(h.repo.resolve(p){})
        val outside=h.repo.search("淡茶",UnifiedKeywordScope.ORIGINAL,UnifiedCaptureRange.parse("2026-10-23","2026-10-23"),{})
        assertTrue(outside.groups.isEmpty());assertNotEquals(p.captureRange,outside.captureRange)
        assertThrows(Exception::class.java){runBlocking{h.repo.search("淡茶",UnifiedKeywordScope.ORIGINAL)}}
    }}
    companion object {
        internal fun entry(h:UnifiedKeywordFixture,at:Long,zone:String,text:String="我偏好淡茶🙂"):String {
            val id=h.f.entry(text=text);h.f.db.write("UPDATE entries SET capturedAtMillis=?,captureTimeZone=? WHERE id=?",at,zone,id);return id
        }
        internal suspend fun all(h:UnifiedKeywordFixture,id:String){h.audio(id);h.image(id);h.preference(id);AcceptedMemoryKeywordSqlTest.memory(h,id,"合成淡茶备注")}
    }
}
