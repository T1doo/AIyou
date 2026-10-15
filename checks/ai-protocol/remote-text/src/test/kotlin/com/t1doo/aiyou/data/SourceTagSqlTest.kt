package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SourceTagSqlTest {
    @Test fun previewIsReadOnlyAndConfirmedTagSurvivesColdReopenAndActualTagLookup() = SourceTagFixture().use { f ->
        val before=f.base.snapshotOld();val p=f.preview();assertEquals(before,f.base.snapshotOld())
        assertEquals(listOf("旅行","生活"),p.proposals.map { it.name });assertTrue(p.tags.names.isEmpty())
        val request=f.request(p);val saved=f.store.apply(request);val bytes=f.bytes().copyOf()
        assertEquals(listOf("旅行"),saved.names);assertEquals(1L,saved.version);assertEquals(p.epoch+1,f.epoch())
        f.store.apply(request);assertArrayEquals(bytes,f.bytes());f.reopen()
        assertEquals(listOf("旅行"),f.tags.read("source").names)
        assertEquals(f.original,f.db.read("SELECT originalText FROM entry_revisions WHERE entryId='source' AND revision=2").single().single())
        assertTrue(f.db.read("SELECT * FROM local_candidate_batches").isEmpty());assertTrue(f.db.read("SELECT * FROM manual_tasks").isEmpty())
        f.db.transaction { sql -> sql.execute("INSERT INTO entry_revisions VALUES('source',3,?,?,6)",listOf("合成改写，不再包含标签行",OriginalExports.hash("合成改写，不再包含标签行")));sql.execute("UPDATE entries SET currentRevision=3 WHERE id='source'") }
        val revised=f.store.preview(SourceVersion("source",3));assertTrue(revised.proposals.isEmpty());assertEquals(listOf("旅行"),revised.tags.names)
        assertThrows(SourceTagConflict::class.java) { f.store.apply(request) }
        val search=SqlLocalSearchRepository(f.base.gateway,f.tags);search.rebuild()
        val results=search.candidates(LocalSearchRequest("",tag="旅行"));assertEquals(listOf("source"),results.entries.map { it.id })
        assertTrue(search.publishCurrent(results) {})
    }
    @Test fun lostCommitAckReplaysExactFullRequestButChangedTimeOrTargetCannotBorrowReceipt(): Unit = SourceTagFixture().use { f ->
        val p=f.preview();val mutation="测".repeat(43);val request=f.request(p,mutation=mutation);var lost=false
        f.db.afterSql={ sql -> if(sql=="COMMIT"&&!lost) { lost=true;throw IllegalStateException("Synthetic lost tag ack") } }
        assertThrows(SourceTagStorageFailure::class.java) { f.store.apply(request) };f.db.afterSql=null
        val bytes=f.bytes().copyOf();assertEquals(1L,f.store.apply(request).version);assertArrayEquals(bytes,f.bytes())
        assertThrows(SourceTagConflict::class.java) { f.store.apply(f.request(p,mutation=mutation,at=41)) }
        assertThrows(SourceTagConflict::class.java) { f.store.apply(f.request(p,index=1,mutation=mutation)) }
        assertThrows(SourceTagConflict::class.java) { f.store.apply(f.request(p,mutation="other-mutation")) }
        assertArrayEquals(bytes,f.bytes())
    }
    @Test fun absentAccessInsertDeleteAndUnrelatedWritesCannotReviveAnOldPreview(): Unit = SourceTagFixture().use { f ->
        f.db.transaction { it.execute("DELETE FROM remote_source_access WHERE entryId='source'") }
        val old=f.request();assertEquals(0L,old.preview.generation)
        f.db.transaction { sql -> sql.execute("INSERT INTO remote_source_access VALUES('source',77,0,NULL,NULL)");sql.execute("DELETE FROM remote_source_access WHERE entryId='source'") }
        assertThrows(SourceTagConflict::class.java) { f.store.apply(old) }
        val unrelated=f.request()
        f.db.transaction { it.execute("UPDATE entries SET capturedAtMillis=capturedAtMillis+1 WHERE id='other'") }
        assertEquals(unrelated.preview.epoch+2,f.epoch()) // Entry update also creates its source-access epoch.
        assertThrows(SourceTagConflict::class.java) { f.store.apply(unrelated) }
        assertTrue(f.tags.read("source").names.isEmpty())
    }
    @Test fun manualEditDuplicateAndRemovalWinOverStaleApplyWithoutMergingOrResurrection(): Unit = SourceTagFixture().use { f ->
        val p=f.preview();val old=f.request(p)
        f.tags.replace(ManualTagMutation("source",0,listOf("旅行"),"manual-write"))
        assertThrows(SourceTagConflict::class.java) { f.store.apply(old) }
        val present=f.preview();assertThrows(SourceTagConflict::class.java) { f.store.apply(f.request(present)) }
        val next=f.request(present,index=1,mutation="explicit-life");f.store.apply(next)
        assertEquals(listOf("旅行","生活"),f.tags.read("source").names)
        f.tags.replace(ManualTagMutation("source",2,emptyList(),"remove-both"))
        assertThrows(SourceTagConflict::class.java) { f.store.apply(next) }
        assertTrue(f.tags.read("source").names.isEmpty())
    }
    @Test fun forgedSourceAndProposalOrFullCapacityCannotWriteOrDeleteExistingTags(): Unit = SourceTagFixture().use { f ->
        val p=f.preview()
        val forged=SourceTagProposal("伪造",OriginalTextReference(p.source,0,3,"#伪造"))
        val cap=SourceTagPreview(p.source,p.sourceHash,p.generation,p.capturedAtMillis,p.captureTimeZone,p.policy,p.epoch,p.tags,listOf(forged))
        assertThrows(SourceTagConflict::class.java) { f.store.apply(SourceTagRequest(cap,forged,"forged",40)) }
        f.tags.replace(ManualTagMutation("source",0,(1..12).map { "已有"+it },"full"))
        val full=f.request();val bytes=f.bytes().copyOf();val e=f.epoch()
        assertThrows(SourceTagLimit::class.java) { f.store.apply(full) };assertArrayEquals(bytes,f.bytes());assertEquals(e,f.epoch())
        f.db.transaction { sql -> sql.execute("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id='source'");sql.execute("UPDATE entries SET privacyPolicy='LOCAL_ONLY' WHERE id='source'") }
        assertThrows(SourceTagConflict::class.java) { f.store.apply(f.request(p)) }
    }
    @Test fun sqlAbortAndUnexpectedPostEpochRollBackTagCiphertextReceiptAndIndexTogether(): Unit = SourceTagFixture().use { f ->
        val request=f.request();val before=f.base.snapshotOld()
        f.db.transaction { it.execute("CREATE TRIGGER synthetic_source_tag_abort AFTER INSERT ON entry_manual_tags BEGIN SELECT RAISE(ABORT,'synthetic'); END") }
        assertThrows(SourceTagStorageFailure::class.java) { f.store.apply(request) }
        f.db.transaction { it.execute("DROP TRIGGER synthetic_source_tag_abort") };assertEquals(before,f.base.snapshotOld())
        f.db.transaction { it.execute("CREATE TRIGGER synthetic_extra_epoch AFTER INSERT ON entry_manual_tags BEGIN UPDATE local_search_epoch SET epoch=epoch+1 WHERE slot=1; END") }
        assertThrows(SourceTagStorageFailure::class.java) { f.store.apply(request) }
        f.db.transaction { it.execute("DROP TRIGGER synthetic_extra_epoch") };assertEquals(before,f.base.snapshotOld())
        assertEquals(listOf("旅行"),f.store.apply(request).names)
    }
    @Test fun independentOwnersHaveOneTagCasWinnerAndPermanentErasureFencesAllPreviews(): Unit = SourceTagFixture().use { f ->
        val p=f.preview();f.base.second().use { db2 ->
            val tags2=SqlManualTagRepository(db2,LocalCandidateFixture.KEY);val other=SqlSourceTagRepository(db2,tags2)
            val go=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
            try {
                val outcomes=listOf(f.store,other).mapIndexed { i,owner -> pool.submit<String> { go.await();try { owner.apply(f.request(p,mutation="owner-"+i));"saved" } catch(_:SourceTagConflict) { "conflict" } } }
                go.countDown();assertEquals(listOf("conflict","saved"),outcomes.map { it.get(3,TimeUnit.SECONDS) }.sorted())
            } finally { pool.shutdown();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));tags2.wipeKey() }
        }
        val after=f.request(index=1,mutation="after-winner")
        f.db.transaction { it.execute("INSERT INTO permanent_erasure_receipts VALUES('source',2,1,'synthetic-fence','PREPARED','','')") }
        assertThrows(SourceTagConflict::class.java) { f.store.preview(f.source) }
        assertThrows(SourceTagConflict::class.java) { f.store.apply(after) }
    }
}
