package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual controllers/VM and product encrypted capture SQL, on owned host fixtures. */
class CaptureDiscardRecoveryTest {
    @Test(timeout=20_000) fun realVmLostDiscardAckPausesNewInputAndRefreshVerifiesOriginalClearBeforeAnotherSave():Unit=runBlocking {
        Harness().use { h ->
            val vm=LocalRecordsViewModel(Application(),{h.store})
            suspend fun await(test:(LocalRecordUiState)->Boolean)=withTimeout(7_000){vm.state.first(test)}
            try {
                await{it.mixedCapture?.draft!=null};vm.mixedCapture.edit(OLD)
                await{it.mixedCapture?.let{u->u.text==OLD&&u.pendingEdits==0}==true}
                val old=vm.state.value.mixedCapture!!.draft!!.id
                h.loseDiscardAck=true;vm.mixedCapture.requestDiscard();vm.mixedCapture.confirmDiscard()
                await{it.mixedCapture?.let{u->!u.busy&&u.error!=null}==true}
                assertNotEquals(old,h.repo.current().id)
                vm.mixedCapture.cancelDiscard();vm.mixedCapture.edit(NEW);vm.mixedCapture.save()
                assertEquals("Unknown clear must not accept a new input against the old identity",OLD,vm.state.value.mixedCapture!!.text)
                vm.mixedCapture.refresh()
                await{it.mixedCapture?.let{u->!u.busy&&u.error==null&&u.draft?.id!=old}==true}
                assertEquals(listOf(old,old),h.discards)
                assertEquals("",vm.state.value.mixedCapture!!.text)
                vm.mixedCapture.edit(NEW)
                await{it.mixedCapture?.let{u->u.text==NEW&&u.pendingEdits==0}==true}
                vm.mixedCapture.save();await{it.entries.size==1&&it.mixedCapture?.notice!=null}
                assertEquals(NEW,vm.state.value.entries.single().originalText)
                assertEquals(ProcessingPolicy.LOCAL_ONLY,vm.state.value.entries.single().policy)
                assertEquals(1,h.f.db.read("SELECT id FROM entries").size)
            } finally {vm.clearForHostTest();assertTrue(h.original.closed.await(5,TimeUnit.SECONDS))}
        }
    }

    @Test fun interruptedImageCleanupCanCloseThePromptButBlocksOtherDraftActionsUntilSameClearIsConfirmed():Unit=runBlocking {
        Harness().use { h ->
            h.start();h.c.edit(OLD);h.drain();h.add()
            val old=h.ui.draft!!.id;val image=h.ui.draft!!.images.single().intent.id
            var once=true;h.f.mediaCheckpoint={if(it=="package-cleaned"&&once){once=false;error("Synthetic image cleanup interruption")}}
            h.c.requestDiscard();h.c.confirmDiscard();h.drain();assertNotNull(h.ui.error)
            h.c.cancelDiscard();assertFalse(h.ui.confirmDiscard)
            h.c.select();assertNull("Unconfirmed clear must not launch another picture selection",h.ui.selection)
            h.c.edit(NEW);h.c.metadata(false);h.c.remove(image);h.c.save();h.drain()
            assertEquals(OLD,h.ui.text);assertTrue(h.ui.retainJpegMetadata)
            assertEquals(0,h.removes);assertEquals(0,h.saves)
            h.c.confirmDiscard();h.drain()
            assertEquals(listOf(old,old),h.discards)
            assertNotEquals(old,h.ui.draft!!.id);assertEquals("",h.ui.text)
            assertTrue(h.ui.draft!!.images.isEmpty());assertNull(h.ui.error)
            assertTrue(h.f.db.read("SELECT id FROM entries").isEmpty())
            h.c.edit(NEW);h.drain();assertEquals(NEW,h.repo.current().text)
        }
    }

    @Test fun firstClearRejectedByTheQueueStillAllowsCancelAndContinuedEditing():Unit=runBlocking {
        Harness().use { h ->
            h.start();h.c.edit(OLD);h.drain();val old=h.ui.draft!!.id
            h.acceptQueue=false;h.c.requestDiscard();h.c.confirmDiscard()
            assertFalse(h.ui.busy);assertNotNull(h.ui.error);assertTrue(h.discards.isEmpty())
            h.c.cancelDiscard();h.acceptQueue=true;h.c.edit(NEW);h.drain()
            assertEquals(old,h.ui.draft!!.id);assertEquals(NEW,h.repo.current().text)
        }
    }

