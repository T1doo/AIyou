package com.t1doo.aiyou

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.ui.Destination
import com.t1doo.aiyou.ui.UiPreferences
import com.t1doo.aiyou.ui.UiPreferencesState
import com.t1doo.aiyou.ui.UiPreferencesViewModel
import com.t1doo.aiyou.ui.FileUiPreferences
import com.t1doo.aiyou.ui.LocalRecapPreferenceActionObserver
import java.time.*
import java.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import java.io.File
import org.junit.rules.TemporaryFolder
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
    @get:Rule val temporary=TemporaryFolder()
    private val f=Fixture();private var registry:SaveableStateRegistry?=null
    private val display=mutableStateOf(UiPreferencesState(UiPreferences(showCompanion=false,reduceMotion=true),ready=true))
    @org.junit.After fun closeAudio(){f.closeFails=false;f.player.stop();runBlocking {f.player.closeAndJoin()};f.transcripts.dispose();f.attachments.markDisposed();runBlocking{f.attachments.disposeOnIo()}}
    private fun content(preferences: UiPreferencesViewModel?=null,
        observeDefault: ((FactRecapPeriod, () -> Unit) -> Unit)?=null) {compose.setContent {
        registry=LocalSaveableStateRegistry.current;val d=LocalDensity.current
        val confirmed=preferences?.state?.collectAsState()?.value ?: display.value
        CompositionLocalProvider(LocalDensity provides Density(d.density,2f),LocalRecapPreferenceActionObserver provides observeDefault) {
            AIyouTheme {AIyouApp(previewDestination=Destination.MEMORIES,local=f.local.value,factRecaps=f.c,attachments=f.attachments,
                audioTranscripts=f.transcripts,audioPlayback=f.player,onAudioRouteChanged=f.player::routeChanged,currentRecords={f.local.value},display=confirmed,onDisplayChange={preferences?.change(it)},onRetryDisplay={preferences?.retry()},
                onOpenTags=f.tags::open,onTagClose=f.tags::close)}
        }
    }}
    private fun click(tag:String) {compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()}
    private fun open(){compose.showNotebookToolsFor("recap-open");compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("recap-open"));compose.onNodeWithTag("recap-open").performClick()}
    private fun drain(){compose.runOnIdle {runBlocking {while(f.queue.isNotEmpty())f.queue.removeFirst()(f.store)}}}
    private fun generate(){click("recap-generate");drain()}
    @Test fun actualSettingsPersistWeekAndOpenedRangeKeepsItsOwnChoiceBeforeExplicitGeneration() {
        val file=File(temporary.newFolder(),"preferences")
        FileUiPreferences(file).write(UiPreferences(false,true))
        val preferences=UiPreferencesViewModel(org.robolectric.RuntimeEnvironment.getApplication(),FileUiPreferences(file),Dispatchers.Unconfined)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        var observedWeek: (() -> Unit)?=null
        content(preferences) { period, action -> if(period==FactRecapPeriod.WEEK) observedWeek=action }
        compose.onNodeWithText("设置").performClick()
        val week=compose.onNodeWithTag("recap-default-week").performScrollTo().assertIsDisplayed().assertIsEnabled()
        val retiredWeek=checkNotNull(observedWeek)
        week.assertHeightIsAtLeast(48.dp).performClick();week.assertIsSelected()
        compose.runOnIdle {assertEquals(UiPreferences(false,true,FactRecapPeriod.WEEK),FileUiPreferences(file).read())}
        compose.onNodeWithText("返回").performClick();open()
        compose.runOnIdle {assertEquals(FactRecapPeriod.WEEK,f.local.value.factRecap!!.period);assertNull(f.local.value.factRecap!!.snapshot);assertTrue(f.queue.isEmpty())}
        compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag("recap-range-preview"))
        compose.onNodeWithTag("recap-range-preview").assertTextContains("2026-10-12",substring=true).assertTextContains("2026-10-19",substring=true)
        // A later confirmed preference is for the next open; it never rewrites this range or result.
        compose.runOnIdle {preferences.change(preferences.state.value.preferences.copy(defaultRecapPeriod=FactRecapPeriod.DAY))}
        compose.runOnIdle {retiredWeek();assertEquals(FactRecapPeriod.DAY,FileUiPreferences(file).read().defaultRecapPeriod)}
        compose.runOnIdle {assertEquals(FactRecapPeriod.WEEK,f.local.value.factRecap!!.period);assertTrue(f.queue.isEmpty())}
        generate();compose.runOnIdle {assertEquals(FactRecapPeriod.WEEK,f.local.value.factRecap!!.snapshot!!.range.period)}
        compose.runOnIdle {preferences.change(preferences.state.value.preferences.copy(defaultRecapPeriod=FactRecapPeriod.WEEK))}
        click("recap-day");compose.runOnIdle {
            assertEquals(FactRecapPeriod.DAY,f.local.value.factRecap!!.period);assertNull(f.local.value.factRecap!!.snapshot)
            assertEquals(FactRecapPeriod.WEEK,FileUiPreferences(file).read().defaultRecapPeriod)
        }
        compose.onNodeWithText("返回回忆").performClick();open()
        compose.runOnIdle {assertEquals(FactRecapPeriod.WEEK,f.local.value.factRecap!!.period);assertTrue(f.queue.isEmpty())}
    }
    @Test fun latePreferenceReadDoesNotRewriteOpenDayAndRetainedOpenUsesLatestConfirmedDefault() {
        display.value=UiPreferencesState()
        content();compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("recap-default-week").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("recap-default-day").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("返回").performClick();open()
        compose.runOnIdle {display.value=UiPreferencesState(UiPreferences(false,true,FactRecapPeriod.WEEK),ready=true)}
        compose.runOnIdle {assertEquals(FactRecapPeriod.DAY,f.local.value.factRecap!!.period);assertNull(f.local.value.factRecap!!.snapshot);assertTrue(f.queue.isEmpty())}
        compose.onNodeWithText("返回回忆").performClick()
        compose.showNotebookToolsFor("recap-open");compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("recap-open"))
        val retained=compose.onNodeWithTag("recap-open").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle {display.value=display.value.copy(preferences=display.value.preferences.copy(defaultRecapPeriod=FactRecapPeriod.DAY))}
        compose.runOnIdle {retained()}
        compose.runOnIdle {assertEquals(FactRecapPeriod.DAY,f.local.value.factRecap!!.period);assertTrue(f.queue.isEmpty())}
    }
    @Test fun actualNarrowDoubleFontRouteShowsOriginalExcerptThenFullTextAndReturnsToSameCard() {
        content();open();generate();val id=f.records[5].id
        compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag("recap-source:$id"))
        compose.onNodeWithTag("recap-source:$id").assertHeightIsAtLeast(48.dp).performClick();drain()
        val text=compose.onNodeWithTag("original-text").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertEquals(f.records[5].originalText,text);assertTrue(text.endsWith("合成全文尾部5"))
        val snapshot=f.local.value.factRecap!!.snapshot
        compose.originalTool("original-manual-tags").performScrollTo().performClick();drain()
        compose.onNodeWithTag("manual-tag-close").performScrollTo().performClick();drain()
        compose.runOnIdle {assertSame(snapshot,f.local.value.factRecap!!.snapshot)}
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
        val originalRecords = f.records
        val captures = File("build/reports/tests/testDebugUnitTest/recap-rejected-input").apply { mkdirs() }
        val measurements = org.json.JSONArray()
        for (badDate in listOf(true, false)) {
            generate()
            val before = checkNotNull(f.local.value.factRecap)
            val tag = if (badDate) "recap-date" else "recap-zone"
            val errorTag = if (badDate) "recap-date-rejected" else "recap-zone-rejected"
            val raw = if (badDate) before.date + "0" else "x".repeat(65)
            compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performTextReplacement(raw)
            // The supporting error grows this same field; verify scroll reachability, not automatic/first-screen placement.
            compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag(tag))
            val error = compose.onNodeWithTag(errorTag, useUnmergedTree = true)
            error.assertIsDisplayed().assertTextContains("未接受", substring = true)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            error.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size); assertEquals(2f, layouts.single().layoutInput.density.fontScale, 0f)
            assertFalse("The actual rejected-input explanation must remain readable", layouts.single().hasVisualOverflow)
            val bounds = error.getUnclippedBoundsInRoot()
            val viewport = compose.onNodeWithTag("recap-list").getUnclippedBoundsInRoot()
            assertTrue("The complete error must stay within the visible list", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom &&
                bounds.left >= viewport.left && bounds.right <= viewport.right)
            measurements.put(org.json.JSONObject().put("field", tag).put("fontScale", layouts.single().layoutInput.density.fontScale)
                .put("hasVisualOverflow", layouts.single().hasVisualOverflow).put("lineCount", layouts.single().lineCount)
                .put("top", bounds.top.value).put("bottom", bounds.bottom.value)
                .put("viewportTop", viewport.top.value).put("viewportBottom", viewport.bottom.value))
            File(captures, "layout.json").writeText(org.json.JSONObject().put("source_sha", System.getenv("GITHUB_SHA") ?: "local-unbound")
                .put("kind", "Actual root TextLayout at provided fontScale2; unclipped dp bounds, not device or dynamic configuration evidence")
                .put("fields", measurements).toString(2))
            compose.runOnIdle {
                val ui = checkNotNull(f.local.value.factRecap)
                assertNull(ui.snapshot); assertFalse(ui.loading); assertTrue(f.queue.isEmpty())
                assertEquals(badDate, ui.dateRejected); assertEquals(!badDate, ui.zoneRejected)
                assertFalse(registry!!.performSave().toString().contains(raw))
            }
            val image = compose.onNodeWithTag("recap-list").captureToImage().asAndroidBitmap()
            File(captures, if (badDate) "01-date-rejected-2x.png" else "02-zone-rejected-2x.png").outputStream().use {
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            click("recap-week")
            compose.runOnIdle {
                assertEquals("Period selection cannot confirm a rejected date", badDate, f.local.value.factRecap!!.dateRejected)
                assertEquals("Period selection cannot confirm a rejected zone", !badDate, f.local.value.factRecap!!.zoneRejected)
            }
            val otherTag = if (badDate) "recap-zone" else "recap-date"
            compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag(otherTag))
            compose.onNodeWithTag(otherTag).performTextReplacement(if (badDate) "Asia/Shanghai" else "2026-10-16")
            compose.runOnIdle {
                assertEquals("Other-field input cannot confirm a rejected date", badDate, f.local.value.factRecap!!.dateRejected)
                assertEquals("Other-field input cannot confirm a rejected zone", !badDate, f.local.value.factRecap!!.zoneRejected)
            }
            click("recap-cancel")
            compose.runOnIdle {
                assertEquals("Cancel cannot confirm a rejected date", badDate, f.local.value.factRecap!!.dateRejected)
                assertEquals("Cancel cannot confirm a rejected zone", !badDate, f.local.value.factRecap!!.zoneRejected)
            }
            compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag("recap-generate"))
            compose.onNodeWithTag("recap-generate").assertIsNotEnabled()
            compose.runOnIdle {
                f.c.generate() // Negative actor guard: a bypassed disabled button must still do no IO.
                assertTrue(f.queue.isEmpty()); assertNull(f.local.value.factRecap!!.snapshot)
                assertEquals(badDate, f.local.value.factRecap!!.dateRejected)
                assertEquals(!badDate, f.local.value.factRecap!!.zoneRejected)
            }
            compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performTextClearance()
            compose.onNodeWithTag(tag).performTextInput(if (badDate) before.date else "UTC")
            error.assertDoesNotExist()
            generate()
            compose.runOnIdle { assertNotNull(f.local.value.factRecap!!.snapshot); assertSame(originalRecords, f.records) }
        }
    }
    @Test fun explicitWeekZoneEmptyResultPrivateSavedStateAndAccurateBackupSettings() {
        content();open();click("recap-week")
        compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag("recap-zone"));compose.onNodeWithTag("recap-zone").performTextReplacement("America/New_York")
        generate()
        compose.runOnIdle {assertTrue(f.local.value.factRecap!!.snapshot!!.cards.any { "合成私密标签" in it.tags.names })
            val saved=registry!!.performSave().toString();assertFalse(saved.contains("合成原文0"));assertFalse(saved.contains("合成私密标签"));assertFalse(saved.contains("America/New_York"))}
        compose.runOnIdle {f.records=emptyList();f.refresh()};generate()
        compose.onNodeWithTag("recap-list").performScrollToNode(hasTestTag("recap-empty"));compose.onNodeWithTag("recap-empty").assertIsDisplayed()
        compose.runOnIdle {val s=f.local.value.factRecap!!.snapshot!!;assertEquals(7,s.range.days.size);assertEquals("America/New_York",s.range.zone)
            val saved=registry!!.performSave().toString();assertFalse(saved.contains("America/New_York"));assertFalse(saved.contains("合成私密标签"));assertFalse(saved.contains("2026-10-15"))}
        compose.onNodeWithText("返回回忆").performClick();compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("可在回忆中制作选定记录与原图的加密包并恢复",substring=true).performScrollTo().assertIsDisplayed()
    }
    @Test fun recapExactAudioOpensManualEditorAndReturnsToSameWeekWithoutChangingOriginal() {
        f.enableAudio();content();open();click("recap-week");generate()
        val snapshot=f.local.value.factRecap!!.snapshot!!;val source=f.audioIntent
        click("recap-audio:"+source.id);drain()
        compose.runOnIdle {assertEquals(source,f.attachments.state.value!!.focusAudio);assertEquals(0,f.transcriptWrites);assertNull(f.local.value.audioTranscripts);assertEquals(0,f.nativeOpens.get())}
        compose.onNodeWithTag("attachment-audio-play:"+source.id).performScrollTo().assertIsEnabled().performClick();drain()
        compose.waitUntil(5000){f.player.state.value.phase==OriginalAudioPhase.PLAYING}
        compose.onNodeWithTag("attachment-transcript:"+source.id).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {f.transcripts.open(source);assertNull(f.local.value.audioTranscripts);f.closeFails=true}
        compose.onNodeWithTag("audio-playback-stop").assertIsDisplayed().performClick()
        compose.waitUntil(5000){f.player.state.value.phase==OriginalAudioPhase.CLOSING_UNKNOWN}
        compose.runOnIdle {f.transcripts.open(source);assertNull(f.local.value.audioTranscripts);f.closeFails=false}
        compose.onNodeWithTag("audio-playback-stop").assertIsDisplayed().performClick()
        compose.waitUntil(5000){f.player.state.value.phase==OriginalAudioPhase.IDLE}
        compose.runOnIdle {assertEquals(1,f.nativeOpens.get());assertEquals(2,f.nativeCloses.get())}
        compose.onNodeWithTag("attachment-transcript:"+source.id).performScrollTo().performClick();drain()
        compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("合成回顾中的人工转写")
        compose.onNodeWithTag("transcript-save").performScrollTo().assertHeightIsAtLeast(48.dp).performClick();drain()
        compose.runOnIdle {assertEquals(1,f.transcriptWrites);assertEquals("合成回顾中的人工转写",f.savedTranscript!!.current.text)}
        compose.onNodeWithTag("transcript-close").performScrollTo().performClick();compose.waitForIdle()
        compose.onNodeWithText("返回原文").assertIsDisplayed().performClick();compose.waitForIdle()
        compose.onNodeWithText("返回回顾").assertIsDisplayed().performClick();drain()
        compose.onNodeWithTag("recap-audio:"+source.id).assertIsDisplayed()
        compose.runOnIdle {assertSame(snapshot,f.local.value.factRecap!!.snapshot);assertEquals(FactRecapPeriod.WEEK,snapshot.range.period)
            assertEquals(f.records.first().originalText,f.local.value.entries.first().originalText);assertArrayEquals(f.wave,runBlocking{f.store.attachments!!.read(source.id)})}
    }
    @Test fun restoredRecapAudioProofRetiresRealFocusedPanelAndOldEditorBeforeAnotherSource() {
        f.enableAudio();val restore=StateRestorationTester(compose)
        restore.setContent {registry=LocalSaveableStateRegistry.current;AIyouTheme {AIyouApp(previewDestination=Destination.MEMORIES,local=f.local.value,currentRecords={f.local.value},factRecaps=f.c,attachments=f.attachments,audioTranscripts=f.transcripts)}}
        open();generate();val source=f.audioIntent;click("recap-audio:"+source.id);drain()
        val oldPanel=f.attachments.state.value!!
        compose.onNodeWithTag("attachment-transcript:"+source.id).performScrollTo().performClick();drain()
        compose.onNodeWithTag("transcript-text").performScrollTo().performTextReplacement("合成尚未保存的人工输入")
        val oldSession=f.local.value.audioTranscripts!!.session
        compose.runOnIdle {assertFalse(registry!!.performSave().toString().contains("合成尚未保存的人工输入"))}
        restore.emulateSavedInstanceStateRestore();compose.waitForIdle();drain()
        compose.runOnIdle {assertNull(f.attachments.state.value);assertNull(f.local.value.audioTranscripts);f.transcripts.save(oldSession);f.attachments.refresh(oldPanel.sessionId)};drain()
        compose.onNodeWithTag("attachment-"+source.id).assertDoesNotExist()
        open();generate();val other=f.records[1];click("recap-source:"+other.id);drain()
        compose.onNodeWithTag("original-text").assertTextEquals(other.originalText)
        compose.runOnIdle {assertNull(f.attachments.state.value);assertEquals(0,f.transcriptWrites)}
    }
    private class Fixture {
        val clock:Clock=Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"),ZoneId.of("UTC"))
        var records=(0..19).map {StoredEntry(UUID.randomUUID().toString(),1,"合成原文$it："+"文".repeat(300)+"合成全文尾部$it",clock.millis()+it,"UTC",ProcessingPolicy.AI_NONE)}
        var audioEnabled=false;val wave=LocalPcmAudio.encode(ShortArray(441){(it%7).toShort()})
        private val audioId=UUID.randomUUID().toString()
        val audioIntent get()=AttachmentIntent(audioId,records.first().id,1,"audio/wav",java.security.MessageDigest.getInstance("SHA-256").digest(wave).joinToString(""){"%02x".format(it.toInt() and 255)},wave.size.toLong())
        fun enableAudio(){audioEnabled=true;local.value=local.value.copy(supportsAudioTranscripts=true,supportsLocalProcessing=true,localProcessingAvailable=true,localProcessingVersions=records.associate{it.id to LocalProcessingVersion(SourceVersion(it.id,it.currentRevision.toLong()),0)});player.foreground(true)}
        val gate=CaptureDraftGate();val nativeOpens=java.util.concurrent.atomic.AtomicInteger();val nativeCloses=java.util.concurrent.atomic.AtomicInteger();@Volatile var closeFails=false
        var savedTranscript:AudioTranscriptSnapshot?=null;var transcriptWrites=0
        private val imageId=UUID.randomUUID().toString();private val imageEntry=records.first().id
        private val bytes=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNQCp35HwADvQIQaYgMTgAAAABJRU5ErkJggg==")
        private fun picture()=LocalAttachment(AttachmentIntent(imageId,imageEntry,1,"image/png",java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)},bytes.size.toLong()),AttachmentState.READY)
        private val labels=mutableMapOf(imageEntry to ManualTagSnapshot(imageEntry,1,listOf("合成私密标签")))
        val local=mutableStateOf(LocalRecordUiState(ready=true,opening=false,supportsFactRecaps=true,supportsRecordPackages=true,entries=records,manualTagsAvailable=true,manualTags=labels.toMap()))
        val queue=ArrayDeque<suspend(EntryRepository)->Unit>();var fail=false
        fun refresh(){local.value=reconcileFactRecap(local.value,local.value.copy(entries=records))}
        val store=object:EntryRepository {
            override val localProcessing=object:LocalProcessingRepository {
                override fun versions()=local.value.localProcessingVersions
                override fun read(source:SourceVersion):LocalProcessingSnapshot {val e=records.single{it.id==source.sourceId&&it.currentRevision.toLong()==source.revision};return LocalProcessingSnapshot(source,OriginalExports.hash(e.originalText),0,e.policy)}
                override fun change(request:LocalProcessingChange):LocalProcessingSnapshot=error("No range change")
            }
            override val audioTranscripts=object:AudioTranscriptRepository {
                override suspend fun preview(intent:AttachmentIntent):AudioTranscriptSource {check(audioEnabled && intent==audioIntent);val e=records.first();return AudioTranscriptSource(intent,OriginalExports.hash(e.originalText),0,e.capturedAtMillis,e.captureTimeZone,e.policy,441)}
                override suspend fun read(anchor:AudioTranscriptSource)=savedTranscript
                override suspend fun write(request:AudioTranscriptWrite):AudioTranscriptSnapshot {
                    check(request.anchor==preview(audioIntent));check(request.expected?.version==savedTranscript?.version);transcriptWrites++
                    return AudioTranscriptSnapshot(request.id,request.anchor,(savedTranscript?.version?:0)+1,false,savedTranscript?.history.orEmpty()+AudioTranscriptVersion((savedTranscript?.history?.size?:0)+1,request.text!!,request.at)).also{savedTranscript=it}
                }
                override suspend fun search(keyword:String,cursor:PreferenceCursor?):AudioTranscriptPage=error("Not used in recap")
                override suspend fun resolve(page:AudioTranscriptPage,item:AudioTranscriptSnapshot?,publish:()->Unit)=error("Not used in recap")
            }
            override val manualTags=object:ManualTagRepository {
                override fun read(entryId:String)=labels[entryId] ?: ManualTagSnapshot(entryId,0,emptyList())
                override fun snapshots()=labels.toMap()
                override fun replace(mutation:ManualTagMutation):ManualTagSnapshot {
                    require(read(mutation.entryId).version==mutation.expectedVersion)
                    return ManualTagSnapshot(mutation.entryId,mutation.expectedVersion+1,mutation.names).also {labels[mutation.entryId]=it}
                }
            }
            override val factRecaps=object:FactRecapRepository {
                override fun capture(range:FactRecapRange):FactRecapSnapshot {
                    if(fail)error("Synthetic read failure")
                    return object:FactRecapSnapshot {
                        override val range=range
                        override val cards=records.filter {it.capturedAtMillis>=range.startMillis&&it.capturedAtMillis<range.endMillis}.map {
                            FactRecapCard(SourceVersion(it.id,it.currentRevision.toLong()),it.capturedAtMillis,it.captureTimeZone,OriginalExports.hash(it.originalText),0,it.originalText.take(240),it.originalText.length>240,
                                manualTags.read(it.id),if(it.id==imageEntry && it.currentRevision==1)listOf(FactRecapImage(picture().intent,AttachmentState.READY))else emptyList(),it.policy,if(audioEnabled&&it.id==audioIntent.entryId)listOf(FactRecapAudio(audioIntent,AttachmentState.READY))else emptyList())
                        }
                    }
                }
                override fun publishCurrent(snapshot:FactRecapSnapshot,publish:()->Unit):Boolean {publish();return true}
                override fun resolve(snapshot:FactRecapSnapshot,source:SourceVersion,publish:()->Unit):Boolean {if(records.none{it.id==source.sourceId&&it.currentRevision.toLong()==source.revision})return false;publish();return true}
            }
            override val attachments=object:AttachmentStore {
                override suspend fun save(intent:AttachmentIntent,source:()->java.io.InputStream):LocalAttachment=error("Unrelated")
                override suspend fun list(entryId:String,revision:Int)=(listOf(picture())+if(audioEnabled)listOf(LocalAttachment(audioIntent,AttachmentState.READY))else emptyList()).filter{it.intent.entryId==entryId&&it.intent.revision==revision}
                override suspend fun read(id:String)=if(audioEnabled&&id==audioIntent.id)wave.copyOf()else bytes.copyOf()
                override suspend fun recover()=Unit
            }
            override suspend fun entries()=records
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("Unrelated")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("Unrelated")
            override suspend fun revisions(entryId:String)=records.filter{it.id==entryId}.map{OriginalRevision(it.id,it.currentRevision,it.originalText,OriginalExports.hash(it.originalText),it.capturedAtMillis)}
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("Unrelated")
        }
        val c=FactRecapController({local.value},{change->local.value=reconcileFactRecap(local.value,change(local.value))},{queue.add(it);true},clock)
        val transcripts=AudioTranscriptController({local.value},{local.value=it(local.value)},{queue.add(it);true},clock,{gate.sourceUi(it)})
        val tags=ManualTagController({local.value},{change->local.value=reconcileFactRecap(local.value,change(local.value))},{queue.add(it);true})
        val attachments:LocalAttachmentController=LocalAttachmentController({queue.add(it);true},PlatformImageDecoder::decode,
            canUseRecord={local.value.ready},canUsePanel={it.hasRecordAccess(local.value)},sourceUi={gate.sourceUi(it)},onPanelClose={player.stop()})
        val player:OriginalAudioController=OriginalAudioController({local.value},{attachments.state.value},{gate},{queue.add(it);true},{object:OriginalAudioOutput {
            override fun focus()=true
            override fun open(){nativeOpens.incrementAndGet()}
            override fun play(){}
            override fun write(samples:ShortArray,count:Int)=count
            override fun playedFrames()=0L
            override fun close(){nativeCloses.incrementAndGet();check(!closeFails){"Synthetic close ACK failure"}}
        }},{true})
    }
}
