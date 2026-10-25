package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class FactRecapControllerTest {
    @Test fun rejectedOversizedRangeHidesPriorRecapAndCannotReuseItsSourceOrGenerateOldRange():Unit=runBlocking {
        for (badDate in listOf(true, false)) FactRecapHarness().use { h ->
            h.c.open(); h.c.generate(); h.drain()
            val old = checkNotNull(h.ui.snapshot).cards.single()
            val date = h.ui.date
            val zone = h.ui.zone
            var opens = 0
            h.c.source(old) { opens++ }
            h.c.input(if (badDate) date + "0" else date, if (badDate) zone else "x".repeat(65), h.ui.period)
            assertNull("Rejected range input must hide the previous private recap", h.ui.snapshot)
            assertNotNull("Rejected input needs an explicit error", h.ui.error)
            h.drain(); assertEquals(0, opens)
            h.c.generate()
            assertFalse(h.ui.loading); assertNull(h.ui.snapshot); assertTrue("Must not query the old accepted range", h.queue.isEmpty())
        }
    }

    @Test fun rejectedOversizedRangeDuringCaptureCannotPublishTheOldRange():Unit=runBlocking {
        for (badDate in listOf(true, false)) FactRecapHarness().use { h ->
            h.c.open()
            h.afterCapture = { h.c.input(if (badDate) h.ui.date + "0" else h.ui.date,
                if (badDate) h.ui.zone else "x".repeat(65), h.ui.period) }
            h.c.generate(); h.drain()
            assertNull("A rejected edit must retire an already captured old result", h.ui.snapshot)
            assertFalse(h.ui.loading); assertNotNull(h.ui.error)
            h.c.generate(); assertTrue(h.queue.isEmpty())
        }
    }

    @Test fun onlyExplicitCorrectionOfEachRejectedFieldReenablesTheRequestedRange():Unit=runBlocking {
        for (badDate in listOf(true, false)) FactRecapHarness().use { h ->
            h.c.open()
            val date = h.ui.date
            if (badDate) h.c.input(date = date + "0") else h.c.input(zone = "x".repeat(65))
            assertEquals(badDate, h.ui.dateRejected); assertEquals(!badDate, h.ui.zoneRejected)
            h.c.input(period = FactRecapPeriod.WEEK)
            if (badDate) h.c.input(zone = "Asia/Shanghai") else h.c.input(date = "2026-10-16")
            h.c.cancel(); h.c.generate()
            assertThrows(Exception::class.java) { h.ui.requestedRange() }
            assertTrue(h.queue.isEmpty()); assertFalse(h.ui.loading); assertNull(h.ui.snapshot)
            assertEquals(badDate, h.ui.dateRejected); assertEquals(!badDate, h.ui.zoneRejected)
            assertTrue(h.ui.date.length <= 10); assertTrue(h.ui.zone.length <= 64)
            if (badDate) h.c.input(date = date) else h.c.input(zone = "UTC")
            val expected = h.ui.requestedRange()
            h.c.generate(); h.drain(); assertEquals(expected, h.ui.snapshot!!.range)
            assertEquals(FactRecapPeriod.WEEK, expected.period)
        }
        FactRecapHarness().use { h ->
            h.c.open(); val date = h.ui.date
            h.c.input(date = date + "0", zone = "x".repeat(65))
            h.c.input(date = date)
            assertFalse(h.ui.dateRejected); assertTrue(h.ui.zoneRejected)
            h.c.generate(); assertTrue(h.queue.isEmpty())
            h.c.input(zone = "UTC"); h.c.generate(); h.drain(); assertNotNull(h.ui.snapshot)
        }
    }

    @Test fun defaultWeekIsCopiedOnOpenAndManualDayDoesNotChangeTheNextDefault():Unit=runBlocking {
        FactRecapHarness().use { h->
            val preferred=FactRecapPeriod.WEEK
            h.c.open(preferred)
            assertEquals(FactRecapPeriod.WEEK,h.ui.period);assertNull(h.ui.snapshot);assertTrue(h.queue.isEmpty())
            assertEquals(LocalDate.parse("2026-10-12"),h.ui.requestedRange().firstDay)
            assertEquals(LocalDate.parse("2026-10-19"),h.ui.requestedRange().endDay)
            h.c.generate();h.drain();assertEquals(FactRecapPeriod.WEEK,h.ui.snapshot!!.range.period)
            h.c.input(h.ui.date,h.ui.zone,FactRecapPeriod.DAY)
            assertNull(h.ui.snapshot);h.c.generate();h.drain()
            assertEquals(FactRecapPeriod.DAY,h.ui.snapshot!!.range.period)
            h.c.close();h.c.open(preferred)
            assertEquals(FactRecapPeriod.WEEK,h.ui.period);assertNull(h.ui.snapshot);assertTrue(h.queue.isEmpty())
        }
    }
    @Test fun newDefaultOpenAndCancelRejectOldRangeResultAndRetainedSource():Unit=runBlocking {
        FactRecapHarness().use { h->
            h.c.open(FactRecapPeriod.WEEK)
            h.afterCapture={h.c.close();h.c.open(FactRecapPeriod.DAY)}
            h.c.generate();h.drain()
            assertEquals(FactRecapPeriod.DAY,h.ui.period);assertNull(h.ui.snapshot);assertFalse(h.ui.loading)
            h.afterCapture={};h.c.generate();h.drain()
            val old=h.ui.snapshot!!.cards.single();var opens=0
            h.c.source(old){opens++};h.c.close();h.c.open(FactRecapPeriod.WEEK);h.drain()
            assertEquals(0,opens);assertNull(h.ui.snapshot)
            h.c.generate();h.c.cancel();h.drain()
            assertEquals(FactRecapPeriod.WEEK,h.ui.period);assertNull(h.ui.snapshot);assertFalse(h.ui.loading)
        }
    }
    @Test fun previewAndGenerationShareActualDstBoundariesAndRejectInvalidRange():Unit=runBlocking {
        FactRecapHarness().use { h->
            h.c.open(FactRecapPeriod.WEEK)
            for(period in FactRecapPeriod.entries) {
                h.c.input("2026-03-08","America/New_York",period)
                val preview=h.ui.requestedRange()
                assertEquals(if(period==FactRecapPeriod.DAY)23L else 167L,(preview.endMillis-preview.startMillis)/3600000)
                h.c.generate();h.drain();assertEquals(preview,h.ui.snapshot!!.range)
            }
            for((date,zone) in listOf("2026-02-30" to "UTC","2026-03-08" to "Made/Up","2101-01-01" to "UTC")) {
                h.c.input(date,zone,FactRecapPeriod.DAY)
                assertThrows(Exception::class.java){h.ui.requestedRange()}
                h.c.generate();assertNotNull(h.ui.error);assertNull(h.ui.snapshot);assertTrue(h.queue.isEmpty())
            }
        }
    }
    @Test fun changedGenerationBetweenOwnershipCheckAndStateTransformCannotWriteOldSnapshot():Unit=runBlocking {
        for(cancel in listOf(false,true)) FactRecapHarness().use {h->
            h.c.open();h.c.generate()
            // Deterministic interleaving at the actual publisher boundary after the initial ownership check.
            h.beforeStateCommit={h.beforeStateCommit={}
                if(cancel)h.c.cancel() else h.c.input("2026-10-16","UTC",FactRecapPeriod.WEEK)}
            h.drain();assertNull(h.ui.snapshot);assertFalse(h.ui.loading)
            if(!cancel)assertEquals("2026-10-16",h.ui.date)
        }
    }
    @Test(timeout=15000) fun finalPublicationAndInputOrCancelHaveOneAtomicOwnershipOrder():Unit=runBlocking {
        for(cancel in listOf(false,true)) FactRecapHarness().use {h->
            h.c.open();h.c.generate()
            val reached=CountDownLatch(1);val release=CountDownLatch(1)
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            h.beforePublication={reached.countDown();check(release.await(5,TimeUnit.SECONDS))}
            val worker=Thread {try{runBlocking{h.drain()}}catch(t:Throwable){failure.set(t)}}
            worker.start()
            try {
                assertTrue(reached.await(5,TimeUnit.SECONDS))
                if(cancel)h.c.cancel() else h.c.input("2026-10-16","UTC",FactRecapPeriod.WEEK)
            } finally {release.countDown();worker.join(5000)}
            assertFalse(worker.isAlive);assertNull(failure.get());assertNull(h.ui.snapshot);assertFalse(h.ui.loading)
            if(!cancel)assertEquals("2026-10-16",h.ui.date)
        }
    }
    @Test(timeout=15000) fun sourceCallbackCannotNavigateAfterCloseAndNewSessionAtItsFinalGate():Unit=runBlocking {
        FactRecapHarness().use {h->
            h.c.open();h.c.generate();h.drain();val card=h.ui.snapshot!!.cards.single()
            val reached=CountDownLatch(1);val release=CountDownLatch(1)
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>();val opens=java.util.concurrent.atomic.AtomicInteger()
            h.beforeResolve={reached.countDown();check(release.await(5,TimeUnit.SECONDS))}
            h.c.source(card){opens.incrementAndGet()}
            val worker=Thread {try{runBlocking{h.drain()}}catch(t:Throwable){failure.set(t)}};worker.start()
            try {assertTrue(reached.await(5,TimeUnit.SECONDS));h.c.close();h.c.open()}
            finally {release.countDown();worker.join(5000)}
            assertFalse(worker.isAlive);assertNull(failure.get());assertEquals(0,opens.get());assertNull(h.ui.snapshot)
        }
    }
    @Test fun finalStateTransformRechecksIndependentReadinessFailureInsideCas():Unit=runBlocking {
        FactRecapHarness().use {h->
            h.c.open();h.c.generate()
            h.beforeStateCommit={h.beforeStateCommit={};h.local=h.local.copy(error="合成独立读取失败")}
            h.drain();assertNull(h.ui.snapshot);assertEquals("合成独立读取失败",h.local.error)
        }
    }
    @Test fun explicitGenerateReadsRealSqlAndOpensOnlyExactCurrentSource():Unit=runBlocking {
        FactRecapHarness().use { h->
            h.c.open();assertNull(h.ui.snapshot);h.c.generate();assertTrue(h.ui.loading);h.drain()
            assertEquals(1,h.ui.snapshot!!.recordCount);val card=h.ui.snapshot!!.cards.single();var opens=0
            h.c.source(card){opens++};assertEquals(0,opens);h.drain();assertEquals(1,opens)
            h.f.db.write("UPDATE remote_source_access SET generation=generation+1 WHERE entryId=?",h.id)
            h.c.source(card){opens++};h.drain();assertEquals(1,opens);assertNull(h.ui.snapshot);assertNotNull(h.ui.error)
        }
    }
    @Test fun cancelCloseAndInputChangesRetireQueuedAndLateGeneratedPrivateResults():Unit=runBlocking {
        FactRecapHarness().use { h->
            h.c.open();h.c.generate();h.c.cancel();h.drain();assertNull(h.ui.snapshot);assertFalse(h.ui.loading)
            h.afterCapture={h.c.input("2026-10-16","UTC",FactRecapPeriod.WEEK)};h.c.generate();h.drain()
            assertNull(h.ui.snapshot);assertEquals("2026-10-16",h.ui.date)
            h.afterCapture={h.c.close()};h.c.generate();h.drain();assertNull(h.local.factRecap)
            h.c.open();h.afterCapture={};h.c.generate();h.drain();assertNotNull(h.ui.snapshot)
            val card=h.ui.snapshot!!.cards.single();var calls=0;h.c.source(card){calls++};h.c.close();h.c.open();h.drain();assertEquals(0,calls)
        }
    }
    @Test fun failuresRetryCancellationAndRejectedQueueNeverPublishFalseEmpty():Unit=runBlocking {
        FactRecapHarness().use { h->
            h.c.open();h.failCapture=true;h.c.generate();h.drain();assertNotNull(h.ui.error);assertNull(h.ui.snapshot)
            h.failCapture=false;h.c.generate();h.drain();assertEquals(1,h.ui.snapshot!!.recordCount)
            h.c.cancel();h.accept=false;h.c.generate();assertFalse(h.ui.loading);assertNull(h.ui.snapshot);assertNotNull(h.ui.error)
            h.accept=true;h.c.generate();h.drain();assertEquals(1,h.ui.snapshot!!.recordCount)
            h.c.input("2026-02-30","Made/Up",FactRecapPeriod.DAY);h.c.generate();assertNotNull(h.ui.error);assertTrue(h.queue.isEmpty())
        }
    }
    @Test fun newRecordsTagsErrorsAndUnavailableStateSynchronouslyHideOldExcerpts():Unit=runBlocking {
        FactRecapHarness().use { h->
            h.c.open();h.c.generate();h.drain();assertNotNull(h.ui.snapshot)
            h.local=reconcileFactRecap(h.local,h.local.copy(error="合成读取失败"));assertNull(h.ui.snapshot)
            h.local=h.local.copy(error=null);h.c.generate();h.drain()
            h.f.tags.replace(ManualTagMutation(h.id,0,listOf("合成新标签"),java.util.UUID.randomUUID().toString()))
            h.local=reconcileFactRecap(h.local,h.local.copy(manualTags=h.f.tags.snapshots()));assertNull(h.ui.snapshot)
            h.c.generate();h.drain();h.f.entry().also {h.f.db.write("UPDATE entries SET capturedAtMillis=? WHERE id=?",h.clock.millis(),it)}
            h.local=reconcileFactRecap(h.local,h.local.copy(entries=h.store.entries()));assertNull(h.ui.snapshot)
        }
    }
    @Test fun returnValidationRejectsImageLedgerChangesAndDisposeErasesOnlyPrivateView():Unit=runBlocking {
        FactRecapHarness().use { h->
            h.c.open();h.c.generate();h.drain()
            h.f.db.write("INSERT INTO attachments VALUES(?,?,1,'image/png',?,8,'FAILED')",java.util.UUID.randomUUID().toString(),h.id,"0".repeat(64))
            h.c.validate();h.drain();assertNull(h.ui.snapshot);assertNotNull(h.ui.error)
            h.c.generate();h.drain();assertEquals(1,h.ui.snapshot!!.imageCount)
            h.c.dispose();assertNull(h.local.factRecap);assertEquals(1,h.store.entries().size)
            assertFalse(FactRecapUiState(date="PRIVATE_DATE",zone="PRIVATE_ZONE").toString().contains("PRIVATE"))
        }
    }
    @Test fun unchangedReferencesWithUnavailableLabelsAndSameBodyPolicyChangesRetireExcerpts():Unit=runBlocking {
        FactRecapHarness().use { h->
            h.c.open();h.c.generate();h.drain();assertNotNull(h.ui.snapshot)
            h.local=reconcileFactRecap(h.local,h.local.copy(manualTagsAvailable=false));assertNull(h.ui.snapshot)
            h.local=h.local.copy(manualTagsAvailable=true);h.c.generate();h.drain()
            h.local=reconcileFactRecap(h.local,h.local.copy(entries=h.local.entries.map {it.copy(policy=ProcessingPolicy.AI_NONE)}))
            assertNull(h.ui.snapshot)
        }
    }
    @Test fun attachmentReadRetainsOriginalViewWhileLedgerMutationImmediatelyRetiresIt():Unit=runBlocking {
        FactRecapHarness().use {h->
            h.c.open();h.c.generate();h.drain();val s=h.ui.snapshot
            h.c.checkCurrent(h.store);assertSame(s,h.ui.snapshot)
            h.f.db.write("INSERT INTO attachments VALUES(?,?,1,'image/png',?,8,'FAILED')",java.util.UUID.randomUUID().toString(),h.id,"0".repeat(64))
            h.c.checkCurrent(h.store);assertNull(h.ui.snapshot)
        }
    }
    @Test(timeout=15000) fun actualVmActorPreservesTagReadAndCancelThenInvalidatesRealMutation():Unit=runBlocking {
        FactRecapHarness().use { h->
            val closed=CountDownLatch(1)
            val owner=object:EntryRepository by h.store,AutoCloseable {override fun close(){closed.countDown()}}
            val vm=LocalRecordsViewModel(android.app.Application(),{owner},clock=h.clock)
            try {
                withTimeout(5000){vm.state.first{it.ready && it.supportsFactRecaps}}
                vm.factRecaps.open();vm.factRecaps.generate();withTimeout(5000){vm.state.first{it.factRecap?.snapshot!=null}}
                val original=vm.state.value.factRecap!!.snapshot
                vm.tags.open(h.id,1);withTimeout(5000){vm.state.first{it.manualTagEditor?.snapshot!=null}}
                assertSame(original,vm.state.value.factRecap!!.snapshot)
                vm.tags.close(vm.state.value.manualTagEditor!!.session);assertSame(original,vm.state.value.factRecap!!.snapshot)
                vm.tags.open(h.id,1);withTimeout(5000){vm.state.first{it.manualTagEditor?.snapshot!=null}}
                val editor=vm.state.value.manualTagEditor!!;vm.tags.add(editor.session,editor.snapshot!!.version,"合成新标签")
                withTimeout(5000){vm.state.first{it.manualTags[h.id]?.names==listOf("合成新标签")}}
                assertNull(vm.state.value.factRecap!!.snapshot);vm.factRecaps.generate()
                withTimeout(5000){vm.state.first{it.factRecap?.snapshot?.cards?.singleOrNull()?.tags?.names==listOf("合成新标签")}}
            } finally {vm.clearForHostTest();assertTrue(closed.await(3,TimeUnit.SECONDS))}
        }
    }
}

