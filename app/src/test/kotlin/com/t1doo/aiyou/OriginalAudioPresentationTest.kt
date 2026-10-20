package com.t1doo.aiyou

import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/** Production screens/controllers, synthetic RAM persistence/output; real Room/JCA tested in AudioProductionGateTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OriginalAudioPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val f=Fixture()
    private val launches=mutableListOf<Intent>()
    private val registry=object:ActivityResultRegistry() {
        override fun <I,O> onLaunch(code:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            launches.add(input as Intent) // No external picker is actually launched.
        }
    }
    private fun content() {
        runBlocking { f.c.initialize(f.store) };f.player.foreground(true)
        compose.setContent {
            val local by f.local.collectAsState();val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f),
                LocalActivityResultRegistryOwner provides object:ActivityResultRegistryOwner { override val activityResultRegistry=registry }) {
                AIyouTheme { Column {
                    Text("合成演示数据·非麦克风录音")
                    Box(Modifier.weight(1f)) { AIyouApp(local=local,mixedCapture=f.c,attachments=f.attachments,
                        audioPlayback=f.player,onAudioRouteChanged={f.player.routeChanged();f.c.audioCapture.cancel()}) }
                } }
            }
        }
    }
    private fun click(tag:String) {
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertIsEnabled().performClick()
    }
    private fun drain()=compose.runOnIdle { runBlocking {
        while(true) { val action=f.queue.pollFirst() ?: break;action(f.store) }
        f.local.value=f.local.value.copy(localProcessingVersions=checkNotNull(f.store.localProcessing).versions())
    } }
    private fun importWave() {
        click("mixed-audio-import");drain();compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1,launches.size);val intent=launches.single()
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY,false))
            assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,true))
            val key=checkNotNull(f.local.value.mixedCapture!!.audio.filePickerKey)
            assertFalse(f.c.audioCapture.claimFile(key)) // Real picker effect already claimed exactly once.
            f.c.audioCapture.selectedFile(key,object:SelectedWaveSource { override fun open()=f.wave.inputStream() })
        }
        compose.waitUntil(5000) { f.queue.isNotEmpty() };drain()
        compose.runOnIdle { assertEquals(CaptureAudioState.READY,f.local.value.mixedCapture!!.draft!!.audio!!.state) }
    }
    @Test fun narrowActualUiImportsExplicitSyntheticFilePlaysStopsAndSavesOnceThenPlaysExactSavedAttachment() {
        content();importWave();click("mixed-audio-play");drain()
        compose.waitUntil(5000) { f.player.state.value.phase==OriginalAudioPhase.PLAYING }
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("mixed-save"))
        compose.onNodeWithTag("mixed-save").assertIsNotEnabled()
        compose.onNodeWithTag("audio-playback-stop").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil(5000) { !f.player.busy };click("mixed-save");drain()
        val id=compose.runOnIdle { assertEquals(1,f.saves);f.local.value.entries.single().id }
        compose.onNodeWithTag("mixed-list").performScrollToNode(hasTestTag("entry-$id"))
        compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-$id"))).performScrollTo().performClick()
        compose.onNodeWithText("附件记录 · 无文字正文；请打开附件查看原始素材。").assertExists()
        compose.onNodeWithText("查看附件").performScrollTo().performClick();drain()
        val attachment=f.saved.single().intent.id
        compose.onNodeWithTag("attachment-audio-play:$attachment").performScrollTo().assertHeightIsAtLeast(48.dp).performClick();drain()
        compose.waitUntil(5000) { f.player.state.value.phase==OriginalAudioPhase.PLAYING }
        compose.onNodeWithText("返回原文").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { !f.player.busy }
        compose.runOnIdle { assertEquals(1,f.saves);assertEquals(2,f.closes.get());assertArrayEquals(f.wave,f.bytes.getValue(attachment)) }
    }
    @Test fun actualDraftRemoveDoesNotChangeTextAndNoAudioIsSavedAfterRemoval() {
        content();importWave();click("mixed-audio-remove");drain()
        compose.runOnIdle { assertNull(f.local.value.mixedCapture!!.draft!!.audio);assertEquals("",f.local.value.mixedCapture!!.text) }
        click("mixed-save");drain();compose.runOnIdle { assertEquals(0,f.saves);assertNotNull(f.local.value.mixedCapture!!.error) }
    }
    @After fun close() { runBlocking { f.player.stop();f.player.closeAndJoin();f.c.drainAudioOnIo(f.store) };f.c.dispose();f.c.disposeOnIo() }

    private class Fixture {
        val wave=LocalPcmAudio.encode(shortArrayOf(0x1234,-2,1000,-1000))
        val g=CaptureDraftGate();val queue=ConcurrentLinkedDeque<suspend(EntryRepository)->Unit>()
        val bytes=mutableMapOf<String,ByteArray>();val saved=mutableListOf<LocalAttachment>();val closes=AtomicInteger()
        var saves=0
        private var draft=CaptureDraft(uuid(),1,"",emptyList())
        private var entries=emptyList<StoredEntry>()
        val local=MutableStateFlow(LocalRecordUiState(ready=true,opening=false,supportsMixedCapture=true,supportsLocalProcessing=true,mixedCapture=MixedCaptureUiState()))
        val store=object:EntryRepository {
            override val captureRecords=object:CaptureRepository {
                override val mutationGate=g
                override suspend fun current()=draft
                override suspend fun peek()=draft
                override suspend fun edit(expected:CaptureDraft,text:String)=g.mutation { same(expected);next(text=text) }
                override suspend fun addAudio(request:CaptureDraftGate.Reservation,bytes:ByteArray)=g.audioWrite(request) {
                    same(request.base);LocalPcmAudio.inspect(bytes);val intent=g.intent(request);this@Fixture.bytes[intent.id]=bytes.copyOf()
                    CaptureDraft(draft.id,draft.version+2,draft.text,draft.images,audio=CaptureDraftAudio(intent,CaptureAudioState.READY)).also { draft=it }
                }
                override suspend fun audio(expected:CaptureDraft):ByteArray { same(expected);return bytes.getValue(checkNotNull(draft.audio).intent.id).copyOf() }
                override suspend fun removeAudio(expected:CaptureDraft)=g.mutation { same(expected);next(audio=null) }
                override suspend fun save(expected:CaptureDraft,capturedAtMillis:Long,zone:String)=g.mutation {
                    same(expected);require(expected.text.isNotBlank() || expected.audio?.state==CaptureAudioState.READY)
                    saves++;entries=entries+StoredEntry(expected.id,1,expected.text,capturedAtMillis,zone,expected.policy)
                    saved.add(LocalAttachment(checkNotNull(expected.audio).intent,AttachmentState.READY))
                    draft=CaptureDraft(uuid(),1,"",emptyList());CaptureSaveResult(expected.id,false,false)
                }
                override suspend fun recover()=Unit
                override suspend fun add(expected:CaptureDraft,intent:AttachmentIntent,bytes:ByteArray):CaptureDraft=error("unrelated")
                override suspend fun image(expected:CaptureDraft,id:String):ByteArray=error("unrelated")
                override suspend fun remove(draftId:String,imageId:String):CaptureDraft=error("unrelated")
                override suspend fun discard(draftId:String):CaptureDraft=error("unrelated")
            }
            override val attachments=object:AttachmentStore {
                override suspend fun save(intent:AttachmentIntent,source:()->java.io.InputStream):LocalAttachment=error("unrelated")
                override suspend fun list(entryId:String,revision:Int)=saved.filter { it.intent.entryId==entryId && it.intent.revision==revision }
                override suspend fun read(id:String)=bytes.getValue(id).copyOf()
                override suspend fun recover()=Unit
            }
            override val localProcessing=object:LocalProcessingRepository {
                override fun versions()=entries.associate { it.id to LocalProcessingVersion(SourceVersion(it.id,it.currentRevision.toLong()),1) }
                override fun read(source:SourceVersion):LocalProcessingSnapshot {
                    val e=entries.single { it.id==source.sourceId && it.currentRevision.toLong()==source.revision }
                    return LocalProcessingSnapshot(source,OriginalExports.hash(e.originalText),1,e.policy)
                }
                override fun change(request:LocalProcessingChange):LocalProcessingSnapshot=error("unrelated")
            }
            override suspend fun entries()=entries
            override suspend fun revisions(entryId:String)=entries.filter { it.id==entryId }.map { OriginalRevision(it.id,1,it.originalText,OriginalExports.hash(it.originalText),it.capturedAtMillis) }
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("unrelated")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("unrelated")
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("unrelated")
        }
        private fun same(expected:CaptureDraft) { require(draft.sameSnapshot(expected)) }
        private fun next(text:String=draft.text,audio:CaptureDraftAudio?=draft.audio)=CaptureDraft(draft.id,draft.version+1,text,draft.images,audio=audio).also { draft=it }
        val c=MixedCaptureController({local.value},{local.value=it(local.value)},{queue.add(it);true},PlatformImageDecoder::thumbnail,Clock.systemUTC())
        val attachments:LocalAttachmentController=LocalAttachmentController({queue.add(it);true},PlatformImageDecoder::decode,
            canUsePanel={it.hasRecordAccess(local.value)},sourceUi={g.sourceUi(it)},onPanelClose={player.stop()})
        val player:OriginalAudioController=OriginalAudioController({local.value},{attachments.state.value},{g},{queue.add(it);true},
            { object:OriginalAudioOutput {
                override fun focus()=true
                override fun open() {}
                override fun play() {}
                override fun write(samples:ShortArray,count:Int)=count
                override fun playedFrames()=0L
                override fun close() { closes.incrementAndGet() }
            } },{true})
        companion object { private fun uuid()=UUID.randomUUID().toString() }
    }
}
