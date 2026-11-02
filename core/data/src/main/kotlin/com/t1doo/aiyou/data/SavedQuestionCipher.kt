package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Purpose-separated encryption inside the already encrypted database; metadata and last intent are authenticated. */
internal class SavedQuestionCipher(databaseKey: ByteArray) {
    private val key = Mac.getInstance("HmacSHA256").run {
        require(databaseKey.size == 32); init(SecretKeySpec(databaseKey, "HmacSHA256"))
        doFinal("com.t1doo.aiyou.saved-questions.v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
    }
    private fun aad(row: List<Any?>): ByteArray = PackageWriter(512).use { w ->
        w.string("AIyou/saved-question/1",32); w.string(row[0] as String,36); w.i64(row[1] as Long)
        w.string(row[2] as String,36); w.i64(row[3] as Long); w.i64(row[4] as Long); w.string(row[5] as String,16); w.bytes()
    }
    fun seal(row: List<Any?>, question: String?): ByteArray {
        val plain = PackageWriter(3072).use { w ->
            w.string("AIyou-user-question",32); w.i32(1)
            val context=aad(row); w.i32(context.size); w.raw(context)
            w.i32(if(question == null) 0 else 1)
            question?.let { SavedQuestions.question(it); w.string(it, SavedQuestions.MAX_UTF8) }; w.bytes()
        }
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce)); c.updateAAD(aad(row))
            byteArrayOf(1) + nonce + c.doFinal(plain)
        } finally { plain.fill(0) }
    }
    fun open(row: List<Any?>): SavedQuestion {
        require(row.size == 7); val id=row[0] as String; SavedQuestions.id(id); SavedQuestions.id(row[2] as String)
        val state=row[5] as String; require(state in setOf("ACTIVE","DELETED"))
        val bytes=row[6] as ByteArray; require(bytes.size in 29..4096 && bytes[0] == 1.toByte())
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,bytes.copyOfRange(1,13))); c.updateAAD(aad(row))
        val plain=c.doFinal(bytes,13,bytes.size-13)
        return try {
            val r=PackageReader(plain); require(r.string(32)=="AIyou-user-question" && r.i32()==1)
            require(java.security.MessageDigest.isEqual(r.raw(r.i32(),512),aad(row)))
            val question=when(r.i32()) { 0 -> null; 1 -> r.string(SavedQuestions.MAX_UTF8); else -> error("Invalid question") }; r.end()
            require((question == null) == (state == "DELETED"))
            SavedQuestion(id,row[1] as Long,row[3] as Long,row[4] as Long,question)
        } finally { plain.fill(0) }
    }
    fun portable(row: List<Any?>, restoring: Boolean): ByteArray {
        val bytes=row[6] as ByteArray
        if(!restoring) { open(row); return PortableCipherCell.open(key,aad(row),bytes,4096) }
        val sealed=PortableCipherCell.seal(key,aad(row),bytes,4096)
        try { open(row.take(6)+listOf(sealed)); return sealed } catch(f:Throwable) { sealed.fill(0); throw f }
    }
    fun wipe() { key.fill(0) }
}
