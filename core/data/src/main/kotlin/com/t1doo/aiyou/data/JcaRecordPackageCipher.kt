package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Sealed capability: app DTOs and unchecked plaintext cannot enter the production importer. */
internal sealed interface AuthenticatedRecordPackage : VerifiedRecordPackage {
    val recordDigests: List<String>
}
private class AuthenticatedPackage(override val manifest: RecordPackageManifest,
    records: List<RecordPackageOriginal>, override val digest: String, hashes: List<String>) : AuthenticatedRecordPackage {
    override val records = Collections.unmodifiableList(records.toList())
    override val recordDigests = Collections.unmodifiableList(hashes.toList())
    override fun toString() = "VerifiedRecordPackage(count=" + records.size + ", redacted)"
}

/** Standard maintained platform JCA primitives; no provider pinning or copied crypto implementation. */
class JcaRecordPackageCipher internal constructor(private val random: SecureRandom,
    private val beforeKdf: () -> Unit) : RecordPackageCipher {
    constructor() : this(SecureRandom(), {})
    override fun seal(snapshot: OriginalExportSnapshot, password: CharArray): EncryptedRecordPackage = sanitized(password) {
        RecordPackages.password(password)
        val plain = encode(snapshot)
        var encrypted: ByteArray? = null
        var envelope: ByteArray? = null
        try {
            val salt = ByteArray(16).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            val header = ByteBuffer.allocate(48).put(MAGIC).putShort(1).putShort(1)
                .putInt(RecordPackages.ITERATIONS).put(salt).put(nonce).putInt(plain.size + 16).array()
            encrypted = crypt(Cipher.ENCRYPT_MODE, password, salt, nonce, header, plain)
            envelope = header + encrypted
            EncryptedRecordPackage(envelope)
        } finally { plain.fill(0); encrypted?.fill(0); envelope?.fill(0) }
    }

    override fun open(envelope: ByteArray, password: CharArray): VerifiedRecordPackage = sanitized(password) {
        RecordPackages.password(password)
        // Every outer bound is checked BEFORE expensive KDF or length-dependent allocation.
        require(envelope.size in 64..RecordPackages.MAX_ENVELOPE)
        val buffer = ByteBuffer.wrap(envelope)
        val magic = ByteArray(8).also(buffer::get); require(magic.contentEquals(MAGIC))
        require(buffer.short.toInt() == 1 && buffer.short.toInt() == 1)
        require(buffer.int == RecordPackages.ITERATIONS)
        val salt = ByteArray(16).also(buffer::get); val nonce = ByteArray(12).also(buffer::get)
        val length = buffer.int
        require(length in 16..(RecordPackages.MAX_ENVELOPE - 48) && length == envelope.size - 48)
        val header = envelope.copyOfRange(0, 48)
        val encrypted = envelope.copyOfRange(48, envelope.size)
        val plain = try { crypt(Cipher.DECRYPT_MODE, password, salt, nonce, header, encrypted) }
            finally { encrypted.fill(0) }
        try {
            require(plain.size <= RecordPackages.MAX_PLAINTEXT)
            parse(plain) // doFinal has authenticated the ENTIRE plaintext before parsing anything.
        } finally { plain.fill(0) }
    }

    private fun crypt(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray,
        aad: ByteArray, input: ByteArray): ByteArray {
        val key = RecordPackageCrypto.derive(password,salt,beforeKdf)
        return try { RecordPackageCrypto.crypt(mode,key,nonce,aad,input) } finally { key.fill(0) }
    }

    internal fun encode(snapshot: OriginalExportSnapshot, allowImageOnly: Boolean = false, allowEmpty: Boolean = false): ByteArray {
        RecordPackages.uuid(snapshot.id)
        require(allowEmpty || snapshot.records.isNotEmpty())
        val originals = snapshot.records.map { r -> RecordPackageOriginal(r.entry.id, r.entry.currentRevision,
            r.entry.capturedAtMillis, r.entry.captureTimeZone, r.entry.originalText, r.contentHash, r.tags.version, r.tags.names, allowImageOnly) }
        require(originals.sumOf { it.originalText.length.toLong() } <= OriginalExports.MAX_TEXT_UNITS)
        val blocks = mutableListOf<ByteArray>()
        try {
            originals.forEach { blocks += encodePackageRecord(it) }
            return PackageWriter(RecordPackages.MAX_PLAINTEXT).use { out ->
                out.string(NAME, 64); out.i32(1); out.i32(1); out.string(snapshot.id, 36); out.i64(snapshot.createdAtMillis)
                out.string("PBKDF2-HMAC-SHA256", 32); out.string("AES-256-GCM", 32)
                listOf(RecordPackages.ITERATIONS, 256, 16, 12, 128).forEach(out::i32)
                out.strings(SCOPE, 2, 32); out.strings(EXCLUDED, 7, 32); out.i32(blocks.size)
                blocks.forEachIndexed { index, block ->
                    out.string(path(index), 64); out.i32(block.size); out.raw(packageHash(block))
                }
                blocks.forEach { block -> out.i32(block.size); out.raw(block) }
                out.bytes()
            }
        } finally { blocks.forEach { it.fill(0) } }
    }

    internal fun parse(plain: ByteArray, allowImageOnly: Boolean = false, allowEmpty: Boolean = false): AuthenticatedRecordPackage {
        val input = PackageReader(plain)
        require(input.string(64) == NAME && input.i32() == 1 && input.i32() == 1)
        val id = input.string(36); val created = input.i64()
        require(input.string(32) == "PBKDF2-HMAC-SHA256" && input.string(32) == "AES-256-GCM")
        listOf(RecordPackages.ITERATIONS, 256, 16, 12, 128).forEach { require(input.i32() == it) }
        require(input.strings(2, 32) == SCOPE && input.strings(7, 32) == EXCLUDED)
        val count = input.count(20); val manifest = RecordPackageManifest(id, created, count)
        require(allowEmpty || count>0)
        val descriptors = List(count) { index ->
            require(input.string(64) == path(index))
            val length = input.i32(); require(length in 1..420_000)
            length to input.raw(32)
        }
        val records = mutableListOf<RecordPackageOriginal>(); val hashes = mutableListOf<String>()
        for ((length, hash) in descriptors) {
            require(input.i32() == length)
            val block = input.raw(length, 420_000)
            try {
                require(MessageDigest.isEqual(hash, packageHash(block)))
                records += decodeRecord(block,allowImageOnly); hashes += packageHex(hash)
            } finally { block.fill(0) }
        }
        input.end()
        require(records.map { it.sourceId }.distinct().size == count)
        require(records.sumOf { it.originalText.length.toLong() } <= OriginalExports.MAX_TEXT_UNITS)
        return AuthenticatedPackage(manifest, records, packageHex(packageHash(plain)), hashes)
    }

    private fun decodeRecord(bytes: ByteArray,allowImageOnly: Boolean): RecordPackageOriginal {
        val input = PackageReader(bytes)
        val id = input.string(512); val revision = input.i32(); val captured = input.i64(); val zone = input.string(128)
        val hash = packageHex(input.raw(32)); val text = input.string(400_000); val tagVersion = input.i64()
        val tags = input.strings(12, 640); input.end()
        return RecordPackageOriginal(id, revision, captured, zone, text, hash, tagVersion, tags,allowImageOnly)
    }

    private inline fun <T> sanitized(password: CharArray, block: () -> T): T =
        try { block() } catch (_: Exception) { throw RecordPackageInvalid() } finally { password.fill('\u0000') }

    companion object {
        private val MAGIC = "AIYOUPK1".toByteArray(Charsets.US_ASCII)
        private const val NAME = "AIyou-record-package"
        private val SCOPE = listOf("currentOriginals", "manualTags")
        private val EXCLUDED = listOf("attachments", "originalHistory", "tasks", "drafts", "aiDerived", "serviceKeys", "deviceKeys")
        private fun path(index: Int) = "records/" + index.toString().padStart(2, '0') + ".bin"
    }
}

internal fun encodePackageRecord(record: RecordPackageOriginal): ByteArray = PackageWriter(420_000).use { out ->
    out.string(record.sourceId, 512); out.i32(record.sourceRevision); out.i64(record.capturedAtMillis)
    out.string(record.captureTimeZone, 128); out.raw(packageUnhex(record.contentHash))
    out.string(record.originalText, 400_000); out.i64(record.sourceTagVersion); out.strings(record.tags, 12, 640)
    out.bytes()
}

/** Shared v1/v2 maintained platform primitives, preserving the original v1 known-answer byte contract. */
internal object RecordPackageCrypto {
    fun derive(password: CharArray,salt: ByteArray,beforeKdf: () -> Unit = {}): ByteArray {
        val spec = PBEKeySpec(password,salt,RecordPackages.ITERATIONS,256)
        var secret: SecretKey? = null
        try {
            beforeKdf()
            secret = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
            return secret.encoded
        } finally {
            spec.clearPassword()
            try { secret?.destroy() } catch (_: Exception) { /* Provider destruction remains best effort. */ }
        }
    }
    fun crypt(mode: Int,key: ByteArray,nonce: ByteArray,aad: ByteArray,input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }
}
