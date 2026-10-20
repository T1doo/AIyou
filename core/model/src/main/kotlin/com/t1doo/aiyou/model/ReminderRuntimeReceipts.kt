package com.t1doo.aiyou.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

class ReminderRuntimeConflict : IllegalStateException("Reminder runtime request conflict")
class ReminderRuntimeLimit : IllegalStateException("Reminder runtime state needs review")

data class ReminderRuntimeScope(val token: TaskReminderToken, val schedule: ManualTaskSchedule, val mode: TaskReminderMode) {
    init { require(token.reminderId == TaskReminders.id(token.taskId) && schedule.instant() != null) }
    override fun toString() = "ReminderRuntimeScope(redacted)"
}
data class ReminderCapabilityObservation(val checkedAtMillis: Long, val availability: TaskReminderAvailability) {
    init { require(checkedAtMillis >= 0) }
    override fun toString() = "ReminderCapabilityObservation(redacted)"
}

data class ReminderEventRequest(val id: String, val scope: ReminderRuntimeScope, val expectedVersion: Long,
    val observedAtMillis: Long, val capability: ReminderCapabilityObservation) {
    init { runtimeVersion(expectedVersion); require(observedAtMillis >= 0); RuntimeRequestIds.check(id, "e1") { eventBody(scope, expectedVersion, observedAtMillis, capability) } }
    val requestHash: String get() = id.substringAfterLast(':')
    override fun toString() = "ReminderEventRequest(redacted)"
    companion object {
        fun create(scope: ReminderRuntimeScope, expectedVersion: Long, observedAtMillis: Long, capability: ReminderCapabilityObservation,
            nonce: String = RuntimeRequestIds.nonce()): ReminderEventRequest = ReminderEventRequest(
            RuntimeRequestIds.make("e1", nonce) { eventBody(scope, expectedVersion, observedAtMillis, capability) }, scope, expectedVersion, observedAtMillis, capability)
    }
}
data class ReminderClaimRequest(val id: String, val event: ReminderEventRequest, val expectedVersion: Long,
    val claimAtMillis: Long, val capability: ReminderCapabilityObservation, val specification: String = "GENERIC_V1") {
    init { runtimeVersion(expectedVersion); require(claimAtMillis >= 0 && specification == "GENERIC_V1")
        RuntimeRequestIds.check(id, "c1") { claimBody(event, expectedVersion, claimAtMillis, capability, specification) } }
    val requestHash: String get() = id.substringAfterLast(':')
    override fun toString() = "ReminderClaimRequest(redacted)"
    companion object {
        fun create(event: ReminderEventRequest, expectedVersion: Long, atMillis: Long, capability: ReminderCapabilityObservation,
            nonce: String = RuntimeRequestIds.nonce()): ReminderClaimRequest = ReminderClaimRequest(
            RuntimeRequestIds.make("c1", nonce) { claimBody(event, expectedVersion, atMillis, capability, "GENERIC_V1") }, event, expectedVersion, atMillis, capability)
    }
}
enum class ReminderPostObservation { POST_RETURNED, CALL_FAILED, CALL_OUTCOME_UNKNOWN, NO_ATTEMPT_CAPABILITY_LOST }
enum class ReminderResultReason { CALL_RETURNED, CALL_FAILED, OUTCOME_UNCONFIRMED, CAPABILITY_UNAVAILABLE }
data class ReminderResultRequest(val id: String, val claim: ReminderClaimRequest, val expectedVersion: Long,
    val observedAtMillis: Long, val observation: ReminderPostObservation, val reason: ReminderResultReason,
    val capability: ReminderCapabilityObservation? = null) {
    init {
        runtimeVersion(expectedVersion); require(observedAtMillis >= 0)
        require((observation == ReminderPostObservation.NO_ATTEMPT_CAPABILITY_LOST) == (capability != null))
        if (capability != null) require(capability.availability.blocked(claim.event.scope.mode) != null)
        require(reason == when (observation) {
            ReminderPostObservation.POST_RETURNED -> ReminderResultReason.CALL_RETURNED
            ReminderPostObservation.CALL_FAILED -> ReminderResultReason.CALL_FAILED
            ReminderPostObservation.CALL_OUTCOME_UNKNOWN -> ReminderResultReason.OUTCOME_UNCONFIRMED
            ReminderPostObservation.NO_ATTEMPT_CAPABILITY_LOST -> ReminderResultReason.CAPABILITY_UNAVAILABLE
        })
        RuntimeRequestIds.check(id, "r1") { resultBody(claim, expectedVersion, observedAtMillis, observation, reason, capability) }
    }
    val requestHash: String get() = id.substringAfterLast(':')
    override fun toString() = "ReminderResultRequest(redacted)"
    companion object {
        fun create(claim: ReminderClaimRequest, expectedVersion: Long, atMillis: Long, observation: ReminderPostObservation,
            capability: ReminderCapabilityObservation? = null, nonce: String = RuntimeRequestIds.nonce()): ReminderResultRequest {
            val reason = when (observation) {
                ReminderPostObservation.POST_RETURNED -> ReminderResultReason.CALL_RETURNED
                ReminderPostObservation.CALL_FAILED -> ReminderResultReason.CALL_FAILED
                ReminderPostObservation.CALL_OUTCOME_UNKNOWN -> ReminderResultReason.OUTCOME_UNCONFIRMED
                ReminderPostObservation.NO_ATTEMPT_CAPABILITY_LOST -> ReminderResultReason.CAPABILITY_UNAVAILABLE
            }
            return ReminderResultRequest(RuntimeRequestIds.make("r1", nonce) { resultBody(claim, expectedVersion, atMillis, observation, reason, capability) },
                claim, expectedVersion, atMillis, observation, reason, capability)
        }
    }
}
enum class ReminderEventDecision { ACCEPTED, TRIGGER_BLOCKED }
enum class ReminderClaimDecision { GRANTED, BLOCKED_CAPABILITY }
enum class ReminderClosureReason { OPERATION_FINISHED, CAPABILITY_UNAVAILABLE_AT_TRIGGER, CAPABILITY_UNAVAILABLE_BEFORE_POST,
    CAPABILITY_LOST_AFTER_CLAIM, OWNER_RETIRED, OUTCOME_UNCONFIRMED, USER_DECISION }
