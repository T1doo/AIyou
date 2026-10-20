package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException

/** CAS, authenticated readback and stable latest-operation replay share one real transaction. */
internal class SqlManualTaskRepository(private val database: RecordsSqlDatabase, key: ByteArray) : ManualTaskRepository, TaskReminderRepository {
    private val cipher = ManualTaskCipher(key)
    override fun snapshots(): List<ManualTaskSnapshot> = safely {
        database.transaction { sql ->
            Collections.unmodifiableList(sql.query("SELECT id,version,lastMutationId,ciphertext FROM manual_tasks WHERE substr(id,1,14)!='TASK_PROPOSAL:' ORDER BY id")
                .map { decode(it).payload.task }.sortedWith(compareByDescending<ManualTaskSnapshot> { it.createdAtMillis }.thenBy { it.id }))
        }
    }
    override fun create(request: ManualTaskCreate): ManualTaskSnapshot = safely {
        if(request.id.startsWith(TaskProposals.PREFIX)||request.id.startsWith("TASK_FROM_PROPOSAL:")) throw ManualTaskConflict()
        database.transaction { sql -> createWithin(sql,request) }
    }
    internal fun readWithin(sql: RecordsSqlConnection,id: String): ManualTaskSnapshot? = row(sql,id)?.payload?.task
    internal fun exportWithin(sql:RecordsSqlConnection):List<ManualTaskSnapshot> =
        sql.query("SELECT id,version,lastMutationId,ciphertext FROM manual_tasks WHERE substr(id,1,14)!='TASK_PROPOSAL:' ORDER BY id LIMIT 257")
            .also {if(it.size>RecordPackageActivities.MAX_ITEMS) throw RecordPackageActivityLimit(RecordPackageActivityBound.FORMAL_TASKS)}
            .map {decode(it).payload.task}
    internal fun importWithin(sql:RecordsSqlConnection,t:ManualTaskSnapshot,mutation:String) {
        require(t.version==1L && t.reminder==null && row(sql,t.id)==null)
        val bytes=cipher.seal(t,mutation,t.updatedAtMillis,"IMPORT")
        sql.execute("INSERT INTO manual_tasks(id,version,lastMutationId,ciphertext) VALUES(?,?,?,?)",listOf(t.id,1,mutation,bytes))
        check(readWithin(sql,t.id)==t)
    }
    internal fun createWithin(sql: RecordsSqlConnection,request: ManualTaskCreate,receipt: SourceTaskReceipt? = null): ManualTaskSnapshot {
        val expected = ManualTaskSnapshot(request.id, 1, request.title, ManualTaskStatus.OPEN,
            request.createdAtMillis, request.creationTimeZone, request.createdAtMillis, request.schedule, receipt)
        val old = row(sql, request.id)
        val hash = cipher.requestProof(request, receipt)
        return if (old != null) {
            replay(old, "CREATE", request.mutationId, hash)?.let { return it }
            if (old.mutationId != request.mutationId || old.payload.task != expected ||
                old.payload.requestedAtMillis != request.createdAtMillis ||
                old.payload.operation != null && old.payload.operation != "CREATE") throw ManualTaskConflict()
            old.payload.task
        } else {
            val bytes = cipher.seal(expected, request.mutationId, request.createdAtMillis,
                userReceipt = cipher.receipt(expected, "CREATE", request.mutationId, 0, request.createdAtMillis, hash))
            sql.execute("INSERT INTO manual_tasks(id,version,lastMutationId,ciphertext) VALUES(?,?,?,?)",
                listOf(request.id, 1, request.mutationId, bytes))
            checkNotNull(row(sql, request.id)).payload.task.also { check(it == expected) }
        }
    }
    override fun change(request: ManualTaskMutation): ManualTaskSnapshot = safely {
        database.transaction { sql ->
            val old = row(sql, request.id) ?: throw ManualTaskConflict()
            val current = old.payload.task
            val hash = cipher.requestProof(request)
            replay(old, "STATUS", request.mutationId, hash)?.let { return@transaction it }
            if (old.mutationId == request.mutationId) {
                if (current.version != request.expectedVersion + 1 || current.status != request.status ||
                    old.payload.requestedAtMillis != request.requestedAtMillis ||
                    old.payload.operation != null && old.payload.operation != "STATUS") throw ManualTaskConflict()
                current
            } else {
                if (current.version != request.expectedVersion || current.status != ManualTaskStatus.OPEN) throw ManualTaskConflict()
                val next = current.copy(version = Math.addExact(current.version, 1), status = request.status,
                    updatedAtMillis = maxOf(current.updatedAtMillis, request.requestedAtMillis), reminder = TaskReminders.revoke(current))
                val bytes = cipher.seal(next, request.mutationId, request.requestedAtMillis,
                    userReceipt = cipher.receipt(next, "STATUS", request.mutationId, request.expectedVersion, request.requestedAtMillis, hash))
                if (sql.execute("UPDATE manual_tasks SET version=?,lastMutationId=?,ciphertext=? WHERE id=? AND version=?",
                    listOf(next.version, request.mutationId, bytes, request.id, request.expectedVersion)) != 1) throw ManualTaskConflict()
                checkNotNull(row(sql, request.id)).payload.task.also { check(it == next) }
            }
        }
    }
    override fun reschedule(request: ManualTaskScheduleMutation): ManualTaskSnapshot = safely {
        database.transaction { sql ->
            val old = row(sql, request.id) ?: throw ManualTaskConflict()
            val current = old.payload.task
            val hash = cipher.requestProof(request)
            replay(old, request.operation.name, request.mutationId, hash)?.let { return@transaction it }
            if (current.status != ManualTaskStatus.OPEN) throw ManualTaskConflict()
            if (old.mutationId == request.mutationId) {
                if (current.version != request.expectedVersion + 1 || current.schedule != request.schedule ||
                    old.payload.operation != request.operation.name || old.payload.requestedAtMillis != request.requestedAtMillis)
                    throw ManualTaskConflict()
                current
            } else {
                if (current.version != request.expectedVersion) throw ManualTaskConflict()
                ManualTaskDates.validate(request.operation, current.schedule, request.schedule)
                val next = current.copy(version = Math.addExact(current.version, 1), schedule = request.schedule,
                    updatedAtMillis = maxOf(current.updatedAtMillis, request.requestedAtMillis), reminder = TaskReminders.revoke(current))
                val bytes = cipher.seal(next, request.mutationId, request.requestedAtMillis, request.operation.name,
                    userReceipt = cipher.receipt(next, request.operation.name, request.mutationId, request.expectedVersion, request.requestedAtMillis, hash))
                if (sql.execute("UPDATE manual_tasks SET version=?,lastMutationId=?,ciphertext=? WHERE id=? AND version=?",
                    listOf(next.version, request.mutationId, bytes, request.id, request.expectedVersion)) != 1) throw ManualTaskConflict()
                checkNotNull(row(sql, request.id)).payload.task.also { check(it == next) }
            }
        }
    }
    override fun rename(request: ManualTaskRename): ManualTaskSnapshot = safely {
        database.transaction { sql ->
            val old = row(sql, request.id) ?: throw ManualTaskConflict()
            val current = old.payload.task
            val hash = cipher.requestProof(request)
            replay(old, "RENAME", request.mutationId, hash)?.let { return@transaction it }
            if (current.status != ManualTaskStatus.OPEN) throw ManualTaskConflict()
            if (old.mutationId == request.mutationId) {
                if (old.payload.operation != "RENAME" || current.version != request.expectedVersion + 1 ||
                    current.title != request.title || old.payload.requestedAtMillis != request.requestedAtMillis)
                    throw ManualTaskConflict()
                current
            } else {
                if (current.version != request.expectedVersion || current.title == request.title) throw ManualTaskConflict()
                val next = current.copy(version = Math.addExact(current.version, 1), title = request.title,
                    updatedAtMillis = maxOf(current.updatedAtMillis, request.requestedAtMillis))
                writeWithin(sql, current, next, request.mutationId, request.requestedAtMillis, "RENAME",
                    userReceipt = cipher.receipt(next, "RENAME", request.mutationId, request.expectedVersion, request.requestedAtMillis, hash))
            }
        }
    }
    override fun changeReminder(request: TaskReminderMutation): ManualTaskSnapshot = safely {
        database.transaction { sql ->
            val old = row(sql, request.task.id) ?: throw ManualTaskConflict()
            val current = old.payload.task
            if (request.task.status != ManualTaskStatus.OPEN) throw ManualTaskConflict()
            if (request.mode != null) {
                val due = request.task.schedule?.instant() ?: throw ManualTaskConflict()
                if (due.toEpochMilli() <= request.requestedAtMillis) throw ManualTaskConflict()
            }
            val proof = cipher.reminderRequestProof(request)
            val hash = cipher.requestProof(request)
            replay(old, "REMIND", request.mutationId, hash)?.let { return@transaction it }
            if (old.mutationId == request.mutationId) {
                // A lost COMMIT acknowledgement is replayed byte-for-byte only for the complete original request.
                if (old.payload.operation != "REMIND" || old.payload.reminderRequestProof != proof ||
                    old.payload.requestedAtMillis != request.requestedAtMillis || current != reminderNext(request))
                    throw ManualTaskConflict()
                current
            } else {
                if (current != request.task) throw ManualTaskConflict()
                val next = reminderNext(request)
                writeWithin(sql, current, next, request.mutationId, request.requestedAtMillis, "REMIND", proof,
                    cipher.receipt(next, "REMIND", request.mutationId, request.task.version, request.requestedAtMillis, hash))
            }
        }
    }
    private fun reminderNext(request: TaskReminderMutation): ManualTaskSnapshot = request.task.copy(
        version = Math.addExact(request.task.version, 1),
        updatedAtMillis = maxOf(request.task.updatedAtMillis, request.requestedAtMillis),
        reminder = request.mode?.let { TaskReminders.confirm(request.task, it) } ?: TaskReminders.revoke(request.task))

