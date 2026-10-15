package com.t1doo.aiyou

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.t1doo.aiyou.data.JcaRecordPackageCipher
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.io.*
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Compose/controller/JCA with synthetic ports; actual SQL is verified in the host suite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
@OptIn(ExperimentalFoundationApi::class)
class RecordPackagePresentationTest {
    @get:Rule val compose = createComposeRule()
    private var fixture = Fixture()
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
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (doubleFont) 2f else density.fontScale),
                LocalOriginalReadingObserver provides observer) {
                AIyouTheme { AIyouApp(local = fixture.state.value, previewDestination = Destination.MEMORIES,
                    recordPackages = fixture.controller, recordPackagePicker = fixture.picker, currentRecords = { fixture.state.value }) }
            }
        }
    }
    private fun click(tag: String) {
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun input(tag: String, value: String) {
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performTextInput(value)
    }
    private fun pump() { compose.runOnIdle { fixture.drain() } }
    private fun open() { compose.onNodeWithTag("package-open").performScrollTo().performClick() }
    private fun preview() { open(); click("package-export"); click("package-select:one"); click("package-preview"); pump() }
    @Test fun actualFullPreviewRequiresScopeAndTwoMatchingSecretInputsBeforeExportPicker() {
        content(); preview()
        compose.onNodeWithTag("package-list").performScrollToNode(hasText(fixture.original.originalText))
        compose.onNodeWithText(fixture.original.originalText).assertExists()
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag("package-encrypt"))
        compose.onNodeWithTag("package-encrypt").assertIsNotEnabled()
        click("package-scope")
        input("package-password", PASSWORD)
        input("package-password-again", "another independent password")
        click("package-encrypt"); pump()
        compose.runOnIdle { assertEquals(0, fixture.picker.launches); assertNotNull(fixture.ui.error) }
        input("package-password", PASSWORD)
        input("package-password-again", PASSWORD)
        click("package-encrypt"); pump()
        compose.runOnIdle { assertEquals(1, fixture.picker.launches); assertTrue(fixture.picker.export)
            assertEquals(RecordPackagePhase.PICKING_EXPORT, fixture.ui.phase) }
    }
    @Test fun restoreAuthenticatesAndPreviewsBeforeDistinctConfirmationAndNoImplicitWrite() {
        content(); open(); click("package-restore")
        compose.runOnIdle { fixture.picker.deliver(fixture.encryptedDocument()) }
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag("package-password"))
        input("package-password", PASSWORD); click("package-authenticate"); pump()
        compose.runOnIdle { assertEquals(0, fixture.imports); assertEquals(RecordPackagePhase.RESTORE_PREVIEW, fixture.ui.phase) }
        compose.onNodeWithTag("package-list").performScrollToNode(hasText(fixture.original.originalText))
        compose.onNodeWithText(fixture.original.originalText).assertExists()
        click("package-confirm"); compose.runOnIdle { assertEquals(0, fixture.imports) }; pump()
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag("package-notice"))
        compose.onNodeWithTag("package-notice").assertTextContains("新副本", substring = true)
        compose.runOnIdle { assertEquals(1, fixture.imports); assertEquals(2, fixture.state.value.entries.size)
            assertEquals("合成独立草稿", fixture.state.value.text) }
    }
    @Test fun wrongPassphraseShowsFiniteFailureWithoutPreviewOrImport() {
        content(); open(); click("package-restore")
        compose.runOnIdle { fixture.picker.deliver(fixture.encryptedDocument()) }
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag("package-password"))
        input("package-password", "wrong independent password")
        click("package-authenticate"); pump()
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag("package-error"))
        compose.onNodeWithTag("package-error").assertExists()
        compose.onAllNodesWithTag("package-confirm").assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, fixture.imports); assertNull(fixture.ui.verified) }
    }
    @Test fun rawSavedStateAndRecreationNeverContainPassphrasesOriginalsTagsManifestOrPickerIdentity() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            saved = LocalSaveableStateRegistry.current
            AIyouTheme { AIyouApp(local = fixture.state.value, previewDestination = Destination.MEMORIES,
                recordPackages = fixture.controller, recordPackagePicker = fixture.picker, currentRecords = { fixture.state.value }) }
        }
        preview(); click("package-scope")
        input("package-password", PASSWORD)
        input("package-password-again", PASSWORD)
        compose.runOnIdle { assertPrivateAbsent(saved!!.performSave(), listOf(PASSWORD, "私密原文", "私密标签", fixture.ui.snapshot!!.id,
            "scopeAccepted", "content://", "recordPackage")) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertPrivateAbsent(saved!!.performSave(), listOf(PASSWORD, "私密原文", "私密标签"))
        }
        click("package-encrypt"); pump()
        compose.runOnIdle { assertEquals(0, fixture.picker.launches); assertNotNull(fixture.ui.error) }
        input("package-password", PASSWORD)
        input("package-password-again", PASSWORD)
        click("package-encrypt"); pump()
        compose.runOnIdle {
            assertEquals(RecordPackagePhase.PICKING_EXPORT, fixture.ui.phase)
            assertPrivateAbsent(saved!!.performSave(), listOf(PASSWORD, fixture.ui.snapshot!!.id, fixture.ui.requestKey!!,
                "私密原文", "私密标签", "manifest"))
            fixture.controller.cancel(fixture.ui.session)
        }
        click("package-restore")
        compose.runOnIdle {
            val key = fixture.ui.requestKey!!
            assertPrivateAbsent(saved!!.performSave(), listOf(key, PASSWORD, "content://"))
            fixture.picker.deliver(fixture.encryptedDocument())
        }
        compose.runOnIdle {
            assertEquals(RecordPackagePhase.RESTORE_PASSWORD, fixture.ui.phase)
            assertPrivateAbsent(saved!!.performSave(), listOf(PASSWORD, "content://", "私密原文"))
        }
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag("package-password"))
        input("package-password", PASSWORD); click("package-authenticate"); pump()
        compose.runOnIdle {
            assertEquals(RecordPackagePhase.RESTORE_PREVIEW, fixture.ui.phase)
            assertPrivateAbsent(saved!!.performSave(), listOf(PASSWORD, fixture.ui.verified!!.manifest.backupId,
                fixture.ui.verified!!.digest, "私密原文", "私密标签", "manifest"))
        }
    }
    @Test fun actualNavigationRetiresRetainedPickerAndConfirmBeforeRecompositionWithoutOpeningOrImporting() {
        content(); open(); click("package-restore")
        val late = Document(byteArrayOf())
        compose.runOnIdle {
            val retained = fixture.picker.retained()
            navigation.get().selectDestination(Destination.NOW)
            retained(late) // Real retained callback, not a cleared fake picker slot.
            assertEquals(0, late.reads); assertEquals(0, late.writes); assertTrue(fixture.queue.isEmpty())
            assertNull(fixture.state.value.recordPackage)
        }
        compose.runOnIdle { navigation.get().selectDestination(Destination.MEMORIES) }
        open(); click("package-restore")
        compose.runOnIdle { fixture.picker.deliver(fixture.encryptedDocument()) }
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag("package-password"))
        input("package-password", PASSWORD); click("package-authenticate"); pump()
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag("package-confirm"))
        val retainedConfirm = compose.onNodeWithTag("package-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            navigation.get().selectDestination(Destination.NOW)
            retainedConfirm()
            assertEquals(0, fixture.imports); assertTrue(fixture.queue.isEmpty()); assertNull(fixture.state.value.recordPackage)
        }
    }
    @Test @Config(sdk = [35], qualifiers = "w320dp-h1000dp")
    fun narrowDoubleFontKeepsConfirmationReachableAndReadFailureHidesOriginalAndSecrets() {
        content(true); preview(); click("package-scope")
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag("package-encrypt"))
        compose.onNodeWithTag("package-encrypt").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { fixture.state.value = fixture.state.value.copy(error = "合成本机读取失败") }
        compose.onAllNodesWithText(fixture.original.originalText).assertCountEquals(0)
        compose.onAllNodesWithTag("package-password").assertCountEquals(0)
        compose.onAllNodesWithTag("package-encrypt").assertCountEquals(0)
        click("package-cancel")
        compose.runOnIdle { assertEquals(RecordPackagePhase.CANCELLED, fixture.ui.phase); assertEquals("合成独立草稿", fixture.state.value.text) }
    }
    @Test @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun imageCountBytesAndConfirmationRemainReachableAtTwoHundredPercentFont() {
        fixture = Fixture(true); content(true); preview()
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag("package-image-summary"))
        compose.onNodeWithTag("package-image-summary").assertTextContains("1 张原图",substring=true).assertTextContains("1024 字节",substring=true)
        click("package-scope")
        input("package-password",PASSWORD); input("package-password-again",PASSWORD)
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag("package-encrypt"))
        compose.onNodeWithTag("package-encrypt").assertIsDisplayed().assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        click("package-cancel"); compose.runOnIdle { assertEquals(0,fixture.imports); assertNull(fixture.ui.snapshot) }
    }
    @Test @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun imageRestorePreviewRequiresSeparateExplicitConfirmationAtLargeFont() {
        fixture = Fixture(true); content(true); open(); click("package-restore")
        compose.runOnIdle { fixture.picker.deliver(fixture.encryptedDocument()) }
        input("package-password",PASSWORD); click("package-authenticate"); pump()
        compose.runOnIdle { assertEquals(1,fixture.ui.verified!!.images.size); assertEquals(0,fixture.imports) }
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag("package-confirm"))
        compose.onNodeWithTag("package-confirm").assertIsDisplayed().assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        click("package-confirm"); pump()
        compose.runOnIdle { assertEquals(1,fixture.imports); assertEquals(RecordPackagePhase.DONE,fixture.ui.phase) }
    }
    private fun assertPrivateAbsent(value: Any?, forbidden: List<String>) {
        when (value) {
            null, is Number, is Boolean, is Char -> Unit
            is String -> forbidden.forEach { assertFalse("Private package state entered SavedState", value.contains(it)) }
            is UUID -> assertPrivateAbsent(value.toString(), forbidden)
            is State<*> -> assertPrivateAbsent(value.value, forbidden)
            is ByteArray -> assertPrivateAbsent(value.toString(Charsets.UTF_8), forbidden)
            is Map<*, *> -> value.forEach { (k,v) -> assertPrivateAbsent(k, forbidden); assertPrivateAbsent(v, forbidden) }
            is Iterable<*> -> value.forEach { assertPrivateAbsent(it, forbidden) }
            is Array<*> -> value.forEach { assertPrivateAbsent(it, forbidden) }
            is IntArray, is LongArray, is FloatArray, is DoubleArray, is BooleanArray -> Unit
            is android.os.Bundle -> value.keySet().forEach { assertPrivateAbsent(it, forbidden); @Suppress("DEPRECATION") assertPrivateAbsent(value.get(it), forbidden) }
            else -> fail("Uninspected SavedState type: " + value.javaClass.name)
        }
    }
    private class Picker : RecordPackagePicker {
        var launches = 0; var export = false; private var callback: ((SelectedRecordPackageDocument?) -> Unit)? = null
        override fun launch(export: Boolean, requestKey: String, callback: (SelectedRecordPackageDocument?) -> Unit): Boolean {
            launches++; this.export = export; this.callback = callback; return true
        }
        override fun cancel(requestKey: String) { callback = null }
        fun deliver(document: SelectedRecordPackageDocument?) { callback?.invoke(document); callback = null }
        fun retained() = checkNotNull(callback)
    }
    private class Fixture(val mediaEnabled: Boolean = false) {
        val original = StoredEntry("one", 1, "合成私密原文😀".repeat(60) + "完整预览尾部", 42, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY)
        val tags = ManualTagSnapshot("one", 1, listOf("合成私密标签"))
        val state = mutableStateOf(LocalRecordUiState(ready = true, opening = false, supportsRecordPackages = true,
            text = "合成独立草稿", entries = listOf(original)))
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>(); val picker = Picker(); var imports = 0
        private var entries = listOf(original)
        private val store = object : EntryRepository {
            override val originalExport = object : OriginalExportRepository {
                override fun capture(sources: List<SourceVersion>, snapshotId: String, createdAtMillis: Long, refreshCurrent: Boolean) =
                    OriginalExportSnapshot(snapshotId, createdAtMillis, listOf(OriginalExportRecord(original, OriginalExports.hash(original.originalText), tags)))
                override fun isCurrent(snapshot: OriginalExportSnapshot) = snapshot.records.single().entry == original
            }
            override val mediaPackages: RecordMediaPackageRepository? = if (!mediaEnabled) null else object : RecordMediaPackageRepository {
                private fun images() = listOf(RecordPackageImage("one",1,"00000000-0000-0000-0000-000000000001","image/png",1024,"a".repeat(64)))
                override suspend fun preview(snapshot: OriginalExportSnapshot) = RecordMediaPreview(snapshot,images())
                override suspend fun isCurrent(preview: RecordMediaPreview) = true
                override suspend fun seal(preview: RecordMediaPreview,password: CharArray): PreparedRecordMediaPackage {
                    password.fill('\u0000'); error("Not exercised by this preview-only UI port")
                }
                override suspend fun open(source: () -> InputStream,password: CharArray): VerifiedRecordPackage {
                    val bytes = source().use { it.readBytes() }; val base = try { JcaRecordPackageCipher().open(bytes,password) } finally { bytes.fill(0) }
                    return object : VerifiedRecordPackage {
                        override val manifest = base.manifest; override val digest = base.digest; override val records = base.records
                        override val images = images()
                    }
                }
                override suspend fun restore(verified: VerifiedRecordPackage,newIds: List<String>,importedAtMillis: Long) =
                    recordPackages.restore(verified,newIds,importedAtMillis)
                override suspend fun recover() = Unit
            }
            override val manualTags = object : ManualTagRepository {
                override fun read(entryId: String) = tags
                override fun snapshots() = mapOf("one" to tags)
                override fun replace(mutation: ManualTagMutation): ManualTagSnapshot = error("Outside UI port")
            }
            override val recordPackages = object : RecordPackageRepository {
                override fun restore(verified: VerifiedRecordPackage, newIds: List<String>, importedAtMillis: Long): RecordPackageRestoreResult {
                    imports++; val r = verified.records.single()
                    entries = entries + StoredEntry(newIds.single(), 1, r.originalText, r.capturedAtMillis, r.captureTimeZone, ProcessingPolicy.AI_NONE)
                    return RecordPackageRestoreResult(newIds, importedAtMillis, false)
                }
                override fun origins(): Map<String, RecordPackageOrigin> = emptyMap()
            }
            override suspend fun entries() = entries
            override suspend fun readDraft(): TextDraft? = null
            override suspend fun updateDraft(text: String): TextDraft = error("Outside UI port")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Outside UI port")
            override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Outside UI port")
        }
        val controller = RecordPackageController({ state.value }, { state.value = it(state.value) }, { queue.add(it); true }, JcaRecordPackageCipher(), Clock.systemUTC())
        val ui get() = checkNotNull(state.value.recordPackage)
        fun drain() = runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(store) }
        fun encryptedDocument(): Document {
            val snapshot = store.originalExport.capture(listOf(SourceVersion("one", 1)), UUID.randomUUID().toString(), 10)
            val payload = JcaRecordPackageCipher().seal(snapshot, PASSWORD.toCharArray())
            val bytes = payload.copyBytes(); payload.release()
            return Document(bytes)
        }
    }
    private class Document(private val bytes: ByteArray) : SelectedRecordPackageDocument {
        var reads = 0; var writes = 0
        override fun openForRead(): InputStream { reads++; return ByteArrayInputStream(bytes) }
        override fun openForWrite(): OutputStream { writes++; return ByteArrayOutputStream() }
        override fun toString() = "SyntheticSelectedDocument(redacted)"
    }
    companion object { const val PASSWORD = " synthetic UI recovery password! " }
}
