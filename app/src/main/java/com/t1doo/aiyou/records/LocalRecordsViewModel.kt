package com.t1doo.aiyou.records

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.domain.StorageKeyUnavailable
import com.t1doo.aiyou.domain.RecordConflict
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.TextDraft
import java.time.Clock
import java.time.ZoneId
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
)

/** All draft writes and saves share one queue, preserving edits around a save boundary. */
class LocalRecordsViewModel internal constructor(
    application: Application,
    private val openStore: () -> EntryRepository,
    private val clock: Clock = Clock.systemUTC(),
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, { EncryptedEntryRepository.open(application) })
    private val mutableState = MutableStateFlow(LocalRecordUiState())
    val state = mutableState.asStateFlow()
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var repository: EntryRepository? = null
    private var persistedDraft: TextDraft? = null
    private val editGeneration = AtomicLong()
    private var pendingCommit: TextDraft? = null
    private val pendingIntent = AtomicReference<SaveIntent?>(null)
    private val originalEditIds = AtomicLong()
    private val originalHistoryIds = AtomicLong()
    // IO-queue owned. Once revise returned, a failed readback must never revise again.
    private var committedOriginal: Pair<Long, StoredEntry>? = null

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
                                        requestId = history.requestId + 1, loading = true, revisions = emptyList(), error = null, unavailable = false) }) }
                                mutableState.value.originalHistory?.let { commands.trySend(Command.ReadHistory(it.sessionId, it.requestId)) }
                            }
                            is Command.Revise -> reviseOriginal(command)
                            is Command.ReadHistory -> readHistory(command)
                        }
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { failed(failure, preserveQueuedSave = command is Command.Edit) }
                }
            } finally {
                (repository as? AutoCloseable)?.close()
                repository = null
            }
        }
    }

    fun changeText(text: String) {
        val current = mutableState.value
        if (!current.ready || current.saving || current.savePending || current.originalEdit != null || current.originalHistory != null) return
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
        if (!current.ready || current.saving || current.text.isBlank() || current.originalEdit != null || current.originalHistory != null) return
        val intent = pendingIntent.get() ?: SaveIntent(current.text, editGeneration.get()).also { pendingIntent.set(it) }
        mutableState.update { it.copy(saving = true, savePending = true, saveNotice = null, error = null, inputNotice = null) }
        commands.trySend(Command.Save(intent))
    }

    fun retry() {
        val current = mutableState.value
        if (current.originalEdit != null || current.originalHistory != null) {
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
        val current = mutableState.value
        if (!current.ready || current.opening || current.error != null || current.saving || current.savePending || current.originalEdit != null || current.originalHistory != null) return
        val entry = current.entries.firstOrNull { it.id == entryId && it.currentRevision == expectedRevision } ?: return
        mutableState.update { it.copy(originalEdit = OriginalEditUiState(originalEditIds.incrementAndGet(),
            entry.id, entry.currentRevision, entry.originalText), originalEditNotice = null) }
    }

    fun changeOriginalEdit(sessionId: Long, text: String) {
        mutableState.update { current ->
            val edit = current.originalEdit
            if (edit == null || edit.sessionId != sessionId || edit.saving || edit.awaitingReadback || current.originalHistory != null) current
            else if (text.length > EncryptedEntryRepository.MAX_TEXT_LENGTH) current.copy(originalEdit = edit.copy(
                inputNotice = "文本过长，本次输入未替换你的修改。长度上限为 ${EncryptedEntryRepository.MAX_TEXT_LENGTH}。"))
            else current.copy(originalEdit = edit.copy(text = text, inputNotice = null,
                error = if (edit.conflict) edit.error else null))
        }
    }

    fun cancelOriginalEdit(sessionId: Long) {
        mutableState.update { current ->
            val edit = current.originalEdit
            if (edit?.sessionId != sessionId || !edit.canCancel || current.originalHistory != null) current
            else current.copy(originalEdit = null, originalEditNotice = null)
        }
    }

    fun saveOriginalEdit(sessionId: Long) {
        val current = mutableState.value
        val edit = current.originalEdit ?: return
        if (edit.sessionId != sessionId || edit.saving || edit.conflict || !current.ready || current.opening || current.error != null || current.originalHistory != null) return
        if (!edit.awaitingReadback && (edit.text.isBlank() || !edit.dirty || edit.text.length > EncryptedEntryRepository.MAX_TEXT_LENGTH)) return
        mutableState.update { it.copy(originalEdit = edit.copy(saving = true, error = null, inputNotice = null)) }
        commands.trySend(Command.Revise(sessionId, edit.entryId, edit.baseRevision, edit.text))
    }

    fun beginOriginalHistory(entryId: String, expectedRevision: Int) {
        val current = mutableState.value
        if (!current.ready || current.opening || current.error != null || current.saving || current.savePending || current.originalHistory != null) return
        if (current.originalEdit?.let { it.entryId != entryId || !it.canCancel } == true) return
        if (current.entries.none { it.id == entryId && it.currentRevision == expectedRevision }) return
        val history = OriginalHistoryUiState(originalHistoryIds.incrementAndGet(), entryId)
        mutableState.update { it.copy(originalHistory = history) }
        commands.trySend(Command.ReadHistory(history.sessionId, history.requestId))
    }

    fun selectOriginalRevision(sessionId: Long, revision: Int?) {
        val current = mutableState.value
        val history = current.originalHistory ?: return
        if (history.sessionId != sessionId || history.loading || history.error != null || history.unavailable ||
            !current.ready || current.opening || current.error != null ||
            revision != null && history.revisions.none { it.revision == revision }) return
        requestHistory(history.copy(selectedRevision = revision))
    }

    fun retryOriginalHistory(sessionId: Long) {
        val current = mutableState.value
        val history = current.originalHistory ?: return
        if (history.sessionId != sessionId || history.loading || !current.ready || current.opening || current.error != null) return
        requestHistory(history)
    }

    fun backOriginalHistory(sessionId: Long) {
        val history = mutableState.value.originalHistory ?: return
        if (history.sessionId != sessionId) return
        if (history.selectedRevision != null) requestHistory(history.copy(selectedRevision = null))
        else mutableState.update { if (it.originalHistory?.sessionId == sessionId) it.copy(originalHistory = null) else it }
    }

    private fun requestHistory(history: OriginalHistoryUiState) {
        val next = history.copy(requestId = history.requestId + 1, loading = true, revisions = emptyList(), error = null, unavailable = false)
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

    private suspend fun reviseOriginal(command: Command.Revise) {
        val active = mutableState.value.originalEdit
        if (active?.sessionId != command.sessionId || !active.saving || active.text != command.text) return
        val store = checkNotNull(repository)
        try {
            val committed = committedOriginal?.takeIf { it.first == command.sessionId }?.second
                ?: store.revise(command.entryId, command.expectedRevision, command.text, clock.millis()).also { entry ->
                    committedOriginal = command.sessionId to entry
                    mutableState.update { current ->
                        if (current.originalEdit?.sessionId != command.sessionId) current
                        else current.copy(originalEdit = current.originalEdit.copy(awaitingReadback = true))
                    }
                }
            val entries = store.entries()
            val latest = entries.firstOrNull { it.id == committed.id }
            check(latest != null && latest.currentRevision >= committed.currentRevision &&
                (latest.currentRevision != committed.currentRevision || latest.originalText == committed.originalText))
            mutableState.update { current ->
                if (current.originalEdit?.sessionId != command.sessionId) current
                else current.copy(entries = entries, originalEdit = null,
                    originalEditNotice = if (latest.currentRevision == committed.currentRevision) "新版本已保存在本机"
                        else "修改已保存在本机，原文又有更新，请查看当前版本。")
            }
            committedOriginal = null
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
                    awaitingReadback = committed, conflict = failure is RecordConflict && !committed,
                    error = when {
                        committed -> "新版本已写入，但读取确认失败。你的修改已保留，请重试读取。"
                        failure is RecordConflict -> "原文版本已变化或记录已不可用。你的修改已保留，未覆盖当前原文。"
                        else -> "修改保存失败。你的修改与原文已保留，可以重试。"
                    }))
            }
        }
    }

    private suspend fun open() {
        try {
            val store = repository ?: openStore().also { repository = it }
            persistedDraft = store.readDraft()
            val entries = store.entries()
            mutableState.update { it.copy(ready = true, opening = false, text = persistedDraft?.text.orEmpty(),
                entries = entries, draftNotice = if (persistedDraft == null) "没有未保存的草稿" else "已恢复本机草稿", error = null) }
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

    override fun onCleared() { commands.close(); super.onCleared() }

    private data class SaveIntent(val text: String, val generation: Long)

    private sealed interface Command {
        data class Edit(val text: String, val generation: Long) : Command
        data class Save(val intent: SaveIntent) : Command
        data object Retry : Command
        data class RecoverDraft(val text: String, val generation: Long) : Command
        data class Revise(val sessionId: Long, val entryId: String, val expectedRevision: Int, val text: String) : Command
        data class ReadHistory(val sessionId: Long, val requestId: Long) : Command
    }
}
