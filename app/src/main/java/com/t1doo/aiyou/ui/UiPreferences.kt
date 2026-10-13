package com.t1doo.aiyou.ui

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ordinary display choices only; never records, consent, provider credentials or keys. */
data class UiPreferences(val showCompanion: Boolean = true, val reduceMotion: Boolean = false)

data class UiPreferencesState(
    val preferences: UiPreferences = UiPreferences(showCompanion = false, reduceMotion = true),
    val ready: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

internal interface UiPreferencesStorage {
    fun read(): UiPreferences
    fun write(value: UiPreferences)
}

/** Serialized by the ViewModel; a separate file outside the encrypted record root. */
internal class FileUiPreferences(private val file: File) : UiPreferencesStorage {
    override fun read(): UiPreferences {
        val bytes = try { AtomicFile(file).openRead().use { it.readBytes() } }
        catch (failure: FileNotFoundException) {
            if (!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists()) return UiPreferences()
            throw failure
        }
        val value = bytes.toString(Charsets.US_ASCII)
        return when (value) {
            encode(UiPreferences(true, true)) -> UiPreferences(true, true)
            encode(UiPreferences(true, false)) -> UiPreferences(true, false)
            encode(UiPreferences(false, true)) -> UiPreferences(false, true)
            encode(UiPreferences(false, false)) -> UiPreferences(false, false)
            else -> throw IOException("Invalid display preferences")
        }
    }

    override fun write(value: UiPreferences) {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(encode(value).toByteArray(Charsets.US_ASCII))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
        // AtomicFile can log a failed rename; never report success without readback.
        if (read() != value) throw IOException("Display preferences not confirmed")
    }

    private fun encode(value: UiPreferences) = "AIyou display v1\n${value.showCompanion}\n${value.reduceMotion}\n"
}

class UiPreferencesViewModel internal constructor(
    application: Application,
    private val storage: UiPreferencesStorage,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, FileUiPreferences(File(application.filesDir, "display/preferences")))

    private val mutableState = MutableStateFlow(UiPreferencesState())
    val state = mutableState.asStateFlow()
    private var pending: UiPreferences? = null

    init { load() }

    private fun load() {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val value = withContext(io) { storage.read() }
                mutableState.value = UiPreferencesState(value, ready = true)
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) {
                mutableState.value = mutableState.value.copy(busy = false, error = "显示设置暂不可读取，轻触重试。")
            }
        }
    }

    fun change(value: UiPreferences) {
        val current = mutableState.value
        if (!current.ready || current.busy) return
        pending = value
        mutableState.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                withContext(io) { storage.write(value) }
                pending = null
                mutableState.value = UiPreferencesState(value, ready = true)
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) {
                mutableState.value = current.copy(error = "设置未保存，仍使用上次确认的选项。可重试。")
            }
        }
    }

    fun retry() { pending?.let(::change) ?: load() }
}
