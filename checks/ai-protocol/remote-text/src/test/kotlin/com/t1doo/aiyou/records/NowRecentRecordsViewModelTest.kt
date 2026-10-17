package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual Now controller/VM and product capture/recycle/search SQL; no Compose or Android execution. */
class NowRecentRecordsViewModelTest {
    @Test fun leavingComposerWithoutAnImageSelectionKeepsFailedDraftWarningUntilExplicitSaveRecovery():Unit=runBlocking {
        MixedCaptureControllerTest.Harness().use { h ->
            h.start();h.failEdit=true;h.c.edit("合成尚未确认的下一份输入");h.drain()
            val warning=checkNotNull(h.ui.error)
            assertNull(h.ui.selection)
            // The production composer's onDispose hook used when opening the new reader entry.
            h.c.cancelSelection();h.drain()
            assertEquals("Leaving for reading must not erase a failed autosave warning",warning,h.ui.error)
            assertEquals("合成尚未确认的下一份输入",h.ui.text)
            assertTrue(h.f.db.read("SELECT id FROM entries").isEmpty())
            h.failEdit=false;h.c.save();h.drain()
            assertEquals("合成尚未确认的下一份输入",h.c.recentEntries().single().originalText)
            assertNull(h.ui.error)
        }
    }

    @Test(timeout=30_000) fun committedChinesePictureRecordAppearsImmediatelyAndTracksActualTrashRestoreWithoutLosingNextDraft():Unit=runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val original=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)){}
            val search=SqlLocalSearchRepository(f.gateway,f.tags)
            val store=object:EntryRepository by original,AutoCloseable {
                override val captureRecords=MixedCaptureSqlTest.repo(f)
                override val manualTags=f.tags
                override val favorites=SqlRecordFavoriteRepository(f.gateway)
                override val recycleBin=f.bin
                override val localSearch=search
                override val localProcessing=SqlLocalProcessingRepository(f.gateway)
                override suspend fun prepare(){check(f.db.read("PRAGMA user_version").single().single()==9L)}
                override fun close()=original.close()
            }
            val clock=Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"),ZoneOffset.UTC)
            val vm=LocalRecordsViewModel(Application(),{store},clock,decodeCapture={object:AttachmentPicture{override val width=1;override val height=1}})
            suspend fun await(test:(LocalRecordUiState)->Boolean)=withTimeout(8_000){vm.state.first(test)}
            try {
                await{it.mixedCapture?.draft!=null}
                vm.mixedCapture.edit("合成今天的中文图文记录")
                await{it.mixedCapture?.let{u->u.text=="合成今天的中文图文记录"&&u.pendingEdits==0}==true}
                vm.mixedCapture.select();val request=vm.state.value.mixedCapture!!.selection!!.requestId
                assertTrue(vm.mixedCapture.claimPicker(request))
                vm.mixedCapture.selected(request,object:SelectedImageSource {
                    override fun mediaType()="image/png"
                    override fun open()=MixedCaptureSqlTest.png.inputStream()
                })
                await{it.mixedCapture?.let{u->u.selection==null&&u.draft?.images?.size==1}==true}
                vm.mixedCapture.save();await{it.entries.size==1&&it.mixedCapture?.notice!=null&&!it.mixedCapture.busy}
                val day=vm.mixedCapture.today(ZoneOffset.UTC)
                val saved=vm.mixedCapture.recentEntries(day).single()
                assertEquals(vm.state.value.entries.single(),saved)
                assertEquals("合成今天的中文图文记录",saved.originalText)
                assertEquals(ProcessingPolicy.LOCAL_ONLY,saved.policy)
                val image=f.media.list(saved.id,1).single()
                assertArrayEquals(MixedCaptureSqlTest.png,f.media.read(image.intent.id))
                withTimeout(5_000){while(f.db.read("SELECT id FROM local_jobs WHERE operation='LOCAL_INDEX' AND state='PENDING'").isNotEmpty())delay(10)}
                assertEquals(saved.id,search.candidates(LocalSearchRequest("中文图文")).entries.single().id)
                vm.mixedCapture.edit("继续输入的下一份草稿")
                await{it.mixedCapture?.let{u->u.text=="继续输入的下一份草稿"&&u.pendingEdits==0}==true}
                vm.recycleBin.requestMove(saved.id,saved.currentRevision)
                val move=await{it.recycleBin?.confirmation!=null}.recycleBin!!
                vm.recycleBin.confirm(move.session,move.confirmation!!.id)
                await{it.entries.isEmpty()&&it.recycleBin==null}
                assertTrue(vm.mixedCapture.recentEntries(day).isEmpty())
                assertEquals("继续输入的下一份草稿",vm.state.value.mixedCapture!!.text)
                vm.recycleBin.open()
                val bin=await{it.recycleBin?.let{b->!b.loading&&b.records.size==1}==true}.recycleBin!!
                val record=bin.records.single()
                vm.recycleBin.select(bin.session,saved.id)
                vm.recycleBin.requestRestore(bin.session,saved.id,record.generation)
                val restore=vm.state.value.recycleBin!!.confirmation!!
                vm.recycleBin.confirm(bin.session,restore.id)
                await{it.entries.size==1&&it.recycleBin?.confirmation==null}
                val restored=vm.mixedCapture.recentEntries(day).single()
                assertEquals(saved.id,restored.id);assertEquals(saved.originalText,restored.originalText)
                assertEquals(ProcessingPolicy.AI_NONE,restored.policy)
                assertArrayEquals(MixedCaptureSqlTest.png,f.media.read(image.intent.id))
                assertEquals("继续输入的下一份草稿",store.captureRecords.current().text)
            } finally {vm.clearForHostTest();assertTrue(original.closed.await(5,TimeUnit.SECONDS))}
        }
    }

    @Test fun lostReadbackShowsNoUnconfirmedRecordAndExplicitRetryPublishesOneOriginalIdentity():Unit=runBlocking {
        MixedCaptureControllerTest.Harness().use { h ->
            h.start();h.c.edit("合成保存确认文字");h.drain();h.add()
            val id=h.ui.draft!!.id;h.failEntries=true;h.c.save();h.drain()
            assertTrue(h.ui.uncertainSave);assertTrue(h.c.recentEntries().isEmpty())
            assertEquals(1,h.f.db.read("SELECT id FROM entries").size)
            h.c.save();h.drain()
            assertEquals(id,h.c.recentEntries().single().id)
            assertEquals("合成保存确认文字",h.c.recentEntries().single().originalText)
            assertEquals(1,h.f.db.read("SELECT id FROM entries").size)
            h.c.edit("下一份草稿");h.drain()
            assertEquals(id,h.c.recentEntries().single().id);assertEquals("下一份草稿",h.ui.text)
        }
    }

    @Test fun todayHonorsDstHalfOpenBoundsSourceOrderLimitCurrentVersionAndUnavailableOwner():Unit=runBlocking {
        MixedCaptureControllerTest.Harness().use { h ->
            h.start();val zone=ZoneId.of("America/New_York")
            for((date,hours) in listOf(LocalDate.of(2026,3,8) to 23L,LocalDate.of(2026,11,1) to 25L)) {
                val day=CaptureDay(date,zone);val start=date.atStartOfDay(zone).toInstant().toEpochMilli()
                val end=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                assertEquals(hours*3_600_000,end-start)
                fun entry(id:String,at:Long,text:String="合成原文")=StoredEntry(id,1,text,at,"Pacific/Auckland",ProcessingPolicy.LOCAL_ONLY)
                val wanted=listOf(entry("latest",end-1,""))+List(5){entry("tie-$it",start+1)}+entry("boundary",start)
                h.local=h.local.copy(entries=listOf(entry("tomorrow",end))+wanted+entry("yesterday",start-1))
                assertEquals(wanted.take(5),h.c.recentEntries(day))
                h.local=h.local.copy(entries=listOf(wanted.first().copy(currentRevision=2,originalText="合成当前原文")))
                assertEquals(2,h.c.recentEntries(day).single().currentRevision)
                h.local=h.local.copy(entries=listOf(entry("boundary",start)))
                assertEquals("boundary",h.c.recentEntries(day).single().id)
                h.local=h.local.copy(opening=true);assertTrue(h.c.recentEntries(day).isEmpty())
                h.local=h.local.copy(opening=false,error="合成本机读取失败");assertTrue(h.c.recentEntries(day).isEmpty())
                h.local=h.local.copy(error=null,ready=false);assertTrue(h.c.recentEntries(day).isEmpty())
                h.local=h.local.copy(ready=true,entries=emptyList());assertTrue(h.c.recentEntries(day).isEmpty())
            }
            h.c.dispose();assertTrue(h.c.recentEntries().isEmpty())
        }
    }
}
