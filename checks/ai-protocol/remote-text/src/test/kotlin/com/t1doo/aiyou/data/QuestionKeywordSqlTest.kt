package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Real controllers, navigation gate, SQL/AEAD owner and synthetic originals, never a device claim. */
class QuestionKeywordSqlTest {
    @Test fun draftsDateReviewAndCancelDoNotQueryAndOneConfirmationPreservesAffirmationNegationAndQuotes():Unit=runBlocking{H().use{h->
        h.open();h.editQuestion("我种薄荷了吗？");h.editTerms("薄荷")
        h.sync();assertEquals(0,h.sql.get());assertEquals(OriginalSearchMode.EXACT,h.input.get().mode)
        val undecided=h.ui();h.helper.confirm(undecided);assertEquals(0,h.commits.get());assertEquals(0,h.sql.get())
        h.helper.terms(h.ui(),"薄荷");h.helper.dateChoice(h.ui(),QuestionDateChoice.UNLIMITED)
        val accepted=h.ui();h.helper.confirm(accepted);h.helper.confirm(accepted)
        assertNull(h.helper.state.value);assertEquals(1,h.commits.get());assertEquals("薄荷",h.input.get().query)
        assertEquals(OriginalSearchMode.KEYWORDS,h.input.get().mode);assertEquals("",h.input.get().from)
        assertEquals(0,h.sql.get()) // confirmation changes inputs; the actual search starts only at sync.
        h.sync();val result=h.ready();assertEquals(3,result.hits.size)
        assertTrue(result.hits.any{it.entry.originalText.contains("没有")});assertTrue(result.hits.any{it.entry.originalText.contains("小舟说")})
        assertTrue(result.hits.any{it.entry.originalText=="我种了薄荷。"})
        h.helper.open();h.editQuestion("第二个问题");val old=h.ui();h.helper.cancel(old)
        assertNull(h.helper.state.value);assertEquals(OriginalSearchStage.WAITING,h.search.visible(h.source.get(),h.input.get()).stage)
        assertTrue(h.search.visible(h.source.get(),h.input.get()).hits.isEmpty());h.helper.confirm(old);assertEquals(1,h.commits.get())
    }}
    @Test fun invalidRangeMissingDateChoiceUnsupportedEventAndOversizedPasteNeverApplyCanonicalInput():Unit=runBlocking{H().use{h->
        h.open();h.editQuestion("10月的薄荷？");h.editTerms("薄荷");val before=h.input.get()
        h.helper.confirm(h.ui());assertSame(before,h.input.get())
        h.helper.terms(h.ui(),"薄荷");h.helper.from(h.ui(),"2026-02-30");h.helper.confirm(h.ui());assertSame(before,h.input.get())
        h.helper.from(h.ui(),"2026-10-01");h.helper.through(h.ui(),"2026-10-31")
        h.helper.basis(h.ui(),RecordDateBasis.EVENT);h.helper.dateChoice(h.ui(),QuestionDateChoice.UNLIMITED)
        h.helper.confirm(h.ui());assertSame(before,h.input.get())
        h.helper.question(h.ui(),"x".repeat(2_000_000));h.helper.confirm(h.ui());assertSame(before,h.input.get())
        h.helper.basis(h.ui(),RecordDateBasis.CAPTURED);h.helper.dateChoice(h.ui(),QuestionDateChoice.UNLIMITED)
        h.helper.confirm(h.ui());assertSame(before,h.input.get()) // another field cannot clear a rejected question
        h.editQuestion("10月的薄荷？");h.editTerms("薄荷");h.helper.basis(h.ui(),RecordDateBasis.CAPTURED)
        h.helper.terms(h.ui(),"没\n种");h.helper.dateChoice(h.ui(),QuestionDateChoice.UNLIMITED)
        h.helper.confirm(h.ui());assertSame(before,h.input.get());h.editTerms("薄荷")
        h.helper.from(h.ui(),"2026-10-01");h.helper.through(h.ui(),"2026-10-31")
        h.helper.confirm(h.ui());assertEquals(1,h.commits.get());assertEquals("2026-10-01",h.input.get().from)
        assertEquals("2026-10-31",h.input.get().through);assertEquals(RecordDateBasis.CAPTURED,h.input.get().dateBasis)
        assertEquals(0,h.sql.get());h.sync();assertEquals(3,h.ready().hits.size)
    }}
    @Test fun rejectedStartDateCannotBeClearedByTermsOrChoiceAndNeedsExplicitCorrection():Unit=runBlocking{
        rejectedDateNeedsCorrection(start=true)
    }
    @Test fun rejectedEndDateCannotBeClearedByTermsOrChoiceAndNeedsExplicitCorrection():Unit=runBlocking{
        rejectedDateNeedsCorrection(start=false)
    }
    @Test fun bothRejectedDatesRequireBothCorrectionsOrAnExplicitWholeRangeReplacement(){
        for(choice in listOf(QuestionDateChoice.RANGE,QuestionDateChoice.CURRENT,QuestionDateChoice.UNLIMITED))H().use{h->
            h.input.set(h.input.get().copy(from="2026-10-01",through="2026-10-31",ownership=Any()))
            h.open();h.editQuestion("我种薄荷了吗？");h.editTerms("薄荷")
            h.helper.from(h.ui(),"2026-10-02");h.helper.through(h.ui(),"2026-10-30")
            h.helper.from(h.ui(),"2026-10-020");h.helper.through(h.ui(),"2026-10-300")
            h.helper.basis(h.ui(),RecordDateBasis.CAPTURED);h.helper.dateChoice(h.ui(),QuestionDateChoice.RANGE)
            h.editTerms("薄荷 我");h.helper.confirm(h.ui());h.sync()
            assertEquals(0,h.commits.get());assertEquals(0,h.sql.get())
            if(choice==QuestionDateChoice.RANGE){
                h.helper.from(h.ui(),"2026-10-03");h.helper.confirm(h.ui());h.sync()
                assertEquals(0,h.commits.get());assertEquals(0,h.sql.get())
                h.helper.through(h.ui(),"2026-10-29")
            }else h.helper.dateChoice(h.ui(),choice)
            h.helper.confirm(h.ui());assertEquals(1,h.commits.get());assertEquals(0,h.sql.get())
            val expected=when(choice){QuestionDateChoice.CURRENT->"2026-10-01" to "2026-10-31"
                QuestionDateChoice.UNLIMITED->"" to "";else->"2026-10-03" to "2026-10-29"}
            assertEquals(expected.first,h.input.get().from);assertEquals(expected.second,h.input.get().through)
        }
    }
    private suspend fun rejectedDateNeedsCorrection(start:Boolean){
        for(choose in listOf(false,true))H().use{h->
            h.open();h.editQuestion("我种薄荷了吗？");h.editTerms("薄荷")
            h.helper.from(h.ui(),"2026-10-01");h.helper.through(h.ui(),"2026-10-31")
            val original=h.input.get()
            if(start)h.helper.from(h.ui(),"2026-10-010")else h.helper.through(h.ui(),"2026-10-310")
            assertNotNull(h.ui().error)
            if(choose)h.helper.choose(h.ui(),h.ui().proposals.values.single{it.text=="我"})else h.editTerms("薄荷 我")
            h.helper.confirm(h.ui());h.sync()
            assertEquals("Rejected date must not commit an older range",0,h.commits.get())
            assertEquals(0,h.sql.get());assertSame(original,h.input.get());assertNotNull(h.helper.state.value)
            // Correcting the other endpoint cannot acknowledge this rejected endpoint.
            if(start)h.helper.through(h.ui(),"2026-10-30")else h.helper.from(h.ui(),"2026-10-02")
            h.helper.confirm(h.ui());h.sync();assertEquals(0,h.commits.get());assertEquals(0,h.sql.get())
            if(start)h.helper.from(h.ui(),"2026-10-02")else h.helper.through(h.ui(),"2026-10-30")
            h.helper.confirm(h.ui());assertEquals(1,h.commits.get());assertEquals(0,h.sql.get())
            assertEquals("2026-10-02",h.input.get().from);assertEquals("2026-10-30",h.input.get().through)
            h.sync();assertEquals(3,h.ready().hits.size)
        }
    }
    @Test fun questionRevisionForeignFragmentsOwnerAndCanonicalConditionsCannotReviveAConfirmation(){H().use{h->
        h.open();h.editQuestion("薄荷记录？");val previous=h.ui();val fragment=previous.proposals.values.first()
        h.editQuestion("新的问题？");val fresh=h.ui();h.helper.choose(previous,fragment);assertSame(fresh,h.ui())
        h.helper.choose(fresh,QuestionKeywordFragments.Fragment(fragment.start,fragment.end,fragment.text));assertSame(fresh,h.ui())
        h.editTerms("薄荷");h.helper.dateChoice(h.ui(),QuestionDateChoice.UNLIMITED);val waiting=h.ui()
        h.gate.publish(h.gate.generation){h.search.changeInput{h.input.set(h.input.get().copy(tag="人工条件",ownership=Any()))}}
        h.helper.confirm(waiting);assertNull(h.helper.state.value);assertEquals(0,h.commits.get())
        h.open();h.editQuestion("薄荷？");h.editTerms("薄荷");h.helper.dateChoice(h.ui(),QuestionDateChoice.UNLIMITED)
        val ownerOld=h.ui();h.source.set(h.source.get().copy(localSearchEpoch=h.source.get().localSearchEpoch!!+1))
        h.helper.confirm(ownerOld);assertNull(h.helper.state.value);assertEquals(0,h.commits.get());assertEquals(0,h.sql.get())
        h.open();h.editQuestion("薄荷？");h.editTerms("薄荷");h.helper.dateChoice(h.ui(),QuestionDateChoice.UNLIMITED)
        h.rejectCommit=true;val beforeFailure=h.ui();h.helper.confirm(beforeFailure)
        assertNotNull(h.helper.state.value);assertTrue(h.ui().error!!.contains("未能一次应用"))
        h.helper.confirm(beforeFailure);h.helper.confirm(h.ui());h.sync();assertEquals(0,h.commits.get());assertEquals(0,h.sql.get())
    }}
    @Test fun pauseNavigationAndUiNullRetainedActionsNeverOverwriteANewHelperSession(){H().use{h->
        h.open();H().use{other->other.open();val own=h.ui();val foreign=other.ui()
            assertEquals(own.session,foreign.session);h.helper.cancel(foreign);assertSame(own,h.helper.state.value)
            h.helper.confirm(foreign);assertSame(own,h.helper.state.value)
        }
        h.editQuestion("薄荷？");h.editTerms("薄荷");h.helper.dateChoice(h.ui(),QuestionDateChoice.UNLIMITED);val old=h.ui()
        h.helper.foreground(false);h.helper.confirm(old);assertNull(h.helper.state.value);assertEquals(0,h.commits.get())
        h.helper.foreground(true);h.open();val new=h.ui();h.helper.question(old,"旧问题");h.helper.cancel(old);h.helper.confirm(old)
        assertSame(new,h.ui());h.gate.advance();assertNull(h.helper.state.value);h.helper.confirm(new);assertEquals(0,h.commits.get())
        h.open();assertTrue(h.ui().session>new.session);h.helper.dispose();h.helper.confirm(old);h.helper.open();assertNull(h.helper.state.value)
    }}
    @Test(timeout=10000) fun confirmationNavigationAndInputUseOneOrderAtDeterministicThreadBarriers(){
        for(inputFirst in listOf(false,true))H().use{h->
            h.open();h.editQuestion("薄荷？");h.editTerms("薄荷");h.helper.dateChoice(h.ui(),QuestionDateChoice.UNLIMITED);val old=h.ui()
            val held=CountDownLatch(1);val contender=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
            try{
                if(inputFirst){
                    val a=pool.submit{h.gate.arbitrate{h.search.changeInput{h.input.set(h.input.get().copy(query="新输入",ownership=Any()))};held.countDown();check(contender.await(2,TimeUnit.SECONDS))}}
                    val b=pool.submit{check(held.await(2,TimeUnit.SECONDS));contender.countDown();h.helper.confirm(old)}
                    a.get(3,TimeUnit.SECONDS);b.get(3,TimeUnit.SECONDS);assertEquals(0,h.commits.get());assertEquals("新输入",h.input.get().query)
                }else{
                    h.bindingBarrier={held.countDown();check(contender.await(2,TimeUnit.SECONDS));h.bindingBarrier=null}
                    val a=pool.submit{h.helper.confirm(old)}
                    val b=pool.submit{check(held.await(2,TimeUnit.SECONDS));contender.countDown();h.gate.advance()}
                    a.get(3,TimeUnit.SECONDS);b.get(3,TimeUnit.SECONDS);assertEquals(1,h.commits.get());assertEquals("薄荷",h.input.get().query)
                }
                assertNull(h.helper.state.value);h.helper.confirm(old);assertEquals(if(inputFirst)0 else 1,h.commits.get())
            }finally{pool.shutdownNow();check(pool.awaitTermination(2,TimeUnit.SECONDS))}
        }
    }
    @Test(timeout=10000) fun actualLocationPublicationWaitsForNavigationBeforeTakingSearchLock():Unit=runBlocking{H().use{h->
        h.input.set(h.input.get().copy(query="薄荷",mode=OriginalSearchMode.KEYWORDS,ownership=Any()));h.sync();val value=h.ready()
        val navHeld=CountDownLatch(1);val navAttempt=CountDownLatch(1);val finished=CountDownLatch(1);val opened=AtomicInteger()
        h.beforeNavigation={navAttempt.countDown()};h.invalidated={finished.countDown()}
        val pool=Executors.newSingleThreadExecutor()
        try{
            val input=pool.submit{h.gate.arbitrate{
                navHeld.countDown();check(navAttempt.await(2,TimeUnit.SECONDS))
                h.search.changeInput{h.input.set(h.input.get().copy(query="后来的输入",ownership=Any()))};h.gate.advance()
            }}
            check(navHeld.await(2,TimeUnit.SECONDS));h.search.open(value,value.hits.first()){opened.incrementAndGet()}
            input.get(3,TimeUnit.SECONDS);check(finished.await(3,TimeUnit.SECONDS));assertEquals(0,opened.get())
            assertEquals("后来的输入",h.input.get().query)
        }finally{pool.shutdownNow();check(pool.awaitTermination(2,TimeUnit.SECONDS))}
    }}
    @Test fun realSourceDeletionAfterConfirmedLookupRejectsOldExactOriginalPublication():Unit=runBlocking{H().use{h->
        h.open();h.editQuestion("薄荷？");h.editTerms("薄荷");h.helper.dateChoice(h.ui(),QuestionDateChoice.UNLIMITED);h.helper.confirm(h.ui())
        h.sync();val value=h.ready();val hit=value.hits.first();val done=CountDownLatch(1);h.invalidated={done.countDown()}
        h.h.f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",hit.entry.id)
        h.search.open(value,hit){fail("Deleted original cannot navigate")};check(done.await(3,TimeUnit.SECONDS))
        assertTrue(h.search.visible(h.source.get(),h.input.get()).hits.isEmpty())
    }}

