package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual controller/VM queue and actual migrated SQLite; no Room/Keystore/device claim. */
class RecordFavoriteControllerTest {
    @Test fun explicitChoiceRefreshesActualOriginalAndEpochWithoutOptimisticFlag(): Unit = Harness().use { h ->
        val epoch=h.state.localSearchEpoch
        h.choose(true)
        assertFalse(h.state.favorites.getValue(h.id).isFavorite)
        assertTrue(h.state.favoriteWrites.getValue(h.id).busy)
        h.drain()
        assertTrue(h.state.favorites.getValue(h.id).isFavorite)
        assertEquals(epoch!!+1,h.state.localSearchEpoch)
        assertEquals(h.f.current(h.id),h.state.entries.single())
        assertTrue(h.state.favoriteWrites.isEmpty());assertNotNull(h.state.favoriteNotice)
    }

    @Test fun lostCommitAcknowledgementAndReadFailureKeepOneStableRetryUntilConfirmed(): Unit = Harness().use { h ->
        var lose=true;var failRead=false;val calls=mutableListOf<RecordFavoriteMutation>()
        val actual=h.f.favorites
        h.port=object: RecordFavoriteRepository by actual {
            override fun set(mutation:RecordFavoriteMutation):RecordFavoriteSnapshot {
                calls+=mutation;val saved=actual.set(mutation)
                if(lose){lose=false;error("Synthetic committed acknowledgement loss")};return saved
            }
            override fun snapshots():Map<String,RecordFavoriteSnapshot> {
                if(failRead)error("Synthetic metadata read failure");return actual.snapshots()
            }
        }
        h.choose(true);h.drain()
        assertTrue(actual.read(h.id).isFavorite);assertNull(h.state.favoriteNotice)
        assertFalse(h.state.favoritesAvailable);val intent=h.state.favoriteWrites.getValue(h.id).intent
        h.controller.refresh();h.drain()
        assertTrue(h.state.favoritesAvailable);assertEquals(intent,h.state.favoriteWrites.getValue(h.id).intent)
        failRead=true;h.controller.retry(h.id);h.drain()
        assertFalse(h.state.favoritesAvailable);assertEquals(1L,actual.read(h.id).version)
        assertEquals(intent,h.state.favoriteWrites.getValue(h.id).intent)
        failRead=false;h.controller.retry(h.id);h.drain()
        assertTrue(h.state.favoriteWrites.isEmpty());assertNotNull(h.state.favoriteNotice)
        assertEquals(3,calls.size);assertTrue(calls.all{it==intent});assertEquals(1L,actual.read(h.id).version)
    }

    @Test fun staleReaderCallbacksCannotOverwriteNewSourceOrLaterFavoriteChoice(): Unit = Harness().use { h ->
        val old=h.state.entries.single();val hash=OriginalExports.hash(old.originalText)
        h.f.revise(h.id);h.controller.refresh();h.drain()
        h.controller.set(h.id,1,hash,0,true);assertTrue(h.queue.isEmpty())
        h.choose(true);h.drain()
        val current=h.state.entries.single()
        h.controller.set(h.id,current.currentRevision,OriginalExports.hash(current.originalText),0,false)
        assertTrue(h.queue.isEmpty());assertTrue(h.f.favorites.read(h.id).isFavorite)
        h.controller.set(h.id,current.currentRevision,"0".repeat(64),1,false)
        assertTrue(h.queue.isEmpty())
    }

    @Test fun independentOwnerConflictRequiresRefreshAndANewExplicitChoice(): Unit = Harness().use { h ->
        h.choose(true);h.f.favorites.set(h.f.intent(h.id,false));h.drain()
        assertTrue(h.state.favoriteWrites.getValue(h.id).conflict);assertNull(h.state.favoriteNotice)
        h.controller.retry(h.id);h.drain()
        assertTrue(h.state.favoriteWrites.isEmpty());assertFalse(h.state.favorites.getValue(h.id).isFavorite)
        assertEquals(1L,h.state.favorites.getValue(h.id).version)
        h.choose(true);h.drain();assertTrue(h.f.favorites.read(h.id).isFavorite)
        assertEquals(2L,h.f.favorites.read(h.id).version)
    }

