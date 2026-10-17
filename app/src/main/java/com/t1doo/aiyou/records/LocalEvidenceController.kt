package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.LocalCandidates
import com.t1doo.aiyou.model.OriginalTextReference
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.SourceVersion
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LocalEvidenceStage { WAITING, RUNNING, READY, EMPTY, INVALID, FAILED, STALE }

/** A local quotation candidate, never an answer or permission to send to a model. */
class LocalEvidenceCard internal constructor(val reference: OriginalTextReference,
    val capturedAtMillis: Long, val captureTimeZone: String, internal val hit: OriginalSearchHit) {
    override fun toString() = "LocalEvidenceCard(redacted)"
}

class LocalEvidenceState internal constructor(val stage: LocalEvidenceStage,
    val question: String = "", cards: List<LocalEvidenceCard> = emptyList(), val limited: Boolean = false,
    val message: String? = null, internal val source: OriginalSearchState? = null,
    internal val generation: Long = 0) {
    val cards: List<LocalEvidenceCard> = Collections.unmodifiableList(ArrayList(cards))
    override fun toString() = "LocalEvidenceState(stage=$stage, redacted)"
}

/** Builds a bounded view of the existing ordered hits; it never runs a second retrieval. */
internal fun localEvidenceCandidates(source: OriginalSearchState, generation: Long): LocalEvidenceState {
    val question = source.input.query
    if (question.length > 1024 || question.isBlank() || !LocalCandidates.validUnicode(question) ||
        question.codePointCount(0, question.length) > LocalEvidenceController.MAX_QUESTION_POINTS ||
        question.toByteArray(Charsets.UTF_8).size > LocalEvidenceController.MAX_QUESTION_BYTES)
        return LocalEvidenceState(LocalEvidenceStage.INVALID, message = "请输入问题中的关键词或精确片段，最多 512 个字符。",
            source = source, generation = generation)
    require(source.stage == OriginalSearchStage.READY && source.hits.size <= 1000)
    val cards = ArrayList<LocalEvidenceCard>()
    val seen = HashSet<String>()
    var points = 0
    var bytes = 0
    var limited = false
    for (hit in source.hits) {
        // Ordinary manual search still shows AI_NONE. It never enters this candidate packet.
        if (hit.entry.policy == ProcessingPolicy.AI_NONE || !seen.add(hit.entry.id) || hit.quote.isEmpty()) continue
        if (cards.size == LocalEvidenceController.MAX_SOURCES) { limited = true; break }
        val original = hit.entry.originalText
        require(hit.entry.currentRevision > 0 && LocalCandidates.validUnicode(original))
        val count = original.codePointCount(0, original.length)
        require(hit.startCodePoint >= 0 && hit.endCodePoint in (hit.startCodePoint + 1)..count)
        val size = hit.quote.codePointCount(0, hit.quote.length)
        require(size <= 240 && size == hit.endCodePoint - hit.startCodePoint && hit.quote == original.substring(
            original.offsetByCodePoints(0, hit.startCodePoint), original.offsetByCodePoints(0, hit.endCodePoint)))
        val encoded = hit.quote.toByteArray(Charsets.UTF_8).size
        if (points + size > LocalEvidenceController.MAX_QUOTE_POINTS || bytes + encoded > LocalEvidenceController.MAX_QUOTE_BYTES) {
            limited = true; break
        }
        points += size; bytes += encoded
        cards += LocalEvidenceCard(OriginalTextReference(SourceVersion(hit.entry.id, hit.entry.currentRevision.toLong()),
            hit.startCodePoint, hit.endCodePoint, hit.quote), hit.entry.capturedAtMillis, hit.entry.captureTimeZone, hit)
    }
    return LocalEvidenceState(if (cards.isEmpty()) LocalEvidenceStage.EMPTY else LocalEvidenceStage.READY,
        question, cards, limited, source = source, generation = generation)
}

