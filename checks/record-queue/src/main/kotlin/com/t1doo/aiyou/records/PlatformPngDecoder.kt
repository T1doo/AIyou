package com.t1doo.aiyou.records

/** Host boundary only. New tests must explicitly inject their decoder, never claim platform decode. */
object PlatformPngDecoder {
    fun decode(@Suppress("UNUSED_PARAMETER") bytes: ByteArray): AttachmentPicture =
        error("Android image decoding is not available in this host check; inject a test boundary")
}