    @Test fun retiredControllerCannotExecuteQueuedWorkOrPublishAfterActualCommit(): Unit = Harness().use { h ->
        h.choose(true);h.controller.dispose();h.drain()
        assertFalse(h.f.favorites.read(h.id).isFavorite);assertTrue(h.state.favoriteControllerRetired)
        h.controller.refresh();h.choose(true);assertTrue(h.queue.isEmpty())
        Harness().use { second ->
            val actual=second.f.favorites
            second.port=object:RecordFavoriteRepository by actual {
                override fun set(mutation:RecordFavoriteMutation):RecordFavoriteSnapshot {
                    val saved=actual.set(mutation);second.controller.dispose();return saved
                }
            }
            second.choose(true);second.drain()
            assertTrue(actual.read(second.id).isFavorite);assertNull(second.state.favoriteNotice)
            assertFalse(second.state.favorites.getValue(second.id).isFavorite)
        }
    }

    @Test fun rejectedQueueAndUnavailableReadPreserveOriginalWithoutFalseSuccess(): Unit = Harness().use { h ->
        h.accept=false;h.choose(true)
        assertFalse(h.state.favoriteWrites.getValue(h.id).busy);assertFalse(h.state.favoritesAvailable)
        assertFalse(h.f.favorites.read(h.id).isFavorite);assertNull(h.state.favoriteNotice)
        h.accept=true
        h.port=object:RecordFavoriteRepository by h.f.favorites {
            override fun snapshots():Map<String,RecordFavoriteSnapshot> = throw RecordFavoriteTooBroad()
        }
        h.controller.refresh();h.drain()
        assertFalse(h.state.favoritesAvailable);assertNotNull(h.state.favoritesError)
        assertEquals(h.f.current(h.id),h.state.entries.single())
        assertTrue(h.state.favoriteWrites.containsKey(h.id))
    }

    @Test fun actualVmPublishesPersistedFavoriteAcrossDrainedColdOwners(): Unit = runBlocking {
        RecordFavoriteFixture().use { f ->
            val id=f.entry()
            Owner(f).use { owner ->
                owner.ready();val source=owner.vm.state.value.entries.single()
                assertTrue(owner.vm.state.value.supportsFavorites)
                owner.vm.favorites.set(id,source.currentRevision,OriginalExports.hash(source.originalText),0,true)
                withTimeout(10_000){owner.vm.state.first{it.favoriteNotice!=null&&it.favoriteWrites.isEmpty()}}
                assertTrue(owner.vm.state.value.favorites.getValue(id).isFavorite)
                assertNotSame(Thread.currentThread(),owner.writeThread.get())
            }
            f.base.reopen()
            Owner(f).use { cold ->
                cold.ready();assertTrue(cold.vm.state.value.favorites.getValue(id).isFavorite)
                assertEquals(1L,cold.vm.state.value.favorites.getValue(id).version)
                assertEquals(f.current(id),cold.vm.state.value.entries.single())
                assertTrue(cold.vm.state.value.favoriteWrites.isEmpty())
            }
        }
    }

    @Test fun actualVmFreshSavePublishesFavoriteDefaultAndCanImmediatelyFavorite(): Unit = runBlocking {
        RecordFavoriteFixture().use { f -> Owner(f).use { owner ->
            owner.ready();owner.vm.changeText("合成首次保存原文")
            withTimeout(10_000){owner.vm.state.first{it.draftNotice=="草稿已保存在本机"}}
            owner.vm.save()
            val saved=withTimeout(10_000){owner.vm.state.first{it.saveNotice!=null&&!it.saving&&!it.savePending}}
            assertTrue(saved.favoritesAvailable);val record=saved.entries.single()
            assertEquals(0L,saved.favorites.getValue(record.id).version)
            owner.vm.favorites.set(record.id,record.currentRevision,OriginalExports.hash(record.originalText),0,true)
            withTimeout(10_000){owner.vm.state.first{it.favoriteNotice!=null}}
            assertTrue(f.favorites.read(record.id).isFavorite)
        } }
    }

