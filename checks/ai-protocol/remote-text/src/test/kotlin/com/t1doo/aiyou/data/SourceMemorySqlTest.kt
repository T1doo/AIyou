package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SourceMemorySqlTest {
    @Test fun pendingAndAcceptedManualClassificationsColdReopenWithoutChangingOriginalTagsOrTasks():Unit=SourceMemoryFixture().use { f ->
        val old=f.base.snapshotOld();val p=f.store.preview(f.source);assertEquals(old,f.base.snapshotOld())
        val request=f.request(p=p);val pending=f.store.create(request)
        assertEquals(SourceMemoryReview.PROPOSED,pending.current!!.review);assertEquals(SourceMemoryAssertion.UNCERTAIN,pending.current!!.content.assertion)
        assertEquals(f.original,pending.current!!.content.reference.quote)
        val bytes=f.bytes().copyOf();f.store.create(request);assertArrayEquals(bytes,f.bytes())
        val accepted=f.store.mutate(f.change(pending,SourceMemoryAction.ACCEPT));assertEquals(SourceMemoryReview.ACCEPTED,accepted.current!!.review)
        assertEquals(old,f.base.snapshotOld());assertFalse(f.bytes().toString(Charsets.UTF_8).contains("人工合成备注"))
        f.reopen();val read=f.store.memories(f.source).single();assertEquals(accepted.versions,read.versions)
        assertEquals(SourceMemoryAssertion.UNCERTAIN,read.current!!.content.assertion)
        assertEquals(SourceMemoryKind.EVENT,read.current!!.content.kind);assertEquals(f.original,read.current!!.content.reference.quote)
        assertEquals(5L,read.capturedAtMillis);assertEquals("Asia/Shanghai",read.captureTimeZone)
        assertTrue(f.db.read("SELECT * FROM entry_manual_tags").isEmpty());assertTrue(f.db.read("SELECT * FROM manual_tasks").isEmpty())
    }
    @Test fun correctionPreservesSupersededReviewAndNeverAutoAcceptsOrAllowsOldReceiptResurrection():Unit=SourceMemoryFixture().use { f ->
        val pending=f.create();val accept=f.change(pending,SourceMemoryAction.ACCEPT);val accepted=f.store.mutate(accept)
        val correctedContent=accepted.current!!.content.copy(kind=SourceMemoryKind.OCCASION_EMOTION,assertion=SourceMemoryAssertion.CONTEXT,note="纠正：只是引用，未判断真实情绪")
        val correction=f.change(accepted,SourceMemoryAction.CORRECT,correctedContent,"correct")
        val corrected=f.store.mutate(correction);val bytes=f.bytes().copyOf();f.store.mutate(correction);assertArrayEquals(bytes,f.bytes())
        assertEquals(2,corrected.current!!.revision);assertEquals(SourceMemoryReview.PROPOSED,corrected.current!!.review)
        assertEquals(SourceMemoryReview.SUPERSEDED,corrected.versions.first().review);assertEquals(SourceMemoryReview.ACCEPTED,corrected.versions.first().priorReview)
        assertEquals(1,corrected.current!!.supersedesRevision);assertThrows(SourceMemoryConflict::class.java) { f.store.mutate(accept) }
        assertThrows(SourceMemoryConflict::class.java) { f.store.mutate(f.change(accepted,SourceMemoryAction.CORRECT,correctedContent.copy(note="改变请求"),"correct")) }
        val rejected=f.store.mutate(f.change(corrected,SourceMemoryAction.REJECT));assertEquals(SourceMemoryReview.REJECTED,rejected.current!!.review)
        assertThrows(SourceMemoryConflict::class.java) { f.store.mutate(f.change(rejected,SourceMemoryAction.ACCEPT)) }
        val reopened=f.store.mutate(f.change(rejected,SourceMemoryAction.REOPEN));val again=f.store.mutate(f.change(reopened,SourceMemoryAction.ACCEPT,mutation="fresh-accept"))
        val revoked=f.store.mutate(f.change(again,SourceMemoryAction.REVOKE));f.reopen()
        assertEquals(SourceMemoryReview.REVOKED,f.store.memories(f.source).single().current!!.review)
        assertThrows(SourceMemoryConflict::class.java) { f.store.mutate(correction) }
        assertEquals(SourceMemoryReview.PROPOSED,f.store.mutate(f.change(revoked,SourceMemoryAction.REOPEN,mutation="after-cold-reopen")).current!!.review)
    }
    @Test fun lostCommitAcknowledgementsReplayExactCreateAndCorrectionTuplesWithOriginalCiphertext():Unit=SourceMemoryFixture().use { f ->
        val create=f.request(mutation="测".repeat(43));var lost=false
        f.db.afterSql={ if(it=="COMMIT"&&!lost) { lost=true;throw IllegalStateException("Synthetic lost create ack") } }
        assertThrows(SourceMemoryStorageFailure::class.java) { f.store.create(create) };f.db.afterSql=null
        val bytes=f.bytes().copyOf();val pending=f.store.create(create);assertArrayEquals(bytes,f.bytes())
        assertThrows(SourceMemoryConflict::class.java) { f.store.create(f.request(p=create.preview,mutation=create.mutationId,at=41)) }
        val correct=f.change(pending,SourceMemoryAction.CORRECT,pending.current!!.content.copy(note="新人工备注"),"测".repeat(43))
        lost=false;f.db.afterSql={ if(it=="COMMIT"&&!lost) { lost=true;throw IllegalStateException("Synthetic lost correction ack") } }
        assertThrows(SourceMemoryStorageFailure::class.java) { f.store.mutate(correct) };f.db.afterSql=null
        val correctedBytes=f.bytes().copyOf();assertEquals(2,f.store.mutate(correct).current!!.revision);assertArrayEquals(correctedBytes,f.bytes())
        assertThrows(SourceMemoryConflict::class.java) { f.store.mutate(f.change(pending,SourceMemoryAction.CORRECT,correct.content,"different-id")) }
    }
    @Test fun physicalProfilesAreIsolatedBeforeLimitsAndTwentiethCreateCanReplayAtCapacity():Unit=SourceMemoryFixture().use { f ->
        val job=f.base.store.begin(LocalCandidateStart("legacy",f.source,9999));val excerpt=f.base.store.complete(job,LocalExcerptRules.extract(job))
        val oldBytes=f.bytes("legacy").copyOf();var last:SourceMemoryCreate?=null
        repeat(20) { n -> val r=f.request(id="memory-"+n,note="备注"+n,mutation="create-"+n,at=100+n.toLong());f.store.create(r);last=r }
        assertEquals(20,f.store.memories(f.source).size);assertEquals(listOf("legacy"),f.base.store.batches(f.source).map { it.id });assertArrayEquals(oldBytes,f.bytes("legacy"))
        val lastBytes=f.bytes(last!!.id).copyOf();f.store.create(last!!);assertArrayEquals(lastBytes,f.bytes(last!!.id))
        assertThrows(SourceMemoryLimit::class.java) { f.store.create(f.request(id="too-many")) }
        assertThrows(SourceMemoryConflict::class.java) { f.store.create(f.request(id=excerpt.id)) }
        assertThrows(LocalCandidateConflict::class.java) { f.base.store.begin(LocalCandidateStart(last!!.id,f.source,30)) }
        assertArrayEquals(oldBytes,f.bytes("legacy"))
    }
    @Test fun sourceEpochAbaEditPolicyAndTrashInvalidateSavedAndUncommittedAuthority():Unit=SourceMemoryFixture().use { f ->
        f.db.transaction { it.execute("DELETE FROM remote_source_access WHERE entryId='source'") }
        val delayed=f.request(id="delayed");val saved=f.create()
        f.db.transaction { sql -> sql.execute("INSERT INTO remote_source_access VALUES('source',77,0,NULL,NULL)");sql.execute("DELETE FROM remote_source_access WHERE entryId='source'") }
        assertThrows(SourceMemoryConflict::class.java) { f.store.create(delayed) };assertThrows(SourceMemoryConflict::class.java) { f.store.mutate(f.change(saved,SourceMemoryAction.ACCEPT)) }
        val invalidated=f.store.memories(f.source).single();assertEquals(SourceMemoryState.INVALIDATED,invalidated.state);assertTrue(f.bytes().isEmpty())
        assertTrue(invalidated.versions.isEmpty());assertNull(invalidated.current);assertNull(invalidated.capturedAtMillis);assertNull(invalidated.captureTimeZone)
        val fresh=f.create("fresh");f.db.transaction { sql -> sql.execute("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id='source'");sql.execute("UPDATE entries SET privacyPolicy='LOCAL_ONLY' WHERE id='source'") }
        assertThrows(SourceMemoryConflict::class.java) { f.store.mutate(f.change(fresh,SourceMemoryAction.ACCEPT)) }
        val latest=f.create("latest");f.db.transaction { it.execute("UPDATE remote_source_access SET deleted=1 WHERE entryId='source'") }
        assertThrows(SourceMemoryConflict::class.java) { f.store.preview(f.source) };assertThrows(SourceMemoryConflict::class.java) { f.store.mutate(f.change(latest,SourceMemoryAction.ACCEPT)) }
        f.db.transaction { sql -> sql.execute("UPDATE remote_source_access SET deleted=0 WHERE entryId='source'");sql.execute("INSERT INTO entry_revisions VALUES('source',3,?,?,6)",listOf("合成新原文",OriginalExports.hash("合成新原文")));sql.execute("UPDATE entries SET currentRevision=3 WHERE id='source'") }
        assertTrue(f.store.memories(SourceVersion("source",3)).isEmpty());assertTrue(f.db.read("SELECT payload FROM local_candidate_batches WHERE ruleVersion=2").all { (it.single() as ByteArray).isEmpty() })
    }
    @Test fun sqliteAbortRollsBackCreationCorrectionAndLatestReceiptWithoutPartialVersions():Unit=SourceMemoryFixture().use { f ->
        val request=f.request();val old=f.base.snapshotOld()
        f.db.transaction { it.execute("CREATE TRIGGER synthetic_memory_abort BEFORE INSERT ON local_candidate_batches WHEN NEW.ruleVersion=2 BEGIN SELECT RAISE(ABORT,'synthetic'); END") }
        assertThrows(SourceMemoryStorageFailure::class.java) { f.store.create(request) };assertTrue(f.db.read("SELECT * FROM local_candidate_batches").isEmpty())
        f.db.transaction { it.execute("DROP TRIGGER synthetic_memory_abort") };val saved=f.store.create(request);val bytes=f.bytes().copyOf()
        val correction=f.change(saved,SourceMemoryAction.CORRECT,saved.current!!.content.copy(note="纠正备注"))
        f.db.transaction { it.execute("CREATE TRIGGER synthetic_memory_update_abort AFTER UPDATE ON local_candidate_batches WHEN NEW.ruleVersion=2 BEGIN SELECT RAISE(ABORT,'synthetic'); END") }
        assertThrows(SourceMemoryStorageFailure::class.java) { f.store.mutate(correction) };assertArrayEquals(bytes,f.bytes());assertEquals(old,f.base.snapshotOld())
        f.db.transaction { it.execute("DROP TRIGGER synthetic_memory_update_abort") };assertEquals(2,f.store.mutate(correction).current!!.revision)
    }
    @Test fun forgedOrAmbiguousEvidenceAndCiphertextTamperingNeverBecomeAnEmptySuccess():Unit=SourceMemoryFixture().use { f ->
        val p=f.store.preview(f.source);val forged=f.content(p).copy(reference=OriginalTextReference(f.source,0,2,"伪造"))
        assertThrows(InvalidSourceMemory::class.java) { f.store.create(SourceMemoryCreate("forged",p,forged,"forged",40)) }
        val pending=f.create();assertThrows(InvalidSourceMemory::class.java) { f.store.mutate(f.change(pending,SourceMemoryAction.CORRECT,forged)) }
        val wrong=SqlSourceMemoryRepository(f.base.gateway,ByteArray(32) { 7 });try { assertThrows(SourceMemoryStorageFailure::class.java) { wrong.memories(f.source) } } finally { wrong.wipeKey() }
        f.db.transaction { it.execute("UPDATE local_candidate_batches SET version=version+1,payload=X'00' WHERE id='memory'") }
        assertThrows(SourceMemoryStorageFailure::class.java) { f.store.memories(f.source) }
        f.db.transaction { sql -> sql.execute("INSERT INTO entry_revisions VALUES('source',3,?,?,6)",listOf("合成 合成",OriginalExports.hash("合成 合成")));sql.execute("UPDATE entries SET currentRevision=3 WHERE id='source'") }
        val repeated=f.store.preview(SourceVersion("source",3));val ambiguous=SourceMemoryContent(OriginalTextReference(repeated.source,0,2,"合成"),SourceMemoryKind.EVENT,SourceMemoryAssertion.UNCERTAIN,"")
        assertThrows(InvalidSourceMemory::class.java) { f.store.create(SourceMemoryCreate("ambiguous",repeated,ambiguous,"ambiguous",60)) }
        val unique=SourceMemoryContent(SourceMemoryRules.reference(repeated,repeated.original),SourceMemoryKind.EVENT,SourceMemoryAssertion.UNCERTAIN,"")
        val valid=f.store.create(SourceMemoryCreate("unique",repeated,unique,"unique",61))
        assertThrows(InvalidSourceMemory::class.java) { f.store.mutate(f.change(valid,SourceMemoryAction.CORRECT,ambiguous)) }
    }
    @Test fun independentOwnersHaveOneCorrectionWinnerAndContentLimitStillAllowsRevocation():Unit=SourceMemoryFixture().use { f ->
        val pending=f.create();f.base.second().use { db2 ->
            val other=SqlSourceMemoryRepository(db2,LocalCandidateFixture.KEY);val pool=Executors.newFixedThreadPool(2);val go=CountDownLatch(1)
            try {
                val requests=listOf("并发纠正A","并发纠正B").mapIndexed { i,note -> f.change(pending,SourceMemoryAction.CORRECT,pending.current!!.content.copy(note=note),"concurrent-"+i) }
                val futures=listOf(f.store,other).mapIndexed { i,repo -> pool.submit<String> {
                    go.await();try { repo.mutate(requests[i]);"saved" } catch(_:SourceMemoryConflict) { "conflict" }
                } };go.countDown();val outcomes=futures.map { it.get(3,TimeUnit.SECONDS) };assertEquals(listOf("conflict","saved"),outcomes.sorted())
                val winner=outcomes.indexOf("saved");val loser=outcomes.indexOf("conflict");val read=f.store.memories(f.source).single()
                assertEquals(1L,f.db.read("SELECT COUNT(*) FROM local_candidate_batches WHERE id='memory' AND ruleVersion=2").single().single())
                assertEquals(2,read.versions.size);assertEquals(SourceMemoryReview.PROPOSED,read.current!!.review)
                assertEquals(SourceMemoryReview.SUPERSEDED,read.versions.first().review);assertEquals(SourceMemoryReview.PROPOSED,read.versions.first().priorReview)
                assertEquals(requests[winner].content,read.current!!.content)
                val bytes=f.bytes().copyOf();assertThrows(SourceMemoryConflict::class.java) { f.store.mutate(requests[loser]) };assertArrayEquals(bytes,f.bytes())
            } finally { pool.shutdown();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));other.wipeKey() }
        }
        var current=f.store.memories(f.source).single()
        repeat(6) { n -> current=f.store.mutate(f.change(current,SourceMemoryAction.CORRECT,current.current!!.content.copy(note="内容纠正"+n),"correct-"+n)) }
        assertEquals(8,current.versions.size)
        assertThrows(SourceMemoryLimit::class.java) { f.store.mutate(f.change(current,SourceMemoryAction.CORRECT,current.current!!.content.copy(note="第九版"),"ninth")) }
        current=f.store.mutate(f.change(current,SourceMemoryAction.ACCEPT,mutation="final-accept"))
        assertEquals(SourceMemoryReview.REVOKED,f.store.mutate(f.change(current,SourceMemoryAction.REVOKE)).current!!.review)
    }
    @Test fun actualTrashRestoreAndPermanentErasureClearAllProfilesAndFenceLateReviews():Unit=RecordFavoriteFixture().use { f ->
        f.db.transaction { sql -> LocalCandidateSqlSchema.migration9To10.forEach { sql.execute(it) };LocalCandidateSqlSchema.guards.forEach { sql.execute(it) } }
        val store=SqlSourceMemoryRepository(f.base.gateway,PermanentErasureFixture.KEY);val id=f.entry();val source=SourceVersion(id,1)
        try {
            val p=store.preview(source);val content=SourceMemoryContent(SourceMemoryRules.reference(p,p.original),SourceMemoryKind.EVENT,SourceMemoryAssertion.UNCERTAIN,"合成待删除备注")
            val request=SourceMemoryCreate("memory",p,content,"create",20);val pending=store.create(request)
            val accept=SourceMemoryMutation(pending.id,source,pending.sourceHash,pending.generation,pending.version,1,SourceMemoryReview.PROPOSED,SourceMemoryAction.ACCEPT,null,"accept",30)
            store.mutate(accept);val trashed=f.base.intent(id)
            assertTrue((f.db.read("SELECT payload FROM local_candidate_batches WHERE id='memory'").single().single() as ByteArray).isEmpty())
            assertThrows(SourceMemoryConflict::class.java) { store.mutate(accept) }
            val restored=f.base.bin.apply(RecycleMutation(source,trashed.generation,true,"restore"));assertEquals(ProcessingPolicy.AI_NONE,restored.entry.policy)
            assertNull(RemoteTextTransactions(f.base.gateway).updateAuthorization(source,restored.generation,ProcessingPolicy.LOCAL_ONLY,null,null))
            assertEquals(SourceMemoryState.INVALIDATED,store.memories(source).single().state)
            assertThrows(SourceMemoryConflict::class.java) { store.create(request) }
            val target=f.base.intent(id);assertTrue(kotlinx.coroutines.runBlocking { f.base.erase.erase(target) }.complete)
            assertTrue(f.db.read("SELECT * FROM local_candidate_batches").isEmpty());assertTrue(f.db.read("SELECT id FROM entries WHERE id=?",id).isEmpty())
            assertThrows(SourceMemoryConflict::class.java) { store.mutate(accept) };assertThrows(SourceMemoryConflict::class.java) { store.create(request) }
        } finally { store.wipeKey() }
    }
}
