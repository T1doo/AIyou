package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SourcePreferenceSqlTest {
    @Test fun separateContentReviewUnknownIntervalAndColdReopen():Unit=Fixture().use { f ->
        val old=f.base.snapshotOld();val request=f.request();val proposed=f.store.create(request)
        assertEquals(PreferenceReview.PROPOSED,proposed.review!!.review);assertEquals(PreferenceInterval(),proposed.current!!.content.interval)
        assertEquals("我不喝咖啡🙂",proposed.current!!.content.reference.quote)
        val bytes=f.bytes(proposed.id);assertTrue(f.store.create(request).same(proposed));assertArrayEquals(bytes,f.bytes(proposed.id))
        val accepted=f.store.mutate(change(proposed,PreferenceAction.ACCEPT))
        assertEquals(proposed.contents,accepted.contents);assertEquals(1,accepted.contents.size)
        assertEquals(PreferenceReview.ACCEPTED,accepted.review!!.review);assertEquals(old,f.base.snapshotOld())
        assertFalse(f.bytes(proposed.id).toString(Charsets.UTF_8).contains("咖啡"))
        f.reopen();assertTrue(f.store.preferences(f.source).single().same(accepted))
        val revoke=change(accepted,PreferenceAction.REVOKE);val withdrawn=f.store.mutate(revoke)
        assertEquals(PreferenceReview.REVOKED,withdrawn.review!!.review);assertTrue(f.store.capture(PreferenceReview.ACCEPTED).items.isEmpty())
        assertThrows(PreferenceUnavailable::class.java) { f.store.mutate(change(withdrawn,PreferenceAction.ACCEPT)) }
    }
    @Test fun correctionRetainsOldReviewAndCannotInheritAcceptanceOrExceedEight():Unit=Fixture().use { f ->
        var item=f.store.mutate(change(f.store.create(f.request()),PreferenceAction.ACCEPT))
        repeat(7) { n ->
            val content=item.current!!.content.copy(interval=PreferenceInterval("2026-10-${10+n}",null,"Asia/Shanghai"))
            val corrected=f.store.mutate(change(item,PreferenceAction.CORRECT,content=content))
            assertEquals(PreferenceReview.PROPOSED,corrected.review!!.review)
            assertEquals(PreferenceReview.ACCEPTED,corrected.reviews[corrected.reviews.lastIndex-1].priorReview)
            assertEquals(item.current,corrected.contents[corrected.contents.lastIndex-1])
            item=f.store.mutate(change(corrected,PreferenceAction.ACCEPT))
        }
        assertEquals(8,item.contents.size)
        assertThrows(PreferenceLimit::class.java) { f.store.mutate(change(item,PreferenceAction.CORRECT,content=item.current!!.content.copy(interval=PreferenceInterval()))) }
        assertEquals(PreferenceReview.REVOKED,f.store.mutate(change(item,PreferenceAction.REVOKE)).review!!.review)
        assertThrows(Exception::class.java) { PreferenceInterval("2026-02-30",null,"UTC") }
        assertThrows(IllegalArgumentException::class.java) { PreferenceInterval("2026-01-01","2026-01-01","UTC") }
        assertThrows(IllegalArgumentException::class.java) { PreferenceInterval(null,null,"UTC") }
        assertThrows(IllegalArgumentException::class.java) { PreferenceInterval("2026-10-21",null,"+08:00") }
    }
    @Test fun twoDirectionsRemainIndependentAcrossABBCAndOldReceiptCannotResurrect():Unit=Fixture().use { f ->
        val a=f.store.mutate(change(f.store.create(f.request()),PreferenceAction.ACCEPT))
        val b=f.store.create(f.request(topic=a));val ab=change(b,PreferenceAction.ACCEPT,a);val acceptedB=f.store.mutate(ab)
        val oldA=f.store.preferences(f.source).single { it.id==a.id };val incoming=acceptedB.review!!.predecessor!!
        assertEquals(a.id,incoming.id);assertEquals(b.id,oldA.review!!.successor!!.id)
        val c=f.store.create(f.request(topic=acceptedB));val bc=change(c,PreferenceAction.ACCEPT,acceptedB);val acceptedC=f.store.mutate(bc)
        val oldB=f.store.preferences(f.source).single { it.id==b.id }
        assertEquals(incoming,oldB.review!!.predecessor);assertEquals(c.id,oldB.review!!.successor!!.id)
        assertNotEquals(incoming.operationId,oldB.review!!.successor!!.operationId)
        assertEquals(b.id,acceptedC.review!!.predecessor!!.id)
        val before=f.bytes(b.id);assertTrue(f.store.mutate(bc).same(acceptedC));assertArrayEquals(before,f.bytes(b.id))
        assertThrows(PreferenceConflict::class.java) { f.store.mutate(ab) }
        assertEquals(listOf(c.id),f.store.capture(PreferenceReview.ACCEPTED).items.map { it.id })
    }
    @Test fun correctionKeepsB1PredecessorAndB2StartsUnacceptedWithoutCopiedEdge():Unit=Fixture().use { f ->
        val a=f.store.mutate(change(f.store.create(f.request()),PreferenceAction.ACCEPT))
        val b=f.store.mutate(change(f.store.create(f.request(topic=a)),PreferenceAction.ACCEPT,a))
        val b2=f.store.mutate(change(b,PreferenceAction.CORRECT,content=b.current!!.content.copy(interval=PreferenceInterval("2026-10-21",null,"UTC"))))
        assertEquals(b.review!!.predecessor,b2.reviews.first().predecessor);assertNull(b2.review!!.predecessor)
        assertTrue(f.store.capture(PreferenceReview.ACCEPTED).items.isEmpty())
        val accepted=f.store.mutate(change(b2,PreferenceAction.ACCEPT));val c=f.store.create(f.request(topic=accepted))
        f.store.mutate(change(c,PreferenceAction.ACCEPT,accepted));val finalB=f.store.preferences(f.source).single { it.id==b.id }
        assertEquals(a.id,finalB.reviews[0].predecessor!!.id);assertNull(finalB.reviews[1].predecessor)
        assertEquals(c.id,finalB.reviews[1].successor!!.id)
        assertEquals(2,f.store.preferences(f.source).single { it.id==c.id }.review!!.predecessor!!.contentRevision)
    }
    @Test fun secondCASFailureRollsBackAndLostCommitAcknowledgementReplaysBothRowsOnce():Unit=Fixture().use { f ->
        val a=f.store.mutate(change(f.store.create(f.request()),PreferenceAction.ACCEPT));val b=f.store.create(f.request(topic=a))
        val request=change(b,PreferenceAction.ACCEPT,a);val beforeA=f.bytes(a.id);val beforeB=f.bytes(b.id)
        var writes=0;f.base.db.beforeSql={ sql -> if(sql.startsWith("UPDATE local_candidate_batches SET version") && ++writes==2)error("Synthetic second CAS failure") }
        assertThrows(PreferenceUnavailable::class.java) { f.store.mutate(request) };f.base.db.beforeSql=null
        assertArrayEquals(beforeA,f.bytes(a.id));assertArrayEquals(beforeB,f.bytes(b.id))
        var lost=false;f.base.db.afterSql={ if(it=="COMMIT" && !lost) {lost=true;error("Synthetic lost commit ACK")} }
        assertThrows(PreferenceUnavailable::class.java) { f.store.mutate(request) };f.base.db.afterSql=null
        val savedA=f.bytes(a.id);val savedB=f.bytes(b.id);val actual=f.store.mutate(request)
        assertEquals(PreferenceReview.ACCEPTED,actual.review!!.review);assertArrayEquals(savedA,f.bytes(a.id));assertArrayEquals(savedB,f.bytes(b.id))
        assertThrows(PreferenceConflict::class.java) { f.store.mutate(PreferenceMutation(b,PreferenceAction.ACCEPT,null,a,request.mutationId,request.requestedAtMillis+1)) }
    }
    @Test fun independentSqlConnectionsCompeteForSameTopicWithoutTwoAccepted():Unit=Fixture().use { f ->
        val a=f.store.create(f.request());val b=f.store.create(f.request(topic=a));val second=f.base.second()
        val other=SqlSourcePreferenceRepository(DrainingRecordsSqlDatabase(second),LocalCandidateFixture.KEY)
        val pool=Executors.newFixedThreadPool(2);val start=CountDownLatch(1)
        try {
            val futures=listOf(a to f.store,b to other).map { (item,store) -> pool.submit<Boolean> { start.await();try { store.mutate(change(item,PreferenceAction.ACCEPT));true } catch(_:PreferenceConflict) {false} } }
            start.countDown();assertEquals(1,futures.count { it.get(10,TimeUnit.SECONDS) })
            assertEquals(1,f.store.capture(PreferenceReview.ACCEPTED).items.size)
        } finally { pool.shutdownNow();other.wipeKey();second.close() }
    }
    @Test fun profilesAreIsolatedTwentiethCreateReplayAndCipherBindingRejectsTampering():Unit=Fixture().use { f ->
        val legacy=f.base.ready("legacy");val old=f.base.db.read("SELECT payload FROM local_candidate_batches WHERE id='legacy'").single().single() as ByteArray
        var request=f.request();repeat(20) { request=f.request();f.store.create(request) }
        val bytes=f.bytes(request.id);f.store.create(request);assertArrayEquals(bytes,f.bytes(request.id))
        assertEquals(listOf(legacy.id),f.base.store.batches(f.source).map { it.id });assertArrayEquals(old,f.base.db.read("SELECT payload FROM local_candidate_batches WHERE id='legacy'").single().single() as ByteArray)
        assertThrows(PreferenceLimit::class.java) { f.store.create(f.request()) }
        val corrupted=bytes.copyOf();corrupted[corrupted.lastIndex]=(corrupted.last().toInt() xor 1).toByte()
        f.base.db.write("UPDATE local_candidate_batches SET version=version+1,payload=? WHERE id=?",corrupted,request.id)
        assertThrows(PreferenceUnavailable::class.java) { f.store.captureSource(f.source) }
        val wrong=SqlSourcePreferenceRepository(f.base.gateway,ByteArray(32))
        try { assertThrows(PreferenceUnavailable::class.java) { wrong.preferences(f.source) } } finally {wrong.wipeKey()}
    }
    @Test fun changedSourceInvalidatesEmptyPayloadAndOldPageCannotPublishOrResurrect():Unit=Fixture().use { f ->
        val item=f.store.mutate(change(f.store.create(f.request()),PreferenceAction.ACCEPT));val page=f.store.capture(PreferenceReview.ACCEPTED)
        f.base.revise();var published=false
        assertFalse(f.store.resolve(page) { published=true });assertFalse(published)
        assertThrows(PreferenceConflict::class.java) { f.store.mutate(change(item,PreferenceAction.REVOKE)) }
        val physical=f.base.db.read("SELECT state,length(payload) FROM local_candidate_batches WHERE id=?",item.id).single()
        assertEquals(listOf("INVALIDATED",0L),physical)
        f.base.db.write("UPDATE entries SET currentRevision=1 WHERE id='source'")
        assertTrue(f.store.preferences(f.source).isEmpty());assertThrows(PreferenceConflict::class.java) { f.store.mutate(change(item,PreferenceAction.REVOKE)) }
    }
    @Test fun deletedHistoricalEndpointDoesNotResurrectOrRevealOldSource():Unit=Fixture().use { f ->
        val a=f.store.mutate(change(f.store.create(f.request()),PreferenceAction.ACCEPT));val b=f.store.mutate(change(f.store.create(f.request(topic=a)),PreferenceAction.ACCEPT,a))
        f.base.db.write("DELETE FROM local_candidate_batches WHERE id=?",a.id)
        var called=false;assertTrue(f.store.resolveEndpoint(b,b.review!!.predecessor!!) { called=true;assertNull(it) });assertTrue(called)
        assertEquals(listOf(b.id),f.store.capture(PreferenceReview.ACCEPTED).items.map { it.id })
    }
    @Test fun independentReplacementsAndCorrectionsUseBothExpectedRowVersions():Unit=Fixture().use { f ->
        val a=f.store.mutate(change(f.store.create(f.request()),PreferenceAction.ACCEPT))
        val b=f.store.create(f.request(topic=a));val c=f.store.create(f.request(topic=a))
        val second=f.base.second();val other=SqlSourcePreferenceRepository(DrainingRecordsSqlDatabase(second),LocalCandidateFixture.KEY)
        val pool=Executors.newFixedThreadPool(2);val start=CountDownLatch(1)
        try {
            val futures=listOf(b to f.store,c to other).map { (item,store) -> pool.submit<Boolean> { start.await();try {store.mutate(change(item,PreferenceAction.ACCEPT,a));true} catch(_:PreferenceConflict) {false} } }
            start.countDown();assertEquals(1,futures.count {it.get(10,TimeUnit.SECONDS)})
            val active=f.store.capture(PreferenceReview.ACCEPTED).items.single()
            val pending=f.store.preferences(f.source).single {it.review!!.review==PreferenceReview.PROPOSED}
            val correction=change(pending,PreferenceAction.CORRECT,content=pending.current!!.content.copy(interval=PreferenceInterval("2026-10-21",null,"UTC")))
            val corrected=other.mutate(correction)
            assertThrows(PreferenceConflict::class.java) {f.store.mutate(change(pending,PreferenceAction.ACCEPT,active))}
            assertEquals(PreferenceReview.PROPOSED,corrected.review!!.review)
            assertEquals(active.id,f.store.capture(PreferenceReview.ACCEPTED).items.single().id)
        } finally {pool.shutdownNow();other.wipeKey();second.close()}
    }
    @Test fun revokedReplacementCanBeCorrectedWithoutMovingItsPredecessorToNewContent():Unit=Fixture().use { f ->
        val a=f.store.mutate(change(f.store.create(f.request()),PreferenceAction.ACCEPT))
        val b=f.store.mutate(change(f.store.create(f.request(topic=a)),PreferenceAction.ACCEPT,a))
        val revoked=f.store.mutate(change(b,PreferenceAction.REVOKE))
        val corrected=f.store.mutate(change(revoked,PreferenceAction.CORRECT,content=revoked.current!!.content.copy(interval=PreferenceInterval("2026-10-21",null,"UTC"))))
        assertEquals(PreferenceReview.REVOKED,corrected.reviews.first().priorReview)
        assertEquals(a.id,corrected.reviews.first().predecessor!!.id);assertNull(corrected.review!!.predecessor)
        assertEquals(PreferenceReview.PROPOSED,corrected.review!!.review)
    }
    @Test fun policyTrashMetadataAndPermanentReceiptRetireAuthorityWithoutRestoration():Unit {
        for (mode in 0..3) Fixture().use { f ->
            f.base.db.write("INSERT INTO remote_source_access VALUES('source',1,0,NULL,NULL)")
            val request=f.request();val item=f.store.create(request)
            when(mode) {
                0 -> {f.base.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id='source'");f.base.db.write("UPDATE entries SET privacyPolicy='LOCAL_ONLY' WHERE id='source'")}
                1 -> {f.base.db.write("UPDATE remote_source_access SET deleted=1 WHERE entryId='source'");f.base.db.write("UPDATE remote_source_access SET deleted=0 WHERE entryId='source'")}
                2 -> {f.base.db.write("UPDATE entries SET captureTimeZone='UTC' WHERE id='source'");f.base.db.write("UPDATE entries SET captureTimeZone='Asia/Shanghai' WHERE id='source'")}
                else -> f.base.db.write("INSERT INTO permanent_erasure_receipts VALUES('source',1,1,'synthetic-erasure','DONE','','')")
            }
            assertThrows(PreferenceConflict::class.java) {f.store.create(request)}
            assertThrows(PreferenceConflict::class.java) {f.store.mutate(change(item,PreferenceAction.ACCEPT))}
            assertTrue(f.store.capture(PreferenceReview.ACCEPTED).items.isEmpty())
            if(mode<3)assertEquals(listOf("INVALIDATED",0L),f.base.db.read("SELECT state,length(payload) FROM local_candidate_batches WHERE id=?",item.id).single())
        }
    }
    @Test fun eventProfileAndExcerptProfileCannotAcquirePreferenceAuthority():Unit=Fixture().use { f ->
        val memories=SqlSourceMemoryRepository(f.base.gateway,LocalCandidateFixture.KEY)
        try {
            val p=memories.preview(f.source);val event=memories.create(SourceMemoryCreate("event",p,SourceMemoryContent(SourceMemoryRules.reference(p,"我不喝咖啡🙂"),SourceMemoryKind.EVENT,SourceMemoryAssertion.SELF_REPORT,"人工合成分类"),"event-create",20))
            f.base.ready("excerpt");val preference=f.store.create(f.request())
            assertEquals(listOf(event.id),memories.memories(f.source).map {it.id});assertEquals(listOf(preference.id),f.store.preferences(f.source).map {it.id})
            assertEquals(listOf("excerpt"),f.base.store.batches(f.source).map {it.id})
        } finally {memories.wipeKey()}
    }
    @Test fun crossSourceReplacementSurvivesOldSourceErasureButCannotReplayItsLostReceipt():Unit=Fixture().use { f ->
        val a=f.store.mutate(change(f.store.create(f.request()),PreferenceAction.ACCEPT))
        val other=SourceVersion("synthetic-second-source",1);val text="合成本人自述：我平时更喜欢淡茶。"
        f.base.db.transaction {sql ->
            sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,6,'UTC','LOCAL_ONLY')",listOf(other.sourceId))
            sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,6)",listOf(other.sourceId,text,OriginalExports.hash(text)))
        }
        val p=f.store.preview(other)
        val b=f.store.create(PreferenceCreate(id(),p,a.topicId!!,a.topicLabel!!,PreferenceContent(SourceMemoryRules.reference(p,"我平时更喜欢淡茶")),true,id(),60,a))
        val replace=change(b,PreferenceAction.ACCEPT,a);val accepted=f.store.mutate(replace)
        f.base.revise();assertEquals(accepted.id,f.store.capture(PreferenceReview.ACCEPTED).items.single().id)
        assertThrows(PreferenceConflict::class.java) {f.store.mutate(replace)}
        var unavailable=false;f.store.resolveEndpoint(accepted,accepted.review!!.predecessor!!) {unavailable=it==null};assertTrue(unavailable)
        f.base.db.write("DELETE FROM local_candidate_batches WHERE entryId='source'")
        assertTrue(f.store.preferences(other).single().same(accepted))
        f.store.mutate(change(accepted,PreferenceAction.REVOKE));assertTrue(f.store.capture(PreferenceReview.ACCEPTED).items.isEmpty())
    }
    private class Fixture:AutoCloseable {
        val base=LocalCandidateFixture();val source=base.source
        var store=SqlSourcePreferenceRepository(base.gateway,LocalCandidateFixture.KEY)
        fun request(topic:SourcePreferenceSnapshot?=null):PreferenceCreate { val p=store.preview(source)
            return PreferenceCreate(id(),p,topic?.topicId ?: id(),topic?.topicLabel ?: "合成口味",PreferenceContent(SourceMemoryRules.reference(p,"我不喝咖啡🙂")),true,id(),40,topic)
        }
        fun bytes(id:String)=base.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",id).single().single() as ByteArray
        fun reopen() {store.wipeKey();base.reopen();store=SqlSourcePreferenceRepository(base.gateway,LocalCandidateFixture.KEY)}
        override fun close() {store.wipeKey();base.close()}
    }
    companion object {
        fun id()=UUID.randomUUID().toString()
        fun change(item:SourcePreferenceSnapshot,action:PreferenceAction,replacing:SourcePreferenceSnapshot?=null,content:PreferenceContent?=null)=PreferenceMutation(item,action,content,replacing,id(),50,true)
    }
}
