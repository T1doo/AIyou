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
    @Test fun missingScanPayloadIsRejectedByNecessaryBoundWithoutClaimingEntropyCompleteness() {
        // The real native codec accepted this original counterexample. Reject its insufficient payload.
        rejected { JpegPayloadValidator.inspect(JpegFixture.earlyEoi()) }
        // Deliberately structural probes, without Huffman tables or a claim of readable pixels.
        fun frame(width:Int,height:Int,factors:List<Int>,progressive:Boolean=false):ByteArray =
            byteArrayOf(-1,0xd8.toByte()) + JpegFixture.segment(if(progressive)0xc2 else 0xc0,
                byteArrayOf(8,(height shr 8).toByte(),height.toByte(),(width shr 8).toByte(),width.toByte(),factors.size.toByte()) +
                    factors.flatMapIndexed { i,f -> listOf((i+1).toByte(),f.toByte(),0.toByte()) }.toByteArray())
        fun scan(ids:List<Int>,payload:ByteArray,first:Int=0,last:Int=63,approx:Int=0):ByteArray =
            JpegFixture.segment(0xda,byteArrayOf(ids.size.toByte()) +
                ids.flatMap { listOf(it.toByte(),0.toByte()) }.toByteArray() +
                byteArrayOf(first.toByte(),last.toByte(),approx.toByte())) + payload
        val eoi=byteArrayOf(-1,0xd9.toByte())
        fun checked(bytes:ByteArray) { JpegPayloadValidator.inspect(bytes) }
        // Grayscale optimized one-bit DC + AC prefix codes may exactly meet this bound.
        checked(frame(64,64,listOf(0x11))+scan(listOf(1),ByteArray(16))+eoi)
        rejected { checked(frame(64,64,listOf(0x11))+scan(listOf(1),ByteArray(15))+eoi) }
        // Odd 4:2:0: interleaved has 24 padded blocks (6 bytes), Y alone only 9 (3 bytes).
        val subsampled=frame(17,17,listOf(0x22,0x11,0x11))
        checked(subsampled+scan(listOf(1,2,3),ByteArray(6))+eoi)
        rejected { checked(subsampled+scan(listOf(1,2,3),ByteArray(5))+eoi) }
        checked(subsampled+scan(listOf(1),ByteArray(3))+scan(listOf(2),ByteArray(1))+scan(listOf(3),ByteArray(1))+eoi)
        rejected { checked(subsampled+scan(listOf(1),ByteArray(3))+scan(listOf(2),ByteArray(1))+eoi) }
        // FF00 is one byte; marker fill and restart markers contribute no encoded bits.
        checked(subsampled+scan(listOf(1,2,3),ByteArray(6).flatMap { listOf((-1).toByte(),0.toByte()) }.toByteArray())+eoi)
        rejected { checked(subsampled+scan(listOf(1,2,3),ByteArray(3).flatMap { listOf((-1).toByte(),0.toByte()) }.toByteArray())+eoi) }
        rejected { checked(subsampled+scan(listOf(1,2,3),ByteArray(5)+byteArrayOf(-1,-1,0xd0.toByte()))+eoi) }
        // Legal DC-only progressive must remain possible; AC EOBRUN has no per-block bound.
        val progressive=frame(64,64,listOf(0x11),true)
        checked(progressive+scan(listOf(1),ByteArray(8),0,0)+eoi)
        checked(progressive+scan(listOf(1),ByteArray(8),0,0)+scan(listOf(1),ByteArray(1),1,63)+eoi)
        rejected { checked(progressive+scan(listOf(1),ByteArray(7),0,0)+eoi) }
        rejected { checked(progressive+scan(listOf(1),ByteArray(8),0,0,0x10)+eoi) }
    }
}
