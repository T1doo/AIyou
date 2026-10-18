package com.t1doo.aiyou

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.records.LocalRecordUiState
import com.t1doo.aiyou.records.originalPreview
import com.t1doo.aiyou.records.LocalOriginalReadingObserver
import com.t1doo.aiyou.records.OriginalReadingCallbacks
import com.t1doo.aiyou.records.OriginalReadingObserver
import com.t1doo.aiyou.ui.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlinx.coroutines.runBlocking

/** Actual production UI with synthetic state. Does not assert native storage or device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
class OriginalReadingPresentationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var callbacks: OriginalReadingCallbacks
    private lateinit var readingClipboard: Clipboard
    private lateinit var selection: SelectionState
    private val observer = object : OriginalReadingObserver {
        override fun onNavigation(callbacks: OriginalReadingCallbacks) { this@OriginalReadingPresentationTest.callbacks = callbacks }
        override fun onClipboard(clipboard: Clipboard) { readingClipboard = clipboard }
        override fun onSelection(selection: SelectionState) { this@OriginalReadingPresentationTest.selection = selection }
    }
    private val clipboard get() = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
    private fun entry(id: String = "record", text: String = "合成完整原文", revision: Int = 1) =
        StoredEntry(id, revision, text, 1_791_647_400_000, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY)
    private fun state(vararg entries: StoredEntry) = mutableStateOf(LocalRecordUiState(
        ready = true, opening = false, text = "合成未保存草稿", entries = entries.toList()))
    private fun content(state: MutableState<LocalRecordUiState>, destination: Destination = Destination.MEMORIES) {
        compose.setContent {
            CompositionLocalProvider(LocalOriginalReadingObserver provides observer) {
                AIyouTheme { AIyouApp(previewDestination = destination, local = state.value) }
            }
        }
    }
    private fun open(id: String = "record") {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-$id"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-$id"))).performClick()
    }
    private fun tab(label: String) = compose.onNode(hasText(label) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
    private fun sentinel() = compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("合成", "哨兵")) }
    private fun copied() = clipboard.primaryClip!!.getItemAt(0).text.toString()
    private fun fullText() = compose.onNodeWithTag("original-text").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun select(start: Int, end: Int) = compose.runOnIdle { selection.select(TextRange(start, end)) }
    private fun nativeCopy() {
        compose.onNodeWithTag("original-text").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.C); keyUp(Key.CtrlLeft) }
        compose.waitForIdle()
    }

    @Test fun boundedUnicodePreviewOpensAndCopiesEveryOriginalCharacter() {
        val text = "🌿".repeat(120) + "界".repeat(99_756) + "末端原文"
        assertEquals(100_000, text.length)
        val state = state(entry(text = text))
        content(state)
        val preview = originalPreview(text)
        assertEquals(241, preview.codePointCount(0, preview.length))
        assertTrue(preview.endsWith("…"))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-record"))
        compose.onNodeWithText(preview).assertIsDisplayed()
        compose.onAllNodesWithText(text).assertCountEquals(0)
        open()
        assertEquals(text, fullText())
        compose.onNodeWithText("复制全文").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(text, copied())
            assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
            assertEquals(text, state.value.entries.single().originalText)
            assertEquals("合成未保存草稿", state.value.text)
        }
    }

    @Test fun closeAndSystemBackPreserveEachListPositionAndSelectedRecord() {
        val state = state(*(0..54).map { entry("record-$it", "合成记录 $it：" + "叶".repeat(300)) }.toTypedArray())
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES, local = state.value) }
        }
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasTestTag("entry-record-20"))
        val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(before > 0f)
        open("record-20")
        compose.runOnIdle { back.onBackPressed() }
        assertEquals(before, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
        assertTrue(compose.onNodeWithTag("entry-record-20").fetchSemanticsNode().config[SemanticsProperties.Selected])
        tab("此刻").performClick()
        open("record-3")
        compose.onNodeWithText("返回列表").performClick()
        assertTrue(compose.onNodeWithTag("entry-record-3").fetchSemanticsNode().config[SemanticsProperties.Selected])
        tab("回忆").performClick()
        assertEquals(before, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
        assertTrue(compose.onNodeWithTag("entry-record-20").fetchSemanticsNode().config[SemanticsProperties.Selected])
    }

    @Test fun stableIdTracksReorderAndRevisionAndShowsDeletedRecordFallback() {
        val state = state(entry(), entry("other", "合成其他记录"))
        content(state); open()
        compose.runOnIdle { state.value = state.value.copy(entries = listOf(entry("other", "合成其他记录"), entry(text = "合成更新全文", revision = 2))) }
        assertEquals("合成更新全文", fullText())
        compose.onNodeWithText("复制全文").performClick()
        compose.runOnIdle { assertEquals("合成更新全文", copied()); state.value = state.value.copy(entries = listOf(entry("other", "合成更新全文"))) }
        compose.onNodeWithText("这条记录已不可用").assertIsDisplayed()
        compose.onAllNodesWithText("复制全文").assertCountEquals(0)
        compose.onAllNodes(hasTestTag("original-text")).assertCountEquals(0)
        compose.onNodeWithText("返回列表").performClick()
        compose.onNodeWithTag("entry-other").assertIsDisplayed()
    }

    @Test fun retainedOpenAndFullCopyCallbacksRejectNavigationRevisionAndReadFailure() {
        val state = state(entry())
        content(state)
        val oldOpen = callbacks.openEntry
        tab("待办").performClick()
        compose.runOnIdle { oldOpen("record") }
        compose.onAllNodesWithText("记录原文").assertCountEquals(0)
        tab("回忆").performClick(); open()
        val oldCopy = callbacks.copy
        sentinel()
        compose.runOnIdle { state.value = state.value.copy(entries = listOf(entry(text = "合成新版", revision = 2))) }
        compose.runOnIdle { assertFalse(oldCopy("record", 1)); assertEquals("哨兵", copied()) }
        val currentCopy = callbacks.copy
        compose.runOnIdle { state.value = state.value.copy(error = "合成读取失败") }
        compose.runOnIdle { assertFalse(currentCopy("record", 2)); assertEquals("哨兵", copied()) }
        compose.onNodeWithText("原文暂不可读取").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(error = null) }
        compose.onNodeWithText("返回列表").performClick(); tab("待办").performClick()
        compose.runOnIdle { assertFalse(currentCopy("record", 2)); assertEquals("哨兵", copied()) }
    }

    @Test fun nativeSelectionCopiesOnlySelectedTextWithSensitiveFlagAndRejectsStaleCopy() {
        val state = state(entry(text = "合成选段与末尾"))
        content(state); open()
        select(2, 4); nativeCopy()
        compose.runOnIdle {
            assertEquals("选段", copied())
            assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        }
        select(0, 2); nativeCopy()
        val stale = readingClipboard
        val oldSelection = compose.runOnIdle { assertEquals("合成", copied()); ClipEntry(clipboard.primaryClip!!) }
        sentinel()
        compose.runOnIdle { state.value = state.value.copy(entries = listOf(entry(text = "合成新原文", revision = 2))) }
        compose.runOnIdle { runBlocking { stale.setClipEntry(oldSelection) } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("哨兵", copied()) }
        // Native selection and keyboard copy stay operable; late writes use its actual proxy.
        select(2, 5); nativeCopy()
        val afterClose = readingClipboard
        val latestSelection = compose.runOnIdle { assertEquals("新原文", copied()); ClipEntry(clipboard.primaryClip!!) }
        sentinel()
        compose.onNodeWithText("返回列表").performClick()
        compose.runOnIdle { runBlocking { afterClose.setClipEntry(latestSelection) } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("哨兵", copied()) }
        open()
        compose.runOnIdle { runBlocking { afterClose.setClipEntry(latestSelection) }; assertEquals("哨兵", copied()) }
    }

    @Test fun retainedNavigationCallbacksRejectOtherRecordAndReopenedSameId() {
        val state = state(entry(), entry("other", "合成另一条原文"))
        content(state)
        val oldOpen = callbacks.openEntry
        val oldTab = callbacks.selectDestination
        open()
        val oldClose = callbacks.returnToList
        val oldCopy = callbacks.copy
        compose.onNodeWithText("返回列表").performClick()
        open("other"); sentinel()
        compose.runOnIdle { oldClose(); assertFalse(oldCopy("record", 1)); oldOpen("record"); oldTab(Destination.TASKS) }
        assertEquals("合成另一条原文", fullText())
        compose.runOnIdle { assertEquals("哨兵", copied()) }
        compose.onNodeWithText("返回列表").performClick(); open()
        compose.runOnIdle { oldClose(); assertFalse(oldCopy("record", 1)); oldOpen("record"); oldTab(Destination.TASKS) }
        assertEquals("合成完整原文", fullText())
        compose.runOnIdle { assertEquals("哨兵", copied()) }
        compose.onNodeWithText("复制全文").performClick()
        compose.runOnIdle { assertEquals("合成完整原文", copied()) }
        compose.onNodeWithText("返回列表").performClick()
    }

    @Test fun restoredNavigationResolvesLatestOriginalAndRestoresListAnchor() {
        val state = state(*(0..34).map { entry("record-$it", "合成全文 $it") }.toTypedArray())
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AIyouTheme { AIyouApp(previewDestination = Destination.MEMORIES, local = state.value) } }
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasTestTag("entry-record-20"))
        val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        open("record-20")
        compose.runOnIdle { state.value = state.value.copy(entries = state.value.entries.map {
            if (it.id == "record-20") entry(it.id, "合成恢复后的最新原文", 2) else it
        }) }
        restoration.emulateSavedInstanceStateRestore()
        assertEquals("合成恢复后的最新原文", fullText())
        compose.onNodeWithText("返回列表").performClick()
        assertEquals(before, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
        assertTrue(compose.onNodeWithTag("entry-record-20").fetchSemanticsNode().config[SemanticsProperties.Selected])
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun maximumMultilineOriginalReachesLastLineWithLargeFontAndKeyboardCopy() {
        val text = ("界🌿\n".repeat(24_999) + "尾🌿终")
        assertEquals(100_000, text.length)
        val state = state(entry(text = text))
        var saves = 0; var edits = 0
        lateinit var input: InputModeManager
        compose.setContent {
            input = LocalInputModeManager.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalOriginalReadingObserver provides observer) {
                Box(Modifier.size(320.dp, 640.dp).testTag("reader-viewport")) {
                    AIyouTheme { AIyouApp(local = state.value, previewEntryId = "record", onSave = { saves++ }, onDraftChange = { edits++ }) }
                }
            }
        }
        val field = compose.onNodeWithTag("original-text")
        assertEquals(text, fullText())
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue(layout.lineCount >= 25_000)
        val scrollNode = compose.onNodeWithTag("original-scroll")
        val scroll = scrollNode.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val before = scroll.value()
        assertTrue(scroll.maxValue() > 0f)
        scrollNode.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, scroll.maxValue())) }
        compose.waitForIdle()
        val after = scrollNode.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(after.value() > before)
        assertEquals(after.maxValue(), after.value(), 1f)
        // Last-line geometry must reach the viewport, beyond full-string/selection assertions.
        val lastTop = layout.getLineTop(layout.lineCount - 1) - after.value()
        val lastBottom = layout.getLineBottom(layout.lineCount - 1) - after.value()
        val fieldHeight = scrollNode.fetchSemanticsNode().boundsInRoot.height
        assertTrue(lastTop >= -1f && lastBottom <= fieldHeight + 1f)
        val viewport = compose.onNodeWithTag("reader-viewport").getUnclippedBoundsInRoot()
        assertEquals(320.dp, viewport.width); assertEquals(640.dp, viewport.height)
        val copy = compose.onNodeWithText("复制全文")
        copy.assertIsDisplayed().assertIsEnabled()
        val bounds = copy.getUnclippedBoundsInRoot()
        assertTrue(bounds.height >= AIyouDimensions.minTouchTarget)
        assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        compose.runOnIdle { assertTrue(input.requestInputMode(InputMode.Keyboard)) }
        copy.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        copy.assertIsFocused().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(text, copied()); assertEquals(0, edits); assertEquals(0, saves)
            assertEquals("合成未保存草稿", state.value.text)
        }
        val edit = field.fetchSemanticsNode().config.getOrNull(SemanticsActions.SetText)?.action
        if (edit != null) compose.runOnIdle { assertFalse(edit(androidx.compose.ui.text.AnnotatedString("不得修改"))) }
        assertEquals(text, fullText())
        sentinel()
        compose.runOnIdle { selection.selectAll(); assertEquals(text, selection.selectedTexts.single().text) }
        nativeCopy()
        compose.runOnIdle { assertEquals(text, copied()) }
        compose.onNodeWithText("返回列表").assertIsDisplayed().performClick()
    }

    @Test fun openingAndReadFailureHidePlaintextAndRetryPreservesDraft() {
        val state = state(entry())
        var retries = 0
        compose.setContent {
            CompositionLocalProvider(LocalOriginalReadingObserver provides observer) {
                AIyouTheme { AIyouApp(local = state.value, previewEntryId = "record", onRetry = {
                    retries++; state.value = state.value.copy(opening = true, error = null)
                }) }
            }
        }
        compose.runOnIdle { state.value = state.value.copy(opening = true) }
        compose.onNodeWithText("正在读取原文").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("original-text")).assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(opening = false, error = "合成读取失败") }
        val staleRetry = callbacks.retry
        compose.onNodeWithText("返回列表").performClick()
        tab("待办").performClick()
        compose.runOnIdle { staleRetry(); assertEquals(0, retries) }
        tab("回忆").performClick(); open()
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals("合成未保存草稿", state.value.text); assertNull(state.value.saveNotice) }
        compose.onNodeWithText("正在读取原文").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], shadows = [DenyClipboardShadow::class])
    fun platformClipboardDenialKeepsOriginalAndShowsFailureForBothCopyPaths() {
        DenyClipboardShadow.denials = 0
        val state = state(entry())
        var saves = 0; var edits = 0
        compose.setContent {
            CompositionLocalProvider(LocalOriginalReadingObserver provides observer) {
                AIyouTheme { AIyouApp(local = state.value, previewEntryId = "record",
                    onSave = { saves++ }, onDraftChange = { edits++ }) }
            }
        }
        compose.onNodeWithText("复制全文").performClick()
        compose.onNodeWithText("无法复制，原文已保留。").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, DenyClipboardShadow.denials) }
        select(0, 2); nativeCopy()
        compose.runOnIdle {
            // Fixed 1.12.1 static selection maps both Ctrl+C down and up to COPY.
            assertEquals(3, DenyClipboardShadow.denials)
            assertEquals("合成未保存草稿", state.value.text)
            assertEquals(0, saves); assertEquals(0, edits)
        }
        assertEquals("合成完整原文", fullText())
        compose.onNodeWithText("无法复制，原文已保留。").assertIsDisplayed()
        compose.onNodeWithText("返回列表").performClick()
    }

    /** Denies actual platform writes; no fake success result is injected into the production UI. */
    @Implements(ClipboardManager::class)
    class DenyClipboardShadow {
        companion object { var denials = 0 }
        @Implementation fun setPrimaryClip(@Suppress("UNUSED_PARAMETER") clip: ClipData) {
            denials++
            throw SecurityException("synthetic clipboard policy")
        }
        @Implementation fun getPrimaryClip(): ClipData? = null
        @Implementation fun getPrimaryClipDescription(): ClipDescription? = null
        @Implementation fun hasPrimaryClip(): Boolean = false
        @Implementation fun addPrimaryClipChangedListener(@Suppress("UNUSED_PARAMETER") listener: ClipboardManager.OnPrimaryClipChangedListener) = Unit
        @Implementation fun removePrimaryClipChangedListener(@Suppress("UNUSED_PARAMETER") listener: ClipboardManager.OnPrimaryClipChangedListener) = Unit
    }
}
