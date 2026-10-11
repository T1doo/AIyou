package com.t1doo.aiyou.records

/** A bounded JPEG container/IFD0 reader, not an entropy decoder. Pixels are decoded by Android. */
internal object JpegPayloadValidator {
    const val MAX_DIMENSION = 8192
    const val MAX_PIXELS = 64_000_000L
    private data class Segment(val start: Int, val end: Int, val marker: Int, val body: Int)
    data class Image(val width: Int, val height: Int, val orientation: Int)
    private data class Parsed(val image: Image, val segments: List<Segment>)

    fun inspect(bytes: ByteArray): Image = parse(bytes).image
    fun prepare(bytes: ByteArray, retainMetadata: Boolean): ByteArray {
        val parsed = parse(bytes)
        if (retainMetadata) return bytes
        // Preserve encoded scans exactly. No JPEG recompression and no plaintext file/cache.
        val replacements = mutableMapOf<Int, ByteArray>()
        var direction = byteArrayOf()
        var result: ByteArray? = null
        try {
        val icc = parsed.segments.filter { it.marker == 0xe2 && starts(bytes, it.body, it.end, "ICC_PROFILE\u0000") }
        validateIcc(bytes, icc)
        for (segment in parsed.segments) {
            if (segment.marker !in 0xe0..0xef && segment.marker != 0xfe) continue
            val safe = when {
                segment.marker == 0xe0 && starts(bytes, segment.body, segment.end, "JFIF\u0000") -> {
                    val size = segment.end - segment.body
                    require(size >= 14 && u(bytes, segment.body + 5) == 1 && u(bytes, segment.body + 6) <= 2)
                    require(u(bytes, segment.body + 7) <= 2)
                    val thumbnails = 3 * u(bytes, segment.body + 12) * u(bytes, segment.body + 13)
                    require(size == 14 + thumbnails)
                    val body = bytes.copyOfRange(segment.body, segment.body + 14)
                    body[12] = 0; body[13] = 0
                    try { marker(0xe0, body) } finally { body.fill(0) }
                }
                segment in icc -> bytes.copyOfRange(segment.start, segment.end)
                segment.marker == 0xee && starts(bytes, segment.body, segment.end, "Adobe") -> {
                    require(segment.end - segment.body == 12 && u(bytes, segment.end - 1) in 0..1)
                    bytes.copyOfRange(segment.start, segment.end)
                }
                else -> byteArrayOf() // EXIF/XMP/IPTC/JFXX, unknown APP extensions and comments.
            }
            replacements[segment.start] = safe
        }
        direction = if (parsed.image.orientation == 1) byteArrayOf() else orientationSegment(parsed.image.orientation)
        val removed = parsed.segments.sumOf { s -> if (s.start in replacements) s.end - s.start else 0 }
        val size = bytes.size - removed + replacements.values.sumOf { it.size } + direction.size
        require(size <= LocalAttachmentController.MAX_SOURCE_BYTES)
        val output = ByteArray(size)
        result = output
            var source = 2; var target = 2
            bytes.copyInto(output, 0, 0, 2); direction.copyInto(output, target); target += direction.size
            for (segment in parsed.segments) {
                val replacement = replacements[segment.start] ?: continue
                bytes.copyInto(output, target, source, segment.start); target += segment.start - source
                replacement.copyInto(output, target); target += replacement.size; source = segment.end
            }
            bytes.copyInto(output, target, source, bytes.size)
            require(inspect(output) == parsed.image)
            return output
        } catch (failure: Exception) { result?.fill(0); throw failure }
        finally { replacements.values.forEach { it.fill(0) }; direction.fill(0) }
    }

