package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual evidence controller, existing matcher and production search SQL/private proof on host SQLite. */
class LocalEvidenceControllerTest {
    @Test fun boundedMultiRecordPacketKeepsOrderDatesRevisionsAndExactUnicodeQuotes(): Unit = Harness(8).use { h ->
        val value = h.ready()
        assertEquals(h.ids.take(5), value.cards.map { it.reference.source.sourceId })
        assertTrue(value.limited); assertEquals("needle", value.question)
        assertEquals(1200, value.cards.sumOf { it.reference.quote.codePointCount(0, it.reference.quote.length) })
        assertTrue(value.cards.sumOf { it.reference.quote.toByteArray().size } <= LocalEvidenceController.MAX_QUOTE_BYTES)
        value.cards.forEach { card ->
            assertEquals(1L, card.reference.source.revision)
            assertEquals(5L, card.capturedAtMillis); assertEquals("UTC", card.captureTimeZone)
            assertEquals(10, card.reference.start); assertEquals(250, card.reference.end)
            val text = card.hit.entry.originalText
            assertEquals(card.reference.quote, text.substring(text.offsetByCodePoints(0, card.reference.start), text.offsetByCodePoints(0, card.reference.end)))
            assertTrue(h.controller.canOpen(value, card))
        }
        try { (value.cards as MutableList).clear(); fail("Immutable candidate list required") } catch (_: UnsupportedOperationException) { }
        assertFalse(value.toString().contains("needle")); assertFalse(value.cards.first().toString().contains("needle"))
    }

    @Test fun aiNoneIsExcludedBeforeBudgetButRemainsInOrdinaryManualSearch(): Unit = Harness(7).use { h ->
        h.f.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?", h.ids[0])
        h.refresh(); val value = h.ready()
        assertTrue(h.source.get().hits.any { it.entry.id == h.ids[0] })
        assertEquals(h.ids.drop(1).take(5), value.cards.map { it.reference.source.sourceId })
        assertTrue(value.limited)
    }

    @Test fun noEvidenceAndOnlyIneligibleSourcesReturnExplicitEmptyWithoutInventedAnswer(): Unit = Harness().use { h ->
        h.refresh(OriginalSearchInput("missing")); val none = h.ready(LocalEvidenceStage.EMPTY)
        assertTrue(none.cards.isEmpty()); assertEquals("missing", none.question)
        h.f.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?", h.ids[0])
        h.refresh(); assertTrue(h.ready(LocalEvidenceStage.EMPTY).cards.isEmpty())
    }

    @Test fun pureImageHasNoInventedQuoteAndDuplicateSourcesCannotFillBudgetTwice() {
        val entry = StoredEntry("synthetic", 1, "", 5, "UTC", ProcessingPolicy.LOCAL_ONLY)
        val blank = OriginalSearchHit(entry, "", 0, 0, false, false)
        val state = OriginalSearchState(OriginalSearchInput("needle"), OriginalSearchStage.READY, listOf(blank))
        assertEquals(LocalEvidenceStage.EMPTY, localEvidenceCandidates(state, 1).stage)
        val text = entry.copy(originalText = "needle")
        val hit = OriginalSearchHit(text, "needle", 0, 6, false, false)
        val duplicated = OriginalSearchState(state.input, state.stage, listOf(hit, hit, hit))
        assertEquals(1, localEvidenceCandidates(duplicated, 2).cards.size)
    }

    @Test fun questionIsBoundedInUnicodePointsAndBytesWithoutLeakingInvalidInput() {
        fun state(q: String) = OriginalSearchState(OriginalSearchInput(q), OriginalSearchStage.READY)
        val boundary = localEvidenceCandidates(state("🙂".repeat(512)), 1)
        assertEquals(LocalEvidenceStage.EMPTY, boundary.stage)
        assertEquals(2048, boundary.question.toByteArray().size)
        for (invalid in listOf("", " \n", "🙂".repeat(513), "a".repeat(100_000), "\uD800")) {
            val value = localEvidenceCandidates(state(invalid), 2)
            assertEquals(LocalEvidenceStage.INVALID, value.stage); assertTrue(value.question.isEmpty()); assertTrue(value.cards.isEmpty())
        }
    }

    @Test fun malformedQuoteOrCodePointRangeFailsTheWholePacketInsteadOfShowingPartialEvidence(): Unit = Harness(2).use { h ->
        val source = h.source.get()
        val hit = source.hits.last()
        val invalid = OriginalSearchHit(hit.entry, "fabricated", hit.startCodePoint, hit.endCodePoint, true, true)
        h.source.set(OriginalSearchState(source.input, source.stage, listOf(source.hits.first(), invalid),
            entries = source.entries, proof = source.proof, owner = source.owner, indexEpoch = source.indexEpoch))
        h.controller.sync(true, h.source.get())
        assertTrue(h.await(LocalEvidenceStage.FAILED).cards.isEmpty())
    }

