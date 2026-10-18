package com.t1doo.aiyou.records

import com.t1doo.aiyou.ai.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Trusted, explicitly memory-only rehearsal. Never install this capability for real external sending. */
class SelectedQuestionConsentFixture(val connection: ProviderConnection, val service: LocalProtocolQuestionService,
    val clock: Clock = Clock.systemUTC(), val questionTimeZone: String = ZoneId.systemDefault().id) {
    val target = SelectedQuestionTarget(connection.protocol, connection.apiRoot, connection.modelId, connection.version)
    val endpoint: String
    init {
        require(service.target == target); ZoneId.of(questionTimeZone)
        endpoint = ProtocolRequests.endpoint(connection.apiRoot, when (connection.protocol) {
            ConnectionProtocol.CHAT_COMPLETIONS -> Protocol.CHAT_COMPLETIONS
            ConnectionProtocol.ANTHROPIC_MESSAGES -> Protocol.ANTHROPIC_MESSAGES
        }).toString()
    }
    override fun toString() = "SelectedQuestionConsentFixture(redacted, memoryOnly=true)"
}

enum class QuestionConsentStage {
    UNAVAILABLE, WAITING, PREPARING, REVIEW, AUTHORIZING, RUNNING, READY, NO_EVIDENCE,
    CANCELLED, WITHDRAWING, WITHDRAWN, WITHDRAW_FAILED, BLOCKED, FAILED, STALE,
}

class QuestionConsentState internal constructor(val stage: QuestionConsentStage, val requestId: String? = null,
    val review: SelectedQuestionSendReview? = null, val receipt: SelectedQuestionSendReceipt? = null) {
    override fun toString() = "QuestionConsentState(stage=$stage, redacted)"
}

/** Local selection is only a read proof. A separate immutable send review must be consumed once.
 * All SQL/exchange/consumer work is outside monitors; no state is saved or turned into a fact/task.
 */
