package com.t1doo.aiyou
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.time.Clock
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.json.JSONObject
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioTranscriptPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val entry=StoredEntry("synthetic-audio-record",1,"合成原音说明，不是转写。",55,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val wave=LocalPcmAudio.encode(ShortArray(441){(it%9).toShort()})
    private val intent=AttachmentIntent(UUID.randomUUID().toString(),entry.id,1,"audio/wav",MessageDigest.getInstance("SHA-256").digest(wave).joinToString(""){"%02x".format(it.toInt() and 255)},wave.size.toLong())
    private val anchor=AudioTranscriptSource(intent,OriginalExports.hash(entry.originalText),0,55,"UTC",ProcessingPolicy.LOCAL_ONLY,441)
    private val entryB=StoredEntry("synthetic-audio-record-b",1,"合成第二条原音说明。",66,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val waveB=LocalPcmAudio.encode(ShortArray(441){(it%13).toShort()})
    private val intentB=AttachmentIntent(UUID.randomUUID().toString(),entryB.id,1,"audio/wav",MessageDigest.getInstance("SHA-256").digest(waveB).joinToString(""){"%02x".format(it.toInt() and 255)},waveB.size.toLong())
    private val anchorB=AudioTranscriptSource(intentB,OriginalExports.hash(entryB.originalText),0,66,"UTC",ProcessingPolicy.LOCAL_ONLY,441)
    private var savedB:AudioTranscriptSnapshot?=null
    private fun savedFor(a:AudioTranscriptSource)=when(a){anchor->saved;anchorB->savedB;else->error("Unknown exact source")}
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,entries=listOf(entry),supportsAudioTranscripts=true))
    private val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>();private var saved:AudioTranscriptSnapshot?=null;private var writes=0;private var registry:SaveableStateRegistry?=null
    private val port=object:AudioTranscriptRepository {
        override suspend fun playbackReference(snapshot:AudioTranscriptSnapshot):AudioTranscriptPlaybackReference {
            check(savedFor(snapshot.anchor)?.same(snapshot)==true&&!snapshot.withdrawn)
            return object:AudioTranscriptPlaybackReference {override val anchor=snapshot.anchor;override val range=snapshot.current.range}
        }
        override suspend fun preview(intent:AttachmentIntent)=when(intent){this@AudioTranscriptPresentationTest.intent->anchor;intentB->anchorB;else->error("Unknown original")}
        override suspend fun read(anchor:AudioTranscriptSource)=savedFor(anchor)
        override suspend fun write(request:AudioTranscriptWrite):AudioTranscriptSnapshot {val old=savedFor(request.anchor);check(if(old==null)request.expected==null else request.expected?.same(old)==true);writes++
            val history=if(request.withdraw)old!!.history else old?.history.orEmpty()+AudioTranscriptVersion((old?.history?.size?:0)+1,request.text!!,request.at,request.range)
            return AudioTranscriptSnapshot(request.id,request.anchor,(old?.version?:0)+1,request.withdraw,history).also{if(request.anchor==anchor)saved=it else savedB=it}
        }
        override suspend fun search(keyword:String,cursor:PreferenceCursor?):AudioTranscriptPage {val values=listOfNotNull(saved,savedB).filter{!it.withdrawn&&it.current.text.contains(keyword)};return object:AudioTranscriptPage{override val keyword=keyword;override val cursor=cursor;override val next:PreferenceCursor?=null;override val items=values}}
        override suspend fun resolve(page:AudioTranscriptPage,item:AudioTranscriptSnapshot?,publish:()->Unit):Boolean {if(page.items.any{savedFor(it.anchor)?.same(it)!=true})return false;publish();return true}
    }
    private val media=object:AttachmentStore {
        override suspend fun save(intent:AttachmentIntent,source:()->java.io.InputStream)=error("No audio mutation")
        override suspend fun list(entryId:String,revision:Int)=listOf(intent,intentB).filter{it.entryId==entryId&&it.revision==revision}.map{LocalAttachment(it,AttachmentState.READY)}
        override suspend fun read(id:String)=when(id){intent.id->wave.copyOf();intentB.id->waveB.copyOf();else->error("Unknown audio")}
        override suspend fun recover(){}
    }
    private val owner=object:EntryRepository {
        override val audioTranscripts=port;override val attachments=media
        override suspend fun readDraft():TextDraft?=null;override suspend fun updateDraft(text:String)=error("No draft");override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("No capture")
        override suspend fun entries()=state.value.entries;override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>();override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("No original write")
    }
    private val c=AudioTranscriptController({state.value},{state.value=it(state.value)},{queue.add(it);true},Clock.systemUTC(),beforeWrite={write->
        attachments.state.value?.takeIf{it.transcriptSource&&it.focusAudio==write.anchor.intent}?.let{attachments.retireFocusedAudio(it.sessionId)}
    })
    private val attachments=LocalAttachmentController({queue.add(it);true},{error("No image decode")},canUsePanel={it.hasRecordAccess(state.value)})
    @After fun dispose(){c.dispose();attachments.markDisposed();runBlocking{attachments.disposeOnIo()}}
    private var viewWidth by mutableStateOf(320)
    private var viewHeight by mutableStateOf(640)
    private var viewFont by mutableStateOf(2f)
    @Composable private fun Screen(){val d=LocalDensity.current;val r=LocalSaveableStateRegistry.current;SideEffect{registry=r};CompositionLocalProvider(LocalDensity provides Density(d.density,viewFont)){AIyouTheme{Box(Modifier.size(viewWidth.dp,viewHeight.dp)){AIyouApp(previewDestination=Destination.MEMORIES,local=state.value,currentRecords={state.value},attachments=attachments,audioTranscripts=c)}}}}
    private fun click(tag:String){compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()}
    private fun drain(){compose.runOnIdle{runBlocking{while(true)(queue.poll()?:break)(owner)}};compose.waitForIdle()}
    private fun openRecord(record:StoredEntry){compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0);compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-"+record.id));compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-"+record.id))).performClick();compose.waitForIdle()}
    private fun openAttachments(){compose.onNodeWithText("查看附件").assertIsDisplayed().assertIsEnabled().performClick();drain()}
    private fun detail(){openRecord(entry);openAttachments();click("attachment-transcript:"+intent.id);drain()}
    private fun backToSearch(panelRetired:Boolean=false){click("transcript-close");compose.waitForIdle();if(panelRetired){assertNull(attachments.state.value)}else{compose.onNodeWithText("返回原文").assertIsDisplayed().performClick();compose.waitForIdle()};compose.onNodeWithText("返回列表").assertIsDisplayed().performClick();compose.waitForIdle();compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0);compose.showNotebookToolsFor("transcripts-open");compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("transcripts-open"));compose.onNodeWithTag("transcripts-open").performClick();drain()}
    private fun query(text:String){compose.onNodeWithTag("transcript-keyword").performScrollTo().performTextReplacement(text);compose.onNodeWithTag("transcript-count").assertDoesNotExist();click("transcript-search");drain()}
    private fun frame(name:String,tag:String) {
        val image=compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val file=File("build/reports/tests/testDebugUnitTest/transcript-ranges/$name.png");file.parentFile.mkdirs()
        file.outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    /** Observe the production Dialog's Android resources and its real editable-text layout. */
    private fun editorScale(expected:Float):JSONObject {
        val field=compose.onNodeWithTag("transcript-text").performScrollTo().assertIsDisplayed().assertIsEnabled()
        val layouts=mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult){assertTrue(it(layouts))}
        assertEquals("Exactly one real editable-text layout",1,layouts.size)
        val layout=layouts.single()
        assertEquals(expected,layout.layoutInput.density.fontScale,0f)
        assertFalse("Editor text must not overflow its actual paragraph",layout.hasVisualOverflow)
        assertTrue(layout.lineCount>0)
        assertEquals(state.value.audioTranscripts!!.text,layout.layoutInput.text.text)
        assertEquals(layout.layoutInput.text.length,layout.getLineEnd(layout.lineCount-1,visibleEnd=true))
        var configurationScale=0f
        compose.runOnIdle{
            val dialog=checkNotNull(ShadowDialog.getLatestDialog())
            assertTrue(dialog.isShowing)
            configurationScale=dialog.context.resources.configuration.fontScale
            assertEquals(expected,configurationScale,0f)
        }
        return JSONObject().put("configurationFontScale",configurationScale.toDouble())
            .put("layoutFontScale",layout.layoutInput.density.fontScale.toDouble())
            .put("density",layout.layoutInput.density.density.toDouble())
            .put("firstLineHeightPx",(layout.getLineBottom(0)-layout.getLineTop(0)).toDouble())
            .put("layoutWidthPx",layout.size.width).put("layoutHeightPx",layout.size.height)
            .put("lineCount",layout.lineCount).put("hasVisualOverflow",layout.hasVisualOverflow)
            .put("text_sha256",OriginalExports.hash(layout.layoutInput.text.text))
    }
    private fun recordEditorScale(fontCase:String,measurement:JSONObject) {
        check(fontCase in setOf("normal","double"))
        val head=System.getenv("GITHUB_SHA")?:ProcessBuilder("git","rev-parse","HEAD").redirectErrorStream(true).start().let{process->
            try {
                check(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)&&process.exitValue()==0)
                process.inputStream.bufferedReader().use{it.readText().trim()}
            }finally{if(process.isAlive)process.destroy()}
        }
        check(Regex("[0-9a-f]{40}").matches(head))
        val file=File("build/reports/tests/testDebugUnitTest/transcript-ranges/editor-fontscale-$fontCase.json")
        file.parentFile.mkdirs()
        file.writeText(JSONObject().put("source_sha",head).put("font_case",fontCase)
            .put("kind","Actual production Dialog resources and text layout; JVM simulation, not device evidence")
            .put("measurement",measurement).toString(2))
    }
    private fun visibleControl(tag:String)=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled().also{
        it.assertHeightIsAtLeast(48.dp)
        assertEquals("Whole control must be inside the Dialog viewport",it.getUnclippedBoundsInRoot(),it.getBoundsInRoot())
    }
    /** Queue stays blocked across real AIyouApp recomposition; no callback substitutes for button clicks. */
    private fun pendingFocusedChange(withdraw:Boolean):AudioTranscriptUi {
        viewFont=1f;compose.setContent{Screen()};detail()
        compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("合成路由转写")
        click("transcript-save");drain();assertEquals(1,writes)
        backToSearch();query("路由转写");click("transcript-source:"+saved!!.id);drain()
        click("attachment-transcript:"+intent.id);drain()
        click("transcript-range-custom");compose.onNodeWithTag("transcript-range-from").performScrollTo().performTextReplacement("0.001")
        val before=state.value.audioTranscripts!!
        if(withdraw){click("transcript-withdraw");click("transcript-withdraw-confirm")}else click("transcript-save")
        compose.waitForIdle() // Consume panel removal and the actual route/disposal effects before releasing the actor queue.
        compose.onNodeWithTag("transcript-dialog").assertIsDisplayed()
        compose.onNodeWithTag("transcript-refresh").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle{assertNull(attachments.state.value);assertEquals(before.session,state.value.audioTranscripts!!.session);assertNotNull(state.value.audioTranscripts!!.pending);assertEquals(1,writes)}
        return before
    }
    @Test fun focusedRangeSaveSurvivesPanelRemovalAndRecompositionBeforeActorRelease(){
        val before=pendingFocusedChange(false);drain()
        compose.runOnIdle{assertEquals(2,writes);assertEquals(AudioSampleRange(45,441),saved!!.current.range);assertEquals(before.session,state.value.audioTranscripts!!.session);assertNull(state.value.audioTranscripts!!.pending);assertNotNull(state.value.audioTranscripts!!.notice)}
        compose.onNodeWithTag("transcript-dialog").assertIsDisplayed()
    }
    @Test fun focusedWithdrawalSurvivesPanelRemovalAndRecompositionBeforeActorRelease(){
        val before=pendingFocusedChange(true);drain()
        compose.runOnIdle{assertEquals(2,writes);assertTrue(saved!!.withdrawn);assertEquals(before.session,state.value.audioTranscripts!!.session);assertNull(state.value.audioTranscripts!!.pending)}
        compose.onNodeWithTag("transcript-dialog").assertIsDisplayed()
    }
    @Test fun explicitCloseStillCancelsAnUnstartedFocusedCorrectionAfterPanelRetirement(){
        pendingFocusedChange(false);click("transcript-close");compose.waitForIdle();drain()
        compose.runOnIdle{assertNull(state.value.audioTranscripts);assertEquals(1,writes);assertNull(saved!!.current.range)}
    }
    @Test fun ownerReplacementStillRetiresPendingFocusedWithdrawalWithoutPublishingIntoNewOwner(){
        pendingFocusedChange(true)
        compose.runOnIdle{state.value=state.value.copy(recordAuthorizationOwner=object:RecordAuthorizationRepository {
            override fun readAuthorization(source:SourceVersion):RecordAuthorizationSnapshot=error("No authority IO")
            override fun previewAuthorization(expected:RecordAuthorizationSource,purpose:TextPurpose,clock:()->java.time.Instant):RecordAuthorizationPreview=error("No authority IO")
            override fun changeAuthorization(request:RecordAuthorizationChange,clock:()->java.time.Instant):RecordAuthorizationReceipt=error("No authority IO")
        })}
        compose.waitForIdle();drain()
        compose.runOnIdle{assertNull(state.value.audioTranscripts);assertEquals(1,writes);assertFalse(saved!!.withdrawn)}
    }
    @Test fun normalPlatformFontRecordsActualRangeEditorLayoutWithoutSavingPrivateInput(){
        RuntimeEnvironment.setFontScale(1f)
        viewFont=1f;compose.setContent{Screen()};detail()
        compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("合成人工片段 · 不自动播放")
        click("transcript-range-custom");compose.onNodeWithTag("transcript-range-from").performScrollTo().performTextReplacement("0.001")
        click("transcript-range-tail");compose.onNodeWithTag("transcript-range-through").performScrollTo().performTextReplacement("0.005")
        val normal=editorScale(1f)
        visibleControl("transcript-save");frame("01-normal-editor","transcript-dialog")
        recordEditorScale("normal",normal)
        assertEquals(0,writes);assertEquals("0.001",state.value.audioTranscripts!!.rangeFrom)
        visibleControl("transcript-close").performClick();drain()
        assertNull(state.value.audioTranscripts);assertEquals(0,writes)
    }
    @Test fun explicitHumanRangeAtNormalAndDoubleFontReachesExactFocusedSourceWithoutAutoplay(){
        // Configure before creating production content. This is a font-layout test, not a live
        // Activity configuration-change test; the verifier compares the separate 1x sample.
        RuntimeEnvironment.setFontScale(2f)
        viewFont=2f;compose.setContent{Screen()};detail()
        compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("合成人工片段 · 不自动播放")
        click("transcript-range-custom");compose.onNodeWithTag("transcript-range-from").performScrollTo().performTextReplacement("0.001")
        click("transcript-range-tail");compose.onNodeWithTag("transcript-range-through").performScrollTo().performTextReplacement("0.005")
        val large=editorScale(2f)
        visibleControl("transcript-range-from").assertTextContains("0.001")
        visibleControl("transcript-range-through").performTextReplacement("9999999")
        visibleControl("transcript-save").performClick();drain();assertEquals(0,writes)
        val error=compose.onNodeWithTag("transcript-range-through-error").performScrollTo().assertIsDisplayed()
            .assertTextEquals("终点输入过长，请明确纠正。")
        assertEquals(error.getUnclippedBoundsInRoot(),error.getBoundsInRoot())
        visibleControl("transcript-range-through").performTextReplacement("0.005")
        compose.onNodeWithTag("transcript-range-through-error").assertDoesNotExist()
        visibleControl("transcript-close") // Check cancellation remains reachable, without discarding this unsaved edit.
        visibleControl("transcript-save");frame("02-double-font-editor","transcript-dialog")
        recordEditorScale("double",large)
        compose.runOnIdle{assertEquals(0,writes);assertEquals("0.001",state.value.audioTranscripts!!.rangeFrom)}
        click("transcript-save");drain();assertEquals(AudioSampleRange(45,221),saved!!.current.range)
        click("transcript-withdraw")
        visibleControl("transcript-withdraw-confirm")
        visibleControl("transcript-withdraw-cancel").performClick();drain()
        assertEquals(1,writes);assertFalse(saved!!.withdrawn);assertEquals(AudioSampleRange(45,221),saved!!.current.range)
        compose.runOnIdle{viewWidth=320;viewHeight=640;viewFont=2f};compose.waitForIdle()
        backToSearch();query("人工片段");click("transcript-source:"+saved!!.id);drain()
        compose.runOnIdle{val p=attachments.state.value!!;assertTrue(p.transcriptSource);assertEquals(anchor,p.transcriptReference!!.anchor);assertEquals(AudioSampleRange(45,221),p.transcriptReference!!.range);assertEquals(1,writes)}
        compose.onNodeWithTag("attachment-audio-play:"+intent.id).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        frame("04-focused-source","attachment-scroll") // No platform output was injected into this synthetic UI fixture.
        assertEquals(entry.originalText,state.value.entries.single().originalText)
    }
    @Test @Config(sdk=[35],qualifiers="w800dp-h360dp")
    fun actualLandscapeWindowKeepsRangeInputAndSaveReachableWithoutChangingTextOrSource(){
        viewWidth=800;viewHeight=360;viewFont=1f
        compose.setContent{Screen()};detail()
        compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("合成横屏人工片段")
        click("transcript-range-custom");compose.onNodeWithTag("transcript-range-from").performScrollTo().performTextReplacement("0.001")
        click("transcript-range-tail");compose.onNodeWithTag("transcript-range-through").performScrollTo().performTextReplacement("0.005")
        compose.onNodeWithTag("transcript-save").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertIsEnabled()
        frame("03-landscape-editor","transcript-dialog")
        assertEquals(0,writes);assertEquals("0.001",state.value.audioTranscripts!!.rangeFrom)
        click("transcript-save");drain();assertEquals(AudioSampleRange(45,221),saved!!.current.range)
        assertEquals(entry.originalText,state.value.entries.single().originalText)
    }
    @Test fun invalidCustomEndpointRemainsVisibleThroughTextEditAndRefreshUntilExplicitCorrection(){
        compose.setContent{Screen()};detail();click("transcript-range-custom")
        compose.onNodeWithTag("transcript-range-from").performScrollTo().performTextReplacement("9999999")
        compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("合成私有范围输入")
        click("transcript-save");drain();assertEquals(0,writes)
        compose.onNodeWithTag("transcript-range-from-error").performScrollTo().assertTextEquals("起点输入过长，请明确纠正。")
        click("transcript-refresh");drain();click("transcript-range-custom")
        compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("仅改字不能接受旧范围")
        click("transcript-save");drain();assertEquals(0,writes)
        compose.onNodeWithTag("transcript-range-from-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("transcript-range-from").performScrollTo().performTextReplacement("0")
        click("transcript-save");drain();assertEquals(1,writes);assertEquals(AudioSampleRange(0,441),saved!!.current.range)
        assertFalse(registry!!.performSave().toString().contains("9999999"));assertEquals(entry.originalText,state.value.entries.single().originalText)
    }
    @Test fun doubleFontManualCorrectionWithdrawalAndKeywordJumpReachExactAudioWithoutChangingOriginal(){
        compose.setContent{Screen()};detail();val save=compose.onNodeWithTag("transcript-save").performScrollTo().assertIsDisplayed();assertTrue(save.getUnclippedBoundsInRoot().height>=48.dp)
        compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("合成人工稿：淡茶");click("transcript-save");drain();assertEquals(1,writes)
        compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("人工纠正：清水");click("transcript-save");drain();assertEquals(2,saved!!.history.size)
        backToSearch();query("淡茶");assertTrue(state.value.audioTranscripts!!.items.isEmpty());query("清水");click("transcript-source:"+saved!!.id);drain()
        assertEquals(intent,attachments.state.value!!.focusAudio);compose.onNodeWithTag("attachment-"+intent.id).performScrollTo().assertIsDisplayed()
        click("attachment-transcript:"+intent.id);drain();click("transcript-withdraw");click("transcript-withdraw-cancel");assertEquals(2,writes)
        click("transcript-withdraw");click("transcript-withdraw-confirm");drain();assertTrue(saved!!.withdrawn);assertEquals(3,writes)
        backToSearch(panelRetired=true);query("清水");assertTrue(state.value.audioTranscripts!!.items.isEmpty());assertEquals(listOf(entry),state.value.entries);assertArrayEquals(wave,runBlocking{media.read(intent.id)})
    }
    @Test fun unsavedManualTextAndRetiredSessionDoNotSurviveRestorationAsWriteAuthority(){
        val restoration=StateRestorationTester(compose);restoration.setContent{Screen()};detail();compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("仅合成人工私有输入")
        val old=state.value.audioTranscripts!!;compose.runOnIdle{assertFalse(registry!!.performSave().toString().contains("仅合成人工私有输入"))}
        restoration.emulateSavedInstanceStateRestore();compose.waitForIdle();compose.runOnIdle{c.save(old.session)};drain();assertEquals(0,writes);assertNull(state.value.audioTranscripts)
    }
    @Test fun recreatedPrivateAudioRouteRetiresRetainedFocusBeforeOpeningOrLocatingAnotherRecord(){
        state.value=state.value.copy(entries=listOf(entry,entryB))
        val restoration=StateRestorationTester(compose);restoration.setContent{Screen()}
        detail();compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("合成甲原音稿");click("transcript-save");drain()
        backToSearch();query("甲原音稿");click("transcript-source:"+saved!!.id);drain()
        val old=attachments.state.value!!;assertEquals(intent,old.focusAudio)
        compose.onNodeWithTag("attachment-"+intent.id).performScrollTo().assertIsDisplayed()
        // Same live controllers survive, but the private RAM proof does not.
        restoration.emulateSavedInstanceStateRestore();compose.waitForIdle();drain()
        compose.runOnIdle{assertNull(attachments.state.value)}
        compose.onNodeWithTag("attachment-"+intent.id).assertDoesNotExist()
        openRecord(entryB);openAttachments()
        compose.runOnIdle{assertEquals(entryB.id,attachments.state.value!!.entryId);assertNull(attachments.state.value!!.focusAudio)}
        compose.onNodeWithTag("attachment-"+intent.id).assertDoesNotExist()
        compose.onNodeWithTag("attachment-"+intentB.id).performScrollTo().assertIsDisplayed()
        compose.runOnIdle{attachments.retireFocusedAudio(old.sessionId);attachments.refresh(old.sessionId)};drain()
        assertEquals(entryB.id,attachments.state.value!!.entryId)
        click("attachment-transcript:"+intentB.id);drain()
        compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("合成乙原音稿");click("transcript-save");drain()
        backToSearch();query("乙原音稿");click("transcript-source:"+savedB!!.id);drain()
        compose.runOnIdle{assertEquals(intentB,attachments.state.value!!.focusAudio);attachments.retireFocusedAudio(old.sessionId)}
        compose.onNodeWithTag("attachment-"+intentB.id).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("attachment-"+intent.id).assertDoesNotExist()
        assertEquals(2,writes)
    }

}
