package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Actual controller/matcher over explicit synthetic ports; durable SQL is tested separately. */
class RecordEventDateControllerTest {
    @Test fun manualSetCorrectionAndClearRequireSeparatePreviewAndNeverReviseOriginal() = Harness().use { h ->
        h.open(); val source = h.state.entries.single(); val editor = h.ui()
        assertEquals("", editor.dateInput); assertEquals("", editor.zoneInput)
        h.controller.edit(editor.session, "2024-02-29", "Asia/Shanghai")
        h.controller.preview(editor.session); assertEquals(0, h.calls.size)
        val firstPreview = h.ui().preview!!
        h.controller.confirm(editor.session, firstPreview.id + 1); assertTrue(h.queue.isEmpty())
        h.controller.confirm(editor.session, firstPreview.id); h.controller.confirm(editor.session, firstPreview.id)
        assertEquals(1, h.queue.size); h.drain()
        assertEquals(RecordEventDate("2024-02-29", "Asia/Shanghai"), h.durable.getValue("one").date)
        assertEquals(source, h.state.entries.single()); assertEquals("private-draft", h.state.text)
        h.controller.edit(editor.session, "2023-10-09", "America/New_York")
        h.controller.preview(editor.session); h.controller.confirm(editor.session, h.ui().preview!!.id); h.drain()
        assertEquals(2L, h.durable.getValue("one").version)
        h.controller.preview(editor.session, clear = true); assertNotNull(h.durable.getValue("one").date)
        assertNull(h.ui().preview!!.date)
        h.controller.confirm(editor.session, h.ui().preview!!.id); h.drain()
        assertNull(h.durable.getValue("one").date); assertEquals(3L, h.durable.getValue("one").version)
        assertEquals(source, h.state.entries.single()); assertEquals(3, h.calls.size)
    }

    @Test fun unknownDateZoneInvalidCalendarOffsetAndYearZeroNeverProducePreviewOrGuess() = Harness().use { h ->
        h.open(); val session = h.ui().session
        for ((day, zone) in listOf("" to "", "2024-02-29" to "", "" to "UTC",
            "2023-02-29" to "Asia/Shanghai", "0000-01-01" to "UTC", "2024-01-01" to "+08:00",
            "2024-01-01" to "Asia/Unknown", "2024-1-01" to "UTC")) {
            h.controller.edit(session, day, zone); h.controller.preview(session)
            assertNull(h.ui().preview); assertNotNull(h.ui().error)
            assertEquals(day, h.ui().dateInput); assertEquals(zone, h.ui().zoneInput)
        }
        assertTrue(h.calls.isEmpty()); assertNull(h.durable.getValue("one").date)
    }

    @Test fun changedInputInvalidatesPreviewAndOldConfirmCannotWrite() = Harness().use { h ->
        h.open(); val session = h.ui().session
        h.controller.edit(session, "2024-01-01", "UTC"); h.controller.preview(session)
        val old = h.ui().preview!!.id
        h.controller.edit(session, "2024-01-02", "UTC")
        assertNull(h.ui().preview); h.controller.confirm(session, old); assertTrue(h.queue.isEmpty())
        h.controller.preview(session); val next = h.ui().preview!!.id
        h.controller.cancelPreview(session, next); h.controller.confirm(session, next)
        assertTrue(h.calls.isEmpty()); assertTrue(h.queue.isEmpty())
    }

    @Test fun unknownCommitBlocksNewIntentAcrossCloseReopenAndRetriesSameObjectOnly() = Harness().use { h ->
        h.open(); val first = h.ui().session; h.lose = true
        h.controller.edit(first, "2024-03-10", "America/New_York"); h.controller.preview(first)
        h.controller.confirm(first, h.ui().preview!!.id); h.drain()
        val pending = h.state.eventDateWrites.getValue("one")
        assertFalse(pending.busy); assertFalse(h.state.eventDatesAvailable); assertNull(h.ui().notice)
        h.controller.edit(first, "2024-03-11", "UTC"); h.controller.preview(first, clear = true)
        assertNull(h.ui().preview); assertEquals("2024-03-10", h.ui().dateInput)
        h.controller.close(first); h.open(); val second = h.ui().session
        assertNotEquals(first, second); assertSame(pending, h.state.eventDateWrites.getValue("one"))
        h.controller.edit(second, "2024-03-11", "UTC"); assertNull(h.ui().preview)
        h.controller.retry(second); h.controller.close(second); h.drain()
        assertTrue(h.state.eventDateWrites.containsKey("one")); assertFalse(h.state.eventDateWrites.getValue("one").busy)
        h.open(); val third = h.ui().session
        h.controller.edit(third, "2024-03-11", "UTC"); assertEquals("2024-03-10", h.ui().dateInput)
        h.controller.retry(third); h.drain()
        assertSame(h.calls[0], h.calls[1]); assertEquals(1L, h.durable.getValue("one").version)
        assertTrue(h.state.eventDateWrites.isEmpty()); assertNotNull(h.ui().notice)
    }

