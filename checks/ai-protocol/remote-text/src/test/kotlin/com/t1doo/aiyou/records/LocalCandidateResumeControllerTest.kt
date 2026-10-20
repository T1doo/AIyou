package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.LocalCandidateFixture
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class LocalCandidateResumeControllerTest {
    @Test(timeout = 15_000) fun producerExitBetweenOpenSnapshotAndPublicationCannotLeaveRecoveryPermanentlyDisabled() = LocalCandidateFixture().use { f ->
        f.store.begin(f.start())
        val state = MutableStateFlow(LocalRecordUiState(ready = true, opening = false, supportsCandidates = true,
            entries = listOf(StoredEntry("source", 1, f.original, 5, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY))))
        val queue = ConcurrentLinkedQueue<suspend (EntryRepository) -> Unit>()
        val firstEntered = CountDownLatch(1); val release = CountDownLatch(1); val slotCleared = CountDownLatch(1)
        val completionQueued = CountDownLatch(1); val gapArmed = AtomicBoolean(); val crossedGap = AtomicBoolean()
        val productions = AtomicInteger(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = object : EntryRepository {
            override val localCandidates = f.store
            override suspend fun readDraft(): TextDraft? = null
            override suspend fun updateDraft(text: String): TextDraft = error("Not used")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Not used")
            override suspend fun entries() = state.value.entries
            override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Not used")
        }
        val controller = LocalCandidateController({ state.value }, { change ->
            val next = change(state.value)
            if (gapArmed.get() && next.candidates?.retiringComputation == true && crossedGap.compareAndSet(false, true)) {
                // open already observed the occupied slot, but has not published its new session.
                release.countDown(); check(slotCleared.await(3, TimeUnit.SECONDS))
            }
            val notifySlot = gapArmed.get() && next.candidates == null
            state.value = next
            // Observe completion only after its empty-panel publication, so the test's controlled
            // publisher cannot itself overwrite the new panel after unblocking open.
            if (notifySlot) slotCleared.countDown()
        }, { action -> queue.add(action); if (productions.get() == 2) completionQueued.countDown(); true }, scope, Clock.systemUTC(),
            extract = { job ->
                if (productions.incrementAndGet() == 1) { firstEntered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                LocalExcerptRules.extract(job)
            })
        fun drain() = runBlocking { while (true) (queue.poll() ?: break)(store) }
        try {
            controller.open("source", 1); drain()
            val first = checkNotNull(state.value.candidates); controller.resume(first.session, "batch", 0); drain()
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            controller.close(first.session); gapArmed.set(true)
            controller.open("source", 1)
            assertTrue(crossedGap.get()); assertFalse(checkNotNull(state.value.candidates).retiringComputation)
            gapArmed.set(false); drain()
            val current = checkNotNull(state.value.candidates); controller.resume(current.session, "batch", 0); drain()
            assertTrue(completionQueued.await(2, TimeUnit.SECONDS)); drain()
            val batch = f.store.batches(f.source).single()
            assertEquals("batch", batch.id); assertEquals(1L, batch.version)
            assertEquals(ExcerptReview.PROPOSED, batch.items.single().review); assertEquals(2, productions.get())
        } finally { release.countDown(); controller.dispose(); scope.cancel() }
    }
}
