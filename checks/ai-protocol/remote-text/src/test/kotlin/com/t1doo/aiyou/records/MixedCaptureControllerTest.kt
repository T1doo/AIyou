package com.t1doo.aiyou.records

import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MixedCaptureControllerTest {
    @Test fun incompleteUnicodeAndRejectedQueueDoNotFreezeOrLoseEditableInput():Unit=runBlocking {
        Harness().use { h->
            h.start();h.c.edit("合成\uD83D");h.drain();h.c.save();h.drain()
            assertFalse(h.ui.uncertainSave);assertFalse(h.ui.busy);assertEquals("合成\uD83D",h.ui.text)
            h.c.edit("合成完整😀");h.drain();h.acceptQueue=false;h.c.save()
            assertFalse(h.ui.busy);assertEquals("合成完整😀",h.ui.text);assertTrue(h.f.db.read("SELECT id FROM entries").isEmpty())
            h.acceptQueue=true;h.c.save();h.drain();assertEquals("合成完整😀",h.local.entries.single().originalText)
        }
    }
    @Test(timeout=15000) fun realVmCaptureCommandRefreshesFavoritesAndSchedulesImmediateLocalSearch():Unit=runBlocking {
        Harness().use { h->
            val closed=CountDownLatch(1)
            val search=SqlLocalSearchRepository(h.f.gateway,h.f.tags)
            val owner=object:EntryRepository by h.store,AutoCloseable {
                override val favorites=SqlRecordFavoriteRepository(h.f.gateway)
                override val localSearch=search
                override fun close(){closed.countDown()}
            }
            val vm=LocalRecordsViewModel(android.app.Application(),{owner},decodeCapture={object:AttachmentPicture{override val width=1;override val height=1}})
            try {
                withTimeout(5000){vm.state.first{it.mixedCapture?.draft!=null}}
                vm.mixedCapture.edit("合成即时查找图文")
                withTimeout(5000){vm.state.first{it.mixedCapture?.let{u->u.text=="合成即时查找图文"&&u.pendingEdits==0}==true}}
                vm.mixedCapture.select();val request=vm.state.value.mixedCapture!!.selection!!.requestId
                assertTrue(vm.mixedCapture.claimPicker(request));vm.mixedCapture.selected(request,Source())
                withTimeout(5000){vm.state.first{it.mixedCapture?.let{u->u.selection==null&&u.draft?.images?.size==1}==true}}
                vm.mixedCapture.save()
                withTimeout(5000){vm.state.first{it.entries.size==1&&it.mixedCapture?.notice!=null}}
                withTimeout(5000){while(h.f.db.read("SELECT id FROM local_jobs WHERE operation='LOCAL_INDEX' AND state='PENDING'").isNotEmpty())delay(10)}
                val state=vm.state.value;assertTrue(state.favoritesAvailable);assertEquals(1,state.favorites.size)
                assertEquals(state.entries.map{it.id},search.candidates(LocalSearchRequest("即时查找")).entries.map{it.id})
                assertNull(state.localIndexError)
            } finally {vm.clearForHostTest();assertTrue(closed.await(3,TimeUnit.SECONDS))}
        }
    }
    @Test fun failedTextRefreshAndImageRemovalKeepUnacknowledgedUserInput():Unit=runBlocking {
        Harness().use { h->
            h.start();h.add();h.failEdit=true;h.c.edit("合成未确认输入");h.drain();assertNotNull(h.ui.error)
            h.c.refresh();h.drain();assertEquals("合成未确认输入",h.ui.text)
            h.c.remove(h.ui.draft!!.images.single().intent.id);h.drain();assertEquals("合成未确认输入",h.ui.text)
            h.failEdit=false;h.c.save();h.drain();assertEquals("合成未确认输入",h.local.entries.single().originalText)
        }
    }
    @Test fun actualPureImageAndMixedSaveWaitForDurableReadbackAndLostAckRetriesSameIdentity(): Unit=runBlocking {
        Harness().use { h->
            h.start();val c=h.c;val id=h.ui.draft!!.id
            c.edit("合成图文输入");h.drain();h.add()
            assertTrue(h.f.db.read("SELECT id FROM entries").isEmpty());assertEquals(1,h.ui.pictures.size)
            h.failEntries=true;c.save();assertTrue(h.f.db.read("SELECT id FROM entries").isEmpty());h.drain()
            assertTrue(h.ui.uncertainSave);assertEquals("合成图文输入",h.ui.text);assertEquals(1,h.ui.draft!!.images.size)
            c.save();h.drain();assertEquals(1,h.local.entries.size);assertEquals(id,h.local.entries.single().id)
            assertEquals(ProcessingPolicy.LOCAL_ONLY,h.local.entries.single().policy);assertEquals("",h.ui.text)
            h.add();c.save();h.drain();assertEquals(2,h.local.entries.size);assertEquals("",h.local.entries.last().originalText)
            assertEquals(2,h.f.db.read("SELECT id FROM attachments").size)
        }
    }
    @Test fun cancelledAndOldDraftPickerCallbacksNeverReadOrAttachToReplacementDraft(): Unit=runBlocking {
        Harness().use { h->
            h.start();h.c.select();val old=h.ui.selection!!;assertTrue(h.c.claimPicker(old.requestId))
            h.c.cancelSelection();h.drain();h.c.requestDiscard();h.c.confirmDiscard();h.drain()
            val source=Source();h.c.selected(old.requestId,source);h.drain();assertEquals(0,source.opens)
            h.c.select();val new=h.ui.selection!!;h.c.selected(old.requestId,source);h.drain();assertEquals(0,source.opens)
            h.c.selected(new.requestId,null);h.drain();assertTrue(h.ui.draft!!.images.isEmpty())
        }
    }
    @Test fun failedImageKeepsRecoverableIdentityBlocksTextOnlySaveAndCanReselectSameOriginal(): Unit=runBlocking {
        Harness().use { h->
            h.start();h.c.edit("不能丢图");h.drain()
            h.f.mediaCheckpoint={if(it=="created") error("Synthetic interrupted image write")}
            h.add();assertEquals(AttachmentImportPhase.FAILED,h.ui.selection!!.phase)
            assertEquals("不能丢图",h.ui.text);h.c.cancelSelection();h.drain();h.c.save();h.drain()
            assertFalse(h.ui.uncertainSave);assertTrue(h.f.db.read("SELECT id FROM entries").isEmpty())
            h.f.mediaCheckpoint={};h.c.refresh();h.drain();val image=h.ui.draft!!.images.single()
            h.c.select(image.intent.id);val s=h.ui.selection!!;h.c.claimPicker(s.requestId);h.c.selected(s.requestId,Source());h.drain()
            assertEquals(CaptureImageState.READY,h.ui.draft!!.images.single().state)
            h.c.save();h.drain();assertEquals(1,h.local.entries.size);assertEquals("不能丢图",h.local.entries.single().originalText)
        }
    }
    @Test fun removeLastImageEmptySaveAndDiscardAreExplicitAndDoNotCreatePlaceholderText(): Unit=runBlocking {
        Harness().use { h->
            h.start();h.add();val d=h.ui.draft!!;h.c.remove(d.images.single().intent.id);h.drain()
            assertTrue(h.ui.draft!!.images.isEmpty());h.c.save();h.drain();assertTrue(h.f.db.read("SELECT id FROM entries").isEmpty())
            assertNotNull(h.ui.error);h.c.requestDiscard();h.c.cancelDiscard();assertEquals(d.id,h.ui.draft!!.id)
            h.c.requestDiscard();h.c.confirmDiscard();h.drain();assertNotEquals(d.id,h.ui.draft!!.id)
            assertEquals("",h.ui.text);h.c.dispose();val late=Source();h.c.selected(1,late);h.drain();assertEquals(0,late.opens)
        }
    }
    internal class Harness:AutoCloseable {
        val f=MixedCaptureSqlTest.fixture();private val repo=MixedCaptureSqlTest.repo(f)
        var local=LocalRecordUiState(ready=true,opening=false,supportsMixedCapture=true,mixedCapture=MixedCaptureUiState())
        val queue=ArrayDeque<suspend(EntryRepository)->Unit>();var failEntries=false;var failEdit=false;var acceptQueue=true
        val store=object:EntryRepository {
            override val captureRecords=object:CaptureRepository by repo {
                override suspend fun edit(expected:CaptureDraft,text:String):CaptureDraft {
                    if(failEdit)error("Synthetic edit failure")
                    return repo.edit(expected,text)
                }
            }
            override val manualTags get()=f.tags
            override suspend fun entries():List<StoredEntry> {
                if(failEntries){failEntries=false;error("Synthetic lost readback")}
                return f.db.read("SELECT e.id,e.currentRevision,r.originalText,e.capturedAtMillis,e.captureTimeZone,e.privacyPolicy FROM entries e JOIN entry_revisions r ON r.entryId=e.id AND r.revision=e.currentRevision ORDER BY e.capturedAtMillis,e.id").map { StoredEntry(it[0] as String,(it[1] as Long).toInt(),it[2] as String,it[3] as Long,it[4] as String,ProcessingPolicy.valueOf(it[5] as String)) }
            }
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("Legacy capture not used")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("Legacy capture not used")
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("Unrelated edit")
        }
        val c=MixedCaptureController({local},{local=it(local)},{if(acceptQueue){queue.add(it);true}else false},{object:AttachmentPicture{override val width=1;override val height=1}},Clock.systemUTC())
        val ui get()=checkNotNull(local.mixedCapture)
        suspend fun start(){c.initialize(store)}
        suspend fun drain(){while(queue.isNotEmpty())queue.removeFirst()(store)}
        suspend fun add(){c.select();val s=ui.selection!!;assertTrue(c.claimPicker(s.requestId));c.selected(s.requestId,Source());drain()}
        override fun close(){c.dispose();runBlocking{drain()};c.disposeOnIo();f.close()}
    }
    private class Source:SelectedImageSource {var opens=0;override fun mediaType()="image/png";override fun open()=MixedCaptureSqlTest.png.inputStream().also{opens++}}
}
