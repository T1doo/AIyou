package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object ReminderWire {
    fun bytes(body:DataOutputStream.()->Unit):ByteArray=ByteArrayOutputStream().use{b->DataOutputStream(b).use{it.body()};b.toByteArray()}
    fun DataOutputStream.text(s:String){val b=s.toByteArray(Charsets.UTF_8);try{require(b.size in 1..512);writeInt(b.size);write(b)}finally{b.fill(0)}}
    fun DataInputStream.text(limit:Int):String {
        val n=readInt();require(n in 1..limit && n<=available());val b=ByteArray(n);readFully(b)
        return try{Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()}finally{b.fill(0)}
    }
    fun DataInputStream.flag()=readUnsignedByte().also{require(it in 0..1)}==1
    fun DataOutputStream.optional(s:String?){writeBoolean(s!=null);s?.let{ text(it) }}
    fun DataInputStream.optional(limit:Int)=if(flag())text(limit)else null
    fun DataOutputStream.token(t:TaskReminderToken){text(t.taskId);text(t.reminderId);writeLong(t.scheduleVersion)}
    fun DataInputStream.token()=TaskReminderToken(text(512),text(64),readLong())
    fun DataOutputStream.platform(p:ReminderPlatformFingerprint){writeBoolean(p.bootCount!=null);p.bootCount?.let(::writeLong);text(p.zone);writeLong(p.appVersion)
        writeBoolean(p.availability.platformConnected);text(p.availability.notifications.name);text(p.availability.exactAlarms.name);writeBoolean(p.availability.channelEnabled)}
    fun DataInputStream.platform():ReminderPlatformFingerprint {
        val boot=if(flag())readLong()else null;val zone=text(128);val app=readLong()
        return ReminderPlatformFingerprint(boot,zone,app,TaskReminderAvailability(flag(),TaskReminderPermission.valueOf(text(16)),TaskReminderPermission.valueOf(text(16)),flag()))
    }
    fun DataOutputStream.cursor(c:ReminderScanCursor?){writeBoolean(c!=null);c?.let{text(it.taskId);writeBoolean(it.afterGeneration!=null);it.afterGeneration?.let(::writeLong);writeBoolean(it.rowExhausted)}}
    fun DataInputStream.cursor():ReminderScanCursor?=if(flag()){
        val id=text(512);val generation=if(flag())readLong()else null;ReminderScanCursor(id,generation,flag())
    }else null
    fun derive(key:ByteArray,purpose:String):ByteArray{require(key.size==32);return Mac.getInstance("HmacSHA256").run{init(SecretKeySpec(key,"HmacSHA256"));doFinal(purpose.toByteArray(Charsets.US_ASCII))}}
    fun seal(key:ByteArray,aad:ByteArray,plain:ByteArray,limit:Int):ByteArray {
        require(plain.size<=limit-29);val nonce=ByteArray(12).also{SecureRandom().nextBytes(it)}
        return Cipher.getInstance("AES/GCM/NoPadding").run{init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));updateAAD(aad);byteArrayOf(1)+nonce+doFinal(plain)}
    }
    fun open(key:ByteArray,aad:ByteArray,cell:ByteArray,limit:Int):ByteArray {
        require(cell.size in 29..limit && cell[0].toInt()==1)
        return Cipher.getInstance("AES/GCM/NoPadding").run{init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,cell.copyOfRange(1,13)));updateAAD(aad);doFinal(cell,13,cell.size-13)}
    }
}

