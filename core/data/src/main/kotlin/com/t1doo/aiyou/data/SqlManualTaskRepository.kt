package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException

/** CAS, authenticated readback and stable latest-operation replay share one real transaction. */
internal class SqlManualTaskRepository(private val database: RecordsSqlDatabase, key: ByteArray) : ManualTaskRepository, ReminderRuntimeRepository {
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
    internal fun reminderRuntimeFromCells(cells:List<Any?>):ReminderRuntimeView = decode(cells).payload.let{ReminderRuntimeView(it.task,it.runtime)}
    /** Rebuild only an existing confirmed future plan, never a trigger or a new user decision. */
    internal fun rebuildFutureWithin(sql:RecordsSqlConnection,id:String,now:Long):Boolean {
        val old=row(sql,id)?:return false;val t=old.payload.task;val r=t.reminder?:return false
        if(t.status!=ManualTaskStatus.OPEN || t.schedule!=r.schedule || requireNotNull(r.schedule.instant()).toEpochMilli()<=now ||
            old.payload.runtime.any{it.token.scheduleVersion==r.scheduleVersion} || r.phase !in setOf(
                TaskReminderPhase.PENDING,TaskReminderPhase.SCHEDULED,TaskReminderPhase.PLATFORM_UNAVAILABLE,
                TaskReminderPhase.NOTIFICATION_REQUIRED,TaskReminderPhase.NOTIFICATION_DENIED,TaskReminderPhase.CHANNEL_DISABLED,
                TaskReminderPhase.EXACT_REQUIRED,TaskReminderPhase.EXACT_DENIED,TaskReminderPhase.FAILED)) return false
        if(r.phase==TaskReminderPhase.PENDING) return false
        val command=TaskReminderCommand(TaskReminderToken(t.id,r.id,r.scheduleVersion),TaskReminderCommandKind.SCHEDULE,r.schedule,r.mode)
        val next=t.copy(version=Math.addExact(t.version,1),updatedAtMillis=maxOf(t.updatedAtMillis,now),
            reminder=r.copy(phase=TaskReminderPhase.PENDING,commands=r.commands+command))
        writeWithin(sql,t,next,"rebuild:"+UUID.randomUUID(),now,"RM_REBLD",userReceipt=receiptForSystem(old))
        return true
    }
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
                writeWithin(sql, current, next, request.mutationId, request.requestedAtMillis, "STATUS",
                    userReceipt = cipher.receipt(next, "STATUS", request.mutationId, request.expectedVersion, request.requestedAtMillis, hash))
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
                writeWithin(sql, current, next, request.mutationId, request.requestedAtMillis, request.operation.name,
                    userReceipt = cipher.receipt(next, request.operation.name, request.mutationId, request.expectedVersion, request.requestedAtMillis, hash))
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
                if (request.mode != null && old.payload.runtime.size >= ReminderRuntimeReceipts.MAX_SLOTS) throw ReminderRuntimeLimit()
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
                TaskReminderCommandKind.DELIVER -> return@transaction false
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
    override fun runtimeView(taskId: String): ReminderRuntimeView? = safely {
        ManualTags.identifier(taskId)
        database.transaction { sql -> row(sql, taskId)?.let(::runtimeView) }
    }
    private fun runtimeView(row: Row) = ReminderRuntimeView(row.payload.task, row.payload.runtime)
    private fun runtimeReply(outcome: ReminderRuntimeOutcome, row: Row?) = ReminderRuntimeReply(outcome, row?.let(::runtimeView))
    private fun runtimeCurrent(t: ManualTaskSnapshot, scope: ReminderRuntimeScope): Boolean = t.status == ManualTaskStatus.OPEN &&
        t.id == scope.token.taskId && t.reminder?.let { it.id == scope.token.reminderId && it.scheduleVersion == scope.token.scheduleVersion &&
            it.schedule == scope.schedule && it.mode == scope.mode && t.schedule == scope.schedule } == true
    private fun runtimeCas(row: Row, expected: Long) { if (row.payload.task.version != expected) throw ReminderRuntimeConflict() }
    private fun runtimeWrite(sql: RecordsSqlConnection, old: Row, phase: TaskReminderPhase, commands: List<TaskReminderCommand>,
        ledger: List<ReminderRuntimeReceipt>, mutation: String, at: Long, operation: String): Row {
        val current = old.payload.task
        val next = current.copy(version = Math.addExact(current.version, 1), updatedAtMillis = maxOf(current.updatedAtMillis, at),
            reminder = requireNotNull(current.reminder).copy(phase = phase, commands = commands))
        writeWithin(sql, current, next, mutation, at, operation, userReceipt = receiptForSystem(old), runtime = ledger)
        return requireNotNull(row(sql, current.id))
    }
    override fun acceptRuntimeEvent(request: ReminderEventRequest): ReminderRuntimeReply = safely {
        database.transaction { sql ->
            val old = row(sql, request.scope.token.taskId) ?: return@transaction runtimeReply(ReminderRuntimeOutcome.MISSING, null)
            val existing = old.payload.runtime.singleOrNull { it.event.id == request.id }
            if (existing != null) {
                if (existing.event != request) throw ReminderRuntimeConflict()
                return@transaction runtimeReply(if (existing.retired != null) ReminderRuntimeOutcome.RETIRED else ReminderRuntimeOutcome.EXACT_REPLAY, old)
            }
            if (old.payload.runtime.any { it.token == request.scope.token }) return@transaction runtimeReply(ReminderRuntimeOutcome.ALREADY_OBSERVED, old)
            val t = old.payload.task; val r = t.reminder
            if (!runtimeCurrent(t, request.scope) || r == null || r.phase !in setOf(TaskReminderPhase.PENDING, TaskReminderPhase.SCHEDULED))
                return@transaction runtimeReply(ReminderRuntimeOutcome.STALE, old)
            val schedule = TaskReminderCommand(request.scope.token, TaskReminderCommandKind.SCHEDULE, request.scope.schedule, request.scope.mode)
            if (r.phase == TaskReminderPhase.PENDING && !TaskReminders.commandCurrent(t, schedule)) return@transaction runtimeReply(ReminderRuntimeOutcome.STALE, old)
            runtimeCas(old, request.expectedVersion)
            if (request.observedAtMillis < requireNotNull(request.scope.schedule.instant()).toEpochMilli()) return@transaction runtimeReply(ReminderRuntimeOutcome.TOO_EARLY, old)
            if (old.payload.runtime.size >= ReminderRuntimeReceipts.MAX_SLOTS) throw ReminderRuntimeLimit()
            val blocked = request.capability.availability.blocked(request.scope.mode) != null
            val receipt = ReminderRuntimeReceipt(request, if (blocked) ReminderEventDecision.TRIGGER_BLOCKED else ReminderEventDecision.ACCEPTED,
                closed = if (blocked) ReminderRuntimeClosure(request.observedAtMillis, ReminderClosureReason.CAPABILITY_UNAVAILABLE_AT_TRIGGER) else null)
            val commands = r.commands.filterNot { it == schedule } + if (blocked) emptyList() else listOf(schedule.copy(kind = TaskReminderCommandKind.DELIVER))
            val saved = runtimeWrite(sql, old, if (blocked) TaskReminderPhase.TRIGGER_BLOCKED else TaskReminderPhase.TRIGGER_ACCEPTED,
                commands, old.payload.runtime + receipt, request.id, request.observedAtMillis, "RM_EVENT")
            runtimeReply(if (blocked) ReminderRuntimeOutcome.BLOCKED else ReminderRuntimeOutcome.ACCEPTED, saved)
        }
    }
    override fun decideRuntimeClaim(request: ReminderClaimRequest): ReminderRuntimeReply = safely {
        database.transaction { sql ->
            val old = row(sql, request.event.scope.token.taskId) ?: return@transaction runtimeReply(ReminderRuntimeOutcome.MISSING, null)
            val slot = old.payload.runtime.singleOrNull { it.event.id == request.event.id } ?: return@transaction runtimeReply(ReminderRuntimeOutcome.STALE, old)
            if (slot.event != request.event) throw ReminderRuntimeConflict()
            slot.claim?.let {
                if (it == request) return@transaction runtimeReply(if (slot.retired != null) ReminderRuntimeOutcome.RETIRED else ReminderRuntimeOutcome.EXACT_REPLAY, old)
                if (slot.closed != null) return@transaction runtimeReply(ReminderRuntimeOutcome.RETIRED, old)
                throw ReminderRuntimeConflict()
            }
            if (slot.closed != null || slot.retired != null) return@transaction runtimeReply(ReminderRuntimeOutcome.RETIRED, old)
            val t = old.payload.task; val r = requireNotNull(t.reminder)
            val deliver = TaskReminderCommand(slot.token, TaskReminderCommandKind.DELIVER, slot.event.scope.schedule, slot.event.scope.mode)
            if (!runtimeCurrent(t, request.event.scope) || r.phase != TaskReminderPhase.TRIGGER_ACCEPTED || !TaskReminders.commandCurrent(t, deliver))
                return@transaction runtimeReply(ReminderRuntimeOutcome.STALE, old)
            runtimeCas(old, request.expectedVersion)
            val blocked = request.capability.availability.blocked(request.event.scope.mode) != null
            val nextSlot = slot.copy(claim = request, claimDecision = if (blocked) ReminderClaimDecision.BLOCKED_CAPABILITY else ReminderClaimDecision.GRANTED,
                closed = if (blocked) ReminderRuntimeClosure(request.claimAtMillis, ReminderClosureReason.CAPABILITY_UNAVAILABLE_BEFORE_POST) else null)
            val saved = runtimeWrite(sql, old, if (blocked) TaskReminderPhase.DELIVERY_BLOCKED else TaskReminderPhase.POSTING,
                r.commands.filterNot { it == deliver }, old.payload.runtime.map { if (it == slot) nextSlot else it },
                request.id, request.claimAtMillis, "RM_CLAIM")
            runtimeReply(if (blocked) ReminderRuntimeOutcome.BLOCKED else ReminderRuntimeOutcome.ACCEPTED, saved)
        }
    }
    override fun recordRuntimeResult(request: ReminderResultRequest, authority: ReminderResultAuthority): ReminderRuntimeReply = safely {
        database.transaction { sql ->
            val old = row(sql, request.claim.event.scope.token.taskId) ?: return@transaction runtimeReply(ReminderRuntimeOutcome.MISSING, null)
            val slot = old.payload.runtime.singleOrNull { it.event.id == request.claim.event.id } ?: return@transaction runtimeReply(ReminderRuntimeOutcome.STALE, old)
            if (slot.claim != request.claim) throw ReminderRuntimeConflict()
            slot.result?.let {
                if (it != request) throw ReminderRuntimeConflict()
                return@transaction runtimeReply(if (slot.retired != null) ReminderRuntimeOutcome.RETIRED else ReminderRuntimeOutcome.EXACT_REPLAY, old)
            }
            if (slot.closed != null || slot.retired != null) return@transaction runtimeReply(ReminderRuntimeOutcome.RETIRED, old)
            val t = old.payload.task; val r = requireNotNull(t.reminder)
            if (!authority.authorizes(request) || !runtimeCurrent(t, request.claim.event.scope) || r.phase != TaskReminderPhase.POSTING)
                return@transaction runtimeReply(ReminderRuntimeOutcome.STALE, old)
            runtimeCas(old, request.expectedVersion)
            val (phase, cause) = when (request.observation) {
                ReminderPostObservation.POST_RETURNED -> TaskReminderPhase.NOTIFICATION_POSTED to ReminderClosureReason.OPERATION_FINISHED
                ReminderPostObservation.CALL_FAILED -> TaskReminderPhase.POST_FAILED to ReminderClosureReason.OPERATION_FINISHED
                ReminderPostObservation.CALL_OUTCOME_UNKNOWN -> TaskReminderPhase.POST_UNKNOWN to ReminderClosureReason.OUTCOME_UNCONFIRMED
                ReminderPostObservation.NO_ATTEMPT_CAPABILITY_LOST -> TaskReminderPhase.DELIVERY_BLOCKED to ReminderClosureReason.CAPABILITY_LOST_AFTER_CLAIM
            }
            val nextSlot = slot.copy(result = request, closed = ReminderRuntimeClosure(request.observedAtMillis, cause))
            val saved = runtimeWrite(sql, old, phase, r.commands, old.payload.runtime.map { if (it == slot) nextSlot else it },
                request.id, request.observedAtMillis, "RM_POST")
            runtimeReply(ReminderRuntimeOutcome.ACCEPTED, saved)
        }
    }
    override fun endRuntime(token: TaskReminderToken, expectedVersion: Long, atMillis: Long, reason: ReminderClosureReason): Boolean = safely {
        require(atMillis >= 0 && reason in setOf(ReminderClosureReason.OWNER_RETIRED, ReminderClosureReason.OUTCOME_UNCONFIRMED))
        database.transaction { sql ->
            val old = row(sql, token.taskId) ?: return@transaction false
            val slot = old.payload.runtime.singleOrNull { it.token == token } ?: return@transaction false
            if (slot.closed != null || slot.retired != null) return@transaction false
            require(slot.claim != null || reason == ReminderClosureReason.OWNER_RETIRED)
            val t = old.payload.task; val r = requireNotNull(t.reminder)
            if (r.scheduleVersion != token.scheduleVersion) return@transaction false
            runtimeCas(old, expectedVersion)
            val phase = if (slot.claim == null) TaskReminderPhase.TRIGGER_UNDELIVERED else TaskReminderPhase.POST_UNKNOWN
            val closure = if (slot.claim == null) reason else ReminderClosureReason.OUTCOME_UNCONFIRMED
            val nextSlot = slot.copy(closed = ReminderRuntimeClosure(atMillis, closure))
            runtimeWrite(sql, old, phase, r.commands.filterNot { it.token == token && it.kind == TaskReminderCommandKind.DELIVER },
                old.payload.runtime.map { if (it == slot) nextSlot else it }, "runtime-end:" + UUID.randomUUID(), atMillis, "RM_END")
            true
        }
    }
    override fun markKnownLostPast(token: TaskReminderToken, expectedVersion: Long, atMillis: Long): Boolean = safely {
        require(atMillis >= 0)
        database.transaction { sql ->
            val old = row(sql, token.taskId) ?: return@transaction false
            val t = old.payload.task; val r = t.reminder ?: return@transaction false
            if (t.status != ManualTaskStatus.OPEN || r.id != token.reminderId || r.scheduleVersion != token.scheduleVersion ||
                r.phase !in setOf(TaskReminderPhase.PENDING, TaskReminderPhase.SCHEDULED) || requireNotNull(r.schedule.instant()).toEpochMilli() > atMillis ||
                old.payload.runtime.any { it.token == token }) return@transaction false
            runtimeCas(old, expectedVersion)
            runtimeWrite(sql, old, TaskReminderPhase.TIME_PASSED, r.commands.filterNot { it.kind == TaskReminderCommandKind.SCHEDULE },
                old.payload.runtime, "runtime-past:" + UUID.randomUUID(), atMillis, "RM_END")
            true
        }
    }
    override fun collectRetiredRuntime(taskId: String, expectedVersion: Long, atMillis: Long): Int = safely {
        require(atMillis >= 0)
        database.transaction { sql ->
            val old = row(sql, taskId) ?: return@transaction 0
            runtimeCas(old, expectedVersion)
            val r = old.payload.task.reminder ?: return@transaction 0
            val remaining = old.payload.runtime.filterNot { s -> s.retired != null && s.closed != null && r.commands.none { it.token == s.token } }
            val count = old.payload.runtime.size - remaining.size
            if (count > 0) runtimeWrite(sql, old, r.phase, r.commands, remaining, "runtime-gc:" + UUID.randomUUID(), atMillis, "RM_GC")
            count
        }
    }
    private fun writeWithin(sql: RecordsSqlConnection, current: ManualTaskSnapshot, next: ManualTaskSnapshot,
        mutation: String, requestedAtMillis: Long, operation: String, proof: String? = null,
        userReceipt: ManualTaskCipher.UserReceipt? = null, runtime: List<ReminderRuntimeReceipt>? = null): ManualTaskSnapshot {
        val prior = row(sql, current.id) ?: throw ManualTaskConflict()
        if (prior.payload.task != current) throw ManualTaskConflict()
        val ledger = runtime ?: prior.payload.runtime.map { receipt ->
            if (receipt.token.scheduleVersion < requireNotNull(next.reminder).scheduleVersion) receipt.copy(
                closed = receipt.closed ?: ReminderRuntimeClosure(requestedAtMillis, ReminderClosureReason.USER_DECISION),
                retired = receipt.retired ?: ReminderRuntimeRetirement(requestedAtMillis)) else receipt
        }
        val bytes = cipher.seal(next, mutation, requestedAtMillis, operation, proof, userReceipt, ledger)
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
        catch (conflict: ReminderRuntimeConflict) { throw conflict }
        catch (limit: ReminderRuntimeLimit) { throw limit }
        catch (_: Exception) { throw ManualTaskStorageFailure() }
    fun wipeKey() = cipher.wipe()
    private companion object {
        val SCHEDULE_RESULTS = setOf(TaskReminderPhase.SCHEDULED, TaskReminderPhase.PLATFORM_UNAVAILABLE,
            TaskReminderPhase.NOTIFICATION_REQUIRED, TaskReminderPhase.NOTIFICATION_DENIED, TaskReminderPhase.CHANNEL_DISABLED,
            TaskReminderPhase.EXACT_REQUIRED, TaskReminderPhase.EXACT_DENIED, TaskReminderPhase.FAILED)
    }
}