/** Composition-owned, memory-only local evidence. Existing search proof owns SQL and navigation. */
class LocalEvidenceController internal constructor(
    private val currentSearch: () -> OriginalSearchState,
    private val onInvalidated: () -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val prepare: suspend (OriginalSearchState, Long) -> LocalEvidenceState = ::localEvidenceCandidates,
) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val waiting = LocalEvidenceState(LocalEvidenceStage.WAITING)
    private val mutable = MutableStateFlow(waiting)
    val state = mutable.asStateFlow()
    private var active = false
    private var closed = false
    private var generation = 0L
    private var job: Job? = null
    private fun owns(value: LocalEvidenceState) = !closed && active && value.generation == generation &&
        value.source != null && currentSearch() === value.source

    fun visible(source: OriginalSearchState): LocalEvidenceState = synchronized(lock) {
        mutable.value.takeIf { it.source === source && owns(it) } ?: when (source.stage) {
            OriginalSearchStage.FAILED, OriginalSearchStage.INVALID -> LocalEvidenceState(LocalEvidenceStage.FAILED,
                message = "当前原文查找未成功，请先修正条件或重试本机读取。没有生成出处候选。")
            OriginalSearchStage.BROWSE -> LocalEvidenceState(LocalEvidenceStage.INVALID,
                message = "请输入问题中的关键词或精确片段，再选取本机出处候选。")
            else -> waiting
        }
    }
    fun canOpen(value: LocalEvidenceState, card: LocalEvidenceCard): Boolean = synchronized(lock) {
        mutable.value === value && owns(value) && value.stage == LocalEvidenceStage.READY &&
            value.cards.any { it === card } && card.hit.entry.policy != ProcessingPolicy.AI_NONE &&
            value.source?.hits?.any { it === card.hit } == true
    }
    fun sync(enabled: Boolean, source: OriginalSearchState) = synchronized(lock) {
        if (closed) return@synchronized
        if (!enabled || source.stage != OriginalSearchStage.READY || currentSearch() !== source) {
            resetLocked(); return@synchronized
        }
        if (active && mutable.value.source === source) return@synchronized
        active = true; generation++
        job?.cancel()
        val request = LocalEvidenceState(LocalEvidenceStage.RUNNING, source = source, generation = generation)
        mutable.value = request
        job = scope.launch {
            val result = try {
                require(source.owner != null && source.proof != null)
                val originals = source.proof.entries.associateBy { it.id }
                require(source.hits.size <= 1000 && originals.size <= 1000 && originals.size == source.proof.entries.size &&
                    source.hits.all { originals[it.entry.id] == it.entry })
                prepare(source, request.generation)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LocalEvidenceState(LocalEvidenceStage.FAILED,
                message = "本机出处暂不可读取，未显示不完整候选。请重试本机读取。", source = source, generation = request.generation) }
            fun publish() = synchronized(lock) {
                if (mutable.value === request && owns(request)) mutable.value = result
            }
            if (result.stage == LocalEvidenceStage.INVALID || result.stage == LocalEvidenceStage.FAILED) { publish(); return@launch }
            val accepted = try { checkNotNull(source.owner).publishCurrent(checkNotNull(source.proof), ::publish) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (!accepted) {
                synchronized(lock) { if (owns(request)) mutable.value = LocalEvidenceState(LocalEvidenceStage.STALE,
                    message = "来源已变化，旧候选已失效，请重新查找。", source = source, generation = request.generation) }
                onInvalidated()
            }
        }
    }
    private fun resetLocked() {
        if (!active && mutable.value === waiting) return
        active = false; generation++; job?.cancel(); job = null; mutable.value = waiting
    }
    fun clear() = synchronized(lock) { resetLocked() }
    override fun close() = synchronized(lock) { closed = true; resetLocked(); scope.cancel() }

    companion object {
        const val MAX_SOURCES = 5
        const val MAX_QUESTION_POINTS = 512
        const val MAX_QUESTION_BYTES = 2048
        const val MAX_QUOTE_POINTS = 1200
        const val MAX_QUOTE_BYTES = 4800
    }
}
