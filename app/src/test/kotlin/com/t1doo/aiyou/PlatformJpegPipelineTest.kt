package com.t1doo.aiyou

import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.media.ExifInterface
import com.t1doo.aiyou.records.JpegPayloadValidator
import com.t1doo.aiyou.records.PlatformAttachmentPicture
import com.t1doo.aiyou.records.PlatformImageDecoder
import com.t1doo.aiyou.records.LocalAttachmentController
import com.t1doo.aiyou.records.SelectedImageSource
import com.t1doo.aiyou.domain.AttachmentStore
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.model.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Android ImageDecoder with native graphics on JVM/API35, not physical-device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlatformJpegPipelineTest {
    private fun corners(picture:PlatformAttachmentPicture):List<Int> {
        val pixels=IntArray(picture.width*picture.height); picture.image.readPixels(pixels)
        return listOf(pixels[8*picture.width+8],pixels[8*picture.width+picture.width-9],
            pixels[(picture.height-9)*picture.width+8],pixels[(picture.height-9)*picture.width+picture.width-9])
    }
    private fun color(pixel:Int):Int {
        val r=Color.red(pixel); val g=Color.green(pixel); val b=Color.blue(pixel)
        // Generated source colors are muted; allow small JPEG conversion rounding only.
        val palette=listOf(intArrayOf(208,32,32),intArrayOf(31,176,47),intArrayOf(32,47,208),intArrayOf(224,192,32))
        return palette.indexOfFirst { kotlin.math.abs(r-it[0])<=5 && kotlin.math.abs(g-it[1])<=5 && kotlin.math.abs(b-it[2])<=5 }
            .also { check(it>=0) { "Unexpected quadrant pixel $pixel" } }
    }
    @Test fun nativeDecoderAppliesAllEightExifDirectionsOnceWithCorrectPixelsAndBothEndianHeaders() {
        val expected=listOf(listOf(0,1,2,3),listOf(1,0,3,2),listOf(3,2,1,0),listOf(2,3,0,1),
            listOf(0,2,1,3),listOf(2,0,3,1),listOf(3,1,2,0),listOf(1,3,0,2))
        for(little in listOf(false,true)) for(direction in 1..8) {
            val bytes=JpegFixture.withMetadata(direction,little); val before=bytes.copyOf()
            val picture=PlatformImageDecoder.decode(bytes) as PlatformAttachmentPicture
            try {
                assertEquals(if(direction>=5)64 else 96,picture.width)
                assertEquals(if(direction>=5)96 else 64,picture.height)
                assertEquals(expected[direction-1],corners(picture).map(::color)); assertArrayEquals(before,bytes)
            } finally { picture.releaseUnpublished() }
        }
    }
    @Test fun progressiveAndLargeJpegActuallyDecodeWithinDisplayBound() {
        val progressive=PlatformImageDecoder.decode(JpegFixture.progressive()) as PlatformAttachmentPicture
        try { assertEquals(listOf(0,1,2,3),corners(progressive).map(::color)) } finally { progressive.releaseUnpublished() }
        val large=PlatformImageDecoder.decode(JpegFixture.large()) as PlatformAttachmentPicture
        try { assertEquals(1024,large.width); assertTrue(large.height in 1..1024)
            assertTrue(large.pixelBytes<=4*1024*1024)
            val pixel=IntArray(large.width*large.height); large.image.readPixels(pixel)
            assertTrue(kotlin.math.abs(20-Color.red(pixel.first()))<=5)
            assertTrue(kotlin.math.abs(79-Color.green(pixel.first()))<=5)
            assertTrue(kotlin.math.abs(179-Color.blue(pixel.first()))<=5)
        } finally { large.releaseUnpublished() }
    }
    @Test fun explicitMetadataRemovalHasNoGpsAndKeepsNativeOrientedPixelsWithoutRecompression() {
        val original=JpegFixture.withMetadata(); val before=original.copyOf()
        val retainedExif=ExifInterface(ByteArrayInputStream(original))
        assertTrue(retainedExif.getLatLong(FloatArray(2)))
        val stripped=JpegPayloadValidator.prepare(original,false)
        val strippedExif=ExifInterface(ByteArrayInputStream(stripped))
        assertFalse(strippedExif.getLatLong(FloatArray(2)))
        assertEquals(6,strippedExif.getAttributeInt(ExifInterface.TAG_ORIENTATION,1))
        val a=PlatformImageDecoder.decode(original) as PlatformAttachmentPicture
        val b=PlatformImageDecoder.decode(stripped) as PlatformAttachmentPicture
        try { assertEquals(corners(a),corners(b)); assertEquals(64,b.width); assertEquals(96,b.height) }
        finally { a.releaseUnpublished(); b.releaseUnpublished() }
        assertArrayEquals(before,original)
    }
    @Test fun nativeEarlyEoiAcceptanceIsRecordedWhileProductionRejectsInsufficientPayloadBeforeSave() {
        val early=JpegFixture.earlyEoi()
        var partialReports=0
        // Preserve the real first-CI counterexample: this fixed codec accepts early EOI without
        // reporting partial. Application rejection below comes from a necessary payload bound.
        val native=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(early))) { decoder,_,_ ->
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setTargetSampleSize(1)
            decoder.setOnPartialImageListener { partialReports++; false }
        }
        try { assertEquals(96,native.width); assertEquals(64,native.height); assertEquals(0,partialReports) }
        finally { native.recycle() }
        for(bytes in listOf(early,JpegFixture.baseline().dropLast(4).toByteArray())) {
            val before=bytes.copyOf()
            try { val picture=PlatformImageDecoder.decode(bytes); picture.releaseUnpublished(); fail("Incomplete JPEG must not publish pixels") }
            catch (_: IllegalArgumentException) {}
            assertArrayEquals(before,bytes)
        }
        var writes=0; var opens=0
        val original=StoredEntry("synthetic-jpeg-record",1,"合成原文",1,"Etc/UTC",ProcessingPolicy.LOCAL_ONLY)
        val repository=object:EntryRepository {
            // Explicit synthetic storage boundary; the real controller must never reach save here.
            override val attachments=object:AttachmentStore {
                override suspend fun recover() {}
                override suspend fun list(entryId:String,revision:Int)=emptyList<LocalAttachment>()
                override suspend fun save(intent:AttachmentIntent,source:()->InputStream):LocalAttachment { writes++; error("invalid image reached save") }
                override suspend fun read(id:String):ByteArray=error("no attachment was stored")
            }
            override suspend fun entries()=listOf(original)
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("must not change capture")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("must not save capture")
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("must not change original")
        }
        val queue=ArrayDeque<suspend (EntryRepository)->Unit>()
        fun drain()=runBlocking { while(queue.isNotEmpty()) queue.removeFirst()(repository) }
        val controller=LocalAttachmentController({queue.addLast(it);true},PlatformImageDecoder::decode)
        controller.open(original.id,1); drain(); controller.select(controller.state.value!!.sessionId)
        val request=controller.state.value!!.selection!!.requestId
        controller.selected(request,object:SelectedImageSource {
            override fun mediaType()="image/jpeg"
            override fun open():InputStream { opens++;return ByteArrayInputStream(early) }
        });drain()
        assertEquals(1,opens);assertEquals(0,writes);assertTrue(controller.state.value!!.items.isEmpty())
        assertNotNull(controller.state.value!!.error);assertNull(controller.state.value!!.notice)
        assertEquals(listOf(original),runBlocking{repository.entries()})
        assertArrayEquals(JpegFixture.earlyEoi(),early)
    }
}
