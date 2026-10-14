package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.StoredEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OriginalSearchState internal constructor(val input: OriginalSearchInput, val stage: OriginalSearchStage,
    val hits: List<OriginalSearchHit> = emptyList(), val message: String? = null,
    internal val entries: List<StoredEntry>? = null, internal val generation: Long = 0) {
    override fun toString() = "OriginalSearchState(stage=$stage, redacted)"
}

/** One composition owner. Fresh source/input getters also fence changes before recomposition. */
class LocalOriginalSearchController internal constructor(
    private val currentSource: () -> LocalRecordUiState?,
    private val currentInput: () -> OriginalSearchInput,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val matcher: suspend (List<StoredEntry>, OriginalSearchInput) -> OriginalSearchMatches = LocalOriginalSearchMatcher::match,
) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var active = false
    private var closed = false
    private var generation = 0L
    private var job: Job? = null
    private var cache: OriginalSearchState? = null
    private val mutableState = MutableStateFlow(OriginalSearchState(OriginalSearchInput(), OriginalSearchStage.WAITING))
    val state = mutableState.asStateFlow()

    private fun usable(local: LocalRecordUiState?) = local != null && local.ready && !local.opening && local.error == null
    private fun owns(value: OriginalSearchState): Boolean {
        val local = currentSource()
        return !closed && active && value.generation == generation && usable(local) &&
            local?.entries === value.entries && currentInput() == value.input
    }

    fun visible(local: LocalRecordUiState?, input: OriginalSearchInput): OriginalSearchState = synchronized(lock) {
        val value = mutableState.value
        if (usable(local) && local?.entries === value.entries && value.input == input && owns(value)) value
        else OriginalSearchState(input, OriginalSearchStage.WAITING)
    }

    /** Called after a committed render; compare snapshot identity rather than List.equals. */
    fun sync(enabled: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        if (!enabled) { pauseLocked(); return@synchronized }
        val local = currentSource()
        val input = currentInput()
        val value = mutableState.value
        if (active && value.input == input && value.entries === local?.entries &&
            ((usable(local) && value.stage != OriginalSearchStage.WAITING &&
                (value.stage != OriginalSearchStage.RUNNING || job?.isActive == true)) ||
                (!usable(local) && value.stage == OriginalSearchStage.WAITING))) return@synchronized
        active = true
        generation++
        job?.cancel(); job = null
        if (!usable(local)) {
            cache = null
            mutableState.value = OriginalSearchState(input, OriginalSearchStage.WAITING, entries = local?.entries, generation = generation)
            return@synchronized
        }
        val entries = checkNotNull(local).entries
        // Preserve immediate manual browsing when no lookup is requested.
        if (input.query.isEmpty() && input.from.isEmpty() && input.through.isEmpty()) {
            val browse = OriginalSearchState(input, OriginalSearchStage.BROWSE, entries = entries, generation = generation)
            cache = browse
            mutableState.value = browse
            return@synchronized
        }
        val saved = cache?.takeIf { it.entries === entries && it.input == input }
        if (saved != null) {
            mutableState.value = OriginalSearchState(input, saved.stage, saved.hits, saved.message, entries, generation)
            return@synchronized
        }
        cache = null
        val request = OriginalSearchState(input, OriginalSearchStage.RUNNING, entries = entries, generation = generation)
        mutableState.value = request
        job = scope.launch {
            val result = try { matcher(entries, input) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { OriginalSearchMatches(OriginalSearchStage.FAILED, message = "本机查找暂不可用，请修改条件后重试。") }
            synchronized(lock) {
                if (owns(request)) {
                    val completed = OriginalSearchState(input, result.stage, result.hits, result.message, entries, request.generation)
                    job = null
                    cache = completed
                    mutableState.value = completed
                } else if (!closed && active && generation == request.generation && mutableState.value === request) {
                    // A fresh input/source can invalidate this worker before the next render.
                    // Wake that render to sync again; never strand a completed worker in RUNNING.
                    job = null
                    cache = null
                    mutableState.value = OriginalSearchState(input, OriginalSearchStage.WAITING,
                        entries = entries, generation = request.generation)
                }
            }
        }
    }

    fun canOpen(value: OriginalSearchState, hit: OriginalSearchHit): Boolean = synchronized(lock) {
        owns(value) && value.stage == OriginalSearchStage.READY && value.hits.any { it === hit } &&
            currentSource()?.entries?.any { it.id == hit.entry.id && it.currentRevision == hit.entry.currentRevision } == true
    }

    fun pause() = synchronized(lock) { pauseLocked() }
    private fun pauseLocked() {
        if (!active) return
        active = false
        generation++
        job?.cancel(); job = null
    }

    fun clear() = synchronized(lock) {
        pauseLocked()
        cache = null
        generation++
        mutableState.value = OriginalSearchState(OriginalSearchInput(), OriginalSearchStage.WAITING, generation = generation)
    }

    override fun close() = synchronized(lock) {
        closed = true
        active = false
        generation++
        job?.cancel(); job = null
        cache = null
        mutableState.value = OriginalSearchState(OriginalSearchInput(), OriginalSearchStage.WAITING, generation = generation)
        scope.cancel()
    }
}
