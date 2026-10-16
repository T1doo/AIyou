package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

data class LocalProcessingUiState(val session: Long, val source: SourceVersion, val sourceHash: String,
    val snapshot: LocalProcessingSnapshot? = null, val busy: Boolean = false,
    val confirmation: LocalProcessingChange? = null, val pending: LocalProcessingChange? = null,
    val request: Long = 0, val error: String? = null, val notice: String? = null) {
    override fun toString() = "LocalProcessingUiState(redacted)"
}

internal fun LocalRecordUiState.hasLocalProcessingProof(source: SourceVersion): Boolean =
    !supportsLocalProcessing || localProcessingAvailable && localProcessingVersions[source.sourceId]?.source == source

/** Explicit per-record choices. A lost acknowledgement retains the exact operation identity. */
class LocalProcessingController(private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean,
    private val retireDerived: (String) -> Unit) {
    private val sessions = AtomicLong()
    @Volatile private var disposed = false
    private fun owns(session: Long, request: Long? = null): Boolean {
        val local = current(); val ui = local.localProcessing ?: return false
        return !disposed && local.ready && !local.opening && local.error == null && ui.session == session &&
            (request == null || request == ui.request) && local.entries.any {
                it.id == ui.source.sourceId && it.currentRevision.toLong() == ui.source.revision &&
                    OriginalExports.hash(it.originalText) == ui.sourceHash }
    }
    private fun update(session: Long, change: (LocalProcessingUiState) -> LocalProcessingUiState) = publish {
        if (disposed || it.localProcessing?.session != session) it else it.copy(localProcessing = change(it.localProcessing))
    }
    fun open(id: String, revision: Int) {
        val local = current()
        if (disposed || !local.ready || local.opening || local.error != null || !local.supportsLocalProcessing ||
            local.saving || local.savePending || local.originalEdit != null || local.originalHistory != null ||
            local.recordPackage != null || local.recycleBin != null || local.localProcessing != null ||
            local.candidates != null || local.sourceTags != null || local.sourceMemories != null || local.manualTagEditor != null) return
        val entry = local.entries.singleOrNull { it.id == id && it.currentRevision == revision } ?: return
        val ui = LocalProcessingUiState(sessions.incrementAndGet(), SourceVersion(id, revision.toLong()), OriginalExports.hash(entry.originalText))
        publish { it.copy(localProcessing = ui) }; execute(ui, null)
    }
    fun close(session: Long) {
        if (!disposed) publish { if (it.localProcessing?.session == session) it.copy(localProcessing = null) else it }
    }
    fun prepare(session: Long, target: LocalProcessingChoice) {
        val ui = current().localProcessing ?: return; val snapshot = ui.snapshot ?: return
        if (!owns(session) || ui.busy || ui.pending != null || snapshot.policy.name == target.name ||
            snapshot.policy == ProcessingPolicy.REMOTE_ALLOWED || snapshot.generation == Long.MAX_VALUE) return
        update(session) { it.copy(confirmation = LocalProcessingChange(snapshot, target, UUID.randomUUID().toString()), error = null, notice = null) }
    }
    fun cancel(session: Long, expected: LocalProcessingChange) {
        if (owns(session)) update(session) { if (it.busy || it.confirmation != expected) it else it.copy(confirmation = null) }
    }
    fun confirm(session: Long, expected: LocalProcessingChange) {
        val ui = current().localProcessing ?: return
        if (!owns(session) || ui.busy || ui.pending != null || ui.confirmation != expected || ui.snapshot != expected.expected) return
        retireDerived(ui.source.sourceId)
        execute(ui, expected)
    }
    fun retry(session: Long, request: Long) {
        val ui = current().localProcessing ?: return
        if (owns(session, request) && !ui.busy) execute(ui, ui.pending)
    }
    private fun execute(ui: LocalProcessingUiState, mutation: LocalProcessingChange?) {
        val request = ui.request + 1
        update(ui.session) { it.copy(busy = true, request = request, pending = mutation, confirmation = null, error = null, notice = null) }
        if (!enqueue { store ->
            if (!owns(ui.session, request)) return@enqueue
            try {
                val port = checkNotNull(store.localProcessing)
                val saved = mutation?.let(port::change)
                val actual = port.read(ui.source)
                val entries = store.entries()
                val entry = entries.singleOrNull { it.id == ui.source.sourceId && it.currentRevision.toLong() == ui.source.revision }
                if (actual.sourceHash != ui.sourceHash || saved != null && actual != saved || entry == null ||
                    entry.policy != actual.policy || OriginalExports.hash(entry.originalText) != actual.sourceHash) throw LocalProcessingConflict()
                val version = LocalProcessingVersion(actual.source, actual.generation)
                if (current().localProcessingVersions[actual.source.sourceId] != version) retireDerived(actual.source.sourceId)
                if (owns(ui.session, request)) publish { local -> if (!owns(ui.session, request)) local else local.copy(
                    entries = entries, localProcessingVersions = local.localProcessingVersions + (actual.source.sourceId to version), localProcessing = local.localProcessing?.copy(snapshot = actual, busy = false, pending = null,
                        error = null, notice = if (saved == null) null else "处理范围已保存在本机。原文与人工内容已保留；不会自动重新生成。")) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(ui.session, request, failure) }
        }) failed(ui.session, request, LocalProcessingStorageFailure())
    }
    private fun failed(session: Long, request: Long, failure: Exception) {
        if (!owns(session, request)) return
        val conflict = failure is LocalProcessingConflict
        update(session) { it.copy(busy = false, snapshot = null, confirmation = null, notice = null,
            pending = if (conflict) null else it.pending,
            error = if (conflict) "记录或处理范围已变化。请重新读取后确认，旧操作不会覆盖新的选择。"
                else "处理范围读取或保存状态待确认。请重试核对；关闭后可重新打开查看实际范围。") }
    }
    fun dispose() { disposed = true }
}
