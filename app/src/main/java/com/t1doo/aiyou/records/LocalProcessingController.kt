package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

data class LocalProcessingUiState(val session: Long, val source: SourceVersion, val sourceHash: String,
    val snapshot: LocalProcessingSnapshot? = null, val busy: Boolean = false,
    val confirmation: LocalProcessingChange? = null, val pending: LocalProcessingChange? = null,
    val request: Long = 0, val error: String? = null, val notice: String? = null,
    val authorizationSource: RecordAuthorizationReadingSource? = null,
    val authorization: RecordAuthorizationPreview? = null,
    val withdrawalConfirmation: RecordAuthorizationChange? = null,
    val pendingWithdrawal: RecordAuthorizationChange? = null) {
    override fun toString() = "LocalProcessingUiState(redacted)"
}

/** Captured by the rendered detail button, including the actual encrypted storage owner. */
class RecordAuthorizationReadingSource internal constructor(val binding: RecordAuthorizationSource,
    internal val owner: RecordAuthorizationRepository) {
    internal fun same(other: RecordAuthorizationReadingSource?) = other != null && binding == other.binding && owner === other.owner
    override fun toString() = "RecordAuthorizationReadingSource(redacted)"
}
internal fun LocalRecordUiState.authorizationReadingSource(id: String, revision: Int): RecordAuthorizationReadingSource? {
    val owner = recordAuthorizationOwner ?: return null
    val source = metadataReadingSource(id, revision) ?: return null
    return RecordAuthorizationReadingSource(RecordAuthorizationSource(source.version, source.contentHash, source.generation), owner)
}

internal fun authorizationStatus(preview: RecordAuthorizationPreview): String = when (preview.credentialReadiness) {
    RecordCredentialReadiness.NOT_CONFIGURED -> "这条记录未配置可用连接。模型发送尚未开放。"
    RecordCredentialReadiness.UNVERIFIED -> "已保存连接信息，凭据未核验；当前不能发送。"
}

internal fun LocalRecordUiState.hasLocalProcessingProof(source: SourceVersion): Boolean =
    !supportsLocalProcessing || localProcessingAvailable && localProcessingVersions[source.sourceId]?.source == source

