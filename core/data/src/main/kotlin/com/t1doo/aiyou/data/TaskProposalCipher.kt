package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Independent AEAD purpose in the existing records owner; drafts contain no copied quote. */
internal class TaskProposalCipher(databaseKey:ByteArray) {
    private val key=Mac.getInstance("HmacSHA256").run {
        require(databaseKey.size==32);init(SecretKeySpec(databaseKey,"HmacSHA256"))
        doFinal("com.t1doo.aiyou.task-proposals.v1".toByteArray(Charsets.US_ASCII)+byteArrayOf(1))
    }
    private val random=SecureRandom()
    private fun aad(id:String,version:Long,hash:String)=PackageWriter(1024).use {w ->
        w.string("AIyou task proposal envelope v1",64);w.string(id,512);w.i64(version);w.string(hash,64);w.bytes()
    }
    fun seal(p:TaskProposal,hash:String):ByteArray {
        val plain=PackageWriter(8192).use {w ->
            w.i32(1);w.string(p.id,512);w.i64(p.version)
            val b=p.binding;w.string(b.source.sourceId,512);w.i64(b.source.revision);w.string(b.sourceHash,64)
            w.i64(b.generation);w.i32(b.start);w.i32(b.end);w.string(p.titleInput,3840)
            w.string(p.dateInput.date,40);w.string(p.dateInput.time,20);w.string(p.dateInput.timeZone,512)
            w.string(p.dateInput.occurrence?.name.orEmpty(),16);w.i64(p.createdAtMillis);w.string(p.creationTimeZone,128)
            w.i64(p.updatedAtMillis);w.string(p.state.name,16);w.bytes()
        }
        val nonce=ByteArray(12).also(random::nextBytes)
        return try {val c=Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));c.updateAAD(aad(p.id,p.version,hash))
            byteArrayOf(1)+nonce+c.doFinal(plain)
        } finally {plain.fill(0)}
    }
    fun open(id:String,version:Long,hash:String,bytes:ByteArray):TaskProposal {
        require(bytes.size in 29..8221&&bytes[0]==1.toByte()&&hash.matches(Regex("[0-9a-f]{64}")))
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,bytes.copyOfRange(1,13)));c.updateAAD(aad(id,version,hash))
        val plain=c.doFinal(bytes,13,bytes.size-13)
        return try {val r=PackageReader(plain);require(r.i32()==1&&r.string(512)==id&&r.i64()==version)
            val b=TaskSourceBinding(SourceVersion(r.string(512),r.i64()),r.string(64),r.i64(),r.i32(),r.i32())
            val title=r.string(3840);val date=r.string(40);val time=r.string(20);val zone=r.string(512);val occurrence=r.string(16)
            TaskProposal(id,version,b,title,ManualTaskDateInput(date,time,zone,occurrence.takeIf(String::isNotEmpty)?.let(ManualTaskOccurrence::valueOf)),
                r.i64(),r.string(128),r.i64(),TaskProposalState.valueOf(r.string(16))).also {r.end()}
        } finally {plain.fill(0)}
    }
    internal fun portable(id:String,version:Long,hash:String,bytes:ByteArray,restoring:Boolean):ByteArray {
        require(bytes.isNotEmpty() && bytes[0]==1.toByte())
        if(!restoring) open(id,version,hash,bytes)
        val result=if(restoring) PortableCipherCell.seal(key,aad(id,version,hash),bytes,8221)
            else PortableCipherCell.open(key,aad(id,version,hash),bytes,8221)
        if(restoring) open(id,version,hash,result)
        return result
    }
    fun wipe()=key.fill(0)
}
