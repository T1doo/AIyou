package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Separate domain on the existing owner key, no new alias or stored credential. */
internal class SourceMemoryCipher(databaseKey: ByteArray) {
    private val key = Mac.getInstance("HmacSHA256").run {
        require(databaseKey.size == 32); init(SecretKeySpec(databaseKey, "HmacSHA256"))
        doFinal("com.t1doo.aiyou.user-source-memories.v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
    }
    private val random = SecureRandom()
    data class Payload(val captured: Long, val zone: String, val policy: ProcessingPolicy, val epoch: Long,
        val versions: List<SourceMemoryVersion>, val operation: String, val expectedVersion: Long,
        val expectedContentRevision: Int, val previousReview: SourceMemoryReview?, val at: Long) {
        override fun toString() = "SourceMemoryPayload(redacted)"
    }
    fun seal(aad: ByteArray, p: Payload): ByteArray {
        val plain = PackageWriter(32768).use { w ->
            w.i32(1); w.i64(p.captured); w.string(p.zone,128); w.string(p.policy.name,32); w.i64(p.epoch)
            w.string(p.operation,16); w.i64(p.expectedVersion); w.i32(p.expectedContentRevision)
            w.string(p.previousReview?.name.orEmpty(),16); w.i64(p.at); w.i32(p.versions.size)
            p.versions.forEach { v ->
                w.i32(v.revision); SourceMemoryEncoding.write(w,v.content); w.string(v.review.name,16)
                w.string(v.priorReview?.name.orEmpty(),16); w.i32(v.supersedesRevision ?: 0)
                w.i64(v.createdAtMillis); w.i64(v.reviewedAtMillis)
            }; w.bytes()
        }
        val nonce = ByteArray(12).also(random::nextBytes)
        return try {
            val c=Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));c.updateAAD(aad)
            byteArrayOf(1)+nonce+c.doFinal(plain)
        } finally { plain.fill(0) }
    }
    fun open(aad: ByteArray, bytes: ByteArray, source: SourceVersion): Payload {
        require(bytes.size in 29..32797 && bytes[0] == 1.toByte())
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,bytes.copyOfRange(1,13)));c.updateAAD(aad)
        val plain=c.doFinal(bytes,13,bytes.size-13)
        return try {
            val r=PackageReader(plain);require(r.i32()==1)
            val captured=r.i64();val zone=r.string(128);val policy=ProcessingPolicy.valueOf(r.string(32));val epoch=r.i64()
            val op=r.string(16);val expected=r.i64();val revision=r.i32();val previous=r.string(16);val at=r.i64()
            require(op in setOf("CREATE","ACCEPT","REJECT","REVOKE","REOPEN","CORRECT"))
            val versions=List(r.count(8)) {
                val v=r.i32();val content=SourceMemoryEncoding.read(r,source);val review=SourceMemoryReview.valueOf(r.string(16))
                val prior=r.string(16);val supersedes=r.i32()
                SourceMemoryVersion(v,content,review,prior.takeIf(String::isNotEmpty)?.let(SourceMemoryReview::valueOf),
                    supersedes.takeIf { it != 0 },r.i64(),r.i64())
            }
            r.end();Payload(captured,zone,policy,epoch,versions,op,expected,revision,
                previous.takeIf(String::isNotEmpty)?.let(SourceMemoryReview::valueOf),at)
        } finally { plain.fill(0) }
    }
    internal fun portable(aad:ByteArray,source:SourceVersion,bytes:ByteArray,restoring:Boolean):ByteArray {
        require(bytes.isNotEmpty() && bytes[0]==1.toByte())
        if(!restoring) open(aad,bytes,source)
        if(!restoring) return PortableCipherCell.open(key,aad,bytes,32797)
        val encrypted=PortableCipherCell.seal(key,aad,bytes,32797)
        return try {
            val p=open(aad,encrypted,source)
            seal(aad,p.copy(policy=if(p.policy==ProcessingPolicy.AI_NONE) p.policy else ProcessingPolicy.LOCAL_ONLY))
        } finally { encrypted.fill(0) }
    }
    fun wipe() { key.fill(0) }
}

internal object SourceMemoryEncoding {
    fun write(w: PackageWriter, c: SourceMemoryContent) {
        w.i32(c.reference.start);w.i32(c.reference.end);w.string(c.reference.quote,1280)
        w.string(c.kind.name,32);w.string(c.assertion.name,32);w.string(c.note,800)
    }
    fun read(r: PackageReader, source: SourceVersion): SourceMemoryContent = SourceMemoryContent(
        OriginalTextReference(source,r.i32(),r.i32(),r.string(1280)),SourceMemoryKind.valueOf(r.string(32)),
        SourceMemoryAssertion.valueOf(r.string(32)),r.string(800))
}
