package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SourceFactSqlTest {
    @Test fun separateContentReviewUnknownIntervalAndColdReopen():Unit=Fixture().use { f ->
        val old=f.base.snapshotOld();val request=f.request();val proposed=f.store.create(request)
        assertEquals(FactReview.PROPOSED,proposed.review!!.review);assertEquals(FactInterval(),proposed.current!!.content.interval)
        assertEquals("我不喝咖啡🙂",proposed.current!!.content.reference.quote)
        val bytes=f.bytes(proposed.id);assertTrue(f.store.create(request).same(proposed));assertArrayEquals(bytes,f.bytes(proposed.id))
        val accepted=f.store.mutate(change(proposed,FactAction.ACCEPT))
        assertEquals(proposed.contents,accepted.contents);assertEquals(1,accepted.contents.size)
        assertEquals(FactReview.ACCEPTED,accepted.review!!.review);assertEquals(old,f.base.snapshotOld())
        assertFalse(f.bytes(proposed.id).toString(Charsets.UTF_8).contains("咖啡"))
        f.reopen();assertTrue(f.store.facts(f.source).single().same(accepted))
        val revoke=change(accepted,FactAction.REVOKE);val withdrawn=f.store.mutate(revoke)
        assertEquals(FactReview.REVOKED,withdrawn.review!!.review);assertTrue(f.store.capture(FactReview.ACCEPTED).items.isEmpty())
        assertThrows(FactUnavailable::class.java) { f.store.mutate(change(withdrawn,FactAction.ACCEPT)) }
    }
    @Test fun correctionRetainsOldReviewAndCannotInheritAcceptanceOrExceedEight():Unit=Fixture().use { f ->
        var item=f.store.mutate(change(f.store.create(f.request()),FactAction.ACCEPT))
        repeat(7) { n ->
            val content=item.current!!.content.copy(interval=FactInterval("2026-10-${10+n}",null,"Asia/Shanghai"))
            val corrected=f.store.mutate(change(item,FactAction.CORRECT,content=content))
            assertEquals(FactReview.PROPOSED,corrected.review!!.review)
            assertEquals(FactReview.ACCEPTED,corrected.reviews[corrected.reviews.lastIndex-1].priorReview)
            assertEquals(item.current,corrected.contents[corrected.contents.lastIndex-1])
            item=f.store.mutate(change(corrected,FactAction.ACCEPT))
        }
        assertEquals(8,item.contents.size)
        assertThrows(FactLimit::class.java) { f.store.mutate(change(item,FactAction.CORRECT,content=item.current!!.content.copy(interval=FactInterval()))) }
        assertEquals(FactReview.REVOKED,f.store.mutate(change(item,FactAction.REVOKE)).review!!.review)
        assertThrows(Exception::class.java) { FactInterval("2026-02-30",null,"UTC") }
        assertThrows(IllegalArgumentException::class.java) { FactInterval("2026-01-01","2026-01-01","UTC") }
        assertThrows(IllegalArgumentException::class.java) { FactInterval(null,null,"UTC") }
        assertThrows(IllegalArgumentException::class.java) { FactInterval("2026-10-21",null,"+08:00") }
    }
    @Test fun twoDirectionsRemainIndependentAcrossABBCAndOldReceiptCannotResurrect():Unit=Fixture().use { f ->
        val a=f.store.mutate(change(f.store.create(f.request()),FactAction.ACCEPT))
        val b=f.store.create(f.request(topic=a));val ab=change(b,FactAction.ACCEPT,a);val acceptedB=f.store.mutate(ab)
        val oldA=f.store.facts(f.source).single { it.id==a.id };val incoming=acceptedB.review!!.predecessor!!
        assertEquals(a.id,incoming.id);assertEquals(b.id,oldA.review!!.successor!!.id)
        val c=f.store.create(f.request(topic=acceptedB));val bc=change(c,FactAction.ACCEPT,acceptedB);val acceptedC=f.store.mutate(bc)
        val oldB=f.store.facts(f.source).single { it.id==b.id }
        assertEquals(incoming,oldB.review!!.predecessor);assertEquals(c.id,oldB.review!!.successor!!.id)
        assertNotEquals(incoming.operationId,oldB.review!!.successor!!.operationId)
        assertEquals(b.id,acceptedC.review!!.predecessor!!.id)
        val before=f.bytes(b.id);assertTrue(f.store.mutate(bc).same(acceptedC));assertArrayEquals(before,f.bytes(b.id))
        assertThrows(FactConflict::class.java) { f.store.mutate(ab) }
        assertEquals(listOf(c.id),f.store.capture(FactReview.ACCEPTED).items.map { it.id })
    }
    @Test fun correctionKeepsB1PredecessorAndB2StartsUnacceptedWithoutCopiedEdge():Unit=Fixture().use { f ->
        val a=f.store.mutate(change(f.store.create(f.request()),FactAction.ACCEPT))
        val b=f.store.mutate(change(f.store.create(f.request(topic=a)),FactAction.ACCEPT,a))
        val b2=f.store.mutate(change(b,FactAction.CORRECT,content=b.current!!.content.copy(interval=FactInterval("2026-10-21",null,"UTC"))))
        assertEquals(b.review!!.predecessor,b2.reviews.first().predecessor);assertNull(b2.review!!.predecessor)
        assertTrue(f.store.capture(FactReview.ACCEPTED).items.isEmpty())
        val accepted=f.store.mutate(change(b2,FactAction.ACCEPT));val c=f.store.create(f.request(topic=accepted))
        f.store.mutate(change(c,FactAction.ACCEPT,accepted));val finalB=f.store.facts(f.source).single { it.id==b.id }
        assertEquals(a.id,finalB.reviews[0].predecessor!!.id);assertNull(finalB.reviews[1].predecessor)
        assertEquals(c.id,finalB.reviews[1].successor!!.id)
        assertEquals(2,f.store.facts(f.source).single { it.id==c.id }.review!!.predecessor!!.contentRevision)
    }
    @Test fun secondCASFailureRollsBackAndLostCommitAcknowledgementReplaysBothRowsOnce():Unit=Fixture().use { f ->
        val a=f.store.mutate(change(f.store.create(f.request()),FactAction.ACCEPT));val b=f.store.create(f.request(topic=a))
        val request=change(b,FactAction.ACCEPT,a);val beforeA=f.bytes(a.id);val beforeB=f.bytes(b.id)
        var writes=0;f.base.db.beforeSql={ sql -> if(sql.startsWith("UPDATE local_candidate_batches SET version") && ++writes==2)error("Synthetic second CAS failure") }
        assertThrows(FactUnavailable::class.java) { f.store.mutate(request) };f.base.db.beforeSql=null
        assertArrayEquals(beforeA,f.bytes(a.id));assertArrayEquals(beforeB,f.bytes(b.id))
        var lost=false;f.base.db.afterSql={ if(it=="COMMIT" && !lost) {lost=true;error("Synthetic lost commit ACK")} }
        assertThrows(FactUnavailable::class.java) { f.store.mutate(request) };f.base.db.afterSql=null
        val savedA=f.bytes(a.id);val savedB=f.bytes(b.id);val actual=f.store.mutate(request)
        assertEquals(FactReview.ACCEPTED,actual.review!!.review);assertArrayEquals(savedA,f.bytes(a.id));assertArrayEquals(savedB,f.bytes(b.id))
        assertThrows(FactConflict::class.java) { f.store.mutate(FactMutation(b,FactAction.ACCEPT,null,a,request.mutationId,request.requestedAtMillis+1)) }
    }
    @Test fun independentSqlConnectionsCompeteForSameTopicWithoutTwoAccepted():Unit=Fixture().use { f ->
        val a=f.store.create(f.request());val b=f.store.create(f.request(topic=a));val second=f.base.second()
        val other=SqlSourceFactRepository(DrainingRecordsSqlDatabase(second),LocalCandidateFixture.KEY)
        val pool=Executors.newFixedThreadPool(2);val start=CountDownLatch(1)
        try {
            val futures=listOf(a to f.store,b to other).map { (item,store) -> pool.submit<Boolean> { start.await();try { store.mutate(change(item,FactAction.ACCEPT));true } catch(_:FactConflict) {false} } }
            start.countDown();assertEquals(1,futures.count { it.get(10,TimeUnit.SECONDS) })
            assertEquals(1,f.store.capture(FactReview.ACCEPTED).items.size)
        } finally { pool.shutdownNow();other.wipeKey();second.close() }
    }
    @Test fun profilesAreIsolatedTwentiethCreateReplayAndCipherBindingRejectsTampering():Unit=Fixture().use { f ->
        val legacy=f.base.ready("legacy");val old=f.base.db.read("SELECT payload FROM local_candidate_batches WHERE id='legacy'").single().single() as ByteArray
        var request=f.request();repeat(20) { request=f.request();f.store.create(request) }
        val bytes=f.bytes(request.id);f.store.create(request);assertArrayEquals(bytes,f.bytes(request.id))
        assertEquals(listOf(legacy.id),f.base.store.batches(f.source).map { it.id });assertArrayEquals(old,f.base.db.read("SELECT payload FROM local_candidate_batches WHERE id='legacy'").single().single() as ByteArray)
        assertThrows(FactLimit::class.java) { f.store.create(f.request()) }
        val corrupted=bytes.copyOf();corrupted[corrupted.lastIndex]=(corrupted.last().toInt() xor 1).toByte()
        f.base.db.write("UPDATE local_candidate_batches SET version=version+1,payload=? WHERE id=?",corrupted,request.id)
        assertThrows(FactUnavailable::class.java) { f.store.captureSource(f.source) }
        val wrong=SqlSourceFactRepository(f.base.gateway,ByteArray(32))
        try { assertThrows(FactUnavailable::class.java) { wrong.facts(f.source) } } finally {wrong.wipeKey()}
    }
    @Test fun changedSourceInvalidatesEmptyPayloadAndOldPageCannotPublishOrResurrect():Unit=Fixture().use { f ->
        val item=f.store.mutate(change(f.store.create(f.request()),FactAction.ACCEPT));val page=f.store.capture(FactReview.ACCEPTED)
        f.base.revise();var published=false
        assertFalse(f.store.resolve(page) { published=true });assertFalse(published)
        assertThrows(FactConflict::class.java) { f.store.mutate(change(item,FactAction.REVOKE)) }
        val physical=f.base.db.read("SELECT state,length(payload) FROM local_candidate_batches WHERE id=?",item.id).single()
        assertEquals(listOf("INVALIDATED",0L),physical)
        f.base.db.write("UPDATE entries SET currentRevision=1 WHERE id='source'")
        assertTrue(f.store.facts(f.source).isEmpty());assertThrows(FactConflict::class.java) { f.store.mutate(change(item,FactAction.REVOKE)) }
    }
    @Test fun deletedHistoricalEndpointDoesNotResurrectOrRevealOldSource():Unit=Fixture().use { f ->
        val a=f.store.mutate(change(f.store.create(f.request()),FactAction.ACCEPT));val b=f.store.mutate(change(f.store.create(f.request(topic=a)),FactAction.ACCEPT,a))
        f.base.db.write("DELETE FROM local_candidate_batches WHERE id=?",a.id)
        var called=false;assertTrue(f.store.resolveEndpoint(b,b.review!!.predecessor!!) { called=true;assertNull(it) });assertTrue(called)
        assertEquals(listOf(b.id),f.store.capture(FactReview.ACCEPTED).items.map { it.id })
    }
    @Test fun independentReplacementsAndCorrectionsUseBothExpectedRowVersions():Unit=Fixture().use { f ->
        val a=f.store.mutate(change(f.store.create(f.request()),FactAction.ACCEPT))
        val b=f.store.create(f.request(topic=a));val c=f.store.create(f.request(topic=a))
        val second=f.base.second();val other=SqlSourceFactRepository(DrainingRecordsSqlDatabase(second),LocalCandidateFixture.KEY)
        val pool=Executors.newFixedThreadPool(2);val start=CountDownLatch(1)
        try {
            val futures=listOf(b to f.store,c to other).map { (item,store) -> pool.submit<Boolean> { start.await();try {store.mutate(change(item,FactAction.ACCEPT,a));true} catch(_:FactConflict) {false} } }
            start.countDown();assertEquals(1,futures.count {it.get(10,TimeUnit.SECONDS)})
            val active=f.store.capture(FactReview.ACCEPTED).items.single()
            val pending=f.store.facts(f.source).single {it.review!!.review==FactReview.PROPOSED}
            val correction=change(pending,FactAction.CORRECT,content=pending.current!!.content.copy(interval=FactInterval("2026-10-21",null,"UTC")))
            val corrected=other.mutate(correction)
            assertThrows(FactConflict::class.java) {f.store.mutate(change(pending,FactAction.ACCEPT,active))}
            assertEquals(FactReview.PROPOSED,corrected.review!!.review)
            assertEquals(active.id,f.store.capture(FactReview.ACCEPTED).items.single().id)
        } finally {pool.shutdownNow();other.wipeKey();second.close()}
    }
    @Test fun revokedReplacementCanBeCorrectedWithoutMovingItsPredecessorToNewContent():Unit=Fixture().use { f ->
        val a=f.store.mutate(change(f.store.create(f.request()),FactAction.ACCEPT))
        val b=f.store.mutate(change(f.store.create(f.request(topic=a)),FactAction.ACCEPT,a))
        val revoked=f.store.mutate(change(b,FactAction.REVOKE))
        val corrected=f.store.mutate(change(revoked,FactAction.CORRECT,content=revoked.current!!.content.copy(interval=FactInterval("2026-10-21",null,"UTC"))))
        assertEquals(FactReview.REVOKED,corrected.reviews.first().priorReview)
        assertEquals(a.id,corrected.reviews.first().predecessor!!.id);assertNull(corrected.review!!.predecessor)
        assertEquals(FactReview.PROPOSED,corrected.review!!.review)
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
            assertThrows(FactConflict::class.java) {f.store.create(request)}
            assertThrows(FactConflict::class.java) {f.store.mutate(change(item,FactAction.ACCEPT))}
            if(mode==3)assertThrows(FactConflict::class.java) {f.store.capture(FactReview.ACCEPTED)}
            else assertTrue(f.store.capture(FactReview.ACCEPTED).items.isEmpty())
            if(mode<3)assertEquals(listOf("INVALIDATED",0L),f.base.db.read("SELECT state,length(payload) FROM local_candidate_batches WHERE id=?",item.id).single())
        }
    }
    @Test fun eventProfileAndExcerptProfileCannotAcquireFactAuthority():Unit=Fixture().use { f ->
        val memories=SqlSourceMemoryRepository(f.base.gateway,LocalCandidateFixture.KEY)
        try {
            val p=memories.preview(f.source);val event=memories.create(SourceMemoryCreate("event",p,SourceMemoryContent(SourceMemoryRules.reference(p,"我不喝咖啡🙂"),SourceMemoryKind.EVENT,SourceMemoryAssertion.SELF_REPORT,"人工合成分类"),"event-create",20))
            f.base.ready("excerpt");val fact=f.store.create(f.request())
            assertEquals(listOf(event.id),memories.memories(f.source).map {it.id});assertEquals(listOf(fact.id),f.store.facts(f.source).map {it.id})
            assertEquals(listOf("excerpt"),f.base.store.batches(f.source).map {it.id})
        } finally {memories.wipeKey()}
    }
    @Test fun crossSourceReplacementSurvivesOldSourceErasureButCannotReplayItsLostReceipt():Unit=Fixture().use { f ->
        val a=f.store.mutate(change(f.store.create(f.request()),FactAction.ACCEPT))
        val other=SourceVersion("synthetic-second-source",1);val text="合成本人自述：我平时更喜欢淡茶。"
        f.base.db.transaction {sql ->
            sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,6,'UTC','LOCAL_ONLY')",listOf(other.sourceId))
            sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,6)",listOf(other.sourceId,text,OriginalExports.hash(text)))
        }
        val p=f.store.preview(other)
        val b=f.store.create(FactCreate(id(),p,a.topicId!!,a.topicLabel!!,FactContent(SourceMemoryRules.reference(p,"我平时更喜欢淡茶")),true,id(),60,a))
        val replace=change(b,FactAction.ACCEPT,a);val accepted=f.store.mutate(replace)
        f.base.revise();assertEquals(accepted.id,f.store.capture(FactReview.ACCEPTED).items.single().id)
        assertThrows(FactConflict::class.java) {f.store.mutate(replace)}
        var unavailable=false;f.store.resolveEndpoint(accepted,accepted.review!!.predecessor!!) {unavailable=it==null};assertTrue(unavailable)
        f.base.db.write("DELETE FROM local_candidate_batches WHERE entryId='source'")
        assertTrue(f.store.facts(other).single().same(accepted))
        f.store.mutate(change(accepted,FactAction.REVOKE));assertTrue(f.store.capture(FactReview.ACCEPTED).items.isEmpty())
    }
    @Test fun keywordFindsAcceptedBehindRawPagesAndAuthenticatesQueryBeforeSourceJump():Unit=Fixture().use {f ->
        val accepted=f.store.mutate(change(f.store.create(f.request(at=10)),FactAction.ACCEPT))
        val other=SourceVersion("other",1);val preview=f.store.preview(other)
        repeat(20) {f.store.create(FactCreate(id(),preview,id(),"合成较新主题",FactContent(SourceMemoryRules.reference(preview,"同名合成来源")),true,id(),100+it.toLong()))}
        assertTrue(f.store.capture(FactReview.ACCEPTED).items.isEmpty()) // Old raw first page.
        val quote=f.store.searchAccepted("咖啡🙂");assertEquals(listOf(accepted.id),quote.items.map {it.id});assertNull(quote.next);assertEquals("咖啡🙂",quote.keyword)
        assertEquals(accepted.id,f.store.searchAccepted("合成口味").items.single().id)
        assertTrue(f.store.searchAccepted("同名合成来源").items.isEmpty()) // Pending never becomes evidence.
        var opened=false;assertTrue(f.store.resolve(quote,quote.items.single()) {opened=true});assertTrue(opened)
        val foreign=SqlSourceFactRepository(f.base.gateway,LocalCandidateFixture.KEY)
        try {assertFalse(foreign.resolve(quote) {fail("Foreign proof")})} finally {foreign.wipeKey()}
        f.store.mutate(change(accepted,FactAction.REVOKE))
        assertFalse(f.store.resolve(quote,quote.items.single()) {fail("Revoked source jump")})
        assertTrue(f.store.searchAccepted("咖啡").items.isEmpty())
        f.reopen();assertTrue(f.store.searchAccepted("合成口味").items.isEmpty())
    }
    @Test fun keywordPagesOnlyMatchedAcceptedRowsAndNeverResurrectsUnavailableSources():Unit {
        for(mode in 0..2) Fixture().use {f ->
            val made=mutableListOf<SourceFactSnapshot>()
            for(source in listOf(f.source,SourceVersion("other",1))) {
                val p=f.store.preview(source);val quote=if(source==f.source)"我不喝咖啡🙂" else "同名合成来源"
                repeat(if(source==f.source)20 else 2) {index ->
                    val row=f.store.create(FactCreate(id(),p,id(),"合成共同关键词",FactContent(SourceMemoryRules.reference(p,quote)),true,id(),if(source==f.source)10+index.toLong() else 100+index.toLong()))
                    made+=f.store.mutate(change(row,FactAction.ACCEPT))
                }
            }
            val first=f.store.searchAccepted("共同");val second=f.store.searchAccepted("共同",first.next)
            assertEquals(20,first.items.size);assertEquals(2,second.items.size);assertNull(second.next)
            assertEquals(made.map {it.id}.toSet(),(first.items+second.items).map {it.id}.toSet())
            when(mode) {
                0 -> {val policy=SqlLocalProcessingRepository(f.base.gateway);policy.change(LocalProcessingChange(policy.read(f.source),LocalProcessingChoice.AI_NONE,"search-off"))}
                1 -> f.base.revise()
                else -> f.base.db.write("INSERT INTO remote_source_access VALUES('source',1,1,NULL,NULL)")
            }
            assertFalse(f.store.resolve(first) {fail("Invalidated proof")})
            val fresh=f.store.searchAccepted("共同");assertEquals(2,fresh.items.size);assertTrue(fresh.items.none {it.source==f.source})
            assertNull(fresh.next)
        }
    }
    @Test fun keywordUsesOnlyCurrentAcceptedContentWithLiteralCaseSensitiveMatching():Unit=Fixture().use {f ->
        val a=f.store.mutate(change(f.store.create(f.request()),FactAction.ACCEPT))
        val b=f.store.create(f.request(a));val accepted=f.store.mutate(change(b,FactAction.ACCEPT,a))
        f.store.mutate(change(f.store.create(f.request()),FactAction.REJECT))
        val d=f.store.mutate(change(f.store.create(f.request()),FactAction.ACCEPT))
        val corrected=FactContent(d.current!!.content.reference,FactInterval("2026-10-21",null,"UTC"))
        f.store.mutate(change(d,FactAction.CORRECT,content=corrected))
        assertEquals(listOf(accepted.id),f.store.searchAccepted("咖啡").items.map {it.id})
        val p=f.store.preview(f.source)
        val english=f.store.create(FactCreate(id(),p,id(),"Coffee 普通口味",FactContent(SourceMemoryRules.reference(p,"我不喝咖啡🙂")),true,id(),60))
        f.store.mutate(change(english,FactAction.ACCEPT))
        assertEquals(english.id,f.store.searchAccepted("Coffee").items.single().id)
        assertTrue(f.store.searchAccepted("coffee").items.isEmpty())
        assertTrue(f.store.searchAccepted("咖 啡").items.isEmpty())
    }
    @Test fun keywordReadRejectsUnauthenticatedNonMatchingCipherAndMalformedInput():Unit=Fixture().use {f ->
        val row=f.store.create(f.request())
        assertTrue(f.store.searchAccepted("不存在").items.isEmpty())
        for(keyword in listOf("字".repeat(65),"\uD800","\uDC00","茶\n"))assertThrows(IllegalArgumentException::class.java) {f.store.searchAccepted(keyword)}
        assertTrue(f.store.searchAccepted("🙂".repeat(64)).items.isEmpty())
        val bytes=f.bytes(row.id);bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
        f.base.db.write("UPDATE local_candidate_batches SET version=version+1,payload=? WHERE id=?",bytes,row.id)
        assertThrows(FactUnavailable::class.java) {f.store.searchAccepted("不存在")}
    }
    private class Fixture:AutoCloseable {
        val base=LocalCandidateFixture();val source=base.source
        var store=SqlSourceFactRepository(base.gateway,LocalCandidateFixture.KEY)
        fun request(topic:SourceFactSnapshot?=null,at:Long=40):FactCreate { val p=store.preview(source)
            return FactCreate(id(),p,topic?.topicId ?: id(),topic?.topicLabel ?: "合成口味",FactContent(SourceMemoryRules.reference(p,"我不喝咖啡🙂")),true,id(),at,topic)
        }
        fun bytes(id:String)=base.db.read("SELECT payload FROM local_candidate_batches WHERE id=?",id).single().single() as ByteArray
        fun reopen() {store.wipeKey();base.reopen();store=SqlSourceFactRepository(base.gateway,LocalCandidateFixture.KEY)}
        override fun close() {store.wipeKey();base.close()}
    }
    companion object {
        fun id()=UUID.randomUUID().toString()
        fun change(item:SourceFactSnapshot,action:FactAction,replacing:SourceFactSnapshot?=null,content:FactContent?=null)=FactMutation(item,action,content,replacing,id(),50,true)
    }
}
