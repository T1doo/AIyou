package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.LocalCandidates
import com.t1doo.aiyou.model.OriginalTextReference
import com.t1doo.aiyou.model.SelectedQuestionRequest
import com.t1doo.aiyou.model.SelectedQuestionSource
import com.t1doo.aiyou.model.SelectedQuestionMode
import com.t1doo.aiyou.domain.SelectedQuestionService
import com.t1doo.aiyou.domain.SelectedQuestionExchange
import java.time.Clock
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Test-only in-memory provider port. Never implement with network, files, secrets or external effects.
 * Production uses a separate fixed excerpt view, never this port. A real sender needs separate authority.
 */
fun interface SyntheticEvidenceAnswerProvider {
    suspend fun answer(request: LocalEvidenceAnswerRequest): LocalEvidenceAnswerResponse
}

/** One immutable multi-source request identity; local confirmation is not a ConsentGrant. */
class LocalEvidenceAnswerRequest internal constructor(val id: String, val packet: LocalEvidencePacket) {
    private val stop = AtomicBoolean()
    val cancelled: Boolean get() = stop.get()
    internal fun cancel() { stop.set(true) }
    override fun toString() = "LocalEvidenceAnswerRequest(redacted, cancelled=$cancelled)"
}

class LocalEvidenceAnswerParagraph(val text: String, references: List<OriginalTextReference>) {
    init { require(text.length <= 2400 && references.size <= 5) }
    val references: List<OriginalTextReference> = Collections.unmodifiableList(ArrayList(references))
    override fun toString() = "LocalEvidenceAnswerParagraph(redacted)"
}

/** Untrusted completed provider output, not a committed answer or evidence of support. */
class LocalEvidenceAnswerResponse(val requestId: String, val selectionId: String,
    paragraphs: List<LocalEvidenceAnswerParagraph> = emptyList(), val noEvidence: Boolean = false) {
    init { require(requestId.length <= 64 && selectionId.length <= 64 && paragraphs.size <= 5) }
    val paragraphs: List<LocalEvidenceAnswerParagraph> = Collections.unmodifiableList(ArrayList(paragraphs))
    override fun toString() = "LocalEvidenceAnswerResponse(redacted)"
}

enum class LocalEvidenceAnswerStage { WAITING, REVIEW, RUNNING, READY, NO_EVIDENCE, FAILED, CANCELLED, STALE, UNAVAILABLE }

class LocalEvidenceAnswerState internal constructor(val stage: LocalEvidenceAnswerStage,
    val requestId: String? = null, paragraphs: List<LocalEvidenceAnswerParagraph> = emptyList(),
    internal val evidence: LocalEvidenceState? = null, val excerptTimeline: Boolean = false,
    val questionReview: SelectedQuestionRequest? = null) {
    val paragraphs: List<LocalEvidenceAnswerParagraph> = Collections.unmodifiableList(ArrayList(paragraphs))
    override fun toString() = "LocalEvidenceAnswerState(stage=$stage, redacted)"
}

