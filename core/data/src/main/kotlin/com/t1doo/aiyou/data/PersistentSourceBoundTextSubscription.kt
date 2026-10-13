package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.SendDenial
import com.t1doo.aiyou.domain.SourceBoundTextSubscription
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * An owned observer of the product database, not a second source of permission.
 * 100 ms is a scheduling interval, not a detection deadline: SQL locks and scheduling
 * can delay a read. The exchange also checks before each preview and before commit.
 */
internal class PersistentSourceBoundTextSubscription private constructor(
    private val readAuthority: () -> SendDenial?,
    private val onInvalidated: (SendDenial) -> Unit,
) : SourceBoundTextSubscription {
    private sealed interface State {
        data object Active : State
        data class Invalid(val denial: SendDenial) : State
        data object Closed : State
    }
    private val state = AtomicReference<State>(State.Active)
    private val executor = AtomicReference<ScheduledThreadPoolExecutor?>()
    private val scheduled = AtomicReference<ScheduledFuture<*>?>()

    override fun check(): SendDenial? {
        when (val current = state.get()) {
            is State.Invalid -> return current.denial
            State.Closed -> return SendDenial.STATE_UNAVAILABLE
            State.Active -> Unit
        }
        // readAuthority completes its SQL transaction before any notification.
        val denial = readAuthority()
        if (denial != null) invalidate(denial)
        return when (val current = state.get()) {
            is State.Invalid -> current.denial
            State.Closed -> SendDenial.STATE_UNAVAILABLE
            State.Active -> denial
        }
    }

    private fun invalidate(denial: SendDenial) {
        // This CAS reserves the sole notification. A notification already reserved
        // before close may finish; a read completing after close cannot reserve one.
        if (!state.compareAndSet(State.Active, State.Invalid(denial))) return
        try { stopScheduling() } finally { onInvalidated(denial) }
    }

    private fun start() {
        if (check() != null) return
        val owned = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "AIyou-source-watch").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        }
        executor.set(owned)
        if (state.get() != State.Active) { stopScheduling(); return }
        try {
            val task = owned.scheduleWithFixedDelay({
                try { check() } catch (failure: Throwable) {
                    // A cancelled/failed observation must not silently abandon a
                    // live request. Fatal failures are rethrown after fail-closed signal.
                    invalidate(if (failure is CancellationException) SendDenial.EXECUTION_CANCELLED
                        else SendDenial.STATE_UNAVAILABLE)
                    if (failure is VirtualMachineError || failure is ThreadDeath || failure is LinkageError)
                        throw failure
                }
            }, INTERVAL_MILLIS, INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            scheduled.set(task)
            if (state.get() != State.Active) stopScheduling()
        } catch (failure: Throwable) {
            invalidate(SendDenial.STATE_UNAVAILABLE)
            stopScheduling()
            if (failure is CancellationException || failure is VirtualMachineError ||
                failure is ThreadDeath || failure is LinkageError) throw failure
        }
    }

    override fun close() {
        state.set(State.Closed)
        stopScheduling()
    }

    private fun stopScheduling() {
        scheduled.getAndSet(null)?.cancel(false)
        executor.getAndSet(null)?.shutdown()
    }

    companion object {
        private const val INTERVAL_MILLIS = 100L
        fun open(readAuthority: () -> SendDenial?, onInvalidated: (SendDenial) -> Unit): SourceBoundTextSubscription =
            PersistentSourceBoundTextSubscription(readAuthority, onInvalidated).also { it.start() }
    }
}
