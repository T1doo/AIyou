package com.t1doo.aiyou.data

/** Test-only observer. No SQL parameters, record bodies, paths or keys enter its events. */
internal class NativeStepTrace(
    private val wallMillis: () -> Long,
    private val processCpuMillis: () -> Long,
    private val stacks: () -> List<TraceThread>,
    private val sink: (TraceEvent) -> Unit,
    private val slowAfterMillis: Long = 10_000,
    private val maxSamplesPerStep: Int = 3,
) {
    private data class Active(val id: Int, val step: String, val iteration: Int, val openKind: String?,
                              val wall: Long, val cpu: Long, val ownerJavaThreadId: Long, var samples: Int = 0)
    private val lock = Any()
    private var active: Active? = null
    private var nextId = 0
    private var sinkFailures = 0

    private fun begin(step: String, iteration: Int, openKind: String?): Active {
        require(step.matches(Regex("[a-z_]+")))
        require(openKind == null || openKind in setOf("FRESH_FILE_FIRST_OPEN", "REOPEN_SAME_PROCESS",
            "EXISTING_FILE_FIRST_OPEN_NEW_PROCESS"))
        val value = synchronized(lock) {
            check(active == null) { "Trace steps must be sequential" }
            Active(++nextId, step, iteration, openKind, wallMillis(), processCpuMillis(), Thread.currentThread().id)
                .also { active = it }
        }
        emit(value.event("start"))
        return value
    }

    private fun Active.event(kind: String, failure: String? = null, threads: List<TraceThread> = emptyList(),
                             sampleIndex: Int? = null, sampleError: String? = null) =
        TraceEvent(kind, id, step, iteration, openKind, wallMillis() - wall,
            processCpuMillis() - cpu, failure, threads, synchronized(lock) { sinkFailures },
            ownerJavaThreadId, sampleIndex, sampleError)

    private fun end(value: Active, failure: Throwable?) {
        val event = value.event("end", failure?.javaClass?.name)
        synchronized(lock) { check(active === value); active = null }
        emit(event)
    }

    private fun emit(event: TraceEvent) {
        // An observer failure must never replace a product exception or change transaction behavior.
        try { sink(event) } catch (_: Exception) { synchronized(lock) { sinkFailures++ } }
    }

    fun <T> measure(step: String, iteration: Int = 0, openKind: String? = null, block: () -> T): T {
        val value = begin(step, iteration, openKind)
        var failure: Throwable? = null
        try { return block() } catch (error: Throwable) { failure = error; throw error }
        finally { end(value, failure) }
    }

    suspend fun <T> measureSuspending(step: String, iteration: Int = 0, block: suspend () -> T): T {
        val value = begin(step, iteration, null)
        var failure: Throwable? = null
        try { return block() } catch (error: Throwable) { failure = error; throw error }
        finally { end(value, failure) }
    }

    /** Called by one watchdog; reserve a bounded sample under the lock, capture stacks outside it. */
    fun sampleSlowStep() {
        val reserved = synchronized(lock) {
            active?.takeIf { wallMillis() - it.wall >= slowAfterMillis && it.samples < maxSamplesPerStep }
                ?.let { it.samples++; it to it.samples }
        } ?: return
        val (value, sampleIndex) = reserved
        var sampleError: String? = null
        val threads = try { stacks().sortedBy { if (it.javaThreadId == value.ownerJavaThreadId) 0 else 1 }.take(32).map {
            it.copy(name = it.name.take(96), frames = it.frames.take(16).map { frame -> frame.take(180) })
        } }
        catch (error: Exception) { sampleError = error.javaClass.name; emptyList() }
        // A sample can finish after end; id identifies the original step without attributing it to the next.
        emit(value.event("slow_sample", threads = threads, sampleIndex = sampleIndex, sampleError = sampleError))
    }
}

internal data class TraceThread(val name: String, val state: String, val frames: List<String>, val javaThreadId: Long = 0)
internal data class TraceEvent(
    val event: String, val id: Int, val step: String, val iteration: Int, val openKind: String?,
    val wallMillis: Long, val processCpuMillis: Long, val failureType: String?,
    val threads: List<TraceThread>, val observerFailures: Int,
    val ownerJavaThreadId: Long, val sampleIndex: Int?, val sampleError: String?,
)
