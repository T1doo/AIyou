package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class ImageTextPayload(val anchor:ImageTextSource,val engine:ImageTextEngine,val history:List<ImageTextVersion>) {
    init { require(anchor.policy!=ProcessingPolicy.AI_NONE) }
    override fun toString()="ImageTextPayload(redacted)"
}
internal object ImageTextEncoding {
    fun anchor(w:PackageWriter,a:ImageTextSource) {val i=a.intent;w.string(i.id,36);w.string(i.entryId,512);w.i32(i.revision);w.string(i.mediaType,32);w.string(i.sha256,64);w.i64(i.byteSize);w.string(a.sourceHash,64);w.i64(a.generation);w.i64(a.capturedAtMillis);w.string(a.zone,128);w.string(a.policy.name,32)}
    fun anchor(r:PackageReader):ImageTextSource {val i=AttachmentIntent(r.string(36),r.string(512),r.i32(),r.string(32),r.string(64),r.i64());return ImageTextSource(i,r.string(64),r.i64(),r.i64(),r.string(128),ProcessingPolicy.valueOf(r.string(32)))}
    fun encode(p:ImageTextPayload)=PackageWriter(65507).use {w->
        w.string("AIyou-image-derived-text",40);w.i32(1);anchor(w,p.anchor);w.string(p.anchor.sourceMap,32)
        w.string(p.engine.id,64);w.string(p.engine.version,64);w.i32(if(p.engine.synthetic)1 else 0)
        w.i32(p.history.size);p.history.forEach{w.i32(it.revision);w.string(it.text,4000);w.i32(if(it.humanCorrection)1 else 0);w.i64(it.at)};w.bytes()
    }
    fun decode(bytes:ByteArray):ImageTextPayload {
        val r=PackageReader(bytes);require(r.string(40)=="AIyou-image-derived-text"&&r.i32()==1)
        val a=anchor(r);require(r.string(32)==a.sourceMap);val id=r.string(64);val version=r.string(64);val synthetic=r.i32();require(synthetic in 0..1)
        val history=List(r.count(8)){val n=r.i32();val text=r.string(4000);val human=r.i32();require(human in 0..1);ImageTextVersion(n,text,human==1,r.i64())};r.end()
        require(history.isNotEmpty()&&history.map{it.revision}==(1..history.size).toList())
        return ImageTextPayload(a,ImageTextEngine(id,version,synthetic==1),history)
    }
}
internal class ImageTextCipher(databaseKey:ByteArray) {
    private val key=Mac.getInstance("HmacSHA256").run{require(databaseKey.size==32);init(SecretKeySpec(databaseKey,"HmacSHA256"));doFinal("com.t1doo.aiyou.image-derived-text.v1".toByteArray(Charsets.US_ASCII)+byteArrayOf(1))}
    fun seal(aad:ByteArray,p:ImageTextPayload):ByteArray {
        val plain=ImageTextEncoding.encode(p);val nonce=ByteArray(12).also(SecureRandom()::nextBytes)
        return try{val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));c.updateAAD(aad);byteArrayOf(5)+nonce+c.doFinal(plain)}finally{plain.fill(0)}
    }
    fun open(aad:ByteArray,b:ByteArray):ImageTextPayload {
        require(b.size in 29..65536&&b[0]==5.toByte());val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,b.copyOfRange(1,13)));c.updateAAD(aad)
        val plain=c.doFinal(b,13,b.size-13);return try{ImageTextEncoding.decode(plain)}finally{plain.fill(0)}
    }
    fun portable(aad:ByteArray,b:ByteArray,restoring:Boolean):ByteArray {
        if(!restoring){open(aad,b);return PortableCipherCell.open(key,aad,b,65536)}
        val sealed=PortableCipherCell.seal(key,aad,b,65536)
        return try{val p=open(aad,sealed);seal(aad,p.copy(anchor=p.anchor.copy(policy=if(p.anchor.policy==ProcessingPolicy.AI_NONE)p.anchor.policy else ProcessingPolicy.LOCAL_ONLY)))}finally{sealed.fill(0)}
    }
    fun wipe(){key.fill(0)}
}