/** Shares the existing selection/proof. Question review adds request identity, never remote consent. */
class LocalEvidenceAnswerController internal constructor(
    private val evidence: LocalEvidenceController,
    private val currentEvidence: () -> LocalEvidenceState,
    private val ownsSession: () -> Boolean,
    private val fixture: SyntheticEvidenceAnswerProvider? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val localSummary: Boolean = false,
    private val questionService: SelectedQuestionService? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val questionTimeZone: String = ZoneId.systemDefault().id,
) : AutoCloseable {
    init {
        require(listOf(fixture != null, localSummary, questionService != null).count { it } <= 1)
        require(questionService == null || questionService.mode == SelectedQuestionMode.LOCAL_PROTOCOL_FIXTURE)
    }
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private fun stateOf(stage: LocalEvidenceAnswerStage, requestId: String? = null,
        paragraphs: List<LocalEvidenceAnswerParagraph> = emptyList(), evidence: LocalEvidenceState? = null,
        review: SelectedQuestionRequest? = null) =
        LocalEvidenceAnswerState(stage, requestId, paragraphs, evidence, excerptTimeline = localSummary, questionReview = review)
    private val waiting = stateOf(if (fixture == null && !localSummary && questionService == null) LocalEvidenceAnswerStage.UNAVAILABLE else LocalEvidenceAnswerStage.WAITING)
    private val mutable = MutableStateFlow(waiting)
    val state = mutable.asStateFlow()
    val available get() = fixture != null || localSummary || questionService != null
    private class Run(val view: LocalEvidenceState) {
        val id = UUID.randomUUID().toString()
        var request: LocalEvidenceAnswerRequest? = null
        val stop = AtomicBoolean()
        var job: Job? = null
        var question: SelectedQuestionRequest? = null
        var exchange: SelectedQuestionExchange? = null
    }
    private val cancellations = ArrayList<SelectedQuestionExchange>()
    private fun cancelLocked(value: Run?) {
        value ?: return
        value.stop.set(true); value.request?.cancel(); value.job?.cancel()
        value.exchange?.let { cancellations += it }; value.exchange = null
    }
    private fun cancelExchange(value: SelectedQuestionExchange?) { try { value?.cancel() } catch (_: Exception) { } }
    private fun drainCancellation() {
        val pending = synchronized(lock) { cancellations.toList().also { cancellations.clear() } }
        pending.forEach(::cancelExchange)
    }
    private var closed = false
    private var run: Run? = null
    private fun owns(value: Run) = !closed && run === value && !value.stop.get() && ownsSession() &&
        currentEvidence() === value.view && value.view.selection.stage == LocalEvidenceSelectionStage.CONFIRMED &&
        (questionService == null || value.question?.target == questionService.target)

    fun visible(): LocalEvidenceAnswerState = synchronized(lock) {
        val value = mutable.value
        if (value.evidence == null) value
        else if (run?.let { owns(it) } == true && value.evidence === run?.view) value
        else stateOf(LocalEvidenceAnswerStage.STALE)
    }
    fun sync() = synchronized(lock) {
        if (run?.let { !owns(it) } == true) {
            cancelLocked(run); run = null; mutable.value = stateOf(LocalEvidenceAnswerStage.STALE)
        }
    }.also { drainCancellation() }
    /** Separate one-shot review of question + target + selected scope; never a remote ConsentGrant. */
    fun review(value: LocalEvidenceState) = synchronized(lock) {
        val service = questionService ?: return@synchronized
        if (closed || !ownsSession() || currentEvidence() !== value || value.stage != LocalEvidenceStage.READY ||
            value.selection.stage != LocalEvidenceSelectionStage.CONFIRMED) return@synchronized
        val packet = try { LocalEvidencePacket.reviewed(value) } catch (_: Exception) { return@synchronized }
        cancelLocked(run)
        val next = Run(value)
        next.question = SelectedQuestionRequest(next.id, packet.selectionId, packet.question, service.target,
            clock.millis(), questionTimeZone, packet.citations.map {
                SelectedQuestionSource(it.reference, it.capturedAtMillis, it.captureTimeZone, it.policy)
            })
        run = next
        mutable.value = stateOf(LocalEvidenceAnswerStage.REVIEW, next.id, evidence = value, review = next.question)
    }.also { drainCancellation() }
    fun ask(value: LocalEvidenceState, expectedReview: SelectedQuestionRequest? = null) = synchronized(lock) {
        if (closed || !available || !ownsSession() || currentEvidence() !== value ||
            value.stage != LocalEvidenceStage.READY || value.selection.stage != LocalEvidenceSelectionStage.CONFIRMED ||
            value.selection.confirmation == null || value.selection.cards.isEmpty()) return@synchronized
        if (run?.let { owns(it) } == true && mutable.value.stage == LocalEvidenceAnswerStage.RUNNING) return@synchronized
        val next = if (questionService != null) {
            run?.takeIf { owns(it) && mutable.value.stage == LocalEvidenceAnswerStage.REVIEW &&
                mutable.value.questionReview === expectedReview && it.question === expectedReview } ?: return@synchronized
        } else {
            cancelLocked(run); Run(value).also { run = it }
        }
        mutable.value = stateOf(LocalEvidenceAnswerStage.RUNNING, next.id, evidence = value)
        next.job = scope.launch {
            try {
                var packet: LocalEvidencePacket? = null
                if (!evidence.inspectConfirmed(value, value.selection.confirmation, next.stop::get) { packet = it }) {
                    finish(next, LocalEvidenceAnswerStage.STALE); return@launch
                }
                val request = synchronized(lock) {
                    if (!owns(next)) null else LocalEvidenceAnswerRequest(next.id, checkNotNull(packet)).also { next.request = it }
                } ?: return@launch
                // Outside SQL/owner locks. Default fallback copies quotes; optional question service
                // remains a local protocol fixture. Neither path grants remote sending authority.
                val response = when {
                    localSummary -> LocalEvidenceTimeline.generate(request)
                    questionService != null -> {
                        val approved = checkNotNull(next.question)
                        require(approved.question == request.packet.question && approved.selectionId == request.packet.selectionId &&
                            approved.sources == request.packet.citations.map {
                                SelectedQuestionSource(it.reference, it.capturedAtMillis, it.captureTimeZone, it.policy)
                            })
                        val exchange = questionService.newExchange(approved)
                        val registered = synchronized(lock) { if (!owns(next)) false else { next.exchange = exchange; true } }
                        if (!registered) { cancelExchange(exchange); return@launch }
                        try {
                            val reply = exchange.execute()
                            LocalEvidenceAnswerResponse(reply.requestId, reply.selectionId,
                                reply.paragraphs.map { LocalEvidenceAnswerParagraph(it.text, it.references) }, reply.noEvidence)
                        } finally {
                            val retained = synchronized(lock) { next.exchange.also { next.exchange = null } }
                            cancelExchange(retained)
                        }
                    }
                    else -> checkNotNull(fixture).answer(request)
                }
                currentCoroutineContext().ensureActive()
                val paragraphs = validate(request, response)
                // A completed provider output is still provisional. Inspect again and wait for COMMIT;
                // neither the SQL callback nor provider completion publishes user-visible content.
                if (!evidence.inspectConfirmed(value, value.selection.confirmation, next.stop::get) { }) {
                    finish(next, LocalEvidenceAnswerStage.STALE); return@launch
                }
                finish(next, if (response.noEvidence) LocalEvidenceAnswerStage.NO_EVIDENCE else LocalEvidenceAnswerStage.READY, paragraphs)
            } catch (cancelled: CancellationException) {
                synchronized(lock) { if (!closed && run === next && !next.stop.get()) {
                    cancelLocked(next); run = null; mutable.value = stateOf(LocalEvidenceAnswerStage.CANCELLED, next.id)
                } }
                drainCancellation()
                throw cancelled
            } catch (_: Exception) { finish(next, LocalEvidenceAnswerStage.FAILED) }
        }
    }.also { drainCancellation() }
    private fun finish(value: Run, stage: LocalEvidenceAnswerStage, paragraphs: List<LocalEvidenceAnswerParagraph> = emptyList()) = synchronized(lock) {
        if (owns(value)) mutable.value = stateOf(stage, value.id, paragraphs, value.view)
        else if (!closed && run === value && !value.stop.get()) {
            cancelLocked(value); run = null; mutable.value = stateOf(LocalEvidenceAnswerStage.STALE)
        }
    }.also { drainCancellation() }
    fun cancel() = synchronized(lock) {
        val old = run ?: return@synchronized
        cancelLocked(old); run = null
        mutable.value = stateOf(LocalEvidenceAnswerStage.CANCELLED, old.id)
    }.also { drainCancellation() }
    /** The source card is still owned by the original evidence view; its existing search.open resolves SQL again. */
    fun sourceFor(value: LocalEvidenceAnswerState, reference: OriginalTextReference): LocalEvidenceCard? = synchronized(lock) {
        val current = run ?: return@synchronized null
        if (mutable.value !== value || !owns(current) || value.stage != LocalEvidenceAnswerStage.READY ||
            value.paragraphs.none { reference in it.references }) return@synchronized null
        current.view.selection.cards.singleOrNull { it.reference == reference && evidence.canOpen(current.view, it) }
    }
    override fun close() = synchronized(lock) { closed = true; cancelLocked(run); run = null; mutable.value = waiting; scope.cancel() }
        .also { drainCancellation() }

    companion object {
        const val MAX_PARAGRAPHS = 5
        const val MAX_ANSWER_POINTS = 1200
        const val MAX_ANSWER_BYTES = 4800
        internal fun validate(request: LocalEvidenceAnswerRequest, response: LocalEvidenceAnswerResponse): List<LocalEvidenceAnswerParagraph> {
            require(!request.cancelled && response.requestId == request.id && response.selectionId == request.packet.selectionId)
            if (response.noEvidence) { require(response.paragraphs.isEmpty()); return emptyList() }
            require(response.paragraphs.size in 1..MAX_PARAGRAPHS)
            val allowed = request.packet.citations.map { it.reference }.toSet()
            var points = 0; var bytes = 0; var refs = 0
            for (paragraph in response.paragraphs) {
                require(paragraph.text.isNotBlank() && paragraph.text.length <= MAX_ANSWER_POINTS * 2 && LocalCandidates.validUnicode(paragraph.text))
                points += paragraph.text.codePointCount(0, paragraph.text.length)
                bytes += paragraph.text.toByteArray(Charsets.UTF_8).size
                require(paragraph.references.isNotEmpty() && paragraph.references.size <= LocalEvidenceController.MAX_SOURCES &&
                    paragraph.references.toSet().size == paragraph.references.size && paragraph.references.all { it in allowed })
                refs += paragraph.references.size
                require(points <= MAX_ANSWER_POINTS && bytes <= MAX_ANSWER_BYTES && refs <= LocalEvidenceController.MAX_SOURCES)
            }
            return response.paragraphs
        }
    }
}

/** Offline fallback view of the confirmed selection, not semantic Q&A or an event timeline.
 * Keep text exactly equal to each quote so a full valid selection retains its byte/point budget.
 * Sorting a copy preserves the confirmed candidate order and all original reference identities.
 */
internal object LocalEvidenceTimeline {
    fun generate(request: LocalEvidenceAnswerRequest): LocalEvidenceAnswerResponse {
        check(!request.cancelled)
        val paragraphs = request.packet.citations.sortedWith(compareBy<LocalEvidenceCitation>(
            { it.capturedAtMillis }, { it.reference.source.sourceId })).map {
            LocalEvidenceAnswerParagraph(it.reference.quote, listOf(it.reference))
        }
        return LocalEvidenceAnswerResponse(request.id, request.packet.selectionId, paragraphs)
    }
}
