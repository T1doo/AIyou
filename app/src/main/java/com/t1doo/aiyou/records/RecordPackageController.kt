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
    val notice: String? = null, val error: String? = null, internal val epoch: Long = 0, val mediaPreview: RecordMediaPreview? = null,
    val includeActivities:Boolean = false, val includeHistory: Boolean = false, val includeEditDrafts: Boolean = false, val fullMode:Boolean=false, val fullSummary:FullBackupSummary?=null, val fullDigest:String?=null) {
    val selection = Collections.unmodifiableList(selection.toList())
    val busy get() = phase in setOf(RecordPackagePhase.PREPARING, RecordPackagePhase.ENCRYPTING,
        RecordPackagePhase.WRITING, RecordPackagePhase.READING, RecordPackagePhase.IMPORTING)
    internal fun copy(phase: RecordPackagePhase = this.phase, selection: List<SourceVersion> = this.selection,
        snapshot: OriginalExportSnapshot? = this.snapshot, verified: VerifiedRecordPackage? = this.verified,
        scopeAccepted: Boolean = this.scopeAccepted, requestKey: String? = this.requestKey,
        pickerLaunched: Boolean = this.pickerLaunched, notice: String? = this.notice, error: String? = this.error,
        epoch: Long = this.epoch, mediaPreview: RecordMediaPreview? = this.mediaPreview,
        includeActivities:Boolean = this.includeActivities, includeHistory: Boolean = this.includeHistory,
        includeEditDrafts: Boolean = this.includeEditDrafts, fullMode:Boolean=this.fullMode,
        fullSummary:FullBackupSummary?=this.fullSummary, fullDigest:String?=this.fullDigest) = RecordPackageUiState(session, phase, selection, snapshot, verified, scopeAccepted,
            requestKey, pickerLaunched, notice, error, epoch, mediaPreview,includeActivities,includeHistory,includeEditDrafts,fullMode,fullSummary,fullDigest)
    override fun toString() = "RecordPackageUiState(phase=" + phase + ", redacted)"
}

