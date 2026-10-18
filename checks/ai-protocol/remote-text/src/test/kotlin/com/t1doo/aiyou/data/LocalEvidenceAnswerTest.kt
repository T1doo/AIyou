package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import kotlinx.serialization.json.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Real selection/controller/proof/SQL path; only a neutral in-memory provider, no network/files/keys. */
class LocalEvidenceAnswerTest {
    @Test fun selectedMultiSourceQuestionProducesBoundedAnswerAndEveryCitationResolvesExactOriginal(): Unit = Harness().use { h ->
        val selected = h.confirm(0, 2)
        val before = h.f.originalState()
        h.answers.ask(selected); val result = h.await(LocalEvidenceAnswerStage.READY); h.joinAnswer()
        assertEquals(1, h.calls.get()); assertEquals(2, result.paragraphs.size)
        val request = h.requests.single()
        assertEquals(result.requestId, request.id); assertEquals(selected.selection.id, request.packet.selectionId)
        assertEquals("needle", request.packet.question)
        assertEquals(listOf(h.ids[0], h.ids[2]), request.packet.citations.map { it.reference.source.sourceId })
        assertTrue(request.packet.textPoints <= 1712 && request.packet.textBytes <= 6848)
        for (paragraph in result.paragraphs) for (ref in paragraph.references) {
            val card = checkNotNull(h.answers.sourceFor(result, ref))
            var original: StoredEntry? = null
            assertTrue(h.owner.resolve(checkNotNull(selected.source?.proof), ref.source.sourceId) { original = it })
            val current = checkNotNull(original)
            assertEquals(card.hit.entry, current); assertEquals(ref.source.revision, current.currentRevision.toLong())
            assertEquals(ref.quote, current.originalText.substring(current.originalText.offsetByCodePoints(0, ref.start), current.originalText.offsetByCodePoints(0, ref.end)))
            assertTrue(paragraph.text.contains(ref.quote))
        }
        assertEquals(before, h.f.originalState()); assertFalse(result.toString().contains("needle"))
        try { (result.paragraphs as MutableList).clear(); fail("Immutable result") } catch (_: UnsupportedOperationException) { }
    }

    @Test fun absentFixtureEmptySelectionAndUnconfirmedCandidatesNeverInvokeProvider(): Unit = Harness().use { h ->
        val ready = h.ready(); h.answers.ask(ready); assertEquals(0, h.calls.get())
        h.evidence.toggle(ready, ready.cards[0]); h.answers.ask(h.evidence.state.value); assertEquals(0, h.calls.get())
        val confirmed = h.confirmCurrent()
        LocalEvidenceAnswerController(h.evidence, h::current, { true }).use { unavailable ->
            unavailable.ask(confirmed); assertFalse(unavailable.available)
            assertEquals(LocalEvidenceAnswerStage.UNAVAILABLE, unavailable.state.value.stage)
        }
        assertEquals(0, h.calls.get())
    }

    @Test fun requestKeepsEachCurrentRevisionDateExactQuoteAndIndividualPolicy(): Unit = Harness().use { h ->
        h.f.append(h.ids[1]); h.f.db.write("UPDATE entries SET privacyPolicy='REMOTE_ALLOWED' WHERE id=?", h.ids[0]); h.refresh()
        val view = h.confirm(0, 1); h.answers.ask(view); h.await(LocalEvidenceAnswerStage.READY)
        val citations = h.requests.single().packet.citations
        assertEquals(listOf(1L, 2L), citations.map { it.reference.source.revision })
        assertEquals(listOf(ProcessingPolicy.REMOTE_ALLOWED, ProcessingPolicy.LOCAL_ONLY), citations.map { it.policy })
        citations.forEach { assertEquals(5L, it.capturedAtMillis); assertEquals("UTC", it.captureTimeZone) }
        assertNull(h.answers.sourceFor(h.answers.state.value, view.cards[2].reference))
    }

    @Test fun unknownUnselectedOldRevisionAlteredQuoteAndShiftedRangeRejectWholeAnswer(): Unit = Harness().use { h ->
        val selected = h.confirm(0, 1); val base = selected.cards[0].reference
        val invalid = listOf(base.copy(source = SourceVersion("unknown", 1)), selected.cards[2].reference,
            base.copy(source = base.source.copy(revision = 2)), base.copy(quote = "forged"), base.copy(start = base.start + 1))
        for (ref in invalid) {
            h.handler = { r -> h.response(r, listOf(LocalEvidenceAnswerParagraph("合成响应", listOf(base, ref)))) }
            h.answers.ask(selected); val failed = h.await(LocalEvidenceAnswerStage.FAILED); h.joinAnswer()
            assertTrue(failed.paragraphs.isEmpty()); assertNull(h.answers.sourceFor(failed, base))
        }
        assertEquals(invalid.size, h.calls.get())
    }

