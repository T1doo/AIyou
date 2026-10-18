package com.t1doo.aiyou

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.data.JcaRecordPackageCipher
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.io.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual App + package controller at 320 dp/double font. Controlled ports; host tests cover AEAD/SQL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class RecordHistoryPackagePresentationTest {
    @get:Rule val compose = createComposeRule()
    private val sourceId = "synthetic-history-source"
    private val currentText = "合成当前原文"
    private val oldText = "合成后来删除的旧文字"
    private val source = StoredEntry(sourceId, 2, currentText, 5, "UTC", ProcessingPolicy.LOCAL_ONLY)
    private val record = RecordPackageOriginal(sourceId, 2, 5, "UTC", currentText, OriginalExports.hash(currentText), 0, emptyList(),
        history = listOf(RecordPackageRevision(1, oldText, OriginalExports.hash(oldText), 5),
            RecordPackageRevision(2, currentText, OriginalExports.hash(currentText), 4)))
    private val state = mutableStateOf(LocalRecordUiState(ready = true, opening = false, supportsRecordPackages = true,
        entries = listOf(source), text = "合成未提交输入"))
    private val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
    private var registry: SaveableStateRegistry? = null
    private var historyPreviews = 0
    private var currentPreviews = 0
    private var restores = 0
    private var seals = 0
    private var authenticated = 0
    private var restored = false
    private var newId: String? = null
    private var editMode = false
    private val editDraft = OriginalEditDraft("00000000-0000-0000-0000-000000000077",sourceId,2,1,1,currentText,"合成独立编辑未提交全文")
    private val restoredEdits = mutableListOf<OriginalEditDraft>()
    private val verified = object : VerifiedRecordPackage {
        override val manifest = RecordPackageManifest(UUID.randomUUID().toString(), 50, 1)
        override val records = listOf(record)
        override val digest = "b".repeat(64)
        override val includesHistory = true
        override val editDrafts get() = if (editMode) listOf(editDraft) else null
    }
    private val packages = object : RecordPackageRepository {
        override fun restore(verified: VerifiedRecordPackage, newIds: List<String>, importedAtMillis: Long): RecordPackageRestoreResult {
            restores++; val replayed = restored
            if (!restored) { newId = newIds.single(); restored = true
                if(editMode) restoredEdits += editDraft.copy(id=UUID.randomUUID().toString(),entryId=newIds.single()) }
            return RecordPackageRestoreResult(listOf(checkNotNull(newId)), 100, replayed)
        }
        override fun origins() = emptyMap<String, RecordPackageOrigin>()
    }
    private val owner = object : EntryRepository {
        override val originalExport = object : OriginalExportRepository {
            override fun capture(sources: List<SourceVersion>, snapshotId: String, createdAtMillis: Long, refreshCurrent: Boolean): OriginalExportSnapshot {
                check(sources == listOf(SourceVersion(sourceId, 2)))
                return OriginalExportSnapshot(snapshotId, createdAtMillis, listOf(OriginalExportRecord(source, OriginalExports.hash(currentText), ManualTagSnapshot(sourceId, 0, emptyList()))))
            }
            override fun isCurrent(snapshot: OriginalExportSnapshot) = snapshot.records.single().entry == source
        }
        override val mediaPackages = object : RecordMediaPackageRepository {
            override suspend fun preview(snapshot: OriginalExportSnapshot): RecordMediaPreview {
                currentPreviews++; return RecordMediaPreview(snapshot, emptyList())
            }
            override suspend fun previewHistory(snapshot: OriginalExportSnapshot, includeActivities: Boolean): RecordMediaPreview {
                check(!includeActivities); historyPreviews++; return RecordMediaPreview(snapshot, emptyList(), history = listOf(record))
            }
            override suspend fun previewEditDrafts(snapshot: OriginalExportSnapshot, includeActivities: Boolean): RecordMediaPreview {
                check(!includeActivities);return RecordMediaPreview(snapshot,emptyList(),history=listOf(record),editDrafts=listOf(editDraft))
            }
            override suspend fun isCurrent(preview: RecordMediaPreview) = true
            override suspend fun seal(preview: RecordMediaPreview, password: CharArray): PreparedRecordMediaPackage {
                password.fill('\u0000'); seals++; error("Presentation test must not write a file")
            }
            override suspend fun open(source: () -> InputStream, password: CharArray): VerifiedRecordPackage {
                try { source().use { check(it.read() == 1) }; authenticated++; return verified } finally { password.fill('\u0000') }
            }
            override suspend fun restore(verified: VerifiedRecordPackage, newIds: List<String>, importedAtMillis: Long) = packages.restore(verified, newIds, importedAtMillis)
            override suspend fun recover() = Unit
        }
        override val recordPackages = packages
        override val editDrafts = object : OriginalEditDraftStore {
            override suspend fun read(entryId:String)=restoredEdits.singleOrNull {it.entryId==entryId}
            override suspend fun pending()=OriginalEditDraftIndex(restoredEdits.map {OriginalEditDraftRef(it.id,it.entryId,it.baseRevision)})
            override suspend fun write(expected:OriginalEditDraft?,next:OriginalEditDraft):OriginalEditDraft=error("Presentation port must not edit")
        }
        override val manualTags = object : ManualTagRepository {
            override fun read(entryId: String) = ManualTagSnapshot(entryId, 0, emptyList())
            override fun snapshots() = (listOf(sourceId) + listOfNotNull(newId)).associateWith { ManualTagSnapshot(it, 0, emptyList()) }
            override fun replace(mutation: ManualTagMutation): ManualTagSnapshot = error("Not requested")
        }
        override suspend fun entries() = listOf(source) + listOfNotNull(newId?.let { source.copy(id = it, policy = ProcessingPolicy.AI_NONE) })
        override suspend fun readDraft(): TextDraft? = null
        override suspend fun updateDraft(text: String): TextDraft = error("Not requested")
        override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Not requested")
        override suspend fun revisions(entryId: String): List<OriginalRevision> = error("Not requested")
        override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Not requested")
    }
    private val controller = RecordPackageController({ state.value }, { change -> state.value = change(state.value) }, { queue.add(it); true },
        JcaRecordPackageCipher(), Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC))
    private var picked: ((SelectedRecordPackageDocument?) -> Unit)? = null
    private val picker = object : RecordPackagePicker {
        override fun launch(export: Boolean, requestKey: String, callback: (SelectedRecordPackageDocument?) -> Unit): Boolean {
            assertFalse(export); picked = callback; return true
        }
        override fun cancel(requestKey: String) = Unit
    }
    @After fun close() { controller.dispose() }
    private fun content() { compose.setContent {
        val saved = LocalSaveableStateRegistry.current; SideEffect { registry = saved }
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { AIyouTheme {
            AIyouApp(local = state.value, previewDestination = Destination.MEMORIES, currentRecords = { state.value }, recordPackages = controller, recordPackagePicker = picker)
        } }
    } }
    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun click(tag: String) { node(tag).assertIsEnabled().performClick() }
    private fun pump() { compose.runOnIdle { runBlocking { while (queue.isNotEmpty()) queue.removeFirst()(owner) } } }
    private fun open() { compose.onNodeWithTag("package-open").performScrollTo().performClick() }

    @Test fun explicitHistoryAtDoubleFontPreviewsEveryVersionAndDeletedTextWarningBeforeScopeConfirmation() {
        content(); open(); click("package-export")
        node("package-include-history").assertIsOff()
        click("package-select:$sourceId"); click("package-include-history")
        node("package-history-warning").assertExists(); assertEquals(0, historyPreviews)
        click("package-preview"); pump()
        assertEquals(1, historyPreviews); assertEquals(0, currentPreviews)
        node("package-history-summary").assertExists()
        node("package-history-record:$sourceId:1").assertExists()
        compose.onNodeWithText(oldText).assertExists()
        node("package-history-record:$sourceId:2").assertExists()
        compose.onNodeWithText(currentText).assertExists()
        node("package-encrypt").assertIsNotEnabled(); assertEquals(0, seals); assertEquals(0, restores)
        click("package-scope"); node("package-encrypt").assertIsEnabled()
        compose.runOnIdle {
            val saved = checkNotNull(registry).performSave().toString()
            assertFalse(saved.contains(oldText)); assertFalse(saved.contains(currentText)); assertFalse(saved.contains("合成未提交输入"))
        }
        click("package-cancel"); assertEquals("合成未提交输入", state.value.text)
        assertEquals(0, seals); assertEquals(0, restores)
    }

    @Test fun authenticatedHistoryShowsAllVersionsBeforeSeparateRestoreConfirmationCreatesNewCopy() {
        content(); open(); click("package-restore")
        compose.runOnIdle { checkNotNull(picked)(object : SelectedRecordPackageDocument {
            override fun openForRead() = byteArrayOf(1).inputStream()
            override fun openForWrite(): OutputStream = error("Restore must not write the selected document")
        }) }
        node("package-password").performTextInput("synthetic history recovery 42"); click("package-authenticate"); pump()
        assertEquals(1, authenticated); assertEquals(0, restores)
        node("package-restore-history-summary").assertExists()
        node("package-history-record:$sourceId:1").assertExists(); compose.onNodeWithText(oldText).assertExists()
        node("package-history-record:$sourceId:2").assertExists(); compose.onNodeWithText(currentText).assertExists()
        node("package-confirm").assertHeightIsAtLeast(48.dp); click("package-confirm"); assertEquals(0, restores); pump()
        assertEquals(1, restores); assertEquals(RecordPackagePhase.DONE, state.value.recordPackage!!.phase)
        assertEquals(2, state.value.entries.size); assertEquals(source, state.value.entries.single { it.id == sourceId })
        val restoredEntry = state.value.entries.single { it.id != sourceId }
        assertEquals(2, restoredEntry.currentRevision); assertEquals(ProcessingPolicy.AI_NONE, restoredEntry.policy)
        assertEquals("合成未提交输入", state.value.text); assertEquals(0, seals)
    }

    @Test fun originalEditScopeDefaultsOffRequiresFullHistoryAndShowsFullDraftBeforeConsentAtDoubleFont() {
        content();open();click("package-export");node("package-include-edit-drafts").assertIsOff()
        click("package-select:$sourceId");click("package-include-edit-drafts")
        node("package-include-history").assertIsOn();click("package-preview");pump()
        node("package-edit-draft-summary").assertTextContains("1 份",substring=true)
        node("package-edit-draft:$sourceId").assertExists();compose.onNodeWithText(editDraft.text).assertExists()
        node("package-encrypt").assertIsNotEnabled();assertEquals(0,seals);assertEquals(0,restores)
        compose.runOnIdle {val saved=checkNotNull(registry).performSave().toString();assertFalse(saved.contains(editDraft.text));assertFalse(saved.contains("合成未提交输入"))}
        click("package-cancel");assertEquals("合成未提交输入",state.value.text);assertTrue(restoredEdits.isEmpty())
    }

    @Test fun authenticatedOriginalEditsRequireSeparateConfirmationAndPublishRecoveryEntryWithoutSavingOriginal() {
        editMode=true;content();open();click("package-restore")
        compose.runOnIdle {checkNotNull(picked)(object:SelectedRecordPackageDocument {
            override fun openForRead()=byteArrayOf(1).inputStream()
            override fun openForWrite():OutputStream=error("Read only selected package")
        })}
        node("package-password").performTextInput("synthetic edit migration 42");click("package-authenticate");pump()
        node("package-edit-draft-summary").assertExists();node("package-edit-draft:$sourceId").assertExists()
        compose.onNodeWithText(editDraft.text).assertExists();assertEquals(0,restores);assertTrue(state.value.pendingEdits.isEmpty())
        node("package-confirm").assertHeightIsAtLeast(48.dp);click("package-confirm");pump()
        assertEquals(1,restores);assertEquals(RecordPackagePhase.DONE,state.value.recordPackage!!.phase)
        assertEquals(newId,state.value.pendingEdits.single().entryId);assertNotEquals(editDraft.id,state.value.pendingEdits.single().id)
        assertTrue(state.value.entries.all {it.originalText==currentText});assertEquals("合成未提交输入",state.value.text)
        node("package-notice").assertTextContains("1 份独立记录编辑草稿",substring=true)
    }
}