    @Test fun existingKeywordExactDateTagFavoriteAndImageFiltersAreReusedWithoutSecondRetrieval(): Unit = Harness(2).use { h ->
        for (id in h.ids) {
            h.f.tags.replace(ManualTagMutation(id, 0, listOf("合成标签"), UUID.randomUUID().toString()))
            h.f.favorites.set(h.f.favorite(id, true))
            h.f.dates.set(h.f.intent(id, RecordEventDate("2000-01-01", "UTC")))
        }
        h.f.db.write("INSERT INTO attachments VALUES(?,?,1,'image/png',?,9,'READY')", UUID.randomUUID().toString(), h.ids[0], "a".repeat(64))
        val input = OriginalSearchInput("needle记忆", mode = OriginalSearchMode.EXACT, from = "2000-01-01", through = "2000-01-01",
            tag = "合成标签", tagSnapshot = h.f.tags.snapshots(), onlyFavorites = true, favoriteSnapshot = h.f.favorites.snapshots(),
            dateBasis = RecordDateBasis.EVENT, eventDateSnapshot = h.f.dates.snapshots(), onlyImages = true)
        h.refresh(input)
        // The image proof may internally recheck its candidate scope. The new controller must
        // not launch an additional retrieval through its borrowed repository capability.
        val candidates = AtomicInteger()
        val source = h.source.get()
        val owner = object : LocalSearchRepository by h.f.search {
            override fun candidates(request: LocalSearchRequest): LocalSearchSnapshot {
                candidates.incrementAndGet(); error("New retrieval is outside the evidence view")
            }
        }
        h.source.set(OriginalSearchState(source.input, source.stage, source.hits, entries = source.entries,
            proof = source.proof, owner = owner, indexEpoch = source.indexEpoch))
        val value = h.ready(); assertEquals(listOf(h.ids[0]), value.cards.map { it.reference.source.sourceId })
        assertEquals(0, candidates.get())
    }

    @Test fun inputAndAccountChangeBeforeRecompositionHideCardsAndRetainedOpen(): Unit = Harness().use { h ->
        val old = h.ready(); val card = old.cards.single()
        h.source.set(OriginalSearchState(OriginalSearchInput("changed"), OriginalSearchStage.WAITING))
        assertTrue(h.controller.visible(h.source.get()).cards.isEmpty()); assertFalse(h.controller.canOpen(old, card))
        h.refresh(); val next = h.ready(); assertFalse(h.controller.canOpen(old, card)); assertTrue(h.controller.canOpen(next, next.cards.single()))
        val current = h.source.get()
        h.source.set(OriginalSearchState(current.input, current.stage, current.hits, entries = current.entries,
            proof = current.proof, owner = object : LocalSearchRepository by h.f.search {}, indexEpoch = current.indexEpoch))
        assertFalse(h.controller.canOpen(next, next.cards.single()))
    }

