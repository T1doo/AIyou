package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/** Rendered reading authority. Generation comes from the source ledger, never a missing people row. */
class ManualPeopleReadingSource internal constructor(val version: SourceVersion, val contentHash: String,
    val generation: Long, internal val owner: ManualPeopleRepository) {
    init { require(version.revision <= Int.MAX_VALUE && generation >= 0 && contentHash.matches(Regex("[0-9a-f]{64}"))) }
    internal fun same(other: ManualPeopleReadingSource?) = other != null && version == other.version &&
        contentHash == other.contentHash && generation == other.generation && owner === other.owner
    override fun toString() = "ManualPeopleReadingSource(redacted)"
}

internal fun LocalRecordUiState.manualPeopleReadingSource(id: String, revision: Int): ManualPeopleReadingSource? {
    if (!ready || opening || error != null || !supportsManualPeople || !localProcessingAvailable) return null
    val owner = manualPeopleOwner ?: return null
    val entry = entries.singleOrNull { it.id == id && it.currentRevision == revision } ?: return null
    val source = localProcessingVersions[id]?.takeIf { it.source == SourceVersion(id, revision.toLong()) } ?: return null
    return ManualPeopleReadingSource(source.source, OriginalExports.hash(entry.originalText), source.generation, owner)
}
internal fun LocalRecordUiState.hasManualPeopleReadingSource(editor: ManualPeopleEditor): Boolean {
    val source = manualPeopleReadingSource(editor.entryId, editor.revision) ?: return false
    return source.owner === editor.owner && source.contentHash == editor.sourceHash && source.generation == editor.sourceGeneration
}

class ManualPeopleEditor(val session: Long, val entryId: String, val revision: Int,
    val snapshot: ManualPeopleSnapshot? = null, val input: String = "", val busy: Boolean = false,
    val error: String? = null, val notice: String? = null, val conflict: Boolean = false,
    internal val pending: ManualPeopleMutation? = null, internal val adding: Boolean = false,
    internal val owner: ManualPeopleRepository? = null, internal val sourceHash: String? = null, internal val sourceGeneration: Long? = null) {
    fun copy(snapshot: ManualPeopleSnapshot? = this.snapshot, input: String = this.input, busy: Boolean = this.busy,
        error: String? = this.error, notice: String? = this.notice, conflict: Boolean = this.conflict,
        pending: ManualPeopleMutation? = this.pending, adding: Boolean = this.adding) =
        ManualPeopleEditor(session, entryId, revision, snapshot, input, busy, error, notice, conflict, pending, adding, owner, sourceHash, sourceGeneration)
    override fun toString() = "ManualPeopleEditor(busy=$busy, redacted)"
}

