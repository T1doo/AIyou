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

enum class RecordPackagePhase { HOME, SELECTING, PREPARING, EXPORT_PREVIEW, ENCRYPTING, PICKING_EXPORT, WRITING,
    PICKING_RESTORE, RESTORE_PASSWORD, READING, RESTORE_PREVIEW, IMPORTING, UNCERTAIN, DONE, FAILED, CANCELLED }
class RecordPackageUiState(val session: Long, val phase: RecordPackagePhase = RecordPackagePhase.HOME,
    selection: List<SourceVersion> = emptyList(), val snapshot: OriginalExportSnapshot? = null,
    val verified: VerifiedRecordPackage? = null, val scopeAccepted: Boolean = false,
    val requestKey: String? = null, val pickerLaunched: Boolean = false,
    val notice: String? = null, val error: String? = null, internal val epoch: Long = 0) {
    val selection = Collections.unmodifiableList(selection.toList())
    val busy get() = phase in setOf(RecordPackagePhase.PREPARING, RecordPackagePhase.ENCRYPTING,
        RecordPackagePhase.WRITING, RecordPackagePhase.READING, RecordPackagePhase.IMPORTING)
    internal fun copy(phase: RecordPackagePhase = this.phase, selection: List<SourceVersion> = this.selection,
        snapshot: OriginalExportSnapshot? = this.snapshot, verified: VerifiedRecordPackage? = this.verified,
        scopeAccepted: Boolean = this.scopeAccepted, requestKey: String? = this.requestKey,
        pickerLaunched: Boolean = this.pickerLaunched, notice: String? = this.notice, error: String? = this.error,
        epoch: Long = this.epoch) = RecordPackageUiState(session, phase, selection, snapshot, verified, scopeAccepted,
            requestKey, pickerLaunched, notice, error, epoch)
    override fun toString() = "RecordPackageUiState(phase=" + phase + ", redacted)"
}