    @Test fun actualVmFavoriteReadFailureDoesNotChangeAcknowledgedSaveAndExplicitRefreshRecovers(): Unit = runBlocking {
        RecordFavoriteFixture().use { f -> Owner(f).use { owner ->
            owner.ready();owner.failMetadata=true;owner.vm.changeText("合成已保存原文")
            withTimeout(10_000){owner.vm.state.first{it.draftNotice=="草稿已保存在本机"}}
            owner.vm.save()
            val saved=withTimeout(10_000){owner.vm.state.first{it.saveNotice!=null&&!it.saving&&!it.savePending}}
            assertNull(saved.error);assertFalse(saved.favoritesAvailable);assertNotNull(saved.favoritesError)
            assertEquals(f.current(saved.entries.single().id),saved.entries.single())
            owner.failMetadata=false;owner.vm.favorites.refresh()
            withTimeout(10_000){owner.vm.state.first{it.favoritesAvailable&&!it.favoritesLoading}}
            assertFalse(owner.vm.state.value.favorites.getValue(saved.entries.single().id).isFavorite)
        } }
    }

    @Test fun actualVmColdTrashRestoreRefreshesPersistedFavorite(): Unit = runBlocking {
        RecordFavoriteFixture().use { f ->
            val id=f.entry();f.favorites.set(f.intent(id,true));val trash=f.base.bin.read(id)
            f.base.bin.apply(RecycleMutation(SourceVersion(id,1),trash.generation,false,"synthetic-trash"))
            Owner(f).use { owner ->
                owner.ready();assertFalse(owner.vm.state.value.favorites.containsKey(id))
                owner.vm.recycleBin.open()
                withTimeout(10_000){owner.vm.state.first{it.recycleBin?.loading==false}}
                var bin=owner.vm.state.value.recycleBin!!
                owner.vm.recycleBin.select(bin.session,id)
                owner.vm.recycleBin.requestRestore(bin.session,id,bin.records.single().generation)
                bin=owner.vm.state.value.recycleBin!!
                owner.vm.recycleBin.confirm(bin.session,bin.confirmation!!.id)
                withTimeout(10_000){owner.vm.state.first{it.recycleBin?.notice!=null&&it.recycleBin.confirmation==null}}
                assertTrue(owner.vm.state.value.favoritesAvailable)
                assertTrue(owner.vm.state.value.favorites.getValue(id).isFavorite)
            }
        }
    }

    @Test fun actualRecycleCommitKeepsUncertainFavoriteThroughTrashAndRemovesOnlyDoneTarget(): Unit = Harness().use { h ->
        val other=h.f.entry();h.state=h.state.copy(entries=listOf(h.f.current(h.id),h.f.current(other)),
            favorites=h.f.favorites.snapshots(),supportsRecycleBin=true,supportsPermanentErasure=true)
        val actual=h.f.favorites;var lose=true
        h.port=object:RecordFavoriteRepository by actual {
            override fun set(mutation:RecordFavoriteMutation):RecordFavoriteSnapshot {
                val saved=actual.set(mutation);if(lose){lose=false;error("Synthetic lost acknowledgement")};return saved
            }
        }
        val source=h.f.current(h.id)
        h.controller.set(h.id,1,OriginalExports.hash(source.originalText),0,true);h.drain()
        val held=h.state.favoriteWrites.getValue(h.id)
        val bin=RecycleBinController({h.state},{h.state=it(h.state)},{h.queue.addLast(it);true})
        try {
            bin.requestMove(h.id,1);h.drain();var ui=h.state.recycleBin!!
            bin.confirm(ui.session,ui.confirmation!!.id);h.drain()
            assertSame(held,h.state.favoriteWrites.getValue(h.id))
            assertFalse(h.state.favorites.containsKey(h.id));assertTrue(h.state.favorites.containsKey(other))
            bin.open();h.drain();ui=h.state.recycleBin!!;bin.select(ui.session,h.id)
            bin.requestErasure(ui.session,h.id,ui.records.single().generation)
            ui=h.state.recycleBin!!;bin.confirm(ui.session,ui.confirmation!!.id);h.drain()
            assertTrue(h.f.base.erase.read(h.id)!!.complete)
            assertFalse(h.state.favoriteWrites.containsKey(h.id));assertFalse(h.state.favorites.containsKey(h.id))
            assertTrue(h.state.favorites.containsKey(other));assertEquals(listOf(other),h.state.entries.map{it.id})
        } finally { bin.dispose() }
    }

