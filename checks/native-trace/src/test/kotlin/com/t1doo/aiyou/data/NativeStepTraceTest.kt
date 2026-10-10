package com.t1doo.aiyou.data

import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

class NativeStepTraceTest {
    private var wall = 100L
    private var cpu = 20L
    private val events = mutableListOf<TraceEvent>()
    private fun recorder(stacks: () -> List<TraceThread> = { emptyList() }) =
        NativeStepTrace({ wall }, { cpu }, stacks, events::add)

    @Test fun distinguishesWallWaitFromCpuAndRetainsOpenHistory() {
        val trace = recorder()
        assertEquals("value", trace.measure("open", 1, "FRESH_FILE_FIRST_OPEN") { wall += 30_000; cpu += 7; "value" })
        trace.measure("open", 1, "REOPEN_SAME_PROCESS") { wall += 50; cpu += 10 }
        assertEquals(listOf("start", "end", "start", "end"), events.map { it.event })
        assertEquals(30_000L, events[1].wallMillis)
        assertEquals(7L, events[1].processCpuMillis)
        assertEquals(listOf(1, 1, 2, 2), events.map { it.id })
        assertEquals("REOPEN_SAME_PROCESS", events.last().openKind)
    }

    @Test fun slowSamplesAreBoundedAndCaptureWaitFramesOnlyForActiveStep() {
        var calls = 0
        val trace = recorder { calls++; List(40) { TraceThread("thread$it", "WAITING", List(20) { "frame$it" }) } }
        trace.sampleSlowStep()
        trace.measure("close") {
            trace.sampleSlowStep(); assertEquals(0, calls)
            wall += 10_000
            repeat(5) { trace.sampleSlowStep() }
        }
        trace.sampleSlowStep()
        assertEquals(3, calls)
        val samples = events.filter { it.event == "slow_sample" }
        assertEquals(3, samples.size)
        assertEquals(32, samples.first().threads.size)
        assertEquals(16, samples.first().threads.first().frames.size)
        assertEquals("WAITING", samples.first().threads.first().state)
    }

    @Test fun productFailureIdentitySurvivesAndNextStepCanRun() {
        val trace = recorder()
        val expected = IllegalStateException("synthetic exception; message must not enter trace")
        try { trace.measure("save_draft") { throw expected }; fail() }
        catch (actual: IllegalStateException) { assertSame(expected, actual) }
        assertEquals(expected.javaClass.name, events.last().failureType)
        assertFalse(events.toString().contains(expected.message!!))
        trace.measure("close") { }
        assertNull(events.last().failureType)
    }

    @Test fun observerFailureCannotMaskProductFailure() {
        val trace = NativeStepTrace({ wall }, { cpu }, { emptyList() }, { throw IllegalArgumentException("observer") })
        val expected = IllegalStateException("product")
        try { trace.measure("open") { throw expected }; fail() }
        catch (actual: IllegalStateException) { assertSame(expected, actual) }
        assertEquals(42, trace.measure("close") { 42 })
    }

    @Test fun invalidOrNestedStepIsRejectedWithoutCorruptingOuterTrace() {
        val trace = recorder()
        try { trace.measure("record body") { fail() }; fail() } catch (_: IllegalArgumentException) { }
        trace.measure("open") {
            try { trace.measure("close") { fail() }; fail() } catch (_: IllegalStateException) { }
        }
        assertEquals(listOf("start", "end"), events.map { it.event })
        assertNull(events.last().failureType)
    }

    @Test fun suspendedOperationRemainsActiveUntilActualContinuationCompletes() {
        val trace = recorder()
        var waiting: Continuation<Int>? = null
        var completed: Result<Int>? = null
        val operation: suspend () -> Int = {
            trace.measureSuspending("save_draft", 2) { suspendCoroutine { waiting = it } }
        }
        operation.startCoroutine(object : Continuation<Int> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Int>) { completed = result }
        })
        assertNull(completed)
        wall += 12_000
        trace.sampleSlowStep()
        assertEquals(listOf("start", "slow_sample"), events.map { it.event })
        checkNotNull(waiting).resumeWith(Result.success(7))
        assertEquals(7, checkNotNull(completed).getOrThrow())
        assertEquals("end", events.last().event)
        assertEquals(12_000L, events.last().wallMillis)
        assertEquals(0L, events.last().processCpuMillis)
    }

    @Test fun stackCaptureFailureIsExplicitAndCannotLookLikeAValidSnapshot() {
        val trace = recorder { throw SecurityException("synthetic observer failure") }
        trace.measure("open") { wall += 10_000; trace.sampleSlowStep() }
        val sample = events.single { it.event == "slow_sample" }
        assertEquals(1, sample.sampleIndex)
        assertEquals(SecurityException::class.java.name, sample.sampleError)
        assertTrue(sample.threads.isEmpty())
        assertNull(events.last().failureType)
    }

    @Test fun cappedSnapshotKeepsTheOriginalOperationThread() {
        val owner = Thread.currentThread().id
        val trace = recorder {
            List(40) { TraceThread("unrelated$it", "WAITING", emptyList(), owner + it + 1) } +
                TraceThread("operation", "RUNNABLE", listOf("nativeOpen"), owner)
        }
        trace.measure("open") { wall += 10_000; trace.sampleSlowStep() }
        val sample = events.single { it.event == "slow_sample" }
        assertEquals(32, sample.threads.size)
        assertEquals(owner, sample.threads.first().javaThreadId)
        assertEquals(owner, sample.ownerJavaThreadId)
    }
}