    @Test fun mismatchedOrReplayedRequestAndSelectionIdentityCannotPublish(): Unit = Harness().use { h ->
        val view = h.confirm(0); h.answers.ask(view); h.await(LocalEvidenceAnswerStage.READY); h.joinAnswer()
        val first = h.requests.first()
        for (bad in listOf("request", "selection", "replay")) {
            h.handler = { r ->
                val p = h.good(r).paragraphs
                LocalEvidenceAnswerResponse(if (bad == "selection") r.id else if (bad == "replay") first.id else "other",
                    if (bad == "selection") "other" else r.packet.selectionId, p)
            }
            h.answers.ask(view); assertTrue(h.await(LocalEvidenceAnswerStage.FAILED).paragraphs.isEmpty()); h.joinAnswer()
        }
        assertEquals(4, h.requests.map { it.id }.toSet().size)
    }

    @Test fun missingDuplicateUncitedMalformedAndOverBudgetOutputIsRejectedWithoutPartialAnswer(): Unit = Harness().use { h ->
        val view = h.confirm(0, 1)
        for (bad in listOf("empty", "uncited", "duplicate", "unicode", "blank", "points", "combined", "false-empty")) {
            h.handler = { r ->
                val ref = r.packet.citations[0].reference
                val paragraphs = when (bad) {
                    "empty" -> emptyList()
                    "uncited" -> listOf(LocalEvidenceAnswerParagraph("合成", emptyList()))
                    "duplicate" -> listOf(LocalEvidenceAnswerParagraph("合成", listOf(ref, ref)))
                    "unicode" -> listOf(LocalEvidenceAnswerParagraph("\uD800", listOf(ref)))
                    "blank" -> listOf(LocalEvidenceAnswerParagraph("  ", listOf(ref)))
                    "points" -> listOf(LocalEvidenceAnswerParagraph("a".repeat(1201), listOf(ref)))
                    "combined" -> List(2) { LocalEvidenceAnswerParagraph("🙂".repeat(601), listOf(ref)) }
                    else -> h.good(r).paragraphs
                }
                LocalEvidenceAnswerResponse(r.id, r.packet.selectionId, paragraphs, noEvidence = bad == "false-empty")
            }
            h.answers.ask(view); assertTrue(h.await(LocalEvidenceAnswerStage.FAILED).paragraphs.isEmpty()); h.joinAnswer()
        }
    }

    @Test fun exactAnswerCodePointAndUtf8BoundaryPassesWithoutTruncation(): Unit = Harness().use { h ->
        val view = h.confirm(0)
        h.handler = { r -> h.response(r, listOf(LocalEvidenceAnswerParagraph("🙂".repeat(1200), listOf(r.packet.citations[0].reference)))) }
        h.answers.ask(view); val paragraph = h.await(LocalEvidenceAnswerStage.READY).paragraphs.single()
        assertEquals(1200, paragraph.text.codePointCount(0, paragraph.text.length)); assertEquals(4800, paragraph.text.toByteArray().size)
    }

    @Test fun explicitNoEvidenceDoesNotInventTextCitationsOrPermanentFacts(): Unit = Harness().use { h ->
        val view = h.confirm(0, 1); val before = h.f.originalState()
        h.handler = { r -> LocalEvidenceAnswerResponse(r.id, r.packet.selectionId, noEvidence = true) }
        h.answers.ask(view); val result = h.await(LocalEvidenceAnswerStage.NO_EVIDENCE); h.joinAnswer()
        assertTrue(result.paragraphs.isEmpty()); assertEquals(before, h.f.originalState())
        assertNull(h.answers.sourceFor(result, view.cards[0].reference))
    }

    @Test fun providerFailureIsBoundedAndRedactedWithoutAutomaticRetry(): Unit = Harness().use { h ->
        val view = h.confirm(0)
        h.handler = { error("Synthetic diagnostic needle must never reach UI") }
        h.answers.ask(view); val result = h.await(LocalEvidenceAnswerStage.FAILED); h.joinAnswer()
        assertEquals(1, h.calls.get()); assertTrue(result.paragraphs.isEmpty()); assertFalse(result.toString().contains("needle"))
    }

