package com.t1doo.aiyou.records

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Android can accept incomplete bitmap input. Validate the whole bounded original before decoding. */
internal object PngPayloadValidator {
    data class Dimensions(val width: Int, val height: Int)
    fun validate(bytes: ByteArray): Dimensions {
        require(bytes.size in 45..LocalAttachmentController.MAX_SOURCE_BYTES)
        require(bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        fun number(offset: Int): Long = (0..3).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 255) }
        var position = 8; var width = 0; var height = 0; var depth = 0; var color = 0; var interlace = 0
        var palette = false; var dataSeen = false; var dataClosed = false; var ended = false
        var compressedSize = 0; var firstData = 0; var lastDataEnd = 0
        while (position < bytes.size) {
            require(!ended && bytes.size - position >= 12)
            val length = number(position)
            require(length <= bytes.size.toLong() - position - 12)
            val size = length.toInt(); val type = String(bytes, position + 4, 4, Charsets.US_ASCII)
            require(type.all { it in 'A'..'Z' || it in 'a'..'z' } && type[2] in 'A'..'Z')
            require(type[0] in 'a'..'z' || type in setOf("IHDR", "PLTE", "IDAT", "IEND"))
            require(position != 8 || type == "IHDR")
            require(type !in setOf("acTL", "fcTL", "fdAT", "eXIf"))
            require(CRC32().apply { update(bytes, position + 4, size + 4) }.value == number(position + 8 + size))
            when (type) {
                "IHDR" -> {
                    require(position == 8 && size == 13)
                    val w = number(position + 8); val h = number(position + 12)
                    require(w in 1..4096 && h in 1..4096)
                    width = w.toInt(); height = h.toInt(); depth = bytes[position + 16].toInt() and 255
                    color = bytes[position + 17].toInt() and 255
                    val depths = when (color) { 0 -> setOf(1, 2, 4, 8, 16); 2, 4, 6 -> setOf(8, 16); 3 -> setOf(1, 2, 4, 8); else -> emptySet() }
                    require(depth in depths && bytes[position + 18] == 0.toByte() && bytes[position + 19] == 0.toByte())
                    interlace = bytes[position + 20].toInt() and 255; require(interlace in 0..1)
                }
                "PLTE" -> {
                    require(!palette && !dataSeen && color != 0 && color != 4 && size in 3..768 && size % 3 == 0)
                    require(color != 3 || size / 3 <= 1 shl depth)
                    palette = true
                }
                "IDAT" -> {
                    require(!dataClosed && (color != 3 || palette))
                    if (!dataSeen) firstData = position
                    dataSeen = true; compressedSize += size; lastDataEnd = position + size + 12
                }
                "IEND" -> { require(size == 0 && dataSeen); ended = true }
            }
            if (dataSeen && type != "IDAT") dataClosed = true
            position += size + 12
        }
        require(ended && compressedSize > 0)
        val compressed = ByteArray(compressedSize)
        var copied = 0; position = firstData
        while (position < lastDataEnd) {
            val size = number(position).toInt()
            bytes.copyInto(compressed, copied, position + 8, position + 8 + size)
            copied += size; position += size + 12
        }
        val channels = when (color) { 0, 3 -> 1; 2 -> 3; 4 -> 2; else -> 4 }
        val passes = if (interlace == 0) listOf(intArrayOf(0, 0, 1, 1)) else listOf(
            intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8),
            intArrayOf(2, 0, 4, 4), intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2))
        val inflater = Inflater(); val buffer = ByteArray(8192)
        try {
            InflaterInputStream(ByteArrayInputStream(compressed), inflater, 8192).use { input ->
                for (pass in passes) {
                    val columns = if (width <= pass[0]) 0 else (width - pass[0] + pass[2] - 1) / pass[2]
                    val rows = if (height <= pass[1]) 0 else (height - pass[1] + pass[3] - 1) / pass[3]
                    if (columns == 0 || rows == 0) continue
                    val rowBytes = (columns * channels * depth + 7) / 8
                    repeat(rows) {
                        require(input.read() in 0..4)
                        var remaining = rowBytes
                        while (remaining > 0) {
                            val count = input.read(buffer, 0, minOf(remaining, buffer.size))
                            require(count > 0); remaining -= count
                        }
                    }
                }
                require(input.read() == -1 && inflater.finished() && inflater.remaining == 0 && inflater.bytesRead == compressed.size.toLong())
            }
        } catch (failure: IOException) { throw IllegalArgumentException("Incomplete PNG payload", failure) }
        finally { inflater.end(); buffer.fill(0); compressed.fill(0) }
        return Dimensions(width, height)
    }
}
