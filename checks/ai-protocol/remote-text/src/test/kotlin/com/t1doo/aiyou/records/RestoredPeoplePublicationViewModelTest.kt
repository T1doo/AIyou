package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual package authentication/restore, VM serial queue and encrypted people SQL on an owned host fixture.
 * Direct-reading assertions use the exact state consumed by the reader; Compose/Android are not executed.
 */
class RestoredPeoplePublicationViewModelTest {
    @Test(timeout=20_000) fun restoreDonePublishesPeopleForDirectReadingWithoutSearchOrOpeningTheEditor():Unit=Harness().use{h->runBlocking{
        h.prepareRestore();h.confirmRestore();val restored=h.done()
        assertEquals(listOf(NAME),h.people.read(restored).names)
        val direct=h.closePackageAndBrowseToReader(restored)
        assertTrue(direct.manualPeopleAvailable)
        assertEquals("Restore DONE must publish the names consumed by direct reading",listOf(NAME),direct.manualPeople[restored]?.names)
        assertNull(direct.manualPeopleEditor)
        assertEquals(2,h.vm.state.value.entries.size)
    }}

    @Test(timeout=20_000) fun restorePeopleReadFailureShowsUnavailableWhileTheCommittedReceiptStaysSuccessful():Unit=Harness().use{h->runBlocking{
        h.prepareRestore();h.failPeopleRead=true;h.confirmRestore();val restored=h.done()
        assertEquals(listOf(NAME),h.people.read(restored).names)
        val direct=h.closePackageAndBrowseToReader(restored)
        assertFalse("A failed people read must not be published as an available empty association",direct.manualPeopleAvailable)
        assertNotNull(direct.manualPeopleError)
        assertTrue(direct.manualPeople.isEmpty())
        assertEquals(2,h.vm.state.value.entries.size)
        // Explicitly authenticate/confirm the same package. Its durable receipt owns the original IDs.
        val ids=h.vm.state.value.entries.map{it.id}.toSet();h.failPeopleRead=false
        h.prepareRestore();h.confirmRestore();assertEquals(restored,h.done())
        val recovered=h.closePackageAndBrowseToReader(restored)
        assertTrue(recovered.manualPeopleAvailable);assertEquals(listOf(NAME),recovered.manualPeople[restored]?.names)
        assertEquals(ids,h.vm.state.value.entries.map{it.id}.toSet())
        assertEquals(1L,h.people.read(restored).version)
    }}

