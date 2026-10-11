package com.t1doo.aiyou.records

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.InputStream

/** Only the explicitly selected content URI is opened; no broad or persistent permission. */
class PlatformPngSource(private val resolver: ContentResolver, private val uri: Uri) : SelectedImageSource {
    init { require(uri.scheme == "content") }
    override fun mediaType(): String? = resolver.getType(uri)
    override fun open(): InputStream = checkNotNull(resolver.openInputStream(uri))
}

class PlatformAttachmentPicture internal constructor(bitmap: Bitmap) : AttachmentPicture {
    val image: ImageBitmap = bitmap.asImageBitmap()
    override val width = bitmap.width
    override val height = bitmap.height
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
