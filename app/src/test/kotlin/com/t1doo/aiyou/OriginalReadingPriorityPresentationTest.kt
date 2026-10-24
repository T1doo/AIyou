package com.t1doo.aiyou

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production reader/root and synthetic state. No device, storage, picker or clipboard IO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OriginalReadingPriorityPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val first = StoredEntry("one", 1, "合成首段：今天看见叶片。\n" + "合成长文🌿。\n".repeat(200),
        1000, "Etc/UTC", ProcessingPolicy.LOCAL_ONLY)
    private fun local(vararg entries: StoredEntry) = LocalRecordUiState(ready = true, opening = false,
        text = "合成独立未保存草稿", entries = entries.toList())

    private fun readingFirst(scale: Float) {
        val state = mutableStateOf(local(first))
        var attachmentRequests = 0; var writes = 0
        val history = mutableListOf<Pair<String, Int>>()
        val attachments = LocalAttachmentController({ attachmentRequests++; false }, { error("No image decoding") })
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                AIyouTheme { Box(Modifier.fillMaxSize().testTag("priority-viewport")) {
                    AIyouApp(local = state.value, currentRecords = { state.value }, previewEntryId = first.id,
                        attachments = attachments, onSave = { writes++ }, onDraftChange = { writes++ },
                        onBeginOriginalHistory = { id, revision -> history += id to revision })
                } }
            }
        }
        val viewport = compose.onNodeWithTag("priority-viewport").getUnclippedBoundsInRoot()
        // No scrolling precedes these assertions. This is the actual selected-record route.
        for (node in listOf(compose.onNodeWithText("复制全文"), compose.onNodeWithText("查看附件"),
            compose.onNodeWithTag("original-tools-open"))) {
            node.assertIsDisplayed().assertIsEnabled()
            val b = node.getUnclippedBoundsInRoot()
            assertTrue(b.height >= 48.dp)
            assertTrue(b.left >= viewport.left && b.right <= viewport.right && b.top >= viewport.top && b.bottom <= viewport.bottom)
        }
        val reader = compose.onNodeWithTag("original-scroll").assertIsDisplayed()
        assertTrue(reader.getUnclippedBoundsInRoot().height >= 48.dp)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("original-text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTrue(layouts.single().getLineBottom(0) <= reader.fetchSemanticsNode().boundsInRoot.height)
        compose.onNodeWithTag("original-reading-edit").assertDoesNotExist()
        assertEquals(0, attachmentRequests); assertTrue(history.isEmpty())
        compose.openOriginalTools()
        compose.onNodeWithText("记录日期：1970-01-01 00:00 · Etc/UTC · 原文版本 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("original-reading-edit").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("历史版本").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf("one" to 1), history)
        compose.onNodeWithTag("original-tools").assertDoesNotExist()
        compose.onNodeWithText("查看附件").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, attachmentRequests)
            assertEquals("one", attachments.state.value?.entryId)
            assertEquals(1, attachments.state.value?.revision)
            assertEquals(0, writes); assertEquals("合成独立未保存草稿", state.value.text)
            assertEquals(first, state.value.entries.single())
        }
    }
    @Test @Config(qualifiers = "w360dp-h800dp")
    fun normalReaderPrioritizesOriginalAndAttachmentsBeforeDisclosedTools() = readingFirst(1f)
    @Test @Config(qualifiers = "w320dp-h640dp")
    fun narrowDoubleFontReaderKeepsFirstLineAndPrimaryActionsBeforeScrolling() = readingFirst(2f)
    @Test @Config(qualifiers = "w800dp-h360dp")
    fun shortLandscapeReaderKeepsFirstLineAndPrimaryActionsBeforeScrolling() = readingFirst(1f)

    private fun owner() = object : RecordAuthorizationRepository {
        override fun readAuthorization(source: SourceVersion): RecordAuthorizationSnapshot = error("No authorization IO")
        override fun previewAuthorization(expected: RecordAuthorizationSource, purpose: TextPurpose,
            clock: () -> java.time.Instant): RecordAuthorizationPreview = error("No authorization IO")
        override fun changeAuthorization(request: RecordAuthorizationChange,
            clock: () -> java.time.Instant): RecordAuthorizationReceipt = error("No authorization IO")
    }
    @Test @Config(qualifiers = "w360dp-h800dp")
    fun toolsRetireAcrossCloseReopenEntryOwnerFailureAndObservedAba() {
        val state = mutableStateOf(local(first).copy(recordAuthorizationOwner = owner()))
        val selected = mutableStateOf("one")
        val edits = mutableListOf<Pair<String, Int>>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AIyouTheme {
            OriginalReadingScreen(selected.value, state.value, {}, { _, _ -> false }, { _, _ -> true },
                onEdit = { id, revision -> edits += id to revision })
        } }
        fun action(): () -> Boolean {
            compose.openOriginalTools()
            return compose.onNodeWithTag("original-reading-edit").performScrollTo()
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        }
        val closed = action()
        compose.onNodeWithTag("original-tools-close").performClick()
        val priorOpening = action()
        compose.runOnIdle { closed(); assertTrue(edits.isEmpty()) }
        compose.onNodeWithTag("original-tools").assertExists()
        val base = state.value
        compose.runOnIdle { state.value = base.copy(entries = listOf(first.copy(id = "two"))); selected.value = "two" }
        compose.onNodeWithTag("original-tools").assertDoesNotExist()
        compose.runOnIdle { priorOpening(); assertTrue(edits.isEmpty()); selected.value = "one"; state.value = base }
        compose.waitForIdle()
        compose.runOnIdle { priorOpening(); assertTrue(edits.isEmpty()) }
        val beforeOwner = action()
        compose.runOnIdle { state.value = base.copy(recordAuthorizationOwner = owner()) }
        compose.onNodeWithTag("original-tools").assertDoesNotExist()
        compose.runOnIdle { beforeOwner(); assertTrue(edits.isEmpty()) }
        val beforeFailure = action()
        compose.runOnIdle { state.value = state.value.copy(error = "合成读取错误") }
        compose.onNodeWithTag("original-text").assertDoesNotExist()
        compose.onNodeWithTag("original-tools").assertDoesNotExist()
        compose.runOnIdle { beforeFailure(); assertTrue(edits.isEmpty()); state.value = base }
        compose.waitForIdle()
        compose.runOnIdle { beforeFailure(); assertTrue(edits.isEmpty()) }
        val current = action()
        compose.runOnIdle { current(); current(); assertEquals(listOf("one" to 1), edits) }
        compose.onNodeWithTag("original-tools").assertDoesNotExist()
        compose.runOnIdle { assertEquals(base, state.value) }
        val beforeRecreation = action()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("original-tools").assertDoesNotExist()
        compose.runOnIdle { beforeRecreation(); assertEquals(listOf("one" to 1), edits); assertEquals(base, state.value) }
    }

    @Test @Config(qualifiers = "w320dp-h640dp")
    fun toolsPreserveExactHighlightSelectionAndScrollWhileChangedSourceFailsClosed() {
        val start = first.originalText.codePointCount(0, first.originalText.indexOf("合成长文"))
        val proof = OriginalReadingLocation(first.id, 1, OriginalExports.hash(first.originalText), start, start + 4)
        val state = mutableStateOf(local(first))
        val scroll = ScrollState(0)
        lateinit var observedSelection: SelectionState
        val observer = object : OriginalReadingObserver {
            override fun onNavigation(callbacks: OriginalReadingCallbacks) = Unit
            override fun onClipboard(clipboard: Clipboard) = Unit
            override fun onSelection(selection: SelectionState) { observedSelection = selection }
        }
        compose.setContent { AIyouTheme {
            CompositionLocalProvider(LocalOriginalReadingObserver provides observer) {
                OriginalReadingScreen(first.id, state.value, {}, { _, _ -> false }, { _, _ -> true },
                    originalScrollState = scroll, location = proof, requiredSource = proof)
            }
        } }
        val full = compose.onNodeWithTag("original-text").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val from = first.originalText.offsetByCodePoints(0, start)
        val through = first.originalText.offsetByCodePoints(from, 4)
        assertEquals(first.originalText, full.text)
        assertEquals(from, full.spanStyles.single().start); assertEquals(through, full.spanStyles.single().end)
        compose.runOnIdle { observedSelection.select(TextRange(from, through)) }
        compose.onNodeWithTag("original-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 200f)) }
        compose.waitForIdle()
        val before = compose.runOnIdle { scroll.value }
        compose.openOriginalTools()
        compose.onNodeWithTag("original-tools-close").performClick()
        compose.runOnIdle {
            assertEquals(before, scroll.value)
            assertEquals(first.originalText.substring(from, through), observedSelection.selectedTexts.single().text)
        }
        compose.openOriginalTools()
        compose.runOnIdle { state.value = state.value.copy(entries = listOf(first.copy(currentRevision = 2, originalText = "合成已变更原文"))) }
        compose.onNodeWithTag("original-tools").assertDoesNotExist()
        compose.onNodeWithTag("original-text").assertDoesNotExist()
        compose.onNodeWithText("回顾来源版本已变化").assertIsDisplayed()
    }
}