    private class H:AutoCloseable{
        val h=UnifiedKeywordFixture();val sql=AtomicInteger();val commits=AtomicInteger()
        @Volatile var bindingBarrier:(()->Unit)?=null
        @Volatile var beforeNavigation:(()->Unit)?=null
        @Volatile var invalidated:()->Unit={}
        var rejectCommit=false
        private var navigationChanged:(()->Unit)?=null
        val gate=SearchNavigationGate{navigationChanged?.invoke()}
        val source:AtomicReference<LocalRecordUiState>
        val input=AtomicReference(OriginalSearchInput(mode=OriginalSearchMode.EXACT,ownership=Any()))
        val search:LocalOriginalSearchController
        val helper:QuestionKeywordController
        init{
            for(text in listOf("我种了薄荷。","我没有种薄荷。","小舟说：“种薄荷。”这不是我的经历。")){
                val id=FullBackupTest.uuid();val time=java.time.Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
                h.f.db.transaction{it.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,?,'UTC','LOCAL_ONLY')",listOf(id,time));it.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,?)",listOf(id,text,OriginalExports.hash(text),time));it.execute("INSERT INTO remote_source_access VALUES(?,1,0,NULL,NULL)",listOf(id))}
            }
            while(h.originals.batch().remaining){}
            val entries=h.originals.candidates(LocalSearchRequest("")).entries
            source=AtomicReference(LocalRecordUiState(ready=true,opening=false,entries=entries,localSearch=h.originals,localSearchEpoch=h.originals.epoch()))
            search=LocalOriginalSearchController(source::get,input::get,onInvalidated={invalidated()},arbitrateNavigation={action->beforeNavigation?.invoke();gate.arbitrate(action)},matcher={e,i->LocalOriginalSearchMatcher.indexed(e,i,h.originals)})
            helper=QuestionKeywordController(gate,currentBinding={bindingBarrier?.invoke();QuestionSearchBinding(gate.generation,source.get(),input.get())},retireSearch=search::invalidateInput,commit={request->
                sync();assertEquals(0,sql.get()) // modal is still blocking before canonical commit
                if(rejectCommit)false else search.tryChangeInput{commits.incrementAndGet();input.set(input.get().copy(query=request.query,from=request.from,through=request.through,dateBasis=request.basis,mode=OriginalSearchMode.KEYWORDS,ownership=Any()))}
            })
            navigationChanged=helper::retire;helper.foreground(true);h.f.db.beforeSql={sql.incrementAndGet()}
        }
        fun open()=helper.open()
        fun ui()=checkNotNull(helper.state.value)
        fun editQuestion(value:String)=helper.question(ui(),value)
        fun editTerms(value:String)=helper.terms(ui(),value)
        fun sync()=search.sync(helper.state.value==null)
        suspend fun ready()=withTimeout(5000){search.state.first{it.stage==OriginalSearchStage.READY}}
        override fun close(){helper.dispose();search.close();h.f.db.beforeSql=null;h.close()}
    }
}
