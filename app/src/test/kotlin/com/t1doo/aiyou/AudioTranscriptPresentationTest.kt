package com.t1doo.aiyou
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
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
        override suspend fun preview(intent:AttachmentIntent)=when(intent){this@AudioTranscriptPresentationTest.intent->anchor;intentB->anchorB;else->error("Unknown original")}
        override suspend fun read(anchor:AudioTranscriptSource)=savedFor(anchor)
        override suspend fun write(request:AudioTranscriptWrite):AudioTranscriptSnapshot {val old=savedFor(request.anchor);check(if(old==null)request.expected==null else request.expected?.same(old)==true);writes++
            val history=if(request.withdraw)old!!.history else old?.history.orEmpty()+AudioTranscriptVersion((old?.history?.size?:0)+1,request.text!!,request.at)
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
    private val c=AudioTranscriptController({state.value},{state.value=it(state.value)},{queue.add(it);true},Clock.systemUTC())
    private val attachments=LocalAttachmentController({queue.add(it);true},{error("No image decode")},canUsePanel={it.hasRecordAccess(state.value)})
    @After fun dispose(){c.dispose();attachments.markDisposed();runBlocking{attachments.disposeOnIo()}}
    @Composable private fun Screen(){val d=LocalDensity.current;val r=LocalSaveableStateRegistry.current;SideEffect{registry=r};CompositionLocalProvider(LocalDensity provides Density(d.density,2f)){AIyouTheme{Box(Modifier.size(320.dp,640.dp)){AIyouApp(previewDestination=Destination.MEMORIES,local=state.value,currentRecords={state.value},attachments=attachments,audioTranscripts=c)}}}}
    private fun click(tag:String){compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()}
    private fun drain(){compose.runOnIdle{runBlocking{while(true)(queue.poll()?:break)(owner)}};compose.waitForIdle()}
    private fun openRecord(record:StoredEntry){compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0);compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-"+record.id));compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-"+record.id))).performClick();compose.waitForIdle()}
    private fun openAttachments(){compose.onNodeWithText("查看附件").assertIsDisplayed().assertIsEnabled().performClick();drain()}
    private fun detail(){openRecord(entry);openAttachments();click("attachment-transcript:"+intent.id);drain()}
    private fun backToSearch(){click("transcript-close");compose.onNodeWithText("返回原文").assertIsDisplayed().performClick();compose.waitForIdle();compose.onNodeWithText("返回列表").assertIsDisplayed().performClick();compose.waitForIdle();compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0);compose.showNotebookToolsFor("transcripts-open");compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("transcripts-open"));compose.onNodeWithTag("transcripts-open").performClick();drain()}
    private fun query(text:String){compose.onNodeWithTag("transcript-keyword").performScrollTo().performTextReplacement(text);compose.onNodeWithTag("transcript-count").assertDoesNotExist();click("transcript-search");drain()}
    @Test fun doubleFontManualCorrectionWithdrawalAndKeywordJumpReachExactAudioWithoutChangingOriginal(){
        compose.setContent{Screen()};detail();val save=compose.onNodeWithTag("transcript-save").performScrollTo().assertIsDisplayed();assertTrue(save.getUnclippedBoundsInRoot().height>=48.dp)
        compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("合成人工稿：淡茶");click("transcript-save");drain();assertEquals(1,writes)
        compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("人工纠正：清水");click("transcript-save");drain();assertEquals(2,saved!!.history.size)
        backToSearch();query("淡茶");assertTrue(state.value.audioTranscripts!!.items.isEmpty());query("清水");click("transcript-source:"+saved!!.id);drain()
        assertEquals(intent,attachments.state.value!!.focusAudio);compose.onNodeWithTag("attachment-"+intent.id).performScrollTo().assertIsDisplayed()
        click("attachment-transcript:"+intent.id);drain();click("transcript-withdraw");click("transcript-withdraw-cancel");assertEquals(2,writes)
        click("transcript-withdraw");click("transcript-withdraw-confirm");drain();assertTrue(saved!!.withdrawn);assertEquals(3,writes)
        backToSearch();query("清水");assertTrue(state.value.audioTranscripts!!.items.isEmpty());assertEquals(listOf(entry),state.value.entries);assertArrayEquals(wave,runBlocking{media.read(intent.id)})
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
