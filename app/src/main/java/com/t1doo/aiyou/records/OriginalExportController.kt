package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

enum class OriginalExportPhase {
    SELECTING, PREPARING, PREVIEW, CHECKING, PICKING, WRITING, CANCELLING, DONE, FAILED, CHANGED, CANCELLED, CLEANING,
}
class OriginalExportUiState(val session: Long, val phase: OriginalExportPhase = OriginalExportPhase.SELECTING,
    selection: List<SourceVersion> = emptyList(), val format: OriginalExportFormat = OriginalExportFormat.MARKDOWN,
    val snapshot: OriginalExportSnapshot? = null, val riskAccepted: Boolean = false, val requestKey: String? = null,
    val pickerLaunched: Boolean = false, val hasFile: Boolean = false, val error: String? = null,
    val notice: String? = null, internal val epoch: Long = 0) {
    val selection = Collections.unmodifiableList(selection.toList())
    val busy get() = phase in setOf(OriginalExportPhase.PREPARING, OriginalExportPhase.CHECKING,
        OriginalExportPhase.WRITING, OriginalExportPhase.CANCELLING, OriginalExportPhase.CLEANING)
    internal fun copy(phase: OriginalExportPhase = this.phase, selection: List<SourceVersion> = this.selection,
        format: OriginalExportFormat = this.format, snapshot: OriginalExportSnapshot? = this.snapshot,
        riskAccepted: Boolean = this.riskAccepted, requestKey: String? = this.requestKey,
        pickerLaunched: Boolean = this.pickerLaunched, hasFile: Boolean = this.hasFile,
        error: String? = this.error, notice: String? = this.notice, epoch: Long = this.epoch) =
        OriginalExportUiState(session, phase, selection, format, snapshot, riskAccepted, requestKey,
            pickerLaunched, hasFile, error, notice, epoch)
    override fun toString() = "OriginalExportUiState(phase=$phase, redacted)"
}

