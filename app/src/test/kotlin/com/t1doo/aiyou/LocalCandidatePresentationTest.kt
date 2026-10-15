package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production routes/controller with a controlled receipt port; SQL and native proofs are separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
class LocalCandidatePresentationTest {
    @get:Rule val compose = createComposeRule()
    private val one = StoredEntry("one", 1, "  合成🙂原文：我不喜欢咖啡。“明天去旅行”只是引用，不是已经完成。",
        55, "UTC", ProcessingPolicy.LOCAL_ONLY)
    private val two = StoredEntry("two", 1, "另一条合成原文", 65, "UTC", ProcessingPolicy.LOCAL_ONLY)
    private val state = mutableStateOf(LocalRecordUiState(ready = true, opening = false,
        text = "synthetic-private-draft", entries = listOf(one, two), supportsCandidates = true))
    private val queue = ConcurrentLinkedQueue<suspend (EntryRepository) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val durable = mutableListOf<LocalCandidateBatch>()
    private val reviews = mutableListOf<ExcerptReviewRequest>()
    private val cancels = mutableListOf<CandidateCancelRequest>()
    private var failRead = false
    private var loseReviewReadback = false
    private var lastReview: ExcerptReviewRequest? = null
    private val port = object : LocalCandidateRepository {
        override fun batches(source: SourceVersion): List<LocalCandidateBatch> {
            if (failRead) throw LocalCandidateStorageFailure()
            return durable.filter { it.source == source }
        }
        override fun begin(request: LocalCandidateStart): LocalCandidateJob {
            val original = state.value.entries.single { it.id == request.source.sourceId }.originalText
            val hash = OriginalExports.hash(original)
            durable += LocalCandidateBatch(request.id, request.source, hash, 0, 0,
                LocalCandidateState.RUNNING, request.requestedAtMillis, emptyList())
            return LocalCandidateJob(request, hash, 0, original, 55, "UTC")
        }
        override fun complete(job: LocalCandidateJob, document: ExcerptDocument): LocalCandidateBatch {
            LocalExcerptRules.validate(document, job.start.source, job.sourceHash, job.original)
            val old = durable.single { it.id == job.start.id }
            check(old.state == LocalCandidateState.RUNNING && old.version == 0L)
            val ready = LocalCandidateBatch(old.id, old.source, old.sourceHash, old.generation, 1,
                LocalCandidateState.READY, old.createdAtMillis, document.proposals.mapIndexed { i, proposal ->
                    ReviewedExcerpt("excerpt-" + (i + 1), proposal, ExcerptReview.PROPOSED)
                })
            durable[durable.indexOf(old)] = ready
            return ready
        }
        override fun review(request: ExcerptReviewRequest): LocalCandidateBatch {
            reviews += request
            val old = durable.single { it.id == request.batchId }
            val next = if (lastReview === request) old else {
                check(old.version == request.expectedVersion)
                val updated = LocalCandidateBatch(old.id, old.source, old.sourceHash, old.generation,
                    old.version + 1, old.state, old.createdAtMillis, old.items.map {
                        if (it.id == request.itemId) {
                            check(it.review == ExcerptReview.PROPOSED)
                            ReviewedExcerpt(it.id, it.proposal, request.decision)
                        } else it
                    })
                durable[durable.indexOf(old)] = updated
                lastReview = request
                updated
            }
            if (loseReviewReadback) { loseReviewReadback = false; failRead = true }
            return next
        }
        override fun cancel(request: CandidateCancelRequest): LocalCandidateBatch {
            cancels += request
            val old = durable.single { it.id == request.batchId }
            check(old.version == request.expectedVersion && old.items.all { it.review == ExcerptReview.PROPOSED })
            val cancelled = LocalCandidateBatch(old.id, old.source, old.sourceHash, old.generation,
                old.version + 1, LocalCandidateState.CANCELLED, old.createdAtMillis, emptyList())
            durable[durable.indexOf(old)] = cancelled
            return cancelled
        }
    }
    private val store = object : EntryRepository {
        override val localCandidates = port
        override suspend fun readDraft() = null
        override suspend fun updateDraft(text: String) = error("Outside presentation contract")
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String) = error("Outside presentation contract")
        override suspend fun entries() = state.value.entries
        override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long) = error("Outside presentation contract")
    }
    private val controller = LocalCandidateController({ state.value }, { state.value = it(state.value) },
        { queue.add(it); true }, scope, Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC))
    private var registry: SaveableStateRegistry? = null
    private val callbacks = AtomicReference<CandidateDialogActions>()
    private val observer = CandidateDialogObserver { callbacks.set(it) }
    @After fun close() { controller.dispose(); scope.cancel() }
    @Composable private fun Screen(large: Boolean = false) {
        val saved = LocalSaveableStateRegistry.current
        SideEffect { registry = saved }
        val density = LocalDensity.current
        CompositionLocalProvider(LocalCandidateDialogObserver provides observer,
            LocalDensity provides if (large) Density(density.density, 2f) else density) {
            AIyouTheme {
                Box(if (large) Modifier.size(320.dp, 640.dp) else Modifier) {
                    AIyouApp(previewDestination = Destination.MEMORIES, local = state.value,
                        currentRecords = { state.value }, candidates = controller)
                }
            }
        }
    }
    private fun openReader(id: String = "one") {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-" + id))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-" + id))).performClick()
    }
    private fun openCandidates() {
        compose.onNodeWithTag("original-local-candidates").performScrollTo().performClick()
        drain()
    }
    private fun drain() {
        compose.runOnIdle { runBlocking { while (true) (queue.poll() ?: break)(store) } }
        compose.waitForIdle()
    }
    private fun generate(): LocalCandidateBatch {
        compose.onNodeWithTag("local-candidates-generate").performScrollTo().performClick()
        drain()
        // The worker can enqueue COMPLETE while the first drain is still consuming BEGIN.
        compose.waitUntil(3_000) { queue.isNotEmpty() || state.value.candidates?.let { ui ->
            !ui.computing && ui.batches.any { it.state == LocalCandidateState.READY }
        } == true }
        drain()
        return durable.single { it.state == LocalCandidateState.READY }
    }
    private fun suffix(batch: LocalCandidateBatch) = batch.id + "-" + batch.items.single().id
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun action(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun business() = checkNotNull(callbacks.get())
    private fun seed() {
        val job = port.begin(LocalCandidateStart("synthetic-private-batch", SourceVersion("one", 1), 55))
        port.complete(job, LocalExcerptRules.extract(job))
    }

    @Test fun actualReaderGeneratesPreviewsConfirmsAndReturnsToExactOriginalWithoutTaskSideEffects() {
        compose.setContent { Screen() }; openReader(); openCandidates()
        val batch = generate(); val suffix = suffix(batch)
        compose.onNodeWithTag("local-candidate-quote-" + suffix).assertTextEquals(batch.items.single().proposal.quote)
        click("local-candidate-accept-" + suffix)
        assertTrue(reviews.isEmpty())
        click("local-candidate-cancel-confirm"); assertTrue(reviews.isEmpty())
        click("local-candidate-accept-" + suffix)
        val retainedConfirm = business().confirm
        click("local-candidate-confirm")
        compose.runOnIdle { retainedConfirm(); assertEquals(1, queue.size) }
        assertEquals(ExcerptReview.PROPOSED, durable.single().items.single().review)
        drain()
        compose.onNodeWithTag("local-candidate-review-" + suffix).assertTextEquals("已采纳摘录")
        click("local-candidates-close"); openCandidates()
        compose.onNodeWithTag("local-candidate-review-" + suffix).assertTextEquals("已采纳摘录")
        click("local-candidate-source-" + suffix)
        val text = compose.onNodeWithTag("original-text").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals(one.originalText, text.text); assertTrue(text.spanStyles.isNotEmpty())
        assertEquals(listOf(one, two), state.value.entries)
        assertTrue(state.value.manualTags.isEmpty()); assertTrue(state.value.tasks.items.isEmpty())
        assertEquals("synthetic-private-draft", state.value.text); assertEquals(1, reviews.size)
    }

    @Test fun lostReadbackShowsUncertainStateAndRetriesTheSameExplicitReviewWithoutFalseEmptyOrSuccess() {
        seed(); compose.setContent { Screen() }; openReader(); openCandidates()
        val batch = durable.single(); val suffix = suffix(batch)
        click("local-candidate-accept-" + suffix); loseReviewReadback = true
        click("local-candidate-confirm"); drain()
        compose.onNodeWithTag("local-candidates-error").assertExists()
        compose.onAllNodesWithTag("local-candidates-notice").assertCountEquals(0)
        compose.onAllNodesWithText("还没有保存的本机摘录候选。").assertCountEquals(0)
        assertEquals(ExcerptReview.ACCEPTED, durable.single().items.single().review)
        compose.runOnIdle {
            val raw = checkNotNull(registry).performSave().toString()
            for (secret in listOf(reviews.single().mutationId, batch.sourceHash,
                batch.items.single().proposal.quote, one.originalText)) assertFalse(raw.contains(secret))
        }
        failRead = false; click("local-candidates-retry"); drain()
        assertSame(reviews[0], reviews[1]); assertEquals(2L, durable.single().version)
        compose.onNodeWithTag("local-candidate-review-" + suffix).assertTextEquals("已采纳摘录")
    }

    @Test fun reopenedSameItemAndRetainedNavigationCallbacksCannotReuseOldConfirmation() {
        seed(); compose.setContent { Screen() }; openReader(); openCandidates()
        val suffix = suffix(durable.single())
        click("local-candidate-accept-" + suffix)
        val oldConfirm = business().confirm; val oldCancel = business().cancelConfirmation
        click("local-candidate-cancel-confirm"); click("local-candidate-accept-" + suffix)
        compose.runOnIdle { oldCancel(); oldConfirm(); assertTrue(queue.isEmpty()) }
        compose.onNodeWithTag("local-candidate-confirm").assertExists()
        val freshConfirm = business().confirm
        val back = compose.onNodeWithText("返回列表").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { back(); freshConfirm(); assertTrue(queue.isEmpty()) }
        compose.waitForIdle(); assertTrue(reviews.isEmpty())
        openReader("two")
        compose.runOnIdle { oldConfirm(); freshConfirm(); assertTrue(queue.isEmpty()) }
        assertEquals(ExcerptReview.PROPOSED, durable.single().items.single().review)
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun narrowDoubleFontKeepsReviewCancelSourceAndCloseReachableWithTouchTargets() {
        seed(); compose.setContent { Screen(true) }; openReader(); openCandidates()
        val batch = durable.single(); val suffix = suffix(batch)
        for (tag in listOf("local-candidate-accept-" + suffix, "local-candidate-reject-" + suffix,
            "local-candidate-source-" + suffix, "local-candidate-cancel-" + batch.id, "local-candidates-close")) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
            assertTrue(node.getUnclippedBoundsInRoot().height >= 48.dp)
        }
        val retainedCancel = action("local-candidate-cancel-" + batch.id)
        click("local-candidate-cancel-" + batch.id)
        compose.runOnIdle { retainedCancel(); assertEquals(1, queue.size) }
        drain()
        assertEquals(LocalCandidateState.CANCELLED, durable.single().state)
        assertTrue(durable.single().items.isEmpty())
        val regenerated = generate(); val regeneratedSuffix = this.suffix(regenerated)
        click("local-candidate-accept-" + regeneratedSuffix)
        for (tag in listOf("local-candidate-confirm", "local-candidate-cancel-confirm", "local-candidates-close")) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            assertTrue(node.getUnclippedBoundsInRoot().height >= 48.dp)
        }
        click("local-candidate-cancel-confirm"); click("local-candidate-reject-" + regeneratedSuffix); drain()
        compose.onNodeWithTag("local-candidate-review-" + regeneratedSuffix).performScrollTo().assertTextEquals("已拒绝摘录")
        click("local-candidates-close"); assertNull(state.value.candidates)
        assertEquals(1, reviews.size); assertEquals(1, cancels.size)
    }

    @Test fun restorationDropsPrivateCandidateAndConfirmationStateAndRetiresOldCallbacks() {
        seed(); val restoration = StateRestorationTester(compose)
        restoration.setContent { Screen() }; openReader(); openCandidates()
        val batch = durable.single(); click("local-candidate-accept-" + suffix(batch))
        val confirm = business().confirm
        compose.runOnIdle {
            val raw = checkNotNull(registry).performSave().toString()
            for (secret in listOf(one.originalText, batch.items.single().proposal.quote,
                batch.sourceHash, batch.id, "synthetic-private-draft")) assertFalse(raw.contains(secret))
        }
        restoration.emulateSavedInstanceStateRestore(); compose.waitForIdle()
        compose.runOnIdle { confirm(); assertTrue(queue.isEmpty()) }
        assertNull(state.value.candidates); assertTrue(reviews.isEmpty())
        assertEquals(ExcerptReview.PROPOSED, durable.single().items.single().review)
    }
}
