package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual SQLite/AEAD/transaction owner, only synthetic source text. */
class AcceptedMemoryKeywordSqlTest {
    @Test fun quoteNoteAndBothAreSeparateCurrentProjectionsAndNeverCollapseOtherEvidence():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="我并不开心🙂，那是引用。")
        val q=memory(h,id,"普通备注",quote="我并不开心🙂")
        val n=memory(h,id,"备注专词",kind=SourceMemoryKind.OCCASION_EMOTION)
        val both=memory(h,id,"我并不开心🙂")
        val p=h.repo.search("开心",UnifiedKeywordScope.ALL)
        val hits=p.groups.single().items.filterIsInstance<UnifiedKeywordEvidence.AcceptedMemory>()
        assertEquals(setOf(q.id,both.id,n.id),hits.map{it.value.id}.toSet())
        assertEquals(4,p.groups.single().items.size) // original + three distinct memory IDs
        assertTrue(hits.single{it.value.id==q.id}.value.let{it.quoteMatch&&!it.noteMatch})
        assertTrue(hits.single{it.value.id==both.id}.value.let{it.quoteMatch&&it.noteMatch})
        val note=h.repo.search("备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY).groups.single().items.single() as UnifiedKeywordEvidence.AcceptedMemory
        assertFalse(note.value.quoteMatch);assertTrue(note.value.noteMatch)
        assertEquals(SourceMemoryKind.OCCASION_EMOTION,note.value.content.kind)
        assertEquals(SourceMemoryAssertion.UNCERTAIN,note.value.content.assertion)
        val before=h.f.db.read("SELECT * FROM entry_revisions")
        var admitted=false
        assertTrue(h.repo.resolve(note.let{h.repo.search("备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY)},null,{check(!admitted)}){admitted=true})
        assertTrue(admitted);assertEquals(before,h.f.db.read("SELECT * FROM entry_revisions"))
        assertTrue(h.f.db.read("SELECT * FROM manual_tasks").isEmpty())
    }}
    @Test fun correctionWithdrawalAndReacceptanceNeverReuseHistoricalAcceptanceOrProof():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry();var row=memory(h,id,"旧备注专词",accept=false)
        assertTrue(h.repo.search("旧备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY).groups.isEmpty())
        row=change(h,row,SourceMemoryAction.ACCEPT);val old=h.repo.search("旧备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY)
        row=change(h,row,SourceMemoryAction.CORRECT,row.current!!.content.copy(note="新备注专词"))
        assertEquals(SourceMemoryReview.ACCEPTED,row.versions.first().priorReview)
        assertTrue(h.repo.search("旧备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY).groups.isEmpty())
        assertTrue(h.repo.search("新备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY).groups.isEmpty())
        assertFalse(h.repo.resolve(old){fail("Historical acceptance cannot publish")})
        row=change(h,row,SourceMemoryAction.ACCEPT)
        val fresh=h.repo.search("新备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY)
        assertEquals(2,(fresh.groups.single().items.single() as UnifiedKeywordEvidence.AcceptedMemory).value.contentRevision)
        row=change(h,row,SourceMemoryAction.REVOKE);assertFalse(h.repo.resolve(fresh){fail("Withdrawn note")})
        row=change(h,row,SourceMemoryAction.REOPEN);row=change(h,row,SourceMemoryAction.REJECT)
        assertTrue(h.repo.search("新备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY).groups.isEmpty())
        assertEquals(SourceMemoryReview.REJECTED,row.current!!.review)
    }}
    @Test fun sparseMatchBeyond129And4096RowsAndUnicodeKeysetBatchesHasNoGlobalSaveOrScanCap():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        var wanted="";val special=listOf("\uE000","\uD800\uDC00","中", "🙂")
        h.f.gateway.transaction {
            var source=""
            repeat(4097){n->
                if(n%20==0)source=h.f.entry(text="合成无关原文 $n")
                val id=if(n<special.size)special[n] else "memory-"+n.toString().padStart(5,'0')
                val row=memory(h,source,if(n==4096)"唯一备注专词"else"无关备注",accept=n==4096,id=id)
                if(n==4096)wanted=row.id
            }
        }
        var batches=0;var fullReads=0
        h.f.db.beforeSql={sql->if(sql.startsWith("SELECT c.id,c.entryId,c.sourceRevision,length(c.payload)")){assertTrue(sql.endsWith("LIMIT 20"));batches++}
            if(sql.startsWith("SELECT id,entryId,sourceRevision,sourceHash,generation,ruleVersion"))fullReads++}
        try {
            val p=h.repo.search("唯一备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY)
            assertEquals(wanted,(p.groups.single().items.single() as UnifiedKeywordEvidence.AcceptedMemory).value.id)
            assertEquals(4097*2,fullReads);assertEquals(205*2,batches)
            assertTrue(h.repo.search("完全不匹配",UnifiedKeywordScope.ACCEPTED_MEMORY).groups.isEmpty())
        }finally{h.f.db.beforeSql=null}
        assertEquals(4097L,h.f.db.read("SELECT count(*) FROM local_candidate_batches WHERE ruleVersion=2").single().single())
    }}
    @Test fun thousandMatchesSucceedAndThousandFirstFailsTheWholeQueryWithoutChangingSources():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        var source=""
        h.f.gateway.transaction {repeat(1000){n->if(n%20==0)source=h.f.entry();memory(h,source,"共同备注",id="bulk-$n")}}
        assertEquals(1000,h.repo.search("共同备注",UnifiedKeywordScope.ACCEPTED_MEMORY).groups.sumOf{it.items.size})
        val captured=UnifiedCaptureRange.parse("1970-01-01","1970-01-01")
        assertEquals(1000,h.repo.search("共同备注",UnifiedKeywordScope.ACCEPTED_MEMORY,captured,{}).groups.sumOf{it.items.size})
        val id=h.f.entry();val last=memory(h,id,"共同备注");val bytes=h.f.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",last.id).single().single() as ByteArray
        assertThrows(UnifiedKeywordTooBroad::class.java){runBlocking{h.repo.search("共同备注",UnifiedKeywordScope.ALL)}}
        assertThrows(UnifiedKeywordTooBroad::class.java){runBlocking{h.repo.search("共同备注",UnifiedKeywordScope.ACCEPTED_MEMORY,captured,{})}}
        assertArrayEquals(bytes,h.f.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",last.id).single().single() as ByteArray)
        assertEquals(1001L,h.f.db.read("SELECT count(*) FROM local_candidate_batches WHERE ruleVersion=2").single().single())
        assertEquals(1,h.repo.search("合成",UnifiedKeywordScope.ORIGINAL).groups.map{it.binding.source.sourceId}.count{it==id})
    }}
    @Test fun nonmatchingReviewChangeAndEmptyToNonemptyAfterCaptureRejectCommonCommit():Unit=runBlocking{
        for(initiallyAccepted in listOf(false,true))UnifiedKeywordFixture().use{h->
            val row=memory(h,h.f.entry(),if(initiallyAccepted)"别的备注"else"备注专词",accept=initiallyAccepted)
            PythonSqliteDatabase(h.f.root.resolve("records.sqlite"),h.f.root.resolve("sqlite_bridge.py"),false,12).use{otherDb->val other=SqlSourceMemoryRepository(otherDb,h.f.key)
                var changed=false
                h.f.db.afterSql={if(it=="COMMIT"&&!changed){changed=true
                    if(initiallyAccepted){val corrected=other.mutate(mutation(row,SourceMemoryAction.CORRECT,row.current!!.content.copy(note="备注专词")));other.mutate(mutation(corrected,SourceMemoryAction.ACCEPT))}
                    else other.mutate(mutation(row,SourceMemoryAction.ACCEPT))}}
                try{assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.search("备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY)}}}
                finally{h.f.db.afterSql=null;other.wipeKey()}
                assertTrue(changed)
            }
            assertEquals(1,h.repo.search("备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY).groups.size)
        }
    }
    @Test fun finalAuthenticationIncludesRealOriginalHashOfNonmatchingRows():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="合成真实原文");memory(h,id,"无关备注")
        val epoch=h.f.db.read("SELECT epoch FROM local_search_epoch").single().single()
        PythonSqliteDatabase(h.f.root.resolve("records.sqlite"),h.f.root.resolve("sqlite_bridge.py"),false,12).use{other->var changed=false
            h.f.db.afterSql={if(it=="COMMIT"&&!changed){changed=true;other.transaction{sql->
                    // Synthetic noncooperative page corruption, with the production guard restored
                    // before the final authenticated scan; no runtime schema/guard is changed.
                    sql.execute("DROP TRIGGER immutable_original_revision")
                    sql.execute("UPDATE entry_revisions SET originalText=? WHERE entryId=?",listOf("篡改真实原文",id))
                    sql.execute(RemoteTextSqlSchema.originalRevisionGuard)
                }}}
            try{assertThrows(Exception::class.java){runBlocking{h.repo.search("绝不匹配",UnifiedKeywordScope.ACCEPTED_MEMORY)}}}
            finally{h.f.db.afterSql=null}
            assertTrue(changed)
        }
        assertEquals("篡改真实原文",h.f.db.read("SELECT originalText FROM entry_revisions WHERE entryId=?",id).single().single())
        assertEquals(epoch,h.f.db.read("SELECT epoch FROM local_search_epoch").single().single())
        assertEquals(1,h.f.db.read("SELECT name FROM sqlite_master WHERE type='trigger' AND name='immutable_original_revision'").size)
    }}
    @Test fun corruptNonacceptedCiphertextRejectsAllTypesAndRetainsEveryCorruptByte():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        val id=h.f.entry(text="合成淡茶");h.audio(id);val row=memory(h,id,"无关备注",accept=false)
        val bad=h.f.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",row.id).single().single() as ByteArray
        bad[bad.lastIndex]=(bad.last().toInt() xor 1).toByte();h.f.db.write("UPDATE local_candidate_batches SET version=version+1,payload=? WHERE id=?",bad,row.id)
        assertThrows(Exception::class.java){runBlocking{h.repo.search("淡茶",UnifiedKeywordScope.ALL)}}
        assertEquals(1,h.repo.search("淡茶",UnifiedKeywordScope.AUDIO).groups.size)
        assertArrayEquals(bad,h.f.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",row.id).single().single() as ByteArray)
    }}
    @Test fun batchGuardRollsBackActualSqlThenAllowsAnotherSearchOnSameOwner():Unit=runBlocking{UnifiedKeywordFixture().use{h->
        h.f.gateway.transaction{repeat(3){g->val source=h.f.entry();repeat(20){memory(h,source,"无关备注",accept=false)}}}
        var batches=0;var rollback=false
        h.f.db.afterSql={if(it.startsWith("SELECT c.id,c.entryId,c.sourceRevision,length(c.payload)"))batches++;if(it=="ROLLBACK")rollback=true}
        class Stop:RuntimeException()
        try{assertThrows(Stop::class.java){runBlocking{h.repo.search("无结果",UnifiedKeywordScope.ACCEPTED_MEMORY){if(batches==2)throw Stop()}}}}
        finally{h.f.db.afterSql=null}
        assertEquals(2,batches);assertTrue(rollback)
        assertTrue(h.repo.search("无结果",UnifiedKeywordScope.ACCEPTED_MEMORY).groups.isEmpty())
        assertEquals(60L,h.f.db.read("SELECT count(*) FROM local_candidate_batches WHERE ruleVersion=2").single().single())
    }}
    @Test fun policyDeletionRevisionAndForeignOwnerCannotResurrectOldAcceptedProof():Unit=runBlocking{
        for(mode in listOf("policy","delete","revision","generation"))UnifiedKeywordFixture().use{h->
            val id=h.f.entry();memory(h,id,"备注专词");val p=h.repo.search("备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY)
            when(mode){"policy"->h.f.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?",id)
                "delete"->h.f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",id)
                "revision"->h.f.revise(id,"合成新版本")
                else->h.f.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?",id)}
            assertTrue(h.repo.search("备注专词",UnifiedKeywordScope.ACCEPTED_MEMORY).groups.isEmpty())
            assertFalse(h.repo.resolve(p){fail("Invalidated source")})
        }
    }
    companion object {
        internal fun memory(h:UnifiedKeywordFixture,source:String,note:String,kind:SourceMemoryKind=SourceMemoryKind.EVENT,
            quote:String?=null,accept:Boolean=true,id:String=FullBackupTest.uuid()):SourceMemorySnapshot {
            val p=h.memories.preview(SourceVersion(source,1));val content=SourceMemoryContent(SourceMemoryRules.reference(p,quote?:p.original),kind,SourceMemoryAssertion.UNCERTAIN,note)
            val row=h.memories.create(SourceMemoryCreate(id,p,content,FullBackupTest.uuid(),50))
            return if(accept)change(h,row,SourceMemoryAction.ACCEPT)else row
        }
        internal fun mutation(row:SourceMemorySnapshot,action:SourceMemoryAction,content:SourceMemoryContent?=null)=SourceMemoryMutation(row.id,row.source,row.sourceHash,row.generation,row.version,row.current!!.revision,row.current!!.review,action,content,FullBackupTest.uuid(),60)
        internal fun change(h:UnifiedKeywordFixture,row:SourceMemorySnapshot,action:SourceMemoryAction,content:SourceMemoryContent?=null)=h.memories.mutate(mutation(row,action,content))
    }
}
