package com.t1doo.aiyou.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import com.t1doo.aiyou.model.OriginalExports

/** Bounded product framing; contains no cryptographic algorithm implementation. */
internal class PackageWriter(private val limit: Int) : AutoCloseable {
    // Fixed public bound: no reallocation can leave an abandoned old plaintext backing array.
    private val buffer = ByteArray(limit)
    private var at = 0
    fun raw(bytes: ByteArray) { require(bytes.size <= limit - at); bytes.copyInto(buffer, at); at += bytes.size }
    fun i32(value: Int) { raw(ByteBuffer.allocate(4).putInt(value).array()) }
    fun i64(value: Long) { raw(ByteBuffer.allocate(8).putLong(value).array()) }
    fun string(value: String, maxBytes: Int) {
        val bytes = OriginalExports.utf8(value)
        try { require(bytes.size <= maxBytes); i32(bytes.size); raw(bytes) } finally { bytes.fill(0) }
    }
    fun strings(values: List<String>, maxCount: Int, maxBytes: Int) {
        require(values.size <= maxCount); i32(values.size); values.forEach { string(it, maxBytes) }
    }
    fun bytes(): ByteArray = buffer.copyOf(at)
    override fun close() { buffer.fill(0); at = 0 }
}

internal class PackageReader(private val bytes: ByteArray) {
    private var at = 0
    val remaining get() = bytes.size - at
    fun raw(length: Int, maximum: Int = length): ByteArray {
        require(length >= 0 && length <= maximum && length <= remaining)
        return bytes.copyOfRange(at, at + length).also { at += length }
    }
    fun i32(): Int {
        require(remaining >= 4)
        return ByteBuffer.wrap(bytes, at, 4).int.also { at += 4 }
    }
    fun i64(): Long {
        require(remaining >= 8)
        return ByteBuffer.wrap(bytes, at, 8).long.also { at += 8 }
    }
    fun count(maximum: Int): Int = i32().also { require(it in 0..maximum) }
    fun string(maximum: Int): String {
        val raw = raw(i32(), maximum)
        val chars = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)) }
        finally { raw.fill(0) }
        return try { chars.toString() } finally { if (chars.hasArray()) chars.array().fill('\u0000') }
    }
    fun strings(maximum: Int, maxBytes: Int): List<String> = List(count(maximum)) { string(maxBytes) }
    fun end() { require(remaining == 0) }
}

internal fun packageHash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
internal fun packageHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun packageUnhex(value: String): ByteArray {
    require(value.matches(Regex("[0-9a-f]{64}")))
    return ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