/** One existing records IO queue; private file/password handles are never UI state or SavedState. */
class RecordPackageController internal constructor(private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean,
    private val cipher: RecordPackageCipher, private val clock: Clock) {
    private class Work(val session: Long, val epoch: Long, val key: String = UUID.randomUUID().toString(),
        val snapshot: OriginalExportSnapshot? = null) {
        val cancelled = AtomicBoolean()
        @Volatile var payload: EncryptedRecordPackage? = null
        @Volatile var document: SelectedRecordPackageDocument? = null
        @Volatile var verified: VerifiedRecordPackage? = null
        var ids: List<String> = emptyList()
        private var password: CharArray? = null
        @Synchronized fun setPassword(value: CharArray) { check(!cancelled.get()); password?.fill('\u0000'); password = value.copyOf() }
        @Synchronized fun takePassword(): CharArray = checkNotNull(password).copyOf().also { password?.fill('\u0000'); password = null }
        @Synchronized fun holdPayload(value: EncryptedRecordPackage): Boolean {
            if (cancelled.get()) { value.release(); return false }; payload = value; return true
        }
        @Synchronized fun holdVerified(value: VerifiedRecordPackage): Boolean {
            if (cancelled.get()) return false
            document = null; verified = value; ids = value.records.map { UUID.randomUUID().toString() }; return true
        }
        @Synchronized fun retire() { cancelled.set(true); password?.fill('\u0000'); password = null; payload?.release(); payload = null; document = null; verified = null }
    }
    private val sessions = AtomicLong(); private val work = AtomicReference<Work?>()
    @Volatile private var disposed = false
    private var pickerOwner: Any? = null
    private fun available(local: LocalRecordUiState = current()) = !disposed && local.ready && !local.opening &&
        local.error == null && local.supportsRecordPackages
    private fun panel(session: Long) = current().recordPackage?.takeIf { !disposed && it.session == session }
    private fun update(session: Long, change: (RecordPackageUiState) -> RecordPackageUiState) = publish { local ->
        val ui = local.recordPackage
        if (disposed || ui?.session != session) local else local.copy(recordPackage = change(ui))
    }
    private fun owned(active: Work, phase: RecordPackagePhase? = null, local: LocalRecordUiState = current()) =
        available(local) && !active.cancelled.get() && work.get() === active &&
        local.recordPackage?.let { it.session == active.session && it.epoch == active.epoch && (phase == null || it.phase == phase) } == true
    private fun updateOwned(active: Work, phase: RecordPackagePhase, change: (RecordPackageUiState) -> RecordPackageUiState) =
        publish { local -> if (!owned(active, phase, local)) local else local.copy(recordPackage = change(checkNotNull(local.recordPackage))) }
    private fun retire() { work.getAndSet(null)?.retire() }
    fun attachPickerOwner(owner: Any) {
        if (pickerOwner != null && pickerOwner !== owner) current().recordPackage?.let { cancel(it.session) }
        pickerOwner = owner
    }
    fun detachPickerOwner(owner: Any) { if (pickerOwner === owner) { current().recordPackage?.let { cancel(it.session) }; pickerOwner = null } }
    fun open() {
        val local = current()
        if (!available(local) || local.recordPackage != null || local.originalExport != null || local.recycleBin != null ||
            local.originalEdit != null || local.originalHistory != null || local.manualTagEditor != null || local.saving || local.savePending) return
        retire(); val ui = RecordPackageUiState(sessions.incrementAndGet())
        publish { if (available(it) && it.recordPackage == null) it.copy(recordPackage = ui) else it }
    }
    fun selectExport(session: Long) {
        val ui = panel(session) ?: return
        if (!available() || ui.busy || current().entries.isEmpty()) return
        retire(); update(session) { RecordPackageUiState(session, RecordPackagePhase.SELECTING, epoch = it.epoch + 1) }
    }
    fun toggle(session: Long, id: String, revision: Int) {
        val ui = panel(session) ?: return
        if (!available() || ui.phase != RecordPackagePhase.SELECTING || current().entries.none { it.id == id && it.currentRevision == revision }) return
        val selected = ui.selection.any { it.sourceId == id }
        if (!selected && ui.selection.size == 20) { update(session) { it.copy(error = "一次最多选择 20 条原文。") }; return }
        update(session) { it.copy(selection = if (selected) it.selection.filterNot { s -> s.sourceId == id }
            else it.selection + SourceVersion(id, revision.toLong()), error = null) }
    }
    fun prepare(session: Long) {
        val ui = panel(session) ?: return
        if (!available() || ui.phase != RecordPackagePhase.SELECTING || ui.selection.isEmpty()) return
        val active = Work(session, ui.epoch + 1); retire(); work.set(active)
        update(session) { it.copy(phase = RecordPackagePhase.PREPARING, epoch = active.epoch, error = null) }
        if (!enqueue { store ->
            if (!owned(active, RecordPackagePhase.PREPARING)) return@enqueue
            try {
                val snapshot = checkNotNull(store.originalExport).capture(ui.selection, UUID.randomUUID().toString(), clock.millis())
                updateOwned(active, RecordPackagePhase.PREPARING) { it.copy(phase = RecordPackagePhase.EXPORT_PREVIEW, snapshot = snapshot) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { fail(active, "原文或标签读取失败，请重新选择。") }
            finally { if (work.compareAndSet(active, null)) active.retire() }
        }) fail(active, "本机操作暂不可用。")
    }
    fun acceptScope(session: Long, snapshotId: String, accepted: Boolean) {
        val ui = panel(session) ?: return
        if (!available() || ui.phase != RecordPackagePhase.EXPORT_PREVIEW || ui.snapshot?.id != snapshotId) return
        update(session) { if (it.phase == RecordPackagePhase.EXPORT_PREVIEW && it.snapshot?.id == snapshotId) it.copy(scopeAccepted = accepted) else it }
    }
    /** UI supplies only controlled arrays; consume both immediately, including all rejected paths. */
    fun encrypt(session: Long, snapshotId: String, password: CharArray, again: CharArray) {
        try {
            val ui = panel(session) ?: return; val snapshot = ui.snapshot ?: return
            if (!available() || ui.phase != RecordPackagePhase.EXPORT_PREVIEW || !ui.scopeAccepted || snapshot.id != snapshotId) return
            try { RecordPackages.password(password); require(password.contentEquals(again)) }
            catch (_: Exception) { update(session) { it.copy(error = "请输入两次一致的独立恢复口令：12–128 个可打印 ASCII 字符，空格原样保留。") }; return }
            val active = Work(session, ui.epoch + 1, snapshot = snapshot); active.setPassword(password); retire(); work.set(active)
            update(session) { it.copy(phase = RecordPackagePhase.ENCRYPTING, epoch = active.epoch, error = null) }
            if (!enqueue { store ->
                try {
                    if (!owned(active, RecordPackagePhase.ENCRYPTING)) return@enqueue
                    if (!checkNotNull(store.originalExport).isCurrent(snapshot)) throw OriginalExportConflict()
                    val payload = cipher.seal(snapshot, active.takePassword())
                    if (!owned(active, RecordPackagePhase.ENCRYPTING)) { payload.release(); return@enqueue }
                    if (!active.holdPayload(payload)) return@enqueue
                    updateOwned(active, RecordPackagePhase.ENCRYPTING) { it.copy(phase = RecordPackagePhase.PICKING_EXPORT, requestKey = active.key, pickerLaunched = false) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { fail(active, "加密或快照核对失败，请重新预览。") }
            }) fail(active, "本机操作暂不可用。")
        } finally { password.fill('\u0000'); again.fill('\u0000') }
    }
    fun chooseRestore(session: Long) {
        val ui = panel(session) ?: return
        if (!available() || ui.busy) return
        val active = Work(session, ui.epoch + 1); retire(); work.set(active)
        update(session) { RecordPackageUiState(session, RecordPackagePhase.PICKING_RESTORE,
            requestKey = active.key, epoch = active.epoch) }
    }
    fun claimPicker(session: Long, key: String): Boolean {
        val ui = panel(session) ?: return false; val active = work.get() ?: return false
        if (!owned(active) || active.key != key || ui.requestKey != key || ui.pickerLaunched ||
            ui.phase !in setOf(RecordPackagePhase.PICKING_EXPORT, RecordPackagePhase.PICKING_RESTORE)) return false
        updateOwned(active, ui.phase) { if (it.requestKey == key && !it.pickerLaunched) it.copy(pickerLaunched = true) else it }
        return panel(session)?.pickerLaunched == true && owned(active)
    }
    fun picked(session: Long, key: String, document: SelectedRecordPackageDocument?) {
        val active = work.get() ?: return; val ui = panel(session) ?: return
        if (!owned(active) || active.key != key || ui.requestKey != key || !ui.pickerLaunched ||
            ui.phase !in setOf(RecordPackagePhase.PICKING_EXPORT, RecordPackagePhase.PICKING_RESTORE)) return
        if (document == null) { cancel(session); return }
        active.document = document
        if (ui.phase == RecordPackagePhase.PICKING_RESTORE) {
            updateOwned(active, RecordPackagePhase.PICKING_RESTORE) { it.copy(phase = RecordPackagePhase.RESTORE_PASSWORD, requestKey = null) }; return
        }
        updateOwned(active, RecordPackagePhase.PICKING_EXPORT) { it.copy(phase = RecordPackagePhase.WRITING) }
        if (!enqueue { store ->
            try {
                if (!owned(active, RecordPackagePhase.WRITING)) return@enqueue
                if (!checkNotNull(store.originalExport).isCurrent(checkNotNull(active.snapshot))) throw OriginalExportConflict()
                RecordPackageIo.writeAndVerify(document, checkNotNull(active.payload)) { owned(active, RecordPackagePhase.WRITING) }
                updateOwned(active, RecordPackagePhase.WRITING) { it.copy(phase = RecordPackagePhase.DONE,
                    snapshot = null, scopeAccepted = false, notice = "加密包已写入并完整读回核对。它包含确认的有限快照，不保证文件服务的后续同步。") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(active, "写入或核对失败，可能留下空文件或部分密文。文件已保留，请检查。") }
            finally { if (work.compareAndSet(active, null)) active.retire() }
        }) fail(active, "本机操作暂不可用；所选文件可能已建立，已保留。")
    }
    fun pickerFailed(session: Long, key: String) { work.get()?.takeIf { it.session == session && it.key == key }?.let { fail(it, "文件选择未完成，请重新选择；可能建立的文件不会自动删除。") } }
    fun authenticate(session: Long, password: CharArray) {
        try {
            val active = work.get() ?: return; val ui = panel(session) ?: return
            if (!owned(active, RecordPackagePhase.RESTORE_PASSWORD)) return
            try { RecordPackages.password(password) } catch (_: Exception) { update(session) { it.copy(error = "恢复口令须为 12–128 个可打印 ASCII 字符，空格原样保留。") }; return }
            active.setPassword(password); updateOwned(active, RecordPackagePhase.RESTORE_PASSWORD) { it.copy(phase = RecordPackagePhase.READING, error = null) }
            if (!enqueue {
                var bytes: ByteArray? = null
                try {
                    if (!owned(active, RecordPackagePhase.READING)) return@enqueue
                    bytes = RecordPackageIo.read(checkNotNull(active.document)) { owned(active, RecordPackagePhase.READING) }
                    val verified = cipher.open(bytes, active.takePassword())
                    if (!owned(active, RecordPackagePhase.READING)) return@enqueue
                    if (!active.holdVerified(verified)) return@enqueue
                    updateOwned(active, RecordPackagePhase.READING) { it.copy(phase = RecordPackagePhase.RESTORE_PREVIEW, verified = verified, requestKey = null) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { fail(active, "口令、完整性或格式核对失败，未导入任何记录。请重新选择加密包。") }
                finally { bytes?.fill(0) }
            }) fail(active, "本机操作暂不可用。")
        } finally { password.fill('\u0000') }
    }
    fun confirmRestore(session: Long, backupId: String, digest: String, epoch: Long) {
        val active = work.get() ?: return; val ui = panel(session) ?: return; val verified = active.verified ?: return
        if (!owned(active) || ui.phase !in setOf(RecordPackagePhase.RESTORE_PREVIEW, RecordPackagePhase.UNCERTAIN) ||
            ui.epoch != epoch || verified.manifest.backupId != backupId || verified.digest != digest || ui.verified !== verified) return
        updateOwned(active, ui.phase) { it.copy(phase = RecordPackagePhase.IMPORTING, error = null, notice = "正在提交并核对本机恢复回执；返回不会撤销已提交事务。") }
        if (!enqueue { store ->
            try {
                if (!owned(active, RecordPackagePhase.IMPORTING)) return@enqueue
                val result = checkNotNull(store.recordPackages).restore(verified, active.ids, clock.millis())
                val entries = store.entries(); val tags = checkNotNull(store.manualTags).snapshots()
                val favorites = readFavoriteSource(store)
                val origins = checkNotNull(store.recordPackages).origins()
                publish { local ->
                    val panel = local.recordPackage
                    if (disposed || !available(local)) local else
                        local.copy(entries = entries, manualTags = tags, manualTagsAvailable = true, manualTagsError = null,
                            packageOrigins = origins, packageOriginsAvailable = true, packageOriginsError = null,
                            recordPackage = if (!owned(active, RecordPackagePhase.IMPORTING, local)) panel else checkNotNull(panel).copy(phase = RecordPackagePhase.DONE, verified = null, notice =
                                if (result.replayed) "已核对第一次恢复回执，没有新增、覆盖或复活记录。已永久删除 " + result.deletedCount +
                                    " 条，删除未完成 " + result.erasingCount + " 条；其余副本以当前本机状态为准。"
                                else "已保存并读回 " + result.ids.size + " 条新副本；初始策略为不参与 AI。原有记录保持不变。"))
                            .withFavoriteSource(favorites)
                }
                if (work.compareAndSet(active, null)) active.retire()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                updateOwned(active, RecordPackagePhase.IMPORTING) { it.copy(phase = RecordPackagePhase.UNCERTAIN,
                    error = "恢复结果尚未确认。请核对同一包的稳定回执；不会另建一批副本。", notice = null) }
            }
        }) { updateOwned(active, RecordPackagePhase.IMPORTING) { it.copy(phase = RecordPackagePhase.UNCERTAIN, error = "恢复结果尚未确认，请保留同一包并重试核对。") } }
    }
    private fun fail(active: Work, message: String) {
        val sourcePhase = panel(active.session)?.phase
        publish { local ->
            val ui = local.recordPackage
            if (disposed || active.cancelled.get() || work.get() !== active || ui?.session != active.session ||
                ui.epoch != active.epoch || ui.phase != sourcePhase) local else local.copy(recordPackage =
                ui.copy(phase = RecordPackagePhase.FAILED, snapshot = null, verified = null, scopeAccepted = false, error = message))
        }
        if (work.compareAndSet(active, null)) active.retire()
    }
    fun cancel(session: Long) {
        val ui = panel(session) ?: return; retire()
        update(session) { if (it.epoch != ui.epoch || it.phase != ui.phase) it else it.copy(phase = RecordPackagePhase.CANCELLED, snapshot = null, verified = null, scopeAccepted = false,
            requestKey = null, epoch = ui.epoch + 1, error = null, notice =
                if (ui.phase == RecordPackagePhase.IMPORTING || ui.phase == RecordPackagePhase.UNCERTAIN)
                    "已退出核对。已开始的事务可能完成；重新认证并确认同一包只核对稳定回执。"
                else "已取消。选择器可能留下空文件或部分密文，文件已保留；不会自动删除。") }
    }
    fun close(session: Long) { if (panel(session) == null) return; retire(); publish { if (it.recordPackage?.session == session) it.copy(recordPackage = null) else it } }
    fun dispose() {
        disposed = true; retire(); pickerOwner = null
        // Mutate the actual state so an already calculated old transform must retry its CAS.
        publish { it.copy(recordPackage = null, ready = false, opening = false) }
    }
}
