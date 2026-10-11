package com.t1doo.aiyou.records

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.InputStream
import java.nio.ByteBuffer

/** Only the explicitly selected content URI is opened; no broad or persistent permission. */
class PlatformPngSource(private val resolver: ContentResolver, private val uri: Uri) : SelectedImageSource {
    init { require(uri.scheme == "content") }
    override fun mediaType(): String? = resolver.getType(uri)
    override fun open(): InputStream = checkNotNull(resolver.openInputStream(uri))
}

class PlatformAttachmentPicture internal constructor(private val bitmap: Bitmap) : AttachmentPicture {
    val image: ImageBitmap = bitmap.asImageBitmap()
    override val width = bitmap.width
    override val height = bitmap.height
    val pixelBytes = bitmap.byteCount
    override fun releaseUnpublished() { bitmap.recycle() }
}

/** API26/27 retains the PNG path; never use a lenient JPEG decoder as a silent fallback. */
object PlatformImageDecoder {
    fun decode(bytes: ByteArray): AttachmentPicture {
        if (bytes.size >= 2 && bytes[0] == (-1).toByte() && bytes[1] == 0xd8.toByte()) {
            if (Build.VERSION.SDK_INT < 28) throw IllegalArgumentException("JPEG requires Android 9 or later")
            return decodeJpeg(bytes)
        }
        return PlatformPngDecoder.decode(bytes)
    }

    @RequiresApi(28)
    private fun decodeJpeg(bytes: ByteArray): AttachmentPicture {
        val original = JpegPayloadValidator.inspect(bytes)
        val swap = original.orientation in 5..8
        val width = if (swap) original.height else original.width
        val height = if (swap) original.width else original.height
        var bitmap: Bitmap? = null
        try {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                require(info.mimeType == "image/jpeg" && info.size.width == width && info.size.height == height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                var sample = 1
                while ((maxOf(width, height) + sample - 1) / sample > PlatformPngDecoder.MAX_PREVIEW_DIMENSION) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.setOnPartialImageListener { false }
            }
            require(bitmap.width in 1..1024 && bitmap.height in 1..1024 && bitmap.byteCount <= 4 * 1024 * 1024)
            return PlatformAttachmentPicture(bitmap)
        } catch (failure: OutOfMemoryError) {
            bitmap?.recycle(); throw IllegalArgumentException("Insufficient image decode memory", failure)
        } catch (failure: Exception) { bitmap?.recycle(); throw failure }
    }
}

/** No disk cache. Original bytes stay unchanged; only this in-memory display copy is sampled. */
object PlatformPngDecoder {
    const val MAX_DIMENSION = 4096
    const val MAX_PREVIEW_DIMENSION = 1024
    fun decode(bytes: ByteArray): AttachmentPicture {
        val dimensions = PngPayloadValidator.validate(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType == "image/png" && bounds.outWidth == dimensions.width && bounds.outHeight == dimensions.height)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
            while ((maxOf(bounds.outWidth, bounds.outHeight) + inSampleSize - 1) / inSampleSize > MAX_PREVIEW_DIMENSION) inSampleSize *= 2
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        require(bitmap.width in 1..MAX_PREVIEW_DIMENSION && bitmap.height in 1..MAX_PREVIEW_DIMENSION)
        return PlatformAttachmentPicture(bitmap)
    }

}
