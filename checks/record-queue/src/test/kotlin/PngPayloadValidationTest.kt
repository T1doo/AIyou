import com.t1doo.aiyou.records.PngPayloadValidator
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Actual production whole-payload validation, independent of Android's permissive decoder. */
class PngPayloadValidationTest {
    private fun compressed(raw: ByteArray) = ByteArrayOutputStream().also { output ->
        DeflaterOutputStream(output).use { it.write(raw) }
    }.toByteArray()
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val name = type.toByteArray()
        return ByteArrayOutputStream().also { out -> DataOutputStream(out).use {
            it.writeInt(data.size); it.write(name); it.write(data)
            it.writeInt(CRC32().apply { update(name); update(data) }.value.toInt())
        } }.toByteArray()
    }
    private fun png(raw: ByteArray, width: Int = 1, height: Int = 1, color: Int = 6, depth: Int = 8, interlace: Int = 0,
        data: ByteArray = compressed(raw), extra: ByteArray = byteArrayOf()): ByteArray {
        val header = ByteArrayOutputStream().also { out -> DataOutputStream(out).use {
            it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(depth.toByte(), color.toByte(), 0, 0, interlace.toByte()))
        } }.toByteArray()
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", header) + extra + chunk("IDAT", data) + chunk("IEND", byteArrayOf())
    }
    private fun rejected(bytes: ByteArray) {
        try { PngPayloadValidator.validate(bytes); fail("Expected complete-payload rejection") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun validColorDepthPaletteAndAdam7PassesAreValidatedWithoutChangingSource() {
        val cases = listOf(png(byteArrayOf(0, 1, 2, 3, 4)), png(byteArrayOf(0, 1, 2, 3, 4), interlace = 1),
            png(byteArrayOf(0, 1), color = 0, depth = 1), png(byteArrayOf(0, 1, 2), color = 0, depth = 16),
            png(byteArrayOf(0, 1, 2, 3), color = 2), png(byteArrayOf(0, 1, 2), color = 4),
            png(byteArrayOf(0, 0), color = 3, depth = 1, extra = chunk("PLTE", byteArrayOf(0, 0, 0))))
        for (bytes in cases) {
            val original = bytes.copyOf(); assertEquals(PngPayloadValidator.Dimensions(1, 1), PngPayloadValidator.validate(bytes))
            assertArrayEquals(original, bytes)
        }
        // Adam7 width2/height2: pass1 one RGBA pixel; pass6 one; pass7 two.
        assertEquals(PngPayloadValidator.Dimensions(2, 2), PngPayloadValidator.validate(png(ByteArray(5 + 5 + 9), 2, 2, interlace = 1)))
    }

    @Test fun crcCorrectCompleteIendCannotHideTruncatedZlibBadAdlerOrTrailingCompressedData() {
        val raw = byteArrayOf(0, 1, 2, 3, 4); val data = compressed(raw)
        for (bad in listOf(data.copyOf(data.size - 1), data.copyOf(data.size - 4),
            data.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }, data + 0)) rejected(png(raw, data = bad))
    }

    @Test fun incompleteExcessRowsAndInvalidFiltersAreRejectedWithBoundedInflation() {
        rejected(png(byteArrayOf(0, 1, 2, 3)))
        rejected(png(byteArrayOf(0, 1, 2, 3, 4, 5)))
        rejected(png(byteArrayOf(5, 1, 2, 3, 4)))
        rejected(png(ByteArray(1024 * 1024)))
        rejected(png(byteArrayOf(0, 1, 2, 3, 4), height = 2))
    }

    @Test fun invalidDimensionsDepthCriticalStructureAndUnsupportedMetadataAreRejected() {
        val raw = byteArrayOf(0, 1, 2, 3, 4)
        listOf(png(raw, width = 0), png(raw, width = 4097), png(raw, depth = 3), png(raw, interlace = 2),
            png(raw, extra = chunk("IHDR", ByteArray(13))), png(raw, extra = chunk("ABCD", byteArrayOf())),
            png(raw, extra = chunk("acTL", ByteArray(8))), png(raw, extra = chunk("eXIf", ByteArray(8))),
            png(byteArrayOf(0, 0), color = 3, depth = 1)).forEach(::rejected)
    }
}