/** One existing records IO queue; private file/password handles are never UI state or SavedState. */
class RecordPackageController internal constructor(private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean,
    private val cipher: RecordPackageCipher, private val clock: Clock,
    private val onFullRestored:suspend (EntryRepository)->Unit = {}) {
    private class Work(val session: Long, val epoch: Long, val key: String = UUID.randomUUID().toString(),
        val snapshot: OriginalExportSnapshot? = null, val mediaPreview: RecordMediaPreview? = null) {
        val cancelled = AtomicBoolean()
        @Volatile var payload: EncryptedRecordPackage? = null
        @Volatile var mediaPayload: PreparedRecordMediaPackage? = null
        @Volatile var document: SelectedRecordPackageDocument? = null
        @Volatile var verified: VerifiedRecordPackage? = null
        var fullSnapshot:FullBackupSnapshot?=null
        var fullVerified:VerifiedFullBackup?=null
        private var fullOperations=0
        @Synchronized fun beginFull():Boolean { if(cancelled.get()) return false;fullOperations++;return true }
        @Synchronized fun endFull() { check(fullOperations>0);fullOperations--;releaseFull() }
        @Synchronized fun holdFull(value:FullBackupSnapshot):Boolean {
            if(cancelled.get()) { value.release();return false }
            fullSnapshot=value;return true
        }
        @Synchronized fun holdFullVerified(value:VerifiedFullBackup):Boolean {
            if(cancelled.get()) { value.release();return false }
            fullVerified=value;document=null;return true
        }
        private fun releaseFull() { if(cancelled.get() && fullOperations==0) {
            fullSnapshot?.release();fullSnapshot=null;fullVerified?.release();fullVerified=null
        } }
        var ids: List<String> = emptyList()
        private var password: CharArray? = null
        @Synchronized fun setPassword(value: CharArray) { check(!cancelled.get()); password?.fill('\u0000'); password = value.copyOf() }
        @Synchronized fun takePassword(): CharArray = checkNotNull(password).copyOf().also { password?.fill('\u0000'); password = null }
        @Synchronized fun holdPayload(value: EncryptedRecordPackage): Boolean {
            if (cancelled.get()) { value.release(); return false }; payload = value; return true
        }
        @Synchronized fun holdVerified(value: VerifiedRecordPackage): Boolean {
            if (cancelled.get()) { value.release(); return false }
            document = null; verified = value; ids = value.records.map { UUID.randomUUID().toString() }; return true
        }
        @Synchronized fun retire() { cancelled.set(true); password?.fill('\u0000'); password = null; payload?.release(); payload = null; mediaPayload?.release(); mediaPayload = null; document = null; verified?.release(); verified = null;releaseFull() }
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
            local.originalEdit != null || local.originalHistory != null || local.manualTagEditor != null || local.saving || local.savePending ||
            local.taskProposals.editor!=null || local.sourceTask!=null || local.tasks.editor!=null || local.tasks.dateEditor!=null) return
        retire(); val ui = RecordPackageUiState(sessions.incrementAndGet())
        publish { if (available(it) && it.recordPackage == null) it.copy(recordPackage = ui) else it }
    }
    fun selectExport(session: Long) {
        val ui = panel(session) ?: return
        if (!available() || ui.busy) return
        retire(); update(session) { RecordPackageUiState(session, RecordPackagePhase.SELECTING, epoch = it.epoch + 1) }
    }
    fun includeActivities(session:Long,include:Boolean) {
        val ui=panel(session) ?: return
        if(available() && ui.phase==RecordPackagePhase.SELECTING) update(session) {it.copy(includeActivities=include,error=null)}
    }
    fun includeHistory(session: Long, include: Boolean) {
        val ui = panel(session) ?: return
        if (available() && ui.phase == RecordPackagePhase.SELECTING) update(session) { it.copy(includeHistory = include,
            includeEditDrafts = include && it.includeEditDrafts, error = null) }
    }
    fun includeEditDrafts(session: Long, include: Boolean) {
        val ui = panel(session) ?: return
        if (available() && ui.phase == RecordPackagePhase.SELECTING) update(session) { it.copy(includeEditDrafts = include,
            includeHistory = include || it.includeHistory, error = null) }
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
        if (!available() || ui.phase != RecordPackagePhase.SELECTING || ui.selection.isEmpty()&&!ui.includeActivities) return
        if (ui.includeHistory && ui.selection.isEmpty()) {
            update(session) { it.copy(error = "完整历史范围至少需要选择一条原文；只迁移任务时请关闭历史选项。") }; return
        }
        val active = Work(session, ui.epoch + 1); retire(); work.set(active)
        update(session) { it.copy(phase = RecordPackagePhase.PREPARING, epoch = active.epoch, error = null) }
        if (!enqueue { store ->
            if (!owned(active, RecordPackagePhase.PREPARING)) return@enqueue
            try {
                val snapshot = if(ui.selection.isEmpty()) OriginalExportSnapshot(UUID.randomUUID().toString(),clock.millis(),emptyList(),true)
                    else checkNotNull(store.originalExport).capture(ui.selection, UUID.randomUUID().toString(), clock.millis())
                val preview = when {
                    ui.includeEditDrafts -> checkNotNull(store.mediaPackages).previewEditDrafts(snapshot, ui.includeActivities)
                    ui.includeHistory -> checkNotNull(store.mediaPackages).previewHistory(snapshot, ui.includeActivities)
                    ui.includeActivities -> checkNotNull(store.mediaPackages).previewActivities(snapshot)
                    else -> store.mediaPackages?.preview(snapshot)
                }
                check((preview?.history != null) == ui.includeHistory)
                check((preview?.editDrafts != null) == ui.includeEditDrafts)
                updateOwned(active, RecordPackagePhase.PREPARING) { it.copy(phase = RecordPackagePhase.EXPORT_PREVIEW, snapshot = snapshot, mediaPreview = preview) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { fail(active, "原文、历史、原图、任务或草稿核对失败，也可能没有选择任何已保存内容或超限。缺失、损坏、未保存完成或超限的内容不会被跳过，请检查后重新选择。") }
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
            if (!available() || ui.phase != RecordPackagePhase.EXPORT_PREVIEW || !ui.scopeAccepted || snapshot.id != snapshotId ||
                ui.includeHistory != (ui.mediaPreview?.history != null) ||
                ui.includeEditDrafts != (ui.mediaPreview?.editDrafts != null)) return
            try { RecordPackages.password(password); require(password.contentEquals(again)) }
            catch (_: Exception) { update(session) { it.copy(error = "请输入两次一致的独立恢复口令：12–128 个可打印 ASCII 字符，空格原样保留。") }; return }
            val active = Work(session, ui.epoch + 1, snapshot = snapshot, mediaPreview = ui.mediaPreview); active.setPassword(password); retire(); work.set(active)
            update(session) { it.copy(phase = RecordPackagePhase.ENCRYPTING, epoch = active.epoch, error = null) }
            if (!enqueue { store ->
                try {
                    if (!owned(active, RecordPackagePhase.ENCRYPTING)) return@enqueue
                    if (!checkNotNull(store.originalExport).isCurrent(snapshot)) throw OriginalExportConflict()
                    val media = store.mediaPackages
                    if (media != null) {
                        val preview = checkNotNull(active.mediaPreview)
                        val prepared = media.seal(preview,active.takePassword())
                        synchronized(active) {
                            if (active.cancelled.get()) prepared.release() else active.mediaPayload = prepared
                        }
                        if (!owned(active, RecordPackagePhase.ENCRYPTING)) { prepared.release(); return@enqueue }
                    } else {
                        val payload = cipher.seal(snapshot, active.takePassword())
                        if (!owned(active, RecordPackagePhase.ENCRYPTING)) { payload.release(); return@enqueue }
                        if (!active.holdPayload(payload)) return@enqueue
                    }
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
            requestKey = active.key, epoch = active.epoch, fullMode=ui.fullMode) }
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
        if(ui.fullMode) { writeFull(active,document);return }
        updateOwned(active, RecordPackagePhase.PICKING_EXPORT) { it.copy(phase = RecordPackagePhase.WRITING) }
        if (!enqueue { store ->
            try {
                if (!owned(active, RecordPackagePhase.WRITING)) return@enqueue
                if (!checkNotNull(store.originalExport).isCurrent(checkNotNull(active.snapshot))) throw OriginalExportConflict()
                if (active.mediaPayload != null) {
                    val media = checkNotNull(store.mediaPackages); val preview = checkNotNull(active.mediaPreview)
                    check(media.isCurrent(preview))
                    RecordPackageIo.writeMediaAndVerify(document,checkNotNull(active.mediaPayload)) { owned(active, RecordPackagePhase.WRITING) }
                    check(media.isCurrent(preview))
                } else RecordPackageIo.writeAndVerify(document, checkNotNull(active.payload)) { owned(active, RecordPackagePhase.WRITING) }
                updateOwned(active, RecordPackagePhase.WRITING) { it.copy(phase = RecordPackagePhase.DONE,
                    snapshot = null, mediaPreview = null, scopeAccepted = false, notice = "加密包已写入并完整读回核对。它包含确认的有限快照，不保证文件服务的后续同步。") }
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
            if(ui.fullMode) { authenticateFull(active,password);return }
            try { RecordPackages.password(password) } catch (_: Exception) { update(session) { it.copy(error = "恢复口令须为 12–128 个可打印 ASCII 字符，空格原样保留。") }; return }
            active.setPassword(password); updateOwned(active, RecordPackagePhase.RESTORE_PASSWORD) { it.copy(phase = RecordPackagePhase.READING, error = null) }
            if (!enqueue { store ->
                var bytes: ByteArray? = null
                try {
                    if (!owned(active, RecordPackagePhase.READING)) return@enqueue
                    val document = checkNotNull(active.document)
                    val media = store.mediaPackages
                    val verified = if (media != null) media.open({ RecordPackageIo.openRead(document) { owned(active) } },active.takePassword()) else {
                        bytes = RecordPackageIo.read(document) { owned(active, RecordPackagePhase.READING) }
                        cipher.open(checkNotNull(bytes), active.takePassword())
                    }
                    if (!owned(active, RecordPackagePhase.READING)) { verified.release(); return@enqueue }
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
                val result = store.mediaPackages?.restore(verified, active.ids, clock.millis())
                    ?: checkNotNull(store.recordPackages).restore(verified, active.ids, clock.millis())
                val entries = store.entries(); val tags = checkNotNull(store.manualTags).snapshots()
                val favorites = readFavoriteSource(store)
                val eventDates = readEventDateSource(store)
                val people = try { store.manualPeople?.snapshots() }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                val origins = checkNotNull(store.recordPackages).origins()
                val tasks=if(verified.activities!=null) checkNotNull(store.manualTasks).snapshots() else null
                val proposals=if(verified.activities!=null) checkNotNull(store.taskProposals).list() else null
                val versions=if(verified.activities!=null) store.localProcessing?.versions() else null
                val editIndex = if (verified.editDrafts != null) try { checkNotNull(store.editDrafts).pending() }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } else null
                publish { local ->
                    val panel = local.recordPackage
                    if (disposed || !available(local)) local else
                        local.copy(entries = entries, manualTags = tags, manualTagsAvailable = true, manualTagsError = null,
                            manualPeople = people.orEmpty(), manualPeopleAvailable = people != null,
                            manualPeopleError = if (store.manualPeople != null && people == null) "手工人物关联暂不可读取，请重试。" else null,
                            packageOrigins = origins, packageOriginsAvailable = true, packageOriginsError = null,
                            tasks=if(tasks!=null && !local.tasks.busy && local.tasks.pending==null && !local.tasks.loading && local.tasks.editor==null && local.tasks.dateEditor==null)
                                local.tasks.copy(items=tasks,available=true,error=null) else local.tasks,
                            taskProposals=if(proposals!=null && local.taskProposals.editor==null) local.taskProposals.copy(items=proposals,available=true,loading=false,error=null) else local.taskProposals,
                            localProcessingVersions=versions ?: local.localProcessingVersions,
                            localProcessingAvailable=if(versions!=null) true else local.localProcessingAvailable,
                            pendingEdits = if (verified.editDrafts != null && editIndex != null) editIndex.drafts else local.pendingEdits,
                            editDraftIndexAvailable = if (verified.editDrafts != null) editIndex != null else local.editDraftIndexAvailable,
                            editDraftIssueCount = editIndex?.unreadableCount ?: local.editDraftIssueCount,
                            editDraftError = if (verified.editDrafts == null) local.editDraftError else if (editIndex == null)
                                "编辑草稿目录暂不可读取，恢复列表尚未确认。加密数据已保留，请重试本机读取。"
                                else if (editIndex.unreadableCount > 0) "${editIndex.unreadableCount} 份编辑草稿暂无法安全恢复；加密数据已保留，请重试读取。" else null,
                            recordPackage = if (!owned(active, RecordPackagePhase.IMPORTING, local)) panel else checkNotNull(panel).copy(phase = RecordPackagePhase.DONE, verified = null, notice =
                                if (result.replayed) "已核对第一次恢复回执，没有新增、覆盖或复活记录、任务或草稿。已永久删除 " + result.deletedCount +
                                    " 条，删除未完成 " + result.erasingCount + " 条；其余副本以当前本机状态为准。"
                                else "已核对恢复回执：首次导入 " + result.ids.size + " 条记录新副本、"+result.taskIds.size+" 条正式待办和 "+result.proposalIds.size+
                                    " 条待办建议草稿、" + verified.editDrafts.orEmpty().size + " 份独立记录编辑草稿；正式原文未提交编辑内容，可从恢复编辑草稿入口继续。当前内容与状态以本机清单为准。记录初始不参与 AI。本次恢复没有自动确认草稿、安排提醒或恢复联网授权。"))
                            .withFavoriteSource(favorites).withEventDateSource(eventDates)
                }
                if (work.compareAndSet(active, null)) active.retire()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                updateOwned(active, RecordPackagePhase.IMPORTING) { it.copy(phase = RecordPackagePhase.UNCERTAIN,
                    error = "恢复结果尚未确认。请核对同一包的稳定回执；不会另建一批副本。", notice = null) }
            }
        }) { updateOwned(active, RecordPackagePhase.IMPORTING) { it.copy(phase = RecordPackagePhase.UNCERTAIN, error = "恢复结果尚未确认，请保留同一包并重试核对。") } }
    }
    fun fullHome(session:Long) {
        val ui=panel(session) ?: return
        if(!available() || !current().supportsFullBackup || ui.busy) return
        retire();update(session) { RecordPackageUiState(session,epoch=it.epoch+1,fullMode=true) }
    }
    fun prepareFull(session:Long) {
        val ui=panel(session) ?: return
        if(!available() || !ui.fullMode || ui.busy) return
        val active=Work(session,ui.epoch+1);retire();work.set(active)
        update(session) { it.copy(phase=RecordPackagePhase.PREPARING,epoch=active.epoch,fullSummary=null,scopeAccepted=false,error=null,notice=null) }
        if(!enqueue { store ->
            if(!owned(active,RecordPackagePhase.PREPARING) || !active.beginFull()) return@enqueue
            try {
                val snapshot=checkNotNull(store.fullBackups).capture(UUID.randomUUID().toString(),clock.millis())
                if(!active.holdFull(snapshot)) return@enqueue
                updateOwned(active,RecordPackagePhase.PREPARING) { it.copy(phase=RecordPackagePhase.EXPORT_PREVIEW,fullSummary=snapshot.summary) }
            } catch(c:CancellationException) { throw c } catch(_:Exception) { fail(active,"完整范围核对失败：可能存在超限、未完成操作、缺失或损坏文件。没有截断或跳过内容，源资料已保留。") }
            finally { active.endFull() }
        }) fail(active,"本机操作暂不可用。")
    }
    fun acceptFullScope(session:Long,id:String,accepted:Boolean) {
        val ui=panel(session) ?: return
        if(available() && ui.fullMode && ui.phase==RecordPackagePhase.EXPORT_PREVIEW && ui.fullSummary?.backupId==id)
            update(session) { it.copy(scopeAccepted=accepted) }
    }
    fun encryptFull(session:Long,id:String,password:CharArray,again:CharArray) {
        try {
            val ui=panel(session) ?: return;val active=work.get() ?: return
            if(!owned(active,RecordPackagePhase.EXPORT_PREVIEW) || !ui.fullMode || !ui.scopeAccepted || ui.fullSummary?.backupId!=id) return
            try { RecordPackages.password(password);require(password.contentEquals(again)) }
            catch(_:Exception) { update(session) { it.copy(error="请输入两次一致的独立恢复口令：12–128 个可打印 ASCII 字符，空格原样保留。") };return }
            active.setPassword(password);updateOwned(active,RecordPackagePhase.EXPORT_PREVIEW) { it.copy(phase=RecordPackagePhase.ENCRYPTING,error=null) }
            if(!enqueue { store ->
                if(!owned(active,RecordPackagePhase.ENCRYPTING) || !active.beginFull()) return@enqueue
                try {
                    val prepared=checkNotNull(store.fullBackups).seal(checkNotNull(active.fullSnapshot),active.takePassword())
                    synchronized(active) { if(active.cancelled.get()) prepared.release() else active.mediaPayload=prepared }
                    updateOwned(active,RecordPackagePhase.ENCRYPTING) { it.copy(phase=RecordPackagePhase.PICKING_EXPORT,requestKey=active.key,pickerLaunched=false) }
                } catch(c:CancellationException) { throw c } catch(_:Exception) { fail(active,"完整备份加密或库存核对失败，请重新预览。") }
                finally { active.endFull() }
            }) fail(active,"本机操作暂不可用。")
        } finally { password.fill('\u0000');again.fill('\u0000') }
    }
    private fun writeFull(active:Work,document:SelectedRecordPackageDocument) {
        updateOwned(active,RecordPackagePhase.PICKING_EXPORT) { it.copy(phase=RecordPackagePhase.WRITING) }
        if(!enqueue {
            if(!owned(active,RecordPackagePhase.WRITING) || !active.beginFull()) return@enqueue
            try {
                RecordPackageIo.writeMediaAndVerify(document,checkNotNull(active.mediaPayload),maximumBytes=FULL_BACKUP_FILE_LIMIT) { owned(active,RecordPackagePhase.WRITING) }
                updateOwned(active,RecordPackagePhase.WRITING) { it.copy(phase=RecordPackagePhase.DONE,scopeAccepted=false,
                    notice="完整资料库快照已加密写入，并完整读回核对。文件服务的后续同步不在此保证内。") }
                if(work.compareAndSet(active,null)) active.retire()
            } catch(c:CancellationException) { throw c } catch(_:Exception) { fail(active,"写入或读回核对失败；可能留下空文件或部分密文，已保留。源资料未改变。") }
            finally { active.endFull() }
        }) fail(active,"本机操作暂不可用；所选文件已保留。")
    }
    private fun authenticateFull(active:Work,password:CharArray) {
        try { RecordPackages.password(password) } catch(_:Exception) { update(active.session) { it.copy(error="恢复口令须为 12–128 个可打印 ASCII 字符。") };return }
        active.setPassword(password);updateOwned(active,RecordPackagePhase.RESTORE_PASSWORD) { it.copy(phase=RecordPackagePhase.READING,error=null) }
        if(!enqueue { store ->
            if(!owned(active,RecordPackagePhase.READING) || !active.beginFull()) return@enqueue
            try {
                val document=checkNotNull(active.document)
                val verified=checkNotNull(store.fullBackups).open({RecordPackageIo.openRead(document) { owned(active) }},active.takePassword())
                if(!active.holdFullVerified(verified)) return@enqueue
                updateOwned(active,RecordPackagePhase.READING) { it.copy(phase=RecordPackagePhase.RESTORE_PREVIEW,fullSummary=verified.summary,fullDigest=verified.digest,requestKey=null) }
            } catch(c:CancellationException) { throw c } catch(_:Exception) { fail(active,"口令、完整性或完整备份格式核对失败。没有导入资料，文件已保留。") }
            finally { active.endFull() }
        }) fail(active,"本机操作暂不可用。")
    }
    fun restoreFull(session:Long,id:String,digest:String,epoch:Long) {
        val ui=panel(session) ?: return;val active=work.get() ?: return
        if(!owned(active) || !ui.fullMode || ui.phase !in setOf(RecordPackagePhase.RESTORE_PREVIEW,RecordPackagePhase.UNCERTAIN) ||
            ui.epoch!=epoch || ui.fullSummary?.backupId!=id || ui.fullDigest!=digest) return
        if(current().text.isNotEmpty() || current().mixedCapture?.let { it.text.isNotEmpty() || it.pendingEdits>0 || it.busy || it.selection!=null || it.uncertainSave || it.confirmDiscard || it.discardPending || it.policyChange!=null }==true) {
            update(session) { it.copy(error="当前输入尚非空，恢复已暂停；请先保全现有草稿。") };return
        }
        updateOwned(active,ui.phase) { it.copy(phase=RecordPackagePhase.IMPORTING,error=null,notice="只导入空资料库；已开始的事务可能在退出后完成。") }
        if(!enqueue { store ->
            if(!owned(active,RecordPackagePhase.IMPORTING) || !active.beginFull()) return@enqueue
            try {
                var completed=false
                val verified=checkNotNull(active.fullVerified)
                val backups=checkNotNull(store.fullBackups)
                val result=try {
                    backups.restore(verified) { owned(active,RecordPackagePhase.IMPORTING) }.also { completed=true }
                } finally {
                    if(!completed) completed=backups.completed(verified)
                    refreshFullSources(store,completed)
                }
                updateOwned(active,RecordPackagePhase.IMPORTING) { it.copy(phase=RecordPackagePhase.DONE,fullDigest=null,notice=
                    if(result.replayed) "已核对同一备份的完成回执。后来的编辑和删除保持当前状态，没有重新安装资料。"
                    else "已核对完整资料库恢复回执。原身份、回收站、版本与草稿保持原义；联网授权已清空，提醒须重新确认。") }
                if(work.compareAndSet(active,null)) active.retire()
            } catch(c:CancellationException) { throw c } catch(_:Exception) {
                updateOwned(active,RecordPackagePhase.IMPORTING) { it.copy(phase=RecordPackagePhase.UNCERTAIN,
                    error="恢复尚未确认：目标可能非空、发生变化或文件发布未完成。资料和失败密文已保留；仅可核对同一备份回执，不会覆盖或删除现有库。",notice=null) }
            } finally { active.endFull() }
        }) updateOwned(active,RecordPackagePhase.IMPORTING) { it.copy(phase=RecordPackagePhase.UNCERTAIN,error="恢复尚未确认，请保留同一备份并重试核对。") }
    }
    /** Data belongs to the still-open records owner even when its importing page has retired.
     * A lost commit ACK still requires reading actual committed rows; only panel completion is session-scoped. */
    private suspend fun refreshFullSources(store:EntryRepository,completed:Boolean) {
        if(!available()) return
        val entries=store.entries()
        publish { local -> if(!available(local)) local else local.copy(entries=entries,manualTagsAvailable=false,
            manualPeopleAvailable=false,packageOriginsAvailable=false,editDraftIndexAvailable=false,localProcessingAvailable=false,
            manualTagsError="恢复后的手工资料尚在核对；读取失败时请重试本机读取。") }
        if(completed && available()) onFullRestored(store)
        val tags=store.manualTags?.snapshots().orEmpty()
        val people=store.manualPeople?.snapshots().orEmpty();val favorites=readFavoriteSource(store);val dates=readEventDateSource(store)
        val origins=store.recordPackages?.origins().orEmpty();val pending=store.editDrafts?.pending()
        val versions=store.localProcessing?.versions().orEmpty()
        publish { local -> if(!available(local)) local else local.copy(entries=entries,manualTags=tags,manualPeople=people,
            packageOrigins=origins,pendingEdits=pending?.drafts.orEmpty(),localProcessingVersions=versions,localSearchEpoch=null,
            manualTagsAvailable=true,manualTagsError=null,manualPeopleAvailable=true,manualPeopleError=null,
            packageOriginsAvailable=true,packageOriginsError=null,editDraftIndexAvailable=true,editDraftError=null,localProcessingAvailable=true)
                .withFavoriteSource(favorites).withEventDateSource(dates) }
    }
    companion object { const val FULL_BACKUP_FILE_LIMIT=80L*1024*1024+16384 }
    private fun fail(active: Work, message: String) {
        val sourcePhase = panel(active.session)?.phase
        publish { local ->
            val ui = local.recordPackage
            if (disposed || active.cancelled.get() || work.get() !== active || ui?.session != active.session ||
                ui.epoch != active.epoch || ui.phase != sourcePhase) local else local.copy(recordPackage =
                ui.copy(phase = RecordPackagePhase.FAILED, snapshot = null, verified = null, mediaPreview = null, scopeAccepted = false, error = message))
        }
        if (work.compareAndSet(active, null)) active.retire()
    }
    fun cancel(session: Long) {
        val ui = panel(session) ?: return; retire()
        update(session) { if (it.epoch != ui.epoch || it.phase != ui.phase) it else it.copy(phase = RecordPackagePhase.CANCELLED, snapshot = null, verified = null, mediaPreview = null, scopeAccepted = false,
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
