package com.t1doo.aiyou.data

import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in instrumentation diagnostics; labels describe process/file history, never OS cache state. */
internal class AndroidNativeTrace : AutoCloseable {
    private val enabled = InstrumentationRegistry.getArguments().getString("aiyouNativeTrace") == "1"
    private val running = AtomicBoolean(enabled)
    private val recorder = NativeStepTrace(SystemClock::elapsedRealtime, Process::getElapsedCpuTime,
        {
            Thread.getAllStackTraces().entries.sortedBy { it.key.name }.map { (thread, frames) ->
                TraceThread(thread.name, thread.state.name, frames.take(16).map { it.toString() }, thread.id)
            }
        }, { event ->
            val payload = JSONObject().put("event", event.event).put("id", event.id)
                .put("step", event.step).put("iteration", event.iteration).put("pid", Process.myPid())
                .put("elapsedRealtimeMillis", SystemClock.elapsedRealtime())
                .put("wallMillis", event.wallMillis).put("processCpuMillis", event.processCpuMillis)
                .put("openKind", event.openKind ?: JSONObject.NULL)
                .put("failureType", event.failureType ?: JSONObject.NULL)
                .put("observerFailures", event.observerFailures)
                .put("ownerJavaThreadId", event.ownerJavaThreadId).put("emitterTid", Process.myTid())
                .put("sampleIndex", event.sampleIndex ?: JSONObject.NULL)
                .put("sampleError", event.sampleError ?: JSONObject.NULL).put("threadCount", event.threads.size)
            // One JSON log line per thread avoids Android log-entry truncation of a complete snapshot.
            Log.i("AIyouD02Trace", payload.toString())
            event.threads.forEach { thread ->
                Log.i("AIyouD02Trace", JSONObject().put("event", "thread").put("id", event.id)
                    .put("pid", Process.myPid()).put("name", thread.name).put("state", thread.state)
                    .put("sampleIndex", event.sampleIndex).put("javaThreadId", thread.javaThreadId)
                    .put("frames", JSONArray(thread.frames)).toString())
            }
        })
    private val watchdog = if (enabled) Thread({
        while (running.get()) {
            try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
            if (running.get()) recorder.sampleSlowStep()
        }
    }, "aiyou-test-trace-watchdog").apply { isDaemon = true; start() } else null

    fun <T> step(name: String, iteration: Int = 0, openKind: String? = null, block: () -> T): T =
        if (enabled) recorder.measure(name, iteration, openKind, block) else block()

    suspend fun <T> suspending(name: String, iteration: Int = 0, block: suspend () -> T): T =
        if (enabled) recorder.measureSuspending(name, iteration, block) else block()

    override fun close() { running.set(false); watchdog?.interrupt() }
}
