package com.t1doo.aiyou

import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import com.t1doo.aiyou.designsystem.AIyouDimensions
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.AttachmentStore
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual root/history/attachment UI and native bitmap; synthetic repository, not Room/Keystore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoricalAttachmentPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGMQCqv4DwADPgHgZNJcPQAAAABJRU5ErkJggg==")
    private val current = StoredEntry("synthetic-original", 2, "合成新版原文", 1, "Etc/UTC", ProcessingPolicy.LOCAL_ONLY)
    private val versions = listOf(OriginalRevision(current.id, 1, "合成旧版全文", "synthetic", 1),
        OriginalRevision(current.id, 2, current.originalText, "synthetic", 2))
    private val intent = AttachmentIntent("synthetic-attachment", current.id, 1, "image/png",
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size.toLong())
    private val ready = LocalAttachment(intent, AttachmentState.READY)
    private inner class Fixture {
        val history = OriginalHistoryUiState(7, current.id, requestId = 3, loading = false,
            currentRevision = 2, revisions = versions, selectedRevision = 1)
        val local = mutableStateOf(LocalRecordUiState(ready = true, opening = false, entries = listOf(current),
            text = "合成独立此刻草稿", originalHistory = history,
            originalEdit = OriginalEditUiState(8, current.id, 2, current.originalText, text = "合成未保存的修改")))
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        var reads = 0; var saves = 0; var onRead: () -> Unit = {}; var failRead = false
        val store = object : AttachmentStore {
            override suspend fun recover() {}
            override suspend fun list(entryId: String, revision: Int) = if (entryId == current.id && revision == 1) listOf(ready) else emptyList()
            override suspend fun save(intent: AttachmentIntent, source: () -> InputStream): LocalAttachment { saves++; error("historical route must not import") }
            override suspend fun read(id: String): ByteArray {
                check(id == intent.id); reads++; onRead()
                if (failRead) throw java.io.IOException("synthetic authenticated-read access failure")
                return bytes.copyOf()
            }
        }
        val repository = object : EntryRepository {
            override val attachments: AttachmentStore = store
            override suspend fun entries() = listOf(current)
            override suspend fun revisions(entryId: String) = versions
            override suspend fun readDraft(): TextDraft? = null
            override suspend fun updateDraft(text: String): TextDraft = error("must not write draft")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("must not save draft")
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("must not revise")
        }
        val controller = LocalAttachmentController({ queue.addLast(it); true }, PlatformPngDecoder::decode,
            canUsePanel = { it.hasRecordAccess(local.value) })
        lateinit var callbacks: OriginalReadingCallbacks
        fun drain() = runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(repository) }
        fun start(narrow: Boolean = false) {
            val observer = object : OriginalReadingObserver {
                override fun onNavigation(callbacks: OriginalReadingCallbacks) { this@Fixture.callbacks = callbacks }
                override fun onClipboard(clipboard: Clipboard) {}
                override fun onSelection(selection: SelectionState) {}
            }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalOriginalReadingObserver provides observer,
                    LocalDensity provides if (narrow) Density(density.density, 2f) else density) {
                    AIyouTheme { AIyouApp(local = local.value, previewDestination = Destination.MEMORIES,
                        previewEntryId = current.id, attachments = controller) }
                }
            }
        }
        fun open() {
            compose.onNodeWithText("查看附件").assertIsDisplayed().performTouchInput { click() }
            compose.runOnIdle { drain() }
        }
    }

    @Test fun actualHistoricalRouteViewsOldNativePictureAndReturnsToSameOriginalAndBothInputs() {
        val f = Fixture(); f.start(); f.open()
        compose.onNodeWithText("附件 · 原文版本 1 · 只读").assertIsDisplayed()
        compose.onAllNodesWithText("选择 PNG 图片").assertCountEquals(0)
        compose.onNodeWithText("查看图片").performScrollTo().performTouchInput { click() }
        compose.runOnIdle { f.drain() }
        compose.onNodeWithTag("attachment-picture").assertIsDisplayed()
        compose.onNodeWithText("返回历史原文").performTouchInput { click() }
        compose.onNodeWithTag("original-text").assertTextEquals("合成旧版全文")
        compose.runOnIdle {
            assertNull(f.controller.state.value); assertEquals(f.history, f.local.value.originalHistory)
            assertEquals("合成独立此刻草稿", f.local.value.text)
            assertEquals("合成未保存的修改", f.local.value.originalEdit!!.text)
            assertEquals(1, f.reads); assertEquals(0, f.saves)
        }
    }

    @Test fun retainedHistoryActionRejectsChangedRequestAndNewVersionNeverShowsOldPicture() {
        val f = Fixture(); f.start()
        f.open(); compose.onNodeWithText("返回历史原文").performClick()
        lateinit var old: OriginalReadingCallbacks
        compose.runOnIdle {
            old = f.callbacks // same navigation generation; only the history request/selection changes below
            f.local.value = f.local.value.copy(originalHistory = f.history.copy(requestId = 4, selectedRevision = 2))
        }
        compose.runOnIdle { old.beginHistoricalAttachments(current.id, 1); assertNull(f.controller.state.value) }
        f.open(); compose.onNodeWithText("附件 · 原文版本 2 · 只读").assertIsDisplayed()
        compose.onAllNodesWithText("查看图片").assertCountEquals(0)
        compose.onAllNodesWithTag("attachment-picture").assertCountEquals(0)
        compose.runOnIdle { assertTrue(f.controller.state.value!!.items.isEmpty()); assertEquals(0, f.reads) }
        compose.onNodeWithText("返回历史原文").performClick()
        compose.runOnIdle { f.local.value = f.local.value.copy(originalHistory = f.history.copy(requestId = 5)) }
        f.open(); compose.onNodeWithText("附件 · 原文版本 1 · 只读").assertIsDisplayed()
        compose.runOnIdle { old.beginHistoricalAttachments(current.id, 1); assertEquals(5, f.controller.state.value!!.historyOwner!!.requestId) }
    }

    @Test fun lateAuthenticatedReadAndGlobalFailureHideHistoricalPictureWithoutChangingOwnership() {
        val f = Fixture(); f.start(); f.open()
        f.onRead = { f.local.value = f.local.value.copy(originalHistory = f.history.copy(requestId = 4, selectedRevision = 2)) }
        compose.onNodeWithText("查看图片").performScrollTo().performClick()
        compose.runOnIdle { f.drain(); assertNull(f.controller.state.value!!.picture) }
        compose.onAllNodesWithTag("attachment-picture").assertCountEquals(0)
        compose.onNodeWithText("当前原文暂不可读取或版本已变化。原文与附件已保留，请返回原文。").assertIsDisplayed()
        compose.onNodeWithText("返回历史原文").performClick()
        compose.onNodeWithTag("original-text").assertTextEquals(current.originalText)
        compose.runOnIdle { f.local.value = f.local.value.copy(originalHistory = f.history.copy(requestId = 5)); f.onRead = {} }
        f.open(); compose.onNodeWithText("查看图片").performScrollTo().performClick(); compose.runOnIdle { f.drain() }
        compose.onNodeWithTag("attachment-picture").assertIsDisplayed()
        compose.runOnIdle { f.local.value = f.local.value.copy(error = "合成全局读取失败") }
        compose.onAllNodesWithTag("attachment-picture").assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, f.controller.state.value!!.revision); assertEquals(0, f.saves) }
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h1000dp")
    fun narrowLargeFontHistoricalFailureRecoveryHasNoImportOrOverwriteActions() {
        var retries = 0
        val state = mutableStateOf(AttachmentUiState(1, current.id, 1, loading = false,
            historyOwner = AttachmentHistoryOwner(7, 3, 2), items = listOf(ready.copy(state = AttachmentState.FAILED))))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AIyouTheme { LocalAttachmentScreen(state.value, {}, { retries++ }, {}, {}, {}, {}) }
            }
        }
        compose.onAllNodesWithText("选择 PNG 图片").assertCountEquals(0)
        compose.onAllNodesWithText("重新选择同一图片").assertCountEquals(0)
        compose.onAllNodesWithText("重试保存确认").assertCountEquals(0)
        val retry = compose.onNodeWithText("重试读取附件").performScrollTo().assertIsEnabled()
        assertTrue(retry.getUnclippedBoundsInRoot().height >= AIyouDimensions.minTouchTarget)
        retry.performTouchInput { click() }; compose.runOnIdle { assertEquals(1, retries) }
        compose.runOnIdle { state.value = state.value.copy(items = listOf(ready.copy(state = AttachmentState.CORRUPT))) }
        compose.onAllNodesWithText("查看图片").assertCountEquals(0)
        compose.onAllNodesWithText("重新选择同一图片").assertCountEquals(0)
    }
}
