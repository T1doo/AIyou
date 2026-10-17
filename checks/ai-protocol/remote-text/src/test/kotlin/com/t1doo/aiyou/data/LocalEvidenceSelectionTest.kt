package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Product controller and SQL proof. The provider is an in-memory recorder, with no network or key resolver. */
class LocalEvidenceSelectionTest {
    @Test fun candidatesAreUnselectedAndCannotPublishBeforeExplicitNonemptyConfirmation(): Unit = Harness().use { h ->
        val ready = h.ready()
        assertTrue(ready.selection.cards.isEmpty()); assertNull(ready.selection.confirmation)
        h.controller.confirm(ready); assertSame(ready, h.value)
        assertFalse(h.provider.inspect(h, ready)); assertEquals(0, h.provider.calls.get())
        val selected = h.select(1)
        assertEquals(LocalEvidenceSelectionStage.CHOOSING, selected.selection.stage)
        assertFalse(h.provider.inspect(h, selected))
        h.controller.toggle(selected, selected.cards[1]); assertTrue(h.value.selection.cards.isEmpty())
        h.controller.confirm(h.value); assertFalse(h.provider.inspect(h, h.value))
    }

    @Test fun explicitSubsetUsesCandidateOrderAndImmutableIdentityWithCombinedBudget(): Unit = Harness(7).use { h ->
        var value = h.ready(); assertEquals(5, value.cards.size); assertTrue(value.limited)
        val identity = value.selection.id
        value = h.select(4, 0, 2)
        assertEquals(listOf(h.ids[0], h.ids[2], h.ids[4]), value.selection.cards.map { it.reference.source.sourceId })
        assertNotEquals(identity, value.selection.id)
        assertEquals(720, value.selection.quotePoints)
        try { (value.selection.cards as MutableList).clear(); fail("Immutable selection required") } catch (_: UnsupportedOperationException) { }
        val confirmed = h.confirmed()
        assertTrue(h.provider.inspect(h, confirmed))
        val packet = checkNotNull(h.provider.packet.get())
        assertEquals(value.selection.id, packet.selectionId)
        assertEquals(726, packet.textPoints); assertTrue(packet.textBytes <= LocalEvidencePacket.MAX_TEXT_BYTES)
        assertEquals(listOf(h.ids[0], h.ids[2], h.ids[4]), packet.citations.map { it.reference.source.sourceId })
        try { (packet.citations as MutableList).clear(); fail("Immutable citations required") } catch (_: UnsupportedOperationException) { }
        assertFalse(packet.toString().contains("needle")); assertFalse(confirmed.selection.toString().contains("needle"))
        assertFalse(confirmed.selection.confirmation.toString().contains(value.selection.id))
    }

    @Test fun syntheticProviderReceivesOnlyConfirmedExactQuotesDatesRevisionsAndLocalPolicy(): Unit = Harness().use { h ->
        h.ready(); h.select(0, 1, 2); val confirmed = h.confirmed()
        assertTrue(h.provider.inspect(h, confirmed)); assertEquals(1, h.provider.calls.get())
        val packet = checkNotNull(h.provider.packet.get()); assertEquals("needle", packet.question)
        assertEquals(726, packet.textPoints)
        for (citation in packet.citations) {
            val card = confirmed.cards.single { it.reference.source == citation.reference.source }
            assertEquals(1L, citation.reference.source.revision)
            assertEquals(10, citation.reference.start); assertEquals(250, citation.reference.end)
            assertEquals(card.reference, citation.reference)
            assertEquals(5L, citation.capturedAtMillis); assertEquals("UTC", citation.captureTimeZone)
            assertEquals(ProcessingPolicy.LOCAL_ONLY, citation.policy)
            assertEquals(citation.reference.quote, card.hit.entry.originalText.substring(
                card.hit.entry.originalText.offsetByCodePoints(0, citation.reference.start),
                card.hit.entry.originalText.offsetByCodePoints(0, citation.reference.end)))
            assertFalse(citation.toString().contains("needle"))
        }
    }

