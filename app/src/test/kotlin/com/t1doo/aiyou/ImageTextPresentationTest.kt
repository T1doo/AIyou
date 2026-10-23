package com.t1doo.aiyou

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production UI/controllers and bounded platform PNG decode; inference output is explicitly synthetic.
 * These tests are JVM simulations, not OCR, Activity recreation, or device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageTextPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val entry=StoredEntry("synthetic-image-a",1,"合成甲图原文。",5,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val entryB=StoredEntry("synthetic-image-b",1,"合成乙图原文。",6,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val bytes=png(Color.RED);private val bytesB=png(Color.BLUE)
    private val intent=intent(entry,bytes);private val intentB=intent(entryB,bytesB)
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,entries=listOf(entry,entryB),supportsImageTexts=true))
    private val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>();private val gate=CaptureDraftGate()
    private val saved=linkedMapOf<String,ImageTextSnapshot>();private var writes=0;private var engineCalls=0;private var engineEnabled=false
    private var lastInput:ByteArray?=null
    private fun anchor(intent:AttachmentIntent):ImageTextSource {
        val e=state.value.entries.single{it.id==intent.entryId&&it.currentRevision==intent.revision}
        return ImageTextSource(intent,OriginalExports.hash(e.originalText),0,e.capturedAtMillis,e.captureTimeZone,e.policy)
    }
    private val port=object:ImageTextRepository {
        override suspend fun preview(intent:AttachmentIntent)=anchor(intent)
        override suspend fun read(anchor:ImageTextSource)=saved[anchor.intent.id].also{check(anchor==this@ImageTextPresentationTest.anchor(anchor.intent))}
        override suspend fun write(request:ImageTextWrite):ImageTextSnapshot? {
            val old=saved[request.anchor.intent.id];check(request.anchor==anchor(request.anchor.intent));check(if(old==null)request.expected==null else request.expected?.same(old)==true)
            writes++;if(request.delete){saved.remove(request.anchor.intent.id);return null}
            val history=old?.history.orEmpty()+ImageTextVersion((old?.history?.size?:0)+1,request.extraction?.text?:request.text!!,old!=null,request.at)
            return ImageTextSnapshot(request.id,request.anchor,old?.engine?:request.extraction!!.engine,history).also{saved[request.anchor.intent.id]=it}
        }
        override suspend fun search(keyword:String,cursor:PreferenceCursor?):ImageTextPage {
            val values=saved.values.filter{state.value.currentImageTextSource(it.anchor)&&it.current.text.contains(keyword)}.toList()
            return object:ImageTextPage{override val keyword=keyword;override val cursor=cursor;override val next:PreferenceCursor?=null;override val items=values}
        }
        override suspend fun resolve(page:ImageTextPage,item:ImageTextSnapshot?,publish:()->Unit):Boolean {
            if(page.items.any{saved[it.anchor.intent.id]?.same(it)!=true||!state.value.currentImageTextSource(it.anchor)})return false
            publish();return true
        }
    }
    private val media=object:AttachmentStore {
        override suspend fun save(intent:AttachmentIntent,source:()->java.io.InputStream)=error("No original image mutation")
        override suspend fun list(entryId:String,revision:Int)=listOf(intent,intentB).filter{it.entryId==entryId&&it.revision==revision}.map{LocalAttachment(it,AttachmentState.READY)}
        override suspend fun read(id:String)=when(id){intent.id->bytes.copyOf();intentB.id->bytesB.copyOf();else->error("Unknown exact image")}
        override suspend fun recover(){}
    }
    private val owner=object:EntryRepository {
        override val imageTexts=port;override val attachments=media
        override suspend fun entries()=state.value.entries;override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun readDraft():TextDraft?=null;override suspend fun updateDraft(text:String)=error("No draft");override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("No capture")
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("No original edit")
    }
    private val attachments=LocalAttachmentController({queue.add(it);true},PlatformImageDecoder::decode,canUsePanel={it.hasRecordAccess(state.value)},sourceUi={gate.sourceUi(it)})
    private val engine=object:ImageTextRecognizer {
        override val available get()=engineEnabled
        override suspend fun recognize(source:ImageTextSource,encoded:ByteArray,cancelled:()->Boolean):ImageTextExtraction? {
            check(!cancelled());engineCalls++;lastInput=encoded
            check(encoded.contentEquals(if(source.intent==intent)bytes else bytesB))
            return ImageTextExtraction(ImageTextEngine("synthetic-compose-port","1",true),if(source.intent==intent)"合成甲图首稿"else"合成乙图首稿")
        }
    }
    private val controller=ImageTextController({state.value},{state.value=it(state.value)},{queue.add(it);true},Clock.systemUTC(),engine,{gate.sourceUi(it)},canOpen={selected->
        val p=attachments.state.value
        if(selected==null)p==null else p?.let{it.entryId==selected.entryId&&it.pictureId==selected.id&&it.picture!=null&&!it.busy&&it.hasRecordAccess(state.value)}==true
    })
    @After fun dispose(){controller.dispose();attachments.markDisposed();runBlocking{attachments.disposeOnIo()}}
    @Composable private fun Screen(){val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,2f)){AIyouTheme{Box(Modifier.size(320.dp,640.dp)){AIyouApp(previewDestination=Destination.MEMORIES,local=state.value,currentRecords={state.value},attachments=attachments,imageTexts=controller)}}}}
    private fun click(tag:String){compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()}
    private fun drain(){compose.runOnIdle{runBlocking{while(true)(queue.poll()?:break)(owner)}};compose.waitForIdle()}
    private fun record(e:StoredEntry){compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0);compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-"+e.id));compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-"+e.id))).performClick();compose.waitForIdle()}
    private fun viewImage(i:AttachmentIntent){
        compose.onNodeWithText("查看附件").performScrollTo().assertIsDisplayed().performClick();drain()
        compose.onNodeWithTag("attachment-"+i.id).performScrollTo();compose.onNode(hasText("查看图片") and hasAnyAncestor(hasTestTag("attachment-"+i.id))).performClick();drain()
        assertEquals(i.id,attachments.state.value!!.pictureId);compose.onNodeWithTag("attachment-picture").assertIsDisplayed()
    }
    private fun form(i:AttachmentIntent){compose.onNodeWithTag("image-text-open:"+i.id).assertIsDisplayed().performClick();drain()}
    private fun extract(){click("image-text-extract");drain();compose.onNodeWithTag("image-text-origin").performScrollTo().assertTextEquals("合成端口测试结果 · 非真实 OCR");assertTrue(lastInput!!.all{it==0.toByte()})}
    private fun backToSearch(){
        click("image-text-close")
        if(compose.onAllNodesWithText("返回附件列表").fetchSemanticsNodes().isNotEmpty()){compose.onNodeWithText("返回附件列表").assertIsDisplayed().performClick();compose.waitForIdle()}
        compose.onNodeWithText("返回原文").assertIsDisplayed().performClick();compose.waitForIdle()
        compose.onNodeWithText("返回列表").assertIsDisplayed().performClick();compose.waitForIdle()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0);compose.showNotebookToolsFor("image-texts-open");compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("image-texts-open"));compose.onNodeWithTag("image-texts-open").performClick();drain()
    }
    private fun query(text:String){compose.onNodeWithTag("image-text-keyword").performScrollTo().performTextReplacement(text);compose.onNodeWithTag("image-text-count").assertDoesNotExist();click("image-text-search");drain()}
    @Test fun unavailableModelAndAiNoneNeverInferCreateManualReplacementOrChangeOriginal(){
        compose.setContent{Screen()};record(entry);viewImage(intent);form(intent)
        compose.onNodeWithTag("image-text-value").assertDoesNotExist();click("image-text-extract")
        compose.onNodeWithTag("image-text-error").performScrollTo().assertTextEquals("当前未安装可用的本地中文文字提取能力，原图已保留；未运行 OCR，也未生成文字。");assertEquals(0,engineCalls);assertEquals(0,writes)
        click("image-text-close");compose.runOnIdle{state.value=state.value.copy(entries=listOf(entry.copy(policy=ProcessingPolicy.AI_NONE),entryB));engineEnabled=true};form(intent)
        click("image-text-extract");compose.onNodeWithTag("image-text-error").performScrollTo().assertTextEquals("此记录设置为不使用 AI。请先明确改为仅本机处理，再主动提取；当前未运行 OCR。")
        assertEquals(0,engineCalls);assertTrue(saved.isEmpty());assertEquals(0,writes);assertArrayEquals(bytes,runBlocking{media.read(intent.id)})
    }
    @Test fun doubleFontControlledExtractionCorrectionKeywordJumpAndConfirmedDeletionReachExactOriginal(){
        engineEnabled=true;compose.setContent{Screen()};record(entry);viewImage(intent);form(intent)
        val button=compose.onNodeWithTag("image-text-extract").performScrollTo().assertIsDisplayed();assertTrue(button.getUnclippedBoundsInRoot().height>=48.dp)
        extract();assertEquals(1,engineCalls);assertEquals(1,writes)
        compose.onNodeWithTag("image-text-value").performScrollTo().performTextReplacement("人工纠正甲图文字");click("image-text-save");drain();assertEquals(2,saved[intent.id]!!.history.size)
        backToSearch();query("甲图首稿");assertTrue(state.value.imageTexts!!.items.isEmpty());query("纠正甲图");click("image-text-source:"+saved[intent.id]!!.id);drain()
        assertEquals(intent,attachments.state.value!!.focusImage);assertEquals(intent.id,attachments.state.value!!.pictureId);compose.onNodeWithTag("attachment-picture").assertIsDisplayed()
        form(intent);click("image-text-delete");click("image-text-delete-cancel");assertEquals(2,writes)
        click("image-text-delete");click("image-text-delete-confirm");drain();assertFalse(saved.containsKey(intent.id));assertEquals(3,writes)
        backToSearch();query("纠正甲图");assertTrue(state.value.imageTexts!!.items.isEmpty());assertEquals(listOf(entry,entryB),state.value.entries);assertArrayEquals(bytes,runBlocking{media.read(intent.id)})
    }
    @Test fun restoredPrivateImageSourceRetiresRetainedAThenBViewerAndLookupCannotDisplayA(){
        engineEnabled=true;val restoration=StateRestorationTester(compose);restoration.setContent{Screen()}
        record(entry);viewImage(intent);form(intent);extract();backToSearch();query("甲图首稿");click("image-text-source:"+saved[intent.id]!!.id);drain()
        val old=attachments.state.value!!;assertEquals(intent,old.focusImage);assertEquals(intent.id,old.pictureId)
        restoration.emulateSavedInstanceStateRestore();compose.waitForIdle();drain();compose.runOnIdle{assertNull(attachments.state.value)}
        compose.onNodeWithTag("attachment-picture").assertDoesNotExist();record(entryB);viewImage(intentB)
        compose.runOnIdle{attachments.retireFocusedImage(old.sessionId);attachments.view(old.sessionId,intent.id)};drain();assertEquals(intentB.id,attachments.state.value!!.pictureId)
        form(intentB);extract();backToSearch();query("乙图首稿");click("image-text-source:"+saved[intentB.id]!!.id);drain()
        assertEquals(intentB,attachments.state.value!!.focusImage);assertEquals(intentB.id,attachments.state.value!!.pictureId)
        compose.runOnIdle{attachments.retireFocusedSource(old.sessionId)};assertEquals(intentB.id,attachments.state.value!!.pictureId);compose.onNodeWithTag("attachment-picture").assertIsDisplayed();assertEquals(2,engineCalls)
    }
    private fun intent(e:StoredEntry,b:ByteArray)=AttachmentIntent(UUID.randomUUID().toString(),e.id,1,"image/png",MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it.toInt() and 255)},b.size.toLong())
    private fun png(color:Int):ByteArray {val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);return try{bitmap.eraseColor(color);ByteArrayOutputStream().also{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray()}finally{bitmap.recycle()}}
}
