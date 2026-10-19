package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Base64

/** Shared existing capture envelope decoder; independent of the SQL owner and backup framing. */
internal object CaptureDraftCodec {
    fun decode(encoded: String): CaptureDraft {
        require(encoded.length<=560000);val bytes=Base64.getDecoder().decode(encoded)
        try {
            require(bytes.size<=420000 && Base64.getEncoder().encodeToString(bytes)==encoded)
            val r=PackageReader(bytes);val format=r.i32();require(format in 1..2)
            val policy=if(format==1) ProcessingPolicy.LOCAL_ONLY else ProcessingPolicy.valueOf(r.string(16)).also { require(it==ProcessingPolicy.AI_NONE) }
            val id=r.string(36);val version=r.i64();val discarding=r.i32();require(discarding in 0..1)
            val count=r.count(100000);val text=CharArray(count) { r.i32().also { require(it in 0..65535) }.toChar() }.let { chars ->try { String(chars) } finally { chars.fill('\u0000') } }
            val images=List(r.count(20)) { val image=r.string(36);val mime=r.string(32);val size=r.i64();val hash=packageHex(r.raw(32));val state=r.i32();require(state in CaptureImageState.entries.indices)
                CaptureDraftImage(AttachmentIntent(image,id,1,mime,hash,size),CaptureImageState.entries[state]) }
            r.end();return CaptureDraft(id,version,text,images,discarding==1,policy)
        } finally { bytes.fill(0) }
    }
}
