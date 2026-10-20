package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Base64

/** Shared existing capture envelope decoder; independent of the SQL owner and backup framing. */
internal object CaptureDraftCodec {
    fun encode(a: CaptureDraft): String=PackageWriter(420000).use { out ->
        // LOCAL_ONLY retains the exact v1 bytes, including existing CAS states and save receipts.
        // v2 adds an explicitly restricted AI_NONE draft; older readers reject it rather than weaken it.
        out.i32(if(a.audio!=null) 3 else if(a.policy==ProcessingPolicy.LOCAL_ONLY) 1 else 2)
        if(a.audio!=null || a.policy==ProcessingPolicy.AI_NONE) out.string(a.policy.name,16)
        out.string(a.id,36);out.i64(a.version);out.i32(if(a.discarding) 1 else 0);out.i32(a.text.length)
        a.text.forEach { out.i32(it.code) };out.i32(a.images.size)
        a.images.forEach { val i=it.intent;out.string(i.id,36);out.string(i.mediaType,32);out.i64(i.byteSize);out.raw(packageUnhex(i.sha256));out.i32(it.state.ordinal) }
        a.audio?.let { audio -> val i=audio.intent;out.string(i.id,36);out.i64(i.byteSize);out.raw(packageUnhex(i.sha256));out.i32(audio.state.ordinal) }
        val bytes=out.bytes();try { Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
    }

    fun decode(encoded: String): CaptureDraft {
        require(encoded.length<=560000);val bytes=Base64.getDecoder().decode(encoded)
        try {
            require(bytes.size<=420000 && Base64.getEncoder().encodeToString(bytes)==encoded)
            val r=PackageReader(bytes);val format=r.i32();require(format in 1..3)
            val policy=if(format==1) ProcessingPolicy.LOCAL_ONLY else ProcessingPolicy.valueOf(r.string(16)).also { require(it==ProcessingPolicy.AI_NONE || (format==3 && it==ProcessingPolicy.LOCAL_ONLY)) }
            val id=r.string(36);val version=r.i64();val discarding=r.i32();require(discarding in 0..1)
            val count=r.count(100000);val text=CharArray(count) { r.i32().also { require(it in 0..65535) }.toChar() }.let { chars ->try { String(chars) } finally { chars.fill('\u0000') } }
            val images=List(r.count(20)) { val image=r.string(36);val mime=r.string(32);val size=r.i64();val hash=packageHex(r.raw(32));val state=r.i32();require(state in CaptureImageState.entries.indices)
                CaptureDraftImage(AttachmentIntent(image,id,1,mime,hash,size),CaptureImageState.entries[state]) }
            val audio=if(format==3) { val audioId=r.string(36);val size=r.i64();val hash=packageHex(r.raw(32));val state=r.i32();require(state in CaptureAudioState.entries.indices)
                CaptureDraftAudio(AttachmentIntent(audioId,id,1,"audio/wav",hash,size),CaptureAudioState.entries[state]) } else null
            r.end();return CaptureDraft(id,version,text,images,discarding==1,policy,audio)
        } finally { bytes.fill(0) }
    }
}
