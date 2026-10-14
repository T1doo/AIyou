package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Actual production controller, SQL, two encrypted stores and state CAS; queue is a host boundary. */
class PermanentErasureControllerTest {
    @Test fun cancellationAndRetiredQueuedConfirmationCannotPrepareOrEraseAnyTarget(): Unit = Harness().use { h ->
        h.open(); h.selectAndRequest(); val old=h.ui; val confirmation=old.confirmation!!
        assertTrue(confirmation.permanent); assertTrue(h.calls.isEmpty()); assertNull(h.f.erase.read(h.id))
        h.controller.cancelConfirmation(old.session,confirmation.id); h.controller.confirm(old.session,confirmation.id)
        assertTrue(h.queue.isEmpty()); assertNull(h.f.erase.read(h.id))
        h.selectAndRequest(); val current=h.ui; h.controller.confirm(current.session,current.confirmation!!.id)
        h.controller.close(current.session); h.controller.open(); val fresh=h.ui.session
        h.drain(); assertEquals(fresh,h.ui.session); assertTrue(h.calls.isEmpty()); assertNull(h.f.erase.read(h.id))
        assertEquals(1,h.f.db.read("SELECT id FROM entries").size)
    }
    @Test fun duplicateConfirmAndLostReadbackRetryReuseOneDurableIntentAndClearSourceBoundPanels(): Unit = Harness().use { h ->
        h.open(); h.selectAndRequest(); val session=h.ui.session; val c=h.ui.confirmation!!
        h.failAfterDone=true; h.controller.confirm(session,c.id); h.controller.confirm(session,c.id)
        assertEquals(1,h.queue.size); h.drain()
        assertTrue(h.f.erase.read(h.id)!!.complete); assertTrue(h.ui.confirmation!!.uncertain); assertFalse(h.ui.confirmation!!.busy)
        assertNull(h.ui.notice); assertEquals(c.erasureIntent!!.mutationId,h.ui.confirmation!!.erasureIntent!!.mutationId)
        h.controller.confirm(session,c.id); h.drain()
        assertEquals(2,h.calls.size); assertEquals(h.calls[0],h.calls[1]); assertNull(h.ui.confirmation); assertTrue(h.ui.records.isEmpty())
        assertTrue(h.ui.notice!!.contains("已删除")); assertTrue(h.local.entries.isEmpty()); assertTrue(h.local.pendingEdits.isEmpty())
        assertTrue(h.f.db.read("SELECT id FROM entries").isEmpty())
    }
    @Test fun preparedColdReopenOffersSameIntentAndBlocksRestoreBeforeAnyRecomposition(): Unit = Harness().use { h ->
        h.open(); h.selectAndRequest(); val c=h.ui.confirmation!!; val session=h.ui.session
        h.f.checkpoint={ if(it=="prepared") error("Synthetic interruption") }
        h.controller.confirm(session,c.id); h.drain(); assertTrue(h.ui.confirmation!!.uncertain)
        val durable=h.f.erase.read(h.id)!!.intent; h.controller.close(session); h.f.checkpoint={}; h.f.reopen()
        h.controller.open(); h.drain(); assertEquals(durable.mutationId,h.ui.erasures[h.id]!!.intent.mutationId)
        h.controller.select(h.ui.session,h.id); h.controller.requestRestore(h.ui.session,h.id,h.ui.records.single().generation)
        assertNull(h.ui.confirmation)
        h.controller.requestErasure(h.ui.session,h.id,h.ui.records.single().generation)
        assertTrue(h.ui.confirmation!!.uncertain); assertEquals(durable.mutationId,h.ui.confirmation!!.erasureIntent!!.mutationId)
        h.controller.confirm(h.ui.session,h.ui.confirmation!!.id); h.drain(); assertTrue(h.f.erase.read(h.id)!!.complete)
    }
    @Test fun staleTargetNavigationAndUnavailableStorageNeverAdoptAnotherConfirmationResult(): Unit = Harness().use { h ->
        h.open(); h.selectAndRequest(); val old=h.ui; val c=old.confirmation!!
        h.controller.close(old.session); h.controller.open(); h.drain()
        h.controller.confirm(old.session,c.id); assertTrue(h.queue.isEmpty()); assertTrue(h.calls.isEmpty())
        h.controller.requestErasure(h.ui.session,h.id,h.ui.records.single().generation+1); assertNull(h.ui.confirmation)
        h.local=h.local.copy(error="Synthetic unavailable")
        h.controller.select(h.ui.session,h.id); h.controller.requestErasure(h.ui.session,h.id,h.ui.records.single().generation)
        assertNull(h.ui.confirmation); assertNull(h.f.erase.read(h.id))
    }
    @Test fun disposalAtActualResultCasCannotReopenPanelAndCommittedErasureRemainsDurable(): Unit = Harness().use { h ->
        h.open(); h.selectAndRequest()
        h.beforePublish={ candidate -> if(candidate.recycleBin?.notice!=null) { h.beforePublish=null; h.controller.dispose() } }
        h.controller.confirm(h.ui.session,h.ui.confirmation!!.id); h.drain()
        assertNull(h.local.recycleBin); assertTrue(h.f.erase.read(h.id)!!.complete)
        assertFalse(h.local.toString().contains("合成私密原文"))
    }
    @Test fun missingTargetReadbackIsAnErrorPanelAndNeverAnEmptySuccessfulRecycleBin(): Unit = Harness().use { h ->
        h.failRead=true; h.controller.open(); h.drain()
        assertNotNull(h.ui.error); assertNull(h.ui.notice); assertTrue(h.calls.isEmpty()); assertNull(h.f.erase.read(h.id))
    }
    @Test fun disposalAfterClosedRouteGlobalCandidateMustInvalidateActualCasEvenWhenPanelAlreadyNull(): Unit = Harness().use { h ->
        h.open(); h.selectAndRequest(); val session=h.ui.session; val confirmation=h.ui.confirmation!!
        var inspected = false
        h.f.checkpoint={
            if(it=="final-sql") h.controller.close(session)
            if(it=="done-committed") h.f.entry(text="独立正常记录，应由新 owner 重新读取")
        }
        h.beforePublish={ candidate -> if(candidate.recycleBin==null && candidate.entries.size==1 && candidate.ready) {
            inspected=true;h.beforePublish=null;h.controller.dispose()
        } }
        h.controller.confirm(session,confirmation.id);h.drain()
        assertTrue(inspected)
        assertTrue(h.f.erase.read(h.id)!!.complete);assertFalse(h.local.ready);assertTrue(h.local.recycleControllerRetired)
        assertTrue(h.local.entries.isEmpty());assertTrue(h.local.manualTags.isEmpty());assertTrue(h.local.packageOrigins.isEmpty())
    }

