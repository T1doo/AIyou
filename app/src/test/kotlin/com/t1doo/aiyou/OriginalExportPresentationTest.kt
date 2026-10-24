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
import androidx.compose.ui.unit.dp
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
import org.robolectric.annotation.GraphicsMode

/** Production Compose/controller/registry and native image decoding; SQL evidence is separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        compose.showNotebookToolsFor("export-open");compose.onNodeWithTag("export-open").performScrollTo().performClick()
        compose.onNodeWithTag("export-select-one").performScrollTo().performClick()
        compose.onNodeWithTag("export-prepare").performScrollTo().performClick(); pump()
    }
    @Test @Config(qualifiers = "w320dp-h640dp")
    fun legacyAndCurrentNullRangeUiNeverClaimsWholeAudioCoverage() {
        RuntimeEnvironment.setFontScale(2f);fixture.includeAudio=true;content()
        val legacy=AudioTranscriptVersion(1,"合成人工转写🙂",50,null)
        val withHistory=listOf(AudioTranscriptVersion(1,"合成历史文字",50,AudioSampleRange(1,3)),AudioTranscriptVersion(2,"合成人工转写🙂",51,null))
        for(history in listOf(listOf(legacy),withHistory)) {
            compose.runOnIdle {fixture.transcriptHistory=history}
            compose.showNotebookToolsFor("export-open");compose.onNodeWithTag("export-open").performScrollTo().performClick()
            compose.onNodeWithTag("export-select-one").performScrollTo().performClick()
            compose.onNodeWithTag("export-format-MEDIA_ZIP").performScrollTo().performClick()
            compose.onNodeWithTag("export-include-transcripts").performScrollTo().assertIsOff().performClick()
            compose.onNodeWithTag("export-prepare").performScrollTo().performClick();pump()
            compose.onNodeWithTag("export-transcript-range-"+Fixture.TRANSCRIPT_ID).performScrollTo()
                .assertTextEquals("未标注片段范围；仅关联此原音。原音总帧数 4（元数据）· 44100 Hz")
            compose.onAllNodes(hasText("整段原音",substring=true),useUnmergedTree=true).assertCountEquals(0)
            compose.runOnIdle {assertNull(fixture.ui().mediaPreview!!.openTranscripts!!.items.single().current.range);assertTrue(registry.intents.isEmpty())}
            compose.onNodeWithTag("export-close").performScrollTo().performClick();compose.waitForIdle()
        }
    }
    @Test @Config(qualifiers = "w320dp-h640dp")
    fun explicitHumanTranscriptScopeAtActualDoubleFontRequiresFreshPlaintextConsentAndStaysOutOfSavedState() {
        RuntimeEnvironment.setFontScale(2f)
        fixture.includeAudio=true;content()
        compose.showNotebookToolsFor("export-open");compose.onNodeWithTag("export-open").performScrollTo().performClick()
        compose.onNodeWithTag("export-select-one").performScrollTo().performClick()
        compose.onNodeWithTag("export-format-MEDIA_ZIP").performScrollTo().performClick()
        compose.onNodeWithTag("export-include-transcripts").performScrollTo().assertIsOff().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("export-include-transcripts").assertIsOn()
        compose.onNodeWithTag("export-prepare").performScrollTo().performClick();pump()
        compose.onNodeWithTag("export-transcript-total").performScrollTo().assertTextContains("当前有效 1 条",substring=true)
        compose.onNodeWithTag("export-transcript-source-"+Fixture.TRANSCRIPT_ID).performScrollTo().assertTextContains("文字版本 1",substring=true)
        val text=compose.onNodeWithTag("export-transcript-text-"+Fixture.TRANSCRIPT_ID).performScrollTo().assertTextEquals("合成人工转写🙂")
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        text.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){assertTrue(it(layouts))}
        assertEquals(2f,layouts.single().layoutInput.density.fontScale,0f);assertFalse(layouts.single().hasVisualOverflow)
        compose.runOnIdle {
            val dialog=checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
            assertEquals(2f,dialog.context.resources.configuration.fontScale,0f)
            val decor=checkNotNull(dialog.window).decorView
            val bitmap=android.graphics.Bitmap.createBitmap(decor.width,decor.height,android.graphics.Bitmap.Config.ARGB_8888)
            try {
                decor.draw(android.graphics.Canvas(bitmap))
                val file=File("build/reports/tests/testDebugUnitTest/open-transcript-export/01-confirm-human-text-2x.png")
                file.parentFile.mkdirs();file.outputStream().use {assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
                File(file.parentFile,"fontscale.json").writeText(org.json.JSONObject().put("source_sha",System.getenv("GITHUB_SHA")?:"local-not-CI")
                    .put("fontScale",layouts.single().layoutInput.density.fontScale).put("hasVisualOverflow",layouts.single().hasVisualOverflow).toString(2))
            } finally {bitmap.recycle()}
        }
        compose.onNodeWithTag("export-choose").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(fixture.ui().includeTranscripts);assertTrue(registry.intents.isEmpty())
            fixture.controller.includeTranscripts(fixture.ui().session,false)
            assertTrue("The confirmed scope cannot change in PREVIEW",fixture.ui().includeTranscripts)
            assertNoPrivateState(checkNotNull(saved).performSave(),listOf("合成人工转写🙂",Fixture.TRANSCRIPT_ID,"openTranscripts","includeTranscripts"))
        }
        compose.onNodeWithTag("export-risk").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("export-choose").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick();pump()
        compose.runOnIdle {assertEquals("application/zip",registry.intents.single().type);registry.dispatchResult(registry.codes.single(),null as Uri?)}
        compose.onNodeWithTag("export-choose").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {assertFalse(fixture.ui().riskAccepted);assertEquals("合成独立草稿",fixture.state.value.text)}
    }
    @Test @Config(qualifiers = "w320dp-h640dp")
    fun narrowAudioZipShowsExactSourceAndSeparatePlaintextConsentWithoutOpeningMedia() {
        fixture.includeAudio = true; fixture.original = fixture.original.copy(originalText = "")
        fixture.state.value = fixture.state.value.copy(entries = listOf(fixture.original))
        content(true)
        compose.showNotebookToolsFor("export-open"); compose.onNodeWithTag("export-open").performScrollTo().performClick()
        compose.onNodeWithTag("export-select-one").performScrollTo().performClick()
        compose.onNodeWithTag("export-format-MEDIA_ZIP").performScrollTo().performClick()
        compose.onNodeWithTag("export-prepare").performScrollTo().performClick(); pump()
        compose.onNodeWithTag("export-audio-total").performScrollTo().assertTextEquals("已认证 1 段原音 · ${fixture.waveBytes.size} 字节")
        compose.onNodeWithTag("export-audio-" + Fixture.AUDIO_ID).performScrollTo()
            .assertTextContains("版本 1", substring = true).assertTextContains(Fixture.AUDIO_ID, substring = true)
        compose.onNodeWithTag("export-choose").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(OriginalExportPhase.PREVIEW, fixture.ui().phase)
            assertEquals(1, fixture.ui().mediaPreview!!.audios.size); assertTrue(fixture.ui().mediaPreview!!.images.isEmpty())
            assertFalse(fixture.ui().riskAccepted); assertTrue(registry.intents.isEmpty())
            assertEquals("合成独立草稿", fixture.state.value.text); assertEquals("", fixture.original.originalText)
        }
        compose.onNodeWithTag("export-risk").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("export-choose").performScrollTo().assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick(); pump()
        compose.runOnIdle {
            assertEquals("application/zip", registry.intents.single().type)
            registry.dispatchResult(registry.codes.single(), null as Uri?)
        }
        compose.onNodeWithTag("export-choose").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertFalse(fixture.ui().riskAccepted); assertFalse(fixture.ui().hasFile); assertEquals("合成独立草稿", fixture.state.value.text) }
    }
    @Test fun imageOnlyZipShowsActualAuthenticatedPictureAndRequiresConsentBeforeZipIntent() {
        fixture.includeImage = true; fixture.original = fixture.original.copy(originalText = "")
        fixture.state.value = fixture.state.value.copy(entries = listOf(fixture.original))
        content(true)
        compose.showNotebookToolsFor("export-open");compose.onNodeWithTag("export-open").performScrollTo().performClick()
        compose.onNodeWithTag("export-select-one").performScrollTo().performClick()
        compose.onNodeWithTag("export-format-MEDIA_ZIP").performScrollTo().performClick()
        compose.onNodeWithTag("export-prepare").performScrollTo().performClick(); pump()
        compose.runOnIdle {
            val ui = fixture.ui()
            assertNull("Authenticated PNG preview failed: ${ui.error}", ui.error)
            assertEquals(OriginalExportPhase.PREVIEW, ui.phase)
            assertEquals(1, ui.mediaPreview!!.images.size)
        }
        compose.onNodeWithTag("export-image-total").performScrollTo().assertExists()
        compose.onNodeWithTag("export-view-" + Fixture.IMAGE_ID).performScrollTo().performClick(); pump()
        compose.onNodeWithTag("export-picture").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            val picture = fixture.ui().picture as PlatformAttachmentPicture
            assertEquals(1, picture.width); assertEquals(1, picture.height)
            val pixels = IntArray(1); picture.image.readPixels(pixels)
            assertEquals(0xff225599.toInt(), pixels.single())
        }
        compose.onNodeWithTag("export-choose").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("export-risk").performScrollTo().performClick()
        compose.onNodeWithTag("export-choose").performScrollTo().performClick(); pump()
        compose.runOnIdle {
            assertEquals("application/zip", registry.intents.single().type)
            assertEquals("AIyou-current-originals.zip", registry.intents.single().getStringExtra(Intent.EXTRA_TITLE))
            assertEquals(1, fixture.ui().mediaPreview!!.images.size)
            registry.dispatchResult(registry.codes.single(), null as Uri?)
        }
        compose.runOnIdle { assertFalse(fixture.ui().riskAccepted); assertEquals(OriginalExportPhase.PREVIEW, fixture.ui().phase) }
    }
    @Test fun mixedZipMalformedAuthenticatedOriginalFailsBeforePreviewOrSystemPicker() {
        fixture.includeImage = true
        fixture.imageBytes = Fixture.PNG.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        content()
        compose.showNotebookToolsFor("export-open");compose.onNodeWithTag("export-open").performScrollTo().performClick()
        compose.onNodeWithTag("export-select-one").performScrollTo().performClick()
        compose.onNodeWithTag("export-format-MEDIA_ZIP").performScrollTo().performClick()
        compose.onNodeWithTag("export-prepare").performScrollTo().performClick(); pump()
        compose.onNodeWithTag("export-error").performScrollTo().assertExists()
        compose.onAllNodesWithTag("export-risk").assertCountEquals(0)
        compose.runOnIdle { assertEquals(OriginalExportPhase.FAILED, fixture.ui().phase); assertNull(fixture.ui().snapshot); assertTrue(registry.intents.isEmpty()) }
    }
    @Test fun actualDialogRequiresSelectionPreviewAndFreshPlaintextConsentBeforeSystemDocumentIntent() {
        content(); compose.showNotebookToolsFor("export-open");compose.onNodeWithTag("export-open").performScrollTo().performClick()
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
        compose.showNotebookToolsFor("export-open");compose.onNodeWithTag("export-open").performScrollTo().performClick()
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
            // Inspect the actual saved value, including framework mutable-state wrappers.
            is State<*> -> assertNoPrivateState(value.value, forbidden)
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
        var transcriptHistory=listOf(AudioTranscriptVersion(1,"合成人工转写🙂",50,AudioSampleRange(1,3)))
        var includeImage = false
        var includeAudio = false
        val waveBytes = LocalPcmAudio.encode(shortArrayOf(0x1234, -2, 1000, -1000))
        var imageBytes = PNG.copyOf()
        var original = StoredEntry("one", 1, "合成私密原文🙂".repeat(70), 42, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY)
        private val tags = ManualTagSnapshot("one", 1, listOf("合成私密标签"))
        val state = mutableStateOf(LocalRecordUiState(ready = true, opening = false, supportsOriginalExport = true,
            text = "合成独立草稿", entries = listOf(original)))
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        private val export = object : OriginalExportRepository {
            override fun capture(sources: List<SourceVersion>, snapshotId: String, createdAtMillis: Long, refreshCurrent: Boolean): OriginalExportSnapshot {
                if (!refreshCurrent && sources.single().revision != original.currentRevision.toLong()) throw OriginalExportConflict()
                return OriginalExportSnapshot(snapshotId, createdAtMillis, listOf(OriginalExportRecord(original, OriginalExports.hash(original.originalText), tags,
                    imageCount = if (includeImage) 1 else 0, audioCount = if (includeAudio) 1 else 0)))
            }
            override fun isCurrent(snapshot: OriginalExportSnapshot) = snapshot.records.single().entry == original && snapshot.records.single().tags.version == tags.version &&
                snapshot.records.single().imageCount == (if (includeImage) 1 else 0) && snapshot.records.single().audioCount == (if (includeAudio) 1 else 0)
        }
        private val media = object : RecordMediaPackageRepository {
            override suspend fun preview(snapshot: OriginalExportSnapshot) = RecordMediaPreview(snapshot, if (includeImage) listOf(
                RecordPackageImage("one", original.currentRevision, IMAGE_ID, "image/png", imageBytes.size.toLong(),
                    java.security.MessageDigest.getInstance("SHA-256").digest(imageBytes).joinToString("") { "%02x".format(it) })) else emptyList(),
                audios = if (includeAudio) listOf(RecordPackageAudio("one", original.currentRevision, AUDIO_ID, "audio/wav", waveBytes.size.toLong(),
                    java.security.MessageDigest.getInstance("SHA-256").digest(waveBytes).joinToString("") { "%02x".format(it) })) else emptyList())
            override suspend fun previewOpenZipWithTranscripts(snapshot:OriginalExportSnapshot,active:()->Boolean):RecordMediaPreview {
                check(active());val base=preview(snapshot);val a=base.audios.single()
                val anchor=AudioTranscriptSource(AttachmentIntent(a.attachmentId,a.sourceId,a.sourceRevision,a.mediaType,a.sha256,a.byteSize),
                    snapshot.records.single().contentHash,0,original.capturedAtMillis,original.captureTimeZone,original.policy,4)
                val scope=object:OpenTranscriptScope {override val items=listOf(AudioTranscriptSnapshot(TRANSCRIPT_ID,anchor,transcriptHistory.size.toLong(),false,transcriptHistory))}
                return RecordMediaPreview(snapshot,base.images,audios=base.audios,openTranscripts=scope)
            }
            override suspend fun isCurrent(preview: RecordMediaPreview) = export.isCurrent(preview.snapshot)
            override suspend fun readOpenImage(preview: RecordMediaPreview, image: RecordPackageImage) = imageBytes.copyOf()
            override suspend fun exportOpenZip(preview: RecordMediaPreview) = OpenRecordZipCodec.prepare(preview, { if (it is RecordPackageAudio) waveBytes.copyOf() else imageBytes.copyOf() }, { isCurrent(preview) })
            override suspend fun seal(preview: RecordMediaPreview, password: CharArray): PreparedRecordMediaPackage = error("Unrelated")
            override suspend fun open(source: () -> InputStream, password: CharArray): VerifiedRecordPackage = error("Unrelated")
            override suspend fun restore(verified: VerifiedRecordPackage, newIds: List<String>, importedAtMillis: Long): RecordPackageRestoreResult = error("Unrelated")
            override suspend fun recover() = Unit
        }
        private val store = object : EntryRepository {
            override val originalExport = export
            override val mediaPackages = media
            override suspend fun readDraft(): TextDraft? = null
            override suspend fun updateDraft(text: String): TextDraft = error("Outside UI port")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Outside UI port")
            override suspend fun entries() = listOf(original)
            override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Outside UI port")
        }
        val controller = OriginalExportController({ state.value }, { state.value = it(state.value) }, { queue.addLast(it); true }, Clock.systemUTC(), PlatformImageDecoder::thumbnail)
        fun ui() = state.value.originalExport!!
        fun drain() { runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(store) } }
        fun previewAndPick() {
            controller.open(); controller.toggle(ui().session, "one", 1); controller.prepare(ui().session); drain()
            val preview = ui(); controller.acceptRisk(preview.session, preview.snapshot!!.id, preview.format, preview.epoch, true)
            controller.choose(preview.session, preview.snapshot.id, preview.format, preview.epoch); drain()
        }
        companion object {
            const val TRANSCRIPT_ID="00000000-0000-4000-8000-000000000123"
            const val IMAGE_ID = "00000000-0000-4000-8000-000000000001"
            const val AUDIO_ID = "00000000-0000-4000-8000-000000000002"
            val PNG = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNQCp35HwADvQIQaYgMTgAAAABJRU5ErkJggg==")
        }
    }
    private class FailingDocument : SelectedExportDocument {
        var deletions = 0
        override fun openForWrite(): OutputStream = object : ByteArrayOutputStream() { override fun close() { throw IOException("Synthetic close failure") } }
        override fun openForRead(): InputStream = error("Never read after failed close")
        override fun delete(): Boolean { deletions++; return true }
    }
}
