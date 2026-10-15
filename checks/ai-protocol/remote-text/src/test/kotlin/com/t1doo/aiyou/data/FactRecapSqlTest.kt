package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.domain.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FactRecapSqlTest {
    private fun range()=FactRecapRange(LocalDate.parse("2026-10-15"),FactRecapPeriod.DAY,"Asia/Shanghai")
    private fun repo(f:PermanentErasureFixture)=SqlFactRecapRepository(f.gateway,f.tags)
    private fun seed(f:PermanentErasureFixture,text:String="合成明确原文",time:Long=range().startMillis):String=f.entry(text=text).also {
        f.db.write("UPDATE entries SET capturedAtMillis=?,captureTimeZone='UTC' WHERE id=?",time,it)
    }
    private fun image(f:PermanentErasureFixture,id:String,state:String="READY",revision:Int=1):String=UUID.randomUUID().toString().also {
        f.db.write("INSERT INTO attachments VALUES(?,?,?,'image/png',?,9,?)",it,id,revision,"a".repeat(64),state)
    }
    @Test fun exactDayUsesRecordInstantNotCaptureZoneOrTextEventDateAndCountsAuthenticatedLabels():Unit=PermanentErasureFixture().use { f->
        val r=range();seed(f,time=r.startMillis-1);val a=seed(f,"合成：明天去公园，不代表已经发生",r.startMillis)
        val b=seed(f,"合成：我以前喝咖啡",r.endMillis-1);seed(f,time=r.endMillis)
        image(f,a);image(f,a,"FAILED");f.tags.replace(ManualTagMutation(a,0,listOf("合成手工标签"),UUID.randomUUID().toString()))
        val owner=repo(f);val s=owner.capture(r)
        assertEquals(listOf(a,b),s.cards.map{it.source.sourceId});assertEquals(2,s.recordCount);assertEquals(2,s.imageCount)
        assertEquals(listOf("合成手工标签"),s.cards.first().tags.names);assertEquals(AttachmentState.FAILED,s.cards.first().images.single{it.state==AttachmentState.FAILED}.state)
        assertTrue(owner.publishCurrent(s){});assertTrue(owner.resolve(s,SourceVersion(a,1)){})
    }
    @Test fun originalExcerptPreservesUnicodeNegationQuotesAndPureImageHasNoPlaceholder():Unit=PermanentErasureFixture().use { f->
        val text="合成：‘今天不想喝茶’，还没有做。"+"😀".repeat(300)
        val a=seed(f,text);val b=seed(f,"");image(f,b)
        val s=repo(f).capture(range());val card=s.cards.single{it.source.sourceId==a}
        assertEquals(240,card.excerpt.codePointCount(0,card.excerpt.length));assertTrue(card.hasMore);assertTrue(text.startsWith(card.excerpt))
        assertEquals("",s.cards.single{it.source.sourceId==b}.excerpt);assertFalse(s.toString().contains("喝茶"));assertFalse(card.toString().contains("喝茶"))
    }
    @Test fun emptyRangeIsCompleteAndNewMemberInvalidatesEvenUnchangedExistingSource():Unit=PermanentErasureFixture().use { f->
        val owner=repo(f);val empty=owner.capture(range());assertEquals(0,empty.recordCount);assertTrue(owner.publishCurrent(empty){})
        val a=seed(f);assertFalse(owner.publishCurrent(empty){fail("stale empty")})
        val old=owner.capture(range());seed(f);assertFalse(owner.resolve(old,SourceVersion(a,1)){fail("stale total")})
    }
    @Test fun bodyRevisionVisibilityGenerationAndTagVersionEachInvalidateProof():Unit=PermanentErasureFixture().use { f->
        val id=seed(f);val owner=repo(f);var s=owner.capture(range())
        f.tags.replace(ManualTagMutation(id,0,listOf("合成标签"),UUID.randomUUID().toString()));assertFalse(owner.publishCurrent(s){})
        s=owner.capture(range());f.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?",id);assertFalse(owner.publishCurrent(s){})
        s=owner.capture(range());f.db.write("INSERT INTO entry_revisions VALUES(?,2,?,?,8)",id,"合成新版本",OriginalExports.hash("合成新版本"));f.db.write("UPDATE entries SET currentRevision=2 WHERE id=?",id)
        assertFalse(owner.resolve(s,SourceVersion(id,1)){});assertEquals(2L,owner.capture(range()).cards.single().source.revision)
    }
    @Test fun imageManifestStateAndHistoricalRevisionAreSourceBound():Unit=PermanentErasureFixture().use { f->
        val id=seed(f);val owner=repo(f);val original=owner.capture(range());val pic=image(f,id)
        assertFalse(owner.publishCurrent(original){});var s=owner.capture(range());assertEquals(1,s.imageCount)
        f.db.write("UPDATE attachments SET state='CORRUPT' WHERE id=?",pic);assertFalse(owner.publishCurrent(s){})
        s=owner.capture(range());assertEquals(AttachmentState.CORRUPT,s.cards.single().images.single().state)
        f.db.write("INSERT INTO entry_revisions VALUES(?,2,?,?,8)",id,"合成版本二",OriginalExports.hash("合成版本二"));f.db.write("UPDATE entries SET currentRevision=2 WHERE id=?",id)
        assertFalse(owner.publishCurrent(s){});assertEquals(0,owner.capture(range()).imageCount)
    }
    @Test fun trashRestorePermanentEraseExcludeEvidenceAndCannotResolveOldSource():Unit=runBlocking {
        PermanentErasureFixture().use { f->
            val id=seed(f);val owner=repo(f);val original=owner.capture(range());val before=f.bin.read(id)
            val trash=f.bin.apply(RecycleMutation(SourceVersion(id,1),before.generation,false,UUID.randomUUID().toString()))
            assertEquals(0,owner.capture(range()).recordCount);assertFalse(owner.resolve(original,SourceVersion(id,1)){})
            f.bin.apply(RecycleMutation(SourceVersion(id,1),trash.generation,true,UUID.randomUUID().toString()))
            assertEquals(1,owner.capture(range()).recordCount);assertFalse(owner.publishCurrent(original){})
            val restored=owner.capture(range());assertTrue(f.erase.erase(f.intent(id)).complete)
            assertEquals(0,owner.capture(range()).recordCount);assertFalse(owner.resolve(restored,SourceVersion(id,1)){})
        }
    }
    @Test fun corruptedOriginalTagAndUnknownMetadataFailInsteadOfFalseEmpty():Unit=PermanentErasureFixture().use { f->
        val id=seed(f);val owner=repo(f);f.tags.replace(ManualTagMutation(id,0,listOf("合成标签"),UUID.randomUUID().toString()))
        f.db.write("UPDATE entry_manual_tags SET ciphertext=? WHERE entryId=?",byteArrayOf(1,2),id)
        assertThrows(FactRecapStorageFailure::class.java){owner.capture(range())}
        f.db.write("DELETE FROM entry_manual_tags WHERE entryId=?",id);f.db.write("UPDATE entry_revisions SET contentHash=? WHERE entryId=?","0".repeat(64),id)
        assertThrows(FactRecapStorageFailure::class.java){owner.capture(range())}
    }
    @Test fun explicitBoundsRejectWholeResultInsteadOfTruncatingTextOrCounts():Unit=PermanentErasureFixture().use { f->
        repeat(21){seed(f,"x".repeat(100000))};assertThrows(FactRecapTooBroad::class.java){repo(f).capture(range())}
    }
    @Test fun completeMembershipLimitIsNotSilentlyCappedAtOneThousand():Unit=PermanentErasureFixture().use { f->
        repeat(1001){seed(f)};assertThrows(FactRecapTooBroad::class.java){repo(f).capture(range())}
    }
    @Test fun overfullImageLedgerIsRejectedAtSixtyFiveRowsBeforeMapping():Unit=PermanentErasureFixture().use {f->
        val id=seed(f);repeat(65){image(f,id)}
        assertThrows(FactRecapTooBroad::class.java){repo(f).capture(range())}
    }
    @Test fun foreignOwnerAndForgedSnapshotsAndWrongVersionCannotPublish():Unit=PermanentErasureFixture().use { f->
        val id=seed(f);val owner=repo(f);val s=owner.capture(range());assertFalse(repo(f).publishCurrent(s){})
        val forged=object:FactRecapSnapshot {override val range=s.range;override val cards=s.cards}
        assertFalse(owner.publishCurrent(forged){});assertFalse(owner.resolve(s,SourceVersion(id,2)){})
        seed(f,time=range().endMillis);assertTrue(owner.publishCurrent(s){})
    }
}
