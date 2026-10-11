package com.t1doo.aiyou

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual UI/registry with explicit synthetic repository boundary; physical grants/storage not certified. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
class AttachmentPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val intent = AttachmentIntent("synthetic-attachment", "record", 1, "image/png", "0".repeat(64), 80)
    private val panel = AttachmentUiState(1, "record", 1, loading = false)
    private val entry = StoredEntry("record", 1, "合成保存的原文", 1, "Etc/UTC", ProcessingPolicy.LOCAL_ONLY)
    private class Registry : ActivityResultRegistry() {
        val codes = mutableListOf<Int>()
        val inputs = mutableListOf<Any?>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(contract is ActivityResultContracts.PickVisualMedia)
            codes += requestCode; inputs += input
        }
    }
    private inner class Fixture {
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        val controller = LocalAttachmentController({ queue.addLast(it); true }, { error("test must not decode") })
        val repository = object : EntryRepository {
            override val attachments = object : AttachmentStore {
                override suspend fun recover() {}
                override suspend fun list(entryId: String, revision: Int) = emptyList<LocalAttachment>()
                override suspend fun save(intent: AttachmentIntent, source: () -> InputStream): LocalAttachment = error("synthetic boundary, not a save test")
                override suspend fun read(id: String): ByteArray = error("not a read test")
            }
            override suspend fun entries() = listOf(entry)
            override suspend fun readDraft(): TextDraft? = null
            override suspend fun updateDraft(text: String): TextDraft = error("unexpected capture write")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("unexpected capture save")
            override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("unexpected original write")
        }
        fun drain() = runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(repository) }
        fun open() { controller.open(entry.id, 1); drain() }
        fun select() { controller.select(controller.state.value!!.sessionId) }
    }
    private fun screen(state: AttachmentUiState, retry: () -> Unit = {}, cancel: () -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AIyouTheme { LocalAttachmentScreen(state, {}, {}, cancel, retry, {}, {}) }
            }
        }
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h1000dp")
    fun narrowLargeFontFailedConfirmationAndCancelAreReachableWithTouchTargets() {
        var retries = 0; var cancels = 0
        screen(panel.copy(selection = AttachmentSelection(2, intent.id, phase = AttachmentImportPhase.FAILED, sourcePrepared = true),
            error = "合成确认失败，原文与图片已保留。"), { retries++ }, { cancels++ })
        val retry = compose.onNodeWithText("重试保存确认").performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertTrue(retry.getUnclippedBoundsInRoot().height >= AIyouDimensions.minTouchTarget)
        retry.performTouchInput { click() }
        compose.onNodeWithText("取消添加").performScrollTo().assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, cancels) }
        compose.onAllNodesWithText("已保存在本机").assertCountEquals(0)
    }

    @Test fun savingAndCorruptStatesOfferNoFalseCancelViewOrOverwriteAction() {
        val state = mutableStateOf(panel.copy(selection = AttachmentSelection(2, intent.id, phase = AttachmentImportPhase.SAVING)))
        compose.setContent { AIyouTheme { LocalAttachmentScreen(state.value, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("正在保存图片").assertIsDisplayed()
        compose.onAllNodesWithText("取消添加").assertCountEquals(0)
        compose.onAllNodesWithText("重试保存确认").assertCountEquals(0)
        compose.runOnIdle { state.value = panel.copy(items = listOf(LocalAttachment(intent, AttachmentState.CORRUPT))) }
        compose.onNodeWithText("附件无法通过完整性校验，原文件已保留，不会显示或覆盖。").assertIsDisplayed()
        compose.onAllNodesWithText("查看图片").assertCountEquals(0)
        compose.onAllNodesWithText("重新选择同一图片").assertCountEquals(0)
        compose.onAllNodesWithText("已保存在本机").assertCountEquals(0)
    }

    @Test fun savedOriginalOpensRealAttachmentRouteAndReturnsWithoutReplacingCapture() {
        val f = Fixture()
        val local = LocalRecordUiState(ready = true, opening = false, text = "合成此刻草稿", entries = listOf(entry))
        compose.setContent { AIyouTheme { AIyouApp(local = local, previewDestination = Destination.MEMORIES,
            previewEntryId = entry.id, attachments = f.controller) } }
        compose.onNodeWithText("查看附件").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { f.drain() }
        compose.onNodeWithText("附件 · 原文版本 1").assertIsDisplayed()
        compose.onNodeWithText("选择 PNG 图片").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("返回原文").performClick()
        compose.onNodeWithTag("original-text").assertIsDisplayed()
        compose.runOnIdle { assertNull(f.controller.state.value); assertEquals("合成此刻草稿", local.text); assertEquals(entry, local.entries.single()) }
    }

    @Test fun actualPickerRegistryBindsOldResultToOldRequestAndRestorationDoesNotRelaunch() {
        val f = Fixture(); f.open(); f.select()
        val registry = Registry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val restore = StateRestorationTester(compose)
        restore.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                AttachmentPicker(f.controller, f.controller.state.collectAsState().value?.selection)
            }
        }
        compose.runOnIdle { assertEquals(1, registry.codes.size) }
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(1, registry.codes.size) }
        val first = registry.codes.single()
        compose.runOnIdle { f.controller.cancel(f.controller.state.value!!.sessionId); f.drain(); f.select() }
        compose.runOnIdle { assertEquals(2, registry.codes.size) }
        val nextRequest = f.controller.state.value!!.selection!!.requestId
        compose.runOnIdle { registry.dispatchResult(first, Uri.parse("content://aiyou.synthetic/old")) }
        compose.runOnIdle {
            assertEquals(nextRequest, f.controller.state.value!!.selection!!.requestId)
            assertEquals(AttachmentImportPhase.PICKING, f.controller.state.value!!.selection!!.phase)
            assertTrue(f.queue.isEmpty())
            registry.dispatchResult(registry.codes.last(), null as Uri?)
            f.drain()
        }
        compose.runOnIdle { assertNull(f.controller.state.value!!.selection) }
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h1000dp")
    fun globalRecordFailureHidesPictureAndShowsReachableActualRecovery() {
        var recovered = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AIyouTheme { LocalAttachmentScreen(panel.copy(unavailable = true, error = "合成本机记录读取失败。"),
                    {}, {}, {}, {}, {}, {}, onRetryRecords = { recovered++ }) }
            }
        }
        compose.onNodeWithText("恢复本机读取").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, recovered) }
        compose.onAllNodesWithText("选择 PNG 图片").assertCountEquals(0)
        compose.onAllNodesWithTag("attachment-picture").assertCountEquals(0)
    }

    @Test fun launchedPickerKeepsActualRegistryOwnerAcrossGlobalRecordFailureAndRecovery() {
        val f = Fixture(); f.open(); f.select()
        val selected = f.controller.state.value!!.selection!!
        val registry = Registry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val local = mutableStateOf(LocalRecordUiState(ready = true, opening = false,
            text = "合成此刻草稿", entries = listOf(entry)))
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                AIyouTheme { AIyouApp(local = local.value, previewDestination = Destination.MEMORIES,
                    previewEntryId = entry.id, attachments = f.controller) }
            }
        }
        compose.runOnIdle { assertEquals(1, registry.codes.size); local.value = local.value.copy(error = "合成迟到读取失败") }
        compose.onNodeWithText("恢复本机读取").assertIsEnabled()
        compose.runOnIdle { local.value = local.value.copy(error = null, opening = false) }
        compose.runOnIdle { registry.dispatchResult(registry.codes.single(), Uri.parse("content://aiyou.synthetic/selected")) }
        compose.runOnIdle {
            assertEquals(1, registry.codes.size)
            assertEquals(selected.requestId, f.controller.state.value!!.selection!!.requestId)
            assertEquals(selected.attachmentId, f.controller.state.value!!.selection!!.attachmentId)
            assertEquals(AttachmentImportPhase.PREPARING, f.controller.state.value!!.selection!!.phase)
            assertEquals(1, f.queue.size)
        }
        // Stops before the explicit synthetic provider/storage boundary; proves actual registry delivery.
    }

    @Test fun restoredCompositionWithNewControllerCannotAdoptOldPickerResult() {
        val old = Fixture(); old.open(); old.select()
        val current = mutableStateOf(old.controller)
        val registry = Registry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val restore = StateRestorationTester(compose)
        restore.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                AttachmentPicker(current.value, current.value.state.collectAsState().value?.selection)
            }
        }
        compose.runOnIdle { assertEquals(1, registry.codes.size) }
        val oldCode = registry.codes.single(); val oldSelection = old.controller.state.value!!.selection!!
        val next = Fixture(); next.open(); next.select()
        val nextSelection = next.controller.state.value!!.selection!!
        assertEquals(oldSelection.requestId, nextSelection.requestId) // numeric counters intentionally restart
        assertNotEquals(oldSelection.pickerKey, nextSelection.pickerKey)
        compose.runOnIdle { old.controller.markDisposed(); old.controller.disposeOnIo(); current.value = next.controller }
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(2, registry.codes.size); registry.dispatchResult(oldCode, Uri.parse("content://aiyou.synthetic/old")) }
        compose.runOnIdle {
            assertEquals(nextSelection.pickerKey, next.controller.state.value!!.selection!!.pickerKey)
            assertEquals(AttachmentImportPhase.PICKING, next.controller.state.value!!.selection!!.phase)
            assertTrue(next.queue.isEmpty())
            registry.dispatchResult(registry.codes.last(), null as Uri?); next.drain()
        }
        compose.runOnIdle { assertNull(next.controller.state.value!!.selection) }
    }
}