    @Test fun changedSelectionAndSameMembershipAbaRequireNewConfirmationAndRejectForgedReceipt(): Unit = Harness().use { h ->
        h.ready(); h.select(0, 1); val old = h.confirmed(); val receipt = old.selection.confirmation
        assertFalse(h.provider.inspect(h, old, LocalEvidenceConfirmation(old.selection.id)))
        h.controller.toggle(old, old.cards[1]); val excluded = h.value
        assertNull(excluded.selection.confirmation); assertFalse(h.provider.inspect(h, old, receipt))
        h.controller.toggle(excluded, excluded.cards[1]); val restored = h.value
        assertEquals(old.selection.cards, restored.selection.cards); assertNotEquals(old.selection.id, restored.selection.id)
        assertFalse(h.provider.inspect(h, restored, receipt)); assertFalse(h.provider.inspect(h, restored))
        val next = h.confirmed(); assertTrue(h.provider.inspect(h, next))
        assertFalse(h.provider.inspect(h, old, receipt)); assertFalse(h.provider.inspect(h, next, receipt))
        assertSame(next, h.value)
    }

    @Test fun questionFilterAndAccountChangesRejectRetainedClicksAndConfirmationBeforeSync(): Unit = Harness().use { h ->
        h.ready(); h.select(0, 1); var old = h.confirmed()
        for (kind in listOf("question", "filter", "account")) {
            val source = h.source.get()
            val input = when (kind) {
                "question" -> source.input.copy(query = "needle changed")
                "filter" -> source.input.copy(onlyFavorites = true)
                else -> source.input
            }
            h.source.set(OriginalSearchState(input, source.stage, source.hits, entries = source.entries,
                proof = source.proof, owner = if (kind == "account") object : LocalSearchRepository by h.owner {} else h.owner))
            assertEquals(LocalEvidenceStage.STALE, h.controller.visible(h.source.get()).stage)
            assertFalse(h.provider.inspect(h, old))
            h.controller.toggle(old, old.cards[0]); h.controller.confirm(old); assertSame(old, h.value)
            h.refresh(); val ready = h.ready(); assertTrue(ready.selection.cards.isEmpty())
            h.select(0); old = h.confirmed()
        }
        assertEquals(0, h.provider.calls.get())
    }

    @Test fun anySourceChangedBeforeConfirmClearsWholeSelectionInsteadOfConfirmingRemainingOnes(): Unit = runBlocking {
        for (kind in listOf("delete", "revision", "withdraw", "withdraw-restore")) Harness().use { h ->
            h.ready(); h.select(0, 1, 2)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            h.beforeProof = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            h.controller.confirm(h.value); assertTrue(entered.await(5, TimeUnit.SECONDS))
            try { h.mutate(kind, 1) } finally { release.countDown() }
            h.awaitStale(); h.joinReview()
            assertTrue(h.value.selection.cards.isEmpty()); assertNull(h.value.selection.confirmation)
            assertEquals(LocalEvidenceSelectionStage.EXPIRED, h.value.selection.stage)
            assertEquals(1, h.invalidations.get()); assertEquals(0, h.provider.calls.get())
        }
    }

    @Test fun everyConfirmedSourceIsRecheckedAtInspectionAfterDeletionRevisionOrWithdrawal(): Unit = Harness().use { h ->
        for (index in 0..2) for (kind in listOf("delete", "revision", "withdraw", "withdraw-restore")) {
            h.refresh(); h.ready(); h.select(0, 1, 2); val confirmed = h.confirmed()
            h.mutate(kind, index)
            assertFalse(h.provider.inspect(h, confirmed)); assertEquals(0, h.provider.calls.get())
            assertEquals(LocalEvidenceStage.STALE, h.value.stage); assertTrue(h.value.selection.cards.isEmpty())
            h.restore(index)
        }
    }

