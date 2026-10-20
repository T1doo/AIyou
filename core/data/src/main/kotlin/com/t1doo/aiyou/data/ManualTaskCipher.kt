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
    internal data class UserReceipt(val taskId: String, val operation: String, val mutationId: String,
        val expectedVersion: Long, val requestedAtMillis: Long, val requestHash: String, val decisionHash: String) {
        init {
            ManualTags.identifier(taskId); ManualTags.identifier(mutationId)
            require(operation in USER_OPERATIONS && expectedVersion in 0 until Long.MAX_VALUE && requestedAtMillis >= 0)
            require((operation == "CREATE") == (expectedVersion == 0L))
            require(listOf(requestHash, decisionHash).all { it.matches(Regex("[a-f0-9]{64}")) })
        }
        override fun toString() = "TaskUserReceipt(redacted)"
    }
    private val key: ByteArray
    private val random = SecureRandom()
    init {
        require(databaseKey.size == 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(databaseKey, "HmacSHA256"))
        key = mac.doFinal("com.t1doo.aiyou.manual-tasks.v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
    }
    fun seal(task: ManualTaskSnapshot, mutationId: String, requestedAtMillis: Long,
        operation: String = if (task.version == 1L) "CREATE" else "STATUS", reminderRequestProof: String? = null,
        userReceipt: UserReceipt? = null, runtime: List<ReminderRuntimeReceipt> = emptyList()): ByteArray {
        val format = if (runtime.isNotEmpty() || operation in RUNTIME_OPERATIONS || task.reminder?.phase in ReminderRuntimeReceipts.phases || task.reminder?.phase == TaskReminderPhase.TIME_PASSED) 6
            else if (userReceipt != null) 5 else if (task.reminder != null || operation in REMINDER_OPERATIONS) 4 else if (task.sourceReceipt == null) 2 else 3
        validateOperation(task, operation, format, reminderRequestProof)
        validateUserReceipt(task, mutationId, requestedAtMillis, operation, reminderRequestProof, userReceipt)
        ReminderRuntimeReceipts.validate(task, runtime)
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
                if (format >= 4) {
                    writeReminder(out, task.reminder)
                    out.writeBoolean(reminderRequestProof != null)
                    reminderRequestProof?.let { writeText(out, it) }
                }
                if (format == 6) out.writeBoolean(userReceipt != null)
                userReceipt?.let { r ->
                    writeText(out, r.taskId); writeText(out, r.operation); writeText(out, r.mutationId)
                    out.writeLong(r.expectedVersion); out.writeLong(r.requestedAtMillis)
                    writeText(out, r.requestHash); writeText(out, r.decisionHash)
                }
                if (format == 6) ReminderRuntimeCipherFields.write(out, runtime)
            }
            bytes.toByteArray()
        }
        val limit = if (format >= 4) MAX_EXTENDED_BYTES else MAX_LEGACY_BYTES
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
        val format = bytes[0].toInt(); require(format in 1..6)
        require(bytes.size <= if (format >= 4) MAX_EXTENDED_BYTES else MAX_LEGACY_BYTES)
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
                if (format >= 4) {
                    task = task.copy(reminder = readReminder(input))
                    if (flag(input)) proof = readText(input, 64)
                }
                val user = if (format == 5 || format == 6 && flag(input)) UserReceipt(readText(input, 512), readText(input, 8), readText(input, 512),
                    input.readLong(), input.readLong(), readText(input, 64), readText(input, 64)) else null
                val runtime = if (format == 6) ReminderRuntimeCipherFields.read(input) else emptyList()
                // Validate only after all authenticated fields, including reminder/outbox, have been decoded.
                if (operation != null) validateOperation(task, operation, format, proof)
                validateUserReceipt(task, mutationId, requested, operation, proof, user)
                ReminderRuntimeReceipts.validate(task, runtime)
                require(requested in 0..task.updatedAtMillis && input.available() == 0)
                Payload(task, requested, operation, proof, user, runtime)
            }
        } finally { plain.fill(0) }
    }
    internal fun portable(id: String, version: Long, mutation: String, bytes: ByteArray, restoring: Boolean): ByteArray {
        require(bytes.isNotEmpty() && bytes[0].toInt() in 1..6)
        if (!restoring) open(id,version,mutation,bytes)
        if (!restoring) return PortableCipherCell.open(key,aad(id,version,mutation,bytes[0].toInt()),bytes,MAX_EXTENDED_BYTES)
        val encrypted=PortableCipherCell.seal(key,aad(id,version,mutation,bytes[0].toInt()),bytes,MAX_EXTENDED_BYTES)
        return try {
            val p=open(id,version,mutation,encrypted)
            val task=p.task.copy(reminder=p.task.reminder?.copy(phase=TaskReminderPhase.REVOKED,commands=emptyList()))
            seal(task,mutation,p.requestedAtMillis,"RESTORE")
        } finally { encrypted.fill(0) }
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
    fun requestProof(r: ManualTaskCreate, source: SourceTaskReceipt? = null) = requestHash("CREATE", r.id, r.mutationId, 0, r.createdAtMillis) {
        writeText(it, r.title); writeText(it, r.creationTimeZone); writeSchedule(it, r.schedule)
        it.writeBoolean(source != null); source?.let { source -> writeReceipt(it, source) }
    }
    fun requestProof(r: ManualTaskMutation) = requestHash("STATUS", r.id, r.mutationId, r.expectedVersion, r.requestedAtMillis) { writeText(it, r.status.name) }
    fun requestProof(r: ManualTaskRename) = requestHash("RENAME", r.id, r.mutationId, r.expectedVersion, r.requestedAtMillis) { writeText(it, r.title) }
    fun requestProof(r: ManualTaskScheduleMutation) = requestHash(r.operation.name, r.id, r.mutationId, r.expectedVersion, r.requestedAtMillis) { writeSchedule(it, r.schedule) }
    fun requestProof(r: TaskReminderMutation) = reminderUserProof(r.task.id, r.mutationId, r.task.version, r.requestedAtMillis, reminderRequestProof(r))
    fun reminderUserProof(id: String, mutation: String, expected: Long, at: Long, proof: String) =
        requestHash("REMIND", id, mutation, expected, at) { writeText(it, proof) }
    private fun requestHash(op: String, id: String, mutation: String, expected: Long, at: Long, extra: (DataOutputStream) -> Unit) = digest { out ->
        writeText(out, "AIyou/task-user-request/1"); writeText(out, id); writeText(out, op); writeText(out, mutation)
        out.writeLong(expected); out.writeLong(at); extra(out)
    }
    fun receipt(task: ManualTaskSnapshot, op: String, mutation: String, expected: Long, at: Long, hash: String) =
        UserReceipt(task.id, op, mutation, expected, at, hash, decisionHash(task))
    /** System phase/outbox/version/time progress is not another user decision. */
    private fun decisionHash(t: ManualTaskSnapshot) = digest { out ->
        writeText(out, "AIyou/task-user-decision/1"); writeText(out, t.id); writeText(out, t.title); writeText(out, t.status.name)
        out.writeLong(t.createdAtMillis); writeText(out, t.creationTimeZone); writeSchedule(out, t.schedule)
        out.writeBoolean(t.sourceReceipt != null); t.sourceReceipt?.let { writeReceipt(out, it) }
        out.writeBoolean(t.reminder != null); t.reminder?.let { r ->
            writeText(out, r.id); writeText(out, r.mode.name); writeSchedule(out, r.schedule)
            out.writeBoolean(r.phase == TaskReminderPhase.REVOKED)
        }
    }
    private fun digest(write: (DataOutputStream) -> Unit): String {
        val raw = ByteArrayOutputStream().use { b -> DataOutputStream(b).use(write); b.toByteArray() }
        return try { MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) } }
        finally { raw.fill(0) }
    }
    private fun validateUserReceipt(t: ManualTaskSnapshot, mutation: String, at: Long, op: String?, proof: String?, r: UserReceipt?) {
        if (r == null) return
        require(r.taskId == t.id && r.expectedVersion + 1 <= t.version && r.requestedAtMillis <= t.updatedAtMillis && r.decisionHash == decisionHash(t))
        val reconstructed = when (r.operation) {
            "CREATE" -> {
                require(t.createdAtMillis == r.requestedAtMillis)
                requestProof(ManualTaskCreate(t.id,t.title,t.createdAtMillis,t.creationTimeZone,r.mutationId,t.schedule),t.sourceReceipt)
            }
            "STATUS" -> requestProof(ManualTaskMutation(t.id,r.expectedVersion,t.status,r.requestedAtMillis,r.mutationId))
            "RENAME" -> requestProof(ManualTaskRename(t.id,r.expectedVersion,t.title,r.requestedAtMillis,r.mutationId))
            "SET", "POSTPONE", "CLEAR" -> requestProof(ManualTaskScheduleMutation(t.id,r.expectedVersion,ManualTaskDateOperation.valueOf(r.operation),t.schedule,r.requestedAtMillis,r.mutationId))
            else -> null // REMIND binds its complete old snapshot via the authenticated opaque request digest.
        }
        reconstructed?.let { require(r.requestHash == it) }
        if (op in USER_OPERATIONS) {
            require(r.operation == op && r.mutationId == mutation && r.requestedAtMillis == at && r.expectedVersion + 1 == t.version)
            if (op == "REMIND") require(r.requestHash == reminderUserProof(t.id, mutation, r.expectedVersion, at, checkNotNull(proof)))
        } else require((op in setOf("RM_ACK", "RM_FIRE") || op in RUNTIME_OPERATIONS) && r.expectedVersion + 1 < t.version)
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
        if (format < 6) require(operation !in RUNTIME_OPERATIONS && reminder?.phase !in ReminderRuntimeReceipts.phases && reminder?.phase != TaskReminderPhase.TIME_PASSED &&
            reminder?.commands?.none { it.kind == TaskReminderCommandKind.DELIVER } != false)
        when (operation) {
            "RESTORE" -> require(reminder == null || reminder.phase == TaskReminderPhase.REVOKED && reminder.commands.isEmpty())
            "IMPORT" -> require(task.version == 1L && reminder == null)
            "CREATE" -> require(task.version == 1L && task.status == ManualTaskStatus.OPEN && reminder == null)
            "STATUS" -> require(task.version > 1 && task.status != ManualTaskStatus.OPEN &&
                (reminder == null || reminder.phase == TaskReminderPhase.REVOKED))
            "SET", "POSTPONE" -> require(task.version > 1 && task.status == ManualTaskStatus.OPEN && task.schedule != null &&
                (reminder == null || reminder.phase == TaskReminderPhase.REVOKED))
            "CLEAR" -> require(task.version > 1 && task.status == ManualTaskStatus.OPEN && task.schedule == null &&
                (reminder == null || reminder.phase == TaskReminderPhase.REVOKED))
            "RENAME" -> require(task.version > 1 && task.status == ManualTaskStatus.OPEN)
            "REMIND" -> require(format >= 4 && task.version > 1 && task.status == ManualTaskStatus.OPEN &&
                (reminder == null || reminder.phase in setOf(TaskReminderPhase.PENDING, TaskReminderPhase.REVOKED)))
            "RM_ACK" -> require(format >= 4 && task.version > 1 && reminder != null)
            "RM_FIRE" -> require(format >= 4 && task.version > 1 && task.status == ManualTaskStatus.OPEN &&
                reminder?.phase == TaskReminderPhase.TRIGGER_ACCEPTED)
            "RM_EVENT", "RM_CLAIM", "RM_POST", "RM_END", "RM_GC" -> require(format == 6 && task.version > 1 && reminder != null)
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
        val reminderRequestProof: String? = null, val userReceipt: UserReceipt? = null, runtime: List<ReminderRuntimeReceipt> = emptyList()) {
        val runtime: List<ReminderRuntimeReceipt> = java.util.Collections.unmodifiableList(runtime.toList())
    }
    /** Records owner only, after the shared SQL gateway is sealed and drained. */
    fun wipe() = key.fill(0)
    private companion object {
        val USER_OPERATIONS = setOf("CREATE", "STATUS", "RENAME", "SET", "POSTPONE", "CLEAR", "REMIND")
        const val MAX_LEGACY_BYTES = 4096
        const val MAX_EXTENDED_BYTES = 65536
        val REMINDER_OPERATIONS = setOf("REMIND", "RM_ACK", "RM_FIRE")
        val RUNTIME_OPERATIONS = setOf("RM_EVENT", "RM_CLAIM", "RM_POST", "RM_END", "RM_GC")
    }
}
