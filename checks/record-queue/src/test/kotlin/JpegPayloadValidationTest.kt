import com.t1doo.aiyou.JpegFixture
import com.t1doo.aiyou.records.JpegPayloadValidator
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Container/metadata assertions only. Android entropy decoding is tested separately. */
class JpegPayloadValidationTest {
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected bounded JPEG rejection") } catch (_: IllegalArgumentException) {}
    }
    private fun prefix(image: ByteArray, segment: ByteArray) = image.copyOfRange(0,2) + segment + image.copyOfRange(2,image.size)
    @Test fun baselineProgressiveAndAllExifDirectionsPreserveOriginalByDefault() {
        assertEquals(JpegPayloadValidator.Image(96,64,1), JpegPayloadValidator.inspect(JpegFixture.baseline()))
        assertEquals(JpegPayloadValidator.Image(96,64,1), JpegPayloadValidator.inspect(JpegFixture.progressive()))
        for (little in listOf(false,true)) for (direction in 1..8) {
            val bytes = JpegFixture.withMetadata(direction,little)
            val original = bytes.copyOf()
            assertEquals(JpegPayloadValidator.Image(96,64,direction), JpegPayloadValidator.inspect(bytes))
            assertSame(bytes, JpegPayloadValidator.prepare(bytes,true)); assertArrayEquals(original,bytes)
        }
    }
    @Test fun explicitStrippingKeepsDirectionAndEncodedScansWhileRemovingPrivateExtensions() {
        for (progressive in listOf(false,true)) for (little in listOf(false,true)) for (direction in 1..8) {
            val bytes=JpegFixture.withMetadata(direction,little,progressive); val before=bytes.copyOf()
            val stripped=JpegPayloadValidator.prepare(bytes,false)
            assertNotSame(bytes,stripped); assertArrayEquals(before,bytes)
            assertEquals(JpegPayloadValidator.inspect(bytes),JpegPayloadValidator.inspect(stripped))
            assertFalse(String(stripped,Charsets.ISO_8859_1).contains("SYNTHETIC_"))
            val normal=if(progressive) JpegFixture.progressive() else JpegFixture.baseline()
            // Only a minimal orientation segment may remain before the original JFIF + scan bytes.
            val start=if(direction==1) 2 else 38
            assertArrayEquals(normal.copyOfRange(2,normal.size), stripped.copyOfRange(start,stripped.size))
        }
    }
    @Test fun embeddedThumbnailsUnknownAppsAndCommentsBetweenProgressiveScansAreRemoved() {
        val base=JpegFixture.baseline()
        val jfif=base.copyOfRange(6,20)+byteArrayOf(12,34,56)
        jfif[12]=1; jfif[13]=1
        val custom=base.copyOfRange(0,2)+JpegFixture.segment(0xe0,jfif)+JpegFixture.segment(0xe0,"JFXX\u0000SYNTHETIC_THUMBNAIL".toByteArray())+base.copyOfRange(20,base.size)
        assertArrayEquals(base,JpegPayloadValidator.prepare(custom,false))
        val progressive=JpegFixture.progressive()
        val scans=(2 until progressive.size-1).filter { progressive[it]==(-1).toByte() && progressive[it+1]==0xda.toByte() }
        val at=scans[1]
        val annotated=progressive.copyOfRange(0,at)+JpegFixture.segment(0xfe,"SYNTHETIC_LATE_COMMENT".toByteArray())+progressive.copyOfRange(at,progressive.size)
        assertArrayEquals(progressive,JpegPayloadValidator.prepare(annotated,false))
    }
    @Test fun validatedMultipartColorProfileIsPreservedAndInvalidChunksRejected() {
        val profile=ByteBuffer.allocate(132).apply { putInt(132); position(36); put("acsp".toByteArray()) }.array()
        fun icc(sequence:Int,count:Int,payload:ByteArray)=JpegFixture.segment(0xe2,"ICC_PROFILE\u0000".toByteArray()+byteArrayOf(sequence.toByte(),count.toByte())+payload)
        val first=icc(1,2,profile.copyOfRange(0,66)); val second=icc(2,2,profile.copyOfRange(66,132))
        val bytes=prefix(JpegFixture.baseline(),second+first)
        assertArrayEquals(bytes,JpegPayloadValidator.prepare(bytes,false))
        rejected { JpegPayloadValidator.prepare(prefix(JpegFixture.baseline(),first),false) }
        rejected { JpegPayloadValidator.prepare(prefix(JpegFixture.baseline(),first+first),false) }
        rejected { JpegPayloadValidator.prepare(prefix(JpegFixture.baseline(),icc(1,1,profile.copyOf().apply{this[39]=0})),false) }
        rejected { JpegPayloadValidator.prepare(prefix(JpegFixture.baseline(),JpegFixture.segment(0xe2,"ICC_PROFILE\u0000".toByteArray())),false) }
    }
    @Test fun invalidBoundsHeadersExifOffsetsDirectionsAndTrailingBytesAreRejected() {
        val base=JpegFixture.baseline()
        val frame=(2 until base.size-1).first{base[it]==(-1).toByte() && base[it+1]==0xc0.toByte()}
        val oversized=base.copyOf().apply{this[frame+7]=0x20;this[frame+8]=1}
        val tooManyPixels=base.copyOf().apply{this[frame+5]=0x20;this[frame+6]=0;this[frame+7]=0x20;this[frame+8]=0}
        listOf(base.copyOf(base.size-1),base+1,oversized,tooManyPixels,byteArrayOf(-1,0xd8.toByte(),-1,0xd9.toByte()),
            prefix(base,JpegFixture.exif(9)),prefix(base,JpegFixture.exif(1)+JpegFixture.exif(1)),
            prefix(base,JpegFixture.exif(1).apply{this[17]=(-1).toByte()})).forEach { rejected{JpegPayloadValidator.inspect(it)} }
    }
    @Test fun earlyEoiContainerCannotBeUsedAsProofOfReadablePixels() {
        // Intentional distinction: marker-valid but incomplete entropy reaches the actual Android decoder.
        assertEquals(JpegPayloadValidator.Image(96,64,1),JpegPayloadValidator.inspect(JpegFixture.earlyEoi()))
    }
}
