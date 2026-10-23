package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.designsystem.AIyouTheme
import java.time.Clock
import java.util.UUID
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real production Now route/controller/platform thumbnail; controlled draft port, real SQL is in host tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MixedCapturePresentationTest {
    @get:Rule val compose=createComposeRule()
    private val f=Fixture();private var registry:SaveableStateRegistry?=null
    private var focusManager:FocusManager?=null
    private val policyActions=mutableMapOf<String,()->Unit>()
    private fun content(viewport:State<DpSize>?=null,fontScale:State<Float>?=null) {
        runBlocking { f.c.initialize(f.store) }
        compose.setContent {
            registry=LocalSaveableStateRegistry.current
            focusManager=LocalFocusManager.current
            val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,fontScale?.value?:2f),
                LocalCapturePolicyActionObserver provides { name,action -> policyActions[name]=action }) {
                val app: @Composable () -> Unit = { AIyouTheme { AIyouApp(local=f.local.value,mixedCapture=f.c) } }
                val size=viewport?.value
                if(size==null) app() else Box(androidx.compose.ui.Modifier.requiredSize(size.width,size.height)) { app() }
            }
        }
    }
    private fun click(tag:String) { compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick() }
    private fun openRecent(id:String) {
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("entry-$id"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-$id")))
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun drain(){compose.runOnIdle { runBlocking { while(f.queue.isNotEmpty())f.queue.removeFirst()(f.store) } }}
    private fun add() { click("mixed-add");compose.runOnIdle { val s=f.ui.selection!!;if(!s.launched)f.c.claimPicker(s.requestId);f.c.selected(s.requestId,Source()) };drain() }
    private fun policyNode(tag:String):SemanticsNodeInteraction {
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    @Test fun narrowPreSaveRangeDefaultsLocalAndRetiredActualChoiceCannotAffectReturnedDraft() {
        content()
        f.failTextWrite=true
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").performTextInput("合成保存前选择范围");drain()
        val textError=f.ui.error;assertNotNull(textError)
        policyNode("mixed-policy-LOCAL_ONLY").assertIsSelected().assertHeightIsAtLeast(48.dp)
        policyNode("mixed-policy-AI_NONE").assertIsNotSelected().assertHeightIsAtLeast(48.dp)
        val stale=compose.runOnIdle { policyActions.getValue("policy:AI_NONE") }
        compose.onNodeWithText("设置").performClick();compose.onNodeWithText("返回").performClick()
        compose.runOnIdle { stale();assertTrue(f.queue.isEmpty());assertNull(f.ui.policyChange)
            assertEquals(ProcessingPolicy.LOCAL_ONLY,f.ui.draft!!.policy) }
        policyNode("mixed-policy-AI_NONE").performClick()
        compose.runOnIdle { assertTrue(f.ui.busy);assertEquals(0,f.saves);assertEquals(ProcessingPolicy.LOCAL_ONLY,f.ui.draft!!.policy) }
        policyNode("mixed-policy-AI_NONE").assertIsNotSelected().assertIsNotEnabled()
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-save"))
        compose.onNodeWithTag("mixed-save").assertIsNotEnabled()
        drain()
        policyNode("mixed-policy-AI_NONE").assertIsSelected()
        compose.runOnIdle { val saved=registry!!.performSave().toString()
            assertEquals(textError,f.ui.error)
            assertFalse(saved.contains("合成保存前选择范围"));assertFalse(saved.contains(f.ui.draft!!.id)) }
        f.failTextWrite=false
        click("mixed-save");drain()
        compose.runOnIdle { assertEquals(ProcessingPolicy.AI_NONE,f.local.value.entries.single().policy)
            assertEquals("合成保存前选择范围",f.local.value.entries.single().originalText)
            assertEquals(ProcessingPolicy.LOCAL_ONLY,f.ui.draft!!.policy) }
    }
    @Test fun narrowLostRangeAckKeepsPendingChoiceDisablesSaveAndRetriesExactTransition() {
        content()
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").performTextInput("合成范围确认失败仍保留");drain()
        val before=f.ui.draft!!;f.failPolicyAck=true
        policyNode("mixed-policy-AI_NONE").performClick();drain()
        policyNode("mixed-policy-pending").assertTextEquals("范围保存尚未确认：不参与 AI。核对前不能保存记录。")
        policyNode("mixed-policy-LOCAL_ONLY").assertIsSelected().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(before.version,f.ui.draft!!.version);assertEquals(1,f.policyCalls)
            assertNotNull(f.ui.error);assertEquals(0,f.saves) }
        f.failPolicyRead=true
        policyNode("mixed-policy-read").assertIsEnabled().performClick();drain()
        compose.runOnIdle { assertNotNull(f.ui.policyChange);assertEquals(1,f.policyCalls) }
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-save"))
        compose.onNodeWithTag("mixed-save").assertIsNotEnabled()
        f.failPolicyRead=false
        policyNode("mixed-policy-retry").assertHeightIsAtLeast(48.dp).performClick();drain()
        compose.runOnIdle { assertNull(f.ui.policyChange);assertNull(f.ui.error);assertEquals(2,f.policyCalls)
            assertEquals(before.version+1,f.ui.draft!!.version);assertEquals("合成范围确认失败仍保留",f.ui.text) }
        policyNode("mixed-policy-AI_NONE").assertIsSelected()
        click("mixed-save");drain()
        compose.runOnIdle { assertEquals(1,f.saves);assertEquals(ProcessingPolicy.AI_NONE,f.local.value.entries.single().policy) }
    }
    @Test fun explicitReadAfterFailedRangeWriteDoesNotResendAndRetiresOldRetryHandler() {
        content();f.failPolicyBefore=true
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").performTextInput("合成只读取当前范围");drain()
        policyNode("mixed-policy-AI_NONE").performClick();drain()
        policyNode("mixed-policy-retry").assertIsEnabled()
        val staleRetry=compose.runOnIdle { policyActions.getValue("retry") }
        policyNode("mixed-policy-read").performClick();drain()
        compose.runOnIdle { assertNull(f.ui.policyChange);assertEquals(1,f.policyCalls)
            assertEquals(ProcessingPolicy.LOCAL_ONLY,f.ui.draft!!.policy);assertEquals("合成只读取当前范围",f.ui.text)
            staleRetry();assertTrue(f.queue.isEmpty()) }
        policyNode("mixed-policy-LOCAL_ONLY").assertIsSelected()
        click("mixed-save");drain()
        compose.runOnIdle { staleRetry();assertTrue(f.queue.isEmpty());assertEquals(1,f.policyCalls)
            assertEquals(ProcessingPolicy.LOCAL_ONLY,f.local.value.entries.single().policy) }
    }
    @Test fun actualNowPurePictureHasThumbnailCountRemovalAndSeparateDurableSaveAtLargeFont() {
        content();add();val id=f.ui.draft!!.images.single().intent.id
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-thumb:"+id));compose.onNodeWithTag("mixed-thumb:"+id).assertIsDisplayed()
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-count"));compose.onNodeWithTag("mixed-count").assertTextContains("1 / 20",substring=true)
        compose.runOnIdle { assertEquals(0,f.saves);assertEquals("",f.ui.text) }
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-save"));compose.onNodeWithTag("mixed-save").assertHeightIsAtLeast(48.dp)
        click("mixed-save");compose.runOnIdle { assertEquals(0,f.saves) };drain()
        compose.runOnIdle { assertEquals(1,f.saves);assertEquals("",f.local.value.entries.single().originalText);assertTrue(f.ui.draft!!.images.isEmpty()) }
        val saved=f.local.value.entries.single().id
        openRecent(saved)
        compose.onNodeWithText("记录原文").assertExists()
        compose.onNodeWithText("附件记录 · 无文字正文；请打开附件查看原始素材。").assertExists()
        compose.onNodeWithText("返回列表").performClick()
        compose.onNodeWithTag("mixed-list").assertExists()
    }
    @Test fun removingLastImageRejectsEmptyRecordAndCancelKeepsDraftAndRetiresPicker() {
        content();add();val id=f.ui.draft!!.images.single().intent.id
        click("mixed-remove:"+id);drain();click("mixed-save");drain()
        compose.runOnIdle { assertEquals(0,f.saves);assertNotNull(f.ui.error) }
        click("mixed-add");val retained=f.ui.selection!!.requestId;click("mixed-cancel-picture");drain()
        compose.runOnIdle { val s=Source();f.c.selected(retained,s);assertEquals(0,s.opens) }
        click("mixed-clear");compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertFalse(f.ui.confirmDiscard);assertEquals(0,f.saves) }
    }
    @Test fun failedSaveRetainsTextAndImagesAndPrivateComposerNeverEntersSavedState() {
        content();compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").performTextInput("合成私密图文输入");drain();add()
        f.failSave=true;click("mixed-save");drain()
        compose.runOnIdle { assertEquals("合成私密图文输入",f.ui.text);assertEquals(1,f.ui.draft!!.images.size);assertTrue(f.ui.uncertainSave)
            assertTrue(f.c.recentEntries().isEmpty())
            val saved=registry!!.performSave().toString();assertFalse(saved.contains("合成私密图文输入"));assertFalse(saved.contains(f.ui.draft!!.id));assertFalse(saved.contains("content://")) }
        f.failSave=false;click("mixed-save");drain();compose.runOnIdle { assertEquals(1,f.saves);assertEquals("合成私密图文输入",f.local.value.entries.single().originalText) }
    }
    @Test fun newSaveWhileComposerIsScrolledOnlyOffersExplicitJumpAndReaderReturnKeepsNextDraft() {
        content();compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").performTextInput("合成今天可直接阅读的原文");drain()
        click("mixed-save");drain()
        val scroll=compose.onNodeWithTag("mixed-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Saving must not jump the scrolled composer to the top",scroll.value()>0f)
        compose.onNodeWithTag("mixed-list").performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNodeWithTag("mixed-new-records").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("今天最近记录").assertIsDisplayed()
        compose.onNodeWithTag("mixed-new-records").assertDoesNotExist()
        compose.onNodeWithTag("mixed-jump-composer").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("mixed-text").performScrollTo().assertIsDisplayed().performTextInput("下一份合成草稿");drain()
        val id=f.local.value.entries.single().id;openRecent(id)
        compose.onNodeWithTag("original-text").assertTextContains("合成今天可直接阅读的原文")
        compose.onNodeWithText("返回列表").performClick()
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").assertTextContains("下一份合成草稿")
    }
    @Test fun lostClearAckHasReachableSameClearRetryAndCannotPromiseContinuedEditingBeforeVerification() {
        content();compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").performTextInput("合成清空前图文");drain();add()
        val old=f.ui.draft!!.id;f.failDiscardAck=true
        click("mixed-clear");compose.onNodeWithTag("mixed-discard-confirm").performClick();drain()
        compose.onNodeWithText("清空结果待核对").assertIsDisplayed()
        compose.onNodeWithText("稍后核对").performClick()
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").assertIsNotEnabled().assertTextContains("合成清空前图文")
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-save"))
        compose.onNodeWithTag("mixed-save").assertIsNotEnabled()
        click("mixed-clear");drain()
        compose.runOnIdle { assertNotEquals(old,f.ui.draft!!.id);assertFalse(f.ui.discardPending);assertNull(f.ui.error);assertEquals(0,f.saves) }
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").assertIsEnabled().performTextInput("合成核对后新输入");drain()
        click("mixed-save");drain()
        compose.runOnIdle { assertEquals("合成核对后新输入",f.local.value.entries.single().originalText) }
    }
    @Test @Config(qualifiers="w840dp-h1000dp")
    fun viewportAndFontChangesPreserveDraftAndRangeThenSaveOnlyOnExplicitAction() {
        val viewport=mutableStateOf(DpSize(800.dp,360.dp))
        val fontScale=mutableStateOf(1f)
        content(viewport,fontScale)
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").performTextInput("合成尺寸切换仍保留图文草稿");drain();add()
        val expected=f.ui.draft!!
        fun checkLayout(width:Int,height:Int,scale:Float,compact:Boolean) {
            compose.runOnIdle { checkNotNull(focusManager).clearFocus(force=true)
                viewport.value=DpSize(width.dp,height.dp);fontScale.value=scale }
            compose.onNodeWithTag("mixed-list").performScrollToIndex(0)
            if(compact) {
                compose.onNodeWithText("生活的小片刻").assertDoesNotExist()
                val heading=compose.onNodeWithTag("mixed-notebook-heading").assertIsDisplayed().getUnclippedBoundsInRoot()
                assertTrue(heading.bottom-heading.top<=88.dp)
            } else compose.onNodeWithText("生活的小片刻").assertIsDisplayed()
            compose.runOnIdle { assertSame(expected,f.ui.draft);assertEquals(0,f.saves);assertTrue(f.queue.isEmpty()) }
        }
        checkLayout(360,800,1f,false)
        // Whole Scaffold is 600dp; content after its bars is shorter than 480dp.
        checkLayout(360,600,1f,false)
        checkLayout(800,480,1f,true)
        checkLayout(800,481,1f,false)
        checkLayout(360,800,2f,true)
        checkLayout(800,360,1f,true)
        policyNode("mixed-policy-AI_NONE").performClick();drain()
        compose.runOnIdle { assertEquals(expected.id,f.ui.draft!!.id);assertEquals(expected.text,f.ui.text)
            assertEquals(expected.images.map{it.intent.id},f.ui.draft!!.images.map{it.intent.id})
            assertEquals(ProcessingPolicy.AI_NONE,f.ui.draft!!.policy);assertEquals(0,f.saves) }
        compose.runOnIdle { viewport.value=DpSize(360.dp,800.dp);fontScale.value=2f }
        click("mixed-save");drain()
        compose.runOnIdle { assertEquals(1,f.saves)
            assertEquals(expected.text,f.local.value.entries.single().originalText)
            assertEquals(ProcessingPolicy.AI_NONE,f.local.value.entries.single().policy) }
    }
    private class Fixture {
        private var draft=CaptureDraft(uuid(),1,"",emptyList());private val bytes=mutableMapOf<String,ByteArray>()
        val queue=ArrayDeque<suspend(EntryRepository)->Unit>();var saves=0;var failSave=false;var failDiscardAck=false
        var failPolicyAck=false;var failPolicyBefore=false;var failPolicyRead=false;var policyCalls=0;var failTextWrite=false
        private val discarded=mutableSetOf<String>()
        val local=mutableStateOf(LocalRecordUiState(ready=true,opening=false,supportsMixedCapture=true,mixedCapture=MixedCaptureUiState()))
        private var entries=emptyList<StoredEntry>()
        val store=object:EntryRepository {
            override val captureRecords=object:CaptureRepository {
                override val supportsProcessingChoice=true
                override suspend fun current():CaptureDraft {
                    if(failPolicyRead)error("Synthetic range read failure")
                    return draft
                }
                override suspend fun changePolicy(expected:CaptureDraft,policy:ProcessingPolicy):CaptureDraft {
                    policyCalls++
                    if(failPolicyBefore)error("Synthetic range write failure")
                    require(policy==ProcessingPolicy.LOCAL_ONLY || policy==ProcessingPolicy.AI_NONE)
                    val changed=CaptureDraft(expected.id,expected.version+1,expected.text,expected.images,policy=policy)
                    if(!(draft.id==changed.id && draft.version==changed.version && draft.policy==changed.policy && draft.text==changed.text && draft.images==changed.images)) {
                        check(draft.id==expected.id && draft.version==expected.version && draft.policy==expected.policy)
                        draft=changed
                    }
                    if(failPolicyAck){failPolicyAck=false;error("Synthetic lost range ACK after commit")}
                    return draft
                }
                override suspend fun edit(expected:CaptureDraft,text:String):CaptureDraft {
                    if(failTextWrite)error("Synthetic text write failure")
                    return next(text=text)
                }
                override suspend fun add(expected:CaptureDraft,intent:AttachmentIntent,bytes:ByteArray):CaptureDraft { this@Fixture.bytes[intent.id]=bytes.copyOf();return next(images=draft.images+CaptureDraftImage(intent,CaptureImageState.READY)) }
                override suspend fun image(expected:CaptureDraft,id:String)=bytes.getValue(id).copyOf()
                override suspend fun remove(draftId:String,imageId:String)=next(images=draft.images.filterNot{it.intent.id==imageId})
                override suspend fun discard(draftId:String):CaptureDraft {
                    if(draft.id==draftId){discarded+=draftId;draft=CaptureDraft(uuid(),1,"",emptyList())}else check(draftId in discarded)
                    if(failDiscardAck){failDiscardAck=false;error("Synthetic lost clear ACK after committed discard")}
                    return draft
                }
                override suspend fun save(expected:CaptureDraft,capturedAtMillis:Long,zone:String):CaptureSaveResult {
                    if(failSave)error("Synthetic failed save")
                    check(draft.id==expected.id && draft.version==expected.version && draft.policy==expected.policy)
                    saves++;entries=entries+StoredEntry(expected.id,1,expected.text,capturedAtMillis,zone,expected.policy)
                    draft=CaptureDraft(uuid(),1,"",emptyList());return CaptureSaveResult(expected.id,false,false)
                }
                override suspend fun recover()=Unit
            }
            override suspend fun entries()=entries
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("Legacy port not used")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("Legacy port not used")
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("Unrelated")
        }
        private fun next(text:String=draft.text,images:List<CaptureDraftImage> = draft.images)=CaptureDraft(draft.id,draft.version+1,text,images,policy=draft.policy).also{draft=it}
        val c=MixedCaptureController({local.value},{local.value=it(local.value)},{queue.add(it);true},PlatformImageDecoder::thumbnail,Clock.systemUTC())
        val ui get()=checkNotNull(local.value.mixedCapture)
    }
    private class Source:SelectedImageSource {var opens=0;override fun mediaType()="image/png";override fun open()=png.inputStream().also{opens++}}
    companion object {private fun uuid()=UUID.randomUUID().toString();private val png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNQCp35HwADvQIQaYgMTgAAAABJRU5ErkJggg==")}
}