/** Private execution metadata; never exported as a user fact or execution authority. */
internal class ReminderRuntimeMetaCipher(databaseKey:ByteArray):AutoCloseable {
    private val key=ReminderWire.derive(databaseKey,"AIyou/reminder-runtime-meta/key/1")
    private var closed=false
    private fun aad(v:Long)=ReminderWire.bytes{with(ReminderWire){text("AIyou/reminder-runtime-meta/1")};writeInt(1);writeLong(v)}
    @Synchronized fun seal(meta:ReminderRuntimeMeta):ByteArray=with(ReminderWire){
        check(!closed);require(meta.version>0)
        val raw=bytes{
            writeInt(1);text(meta.lastWriteId);writeBoolean(meta.request!=null);meta.request?.let{r->text(r.id);writeLong(r.expectedMetaVersion);writeInt(r.reasons);platform(r.platform)}
            optional(meta.planUpperTaskId);optional(meta.planCursor);writeBoolean(meta.planDone)
            writeBoolean(meta.drain!=null);meta.drain?.let{d->text(d.id);optional(d.upperTaskId);cursor(d.next);writeBoolean(d.done);writeBoolean(d.dirty)}
            writeInt(meta.failedRows)
        }
        try{ReminderWire.seal(key,aad(meta.version),raw,4096)}finally{raw.fill(0)}
    }
    @Synchronized fun open(version:Long,cell:ByteArray):ReminderRuntimeMeta=with(ReminderWire){
        check(!closed);require(version>0);val raw=ReminderWire.open(key,aad(version),cell,4096)
        try{DataInputStream(ByteArrayInputStream(raw)).use{i->
            require(i.readInt()==1);val last=i.text(512)
            val request=if(i.flag())ReminderRebuildRequest(i.text(100),i.readLong(),i.readInt(),i.platform())else null
            val upper=i.optional(512);val cursor=i.optional(512);val done=i.flag()
            val drain=if(i.flag())ReminderDrainPass(i.text(36),i.optional(512),i.cursor(),i.flag(),i.flag())else null
            ReminderRuntimeMeta(version,last,request,upper,cursor,done,drain,i.readInt()).also{require(i.available()==0)}
        }}finally{raw.fill(0)}
    }
    @Synchronized override fun close(){closed=true;key.fill(0)}
}

class ReminderEnvelopeCipher(databaseKey:ByteArray,private val applicationId:String="com.t1doo.aiyou"):ReminderEnvelopePort {
    private val key=ReminderWire.derive(databaseKey,"AIyou/reminder-envelope/key/1")
    private val identityKey=ReminderWire.derive(databaseKey,"AIyou/reminder-envelope/identity/1")
    private var closed=false
    init { require(applicationId.matches(Regex("[A-Za-z0-9_.]{1,128}"))) }
    @Synchronized override fun identity(token:TaskReminderToken):String=with(ReminderWire){
        check(!closed);val raw=bytes{text("AIyou/reminder-identity/1");token(token)}
        try{Mac.getInstance("HmacSHA256").run{init(SecretKeySpec(identityKey,"HmacSHA256"));doFinal(raw)}.joinToString(""){"%02x".format(it.toInt() and 255)}}finally{raw.fill(0)}
    }
    private fun aad(id:String,purpose:ReminderEnvelopePurpose)=ReminderWire.bytes{with(ReminderWire){text("AIyou/reminder-envelope/1");text(applicationId);text(purpose.name);text(id)}}
    @Synchronized override fun seal(token:TaskReminderToken,purpose:ReminderEnvelopePurpose):ReminderEnvelope=with(ReminderWire){
        check(!closed);val id=identity(token);val raw=bytes{token(token)}
        try{ReminderEnvelope(id,ReminderWire.seal(key,aad(id,purpose),raw,2048))}finally{raw.fill(0)}
    }
    @Synchronized override fun open(envelope:ReminderEnvelope,purpose:ReminderEnvelopePurpose):TaskReminderToken=with(ReminderWire){
        check(!closed);val cell=envelope.bytes();val raw=try{ReminderWire.open(key,aad(envelope.identity,purpose),cell,2048)}finally{cell.fill(0)}
        try{DataInputStream(ByteArrayInputStream(raw)).use{i->i.token().also{require(i.available()==0 && identity(it)==envelope.identity)}}}finally{raw.fill(0)}
    }
    @Synchronized override fun close(){closed=true;key.fill(0);identityKey.fill(0)}
}