    @Test fun deletionRevisionAndPolicyWithdrawalBeforePublicationInvalidateWholePacket(): Unit = runBlocking {
        for (kind in listOf("delete", "revision", "withdraw", "withdraw-restore")) Harness().use { h ->
            val computed = CountDownLatch(1); val release = CountDownLatch(1)
            h.afterPrepare = { computed.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            h.controller.sync(true, h.source.get()); assertTrue(computed.await(5, TimeUnit.SECONDS))
            try {
                when (kind) {
                    "delete" -> h.f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?", h.ids[0])
                    "revision" -> h.f.append(h.ids[0])
                    else -> {
                        h.f.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?", h.ids[0])
                        h.f.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?", h.ids[0])
                        if (kind == "withdraw-restore") {
                            h.f.db.write("UPDATE entries SET privacyPolicy='LOCAL_ONLY' WHERE id=?", h.ids[0])
                            h.f.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?", h.ids[0])
                        }
                    }
                }
            } finally { release.countDown() }
            assertTrue(h.await(LocalEvidenceStage.STALE).cards.isEmpty()); joinWorker(h.controller)
            assertEquals(1, h.invalidations.get())
        }
    }

    @Test fun emptyProofCannotPublishFalseNoEvidenceAfterNewMatchingRecord(): Unit = Harness().use { h ->
        h.refresh(OriginalSearchInput("newword"))
        val computed = CountDownLatch(1); val release = CountDownLatch(1)
        h.afterPrepare = { computed.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        h.controller.sync(true, h.source.get()); assertTrue(computed.await(5, TimeUnit.SECONDS))
        h.f.entry(text = "newword"); release.countDown()
        assertTrue(h.await(LocalEvidenceStage.STALE).cards.isEmpty())
    }

    @Test fun clearPauseAndSameQuestionReentryCannotAcceptLateNonCancellablePreparation(): Unit = Harness().use { h ->
        val computed = CountDownLatch(1); val release = CountDownLatch(1)
        h.afterPrepare = { computed.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        h.controller.sync(true, h.source.get()); assertTrue(computed.await(5, TimeUnit.SECONDS))
        val oldRun = worker(h.controller)
        h.controller.clear(); h.afterPrepare = null
        h.controller.sync(true, h.source.get()); val next = h.await(LocalEvidenceStage.READY)
        release.countDown()
        runBlocking { withTimeout(5_000) { oldRun.join() } }
        assertEquals(2, h.preparesEnded.get())
        assertSame(next, h.controller.state.value)
        h.controller.sync(false, h.source.get()); assertFalse(h.controller.canOpen(next, next.cards.single()))
    }

    @Test fun absentProofAndReadFailureAreExplicitFailuresAndClosingCannotResurrect(): Unit = Harness().use { h ->
        val source = h.source.get()
        h.source.set(OriginalSearchState(source.input, source.stage, source.hits, entries = source.entries))
        assertTrue(h.ready(LocalEvidenceStage.FAILED).cards.isEmpty())
        h.refresh(); h.f.db.beforeSql = { if (it.startsWith("SELECT slot,epoch,")) error("Synthetic proof failure") }
        assertTrue(h.ready(LocalEvidenceStage.STALE).cards.isEmpty())
        h.f.db.beforeSql = null; h.controller.close(); h.controller.sync(true, h.source.get())
        assertTrue(h.controller.state.value.cards.isEmpty())
    }

    @Test fun foreignHitNotBoundByExistingProofCannotBecomeACitation(): Unit = Harness().use { h ->
        val source = h.source.get(); val foreign = source.hits.single().copyEntry("foreign")
        h.source.set(OriginalSearchState(source.input, source.stage, listOf(foreign), entries = source.entries,
            proof = source.proof, owner = source.owner, indexEpoch = source.indexEpoch))
        assertTrue(h.ready(LocalEvidenceStage.FAILED).cards.isEmpty())
    }

    @Test fun failedLookupAndBlankBrowseAreNotMisreportedAsEmptyEvidence(): Unit = Harness().use { h ->
        val failed = OriginalSearchState(OriginalSearchInput("needle"), OriginalSearchStage.FAILED)
        h.source.set(failed); h.controller.sync(true, failed)
        assertEquals(LocalEvidenceStage.FAILED, h.controller.visible(failed).stage)
        val browse = OriginalSearchState(OriginalSearchInput(), OriginalSearchStage.BROWSE)
        h.source.set(browse); h.controller.sync(true, browse)
        assertEquals(LocalEvidenceStage.INVALID, h.controller.visible(browse).stage)
    }

    private fun OriginalSearchHit.copyEntry(id: String) = OriginalSearchHit(entry.copy(id = id), quote,
        startCodePoint, endCodePoint, leading, trailing, matchStartCodePoint, matchEndCodePoint)

    /** Join the actual worker before disposal can mask a late publication. No production test hook. */
    private fun worker(controller: LocalEvidenceController) = LocalEvidenceController::class.java.getDeclaredField("job")
        .apply { isAccessible = true }.get(controller) as Job
    private fun joinWorker(controller: LocalEvidenceController) = runBlocking { withTimeout(5_000) { worker(controller).join() } }

    private class Harness(count: Int = 1) : AutoCloseable {
        val f = RecordEventDateFixture()
        val ids = (1..count).map { "40000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
        val source = AtomicReference<OriginalSearchState>()
        val invalidations = AtomicInteger()
        val preparesEnded = AtomicInteger()
        @Volatile var afterPrepare: (() -> Unit)? = null
        val controller = LocalEvidenceController(source::get, { invalidations.incrementAndGet() }, prepare = { state, generation ->
            try { localEvidenceCandidates(state, generation).also { afterPrepare?.invoke() } }
            finally { preparesEnded.incrementAndGet() }
        })
        init { ids.forEach { f.entry(it, "🙂".repeat(90) + "needle记忆\n" + "中".repeat(300)) }; refresh() }
        fun refresh(input: OriginalSearchInput = OriginalSearchInput("needle")) {
            f.drain()
            val entries = f.search.candidates(LocalSearchRequest("")).entries
            val result = runBlocking { LocalOriginalSearchMatcher.indexed(entries, input, f.search) }
            check(result.stage == OriginalSearchStage.READY)
            source.set(OriginalSearchState(input, result.stage, result.hits, entries = entries,
                proof = result.proof, owner = f.search, indexEpoch = f.search.epoch()))
        }
        fun ready(stage: LocalEvidenceStage = LocalEvidenceStage.READY): LocalEvidenceState {
            controller.sync(true, source.get()); return await(stage)
        }
        fun await(stage: LocalEvidenceStage) = runBlocking { withTimeout(5_000) { controller.state.first { it.stage == stage } } }
        override fun close() { afterPrepare = null; f.db.beforeSql = null; controller.close(); f.close() }
    }
}