    @Test fun sourceAndMetadataConflictsRequireFreshReadAndNewPreview() = Harness().use { h ->
        h.open(); val session = h.ui().session
        h.controller.edit(session, "2024-04-01", "UTC"); h.controller.preview(session)
        val stalePreview = h.ui().preview!!.id
        h.durable["one"] = RecordEventDateSnapshot("one", 1, RecordEventDate("2024-05-01", "UTC"))
        h.controller.confirm(session, stalePreview); h.drain()
        assertTrue(h.ui().conflict); assertNull(h.ui().notice)
        h.controller.retry(session); h.drain(); assertFalse(h.ui().conflict)
        assertEquals("2024-04-01", h.ui().dateInput); assertEquals(1L, h.ui().snapshot!!.version)
        h.controller.confirm(session, stalePreview); assertTrue(h.queue.isEmpty())
        h.controller.preview(session); h.controller.confirm(session, h.ui().preview!!.id)
        h.state = h.state.copy(entries = listOf(h.entry.copy(currentRevision = 2, originalText = "changed-original")))
        h.drain(); assertTrue(h.ui().conflict)
        assertEquals("2024-05-01", h.durable.getValue("one").date!!.localDate)
    }

    @Test fun navigationAndRetiredCallbacksCannotStartQueuedWritesOrPublishCrossRecordNotice() = Harness().use { h ->
        h.open(); var allowed = true
        h.controller.close(h.ui().session)
        h.controller.open("one", 1) { allowed }; h.drain(); val session = h.ui().session
        h.controller.edit(session, "2024-01-01", "UTC"); h.controller.preview(session)
        h.controller.confirm(session, h.ui().preview!!.id); allowed = false; h.drain()
        assertTrue(h.calls.isEmpty()); assertTrue(h.state.eventDateWrites.isEmpty())
        h.controller.close(session); h.open()
        h.controller.edit(h.ui().session, "2024-01-01", "UTC"); h.controller.preview(h.ui().session)
        h.controller.confirm(h.ui().session, h.ui().preview!!.id)
        h.afterSet = { h.controller.close(h.ui().session) }; h.drain()
        assertNull(h.state.eventDateEditor); assertTrue(h.state.eventDateWrites.isEmpty())
        assertEquals("2024-01-01", h.state.eventDates.getValue("one").date!!.localDate)
        h.open(); val last = h.ui().session; h.controller.dispose(); h.controller.preview(last)
        assertTrue(h.state.eventDateControllerRetired); assertNull(h.state.eventDateEditor)
    }

    @Test fun rejectedQueueReadFailureAndColdOwnerNeverShowFalseKnownOrSaveSuccess() = Harness().use { h ->
        h.accept = false; h.controller.open("one", 1)
        assertFalse(h.ui().busy); assertFalse(h.state.eventDatesAvailable); assertNotNull(h.ui().error)
        h.accept = true; h.failRead = true; h.controller.retry(h.ui().session); h.drain()
        assertFalse(h.state.eventDatesAvailable); assertNull(h.ui().snapshot)
        h.failRead = false; h.controller.retry(h.ui().session); h.drain()
        h.controller.edit(h.ui().session, "2024-02-29", "UTC"); h.controller.preview(h.ui().session)
        h.accept = false; h.controller.confirm(h.ui().session, h.ui().preview!!.id)
        assertFalse(h.state.eventDateWrites.getValue("one").busy); assertNull(h.ui().notice)
        h.accept = true; h.controller.retry(h.ui().session); h.drain()
        h.controller.dispose()
        h.state = h.state.copy(eventDateControllerRetired = false, eventDateEditor = null,
            eventDates = emptyMap(), eventDatesAvailable = false, eventDateWrites = emptyMap())
        val cold = h.newController(); cold.open("one", 1); h.drain()
        assertEquals("2024-02-29", h.ui().snapshot!!.date!!.localDate)
        assertEquals("2024-02-29", h.ui().dateInput); assertNull(h.ui().notice); cold.dispose()
    }

