package com.t1doo.aiyou

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual production root/controller/Dialog. Storage here is explicitly synthetic; SQL has separate real-file tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h1000dp")
class ChatHistoryPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val port=Port()
    private val lane=ArrayDeque<suspend(EntryRepository)->Unit>()
    private var writes=0
    private val store=object:EntryRepository {
        override val chatHistory=port
        override suspend fun readDraft():TextDraft?=null
        override suspend fun entries()=emptyList<StoredEntry>()
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun updateDraft(text:String):TextDraft {writes++;error("No Entry write")}
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry {writes++;error("No Entry write")}
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry {writes++;error("No Entry write")}
    }
    private val local=LocalRecordUiState(ready=true,opening=false,text="未保存合成原文草稿",chatHistoryOwner=port)
    private val controller=ChatHistoryController({local},{lane.add(it);true},Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC))
    private fun click(tag:String){compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick();compose.waitForIdle()}
    private fun drain(){compose.runOnIdle{runBlocking{while(lane.isNotEmpty())lane.removeFirst()(store)}};compose.waitForIdle()}
    private fun open(scale:Float){RuntimeEnvironment.setFontScale(scale);compose.setContent{AIyouTheme{
        AIyouApp(local=local,previewSettings=true,chatHistory=controller)}}
        click("settings-chat-history");drain();click("chat-history-item-${port.message.id}");drain()}
    @Test fun actualSettingsHistoryReadsAnswerAndDeletesOnlyAfterReviewWithoutEntryWrites(){
        open(1f);compose.onNodeWithTag("chat-history-question").assertTextEquals("合成历史问题🙂")
        compose.onNodeWithTag("chat-history-answer-0").performScrollTo().assertTextEquals("合成模型回答，待核对。")
        assertEquals(0,port.requests.size)
        click("chat-history-delete");click("chat-history-cancel-delete");assertEquals(0,port.requests.size)
        click("chat-history-delete");click("chat-history-confirm-delete");drain()
        compose.onNodeWithTag("chat-history-empty").assertExists();assertEquals(1,port.requests.size)
        click("chat-history-close");click("settings-chat-history");drain()
        compose.onNodeWithTag("chat-history-empty").assertExists()
        assertEquals(0,writes);assertEquals("未保存合成原文草稿",local.text)
        compose.runOnIdle{controller.dispose()}
    }
    @Test fun actualDoubleFontUnknownDeletionKeepsExactIntentAcrossCloseAndReadCannotClaimEmpty(){
        open(2f);click("chat-history-delete");click("chat-history-confirm-delete")
        port.loseAck=true;drain()
        val pending=controller.visible()!!.pending!!
        compose.onNodeWithTag("chat-history-empty").assertDoesNotExist()
        compose.onNodeWithTag("chat-history-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chat-history-refresh").performScrollTo().assertIsNotEnabled()
        click("chat-history-close");click("settings-chat-history")
        assertSame(pending,controller.visible()!!.pending)
        click("chat-history-retry-delete");drain()
        compose.onNodeWithTag("chat-history-empty").assertExists()
        assertEquals(2,port.requests.size);assertSame(port.requests[0],port.requests[1]);assertEquals(0,writes)
        compose.runOnIdle{controller.dispose()}
    }
    private class Port:ChatHistoryRepository {
        val message=ChatMessage("a".repeat(64),1,10,10,ChatMessageState.ANSWERED,"合成历史问题🙂")
        val reply=SelectedQuestionReply("synthetic-request","synthetic-selection",false,
            listOf(SelectedQuestionParagraph("合成模型回答，待核对。",emptyList())))
        val view=object:ChatMessageView {override val message=this@Port.message;override val reply=this@Port.reply
            override val citations=emptyList<ChatHistoryCitation>()}
        val requests=mutableListOf<DeleteChatMessage>();var deleted=false;var loseAck=false
        override fun list()=if(deleted)emptyList()else listOf(message)
        override fun open(id:String,expectedVersion:Long)=view.takeIf{!deleted&&id==message.id&&expectedVersion==message.version}
        override fun current(view:ChatMessageView)=view===this.view&&!deleted
        override fun locate(view:ChatMessageView,reference:OriginalTextReference):ChatMessageLocation?=null
        override fun locationCurrent(location:ChatMessageLocation)=false
        override fun delete(request:DeleteChatMessage):ChatMessage {
            requests+=request;check(request.id==message.id&&request.expectedVersion==message.version)
            deleted=true
            if(loseAck){loseAck=false;throw ChatHistoryUnavailable()}
            return ChatMessage(message.id,2,10,request.atMillis,ChatMessageState.DELETED,null)
        }
    }
}
