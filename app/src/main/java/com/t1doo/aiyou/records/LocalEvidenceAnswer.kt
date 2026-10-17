package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.LocalCandidates
import com.t1doo.aiyou.model.OriginalTextReference
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Test-only in-memory provider port. Never implement with network, files, secrets or external effects.
 * The production Activity supplies no fixture. A real multi-source sender needs a separate authority.
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

enum class LocalEvidenceAnswerStage { WAITING, RUNNING, READY, NO_EVIDENCE, FAILED, CANCELLED, STALE, UNAVAILABLE }

class LocalEvidenceAnswerState internal constructor(val stage: LocalEvidenceAnswerStage,
    val requestId: String? = null, paragraphs: List<LocalEvidenceAnswerParagraph> = emptyList(),
    internal val evidence: LocalEvidenceState? = null) {
    val paragraphs: List<LocalEvidenceAnswerParagraph> = Collections.unmodifiableList(ArrayList(paragraphs))
    override fun toString() = "LocalEvidenceAnswerState(stage=$stage, redacted)"
}

/** Reuses the existing selection/receipt and SQL proof. No second retrieval or confirmation layer. */
class LocalEvidenceAnswerController internal constructor(
    private val evidence: LocalEvidenceController,
    private val currentEvidence: () -> LocalEvidenceState,
    private val ownsSession: () -> Boolean,
    private val fixture: SyntheticEvidenceAnswerProvider? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val waiting = LocalEvidenceAnswerState(if (fixture == null) LocalEvidenceAnswerStage.UNAVAILABLE else LocalEvidenceAnswerStage.WAITING)
    private val mutable = MutableStateFlow(waiting)
    val state = mutable.asStateFlow()
    val available get() = fixture != null
    private class Run(val view: LocalEvidenceState) {
        val id = UUID.randomUUID().toString()
        var request: LocalEvidenceAnswerRequest? = null
        val stop = AtomicBoolean()
        var job: Job? = null
        fun cancel() { stop.set(true); request?.cancel(); job?.cancel() }
    }
    private var closed = false
    private var run: Run? = null
    private fun owns(value: Run) = !closed && run === value && !value.stop.get() && ownsSession() &&
        currentEvidence() === value.view && value.view.selection.stage == LocalEvidenceSelectionStage.CONFIRMED

    fun visible(): LocalEvidenceAnswerState = synchronized(lock) {
        val value = mutable.value
        if (value.evidence == null) value
        else if (run?.let { owns(it) } == true && value.evidence === run?.view) value
        else LocalEvidenceAnswerState(LocalEvidenceAnswerStage.STALE)
    }
    fun sync() = synchronized(lock) {
        if (run?.let { !owns(it) } == true) {
            run?.cancel(); run = null; mutable.value = LocalEvidenceAnswerState(LocalEvidenceAnswerStage.STALE)
        }
    }
    fun ask(value: LocalEvidenceState) = synchronized(lock) {
        if (closed || fixture == null || !ownsSession() || currentEvidence() !== value ||
            value.stage != LocalEvidenceStage.READY || value.selection.stage != LocalEvidenceSelectionStage.CONFIRMED ||
            value.selection.confirmation == null || value.selection.cards.isEmpty()) return@synchronized
        if (run?.let { owns(it) } == true && mutable.value.stage == LocalEvidenceAnswerStage.RUNNING) return@synchronized
        run?.cancel()
        val next = Run(value); run = next
        mutable.value = LocalEvidenceAnswerState(LocalEvidenceAnswerStage.RUNNING, next.id, evidence = value)
        next.job = scope.launch {
            try {
                var packet: LocalEvidencePacket? = null
                if (!evidence.inspectConfirmed(value, value.selection.confirmation, next.stop::get) { packet = it }) {
                    finish(next, LocalEvidenceAnswerStage.STALE); return@launch
                }
                val request = synchronized(lock) {
                    if (!owns(next)) null else LocalEvidenceAnswerRequest(next.id, checkNotNull(packet)).also { next.request = it }
                } ?: return@launch
                // Only synthetic, in-memory processing outside all SQL transactions and owner locks.
                val response = fixture.answer(request)
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
                    next.cancel(); run = null; mutable.value = LocalEvidenceAnswerState(LocalEvidenceAnswerStage.CANCELLED, next.id)
                } }
                throw cancelled
            } catch (_: Exception) { finish(next, LocalEvidenceAnswerStage.FAILED) }
        }
    }
    private fun finish(value: Run, stage: LocalEvidenceAnswerStage, paragraphs: List<LocalEvidenceAnswerParagraph> = emptyList()) = synchronized(lock) {
        if (owns(value)) mutable.value = LocalEvidenceAnswerState(stage, value.id, paragraphs, value.view)
        else if (!closed && run === value && !value.stop.get()) {
            value.cancel(); run = null; mutable.value = LocalEvidenceAnswerState(LocalEvidenceAnswerStage.STALE)
        }
    }
    fun cancel() = synchronized(lock) {
        val old = run ?: return@synchronized
        old.cancel(); run = null
        mutable.value = LocalEvidenceAnswerState(LocalEvidenceAnswerStage.CANCELLED, old.id)
    }
    /** The source card is still owned by the original evidence view; its existing search.open resolves SQL again. */
    fun sourceFor(value: LocalEvidenceAnswerState, reference: OriginalTextReference): LocalEvidenceCard? = synchronized(lock) {
        val current = run ?: return@synchronized null
        if (mutable.value !== value || !owns(current) || value.stage != LocalEvidenceAnswerStage.READY ||
            value.paragraphs.none { reference in it.references }) return@synchronized null
        current.view.selection.cards.singleOrNull { it.reference == reference && evidence.canOpen(current.view, it) }
    }
    override fun close() = synchronized(lock) { closed = true; run?.cancel(); run = null; mutable.value = waiting; scope.cancel() }

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