    @Test(timeout=20_000) fun lateRestorePublicationDoesNotReauthorizeQueuedOldPeopleEditorAfterSourceAba():Unit=Harness().use{h->runBlocking{
        h.prepareRestore();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        h.afterRestore={entered.countDown();check(release.await(5,TimeUnit.SECONDS))};h.confirmRestore()
        var old:ManualPeopleEditor?=null
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val panel=checkNotNull(h.vm.state.value.recordPackage);h.vm.recordPackages.close(panel.session)
            h.vm.people.open(h.id,1);old=checkNotNull(h.vm.state.value.manualPeopleEditor)
            assertTrue(old.busy);assertNull(old.snapshot);assertEquals(1L,old.sourceGeneration)
            h.independentSourceAba()
        }finally{release.countDown()}
        val held=checkNotNull(old)
        val state=h.await{it.entries.size==2&&it.localProcessingVersions[h.id]?.generation==3L&&it.manualPeopleEditor?.let{e->!e.busy&&e.conflict}==true}
        val editor=checkNotNull(state.manualPeopleEditor);assertEquals(held.session,editor.session)
        assertEquals(1L,editor.sourceGeneration);assertNull(editor.snapshot)
        h.vm.people.add(held.session,0,"旧来源的迟到输入")
        assertTrue(h.peopleWrites.isEmpty());assertEquals(listOf(NAME),h.people.read(h.id).names)
        val restored=state.entries.single{it.id!=h.id}.id;assertEquals(listOf(NAME),h.people.read(restored).names)
        h.vm.people.close(held.session);h.vm.people.open(h.id,1)
        val fresh=checkNotNull(h.await{it.manualPeopleEditor?.let{e->!e.busy&&e.snapshot!=null}==true}.manualPeopleEditor)
        assertEquals(3L,fresh.sourceGeneration)
        h.vm.people.add(fresh.session,1,"新会话明确新增")
        h.await{it.manualPeopleEditor?.notice!=null&&!it.manualPeopleEditor.busy}
        assertEquals(listOf(NAME,"新会话明确新增"),h.people.read(h.id).names)
        assertEquals(listOf(NAME),h.people.read(restored).names);assertEquals(1,h.peopleWrites.size)
    }}

    private class Harness:AutoCloseable {
        val f=PermanentErasureFixture(ManualPeopleFixture.KEY)
        val id=UUID.randomUUID().toString()
        val people:SqlManualPeopleRepository
        private val originals:SqlOriginalExportRepository
        private val packages:SqlRecordPackageRepository
        private val media:SqlRecordMediaPackageRepository
        val search:SqlLocalSearchRepository
        private val bytes:ByteArray
        @Volatile var failPeopleRead=false
        @Volatile var afterRestore:(()->Unit)?=null
        val peopleReads=AtomicInteger()
        val peopleWrites=CopyOnWriteArrayList<String>()
        init{
            f.db.transaction{sql->
                (RecordFavoriteSqlSchema.migration8To9+LocalCandidateSqlSchema.migration9To10+
                    RecordEventDateSqlSchema.migration10To11+ManualPeopleSqlSchema.migration11To12).forEach{sql.execute(it)}
                sql.execute("PRAGMA user_version=12")
                sql.execute("INSERT INTO entries(id,currentRevision,capturedAtMillis,captureTimeZone,privacyPolicy) VALUES(?,1,5,'UTC','LOCAL_ONLY')",listOf(id))
                sql.execute("INSERT INTO entry_revisions VALUES(?,1,?,?,5)",listOf(id,TEXT,OriginalExports.hash(TEXT)))
                sql.execute("INSERT INTO remote_source_access VALUES(?,1,0,NULL,NULL)",listOf(id))
                sql.execute("INSERT INTO local_jobs VALUES(?,?,1,'LOCAL_INDEX','PENDING')",listOf("INDEX:"+id,id))
            }
            people=SqlManualPeopleRepository(f.gateway,ManualPeopleFixture.KEY)
            people.replace(ManualPeopleMutation(people.read(id),listOf(NAME),UUID.randomUUID().toString()))
            originals=SqlOriginalExportRepository(f.gateway,f.tags,people=people)
            packages=SqlRecordPackageRepository(f.gateway,f.tags,people=people)
            media=SqlRecordMediaPackageRepository(f.gateway,originals,f.media,packages)
            search=SqlLocalSearchRepository(f.gateway,f.tags,people=people)
            bytes=runBlocking{
                val snapshot=originals.capture(listOf(SourceVersion(id,1)),UUID.randomUUID().toString(),50)
                val prepared=media.seal(media.preview(snapshot),PASSWORD.toCharArray())
                try{ByteArrayOutputStream().use{out->prepared.writeTo(out){true};out.toByteArray()}}finally{prepared.release()}
            }
        }
        private val peoplePort=object:ManualPeopleRepository by people {
            override fun snapshots():Map<String,ManualPeopleSnapshot>{peopleReads.incrementAndGet();if(failPeopleRead)throw ManualPeopleStorageFailure();return people.snapshots()}
            override fun replace(mutation:ManualPeopleMutation):ManualPeopleSnapshot{peopleWrites+=mutation.mutationId;return people.replace(mutation)}
        }
        private val mediaPort=object:RecordMediaPackageRepository by media {
            override suspend fun restore(verified:VerifiedRecordPackage,newIds:List<String>,importedAtMillis:Long):RecordPackageRestoreResult {
                val result=media.restore(verified,newIds,importedAtMillis);afterRestore?.invoke();return result
            }
        }
        private val original=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)){}
        private val store=object:EntryRepository by original,AutoCloseable {
            override val manualPeople=peoplePort
            override val manualTags=f.tags
            override val favorites=SqlRecordFavoriteRepository(f.gateway)
            override val eventDates=SqlRecordEventDateRepository(f.gateway)
            override val originalExport=originals
            override val recordPackages=packages
            override val mediaPackages=mediaPort
            override val localSearch=search
            override val localProcessing=SqlLocalProcessingRepository(f.gateway)
            override suspend fun prepare(){check(f.db.read("PRAGMA user_version").single().single()==12L)}
            override fun close()=original.close()
        }
        val vm=LocalRecordsViewModel(Application(),{store})
        suspend fun await(test:(LocalRecordUiState)->Boolean)=withTimeout(8_000){vm.state.first(test)}
        suspend fun prepareRestore(){
            await{it.ready&&!it.opening};val c=vm.recordPackages;c.open();var ui=checkNotNull(vm.state.value.recordPackage)
            c.chooseRestore(ui.session);ui=checkNotNull(vm.state.value.recordPackage)
            val key=checkNotNull(ui.requestKey);check(c.claimPicker(ui.session,key))
            c.picked(ui.session,key,RecordPackageControllerTest.Document(bytes));c.authenticate(ui.session,PASSWORD.toCharArray())
            await{it.recordPackage?.phase==RecordPackagePhase.RESTORE_PREVIEW}
        }
        fun confirmRestore(){val ui=checkNotNull(vm.state.value.recordPackage);val pack=checkNotNull(ui.verified)
            vm.recordPackages.confirmRestore(ui.session,pack.manifest.backupId,pack.digest,ui.epoch)}
        suspend fun done():String {
            val state=await{it.recordPackage?.phase==RecordPackagePhase.DONE&&it.entries.size==2}
            val restored=state.entries.single{it.id!=id}.id
            await{it.localProcessingVersions.containsKey(restored)};return restored
        }
        fun closePackageAndBrowseToReader(restored:String):LocalRecordUiState {
            val panel=checkNotNull(vm.state.value.recordPackage);assertEquals(RecordPackagePhase.DONE,panel.phase)
            vm.recordPackages.close(panel.session)
            val invalidations=AtomicInteger()
            val browse=LocalOriginalSearchController({vm.state.value},{OriginalSearchInput()},Dispatchers.IO,
                matcher={entries,input->LocalOriginalSearchMatcher.indexed(entries,input,search)},onInvalidated={invalidations.incrementAndGet()})
            try{browse.sync(true);assertEquals(OriginalSearchStage.BROWSE,browse.state.value.stage);assertEquals(0,invalidations.get());browse.pause()}
            finally{browse.close()}
            return vm.state.value.also{assertTrue(it.ready);assertNull(it.error);assertNull(it.recordPackage)
                assertTrue(it.entries.any{e->e.id==restored});assertNull(it.manualPeopleEditor)}
        }
        fun independentSourceAba(){f.second().use{other->
            val port=SqlLocalProcessingRepository(other);val before=port.read(SourceVersion(id,1))
            val middle=port.change(LocalProcessingChange(before,LocalProcessingChoice.AI_NONE,UUID.randomUUID().toString()))
            val after=port.change(LocalProcessingChange(middle,LocalProcessingChoice.LOCAL_ONLY,UUID.randomUUID().toString()))
            assertEquals(1L,before.generation);assertEquals(before.copy(generation=3),after)
        }}
        override fun close(){afterRestore=null;failPeopleRead=false;vm.clearForHostTest();check(original.closed.await(5,TimeUnit.SECONDS))
            bytes.fill(0);people.wipeKey();f.close()}
    }
    companion object {
        private const val NAME="合成人物恢复核对"
        private const val TEXT="合成待恢复原文🙂"
        private const val PASSWORD="synthetic restored people publication"
    }
}