    @Test fun originalClearReplayPreservesANewerDurableDraftAndNeverClearsItsIdentity():Unit=runBlocking {
        Harness().use { h ->
            h.start();h.c.edit(OLD);h.drain();val old=h.ui.draft!!.id
            h.loseDiscardAck=true;h.c.requestDiscard();h.c.confirmDiscard();h.drain()
            val replacement=h.repo.current();val newer=h.repo.edit(replacement,OTHER)
            h.c.cancelDiscard();h.c.refresh();h.drain()
            assertEquals("Retry must use only the original confirmed clear identity",listOf(old,old),h.discards)
            assertDraft(newer,h.repo.current());assertEquals(newer.id,h.ui.draft!!.id)
            assertEquals(OTHER,h.ui.text);assertNull(h.ui.error)
            h.c.confirmDiscard();h.drain();assertEquals(listOf(old,old),h.discards)
            assertDraft(newer,h.repo.current())
        }
    }

    @Test fun ordinaryRefreshStillRejectsAnUnrelatedReplacementWithoutAnOwnedClearIntent():Unit=runBlocking {
        Harness().use { h ->
            h.start();h.c.edit(OLD);h.drain();val old=h.ui.draft!!.id
            val replacement=h.repo.discard(old);val other=h.repo.edit(replacement,OTHER)
            h.c.refresh();h.drain()
            assertEquals(old,h.ui.draft!!.id);assertEquals(OLD,h.ui.text);assertNotNull(h.ui.error)
            assertDraft(other,h.repo.current());assertTrue(h.discards.isEmpty())
        }
    }

    private class Harness:AutoCloseable {
        val f=MixedCaptureSqlTest.fixture();val repo=MixedCaptureSqlTest.repo(f)
        val original=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)){}
        @Volatile var loseDiscardAck=false
        val discards=java.util.concurrent.CopyOnWriteArrayList<String>();var removes=0;var saves=0
        val store:EntryRepository=object:EntryRepository by original,AutoCloseable {
            override val captureRecords=object:CaptureRepository by repo {
                override suspend fun discard(draftId:String):CaptureDraft {
                    discards.add(draftId);val result=repo.discard(draftId)
                    if(loseDiscardAck){loseDiscardAck=false;error("Synthetic lost ACK after real discard commit")}
                    return result
                }
                override suspend fun remove(draftId:String,imageId:String):CaptureDraft {removes++;return repo.remove(draftId,imageId)}
                override suspend fun save(expected:CaptureDraft,capturedAtMillis:Long,zone:String):CaptureSaveResult {saves++;return repo.save(expected,capturedAtMillis,zone)}
            }
            override val manualTags=f.tags
            override val favorites=SqlRecordFavoriteRepository(f.gateway)
            override val localProcessing=SqlLocalProcessingRepository(f.gateway)
            override suspend fun prepare(){check(f.db.read("PRAGMA user_version").single().single()==9L)}
            override fun close()=original.close()
        }
        var local=LocalRecordUiState(ready=true,opening=false,supportsMixedCapture=true,mixedCapture=MixedCaptureUiState())
        val queue=ArrayDeque<suspend(EntryRepository)->Unit>();var acceptQueue=true
        val c=MixedCaptureController({local},{local=it(local)},{if(acceptQueue){queue.add(it);true}else false},
            {object:AttachmentPicture{override val width=1;override val height=1}},Clock.systemUTC())
        val ui get()=checkNotNull(local.mixedCapture)
        suspend fun start(){c.initialize(store)}
        suspend fun drain(){while(queue.isNotEmpty())queue.removeFirst()(store)}
        suspend fun add(){c.select();val s=ui.selection!!;check(c.claimPicker(s.requestId));c.selected(s.requestId,object:SelectedImageSource {
            override fun mediaType()="image/png";override fun open()=MixedCaptureSqlTest.png.inputStream()
        });drain()}
        override fun close(){c.dispose();runBlocking{drain()};c.disposeOnIo();original.close();f.close()}
    }
    private fun assertDraft(expected:CaptureDraft,actual:CaptureDraft) {
        assertEquals(expected.id,actual.id);assertEquals(expected.version,actual.version)
        assertEquals(expected.text,actual.text);assertEquals(expected.images,actual.images)
        assertEquals(expected.discarding,actual.discarding)
    }
    companion object {private const val OLD="合成清空前文字";private const val NEW="合成恢复后新输入";private const val OTHER="合成另一份已持久草稿"}
}
