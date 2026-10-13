package com.t1doo.aiyou.records

import com.t1doo.aiyou.ai.SourceBoundStreamingExchange
import com.t1doo.aiyou.ai.StreamEvent
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.RemoteSendIntent
import com.t1doo.aiyou.model.SourceVersion
import com.t1doo.aiyou.model.TextPurpose
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** IO work is independent of both the record queue and the cancellable ViewModel scope. */
internal class LocalRecordAiController(
    private val currentPolicy: (SourceVersion) -> ProcessingPolicy?,
    private val publish: (RecordAiUiState?) -> Unit,
) {
    private val lock = Any()
    private val lifetime: CompletableJob = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.IO)
    private var owner: RecordsStorageOwnership? = null
    private var capability: RecordAiCapability? = null
    private var session: Session? = null
    private var ids = 0L
    private var disposed = false
    private var closing: Deferred<Unit>? = null
    private var previousRun: Job? = null
    private val work = mutableSetOf<Job>()
    private var firstFatal: Throwable? = null

    private class Session(val state: RecordAiUiState, val owner: RecordsStorageOwnership?) {
        var current = state
        var live = true
        var run: Run? = null
    }
    private class Run(val session: Session) {
        var exchange: SourceBoundStreamingExchange? = null
        var accepting = true
    }

    /** Called only after local preparation. Retry with the same owner cannot replace its transport. */
    fun bind(value: RecordsStorageOwnership) = synchronized(lock) {
        if (!disposed) {
            check(owner == null || owner === value) { "An open records owner cannot be replaced" }
            owner = value
        }
    }

    /** Every open, including an ABA reopen of the same ID/revision, has a new identity. */
    fun open(source: SourceVersion, policy: ProcessingPolicy): Long = synchronized(lock) {
        if (disposed) return 0L
        invalidateLocked(session)
        val notice = when {
            policy == ProcessingPolicy.AI_NONE -> "这条原文不参与 AI 处理。"
            policy == ProcessingPolicy.LOCAL_ONLY -> "这条原文仅在本机处理，未授权发送给模型。"
            owner?.openAi == null -> "模型连接尚未开放，当前不会发送原文。"
            firstFatal != null -> "整理暂不可用，原文保存不受影响。"
            currentPolicy(source) == null -> "这份原文版本已不可用，请重新打开当前原文。"
            else -> "仅整理这份已授权原文；预览尚未保存。"
        }
        val enabled = firstFatal == null && policy == ProcessingPolicy.REMOTE_ALLOWED && owner?.openAi != null &&
            currentPolicy(source) == ProcessingPolicy.REMOTE_ALLOWED
        val next = Session(RecordAiUiState(++ids, source,
            if (enabled) RecordAiPhase.READY else RecordAiPhase.BLOCKED, notice), owner)
        session = next
        publish(next.current)
        next.current.sessionId
    }

    fun start(sessionId: Long) {
        val job = synchronized(lock) {
            val selected = session ?: return
            if (disposed || firstFatal != null || !selected.live || selected.current.sessionId != sessionId ||
                selected.current.phase != RecordAiPhase.READY || selected.owner !== owner ||
                currentPolicy(selected.current.source) != ProcessingPolicy.REMOTE_ALLOWED) return
            val openAi = selected.owner?.openAi ?: return
            val running = Run(selected)
            selected.run = running
            selected.current = selected.current.copy(phase = RecordAiPhase.RUNNING,
                notice = "正在整理，预览尚未保存。", preview = "", annotation = null)
            publish(selected.current)
            val predecessor = previousRun
            val intent = RemoteSendIntent(selected.current.source, TextPurpose.ORGANIZATION)
            scope.launch(start = CoroutineStart.LAZY) {
                var exchange: SourceBoundStreamingExchange? = null
                var lateCapability: RecordAiCapability? = null
                try {
                    predecessor?.join() // Only AI waits; local record commands continue independently.
                    if (!owns(running)) return@launch
                    val active = synchronized(lock) { capability } ?: openAi().let { created ->
                        synchronized(lock) {
                            if (ownsLocked(running)) capability = created else lateCapability = created
                        }
                        created
                    }
                    if (!owns(running)) return@launch
                    exchange = active.newExchange(intent)
                    val execute = synchronized(lock) {
                        running.exchange = exchange
                        ownsLocked(running)
                    }
                    if (!execute) return@launch
                    val outcome = exchange.execute { event ->
                        synchronized(lock) {
                            if (!ownsLocked(running)) return@synchronized
                            selected.current = when (event) {
                                is StreamEvent.TextDelta -> selected.current.copy(preview = selected.current.preview + event.text)
                                StreamEvent.Completed -> selected.current.copy(phase = RecordAiPhase.CONFIRMING,
                                    notice = "正在确认整理结果，尚未保存。")
                                else -> selected.current
                            }
                            // No SQL, arbitrary consumer or queued Main callback inside this gate.
                            publish(selected.current)
                        }
                    }
                    synchronized(lock) {
                        if (!ownsLocked(running)) return@synchronized
                        running.accepting = false
                        val annotation = outcome.annotation
                        selected.current = when {
                            outcome.terminal == StreamEvent.Completed && annotation != null &&
                                annotation.source == selected.current.source -> selected.current.copy(
                                phase = RecordAiPhase.SUCCEEDED, notice = "整理候选已保存在本机，尚未确认为事实或待办。",
                                preview = "", annotation = annotation)
                            outcome.terminal == StreamEvent.Cancelled -> selected.current.copy(
                                phase = RecordAiPhase.CANCELLED, notice = "整理已停止，预览未保存。", preview = "", annotation = null)
                            else -> selected.current.copy(phase = RecordAiPhase.FAILED,
                                notice = "整理未完成，原文保存不受影响。", preview = "", annotation = null)
                        }
                        publish(selected.current)
                    }
                } catch (failure: Throwable) {
                    synchronized(lock) {
                        if (ownsLocked(running)) {
                            running.accepting = false
                            selected.current = selected.current.copy(
                                phase = if (failure is CancellationException) RecordAiPhase.CANCELLED else RecordAiPhase.FAILED,
                                notice = if (failure is CancellationException) "整理已停止，预览未保存。"
                                    else "整理未完成，原文保存不受影响。", preview = "", annotation = null)
                            publish(selected.current)
                        }
                    }
                    rethrowFatal(failure)
                    if (failure is CancellationException) throw failure
                } finally {
                    // Includes a capability that arrived after stop/dispose; never lose queued work.
                    try { exchange?.cancel() } catch (failure: Throwable) { rethrowFatal(failure) }
                    finally {
                        try { lateCapability?.close() } catch (failure: Throwable) { rethrowFatal(failure) }
                        finally {
                            synchronized(lock) { running.accepting = false; running.exchange = null }
                        }
                    }
                }
            }.also { owned ->
                previousRun = owned
                registerLocked(owned)
            }
        }
        job.start()
    }

    fun stop(sessionId: Long): Unit = synchronized(lock) {
        val selected = session ?: return
        if (disposed || selected.current.sessionId != sessionId) return
        invalidateLocked(selected)
        if (selected.current.phase != RecordAiPhase.SUCCEEDED) selected.current = selected.current.copy(
            phase = RecordAiPhase.CANCELLED, notice = "整理已停止，预览未保存。", preview = "", annotation = null)
        publish(selected.current)
    }

    fun leave(sessionId: Long): Unit = synchronized(lock) {
        if (disposed || session?.current?.sessionId != sessionId) return
        invalidateLocked(session)
        session = null
        publish(null)
    }

    fun reconcileRecords(): Unit = synchronized(lock) {
        val selected = session ?: return
        val policy = currentPolicy(selected.current.source)
        if (!disposed && selected.live && (policy == null ||
                selected.current.phase != RecordAiPhase.BLOCKED && policy != ProcessingPolicy.REMOTE_ALLOWED)) {
            invalidateLocked(selected)
            selected.current = selected.current.copy(phase = RecordAiPhase.CANCELLED,
                notice = "原文或版本已变化，请重新打开当前原文。", preview = "", annotation = null)
            publish(selected.current)
        }
    }

    /** Identity invalidation is immediate. Blocking transport/SQL cleanup is scheduled on owned IO. */
    fun markDisposed() {
        val shutdown = synchronized(lock) {
            closing?.let { return }
            disposed = true
            invalidateLocked(session)
            session = null
            publish(null)
            val retiring = capability
            val pending = work.toList()
            scope.async<Unit>(start = CoroutineStart.LAZY) {
                try { retiring?.close() } catch (failure: Throwable) {
                    rememberFatal(failure)
                }
                // Factory, execute and cancellation workers still borrow this records owner.
                pending.forEach { it.join() }
                lifetime.complete()
                synchronized(lock) { firstFatal }?.let { throw it }
            }.also { closing = it }
        }
        shutdown.start()
    }

    /** Caller must be an uncancelled IO cleanup context. This does not close shared records. */
    suspend fun closeAndJoin() {
        markDisposed()
        checkNotNull(synchronized(lock) { closing }).await()
    }

    private fun invalidateLocked(selected: Session?) {
        if (selected == null || !selected.live) return
        selected.live = false
        selected.run?.let { running ->
            running.accepting = false
            running.exchange?.let { exchange ->
                val cancel = scope.launch(start = CoroutineStart.LAZY) {
                    try { exchange.cancel() } catch (failure: Throwable) { rethrowFatal(failure) }
                }
                registerLocked(cancel)
                cancel.start()
            }
        }
    }

    private fun registerLocked(job: Job) {
        work.add(job)
        job.invokeOnCompletion { failure -> synchronized(lock) {
            work.remove(job)
            if (failure != null) rememberFatal(failure)
        } }
    }

    private fun owns(running: Run) = synchronized(lock) { ownsLocked(running) }
    private fun ownsLocked(running: Run): Boolean = !disposed && firstFatal == null && running.accepting && running.session.live &&
        session === running.session && running.session.run === running && running.session.owner === owner &&
        currentPolicy(running.session.current.source) == ProcessingPolicy.REMOTE_ALLOWED

    private fun isFatal(failure: Throwable) = failure is VirtualMachineError || failure is ThreadDeath || failure is LinkageError
    private fun rememberFatal(failure: Throwable) = synchronized(lock) {
        if (isFatal(failure) && firstFatal == null) firstFatal = failure
    }
    private fun rethrowFatal(failure: Throwable) {
        if (isFatal(failure)) { rememberFatal(failure); throw failure }
    }
}