    @Test fun cooperativeCancellationSignalsRequestAndStopsWithoutClaimingCompletedAnswer(): Unit = Harness().use { h ->
        val started = CompletableDeferred<Unit>()
        h.handler = { started.complete(Unit); awaitCancellation() }
        val view = h.confirm(0); h.answers.ask(view)
        runBlocking { withTimeout(5_000) { started.await() } }; val job = h.worker()
        h.answers.cancel(); runBlocking { withTimeout(5_000) { job.join() } }
        assertTrue(h.requests.single().cancelled); assertEquals(LocalEvidenceAnswerStage.CANCELLED, h.answers.visible().stage)
        assertTrue(h.answers.visible().paragraphs.isEmpty())
    }

    @Test fun lateNonCooperativeProviderCannotOverwriteRetryAfterUserStops(): Unit = Harness().use { h ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.handler = { r -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); h.good(r) }
        val view = h.confirm(0); h.answers.ask(view); assertTrue(entered.await(5, TimeUnit.SECONDS)); val old = h.worker()
        try {
            h.answers.cancel(); h.handler = h::good; h.answers.ask(view); val next = h.await(LocalEvidenceAnswerStage.READY); h.joinAnswer()
            release.countDown(); runBlocking { withTimeout(5_000) { old.join() } }
            assertSame(next, h.answers.state.value); assertTrue(h.requests.first().cancelled)
            assertNotEquals(h.requests.first().id, h.requests.last().id)
        } finally { release.countDown() }
    }

    @Test fun deletionRevisionAndWithdrawalAbaDuringProviderWorkInvalidateWholeAnswer(): Unit = runBlocking {
        for (kind in listOf("delete", "revision", "withdraw", "withdraw-restore")) Harness().use { h ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            h.handler = { r -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); h.good(r) }
            val view = h.confirm(0, 1, 2); h.answers.ask(view); assertTrue(entered.await(5, TimeUnit.SECONDS)); val job = h.worker()
            try { h.mutate(kind, 1) } finally { release.countDown() }
            assertTrue(h.await(LocalEvidenceAnswerStage.STALE).paragraphs.isEmpty()); withTimeout(5_000) { job.join() }
            assertTrue(h.requests.single().cancelled); assertNull(h.evidence.state.value.selection.confirmation)
        }
    }

    @Test fun inspectionCommitFailureBeforeProviderStartsEmitsNoRequestOrAnswer(): Unit = Harness().use { h ->
        val view = h.confirm(0, 1)
        h.f.db.beforeCommit = { assertEquals(0, h.calls.get()); error("Synthetic preparation COMMIT failure") }
        h.answers.ask(view); assertTrue(h.await(LocalEvidenceAnswerStage.STALE).paragraphs.isEmpty())
        assertEquals(0, h.calls.get()); assertTrue(h.requests.isEmpty()); assertNull(h.evidence.state.value.selection.confirmation)
    }

    @Test fun outputCommitFailureCannotDisplayAnswerEvenThoughLocalProviderAlreadyReadEvidence(): Unit = Harness().use { h ->
        val view = h.confirm(0, 1)
        h.handler = { r -> h.good(r).also {
            h.f.db.beforeCommit = {
                assertEquals(LocalEvidenceAnswerStage.RUNNING, h.answers.state.value.stage)
                error("Synthetic result COMMIT failure")
            }
        } }
        h.answers.ask(view); assertTrue(h.await(LocalEvidenceAnswerStage.STALE).paragraphs.isEmpty())
        assertEquals(1, h.calls.get()); assertNull(h.evidence.state.value.selection.confirmation)
        assertTrue(h.requests.single().cancelled)
    }

    @Test fun accountInputOrRouteChangeHidesRetainedAnswerAndPreventsOldSourceNavigation(): Unit = Harness().use { h ->
        for (kind in listOf("input", "account", "route")) {
            h.session.set(true); h.refresh(); val view = h.confirm(0, 1)
            h.answers.ask(view); val result = h.await(LocalEvidenceAnswerStage.READY); h.joinAnswer(); val ref = result.paragraphs[0].references[0]
            if (kind == "route") h.session.set(false) else {
                val old = h.source.get()
                h.source.set(OriginalSearchState(if (kind == "input") old.input.copy(query = "changed") else old.input,
                    old.stage, old.hits, entries = old.entries, proof = old.proof,
                    owner = if (kind == "account") object : LocalSearchRepository by h.owner {} else h.owner))
            }
            assertEquals(LocalEvidenceAnswerStage.STALE, h.answers.visible().stage)
            val calls = h.calls.get(); h.answers.ask(view); assertEquals(calls, h.calls.get())
            assertNull(h.answers.sourceFor(result, ref)); h.answers.sync(); assertTrue(h.requests.last().cancelled)
        }
    }

    @Test fun changedSelectionAndDelayedOriginalResolveCannotReuseDisplayedAnswerAuthority(): Unit = Harness().use { h ->
        val view = h.confirm(0, 1); h.answers.ask(view); val result = h.await(LocalEvidenceAnswerStage.READY); h.joinAnswer()
        val ref = result.paragraphs[0].references[0]; val card = checkNotNull(h.answers.sourceFor(result, ref))
        h.mutate("revision", 0)
        var locations = 0
        assertFalse(h.owner.resolve(checkNotNull(view.source?.proof), card.reference.source.sourceId) { locations++ }); assertEquals(0, locations)
        h.evidence.toggle(view, view.cards[1]); assertNull(h.answers.sourceFor(result, ref))
        assertEquals(LocalEvidenceAnswerStage.STALE, h.answers.visible().stage)
    }

    @Test fun duplicateStartDuringOneRunDoesNotCreateAnotherProviderRequest(): Unit = Harness().use { h ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.handler = { r -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); h.good(r) }
        val view = h.confirm(0); h.answers.ask(view); assertTrue(entered.await(5, TimeUnit.SECONDS))
        try { repeat(5) { h.answers.ask(view) }; assertEquals(1, h.calls.get()) } finally { release.countDown() }
        h.await(LocalEvidenceAnswerStage.READY); h.joinAnswer(); assertEquals(1, h.requests.size)
    }

    @Test fun cancelAtPreparationCommitBoundaryPreventsPhaseTwoProviderConsumption(): Unit = Harness().use { h ->
        val view = h.confirm(0); h.f.db.beforeCommit = { h.answers.cancel() }
        h.answers.ask(view); h.await(LocalEvidenceAnswerStage.CANCELLED)
        assertEquals(0, h.calls.get()); assertTrue(h.requests.isEmpty())
    }

    @Test fun productionExcerptTimelineRequiresConfirmationCopiesOnlySelectedQuotesAndKeepsOriginalState(): Unit = Harness(timeline = true).use { h ->
        assertTrue(h.answers.available); assertTrue(h.answers.visible().excerptTimeline)
        val ready = h.ready(); h.answers.ask(ready)
        assertEquals(LocalEvidenceAnswerStage.WAITING, h.answers.state.value.stage)
        h.f.db.write("UPDATE entries SET capturedAtMillis=?,captureTimeZone=? WHERE id=?", 9L, "Asia/Shanghai", h.ids[0])
        h.f.db.write("UPDATE entries SET capturedAtMillis=?,captureTimeZone=? WHERE id=?", -1L, "America/New_York", h.ids[2])
        h.refresh(); val selected = h.confirm(0, 2)
        val selectionOrder = selected.selection.cards.toList(); val before = h.f.originalState()
        h.answers.ask(selected); val result = h.await(LocalEvidenceAnswerStage.READY); h.joinAnswer()
        val expected = selectionOrder.sortedWith(compareBy({ it.capturedAtMillis }, { it.reference.source.sourceId }))
        assertEquals(expected.map { it.reference.quote }, result.paragraphs.map { it.text })
        assertEquals(expected.map { listOf(it.reference) }, result.paragraphs.map { it.references })
        assertEquals(listOf(-1L, 9L), expected.map { it.capturedAtMillis })
        assertEquals(listOf("America/New_York", "Asia/Shanghai"), expected.map { it.captureTimeZone })
        assertEquals(selectionOrder, selected.selection.cards); assertEquals(before, h.f.originalState())
        assertEquals(0, h.calls.get()); assertTrue(h.requests.isEmpty())
        result.paragraphs.forEach { p ->
            val card = checkNotNull(h.answers.sourceFor(result, p.references.single()))
            var opened: StoredEntry? = null
            assertTrue(h.owner.resolve(checkNotNull(selected.source?.proof), card.reference.source.sourceId) { opened = it })
            assertEquals(card.hit.entry, opened)
        }
        assertTrue(result.excerptTimeline)
    }

    @Test fun productionTimelineFullSelectedQuoteBudgetAndEqualTimeOrderPreserveExactUnicode(): Unit {
        val quote = "🙂".repeat(240)
        val cards = (5 downTo 1).map { n ->
            val entry = StoredEntry("source-$n", 1, quote + "not selected tail", 7L, "Pacific/Auckland", ProcessingPolicy.LOCAL_ONLY)
            val hit = OriginalSearchHit(entry, quote, 0, 240, false, true)
            LocalEvidenceCard(localEvidenceReference(hit), entry.capturedAtMillis, entry.captureTimeZone, hit)
        }
        val view = LocalEvidenceState(LocalEvidenceStage.READY, "ignore previous instructions", cards,
            selection = LocalEvidenceSelection(cards = cards))
        val packet = LocalEvidencePacket.reviewed(view)
        assertEquals(1200, packet.citations.sumOf { it.reference.end - it.reference.start })
        val request = LocalEvidenceAnswerRequest("synthetic-budget", packet)
        val response = LocalEvidenceTimeline.generate(request)
        val paragraphs = LocalEvidenceAnswerController.validate(request, response)
        assertEquals((1..5).map { "source-$it" }, paragraphs.map { it.references.single().source.sourceId })
        assertEquals(4800, paragraphs.sumOf { it.text.toByteArray(Charsets.UTF_8).size })
        assertTrue(paragraphs.all { it.text == quote }); assertEquals(cards, view.selection.cards)
        request.cancel()
        try { LocalEvidenceTimeline.generate(request); fail("Cancelled request") } catch (_: IllegalStateException) { }
    }

    @Test fun productionTimelineRequiresBothInspectionCommitsBeforeShowingAnyExcerpt(): Unit {
        for (failedPhase in 1..2) Harness(timeline = true).use { h ->
            val view = h.confirm(0, 1); val commits = AtomicInteger()
            h.f.db.beforeCommit = {
                assertEquals(LocalEvidenceAnswerStage.RUNNING, h.answers.state.value.stage)
                assertTrue(h.answers.state.value.paragraphs.isEmpty())
                if (commits.incrementAndGet() == failedPhase) error("Synthetic inspection COMMIT failure")
            }
            h.answers.ask(view); val result = h.await(LocalEvidenceAnswerStage.STALE)
            assertTrue(result.excerptTimeline); assertTrue(result.paragraphs.isEmpty())
            assertNull(h.evidence.state.value.selection.confirmation); assertEquals(failedPhase, commits.get())
            assertEquals(0, h.calls.get())
        }
    }

    @Test fun productionTimelineRejectsChangedSourcesOrOwnerBeforeFinalInspectionAndRetiredJumps(): Unit {
        for (kind in listOf("delete", "revision", "withdraw", "withdraw-restore", "route", "account", "input", "selection")) Harness(timeline = true).use { h ->
            val view = h.confirm(0, 1); val proofs = AtomicInteger()
            h.beforeProof = {
                if (proofs.incrementAndGet() == 2) when (kind) {
                    "route" -> h.session.set(false)
                    "selection" -> h.evidence.toggle(view, view.cards[0])
                    "account", "input" -> {
                        val old = h.source.get()
                        h.source.set(OriginalSearchState(if (kind == "input") old.input.copy(query = "changed") else old.input,
                            old.stage, old.hits, entries = old.entries, proof = old.proof,
                            owner = if (kind == "account") object : LocalSearchRepository by h.owner {} else h.owner))
                    }
                    else -> h.mutate(kind, 0)
                }
            }
            h.answers.ask(view); val result = h.await(LocalEvidenceAnswerStage.STALE)
            assertTrue(result.paragraphs.isEmpty()); assertTrue(result.excerptTimeline)
            assertNull(h.answers.sourceFor(result, view.cards[0].reference)); assertEquals(0, h.calls.get())
        }
    }

    @Test fun productionTimelineStopAtCommitBoundaryAndLockHideCompletedPrivateContent(): Unit = Harness(timeline = true).use { h ->
        var view = h.confirm(0)
        h.f.db.beforeCommit = { h.answers.cancel() }
        h.answers.ask(view); assertTrue(h.await(LocalEvidenceAnswerStage.CANCELLED).paragraphs.isEmpty())
        h.f.db.beforeCommit = null; h.refresh(); view = h.confirm(0)
        h.answers.ask(view); val ready = h.await(LocalEvidenceAnswerStage.READY); h.joinAnswer()
        val ref = ready.paragraphs.single().references.single()
        h.session.set(false)
        assertEquals(LocalEvidenceAnswerStage.STALE, h.answers.visible().stage)
        assertTrue(h.answers.visible().paragraphs.isEmpty()); assertNull(h.answers.sourceFor(ready, ref))
        h.answers.sync(); assertTrue(h.answers.visible().excerptTimeline)
    }

    @Test fun localProtocolQuestionRequiresExactOneShotScopeReviewAndBothProtocolsReachOriginal(): Unit {
        for (protocol in ConnectionProtocol.entries) {
            val fixture = QuestionProtocolFixture(protocol)
            Harness(question = fixture).use { h ->
                val view = h.confirm(0, 2); val before = h.f.originalState()
                h.answers.ask(view); assertEquals(0, fixture.opened.get())
                h.answers.review(view); val first = checkNotNull(h.answers.state.value.questionReview)
                h.answers.review(view); val second = checkNotNull(h.answers.state.value.questionReview)
                assertNotSame(first, second); assertEquals("needle", second.question); assertEquals(fixture.target, second.target)
                h.answers.ask(view, first); assertEquals(0, fixture.opened.get())
                h.answers.ask(view, second); h.answers.ask(view, second)
                val answer = h.await(LocalEvidenceAnswerStage.READY); h.joinAnswer()
                assertEquals("本机假服务根据所选片段回应🙂", answer.paragraphs.single().text)
                assertEquals(1, fixture.opened.get()); assertEquals(1, fixture.executions.get()); assertEquals(0, h.calls.get())
                val refs = view.selection.cards.map { it.reference }
                assertEquals(refs, answer.paragraphs.single().references)
                assertEquals(refs.map { it.quote }, checkNotNull(fixture.payload).getValue("sources").jsonArray.map {
                    it.jsonObject.getValue("quote").jsonPrimitive.content
                })
                assertTrue(refs.all { it.end - it.start <= 240 })
                assertFalse(checkNotNull(fixture.prepared).bodyJson.contains("中".repeat(300)))
                refs.forEach { ref ->
                    val card = checkNotNull(h.answers.sourceFor(answer, ref)); var opened: StoredEntry? = null
                    assertTrue(h.owner.resolve(checkNotNull(view.source?.proof), ref.source.sourceId) { opened = it })
                    assertEquals(card.hit.entry, opened)
                }
                h.answers.ask(view, second); assertEquals(1, fixture.opened.get())
                assertEquals(before, h.f.originalState())
            }
        }
    }

    @Test fun questionReviewTargetRouteAbaAndChangedAccountInputOrSelectionRejectOldConfirmation(): Unit {
        for (kind in listOf("target", "route", "account", "input", "selection")) {
            val fixture = QuestionProtocolFixture(); val target = AtomicReference(fixture.target)
            val service = object : SelectedQuestionService by fixture.service { override val target get() = target.get() }
            Harness(question = fixture, questionService = service).use { h ->
                val view = h.confirm(0); h.answers.review(view); val old = checkNotNull(h.answers.state.value.questionReview)
                when (kind) {
                    "target" -> target.set(fixture.target.copy(modelId = "other-model"))
                    "route" -> h.session.set(false)
                    "selection" -> h.evidence.toggle(view, view.cards[0])
                    else -> {
                        val search = h.source.get()
                        h.source.set(OriginalSearchState(if (kind == "input") search.input.copy(query = "changed") else search.input,
                            search.stage, search.hits, entries = search.entries, proof = search.proof,
                            owner = if (kind == "account") object : LocalSearchRepository by h.owner {} else h.owner))
                    }
                }
                h.answers.sync(); assertEquals(LocalEvidenceAnswerStage.STALE, h.answers.visible().stage)
                if (kind == "target" || kind == "route") {
                    target.set(fixture.target); h.session.set(true); h.answers.review(view)
                    assertNotSame(old, h.answers.state.value.questionReview)
                }
                h.answers.ask(view, old); assertEquals(0, fixture.opened.get())
            }
        }
    }

    @Test fun questionBothInspectionCommitsAndDeletedRevisedOrWithdrawnSourcesBlockPublication(): Unit {
        for (failedPhase in 1..2) {
            val fixture = QuestionProtocolFixture()
            Harness(question = fixture).use { h ->
                val view = h.confirm(0, 1); h.answers.review(view); val review = h.answers.state.value.questionReview
                val commits = AtomicInteger()
                h.f.db.beforeCommit = { if (commits.incrementAndGet() == failedPhase) error("Synthetic question COMMIT failure") }
                h.answers.ask(view, review); assertTrue(h.await(LocalEvidenceAnswerStage.STALE).paragraphs.isEmpty())
                assertEquals(failedPhase - 1, fixture.executions.get()); assertNull(h.evidence.state.value.selection.confirmation)
            }
        }
        for (kind in listOf("delete", "revision", "withdraw", "withdraw-restore")) {
            val fixture = QuestionProtocolFixture()
            Harness(question = fixture).use { h ->
                val view = h.confirm(0, 1); h.answers.review(view); val review = h.answers.state.value.questionReview
                val checks = AtomicInteger(); h.beforeProof = { if (checks.incrementAndGet() == 2) h.mutate(kind, 0) }
                h.answers.ask(view, review); assertTrue(h.await(LocalEvidenceAnswerStage.STALE).paragraphs.isEmpty())
                assertEquals(1, fixture.executions.get()); assertNull(h.answers.sourceFor(h.answers.state.value, view.cards[0].reference))
            }
        }
    }

    @Test fun questionCancelledWhileExchangeIsBeingCreatedCancelsReturnedOwnerOutsideControllerLocks(): Unit {
        val fixture = QuestionProtocolFixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Harness(question = fixture).use { h ->
            fixture.onOpen = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            fixture.onCancel = {
                for (controller in listOf(h.answers, h.evidence)) {
                    val lock = controller.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(controller)
                    assertFalse("No external cancellation under owner lock", Thread.holdsLock(lock))
                }
            }
            val view = h.confirm(0); h.answers.review(view); h.answers.ask(view, h.answers.state.value.questionReview)
            assertTrue(entered.await(5, TimeUnit.SECONDS)); val job = h.worker()
            try { h.answers.cancel() } finally { release.countDown() }
            runBlocking { withTimeout(5_000) { job.join() } }
            assertEquals(LocalEvidenceAnswerStage.CANCELLED, h.answers.visible().stage)
            assertEquals(0, fixture.executions.get()); assertEquals(1, fixture.cancels.get())
        }
    }

    @Test fun questionStopRejectsNonCooperativeLateReplyAndFreshReviewCanComplete(): Unit {
        val fixture = QuestionProtocolFixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Harness(question = fixture).use { h ->
            fixture.beforeExecute = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val view = h.confirm(0); h.answers.review(view); h.answers.ask(view, h.answers.state.value.questionReview)
            assertTrue(entered.await(5, TimeUnit.SECONDS)); val old = h.worker()
            try {
                h.answers.cancel(); assertEquals(1, fixture.cancels.get())
                fixture.beforeExecute = {}; h.answers.review(view); h.answers.ask(view, h.answers.state.value.questionReview)
                val next = h.await(LocalEvidenceAnswerStage.READY); h.joinAnswer()
                release.countDown(); runBlocking { withTimeout(5_000) { old.join() } }
                assertSame(next, h.answers.state.value); assertEquals(2, fixture.executions.get())
            } finally { release.countDown() }
        }
    }

    @Test fun questionForgedCitationNoEvidenceAndProviderFailureNeverWriteFactsOrAutoRetry(): Unit {
        val fixture = QuestionProtocolFixture()
        Harness(question = fixture).use { h ->
            val view = h.confirm(0); val before = h.f.originalState()
            for (kind in listOf("forged", "failure", "no-evidence")) {
                fixture.wire = { payload ->
                    if (kind == "failure") error("Synthetic private diagnostic")
                    val reply = QuestionProtocolFixture.reply(payload, noEvidence = kind == "no-evidence")
                    QuestionProtocolFixture.wire(fixture.target.protocol,
                        if (kind == "forged") reply.replace(view.cards[0].reference.source.sourceId, "unselected") else reply)
                }
                h.answers.review(view); h.answers.ask(view, h.answers.state.value.questionReview)
                val result = h.await(if (kind == "no-evidence") LocalEvidenceAnswerStage.NO_EVIDENCE else LocalEvidenceAnswerStage.FAILED)
                h.joinAnswer(); assertTrue(result.paragraphs.isEmpty())
            }
            assertEquals(3, fixture.executions.get()); assertEquals(before, h.f.originalState())
        }
    }

    private class Harness(timeline: Boolean = false, question: QuestionProtocolFixture? = null,
        questionService: SelectedQuestionService? = question?.service) : AutoCloseable {
        val f = RecordEventDateFixture()
        val ids = (1..3).map { "60000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
        val source = AtomicReference<OriginalSearchState>()
        val session = AtomicBoolean(true)
        val calls = AtomicInteger()
        val requests = CopyOnWriteArrayList<LocalEvidenceAnswerRequest>()
        @Volatile var beforeProof: () -> Unit = {}
        val owner = object : LocalSearchRepository by f.search {
            override fun candidates(request: LocalSearchRequest): LocalSearchSnapshot = error("Answer must not retrieve again")
            override fun publishCurrent(snapshot: LocalSearchSnapshot, publish: () -> Unit): Boolean {
                beforeProof(); return f.search.publishCurrent(snapshot, publish)
            }
        }
        val evidence = LocalEvidenceController(source::get)
        @Volatile var handler: suspend (LocalEvidenceAnswerRequest) -> LocalEvidenceAnswerResponse = { good(it) }
        val fixture = SyntheticEvidenceAnswerProvider { request ->
            val depth = PythonSqliteDatabase::class.java.getDeclaredField("transactionDepth").apply { isAccessible = true }.getInt(f.db)
            assertEquals("No provider invocation inside a SQL transaction", 0, depth)
            val ownerLock = LocalEvidenceController::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(evidence)
            assertFalse(Thread.holdsLock(ownerLock))
            calls.incrementAndGet(); requests += request; handler(request)
        }
        val answers = LocalEvidenceAnswerController(evidence, ::current, session::get,
            if (timeline || questionService != null) null else fixture, localSummary = timeline, questionService = questionService)
        init { ids.forEach { f.entry(it, "🙂".repeat(90) + "needle 合成记录\n" + "中".repeat(300)) }; refresh() }
        fun current() = evidence.visible(source.get())
        fun refresh() {
            f.drain(); val input = OriginalSearchInput("needle")
            val entries = f.search.candidates(LocalSearchRequest("")).entries
            val result = runBlocking { LocalOriginalSearchMatcher.indexed(entries, input, f.search) }
            check(result.stage == OriginalSearchStage.READY)
            source.set(OriginalSearchState(input, result.stage, result.hits, entries = entries,
                proof = result.proof, owner = owner, indexEpoch = f.search.epoch()))
        }
        fun ready(): LocalEvidenceState {
            evidence.sync(true, source.get())
            return runBlocking { withTimeout(5_000) { evidence.state.first { it.stage == LocalEvidenceStage.READY } } }
        }
        fun confirm(vararg selected: Int): LocalEvidenceState {
            ready(); selected.forEach { evidence.toggle(evidence.state.value, evidence.state.value.cards[it]) }; return confirmCurrent()
        }
        fun confirmCurrent(): LocalEvidenceState {
            evidence.confirm(evidence.state.value)
            return runBlocking { withTimeout(5_000) { evidence.state.first { it.selection.stage == LocalEvidenceSelectionStage.CONFIRMED } } }
        }
        fun await(stage: LocalEvidenceAnswerStage) = runBlocking { withTimeout(8_000) { answers.state.first { it.stage == stage } } }
        fun worker(): Job {
            val run = LocalEvidenceAnswerController::class.java.getDeclaredField("run").apply { isAccessible = true }.get(answers)
            return checkNotNull(run.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(run) as? Job)
        }
        fun joinAnswer() = runBlocking { withTimeout(5_000) { worker().join() } }
        fun response(r: LocalEvidenceAnswerRequest, p: List<LocalEvidenceAnswerParagraph>) = LocalEvidenceAnswerResponse(r.id, r.packet.selectionId, p)
        fun good(r: LocalEvidenceAnswerRequest) = response(r, r.packet.citations.map {
            LocalEvidenceAnswerParagraph("你在合成记录中提到：" + it.reference.quote, listOf(it.reference))
        })
        fun mutate(kind: String, index: Int) {
            val id = ids[index]
            when (kind) {
                "revision" -> f.append(id)
                "delete" -> f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?", id)
                else -> {
                    f.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?", id)
                    f.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?", id)
                    if (kind == "withdraw-restore") {
                        f.db.write("UPDATE entries SET privacyPolicy='LOCAL_ONLY' WHERE id=?", id)
                        f.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?", id)
                    }
                }
            }
        }
        override fun close() { f.db.beforeCommit = null; f.db.afterSql = null; answers.close(); evidence.close(); f.close() }
    }
}