    @Test fun mutationAfterInspectionStartsStillBlocksSyntheticProviderAtTheSqlBoundary(): Unit = Harness().use { h ->
        h.ready(); h.select(0, 2); val confirmed = h.confirmed()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.beforeProof = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val result = CompletableDeferred<Boolean>()
        val work = CoroutineScope(Dispatchers.IO).launch { result.complete(h.provider.inspect(h, confirmed)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try { h.mutate("withdraw-restore", 2) } finally { release.countDown() }
        runBlocking { withTimeout(5_000) { work.join(); assertFalse(result.await()) } }
        assertEquals(0, h.provider.calls.get()); assertEquals(LocalEvidenceStage.STALE, h.value.stage)
    }

    @Test fun retainedInspectionCannotPublishAfterSelectionChangesWhileItsProofCheckIsPending(): Unit = Harness().use { h ->
        h.ready(); h.select(0, 1); val old = h.confirmed()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.beforeProof = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val result = CompletableDeferred<Boolean>()
        val work = CoroutineScope(Dispatchers.IO).launch { result.complete(h.provider.inspect(h, old)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            h.beforeProof = null; h.controller.toggle(old, old.cards[1]); val next = h.confirmed()
            release.countDown(); runBlocking { withTimeout(5_000) { work.join(); assertFalse(result.await()) } }
            assertEquals(0, h.provider.calls.get()); assertSame(next, h.value)
            assertTrue(h.provider.inspect(h, next)); assertEquals(1, h.provider.calls.get())
        } finally { release.countDown() }
    }

    @Test fun clearAndReentryCannotPublishLateConfirmationEvenWithIdenticalQuestionAndSources(): Unit = Harness().use { h ->
        h.ready(); h.select(0); val first = h.value
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.beforeProof = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        h.controller.confirm(first); assertTrue(entered.await(5, TimeUnit.SECONDS)); val oldWorker = h.reviewWorker()
        try {
            h.controller.clear(); h.beforeProof = null; h.ready(); h.select(0); val next = h.confirmed()
            assertNotEquals(first.selection.id, next.selection.id)
            release.countDown(); runBlocking { withTimeout(5_000) { oldWorker.join() } }
            assertSame(next, h.value); assertTrue(h.provider.inspect(h, next))
            assertFalse(h.provider.inspect(h, first))
        } finally { release.countDown() }
    }

    @Test fun disableAndCloseDiscardConfirmedIdentityAndPreventRetainedPublication(): Unit = Harness().use { h ->
        h.ready(); h.select(0); val first = h.confirmed()
        h.controller.sync(false, h.source.get()); assertFalse(h.provider.inspect(h, first))
        h.ready(); h.select(0); val second = h.confirmed(); assertNotEquals(first.selection.id, second.selection.id)
        h.controller.close(); assertFalse(h.provider.inspect(h, second)); h.controller.confirm(second)
        assertTrue(h.value.selection.cards.isEmpty()); assertEquals(0, h.provider.calls.get())
    }

    @Test fun oldConfirmationCannotCrossControllersEvenOnTheSameProofAndOwner(): Unit = Harness().use { h ->
        h.ready(); h.select(0); val first = h.confirmed()
        LocalEvidenceController(h.source::get).use { other ->
            other.sync(true, h.source.get())
            val ready = runBlocking { withTimeout(5_000) { other.state.first { it.stage == LocalEvidenceStage.READY } } }
            other.toggle(ready, ready.cards[0]); other.confirm(other.state.value)
            val second = runBlocking { withTimeout(5_000) { other.state.first { it.selection.stage == LocalEvidenceSelectionStage.CONFIRMED } } }
            assertFalse(other.inspectConfirmed(second, first.selection.confirmation) { fail("Foreign authority") })
            assertFalse(h.controller.inspectConfirmed(first, second.selection.confirmation) { fail("Foreign authority") })
        }
    }

    @Test fun readAndCommitFailureInvalidateConfirmationWithoutLeavingAFalseConfirmedState(): Unit = Harness().use { h ->
        h.ready(); h.select(0)
        h.f.db.beforeCommit = { error("Synthetic commit rejection") }
        h.controller.confirm(h.value); h.awaitStale(); h.joinReview()
        assertNull(h.value.selection.confirmation); assertTrue(h.value.selection.cards.isEmpty())
        h.f.db.beforeCommit = null; h.refresh(); h.ready(); h.select(0); val confirmed = h.confirmed()
        h.f.db.beforeSql = { if (it.startsWith("SELECT slot,epoch,")) error("Synthetic read failure") }
        assertFalse(h.provider.inspect(h, confirmed)); assertEquals(0, h.provider.calls.get())
        assertEquals(LocalEvidenceStage.STALE, h.value.stage)
    }

    @Test fun foreignOrAlteredCardCannotBeSelectedOrReplaceAnExactCitation(): Unit = Harness().use { h ->
        val ready = h.ready(); val card = ready.cards[0]
        val altered = LocalEvidenceCard(card.reference.copy(quote = "forged"), card.capturedAtMillis, card.captureTimeZone, card.hit)
        h.controller.toggle(ready, altered); assertSame(ready, h.value)
        val forged = ready.selecting(LocalEvidenceSelection(cards = listOf(altered)))
        h.controller.confirm(forged); assertSame(ready, h.value)
        assertFalse(h.provider.inspect(h, forged, LocalEvidenceConfirmation(forged.selection.id)))
        val changed = OriginalSearchHit(card.hit.entry, "wrong", 0, 5, false, false)
        try { localEvidenceReference(changed); fail("Exact code-point quote required") } catch (_: IllegalArgumentException) { }
        assertEquals(0, h.provider.calls.get())
    }

    @Test fun singleSourceRemoteConsentCannotAuthorizeASecondSourceOrConvertLocalConfirmation(): Unit = Harness().use { h ->
        val connection = ProviderConnection("synthetic", 1, "https://synthetic.invalid/v1", ConnectionProtocol.CHAT_COMPLETIONS,
            "synthetic", SecretReference("synthetic-only"))
        val now = Instant.ofEpochSecond(100)
        val source = SourceVersion(h.ids[0], 1)
        val grant = ConsentGrant("synthetic", 1, source, connection, TextPurpose.CONVERSATION, now.minusSeconds(1), now.plusSeconds(60))
        h.f.db.write("UPDATE entries SET privacyPolicy='REMOTE_ALLOWED' WHERE id=?", h.ids[0]); h.refresh()
        h.ready(); h.select(0, 1); val local = h.confirmed(); assertTrue(h.provider.inspect(h, local))
        val packet = checkNotNull(h.provider.packet.get()); assertEquals(2, packet.citations.size)
        val firstAuthority = CurrentSendAuthority(RemoteSendState(source, ProcessingPolicy.REMOTE_ALLOWED, connection = connection, consent = grant))
        assertNull(firstAuthority.check(firstAuthority.queue(RemoteSendIntent(source, TextPurpose.CONVERSATION)), now))
        val second = SourceVersion(h.ids[1], 1)
        val secondAuthority = CurrentSendAuthority(RemoteSendState(second, ProcessingPolicy.REMOTE_ALLOWED, connection = connection, consent = grant))
        assertEquals(SendDenial.SCOPE_MISMATCH, secondAuthority.check(secondAuthority.queue(RemoteSendIntent(second, TextPurpose.CONVERSATION)), now))
        val localAuthority = CurrentSendAuthority(RemoteSendState(second))
        assertEquals(SendDenial.POLICY_BLOCKED, localAuthority.check(localAuthority.queue(RemoteSendIntent(second, TextPurpose.CONVERSATION)), now))
        assertEquals(listOf(ProcessingPolicy.REMOTE_ALLOWED, ProcessingPolicy.LOCAL_ONLY), packet.citations.map { it.policy })
        // There is deliberately no network-request conversion or connection/consent field on this local packet.
        assertFalse(LocalEvidencePacket::class.java.declaredFields.any { it.type == ConsentGrant::class.java || it.type == ProviderConnection::class.java })
    }

    @Test fun maximumSelectedTextHonorsQuestionPlusQuoteCodePointAndUtf8Budgets(): Unit = Harness(5, "🙂".repeat(512)).use { h ->
        h.ready(); h.select(0, 1, 2, 3, 4); val confirmed = h.confirmed()
        assertEquals(1200, confirmed.selection.quotePoints)
        assertTrue(h.provider.inspect(h, confirmed)); val packet = checkNotNull(h.provider.packet.get())
        assertEquals(1712, packet.textPoints); assertEquals(6848, packet.textBytes)
        assertEquals(LocalEvidencePacket.MAX_TEXT_POINTS, packet.textPoints)
        assertEquals(LocalEvidencePacket.MAX_TEXT_BYTES, packet.textBytes)
    }

    private class SyntheticProvider {
        val calls = AtomicInteger()
        val packet = AtomicReference<LocalEvidencePacket>()
        fun inspect(h: Harness, value: LocalEvidenceState, receipt: LocalEvidenceConfirmation? = value.selection.confirmation) =
            h.controller.inspectConfirmed(value, receipt) { packet.set(it); calls.incrementAndGet() }
    }

    private class Harness(count: Int = 3, private val query: String = "needle") : AutoCloseable {
        val f = RecordEventDateFixture()
        val ids = (1..count).map { "50000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
        val source = AtomicReference<OriginalSearchState>()
        val invalidations = AtomicInteger()
        val provider = SyntheticProvider()
        @Volatile var beforeProof: (() -> Unit)? = null
        val owner = object : LocalSearchRepository by f.search {
            override fun publishCurrent(snapshot: LocalSearchSnapshot, publish: () -> Unit): Boolean {
                beforeProof?.invoke(); return f.search.publishCurrent(snapshot, publish)
            }
            override fun candidates(request: LocalSearchRequest): LocalSearchSnapshot = error("Review must reuse existing proof")
        }
        val controller = LocalEvidenceController(source::get, { invalidations.incrementAndGet() })
        val value get() = controller.state.value
        init {
            ids.forEach { f.entry(it, if (query == "needle") "🙂".repeat(90) + "needle记忆\\n" + "中".repeat(300) else "🙂".repeat(600)) }
            refresh()
        }
        fun refresh() {
            f.drain(); val input = OriginalSearchInput(query)
            val entries = f.search.candidates(LocalSearchRequest("")).entries
            val result = runBlocking { LocalOriginalSearchMatcher.indexed(entries, input, f.search) }
            check(result.stage == OriginalSearchStage.READY)
            source.set(OriginalSearchState(input, result.stage, result.hits, entries = entries, proof = result.proof,
                owner = owner, indexEpoch = f.search.epoch()))
        }
        fun ready(): LocalEvidenceState {
            controller.sync(true, source.get())
            return runBlocking { withTimeout(5_000) { controller.state.first { it.stage == LocalEvidenceStage.READY } } }
        }
        fun select(vararg indices: Int): LocalEvidenceState {
            indices.forEach { controller.toggle(value, value.cards[it]) }; return value
        }
        fun confirmed(): LocalEvidenceState {
            controller.confirm(value)
            val result = runBlocking { withTimeout(5_000) { controller.state.first {
                it.selection.stage == LocalEvidenceSelectionStage.CONFIRMED || it.stage == LocalEvidenceStage.STALE } } }
            assertEquals(LocalEvidenceStage.READY, result.stage)
            assertEquals(LocalEvidenceSelectionStage.CONFIRMED, result.selection.stage)
            joinReview(); return result
        }
        fun awaitStale() = runBlocking { withTimeout(5_000) { controller.state.first { it.stage == LocalEvidenceStage.STALE } } }
        fun reviewWorker() = LocalEvidenceController::class.java.getDeclaredField("reviewJob").apply { isAccessible = true }.get(controller) as Job
        fun joinReview() = runBlocking { withTimeout(5_000) { reviewWorker().join() } }
        fun mutate(kind: String, index: Int) {
            val id = ids[index]
            when (kind) {
                "revision" -> f.append(id)
                "delete" -> f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?", id)
                else -> {
                    f.db.write("UPDATE entries SET privacyPolicy='AI_NONE' WHERE id=?", id)
                    f.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?", id)
                    if (kind == "withdraw-restore") restore(index)
                }
            }
        }
        fun restore(index: Int) {
            f.db.write("UPDATE entries SET privacyPolicy='LOCAL_ONLY' WHERE id=?", ids[index])
            f.db.write("UPDATE remote_source_access SET deleted=0,generation=generation+1 WHERE entryId=?", ids[index])
        }
        override fun close() {
            beforeProof = null; f.db.beforeSql = null; f.db.beforeCommit = null; controller.close(); f.close()
        }
    }
}
