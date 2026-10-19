package com.t1doo.aiyou.ui

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.t1doo.aiyou.model.FactRecapPeriod
import com.t1doo.aiyou.model.ProcessingPolicy
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

/** Device defaults only; never records, consent, credentials or keys. */
data class UiPreferences(val showCompanion: Boolean = true, val reduceMotion: Boolean = false,
    val defaultRecapPeriod: FactRecapPeriod = FactRecapPeriod.DAY,
    val defaultCapturePolicy: ProcessingPolicy = ProcessingPolicy.LOCAL_ONLY) {
    init { require(defaultCapturePolicy in setOf(ProcessingPolicy.LOCAL_ONLY, ProcessingPolicy.AI_NONE)) }
}

data class UiPreferencesState(
    val preferences: UiPreferences = UiPreferences(showCompanion = false, reduceMotion = true),
    val ready: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val saveUnconfirmed: Boolean = false,
)

internal interface UiPreferencesStorage {
    fun read(): UiPreferences
    fun write(value: UiPreferences)
}

/** Serialized by the ViewModel; a separate file outside the encrypted record root. */
internal class FileUiPreferences(private val file: File) : UiPreferencesStorage {
    override fun read(): UiPreferences = synchronized(fileGuard) {
        val bytes = try { AtomicFile(file).openRead().use { it.readBytes() } }
        catch (failure: FileNotFoundException) {
            if (!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists()) return@synchronized UiPreferences()
            throw failure
        }
        val value = bytes.toString(Charsets.US_ASCII)
        for (companion in listOf(false, true)) for (motion in listOf(false, true)) {
            val legacy = UiPreferences(companion, motion)
            // Reading a v1 file neither migrates it nor changes either existing display choice.
            if (value == "AIyou display v1\n$companion\n$motion\n") return@synchronized legacy
            for (period in FactRecapPeriod.entries) {
                val candidate = legacy.copy(defaultRecapPeriod = period)
                if (value == "AIyou display v2\n$companion\n$motion\n${period.name}\n") return@synchronized candidate
                for (policy in listOf(ProcessingPolicy.LOCAL_ONLY, ProcessingPolicy.AI_NONE)) {
                    val configured = candidate.copy(defaultCapturePolicy = policy)
                    if (value == encode(configured)) return@synchronized configured
                }
            }
        }
        throw IOException("Invalid UI preferences")
    }

    /** Unknown preference bytes must never broaden processing or prevent a new local record. */
    fun newCapturePolicy(): ProcessingPolicy = try { read().defaultCapturePolicy }
        catch (_: IOException) { ProcessingPolicy.AI_NONE }
        catch (_: SecurityException) { ProcessingPolicy.AI_NONE }

    override fun write(value: UiPreferences) = synchronized(fileGuard) {
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
        if (read() != value) throw IOException("UI preferences not confirmed")
    }

    private fun encode(value: UiPreferences) = "AIyou display v3\n${value.showCompanion}\n${value.reduceMotion}\n${value.defaultRecapPeriod.name}\n${value.defaultCapturePolicy.name}\n"

    // AtomicFile readers may recover .bak, so they share the complete write/readback boundary.
    // No SQL or UI callbacks run while this lock is held.
    private companion object { val fileGuard = Any() }
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
                mutableState.value = mutableState.value.copy(busy = false, error = "偏好设置暂不可读取，轻触重试。")
            }
        }
    }

    fun change(value: UiPreferences) {
        val current = mutableState.value
        if (!current.ready || current.busy || current.saveUnconfirmed) return
        pending = value
        persist(value)
    }

    private fun persist(value: UiPreferences) {
        val current = mutableState.value
        if (!current.ready || current.busy) return
        mutableState.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                withContext(io) { storage.write(value) }
                pending = null
                mutableState.value = UiPreferencesState(value, ready = true)
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) {
                mutableState.value = current.copy(saveUnconfirmed = true,
                    error = "设置保存尚未确认；界面仍显示上次确认的选项，文件可能已保存。请重试核对同一次设置。当前草稿范围不变，新草稿以草稿中显示的范围为准。")
            }
        }
    }

    fun retry() { pending?.let(::persist) ?: load() }
}
