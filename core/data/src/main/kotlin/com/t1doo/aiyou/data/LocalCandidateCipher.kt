package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Separate purpose, same draining database owner and existing key; no new alias or credential. */
internal class LocalCandidateCipher(databaseKey: ByteArray) {
    private val key = Mac.getInstance("HmacSHA256").run {
        require(databaseKey.size == 32); init(SecretKeySpec(databaseKey, "HmacSHA256"))
        doFinal("com.t1doo.aiyou.local-candidates.v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
    }
    private val random = SecureRandom()
    data class Payload(val items: List<ReviewedExcerpt>, val operation: String, val expectedVersion: Long,
        val itemId: String, val requestedAtMillis: Long) {
        override fun toString() = "CandidatePayload(redacted)"
    }
    fun seal(aad: ByteArray, payload: Payload): ByteArray {
        val plain = encode(payload); val nonce = ByteArray(12).also(random::nextBytes)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); c.updateAAD(versionedAad(aad, 2))
            byteArrayOf(2) + nonce + c.doFinal(plain)
        } finally { plain.fill(0) }
    }
    fun open(aad: ByteArray, bytes: ByteArray): Payload {
        require(bytes.size in 29..32768 && bytes[0].toInt() in 1..2)
        val envelope = bytes[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(1, 13))); c.updateAAD(versionedAad(aad, envelope))
        val plain = c.doFinal(bytes, 13, bytes.size - 13)
        return try {
            val r = PackageReader(plain); require(r.i32() == envelope)
            val op = r.string(16)
            require(op in setOf("START", "COMPLETE", "ACCEPTED", "REJECTED", "CANCEL") ||
                envelope == 2 && op in setOf("REOPEN_ACCEPTED", "REOPEN_REJECTED"))
            val version = r.i64(); val itemId = r.string(128); val at = r.i64(); require(version >= 0 && at >= 0)
            val items = List(r.count(LocalCandidates.MAX_ITEMS)) {
                ReviewedExcerpt(r.string(128), ExcerptProposal(r.i32(), r.i32(), r.string(1280)), ExcerptReview.valueOf(r.string(16)))
            }
            r.end(); require(items.map { it.id }.distinct().size == items.size)
            Payload(items, op, version, itemId, at)
        } finally { plain.fill(0) }
    }
    private fun encode(p: Payload): ByteArray = PackageWriter(32768).use { w ->
        w.i32(2); w.string(p.operation, 16); w.i64(p.expectedVersion); w.string(p.itemId, 128); w.i64(p.requestedAtMillis)
        require(p.items.size <= LocalCandidates.MAX_ITEMS); w.i32(p.items.size)
        p.items.forEach { w.string(it.id, 128); w.i32(it.proposal.start); w.i32(it.proposal.end); w.string(it.proposal.quote, 1280); w.string(it.review.name, 16) }
        w.bytes()
    }
    private fun versionedAad(aad: ByteArray, version: Int) = if (version == 1) aad else
        aad + "\u0000AIyou/local-candidates/envelope/2".toByteArray(Charsets.US_ASCII)
    fun wipe() { key.fill(0) }
}
