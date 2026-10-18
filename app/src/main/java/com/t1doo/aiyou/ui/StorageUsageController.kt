package com.t1doo.aiyou.ui

import com.t1doo.aiyou.domain.StorageUsageReader
import com.t1doo.aiyou.model.LocalStorageUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

data class StorageUsageUiState(
    val session: Long = 0,
    val active: Boolean = false,
    val busy: Boolean = false,
    val usage: LocalStorageUsage? = null,
    val error: Boolean = false,
)

/** Main-thread navigation ownership; IO completion cannot repopulate a later settings visit. */
class StorageUsageController(
    private val scope: CoroutineScope,
    private val reader: StorageUsageReader,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(StorageUsageUiState())
    val state = mutableState.asStateFlow()
    private var session = 0L
    private var job: Job? = null
    private var disposed = false

    fun enter(): Long {
        if (disposed) return session
        job?.cancel()
        session = Math.addExact(session, 1)
        mutableState.value = StorageUsageUiState(session, active = true)
        return session
    }

    fun leave(owner: Long) {
        if (mutableState.value.session != owner) return
        job?.cancel()
        mutableState.value = StorageUsageUiState(session)
    }

    fun query(owner: Long) {
        val before = mutableState.value
        if (disposed || !before.active || before.session != owner || before.busy) return
        mutableState.value = before.copy(busy = true, usage = null, error = false)
        job = scope.launch {
            val result = try { runInterruptible(io) { reader.read() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            val current = mutableState.value
            if (!disposed && current.active && current.session == owner) {
                mutableState.value = current.copy(busy = false, usage = result, error = result == null)
            }
        }
    }

    fun dispose() {
        disposed = true
        leave(session)
    }
}