    @Test(timeout=15000) fun actualVmActorLoadsAndAcknowledgesEventDateThenNewOwnerReadsPersistedChoice(): Unit = runBlocking {
        Harness().use { h ->
            val caller = Thread.currentThread(); val writer = AtomicReference<Thread>()
            h.afterSet = { writer.set(Thread.currentThread()) }
            suspend fun owner(write: Boolean) {
                val closed = CountDownLatch(1)
                val store = object: EntryRepository by h.store, AutoCloseable { override fun close() { closed.countDown() } }
                val vm = LocalRecordsViewModel(android.app.Application(), { store })
                try {
                    withTimeout(5000) { vm.state.first { it.ready && !it.opening } }
                    assertTrue(vm.state.value.supportsEventDates); assertTrue(vm.state.value.eventDatesAvailable)
                    vm.eventDates.open("one", 1)
                    val loaded = withTimeout(5000) { vm.state.first { it.eventDateEditor?.snapshot != null && it.eventDateEditor?.busy == false } }
                    val session = loaded.eventDateEditor!!.session
                    if (write) {
                        vm.eventDates.edit(session, "2024-02-29", "Asia/Shanghai"); vm.eventDates.preview(session)
                        assertTrue(h.calls.isEmpty())
                        vm.eventDates.confirm(session, vm.state.value.eventDateEditor!!.preview!!.id)
                        val saved = withTimeout(5000) { vm.state.first { it.eventDateEditor?.notice != null } }
                        assertEquals("2024-02-29", saved.eventDates.getValue("one").date!!.localDate)
                        assertEquals(h.entry, saved.entries.single()); assertTrue(saved.eventDateWrites.isEmpty())
                        assertNotSame(caller, writer.get())
                    } else {
                        assertEquals("2024-02-29", loaded.eventDateEditor!!.snapshot!!.date!!.localDate)
                        assertEquals("2024-02-29", loaded.eventDateEditor!!.dateInput)
                        assertNull(loaded.eventDateEditor!!.notice); assertTrue(loaded.eventDateWrites.isEmpty())
                    }
                } finally { vm.clearForHostTest(); assertTrue(closed.await(3, TimeUnit.SECONDS)) }
            }
            owner(true); owner(false)
        }
    }

    @Test fun eventDateSearchUsesOwnDayAndAndCombinesKeywordTagFavoriteWithoutCaptureFallback() = runBlocking {
        val a = StoredEntry("one", 1, "Coffee needle", 0, "UTC", ProcessingPolicy.LOCAL_ONLY)
        val b = a.copy(id = "two", originalText = "Tea needle")
        val c = a.copy(id = "three", originalText = "Coffee needle")
        val dates = mapOf("one" to RecordEventDateSnapshot("one", 1, RecordEventDate("2024-03-10", "America/New_York")),
            "two" to RecordEventDateSnapshot("two", 1, RecordEventDate("2024-03-10", "Asia/Shanghai")),
            "three" to RecordEventDateSnapshot("three", 0, null))
        val input = OriginalSearchInput("coffee", "2024-03-10", "2024-03-10", tag = "tag",
            tagSnapshot = mapOf("one" to ManualTagSnapshot("one", 1, listOf("tag")), "two" to ManualTagSnapshot("two", 1, listOf("tag"))),
            onlyFavorites = true, favoriteSnapshot = mapOf("one" to RecordFavoriteSnapshot("one", 1, true)),
            dateBasis = RecordDateBasis.EVENT, eventDateSnapshot = dates)
        assertEquals(listOf("one"), LocalOriginalSearchMatcher.match(listOf(a,b,c), input).hits.map { it.entry.id })
        assertTrue(LocalOriginalSearchMatcher.match(listOf(a,b,c), input.copy(from = "1970-01-01", through = "1970-01-01")).hits.isEmpty())
        val allEvent = OriginalSearchInput(dateBasis = RecordDateBasis.EVENT, eventDateSnapshot = dates)
        assertEquals(listOf("one","two"), LocalOriginalSearchMatcher.match(listOf(a,b,c), allEvent).hits.map { it.entry.id })
        assertEquals(OriginalSearchStage.FAILED, LocalOriginalSearchMatcher.match(listOf(a,b,c), allEvent.copy(eventDateSnapshot = null)).stage)
        val captured = OriginalSearchInput(from = "1970-01-01", through = "1970-01-01")
        assertEquals(3, LocalOriginalSearchMatcher.match(listOf(a,b,c), captured).hits.size)
        assertEquals(OriginalSearchStage.INVALID, LocalOriginalSearchMatcher.match(listOf(a), input.copy(from = "0000-01-01")).stage)
    }

