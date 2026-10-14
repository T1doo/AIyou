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
import kotlinx.coroutines.flow.update

class OriginalSearchState internal constructor(val input: OriginalSearchInput, val stage: OriginalSearchStage,
    val hits: List<OriginalSearchHit> = emptyList(), val message: String? = null,
    internal val entries: List<StoredEntry>? = null, internal val generation: Long = 0,
    internal val proof: com.t1doo.aiyou.domain.LocalSearchSnapshot? = null,
    internal val owner: com.t1doo.aiyou.domain.LocalSearchRepository? = null,
    internal val indexEpoch: Long? = null) {
    override fun toString() = "OriginalSearchState(stage=$stage, redacted)"
}

/** One composition owner. Fresh source/input getters also fence changes before recomposition. */
class LocalOriginalSearchController internal constructor(
    private val currentSource: () -> LocalRecordUiState?,
    private val currentInput: () -> OriginalSearchInput,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val stateFactory: (OriginalSearchState) -> MutableStateFlow<OriginalSearchState> = { MutableStateFlow(it) },
    private val onInvalidated: () -> Unit = {},
    private val matcher: suspend (List<StoredEntry>, OriginalSearchInput) -> OriginalSearchMatches = LocalOriginalSearchMatcher::match,
) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var active = false
    private var closed = false
    private var generation = 0L
    private var job: Job? = null
    private var cache: OriginalSearchState? = null
    private val mutableState = stateFactory(OriginalSearchState(OriginalSearchInput(), OriginalSearchStage.WAITING))
    val state = mutableState.asStateFlow()

    private fun usable(local: LocalRecordUiState?) = local != null && local.ready && !local.opening && local.error == null
    private fun owns(value: OriginalSearchState): Boolean {
        val local = currentSource()
        return !closed && active && value.generation == generation && usable(local) &&
            local?.entries === value.entries && currentInput() == value.input &&
            local?.localSearch === value.owner && local?.localSearchEpoch == value.indexEpoch &&
            (value.input.tag == null || (if (local?.manualTagsAvailable == true) local.manualTags else null) === value.input.tagSnapshot)
    }

    fun visible(local: LocalRecordUiState?, input: OriginalSearchInput): OriginalSearchState = synchronized(lock) {
        val value = mutableState.value
        if (usable(local) && local?.entries === value.entries && value.input == input && owns(value)) value
        else OriginalSearchState(input, OriginalSearchStage.WAITING)
    }

    /** Test observer only: booleans and enums, never query, dates or original text. */
    internal fun visibilityDiagnostics(local: LocalRecordUiState?, input: OriginalSearchInput): String = synchronized(lock) {
        val value = mutableState.value
        val fresh = currentSource()
        val freshInput = currentInput()
        "raw=${value.stage}/${value.generation}, owner=$generation, active=$active, closed=$closed, " +
            "renderUsable=${usable(local)}, freshUsable=${usable(fresh)}, " +
            "renderSource=${local?.entries === value.entries}, freshSource=${fresh?.entries === value.entries}, " +
            "renderInput=${input == value.input}, freshInput=${freshInput == value.input}, " +
            "renderQuery=${input.query == value.input.query}, freshQuery=${freshInput.query == value.input.query}, " +
            "renderOwnership=${input.ownership == value.input.ownership}, freshOwnership=${freshInput.ownership == value.input.ownership}"
    }

    /** Called after a committed render; compare snapshot identity rather than List.equals. */
    fun sync(enabled: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        if (!enabled) { pauseLocked(); return@synchronized }
        val local = currentSource()
        val input = currentInput()
        val value = mutableState.value
        if (active && value.input == input && value.input.tagSnapshot === input.tagSnapshot && value.entries === local?.entries &&
            value.indexEpoch == local?.localSearchEpoch && value.owner === local?.localSearch &&
            ((usable(local) && value.stage != OriginalSearchStage.WAITING &&
                (value.stage != OriginalSearchStage.RUNNING || job?.isActive == true)) ||
                (!usable(local) && value.stage == OriginalSearchStage.WAITING))) return@synchronized
        active = true
        generation++
        job?.cancel(); job = null
        if (!usable(local)) {
            cache = null
            mutableState.value = OriginalSearchState(input, OriginalSearchStage.WAITING, entries = local?.entries,
                generation = generation, owner = local?.localSearch, indexEpoch = local?.localSearchEpoch)
            return@synchronized
        }
        val entries = checkNotNull(local).entries
        // Preserve immediate manual browsing when no lookup is requested.
        if (input.query.isEmpty() && input.from.isEmpty() && input.through.isEmpty() && input.tag == null) {
            val browse = OriginalSearchState(input, OriginalSearchStage.BROWSE, entries = entries, generation = generation,
                owner = local.localSearch, indexEpoch = local.localSearchEpoch)
            cache = browse
            mutableState.value = browse
            return@synchronized
        }
        val saved = cache?.takeIf { local.localSearch == null && it.proof == null && it.entries === entries &&
            it.input == input && it.input.tagSnapshot === input.tagSnapshot }
        if (saved != null) {
            mutableState.value = OriginalSearchState(input, saved.stage, saved.hits, saved.message, entries, generation)
            return@synchronized
        }
        cache = null
        val request = OriginalSearchState(input, OriginalSearchStage.RUNNING, entries = entries, generation = generation,
            owner = local.localSearch, indexEpoch = local.localSearchEpoch)
        mutableState.value = request
        job = scope.launch {
            var completedByRequest: OriginalSearchState? = null
            val result = try { matcher(entries, input) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { OriginalSearchMatches(OriginalSearchStage.FAILED, message = "本机查找暂不可用，请修改条件后重试。") }
            fun publishResult() = synchronized(lock) {
                if (owns(request)) {
                    val completed = OriginalSearchState(input, result.stage, result.hits, result.message, entries, request.generation,
                        result.proof, request.owner, request.indexEpoch)
                    completedByRequest = completed
                    mutableState.update { current -> if (current === request && owns(request)) completed else current }
                    if (mutableState.value === completed) { job = null; cache = completed }
                } else if (!closed && active && generation == request.generation && mutableState.value === request) {
                    // A fresh input/source can invalidate this worker before the next render.
                    // Wake that render to sync again; never strand a completed worker in RUNNING.
                    job = null
                    cache = null
                    mutableState.value = OriginalSearchState(input, OriginalSearchStage.WAITING,
                        entries = entries, generation = request.generation, owner = request.owner, indexEpoch = request.indexEpoch)
                }
            }
            var cancellation: CancellationException? = null
            val published = try {
                if (result.proof != null && result.owner !== request.owner) false
                else if (result.proof != null && result.owner != null)
                    result.owner.publishCurrent(result.proof, ::publishResult)
                else { publishResult(); true }
            } catch (cancelled: CancellationException) { cancellation = cancelled; false } catch (_: Exception) { false }
            if (!published || result.stage == OriginalSearchStage.WAITING) {
                synchronized(lock) {
                    if (owns(request) && (mutableState.value === request ||
                        completedByRequest != null && mutableState.value === completedByRequest)) {
                        job = null; cache = null
                        mutableState.value = OriginalSearchState(input, OriginalSearchStage.WAITING,
                            entries = entries, generation = request.generation, owner = request.owner, indexEpoch = request.indexEpoch)
                    }
                }
                onInvalidated()
            }
            cancellation?.let { throw it }
        }
    }

    fun canOpen(value: OriginalSearchState, hit: OriginalSearchHit): Boolean = synchronized(lock) {
        owns(value) && (value.input.tag == null || currentSource()?.manualTagsAvailable == true) && value.stage == OriginalSearchStage.READY && value.hits.any { it === hit } &&
            currentSource()?.entries?.any { it.id == hit.entry.id && it.currentRevision == hit.entry.currentRevision } == true
    }

    /** Production executes actual location publication inside the IO-held source/epoch lease. */
    fun open(value: OriginalSearchState, hit: OriginalSearchHit, rollback: () -> Unit = {}, publish: () -> Unit) {
        if (!canOpen(value, hit)) return
        val owner = value.owner
        val proof = value.proof
        if (owner == null || proof == null) { if (canOpen(value, hit)) publish(); return }
        scope.launch(Dispatchers.IO) {
            var opened = false
            var cancellation: CancellationException? = null
            val resolved = try {
                owner.resolve(proof, hit.entry.id) { current ->
                    synchronized(lock) {
                        if (current == hit.entry && canOpen(value, hit)) { publish(); opened = true }
                    }
                }
            } catch (cancelled: CancellationException) { cancellation = cancelled; false } catch (_: Exception) { false }
            if (!resolved || !opened) {
                synchronized(lock) {
                    // Root rollback uses the exact location/nav publication token;
                    // it cannot retire a later navigation or a new composition.
                    if (opened) rollback()
                    if (owns(value)) {
                        cache = null; job = null
                        mutableState.value = OriginalSearchState(value.input, OriginalSearchStage.WAITING,
                            entries = value.entries, generation = value.generation, owner = value.owner, indexEpoch = value.indexEpoch)
                    }
                }
                onInvalidated()
            }
            cancellation?.let { throw it }
        }
    }

    fun pause() = synchronized(lock) { pauseLocked() }
    /** Called synchronously before a real UI input commit or a tag/mode change. */
    fun invalidateInput() = synchronized(lock) {
        if (closed) return@synchronized
        pauseLocked()
        cache = null
        generation++
        mutableState.value = OriginalSearchState(OriginalSearchInput(), OriginalSearchStage.WAITING, generation = generation)
    }
    fun changeInput(change: () -> Unit) = synchronized(lock) { invalidateInput(); if (!closed) change() }
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
