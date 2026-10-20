package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.util.Collections

class ReminderRuntimeView(val task: ManualTaskSnapshot, receipts: List<ReminderRuntimeReceipt>) {
    val receipts: List<ReminderRuntimeReceipt> = Collections.unmodifiableList(receipts.toList())
    fun event(id: String): ReminderRuntimeReceipt? = receipts.singleOrNull { it.event.id == id }
    fun unverifiedPast(nowMillis: Long): Boolean = task.reminder?.let {
        it.phase in setOf(TaskReminderPhase.PENDING, TaskReminderPhase.SCHEDULED, TaskReminderPhase.TIME_PASSED) &&
            requireNotNull(it.schedule.instant()).toEpochMilli() <= nowMillis
    } == true
    override fun toString() = "ReminderRuntimeView(redacted)"
}
enum class ReminderRuntimeOutcome { ACCEPTED, EXACT_REPLAY, ALREADY_OBSERVED, STALE, MISSING, TOO_EARLY, BLOCKED, RETIRED, NO_ACCESS, UNKNOWN, CAPACITY }
class ReminderRuntimeReply(val outcome: ReminderRuntimeOutcome, val view: ReminderRuntimeView?) {
    override fun toString() = "ReminderRuntimeReply(outcome=$outcome)"
}

/** Records observations, never invokes a platform API or creates a runtime execution permit. */
interface ReminderRuntimeRepository : TaskReminderRepository {
    fun runtimeView(taskId: String): ReminderRuntimeView?
    fun acceptRuntimeEvent(request: ReminderEventRequest): ReminderRuntimeReply
    fun decideRuntimeClaim(request: ReminderClaimRequest): ReminderRuntimeReply
    fun recordRuntimeResult(request: ReminderResultRequest, authority: ReminderResultAuthority): ReminderRuntimeReply
    fun endRuntime(token: TaskReminderToken, expectedVersion: Long, atMillis: Long, reason: ReminderClosureReason): Boolean
    fun markKnownLostPast(token: TaskReminderToken, expectedVersion: Long, atMillis: Long): Boolean
    /** Only under the execution gate, after active operations have drained. ACK never implicitly collects. */
    fun collectRetiredRuntime(taskId: String, expectedVersion: Long, atMillis: Long): Int
}

/** A fact about the one actual call, bounded by the original live operation; never serialized. */
class ReminderResultAuthority internal constructor(private val request: ReminderResultRequest, private val active: () -> Boolean) {
    fun authorizes(value: ReminderResultRequest): Boolean = active() && request == value
    override fun toString() = "ReminderResultAuthority(redacted)"
}

/** Synthetic/controlled in R2a. The Android adapter is deliberately not installed. */
interface ReminderRuntimeDriver : TaskReminderDriver {
    fun post(token: TaskReminderToken, specification: String): ReminderPostObservation
}
