package com.t1doo.aiyou.records

/** Image decoding is outside these source-text contracts. */
object PlatformImageDecoder {
    fun gridThumbnail(bytes: ByteArray): AttachmentPicture = decode(bytes)
    fun thumbnail(bytes: ByteArray): AttachmentPicture = decode(bytes)
    fun decode(@Suppress("UNUSED_PARAMETER") bytes: ByteArray): AttachmentPicture =
        error("Host checks do not provide an Android image decoder")
}
