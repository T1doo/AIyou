package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.ManualTags
import com.t1doo.aiyou.model.ManualPersonAlias
import com.t1doo.aiyou.model.ManualPeopleAliases
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
internal class ManualPeopleLabels(val names: List<String>, val aliases: List<ManualPersonAlias>) {
    override fun toString() = "ManualPeopleLabels(redacted)"
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
        return sealBytes(entryId, version, mutationId, encode(names))
    }
    /** Names-only writes retain the old format; the authenticated marker cannot be a legacy 0..12 count. */
    fun sealPeople(entryId: String, version: Long, mutationId: String, names: List<String>, aliases: List<ManualPersonAlias>): ByteArray {
        require(purpose == ManualLabelPurpose.PEOPLE && ManualTags.canonical(names) == names)
        ManualPeopleAliases.canonical(names, aliases)
        if (aliases.isEmpty()) return seal(entryId, version, mutationId, names)
        val plain = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(0x41495032); out.writeInt(names.size)
                names.forEach { writeName(out, it) }; out.writeInt(aliases.size)
                aliases.forEach { writeName(out, it.alias); writeName(out, it.name) }
            }; bytes.toByteArray()
        }
        return sealBytes(entryId, version, mutationId, plain)
    }
    private fun sealBytes(entryId: String, version: Long, mutationId: String, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        return try {
            require(plain.size + 29 <= 8192)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(entryId, version, mutationId))
            byteArrayOf(1) + nonce + cipher.doFinal(plain)
        } finally { plain.fill(0) }
    }
    fun open(entryId: String, version: Long, mutationId: String, bytes: ByteArray): List<String> {
        return openLabels(entryId, version, mutationId, bytes).names
    }
    fun openPeople(entryId: String, version: Long, mutationId: String, bytes: ByteArray): ManualPeopleLabels {
        require(purpose == ManualLabelPurpose.PEOPLE)
        return openLabels(entryId, version, mutationId, bytes)
    }
    private fun openLabels(entryId: String, version: Long, mutationId: String, bytes: ByteArray): ManualPeopleLabels {
        require(bytes.size in 29..8192 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(aad(entryId, version, mutationId))
        val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
        return try {
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                val marker = input.readInt()
                val extended = marker == 0x41495032
                require(!extended || purpose == ManualLabelPurpose.PEOPLE)
                val count = if (extended) input.readInt() else marker
                require(count in 0..ManualTags.MAX_COUNT)
                val names = List(count) { readName(input) }
                val aliases = if (extended) {
                    val size = input.readInt(); require(size in 1..ManualPeopleAliases.MAX_COUNT)
                    List(size) { ManualPersonAlias(readName(input), readName(input)) }
                } else emptyList()
                require(input.available() == 0 && ManualTags.canonical(names) == names)
                ManualPeopleAliases.canonical(names, aliases)
                ManualPeopleLabels(names, aliases)
            }
        } finally { plain.fill(0) }
    }
    private fun readName(input: DataInputStream): String {
        val size = input.readInt(); require(size in 1..160 && size <= input.available())
        val raw = ByteArray(size); input.readFully(raw)
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() }
        finally { raw.fill(0) }
    }
    private fun encode(names: List<String>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(names.size)
            names.forEach { writeName(output, it) }
        }
        bytes.toByteArray()
    }
    private fun writeName(output: DataOutputStream, name: String) {
        val raw = name.toByteArray(Charsets.UTF_8)
        try { output.writeInt(raw.size); output.write(raw) } finally { raw.fill(0) }
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
