package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.ManualTags
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** HKDF-Expand from the existing uniformly random 256-bit database key; no new credential/alias. */
internal enum class ManualLabelPurpose(val keyContext: String, val aadContext: String) {
    TAGS("com.t1doo.aiyou.manual-tags.v1", "AIyou/manual-tags/1"),
    PEOPLE("com.t1doo.aiyou.manual-people.v1", "AIyou/manual-people/1"),
}
internal class ManualTagCipher(databaseKey: ByteArray, private val purpose: ManualLabelPurpose = ManualLabelPurpose.TAGS) {
    private val key: ByteArray
    private val random = SecureRandom()
    init {
        require(databaseKey.size == 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(databaseKey, "HmacSHA256"))
        key = mac.doFinal(purpose.keyContext.toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
    }
    fun seal(entryId: String, version: Long, mutationId: String, names: List<String>): ByteArray {
        val plain = encode(names)
        val nonce = ByteArray(12).also(random::nextBytes)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(entryId, version, mutationId))
            byteArrayOf(1) + nonce + cipher.doFinal(plain)
        } finally { plain.fill(0) }
    }
    fun open(entryId: String, version: Long, mutationId: String, bytes: ByteArray): List<String> {
        require(bytes.size in 29..8192 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(aad(entryId, version, mutationId))
        val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
        return try {
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                val count = input.readInt()
                require(count in 0..ManualTags.MAX_COUNT)
                val names = List(count) {
                    val size = input.readInt(); require(size in 1..160 && size <= input.available())
                    val raw = ByteArray(size); input.readFully(raw)
                    try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() }
                    finally { raw.fill(0) }
                }
                require(input.available() == 0 && ManualTags.canonical(names) == names)
                names
            }
        } finally { plain.fill(0) }
    }
    private fun encode(names: List<String>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(names.size)
            names.forEach { name -> val raw = name.toByteArray(Charsets.UTF_8)
                try { output.writeInt(raw.size); output.write(raw) } finally { raw.fill(0) } }
        }
        bytes.toByteArray()
    }
    private fun aad(id: String, version: Long, mutation: String): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF(purpose.aadContext); out.writeUTF(id); out.writeLong(version); out.writeUTF(mutation)
        }; bytes.toByteArray()
    }
    internal fun portable(id: String, version: Long, mutation: String, bytes: ByteArray, restoring: Boolean): ByteArray {
        if (!restoring) open(id,version,mutation,bytes)
        val result = if (restoring) PortableCipherCell.seal(key,aad(id,version,mutation),bytes,8192)
            else PortableCipherCell.open(key,aad(id,version,mutation),bytes,8192)
        if (restoring) open(id,version,mutation,result)
        return result
    }
    fun wipe() { key.fill(0) }
}