    private class Harness : AutoCloseable {
        val f=RecordFavoriteFixture();val id=f.entry();var port:RecordFavoriteRepository=f.favorites
        var state=LocalRecordUiState(ready=true,opening=false,entries=listOf(f.current(id)),supportsFavorites=true,
            favorites=f.favorites.snapshots(),favoritesAvailable=true,localSearch=f.search,localSearchEpoch=f.epoch())
        val queue=ArrayDeque<suspend(EntryRepository)->Unit>();var accept=true
        private val base=SqliteVmEntryRepository(f.db,f.base.gateway,RemoteTextTransactions(f.base.gateway)){}
        private val store=object:EntryRepository by base {
            override val favorites get()=port;override val localSearch get()=f.search
            override val recycleBin get()=f.base.bin;override val permanentErasure get()=f.base.erase
        }
        val controller=RecordFavoriteController({state},{state=it(state)},{if(accept){queue.addLast(it);true}else false})
        fun choose(value:Boolean){val entry=state.entries.single();controller.set(id,entry.currentRevision,
            OriginalExports.hash(entry.originalText),state.favorites.getValue(id).version,value)}
        fun drain()=runBlocking{while(queue.isNotEmpty())queue.removeFirst()(store)}
        override fun close(){controller.dispose();f.close()}
    }

    private class Owner(f:RecordFavoriteFixture):AutoCloseable {
        private val gate=f.base.gateway;private val primary=f.db;private val tags=f.base.tags;private val tasks=f.base.tasks
        private val lifetime=DrainingRecordsStorage(gate,f.base.media,f.base.edits,{primary.close()},{tags.wipeKey();tasks.wipeKey()})
        private val closed=CountDownLatch(1);private val failure=AtomicReference<Throwable>()
        val writeThread=AtomicReference<Thread>()
        @Volatile var failMetadata=false
        private val base=SqliteVmEntryRepository(primary,gate,RemoteTextTransactions(gate)){}
        private val actual=f.favorites
        private val store=object:EntryRepository by base,AwaitableRecordStorage {
            override val favorites=object:RecordFavoriteRepository by actual {
                override fun snapshots():Map<String,RecordFavoriteSnapshot> {
                    if(failMetadata)throw RecordFavoriteStorageFailure();return actual.snapshots()
                }
                override fun set(mutation:RecordFavoriteMutation):RecordFavoriteSnapshot {
                    writeThread.set(Thread.currentThread());return actual.set(mutation)
                }
            }
            override val manualTags=tags;override val manualTasks=tasks
            override val attachments=f.base.media;override val editDrafts=f.base.edits
            override val recordPackages=f.base.packages;override val recycleBin=f.base.bin
            override val permanentErasure=f.base.erase
            override val localSearch=f.search
            override suspend fun prepare(){check(primary.read("PRAGMA user_version").single().single()==9L)}
            override fun close()=lifetime.close()
            override fun awaitClosed(){try{lifetime.awaitClosed()}catch(t:Throwable){failure.set(t);throw t}finally{closed.countDown()}}
        }
        val vm=LocalRecordsViewModel(Application(),{store})
        suspend fun ready()=withTimeout(10_000){vm.state.first{it.ready&&!it.opening}}
        override fun close(){vm.clearForHostTest();assertTrue(closed.await(5,TimeUnit.SECONDS));failure.get()?.let{throw it}}
    }
}
