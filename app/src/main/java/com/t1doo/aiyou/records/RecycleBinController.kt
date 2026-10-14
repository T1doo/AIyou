package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

class RecycleConfirmation(val id: Long, val record: RecycleRecord, internal val mutation: RecycleMutation,
    val busy: Boolean = false, val uncertain: Boolean = false, val conflict: Boolean = false,
    val error: String? = null) {
    internal fun copy(busy: Boolean = this.busy, uncertain: Boolean = this.uncertain,
        conflict: Boolean = this.conflict, error: String? = this.error) =
        RecycleConfirmation(id, record, mutation, busy, uncertain, conflict, error)
    override fun toString() = "RecycleConfirmation(busy=$busy, redacted)"
}
class RecycleBinUiState(val session: Long, val browsing: Boolean, val loading: Boolean = true,
    records: List<RecycleRecord> = emptyList(), val selectedId: String? = null,
    val confirmation: RecycleConfirmation? = null, val error: String? = null, val notice: String? = null) {
    val records = Collections.unmodifiableList(records.toList())
    val busy get() = loading || confirmation?.busy == true
    internal fun copy(loading: Boolean = this.loading, records: List<RecycleRecord> = this.records,
        selectedId: String? = this.selectedId, confirmation: RecycleConfirmation? = this.confirmation,
        error: String? = this.error, notice: String? = this.notice) =
        RecycleBinUiState(session, browsing, loading, records, selectedId, confirmation, error, notice)
    override fun toString() = "RecycleBinUiState(busy=$busy, redacted)"
}

