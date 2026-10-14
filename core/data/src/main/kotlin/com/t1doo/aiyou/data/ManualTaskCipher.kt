package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Independently derived key from the existing random database key; no new alias or credential. */
internal class ManualTaskCipher(databaseKey: ByteArray) {
    private val key: ByteArray
    private val random = SecureRandom()
    init {
        require(databaseKey.size == 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(databaseKey, "HmacSHA256"))
        key = mac.doFinal("com.t1doo.aiyou.manual-tasks.v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
    }
    fun seal(task: ManualTaskSnapshot, mutationId: String, requestedAtMillis: Long): ByteArray {
        val plain = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                writeText(out, task.title); writeText(out, task.status.name)
                out.writeLong(task.createdAtMillis); writeText(out, task.creationTimeZone)
                out.writeLong(task.updatedAtMillis); out.writeLong(requestedAtMillis)
            }
            bytes.toByteArray()
        }
        val nonce = ByteArray(12).also(random::nextBytes)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(task.id, task.version, mutationId))
            byteArrayOf(1) + nonce + cipher.doFinal(plain)
        } finally { plain.fill(0) }
    }
    fun open(id: String, version: Long, mutationId: String, bytes: ByteArray): Payload {
        require(bytes.size in 29..4096 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(aad(id, version, mutationId))
        val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
        return try {
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                val task = ManualTaskSnapshot(id, version, readText(input, 960), ManualTaskStatus.valueOf(readText(input, 16)),
                    input.readLong(), readText(input, 128), input.readLong())
                val requested = input.readLong()
                require(requested in 0..task.updatedAtMillis && input.available() == 0)
                Payload(task, requested)
            }
        } finally { plain.fill(0) }
    }
    private fun writeText(out: DataOutputStream, text: String) {
        val raw = text.toByteArray(Charsets.UTF_8)
        try { out.writeInt(raw.size); out.write(raw) } finally { raw.fill(0) }
    }
    private fun readText(input: DataInputStream, limit: Int): String {
        val size = input.readInt(); require(size in 1..limit && size <= input.available())
        val raw = ByteArray(size); input.readFully(raw)
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() }
        finally { raw.fill(0) }
    }
    private fun aad(id: String, version: Long, mutationId: String) = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF("AIyou manual tasks v1"); out.writeUTF(id); out.writeLong(version); out.writeUTF(mutationId)
        }
        bytes.toByteArray()
    }
    internal class Payload(val task: ManualTaskSnapshot, val requestedAtMillis: Long)
    /** Records owner only, after the shared SQL gateway is sealed and drained. */
    fun wipe() = key.fill(0)
}