/** In-memory scope/consent and UUID-bound destination callback; one existing IO queue owns all work. */
class OriginalExportController internal constructor(
    private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean,
    private val clock: Clock,
) {
    private val sessions = AtomicLong()
    @Volatile private var disposed = false
    private class Work(val session: Long, val key: String, val snapshot: OriginalExportSnapshot,
        val payload: OriginalExportPayload, val cancelled: AtomicBoolean = AtomicBoolean())
    private class Destination(val session: Long, val key: String, val document: SelectedExportDocument)
    private val work = AtomicReference<Work?>()
    @Volatile private var destination: Destination? = null
    private fun available(local: LocalRecordUiState = current()) = !disposed && local.ready && !local.opening &&
        local.error == null && local.supportsOriginalExport
    private fun panel(session: Long) = current().originalExport?.takeIf { !disposed && it.session == session }
    private fun update(session: Long, change: (OriginalExportUiState) -> OriginalExportUiState) = publish { local ->
        val ui = local.originalExport
        if (disposed || ui?.session != session) local else local.copy(originalExport = change(ui))
    }
    private fun updateOwned(session: Long, epoch: Long, phase: OriginalExportPhase, requireAvailable: Boolean = true,
        change: (OriginalExportUiState) -> OriginalExportUiState) = publish { local ->
        val ui = local.originalExport
        if (disposed || (requireAvailable && !available(local)) || ui?.session != session || ui.epoch != epoch || ui.phase != phase) local
        else local.copy(originalExport = change(ui))
    }
    private fun releaseWork() { work.getAndSet(null)?.let { it.cancelled.set(true); it.payload.release() } }
    fun open() {
        val local = current()
        if (!available(local) || local.recycleBin != null || local.originalExport != null || local.originalEdit != null || local.originalHistory != null ||
            local.saving || local.savePending || local.entries.isEmpty()) return
        releaseWork(); destination = null
        val ui = OriginalExportUiState(sessions.incrementAndGet())
        publish { latest -> if (available(latest) && latest.originalExport == null) latest.copy(originalExport = ui) else latest }
    }
    fun toggle(session: Long, id: String, revision: Int) {
        val ui = panel(session) ?: return
        if (!available() || ui.phase != OriginalExportPhase.SELECTING ||
            current().entries.none { it.id == id && it.currentRevision == revision }) return
        val old = ui.selection.firstOrNull { it.sourceId == id }
        if (old == null && ui.selection.size >= OriginalExports.MAX_RECORDS) {
            update(session) { it.copy(error = "一次最多选择 20 条原文，请减少选择。") }; return
        }
        update(session) { it.copy(selection = if (old == null) it.selection + SourceVersion(id, revision.toLong())
            else it.selection.filterNot { source -> source.sourceId == id }, snapshot = null, riskAccepted = false, error = null, notice = null) }
    }
    fun format(session: Long, value: OriginalExportFormat) {
        val ui = panel(session) ?: return
        if (!available() || ui.busy || ui.phase == OriginalExportPhase.PICKING || ui.hasFile) return
        releaseWork(); updateOwned(session, ui.epoch, ui.phase) { it.copy(format = value, riskAccepted = false,
            requestKey = null, pickerLaunched = false, notice = null, epoch = it.epoch + 1) }
    }
    fun prepare(session: Long, refreshCurrent: Boolean = false) {
        val ui = panel(session) ?: return
        if (!available() || ui.busy || ui.phase == OriginalExportPhase.PICKING || ui.hasFile || ui.selection.isEmpty()) return
        val epoch = ui.epoch + 1
        releaseWork()
        update(session) { it.copy(phase = OriginalExportPhase.PREPARING, snapshot = null, riskAccepted = false,
            requestKey = null, pickerLaunched = false, error = null, notice = null, epoch = epoch) }
        if (!enqueue { store ->
            if (!owns(session, epoch, OriginalExportPhase.PREPARING)) return@enqueue
            try {
                val snapshot = checkNotNull(store.originalExport).capture(ui.selection, UUID.randomUUID().toString(), clock.millis(), refreshCurrent)
                if (!available()) throw OriginalExportStorageFailure()
                updateOwned(session, epoch, OriginalExportPhase.PREPARING) { it.copy(phase = OriginalExportPhase.PREVIEW, snapshot = snapshot,
                    selection = snapshot.records.map { r -> SourceVersion(r.entry.id, r.entry.currentRevision.toLong()) }, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(session, epoch, failure) }
        }) fail(session, epoch, IllegalStateException())
    }
    fun acceptRisk(session: Long, snapshotId: String, format: OriginalExportFormat, epoch: Long, accepted: Boolean) {
        val ui = panel(session) ?: return
        if (ui.snapshot?.id != snapshotId || ui.format != format || ui.epoch != epoch || ui.hasFile) return
        updateOwned(session, epoch, OriginalExportPhase.PREVIEW) {
            if (it.snapshot?.id != snapshotId || it.format != format || it.hasFile) it else it.copy(riskAccepted = accepted, error = null)
        }
    }
    fun choose(session: Long, snapshotId: String, format: OriginalExportFormat, expectedEpoch: Long) {
        val ui = panel(session) ?: return
        val snapshot = ui.snapshot ?: return
        if (!available() || ui.phase != OriginalExportPhase.PREVIEW || !ui.riskAccepted || ui.hasFile ||
            snapshot.id != snapshotId || ui.format != format || ui.epoch != expectedEpoch) return
        val epoch = ui.epoch + 1
        updateOwned(session, expectedEpoch, OriginalExportPhase.PREVIEW) {
            if (!it.riskAccepted || it.snapshot?.id != snapshotId || it.format != format || it.hasFile) it
            else it.copy(phase = OriginalExportPhase.CHECKING, error = null, notice = null, epoch = epoch)
        }
        if (!enqueue { store ->
            var payload: OriginalExportPayload? = null
            var candidate: Work? = null
            try {
                if (!owns(session, epoch, OriginalExportPhase.CHECKING)) return@enqueue
                if (!available()) throw OriginalExportStorageFailure()
                if (!checkNotNull(store.originalExport).isCurrent(snapshot)) throw OriginalExportConflict()
                payload = OriginalExportCodec.render(snapshot, ui.format)
                val ready = Work(session, UUID.randomUUID().toString(), snapshot, payload)
                candidate = ready
                if (!work.compareAndSet(null, ready)) return@enqueue
                updateOwned(session, epoch, OriginalExportPhase.CHECKING) {
                    if (ready.cancelled.get() || work.get() !== ready) it else
                        it.copy(phase = OriginalExportPhase.PICKING, requestKey = ready.key, pickerLaunched = false)
                }
                if (panel(session)?.let { it.epoch == epoch && it.phase == OriginalExportPhase.PICKING && it.requestKey == ready.key } == true &&
                    !ready.cancelled.get() && work.get() === ready) {
                    payload = null; candidate = null // The published UUID now owns this payload.
                }
                else if (owns(session, epoch, OriginalExportPhase.CHECKING) && !available()) throw OriginalExportStorageFailure()
            } catch (cancelled: CancellationException) { payload?.release(); throw cancelled }
            catch (failure: Exception) { payload?.release(); fail(session, epoch, failure) }
            finally {
                candidate?.let { work.compareAndSet(it, null); it.cancelled.set(true) }
                payload?.release()
            }
        }) fail(session, epoch, IllegalStateException())
    }
    fun claimPicker(session: Long, key: String): Boolean {
        val ui = panel(session) ?: return false
        if (!available() || ui.phase != OriginalExportPhase.PICKING || ui.requestKey != key || ui.pickerLaunched ||
            work.get()?.let { it.session == session && it.key == key && !it.cancelled.get() } != true) return false
        updateOwned(session, ui.epoch, OriginalExportPhase.PICKING) {
            if (it.requestKey != key || it.pickerLaunched) it else it.copy(pickerLaunched = true)
        }
        return panel(session)?.let { it.requestKey == key && it.pickerLaunched && it.phase == OriginalExportPhase.PICKING } == true
    }
    fun pickerFailed(session: Long, key: String) {
        val ui = panel(session) ?: return
        if (ui.phase != OriginalExportPhase.PICKING || ui.requestKey != key) return
        releaseWork(); fail(session, ui.epoch, IllegalStateException())
    }
    fun picked(session: Long, key: String, document: SelectedExportDocument?) {
        val ui = panel(session) ?: return
        val active = work.get() ?: return
        if (ui.phase != OriginalExportPhase.PICKING || ui.requestKey != key || active.session != session || active.key != key) return
        if (document == null) {
            releaseWork(); update(session) { it.copy(phase = OriginalExportPhase.PREVIEW, riskAccepted = false,
                requestKey = null, pickerLaunched = false, notice = "已取消目的地选择，未执行写入。") }; return
        }
        val target = Destination(session, key, document); destination = target
        update(session) { it.copy(hasFile = true, phase = OriginalExportPhase.WRITING, error = null, notice = null) }
        if (!available()) { releaseWork(); fail(session, ui.epoch, IllegalStateException()); return }
        if (!enqueue { store ->
            try {
                if (!writing(active)) throw OriginalExportCancelled()
                if (!checkNotNull(store.originalExport).isCurrent(active.snapshot)) throw OriginalExportConflict()
                OriginalExportIo.writeAndVerify(target.document, active.payload) { writing(active) }
                if (!writing(active)) throw OriginalExportCancelled()
                val fresh = try { store.originalExport?.isCurrent(active.snapshot) }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                updateOwned(session, ui.epoch, OriginalExportPhase.WRITING) { state -> if (state.requestKey != key || active.cancelled.get()) state else state.copy(
                    phase = OriginalExportPhase.DONE, error = null, riskAccepted = false,
                    notice = when (fresh) {
                        true -> "所选文件已写入并读取核对，与确认快照一致。"
                        false -> "所选文件与确认快照一致；之后的原文或标签变化未包含在这个文件中。"
                        null -> "所选文件与确认快照一致；当前本机原文状态暂不能核对。"
                    }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OriginalExportCancelled) { stopped(session, key) }
            catch (failure: Exception) { if (active.cancelled.get()) stopped(session, key) else fail(session, ui.epoch, failure) }
            finally { active.payload.release(); work.compareAndSet(active, null) }
        }) { releaseWork(); fail(session, ui.epoch, IllegalStateException()) }
    }
    private fun stopped(session: Long, key: String) = update(session) {
        if (it.requestKey != key || it.phase !in setOf(OriginalExportPhase.WRITING, OriginalExportPhase.CANCELLING)) it
        else it.copy(phase = OriginalExportPhase.CANCELLED, riskAccepted = false, error = null,
            notice = "导出已停止。所选文件可能为空或不完整，请检查该文件。")
    }
    private fun writing(active: Work): Boolean = !disposed && !active.cancelled.get() && available() &&
        panel(active.session)?.let { it.requestKey == active.key && it.phase == OriginalExportPhase.WRITING } == true
    fun cancel(session: Long) {
        val ui = panel(session) ?: return
        if (ui.phase == OriginalExportPhase.CLEANING || ui.phase == OriginalExportPhase.DONE) return
        if (ui.phase == OriginalExportPhase.WRITING || ui.phase == OriginalExportPhase.CANCELLING) {
            work.get()?.takeIf { it.session == session }?.cancelled?.set(true)
            update(session) { it.copy(phase = OriginalExportPhase.CANCELLING, notice = "正在停止写入；所选文件可能不完整。") }
        } else {
            releaseWork()
            update(session) { it.copy(phase = OriginalExportPhase.CANCELLED, riskAccepted = false, epoch = it.epoch + 1,
                requestKey = if (it.hasFile) it.requestKey else null, error = null,
                notice = "已取消本次导出；若选择器已经建立文件，可能留下空文件。") }
        }
    }
    /** UI invokes only after explicit confirmation naming the selected export document. */
    fun deleteSelectedFile(session: Long, key: String) {
        val ui = panel(session) ?: return
        val target = destination?.takeIf { it.session == session && it.key == key } ?: return
        if (ui.busy || !ui.hasFile || ui.phase == OriginalExportPhase.DONE || ui.requestKey != key) return
        val epoch = ui.epoch + 1
        update(session) { it.copy(phase = OriginalExportPhase.CLEANING, error = null, notice = null, epoch = epoch) }
        if (!enqueue {
            if (!owns(session, epoch, OriginalExportPhase.CLEANING) || destination !== target) return@enqueue
            try {
                check(target.document.delete())
                if (destination === target) destination = null
                updateOwned(session, epoch, OriginalExportPhase.CLEANING, requireAvailable = false) {
                    if (it.requestKey != key) it else it.copy(phase = if (it.snapshot == null) OriginalExportPhase.SELECTING else OriginalExportPhase.PREVIEW,
                    hasFile = false, riskAccepted = false, requestKey = null, pickerLaunched = false,
                    notice = "所选导出文件已删除。重新导出需再次确认并选择目的地。") }
            } catch (_: Exception) { updateOwned(session, epoch, OriginalExportPhase.CLEANING, requireAvailable = false) { if (it.requestKey != key) it else it.copy(phase = OriginalExportPhase.FAILED,
                error = "无法删除所选文件。请在刚才选择的位置检查，未显示删除成功。") } }
        }) fail(session, epoch, IllegalStateException())
    }
    fun close(session: Long) {
        val ui = panel(session) ?: return
        if (ui.phase == OriginalExportPhase.WRITING || ui.phase == OriginalExportPhase.CANCELLING) { cancel(session); return }
        if (ui.phase == OriginalExportPhase.CLEANING) return
        releaseWork(); destination = null
        publish { local -> if (disposed || local.originalExport?.session != session) local else local.copy(originalExport = null) }
    }
    private fun owns(session: Long, epoch: Long, phase: OriginalExportPhase) = panel(session)?.let { it.epoch == epoch && it.phase == phase } == true
    private fun fail(session: Long, epoch: Long, failure: Exception) = update(session) { ui ->
        if (ui.epoch != epoch) ui else ui.copy(phase = if (failure is OriginalExportConflict) OriginalExportPhase.CHANGED else OriginalExportPhase.FAILED,
            riskAccepted = false, notice = null, error = when (failure) {
                is OriginalExportConflict -> "所选原文或标签已变化，未自动改用新内容。请重新预览最新原文后再确认。"
                is OriginalExportTooLarge -> "选择的数据较多，请关闭后减少选择，再重新预览。"
                else -> if (ui.hasFile) "导出写入尚未确认。所选文件可能为空或不完整，请检查或明确删除后重试。"
                    else "导出准备或选择失败。原文与标签已保留，请重新预览。"
            })
    }
    fun dispose() {
        disposed = true; releaseWork(); destination = null
        // Retire the state owner too: a transform computed before disposal must lose its CAS.
        publish { local -> if (local.originalExport == null) local else local.copy(originalExport = null) }
    }
}
