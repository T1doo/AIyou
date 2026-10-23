package com.t1doo.aiyou

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

/** Actual root/Compose/controller and synthetic IO ports. Cross-key SQL/file tests run in the host suite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h900dp")
@OptIn(ExperimentalFoundationApi::class)
class FullBackupPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val f=Fixture()
    private var saved:SaveableStateRegistry?=null
    private val navigation=AtomicReference<OriginalReadingCallbacks>()
    private val observer=object:OriginalReadingObserver {
        override fun onNavigation(callbacks:OriginalReadingCallbacks) { navigation.set(callbacks) }
        override fun onClipboard(clipboard:Clipboard)=Unit
        override fun onSelection(selection:SelectionState)=Unit
    }
    @Composable private fun Content() {
        saved=LocalSaveableStateRegistry.current
        val density=LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density,2f),LocalOriginalReadingObserver provides observer) {
            AIyouTheme { AIyouApp(local=f.state.value,previewDestination=Destination.MEMORIES,
                recordPackages=f.controller,recordPackagePicker=f.picker,currentRecords={f.state.value}) }
        }
    }
    private fun open() {
        compose.showNotebookToolsFor("package-open");compose.onNodeWithTag("package-open").performScrollTo().performClick()
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag("package-full-backup"))
        compose.onNodeWithTag("package-full-backup").performClick()
    }
    private fun click(tag:String) {
        compose.onNodeWithTag("full-backup").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun input(tag:String,text:String) {
        compose.onNodeWithTag("full-backup").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).performTextInput(text)
    }
    private fun pump() { compose.runOnIdle { runBlocking { f.drain() } } }
    @Test fun actualRootFullScopeRequiresExplicitAcceptanceAndMatchingIndependentPasswords() {
        compose.setContent { Content() };open()
        compose.onNodeWithText("内容范围").assertDoesNotExist()
        compose.onNodeWithText("恢复条件").assertExists();compose.onNodeWithText("恢复口令").assertExists()
        click("full-backup-details")
        for(title in listOf("内容范围","不随备份恢复","容量上限")) compose.onNodeWithText(title).assertExists()
        compose.runOnIdle {assertTrue(f.queue.isEmpty());assertEquals(0,f.seals);assertEquals(0,f.imports);assertEquals(0,f.picker.launches)}
        click("full-backup-details");compose.onNodeWithText("内容范围").assertDoesNotExist()
        click("full-backup-preview");pump()
        for(title in listOf("内容范围","不随备份恢复","容量上限","恢复条件","恢复口令")) compose.onNodeWithText(title).assertExists()
        compose.onNodeWithTag("full-backup-details").assertDoesNotExist()
        compose.onNodeWithTag("full-backup").performScrollToNode(hasTestTag("full-backup-summary"))
        compose.onNodeWithTag("full-backup-summary").assertTextEquals("记录 26 条（回收站 1 条）；修订 27 个；原图 2 张／24 字节。")
        compose.onNodeWithTag("full-backup").performScrollToNode(hasTestTag("full-backup-save"))
        compose.onNodeWithTag("full-backup-save").assertIsNotEnabled()
        click("full-backup-scope");input("full-backup-password",PASSWORD);input("full-backup-password-again","mismatched synthetic passphrase")
        click("full-backup-save");pump();compose.runOnIdle { assertEquals(0,f.seals);assertEquals(0,f.picker.launches) }
        input("full-backup-password",PASSWORD);input("full-backup-password-again",PASSWORD);click("full-backup-save");pump()
        compose.runOnIdle { assertEquals(1,f.seals);assertEquals(1,f.picker.launches);assertTrue(f.picker.export) }
    }
    @Test fun fullRestoreAuthenticatesManifestThenRequiresSeparateEmptyTargetConfirmation() {
        compose.setContent { Content() };open();click("full-backup-open")
        compose.runOnIdle { f.picker.deliver(Document()) };input("full-backup-password",PASSWORD);click("full-backup-authenticate");pump()
        compose.runOnIdle { assertEquals(0,f.imports);assertEquals(RecordPackagePhase.RESTORE_PREVIEW,f.ui.phase) }
        click("full-backup-restore");compose.runOnIdle { assertEquals(0,f.imports) };pump()
        compose.onNodeWithTag("full-backup").performScrollToNode(hasTestTag("full-backup-notice"))
        compose.onNodeWithTag("full-backup-notice").assertTextEquals("已核对完整资料库恢复回执。原身份、回收站、版本与草稿保持原义；联网授权已清空，提醒须重新确认。")
        compose.runOnIdle { assertEquals(1,f.imports);assertEquals(RecordPackagePhase.DONE,f.ui.phase) }
    }
    @Test fun narrowDoubleFontRecreationDropsPasswordsAndNavigationRetiresPickerBeforeRecomposition() {
        val restoration=StateRestorationTester(compose);restoration.setContent { Content() }
        open();click("full-backup-preview");pump();click("full-backup-scope")
        input("full-backup-password",PASSWORD);input("full-backup-password-again",PASSWORD)
        compose.runOnIdle { assertFalse(saved!!.performSave().toString().contains(PASSWORD));assertFalse(saved!!.performSave().toString().contains(f.summary.backupId)) }
        restoration.emulateSavedInstanceStateRestore();click("full-backup-save");pump()
        compose.runOnIdle { assertEquals(0,f.seals) }
        click("full-backup-cancel");click("full-backup-open")
        compose.runOnIdle {
            val late=f.picker.retained();navigation.get().selectDestination(Destination.NOW)
            val document=Document();late(document);assertEquals(0,document.reads);assertNull(f.state.value.recordPackage);assertTrue(f.queue.isEmpty())
        }
    }
    private class Fixture {
        val summary=FullBackupSummary(UUID.randomUUID().toString(),100,26,1,27,2,24,1,2,3,1,1)
        val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,supportsRecordPackages=true,supportsFullBackup=true))
        val queue=ArrayDeque<suspend (EntryRepository)->Unit>();val picker=Picker();var seals=0;var imports=0
        val full=object:FullBackupRepository {
            override suspend fun capture(backupId:String,createdAtMillis:Long)=object:FullBackupSnapshot { override val summary=this@Fixture.summary;override fun release()=Unit }
            override suspend fun seal(snapshot:FullBackupSnapshot,password:CharArray):PreparedRecordMediaPackage {
                seals++;password.fill('\u0000');return object:PreparedRecordMediaPackage {
                    override suspend fun writeTo(output:OutputStream,active:()->Boolean) { check(active());output.write(byteArrayOf(1,2,3)) }
                    override fun release()=Unit
                }
            }
            override suspend fun open(source:()->InputStream,password:CharArray):VerifiedFullBackup {
                try { check(String(password)==PASSWORD);source().use { it.read() } } finally { password.fill('\u0000') }
                return object:VerifiedFullBackup { override val summary=this@Fixture.summary;override val digest="a".repeat(64);override fun release()=Unit }
            }
            override suspend fun restore(backup:VerifiedFullBackup,active:()->Boolean):FullBackupRestored { check(active());imports++;return FullBackupRestored(summary,false) }
        }
        val store=object:EntryRepository {
            override val fullBackups=full
            override suspend fun entries()=emptyList<StoredEntry>()
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("not used")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("not used")
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("not used")
        }
        val controller=RecordPackageController({state.value},{state.value=it(state.value)},{queue.add(it);true},JcaRecordPackageCipher(),Clock.systemUTC())
        val ui get()=checkNotNull(state.value.recordPackage)
        suspend fun drain() { while(queue.isNotEmpty()) queue.removeFirst()(store) }
    }
    private class Picker:RecordPackagePicker {
        var launches=0;var export=false;private var callback:((SelectedRecordPackageDocument?)->Unit)?=null
        override fun launch(export:Boolean,requestKey:String,callback:(SelectedRecordPackageDocument?)->Unit):Boolean { launches++;this.export=export;this.callback=callback;return true }
        override fun cancel(requestKey:String) { callback=null }
        fun deliver(document:Document) { callback?.invoke(document);callback=null }
        fun retained()=checkNotNull(callback)
    }
    private class Document:SelectedRecordPackageDocument { var reads=0
        override fun openForRead():InputStream { reads++;return byteArrayOf(1,2,3).inputStream() }
        override fun openForWrite():OutputStream=ByteArrayOutputStream()
    }
    companion object { private const val PASSWORD="synthetic full backup password" }
}