internal class FactRecapHarness : AutoCloseable {
    val f=PermanentErasureFixture()
    val clock:Clock=Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"),ZoneId.of("UTC"))
    val id=f.entry(text="合成：只是计划，不是已经发生的事件。🙂").also {f.db.write("UPDATE entries SET capturedAtMillis=? WHERE id=?",clock.millis(),it)}
    var failCapture=false;var afterCapture:()->Unit={};var beforePublication:()->Unit={};var beforeResolve:()->Unit={}
    var beforeStateCommit:()->Unit={};var accept=true
    private val base=SqlFactRecapRepository(f.gateway,f.tags)
    val store=object:EntryRepository {
        override val factRecaps=object:FactRecapRepository by base {
            override fun capture(range:FactRecapRange):FactRecapSnapshot {
                if(failCapture)error("Synthetic read failure")
                return base.capture(range).also{afterCapture()}
            }
            override fun publishCurrent(snapshot:FactRecapSnapshot,publish:()->Unit)=base.publishCurrent(snapshot){beforePublication();publish()}
            override fun resolve(snapshot:FactRecapSnapshot,source:SourceVersion,publish:()->Unit)=base.resolve(snapshot,source){beforeResolve();publish()}
        }
        override val manualTags=f.tags
        override suspend fun entries()=f.db.read("SELECT e.id,e.currentRevision,r.originalText,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision WHERE NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1)").map {
            StoredEntry(it[0] as String,(it[1] as Long).toInt(),it[2] as String,it[3] as Long,it[4] as String,ProcessingPolicy.valueOf(it[5] as String))
        }
        override suspend fun readDraft():TextDraft?=null
        override suspend fun updateDraft(text:String):TextDraft=error("Unrelated")
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("Unrelated")
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("Unrelated")
    }
    @Volatile var local=runBlocking {LocalRecordUiState(ready=true,opening=false,supportsFactRecaps=true,manualTagsAvailable=true,manualTags=f.tags.snapshots(),entries=store.entries())}
    val queue=ArrayDeque<suspend(EntryRepository)->Unit>()
    val c=FactRecapController({local},{change->beforeStateCommit();local=reconcileFactRecap(local,change(local))},{if(accept){queue.add(it);true}else false},clock)
    val ui get()=checkNotNull(local.factRecap)
    suspend fun drain(){while(queue.isNotEmpty())queue.removeFirst()(store)}
    override fun close(){c.dispose();f.close()}
}
