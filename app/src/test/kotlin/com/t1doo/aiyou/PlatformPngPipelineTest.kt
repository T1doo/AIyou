package com.t1doo.aiyou

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.t1doo.aiyou.records.PlatformPngDecoder
import com.t1doo.aiyou.records.PlatformPngSource
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/** Actual ContentResolver and Android bitmap native graphics on the JVM, not a physical device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlatformPngPipelineTest {
    @Test fun actualNativeDecoderReadsPixelsAndKeepsOriginalBytesUnchanged() {
        val bytes = png(1, 1); val original = bytes.copyOf()
        val picture = PlatformPngDecoder.decode(bytes)
        assertEquals(1, picture.width); assertEquals(1, picture.height)
        val image = (picture as com.t1doo.aiyou.records.PlatformAttachmentPicture).image
        val buffer = IntArray(1); image.readPixels(buffer)
        assertEquals(0xff125678.toInt(), buffer.single())
        assertArrayEquals(original, bytes)
    }

    @Test fun largePngIsActuallySampledAndDimensionsAboveBoundAreRejected() {
        val picture = PlatformPngDecoder.decode(png(4096, 8))
        assertEquals(1024, picture.width); assertTrue(picture.height in 1..1024)
        rejected { PlatformPngDecoder.decode(png(4097, 1)) }
        rejected { PlatformPngDecoder.decode(png(1, 4097)) }
    }

    @Test fun malformedPixelsCrcTruncationAnimationOrientationAndTrailingBytesAreRejected() {
        val valid = png(1, 1)
        val invalid = listOf(valid.copyOf().apply { this[29] = (this[29].toInt() xor 1).toByte() },
            valid.copyOf(valid.size - 1), valid + 1, png(1, 1, extra = chunk("acTL", ByteArray(8))),
            png(1, 1, extra = chunk("eXIf", ByteArray(8))), png(1, 1, compressed = byteArrayOf(1, 2, 3)),
            png(1, 1, compressed = valid.copyOfRange(41, valid.size - 16).dropLast(3).toByteArray()),
            byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        invalid.forEach { bytes -> rejected { PlatformPngDecoder.decode(bytes) } }
    }

    @Test fun selectedContentSourceUsesRealResolverAndPreservesPermissionFailure() {
        val app = RuntimeEnvironment.getApplication()
        val bytes = png(2, 3)
        val file = File(app.cacheDir, "synthetic-test-provider.png").apply { writeBytes(bytes) }
        val provider = TestProvider(file)
        ShadowContentResolver.registerProviderInternal("aiyou.synthetic", provider)
        val source = PlatformPngSource(app.contentResolver, Uri.parse("content://aiyou.synthetic/image"))
        assertEquals("image/png", source.mediaType())
        val read = source.open().use { it.readBytes() }
        assertArrayEquals(bytes, read)
        assertEquals(2, PlatformPngDecoder.decode(read).width)
        provider.denied = true
        try { source.open(); fail("Expected actual provider denial") } catch (_: SecurityException) { }
        rejected { PlatformPngSource(app.contentResolver, Uri.parse("file:///synthetic.png")) }
        rejected { PlatformPngSource(app.contentResolver, Uri.parse("https://example.com/synthetic.png")) }
        assertArrayEquals(bytes, file.readBytes())
        check(file.delete()) // only the test-owned provider fixture
    }

    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected invalid PNG/source rejection") }
        catch (_: IllegalArgumentException) { }
    }

    private class TestProvider(private val file: File) : ContentProvider() {
        var denied = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (denied) throw SecurityException("synthetic selected source permission revoked")
            check(mode == "r")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read only test provider")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("read only test provider")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("read only test provider")
    }

    companion object {
        internal fun png(width: Int, height: Int, extra: ByteArray = byteArrayOf(), compressed: ByteArray? = null): ByteArray {
            val header = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use {
                it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 6, 0, 0, 0))
            } }.toByteArray()
            val pixels = compressed ?: ByteArrayOutputStream().also { stream ->
                DeflaterOutputStream(stream).use { output -> repeat(height) {
                    output.write(0)
                    repeat(width) { output.write(byteArrayOf(0x12, 0x56, 0x78, -1)) }
                } }
            }.toByteArray()
            return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", header) + extra + chunk("IDAT", pixels) + chunk("IEND", byteArrayOf())
        }
        internal fun chunk(type: String, bytes: ByteArray): ByteArray {
            val name = type.toByteArray(Charsets.US_ASCII)
            return ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use {
                it.writeInt(bytes.size); it.write(name); it.write(bytes)
                it.writeInt(CRC32().apply { update(name); update(bytes) }.value.toInt())
            } }.toByteArray()
        }
    }
}
