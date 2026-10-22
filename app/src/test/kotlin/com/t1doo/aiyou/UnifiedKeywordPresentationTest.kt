package com.t1doo.aiyou

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.ui.Destination
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.*

/** Actual root/dialog/controller/readers with controlled result ports. Common SQL/crypto proof
 * is covered by UnifiedKeywordSqlTest/UnifiedKeywordViewModelTest, not by this UI fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnifiedKeywordPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val f=Fixture();private var registry:SaveableStateRegistry?=null
    @org.junit.After fun close(){f.c.dispose();f.attachments.markDisposed();runBlocking{f.attachments.disposeOnIo()}}
    private fun content(restore:StateRestorationTester?=null){
        val body:@Composable ()->Unit={registry=LocalSaveableStateRegistry.current;val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,2f)){AIyouTheme{
                AIyouApp(previewDestination=Destination.MEMORIES,local=f.local.value,currentRecords={f.local.value},unifiedKeywords=f.c,attachments=f.attachments)
            }}}
        if(restore==null)compose.setContent(body)else restore.setContent(body)
    }
    private fun drain(){repeat(2){compose.runOnIdle{runBlocking{while(f.queue.isNotEmpty())f.queue.removeFirst()(f.store)}};compose.waitForIdle()}}
    private fun open(){compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0);compose.onNodeWithTag("unified-open").performScrollTo().performClick();compose.waitForIdle()}
    private fun query(value:String){compose.onNodeWithTag("unified-results").performScrollToIndex(0)
        compose.onNodeWithTag("unified-query").performScrollTo().performTextReplacement(value)
        compose.onNodeWithTag("unified-search").performScrollTo().assertHeightIsAtLeast(48.dp).performClick();drain()}
    private fun result(e:UnifiedKeywordEvidence){compose.onNodeWithTag("unified-results").performScrollToIndex(1)
        compose.onNodeWithTag("unified-source:"+e.key).performScrollTo().assertHeightIsAtLeast(48.dp).performClick();drain()}
    private fun back(){compose.onNodeWithText("返回列表").assertIsDisplayed().performClick();drain()}
    @Test fun doubleFontFourTypedResultsOpenExactOriginalAndPreferenceThenRequeryWithoutChangingSources(){
        content();open();compose.onNodeWithTag("unified-empty").assertDoesNotExist();query("淡茶")
        val before=f.local.value.unifiedKeyword!!.snapshot!!;val group=before.groups.single()
        assertEquals(4,group.items.size);compose.onNodeWithTag("unified-count").assertTextEquals("第 1 页 · 共 1 个原件组")
        result(group.items.filterIsInstance<UnifiedKeywordEvidence.Original>().single())
        compose.onNodeWithTag("original-text").assertTextEquals(f.records[0].originalText);back()
        compose.runOnIdle{assertNotSame(before,f.local.value.unifiedKeyword!!.snapshot);assertEquals("淡茶",f.local.value.unifiedKeyword!!.keyword)}
        val pref=f.local.value.unifiedKeyword!!.snapshot!!.groups.single().items.filterIsInstance<UnifiedKeywordEvidence.Preference>().single()
        result(pref);compose.onNodeWithTag("original-text").assertTextEquals(f.records[0].originalText);back()
        val audio=f.local.value.unifiedKeyword!!.snapshot!!.groups.single().items.filterIsInstance<UnifiedKeywordEvidence.Audio>().single()
        result(audio);compose.runOnIdle{assertEquals(audio.value.anchor.intent,f.attachments.state.value!!.focusAudio);assertEquals(0,f.mediaReads)}
        compose.onNodeWithText("返回原文").assertIsDisplayed().performClick();compose.waitForIdle();back()
        compose.runOnIdle{assertEquals(listOf("我偏好淡茶🙂","我偏好清水🙂"),f.records.map{it.originalText});assertEquals(0,f.writes)}
    }
    @Test fun literalNoResultClearAndRestoreNeverSaveQueryOrRetainOldSession(){
        val restore=StateRestorationTester(compose);content(restore);open();query("不存在的合成词")
        compose.onNodeWithTag("unified-empty").assertTextEquals("当前所选内容没有找到逐字匹配。可修改关键词或范围。")
        compose.runOnIdle{assertFalse(registry!!.performSave().toString().contains("不存在的合成词"))}
        compose.onNodeWithTag("unified-results").performScrollToIndex(0)
        compose.onNodeWithTag("unified-clear").performScrollTo().performClick();compose.waitForIdle()
        compose.onNodeWithTag("unified-empty").assertDoesNotExist();query("淡茶")
        val old=f.local.value.unifiedKeyword!!;val item=old.snapshot!!.groups.single().items.first()
        restore.emulateSavedInstanceStateRestore();compose.waitForIdle();drain()
        compose.runOnIdle{assertNull(f.local.value.unifiedKeyword);f.c.locate(old.session,item){_,_->fail("Old query restored")}}
        open();assertEquals("",compose.onNodeWithTag("unified-query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("unified-empty").assertDoesNotExist()
    }
    @Test fun restoredExactImageProofRetiresAPanelBeforeBLookupAndRejectsRetainedAAction(){
        val restore=StateRestorationTester(compose);content(restore);open();query("淡茶")
        val old=f.local.value.unifiedKeyword!!;val a=old.snapshot!!.groups.single().items.filterIsInstance<UnifiedKeywordEvidence.Image>().single()
        result(a);compose.onNodeWithTag("attachment-picture").assertIsDisplayed()
        compose.runOnIdle{assertEquals(a.value.anchor.intent,f.attachments.state.value!!.focusImage)}
        val panel=f.attachments.state.value!!;restore.emulateSavedInstanceStateRestore();compose.waitForIdle();drain()
        compose.runOnIdle{assertNull(f.attachments.state.value);f.attachments.refresh(panel.sessionId);f.c.locate(old.session,a){_,_->fail("Old image route")}};drain()
        open();query("清水");val b=f.local.value.unifiedKeyword!!.snapshot!!.groups.single().items.filterIsInstance<UnifiedKeywordEvidence.Image>().single()
        result(b);compose.onNodeWithTag("attachment-picture").assertIsDisplayed()
        compose.runOnIdle{assertEquals(b.value.anchor.intent,f.attachments.state.value!!.focusImage);assertNotEquals(a.value.anchor.intent,b.value.anchor.intent);assertEquals(0,f.writes)}
    }
    @Test @Config(qualifiers="w640dp-h320dp-land")
    fun landscapeDoubleFontScrollsToFocusedQuerySearchAndExactSourceWithoutHiddenActions(){
        content();open()
        compose.onNodeWithTag("unified-query").performScrollTo().performClick().assertIsFocused().performTextInput("淡茶")
        val search=compose.onNodeWithTag("unified-search").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        search.performSemanticsAction(SemanticsActions.RequestFocus){assertTrue(it())}
        search.assertIsFocused().performKeyInput{pressKey(Key.Enter)};drain()
        val item=f.local.value.unifiedKeyword!!.snapshot!!.groups.single().items.filterIsInstance<UnifiedKeywordEvidence.Original>().single()
        compose.onNodeWithTag("unified-results").performScrollToIndex(1)
        val source=compose.onNodeWithTag("unified-source:"+item.key).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        source.performSemanticsAction(SemanticsActions.RequestFocus){assertTrue(it())}
        source.assertIsFocused().performKeyInput{pressKey(Key.Enter)};drain()
        compose.onNodeWithTag("original-text").assertTextEquals(item.entry.originalText)
        compose.runOnIdle{assertEquals(0,f.writes)}
    }
    private class Fixture {
        private fun id()=UUID.randomUUID().toString()
        val records=listOf("我偏好淡茶🙂","我偏好清水🙂").map{StoredEntry(id(),1,it,55,"UTC",ProcessingPolicy.LOCAL_ONLY)}
        private val png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNQCp35HwADvQIQaYgMTgAAAABJRU5ErkJggg==")
        private val wave=LocalPcmAudio.encode(ShortArray(32){it.toShort()})
        private fun hash(b:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
        private val pictures=records.map{AttachmentIntent(id(),it.id,1,"image/png",hash(png),png.size.toLong())}
        private val audio=AttachmentIntent(id(),records[0].id,1,"audio/wav",hash(wave),wave.size.toLong())
        private fun bind(e:StoredEntry)=UnifiedKeywordBinding(SourceVersion(e.id,1),OriginalExports.hash(e.originalText),0,55,"UTC",e.policy)
        private val evidence=records.flatMapIndexed{at,e->
            val b=bind(e);val image=ImageTextSnapshot(id(),ImageTextSource(pictures[at],b.hash,0,55,"UTC",b.policy),ImageTextEngine("synthetic-ui-port","1",true),listOf(ImageTextVersion(1,e.originalText,false,55)))
            val rows=mutableListOf<UnifiedKeywordEvidence>(UnifiedKeywordEvidence.Original(b,e),UnifiedKeywordEvidence.Image(b,image))
            if(at==0){rows+=UnifiedKeywordEvidence.Audio(b,AudioTranscriptSnapshot(id(),AudioTranscriptSource(audio,b.hash,0,55,"UTC",b.policy,32),1,false,listOf(AudioTranscriptVersion(1,e.originalText,55))))
                val preview=SourceMemoryPreview(b.source,b.hash,0,e.originalText,55,"UTC",b.policy,1)
                val preference=SourcePreferenceSnapshot(id(),b.source,b.hash,0,2,PreferenceAvailability.AVAILABLE,55,55,"UTC",b.policy,id(),"合成口味",listOf(PreferenceContentVersion(1,PreferenceContent(SourceMemoryRules.reference(preview,e.originalText)),55)),listOf(PreferenceCheckpoint(1,PreferenceReview.ACCEPTED,null,55)))
                rows+=UnifiedKeywordEvidence.Preference(b,preference)};rows
        }
        var writes=0;var mediaReads=0
        val queue=ArrayDeque<suspend(EntryRepository)->Unit>()
        val repo:UnifiedKeywordRepository=object:UnifiedKeywordRepository {
            override suspend fun search(keyword:String,scope:UnifiedKeywordScope):UnifiedKeywordSnapshot {
                val matched=evidence.filter{e->records.single{it.id==e.binding.source.sourceId}.originalText.contains(keyword)&&when(scope){UnifiedKeywordScope.ALL->true;UnifiedKeywordScope.ORIGINAL->e is UnifiedKeywordEvidence.Original;UnifiedKeywordScope.AUDIO->e is UnifiedKeywordEvidence.Audio;UnifiedKeywordScope.IMAGE->e is UnifiedKeywordEvidence.Image;UnifiedKeywordScope.PREFERENCE->e is UnifiedKeywordEvidence.Preference}}
                return object:UnifiedKeywordSnapshot{override val keyword=keyword;override val scope=scope;override val bindings=records.map(::bind)
                    override val groups=matched.groupBy{it.binding}.map{(b,rows)->UnifiedKeywordGroup(b,rows.sortedBy{it.key})}}
            }
            override suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,admit:()->Unit):Boolean {if(item!=null&&snapshot.groups.none{it.items.any{row->row===item}})return false;admit();return true}
        }
        val local=mutableStateOf(LocalRecordUiState(ready=true,opening=false,entries=records,unifiedKeywordOwner=repo))
        val store:EntryRepository=object:EntryRepository {
            override val unifiedKeywords=repo
            override val attachments=object:AttachmentStore {
                override suspend fun save(intent:AttachmentIntent,source:()->java.io.InputStream):LocalAttachment{writes++;error("No source writes")}
                override suspend fun list(entryId:String,revision:Int)=(pictures+audio).filter{it.entryId==entryId&&it.revision==revision}.map{LocalAttachment(it,AttachmentState.READY)}
                override suspend fun read(id:String):ByteArray{mediaReads++;return if(id==audio.id)wave.copyOf()else png.copyOf()}
                override suspend fun recover()=Unit
            }
            override suspend fun entries()=records
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("Unrelated")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("Unrelated")
            override suspend fun revisions(entryId:String)=records.filter{it.id==entryId}.map{OriginalRevision(it.id,1,it.originalText,OriginalExports.hash(it.originalText),55)}
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("Unrelated")
        }
        val c=UnifiedKeywordController({local.value},{change->val next=change(local.value);local.value=if(next.unifiedKeyword?.let{!next.canRetainUnified(it)}==true)next.copy(unifiedKeyword=null)else next},{queue.add(it);true})
        val attachments=LocalAttachmentController({queue.add(it);true},PlatformImageDecoder::decode,canUseRecord={local.value.ready},canUsePanel={it.hasRecordAccess(local.value)})
    }
}
