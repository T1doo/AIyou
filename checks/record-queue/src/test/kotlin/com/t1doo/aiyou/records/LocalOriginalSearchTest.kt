package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual app matcher/controller and immutable current-original snapshots. No storage or device claim. */
class LocalOriginalSearchTest {
    @Test(timeout = 5_000) fun manualTagOnlyAndCombinedFiltersFenceTagChangesAndReadFailureBeforeComposition() = runBlocking {
        val records = listOf(entry("one", "咖啡原文"), entry("two", "咖啡原文"), entry("three", "茶原文"))
        fun tags(version: Long) = mapOf("one" to com.t1doo.aiyou.model.ManualTagSnapshot("one", version, listOf("手工🙂")),
            "three" to com.t1doo.aiyou.model.ManualTagSnapshot("three", version, listOf("手工🙂")))
        val source = AtomicReference(LocalRecordUiState(ready = true, opening = false, entries = records,
            manualTags = tags(1), manualTagsAvailable = true))
        val input = AtomicReference(OriginalSearchInput(tag = "手工🙂", tagSnapshot = source.get().manualTags))
        val calls = AtomicInteger()
        val c = LocalOriginalSearchController(source::get, input::get) { entries, query -> calls.incrementAndGet(); LocalOriginalSearchMatcher.match(entries, query) }
        try {
            c.sync(true)
            val only = withTimeout(2_000) { c.state.first { it.stage == OriginalSearchStage.READY } }
            assertEquals(listOf("one", "three"), only.hits.map { it.entry.id })
            input.set(OriginalSearchInput("咖啡", "2026-10-13", "2026-10-13", tag = "手工🙂", tagSnapshot = source.get().manualTags))
            c.sync(true)
            val combined = withTimeout(2_000) { c.state.first { it.stage == OriginalSearchStage.READY && it.input.query == "咖啡" } }
            assertEquals(listOf("one"), combined.hits.map { it.entry.id }); assertTrue(c.canOpen(combined, combined.hits.single()))
            source.set(source.get().copy(manualTags = tags(2)))
            assertFalse(c.canOpen(combined, combined.hits.single()))
            input.set(input.get().copy(tagSnapshot = source.get().manualTags)); c.sync(true)
            withTimeout(2_000) { c.state.first { it.stage == OriginalSearchStage.READY && it !== combined } }
            source.set(source.get().copy(manualTagsAvailable = false, manualTags = emptyMap()))
            input.set(input.get().copy(tagSnapshot = null)); c.sync(true)
            val failed = withTimeout(2_000) { c.state.first { it.stage == OriginalSearchStage.FAILED } }
            assertTrue(failed.hits.isEmpty()); assertSame(failed, c.visible(source.get(), input.get()))
            val bounded = calls.get(); repeat(10) { c.sync(true) }; assertEquals(bounded, calls.get())
            source.set(source.get().copy(manualTagsAvailable = true, manualTags = emptyMap()))
            input.set(input.get().copy(tagSnapshot = source.get().manualTags)); c.sync(true)
            val empty = withTimeout(2_000) { c.state.first { it.stage == OriginalSearchStage.READY } }
            assertTrue(empty.hits.isEmpty()); assertFalse(c.canOpen(combined, combined.hits.single()))
        } finally { c.close() }
    }
    @Test(timeout = 5_000) fun keywordAndIsLiteralUnicodeLocaleIndependentAndPreservesSnapshotOrderAndPolicy() = runBlocking {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val text = "MiXeD 咖啡🙂 字面.* + %_[ 合成当前原文"
            val entries = listOf(entry("remote", text, policy = ProcessingPolicy.REMOTE_ALLOWED),
                entry("missing", "MiXeD 咖啡🙂 字面.* + 合成当前原文"), entry("none", text, policy = ProcessingPolicy.AI_NONE),
                entry("local", text), entry("draft-only", "合成当前原文，不含所查词"))
            val found = LocalOriginalSearchMatcher.match(entries, OriginalSearchInput("mixed\u2003咖啡\t🙂\n.* + %_["))
            assertEquals(OriginalSearchStage.READY, found.stage)
            assertEquals(listOf("remote", "none", "local"), found.hits.map { it.entry.id })
            assertEquals(listOf(ProcessingPolicy.REMOTE_ALLOWED, ProcessingPolicy.AI_NONE, ProcessingPolicy.LOCAL_ONLY), found.hits.map { it.entry.policy })
            found.hits.forEach { assertSame(entries.single { e -> e.id == it.entry.id }, it.entry) }
            assertTrue(LocalOriginalSearchMatcher.match(entries, OriginalSearchInput("MiXeD 不存在")).hits.isEmpty())
            assertTrue(LocalOriginalSearchMatcher.match(entries, OriginalSearchInput(".*?")).hits.isEmpty())
            val repeated = listOf(entry("long", "a".repeat(99_997) + "b🙂"))
            assertTrue(LocalOriginalSearchMatcher.match(repeated, OriginalSearchInput("a".repeat(49_999) + "c", mode = OriginalSearchMode.EXACT)).hits.isEmpty())
            assertEquals(listOf("long"), ids(LocalOriginalSearchMatcher.match(repeated,
                OriginalSearchInput("a".repeat(49_999) + "b", mode = OriginalSearchMode.EXACT))))
        } finally { Locale.setDefault(oldLocale) }
    }

    @Test(timeout = 5_000) fun exactAndFoldedExcerptsRoundTripOriginalCodePointsWithoutChangingCaseOrWhitespace() = runBlocking {
        val prefix = "前🙂".repeat(150)
        val fragment = "İ𐐀🙂MiXeD \t咖啡e\u0301\n保持空白"
        val original = prefix + fragment + "后🌿".repeat(160)
        val record = entry("unicode", original)
        val exact = LocalOriginalSearchMatcher.match(listOf(record), OriginalSearchInput(fragment, mode = OriginalSearchMode.EXACT)).hits.single()
        val folded = LocalOriginalSearchMatcher.match(listOf(record), OriginalSearchInput("i𐐨🙂mixed")).hits.single()
        for (hit in listOf(exact, folded)) {
            assertSame(record, hit.entry)
            assertEquals(220, hit.startCodePoint); assertEquals(460, hit.endCodePoint)
            assertEquals(original.substring(original.offsetByCodePoints(0, hit.startCodePoint), original.offsetByCodePoints(0, hit.endCodePoint)), hit.quote)
            assertEquals(240, hit.quote.codePointCount(0, hit.quote.length)); assertTrue(hit.quote.contains(fragment))
            assertTrue(hit.leading); assertTrue(hit.trailing); assertEquals("…" + hit.quote + "…", hit.excerpt)
            assertWellFormed(hit.quote); assertWellFormed(hit.excerpt)
        }
        assertTrue(LocalOriginalSearchMatcher.match(listOf(record), OriginalSearchInput(fragment.replace("MiXeD", "mixed"), mode = OriginalSearchMode.EXACT)).hits.isEmpty())
        assertTrue(LocalOriginalSearchMatcher.match(listOf(record), OriginalSearchInput(fragment.replace(" \t", " "), mode = OriginalSearchMode.EXACT)).hits.isEmpty())
        assertTrue(LocalOriginalSearchMatcher.match(listOf(record), OriginalSearchInput("é")).hits.isEmpty())
        assertEquals(1, LocalOriginalSearchMatcher.match(listOf(record), OriginalSearchInput("e\u0301")).hits.size)
        val edge = LocalOriginalSearchMatcher.match(listOf(entry("edge", "🙂".repeat(300) + "末")), OriginalSearchInput("末")).hits.single()
        assertTrue(edge.leading); assertFalse(edge.trailing); assertEquals(301, edge.endCodePoint); assertWellFormed(edge.quote)
    }

    @Test(timeout = 5_000) fun captureDatesUseSavedIanaZoneInclusiveDaysAndDstWithOpenBounds() = runBlocking {
        val sameInstant = "2026-10-13T23:30:00Z"
        val zones = listOf(entry("utc", "日期", captured = sameInstant, zone = "UTC"), entry("shanghai", "日期", captured = sameInstant, zone = "Asia/Shanghai"))
        assertEquals(listOf("shanghai"), ids(LocalOriginalSearchMatcher.match(zones, OriginalSearchInput(from = "2026-10-14", through = "2026-10-14"))))
        assertEquals(listOf("utc"), ids(LocalOriginalSearchMatcher.match(zones, OriginalSearchInput(through = "2026-10-13"))))
        val dst = listOf(entry("before", "日期", captured = "2026-03-08T04:59:59Z", zone = "America/New_York"),
            entry("before-jump", "日期", captured = "2026-03-08T06:59:59Z", zone = "America/New_York"),
            entry("after-jump", "日期", captured = "2026-03-08T07:00:00Z", zone = "America/New_York"),
            entry("next-day", "日期", captured = "2026-03-09T04:00:00Z", zone = "America/New_York"))
        assertEquals(listOf("before-jump", "after-jump"), ids(LocalOriginalSearchMatcher.match(dst, OriginalSearchInput(from = "2026-03-08", through = "2026-03-08"))))
        assertEquals(listOf("before-jump", "after-jump", "next-day"), ids(LocalOriginalSearchMatcher.match(dst, OriginalSearchInput(from = "2026-03-08"))))
        assertEquals(listOf("before", "before-jump", "after-jump"), ids(LocalOriginalSearchMatcher.match(dst, OriginalSearchInput(through = "2026-03-08"))))
        val malformedZone = LocalOriginalSearchMatcher.match(listOf(entry("bad-zone", "日期", zone = "Invalid/Synthetic")), OriginalSearchInput(from = "2026-10-13"))
        assertEquals(OriginalSearchStage.FAILED, malformedZone.stage); assertTrue(malformedZone.hits.isEmpty()); assertNotNull(malformedZone.message)
    }

    @Test(timeout = 5_000) fun invalidConditionsAreDistinctFromNoHitsAndBlankKeywordIsBrowseButExactBlankIsLiteral() = runBlocking {
        val entries = listOf(entry("one", "保留 空白与原文"))
        for (input in listOf(OriginalSearchInput(from = "2026-02-30"), OriginalSearchInput(from = "2025-02-29"),
            OriginalSearchInput(from = "2026-1-01"), OriginalSearchInput(from = " 2026-10-13"),
            OriginalSearchInput(from = "2026-10-14", through = "2026-10-13"), OriginalSearchInput(query = "synthetic-private-query-\uD800"), OriginalSearchInput(query = "\uDC00"))) {
            val result = LocalOriginalSearchMatcher.match(entries, input)
            assertEquals(OriginalSearchStage.INVALID, result.stage); assertTrue(result.hits.isEmpty()); assertNotNull(result.message)
            assertFalse(result.message!!.contains("synthetic-private-query")); assertFalse(input.toString().contains("synthetic-private-query"))
        }
        assertEquals(OriginalSearchStage.BROWSE, LocalOriginalSearchMatcher.match(entries, OriginalSearchInput("\u2003\t\n")).stage)
        assertEquals(listOf("one"), ids(LocalOriginalSearchMatcher.match(entries, OriginalSearchInput(" ", mode = OriginalSearchMode.EXACT))))
        val miss = LocalOriginalSearchMatcher.match(entries, OriginalSearchInput("未命中合成词"))
        assertEquals(OriginalSearchStage.READY, miss.stage); assertTrue(miss.hits.isEmpty()); assertNull(miss.message)
        val leap = LocalOriginalSearchMatcher.match(listOf(entry("leap", "原文", captured = "2024-02-29T12:00:00Z")), OriginalSearchInput(from = "2024-02-29", through = "2024-02-29"))
        assertEquals(listOf("leap"), ids(leap))
    }

    @Test(timeout = 5_000) fun latestQueryAndSameQueryAbaRejectOldNonCancellableActualMatchingResults() {
        for (aba in listOf(false, true)) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
            val source = AtomicReference<LocalRecordUiState?>(local(listOf(entry("old", "旧词"), entry("next", "新词"))))
            val input = AtomicReference(OriginalSearchInput("旧词"))
            Workers().use { workers ->
                val c = LocalOriginalSearchController(source::get, input::get, workers) { entries, query ->
                    if (calls.incrementAndGet() == 1) withContext(NonCancellable) {
                        entered.countDown(); await(release); LocalOriginalSearchMatcher.match(entries, query)
                    } else LocalOriginalSearchMatcher.match(entries, query)
                }
                try {
                    c.sync(true); await(entered)
                    input.set(OriginalSearchInput("新词")); c.sync(true)
                    val next = ready(c, "新词"); assertEquals(listOf("next"), next.hits.map { it.entry.id })
                    val winner = if (aba) { input.set(OriginalSearchInput("旧词")); c.sync(true); ready(c, "旧词") } else next
                    assertEquals(if (aba) listOf("old") else listOf("next"), winner.hits.map { it.entry.id }); assertTrue(winner.generation > 1)
                    release.countDown(); await(workers.firstExited)
                    assertSame(winner, c.state.value); assertTrue(c.canOpen(winner, winner.hits.single())); assertEquals(if (aba) 3 else 2, calls.get())
                } finally { release.countDown(); c.close() }
            }
        }
    }

    @Test(timeout = 5_000) fun freshSnapshotFencesRevisionDeletionAndReadFailureBeforeSyncAndRejectsForeignHitIdentity() {
        for (variant in 0..4) {
            val entries = listOf(entry("source", "needle 当前原文"))
            val source = AtomicReference<LocalRecordUiState?>(local(entries)); val input = AtomicReference(OriginalSearchInput("needle"))
            Workers().use { workers ->
                val c = LocalOriginalSearchController(source::get, input::get, workers)
                try {
                    c.sync(true); val old = ready(c, "needle"); val hit = old.hits.single(); assertTrue(c.canOpen(old, hit))
                    val foreign = OriginalSearchHit(hit.entry, hit.quote, hit.startCodePoint, hit.endCodePoint, hit.leading, hit.trailing)
                    assertFalse(c.canOpen(old, foreign))
                    val changed = when (variant) { 0 -> local(listOf(entry("source", "needle 新版本", revision = 2)))
                        1 -> local(emptyList()); 2 -> local(entries).copy(ready = false); 3 -> local(entries).copy(opening = true)
                        else -> local(entries).copy(error = "synthetic local read failure") }
                    source.set(changed)
                    assertEquals(OriginalSearchStage.WAITING, c.visible(changed, input.get()).stage); assertFalse(c.canOpen(old, hit))
                    c.sync(true)
                    if (variant < 2) {
                        val fresh = ready(c, "needle")
                        assertEquals(if (variant == 0) listOf(2) else emptyList<Int>(), fresh.hits.map { it.entry.currentRevision })
                        if (variant == 0) assertTrue(c.canOpen(fresh, fresh.hits.single()))
                    } else assertEquals(OriginalSearchStage.WAITING, c.state.value.stage)
                    assertFalse(c.canOpen(old, hit))
                } finally { c.close() }
            }
        }
    }

    @Test(timeout = 5_000) fun rejectedCompletedWorkerInvalidatesRunningAndRestoredSnapshotCanFinish() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val original = local(listOf(entry("source", "needle 当前原文")))
        val source = AtomicReference<LocalRecordUiState?>(original)
        val input = AtomicReference(OriginalSearchInput("needle"))
        Workers().use { workers ->
            val c = LocalOriginalSearchController(source::get, input::get, workers) { entries, query ->
                if (calls.incrementAndGet() == 1) withContext(NonCancellable) {
                    entered.countDown(); await(release); LocalOriginalSearchMatcher.match(entries, query)
                } else LocalOriginalSearchMatcher.match(entries, query)
            }
            try {
                c.sync(true); await(entered); val running = c.state.value
                assertEquals(OriginalSearchStage.RUNNING, running.stage)
                // The authoritative source changes before a committed render can call sync.
                source.set(local(listOf(entry("source", "needle 修订原文", revision = 2))))
                release.countDown(); await(workers.firstExited)
                val rejected = c.state.value
                assertEquals(OriginalSearchStage.WAITING, rejected.stage); assertTrue(rejected.hits.isEmpty())
                assertEquals(running.generation, rejected.generation)
                source.set(original); c.sync(true)
                val recovered = ready(c, "needle")
                assertEquals(listOf(1), recovered.hits.map { it.entry.currentRevision })
                assertTrue(recovered.generation > running.generation); assertEquals(2, calls.get())
                assertTrue(c.canOpen(recovered, recovered.hits.single()))
                c.sync(true); assertSame(recovered, c.state.value); assertEquals(2, calls.get())
            } finally { release.countDown(); c.close() }
        }
    }

    @Test(timeout = 5_000) fun pauseUsesExactSnapshotCacheWithNewGenerationAndClearCannotReuseItsResults() {
        val source = AtomicReference<LocalRecordUiState?>(local(listOf(entry("source", "needle"))))
        val input = AtomicReference(OriginalSearchInput("needle")); val calls = AtomicInteger()
        Workers().use { workers ->
            val c = LocalOriginalSearchController(source::get, input::get, workers) { entries, query -> calls.incrementAndGet(); LocalOriginalSearchMatcher.match(entries, query) }
            try {
                c.sync(true); val first = ready(c, "needle"); c.sync(true); assertEquals(1, calls.get())
                c.pause(); assertFalse(c.canOpen(first, first.hits.single())); assertEquals(OriginalSearchStage.WAITING, c.visible(source.get(), input.get()).stage)
                c.sync(true); val cached = ready(c, "needle")
                assertNotSame(first, cached); assertTrue(cached.generation > first.generation); assertSame(first.hits.single(), cached.hits.single()); assertEquals(1, calls.get())
                assertFalse(c.canOpen(first, first.hits.single())); assertTrue(c.canOpen(cached, cached.hits.single()))
                source.set(local(ArrayList(checkNotNull(source.get()).entries))); assertFalse(c.canOpen(cached, cached.hits.single()))
                c.sync(true); val newSnapshot = ready(c, "needle"); assertEquals(2, calls.get()); assertNotSame(cached.hits.single(), newSnapshot.hits.single())
                input.set(OriginalSearchInput()); c.clear(); assertTrue(c.state.value.hits.isEmpty()); assertFalse(c.canOpen(newSnapshot, newSnapshot.hits.single()))
                c.sync(true); waitStage(c, OriginalSearchStage.BROWSE); assertEquals(2, calls.get())
                input.set(OriginalSearchInput("needle")); c.sync(true); val restarted = ready(c, "needle")
                assertEquals(3, calls.get()); assertNotSame(newSnapshot.hits.single(), restarted.hits.single())
            } finally { c.close() }
        }
    }

    @Test(timeout = 5_000) fun closedOwnerCannotResurrectAndOrdinaryMatcherFailureIsRedactedAndRetryable() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val source = AtomicReference<LocalRecordUiState?>(local(listOf(entry("source", "needle")))); val input = AtomicReference(OriginalSearchInput("needle"))
        Workers().use { workers ->
            val c = LocalOriginalSearchController(source::get, input::get, workers) { entries, query ->
                withContext(NonCancellable) { entered.countDown(); await(release); LocalOriginalSearchMatcher.match(entries, query) }
            }
            try {
                c.sync(true); await(entered); c.close(); val closed = c.state.value
                assertEquals(OriginalSearchStage.WAITING, closed.stage); assertTrue(closed.hits.isEmpty()); assertEquals(OriginalSearchInput(), closed.input)
                c.sync(true); c.pause(); release.countDown(); await(workers.firstExited)
                assertSame(closed, c.state.value); assertEquals(OriginalSearchStage.WAITING, c.visible(source.get(), input.get()).stage)
            } finally { release.countDown(); c.close() }
        }
        val calls = AtomicInteger(); val marker = "synthetic-private-search-failure"
        Workers().use { workers ->
            val c = LocalOriginalSearchController(source::get, input::get, workers) { entries, query ->
                if (calls.incrementAndGet() == 1) throw IllegalStateException(marker)
                LocalOriginalSearchMatcher.match(entries, query)
            }
            try {
                c.sync(true); val failed = waitStage(c, OriginalSearchStage.FAILED)
                assertTrue(failed.hits.isEmpty()); assertNotNull(failed.message); assertFalse(failed.message!!.contains(marker)); assertFalse(failed.toString().contains(marker))
                input.set(OriginalSearchInput("NEEDLE")); c.sync(true); val restored = ready(c, "NEEDLE")
                assertEquals(listOf("source"), restored.hits.map { it.entry.id }); assertEquals(2, calls.get()); assertTrue(c.canOpen(restored, restored.hits.single()))
            } finally { c.close() }
        }
    }

    private fun entry(id: String, text: String, revision: Int = 1, captured: String = "2026-10-13T12:00:00Z", zone: String = "UTC", policy: ProcessingPolicy = ProcessingPolicy.LOCAL_ONLY) =
        StoredEntry(id, revision, text, Instant.parse(captured).toEpochMilli(), zone, policy)
    private fun local(entries: List<StoredEntry>) = LocalRecordUiState(ready = true, opening = false, entries = entries)
    private fun ids(result: OriginalSearchMatches) = result.hits.map { it.entry.id }
    private fun ready(c: LocalOriginalSearchController, query: String): OriginalSearchState = runBlocking {
        withTimeout(2_000) { c.state.first { it.stage == OriginalSearchStage.READY && it.input.query == query } }
    }
    private fun waitStage(c: LocalOriginalSearchController, stage: OriginalSearchStage): OriginalSearchState = runBlocking {
        withTimeout(2_000) { c.state.first { it.stage == stage } }
    }
    private fun await(latch: CountDownLatch) = assertTrue("Owned worker barrier must complete", latch.await(2, TimeUnit.SECONDS))
    private fun assertWellFormed(text: String) {
        var at = 0
        while (at < text.length) {
            val value = text[at++]
            if (Character.isHighSurrogate(value)) { assertTrue(at < text.length); assertTrue(Character.isLowSurrogate(text[at++])) }
            else assertFalse(Character.isLowSurrogate(value))
        }
    }
    private class Workers : CoroutineDispatcher(), AutoCloseable {
        private val executor = Executors.newFixedThreadPool(2)
        private val sequence = AtomicInteger()
        val firstExited = CountDownLatch(1)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val ticket = sequence.incrementAndGet()
            executor.execute { try { block.run() } finally { if (ticket == 1) firstExited.countDown() } }
        }
        override fun close() { executor.shutdown(); assertTrue("Only owned workers must join", executor.awaitTermination(2, TimeUnit.SECONDS)) }
    }
}
