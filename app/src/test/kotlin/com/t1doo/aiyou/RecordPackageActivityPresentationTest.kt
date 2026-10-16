package com.t1doo.aiyou

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual App/screen/controller with controlled authenticated ports; v3 AEAD/SQL run in host tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
class RecordPackageActivityPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val oldTask=ManualTaskSnapshot("synthetic-task",3,"合成已完成待办",ManualTaskStatus.COMPLETED,10,"UTC",30,
        ManualTaskDateInput("2026-11-01","01:30","America/New_York",ManualTaskOccurrence.SECOND).parse())
    private val oldDraft=TaskProposal("synthetic-proposal",2,TaskSourceBinding(SourceVersion("old-source",1),"a".repeat(64),1,0,5),
        "合成仍待确认的草稿",ManualTaskDateInput("2026-",timeZone="UTC",occurrence=ManualTaskOccurrence.SECOND),10,"UTC",30)
    private val activities=RecordPackageActivities(listOf(RecordPackageTask(oldTask)),listOf(RecordPackageDraft(oldDraft)))
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,supportsRecordPackages=true,text="合成当前捕获输入",
        tasks=ManualTasksUiState(supported=true,available=true),taskProposals=TaskProposalsUiState(supported=true,available=true)))
    private val queue=ArrayDeque<suspend(EntryRepository)->Unit>()
    private var currentTasks=emptyList<ManualTaskSnapshot>();private var currentDrafts=emptyList<TaskProposalItem>()
    private var restored=false;private var imports=0;private var confirms=0;private var failActivityRead=false
    private var registry:SaveableStateRegistry?=null
    private val verified=object:VerifiedRecordPackage {
        override val manifest=RecordPackageManifest(UUID.randomUUID().toString(),50,0)
        override val digest="b".repeat(64);override val records=emptyList<RecordPackageOriginal>()
        override val activities=this@RecordPackageActivityPresentationTest.activities
    }
    private val packages=object:RecordPackageRepository {
        override fun origins()=emptyMap<String,RecordPackageOrigin>()
        override fun restore(verified:VerifiedRecordPackage,newIds:List<String>,importedAtMillis:Long):RecordPackageRestoreResult {
            imports++;val replay=restored
            if(!restored) {
                restored=true;currentTasks=listOf(oldTask.copy(id="restored-task",version=1))
                currentDrafts=listOf(TaskProposalItem(oldDraft.copy(id="restored-proposal",version=1,binding=oldDraft.binding.copy(
                    source=SourceVersion(RecordPackageActivities.UNAVAILABLE_PREFIX+"a".repeat(64),1))),false))
            }
            return RecordPackageRestoreResult(emptyList(),100,replay,taskIds=listOf("restored-task"),proposalIds=listOf("restored-proposal"))
        }
    }
    private val owner=object:EntryRepository {
        override val originalExport=object:OriginalExportRepository {
            override fun capture(sources:List<SourceVersion>,snapshotId:String,createdAtMillis:Long,refreshCurrent:Boolean):OriginalExportSnapshot=error("Task-only selection must not invent records")
            override fun isCurrent(snapshot:OriginalExportSnapshot)=snapshot.records.isEmpty()
        }
        override val mediaPackages=object:RecordMediaPackageRepository {
            override suspend fun preview(snapshot:OriginalExportSnapshot)=RecordMediaPreview(snapshot,emptyList())
            override suspend fun previewActivities(snapshot:OriginalExportSnapshot)=RecordMediaPreview(snapshot,emptyList(),activities)
            override suspend fun isCurrent(preview:RecordMediaPreview)=true
            override suspend fun seal(preview:RecordMediaPreview,password:CharArray):PreparedRecordMediaPackage {password.fill('\u0000');error("Preview test does not write")}
            override suspend fun open(source:()->InputStream,password:CharArray):VerifiedRecordPackage {
                try {source().use {check(it.read()==1)};return verified} finally {password.fill('\u0000')}
            }
            override suspend fun restore(verified:VerifiedRecordPackage,newIds:List<String>,importedAtMillis:Long)=packages.restore(verified,newIds,importedAtMillis)
            override suspend fun recover()=Unit
        }
        override val recordPackages=packages
        override val manualTags=object:ManualTagRepository {
            override fun read(entryId:String)=error("No record")
            override fun snapshots()=emptyMap<String,ManualTagSnapshot>()
            override fun replace(mutation:ManualTagMutation)=error("Not requested")
        }
        override val manualTasks=object:ManualTaskRepository {
            override fun snapshots():List<ManualTaskSnapshot> {if(failActivityRead) {failActivityRead=false;error("Synthetic readback failure")};return currentTasks}
            override fun create(request:ManualTaskCreate)=error("No new task confirmation requested")
            override fun change(request:ManualTaskMutation)=error("Not requested")
            override fun rename(request: com.t1doo.aiyou.model.ManualTaskRename): ManualTaskSnapshot = error("Title editing not requested by this test")
            override fun reschedule(request:ManualTaskScheduleMutation)=error("Not requested")
        }
        override val taskProposals=object:TaskProposalRepository {
            override fun list()=currentDrafts
            override fun write(request:TaskProposalWrite)=error("Not requested")
            override fun confirm(request:TaskProposalConfirm):ManualTaskSnapshot {confirms++;error("Restore must not confirm a draft")}
        }
        override suspend fun entries()=emptyList<StoredEntry>()
        override suspend fun readDraft()=null
        override suspend fun updateDraft(text:String)=error("Not requested")
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("Not requested")
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("Not requested")
    }
    private val controller=RecordPackageController({state.value},{state.value=it(state.value)},{queue.add(it);true},JcaRecordPackageCipher(),Clock.systemUTC())
    private var picked:((SelectedRecordPackageDocument?)->Unit)?=null
    private val picker=object:RecordPackagePicker {
        override fun launch(export:Boolean,requestKey:String,callback:(SelectedRecordPackageDocument?)->Unit):Boolean {assertFalse(export);picked=callback;return true}
        override fun cancel(requestKey:String)=Unit
    }
    @After fun close() {controller.dispose()}
    private fun content() {compose.setContent {
        val saved=LocalSaveableStateRegistry.current;SideEffect {registry=saved}
        val density=LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {AIyouTheme {
            AIyouApp(local=state.value,previewDestination=Destination.MEMORIES,currentRecords={state.value},recordPackages=controller,recordPackagePicker=picker)
        }}
    }}
    private fun node(tag:String):SemanticsNodeInteraction {
        compose.onNodeWithTag("package-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun click(tag:String) {node(tag).assertIsEnabled().performClick()}
    private fun pump() {compose.runOnIdle {runBlocking {while(queue.isNotEmpty()) queue.removeFirst()(owner)}}}
    private fun open() {compose.onNodeWithTag("package-open").performScrollTo().performClick()}
    @Test fun taskOnlySelectionAtDoubleFontPreviewsEveryKindAndKeepsExplicitScopeGateAndPrivateState() {
        content();open();click("package-export")
        node("package-preview").assertIsNotEnabled();click("package-include-activities");click("package-preview");pump()
        node("package-activity-summary").assertTextEquals("v3 包内正式待办 1 条、待确认草稿 1 条；未映射来源 1 处。")
        node("package-task:synthetic-task").assertExists();node("package-draft:synthetic-proposal").assertExists()
        compose.onNodeWithTag("package-list").performScrollToNode(hasText("待确认草稿 · 原输入：2026-  · UTC"))
        compose.onNodeWithText("待确认草稿 · 原输入：2026-  · UTC").assertExists()
        compose.onNodeWithTag("package-list").performScrollToNode(hasText("重复时刻选择：第二次"))
        compose.onNodeWithText("重复时刻选择：第二次").assertExists()
        compose.onNodeWithTag("package-list").performScrollToNode(hasText("2026-11-01 01:30 · America/New_York · 偏移 -05:00"))
        compose.onNodeWithText("2026-11-01 01:30 · America/New_York · 偏移 -05:00").assertExists()
        node("package-encrypt").assertIsNotEnabled();assertEquals(0,imports);assertEquals(0,confirms)
        click("package-scope");node("package-encrypt").assertIsEnabled()
        compose.runOnIdle {val saved=checkNotNull(registry).performSave().toString();assertFalse(saved.contains(oldDraft.titleInput));assertFalse(saved.contains(oldTask.title));assertFalse(saved.contains("2026-"))}
        click("package-cancel");assertEquals("合成当前捕获输入",state.value.text);assertEquals(0,imports)
    }
    @Test fun taskRestoreRequiresActualSeparateConfirmationThenUnknownReadbackRetriesWithoutPromotingDraft() {
        content();open();click("package-restore")
        compose.runOnIdle {picked!!(object:SelectedRecordPackageDocument {override fun openForRead()=byteArrayOf(1).inputStream();override fun openForWrite():OutputStream=error("Not requested")})}
        node("package-password").performTextInput("synthetic UI recovery password");click("package-authenticate");pump()
        node("package-activity-summary").assertTextEquals("v3 包内正式待办 1 条、待确认草稿 1 条；未映射来源 1 处。");assertEquals(0,imports)
        node("package-confirm").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        failActivityRead=true;click("package-confirm");assertEquals(0,imports);pump()
        assertEquals(RecordPackagePhase.UNCERTAIN,state.value.recordPackage!!.phase);assertEquals(1,currentTasks.size);assertEquals(1,currentDrafts.size)
        click("package-confirm");pump();assertEquals(RecordPackagePhase.DONE,state.value.recordPackage!!.phase)
        assertEquals(2,imports);assertEquals(1,state.value.tasks.items.size);assertEquals(1,state.value.taskProposals.items.size)
        assertEquals(0,confirms);assertEquals("2026-",state.value.taskProposals.items.single().proposal.dateInput.date)
        assertEquals("合成当前捕获输入",state.value.text);node("package-notice").assertTextEquals(
            "已核对第一次恢复回执，没有新增、覆盖或复活记录、任务或草稿。已永久删除 0 条，删除未完成 0 条；其余副本以当前本机状态为准。")
    }
}
