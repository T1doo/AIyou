package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SavedQuestionsControllerTest {
    @Test fun changingSelectionNeedsExplicitDiscardAndCloseKeepsTheUnsubmittedBuffer():Unit=Harness().use {h ->
        h.c.open("未保存的合成输入");h.drain();h.c.select(h.ui,null)
        assertNotNull(h.ui.replacement);h.c.cancelSwitch(h.ui);assertEquals("未保存的合成输入",h.ui.text)
        h.c.select(h.ui,null);val stale=h.ui;h.c.close(stale);h.c.open("新入口不能覆盖旧输入");h.drain()
        h.c.confirmSwitch(stale);assertEquals("未保存的合成输入",h.ui.text);assertNull(h.ui.replacement)
        h.c.select(h.ui,null);h.c.confirmSwitch(h.ui);assertEquals("",h.ui.text)
        assertEquals(0,h.port.writes.size);assertTrue(h.q.isEmpty())
    }
    @Test fun closingAndReopeningRetiresUnsubmittedConfirmationAndEveryOldUiCallback():Unit=Harness().use {h ->
        h.c.open("还没有提交的问题");h.drain();h.c.review(h.ui);val stale=h.ui
        h.c.close(stale);h.c.open();h.drain();assertNull(h.ui.confirmation)
        h.c.confirm(stale);h.c.edit(stale,"迟到编辑");h.c.close(stale)
        assertNotNull(h.c.visible());assertTrue(h.q.isEmpty());assertEquals("还没有提交的问题",h.ui.text)
        h.c.review(h.ui);h.c.confirm(h.ui);h.drain();assertEquals(1,h.port.writes.size)
    }
    @Test fun confirmedCapacityRefusalDoesNotFreezeAllExistingQuestionsAsUnknown():Unit=Harness().use {h ->
        repeat(SavedQuestions.MAX_RETAINED) {n ->h.port.storage.save(SaveQuestion(java.util.UUID.randomUUID().toString(),0,
            java.util.UUID.randomUUID().toString(),"已有问题$n",10))}
        h.c.open("超出容量的新问题");h.drain();h.c.review(h.ui);h.c.confirm(h.ui);h.drain()
        assertNull(h.ui.pending);assertFalse(h.ui.busy);assertEquals("超出容量的新问题",h.ui.text);assertNotNull(h.ui.error)
        h.c.select(h.ui,h.ui.items.first());assertNotNull(h.ui.replacement);h.c.confirmSwitch(h.ui)
        h.c.edit(h.ui,"仍可编辑已有问题");h.c.review(h.ui);h.c.confirm(h.ui);h.drain()
        assertEquals("仍可编辑已有问题",h.ui.selected!!.question)
    }
    @Test fun onlyExplicitConfirmationWritesAndEditingOrDeletingNeedsANewConfirmation():Unit=Harness().use { h ->
        h.c.open("合成离线问题");h.drain();assertTrue(h.ui.available)
        h.c.review(h.ui);assertNotNull(h.ui.confirmation);assertTrue(h.f.db.read("SELECT * FROM saved_questions").isEmpty())
        h.c.cancelReview(h.ui);assertTrue(h.q.isEmpty())
        h.c.review(h.ui);h.c.confirm(h.ui);assertTrue(h.ui.busy);h.drain()
        assertEquals(1,h.port.writes.size);assertEquals("合成离线问题",h.ui.selected!!.question)
        val id=h.ui.selected!!.id;h.c.edit(h.ui,"修改的问题");h.c.review(h.ui);h.c.confirm(h.ui);h.drain()
        assertEquals(2L,h.port.read(id)!!.version)
        assertEquals("修改的问题",h.c.questionForUse(h.ui));assertEquals(2,h.port.writes.size)
        h.c.review(h.ui,true);h.c.cancelReview(h.ui);assertNotNull(h.port.read(id)!!.question)
        h.c.review(h.ui,true);h.c.confirm(h.ui);h.drain();assertTrue(h.ui.items.isEmpty());assertNull(h.port.read(id)!!.question)
        h.c.close(h.ui);h.c.open();assertTrue(h.ui.items.isEmpty());assertEquals(3,h.port.writes.size)
        assertTrue(h.f.db.read("SELECT * FROM entries").isEmpty());assertTrue(h.f.db.read("SELECT * FROM local_candidate_batches").isEmpty())
    }
    @Test fun lostCommitAckFreezesInputAcrossCloseAndReopenAndRetriesSameIntentOnce():Unit=Harness().use { h ->
        h.c.open("不能丢失的合成问题");h.drain();h.c.review(h.ui);h.c.confirm(h.ui)
        val pending=h.ui.pending!!;var wrote=false;var lost=false
        h.f.db.beforeSql={if(it.startsWith("INSERT INTO saved_questions"))wrote=true}
        h.f.db.afterSql={if(it=="COMMIT"&&wrote&&!lost){lost=true;error("Synthetic durable ACK loss")}}
        h.drain();h.f.db.beforeSql=null;h.f.db.afterSql=null
        assertTrue(lost);assertSame(pending,h.ui.pending);assertFalse(h.ui.busy)
        assertNull(h.c.questionForUse(h.ui))
        assertFalse(h.ui.showEmpty)
        h.c.edit(h.ui,"另一个意图");h.c.select(h.ui,null);h.c.review(h.ui,true);h.c.refresh(h.ui)
        assertEquals("不能丢失的合成问题",h.ui.text);assertSame(pending,h.ui.pending);assertTrue(h.q.isEmpty())
        h.c.close(h.ui);assertNull(h.c.visible());h.c.open("不应覆盖未知输入")
        assertSame(pending,h.ui.pending);h.c.retry(h.ui);h.drain()
        assertNull(h.ui.pending);assertEquals(1L,h.ui.selected!!.version)
        assertEquals(2,h.port.writes.size);assertSame(h.port.writes[0],h.port.writes[1])
        assertEquals(1L,h.f.db.read("SELECT count(*) FROM saved_questions").single().single())
    }
    @Test fun temporaryUnavailableSameOwnerKeepsUnknownIntentButTrueOwnerChangeStillRetires():Unit=Harness().use {h ->
        h.c.open("不能因读取错误丢失的问题");h.drain();h.c.review(h.ui);h.c.confirm(h.ui)
        val pending=h.ui.pending!!;var wrote=false;var lost=false
        h.f.db.beforeSql={if(it.startsWith("INSERT INTO saved_questions"))wrote=true}
        h.f.db.afterSql={if(it=="COMMIT"&&wrote&&!lost){lost=true;error("Synthetic lost ACK")}}
        h.drain();h.f.db.beforeSql=null;h.f.db.afterSql=null;assertTrue(lost)
        val before=h.ui;h.available=false;h.c.sync();assertNull(h.c.visible())
        h.c.retry(before);h.c.edit(before,"不能编辑");assertTrue(h.q.isEmpty())
        h.available=true;h.c.open();assertSame(pending,h.ui.pending);assertEquals(before.text,h.ui.text)
        h.c.retry(h.ui);h.drain();assertSame(h.port.writes[0],h.port.writes[1]);assertEquals(1L,h.ui.selected!!.version)
        h.owner=null;h.c.sync();assertNull(h.c.visible());h.owner=h.port;h.c.open();h.drain()
        assertNull(h.ui.pending);assertNull(h.ui.selected);assertEquals(1,h.ui.items.size)
    }
    @Test fun definiteConflictCanReadCurrentVersionButUnknownConflictNeverDropsPriorIntent():Unit=Harness().use {h ->
        h.c.open("旧版本问题");h.drain();h.c.review(h.ui);h.c.confirm(h.ui);h.drain();val old=h.ui.selected!!
        h.port.storage.save(SaveQuestion(old.id,1,java.util.UUID.randomUUID().toString(),"其他会话已编辑",101))
        h.c.edit(h.ui,"不能覆盖其他会话");h.c.review(h.ui);h.c.confirm(h.ui);h.drain()
        assertNull(h.ui.pending);assertTrue(h.ui.selectionStale);h.c.refresh(h.ui);h.drain()
        h.c.select(h.ui,h.ui.items.single());h.c.confirmSwitch(h.ui);assertEquals("其他会话已编辑",h.ui.text)
        h.c.edit(h.ui,"本次明确编辑");h.c.review(h.ui);h.c.confirm(h.ui);val unknown=h.ui.pending!!
        var wrote=false;var lost=false
        h.f.db.beforeSql={if(it.startsWith("UPDATE saved_questions"))wrote=true}
        h.f.db.afterSql={if(it=="COMMIT"&&wrote&&!lost){lost=true;error("Synthetic lost ACK")}}
        h.drain();h.f.db.beforeSql=null;h.f.db.afterSql=null
        h.port.storage.save(SaveQuestion(old.id,3,java.util.UUID.randomUUID().toString(),"其后再次编辑",102))
        h.c.retry(h.ui);h.drain();assertSame(unknown,h.ui.pending);assertTrue(h.ui.pendingUncertain);assertTrue(h.ui.pendingConflict)
        h.c.refresh(h.ui);h.drain();assertSame(unknown,h.ui.pending);assertEquals("其后再次编辑",h.ui.items.single().question)
        h.c.edit(h.ui,"不能偷换意图");assertSame(unknown,h.ui.pending);assertFalse(h.ui.showEmpty)
    }
    @Test fun acceptedWriteFinishesAfterUiCloseWithoutReopeningButOwnerReplacementRejectsOldActions():Unit=Harness().use { h ->
        h.c.open("确认后关闭的问题");h.drain();h.c.review(h.ui);h.c.confirm(h.ui);val stale=h.ui
        h.c.close(h.ui);h.drain();assertNull(h.c.visible());assertEquals(1,h.port.list().size)
        h.c.open();h.drain();assertEquals("确认后关闭的问题",h.ui.selected!!.question)
        h.c.select(h.ui,null);h.c.edit(h.ui,"旧owner未执行问题");h.c.review(h.ui);h.c.confirm(h.ui)
        h.owner=null;h.c.sync();assertNull(h.c.visible());h.drain();assertEquals(1,h.port.writes.size)
        h.owner=h.port;h.c.open();h.drain();h.c.confirm(stale);h.c.edit(stale,"迟到")
        assertTrue(h.q.isEmpty());assertEquals(1,h.ui.items.size)
        h.c.dispose();h.c.open();assertNull(h.c.visible())
    }
    @Test fun failedReadIsNotAnEmptyCollectionAndRefreshCannotTurnAStaleEditIntoNewMessage():Unit=Harness().use { h ->
        h.port.failRead=true;h.c.open();h.drain();assertFalse(h.ui.available);assertNotNull(h.ui.error)
        h.c.review(h.ui);assertTrue(h.q.isEmpty());h.port.failRead=false;h.c.refresh(h.ui);h.drain()
        h.c.edit(h.ui,"保存后会被另一会话修改的问题");h.c.review(h.ui);h.c.confirm(h.ui);h.drain()
        val old=h.ui.selected!!
        h.port.storage.save(SaveQuestion(old.id,old.version,java.util.UUID.randomUUID().toString(),"另一会话的新版本",101))
        h.c.refresh(h.ui);h.drain();assertTrue(h.ui.selectionStale);assertEquals(old.question,h.ui.text)
        h.c.review(h.ui);assertNull(h.ui.confirmation);assertEquals(1,h.port.writes.size)
        h.c.select(h.ui,h.ui.items.single());h.c.confirmSwitch(h.ui);assertFalse(h.ui.selectionStale);assertEquals("另一会话的新版本",h.ui.text)
    }
    @Test fun rejectedInputCannotSaveAnOlderValidQuestionAndQueueRejectionKeepsExactIntent():Unit=Harness().use { h ->
        h.c.open("原有合成问题");h.drain();h.c.edit(h.ui,"x".repeat(513));assertTrue(h.ui.rejectedInput)
        h.c.review(h.ui);assertNull(h.ui.confirmation);assertTrue(h.q.isEmpty())
        h.c.edit(h.ui,"修正后的合成问题");h.c.review(h.ui);h.rejectQueue=true;h.c.confirm(h.ui)
        val request=h.ui.pending!!;assertFalse(h.ui.busy);assertEquals(0,h.port.writes.size)
        h.rejectQueue=false;h.c.retry(h.ui);h.drain();assertSame(request,h.port.writes.single());assertNull(h.ui.pending)
    }
    private class Harness:AutoCloseable {
        val f=FullFixture();val port=Port(SqlSavedQuestionRepository(f.gateway,f.key))
        var owner:SavedQuestionRepository?=port;var available=true;val q=ArrayDeque<suspend (EntryRepository)->Unit>();var rejectQueue=false
        val store=object:EntryRepository {
            override val savedQuestions get()=owner
            override suspend fun entries()=emptyList<StoredEntry>()
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("Question history must not update capture")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("Must not save an Entry")
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("Must not revise an Entry")
        }
        val c=SavedQuestionsController({owner},{if(rejectQueue)false else {q.add(it);true}},Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC),{available})
        val ui get()=checkNotNull(c.visible())
        fun drain()=runBlocking {while(q.isNotEmpty())q.removeFirst()(store)}
        override fun close(){c.dispose();f.db.beforeSql=null;f.db.afterSql=null;port.storage.wipeKey();f.close()}
    }
    private class Port(val storage:SqlSavedQuestionRepository):SavedQuestionRepository {
        var failRead=false;val writes=mutableListOf<SaveQuestion>()
        override fun read(id:String)=storage.read(id)
        override fun list():List<SavedQuestion> {if(failRead)error("Synthetic read failure");return storage.list()}
        override fun save(request:SaveQuestion):SavedQuestion {writes+=request;return storage.save(request)}
    }
}
