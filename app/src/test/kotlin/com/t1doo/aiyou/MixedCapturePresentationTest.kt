package com.t1doo.aiyou

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
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
    private fun content() {
        runBlocking { f.c.initialize(f.store) }
        compose.setContent {
            registry=LocalSaveableStateRegistry.current
            val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,2f)) {
                AIyouTheme { AIyouApp(local=f.local.value,mixedCapture=f.c) }
            }
        }
    }
    private fun click(tag:String) { compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick() }
    private fun drain(){compose.runOnIdle { runBlocking { while(f.queue.isNotEmpty())f.queue.removeFirst()(f.store) } }}
    private fun add() { click("mixed-add");compose.runOnIdle { val s=f.ui.selection!!;if(!s.launched)f.c.claimPicker(s.requestId);f.c.selected(s.requestId,Source()) };drain() }
    @Test fun actualNowPurePictureHasThumbnailCountRemovalAndSeparateDurableSaveAtLargeFont() {
        content();add();val id=f.ui.draft!!.images.single().intent.id
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-thumb:"+id));compose.onNodeWithTag("mixed-thumb:"+id).assertIsDisplayed()
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-count"));compose.onNodeWithTag("mixed-count").assertTextContains("1 / 20",substring=true)
        compose.runOnIdle { assertEquals(0,f.saves);assertEquals("",f.ui.text) }
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-save"));compose.onNodeWithTag("mixed-save").assertHeightIsAtLeast(48.dp)
        click("mixed-save");compose.runOnIdle { assertEquals(0,f.saves) };drain()
        compose.runOnIdle { assertEquals(1,f.saves);assertEquals("",f.local.value.entries.single().originalText);assertTrue(f.ui.draft!!.images.isEmpty()) }
        val saved=f.local.value.entries.single().id
        click("entry-$saved")
        compose.onNodeWithText("记录原文").assertExists()
        compose.onNodeWithText("图片记录 · 无文字正文；请打开附件查看原图。").assertExists()
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
        compose.onNodeWithTag("mixed-new-records").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(0f,compose.onNodeWithTag("mixed-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(),0f)
        compose.onNodeWithTag("mixed-jump-composer").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("mixed-text").performTextInput("下一份合成草稿");drain()
        val id=f.local.value.entries.single().id;click("entry-$id")
        compose.onNodeWithTag("original-text").assertTextContains("合成今天可直接阅读的原文")
        compose.onNodeWithText("返回列表").performClick()
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-text"))
        compose.onNodeWithTag("mixed-text").assertTextContains("下一份合成草稿")
    }
    private class Fixture {
        private var draft=CaptureDraft(uuid(),1,"",emptyList());private val bytes=mutableMapOf<String,ByteArray>()
        val queue=ArrayDeque<suspend(EntryRepository)->Unit>();var saves=0;var failSave=false
        val local=mutableStateOf(LocalRecordUiState(ready=true,opening=false,supportsMixedCapture=true,mixedCapture=MixedCaptureUiState()))
        private var entries=emptyList<StoredEntry>()
        val store=object:EntryRepository {
            override val captureRecords=object:CaptureRepository {
                override suspend fun current()=draft
                override suspend fun edit(expected:CaptureDraft,text:String)=next(text=text)
                override suspend fun add(expected:CaptureDraft,intent:AttachmentIntent,bytes:ByteArray):CaptureDraft { this@Fixture.bytes[intent.id]=bytes.copyOf();return next(images=draft.images+CaptureDraftImage(intent,CaptureImageState.READY)) }
                override suspend fun image(expected:CaptureDraft,id:String)=bytes.getValue(id).copyOf()
                override suspend fun remove(draftId:String,imageId:String)=next(images=draft.images.filterNot{it.intent.id==imageId})
                override suspend fun discard(draftId:String):CaptureDraft { draft=CaptureDraft(uuid(),1,"",emptyList());return draft }
                override suspend fun save(expected:CaptureDraft,capturedAtMillis:Long,zone:String):CaptureSaveResult {
                    if(failSave)error("Synthetic failed save")
                    saves++;entries=entries+StoredEntry(expected.id,1,expected.text,capturedAtMillis,zone,ProcessingDefaults.policy)
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
        private fun next(text:String=draft.text,images:List<CaptureDraftImage> = draft.images)=CaptureDraft(draft.id,draft.version+1,text,images).also{draft=it}
        val c=MixedCaptureController({local.value},{local.value=it(local.value)},{queue.add(it);true},PlatformImageDecoder::thumbnail,Clock.systemUTC())
        val ui get()=checkNotNull(local.value.mixedCapture)
    }
    private class Source:SelectedImageSource {var opens=0;override fun mediaType()="image/png";override fun open()=png.inputStream().also{opens++}}
    companion object {private fun uuid()=UUID.randomUUID().toString();private val png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNQCp35HwADvQIQaYgMTgAAAABJRU5ErkJggg==")}
}
