package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private class ReminderAccessUnavailable : IllegalStateException()
private val reminderTokenOrder = compareBy<TaskReminderToken>({ it.taskId }, { it.reminderId }, { it.scheduleVersion })

/** Process-local execution and retirement boundary. No Activity, key, Android API or persistent permit. */
class ReminderExecutionGate {
    private val execution = ReentrantLock(true)
    private val depth = ThreadLocal.withInitial { 0 }
    @Volatile private var current: ReminderRuntimeOwner? = null
    fun openOwner(accessAllowed: Boolean): ReminderRuntimeOwner = execution.withLock {
        check(depth.get() == 0 && current == null) { "Previous reminder owner not closed" }
        ReminderRuntimeOwner(this, accessAllowed).also { current = it }
    }
    /** Production admission: no key/store factory until access is confirmed under this same gate.
     * A failed prepare leaves its owner sealed; the caller must confirm cleanup with closeAndAwait.
     */
    fun <T> admit(access: () -> Boolean, prepare: (ReminderRuntimeOwner) -> T): T? = execution.withLock {
        check(depth.get() == 0 && current == null) { "Previous reminder owner not closed" }
        depth.set(1)
        try {
            if (!access()) return@withLock null
            val owner = ReminderRuntimeOwner(this, true).also { current = it }
            try { prepare(owner) } catch (failure: Throwable) { owner.seal(); throw failure }
        } finally { depth.set(0) }
    }
    /** Preference-only observation/admission serialization, including while a prior owner is sealed.
     * No repository or key is supplied by this operation. Production callers use the process worker.
     */
    fun <T> inspectAdmission(action:()->T):T=execution.withLock {
        check(depth.get()==0){"Cannot reenter reminder admission"};depth.set(1)
        try{action()}finally{depth.set(0)}
    }
    fun <T> perform(owner: ReminderRuntimeOwner, action: () -> T): T = execution.withLock {
        check(depth.get() == 0) { "Cannot reenter reminder execution" }
        if (current !== owner || !owner.admitted()) throw ReminderAccessUnavailable()
        depth.set(1)
        try { action() } finally { depth.set(0) }
    }
    internal fun close(owner: ReminderRuntimeOwner, action: () -> Unit) {
        owner.seal()
        check(depth.get() == 0) { "Cannot close inside a reminder operation" }
        execution.withLock {
            check(current === owner) { "Reminder owner is not current" }
            action() // A failure keeps the old owner sealed and blocks replacement.
            current = null
        }
    }
}

class ReminderRuntimeOwner internal constructor(internal val gate: ReminderExecutionGate, private val accessAllowed: Boolean) {
    private val sealed = AtomicBoolean()
    private val pending = LinkedHashMap<TaskReminderToken, ReminderRuntimeIngress>()
    // Gate-confined ordering only, never a durable command or execution permit.
    private var drainAfter: TaskReminderToken? = null
    internal fun admitted() = accessAllowed && !sealed.get()
    val isAdmitted: Boolean get() = admitted()
    fun capture(token: TaskReminderToken, observedAtMillis: Long): ReminderRuntimeIngress? = synchronized(pending) {
        require(observedAtMillis >= 0)
        if (!admitted()) return@synchronized null
        pending[token]?.let { return@synchronized it }
        if (pending.size >= 64) return@synchronized null
        ReminderRuntimeIngress(this, token, observedAtMillis).also { pending[token] = it }
    }
    fun seal() { sealed.set(true); synchronized(pending) { pending.values.forEach { it.retire() }; pending.clear() } }
    fun closeAndAwait(action: () -> Unit = {}) = gate.close(this, action)
    internal fun release(ingress: ReminderRuntimeIngress) = synchronized(pending) { if (pending[ingress.token] === ingress) pending.remove(ingress.token); Unit }
    internal fun drainBatch(commands: List<TaskReminderCommand>, nowMillis: Long, limit: Int): List<TaskReminderCommand> {
        val eligible = commands.filter { it.kind == TaskReminderCommandKind.CANCEL ||
            it.kind == TaskReminderCommandKind.SCHEDULE && requireNotNull(it.schedule.instant()).toEpochMilli() > nowMillis }
            .sortedWith { a, b -> reminderTokenOrder.compare(a.token, b.token) }
        if (eligible.isEmpty()) return emptyList()
        val after = drainAfter
        val start = if (after == null) 0 else eligible.indexOfFirst { reminderTokenOrder.compare(it.token, after) > 0 }.let { if (it < 0) 0 else it }
        return List(minOf(limit, eligible.size)) { eligible[(start + it) % eligible.size] }.also { drainAfter = it.last().token }
    }
    override fun toString() = "ReminderRuntimeOwner(redacted)"
}

