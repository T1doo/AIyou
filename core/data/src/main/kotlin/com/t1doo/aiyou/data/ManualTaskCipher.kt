package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Independently derived key from the existing random database key; no new alias or credential. */
internal class ManualTaskCipher(databaseKey: ByteArray) {
    private val key: ByteArray
    private val random = SecureRandom()
    init {
        require(databaseKey.size == 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(databaseKey, "HmacSHA256"))
        key = mac.doFinal("com.t1doo.aiyou.manual-tasks.v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
    }
    fun seal(task: ManualTaskSnapshot, mutationId: String, requestedAtMillis: Long,
        operation: String = if (task.version == 1L) "CREATE" else "STATUS"): ByteArray {
        validateOperation(task, operation)
        val format=if(task.sourceReceipt==null) 2 else 3
        val plain = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                writeText(out, task.title); writeText(out, task.status.name)
                out.writeLong(task.createdAtMillis); writeText(out, task.creationTimeZone)
                out.writeLong(task.updatedAtMillis); out.writeLong(requestedAtMillis)
                writeText(out, operation)
                out.writeByte(if (task.schedule == null) 0 else 1)
                task.schedule?.let { due ->
                    writeText(out, due.date.toString()); writeText(out, due.timeZone)
                    out.writeByte(if (due.time == null) 0 else 1)
                    due.time?.let { writeText(out, it.toString()); out.writeInt(checkNotNull(due.offsetSeconds)) }
                }
                if(format==3) out.writeBoolean(true)
                task.sourceReceipt?.let { receipt ->
                    val b = receipt.binding
                    writeText(out,b.source.sourceId);out.writeLong(b.source.revision);writeText(out,b.sourceHash)
                    out.writeLong(b.generation);out.writeInt(b.start);out.writeInt(b.end);writeText(out,receipt.requestHash)
                }
            }
            bytes.toByteArray()
        }
        val nonce = ByteArray(12).also(random::nextBytes)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(task.id, task.version, mutationId, format))
            byteArrayOf(format.toByte()) + nonce + cipher.doFinal(plain)
        } finally { plain.fill(0) }
    }
    fun open(id: String, version: Long, mutationId: String, bytes: ByteArray): Payload {
        require(bytes.size in 29..4096)
        val format = bytes[0].toInt(); require(format in 1..3)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(aad(id, version, mutationId, format))
        val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
        return try {
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                var task = ManualTaskSnapshot(id, version, readText(input, 960), ManualTaskStatus.valueOf(readText(input, 16)),
                    input.readLong(), readText(input, 128), input.readLong())
                val requested = input.readLong()
                val operation = if (format == 1) null else readText(input, 8)
                if (format >= 2) {
                    val hasSchedule = input.readUnsignedByte(); require(hasSchedule in 0..1)
                    if (hasSchedule == 1) {
                        val date = java.time.LocalDate.parse(readText(input, 10))
                        val zone = readText(input, 128)
                        val hasTime = input.readUnsignedByte(); require(hasTime in 0..1)
                        val time = if (hasTime == 1) java.time.LocalTime.parse(readText(input, 5)) else null
                        val offset = if (hasTime == 1) input.readInt() else null
                        task = task.copy(schedule = ManualTaskSchedule(date, time, zone, offset))
                    }
                    validateOperation(task, checkNotNull(operation))
                }
                if (format == 3) {
                    val hasSource=input.readUnsignedByte();require(hasSource in 0..1)
                    if(hasSource==1) {
                        val source=SourceVersion(readText(input,512),input.readLong())
                        val b=TaskSourceBinding(source,readText(input,64),input.readLong(),input.readInt(),input.readInt())
                        task=task.copy(sourceReceipt=SourceTaskReceipt(b,readText(input,64)))
                    }
                }
                require(requested in 0..task.updatedAtMillis && input.available() == 0)
                Payload(task, requested, operation)
            }
        } finally { plain.fill(0) }
    }
    private fun writeText(out: DataOutputStream, text: String) {
        val raw = text.toByteArray(Charsets.UTF_8)
        try { out.writeInt(raw.size); out.write(raw) } finally { raw.fill(0) }
    }
    private fun readText(input: DataInputStream, limit: Int): String {
        val size = input.readInt(); require(size in 1..limit && size <= input.available())
        val raw = ByteArray(size); input.readFully(raw)
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() }
        finally { raw.fill(0) }
    }
    private fun validateOperation(task: ManualTaskSnapshot, operation: String) {
        when (operation) {
            "CREATE" -> require(task.version == 1L && task.status == ManualTaskStatus.OPEN)
            "STATUS" -> require(task.version > 1 && task.status != ManualTaskStatus.OPEN)
            "SET", "POSTPONE" -> require(task.version > 1 && task.status == ManualTaskStatus.OPEN && task.schedule != null)
            "CLEAR" -> require(task.version > 1 && task.status == ManualTaskStatus.OPEN && task.schedule == null)
            else -> throw IllegalArgumentException("Unknown task operation")
        }
    }
    private fun aad(id: String, version: Long, mutationId: String, format: Int) = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF("AIyou manual tasks v" + format)
            out.writeUTF(id); out.writeLong(version); out.writeUTF(mutationId)
        }
        bytes.toByteArray()
    }
    internal class Payload(val task: ManualTaskSnapshot, val requestedAtMillis: Long, val operation: String?)
    /** Records owner only, after the shared SQL gateway is sealed and drained. */
    fun wipe() = key.fill(0)
}
