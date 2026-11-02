package com.t1doo.aiyou

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Production root/settings/real history controller/Dialog; only storage is a declared synthetic port. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h1000dp")
class SavedQuestionsPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val f=Fixture()
    private val local=LocalRecordUiState(ready=true,opening=false,text="合成未保存原文草稿",savedQuestionOwner=f.port)
    private fun screen(scale:Float) {
        RuntimeEnvironment.setFontScale(scale)
        compose.setContent {AIyouTheme {AIyouApp(local=local,previewSettings=true,savedQuestions=f.controller)}}
        click("settings-saved-questions");drain()
    }
    private fun click(tag:String) {compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick();compose.waitForIdle()}
    private fun drain() {compose.runOnIdle {runBlocking {while(f.queue.isNotEmpty())f.queue.removeFirst()(f.store)}};compose.waitForIdle()}
    private fun input(value:String) {compose.onNodeWithTag("saved-question-input").performScrollTo().performTextReplacement(value);compose.waitForIdle()}
    @Test fun actualSettingsEntrySavesEditsDeletesOnlyAfterConfirmationAndReopensWithoutEntryWrites() {
        screen(1f);input("合成离线问题🙂");frame("01-normal")
        click("saved-question-new");click("saved-question-keep-input")
        compose.onNodeWithTag("saved-question-input").assertTextContains("合成离线问题🙂")
        click("saved-question-save");compose.runOnIdle {assertTrue(f.port.rows.isEmpty());assertEquals(0,f.port.calls)}
        click("saved-question-cancel-review");click("saved-question-save");click("saved-question-confirm");drain()
        val id=f.port.rows.keys.single();assertEquals("合成离线问题🙂",f.port.rows.getValue(id).question)
        click("saved-question-close");click("settings-saved-questions");drain()
        compose.onNodeWithTag("saved-question-input").assertTextContains("合成离线问题🙂")
        input("明确编辑后的问题");click("saved-question-save");click("saved-question-confirm");drain()
        assertEquals(2L,f.port.rows.getValue(id).version)
        click("saved-question-delete");click("saved-question-confirm");drain()
        assertNull(f.port.rows.getValue(id).question);assertEquals(3L,f.port.rows.getValue(id).version)
        compose.onNodeWithTag("saved-questions-empty").assertExists()
        assertEquals("合成未保存原文草稿",local.text);assertEquals(0,f.entryWrites)
        compose.runOnIdle {f.controller.dispose()}
    }
    @Test fun actualDoubleFontUnknownNeverShowsFalseEmptyAndKeepsSameIntentAcrossClose() {
        screen(2f);input("合成待确认问题：重开后保留同一次操作，不会另存成原文。")
        compose.onNodeWithTag("saved-question-input").performScrollTo()
        val layouts=mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("saved-question-input").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        val layout=layouts.last();assertEquals(2f,layout.layoutInput.density.fontScale,0f);assertFalse(layout.hasVisualOverflow)
        compose.runOnIdle {assertEquals(2f,checkNotNull(ShadowDialog.getLatestDialog()).context.resources.configuration.fontScale,0f)}
        frame("02-double-font")
        f.port.loseAck=true;click("saved-question-save");click("saved-question-confirm");val intent=f.controller.state.value!!.pending!!;drain()
        compose.onNodeWithTag("saved-questions-empty").assertDoesNotExist()
        compose.onNodeWithTag("saved-question-input").assertIsNotEnabled()
        click("saved-question-close");click("settings-saved-questions")
        compose.runOnIdle {assertSame(intent,f.controller.state.value!!.pending)}
        click("saved-question-retry");drain();assertSame(f.port.requests[0],f.port.requests[1])
        assertEquals(1,f.port.rows.size);assertEquals(1L,f.port.rows.values.single().version);assertEquals(0,f.entryWrites)
        val out=File("build/reports/tests/testDebugUnitTest/saved-questions/fontscale.json");out.parentFile.mkdirs()
        out.writeText(JSONObject().put("source_sha",System.getenv("GITHUB_SHA")?:"local-not-CI")
            .put("configurationFontScale",2f).put("layoutFontScale",layout.layoutInput.density.fontScale)
            .put("inputHasVisualOverflow",layout.hasVisualOverflow).put("questionMutationCalls",f.port.calls)
            .put("questionVersion",f.port.rows.values.single().version).put("entryWrites",f.entryWrites)
            .put("scope","Production Dialog with synthetic storage; not device or real remote evidence").toString(2))
        compose.runOnIdle {f.controller.dispose()}
    }
    private fun frame(name:String) {
        val image=compose.onNodeWithTag("saved-questions-scroll").captureToImage().asAndroidBitmap()
        val file=File("build/reports/tests/testDebugUnitTest/saved-questions/$name.png");file.parentFile.mkdirs()
        file.outputStream().use {check(image.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    private class Fixture {
        val queue=ArrayDeque<suspend (EntryRepository)->Unit>();val port=Port();var entryWrites=0
        val store=object:EntryRepository {
            override val savedQuestions=port
            override suspend fun readDraft():TextDraft?=null
            override suspend fun entries()=emptyList<StoredEntry>()
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun updateDraft(text:String):TextDraft {entryWrites++;error("No capture writes")}
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry {entryWrites++;error("No Entry saves")}
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry {entryWrites++;error("No Entry edits")}
        }
        val controller=SavedQuestionsController({port},{queue.add(it);true},Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC))
    }
    private class Port:SavedQuestionRepository {
        val rows=linkedMapOf<String,SavedQuestion>();val requests=mutableListOf<SaveQuestion>();var calls=0;var loseAck=false
        private var last:SaveQuestion?=null
        override fun read(id:String)=rows[id]
        override fun list()=rows.values.filter {it.question!=null}
        override fun save(request:SaveQuestion):SavedQuestion {
            calls++;requests+=request
            if(last===request)return rows.getValue(request.id)
            val old=rows[request.id];check((old?.version?:0)==request.expectedVersion)
            val next=SavedQuestion(request.id,request.expectedVersion+1,old?.createdAtMillis?:request.atMillis,request.atMillis,request.question)
            rows[request.id]=next;last=request
            if(loseAck){loseAck=false;throw SavedQuestionStorageFailure()}
            return next
        }
    }
}
