package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Reading-button arguments -> actual controllers/VM queue -> product SQL on an owned synthetic DB.
 * The held-set cases use an independent SQL owner; the failed-retry cases use the actual recycle controller.
 * No Compose, Android, or device execution is claimed.
 */
class MetadataSourceGenerationViewModelTest {
    @Test(timeout=15_000) fun favoriteQueuedBeforeIndependentTrashRestoreCannotFirstCommitOnRestoredSource(): Unit = Harness().use { h -> runBlocking {
        h.ready();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        h.holdFavorite={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        h.favorite(true)
        try {assertTrue(entered.await(5,TimeUnit.SECONDS));h.independentTrashRestore()} finally {release.countDown()}
        h.await{it.favoriteWrites[h.id]?.busy==false || it.favoriteNotice!=null};h.holdFavorite=null
        assertFalse("Old queued favorite must not first commit after source generation changed",h.f.base.favorites.read(h.id).isFavorite)
        assertTrue(h.vm.state.value.favoriteWrites.getValue(h.id).conflict)
        h.vm.favorites.retry(h.id);h.await{it.favoritesAvailable&&it.favoriteWrites.isEmpty()};h.refresh()
        h.favorite(true);h.await{it.favoriteNotice!=null&&it.favoriteWrites.isEmpty()}
        assertTrue(h.f.base.favorites.read(h.id).isFavorite)
    } }

    @Test(timeout=15_000) fun favoriteFailedBeforeCommitThenActualRecycleRestoreRetryCannotFirstCommitOldIntent(): Unit = Harness().use { h -> runBlocking {
        h.ready();h.failFavorite=true;h.favorite(true)
        h.await{it.favoriteWrites[h.id]?.let{w->!w.busy&&w.error!=null}==true}
        val pending=h.vm.state.value.favoriteWrites.getValue(h.id).intent
        assertFalse(h.f.base.favorites.read(h.id).isFavorite)
        h.failFavorite=false;h.actualTrashRestore()
        assertSame(pending,h.vm.state.value.favoriteWrites.getValue(h.id).intent)
        h.vm.favorites.retry(h.id);h.await{it.favoriteWrites[h.id]?.busy==false || it.favoriteNotice!=null}
        assertFalse("Retry of uncommitted old intent must not write after actual UI recycle/restore",h.f.base.favorites.read(h.id).isFavorite)
        assertTrue(h.vm.state.value.favoriteWrites.getValue(h.id).conflict)
        assertEquals(2,h.favoriteCalls.size);assertSame(h.favoriteCalls[0],h.favoriteCalls[1])
        h.vm.favorites.retry(h.id);h.await{it.favoritesAvailable&&it.favoriteWrites.isEmpty()};h.refresh()
        h.favorite(true);h.await{it.favoriteNotice!=null&&it.favoriteWrites.isEmpty()}
        assertTrue(h.f.base.favorites.read(h.id).isFavorite)
    } }

    @Test(timeout=15_000) fun dateQueuedBeforeIndependentTrashRestoreCannotFirstCommitOnRestoredSource(): Unit = Harness().use { h -> runBlocking {
        h.ready();val editor=h.openDate();h.prepareDate(editor)
        val preview=checkNotNull(h.vm.state.value.eventDateEditor?.preview)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        h.holdDate={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        h.vm.eventDates.confirm(editor.session,preview.id)
        try {assertTrue(entered.await(5,TimeUnit.SECONDS));h.independentTrashRestore()} finally {release.countDown()}
        h.await{it.eventDateWrites[h.id]?.busy==false || it.eventDateEditor?.notice!=null};h.holdDate=null
        assertNull("Old queued date must not first commit after source generation changed",h.f.base.dates.read(h.id).date)
        assertTrue(h.vm.state.value.eventDateWrites.getValue(h.id).conflict)
        h.vm.eventDates.close(editor.session);h.refresh()
        val fresh=h.openDate();h.vm.eventDates.retry(fresh.session)
        h.await{it.eventDatesAvailable&&it.eventDateWrites.isEmpty()&&!checkNotNull(it.eventDateEditor).busy}
        h.saveDate(checkNotNull(h.vm.state.value.eventDateEditor));assertEquals(DAY,h.f.base.dates.read(h.id).date)
    } }

    @Test(timeout=15_000) fun dateFailedBeforeCommitCloseActualRecycleRestoreReopenRetryCannotFirstCommitOldIntent(): Unit = Harness().use { h -> runBlocking {
        h.ready();val editor=h.openDate();h.prepareDate(editor);h.failDate=true
        h.vm.eventDates.confirm(editor.session,checkNotNull(h.vm.state.value.eventDateEditor?.preview).id)
        h.await{it.eventDateWrites[h.id]?.let{w->!w.busy&&w.error!=null}==true}
        val pending=h.vm.state.value.eventDateWrites.getValue(h.id).intent
        assertNull(h.f.base.dates.read(h.id).date)
        h.vm.eventDates.close(editor.session);h.failDate=false;h.actualTrashRestore()
        val reopened=h.openDate();assertSame(pending,h.vm.state.value.eventDateWrites.getValue(h.id).intent)
        h.vm.eventDates.retry(reopened.session)
        h.await{it.eventDateWrites[h.id]?.busy==false || it.eventDateEditor?.notice!=null}
        assertNull("Reopened editor must not first commit a pre-delete intent",h.f.base.dates.read(h.id).date)
        assertTrue(h.vm.state.value.eventDateWrites.getValue(h.id).conflict)
        assertEquals(2,h.dateCalls.size);assertSame(h.dateCalls[0],h.dateCalls[1])
        h.vm.eventDates.retry(reopened.session)
        h.await{it.eventDatesAvailable&&it.eventDateWrites.isEmpty()&&!checkNotNull(it.eventDateEditor).busy}
        h.saveDate(checkNotNull(h.vm.state.value.eventDateEditor));assertEquals(DAY,h.f.base.dates.read(h.id).date)
    } }

    @Test(timeout=15_000) fun committedFavoriteWithLostAckCanBeConfirmedAfterActualTrashRestoreWithoutAnotherWrite(): Unit = Harness().use { h -> runBlocking {
        h.ready();h.loseFavoriteAck=true;h.favorite(true)
        h.await{it.favoriteWrites[h.id]?.let{w->!w.busy&&w.error!=null}==true}
        assertTrue(h.f.base.favorites.read(h.id).isFavorite)
        val pending=h.vm.state.value.favoriteWrites.getValue(h.id).intent
        h.actualTrashRestore();val epoch=h.f.search.epoch()
        h.vm.favorites.retry(h.id);h.await{it.favoriteWrites.isEmpty()&&it.favoriteNotice!=null}
        assertSame(pending,h.favoriteCalls.last());assertEquals(1L,h.f.base.favorites.read(h.id).version)
        assertEquals(epoch,h.f.search.epoch());assertTrue(h.f.base.favorites.read(h.id).isFavorite)
    } }

    @Test(timeout=15_000) fun committedDateWithLostAckCanBeConfirmedAfterCloseTrashRestoreReopenWithoutAnotherWrite(): Unit = Harness().use { h -> runBlocking {
        h.ready();val editor=h.openDate();h.prepareDate(editor);h.loseDateAck=true
        h.vm.eventDates.confirm(editor.session,checkNotNull(h.vm.state.value.eventDateEditor?.preview).id)
        h.await{it.eventDateWrites[h.id]?.let{w->!w.busy&&w.error!=null}==true}
        assertEquals(DAY,h.f.base.dates.read(h.id).date)
        val pending=h.vm.state.value.eventDateWrites.getValue(h.id).intent
        h.vm.eventDates.close(editor.session);h.actualTrashRestore();val epoch=h.f.search.epoch()
        val fresh=h.openDate();h.vm.eventDates.retry(fresh.session)
        h.await{it.eventDateWrites.isEmpty()&&it.eventDateEditor?.notice!=null}
        assertSame(pending,h.dateCalls.last());assertEquals(1L,h.f.base.dates.read(h.id).version)
        assertEquals(epoch,h.f.search.epoch());assertEquals(DAY,h.f.base.dates.read(h.id).date)
    } }

    @Test(timeout=15_000) fun renderedMetadataButtonsNeedAvailableLedgerAndCannotAdoptRestoredGeneration(): Unit = Harness().use { h -> runBlocking {
        h.ready();h.failVersions=true;h.vm.refreshLocalSearch();h.await{!it.localProcessingAvailable}
        assertNull(h.vm.state.value.metadataReadingSource(h.id,1))
        h.favorite(true);h.vm.eventDates.open(h.id,1)
        assertTrue(h.favoriteCalls.isEmpty());assertNull(h.vm.state.value.eventDateEditor)
        h.failVersions=false;h.vm.refreshLocalSearch();h.await{it.localProcessingAvailable&&it.localProcessingVersions.containsKey(h.id)}
        val rendered=checkNotNull(h.vm.state.value.metadataReadingSource(h.id,1))
        h.actualTrashRestore();val refreshed=checkNotNull(h.vm.state.value.metadataReadingSource(h.id,1))
        assertTrue(refreshed.generation>rendered.generation)
        h.vm.favorites.set(rendered,0,true);h.vm.eventDates.open(rendered)
        assertTrue(h.vm.state.value.favoriteWrites.isEmpty());assertTrue(h.favoriteCalls.isEmpty());assertNull(h.vm.state.value.eventDateEditor)
        h.vm.favorites.set(refreshed,0,true);h.await{it.favoriteNotice!=null&&it.favoriteWrites.isEmpty()}
        val date=h.openDate();h.saveDate(date)
        assertEquals(refreshed.generation,h.favoriteCalls.single().sourceGeneration)
        assertEquals(refreshed.generation,h.dateCalls.single().sourceGeneration)
    } }

    @Test(timeout=15_000) fun visibleFavoriteRereadAfterGenerationOnlyAbaAllowsANewChoiceWithoutPrivateRefresh(): Unit = Harness().use { h -> runBlocking {
        h.ready();val original=h.vm.state.value.entries
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        h.holdFavorite={entered.countDown();check(release.await(5,TimeUnit.SECONDS))};h.favorite(true)
        try{assertTrue(entered.await(5,TimeUnit.SECONDS));h.independentPolicyAba()}finally{release.countDown()}
        h.await{it.favoriteWrites[h.id]?.let{w->!w.busy&&w.conflict}==true};h.holdFavorite=null
        assertFalse(h.f.base.favorites.read(h.id).isFavorite);assertEquals(original,h.vm.state.value.entries)
        // Actual "重新读取收藏" callback. No refreshLocalSearch/private helper is used here.
        h.vm.favorites.retry(h.id);h.await{it.favoritesAvailable&&!it.favoritesLoading&&it.favoriteWrites.isEmpty()}
        h.favorite(true);h.await{it.favoriteWrites[h.id]?.busy==false || it.favoriteNotice!=null}
        assertTrue("Visible reread must refresh source authority for a new explicit favorite choice",h.f.base.favorites.read(h.id).isFavorite)
        assertEquals(3L,h.vm.state.value.localProcessingVersions.getValue(h.id).generation)
        assertEquals(1L,h.favoriteCalls.first().sourceGeneration);assertEquals(3L,h.favoriteCalls.last().sourceGeneration)
    } }

    @Test(timeout=15_000) fun visibleDateRereadAfterGenerationOnlyAbaAllowsNewPreviewWithoutPrivateRefresh(): Unit = Harness().use { h -> runBlocking {
        h.ready();val original=h.vm.state.value.entries;val editor=h.openDate();h.prepareDate(editor)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        h.holdDate={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        h.vm.eventDates.confirm(editor.session,checkNotNull(h.vm.state.value.eventDateEditor?.preview).id)
        try{assertTrue(entered.await(5,TimeUnit.SECONDS));h.independentPolicyAba()}finally{release.countDown()}
        h.await{it.eventDateWrites[h.id]?.let{w->!w.busy&&w.conflict}==true};h.holdDate=null
        assertNull(h.f.base.dates.read(h.id).date);assertEquals(original,h.vm.state.value.entries)
        // Actual "重新读取事件日期" callback; a new preview+confirmation is still required.
        h.vm.eventDates.retry(editor.session)
        val loaded=checkNotNull(h.await{it.eventDateWrites.isEmpty()&&it.eventDateEditor?.let{e->!e.busy&&e.snapshot!=null}==true}.eventDateEditor)
        // The old editor keeps its immutable authority. Use its visible close/open actions, then re-enter and preview.
        h.vm.eventDates.close(loaded.session);val reopened=h.openDate()
        h.prepareDate(reopened);h.vm.eventDates.confirm(reopened.session,checkNotNull(h.vm.state.value.eventDateEditor?.preview).id)
        h.await{it.eventDateWrites[h.id]?.busy==false || it.eventDateEditor?.notice!=null}
        assertEquals("Visible reread must refresh source authority before the new date confirmation",DAY,h.f.base.dates.read(h.id).date)
        assertEquals(3L,h.vm.state.value.localProcessingVersions.getValue(h.id).generation)
        assertEquals(1L,h.dateCalls.first().sourceGeneration);assertEquals(3L,h.dateCalls.last().sourceGeneration)
    } }

    private class Harness:AutoCloseable {
        val f=ManualPeopleFixture();val id=f.base.entry()
        @Volatile var failFavorite=false
        @Volatile var failDate=false
        @Volatile var loseFavoriteAck=false
        @Volatile var loseDateAck=false
        @Volatile var failVersions=false
        @Volatile var holdFavorite:(()->Unit)?=null
        @Volatile var holdDate:(()->Unit)?=null
        val favoriteCalls=CopyOnWriteArrayList<RecordFavoriteMutation>()
        val dateCalls=CopyOnWriteArrayList<RecordEventDateMutation>()
        private val favorites=object:RecordFavoriteRepository by f.base.favorites {
            override fun set(mutation:RecordFavoriteMutation):RecordFavoriteSnapshot {
                favoriteCalls+=mutation;holdFavorite?.invoke();if(failFavorite)throw RecordFavoriteStorageFailure()
                val result=f.base.favorites.set(mutation)
                if(loseFavoriteAck){loseFavoriteAck=false;throw RecordFavoriteStorageFailure()}
                return result
            }
        }
        private val dates=object:RecordEventDateRepository by f.base.dates {
            override fun set(mutation:RecordEventDateMutation):RecordEventDateSnapshot {
                dateCalls+=mutation;holdDate?.invoke();if(failDate)throw RecordEventDateStorageFailure()
                val result=f.base.dates.set(mutation)
                if(loseDateAck){loseDateAck=false;throw RecordEventDateStorageFailure()}
                return result
            }
        }
        private val actualProcessing=SqlLocalProcessingRepository(f.base.gateway)
        private val processing=object:LocalProcessingRepository by actualProcessing {
            override fun versions():Map<String,LocalProcessingVersion>{if(failVersions)throw LocalProcessingStorageFailure();return actualProcessing.versions()}
        }
        private val bin=SqlRecycleBinRepository(f.base.gateway,f.base.tags)
        private val original=SqliteVmEntryRepository(f.base.db,f.base.gateway,RemoteTextTransactions(f.base.gateway)){}
        private val store=object:EntryRepository by original,AutoCloseable {
            override val favorites=this@Harness.favorites
            override val eventDates=dates
            override val recycleBin=bin
            override val manualTags=f.base.tags
            override val localSearch=f.search
            override val localProcessing=processing
            override suspend fun prepare(){check(f.base.db.read("PRAGMA user_version").single().single()==12L)}
            override fun close()=original.close()
        }
        val vm=LocalRecordsViewModel(Application(),{store})
        suspend fun await(test:(LocalRecordUiState)->Boolean)=withTimeout(5_000){vm.state.first(test)}
        suspend fun ready()=await{it.ready&&!it.opening}
        fun favorite(value:Boolean){val local=vm.state.value;val entry=local.entries.single()
            vm.favorites.set(id,entry.currentRevision,OriginalExports.hash(entry.originalText),local.favorites.getValue(id).version,value)}
        suspend fun openDate():RecordEventDateEditor {vm.eventDates.open(id,1);return checkNotNull(await{it.eventDateEditor?.let{e->!e.busy&&e.snapshot!=null}==true}.eventDateEditor)}
        fun prepareDate(editor:RecordEventDateEditor){vm.eventDates.edit(editor.session,DAY.localDate,DAY.timeZone);vm.eventDates.preview(editor.session)}
        suspend fun saveDate(editor:RecordEventDateEditor){prepareDate(editor);vm.eventDates.confirm(editor.session,checkNotNull(vm.state.value.eventDateEditor?.preview).id)
            await{it.eventDateEditor?.notice!=null&&it.eventDateWrites.isEmpty()}}
        suspend fun refresh(){vm.refreshLocalSearch();await{it.localProcessingVersions[id]?.generation==3L}}
        fun independentTrashRestore(){f.base.second().use{other->val tags=SqlManualTagRepository(other,ManualPeopleFixture.KEY)
            try{val otherBin=SqlRecycleBinRepository(other,tags);val before=otherBin.read(id);val source=SourceVersion(id,1)
                val trashed=otherBin.apply(RecycleMutation(source,before.generation,false,UUID.randomUUID().toString()))
                assertEquals(3L,otherBin.apply(RecycleMutation(source,trashed.generation,true,UUID.randomUUID().toString())).generation)
            }finally{tags.wipeKey()}}}
        fun independentPolicyAba(){f.base.second().use{other->
            val port=SqlLocalProcessingRepository(other);val before=port.read(SourceVersion(id,1))
            val middle=port.change(LocalProcessingChange(before,LocalProcessingChoice.AI_NONE,UUID.randomUUID().toString()))
            val after=port.change(LocalProcessingChange(middle,LocalProcessingChoice.LOCAL_ONLY,UUID.randomUUID().toString()))
            assertEquals(1L,before.generation);assertEquals(3L,after.generation)
            assertEquals(before.copy(generation=3),after)
        }}
        suspend fun actualTrashRestore(){
            assertEquals(1L,bin.read(id).generation)
            vm.recycleBin.requestMove(id,1);var ui=checkNotNull(await{it.recycleBin?.confirmation!=null&&!it.recycleBin.busy}.recycleBin)
            vm.recycleBin.confirm(ui.session,checkNotNull(ui.confirmation).id)
            await{it.recycleBin==null&&it.entries.none{e->e.id==id}}
            vm.recycleBin.open();ui=checkNotNull(await{it.recycleBin?.let{b->!b.loading&&b.records.size==1}==true}.recycleBin)
            vm.recycleBin.select(ui.session,id);vm.recycleBin.requestRestore(ui.session,id,ui.records.single().generation)
            ui=checkNotNull(vm.state.value.recycleBin);vm.recycleBin.confirm(ui.session,checkNotNull(ui.confirmation).id)
            await{it.recycleBin?.notice!=null&&it.recycleBin.confirmation==null&&it.entries.any{e->e.id==id}}
            vm.recycleBin.close(ui.session);await{it.localProcessingVersions[id]?.generation==3L}
            assertEquals(3L,bin.read(id).generation);assertEquals(1,vm.state.value.entries.single().currentRevision)
        }
        override fun close(){holdFavorite=null;holdDate=null;failFavorite=false;failDate=false
            vm.clearForHostTest();check(original.closed.await(5,TimeUnit.SECONDS));f.close()}
    }
    companion object {private val DAY=RecordEventDate("2024-02-29","UTC")}
}
