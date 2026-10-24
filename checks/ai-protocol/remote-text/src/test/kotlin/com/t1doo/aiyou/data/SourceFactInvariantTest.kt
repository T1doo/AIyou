package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SourceFactInvariantTest {
    @Test fun rejectedRevokedAndSupersededHaveNoReopenOrImplicitAccept() {
        assertEquals(setOf("ACCEPT","REJECT","REVOKE","CORRECT"),FactAction.entries.map { it.name }.toSet())
        for (review in FactReview.entries) for (action in FactAction.entries) {
            val allowed=when(action) {
                FactAction.ACCEPT,FactAction.REJECT -> review==FactReview.PROPOSED
                FactAction.REVOKE -> review==FactReview.ACCEPTED
                FactAction.CORRECT -> review!=FactReview.SUPERSEDED
            }
            if(allowed) assertEquals(when(action) {
                FactAction.ACCEPT -> FactReview.ACCEPTED
                FactAction.REJECT -> FactReview.REJECTED
                FactAction.REVOKE -> FactReview.REVOKED
                FactAction.CORRECT -> FactReview.PROPOSED
            },FactRules.decision(review,action))
            else assertThrows(IllegalArgumentException::class.java) {FactRules.decision(review,action)}
        }
    }

    @Test fun sameCreateIntentCannotBypassAnAuthenticatedTwentyFirstReadyRow():Unit=FullFixture().use { f ->
        val repo=SqlSourceFactRepository(f.gateway,f.key)
        try {
            val first=SourceVersion(f.entry(text=TEXT),1);val second=SourceVersion(f.entry(text=TEXT),1)
            val request=request(repo,first);repo.create(request);repeat(19){repo.create(request(repo,first))}
            val last=repo.create(request(repo,second))
            val cells=f.db.read("SELECT id,entryId,sourceRevision,sourceHash,generation,ruleVersion,version,state,lastMutationId,createdAtMillis,payload FROM local_candidate_batches WHERE id=?",last.id).single()
            val portable=cells.toMutableList().also {it[1]=first.sourceId;it[10]=repo.portable(cells,false)}
            val rebound=portable.toMutableList().also {it[10]=repo.portable(portable,true)}
            try { f.db.transaction {sql ->
                sql.execute("DELETE FROM local_candidate_batches WHERE id=?",listOf(last.id))
                sql.execute("INSERT INTO local_candidate_batches VALUES(?,?,?,?,?,?,?,?,?,?,?)",rebound)
            } } finally {(portable[10] as ByteArray).fill(0);(rebound[10] as ByteArray).fill(0)}
            assertEquals(21L,f.db.read("SELECT count(*) FROM local_candidate_batches WHERE ruleVersion=6 AND entryId=? AND state='READY'",first.sourceId).single().single())
            assertThrows(FactLimit::class.java) {repo.create(request)}
            assertThrows(FactLimit::class.java) {repo.facts(first)}
        } finally {repo.wipeKey()}
    }

    @Test fun historicalEndpointUsesReciprocalHistoryRatherThanTheTargetsLastReceipt():Unit=FullFixture().use { f ->
        val repo=SqlSourceFactRepository(f.gateway,f.key)
        try {
            val source=SourceVersion(f.entry(text=TEXT),1)
            val a=repo.mutate(change(repo.create(request(repo,source)),FactAction.ACCEPT))
            val b=repo.mutate(change(repo.create(request(repo,source,a)),FactAction.ACCEPT,a))
            repo.mutate(change(repo.create(request(repo,source,b)),FactAction.ACCEPT,b))
            val oldA=repo.facts(source).single {it.id==a.id};val endpoint=oldA.review!!.successor!!
            var published:SourceFactSnapshot?=null
            assertTrue(repo.resolveEndpoint(oldA,endpoint){published=it})
            assertEquals(b.id,published!!.id)
            // B's last row receipt is now B->C, while its authenticated predecessor still proves A->B.
            val cells=f.db.read("SELECT id,entryId,sourceRevision,sourceHash,generation,ruleVersion,version,state,lastMutationId,createdAtMillis,payload FROM local_candidate_batches WHERE id=?",b.id).single()
            val bytes=repo.portable(cells,false)
            val payload=try {FactEncoding.decode(bytes.copyOfRange(1,bytes.size),source)} finally {bytes.fill(0)}
            val checkpoint=payload.reviews.single()
            val forged=payload.copy(expectedVersion=cells[6] as Long,reviews=listOf(checkpoint.copy(predecessor=checkpoint.predecessor!!.copy(operationId=id()))))
            val plain=byteArrayOf(6)+FactEncoding.encode(forged)
            val changed=cells.toMutableList().also {it[6]=(cells[6] as Long)+1;it[10]=plain}
            val sealed=repo.portable(changed,true)
            try { f.db.write("UPDATE local_candidate_batches SET version=version+1,payload=? WHERE id=?",sealed,b.id) }
            finally {plain.fill(0);sealed.fill(0)}
            published=null
            assertThrows(FactUnavailable::class.java) {repo.resolveEndpoint(oldA,endpoint){published=it}}
            assertNull(published)
        } finally {repo.wipeKey()}
    }

    @Test fun rejectedAndRevokedStillConsumeTheTwentyReadySlots():Unit=FullFixture().use { f ->
        val repo=SqlSourceFactRepository(f.gateway,f.key)
        try {
            val source=SourceVersion(f.entry(text=TEXT),1)
            repeat(20) { n ->
                val p=repo.create(request(repo,source))
                if(n%2==0)repo.mutate(change(p,FactAction.REJECT))
                else repo.mutate(change(repo.mutate(change(p,FactAction.ACCEPT)),FactAction.REVOKE))
            }
            assertEquals(20,repo.facts(source).size)
            assertTrue(repo.searchAccepted("").items.isEmpty())
            assertThrows(FactLimit::class.java) {repo.create(request(repo,source))}
            val rejected=repo.facts(source).first {it.review!!.review==FactReview.REJECTED}
            val corrected=repo.mutate(FactMutation(rejected,FactAction.CORRECT,rejected.current!!.content.copy(interval=FactInterval("2026-10-24",null,"UTC")),null,id(),90,true))
            assertEquals(FactReview.PROPOSED,corrected.review!!.review)
            assertEquals(FactReview.REJECTED,corrected.reviews.first().priorReview)
            assertEquals(20,repo.facts(source).size)
        } finally {repo.wipeKey()}
    }

    @Test fun boundedPortableByteGateCountsAllReadyPayloadsAndAllowsTheExactThresholdToReachAuthentication():Unit=FullFixture().use { f ->
        val repo=SqlSourceFactRepository(f.gateway,f.key)
        try {
            val source=SourceVersion(f.entry(text=TEXT),1);val saved=repo.create(request(repo,source))
            val template=f.db.read("SELECT id,entryId,sourceRevision,sourceHash,generation,ruleVersion,version,state,lastMutationId,createdAtMillis,payload FROM local_candidate_batches WHERE id=?",saved.id).single()
            // These are adversarial cells, not valid full-size statements. Test the byte preflight
            // independently of decoding; successful valid-data persistence is covered separately.
            fun rows(extra:Int)=List(128) {n -> template.toMutableList().also {
                it[0]=id();it[10]=ByteArray(32768+if(n==0)extra else 0)
            } to null}
            val exact=rows(0)
            assertEquals(4_194_304,exact.sumOf {(it.first[10] as ByteArray).size})
            val authentication=assertThrows(Exception::class.java) {repo.validatePortableGraph(exact)}
            assertFalse(authentication is FactLimit)
            val excessive=rows(1)
            assertThrows(FactLimit::class.java) {repo.validatePortableGraph(excessive)}
            (exact+excessive).forEach {(it.first[10] as ByteArray).fill(0)}
            assertTrue(repo.facts(source).single().same(saved))
        } finally {repo.wipeKey()}
    }

    @Test fun closedAndForeignOwnersCannotPublishAnAuthenticatedSourcePage():Unit=FullFixture().use { f ->
        val repo=SqlSourceFactRepository(f.gateway,f.key)
        val other=SqlSourceFactRepository(f.gateway,f.key)
        try {
            val source=SourceVersion(f.entry(text=TEXT),1);repo.create(request(repo,source))
            val page=repo.captureSource(source)
            assertFalse(other.resolveSource(page){fail("Foreign owner published")})
            f.gateway.seal();f.gateway.awaitIdle()
            assertThrows(FactUnavailable::class.java) {repo.resolveSource(page){fail("Closed owner published")}}
        }finally{repo.wipeKey();other.wipeKey()}
    }

    companion object {
        const val TEXT="我的合成杯子是绿色🙂。另一句仅供逐字定位。"
        fun id()=UUID.randomUUID().toString()
        internal fun request(repo:SqlSourceFactRepository,source:SourceVersion,topic:SourceFactSnapshot?=null):FactCreate {
            val p=repo.preview(source)
            return FactCreate(id(),p,topic?.topicId?:id(),topic?.topicLabel?:"合成杯子颜色",FactContent(SourceMemoryRules.reference(p,"我的合成杯子是绿色🙂")),true,id(),40,topic)
        }
        fun change(item:SourceFactSnapshot,action:FactAction,replacing:SourceFactSnapshot?=null)=FactMutation(item,action,null,replacing,id(),50)
    }
}
