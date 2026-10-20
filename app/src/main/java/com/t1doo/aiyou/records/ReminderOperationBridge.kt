package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.ReminderExecutionGate
import com.t1doo.aiyou.domain.ReminderRuntimeOwner
import java.util.concurrent.Executors
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*

/** One process IO worker. The JVM gate is acquired/released on this physical thread even when an
 * admitted foreground action suspends on another dispatcher. No Activity or data is retained here.
 */
internal class ReminderOperationBridge(val gate: ReminderExecutionGate = ReminderExecutionGate()) : AutoCloseable {
    private class Operation : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Operation>
    }
    @Volatile private var worker: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { action ->
        Thread(action, "AIyou-reminder-operations").apply { isDaemon = true; worker = this }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    private suspend fun checkTopLevel() { check(currentCoroutineContext()[Operation] == null && Thread.currentThread() !== worker) { "Reminder operation cannot await its own worker" } }
    suspend fun assertOutsideOperation() = checkTopLevel()
    suspend fun <T> operation(owner: ReminderRuntimeOwner, action: suspend () -> T): T {
        checkTopLevel()
        return withContext(NonCancellable + dispatcher) { gate.perform(owner) { runBlocking(Operation()) { action() } } }
    }
    /** Runtime coordinator/admission/close takes the same gate itself; never wrap it in operation. */
    suspend fun <T> runtime(action: () -> T): T {
        checkTopLevel()
        return withContext(NonCancellable + dispatcher) { action() }
    }
    suspend fun closeOwner(owner: ReminderRuntimeOwner, close: () -> Unit) = runtime { owner.closeAndAwait(close) }
    /** Queue a state-only observation after prior worker activity, without blocking lifecycle code.
     * Uses the same executor and never falls back to a different worker if it has been closed. */
    fun postObservation(action:()->Unit){executor.execute(action)}
    override fun close() { dispatcher.close() }
}