enum class ReminderRetirementReason { USER_DECISION }
data class ReminderRuntimeClosure(val atMillis: Long, val reason: ReminderClosureReason) { init { require(atMillis >= 0) } }
data class ReminderRuntimeRetirement(val atMillis: Long, val reason: ReminderRetirementReason = ReminderRetirementReason.USER_DECISION) { init { require(atMillis >= 0) } }

data class ReminderRuntimeReceipt(val event: ReminderEventRequest, val eventDecision: ReminderEventDecision,
    val claim: ReminderClaimRequest? = null, val claimDecision: ReminderClaimDecision? = null,
    val result: ReminderResultRequest? = null, val closed: ReminderRuntimeClosure? = null,
    val retired: ReminderRuntimeRetirement? = null) {
    init {
        require((claim == null) == (claimDecision == null))
        require(eventDecision == if (event.capability.availability.blocked(event.scope.mode) == null) ReminderEventDecision.ACCEPTED else ReminderEventDecision.TRIGGER_BLOCKED)
        claim?.let { require(it.event == event && eventDecision == ReminderEventDecision.ACCEPTED && it.expectedVersion > event.expectedVersion)
            require(claimDecision == if (it.capability.availability.blocked(event.scope.mode) == null) ReminderClaimDecision.GRANTED else ReminderClaimDecision.BLOCKED_CAPABILITY) }
        result?.let { require(claim != null && it.claim == claim && claimDecision == ReminderClaimDecision.GRANTED && it.expectedVersion > claim.expectedVersion) }
        require(eventDecision != ReminderEventDecision.TRIGGER_BLOCKED || closed != null && claim == null)
        require(claimDecision != ReminderClaimDecision.BLOCKED_CAPABILITY || closed != null && result == null)
        require(result == null || closed != null)
        require(retired == null || closed != null)
        if (eventDecision == ReminderEventDecision.TRIGGER_BLOCKED) require(closed?.reason == ReminderClosureReason.CAPABILITY_UNAVAILABLE_AT_TRIGGER)
        if (claimDecision == ReminderClaimDecision.BLOCKED_CAPABILITY) require(closed?.reason == ReminderClosureReason.CAPABILITY_UNAVAILABLE_BEFORE_POST)
        result?.let { require(closed?.reason == when(it.observation) {
            ReminderPostObservation.POST_RETURNED, ReminderPostObservation.CALL_FAILED -> ReminderClosureReason.OPERATION_FINISHED
            ReminderPostObservation.CALL_OUTCOME_UNKNOWN -> ReminderClosureReason.OUTCOME_UNCONFIRMED
            ReminderPostObservation.NO_ATTEMPT_CAPABILITY_LOST -> ReminderClosureReason.CAPABILITY_LOST_AFTER_CLAIM
        }) }
        if (eventDecision == ReminderEventDecision.ACCEPTED && closed != null && result == null && claimDecision != ReminderClaimDecision.BLOCKED_CAPABILITY)
            require(closed.reason in if (claim == null) setOf(ReminderClosureReason.OWNER_RETIRED, ReminderClosureReason.USER_DECISION)
                else setOf(ReminderClosureReason.OUTCOME_UNCONFIRMED, ReminderClosureReason.USER_DECISION))
    }
    val token: TaskReminderToken get() = event.scope.token
    override fun toString() = "ReminderRuntimeReceipt(redacted)"
}

