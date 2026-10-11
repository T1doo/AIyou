package com.t1doo.aiyou

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual navigation/Compose/native decode. Repository is synthetic, not Room or Keystore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentNavigationPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val currentText = (1..80).joinToString("\n") { "合成当前原文第 $it 行：山川与几何色块。" }
    private val oldText = (1..80).joinToString("\n") { "合成历史原文第 $it 行：旧版本仍完整保留。" }
    private val current = StoredEntry("synthetic-long-original", 2, currentText, 1, "Etc/UTC", ProcessingPolicy.LOCAL_ONLY)
    private inner class Fixture(val historical: Boolean) {
        val bytes = if (historical) AttachmentUiImages.landscape() else AttachmentUiImages.portrait()
        val revision = if (historical) 1 else 2
        val versions = listOf(OriginalRevision(current.id, 1, oldText, "synthetic", 1),
            OriginalRevision(current.id, 2, currentText, "synthetic", 2))
        val history = if (historical) OriginalHistoryUiState(7, current.id, requestId = 3, loading = false,
            currentRevision = 2, revisions = versions, selectedRevision = 1) else null
        val local = mutableStateOf(LocalRecordUiState(ready = true, opening = false, entries = listOf(current),
            text = "合成独立此刻草稿", originalHistory = history,
            originalEdit = if (historical) OriginalEditUiState(8, current.id, 2, currentText, text = "合成未保存的修改") else null))
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val items = (1..24).map { n -> LocalAttachment(AttachmentIntent("synthetic-image-$n", current.id,
            revision, if (historical) "image/png" else "image/jpeg", digest, bytes.size.toLong()), AttachmentState.READY) }
        val tail = items.last().intent.id
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        var reads = 0
        val repository = object : EntryRepository {
            override val attachments = object : AttachmentStore {
                override suspend fun recover() {}
                override suspend fun list(entryId: String, revision: Int): List<LocalAttachment> {
                    check(entryId == current.id && revision == this@Fixture.revision); return items
                }
                override suspend fun save(intent: AttachmentIntent, source: () -> InputStream): LocalAttachment = error("must not import")
                override suspend fun read(id: String): ByteArray { check(id == tail); reads++; return bytes.copyOf() }
            }
            override suspend fun entries() = listOf(current)
            override suspend fun revisions(entryId: String) = versions
            override suspend fun readDraft(): TextDraft? = null
            override suspend fun updateDraft(text: String): TextDraft = error("must not write draft")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("must not save draft")
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("must not revise")
        }
        val controller = LocalAttachmentController({ queue.addLast(it); true }, PlatformImageDecoder::decode,
            canUsePanel = { it.hasRecordAccess(local.value) })
        fun drain() = runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(repository) }
        fun start() {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    AIyouTheme { AIyouApp(local = local.value, previewDestination = Destination.MEMORIES,
                        previewEntryId = current.id, attachments = controller) }
                }
            }
        }
        fun viewTail() {
            compose.onNode(hasText("查看图片") and hasAnyAncestor(hasTestTag("attachment-$tail")))
                .performScrollTo().assertIsDisplayed().performTouchInput { click() }
            compose.runOnIdle { drain() }
        }
    }
    private fun offset(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun scrollOriginalToEnd(): Float {
        compose.onNodeWithTag("original-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
        compose.waitForIdle()
        return offset("original-scroll").also { assertTrue(it > 1000f) }
    }
    private fun assertViewer(f: Fixture) {
        // Do not scroll to the picture: successful viewing itself must make it visible.
        val node = compose.onNodeWithTag("attachment-picture").assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue(bounds.height.value > 80f)
        assertTrue(bounds.top.value >= 0f && bounds.bottom.value <= 640f)
        compose.onAllNodesWithTag("attachment-scroll").assertCountEquals(0)
        compose.onAllNodesWithText("选择图片").assertCountEquals(0)
        compose.onAllNodesWithText("返回附件列表").assertCountEquals(1)
        assertTrue(compose.onNodeWithText("返回附件列表").getUnclippedBoundsInRoot().height >= AIyouDimensions.minTouchTarget)
        compose.runOnIdle {
            val picture = f.controller.state.value!!.picture as PlatformAttachmentPicture
            assertEquals(if (f.historical) 960 else 640, picture.width)
            assertEquals(if (f.historical) 540 else 960, picture.height)
            assertEquals(f.digest, MessageDigest.getInstance("SHA-256").digest(f.bytes).joinToString("") { "%02x".format(it) })
        }
    }
    private fun exercise(historical: Boolean) {
        val f = Fixture(historical); f.start()
        val originalOffset = scrollOriginalToEnd()
        compose.onNodeWithText("查看附件").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { f.drain() }
        val view = compose.onNode(hasText("查看图片") and hasAnyAncestor(hasTestTag("attachment-${f.tail}")))
        view.performScrollTo().assertIsDisplayed()
        val listOffset = offset("attachment-scroll")
        assertTrue(listOffset > 1000f)
        view.performTouchInput { click() }; compose.runOnIdle { f.drain() }
        assertViewer(f)
        val help = compose.onNodeWithTag("attachment-limits").assertIsDisplayed()
        assertTrue(help.getUnclippedBoundsInRoot().height >= AIyouDimensions.minTouchTarget)
        help.performTouchInput { click() }
        compose.onNodeWithText("支持 JPEG 与静态 PNG", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("选择移除资料后", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("关闭说明").assertIsDisplayed().performTouchInput { click() }
        if (historical) compose.onNodeWithText("返回附件列表").performTouchInput { click() }
        else compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOffset, offset("attachment-scroll"), 1f)
        view.assertIsDisplayed()
        compose.onAllNodesWithTag("attachment-picture").assertCountEquals(0)
        if (historical) {
            compose.onAllNodesWithText("选择图片").assertCountEquals(0)
            compose.onAllNodesWithTag("jpeg-metadata-choice").assertCountEquals(0)
        } else {
            val choice = compose.onNodeWithTag("jpeg-metadata-choice").performScrollTo().assertIsDisplayed()
            assertTrue(choice.getUnclippedBoundsInRoot().height >= AIyouDimensions.minTouchTarget)
            compose.onNodeWithText("原图可能包含拍摄位置等资料，仅加密保存在本机。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("选择图片").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(if (historical) "返回历史原文" else "返回原文").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithTag("original-text").assertTextEquals(if (historical) oldText else currentText)
        assertEquals(originalOffset, offset("original-scroll"), 1f)
        compose.runOnIdle {
            assertNull(f.controller.state.value)
            assertEquals(f.history, f.local.value.originalHistory)
            assertEquals("合成独立此刻草稿", f.local.value.text)
            if (historical) assertEquals("合成未保存的修改", f.local.value.originalEdit!!.text)
            assertEquals(current, f.local.value.entries.single())
            assertEquals(1, f.reads)
        }
    }
    @Test fun currentLongOriginalTailJpegViewerAndBackPreserveBothScrollPositions() = exercise(false)
    @Test fun historicalLongOriginalTailPngViewerAndBackPreserveBothInputsAndLimits() = exercise(true)
}
