package com.t1doo.aiyou.data
import com.t1doo.aiyou.model.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
internal data class TranscriptPayload(val anchor:AudioTranscriptSource,val history:List<AudioTranscriptVersion>,val withdrawn:Boolean,val expected:Long,val at:Long) {
    override fun toString()="TranscriptPayload(redacted)"
}
internal object TranscriptEncoding {
    fun anchor(w:PackageWriter,a:AudioTranscriptSource) {val i=a.intent;w.string(i.id,36);w.string(i.entryId,512);w.i32(i.revision);w.string(i.mediaType,32);w.string(i.sha256,64);w.i64(i.byteSize);w.string(a.sourceHash,64);w.i64(a.generation);w.i64(a.capturedAtMillis);w.string(a.zone,128);w.string(a.policy.name,32);w.i32(a.samples)}
    fun anchor(r:PackageReader):AudioTranscriptSource {val i=AttachmentIntent(r.string(36),r.string(512),r.i32(),r.string(32),r.string(64),r.i64());return AudioTranscriptSource(i,r.string(64),r.i64(),r.i64(),r.string(128),ProcessingPolicy.valueOf(r.string(32)),r.i32())}
    private const val MAGIC="AIyou-human-audio-transcript"
    fun range(w:PackageWriter,value:AudioSampleRange?) {w.i32(if(value==null)0 else 1);value?.let {w.i32(it.startFrame);w.i32(it.endFrameExclusive)}}
    private fun range(r:PackageReader):AudioSampleRange? = when(r.i32()) {0->null;1->AudioSampleRange(r.i32(),r.i32());else->error("Unknown range flag")}
    fun encode(p:TranscriptPayload):ByteArray {
        p.history.forEach {it.range?.validate(p.anchor.samples)}
        val version=if(p.history.any {it.range!=null})2 else 1
        return PackageWriter(65507).use {w->w.string(MAGIC,40);w.i32(version);anchor(w,p.anchor);w.i32(if(p.withdrawn)1 else 0);w.i64(p.expected);w.i64(p.at);w.i32(p.history.size);p.history.forEach {w.i32(it.revision);w.string(it.text,4000);w.i64(it.at);if(version==2)range(w,it.range)};w.bytes()}
    }
    fun decode(bytes:ByteArray):TranscriptPayload {
        val r=PackageReader(bytes);require(r.string(40)==MAGIC);val version=r.i32();require(version in 1..2)
        val a=anchor(r);val flag=r.i32();require(flag in 0..1);val expected=r.i64();val at=r.i64()
        val h=List(r.count(8)){AudioTranscriptVersion(r.i32(),r.string(4000),r.i64(),if(version==2)range(r)else null)}
        r.end();require(h.isNotEmpty()&&h.map{it.revision}==(1..h.size).toList()&&expected>=0&&at>=0)
        h.forEach {it.range?.validate(a.samples)};require(version==1||h.any {it.range!=null})
        return TranscriptPayload(a,h,flag==1,expected,at)
    }
    /** Cheap format selection only; full portable validation remains mandatory before an intent. */
    fun portableVersion(bytes:ByteArray):Int {
        val r=PackageReader(bytes);require(r.raw(1).single()==4.toByte()&&r.string(40)==MAGIC)
        return r.i32().also {require(it in 1..2)}
    }
    fun decodePortable(bytes:ByteArray):TranscriptPayload {
        require(bytes.size in 2..65508&&bytes[0]==4.toByte())
        val plain=bytes.copyOfRange(1,bytes.size)
        return try {decode(plain)}finally {plain.fill(0)}
    }
}
internal class AudioTranscriptCipher(databaseKey:ByteArray) {
    private val key=Mac.getInstance("HmacSHA256").run {require(databaseKey.size==32);init(SecretKeySpec(databaseKey,"HmacSHA256"));doFinal("com.t1doo.aiyou.manual-audio-transcripts.v1".toByteArray(Charsets.US_ASCII)+byteArrayOf(1))}
    fun seal(aad:ByteArray,p:TranscriptPayload):ByteArray {val plain=TranscriptEncoding.encode(p);val nonce=ByteArray(12).also(SecureRandom()::nextBytes);return try {val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));c.updateAAD(aad);byteArrayOf(4)+nonce+c.doFinal(plain)}finally{plain.fill(0)}}
    fun open(aad:ByteArray,b:ByteArray):TranscriptPayload {require(b.size in 29..65536&&b[0]==4.toByte());val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,b.copyOfRange(1,13)));c.updateAAD(aad);val plain=c.doFinal(b,13,b.size-13);return try{TranscriptEncoding.decode(plain)}finally{plain.fill(0)}}
    fun portable(aad:ByteArray,b:ByteArray,restoring:Boolean):ByteArray {if(!restoring){open(aad,b);return PortableCipherCell.open(key,aad,b,65536)};val sealed=PortableCipherCell.seal(key,aad,b,65536);return try {val p=open(aad,sealed);seal(aad,p.copy(anchor=p.anchor.copy(policy=if(p.anchor.policy==ProcessingPolicy.AI_NONE)p.anchor.policy else ProcessingPolicy.LOCAL_ONLY)))}finally{sealed.fill(0)}}
    fun wipe(){key.fill(0)}
}
