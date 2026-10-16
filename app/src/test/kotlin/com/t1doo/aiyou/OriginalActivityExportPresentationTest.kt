package com.t1doo.aiyou

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.time.Clock
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Actual App, dialog, controller, codec and SAF intent; controlled read-only storage ports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
class OriginalActivityExportPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val task=ManualTaskSnapshot("task",2,"合成已完成待办",ManualTaskStatus.COMPLETED,10,"UTC",20,
        ManualTaskDateInput("2026-11-01","01:30","America/New_York",ManualTaskOccurrence.SECOND).parse())
    private val draft=TaskProposal("draft",1,TaskSourceBinding(SourceVersion("old-source",1),"a".repeat(64),1,0,5),
        "合成尚待确认草稿",ManualTaskDateInput("2026-",timeZone="UTC",occurrence=ManualTaskOccurrence.SECOND),10,"UTC",10)
    private val activities=RecordPackageActivities(listOf(RecordPackageTask(task)),listOf(RecordPackageDraft(draft)))
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,supportsOriginalExport=true,text="合成尚未保存输入",
        tasks=ManualTasksUiState(supported=true,available=true),taskProposals=TaskProposalsUiState(supported=true,available=true)))
    private var current=true
    private val queue=ArrayDeque<suspend(EntryRepository)->Unit>()
    private val store=object:EntryRepository {
        override val originalExport=object:OriginalExportRepository {
            override fun capture(sources:List<SourceVersion>,snapshotId:String,createdAtMillis:Long,refreshCurrent:Boolean):OriginalExportSnapshot=error("No invented record")
            override fun isCurrent(snapshot:OriginalExportSnapshot)=current
            override fun captureActivities(snapshot:OriginalExportSnapshot)=activities
            override fun areActivitiesCurrent(snapshot:OriginalExportSnapshot,activities:RecordPackageActivities)=current
        }
        override suspend fun entries()=emptyList<StoredEntry>();override suspend fun readDraft()=null
        override suspend fun updateDraft(text:String)=error("No writes")
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("No writes")
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("No writes")
    }
    private val controller=OriginalExportController({state.value},{state.value=it(state.value)},{queue.add(it);true},Clock.systemUTC())
    private val registry=Registry()
    private val owner=object:ActivityResultRegistryOwner {override val activityResultRegistry=registry}
    private var saved:SaveableStateRegistry?=null
    @After fun dispose()=controller.dispose()
    private fun content() {compose.setContent {
        saved=LocalSaveableStateRegistry.current
        val density=LocalDensity.current
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner,LocalDensity provides Density(density.density,2f)) {
            AIyouTheme {AIyouApp(local=state.value,previewDestination=Destination.MEMORIES,currentRecords={state.value},exports=controller)}
        }
    }}
    private fun node(tag:String)=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    private fun click(tag:String) {node(tag).assertIsEnabled().performClick()}
    private fun pump() {compose.runOnIdle {runBlocking {while(queue.isNotEmpty()) queue.removeFirst()(store)}}}
    private fun ui()=checkNotNull(state.value.originalExport)
    private fun preview() {click("export-open");click("export-include-activities");click("export-prepare");pump()}
    @Test fun taskOnlyDoubleFontPreviewSeparatesKindsAndRequiresPlaintextConsentBeforeSafWithoutSavingPrivateState() {
        content();click("export-open");node("export-prepare").assertIsNotEnabled()
        compose.runOnIdle {assertTrue(state.value.entries.isEmpty());assertFalse(ui().includeActivities)}
        click("export-include-activities");click("export-prepare");pump()
        node("export-activity-summary").assertTextEquals("正式待办 1 条；待确认草稿 1 条；未完整包含来源 1 处。")
        node("export-task:task").assertExists();node("export-draft:draft").assertExists()
        compose.onNodeWithText("待确认草稿 · 原输入：2026-  · UTC").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重复时刻选择：第二次").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2026-11-01 01:30 · America/New_York · 偏移 -05:00").performScrollTo().assertIsDisplayed()
        node("export-choose").assertIsNotEnabled();assertTrue(registry.intents.isEmpty())
        compose.runOnIdle {val raw=checkNotNull(saved).performSave().toString();assertFalse(raw.contains(task.title));assertFalse(raw.contains(draft.titleInput));assertFalse(raw.contains("old-source"));assertFalse(raw.contains("2026-"))}
        click("export-risk");click("export-choose");pump()
        compose.runOnIdle {
            val intent=registry.intents.single();assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.action);assertEquals("text/markdown",intent.type)
            assertEquals("AIyou-originals-and-activities.md",intent.getStringExtra(Intent.EXTRA_TITLE));assertFalse(intent.toString().contains(task.title))
            assertEquals(0,intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);assertFalse(ui().hasFile)
            registry.dispatchResult(registry.codes.single(),null as Uri?)
        }
        node("export-choose").assertIsNotEnabled();assertEquals(OriginalExportPhase.PREVIEW,ui().phase)
        assertEquals("合成尚未保存输入",state.value.text)
    }
    @Test fun formatScopeChangesRetireConsentAndChangedActivitiesNeverLaunchDestinationOrPromoteDraft() {
        content();preview();click("export-risk");val old=ui()
        click("export-format-JSONL");assertFalse(ui().riskAccepted)
        node("export-choose").assertIsNotEnabled()
        compose.runOnIdle {controller.choose(old.session,old.snapshot!!.id,old.format,old.epoch)}
        pump();assertTrue(registry.intents.isEmpty())
        click("export-format-MARKDOWN")
        compose.runOnIdle {controller.acceptRisk(old.session,old.snapshot!!.id,old.format,old.epoch,true)}
        assertFalse(ui().riskAccepted);node("export-choose").assertIsNotEnabled()
        click("export-format-MEDIA_ZIP")
        compose.runOnIdle {assertFalse(ui().includeActivities);assertNull(ui().activities);assertNull(ui().snapshot)}
        node("export-prepare").assertIsNotEnabled();click("export-format-MARKDOWN")
        click("export-include-activities");click("export-prepare");pump();click("export-risk")
        compose.runOnIdle {current=false};click("export-choose");pump()
        node("export-error").assertTextContains("已变化",substring=true)
        assertEquals(OriginalExportPhase.CHANGED,ui().phase);assertTrue(registry.intents.isEmpty())
        assertEquals(TaskProposalState.DRAFT,draft.state);assertEquals(ManualTaskStatus.COMPLETED,task.status)
        assertEquals("合成尚未保存输入",state.value.text)
    }
    @Test fun activitySafCancelReselectRetiresOldUuidAndDuplicateResultWritesAndReadsOnlyOnce() {
        val app=RuntimeEnvironment.getApplication();val file=File.createTempFile("synthetic-activity-export-",".jsonl",app.cacheDir)
        val providerAuthority="aiyou.synthetic.activityexport";val provider=Provider(file)
        provider.attachInfo(app,ProviderInfo().apply {authority=providerAuthority})
        ShadowContentResolver.registerProviderInternal(providerAuthority,provider)
        try {
            content();preview();click("export-risk");click("export-choose");pump()
            // pump publishes PICKING inside its idle callback; settle that recomposition and
            // both picker effects before observing the actual registry launch.
            val (oldKey,oldCode)=compose.runOnIdle {
                assertEquals(OriginalExportPhase.PICKING,ui().phase);assertTrue(ui().pickerLaunched)
                assertEquals(1,registry.codes.size);assertEquals(1,registry.intents.size)
                assertEquals(Intent.ACTION_CREATE_DOCUMENT,registry.intents.single().action)
                assertEquals("text/markdown",registry.intents.single().type)
                checkNotNull(ui().requestKey) to registry.codes.single()
            }
            compose.runOnIdle {registry.dispatchResult(oldCode,null as Uri?)}
            node("export-choose").assertIsNotEnabled()
            assertFalse(ui().riskAccepted);assertEquals(OriginalExportPhase.PREVIEW,ui().phase);assertTrue(provider.modes.isEmpty())
            click("export-format-JSONL");node("export-choose").assertIsNotEnabled()
            click("export-risk");click("export-choose");pump()
            compose.runOnIdle {
                assertEquals(OriginalExportPhase.PICKING,ui().phase);assertTrue(ui().pickerLaunched)
                assertEquals(2,registry.codes.size);assertEquals(2,registry.intents.size)
                val key=checkNotNull(ui().requestKey);assertNotEquals(oldKey,key)
                assertEquals(Intent.ACTION_CREATE_DOCUMENT,registry.intents.last().action)
                assertEquals("application/x-ndjson",registry.intents.last().type)
                registry.dispatchResult(oldCode,Uri.parse("content://$providerAuthority/old"))
                assertEquals(OriginalExportPhase.PICKING,ui().phase);assertEquals(key,ui().requestKey)
                assertTrue(queue.isEmpty());assertTrue(provider.modes.isEmpty())
                val code=registry.codes.last();val uri=Uri.parse("content://$providerAuthority/current")
                registry.dispatchResult(code,uri);registry.dispatchResult(code,uri)
                assertEquals(OriginalExportPhase.WRITING,ui().phase);assertEquals(1,queue.size)
            }
            pump()
            assertEquals(OriginalExportPhase.DONE,ui().phase);assertEquals(listOf("wt","r"),provider.modes)
            assertTrue(provider.paths.all {it=="/current"});assertEquals(0,provider.deletes)
            val text=file.readText();assertEquals(1,text.lines().count {it.startsWith("{\"type\":\"manualTask\"")})
            assertEquals(1,text.lines().count {it.startsWith("{\"type\":\"taskProposal\"")})
            assertEquals(TaskProposalState.DRAFT,draft.state);assertEquals(ManualTaskStatus.COMPLETED,task.status)
            assertEquals("合成尚未保存输入",state.value.text)
        } finally {if(file.exists()) check(file.delete())}
    }
    private class Provider(private val file:File):ContentProvider() {
        val modes=mutableListOf<String>();val paths=mutableListOf<String?>();var deletes=0
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/x-ndjson"
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            modes.add(mode);paths.add(uri.path)
            return ParcelFileDescriptor.open(file,if(mode=="r") ParcelFileDescriptor.MODE_READ_ONLY
                else ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
        override fun insert(uri:Uri,values:ContentValues?):Uri?=error("No inserts")
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int {deletes++;error("No automatic deletion")}
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=error("No updates")
    }
    private class Registry:ActivityResultRegistry() {
        val codes=mutableListOf<Int>();val intents=mutableListOf<Intent>()
        override fun <I,O> onLaunch(code:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            codes.add(code);intents.add(contract.createIntent(RuntimeEnvironment.getApplication(),input))
        }
    }
}
