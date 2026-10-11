package com.t1doo.aiyou.records

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.domain.StorageKeyUnavailable
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
)

/** All draft writes and saves share one queue, preserving edits around a save boundary. */
class LocalRecordsViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(LocalRecordUiState())
    val state = mutableState.asStateFlow()
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var repository: EncryptedEntryRepository? = null
    private var persistedDraft: TextDraft? = null
    private val editGeneration = AtomicLong()
    private var pendingCommit: TextDraft? = null
    private val pendingIntent = AtomicReference<SaveIntent?>(null)
    private val clock = Clock.systemUTC()

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
                        }
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { failed(failure, preserveQueuedSave = command is Command.Edit) }
                }
            } finally {
                repository?.close()
                repository = null
            }
        }
    }

    fun changeText(text: String) {
        val current = mutableState.value
        if (!current.ready || current.saving || current.savePending) return
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
        if (!current.ready || current.saving || current.text.isBlank()) return
        val intent = pendingIntent.get() ?: SaveIntent(current.text, editGeneration.get()).also { pendingIntent.set(it) }
        mutableState.update { it.copy(saving = true, savePending = true, saveNotice = null, error = null, inputNotice = null) }
        commands.trySend(Command.Save(intent))
    }

    fun retry() {
        if (!mutableState.value.ready && !mutableState.value.opening) {
            mutableState.update { it.copy(opening = true, error = null) }
            commands.trySend(Command.Retry)
        } else if (mutableState.value.savePending) save()
        else if (mutableState.value.ready) changeText(mutableState.value.text)
    }

    private suspend fun open() {
        try {
            val store = repository ?: EncryptedEntryRepository.open(getApplication()).also { repository = it }
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
    }
}
