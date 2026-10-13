package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant

/** Versioned canonical binary envelopes; no Java serialization or unbounded allocation. */
object RemoteTextCodec {
    private const val MAX_BYTES = 1_048_576
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    fun textHash(text: String): String { require(validUnicode(text)); return sha256(text.toByteArray(Charsets.UTF_8)) }

    fun encodeConnection(value: ProviderConnection): ByteArray = encode(1) { connection(value) }
    fun decodeConnection(bytes: ByteArray): ProviderConnection = decode(bytes, 1) { connection() }
    fun encodeConsent(value: ConsentGrant): ByteArray = encode(2) {
        text(value.consentId,256); writeLong(value.version); source(value.source); connection(value.connection)
        text(value.purpose.name,32); instant(value.issuedAt); instant(value.expiresAt); writeBoolean(value.revoked)
    }
    fun decodeConsent(bytes: ByteArray): ConsentGrant = decode(bytes,2) {
        ConsentGrant(text(256),readLong(),source(),connection(),TextPurpose.valueOf(text(32)),instant(),instant(),readBoolean())
    }
    fun encodeCandidate(value: DerivedTextCandidate): ByteArray = encode(3) {
        require(value.references.size in 1..32); text(value.text,16_384); writeInt(value.references.size)
        value.references.forEach { source(it.source); writeInt(it.start); writeInt(it.end); text(it.quote,100_000) }
    }
    fun decodeCandidate(bytes: ByteArray): DerivedTextCandidate = decode(bytes,3) {
        val summary=text(16_384); val count=readInt(); require(count in 1..32)
        DerivedTextCandidate(summary,(0 until count).map { OriginalTextReference(source(),readInt(),readInt(),text(100_000)) })
    }
    fun binding(source: SourceVersion, purpose: TextPurpose, prompt: String, generation: Long,
                connection: ProviderConnection, consent: ConsentGrant): String = sha256(encode(4) {
        source(source); text(purpose.name,32); text(prompt,128); writeLong(generation); connection(connection)
        val grant=encodeConsent(consent); writeInt(grant.size); write(grant)
    })

    private fun encode(kind:Int, block:DataOutputStream.()->Unit):ByteArray {
        val buffer=ByteArrayOutputStream()
        val bounded=object:OutputStream() {
            override fun write(value:Int) { require(buffer.size()<MAX_BYTES) { "Invalid envelope bounds" };buffer.write(value) }
            override fun write(bytes:ByteArray,offset:Int,length:Int) {
                require(length<=MAX_BYTES-buffer.size()) { "Invalid envelope bounds" };buffer.write(bytes,offset,length)
            }
        }
        DataOutputStream(bounded).use { it.writeInt(0x41595458); it.writeInt(1); it.writeInt(kind); it.block() }
        return buffer.toByteArray().also { require(it.size<=MAX_BYTES) { "Invalid envelope bounds" } }
    }
    private fun <T> decode(bytes:ByteArray,kind:Int,block:DataInputStream.()->T):T {
        try {
            require(bytes.size in 12..MAX_BYTES)
            return DataInputStream(ByteArrayInputStream(bytes)).use {
                require(it.readInt()==0x41595458 && it.readInt()==1 && it.readInt()==kind)
                val result=it.block(); require(it.available()==0); result
            }
        } catch(failure:Throwable) {
            if(failure is VirtualMachineError || failure is ThreadDeath || failure is LinkageError)throw failure
            throw IllegalArgumentException("Invalid canonical envelope")
        }
    }
    private fun DataOutputStream.text(value:String,max:Int) {
        require(value.length<=max && validUnicode(value)) { "Invalid envelope text" }
        val bytes=value.toByteArray(Charsets.UTF_8); writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.text(max:Int):String {
        val size=readInt(); require(size in 0..max*4 && size<=available()) { "Invalid envelope text" }
        val bytes=ByteArray(size); readFully(bytes)
        val value=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(value.length<=max && validUnicode(value)); return value
    }
    private fun DataOutputStream.source(value:SourceVersion) { text(value.sourceId,256); writeLong(value.revision) }
    private fun DataInputStream.source()=SourceVersion(text(256),readLong())
    private fun DataOutputStream.instant(value:Instant) { writeLong(value.epochSecond); writeInt(value.nano) }
    private fun DataInputStream.instant():Instant { val seconds=readLong(); val nano=readInt(); require(nano in 0..999_999_999); return Instant.ofEpochSecond(seconds,nano.toLong()) }
    private fun DataOutputStream.connection(value:ProviderConnection) {
        text(value.providerId,256); writeLong(value.version); text(value.apiRoot,4096); text(value.protocol.name,32)
        text(value.modelId,256); text(value.secretRef.value,256); text(value.chatBudgetField.name,32); writeBoolean(value.chatUsageSupported)
    }
    private fun DataInputStream.connection()=ProviderConnection(text(256),readLong(),text(4096),ConnectionProtocol.valueOf(text(32)),
        text(256),SecretReference(text(256)),ChatBudgetField.valueOf(text(32)),readBoolean())
    fun validUnicode(value:String):Boolean {
        var i=0
        while(i<value.length) { val c=value[i++]; if(c.isHighSurrogate()) { if(i>=value.length || !value[i++].isLowSurrogate())return false }
            else if(c.isLowSurrogate())return false }
        return true
    }
}