/** Only an explicit, session-bound confirmation mutates storage, on the existing records IO queue. */
class RecycleBinController internal constructor(
    private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean,
    private val canMove: () -> Boolean = { true },
    private val onCommittedMove: (String) -> Unit = {},
) {
    private val sessions = AtomicLong()
    private val confirmations = AtomicLong()
    @Volatile private var disposed = false
    private fun available(local: LocalRecordUiState = current()) = !disposed && local.ready && !local.opening &&
        local.error == null && local.supportsRecycleBin
    private fun owns(session: Long, local: LocalRecordUiState = current()) =
        available(local) && local.recycleBin?.session == session
    private fun update(session: Long, change: (RecycleBinUiState) -> RecycleBinUiState) = publish { local ->
        if (disposed || local.recycleBin?.session != session) local else local.copy(recycleBin = change(local.recycleBin))
    }
    fun open() {
        val local = current()
        if (!available(local) || !canMove() || local.recordPackage != null || local.recycleBin != null || local.originalEdit != null || local.originalHistory != null ||
            local.saving || local.savePending) return
        val ui = RecycleBinUiState(sessions.incrementAndGet(), true)
        publish { latest -> if (available(latest) && latest.recycleBin == null) latest.copy(recycleBin = ui) else latest }
        load(ui.session)
    }
    fun requestMove(id: String, revision: Int) {
        val local = current()
        if (!available(local) || !canMove() || local.recordPackage != null || local.recycleBin != null || local.originalEdit != null || local.originalHistory != null ||
            local.manualTagEditor != null || local.saving || local.savePending ||
            local.entries.none { it.id == id && it.currentRevision == revision }) return
        val ui = RecycleBinUiState(sessions.incrementAndGet(), false)
        publish { latest -> if (available(latest) && latest.recycleBin == null &&
            latest.entries.any { it.id == id && it.currentRevision == revision }) latest.copy(recycleBin = ui) else latest }
        if (!enqueue { store ->
            if (!owns(ui.session)) return@enqueue
            try {
                val record = checkNotNull(store.recycleBin).read(id)
                if (record.deleted || record.entry.currentRevision != revision) throw RecycleConflict()
                val confirmation = confirmation(record)
                publish { latest -> if (!owns(ui.session, latest) || latest.entries.none { it.id == id && it.currentRevision == revision }) latest
                    else latest.copy(recycleBin = latest.recycleBin?.copy(loading = false, confirmation = confirmation)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { update(ui.session) { it.copy(loading = false, error = "无法确认当前原文状态，未执行移入。请关闭后重新读取。") } }
        }) update(ui.session) { it.copy(loading = false, error = "本机队列不可用，未执行移入。") }
    }
    private fun confirmation(record: RecycleRecord) = RecycleConfirmation(confirmations.incrementAndGet(), record,
        RecycleMutation(SourceVersion(record.entry.id, record.entry.currentRevision.toLong()), record.generation,
            record.deleted, UUID.randomUUID().toString()))
    fun select(session: Long, id: String?) = publish { local ->
        val ui = local.recycleBin
        if (!owns(session, local) || ui == null || ui.busy || ui.confirmation != null ||
            (id != null && ui.records.none { it.entry.id == id })) local else local.copy(recycleBin = ui.copy(selectedId = id))
    }
    fun requestRestore(session: Long, id: String, generation: Long) = publish { local ->
        val ui = local.recycleBin
        val record = ui?.records?.singleOrNull { it.entry.id == id && it.generation == generation && it.deleted }
        if (!owns(session, local) || ui == null || !ui.browsing || ui.busy || ui.confirmation != null || record == null || ui.selectedId != id) local
        else local.copy(recycleBin = ui.copy(confirmation = confirmation(record)))
    }
    fun cancelConfirmation(session: Long, id: Long) = publish { local ->
        val ui = local.recycleBin
        if (disposed || ui?.session != session || ui.confirmation?.id != id || ui.confirmation.busy) local
        else local.copy(recycleBin = if (ui.browsing) ui.copy(confirmation = null) else null)
    }
    fun close(session: Long) = publish { local ->
        if (disposed || local.recycleBin?.session != session) local else local.copy(recycleBin = null)
    }
    fun refresh(session: Long) {
        val ui = current().recycleBin ?: return
        if (!owns(session) || !ui.browsing || ui.busy || ui.confirmation != null) return
        update(session) { it.copy(loading = true, records = emptyList(), selectedId = null, error = null) }
        load(session)
    }
    private fun load(session: Long) {
        if (!enqueue { store ->
            if (!owns(session)) return@enqueue
            try {
                val records = checkNotNull(store.recycleBin).records()
                val entries = store.entries()
                val tags = store.manualTags?.snapshots()
                publish { local -> if (!owns(session, local) || local.recycleBin?.loading != true) local else local.copy(
                    entries = entries, manualTags = tags.orEmpty(), manualTagsAvailable = tags != null,
                    manualTagsError = if (store.manualTags != null && tags == null) "手工标签暂不可读取，请重试。" else null,
                    recycleBin = local.recycleBin.copy(loading = false, records = records, error = null)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { update(session) { it.copy(loading = false, records = emptyList(), selectedId = null,
                error = "回收站读取失败，加密数据已保留。请重试读取。") } }
        }) update(session) { it.copy(loading = false, error = "本机队列不可用，请重试读取。") }
    }
    fun confirm(session: Long, id: Long) {
        val ui = current().recycleBin ?: return
        val intent = ui.confirmation ?: return
        if (!owns(session) || ui.loading || intent.id != id || intent.busy || intent.conflict) return
        publish { local -> val latest = local.recycleBin
            if (!owns(session, local) || latest?.confirmation?.id != id || latest.confirmation.busy || latest.confirmation.conflict) local
            else local.copy(recycleBin = latest.copy(confirmation = latest.confirmation.copy(busy = true, error = null))) }
        if (!enqueue { store ->
            val pending = current().recycleBin?.confirmation
            if (!owns(session) || pending?.id != id || !pending.busy) return@enqueue
            try {
                val bin = checkNotNull(store.recycleBin)
                if (!intent.record.deleted && (!canMove() || current().let { it.originalEdit != null ||
                    it.originalHistory != null || it.manualTagEditor != null })) throw RecycleConflict()
                bin.apply(intent.mutation)
                val entries = store.entries()
                val tags = store.manualTags?.snapshots()
                val recycled = if (ui.browsing) bin.records() else emptyList()
                val notice = if (intent.record.deleted) "已恢复到本机列表；不参与 AI，旧远端授权未恢复。"
                    else "已移入回收站。原文版本、手工标签和附件已保留；已导出文件无法撤回。"
                publish { local ->
                    if (disposed) local else {
                        val latest = local.recycleBin
                        // Durable state is authoritative even if a route was closed during the transaction.
                        val confirmed = local.copy(entries = entries, manualTags = tags.orEmpty(), manualTagsAvailable = tags != null,
                            manualTagsError = if (store.manualTags != null && tags == null) "手工标签暂不可读取，请重试。" else null,
                            recycleNotice = notice,
                            manualTagEditor = local.manualTagEditor?.takeUnless { !intent.record.deleted && it.entryId == intent.record.entry.id },
                            originalHistory = local.originalHistory?.takeUnless { !intent.record.deleted && it.entryId == intent.record.entry.id },
                            originalEdit = local.originalEdit?.takeUnless { !intent.record.deleted && it.entryId == intent.record.entry.id })
                        if (latest?.session != session || latest.confirmation?.id != id) confirmed
                        else confirmed.copy(recycleBin = if (!ui.browsing) null else latest.copy(records = recycled,
                            selectedId = null, confirmation = null, error = null, notice = notice))
                    }
                }
                if (!intent.record.deleted) onCommittedMove(intent.record.entry.id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // A failed readback may follow a committed transaction. Reuse this exact durable receipt on retry.
                val entries = try { store.entries() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                publish { local ->
                    if (disposed) local else {
                        val safe = local.copy(manualTags = emptyMap(), manualTagsAvailable = false,
                            manualTagsError = "操作后手工标签状态尚未确认，请重新读取。")
                        val refreshed = if (entries == null) safe.copy(error = "当前原文状态尚未确认，请重试本机读取。") else safe.copy(entries = entries)
                        val latest = refreshed.recycleBin
                        if (latest?.session != session || latest.confirmation?.id != id) refreshed
                        else refreshed.copy(recycleBin = latest.copy(records = emptyList(), selectedId = null,
                            error = if (latest.browsing) "操作后回收站状态尚未确认，请重新读取。" else latest.error,
                            confirmation = latest.confirmation.copy(busy = false,
                            uncertain = failure !is RecycleConflict, conflict = failure is RecycleConflict,
                            error = if (failure is RecycleConflict) "原文版本或回收站状态已变化。请关闭确认并重新读取。"
                                else "操作结果尚未确认，请用同一操作重试确认；返回不会撤销已提交的操作。")))
                    }
                }
                if (!intent.record.deleted && entries != null && entries.none { it.id == intent.record.entry.id }) onCommittedMove(intent.record.entry.id)
            }
        }) update(session) { it.copy(confirmation = it.confirmation?.copy(busy = false, error = "本机队列不可用，未执行操作。")) }
    }
    fun dispose() { disposed = true; publish { it.copy(recycleBin = null) } }
}