object ReminderRuntimeReceipts {
    const val MAX_SLOTS = 4
    const val MAX_SLOT_BYTES = 3072
    const val MAX_EVENT_BYTES = 1536
    const val MAX_BYTES = 16384
    val phases = setOf(TaskReminderPhase.TRIGGER_BLOCKED, TaskReminderPhase.DELIVERY_BLOCKED, TaskReminderPhase.POSTING,
        TaskReminderPhase.NOTIFICATION_POSTED, TaskReminderPhase.POST_FAILED, TaskReminderPhase.POST_UNKNOWN,
        TaskReminderPhase.TRIGGER_UNDELIVERED)
    fun validate(task: ManualTaskSnapshot, receipts: List<ReminderRuntimeReceipt>) {
        if (receipts.size > MAX_SLOTS) throw ReminderRuntimeLimit()
        require(receipts.map { it.token }.distinct().size == receipts.size)
        require(receipts.map { it.event.id }.distinct().size == receipts.size)
        val r = task.reminder
        receipts.forEach { s ->
            require(s.token.taskId == task.id && r != null && s.token.scheduleVersion <= r.scheduleVersion)
            require(s.event.expectedVersion < task.version)
            s.claim?.let { require(it.expectedVersion < task.version) }
            s.result?.let { require(it.expectedVersion < task.version) }
            if (s.token.scheduleVersion < r.scheduleVersion) require(s.retired != null)
            else {
                require(s.retired == null && s.event.scope.schedule == r.schedule && s.event.scope.mode == r.mode)
                when (r.phase) {
                    TaskReminderPhase.TRIGGER_ACCEPTED -> require(s.eventDecision == ReminderEventDecision.ACCEPTED && s.claim == null && s.closed == null && r.commands.any { it.kind == TaskReminderCommandKind.DELIVER })
                    TaskReminderPhase.TRIGGER_BLOCKED -> require(s.eventDecision == ReminderEventDecision.TRIGGER_BLOCKED)
                    TaskReminderPhase.DELIVERY_BLOCKED -> require(s.claimDecision == ReminderClaimDecision.BLOCKED_CAPABILITY || s.result?.observation == ReminderPostObservation.NO_ATTEMPT_CAPABILITY_LOST)
                    TaskReminderPhase.POSTING -> require(s.claimDecision == ReminderClaimDecision.GRANTED && s.result == null && s.closed == null)
                    TaskReminderPhase.NOTIFICATION_POSTED -> require(s.result?.observation == ReminderPostObservation.POST_RETURNED)
                    TaskReminderPhase.POST_FAILED -> require(s.result?.observation == ReminderPostObservation.CALL_FAILED)
                    TaskReminderPhase.POST_UNKNOWN -> require(s.result?.observation == ReminderPostObservation.CALL_OUTCOME_UNKNOWN || s.result == null && s.claimDecision == ReminderClaimDecision.GRANTED && s.closed != null)
                    TaskReminderPhase.TRIGGER_UNDELIVERED -> require(s.claim == null && s.closed != null)
                    else -> throw IllegalArgumentException("Runtime phase mismatch")
                }
            }
        }
        if (r != null && (r.phase in phases || r.commands.any { it.kind == TaskReminderCommandKind.DELIVER }))
            require(receipts.any { it.token.scheduleVersion == r.scheduleVersion })
    }
}

