package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

data class SourceTagConfirmation(val token: Long, val proposal: SourceTagProposal, val epoch: Long, val tagVersion: Long)
data class SourceTagUiState(val session: Long, val source: SourceVersion, val sourceHash: String,
    val preview: SourceTagPreview? = null, val busy: Boolean = true, val error: String? = null,
    val notice: String? = null, val confirmation: SourceTagConfirmation? = null,
    val pending: SourceTagRequest? = null, val request: Long = 0) {
    override fun toString() = "SourceTagUiState(redacted)"
}

/** Memory-only proposals; only an authenticated manual-tag transaction is success. */
class SourceTagController(private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean, private val clock: Clock) {
    private val sessions = AtomicLong()
    private val tokens = AtomicLong()
    @Volatile private var disposed = false
    private fun owns(session: Long, request: Long? = null): Boolean {
        val local = current(); val ui = local.sourceTags ?: return false
        return !disposed && ui.session == session && (request == null || request == ui.request) &&
            local.ready && !local.opening && local.error == null && local.hasLocalProcessingProof(ui.source) && local.entries.any {
                it.id == ui.source.sourceId && it.currentRevision.toLong() == ui.source.revision &&
                    it.policy != ProcessingPolicy.AI_NONE && OriginalExports.hash(it.originalText) == ui.sourceHash }
    }
    private fun update(session: Long, change: (SourceTagUiState) -> SourceTagUiState) = publish { local ->
        if (disposed || local.sourceTags?.session != session) local else local.copy(sourceTags = change(checkNotNull(local.sourceTags)))
    }
    fun open(id: String, revision: Int) {
        val local = current()
        if (disposed || !local.ready || local.opening || local.error != null || !local.supportsSourceTags ||
            local.saving || local.savePending || local.candidates != null || local.sourceTags != null || local.sourceMemories != null ||
            local.manualTagEditor != null || local.recordPackage != null || local.recycleBin != null) return
        if (!local.hasLocalProcessingProof(SourceVersion(id, revision.toLong())) || local.localProcessing != null) return
        val entry = local.entries.singleOrNull { it.id == id && it.currentRevision == revision && it.policy != ProcessingPolicy.AI_NONE } ?: return
        val ui = SourceTagUiState(sessions.incrementAndGet(), SourceVersion(id, revision.toLong()), OriginalExports.hash(entry.originalText), busy = false)
        publish { it.copy(sourceTags = ui) }; refresh(ui.session)
    }
    fun close(session: Long) {
        if (!disposed) publish { if (it.sourceTags?.session == session) it.copy(sourceTags = null) else it }
    }
    fun refresh(session: Long) {
        val ui = current().sourceTags ?: return
        if (!owns(session) || ui.busy || ui.pending != null) return
        execute(ui, null)
    }
    fun requestConfirmation(session: Long, proposal: SourceTagProposal) {
        val ui = current().sourceTags ?: return; val p = ui.preview ?: return
        if (!owns(session) || ui.busy || ui.pending != null || proposal !in p.proposals || proposal.name in p.tags.names ||
            p.tags.names.size >= ManualTags.MAX_COUNT || p.epoch == Long.MAX_VALUE || p.tags.version == Long.MAX_VALUE) return
        update(session) { it.copy(confirmation = SourceTagConfirmation(tokens.incrementAndGet(), proposal, p.epoch, p.tags.version), notice = null) }
    }
    fun cancelConfirmation(session: Long, expected: SourceTagConfirmation) {
        if (owns(session)) update(session) { if (it.busy || it.confirmation != expected) it else it.copy(confirmation = null) }
    }
    fun confirm(session: Long, expected: SourceTagConfirmation) {
        val ui = current().sourceTags ?: return; val p = ui.preview ?: return
        if (!owns(session) || ui.busy || ui.pending != null || ui.confirmation != expected ||
            p.epoch != expected.epoch || p.tags.version != expected.tagVersion || p.epoch == Long.MAX_VALUE || p.tags.version == Long.MAX_VALUE) return
        execute(ui, SourceTagRequest(p, expected.proposal, UUID.randomUUID().toString(), clock.millis()))
    }
    fun retry(session: Long, request: Long) {
        val ui = current().sourceTags ?: return
        if (!owns(session, request) || ui.busy) return
        execute(ui, ui.pending)
    }
    fun reference(session: Long, proposal: SourceTagProposal): OriginalTextReference? {
        val ui = current().sourceTags ?: return null
        return if (owns(session) && !ui.busy && ui.pending == null && proposal in ui.preview?.proposals.orEmpty()) proposal.reference else null
    }
    private fun execute(ui: SourceTagUiState, mutation: SourceTagRequest?) {
        val next = ui.request + 1
        update(ui.session) { it.copy(busy = true, error = null, notice = null, pending = mutation, request = next) }
        if (!enqueue { store ->
            if (!owns(ui.session, next)) return@enqueue
            try {
                val owner = checkNotNull(store.sourceTags)
                val saved = mutation?.let(owner::apply)
                val p = owner.preview(ui.source)
                if (p.sourceHash != ui.sourceHash) throw SourceTagConflict()
                val all = checkNotNull(store.manualTags).snapshots()
                val actual = all[ui.source.sourceId] ?: ManualTagSnapshot(ui.source.sourceId, 0, emptyList())
                check(actual.version == p.tags.version && actual.names == p.tags.names)
                if (saved != null) check(actual.version == saved.version && actual.names == saved.names)
                if (owns(ui.session, next)) publish { local -> if (!owns(ui.session, next)) local else local.copy(
                    manualTags = all, manualTagsAvailable = true, manualTagsError = null,
                    sourceTags = local.sourceTags?.copy(preview = p, busy = false, pending = null, confirmation = null,
                        error = null, notice = if (saved == null) null else "记录标签已保存在本机，可在标签筛选中使用。")) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(ui.session, next, failure) }
        }) failed(ui.session, next, SourceTagStorageFailure())
    }
    private fun failed(session: Long, request: Long, failure: Exception) {
        if (!owns(session, request)) return
        val terminal = failure is SourceTagConflict || failure is InvalidSourceTagFormat || failure is SourceTagLimit
        val error = when (failure) {
                is SourceTagConflict -> "记录或标签已变化，请重新预览后确认；旧请求不会再次加入标签。"
                is InvalidSourceTagFormat -> "标签行格式不符合要求：标签：#旅行 #生活；每个名称最多40个字符，合计最多12个。"
                is SourceTagLimit -> "记录已有12个标签，请先管理记录标签。"
                else -> "标签读取或保存状态待确认，请重试核对；原文已保留。"
            }
        publish { local -> if (!owns(session, request)) local else local.copy(manualTags = emptyMap(),
            manualTagsAvailable = false, manualTagsError = "记录标签暂不可读取，请重新核对。",
            sourceTags = local.sourceTags?.copy(preview = null, busy = false, confirmation = null, notice = null,
                pending = if (terminal) null else local.sourceTags.pending, error = error)) }
    }
    fun dispose() { disposed = true }
}
