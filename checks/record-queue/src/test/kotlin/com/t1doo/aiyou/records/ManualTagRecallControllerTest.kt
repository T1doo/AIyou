package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Actual production matcher/controller and controlled owned workers; no Android/storage claim. */
class ManualTagRecallControllerTest {
    private val entry = StoredEntry("synthetic", 1, "咖啡原文", 1, "UTC", ProcessingPolicy.LOCAL_ONLY)
    private fun tags(version: Long, present: Boolean = true) = mapOf(entry.id to ManualTagSnapshot(entry.id, version,
        if (present) listOf("旅行") else emptyList()))
    private fun local(version: Long = 1, present: Boolean = true) = LocalRecordUiState(ready = true, opening = false,
        entries = listOf(entry), manualTagsAvailable = true, manualTags = tags(version, present))
    private fun ready(c: LocalOriginalSearchController) = runBlocking {
        withTimeout(2_000) { c.state.first { it.stage == OriginalSearchStage.READY } }
    }

    @Test fun keywordTagSnapshotReplacementRetiresOpeningBeforeRecompositionAndSameNamesCannotRestoreOldResult() {
        val source = AtomicReference(local()); val input = AtomicReference(OriginalSearchInput("旅行", tagSnapshot = source.get().manualTags))
        val c = LocalOriginalSearchController(source::get, input::get)
        try {
            c.sync(true); val old = ready(c); val hit = old.hits.single()
            assertFalse(hit.originalMatchesQuery); assertTrue(c.canOpen(old, hit))
            source.set(source.get().copy(manualTags = tags(2, false)))
            assertFalse(c.canOpen(old, hit)); assertEquals(OriginalSearchStage.WAITING, c.visible(source.get(), input.get()).stage)
            var opened = false; c.open(old, hit) { opened = true }; assertFalse(opened)
            input.set(input.get().copy(tagSnapshot = source.get().manualTags)); c.sync(true)
            assertTrue(ready(c).hits.isEmpty())
            source.set(source.get().copy(manualTags = tags(3)))
            input.set(input.get().copy(tagSnapshot = source.get().manualTags)); c.sync(true)
            val current = ready(c); assertEquals(entry.id, current.hits.single().entry.id)
            assertFalse(c.canOpen(old, hit)); assertTrue(c.canOpen(current, current.hits.single()))
            c.close(); c.open(current, current.hits.single()) { opened = true }; assertFalse(opened)
        } finally { c.close() }
    }

    @Test fun actualLateTagMatchCannotPublishAfterTagRemovalBeforeRenderOrOwnerClose() {
        for (close in listOf(false, true)) Workers().use { workers ->
            val source = AtomicReference(local()); val input = AtomicReference(OriginalSearchInput("旅行", tagSnapshot = source.get().manualTags))
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val c = LocalOriginalSearchController(source::get, input::get, workers) { entries, query ->
                withContext(NonCancellable) {
                    val result = LocalOriginalSearchMatcher.match(entries, query)
                    assertEquals(entry.id, result.hits.single().entry.id)
                    entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); result
                }
            }
            try {
                c.sync(true); assertTrue(entered.await(2, TimeUnit.SECONDS))
                if (close) c.close() else source.set(source.get().copy(manualTags = tags(2, false)))
                release.countDown(); assertTrue(workers.exited.await(2, TimeUnit.SECONDS))
                assertEquals(OriginalSearchStage.WAITING, c.state.value.stage); assertTrue(c.state.value.hits.isEmpty())
                assertEquals(OriginalSearchStage.WAITING, c.visible(source.get(), input.get()).stage)
            } finally { release.countDown(); c.close() }
        }
    }
    private class Workers : CoroutineDispatcher(), AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        val exited = CountDownLatch(1)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            executor.execute { try { block.run() } finally { exited.countDown() } }
        }
        override fun close() { executor.shutdown(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)) }
    }
}
