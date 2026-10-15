package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

sealed interface LocalCandidateIntent {
    data class Generate(val request: LocalCandidateStart) : LocalCandidateIntent
    data class Review(val request: ExcerptReviewRequest) : LocalCandidateIntent
    data class Cancel(val request: CandidateCancelRequest) : LocalCandidateIntent
}
data class ExcerptConfirmation(val batchId: String, val version: Long, val itemId: String)
data class LocalCandidateUiState(val session: Long, val source: SourceVersion, val sourceHash: String,
    val loading: Boolean = true, val busy: Boolean = false, val computing: Boolean = false,
    val batches: List<LocalCandidateBatch> = emptyList(), val error: String? = null, val notice: String? = null,
    val pending: LocalCandidateIntent? = null, val confirmation: ExcerptConfirmation? = null, val request: Long = 0) {
    override fun toString() = "LocalCandidateUiState(session=" + session + ", redacted)"
}

/** UI sessions never replace database source/CAS authority. Computation borrows no records owner. */
class LocalCandidateController(
    private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val extract: (LocalCandidateJob) -> ExcerptDocument = LocalExcerptRules::extract,
) {
    private val sessions = AtomicLong()
    @Volatile private var disposed = false
    @Volatile private var worker: Job? = null
    private fun usable(ui: LocalCandidateUiState): Boolean {
        val local = current()
        return !disposed && local.ready && !local.opening && local.error == null &&
            local.entries.any { it.id == ui.source.sourceId && it.currentRevision.toLong() == ui.source.revision &&
                it.policy != ProcessingPolicy.AI_NONE && OriginalExports.hash(it.originalText) == ui.sourceHash }
    }
    private fun owns(session: Long, request: Long? = null): Boolean =
        current().candidates?.let { it.session == session && (request == null || it.request == request) && usable(it) } == true
    private fun update(session: Long, transform: (LocalCandidateUiState) -> LocalCandidateUiState) =
        publish { local ->
            val ui = local.candidates
            if (disposed || ui?.session != session) local else local.copy(candidates = transform(ui))
        }

    fun open(id: String, revision: Int) {
        val local = current()
        if (disposed || !local.ready || local.opening || local.error != null || !local.supportsCandidates) return
        val entry = local.entries.singleOrNull { it.id == id && it.currentRevision == revision && it.policy != ProcessingPolicy.AI_NONE } ?: return
        worker?.cancel()
        val ui = LocalCandidateUiState(sessions.incrementAndGet(), SourceVersion(id, revision.toLong()), OriginalExports.hash(entry.originalText))
        publish { it.copy(candidates = ui) }
        refresh(ui.session)
    }
    fun close(session: Long) {
        if (disposed || current().candidates?.session != session) return
        worker?.cancel()
        publish { if (it.candidates?.session == session) it.copy(candidates = null) else it }
    }
    fun refresh(session: Long) {
        val ui = current().candidates ?: return
        if (!owns(session) || ui.busy || ui.computing || ui.pending != null) return
        val request = ui.request + 1
        update(session) { it.copy(loading = true, error = null, confirmation = null, request = request) }
        if (!enqueue { store ->
            if (!owns(session, request)) return@enqueue
            try {
                val values = checkNotNull(store.localCandidates).batches(ui.source)
                if (owns(session, request)) update(session) { it.copy(loading = false, batches = values, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(session, request, failure) }
        }) failed(session, request, IllegalStateException())
    }
    fun generate(session: Long) {
        val ui = current().candidates ?: return
        if (!owns(session) || ui.loading || ui.busy || ui.computing || ui.pending != null ||
            ui.batches.any { it.state == LocalCandidateState.RUNNING || it.state == LocalCandidateState.READY }) return
        submit(session, LocalCandidateIntent.Generate(LocalCandidateStart(UUID.randomUUID().toString(), ui.source, clock.millis())))
    }
    fun requestAccept(session: Long, batchId: String, version: Long, itemId: String) {
        val ui = current().candidates ?: return
        if (!actionable(ui, session)) return
        val batch = ui.batches.singleOrNull { it.id == batchId && it.version == version && it.state == LocalCandidateState.READY } ?: return
        if (batch.items.none { it.id == itemId && it.review == ExcerptReview.PROPOSED }) return
        update(session) { it.copy(confirmation = ExcerptConfirmation(batchId, version, itemId), error = null, notice = null) }
    }
    fun cancelConfirmation(session: Long, expected: ExcerptConfirmation) {
        if (owns(session)) update(session) { if (it.busy || it.confirmation != expected) it else it.copy(confirmation = null) }
    }
    fun confirm(session: Long, expected: ExcerptConfirmation) {
        val ui = current().candidates ?: return
        val target = ui.confirmation ?: return
        if (target != expected) return
        review(session, target.batchId, target.version, target.itemId, ExcerptReview.ACCEPTED)
    }
    fun reject(session: Long, batchId: String, version: Long, itemId: String) =
        review(session, batchId, version, itemId, ExcerptReview.REJECTED)
    private fun review(session: Long, batchId: String, version: Long, itemId: String, decision: ExcerptReview) {
        val ui = current().candidates ?: return
        if (!actionable(ui, session)) return
        val batch = ui.batches.singleOrNull { it.id == batchId && it.version == version && it.state == LocalCandidateState.READY } ?: return
        if (batch.items.none { it.id == itemId && it.review == ExcerptReview.PROPOSED }) return
        submit(session, LocalCandidateIntent.Review(ExcerptReviewRequest(batch.id, batch.source, batch.sourceHash, batch.generation,
            batch.version, itemId, decision, UUID.randomUUID().toString(), clock.millis())))
    }
    fun cancelBatch(session: Long, batchId: String, version: Long) {
        val ui = current().candidates ?: return
        if (!owns(session) || ui.loading || ui.busy || ui.pending != null && ui.pending !is LocalCandidateIntent.Generate) return
        val batch = ui.batches.singleOrNull { it.id == batchId && it.version == version } ?: return
        if (batch.state !in setOf(LocalCandidateState.RUNNING, LocalCandidateState.READY) ||
            batch.items.any { it.review != ExcerptReview.PROPOSED }) return
        worker?.cancel()
        submit(session, LocalCandidateIntent.Cancel(CandidateCancelRequest(batch.id, batch.source, batch.sourceHash, batch.generation,
            batch.version, UUID.randomUUID().toString(), clock.millis())))
    }
    fun retry(session: Long, expectedRequest: Long) {
        val ui = current().candidates ?: return
        if (!owns(session) || ui.request != expectedRequest || ui.busy || ui.computing) return
        ui.pending?.let { submit(session, it) } ?: refresh(session)
    }
    private fun actionable(ui: LocalCandidateUiState, session: Long) =
        owns(session) && !ui.loading && !ui.busy && !ui.computing && ui.pending == null
    fun sourceReference(session: Long, batchId: String, version: Long, itemId: String): OriginalTextReference? {
        val ui = current().candidates ?: return null
        if (!actionable(ui, session)) return null
        val batch = ui.batches.singleOrNull { it.id == batchId && it.version == version && it.state == LocalCandidateState.READY } ?: return null
        val item = batch.items.singleOrNull { it.id == itemId } ?: return null
        return OriginalTextReference(batch.source, item.proposal.start, item.proposal.end, item.proposal.quote)
    }

    private fun submit(session: Long, intent: LocalCandidateIntent) {
        val ui = current().candidates ?: return
        val request = ui.request + 1
        update(session) { it.copy(busy = true, computing = false, pending = intent, error = null, notice = null, request = request) }
        if (!enqueue { store ->
            if (!owns(session, request)) return@enqueue
            try {
                val repository = checkNotNull(store.localCandidates)
                when (intent) {
                    is LocalCandidateIntent.Generate -> {
                        val job = repository.begin(intent.request)
                        val values = repository.batches(ui.source)
                        if (!owns(session, request)) return@enqueue
                        update(session) { it.copy(busy = false, loading = false, computing = true, batches = values) }
                        worker = scope.launch(Dispatchers.Default) {
                            try {
                                if (!owns(session, request)) return@launch
                                val document = extract(job)
                                ensureActive()
                                if (owns(session, request) && !enqueue complete@{ currentStore ->
                                    if (!owns(session, request)) return@complete
                                    try {
                                        val owner = checkNotNull(currentStore.localCandidates)
                                        val result = owner.complete(job, document)
                                        acknowledge(session, request, ui.source, owner, result, "候选已保存在本机，尚未采纳。")
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { failed(session, request, failure) }
                                }) failed(session, request, IllegalStateException())
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { failed(session, request, failure) }
                        }
                    }
                    is LocalCandidateIntent.Review -> acknowledge(session, request, ui.source, repository,
                        repository.review(intent.request), if (intent.request.decision == ExcerptReview.ACCEPTED)
                            "已采纳原文摘录。没有创建事实、标签或待办。" else "已拒绝摘录，原文未改变。")
                    is LocalCandidateIntent.Cancel -> acknowledge(session, request, ui.source, repository,
                        repository.cancel(intent.request), "本批候选已取消。")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(session, request, failure) }
        }) failed(session, request, IllegalStateException())
    }
    private fun acknowledge(session: Long, request: Long, source: SourceVersion, repository: LocalCandidateRepository,
        result: LocalCandidateBatch, notice: String) {
        val all = repository.batches(source)
        check(all.singleOrNull { it.id == result.id }?.let { it.version == result.version && it.state == result.state } == true)
        if (owns(session, request)) update(session) { it.copy(loading = false, busy = false, computing = false,
            batches = all, pending = null, confirmation = null, error = null, notice = notice) }
    }
    private fun failed(session: Long, request: Long, failure: Exception) {
        if (!owns(session, request)) return
        val conflict = failure is LocalCandidateConflict
        update(session) { it.copy(loading = false, busy = false, computing = false, batches = emptyList(), confirmation = null,
            pending = if (conflict) null else it.pending, notice = null,
            error = if (conflict) "来源或候选已变化，请重新读取；旧请求不能采纳。"
                else "候选读取或保存状态尚未确认，请重试；原文已保留。") }
    }
    fun dispose() { disposed = true; worker?.cancel() }
}