/** An in-memory admission captured before a later unlock/owner change; cannot be reconstructed from an ID. */
class ReminderRuntimeIngress internal constructor(internal val owner: ReminderRuntimeOwner, internal val token: TaskReminderToken,
    internal val observedAtMillis: Long) {
    internal val deliveries = AtomicInteger()
    internal var event: ReminderEventRequest? = null
    private val lifetime = AtomicInteger() // 0 active, 1 platform call admitted, 2 retired, 3 finished
    fun retire() { lifetime.compareAndSet(0, 2) }
    internal fun mayStart() = lifetime.get() == 0
    internal fun beginPost() = lifetime.compareAndSet(0, 1)
    internal fun finish() { lifetime.set(3) }
    override fun toString() = "ReminderRuntimeIngress(redacted)"
}

/** Controlled R2a runtime. Neither this class nor its repository is wired into an Android receiver. */
class ReminderRuntimeCoordinator(private val owner: ReminderRuntimeOwner, private val repository: () -> ReminderRuntimeRepository,
    private val driver: ReminderRuntimeDriver, private val clock: () -> Long) {
    private class LiveClaimAttempt { val active = AtomicBoolean(true); val consumed = AtomicBoolean() }

    fun <T> mutate(action: (ReminderRuntimeRepository) -> T): T = owner.gate.perform(owner) { action(repository()) }

    fun handle(ingress: ReminderRuntimeIngress): ReminderRuntimeReply {
        if (ingress.owner !== owner) return ReminderRuntimeReply(ReminderRuntimeOutcome.NO_ACCESS, null)
        return try { owner.gate.perform(owner) {
            val delivery = ingress.deliveries.getAndIncrement()
            if (delivery == 0 && !ingress.mayStart()) { owner.release(ingress); return@perform ReminderRuntimeReply(ReminderRuntimeOutcome.RETIRED, null) }
            if (delivery > 1) { owner.release(ingress); return@perform ReminderRuntimeReply(ReminderRuntimeOutcome.RETIRED, null) }
            if (delivery > 0 && ingress.event == null) { owner.release(ingress); return@perform ReminderRuntimeReply(ReminderRuntimeOutcome.STALE, null) }
            val store = try { repository() } catch (cancelled: java.util.concurrent.CancellationException) { owner.release(ingress); throw cancelled }
            catch (_: Exception) { owner.release(ingress); return@perform ReminderRuntimeReply(ReminderRuntimeOutcome.UNKNOWN, null) }
            if (delivery == 0 && !ingress.mayStart()) { owner.release(ingress); return@perform ReminderRuntimeReply(ReminderRuntimeOutcome.RETIRED, null) }
            if (delivery > 0) {
                val event = ingress.event ?: return@perform ReminderRuntimeReply(ReminderRuntimeOutcome.STALE, null)
                return@perform eventReadback(store, event) ?: ReminderRuntimeReply(ReminderRuntimeOutcome.STALE, store.runtimeView(ingress.token.taskId))
            }
            var attempt: LiveClaimAttempt? = null
            try {
                val initial = store.runtimeView(ingress.token.taskId) ?: return@perform ReminderRuntimeReply(ReminderRuntimeOutcome.MISSING, null)
                val r = initial.task.reminder ?: return@perform ReminderRuntimeReply(ReminderRuntimeOutcome.STALE, initial)
                if (initial.task.id != ingress.token.taskId || r.id != ingress.token.reminderId || r.scheduleVersion != ingress.token.scheduleVersion)
                    return@perform ReminderRuntimeReply(ReminderRuntimeOutcome.STALE, initial)
                val scope = ReminderRuntimeScope(ingress.token, r.schedule, r.mode)
                val event = ReminderEventRequest.create(scope, initial.task.version, ingress.observedAtMillis, observe())
                ingress.event = event
                val accepted = settle({ store.acceptRuntimeEvent(event) }, { eventReadback(store, event) })
                val observed = accepted.view?.event(event.id)
                if (accepted.outcome !in setOf(ReminderRuntimeOutcome.ACCEPTED, ReminderRuntimeOutcome.EXACT_REPLAY) ||
                    observed?.eventDecision != ReminderEventDecision.ACCEPTED || observed.closed != null || observed.retired != null || observed.claim != null)
                    return@perform accepted
                if (!owner.admitted() || !ingress.mayStart()) { end(store, event.scope.token); return@perform ReminderRuntimeReply(ReminderRuntimeOutcome.RETIRED, store.runtimeView(scope.token.taskId)) }
                val claim = ReminderClaimRequest.create(event, accepted.view.task.version, clock(), observe())
                val live = LiveClaimAttempt().also { attempt = it }
                // A negative observation permanently closes this attempt even if its SQL write rolls back.
                if (claim.capability.availability.blocked(scope.mode) != null) live.consumed.set(true)
                val claimed = settle({ store.decideRuntimeClaim(claim) }, { claimReadback(store, claim) })
                val claimedSlot = claimed.view?.event(event.id)
                if (claimedSlot?.claim != claim || claimedSlot.claimDecision != ReminderClaimDecision.GRANTED || claimedSlot.closed != null ||
                    claimedSlot.retired != null || live.consumed.get() || !owner.admitted() || !ingress.mayStart()) {
                    if (claimedSlot != null && claimedSlot.closed == null) end(store, scope.token)
                    return@perform if (!owner.admitted() || !ingress.mayStart()) ReminderRuntimeReply(ReminderRuntimeOutcome.RETIRED, store.runtimeView(scope.token.taskId)) else claimed
                }
                check(live.consumed.compareAndSet(false,true)) // At most one port attempt; readback never resets it.
                val preflight = observe()
                if (!owner.admitted()) { end(store, scope.token); return@perform ReminderRuntimeReply(ReminderRuntimeOutcome.RETIRED, store.runtimeView(scope.token.taskId)) }
                if (!ingress.beginPost()) { end(store, scope.token); return@perform ReminderRuntimeReply(ReminderRuntimeOutcome.RETIRED, store.runtimeView(scope.token.taskId)) }
                val observation = if (preflight.availability.blocked(scope.mode) != null) ReminderPostObservation.NO_ATTEMPT_CAPABILITY_LOST
                else try {
                    driver.post(scope.token, claim.specification).let {
                        if (it == ReminderPostObservation.NO_ATTEMPT_CAPABILITY_LOST) ReminderPostObservation.CALL_OUTCOME_UNKNOWN else it
                    }
                } catch (_: Exception) { ReminderPostObservation.CALL_OUTCOME_UNKNOWN }
                val beforeResult = store.runtimeView(scope.token.taskId) ?: return@perform ReminderRuntimeReply(ReminderRuntimeOutcome.MISSING, null)
                val result = ReminderResultRequest.create(claim, beforeResult.task.version, clock(), observation,
                    if (observation == ReminderPostObservation.NO_ATTEMPT_CAPABILITY_LOST) preflight else null)
                val authority = ReminderResultAuthority(result) { live.active.get() && live.consumed.get() }
                settle({ store.recordRuntimeResult(result, authority) }, { resultReadback(store, result) })
            } catch (_: ReminderRuntimeLimit) { ReminderRuntimeReply(ReminderRuntimeOutcome.CAPACITY, safeView(store, ingress.token.taskId)) }
            catch (_: ReminderRuntimeConflict) { ReminderRuntimeReply(ReminderRuntimeOutcome.STALE, safeView(store, ingress.token.taskId)) }
            catch (_: ManualTaskConflict) { ReminderRuntimeReply(ReminderRuntimeOutcome.STALE, safeView(store, ingress.token.taskId)) }
            catch (_: ManualTaskStorageFailure) { ReminderRuntimeReply(ReminderRuntimeOutcome.UNKNOWN, safeView(store, ingress.token.taskId)) }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) { ReminderRuntimeReply(ReminderRuntimeOutcome.UNKNOWN, safeView(store, ingress.token.taskId)) }
            finally {
                attempt?.active?.set(false)
                ingress.finish()
                // If an uncertain write left runnable work, only retire it; never revive publication.
                try { ingress.event?.let { end(store, ingress.token, it.id) } } catch (_: Exception) { /* Durable unknown remains visibly unverified. */ }
                owner.release(ingress)
            }
        } } catch (_: ReminderAccessUnavailable) { ReminderRuntimeReply(ReminderRuntimeOutcome.NO_ACCESS, null) }
    }

    private fun observe() = ReminderCapabilityObservation(clock(), driver.availability())
    private fun safeView(store: ReminderRuntimeRepository, id: String) = try { store.runtimeView(id) } catch (_: Exception) { null }
    private fun end(store: ReminderRuntimeRepository, token: TaskReminderToken, ownedEventId: String? = null) {
        val v = store.runtimeView(token.taskId) ?: return
        val slot = v.receipts.singleOrNull { it.token == token } ?: return
        if (ownedEventId != null && slot.event.id != ownedEventId) return
        if (slot.closed == null && slot.retired == null) store.endRuntime(token, v.task.version, clock(), ReminderClosureReason.OWNER_RETIRED)
    }
    private fun settle(call: () -> ReminderRuntimeReply, readback: () -> ReminderRuntimeReply?): ReminderRuntimeReply {
        repeat(2) {
            try { return call() } catch (_: ManualTaskStorageFailure) {
                try { readback()?.let { return it } } catch (_: ManualTaskStorageFailure) { /* Same immutable request only. */ }
            }
        }
        return ReminderRuntimeReply(ReminderRuntimeOutcome.UNKNOWN, null)
    }
    private fun eventReadback(s: ReminderRuntimeRepository, r: ReminderEventRequest): ReminderRuntimeReply? {
        val v = s.runtimeView(r.scope.token.taskId) ?: return null
        val slot = v.event(r.id) ?: return null
        if (slot.event != r) throw ReminderRuntimeConflict()
        return ReminderRuntimeReply(if (slot.retired != null) ReminderRuntimeOutcome.RETIRED else ReminderRuntimeOutcome.EXACT_REPLAY, v)
    }
    private fun claimReadback(s: ReminderRuntimeRepository, r: ReminderClaimRequest): ReminderRuntimeReply? {
        val v = s.runtimeView(r.event.scope.token.taskId) ?: return null
        val slot = v.event(r.event.id) ?: return null
        val claim = slot.claim ?: return null
        if (claim != r) throw ReminderRuntimeConflict()
        return ReminderRuntimeReply(if (slot.retired != null) ReminderRuntimeOutcome.RETIRED else ReminderRuntimeOutcome.EXACT_REPLAY, v)
    }
    private fun resultReadback(s: ReminderRuntimeRepository, r: ReminderResultRequest): ReminderRuntimeReply? {
        val v = s.runtimeView(r.claim.event.scope.token.taskId) ?: return null
        val result = v.event(r.claim.event.id)?.result ?: return null
        if (result != r) throw ReminderRuntimeConflict()
        return ReminderRuntimeReply(ReminderRuntimeOutcome.EXACT_REPLAY, v)
    }

    /** One snapshot, at most limit attempts; ineligible work is retained without occupying a batch slot. */
    fun drain(limit: Int = 16): Int {
        require(limit in 1..16)
        val commands = try { mutate { owner.drainBatch(it.reminderCommands(), clock(), limit) } }
            catch (_: ReminderAccessUnavailable) { return 0 }
        return executeCommands(commands)
    }
    /** Bounded SQL page supplied by the same admitted owner. Every command is still reauthenticated. */
    fun drainPage(page: ReminderCommandPage, proceed: () -> Boolean = { true }): Int = executeCommands(page.commands, proceed)
    private fun executeCommands(commands: List<TaskReminderCommand>, proceed: () -> Boolean = { true }): Int {
        require(commands.size <= 16)
        var acknowledged = 0
        for (command in commands) {
            if (!proceed()) break
            try { mutate { s ->
                if (!proceed()) return@mutate
                if (command.kind == TaskReminderCommandKind.DELIVER) return@mutate
                if (!s.isReminderCommandCurrent(command)) return@mutate
                if (command.kind == TaskReminderCommandKind.SCHEDULE && requireNotNull(command.schedule.instant()).toEpochMilli() <= clock()) return@mutate
                if (!owner.admitted() || !proceed()) return@mutate
                val result = if (command.kind == TaskReminderCommandKind.CANCEL) driver.cancel(command) else {
                    val blocked = driver.availability().blocked(command.mode)
                    if (!owner.admitted() || !proceed()) return@mutate
                    // A blocking capability query may have crossed due; retain the original late-callback eligibility.
                    if (requireNotNull(command.schedule.instant()).toEpochMilli() <= clock()) return@mutate
                    blocked ?: driver.schedule(command)
                }
                if (s.acknowledgeReminder(command, result, clock())) acknowledged++
            } } catch (_: ReminderAccessUnavailable) { break }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Unknown schedule effect retains the original command. */ }
        }
        return acknowledged
    }

    fun reconcile(taskId: String, knownScheduleLost: Boolean = false): ReminderRuntimeView? = mutate { s ->
        var v = s.runtimeView(taskId) ?: return@mutate null
        v.receipts.filter { it.retired == null && it.closed == null }.forEach { end(s, it.token) }
        v = s.runtimeView(taskId) ?: return@mutate null
        val r = v.task.reminder
        if (knownScheduleLost && r != null) s.markKnownLostPast(TaskReminderToken(taskId, r.id, r.scheduleVersion), v.task.version, clock())
        s.runtimeView(taskId)
    }
    fun collect(taskId: String): Int = mutate { s -> val v = s.runtimeView(taskId) ?: return@mutate 0; s.collectRetiredRuntime(taskId, v.task.version, clock()) }
}
