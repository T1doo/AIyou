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
    fun encode(p:TranscriptPayload)=PackageWriter(65507).use {w->w.string("AIyou-human-audio-transcript",40);w.i32(1);anchor(w,p.anchor);w.i32(if(p.withdrawn)1 else 0);w.i64(p.expected);w.i64(p.at);w.i32(p.history.size);p.history.forEach {w.i32(it.revision);w.string(it.text,4000);w.i64(it.at)};w.bytes()}
    fun decode(bytes:ByteArray):TranscriptPayload {val r=PackageReader(bytes);require(r.string(40)=="AIyou-human-audio-transcript"&&r.i32()==1);val a=anchor(r);val flag=r.i32();require(flag in 0..1);val expected=r.i64();val at=r.i64();val h=List(r.count(8)){AudioTranscriptVersion(r.i32(),r.string(4000),r.i64())};r.end();require(h.isNotEmpty()&&h.map{it.revision}==(1..h.size).toList()&&expected>=0&&at>=0);return TranscriptPayload(a,h,flag==1,expected,at)}
}
internal class AudioTranscriptCipher(databaseKey:ByteArray) {
    private val key=Mac.getInstance("HmacSHA256").run {require(databaseKey.size==32);init(SecretKeySpec(databaseKey,"HmacSHA256"));doFinal("com.t1doo.aiyou.manual-audio-transcripts.v1".toByteArray(Charsets.US_ASCII)+byteArrayOf(1))}
    fun seal(aad:ByteArray,p:TranscriptPayload):ByteArray {val plain=TranscriptEncoding.encode(p);val nonce=ByteArray(12).also(SecureRandom()::nextBytes);return try {val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));c.updateAAD(aad);byteArrayOf(4)+nonce+c.doFinal(plain)}finally{plain.fill(0)}}
    fun open(aad:ByteArray,b:ByteArray):TranscriptPayload {require(b.size in 29..65536&&b[0]==4.toByte());val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,b.copyOfRange(1,13)));c.updateAAD(aad);val plain=c.doFinal(b,13,b.size-13);return try{TranscriptEncoding.decode(plain)}finally{plain.fill(0)}}
    fun portable(aad:ByteArray,b:ByteArray,restoring:Boolean):ByteArray {if(!restoring){open(aad,b);return PortableCipherCell.open(key,aad,b,65536)};val sealed=PortableCipherCell.seal(key,aad,b,65536);return try {val p=open(aad,sealed);seal(aad,p.copy(anchor=p.anchor.copy(policy=if(p.anchor.policy==ProcessingPolicy.AI_NONE)p.anchor.policy else ProcessingPolicy.LOCAL_ONLY)))}finally{sealed.fill(0)}}
    fun wipe(){key.fill(0)}
}
