package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class FactPayload(val captured: Long, val zone: String, val policy: ProcessingPolicy, val epoch: Long,
    val topicId: String, val topicLabel: String, val contents: List<FactContentVersion>, val reviews: List<FactCheckpoint>,
    val operation: String, val expectedVersion: Long, val expectedContentRevision: Int, val previousReview: FactReview?, val at: Long) {
    override fun toString() = "FactPayload(redacted)"
}
internal object FactEncoding {
    fun content(w: PackageWriter, c: FactContent) {
        w.string(FactAssertion.SELF_REPORT.name,16);w.i32(c.reference.start); w.i32(c.reference.end); w.string(c.reference.quote,1280)
        w.string(c.interval.fromDate.orEmpty(),16); w.string(c.interval.untilExclusive.orEmpty(),16); w.string(c.interval.zone.orEmpty(),128)
    }
    fun content(r: PackageReader, source: SourceVersion): FactContent {
        require(r.string(16)==FactAssertion.SELF_REPORT.name)
        val ref=OriginalTextReference(source,r.i32(),r.i32(),r.string(1280))
        return FactContent(ref,FactInterval(r.string(16).ifEmpty { null },r.string(16).ifEmpty { null },r.string(128).ifEmpty { null }))
    }
    private fun endpoint(w: PackageWriter, e: FactEndpoint?) {
        w.i32(if(e==null)0 else 1)
        e?.let { w.string(it.id,36);w.i32(it.contentRevision);w.string(it.operationId,36);w.string(it.digest,64) }
    }
    private fun endpoint(r: PackageReader): FactEndpoint? = when(r.i32()) {
        0 -> null
        1 -> FactEndpoint(r.string(36),r.i32(),r.string(36),r.string(64))
        else -> error("Invalid fact endpoint")
    }
    fun histories(w: PackageWriter, contents: List<FactContentVersion>, reviews: List<FactCheckpoint>) {
        require(contents.size in 1..8 && reviews.size==contents.size);w.i32(contents.size)
        contents.forEach { w.i32(it.revision);content(w,it.content);w.i64(it.createdAtMillis) }
        reviews.forEach { w.i32(it.contentRevision);w.string(it.review.name,16);w.string(it.priorReview?.name.orEmpty(),16);w.i64(it.reviewedAtMillis);endpoint(w,it.predecessor);endpoint(w,it.successor) }
    }
    fun encode(p: FactPayload): ByteArray = PackageWriter(32768).use { w ->
        w.string("AIyou-explicit-fact",32);w.i32(1);w.i64(p.captured);w.string(p.zone,128);w.string(p.policy.name,32);w.i64(p.epoch)
        w.string(p.topicId,36);w.string(p.topicLabel,160);histories(w,p.contents,p.reviews)
        w.string(p.operation,16);w.i64(p.expectedVersion);w.i32(p.expectedContentRevision);w.string(p.previousReview?.name.orEmpty(),16);w.i64(p.at);w.bytes()
    }
    fun decode(bytes: ByteArray, source: SourceVersion): FactPayload {
        require(bytes.size<=32768);val r=PackageReader(bytes);require(r.string(32)=="AIyou-explicit-fact" && r.i32()==1)
        val captured=r.i64();val zone=r.string(128);val policy=ProcessingPolicy.valueOf(r.string(32));val epoch=r.i64()
        val topic=r.string(36);val label=r.string(160);val n=r.count(8);require(n>0)
        val contents=List(n) { FactContentVersion(r.i32(),content(r,source),r.i64()) }
        val reviews=List(n) { FactCheckpoint(r.i32(),FactReview.valueOf(r.string(16)),r.string(16).ifEmpty { null }?.let(FactReview::valueOf),r.i64(),endpoint(r),endpoint(r)) }
        val op=r.string(16);val expected=r.i64();val revision=r.i32();val prior=r.string(16).ifEmpty { null }?.let(FactReview::valueOf);val at=r.i64();r.end()
        require(op in setOf("CREATE","ACCEPT","REJECT","REVOKE","CORRECT","REPLACE_OLD"))
        return FactPayload(captured,zone,policy,epoch,topic,label,contents,reviews,op,expected,revision,prior,at)
    }
}

/** Profile6 has an independent key purpose, framing byte, plaintext magic, and bound AAD. */
internal class SourceFactCipher(databaseKey: ByteArray) {
    private val key=Mac.getInstance("HmacSHA256").run {
        require(databaseKey.size==32);init(SecretKeySpec(databaseKey,"HmacSHA256"));doFinal("com.t1doo.aiyou.explicit-ordinary-facts.v1".toByteArray(Charsets.US_ASCII)+byteArrayOf(1))
    }
    fun seal(aad: ByteArray, payload: FactPayload): ByteArray {
        val plain=FactEncoding.encode(payload);val nonce=ByteArray(12).also(SecureRandom()::nextBytes)
        return try {
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));c.updateAAD(aad)
            byteArrayOf(6)+nonce+c.doFinal(plain)
        } finally { plain.fill(0) }
    }
    fun open(aad: ByteArray, bytes: ByteArray, source: SourceVersion): FactPayload {
        require(bytes.size in 29..32797 && bytes[0]==6.toByte())
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,bytes.copyOfRange(1,13)));c.updateAAD(aad)
        val plain=c.doFinal(bytes,13,bytes.size-13)
        return try { FactEncoding.decode(plain,source) } finally { plain.fill(0) }
    }
    fun portable(aad: ByteArray, source: SourceVersion, bytes: ByteArray, restoring: Boolean): ByteArray {
        require(bytes.isNotEmpty() && bytes[0]==6.toByte())
        if(!restoring) { open(aad,bytes,source);return PortableCipherCell.open(key,aad,bytes,32797) }
        val sealed=PortableCipherCell.seal(key,aad,bytes,32797)
        return try { val p=open(aad,sealed,source);seal(aad,p.copy(policy=if(p.policy==ProcessingPolicy.AI_NONE)p.policy else ProcessingPolicy.LOCAL_ONLY)) } finally { sealed.fill(0) }
    }
    fun wipe() { key.fill(0) }
}
