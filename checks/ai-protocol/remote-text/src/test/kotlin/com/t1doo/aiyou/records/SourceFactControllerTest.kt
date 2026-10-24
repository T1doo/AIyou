package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual controller + encrypted SQLite repository. Queue barriers never invoke a UI callback as a write. */
class SourceFactControllerTest {
    @Test fun twoExplicitConfirmationsCorrectionReentersProposedAndRejectedNeverReopens():Unit=runBlocking {Harness().use{h->
        val s=h.open();h.form();h.c.save(s);val old=h.ui().confirmation!!
        h.c.cancel(s,old);assertEquals(0,h.creates.size)
        h.c.save(s);val fresh=h.ui().confirmation!!;h.c.confirm(s,old);assertSame(fresh,h.ui().confirmation)
        h.confirm();var item=h.ui().items.single();assertEquals(FactReview.PROPOSED,item.review!!.review)
        h.action(item,FactAction.ACCEPT);item=h.ui().items.single();assertEquals(FactReview.ACCEPTED,item.review!!.review)
        h.c.correct(s,item);h.c.edit(s,FactInput.FROM,"2026-10-24");h.c.edit(s,FactInput.ZONE,"UTC");h.c.affirm(s,true);h.c.save(s);h.confirm()
        item=h.ui().items.single();assertEquals(FactReview.PROPOSED,item.review!!.review);assertEquals(FactReview.ACCEPTED,item.reviews.first().priorReview)
        h.action(item,FactAction.REJECT);item=h.ui().items.single();assertEquals(FactReview.REJECTED,item.review!!.review)
        h.c.action(s,item,FactAction.ACCEPT);assertNull(h.ui().confirmation);assertTrue(h.q.isEmpty())
        assertEquals(h.text,h.base.entries().single{it.id==h.f.source.sourceId}.originalText)
    }}
    @Test fun unknownWriteCannotRefreshOrReplaceImmutableIntentAndRetryCommitsOnlyOnce():Unit=runBlocking {Harness().use{h->
        val s=h.open();h.form();h.c.save(s);h.loseAck=true;h.confirm()
        val unknown=h.ui();assertNotNull(unknown.pending);assertNull(unknown.sourcePage);assertTrue(unknown.items.isEmpty());assertNotNull(unknown.error)
        val intent=unknown.pending
        h.failRead=true;h.c.refresh(s);assertTrue(h.q.isEmpty());assertSame(intent,h.ui().pending)
        h.c.edit(s,FactInput.FROM,"2027-01-01");h.c.affirm(s,false);h.c.save(s);assertSame(intent,h.ui().pending)
        h.failRead=false;h.c.retry(s);h.drain();assertNull(h.ui().pending);assertEquals(2,h.creates.size)
        assertSame(h.creates[0],h.creates[1]);assertEquals(1L,h.store.facts(h.f.source).single().rowVersion)
    }}
    @Test fun eachRejectedEndpointStaysRejectedAcrossOtherEditsRefreshAndAffirmUntilExplicitCorrection():Unit=runBlocking {
        for(field in listOf(FactInput.FROM,FactInput.UNTIL))Harness().use{h->
            val s=h.open();h.form();h.c.edit(s,FactInput.FROM,"2026-10-24");h.c.edit(s,FactInput.UNTIL,"2026-10-25");h.c.edit(s,FactInput.ZONE,"UTC")
            h.c.edit(s,field,"2026-10-240");assertTrue(field in h.ui().rejectedInput)
            h.c.edit(s,FactInput.QUOTE,h.quote);h.c.affirm(s,true);h.c.refresh(s);h.drain();h.c.save(s)
            assertNull(h.ui().confirmation);assertTrue(h.creates.isEmpty());assertTrue(field in h.ui().rejectedInput)
            h.c.edit(s,field,if(field==FactInput.FROM)"2026-10-24"else"2026-10-25");h.c.save(s);assertNotNull(h.ui().confirmation)
            h.confirm();assertEquals(FactInterval("2026-10-24","2026-10-25","UTC"),h.ui().items.single().current!!.content.interval)
        }
    }
    @Test fun admittedWriteRetiresQueriesButKeepsPendingEditorUntilActualActorSettlement():Unit=runBlocking {Harness().use{h->
        val s=h.open();h.form();h.c.save(s);h.c.confirm(s,h.ui().confirmation!!)
        assertEquals(1,h.retiredQueries);assertTrue(h.ui().busy);assertNotNull(h.ui().pending);assertEquals(s,h.ui().session)
        // Model the route recomposition: the editor is source-owned, not query-owned.
        assertTrue(h.state.get().canRetainFactSources(h.ui()));assertEquals(1,h.q.size);assertTrue(h.creates.isEmpty())
        h.drain();assertEquals(1,h.creates.size);assertNotNull(h.ui().notice)
        var item=h.ui().items.single();h.action(item,FactAction.ACCEPT);item=h.ui().items.single()
        h.c.action(s,item,FactAction.REVOKE);h.drain();h.c.confirm(s,h.ui().confirmation!!)
        assertTrue(h.ui().busy);assertNotNull(h.ui().pending);h.drain();assertEquals(FactReview.REVOKED,h.ui().items.single().review!!.review)
    }}
    @Test fun realCloseOwnerChangeAndLateOldConfirmNeverWriteOrPublishIntoNewOwner():Unit=runBlocking {
        for(changeOwner in listOf(false,true))Harness().use{h->
            val s=h.open();h.form();h.c.save(s);val token=h.ui().confirmation!!;h.c.confirm(s,token)
            if(changeOwner)h.state.updateAndGet{it.copy(sourceFactOwner=object:SourceFactRepository by h.port {})}else h.c.close(s)
            h.drain();assertTrue(h.creates.isEmpty());assertNull(h.state.get().sourceFacts?.notice)
            h.c.retire();h.state.updateAndGet{it.copy(sourceFactOwner=h.port)};val fresh=h.open();h.c.confirm(s,token)
            assertEquals(fresh,h.ui().session);assertTrue(h.creates.isEmpty())
        }
    }
    @Test fun rowGuardNeverReadsEntriesWhileFullPublicationStillAuthenticatesTheirHashes():Unit=runBlocking {Harness().use{h->
        var duringGuard=false;var guarded=0;var entriesReads=0
        val actual=h.state.get().entries
        val counted=object:AbstractList<StoredEntry>(){override val size get()=actual.size
            override fun get(index:Int):StoredEntry {if(duringGuard)entriesReads++;return actual[index]}}
        h.state.updateAndGet{it.copy(entries=counted)}
        h.wrapRead={check->duringGuard=true;try{repeat(895){guarded++;check()}}finally{duringGuard=false}}
        h.open();assertEquals(895,guarded);assertEquals(0,entriesReads);assertNotNull(h.ui().sourcePage)
        // Change the observed source after read admission but before its publication boundary.
        h.wrapRead={h.state.updateAndGet{it.copy(entries=actual.map{e->e.copy(originalText="合成来源已改变")})};Unit}
        h.c.refresh(h.ui().session);h.drain();assertNull(h.state.get().sourceFacts);assertTrue(h.q.isEmpty())
    }}
    @Test fun acknowledgedSourceHandoffCannotResurrectAfterRetireAndNormalHandoffIsOneShot():Unit=runBlocking {Harness().use{h->
        var s=h.open();h.form();h.c.save(s);h.confirm();val row=h.ui().items.single()
        var jump:UnifiedKeywordHandoff?=null;var location:OriginalReadingLocation?=null
        h.c.locate(s,row){l,confirm->location=l;jump=confirm};h.drain();assertNotNull(jump);assertNotNull(h.state.get().sourceFacts)
        h.c.retire();var nav=0;assertFalse(jump!!{nav++});assertEquals(0,nav);assertNull(h.state.get().sourceFacts)
        s=h.open();h.c.locate(s,h.ui().items.single()){l,confirm->location=l;jump=confirm};h.drain()
        assertTrue(jump!!{nav++});assertFalse(jump!!{nav++});assertEquals(1,nav);assertNull(h.state.get().sourceFacts)
        val p=h.store.preview(h.f.source);val ref=SourceMemoryRules.reference(p,h.quote)
        assertEquals(ref.start,location!!.startCodePoint);assertEquals(ref.end,location!!.endCodePoint)
    }}
    @Test fun resolveCommitAckLossDoesNotPublishVerifiedPageOrGrantJump():Unit=runBlocking {Harness().use{h->
        h.failResolveAfterAdmission=true;h.c.open(h.f.source);h.drain()
        assertNull(h.ui().sourcePage);assertNull(h.ui().notice);assertNotNull(h.ui().error)
        assertTrue(h.ui().items.isEmpty());assertTrue(h.creates.isEmpty())
    }}
    @Test fun offscreenReplacementConfirmationIncludesBothSourceSnapshots():Unit=runBlocking {Harness().use{h->
        val p=h.store.preview(h.f.source);val old=h.store.create(FactCreate(id(),p,id(),"合成口味",FactContent(SourceMemoryRules.reference(p,h.quote)),true,id(),10))
        val accepted=h.store.mutate(FactMutation(old,FactAction.ACCEPT,null,null,id(),11))
        val other=SourceVersion("synthetic-other",1);h.addSource(other,"合成：我平时喝淡茶。")
        val preview=h.store.preview(other);val created=h.store.create(FactCreate(id(),preview,accepted.topicId!!,accepted.topicLabel!!,FactContent(SourceMemoryRules.reference(preview,"我平时喝淡茶")),true,id(),20,accepted))
        repeat(19){h.store.create(FactCreate(id(),preview,id(),"合成其他$it",FactContent(SourceMemoryRules.reference(preview,"我平时喝淡茶")),true,id(),21+it.toLong()))}
        h.c.open();h.drain();val ui=h.ui();assertEquals(20,ui.items.size);assertTrue(ui.items.none{it.id==accepted.id})
        h.c.action(ui.session,ui.items.single{it.id==created.id},FactAction.ACCEPT);h.drain()
        val confirmation=h.ui().confirmation!!;assertEquals(accepted.id,(confirmation.intent as FactIntent.Mutate).request.replacing!!.id)
        h.state.updateAndGet{it.copy(entries=it.entries.filterNot{e->e.id==accepted.source.sourceId})}
        h.c.confirm(ui.session,confirmation);assertTrue(h.mutations.isEmpty());assertNull(h.state.get().sourceFacts?.notice)
    }}
    @Test fun delayedReplacementComparisonCannotPublishAnUnobservedOldSourceAfterItWasRemoved():Unit=runBlocking {Harness().use{h->
        val p=h.store.preview(h.f.source);val seed=h.store.create(FactCreate(id(),p,id(),"合成口味",FactContent(SourceMemoryRules.reference(p,h.quote)),true,id(),10))
        val old=h.store.mutate(FactMutation(seed,FactAction.ACCEPT,null,null,id(),11))
        val other=SourceVersion("synthetic-late-comparison",1);h.addSource(other,"我平时喝淡茶")
        val preview=h.store.preview(other);val row=h.store.create(FactCreate(id(),preview,old.topicId!!,old.topicLabel!!,FactContent(SourceMemoryRules.reference(preview,preview.original)),true,id(),20,old))
        h.c.open();h.drain();val s=h.ui().session
        h.afterComparison={h.state.updateAndGet{it.copy(entries=it.entries.filterNot{e->e.id==old.source.sourceId})};Unit}
        h.c.action(s,h.ui().items.single{it.id==row.id},FactAction.ACCEPT);h.drain()
        assertNull(h.state.get().sourceFacts);assertTrue(h.mutations.isEmpty())
    }}
    @Test fun unknownReplacementRetainsBothExpectedSourcesAndRetriesOnlyTheOriginalMutation():Unit=runBlocking {Harness().use{h->
        val p=h.store.preview(h.f.source);val seed=h.store.create(FactCreate(id(),p,id(),"合成口味",FactContent(SourceMemoryRules.reference(p,h.quote)),true,id(),10))
        val old=h.store.mutate(FactMutation(seed,FactAction.ACCEPT,null,null,id(),11))
        val other=SourceVersion("synthetic-unknown-replacement",1);h.addSource(other,"我平时喝淡茶")
        val preview=h.store.preview(other);val row=h.store.create(FactCreate(id(),preview,old.topicId!!,old.topicLabel!!,FactContent(SourceMemoryRules.reference(preview,preview.original)),true,id(),20,old))
        h.c.open();h.drain();val s=h.ui().session;h.c.action(s,h.ui().items.single{it.id==row.id},FactAction.ACCEPT);h.drain()
        h.loseMutationAck=true;h.confirm();val frozen=h.ui().pending!!
        assertTrue(h.ui().items.isEmpty());assertNull(h.ui().page);assertEquals(old.id,(frozen as FactIntent.Mutate).request.replacing!!.id)
        h.c.refresh(s);assertTrue(h.q.isEmpty());assertSame(frozen,h.ui().pending)
        h.c.retry(s);h.drain();assertNull(h.ui().pending);assertEquals(2,h.mutations.size);assertSame(h.mutations[0],h.mutations[1])
        val saved=h.store.facts(other).single();assertEquals(2L,saved.rowVersion);assertEquals(FactReview.ACCEPTED,saved.review!!.review)
        assertEquals(FactReview.SUPERSEDED,h.store.facts(h.f.source).single().review!!.review)
    }}
    private fun id()=java.util.UUID.randomUUID().toString()
    private class Harness:AutoCloseable {
        val f=LocalCandidateFixture();val text=f.original;val quote="我不喝咖啡🙂"
        val store=SqlSourceFactRepository(f.gateway,LocalCandidateFixture.KEY)
        val q=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
        val creates=mutableListOf<FactCreate>();val mutations=mutableListOf<FactMutation>()
        var loseAck=false;var loseMutationAck=false;var failRead=false;var failResolveAfterAdmission=false;var retiredQueries=0
        var wrapRead:((()->Unit)->Unit)?=null
        var afterComparison:(()->Unit)?=null
        val port=object:SourceFactRepository by store {
            override fun captureSource(source:SourceVersion,checkCurrent:()->Unit):FactSourcePage {check(!failRead);wrapRead?.invoke(checkCurrent);return store.captureSource(source,checkCurrent)}
            override fun create(request:FactCreate,checkCurrent:()->Unit):SourceFactSnapshot {creates+=request;return store.create(request,checkCurrent).also{if(loseAck){loseAck=false;throw FactUnavailable()}}}
            override fun mutate(request:FactMutation,checkCurrent:()->Unit):SourceFactSnapshot {mutations+=request;return store.mutate(request,checkCurrent).also{if(loseMutationAck){loseMutationAck=false;throw FactUnavailable()}}}
            override fun acceptedForTopic(expected:SourceFactSnapshot,checkCurrent:()->Unit):SourceFactSnapshot? = store.acceptedForTopic(expected,checkCurrent).also{afterComparison?.invoke()}
            override fun resolveSource(page:FactSourcePage,checkCurrent:()->Unit,publish:()->Unit):Boolean = store.resolveSource(page,checkCurrent,publish).also{if(failResolveAfterAdmission)throw FactUnavailable()}
        }
        val base=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)){}
        val owner:EntryRepository=object:EntryRepository by base {override val sourceFacts=port}
        val state=AtomicReference(LocalRecordUiState(ready=true,opening=false,entries=listOf(StoredEntry(f.source.sourceId,1,text,5,"Asia/Shanghai",ProcessingPolicy.LOCAL_ONLY)),supportsSourceFacts=true,sourceFactOwner=port))
        val c=SourceFactController({state.get()},{change->state.updateAndGet(change)},{q.add(it);true},Clock.fixed(Instant.ofEpochMilli(50),ZoneOffset.UTC),beforeWrite={retiredQueries++})
        fun ui()=checkNotNull(state.get().sourceFacts)
        suspend fun drain(){while(true)(q.poll()?:break)(owner)}
        suspend fun open():Long {c.open(f.source);drain();check(ui().sourcePage!=null&&!ui().busy);return ui().session}
        fun form(){val s=ui().session;c.edit(s,FactInput.QUOTE,quote);c.edit(s,FactInput.TOPIC,"合成口味");c.affirm(s,true)}
        suspend fun confirm(){val u=ui();c.confirm(u.session,u.confirmation!!);drain()}
        suspend fun action(item:SourceFactSnapshot,a:FactAction){c.action(ui().session,item,a);drain();confirm()}
        fun addSource(source:SourceVersion,text:String){f.db.transaction{sql->
            sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,5,'UTC','LOCAL_ONLY')",listOf(source.sourceId))
            sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,5)",listOf(source.sourceId,text,OriginalExports.hash(text)))}
            state.updateAndGet{it.copy(entries=it.entries+StoredEntry(source.sourceId,1,text,5,"UTC",ProcessingPolicy.LOCAL_ONLY))}
        }
        override fun close(){c.dispose();f.gateway.stopAndAwaitIdle();store.wipeKey();f.close()}
    }
}
