package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceFactQuerySqlTest {
    @Test fun oneCollectionScanAuthenticatesAllReadyRowsBeforeSelectionAndCancellationReleasesSql():Unit=UnifiedKeywordFixture().use { h ->
        val source=SourceVersion(h.f.entry(text="我的合成杯子是绿色🙂"),1)
        repeat(20){create(h,source,accepted=it==0)}
        var scans=0;var checks=0
        h.f.db.beforeSql={sql->if(sql.contains("WHERE ruleVersion=6 AND state='READY' ORDER BY id LIMIT 129"))scans++}
        val set=try{h.facts.keywordSet("绿色"){checks++}}finally{h.f.db.beforeSql=null}
        assertEquals(1,scans);assertEquals(1,set.items.size);assertEquals(listOf(source),set.sources.distinct())
        assertTrue(checks>=20);assertTrue("Guard work stays bounded per row",checks<=20*20+20)
        val before=h.f.db.read("SELECT id,version FROM local_candidate_batches ORDER BY id")
        var cancelled=0
        assertThrows(CancellationException::class.java){h.facts.keywordSet("绿色"){if(++cancelled==12)throw CancellationException("Synthetic cancellation")}}
        assertEquals(before,h.f.db.read("SELECT id,version FROM local_candidate_batches ORDER BY id"))
        assertEquals(20,h.facts.facts(source).size)
    }

    @Test fun unifiedAllKeepsTypedFactAndOriginalAndOnlyAcceptedCurrentFactCanResolve():Unit=runBlocking {
        UnifiedKeywordFixture().use {h->
            val source=SourceVersion(h.f.entry(text="我的合成杯子是绿色🙂"),1)
            val accepted=create(h,source);val rejected=create(h,source,false)
            h.facts.mutate(FactMutation(rejected,FactAction.REJECT,null,null,FullBackupTest.uuid(),30))
            val fact=h.repo.search("绿色",UnifiedKeywordScope.FACT)
            val hit=fact.groups.single().items.single() as UnifiedKeywordEvidence.Fact
            assertEquals(accepted.id,hit.value.id);assertEquals(source,hit.binding.source)
            assertTrue(com.t1doo.aiyou.records.unifiedOriginalLocation(hit,"绿色")!!.highlightRange)
            val all=h.repo.search("绿色",UnifiedKeywordScope.ALL)
            assertEquals(setOf("O:${source.sourceId}:1","F:${accepted.id}"),all.groups.single().items.map{it.key}.toSet())
            var admissions=0;assertTrue(h.repo.resolve(fact,hit){admissions++});assertEquals(1,admissions)
            h.facts.mutate(FactMutation(accepted,FactAction.REVOKE,null,null,FullBackupTest.uuid(),40))
            assertFalse(h.repo.resolve(fact,hit){fail("Revoked fact navigated")})
            assertTrue(h.repo.search("绿色",UnifiedKeywordScope.FACT).groups.isEmpty())
            assertEquals("我的合成杯子是绿色🙂",h.f.db.read("SELECT originalText FROM entry_revisions WHERE entryId=?",source.sourceId).single().single())
        }
    }

    @Test fun acceptedOlderThanRawPagesAndTopicOnlyHitNeverInventsAnOriginalOffset():Unit=runBlocking {
        UnifiedKeywordFixture().use {h->
            val old=SourceVersion(h.f.entry(text="我的合成杯子是绿色🙂"),1)
            val accepted=create(h,old,topic="合成办公物品")
            val newer=SourceVersion(h.f.entry(text="我的合成杯子是绿色🙂"),1)
            repeat(20){create(h,newer,false,at=100+it.toLong())}
            val result=h.repo.search("办公",UnifiedKeywordScope.FACT)
            val hit=result.groups.single().items.single() as UnifiedKeywordEvidence.Fact
            assertEquals(accepted.id,hit.value.id)
            val location=com.t1doo.aiyou.records.unifiedOriginalLocation(hit,"办公")!!
            assertEquals(accepted.current!!.content.reference.start,location.startCodePoint)
            assertEquals(accepted.current!!.content.reference.end,location.endCodePoint)
            assertFalse("A topic-only jump locates provenance without claiming an original keyword match",location.highlightRange)
            assertFalse(accepted.current!!.content.reference.quote.contains("办公"))
        }
    }

    @Test fun intervalOnlyCorrectionAndAbaCannotReviveAnOldUnifiedProof():Unit=runBlocking {
        UnifiedKeywordFixture().use {h->
            val source=SourceVersion(h.f.entry(text="我的合成杯子是绿色🙂"),1);val first=create(h,source)
            val proof=h.repo.search("绿色",UnifiedKeywordScope.FACT);val old=proof.groups.single().items.single()
            val revised=h.facts.mutate(FactMutation(first,FactAction.CORRECT,first.current!!.content.copy(interval=FactInterval("2026-10-24",null,"UTC")),null,FullBackupTest.uuid(),50,true))
            val accepted=h.facts.mutate(FactMutation(revised,FactAction.ACCEPT,null,null,FullBackupTest.uuid(),51))
            assertFalse(h.repo.resolve(proof,old){fail("Range-only stale proof")})
            val back=h.facts.mutate(FactMutation(accepted,FactAction.CORRECT,first.current!!.content,null,FullBackupTest.uuid(),52,true))
            h.facts.mutate(FactMutation(back,FactAction.ACCEPT,null,null,FullBackupTest.uuid(),53))
            assertFalse(h.repo.resolve(proof,old){fail("ABA stale proof")})
            assertEquals(1,h.repo.search("绿色",UnifiedKeywordScope.FACT).groups.size)
        }
    }

    @Test fun nonmatchingCipherCorruptionRejectsTheWholeResultInsteadOfPublishingPartialFacts():Unit=runBlocking {
        UnifiedKeywordFixture().use {h->
            val source=SourceVersion(h.f.entry(text="我的合成杯子是绿色🙂"),1);create(h,source)
            val second=SourceVersion(h.f.entry(text="我的合成杯子是绿色🙂"),1);val hidden=create(h,second,topic="不匹配主题")
            val bytes=h.f.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",hidden.id).single().single() as ByteArray
            bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
            h.f.db.write("UPDATE local_candidate_batches SET version=version+1,payload=? WHERE id=?",bytes,hidden.id)
            assertThrows(FactUnavailable::class.java){runBlocking{h.repo.search("合成办公",UnifiedKeywordScope.FACT)}}
            assertEquals(2L,h.f.db.read("SELECT count(*) FROM entries").single().single())
        }
    }

    @Test fun finalCommonCommitAcknowledgementIsRequiredBeforeReturningAnyFactSnapshot():Unit=runBlocking {
        UnifiedKeywordFixture().use {h->
            create(h,SourceVersion(h.f.entry(text="我的合成杯子是绿色🙂"),1))
            var commonSourceChecked=false;var failedAcks=0;var result:UnifiedKeywordSnapshot?=null
            h.f.db.beforeSql={sql->if(sql.startsWith("SELECT e.currentRevision,r.contentHash,COALESCE"))commonSourceChecked=true}
            h.f.db.afterSql={sql->if(sql=="COMMIT"&&commonSourceChecked){failedAcks++;error("Synthetic final common commit ACK loss")}}
            try {assertThrows(Exception::class.java){runBlocking{result=h.repo.search("绿色",UnifiedKeywordScope.FACT)}}}
            finally{h.f.db.afterSql=null;h.f.db.beforeSql=null}
            assertTrue(commonSourceChecked);assertEquals(1,failedAcks);assertNull(result)
            assertEquals(1,h.repo.search("绿色",UnifiedKeywordScope.FACT).groups.size)
        }
    }

    @Test fun emptyCollectionChangedAfterInitialCommitCannotPassTheFinalCommonTransaction():Unit=runBlocking {
        UnifiedKeywordFixture().use {h->
            val source=SourceVersion(h.f.entry(text="我的合成杯子是绿色🙂"),1)
            val pending=create(h,source,false);var collectionRead=false;var committed=false;var changed=false
            h.f.db.beforeSql={sql->if(sql.contains("WHERE ruleVersion=6 AND state='READY' ORDER BY id LIMIT 129"))collectionRead=true}
            h.f.db.afterSql={sql->if(sql=="COMMIT"&&collectionRead)committed=true}
            try {
                assertThrows(UnifiedKeywordConflict::class.java){runBlocking{h.repo.search("绿色",UnifiedKeywordScope.FACT){
                    if(committed&&!changed){changed=true;h.facts.mutate(FactMutation(pending,FactAction.ACCEPT,null,null,FullBackupTest.uuid(),40))}
                }}}
            }finally{h.f.db.beforeSql=null;h.f.db.afterSql=null}
            assertTrue(changed);assertEquals(1,h.repo.search("绿色",UnifiedKeywordScope.FACT).groups.size)
        }
    }
    @Test fun foreignOwnerAndForgedPublicFactSnapshotNeverReceiveResolveAuthority():Unit=runBlocking {
        UnifiedKeywordFixture().use{h->
            create(h,SourceVersion(h.f.entry(text="我的合成杯子是绿色🙂"),1))
            val proof=h.repo.search("绿色",UnifiedKeywordScope.FACT);val hit=proof.groups.single().items.single()
            val cold=SqlUnifiedKeywordRepository(h.f.gateway,h.originals,h.audios,h.images,h.preferences,h.memories,h.facts,h.f.tags,h.people)
            assertFalse(cold.resolve(proof,hit){fail("Foreign owner proof")})
            val fake=object:UnifiedKeywordSnapshot {override val keyword=proof.keyword;override val scope=proof.scope;override val groups=proof.groups;override val bindings=proof.bindings}
            assertFalse(h.repo.resolve(fake,hit){fail("Public properties are not an owner proof")})
            assertTrue(h.repo.resolve(proof,hit){})
        }
    }

    private fun create(h:UnifiedKeywordFixture,source:SourceVersion,accepted:Boolean=true,topic:String="合成办公物品",at:Long=10):SourceFactSnapshot {
        val p=h.facts.preview(source)
        val row=h.facts.create(FactCreate(FullBackupTest.uuid(),p,FullBackupTest.uuid(),topic,FactContent(SourceMemoryRules.reference(p,"我的合成杯子是绿色🙂")),true,FullBackupTest.uuid(),at))
        return if(accepted)h.facts.mutate(FactMutation(row,FactAction.ACCEPT,null,null,FullBackupTest.uuid(),at+1))else row
    }
}
