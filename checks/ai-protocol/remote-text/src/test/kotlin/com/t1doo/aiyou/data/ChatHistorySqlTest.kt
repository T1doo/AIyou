package com.t1doo.aiyou.data

import com.t1doo.aiyou.ai.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual production send transactions/ciphers and owned file SQLite; only provider transport is controlled. */
class ChatHistorySqlTest {
    @Test fun actualExchangeCommitsBodyAndFinallyPreservesItAcrossColdOwnerReopen() = SelectedQuestionAuthorizationTest.Harness().use {h->
        val permit=h.authorize();val id=SelectedQuestionSendProof.review(permit.review)
        assertEquals(ChatMessageState.PENDING,h.history.list().single().state)
        val service=h.service {object:LocalQuestionWireExchange {
            override fun cancel() {}
            override fun execute(onChunk:(ByteArray)->Unit){onChunk(h.sse(h.reply(permit.review.request)))}
        }}
        val receipt=PersistedLocalQuestionExchange(h.repo,permit,service,h.clock).execute()
        h.repo.cancelPending(permit);h.repo.cancelPending(permit) // Both real finally layers do this too.
        val message=h.history.list().single();assertEquals(id,message.id);assertEquals(ChatMessageState.ANSWERED,message.state)
        val view=h.history.open(id,message.version)!!;assertEquals(receipt.reply.paragraphs.map{it.text},view.reply!!.paragraphs.map{it.text})
        val location=h.history.locate(view,view.reply!!.paragraphs.first().references.first())!!;assertTrue(h.history.locationCurrent(location))
        val blobs=h.f.db.read("SELECT question,answer FROM chat_messages").single().filterIsInstance<ByteArray>()
        for(blob in blobs){assertFalse(blob.toString(Charsets.UTF_8).contains(permit.review.request.question));assertFalse(blob.toString(Charsets.UTF_8).contains("生成内容待核对"))}
        Files.list(h.f.root).use {paths->paths.filter{it.fileName.toString().startsWith("records.sqlite")}.forEach {file->
            assertFalse(Files.readAllBytes(file).toString(Charsets.UTF_8).contains("生成内容待核对"))
        }}
        h.repo.close();h.history.close();h.f.reopen()
        val fresh=SqlChatHistoryRepository(h.f.gateway,ManualTagSqlTest.KEY)
        try {
            assertFalse(h.history.current(view));assertFalse(fresh.current(view));assertFalse(fresh.locationCurrent(location))
            val reopened=fresh.open(id,message.version)!!
            assertTrue(fresh.current(reopened));assertEquals(view.reply!!.paragraphs.map{it.text},reopened.reply!!.paragraphs.map{it.text})
            assertNotNull(fresh.locate(reopened,reopened.reply!!.paragraphs.first().references.first()))
        }finally{fresh.wipeKey();h.history.wipeKey()}
    }
    @Test fun bodyAndGroupRollbackTogetherAndLostCommitAckDoesNotRewriteOrDuplicate() = SelectedQuestionAuthorizationTest.Harness().use {h->
        val permit=h.authorize();h.repo.admit(permit,h.clock);val reply=h.reply(permit.review.request)
        h.f.db.beforeSql={if(it.startsWith("UPDATE local_jobs SET state="))error("Synthetic group commit failure")}
        assertThrows(SelectedQuestionSendDenied::class.java){h.repo.accept(permit,reply,h.clock)}
        h.f.db.beforeSql=null
        assertEquals(ChatMessageState.PENDING,h.history.list().single().state);assertTrue(h.jobs().all{it[1]=="ADMITTED"})
        val answerWritten=AtomicBoolean();val lost=AtomicBoolean()
        h.f.db.afterSql={sql->
            if(sql.startsWith("UPDATE chat_messages SET version=")&&sql.contains("resultHash"))answerWritten.set(true)
            if(sql=="COMMIT"&&answerWritten.get()&&lost.compareAndSet(false,true))error("Synthetic committed ACK loss")
        }
        assertThrows(SelectedQuestionSendDenied::class.java){h.repo.accept(permit,reply,h.clock)}
        h.f.db.afterSql=null;assertTrue(lost.get())
        val bytes=h.f.db.read("SELECT answer FROM chat_messages").single().single() as ByteArray
        val receipt=h.repo.accept(permit,reply,h.clock);assertNull(h.repo.checkReceipt(receipt,h.clock))
        assertEquals(1,h.history.list().size);assertArrayEquals(bytes,h.f.db.read("SELECT answer FROM chat_messages").single().single() as ByteArray)
    }
    @Test fun deletingPendingOrCommittedHistoryBlocksEveryOldSendAndReceiptWithoutResurrection() = SelectedQuestionAuthorizationTest.Harness().use {h->
        val pending=h.authorize();val first=h.history.list().single()
        val deletion=DeleteChatMessage(first.id,first.version,UUID.randomUUID().toString(),first.createdAtMillis+1)
        h.history.delete(deletion);h.history.delete(deletion)
        assertNotNull(h.repo.check(pending,h.clock));assertThrows(SelectedQuestionSendDenied::class.java){h.repo.admit(pending,h.clock)}
        var invalidated=false;h.repo.subscribe(pending,h.clock){invalidated=true}.close();assertTrue(invalidated)
        h.repo.cancelPending(pending)
        val live=h.authorize();h.repo.admit(live,h.clock);val receipt=h.repo.accept(live,h.reply(live.review.request),h.clock)
        val second=h.history.list().single();val old=h.history.open(second.id,second.version)!!
        h.history.delete(DeleteChatMessage(second.id,second.version,UUID.randomUUID().toString(),second.createdAtMillis+1))
        assertFalse(h.history.current(old));assertNotNull(h.repo.checkReceipt(receipt,h.clock))
        assertThrows(SelectedQuestionSendDenied::class.java){h.repo.accept(live,receipt.reply,h.clock)}
        assertTrue(h.history.list().isEmpty());assertTrue(h.f.db.read("SELECT state,length(answer) FROM chat_messages").all{it==listOf("DELETED",0L)})
    }
    @Test fun anySelectedSourceRetiresAnswerButPreservesQuestionThroughRevisionAndPermissionAba() {
        for(kind in listOf("revision","authorization","trash"))SelectedQuestionAuthorizationTest.Harness().use {h->
            val permit=h.authorize();h.repo.admit(permit,h.clock);val receipt=h.repo.accept(permit,h.reply(permit.review.request),h.clock)
            val before=h.history.list().single();val view=h.history.open(before.id,before.version)!!
            when(kind){"revision"->h.revise(1);"authorization"->{h.withdrawSource(1);h.approve(1)};else->h.f.intent(h.ids[1])}
            val after=h.history.list().single();assertEquals(permit.review.request.question,after.question)
            assertEquals(ChatMessageState.INVALIDATED,after.state);assertFalse(h.history.current(view));assertNull(h.history.open(after.id,after.version)!!.reply)
            assertNotNull(h.repo.checkReceipt(receipt,h.clock));assertEquals(0L,h.f.db.read("SELECT length(answer) FROM chat_messages").single().single())
            assertThrows(SelectedQuestionSendDenied::class.java){h.repo.accept(permit,receipt.reply,h.clock)}
        }
    }
    @Test fun actualPermanentErasureOfSecondarySourceClearsAllQuotedAnswerPayloads():Unit=runBlocking {
        SelectedQuestionAuthorizationTest.Harness().use {h->
            val permit=h.authorize();h.repo.admit(permit,h.clock);h.repo.accept(permit,h.reply(permit.review.request),h.clock)
            val before=h.history.list().single();h.f.erase.erase(h.f.intent(h.ids[1]))
            val after=h.history.list().single();assertEquals(before.question,after.question);assertEquals(ChatMessageState.INVALIDATED,after.state)
            assertTrue(h.f.db.read("SELECT entryId FROM chat_message_sources WHERE entryId=?",h.ids[1]).isEmpty())
            assertEquals(0L,h.f.db.read("SELECT length(answer) FROM chat_messages").single().single());assertNull(h.history.open(after.id,after.version)!!.reply)
        }
    }
    @Test fun maximumAnswerBytesAreReservedBeforeSendAndRejectedAdmissionLeavesNoPartialGroup() = SelectedQuestionAuthorizationTest.Harness().use {h->
        var accepted=0
        while(accepted<ChatMessages.MAX_RETAINED) {
            try {h.authorize();accepted++}catch(_:SelectedQuestionSendDenied){break}
        }
        assertTrue(accepted>0&&accepted<ChatMessages.MAX_RETAINED)
        val groups=h.groups();val count=h.history.list().size
        assertThrows(SelectedQuestionSendDenied::class.java){h.authorize()}
        assertEquals(groups,h.groups());assertEquals(count,h.history.list().size)
        val rows=h.f.db.read("SELECT length(question),length(answer) FROM chat_messages")
        assertTrue(rows.sumOf{it[0] as Long+ChatMessages.MAX_ANSWER_CELL}<=ChatMessages.MAX_BYTES)
        assertTrue(rows.sumOf{(it[0] as Long)+(it[1] as Long)}<ChatMessages.MAX_BYTES)
        val first=h.history.list().first();h.history.delete(DeleteChatMessage(first.id,first.version,UUID.randomUUID().toString(),first.createdAtMillis+1))
        val permit=h.authorize();h.repo.admit(permit,h.clock)
        val maximum=SelectedQuestionReply(permit.review.request.id,permit.review.request.selectionId,false,
            listOf(SelectedQuestionParagraph("🙂".repeat(1200),listOf(permit.review.request.sources[0].reference))))
        h.repo.accept(permit,maximum,h.clock)
        assertTrue(h.history.list().any{it.state==ChatMessageState.ANSWERED})
    }
}