class SelectedQuestionConsentController internal constructor(
    private val evidence: LocalEvidenceController,
    private val currentEvidence: () -> LocalEvidenceState,
    private val ownsSession: () -> Boolean,
    private val owner: SelectedQuestionAuthorizationRepository?,
    private val currentOwner: () -> SelectedQuestionAuthorizationRepository?,
    private val fixture: SelectedQuestionConsentFixture?,
    private val currentFixture: () -> SelectedQuestionConsentFixture?,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile private var closed = false
    val available get() = owner != null && fixture != null
    private val waiting = QuestionConsentState(if (available) QuestionConsentStage.WAITING else QuestionConsentStage.UNAVAILABLE)
    private val mutable = MutableStateFlow(waiting)
    val state = mutable.asStateFlow()
    private class Run(val view: LocalEvidenceState, val request: SelectedQuestionRequest) {
        val retired = AtomicBoolean()
        val executionStopped = AtomicBoolean()
        var review: SelectedQuestionSendReview? = null
        var permit: SelectedQuestionSendPermit? = null
        var receipt: SelectedQuestionSendReceipt? = null
        var exchange: PersistedLocalQuestionExchange? = null
        var execution: Job? = null
        var observer: Job? = null
    }
    private var run: Run? = null
    private val cancellations = ArrayList<PersistedLocalQuestionExchange>()
    private fun stopExecutionLocked(value: Run) {
        value.executionStopped.set(true); value.execution?.cancel()
        value.exchange?.let { cancellations += it }; value.exchange = null
    }
    private fun retireLocked(value: Run?) {
        value ?: return
        value.retired.set(true); stopExecutionLocked(value); value.observer?.cancel()
    }
    private fun drain() {
        val pending = synchronized(lock) { cancellations.toList().also { cancellations.clear() } }
        pending.forEach { try { it.cancel() } catch (_: Exception) { } }
    }
    private fun owns(value: Run) = !closed && run === value && !value.retired.get() && ownsSession() &&
        currentOwner() === owner && currentFixture() === fixture && currentEvidence() === value.view &&
        value.view.selection.stage == LocalEvidenceSelectionStage.CONFIRMED
    private fun executionOwned(value: Run) = owns(value) && !value.executionStopped.get()
    private fun now(value: Run, executing: Boolean = false): java.time.Instant {
        if (closed || value.retired.get() || (executing && value.executionStopped.get())) throw CancellationException()
        return checkNotNull(fixture).clock.instant().also {
            if (closed || value.retired.get() || (executing && value.executionStopped.get())) throw CancellationException()
        }
    }
    private fun stateOf(stage: QuestionConsentStage, value: Run) = QuestionConsentState(stage, value.request.id,
        if (stage == QuestionConsentStage.REVIEW) value.review else null,
        if (stage == QuestionConsentStage.READY || stage == QuestionConsentStage.NO_EVIDENCE) value.receipt else null)
    fun visible(): QuestionConsentState = synchronized(lock) {
        if (run == null || run?.let(::owns) == true) mutable.value else QuestionConsentState(QuestionConsentStage.STALE)
    }
    fun sync() = synchronized(lock) {
        if (run?.let { !owns(it) } == true) {
            retireLocked(run); run = null; mutable.value = QuestionConsentState(QuestionConsentStage.STALE)
        }
    }.also { drain() }

    /** Callback only retains the packet in memory. SQL authorization never runs inside it. */
    private fun inspect(value: Run, executing: Boolean = false): Boolean {
        var packet: LocalEvidencePacket? = null
        if (!evidence.inspectConfirmed(value.view, value.view.selection.confirmation,
                { value.retired.get() || (executing && value.executionStopped.get()) }) { packet = it }) return false
        val p = checkNotNull(packet)
        return p.selectionId == value.request.selectionId && p.question == value.request.question &&
            p.citations.map { SelectedQuestionSource(it.reference, it.capturedAtMillis, it.captureTimeZone, it.policy) } == value.request.sources
    }
    fun review(value: LocalEvidenceState) {
        val job = synchronized(lock) {
            if (closed || !available || !ownsSession() || currentOwner() !== owner || currentFixture() !== fixture ||
                currentEvidence() !== value || value.stage != LocalEvidenceStage.READY ||
                value.selection.stage != LocalEvidenceSelectionStage.CONFIRMED) return@synchronized null
            val packet = try { LocalEvidencePacket.reviewed(value) } catch (_: Exception) { return@synchronized null }
            val f = checkNotNull(fixture)
            val request = try { SelectedQuestionRequest(UUID.randomUUID().toString(), packet.selectionId, packet.question,
                f.target, f.clock.millis(), f.questionTimeZone, packet.citations.map {
                    SelectedQuestionSource(it.reference, it.capturedAtMillis, it.captureTimeZone, it.policy)
                }) } catch (_: Exception) { return@synchronized null }
            retireLocked(run)
            val next = Run(value, request); run = next; mutable.value = stateOf(QuestionConsentStage.PREPARING, next)
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (!inspect(next)) { invalidate(next); return@launch }
                    val reviewed = checkNotNull(owner).review(request, f.connection, PROMPT_VERSION) { now(next) }
                    val accepted = synchronized(lock) {
                        if (!owns(next)) false else { next.review = reviewed; mutable.value = stateOf(QuestionConsentStage.REVIEW, next); true }
                    }
                    if (accepted) observe(next)
                } catch (cancelled: CancellationException) {
                    if (isActive) invalidate(next)
                    throw cancelled
                }
                catch (_: Exception) { finish(next, QuestionConsentStage.BLOCKED) }
            }.also { next.execution = it }
        }
        drain(); job?.start()
    }
    /** Identity consumption happens here; persisted authorization happens only on the IO owner. */
    fun confirm(expected: SelectedQuestionSendReview?) {
        val job = synchronized(lock) {
            val next = run ?: return@synchronized null
            if (!executionOwned(next) || mutable.value.stage != QuestionConsentStage.REVIEW || expected == null ||
                next.review !== expected || mutable.value.review !== expected) return@synchronized null
            mutable.value = stateOf(QuestionConsentStage.AUTHORIZING, next)
            scope.launch(start = CoroutineStart.LAZY) {
                var permit: SelectedQuestionSendPermit? = null
                try {
                    if (!inspect(next, true)) { invalidate(next); return@launch }
                    permit = checkNotNull(owner).authorizeReviewedSend(expected) { now(next, true) }
                    val retained = synchronized(lock) {
                        if (!executionOwned(next)) false else {
                            next.permit = permit; mutable.value = stateOf(QuestionConsentStage.RUNNING, next); true
                        }
                    }
                    if (!retained) return@launch
                    val exchange = PersistedLocalQuestionExchange(owner, permit, checkNotNull(fixture).service) { now(next, true) }
                    val registered = synchronized(lock) { if (!executionOwned(next)) false else { next.exchange = exchange; true } }
                    if (!registered) { exchange.cancel(); return@launch }
                    val receipt = exchange.execute()
                    if (!inspect(next, true)) { invalidate(next); return@launch }
                    owner.checkReceipt(receipt) { now(next, true) }?.let { throw SelectedQuestionSendDenied(it) }
                    synchronized(lock) {
                        if (executionOwned(next) && mutable.value.stage == QuestionConsentStage.RUNNING) {
                            next.receipt = receipt
                            mutable.value = stateOf(if (receipt.reply.noEvidence) QuestionConsentStage.NO_EVIDENCE else QuestionConsentStage.READY, next)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (isActive) invalidate(next)
                    throw cancelled
                }
                catch (_: Exception) { finish(next, QuestionConsentStage.FAILED) }
                finally {
                    val exchange = synchronized(lock) { next.exchange.also { next.exchange = null } }
                    try { exchange?.cancel() } finally {
                        // This original IO owner cleans a just-returned permit even after its scope
                        // was cancelled. A separate launch on a cancelled scope would silently skip it.
                        withContext(NonCancellable) { try { permit?.let { owner?.cancelPending(it) } } catch (_: Exception) { } }
                    }
                }
            }.also { next.execution = it }
        }
        job?.start()
    }
    private fun observe(value: Run) {
        val observer = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isActive) {
                    delay(150)
                    val snapshot = synchronized(lock) {
                        if (!owns(value) || mutable.value.stage !in OBSERVED) null else value.review to value.receipt
                    } ?: return@launch
                    val receipt = snapshot.second
                    if (receipt == null) {
                        // Read-only review checks all grants without mistaking this owner's own
                        // COMMITTED transition for a pending-job failure.
                        val old = checkNotNull(snapshot.first)
                        val fresh = checkNotNull(owner).review(value.request, old.connection, old.promptVersion) { now(value) }
                        if (fresh.sources != old.sources || fresh.sessionId != old.sessionId) { invalidate(value); return@launch }
                    } else checkNotNull(owner).checkReceipt(receipt) { now(value) }?.let { invalidate(value); return@launch }
                    if (!inspect(value)) { invalidate(value); return@launch }
                }
            } catch (cancelled: CancellationException) {
                // A cancelled read/clock is not necessarily an owner-cancelled job. Never
                // leave a completed answer visible after its only observer has exited.
                if (isActive) invalidate(value)
                throw cancelled
            }
            catch (_: Exception) { invalidate(value) }
        }
        val start = synchronized(lock) { if (!owns(value)) false else { value.observer = observer; true } }
        if (start) observer.start() else observer.cancel()
    }
    private fun finish(value: Run, stage: QuestionConsentStage) = synchronized(lock) {
        if (executionOwned(value)) mutable.value = stateOf(stage, value)
    }
    private fun invalidate(value: Run) = synchronized(lock) {
        if (!closed && run === value && mutable.value.stage !in setOf(QuestionConsentStage.WITHDRAWING,
                QuestionConsentStage.WITHDRAWN, QuestionConsentStage.WITHDRAW_FAILED)) {
            retireLocked(value); run = null; mutable.value = QuestionConsentState(QuestionConsentStage.STALE)
        }
    }.also { drain() }
    fun stop(expected: QuestionConsentState) = synchronized(lock) {
        val value = run ?: return@synchronized
        if (mutable.value !== expected || !owns(value) || expected.stage !in STOPPABLE) return@synchronized
        retireLocked(value); run = null; mutable.value = QuestionConsentState(QuestionConsentStage.CANCELLED, value.request.id)
    }.also { drain() }
    fun withdraw(expected: QuestionConsentState) {
        val job = synchronized(lock) {
            val value = run ?: return@synchronized null
            val permit = value.permit ?: return@synchronized null
            if (!owns(value) || expected.requestId != value.request.id || expected.stage !in WITHDRAWABLE ||
                mutable.value.stage !in WITHDRAWABLE) return@synchronized null
            stopExecutionLocked(value); value.observer?.cancel()
            mutable.value = stateOf(QuestionConsentStage.WITHDRAWING, value) // Hide output before any external cancellation or SQL.
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    checkNotNull(owner).withdraw(permit)
                    synchronized(lock) { if (owns(value)) mutable.value = stateOf(QuestionConsentStage.WITHDRAWN, value) }
                } catch (cancelled: CancellationException) {
                    if (isActive) synchronized(lock) { if (owns(value)) mutable.value = stateOf(QuestionConsentStage.WITHDRAW_FAILED, value) }
                    throw cancelled
                }
                catch (_: Exception) { synchronized(lock) { if (owns(value)) mutable.value = stateOf(QuestionConsentStage.WITHDRAW_FAILED, value) } }
            }
        }
        drain(); job?.start()
    }
    /** Fresh proof and receipt checks precede the UI consumer; consumers never run under SQL/monitors. */
    fun openSource(expected: QuestionConsentState, reference: OriginalTextReference, onOpen: (LocalEvidenceCard) -> Unit) {
        val value = synchronized(lock) {
            run?.takeIf { owns(it) && mutable.value === expected && expected.stage == QuestionConsentStage.READY &&
                expected.receipt === it.receipt && expected.receipt?.reply?.paragraphs?.any { p -> reference in p.references } == true }
        } ?: return
        scope.launch {
            val receipt = value.receipt ?: return@launch
            try {
                if (!inspect(value)) { invalidate(value); return@launch }
                if (checkNotNull(owner).checkReceipt(receipt) { now(value) } != null) { invalidate(value); return@launch }
                withContext(uiDispatcher) {
                    val card = synchronized(lock) {
                        if (!owns(value) || mutable.value !== expected) null else value.view.selection.cards.singleOrNull {
                            it.reference == reference && evidence.canOpen(value.view, it)
                        }
                    }
                    card?.let(onOpen)
                }
            } catch (cancelled: CancellationException) {
                if (isActive) invalidate(value)
                throw cancelled
            }
            catch (_: Exception) { invalidate(value) }
        }
    }
    override fun close() {
        synchronized(lock) { closed = true; retireLocked(run); run = null; mutable.value = waiting; scope.cancel() }
        drain()
    }
    companion object {
        const val PROMPT_VERSION = "selected-question-protocol-v1"
        private val OBSERVED = setOf(QuestionConsentStage.REVIEW, QuestionConsentStage.AUTHORIZING, QuestionConsentStage.RUNNING,
            QuestionConsentStage.READY, QuestionConsentStage.NO_EVIDENCE)
        private val STOPPABLE = setOf(QuestionConsentStage.PREPARING, QuestionConsentStage.REVIEW, QuestionConsentStage.AUTHORIZING, QuestionConsentStage.RUNNING)
        private val WITHDRAWABLE = setOf(QuestionConsentStage.RUNNING, QuestionConsentStage.READY, QuestionConsentStage.NO_EVIDENCE, QuestionConsentStage.WITHDRAW_FAILED)
    }
}