    private class Harness : AutoCloseable {
        val entry = StoredEntry("one",1,"synthetic original",0,"UTC",ProcessingPolicy.LOCAL_ONLY)
        var state = LocalRecordUiState(ready=true,opening=false,entries=listOf(entry),text="private-draft",
            localProcessingVersions=mapOf("one" to LocalProcessingVersion(SourceVersion("one",1),0)),
            supportsEventDates=true,eventDatesAvailable=true,eventDates=mapOf("one" to RecordEventDateSnapshot("one",0,null)))
        val durable = state.eventDates.toMutableMap(); val calls=mutableListOf<RecordEventDateMutation>()
        val receipts=mutableMapOf<String,RecordEventDateSnapshot>()
        val queue=ArrayDeque<suspend(EntryRepository)->Unit>()
        var accept=true; var lose=false; var failRead=false; var afterSet:()->Unit={}
        private val port=object:RecordEventDateRepository {
            override fun read(entryId:String)=durable.getValue(entryId)
            override fun snapshots():Map<String,RecordEventDateSnapshot> { if(failRead)throw RecordEventDateStorageFailure();return durable.toMap() }
            override fun set(mutation:RecordEventDateMutation):RecordEventDateSnapshot {
                calls+=mutation
                receipts[mutation.mutationId]?.let { return it }
                val original=state.entries.singleOrNull { it.id==mutation.entryId } ?: throw RecordEventDateUnavailable()
                val before=durable.getValue(mutation.entryId)
                if(original.currentRevision!=mutation.sourceRevision || OriginalExports.hash(original.originalText)!=mutation.sourceHash || before.version!=mutation.expectedVersion)
                    throw RecordEventDateConflict()
                val next=RecordEventDateSnapshot(mutation.entryId,before.version+1,mutation.date)
                durable[mutation.entryId]=next;receipts[mutation.mutationId]=next;afterSet()
                if(lose){lose=false;throw RecordEventDateStorageFailure()};return next
            }
        }
        val store=object:EntryRepository {
            override val localProcessing:LocalProcessingRepository=object:LocalProcessingRepository {
                override fun versions()=state.entries.associate{it.id to LocalProcessingVersion(SourceVersion(it.id,it.currentRevision.toLong()),0)}
                override fun read(source:SourceVersion):LocalProcessingSnapshot {val e=state.entries.single{it.id==source.sourceId};return LocalProcessingSnapshot(source,OriginalExports.hash(e.originalText),0,e.policy)}
                override fun change(request:LocalProcessingChange)=error("Outside synthetic source contract")
            }
            override val eventDates=port
            override suspend fun readDraft()=null
            override suspend fun updateDraft(text:String)=error("Outside contract")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("Outside contract")
            override suspend fun entries()=state.entries
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("Outside contract")
        }
        fun newController()=RecordEventDateController({state},{state=it(state)},{if(accept){queue.addLast(it);true}else false})
        val controller=newController()
        fun ui()=state.eventDateEditor!!
        fun open(){controller.open("one",state.entries.single().currentRevision);drain()}
        fun drain()=runBlocking{while(queue.isNotEmpty())queue.removeFirst()(store)}
        override fun close(){controller.dispose()}
    }
}