/** Explicit per-record choices. A lost acknowledgement retains the exact operation identity. */
class LocalProcessingController(private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean,
    private val retireDerived: (String) -> Unit, private val clock: Clock = Clock.systemUTC()) {
    private val sessions = AtomicLong()
    @Volatile private var disposed = false
    private fun owns(session: Long, request: Long? = null): Boolean {
        return owns(current(), session, request)
    }
    private fun owns(local: LocalRecordUiState, session: Long, request: Long? = null): Boolean {
        val ui = local.localProcessing ?: return false
        return !disposed && local.ready && !local.opening && local.error == null && ui.session == session &&
            (ui.authorizationSource == null || local.recordAuthorizationOwner === ui.authorizationSource.owner) &&
            (request == null || request == ui.request) && local.entries.any {
                it.id == ui.source.sourceId && it.currentRevision.toLong() == ui.source.revision &&
                    OriginalExports.hash(it.originalText) == ui.sourceHash }
    }
    private fun update(session: Long, change: (LocalProcessingUiState) -> LocalProcessingUiState) = publish {
        if (!owns(it, session)) it else it.copy(localProcessing = change(checkNotNull(it.localProcessing)))
    }
    fun open(id: String, revision: Int) {
        if (current().recordAuthorizationOwner != null) {
            current().authorizationReadingSource(id, revision)?.let(::open); return
        }
        openWithin(id, revision, null)
    }
    fun open(source: RecordAuthorizationReadingSource) {
        val id = source.binding.source.sourceId; val revision = source.binding.source.revision.toInt()
        if (!source.same(current().authorizationReadingSource(id, revision))) return
        openWithin(id, revision, source)
    }
    private fun openWithin(id: String, revision: Int, source: RecordAuthorizationReadingSource?) {
        val local = current()
        if (disposed || !local.ready || local.opening || local.error != null || !local.supportsLocalProcessing ||
            local.saving || local.savePending || local.originalEdit != null || local.originalHistory != null ||
            local.recordPackage != null || local.recycleBin != null || local.localProcessing != null ||
            local.candidates != null || local.sourceTags != null || local.sourceMemories != null || local.manualTagEditor != null) return
        val entry = local.entries.singleOrNull { it.id == id && it.currentRevision == revision } ?: return
        val ui = LocalProcessingUiState(sessions.incrementAndGet(), SourceVersion(id, revision.toLong()), OriginalExports.hash(entry.originalText), authorizationSource = source)
        publish { if (source != null && !source.same(it.authorizationReadingSource(id, revision))) it else it.copy(localProcessing = ui) }
        if (owns(ui.session)) execute(ui, null)
    }
    fun close(session: Long) {
        if (!disposed) publish { if (it.localProcessing?.session == session) it.copy(localProcessing = null) else it }
    }
    fun prepare(session: Long, target: LocalProcessingChoice) {
        val ui = current().localProcessing ?: return; val snapshot = ui.snapshot ?: return
        if (!owns(session) || !canPrepare(ui) || ui.busy || ui.pending != null || ui.pendingWithdrawal != null || snapshot.policy.name == target.name ||
            snapshot.policy == ProcessingPolicy.REMOTE_ALLOWED || snapshot.generation == Long.MAX_VALUE) return
        update(session) { it.copy(confirmation = LocalProcessingChange(snapshot, target, UUID.randomUUID().toString()), withdrawalConfirmation = null, error = null, notice = null) }
    }
    fun cancel(session: Long, expected: LocalProcessingChange) {
        if (owns(session)) update(session) { if (it.busy || it.confirmation != expected) it else it.copy(confirmation = null) }
    }
    fun confirm(session: Long, expected: LocalProcessingChange) {
        val ui = current().localProcessing ?: return
        if (!owns(session) || !canPrepare(ui) || ui.busy || ui.pending != null || ui.pendingWithdrawal != null || ui.confirmation != expected || ui.snapshot != expected.expected) return
        retireDerived(ui.source.sourceId)
        execute(ui, expected)
    }
    fun retry(session: Long, request: Long) {
        val ui = current().localProcessing ?: return
        if (owns(session, request) && !ui.busy) execute(ui, ui.pending)
    }
    private fun execute(ui: LocalProcessingUiState, mutation: LocalProcessingChange?) {
        if (ui.authorizationSource != null) { executeManaged(ui, mutation, ui.pendingWithdrawal); return }
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
    private fun canPrepare(ui: LocalProcessingUiState): Boolean = ui.authorizationSource?.let {
        it.same(current().authorizationReadingSource(ui.source.sourceId, ui.source.revision.toInt())) && ui.authorization?.snapshot?.binding == it.binding
    } ?: true
    fun prepareWithdrawal(session: Long) {
        val ui = current().localProcessing ?: return
        val snapshot = ui.authorization?.snapshot ?: return
        if (!owns(session) || !canPrepare(ui) || ui.busy || ui.pending != null || ui.pendingWithdrawal != null ||
            snapshot.binding.generation == Long.MAX_VALUE || snapshot.connection == null && snapshot.consent == null && snapshot.policy != ProcessingPolicy.REMOTE_ALLOWED) return
        update(session) { it.copy(confirmation = null, withdrawalConfirmation = RecordAuthorizationChange.withdraw(snapshot,
            ProcessingPolicy.LOCAL_ONLY, UUID.randomUUID().toString()), error = null, notice = null) }
    }
    fun cancelWithdrawal(session: Long, expected: RecordAuthorizationChange) {
        if (owns(session)) update(session) { if (it.busy || it.withdrawalConfirmation !== expected) it else it.copy(withdrawalConfirmation = null) }
    }
    fun confirmWithdrawal(session: Long, expected: RecordAuthorizationChange) {
        val ui = current().localProcessing ?: return
        if (!owns(session) || !canPrepare(ui) || ui.busy || ui.pending != null || ui.pendingWithdrawal != null ||
            ui.withdrawalConfirmation !== expected || ui.authorization?.snapshot != expected.expected) return
        retireDerived(ui.source.sourceId)
        executeManaged(ui, null, expected)
    }
    private fun executeManaged(ui: LocalProcessingUiState, mutation: LocalProcessingChange?, withdrawal: RecordAuthorizationChange?) {
        val source = checkNotNull(ui.authorizationSource); val request = ui.request + 1
        update(ui.session) { it.copy(busy = true, request = request, pending = mutation, pendingWithdrawal = withdrawal,
            confirmation = null, withdrawalConfirmation = null, error = null, notice = null) }
        if (!enqueue { store ->
            if (!owns(ui.session, request)) return@enqueue
            try {
                val port = checkNotNull(store.recordAuthorizations)
                if (port !== source.owner) throw RecordAuthorizationConflict()
                val receipt = withdrawal?.let { port.changeAuthorization(it, clock::instant) }
                val localSaved = mutation?.let { checkNotNull(store.localProcessing).change(it) }
                val actual = receipt?.current ?: port.readAuthorization(ui.source)
                if (withdrawal == null && mutation == null && actual.binding != source.binding) throw RecordAuthorizationConflict()
                if (localSaved != null && (actual.binding.source != localSaved.source || actual.binding.sourceHash != localSaved.sourceHash ||
                    actual.binding.generation != localSaved.generation || actual.policy != localSaved.policy)) throw RecordAuthorizationConflict()
                val entries = store.entries()
                val entry = entries.singleOrNull { it.id == ui.source.sourceId && it.currentRevision.toLong() == ui.source.revision }
                if (entry == null || actual.binding.sourceHash != ui.sourceHash || entry.policy != actual.policy ||
                    OriginalExports.hash(entry.originalText) != actual.binding.sourceHash) throw RecordAuthorizationConflict()
                val preview = port.previewAuthorization(actual.binding, actual.consent?.purpose ?: TextPurpose.ORGANIZATION, clock::instant)
                val snapshot = LocalProcessingSnapshot(actual.binding.source, actual.binding.sourceHash, actual.binding.generation, actual.policy)
                val version = LocalProcessingVersion(snapshot.source, snapshot.generation)
                if (current().localProcessingVersions[snapshot.source.sourceId] != version) retireDerived(snapshot.source.sourceId)
                publish { local -> if (!owns(local, ui.session, request)) local else local.copy(entries = entries,
                    localProcessingVersions = local.localProcessingVersions + (snapshot.source.sourceId to version),
                    localProcessing = checkNotNull(local.localProcessing).copy(snapshot = snapshot, authorization = preview,
                        authorizationSource = RecordAuthorizationReadingSource(actual.binding, port), busy = false, pending = null, pendingWithdrawal = null,
                        error = null, notice = when {
                            receipt != null && receipt.decisionStillCurrent -> "这条记录的远端授权已撤回，当前仅本机。原文与人工内容已保留。"
                            receipt != null -> "原撤回回执已核对；当前范围以本次读取为准，未重新应用旧决定。"
                            localSaved != null -> "处理范围已保存在本机。原文与人工内容已保留；不会自动重新生成。"
                            else -> null
                        })) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(ui.session, request, failure) }
        }) failed(ui.session, request, LocalProcessingStorageFailure())
    }
    private fun failed(session: Long, request: Long, failure: Exception) {
        if (!owns(session, request)) return
        val conflict = failure is LocalProcessingConflict || failure is RecordAuthorizationConflict
        update(session) { it.copy(busy = false, snapshot = null, authorization = null, confirmation = null, withdrawalConfirmation = null, notice = null,
            pending = if (conflict) null else it.pending,
            pendingWithdrawal = if (conflict) null else it.pendingWithdrawal,
            error = if (conflict) "记录或处理范围已变化。请重新读取后确认，旧操作不会覆盖新的选择。"
                else "处理范围读取或保存状态待确认。请重试核对；关闭后可重新打开查看实际范围。") }
    }
    fun dispose() { disposed = true }
}
