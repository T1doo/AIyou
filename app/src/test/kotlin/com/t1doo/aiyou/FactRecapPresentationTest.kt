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
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.ui.Destination
import java.time.*
import java.util.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual production route/controller/reader/image decoder; controlled ports, SQL checked separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FactRecapPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val f=Fixture();private var registry:SaveableStateRegistry?=null
    private fun content() {compose.setContent {
        registry=LocalSaveableStateRegistry.current;val d=LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density,2f)) {
            AIyouTheme {AIyouApp(previewDestination=Destination.MEMORIES,local=f.local.value,factRecaps=f.c,attachments=f.attachments)}
        }
    }}
    private fun click(tag:String) {compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()}
    private fun open(){compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("recap-open"));compose.onNodeWithTag("recap-open").performClick()}
    private fun drain(){compose.runOnIdle {runBlocking {while(f.queue.isNotEmpty())f.queue.removeFirst()(f.store)}}}
    private fun generate(){click("recap-generate");drain()}
    @Test fun actualNarrowDoubleFontRouteShowsOriginalExcerptThenFullTextAndReturnsToSameCard() {
        content();open();generate();val id=f.records[5].id
        compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag("recap-source:$id"))
        compose.onNodeWithTag("recap-source:$id").assertHeightIsAtLeast(48.dp).performClick();drain()
        val text=compose.onNodeWithTag("original-text").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertEquals(f.records[5].originalText,text);assertTrue(text.endsWith("合成全文尾部5"))
        compose.onNodeWithText("返回回顾").performClick();drain()
        compose.onNodeWithTag("recap-source:$id").assertIsDisplayed()
        compose.runOnIdle {assertEquals(20,f.local.value.factRecap!!.snapshot!!.recordCount)}
    }
    @Test fun pureImageCardOpensExactVersionAttachmentAndReturnsWithoutInventingText() {
        f.records=listOf(f.records.first().copy(originalText=""));f.refresh()
        content();open();generate();val id=f.records.single().id
        compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag("recap-excerpt:$id"))
        compose.onNodeWithTag("recap-excerpt:$id").assertTextEquals("图片记录：没有文字正文。")
        click("recap-images:$id");drain()
        compose.onNodeWithText("查看图片").performScrollTo().performClick();drain()
        compose.onNodeWithTag("attachment-picture").assertIsDisplayed()
        compose.onNodeWithText("返回附件列表").performClick();compose.onNodeWithText("返回原文").performClick()
        compose.onNodeWithText("返回回顾").performClick();drain()
        compose.runOnIdle {assertEquals(1,f.local.value.factRecap!!.snapshot!!.imageCount);assertEquals("",f.records.single().originalText)}
    }
    @Test fun failedReadRetryCancelAndChangedSourceHidePrivateResultsAndRejectRetainedClick() {
        content();open();f.fail=true;generate();compose.onNodeWithTag("recap-error").assertExists()
        compose.runOnIdle {assertNull(f.local.value.factRecap!!.snapshot)}
        f.fail=false;generate();val card=f.local.value.factRecap!!.snapshot!!.cards.first()
        compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag("recap-source:"+card.source.sourceId))
        val retained=compose.onNodeWithTag("recap-source:"+card.source.sourceId).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle {f.records=f.records.map {it.copy(currentRevision=2,originalText="合成修改之后")};f.refresh();retained()};drain()
        compose.onNodeWithTag("original-text").assertDoesNotExist();compose.runOnIdle {assertNull(f.local.value.factRecap!!.snapshot)}
        click("recap-generate");click("recap-cancel");drain();compose.runOnIdle {assertNull(f.local.value.factRecap!!.snapshot);assertFalse(f.local.value.factRecap!!.loading)}
        generate();click("recap-cancel");compose.onNodeWithTag("recap-excerpt:"+card.source.sourceId).assertDoesNotExist()
    }
    @Test fun explicitWeekZoneEmptyResultPrivateSavedStateAndAccurateBackupSettings() {
        content();open();click("recap-week")
        compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag("recap-zone"));compose.onNodeWithTag("recap-zone").performTextReplacement("America/New_York")
        compose.runOnIdle {f.records=emptyList();f.refresh()};generate()
        compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag("recap-empty"));compose.onNodeWithTag("recap-empty").assertIsDisplayed()
        compose.runOnIdle {val s=f.local.value.factRecap!!.snapshot!!;assertEquals(7,s.range.days.size);assertEquals("America/New_York",s.range.zone)
            val saved=registry!!.performSave().toString();assertFalse(saved.contains("America/New_York"));assertFalse(saved.contains("合成私密标签"));assertFalse(saved.contains("2026-10-15"))}
        compose.onNodeWithText("返回回忆").performClick();compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("可在回忆中制作选定记录与原图的加密包并恢复",substring=true).performScrollTo().assertIsDisplayed()
    }
    private class Fixture {
        val clock:Clock=Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"),ZoneId.of("UTC"))
        var records=(0..19).map {StoredEntry(UUID.randomUUID().toString(),1,"合成原文$it："+"文".repeat(300)+"合成全文尾部$it",clock.millis()+it,"UTC",ProcessingPolicy.AI_NONE)}
        private val imageId=UUID.randomUUID().toString();private val imageEntry=records.first().id
        private val bytes=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNQCp35HwADvQIQaYgMTgAAAABJRU5ErkJggg==")
        private fun picture()=LocalAttachment(AttachmentIntent(imageId,imageEntry,1,"image/png",java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)},bytes.size.toLong()),AttachmentState.READY)
        val local=mutableStateOf(LocalRecordUiState(ready=true,opening=false,supportsFactRecaps=true,supportsRecordPackages=true,entries=records,manualTagsAvailable=true))
        val queue=ArrayDeque<suspend(EntryRepository)->Unit>();var fail=false
        fun refresh(){local.value=reconcileFactRecap(local.value,local.value.copy(entries=records))}
        val store=object:EntryRepository {
            override val factRecaps=object:FactRecapRepository {
                override fun capture(range:FactRecapRange):FactRecapSnapshot {
                    if(fail)error("Synthetic read failure")
                    return object:FactRecapSnapshot {
                        override val range=range
                        override val cards=records.filter {it.capturedAtMillis>=range.startMillis&&it.capturedAtMillis<range.endMillis}.map {
                            FactRecapCard(SourceVersion(it.id,it.currentRevision.toLong()),it.capturedAtMillis,it.captureTimeZone,OriginalExports.hash(it.originalText),0,it.originalText.take(240),it.originalText.length>240,
                                ManualTagSnapshot(it.id,0,emptyList()),if(it.id==imageEntry && it.currentRevision==1)listOf(FactRecapImage(picture().intent,AttachmentState.READY))else emptyList(),it.policy)
                        }
                    }
                }
                override fun publishCurrent(snapshot:FactRecapSnapshot,publish:()->Unit):Boolean {publish();return true}
                override fun resolve(snapshot:FactRecapSnapshot,source:SourceVersion,publish:()->Unit):Boolean {if(records.none{it.id==source.sourceId&&it.currentRevision.toLong()==source.revision})return false;publish();return true}
            }
            override val attachments=object:AttachmentStore {
                override suspend fun save(intent:AttachmentIntent,source:()->java.io.InputStream):LocalAttachment=error("Unrelated")
                override suspend fun list(entryId:String,revision:Int)=listOf(picture()).filter{it.intent.entryId==entryId&&it.intent.revision==revision}
                override suspend fun read(id:String)=bytes.copyOf()
                override suspend fun recover()=Unit
            }
            override suspend fun entries()=records
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("Unrelated")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("Unrelated")
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("Unrelated")
        }
        val c=FactRecapController({local.value},{change->local.value=reconcileFactRecap(local.value,change(local.value))},{queue.add(it);true},clock)
        val attachments=LocalAttachmentController({queue.add(it);true},PlatformImageDecoder::decode,
            canUseRecord={local.value.ready},canUsePanel={it.hasRecordAccess(local.value)})
    }
}
