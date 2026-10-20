package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production route and controllers with a controlled read port; SQL proof tests are separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class SourceMemoryInboxPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val entry = StoredEntry("one", 1, "🙂合成原文：他说很开心；我并不开心；旅行计划还没有完成。", 55, "UTC", ProcessingPolicy.LOCAL_ONLY)
    private val source = SourceVersion(entry.id, 1)
    private val hash = OriginalExports.hash(entry.originalText)
    private var saved = SourceMemorySnapshot("memory", source, hash, 0, 1, SourceMemoryState.ACTIVE, 70, 55, "UTC",
        listOf(SourceMemoryVersion(1, SourceMemoryContent(OriginalTextReference(source, 0, entry.originalText.codePointCount(0, entry.originalText.length), entry.originalText),
            SourceMemoryKind.EVENT, SourceMemoryAssertion.UNCERTAIN, "私有合成备注"), SourceMemoryReview.PROPOSED, null, null, 70, 70)))
    private val state = mutableStateOf(LocalRecordUiState(ready = true, opening = false, entries = listOf(entry),
        supportsSourceMemories = true, supportsMemoryInbox = true))
    private val queue = ConcurrentLinkedQueue<suspend (EntryRepository) -> Unit>()
    private var failRead = false
    private var mutations = 0
    private class Page(override val pending: List<SourceMemorySnapshot>) : SourceMemoryInboxSnapshot {
        override val cursor: SourceMemoryInboxCursor? = null
        override val next: SourceMemoryInboxCursor? = null
    }
    private val inbox = object : SourceMemoryInboxRepository {
        private fun checkCurrent(s: SourceMemoryInboxSnapshot) = s is Page && s.pending ==
            (if (saved.current!!.review == SourceMemoryReview.PROPOSED) listOf(saved) else emptyList<SourceMemorySnapshot>())
        override fun capture(cursor: SourceMemoryInboxCursor?): SourceMemoryInboxSnapshot {
            if (failRead) throw SourceMemoryStorageFailure()
            return Page(if (saved.current!!.review == SourceMemoryReview.PROPOSED) listOf(saved) else emptyList())
        }
        override fun publishCurrent(snapshot: SourceMemoryInboxSnapshot, publish: () -> Unit): Boolean {
            if (failRead) throw SourceMemoryStorageFailure()
            return if (checkCurrent(snapshot)) { publish(); true } else false
        }
        override fun resolve(snapshot: SourceMemoryInboxSnapshot, item: SourceMemorySnapshot, publish: () -> Unit): Boolean =
            if (checkCurrent(snapshot) && snapshot.pending.any { it === item }) { publish(); true } else false
    }
    private val memories = object : SourceMemoryRepository {
        override val inbox = this@SourceMemoryInboxPresentationTest.inbox
        override fun preview(source: SourceVersion) = SourceMemoryPreview(source, hash, 0, entry.originalText, 55, "UTC", ProcessingPolicy.LOCAL_ONLY, 1)
        override fun memories(source: SourceVersion) = listOf(saved)
        override fun create(request: SourceMemoryCreate): SourceMemorySnapshot = error("No create action in this slice")
        override fun mutate(request: SourceMemoryMutation): SourceMemorySnapshot {
            check(request.id == saved.id && request.expectedVersion == saved.version && request.previousReview == saved.current!!.review)
            mutations++
            saved = SourceMemorySnapshot(saved.id, source, hash, 0, saved.version + 1, saved.state, 70, 55, "UTC",
                listOf(saved.current!!.copy(review = SourceMemoryRules.decision(saved.current!!.review, request.action), reviewedAtMillis = request.requestedAtMillis)))
            return saved
        }
    }
    private val owner = object : EntryRepository {
        override val sourceMemories = memories
        override suspend fun readDraft(): TextDraft? = null
        override suspend fun updateDraft(text: String): TextDraft = error("No saving")
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("No saving")
        override suspend fun entries() = state.value.entries
        override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("No saving")
    }
    private val controller = SourceMemoryInboxController({ state.value }, { state.value = reconcileMemoryInbox(state.value, it(state.value)) }, { queue.add(it) })
    private val review = SourceMemoryController({ state.value }, { state.value = it(state.value) }, { queue.add(it) }, Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC))
    private val actions = AtomicReference<MemoryInboxActions>()
    private var registry: SaveableStateRegistry? = null
    @After fun close() { controller.dispose(); review.dispose() }
    @Composable private fun Screen() {
        val density = LocalDensity.current; val savedRegistry = LocalSaveableStateRegistry.current
        SideEffect { registry = savedRegistry }
        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f),
            LocalMemoryInboxObserver provides MemoryInboxObserver { actions.set(it) }) {
            AIyouTheme { Box(Modifier.size(320.dp, 640.dp)) {
                AIyouApp(previewDestination = Destination.MEMORIES, local = state.value, currentRecords = { state.value },
                    sourceMemories = review, memoryInbox = controller)
            } }
        }
    }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun drain() { compose.runOnIdle { runBlocking { while (true) (queue.poll() ?: break)(owner) } }; compose.waitForIdle() }
    private fun open() { click("memory-inbox-open"); drain() }

    @Test fun narrowInboxReachesExactOriginalAndRequiresSeparateExistingAcceptanceAtDoubleFont() {
        compose.setContent { Screen() }; open()
        compose.onNodeWithTag("memory-inbox-count").assertTextEquals("本页待审 1 条")
        compose.onNodeWithTag("memory-inbox-quote-memory").performScrollTo().assertTextEquals(entry.originalText)
        for (tag in listOf("memory-inbox-source-memory", "memory-inbox-refresh", "memory-inbox-close")) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            assertTrue(node.getUnclippedBoundsInRoot().height >= 48.dp)
        }
        click("memory-inbox-source-memory"); assertEquals(0, mutations); drain()
        compose.onNodeWithTag("source-memory-review-0").performScrollTo().assertTextEquals("待审候选")
        click("source-memory-ACCEPT-0"); assertEquals(0, mutations)
        click("source-memory-cancel-confirm"); assertEquals(0, mutations)
        click("source-memory-ACCEPT-0"); click("source-memory-confirm"); drain()
        assertEquals(1, mutations); assertEquals(SourceMemoryReview.ACCEPTED, saved.current!!.review)
        click("source-memory-source-0")
        compose.onNodeWithTag("original-text").assertTextEquals(entry.originalText)
        assertEquals(listOf(entry), state.value.entries); assertTrue(state.value.tasks.items.isEmpty()); assertTrue(state.value.manualTags.isEmpty())
    }
    @Test fun failedReadAndRestorationHidePrivateResultsAndRetireRetainedPageCallbacks() {
        val restoration = StateRestorationTester(compose); restoration.setContent { Screen() }; open()
        val old = actions.get()
        compose.runOnIdle {
            val raw = checkNotNull(registry).performSave().toString()
            assertFalse(raw.contains(entry.originalText)); assertFalse(raw.contains(hash)); assertFalse(raw.contains("私有合成备注"))
            failRead = true
        }
        click("memory-inbox-refresh"); drain()
        compose.onNodeWithTag("memory-inbox-error").assertExists()
        compose.onNodeWithTag("memory-inbox-count").assertDoesNotExist()
        compose.onNodeWithTag("memory-inbox-quote-memory").assertDoesNotExist()
        compose.runOnIdle { old.open.getValue("memory")(); old.refresh(); assertTrue(queue.isEmpty()); failRead = false }
        click("memory-inbox-refresh"); drain(); val fresh = actions.get()
        restoration.emulateSavedInstanceStateRestore(); compose.waitForIdle()
        compose.runOnIdle { fresh.open.getValue("memory")(); fresh.refresh(); assertTrue(queue.isEmpty()) }
        assertNull(state.value.memoryInbox); assertNull(state.value.sourceMemories); assertEquals(0, mutations)
        // A restored source ID must not become an unbound ordinary reader after losing its private proof.
        open(); click("memory-inbox-source-memory"); drain()
        compose.onNodeWithTag("source-memory-review-0").performScrollTo().assertTextEquals("待审候选")
        restoration.emulateSavedInstanceStateRestore(); compose.waitForIdle()
        compose.onNodeWithTag("original-text").assertDoesNotExist()
        compose.onNodeWithTag("source-memory-review-0").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(entries = listOf(entry.copy(currentRevision = 2, originalText = "合成变化后的新原文"))) }
        compose.onNodeWithTag("original-text").assertDoesNotExist()
        assertNull(state.value.sourceMemories); assertEquals(0, mutations)
    }
}