/** UI memory only; durable labels live exclusively in the borrowed encrypted repository. */
class ManualPeopleController internal constructor(
    private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean,
) {
    private val sessions = AtomicLong()
    @Volatile private var disposed = false
    private fun live(editor: ManualPeopleEditor, local: LocalRecordUiState = current()) = !disposed &&
        editor.owner != null && local.manualPeopleOwner === editor.owner && local.ready && !local.opening && local.error == null && local.manualPeopleEditor?.session == editor.session &&
        local.hasManualPeopleReadingSource(editor)
    private fun update(editor: ManualPeopleEditor, change: (ManualPeopleEditor) -> ManualPeopleEditor) = publish { local ->
        if (live(editor, local)) local.copy(manualPeopleEditor = change(checkNotNull(local.manualPeopleEditor))) else local
    }
    fun open(id: String, revision: Int) { current().manualPeopleReadingSource(id, revision)?.let(::open) }
    /** The actual reading button passes its rendered source, so a retained click cannot adopt a newer generation. */
    fun open(source: ManualPeopleReadingSource) {
        val id = source.version.sourceId; val revision = source.version.revision.toInt()
        val local = current()
        if (disposed || !source.same(local.manualPeopleReadingSource(id, revision)) || local.recordPackage != null ||
            local.recycleBin != null || local.sourceMemories != null || local.saving || local.savePending) return
        val editor = ManualPeopleEditor(sessions.incrementAndGet(), id, revision, busy = true, owner = source.owner,
            sourceHash = source.contentHash, sourceGeneration = source.generation)
        publish { latest -> if (!disposed && source.same(latest.manualPeopleReadingSource(id, revision)))
            latest.copy(manualPeopleEditor = editor) else latest }
        if (live(editor)) enqueue { store -> load(editor, store) }
    }
    fun edit(session: Long, version: Long, input: String) {
        val editor = current().manualPeopleEditor?.takeIf { it.session == session && it.snapshot?.version == version && !it.busy && live(it) } ?: return
        if (input == editor.input) return
        update(editor) { it.copy(input = input, notice = null, pending = if (input == it.input) it.pending else null) }
    }
    fun close(session: Long) {
        if (disposed) return
        publish { local -> if (local.manualPeopleEditor?.session == session) local.copy(manualPeopleEditor = null) else local }
    }
    fun add(session: Long, version: Long, input: String) {
        edit(session, version, input)
        val editor = current().manualPeopleEditor?.takeIf { it.session == session && it.snapshot?.version == version && !it.busy && live(it) } ?: return
        val snapshot = editor.snapshot ?: return
        if (editor.pending != null || editor.conflict) return
        try { submit(editor, ManualPeopleMutation(snapshot, snapshot.names + input, UUID.randomUUID().toString()), true) }
        catch (_: IllegalArgumentException) { update(editor) { it.copy(error = "人物关联需为 1–40 个字符，最多 12 个，不可重复或包含控制字符。") } }
    }
    fun remove(session: Long, version: Long, name: String) = remove(session, version, name, current().manualPeopleEditor?.input.orEmpty())
    fun remove(session: Long, version: Long, name: String, input: String) {
        edit(session, version, input)
        val editor = current().manualPeopleEditor?.takeIf { it.session == session && it.snapshot?.version == version && !it.busy && live(it) && it.pending == null && !it.conflict } ?: return
        val snapshot = editor.snapshot ?: return
        if (name !in snapshot.names) return
        submit(editor, ManualPeopleMutation(snapshot, snapshot.names - name, UUID.randomUUID().toString()), false)
    }
    fun retry(session: Long) {
        val editor = current().manualPeopleEditor?.takeIf { it.session == session && !it.busy && live(it) } ?: return
        if (editor.pending != null && !editor.conflict) submit(editor, editor.pending, editor.adding)
        else { update(editor) { it.copy(busy = true, notice = null) }; enqueue { store -> load(editor, store) } }
    }
    private suspend fun load(editor: ManualPeopleEditor, store: EntryRepository) {
        if (!live(editor)) return
        try {
            val tags = checkNotNull(store.manualPeople)
            if (tags !== editor.owner) throw ManualPeopleUnavailable()
            val sources = checkNotNull(store.localProcessing)
            if (!sourceMatches(editor, sources.read(SourceVersion(editor.entryId, editor.revision.toLong())))) {
                failed(editor, conflict = true, sourceChanged = true); return
            }
            val snapshot = tags.read(editor.entryId)
            // Loading newer metadata must not grant a stale reader authority over a
            // different original or a restored/revoked source generation. Metadata
            // version conflicts on the same source can still be explicitly reloaded.
            if (snapshot.entryId != editor.entryId || snapshot.sourceRevision != editor.revision ||
                snapshot.sourceHash != editor.sourceHash ||
                snapshot.generation != editor.sourceGeneration) {
                failed(editor, conflict = true, sourceChanged = true)
                return
            }
            val all = tags.snapshots()
            val epoch = store.localSearch?.epoch()
            // Recheck after the separate metadata reads; even an empty people map carries no source generation.
            if (!sourceMatches(editor, sources.read(SourceVersion(editor.entryId, editor.revision.toLong())))) {
                failed(editor, conflict = true, sourceChanged = true); return
            }
            publish { local -> if (!live(editor, local)) local else local.copy(manualPeople = all,
                manualPeopleAvailable = true, manualPeopleError = null, localSearchEpoch = epoch ?: local.localSearchEpoch, manualPeopleEditor = local.manualPeopleEditor?.copy(
                    snapshot = snapshot, busy = false, pending = null, conflict = false, error = null, notice = null)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { val changed = failure is LocalProcessingConflict || failure is ManualPeopleUnavailable
            failed(editor, conflict = changed, reading = true, sourceChanged = changed) }
    }
    private fun sourceMatches(editor: ManualPeopleEditor, actual: LocalProcessingSnapshot) =
        actual.source == SourceVersion(editor.entryId, editor.revision.toLong()) &&
            actual.sourceHash == editor.sourceHash && actual.generation == editor.sourceGeneration
    private fun submit(editor: ManualPeopleEditor, mutation: ManualPeopleMutation, adding: Boolean) {
        update(editor) { it.copy(busy = true, pending = mutation, adding = adding, error = null, notice = null) }
        enqueue { store ->
            if (!live(editor)) {
                publish { local -> if (disposed || local.manualPeopleOwner !== editor.owner || local.manualPeopleEditor?.session != editor.session) local else local.copy(
                    manualPeopleEditor = local.manualPeopleEditor?.copy(busy = false, conflict = true,
                        error = "原文已变化或不可用，输入已保留，请关闭后重新打开。")) }; return@enqueue
            }
            try {
                val tags = checkNotNull(store.manualPeople)
                if (tags !== editor.owner) throw ManualPeopleUnavailable()
                val saved = tags.replace(mutation)
                val all = tags.snapshots()
                val epoch = store.localSearch?.epoch()
                check(all[editor.entryId]?.let { it.version == saved.version && it.names == saved.names } == true)
                publish { local ->
                    if (disposed || local.manualPeopleOwner !== editor.owner) local else {
                        val confirmed = local.copy(manualPeople = all, manualPeopleAvailable = true, manualPeopleError = null, localSearchEpoch = epoch ?: local.localSearchEpoch)
                        if (!live(editor, local)) confirmed else confirmed.copy(manualPeopleEditor = local.manualPeopleEditor?.copy(
                            snapshot = saved, input = if (adding) "" else local.manualPeopleEditor.input,
                            busy = false, pending = null, conflict = false, error = null, notice = "手工人物关联已保存在本机"))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(editor, failure is ManualPeopleConflict || failure is ManualPeopleUnavailable) }
        }
    }
    private fun failed(editor: ManualPeopleEditor, conflict: Boolean, reading: Boolean = false, sourceChanged: Boolean = false) = publish { local ->
        if (disposed || local.manualPeopleOwner !== editor.owner) local else local.copy(manualPeople = emptyMap(), manualPeopleAvailable = false,
            manualPeopleError = "手工人物关联暂不可读取，请重试。", manualPeopleEditor = if (!live(editor, local)) local.manualPeopleEditor else local.manualPeopleEditor?.copy(
                busy = false, conflict = conflict, notice = null,
                error = if (sourceChanged) "原文版本或可用范围已变化。输入已保留，请返回列表刷新原文后重新打开。"
                    else if (conflict) "人物关联已由另一操作变化。输入已保留，请重新读取后再保存。"
                    else if (reading) "手工人物关联读取失败，输入与加密数据已保留，请重试。"
                    else "人物关联保存状态待确认，输入已保留，请重试确认。"))
    }
    fun dispose() { disposed = true }
}
