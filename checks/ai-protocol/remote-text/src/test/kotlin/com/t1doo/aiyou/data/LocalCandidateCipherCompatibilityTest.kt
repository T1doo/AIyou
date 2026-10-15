package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

/** Synthetic v1 bytes encoded with the frozen 9f4 format, not the current production writer. */
class LocalCandidateCipherCompatibilityTest {
    @Test fun legacyAcceptedRowReadsAndReplaysUnchangedThenExplicitCorrectionWritesVersionTwo(): Unit = LocalCandidateFixture().use { f ->
        val hash = OriginalExports.hash(f.original)
        val aad = PackageWriter(4096).use { w ->
            w.string("AIyou/local-candidates/1",64); w.string("legacy",512); w.string("source",512)
            w.i64(1); w.string(hash,64); w.i64(0); w.i32(1); w.i64(2)
            w.string("READY",16); w.string("legacy-accept",512); w.i64(20); w.bytes()
        }
        val items = listOf(ReviewedExcerpt("excerpt-1", ExcerptProposal(0,
            f.original.codePointCount(0,f.original.length),f.original),ExcerptReview.ACCEPTED))
        val old = legacy(aad, LocalCandidateCipher.Payload(items,"ACCEPTED",1,"excerpt-1",30))
        f.db.transaction { it.execute("INSERT INTO local_candidate_batches VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            listOf("legacy","source",1,hash,0,1,2,"READY","legacy-accept",20,old)) }
        f.reopen()
        assertEquals(ExcerptReview.ACCEPTED,f.store.batches(f.source).single().items.single().review)
        val replay = ExcerptReviewRequest("legacy",f.source,hash,0,1,"excerpt-1",ExcerptReview.ACCEPTED,"legacy-accept",30)
        f.store.review(replay)
        fun bytes() = f.db.read("SELECT payload FROM local_candidate_batches WHERE id='legacy'").single().single() as ByteArray
        assertArrayEquals(old,bytes()); assertEquals(1,bytes()[0].toInt())
        f.store.reopen(ExcerptReopenRequest("legacy",f.source,hash,0,2,"excerpt-1",ExcerptReview.ACCEPTED,"correct",40))
        assertEquals(2,bytes()[0].toInt()); f.reopen()
        assertEquals(ExcerptReview.PROPOSED,f.store.batches(f.source).single().items.single().review)
        assertThrows(LocalCandidateConflict::class.java) { f.store.review(replay) }
    }
    @Test fun legacyWhitelistAndEofStayStrictAndEnvelopeRelabelingFailsAuthentication() {
        val cipher=LocalCandidateCipher(LocalCandidateFixture.KEY)
        val aad="synthetic-envelope-binding".toByteArray()
        val payload=LocalCandidateCipher.Payload(emptyList(),"START",0,"",20)
        try {
            val old=legacy(aad,payload)
            assertEquals(payload,cipher.open(aad,old))
            val changed=old.copyOf().also { it[0]=2 }
            assertThrows(AEADBadTagException::class.java) { cipher.open(aad,changed) }
            val current=cipher.seal(aad,payload)
            assertEquals(2,current[0].toInt()); assertEquals(payload,cipher.open(aad,current))
            assertThrows(AEADBadTagException::class.java) { cipher.open(aad,current.copyOf().also { it[0]=1 }) }
            assertThrows(IllegalArgumentException::class.java) { cipher.open(aad,legacy(aad,payload.copy(operation="REOPEN_ACCEPTED"))) }
            assertThrows(IllegalArgumentException::class.java) { cipher.open(aad,legacy(aad,payload,true)) }
            assertThrows(IllegalArgumentException::class.java) { cipher.open(aad,current.copyOf().also { it[0]=3 }) }
        } finally { cipher.wipe() }
    }
    private fun legacy(aad:ByteArray,p:LocalCandidateCipher.Payload,trailing:Boolean=false):ByteArray {
        val plain=PackageWriter(32768).use { w ->
            w.i32(1); w.string(p.operation,16); w.i64(p.expectedVersion); w.string(p.itemId,128); w.i64(p.requestedAtMillis)
            w.i32(p.items.size)
            p.items.forEach { w.string(it.id,128); w.i32(it.proposal.start); w.i32(it.proposal.end); w.string(it.proposal.quote,1280); w.string(it.review.name,16) }
            if(trailing) w.i32(0)
            w.bytes()
        }
        val key=Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(LocalCandidateFixture.KEY,"HmacSHA256"))
            doFinal("com.t1doo.aiyou.local-candidates.v1".toByteArray(Charsets.US_ASCII)+byteArrayOf(1))
        }
        try {
            val nonce=ByteArray(12) { (it+7).toByte() } // Fixed synthetic legacy fixture, never a production nonce.
            val c=Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce)); c.updateAAD(aad)
            return byteArrayOf(1)+nonce+c.doFinal(plain)
        } finally { key.fill(0); plain.fill(0) }
    }
}
