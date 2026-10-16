package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
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
        operation: String = if (task.version == 1L) "CREATE" else "STATUS", reminderRequestProof: String? = null): ByteArray {
        val format = if (task.reminder != null || operation in REMINDER_OPERATIONS) 4 else if (task.sourceReceipt == null) 2 else 3
        validateOperation(task, operation, format, reminderRequestProof)
        require(requestedAtMillis in 0..task.updatedAtMillis)
        val plain = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                writeCore(out, task)
                out.writeLong(requestedAtMillis); writeText(out, operation)
                writeSchedule(out, task.schedule)
                if (format >= 3) {
                    out.writeBoolean(task.sourceReceipt != null)
                    task.sourceReceipt?.let { writeReceipt(out, it) }
                }
                if (format == 4) {
                    writeReminder(out, task.reminder)
                    out.writeBoolean(reminderRequestProof != null)
                    reminderRequestProof?.let { writeText(out, it) }
                }
            }
            bytes.toByteArray()
        }
        val limit = if (format == 4) MAX_V4_BYTES else MAX_LEGACY_BYTES
        val nonce = ByteArray(12).also(random::nextBytes)
        return try {
            require(plain.size <= limit - 29)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(task.id, task.version, mutationId, format))
            byteArrayOf(format.toByte()) + nonce + cipher.doFinal(plain)
        } finally { plain.fill(0) }
    }
    fun open(id: String, version: Long, mutationId: String, bytes: ByteArray): Payload {
        require(bytes.size >= 29)
        val format = bytes[0].toInt(); require(format in 1..4)
        require(bytes.size <= if (format == 4) MAX_V4_BYTES else MAX_LEGACY_BYTES)
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
                if (format >= 2) task = task.copy(schedule = readSchedule(input))
                if (format >= 3 && flag(input)) task = task.copy(sourceReceipt = readReceipt(input))
                var proof: String? = null
                if (format == 4) {
                    task = task.copy(reminder = readReminder(input))
                    if (flag(input)) proof = readText(input, 64)
                }
                // Validate only after all authenticated fields, including reminder/outbox, have been decoded.
                if (operation != null) validateOperation(task, operation, format, proof)
                require(requested in 0..task.updatedAtMillis && input.available() == 0)
                Payload(task, requested, operation, proof)
            }
        } finally { plain.fill(0) }
    }
    /** Exact reminder replay binds every field of the caller's old snapshot, not just its version. */
    fun reminderRequestProof(request: TaskReminderMutation): String {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                writeText(out, "AIyou reminder request v1")
                writeText(out, request.task.id); out.writeLong(request.task.version)
                writeCore(out, request.task); writeSchedule(out, request.task.schedule)
                out.writeBoolean(request.task.sourceReceipt != null)
                request.task.sourceReceipt?.let { writeReceipt(out, it) }
                writeReminder(out, request.task.reminder)
                out.writeBoolean(request.mode != null); request.mode?.let { writeText(out, it.name) }
                out.writeLong(request.requestedAtMillis); writeText(out, request.mutationId)
            }
            buffer.toByteArray()
        }
        return try { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } }
        finally { bytes.fill(0) }
    }
    private fun writeCore(out: DataOutputStream, task: ManualTaskSnapshot) {
        writeText(out, task.title); writeText(out, task.status.name)
        out.writeLong(task.createdAtMillis); writeText(out, task.creationTimeZone); out.writeLong(task.updatedAtMillis)
    }
    private fun writeSchedule(out: DataOutputStream, schedule: ManualTaskSchedule?) {
        out.writeBoolean(schedule != null)
        schedule?.let { due ->
            writeText(out, due.date.toString()); writeText(out, due.timeZone)
            out.writeBoolean(due.time != null)
            due.time?.let { writeText(out, it.toString()); out.writeInt(checkNotNull(due.offsetSeconds)) }
        }
    }
    private fun readSchedule(input: DataInputStream): ManualTaskSchedule? {
        if (!flag(input)) return null
        val date = java.time.LocalDate.parse(readText(input, 10)); val zone = readText(input, 128)
        val hasTime = flag(input)
        val time = if (hasTime) java.time.LocalTime.parse(readText(input, 5)) else null
        val offset = if (hasTime) input.readInt() else null
        return ManualTaskSchedule(date, time, zone, offset)
    }
    private fun writeReceipt(out: DataOutputStream, receipt: SourceTaskReceipt) {
        val b = receipt.binding
        writeText(out, b.source.sourceId); out.writeLong(b.source.revision); writeText(out, b.sourceHash)
        out.writeLong(b.generation); out.writeInt(b.start); out.writeInt(b.end); writeText(out, receipt.requestHash)
    }
    private fun readReceipt(input: DataInputStream): SourceTaskReceipt {
        val source = SourceVersion(readText(input, 512), input.readLong())
        val binding = TaskSourceBinding(source, readText(input, 64), input.readLong(), input.readInt(), input.readInt())
        return SourceTaskReceipt(binding, readText(input, 64))
    }
    private fun writeReminder(out: DataOutputStream, reminder: TaskReminderSnapshot?) {
        out.writeBoolean(reminder != null)
        reminder?.let {
            writeText(out, it.id); out.writeLong(it.scheduleVersion); writeText(out, it.mode.name)
            writeSchedule(out, it.schedule); writeText(out, it.phase.name)
            out.writeInt(it.commands.size)
            for (command in it.commands) {
                writeText(out, command.token.taskId); writeText(out, command.token.reminderId); out.writeLong(command.token.scheduleVersion)
                writeText(out, command.kind.name); writeSchedule(out, command.schedule); writeText(out, command.mode.name)
            }
        }
    }
    private fun readReminder(input: DataInputStream): TaskReminderSnapshot? {
        if (!flag(input)) return null
        val id = readText(input, 64); val version = input.readLong(); val mode = TaskReminderMode.valueOf(readText(input, 16))
        val schedule = requireNotNull(readSchedule(input)); val phase = TaskReminderPhase.valueOf(readText(input, 32))
        val count = input.readInt(); require(count in 0..TaskReminders.MAX_COMMANDS)
        val commands = List(count) {
            val token = TaskReminderToken(readText(input, 512), readText(input, 64), input.readLong())
            val kind = TaskReminderCommandKind.valueOf(readText(input, 8))
            TaskReminderCommand(token, kind, requireNotNull(readSchedule(input)), TaskReminderMode.valueOf(readText(input, 16)))
        }
        return TaskReminderSnapshot(id, version, mode, schedule, phase, commands)
    }
    private fun flag(input: DataInputStream): Boolean = input.readUnsignedByte().also { require(it in 0..1) } == 1
    private fun writeText(out: DataOutputStream, text: String) {
        val raw = text.toByteArray(Charsets.UTF_8)
        try { require(raw.size in 1..960); out.writeInt(raw.size); out.write(raw) } finally { raw.fill(0) }
    }
    private fun readText(input: DataInputStream, limit: Int): String {
        val size = input.readInt(); require(size in 1..limit && size <= input.available())
        val raw = ByteArray(size); input.readFully(raw)
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() }
        finally { raw.fill(0) }
    }
    private fun validateOperation(task: ManualTaskSnapshot, operation: String, format: Int, proof: String?) {
        require(operation.length <= 8)
        val reminder = task.reminder
        reminder?.let {
            TaskReminders.validate(task.id, it)
            if (it.phase != TaskReminderPhase.REVOKED) require(task.status == ManualTaskStatus.OPEN && task.schedule == it.schedule)
        }
        require((operation == "REMIND") == (proof != null))
        proof?.let { require(it.matches(Regex("[a-f0-9]{64}"))) }
        if (format < 4) require(reminder == null && proof == null && operation !in REMINDER_OPERATIONS)
        when (operation) {
            "IMPORT" -> require(task.version == 1L && reminder == null)
            "CREATE" -> require(task.version == 1L && task.status == ManualTaskStatus.OPEN && reminder == null)
            "STATUS" -> require(task.version > 1 && task.status != ManualTaskStatus.OPEN &&
                (reminder == null || reminder.phase == TaskReminderPhase.REVOKED))
            "SET", "POSTPONE" -> require(task.version > 1 && task.status == ManualTaskStatus.OPEN && task.schedule != null &&
                (reminder == null || reminder.phase == TaskReminderPhase.REVOKED))
            "CLEAR" -> require(task.version > 1 && task.status == ManualTaskStatus.OPEN && task.schedule == null &&
                (reminder == null || reminder.phase == TaskReminderPhase.REVOKED))
            "RENAME" -> require(task.version > 1 && task.status == ManualTaskStatus.OPEN)
            "REMIND" -> require(format == 4 && task.version > 1 && task.status == ManualTaskStatus.OPEN &&
                (reminder == null || reminder.phase in setOf(TaskReminderPhase.PENDING, TaskReminderPhase.REVOKED)))
            "RM_ACK" -> require(format == 4 && task.version > 1 && reminder != null)
            "RM_FIRE" -> require(format == 4 && task.version > 1 && task.status == ManualTaskStatus.OPEN &&
                reminder?.phase == TaskReminderPhase.TRIGGER_ACCEPTED)
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
    internal class Payload(val task: ManualTaskSnapshot, val requestedAtMillis: Long, val operation: String?,
        val reminderRequestProof: String? = null)
    /** Records owner only, after the shared SQL gateway is sealed and drained. */
    fun wipe() = key.fill(0)
    private companion object {
        const val MAX_LEGACY_BYTES = 4096
        const val MAX_V4_BYTES = 65536
        val REMINDER_OPERATIONS = setOf("REMIND", "RM_ACK", "RM_FIRE")
    }
}
