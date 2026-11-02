package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real encrypted SQL history; a controlled records lane, not a device or provider service. */
class ChatHistoryControllerTest {
    private class Host:AutoCloseable {
        val h=SelectedQuestionAuthorizationTest.Harness()
        val base=SqliteVmEntryRepository(h.f.db,h.f.gateway,h.raw){}
        val store=object:EntryRepository by base {override val chatHistory=h.history}
        val lane=ArrayDeque<suspend(EntryRepository)->Unit>()
        var reject=false
        var local=LocalRecordUiState(ready=true,opening=false,chatHistoryOwner=h.history,
            supportsLocalProcessing=true,localProcessingVersions=SqlLocalProcessingRepository(h.f.gateway).versions(),
            entries=runBlocking{base.entries()})
        val controller=ChatHistoryController({local},{action->if(reject)false else{lane.addLast(action);true}},
            Clock.fixed(h.clock(),ZoneOffset.UTC))
        fun drain(){runBlocking{while(lane.isNotEmpty())lane.removeFirst()(store)}}
        fun answer(){val p=h.authorize();h.repo.admit(p,h.clock);h.repo.accept(p,h.reply(p.review.request),h.clock)}
        fun selected():ChatHistoryUi {controller.open();drain();val u=controller.visible()!!;controller.select(u,u.items.first());drain();return controller.visible()!!}
        fun ui()=controller.visible()!!
        override fun close(){controller.dispose();h.close()}
    }
    @Test fun actualSqlReadSourceAndPlainQuestionAreOneUseWithoutResendingOrWritingEntries()=Host().use {x->
        x.answer();val u=x.selected();assertNotNull(u.view!!.reply);assertEquals(2,u.view.citations.size)
        val before=x.h.f.db.read("SELECT * FROM entry_revisions")
        val groups=x.h.groups()
        x.controller.useQuestion(u);x.drain();val q=x.ui();val token=q.questionUse!!
        var text:String?=null;assertTrue(x.controller.consumeQuestion(q,token){text=it;true});assertEquals(u.selected!!.question,text)
        assertFalse(x.controller.consumeQuestion(q,token){fail("already consumed");true})
        val again=x.selected();val ref=again.view!!.reply!!.paragraphs.first().references.first()
        x.controller.openSource(again,ref);x.drain();val jump=x.ui();val source=jump.jump!!
        assertTrue(x.h.history.locationCurrent(source.location));assertTrue(source.location.generation>=0)
        assertTrue(x.controller.consumeSource(jump,source){assertEquals(ref,it.reference);true})
        assertEquals(groups,x.h.groups());assertEquals(before,x.h.f.db.read("SELECT * FROM entry_revisions"))
    }
    @Test fun acceptedDeleteSurvivesCloseAndSourceViewRetirementWhileActorIsBlocked()=Host().use {x->
        x.answer();val selected=x.selected();x.controller.reviewDelete(selected);val review=x.ui()
        var before=0;x.controller.confirmDelete(review){before++};val pending=x.ui().pending!!
        assertEquals(1,before);assertEquals(1,x.lane.size)
        x.controller.retireSources();x.controller.close(x.ui());assertNull(x.controller.visible())
        x.controller.open();assertSame(pending,x.ui().pending);assertEquals(1,x.lane.size)
        x.drain();assertNull(x.ui().pending);assertTrue(x.h.history.list().isEmpty())
        x.controller.confirmDelete(review);assertTrue(x.lane.isEmpty())
        assertEquals(1,x.h.f.db.read("SELECT * FROM chat_messages WHERE state='DELETED'").size)
    }
    @Test fun lostDeleteAckFreezesSameIntentThroughRefreshCloseAndRetry()=Host().use {x->
        x.answer();x.controller.reviewDelete(x.selected());x.controller.confirmDelete(x.ui())
        val intent=x.ui().pending!!;var once=false
        x.h.f.db.afterSql={sql->if(sql=="COMMIT"&&!once){once=true;error("synthetic lost delete ACK")}}
        x.drain();x.h.f.db.afterSql=null
        assertTrue(x.ui().uncertain);assertSame(intent,x.ui().pending)
        x.controller.refresh(x.ui());assertTrue(x.lane.isEmpty());assertSame(intent,x.ui().pending)
        x.controller.close(x.ui());x.controller.open();assertSame(intent,x.ui().pending)
        x.controller.retryDelete(x.ui());x.drain();assertNull(x.ui().pending);assertTrue(x.h.history.list().isEmpty())
        assertEquals(intent.mutationId,x.h.f.db.read("SELECT lastMutationId FROM chat_messages").single().single())
    }
    @Test fun failedReadNeverClaimsEmptyAndOldOwnerCallbackCannotDeleteOrNavigate()=Host().use {x->
        x.answer();val selected=x.selected();val ref=selected.view!!.reply!!.paragraphs.first().references.first()
        x.controller.openSource(selected,ref);x.drain();val jump=x.ui()
        x.local=x.local.copy(chatHistoryOwner=null);x.controller.sync();assertNull(x.controller.visible())
        assertFalse(x.controller.consumeSource(jump,jump.jump!!){fail("retired owner");true})
        x.local=x.local.copy(chatHistoryOwner=x.h.history);x.reject=true;x.controller.open()
        assertFalse(x.ui().showEmpty);assertFalse(x.ui().available);assertNotNull(x.ui().error)
        x.reject=false;x.controller.refresh(x.ui());x.drain();assertEquals(1,x.ui().items.size)
        x.controller.select(x.ui(),x.ui().items.single());x.drain();x.controller.reviewDelete(x.ui());x.controller.confirmDelete(x.ui())
        x.local=x.local.copy(chatHistoryOwner=null);x.controller.sync();x.drain()
        assertEquals(1,x.h.history.list().size)
    }
    @Test fun closeReopenRetiresQueuedReadHandoffsButNotAcceptedDeletion() {
        for(source in listOf(false,true))Host().use{x->
            x.answer();val selected=x.selected()
            if(source)x.controller.openSource(selected,selected.view!!.reply!!.paragraphs.first().references.first())
            else x.controller.useQuestion(selected)
            assertEquals(1,x.lane.size)
            x.controller.close(x.ui());x.controller.open();assertEquals(1,x.lane.size)
            x.drain();assertNull(x.ui().jump);assertNull(x.ui().questionUse);assertTrue(x.ui().stale)
            x.controller.refresh(x.ui());x.drain();assertNotNull(x.ui().view)
        }
    }
    @Test fun sourceGenerationChangeRetiresPendingHandoffWithoutReusingSendAuthorization()=Host().use {x->
        x.answer();val u=x.selected();val ref=u.view!!.reply!!.paragraphs.first().references.first()
        x.controller.openSource(u,ref);x.drain();val ready=x.ui();assertNotNull(ready.jump)
        val location=ready.jump!!.location;assertTrue(x.local.canUseChatLocation(x.h.history,location))
        val original=location.entry
        for(changed in listOf(
            original.copy(capturedAtMillis=original.capturedAtMillis+1),
            original.copy(captureTimeZone="Asia/Tokyo"),original.copy(policy=ProcessingPolicy.AI_NONE))) {
            val altered=x.local.copy(entries=x.local.entries.map{if(it.id==changed.id)changed else it})
            assertFalse(altered.canUseChatLocation(x.h.history,location))
        }
        assertFalse(x.local.canUseChatLocation(null,location))

        x.h.withdrawSource(0);x.h.approve(0)
        x.local=x.local.copy(localProcessingVersions=SqlLocalProcessingRepository(x.h.f.gateway).versions())
        x.controller.sync();assertTrue(x.ui().stale);assertNull(x.ui().jump)
        assertFalse(x.controller.consumeSource(ready,ready.jump!!){fail("stale private proof");true})
        x.controller.refresh(x.ui());x.drain();assertNull(x.ui().view!!.reply)
        assertEquals(u.selected!!.question,x.ui().selected!!.question)
    }
}
