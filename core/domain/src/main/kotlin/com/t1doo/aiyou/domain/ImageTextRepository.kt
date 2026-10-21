package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

interface ImageTextPage {val keyword:String;val cursor:PreferenceCursor?;val next:PreferenceCursor?;val items:List<ImageTextSnapshot>}
interface ImageTextRepository {
    suspend fun preview(intent:AttachmentIntent):ImageTextSource
    suspend fun read(anchor:ImageTextSource):ImageTextSnapshot?
    suspend fun write(request:ImageTextWrite):ImageTextSnapshot?
    suspend fun search(keyword:String,cursor:PreferenceCursor?=null):ImageTextPage
    suspend fun resolve(page:ImageTextPage,item:ImageTextSnapshot?=null,publish:()->Unit):Boolean
}
/** Only a separately authorized, bundled local adapter may implement production inference.
 * Input is one authenticated original (<=16MiB), never a saved plaintext file. The adapter must
 * validate/decode orientation, bound its bitmap to <=2048px/edge and <=4,194,304 pixels, and
 * return/throw only after task and image/recognizer resources have actually settled/closed.
 * Cancellation/deadline requests are not resource-release acknowledgements. No remote fallback. */
interface ImageTextRecognizer {
    val available:Boolean
    suspend fun recognize(source:ImageTextSource,encoded:ByteArray,cancelled:()->Boolean):ImageTextExtraction?
}
object UnavailableImageTextRecognizer:ImageTextRecognizer {
    override val available=false
    override suspend fun recognize(source:ImageTextSource,encoded:ByteArray,cancelled:()->Boolean):ImageTextExtraction? = throw ImageTextModelUnavailable()
}
class ImageTextModelUnavailable:IllegalStateException("Local OCR model is not authorized and installed")
class ImageTextConflict:IllegalStateException("Image text or original changed")
class ImageTextUnavailable:IllegalStateException("Image text storage unavailable")
class ImageTextLimit:IllegalStateException("Image text capacity exceeded")
