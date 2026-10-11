package com.t1doo.aiyou.records

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.domain.StorageKeyUnavailable
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.OriginalEditDraftConflict
import com.t1doo.aiyou.model.OriginalEditDraft
import com.t1doo.aiyou.model.OriginalEditDraftRef
import com.t1doo.aiyou.model.OriginalEditDraftIndex
import com.t1doo.aiyou.model.OriginalEditDraftState
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.TextDraft
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocalRecordUiState(
    val ready: Boolean = false,
    val opening: Boolean = true,
    val saving: Boolean = false,
    val savePending: Boolean = false,
    val text: String = "",
    val entries: List<StoredEntry> = emptyList(),
    val draftNotice: String = "正在读取本机草稿",
    val saveNotice: String? = null,
    val error: String? = null,
    val inputNotice: String? = null,
    val originalEdit: OriginalEditUiState? = null,
    val originalEditNotice: String? = null,
    val originalHistory: OriginalHistoryUiState? = null,
    val supportsEditDrafts: Boolean = false,
    val pendingEdits: List<OriginalEditDraftRef> = emptyList(),
    val editDraftError: String? = null,
    val editDraftIssueCount: Int = 0,
    val editDraftIndexAvailable: Boolean = true,
)

/** All draft writes and saves share one queue, preserving edits around a save boundary. */
class LocalRecordsViewModel internal constructor(
    application: Application,
    private val openStore: () -> EntryRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val decodePng: (ByteArray) -> AttachmentPicture = PlatformImageDecoder::decode,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, { EncryptedEntryRepository.open(application) })
    private val mutableState = MutableStateFlow(LocalRecordUiState())
    val state = mutableState.asStateFlow()
    private val commands = Channel<Command>(Channel.UNLIMITED)
    val attachments = LocalAttachmentController(
        enqueue = { action -> commands.trySend(Command.Attachment(action)).isSuccess }, decodePng = decodePng,
        canUseRecord = { mutableState.value.let { it.ready && !it.opening && it.error == null &&
            !it.saving && !it.savePending } },
        canUsePanel = { it.hasRecordAccess(mutableState.value) })
    private var repository: EntryRepository? = null
    private var persistedDraft: TextDraft? = null
    private val editGeneration = AtomicLong()
    private var pendingCommit: TextDraft? = null
    private val pendingIntent = AtomicReference<SaveIntent?>(null)
    private val originalEditIds = AtomicLong()
    private val originalHistoryIds = AtomicLong()
    private val historyReplacementIds = AtomicLong()
    // IO-queue owned. Once revise returned, a failed readback must never revise again.
    private var committedOriginal: Pair<Long, StoredEntry>? = null
    private data class DraftBinding(val sessionId: Long, var snapshot: OriginalEditDraft,
        var expected: OriginalEditDraft?, var persisted: Boolean, var unacknowledged: OriginalEditDraft? = null)
    private var originalDraft: DraftBinding? = null // IO queue owned, never Bundle/log/plaintext storage.
    @Volatile private var editsDisposed = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                open()
                for (command in commands) {
                    try {
                        when (command) {
                            is Command.Edit -> {
                                persistedDraft = checkNotNull(repository).updateDraft(command.text)
                                if (command.generation == editGeneration.get()) mutableState.update {
                                    it.copy(draftNotice = "草稿已保存在本机", error = null, saveNotice = null)
                                }
                            }
                            is Command.Save -> {
                                if (pendingIntent.get() !== command.intent) continue
                                val store = checkNotNull(repository)
                                val draft = pendingCommit ?: persistedDraft?.takeIf { it.text == command.intent.text }
                                    ?: store.updateDraft(command.intent.text)
                                pendingCommit = draft
                                store.saveDraft(draft, clock.millis(), ZoneId.systemDefault().id)
                                val entries = store.entries()
                                pendingCommit = null
                                persistedDraft = null
                                pendingIntent.compareAndSet(command.intent, null)
                                // Success follows commit; the list is read from the database.
                                mutableState.update { it.copy(text = "", saving = false, savePending = false, entries = entries, draftNotice = "没有未保存的草稿", saveNotice = "已保存在本机", error = null) }
                            }
                            Command.Retry -> open()
                            is Command.RecoverDraft -> {
                                val store = checkNotNull(repository)
                                persistedDraft = store.updateDraft(command.text)
                                val entries = store.entries()
                                mutableState.update { it.copy(opening = false, entries = entries, error = null,
                                    draftNotice = if (command.generation == editGeneration.get()) "草稿已保存在本机" else it.draftNotice,
                                    originalHistory = it.originalHistory?.let { history -> history.copy(
                                        requestId = history.requestId + 1, loading = true, revisions = emptyList(), error = null, unavailable = false,
                                        replacement = null) }) }
                                mutableState.value.originalHistory?.let { commands.trySend(Command.ReadHistory(it.sessionId, it.requestId)) }
                            }
                            is Command.Revise -> reviseOriginal(command)
                            is Command.OpenEdit -> openOriginalDraft(command)
                            is Command.PersistEdit -> persistOriginalDraft(command)
                            is Command.DiscardEdit -> discardOriginalDraft(command.sessionId)
                            is Command.ReleaseEdit -> {
                                if (originalDraft?.sessionId == command.sessionId) originalDraft = null
                                refreshPendingDrafts()
                            }
                            Command.RefreshEditDrafts -> refreshPendingDrafts()
                            is Command.ReadHistory -> readHistory(command)
                            is Command.Attachment -> command.action(checkNotNull(repository))
                        }
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { failed(failure, preserveQueuedSave = command is Command.Edit) }
                }
            } finally {
                editsDisposed = true
                attachments.markDisposed()
                attachments.disposeOnIo()
                (repository as? AutoCloseable)?.close()
                repository = null
            }
        }
    }

    fun changeText(text: String) {
        val current = mutableState.value
        if (!current.ready || current.saving || current.savePending || current.originalEdit != null || current.originalHistory != null || attachments.state.value != null) return
        if (text.length > EncryptedEntryRepository.MAX_TEXT_LENGTH) {
            mutableState.update { it.copy(
                inputNotice = "文本过长，输入长度上限为 ${EncryptedEntryRepository.MAX_TEXT_LENGTH}。本次输入未替换原草稿，请缩短后重试。",
                saveNotice = null,
            ) }
            return
        }
        val generation = editGeneration.incrementAndGet()
        mutableState.update { it.copy(text = text, draftNotice = "正在保存草稿", saveNotice = null, inputNotice = null) }
        commands.trySend(Command.Edit(text, generation))
    }

    fun save() {
        val current = mutableState.value
        if (!current.ready || current.saving || current.text.isBlank() || current.originalEdit != null || current.originalHistory != null || attachments.state.value != null) return
        val intent = pendingIntent.get() ?: SaveIntent(current.text, editGeneration.get()).also { pendingIntent.set(it) }
        mutableState.update { it.copy(saving = true, savePending = true, saveNotice = null, error = null, inputNotice = null) }
        commands.trySend(Command.Save(intent))
    }

    fun retry() {
        val current = mutableState.value
        if (current.editDraftError != null && current.ready) {
            commands.trySend(Command.RefreshEditDrafts)
            if (current.error == null) return
        }
        if (current.originalEdit != null || current.originalHistory != null || attachments.state.value != null) {
            if (!current.ready || current.opening || current.error == null || current.originalEdit?.saving == true) return
            // Recover the prior capture write without replacing this editor or routing through changeText.
            mutableState.update { it.copy(opening = true) }
            commands.trySend(Command.RecoverDraft(current.text, editGeneration.get()))
        } else if (!current.ready && !current.opening) {
            mutableState.update { it.copy(opening = true, error = null) }
            commands.trySend(Command.Retry)
        } else if (mutableState.value.savePending) save()
        else if (mutableState.value.ready) changeText(mutableState.value.text)
    }

    fun beginOriginalEdit(entryId: String, expectedRevision: Int) {
        if (editsDisposed) return
        val current = mutableState.value
        if (!current.ready || current.opening || current.error != null || current.saving || current.savePending || current.originalEdit != null || current.originalHistory != null || attachments.state.value != null) return
        val entry = current.entries.firstOrNull { it.id == entryId && it.currentRevision == expectedRevision } ?: return
        val edit = OriginalEditUiState(originalEditIds.incrementAndGet(), entry.id, entry.currentRevision, entry.originalText,
            loadingDraft = current.supportsEditDrafts)
        mutableState.update { it.copy(originalEdit = edit, originalEditNotice = null) }
        if (current.supportsEditDrafts) commands.trySend(Command.OpenEdit(edit, false))
    }

    fun resumeOriginalEdit(entryId: String, draftId: String) {
        val current = mutableState.value
        if (editsDisposed || !current.ready || current.opening || current.error != null || !current.editDraftIndexAvailable || current.saving || current.savePending ||
            current.originalEdit != null || current.originalHistory != null || attachments.state.value != null) return
        val ref = current.pendingEdits.singleOrNull { it.entryId == entryId && it.id == draftId } ?: return
        val entry = current.entries.firstOrNull { it.id == entryId }
        val edit = OriginalEditUiState(originalEditIds.incrementAndGet(), entryId, entry?.currentRevision ?: ref.baseRevision,
            entry?.originalText.orEmpty(), loadingDraft = true)
        mutableState.update { it.copy(originalEdit = edit, originalEditNotice = null) }
        commands.trySend(Command.OpenEdit(edit, false, draftId))
    }

    fun changeOriginalEdit(sessionId: Long, text: String) {
        if (editsDisposed) return
        var accepted = false
        mutableState.update { current ->
            val edit = current.originalEdit
            if (edit == null || edit.sessionId != sessionId || edit.saving || edit.awaitingReadback || edit.loadingDraft || edit.discarding || current.originalHistory != null) current
            else if (text.length > EncryptedEntryRepository.MAX_TEXT_LENGTH) current.copy(originalEdit = edit.copy(
                inputNotice = "文本过长，本次输入未替换你的修改。长度上限为 ${EncryptedEntryRepository.MAX_TEXT_LENGTH}。"))
            else {
                accepted = true
                current.copy(originalEdit = edit.copy(text = text, inputNotice = null,
                    draftNotice = if (current.supportsEditDrafts) "正在保存本机编辑草稿" else edit.draftNotice,
                    error = if (edit.conflict) edit.error else null))
            }
        }
        if (accepted && mutableState.value.supportsEditDrafts) commands.trySend(Command.PersistEdit(sessionId, text))
    }

    fun cancelOriginalEdit(sessionId: Long) {
        if (editsDisposed) return
        val active = mutableState.value.originalEdit ?: return
        if (active.sessionId != sessionId || !active.canCancel || mutableState.value.originalHistory != null) return
        if (mutableState.value.supportsEditDrafts && !active.draftConflict) {
            mutableState.update { if (it.originalEdit?.sessionId != sessionId) it else it.copy(
                originalEdit = it.originalEdit.copy(discarding = true, error = null)) }
            commands.trySend(Command.DiscardEdit(sessionId))
            return
        }
        mutableState.update { current ->
            val edit = current.originalEdit
            if (edit?.sessionId != sessionId || !edit.canCancel || current.originalHistory != null) current
            else current.copy(originalEdit = null, originalEditNotice = null)
        }
        if (mutableState.value.supportsEditDrafts) commands.trySend(Command.ReleaseEdit(sessionId))
    }

    fun saveOriginalEdit(sessionId: Long) {
        if (editsDisposed) return
        val current = mutableState.value
        val edit = current.originalEdit ?: return
        if (edit.sessionId != sessionId || edit.saving || edit.conflict || edit.loadingDraft || edit.discarding || !current.ready || current.opening || current.error != null || current.originalHistory != null) return
        if (!edit.awaitingReadback && (edit.text.isBlank() || !edit.dirty || edit.text.length > EncryptedEntryRepository.MAX_TEXT_LENGTH)) return
        mutableState.update { it.copy(originalEdit = edit.copy(saving = true, error = null, inputNotice = null)) }
        commands.trySend(Command.Revise(sessionId, edit.entryId, edit.baseRevision, edit.text))
    }

    fun beginOriginalHistory(entryId: String, expectedRevision: Int) {
        val current = mutableState.value
        if (!current.ready || current.opening || current.error != null || current.saving || current.savePending || current.originalHistory != null || attachments.state.value != null) return
        if (current.originalEdit?.let { it.entryId != entryId || !it.canCancel } == true) return
        if (current.entries.none { it.id == entryId && it.currentRevision == expectedRevision }) return
        val history = OriginalHistoryUiState(originalHistoryIds.incrementAndGet(), entryId)
        mutableState.update { it.copy(originalHistory = history) }
        commands.trySend(Command.ReadHistory(history.sessionId, history.requestId))
    }

    fun selectOriginalRevision(sessionId: Long, revision: Int?) {
        if (attachments.state.value != null) return
        val current = mutableState.value
        val history = current.originalHistory ?: return
        if (history.sessionId != sessionId || history.loading || history.error != null || history.unavailable ||
            !current.ready || current.opening || current.error != null ||
            revision != null && history.revisions.none { it.revision == revision }) return
        requestHistory(history.copy(selectedRevision = revision))
    }

    fun retryOriginalHistory(sessionId: Long) {
        if (attachments.state.value != null) return
        val current = mutableState.value
        val history = current.originalHistory ?: return
        if (history.sessionId != sessionId || history.loading || !current.ready || current.opening || current.error != null) return
        requestHistory(history)
    }

    fun backOriginalHistory(sessionId: Long) {
        if (attachments.state.value != null) return
        val history = mutableState.value.originalHistory ?: return
        if (history.sessionId != sessionId) return
        if (history.replacement != null) cancelHistoryReplacement(sessionId, history.requestId, history.replacement.id)
        else if (history.selectedRevision != null) requestHistory(history.copy(selectedRevision = null))
        else mutableState.update { if (it.originalHistory?.sessionId == sessionId) it.copy(originalHistory = null) else it }
    }

    fun editOriginalHistory(sessionId: Long, requestId: Long, revision: Int, expectedCurrentRevision: Int) {
        val beforeEditId = mutableState.value.originalEdit?.sessionId
        mutableState.update { current ->
            if (!canEditHistory(current, sessionId, requestId, revision, expectedCurrentRevision)) current
            else if (current.originalEdit?.dirty == true) current.copy(originalHistory = current.originalHistory!!.copy(
                replacement = current.originalHistory.replacement ?: OriginalHistoryReplacement(
                    historyReplacementIds.incrementAndGet(), current.originalEdit.sessionId)))
            else historyToEditor(current)
        }
        queueHistoricalDraft(beforeEditId)
    }

    fun confirmHistoryReplacement(sessionId: Long, requestId: Long, replacementId: Long) {
        val beforeEditId = mutableState.value.originalEdit?.sessionId
        mutableState.update { current ->
            val history = current.originalHistory ?: return@update current
            val pending = history.replacement ?: return@update current
            if (pending.id != replacementId || current.originalEdit?.sessionId != pending.editSessionId ||
                history.selectedRevision == null || history.currentRevision == null ||
                !canEditHistory(current, sessionId, requestId, history.selectedRevision, history.currentRevision)) current
            else historyToEditor(current)
        }
        queueHistoricalDraft(beforeEditId)
    }

    private fun queueHistoricalDraft(beforeEditId: Long?) {
        mutableState.value.originalEdit?.takeIf { it.loadingDraft && it.sessionId != beforeEditId }?.let {
            commands.trySend(Command.OpenEdit(it, true))
        }
    }

    fun cancelHistoryReplacement(sessionId: Long, requestId: Long, replacementId: Long) {
        if (attachments.state.value != null) return
        mutableState.update { current ->
            val history = current.originalHistory
            if (history?.sessionId != sessionId || history.requestId != requestId || history.replacement?.id != replacementId) current
            else current.copy(originalHistory = history.copy(replacement = null))
        }
    }

    private fun canEditHistory(current: LocalRecordUiState, sessionId: Long, requestId: Long,
        revision: Int, expectedCurrentRevision: Int): Boolean {
        val history = current.originalHistory ?: return false
        return attachments.state.value == null && current.ready && !current.opening && current.error == null && !current.saving && !current.savePending &&
            history.sessionId == sessionId && history.requestId == requestId && !history.loading &&
            history.error == null && !history.unavailable && history.selectedRevision == revision &&
            history.currentRevision == expectedCurrentRevision && history.revisions.any { it.revision == revision } &&
            current.entries.any { it.id == history.entryId && it.currentRevision == expectedCurrentRevision } &&
            (current.originalEdit == null || current.originalEdit.entryId == history.entryId && current.originalEdit.canCancel)
    }

    private fun historyToEditor(current: LocalRecordUiState): LocalRecordUiState {
        val history = checkNotNull(current.originalHistory)
        val entry = current.entries.single { it.id == history.entryId }
        val source = history.revisions.single { it.revision == history.selectedRevision }
        val edit = OriginalEditUiState(originalEditIds.incrementAndGet(), entry.id,
            entry.currentRevision, entry.originalText, text = source.originalText, sourceRevision = source.revision,
            loadingDraft = current.supportsEditDrafts)
        return current.copy(originalHistory = null, originalEditNotice = null, originalEdit = edit)
    }

    private fun requestHistory(history: OriginalHistoryUiState) {
        val next = history.copy(requestId = history.requestId + 1, loading = true, revisions = emptyList(), error = null, unavailable = false,
            replacement = null)
        mutableState.update { if (it.originalHistory?.sessionId == history.sessionId) it.copy(originalHistory = next) else it }
        commands.trySend(Command.ReadHistory(next.sessionId, next.requestId))
    }

    private suspend fun readHistory(command: Command.ReadHistory) {
        val history = mutableState.value.originalHistory ?: return
        if (history.sessionId != command.sessionId || history.requestId != command.requestId || !history.loading) return
        fun owns(current: LocalRecordUiState) = current.originalHistory?.let {
            it.sessionId == command.sessionId && it.requestId == command.requestId
        } == true
        var observedEntries: List<StoredEntry>? = null
        try {
            val store = checkNotNull(repository)
            val before = store.entries().also { observedEntries = it }.firstOrNull { it.id == history.entryId }
            val revisions = if (before == null) emptyList() else store.revisions(history.entryId)
            val entries = store.entries().also { observedEntries = it }
            val after = entries.firstOrNull { it.id == history.entryId }
            if (after == null) {
                mutableState.update { if (!owns(it)) it else it.copy(entries = entries, originalHistory =
                    it.originalHistory!!.copy(loading = false, revisions = emptyList(), currentRevision = null, unavailable = true)) }
                return
            }
            if (before != after) throw RecordConflict()
            check(revisions.isNotEmpty() && revisions.all { it.entryId == after.id && it.revision in 1..after.currentRevision } &&
                revisions.map { it.revision }.toSet().size == revisions.size &&
                revisions.singleOrNull { it.revision == after.currentRevision }?.originalText == after.originalText)
            val ordered = revisions.sortedByDescending { it.revision }
            mutableState.update { if (!owns(it)) it else it.copy(entries = entries, originalHistory =
                it.originalHistory!!.copy(loading = false, currentRevision = after.currentRevision, revisions = ordered,
                    unavailable = history.selectedRevision != null && ordered.none { r -> r.revision == history.selectedRevision })) }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            mutableState.update { if (!owns(it)) it else it.copy(entries = observedEntries ?: it.entries, originalHistory = it.originalHistory!!.copy(
                loading = false, revisions = emptyList(), error = if (failure is RecordConflict)
                    "读取期间原文版本变化。请重新读取，正在编辑的修改仍保留。"
                else "历史版本读取失败。原文和正在编辑的修改仍保留，可以重试。")) }
        }
    }

    private fun updatePendingDraft(snapshot: OriginalEditDraft) {
        mutableState.update { current -> current.copy(pendingEdits = current.pendingEdits.filter { it.entryId != snapshot.entryId } +
            if (snapshot.state == OriginalEditDraftState.ACTIVE) listOf(OriginalEditDraftRef(snapshot.id, snapshot.entryId, snapshot.baseRevision)) else emptyList()) }
    }

    private fun indexNotice(index: OriginalEditDraftIndex): String? = if (index.unreadableCount == 0) null else
        "${index.unreadableCount} 份编辑草稿暂无法安全恢复。可能损坏、密钥不匹配或读取期间变化。加密数据已保留；" +
            if (index.drafts.isEmpty()) "当前没有可安全恢复的编辑草稿，请保留数据并重试读取。" else "可先恢复下方已校验的草稿。"

    private suspend fun refreshPendingDrafts() {
        try {
            val pending = repository?.editDrafts?.pending() ?: OriginalEditDraftIndex(emptyList())
            mutableState.update { it.copy(pendingEdits = pending.drafts, editDraftError = indexNotice(pending),
                editDraftIssueCount = pending.unreadableCount, editDraftIndexAvailable = true) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.update { it.copy(editDraftIndexAvailable = false, editDraftError = "编辑草稿目录暂不可读取，恢复列表尚未确认。已保留加密数据；请重试本机读取，未自动删除或替换。") }
        }
    }

    private suspend fun openOriginalDraft(command: Command.OpenEdit) {
        val sessionId = command.edit.sessionId
        if (mutableState.value.originalEdit?.let { it.sessionId == sessionId && it.loadingDraft } != true) return
        val store = checkNotNull(repository)
        val drafts = checkNotNull(store.editDrafts)
        try {
            val previous = drafts.read(command.edit.entryId)
            if (command.expectedDraftId != null && (previous?.id != command.expectedDraftId || previous.state != OriginalEditDraftState.ACTIVE))
                throw OriginalEditDraftConflict()
            // A confirmed historical replacement compares against the last acknowledged old session,
            // not a newly read competing writer's snapshot.
            val oldBinding = originalDraft?.takeIf { it.snapshot.entryId == command.edit.entryId }
            if (command.replace && oldBinding != null) {
                val known = listOf(if (oldBinding.persisted) oldBinding.snapshot else oldBinding.expected, oldBinding.unacknowledged)
                if (previous !in known) throw OriginalEditDraftConflict()
            }
            // Entering history directly is not consent to discard a dormant recovered edit.
            val restore = previous?.state == OriginalEditDraftState.ACTIVE && (!command.replace || oldBinding == null)
            val snapshot = if (restore) checkNotNull(previous) else OriginalEditDraft(UUID.randomUUID().toString(),
                command.edit.entryId, command.edit.baseRevision, command.edit.sourceRevision, 1,
                command.edit.originalText, command.edit.text)
            val before = store.entries()
            val revisions = store.revisions(snapshot.entryId)
            val entries = store.entries()
            val current = entries.firstOrNull { it.id == snapshot.entryId }
            val base = revisions.singleOrNull { it.revision == snapshot.baseRevision }
            val source = revisions.singleOrNull { it.revision == snapshot.sourceRevision }
            val missing = current == null || base == null || source == null
            if (!missing && base.originalText != snapshot.originalText) throw com.t1doo.aiyou.domain.RecordIntegrityFailure()
            val committed = if (restore) store.committedEdit(snapshot) else null
            val conflict = missing || before != entries || current.policy != ProcessingPolicy.LOCAL_ONLY ||
                committed == null && current.currentRevision != snapshot.baseRevision
            if (committed != null && !conflict) committedOriginal = sessionId to committed
            originalDraft = DraftBinding(sessionId, snapshot, previous, restore)
            // Historical source selection itself creates a dirty input snapshot.
            if (command.replace && !restore && snapshot.text != snapshot.originalText && !conflict) persistDraftSnapshot(sessionId, snapshot.text)
            mutableState.update { local ->
                if (local.originalEdit?.sessionId != sessionId) local else local.copy(entries = entries,
                    originalEdit = command.edit.copy(baseRevision = snapshot.baseRevision, sourceRevision = snapshot.sourceRevision,
                        originalText = snapshot.originalText, text = snapshot.text, loadingDraft = false,
                        awaitingReadback = committed != null && !conflict,
                        conflict = conflict, draftNotice = if (restore && command.replace) "已恢复已有的未保存修改；请先处理它，历史文本尚未替换此草稿。"
                            else if (committed != null) "已核对这份编辑的加密事务回执。新版本已写入，请重试确认草稿收尾。"
                            else if (restore) "已恢复本机编辑草稿，正式原文尚未被这次恢复修改。"
                            else if (snapshot.text != snapshot.originalText) "编辑草稿已加密保存在本机"
                            else "修改会加密保存为独立的本机编辑草稿。",
                        error = if (conflict) "源记录不可用或版本已变化。已保留你的修改，请复制或明确放弃；未覆盖当前原文。" else null))
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            originalDraft = null
            mutableState.update { local -> if (local.originalEdit?.sessionId != sessionId) local else local.copy(
                originalEdit = local.originalEdit.copy(loadingDraft = false, conflict = true,
                    draftConflict = failure is OriginalEditDraftConflict,
                    error = "无法安全恢复编辑草稿。当前原文及加密数据已保留，未自动覆盖或删除。请关闭后重试。")) }
            refreshPendingDrafts()
        }
    }

    // Both retries and explicit discard must account for publication whose acknowledgement was lost.
    private suspend fun reconcileDraftAttempt(binding: DraftBinding) {
        val store = checkNotNull(repository?.editDrafts)
        binding.unacknowledged?.let { attempted ->
            val current = store.read(attempted.entryId)
            if (current == attempted) {
                // Visibility after rename is insufficient: reconfirm directory durability without re-encrypting.
                store.write(attempted, attempted)
                binding.snapshot = attempted; binding.expected = attempted; binding.persisted = true
            } else if (current != if (binding.persisted) binding.snapshot else binding.expected) throw OriginalEditDraftConflict()
            binding.unacknowledged = null
        }
    }

    private suspend fun persistDraftSnapshot(sessionId: Long, text: String) {
        val binding = originalDraft?.takeIf { it.sessionId == sessionId } ?: throw OriginalEditDraftConflict()
        val records = checkNotNull(repository)
        val entries = records.entries()
        val source = entries.firstOrNull { it.id == binding.snapshot.entryId }
        mutableState.update { local -> if (local.originalEdit?.sessionId != sessionId) local else local.copy(entries = entries,
            originalEdit = local.originalEdit.copy(conflict = local.originalEdit.conflict || source == null ||
                source.currentRevision != binding.snapshot.baseRevision || source.policy != ProcessingPolicy.LOCAL_ONLY)) }
        if (source == null || source.policy != ProcessingPolicy.LOCAL_ONLY) throw RecordConflict()
        val store = checkNotNull(records.editDrafts)
        reconcileDraftAttempt(binding)
        if (binding.snapshot.state != OriginalEditDraftState.ACTIVE) throw OriginalEditDraftConflict()
        if (binding.persisted && binding.snapshot.text == text) return
        val next = binding.snapshot.copy(text = text,
            version = if (binding.persisted) Math.addExact(binding.snapshot.version, 1L) else 1L)
        val expected = if (binding.persisted) binding.snapshot else binding.expected
        binding.unacknowledged = next
        binding.snapshot = store.write(expected, next)
        binding.unacknowledged = null
        binding.expected = binding.snapshot
        binding.persisted = true
        updatePendingDraft(binding.snapshot)
    }

    private suspend fun persistOriginalDraft(command: Command.PersistEdit) {
        if (mutableState.value.originalEdit?.sessionId != command.sessionId) return
        try {
            persistDraftSnapshot(command.sessionId, command.text)
            mutableState.update { local ->
                val edit = local.originalEdit
                if (edit?.sessionId != command.sessionId || edit.text != command.text) local
                else local.copy(originalEdit = edit.copy(draftNotice = "编辑草稿已加密保存在本机",
                    error = if (edit.conflict) edit.error else null))
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            mutableState.update { local -> if (local.originalEdit?.sessionId != command.sessionId) local else local.copy(
                originalEdit = local.originalEdit.copy(draftNotice = "编辑草稿保存状态待确认，当前输入仍保留。",
                    conflict = local.originalEdit.conflict || failure is OriginalEditDraftConflict || failure is RecordConflict,
                    draftConflict = failure is OriginalEditDraftConflict,
                    error = if (failure is RecordConflict) "源记录已不可用或不再允许处理。当前输入仍保留，未重新创建记录或写回已删除来源。请复制输入或明确放弃。"
                        else "编辑草稿写入失败。输入与原数据仍保留；重新输入或保存新版本时会重试。若另一会话已修改草稿，请复制输入并明确放弃当前输入后恢复。")) }
        }
    }

    private suspend fun discardOriginalDraft(sessionId: Long) {
        if (mutableState.value.originalEdit?.let { it.sessionId == sessionId && it.discarding } != true) return
        try {
            originalDraft?.takeIf { it.sessionId == sessionId }?.let { binding ->
                reconcileDraftAttempt(binding)
                if (!binding.persisted) return@let
                if (binding.snapshot.state != OriginalEditDraftState.ACTIVE) {
                    updatePendingDraft(binding.snapshot)
                    return@let
                }
                val next = binding.snapshot.copy(version = Math.addExact(binding.snapshot.version, 1L),
                    originalText = "", text = "", state = OriginalEditDraftState.DISCARDED)
                binding.unacknowledged = next
                binding.snapshot = checkNotNull(repository?.editDrafts).write(binding.snapshot, next)
                binding.unacknowledged = null
                updatePendingDraft(binding.snapshot)
            }
            originalDraft = null
            mutableState.update { if (it.originalEdit?.sessionId != sessionId) it else it.copy(originalEdit = null, originalEditNotice = null) }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            mutableState.update { local -> if (local.originalEdit?.sessionId != sessionId) local else local.copy(
                originalEdit = local.originalEdit.copy(discarding = false,
                    draftNotice = "丢弃状态待确认，当前输入仍保留。",
                    draftConflict = failure is OriginalEditDraftConflict,
                    error = if (failure is OriginalEditDraftConflict) "本机已有另一会话的草稿。当前输入仍保留；明确放弃当前输入只会关闭此编辑，不会删除另一会话的修改。"
                        else "丢弃未完成。输入与本机草稿仍保留，可以重试；尚未关闭编辑。")) }
        }
    }

    private suspend fun reviseOriginal(command: Command.Revise) {
        val active = mutableState.value.originalEdit
        if (active?.sessionId != command.sessionId || !active.saving || active.text != command.text) return
        val store = checkNotNull(repository)
        try {
            val committed = committedOriginal?.takeIf { it.first == command.sessionId }?.second
                ?: run {
                    if (store.editDrafts != null) persistDraftSnapshot(command.sessionId, command.text)
                    val draft = originalDraft?.takeIf { it.sessionId == command.sessionId && it.persisted }?.snapshot
                    if (draft == null) store.revise(command.entryId, command.expectedRevision, command.text, clock.millis())
                    else store.reviseFromDraft(draft, clock.millis())
                }.also { entry ->
                    committedOriginal = command.sessionId to entry
                    mutableState.update { current ->
                        if (current.originalEdit?.sessionId != command.sessionId) current
                        else current.copy(originalEdit = current.originalEdit.copy(awaitingReadback = true))
                    }
                }
            var competingDraft = false
            try {
                originalDraft?.takeIf { it.sessionId == command.sessionId }?.let { binding ->
                    reconcileDraftAttempt(binding)
                    if (binding.persisted && binding.snapshot.state == OriginalEditDraftState.ACTIVE) {
                        val next = binding.snapshot.copy(version = Math.addExact(binding.snapshot.version, 1L),
                            originalText = "", text = "", state = OriginalEditDraftState.COMMITTED)
                        binding.unacknowledged = next
                        binding.snapshot = checkNotNull(store.editDrafts).write(binding.snapshot, next)
                        binding.unacknowledged = null
                        updatePendingDraft(binding.snapshot)
                    }
                }
            } catch (_: OriginalEditDraftConflict) {
                // The formal transaction already returned. Preserve the competing draft and
                // confirm that transaction independently; a CAS loss must not repeat revise.
                competingDraft = true
                refreshPendingDrafts()
            }
            val entries = store.entries()
            val latest = entries.firstOrNull { it.id == committed.id }
            check(latest != null && latest.currentRevision >= committed.currentRevision &&
                (latest.currentRevision != committed.currentRevision || latest.originalText == committed.originalText))
            mutableState.update { current ->
                if (current.originalEdit?.sessionId != command.sessionId) current
                else current.copy(entries = entries, originalEdit = null,
                    originalEditNotice = if (competingDraft) "新版本已保存在本机；本机另一会话的编辑草稿已保留，请单独恢复处理。"
                        else if (latest.currentRevision == committed.currentRevision) "新版本已保存在本机"
                        else "修改已保存在本机，原文又有更新，请查看当前版本。")
            }
            committedOriginal = null
            originalDraft = null
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            val committed = committedOriginal?.first == command.sessionId
            val refreshed = if (failure is RecordConflict && !committed) {
                try { store.entries() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            } else null
            mutableState.update { current ->
                val edit = current.originalEdit
                if (edit?.sessionId != command.sessionId) current
                else current.copy(entries = refreshed ?: current.entries, originalEdit = edit.copy(saving = false,
                    awaitingReadback = committed, conflict = (failure is RecordConflict || failure is OriginalEditDraftConflict) && !committed,
                    draftConflict = failure is OriginalEditDraftConflict && !committed,
                    error = when {
                        committed -> "新版本已写入，但草稿收尾或读取确认失败。请重试确认，不会再次创建版本。"
                        failure is OriginalEditDraftConflict -> "本机编辑草稿已由另一会话变化。当前输入仍保留，未覆盖本机草稿或原文。可复制输入，明确放弃当前输入后重新恢复。"
                        failure is RecordConflict -> "原文版本已变化或记录已不可用。你的修改已保留，未覆盖当前原文。"
                        else -> "修改保存失败。你的修改与原文已保留，可以重试。"
                    }))
            }
        }
    }

    private suspend fun open() {
        try {
            val store = repository ?: openStore().also { repository = it }
            store.prepare()
            persistedDraft = store.readDraft()
            val entries = store.entries()
            val pending = try { store.editDrafts?.pending() ?: OriginalEditDraftIndex(emptyList()) }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            mutableState.update { it.copy(ready = true, opening = false, text = persistedDraft?.text.orEmpty(),
                entries = entries, draftNotice = if (persistedDraft == null) "没有未保存的草稿" else "已恢复本机草稿", error = null,
                supportsEditDrafts = store.editDrafts != null, pendingEdits = pending?.drafts.orEmpty(),
                editDraftIssueCount = pending?.unreadableCount ?: 0, editDraftIndexAvailable = pending != null,
                editDraftError = if (pending == null) "编辑草稿目录暂不可读取，恢复列表尚未确认。已保留加密数据；请重试本机读取，未自动删除或替换。" else indexNotice(pending)) }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { failed(failure) }
        catch (_: LinkageError) { failed(IllegalStateException()) }
    }

    private fun failed(failure: Exception, preserveQueuedSave: Boolean = false) {
        mutableState.update { it.copy(opening = false, saving = if (preserveQueuedSave) it.saving else false,
            error = if (failure is StorageKeyUnavailable) "无法读取本机加密密钥。请保留原数据并先解锁设备重试。备份恢复尚未开放。"
            else "本机存储操作失败。输入与原数据已保留，可以重试。",
            draftNotice = if (it.ready) "草稿保存状态待确认" else "草稿尚未读取") }
    }

    override fun onCleared() { editsDisposed = true; attachments.markDisposed(); commands.close(); super.onCleared() }

    private data class SaveIntent(val text: String, val generation: Long)

    private sealed interface Command {
        data object RefreshEditDrafts : Command
        data class OpenEdit(val edit: OriginalEditUiState, val replace: Boolean, val expectedDraftId: String? = null) : Command
        data class PersistEdit(val sessionId: Long, val text: String) : Command
        data class DiscardEdit(val sessionId: Long) : Command
        data class ReleaseEdit(val sessionId: Long) : Command
        data class Attachment(val action: suspend (EntryRepository) -> Unit) : Command
        data class Edit(val text: String, val generation: Long) : Command
        data class Save(val intent: SaveIntent) : Command
        data object Retry : Command
        data class RecoverDraft(val text: String, val generation: Long) : Command
        data class Revise(val sessionId: Long, val entryId: String, val expectedRevision: Int, val text: String) : Command
        data class ReadHistory(val sessionId: Long, val requestId: Long) : Command
    }
}