    override fun reminderCommands(): List<TaskReminderCommand> = safely {
        database.transaction { sql ->
            Collections.unmodifiableList(sql.query("SELECT id,version,lastMutationId,ciphertext FROM manual_tasks WHERE substr(id,1,14)!='TASK_PROPOSAL:' ORDER BY id")
                .flatMap { decode(it).payload.task.reminder?.commands.orEmpty() })
        }
    }
    override fun isReminderCommandCurrent(command: TaskReminderCommand): Boolean = safely {
        database.transaction { sql -> TaskReminders.commandCurrent(row(sql, command.token.taskId)?.payload?.task, command) }
    }
    override fun acknowledgeReminder(command: TaskReminderCommand, result: TaskReminderPhase, atMillis: Long): Boolean = safely {
        require(atMillis >= 0)
        database.transaction { sql ->
            val old = row(sql, command.token.taskId) ?: return@transaction false
            val current = old.payload.task
            val reminder = current.reminder ?: return@transaction false
            if (command !in reminder.commands) return@transaction false
            val isCurrent = TaskReminders.commandCurrent(current, command)
            val phase = when (command.kind) {
                TaskReminderCommandKind.CANCEL -> {
                    if (result != TaskReminderPhase.REVOKED) return@transaction false
                    reminder.phase
                }
                TaskReminderCommandKind.SCHEDULE -> {
                    // A driver may return after a later edit. Retire its exact old command without rearming the task.
                    if (!isCurrent) reminder.phase
                    else {
                        if (result !in SCHEDULE_RESULTS) return@transaction false
                        result
                    }
                }
            }
            val next = current.copy(version = Math.addExact(current.version, 1),
                updatedAtMillis = maxOf(current.updatedAtMillis, atMillis),
                reminder = reminder.copy(phase = phase, commands = reminder.commands.filterNot { it == command }))
            writeWithin(sql, current, next, "reminder-ack:" + UUID.randomUUID(), atMillis, "RM_ACK", userReceipt = receiptForSystem(old))
            true
        }
    }
    override fun acceptReminderTrigger(token: TaskReminderToken, nowMillis: Long, availability: TaskReminderAvailability): Boolean = safely {
        database.transaction { sql ->
            val old = row(sql, token.taskId) ?: return@transaction false
            val current = old.payload.task
            if (!TaskReminders.canAccept(current, token, nowMillis, availability)) return@transaction false
            val next = current.copy(version = Math.addExact(current.version, 1),
                updatedAtMillis = maxOf(current.updatedAtMillis, nowMillis),
                reminder = checkNotNull(current.reminder).copy(phase = TaskReminderPhase.TRIGGER_ACCEPTED))
            writeWithin(sql, current, next, "reminder-fire:" + UUID.randomUUID(), nowMillis, "RM_FIRE", userReceipt = receiptForSystem(old))
            true
        }
    }
    private fun writeWithin(sql: RecordsSqlConnection, current: ManualTaskSnapshot, next: ManualTaskSnapshot,
        mutation: String, requestedAtMillis: Long, operation: String, proof: String? = null,
        userReceipt: ManualTaskCipher.UserReceipt? = null): ManualTaskSnapshot {
        val bytes = cipher.seal(next, mutation, requestedAtMillis, operation, proof, userReceipt)
        if (sql.execute("UPDATE manual_tasks SET version=?,lastMutationId=?,ciphertext=? WHERE id=? AND version=?",
            listOf(next.version, mutation, bytes, current.id, current.version)) != 1) throw ManualTaskConflict()
        return checkNotNull(row(sql, current.id)).payload.task.also { check(it == next) }
    }
    private fun replay(row: Row, op: String, mutation: String, hash: String): ManualTaskSnapshot? {
        val r = row.payload.userReceipt ?: return null
        if (r.mutationId != mutation) return null
        if (r.operation != op || r.requestHash != hash) throw ManualTaskConflict()
        return row.payload.task
    }
    /** Upgrade only an unambiguous latest legacy user operation; never invent a lost historical receipt. */
    private fun receiptForSystem(row: Row): ManualTaskCipher.UserReceipt? {
        row.payload.userReceipt?.let { return it }
        val p = row.payload; val t = p.task; val op = p.operation ?: return null
        val expected = t.version - 1; val at = p.requestedAtMillis; val m = row.mutationId
        val hash = when (op) {
            "CREATE" -> cipher.requestProof(ManualTaskCreate(t.id, t.title, t.createdAtMillis, t.creationTimeZone, m, t.schedule), t.sourceReceipt)
            "STATUS" -> cipher.requestProof(ManualTaskMutation(t.id, expected, t.status, at, m))
            "RENAME" -> cipher.requestProof(ManualTaskRename(t.id, expected, t.title, at, m))
            "SET", "POSTPONE", "CLEAR" -> cipher.requestProof(ManualTaskScheduleMutation(t.id, expected, ManualTaskDateOperation.valueOf(op), t.schedule, at, m))
            "REMIND" -> cipher.reminderUserProof(t.id, m, expected, at, checkNotNull(p.reminderRequestProof))
            else -> return null
        }
        return cipher.receipt(t, op, m, expected, at, hash)
    }
    private fun row(sql: RecordsSqlConnection, id: String): Row? {
        if(id.startsWith(TaskProposals.PREFIX)) throw ManualTaskConflict()
        return sql.query(
        "SELECT id,version,lastMutationId,ciphertext FROM manual_tasks WHERE id=?", listOf(id)).singleOrNull()?.let(::decode)
    }
    private fun decode(cells: List<Any?>): Row {
        val id = cells[0] as String; val version = cells[1] as Long; val mutation = cells[2] as String
        ManualTags.identifier(mutation)
        return Row(mutation, cipher.open(id, version, mutation, cells[3] as ByteArray))
    }
    private class Row(val mutationId: String, val payload: ManualTaskCipher.Payload)
    private inline fun <T> safely(block: () -> T): T = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (conflict: ManualTaskConflict) { throw conflict }
        catch (invalid: ManualTaskDateInvalid) { throw invalid }
        catch (limit: TaskReminderLimit) { throw limit }
        catch (_: Exception) { throw ManualTaskStorageFailure() }
    fun wipeKey() = cipher.wipe()
    private companion object {
        val SCHEDULE_RESULTS = setOf(TaskReminderPhase.SCHEDULED, TaskReminderPhase.PLATFORM_UNAVAILABLE,
            TaskReminderPhase.NOTIFICATION_REQUIRED, TaskReminderPhase.NOTIFICATION_DENIED, TaskReminderPhase.CHANNEL_DISABLED,
            TaskReminderPhase.EXACT_REQUIRED, TaskReminderPhase.EXACT_DENIED, TaskReminderPhase.FAILED)
    }
}