    private fun parse(bytes: ByteArray): Parsed {
        require(bytes.size in 4..LocalAttachmentController.MAX_SOURCE_BYTES && u(bytes, 0) == 255 && u(bytes, 1) == 0xd8)
        var position = 2; var width = 0; var height = 0; var frame = 0; var orientation = 1
        var exifSeen = false; var scans = 0; var segments = 0
        var componentIds = emptySet<Int>()
        val observed = mutableListOf<Segment>()
        while (position < bytes.size) {
            require(++segments <= 1024)
            val start = position
            require(u(bytes, position++) == 255)
            while (position < bytes.size && u(bytes, position) == 255) position++
            require(position < bytes.size)
            val code = u(bytes, position++)
            if (code == 0xd9) {
                require(scans > 0 && position == bytes.size)
                return Parsed(Image(width, height, orientation), observed)
            }
            require(code in setOf(0xc0, 0xc2, 0xc4, 0xdb, 0xdd, 0xda, 0xfe) || code in 0xe0..0xef)
            require(bytes.size - position >= 2)
            val length = word(bytes, position)
            require(length >= 2 && length <= bytes.size - position)
            val body = position + 2; val end = position + length
            observed += Segment(start, end, code, body)
            when (code) {
                0xc0, 0xc2 -> {
                    require(frame == 0 && length >= 8 && u(bytes, body) == 8)
                    frame = code; height = word(bytes, body + 1); width = word(bytes, body + 3)
                    require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION && width.toLong() * height <= MAX_PIXELS)
                    val count = u(bytes, body + 5)
                    require(count in setOf(1, 3) && length == 8 + 3 * count)
                    componentIds = (0 until count).map { index ->
                        val at = body + 6 + 3 * index; val factors = u(bytes, at + 1)
                        require(factors shr 4 in 1..4 && factors and 15 in 1..4 && u(bytes, at + 2) <= 3)
                        u(bytes, at)
                    }.toSet()
                    require(componentIds.size == count)
                }
                0xe1 -> if (starts(bytes, body, end, "Exif\u0000\u0000")) {
                    require(!exifSeen); exifSeen = true
                    orientation = exifOrientation(bytes, body + 6, end)
                }
                0xdd -> require(length == 4)
                0xda -> {
                    require(frame != 0 && ++scans <= 128 && length >= 6)
                    val count = u(bytes, body)
                    require(count in 1..componentIds.size && length == 6 + 2 * count)
                    val ids = (0 until count).map { u(bytes, body + 1 + 2 * it) }
                    require(ids.distinct().size == count && ids.all { it in componentIds })
                    val spectral = body + 1 + 2 * count
                    val first = u(bytes, spectral); val last = u(bytes, spectral + 1); val approx = u(bytes, spectral + 2)
                    require(first <= last && last <= 63 && approx shr 4 <= 13 && approx and 15 <= 13)
                    require(if (frame == 0xc0) first == 0 && last == 63 && approx == 0 else first != 0 || last == 0)
                    if (first > 0) require(count == 1)
                    position = end
                    val entropyStart = position
                    while (position < bytes.size) {
                        if (u(bytes, position) != 255) { position++; continue }
                        val markerStart = position++
                        while (position < bytes.size && u(bytes, position) == 255) position++
                        require(position < bytes.size)
                        val next = u(bytes, position)
                        if (next == 0 || next in 0xd0..0xd7) { position++; continue }
                        position = markerStart; break
                    }
                    require(position > entropyStart && position < bytes.size)
                    continue
                }
            }
            position = end
        }
        throw IllegalArgumentException("Incomplete JPEG container")
    }

    private fun exifOrientation(bytes: ByteArray, base: Int, end: Int): Int {
        require(end - base >= 8)
        val little = u(bytes, base) == 0x49 && u(bytes, base + 1) == 0x49
        require(little || u(bytes, base) == 0x4d && u(bytes, base + 1) == 0x4d)
        fun number(offset: Int, count: Int): Long {
            require(offset >= 0 && offset.toLong() + count <= end.toLong() - base)
            return (0 until count).fold(0L) { value, index ->
                (value shl 8) or u(bytes, base + offset + if (little) count - 1 - index else index).toLong()
            }
        }
        require(number(2, 2) == 42L)
        val offset = number(4, 4); require(offset >= 8 && offset <= end.toLong() - base - 2)
        val count = number(offset.toInt(), 2)
        require(offset + 2 + count * 12 + 4 <= end.toLong() - base)
        var direction: Int? = null
        repeat(count.toInt()) { index ->
            val at = offset.toInt() + 2 + index * 12
            if (number(at, 2) == 0x112L) {
                require(direction == null && number(at + 2, 2) == 3L && number(at + 4, 4) == 1L)
                direction = number(at + 8, 2).toInt(); require(direction in 0..8)
            }
        }
        return direction?.takeUnless { it == 0 } ?: 1
    }

    private fun validateIcc(bytes: ByteArray, segments: List<Segment>) {
        if (segments.isEmpty()) return
        require(segments.first().end - segments.first().body > 14)
        val count = u(bytes, segments.first().body + 13)
        require(count in 1..255 && segments.size == count)
        val chunks = segments.associateBy {
            require(it.end - it.body > 14 && u(bytes, it.body + 13) == count)
            u(bytes, it.body + 12).also { sequence -> require(sequence in 1..count) }
        }
        require(chunks.size == count)
        val size = segments.sumOf { it.end - it.body - 14 }; require(size in 132..4 * 1024 * 1024)
        val profile = ByteArray(size)
        try {
            var at = 0
            for (index in 1..count) { val chunk = checkNotNull(chunks[index]); bytes.copyInto(profile, at, chunk.body + 14, chunk.end); at += chunk.end - chunk.body - 14 }
            fun number(offset: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or u(profile, offset + i).toLong() }
            require(number(0) == size.toLong() && starts(profile, 36, size, "acsp"))
            val tags = number(128); require(tags <= 4096 && 132 + 12 * tags <= size)
            repeat(tags.toInt()) { index -> val tag = 132 + 12 * index; require(number(tag + 4) + number(tag + 8) <= size) }
        } finally { profile.fill(0) }
    }

    private fun starts(bytes: ByteArray, at: Int, end: Int, text: String): Boolean =
        end - at >= text.length && text.indices.all { u(bytes, at + it) == text[it].code }
    private fun u(bytes: ByteArray, at: Int) = bytes[at].toInt() and 255
    private fun word(bytes: ByteArray, at: Int) = (u(bytes, at) shl 8) or u(bytes, at + 1)
    private fun marker(code: Int, body: ByteArray): ByteArray = byteArrayOf(-1, code.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body
    private fun orientationSegment(orientation: Int): ByteArray = marker(0xe1,
        byteArrayOf(69, 120, 105, 102, 0, 0, 77, 77, 0, 42, 0, 0, 0, 8, 0, 1,
            1, 18, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0))
}