    internal class Harness : AutoCloseable {
        val f=PermanentErasureFixture(); val id=f.entry(); val queue=ArrayDeque<suspend (EntryRepository)->Unit>()
        val calls=mutableListOf<String>(); var failAfterDone=false; var failRead=false
        var beforePublish: ((LocalRecordUiState)->Unit)?=null
        private val state=MutableStateFlow(LocalRecordUiState(ready=true,opening=false,supportsRecycleBin=true,supportsPermanentErasure=true))
        var local: LocalRecordUiState get()=state.value; set(value) {state.value=value}
        val ui get()=checkNotNull(local.recycleBin)
        init { f.intent(id) }
        private val store=object : EntryRepository {
            override val permanentErasure=object : PermanentErasureRepository {
                override fun read(entryId: String)=f.erase.read(entryId)
                override fun pending(limit: Int,afterEntryId: String?)=f.erase.pending(limit,afterEntryId)
                override suspend fun erase(intent: PermanentErasureIntent): PermanentErasureReceipt {
                    calls+=intent.mutationId
                    val result=f.erase.erase(intent)
                    if(failAfterDone) {failAfterDone=false;throw PermanentErasureIncomplete()}
                    return result
                }
            }
            override val recycleBin get()=object : RecycleBinRepository {
                override fun records(): List<RecycleRecord> {if(failRead) throw RecycleStorageFailure();return f.bin.records()}
                override fun read(entryId: String)=f.bin.read(entryId)
                override fun apply(mutation: RecycleMutation)=f.bin.apply(mutation)
            }
            override val manualTags get()=f.tags
            override val recordPackages get()=f.packages
            override val editDrafts get()=f.edits
            override suspend fun entries(): List<StoredEntry> = f.db.read("SELECT e.id,e.currentRevision,r.originalText,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy FROM entries e JOIN entry_revisions r ON e.id=r.entryId AND e.currentRevision=r.revision WHERE NOT EXISTS(SELECT 1 FROM remote_source_access a WHERE a.entryId=e.id AND a.deleted=1)").map {
                StoredEntry(it[0] as String,(it[1] as Long).toInt(),it[2] as String,it[3] as Long,it[4] as String,ProcessingPolicy.valueOf(it[5] as String))
            }
            override suspend fun readDraft(): TextDraft?=null
            override suspend fun updateDraft(text: String): TextDraft=error("Unused capture boundary")
            override suspend fun saveDraft(expected: TextDraft,capturedAtMillis: Long,captureTimeZone: String): StoredEntry=error("Unused capture boundary")
            override suspend fun revisions(entryId: String): List<OriginalRevision> =error("Unused history boundary")
            override suspend fun revise(entryId: String,expectedRevision: Int,text: String,createdAtMillis: Long): StoredEntry=error("Unused edit boundary")
        }
        val controller=RecycleBinController({local},{change -> state.update { latest -> change(latest).also {beforePublish?.invoke(it)} }},{queue.addLast(it);true})
        fun open() {controller.open();drain()}
        fun selectAndRequest() {controller.select(ui.session,id);controller.requestErasure(ui.session,id,ui.records.single {it.entry.id==id}.generation)}
        fun drain()=runBlocking {while(queue.isNotEmpty()) queue.removeFirst()(store)}
        override fun close() {controller.dispose();queue.clear();f.close()}
    }
}
