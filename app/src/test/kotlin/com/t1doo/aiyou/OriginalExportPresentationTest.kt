package com.t1doo.aiyou

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.io.*
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Production Compose/controller/registry. Synthetic memory ports; SQL evidence is separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
@OptIn(ExperimentalFoundationApi::class)
class OriginalExportPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = Fixture()
    private val registry = Registry()
    private val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
    private var saved: SaveableStateRegistry? = null
    private val navigation = AtomicReference<OriginalReadingCallbacks>()
    private val observer = object : OriginalReadingObserver {
        override fun onNavigation(callbacks: OriginalReadingCallbacks) { navigation.set(callbacks) }
        override fun onClipboard(clipboard: Clipboard) = Unit
        override fun onSelection(selection: SelectionState) = Unit
    }
    private fun content(doubleFont: Boolean = false) {
        compose.setContent {
            saved = LocalSaveableStateRegistry.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner,
                LocalOriginalReadingObserver provides observer,
                LocalDensity provides Density(density.density, if (doubleFont) 2f else density.fontScale)) {
                AIyouTheme { AIyouApp(local = fixture.state.value, previewDestination = Destination.MEMORIES,
                    exports = fixture.controller, currentRecords = { fixture.state.value }) }
            }
        }
    }
    private fun pump() { compose.runOnIdle { fixture.drain() } }
    private fun preview() {
        compose.onNodeWithTag("export-open").performScrollTo().performClick()
        compose.onNodeWithTag("export-select-one").performScrollTo().performClick()
        compose.onNodeWithTag("export-prepare").performScrollTo().performClick(); pump()
    }
    @Test fun actualDialogRequiresSelectionPreviewAndFreshPlaintextConsentBeforeSystemDocumentIntent() {
        content(); compose.onNodeWithTag("export-open").performScrollTo().performClick()
        compose.onNodeWithTag("export-prepare").assertIsNotEnabled(); assertTrue(fixture.ui().selection.isEmpty())
        compose.onNodeWithTag("export-select-one").performScrollTo().performClick()
        compose.onNodeWithTag("export-format-JSONL").performScrollTo().performClick()
        compose.onNodeWithTag("export-prepare").performScrollTo().performClick(); pump()
        compose.onNodeWithText("这里只预览前 300 字符，导出包含完整正文。").performScrollTo().assertExists()
        compose.onNodeWithTag("export-choose").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("export-risk").performScrollTo().performClick()
        compose.onNodeWithTag("export-choose").performScrollTo().performClick(); pump()
        compose.runOnIdle {
            assertEquals(1, registry.intents.size); val intent = registry.intents.single()
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action); assertEquals("application/x-ndjson", intent.type)
            assertEquals("AIyou-current-originals.jsonl", intent.getStringExtra(Intent.EXTRA_TITLE))
            assertTrue(intent.categories?.contains(Intent.CATEGORY_OPENABLE) == true); assertEquals(0, intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            assertFalse(intent.toString().contains("私密")); assertFalse(fixture.ui().hasFile)
            registry.dispatchResult(registry.codes.single(), null as Uri?)
        }
        compose.runOnIdle { assertEquals(OriginalExportPhase.PREVIEW, fixture.ui().phase); assertFalse(fixture.ui().riskAccepted) }
        compose.onNodeWithTag("export-choose").performScrollTo().assertIsNotEnabled()
    }
    @Test fun sourceChangeShowsConflictAndRequiresExplicitNewPreviewWithoutWritingAnyFile() {
        content(); preview(); compose.onNodeWithTag("export-risk").performScrollTo().performClick()
        compose.runOnIdle { fixture.original = fixture.original.copy(currentRevision = 2, originalText = "合成新原文") }
        compose.onNodeWithTag("export-choose").performScrollTo().performClick(); pump()
        compose.onNodeWithTag("export-error").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(OriginalExportPhase.CHANGED, fixture.ui().phase); assertTrue(registry.codes.isEmpty())
            fixture.state.value = fixture.state.value.copy(entries = listOf(fixture.original)) }
        compose.onNodeWithText("重新预览最新原文").performScrollTo().performClick(); pump()
        compose.onNodeWithTag("export-preview-one").performScrollTo().assertTextEquals("合成新原文")
        compose.runOnIdle { assertFalse(fixture.ui().riskAccepted); assertEquals(2, fixture.ui().snapshot!!.records.single().entry.currentRevision) }
    }
    @Test fun failedPartialFileHasNoAutomaticDeleteAndRequiresSeparateExactTargetConfirmation() {
        fixture.previewAndPick(); val file = FailingDocument(); fixture.controller.picked(fixture.ui().session, fixture.ui().requestKey!!, file); fixture.drain()
        content(); compose.onNodeWithTag("export-error").performScrollTo().assertExists()
        compose.onNodeWithTag("export-delete").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, file.deletions); assertEquals(OriginalExportPhase.FAILED, fixture.ui().phase) }
        compose.onNodeWithText("保留文件").performClick(); compose.runOnIdle { assertEquals(0, file.deletions) }
        compose.onNodeWithTag("export-delete").performScrollTo().performClick()
        compose.onNodeWithTag("export-confirm-delete").performClick(); compose.runOnIdle { assertEquals(0, file.deletions) }
        pump(); compose.runOnIdle { assertEquals(1, file.deletions); assertFalse(fixture.ui().hasFile); assertFalse(fixture.ui().riskAccepted) }
        compose.onNodeWithTag("export-choose").performScrollTo().assertIsNotEnabled()
    }
    @Test @Config(sdk = [35], qualifiers = "w320dp-h1000dp")
    fun narrowDoubleFontKeepsRiskDestinationAndCloseReachableAndHidesPreviewOnReadFailure() {
        content(true)
        compose.onNodeWithTag("export-open").performScrollTo().performClick()
        compose.onNodeWithTag("export-select-one").performScrollTo().performClick()
        compose.runOnIdle { fixture.state.value = fixture.state.value.copy(ready = false) }
        compose.onAllNodesWithTag("export-select-one").assertCountEquals(0)
        compose.onAllNodesWithTag("export-prepare").assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, fixture.ui().selection.size); fixture.state.value = fixture.state.value.copy(ready = true) }
        compose.onNodeWithTag("export-select-one").assertIsOn()
        compose.onNodeWithTag("export-prepare").performScrollTo().performClick(); pump()
        val risk = compose.onNodeWithTag("export-risk").performScrollTo().assertIsEnabled()
        assertTrue(risk.getUnclippedBoundsInRoot().height.value >= 48f); risk.performClick()
        compose.onNodeWithTag("export-choose").performScrollTo().assertIsEnabled()
        compose.runOnIdle { fixture.state.value = fixture.state.value.copy(error = "合成读取失败") }
        compose.onAllNodesWithTag("export-preview-one").assertCountEquals(0)
        compose.onAllNodesWithTag("export-risk").assertCountEquals(0)
        compose.onNodeWithText("本机记录暂不可读取，预览已隐藏；恢复记录后再继续。").performScrollTo().assertExists()
        compose.onNodeWithTag("export-close").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNull(fixture.state.value.originalExport); assertEquals("合成独立草稿", fixture.state.value.text) }
    }
    @Test fun actualRegistryRestorationCannotRelaunchOrDeliverOldResultToNewVmUuid() {
        val old = Fixture().also { it.previewAndPick() }; val active = mutableStateOf(old)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                OriginalExportPicker(active.value.controller, active.value.ui(), true) { true }
            }
        }
        compose.runOnIdle { assertEquals(1, registry.codes.size) }; val oldCode = registry.codes.single(); val oldKey = old.ui().requestKey
        restoration.emulateSavedInstanceStateRestore(); compose.runOnIdle { assertEquals(1, registry.codes.size) }
        val next = Fixture().also { it.previewAndPick() }; assertEquals(old.ui().session, next.ui().session); assertNotEquals(oldKey, next.ui().requestKey)
        compose.runOnIdle { old.controller.dispose(); active.value = next }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(2, registry.codes.size); registry.dispatchResult(oldCode, Uri.parse("content://aiyou.synthetic/old-export")) }
        compose.runOnIdle { assertEquals(OriginalExportPhase.PICKING, next.ui().phase); assertTrue(next.queue.isEmpty())
            registry.dispatchResult(registry.codes.last(), null as Uri?) }
        compose.runOnIdle { assertEquals(OriginalExportPhase.PREVIEW, next.ui().phase); assertFalse(next.ui().riskAccepted) }
    }
    @Test fun rawSavedStateExcludesSnapshotOriginalTagsConsentAndDestinationAndNavigationRetiresPendingWork() {
        content(); preview(); compose.onNodeWithTag("export-risk").performScrollTo().performClick()
        compose.onNodeWithTag("export-choose").performScrollTo().performClick(); pump()
        compose.runOnIdle {
            val ui = fixture.ui(); assertEquals(OriginalExportPhase.PICKING, ui.phase)
            val secrets = listOf("私密原文", "私密标签", ui.snapshot!!.id, ui.requestKey!!,
                "riskAccepted", "originalExport", "content://aiyou.synthetic")
            assertNoPrivateState(checkNotNull(saved).performSave(), secrets)
            val code = registry.codes.single()
            navigation.get().selectDestination(Destination.NOW)
            // Deliver before recomposition: the actual callback must observe revoked navigation.
            registry.dispatchResult(code, Uri.parse("content://aiyou.synthetic/late-export"))
            assertTrue(fixture.queue.isEmpty())
        }
        compose.runOnIdle { assertNull(fixture.state.value.originalExport); assertEquals("合成独立草稿", fixture.state.value.text) }
    }
    private fun assertNoPrivateState(value: Any?, forbidden: List<String>) {
        when (value) {
            null, is Number, is Boolean, is Char -> Unit
            is String -> forbidden.forEach { assertFalse("Private export state entered SavedState", value.contains(it)) }
            // Compose Dialog saves its own window UUID. Check its exact value too; export UUIDs remain forbidden.
            // https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/window/AndroidDialog.android.kt
            is java.util.UUID -> assertNoPrivateState(value.toString(), forbidden)
            is ByteArray -> assertNoPrivateState(value.toString(Charsets.UTF_8), forbidden)
            is Map<*, *> -> value.forEach { (key, child) -> assertNoPrivateState(key, forbidden); assertNoPrivateState(child, forbidden) }
            is Iterable<*> -> value.forEach { assertNoPrivateState(it, forbidden) }
            is Array<*> -> value.forEach { assertNoPrivateState(it, forbidden) }
            is IntArray, is LongArray, is FloatArray, is DoubleArray, is BooleanArray -> Unit
            is android.os.Bundle -> value.keySet().forEach { assertNoPrivateState(it, forbidden); assertNoPrivateState(value.get(it), forbidden) }
            else -> fail("Unexpected SavedState type requires inspection: " + value.javaClass.name)
        }
    }
    private class Registry : ActivityResultRegistry() {
        val codes = mutableListOf<Int>(); val intents = mutableListOf<Intent>()
        override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            codes.add(code); intents.add(contract.createIntent(RuntimeEnvironment.getApplication(), input))
        }
    }
    private class Fixture {
        var original = StoredEntry("one", 1, "合成私密原文🙂".repeat(70), 42, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY)
        private val tags = ManualTagSnapshot("one", 1, listOf("合成私密标签"))
        val state = mutableStateOf(LocalRecordUiState(ready = true, opening = false, supportsOriginalExport = true,
            text = "合成独立草稿", entries = listOf(original)))
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        private val export = object : OriginalExportRepository {
            override fun capture(sources: List<SourceVersion>, snapshotId: String, createdAtMillis: Long, refreshCurrent: Boolean): OriginalExportSnapshot {
                if (!refreshCurrent && sources.single().revision != original.currentRevision.toLong()) throw OriginalExportConflict()
                return OriginalExportSnapshot(snapshotId, createdAtMillis, listOf(OriginalExportRecord(original, OriginalExports.hash(original.originalText), tags)))
            }
            override fun isCurrent(snapshot: OriginalExportSnapshot) = snapshot.records.single().entry == original && snapshot.records.single().tags.version == tags.version
        }
        private val store = object : EntryRepository {
            override val originalExport = export
            override suspend fun readDraft(): TextDraft? = null
            override suspend fun updateDraft(text: String): TextDraft = error("Outside UI port")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Outside UI port")
            override suspend fun entries() = listOf(original)
            override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Outside UI port")
        }
        val controller = OriginalExportController({ state.value }, { state.value = it(state.value) }, { queue.addLast(it); true }, Clock.systemUTC())
        fun ui() = state.value.originalExport!!
        fun drain() { runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(store) } }
        fun previewAndPick() {
            controller.open(); controller.toggle(ui().session, "one", 1); controller.prepare(ui().session); drain()
            val preview = ui(); controller.acceptRisk(preview.session, preview.snapshot!!.id, preview.format, preview.epoch, true)
            controller.choose(preview.session, preview.snapshot.id, preview.format, preview.epoch); drain()
        }
    }
    private class FailingDocument : SelectedExportDocument {
        var deletions = 0
        override fun openForWrite(): OutputStream = object : ByteArrayOutputStream() { override fun close() { throw IOException("Synthetic close failure") } }
        override fun openForRead(): InputStream = error("Never read after failed close")
        override fun delete(): Boolean { deletions++; return true }
    }
}