private fun runtimeVersion(v: Long) { require(v in 1 until Long.MAX_VALUE) }
private object RuntimeRequestIds {
    private val random = SecureRandom()
    fun nonce(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun make(kind: String, nonce: String, body: DataOutputStream.() -> Unit): String {
        require(nonce.matches(Regex("[a-f0-9]{32}")))
        val (name, op) = when (kind) { "e1" -> "event" to "ACCEPT"; "c1" -> "claim" to "DECIDE_POST"; "r1" -> "result" to "ACK_POST"; else -> error("Runtime kind") }
        val raw = ByteArrayOutputStream().use { b -> DataOutputStream(b).use { it.text("AIyou/task-runtime/$name/1"); it.writeInt(1); it.writeInt(1); it.text(op); it.text(nonce); it.body() }; b.toByteArray() }
        return try { "$kind:$nonce:" + MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) } } finally { raw.fill(0) }
    }
    fun check(id: String, kind: String, body: DataOutputStream.() -> Unit) {
        if (!id.matches(Regex("$kind:[a-f0-9]{32}:[a-f0-9]{64}")) || make(kind, id.substring(3,35), body) != id) throw ReminderRuntimeConflict()
    }
}
private fun DataOutputStream.text(s: String) { val b = s.toByteArray(Charsets.UTF_8); try { writeInt(b.size); write(b) } finally { b.fill(0) } }
private fun DataOutputStream.token(s: ReminderRuntimeScope) {
    text(s.token.taskId); text(s.token.reminderId); writeLong(s.token.scheduleVersion)
}
private fun DataOutputStream.plan(s: ReminderRuntimeScope) {
    writeBoolean(true); text(s.schedule.date.toString()); text(s.schedule.timeZone); writeBoolean(true)
    text(requireNotNull(s.schedule.time).toString()); writeInt(requireNotNull(s.schedule.offsetSeconds)); text(s.mode.name)
}
private fun DataOutputStream.scope(s: ReminderRuntimeScope) { token(s); plan(s) }
private fun DataOutputStream.capability(c: ReminderCapabilityObservation) {
    writeLong(c.checkedAtMillis); writeBoolean(c.availability.platformConnected); text(c.availability.notifications.name)
    text(c.availability.exactAlarms.name); writeBoolean(c.availability.channelEnabled)
}
private fun DataOutputStream.eventBody(s: ReminderRuntimeScope, v: Long, at: Long, c: ReminderCapabilityObservation) {
    token(s); writeLong(v); plan(s); writeLong(at); capability(c)
}
private fun DataOutputStream.claimBody(e: ReminderEventRequest, v: Long, at: Long, c: ReminderCapabilityObservation, spec: String) {
    text(e.id); text(e.requestHash); scope(e.scope); writeLong(v); writeLong(at); capability(c); text(spec)
}
private fun DataOutputStream.resultBody(c: ReminderClaimRequest, v: Long, at: Long, o: ReminderPostObservation, reason: ReminderResultReason, cap: ReminderCapabilityObservation?) {
    text(c.event.id); text(c.event.requestHash); text(c.id); text(c.requestHash)
    text(c.event.scope.token.taskId); text(c.event.scope.token.reminderId); writeLong(c.event.scope.token.scheduleVersion)
    writeLong(v); writeLong(at); text(o.name); text(reason.name); writeBoolean(cap != null); cap?.let { capability(it) }
}
