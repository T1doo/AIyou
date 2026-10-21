package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.ui.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class ReminderFrontLifecycleTest {
    private fun driver(f:NativeSqlFixture)=object:ReminderRuntimeDriver {
        override fun availability()=f.available
        override fun schedule(command:TaskReminderCommand):TaskReminderPhase=error("No schedule")
        override fun cancel(command:TaskReminderCommand):TaskReminderPhase=error("No cancel")
        override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation=error("No post")
    }
    @Test fun actualControllerOrdinaryDisposeAllowsNewColdCandidateOnlyAfterConfirmedFrontClose():Unit=runBlocking {
        NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val reads=AtomicInteger();val factories=AtomicInteger()
            val owners=ReminderProcessOwner(bridge,{true},{reads.incrementAndGet();true},{f.open()},{driver(f)},{f.now}){f.fingerprint}
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);lateinit var port:ReminderVmRuntime
            val readyEntered=CountDownLatch(1);val allowReady=CountDownLatch(1);val frontReady=CompletableDeferred<Unit>()
            val preferences=object:AppAccessPreferenceStorage {
                override fun read()=AppAccessPreference(0,false)
                override fun write(expected:AppAccessPreference,enabled:Boolean):AppAccessPreference=error("No preference write")
            }
            val controller=AppAccessController(preferences,scope,{true},{
                val prepared=owners.prepareFront{factories.incrementAndGet();f.foregroundRecords()}
                port=prepared.second;port.attach{false}
            },preferenceOperations=AppAccessPreferenceOperations(),disposedSession=owners::releaseFront,
                changed={state->when(state.phase){AppAccessPhase.OPENING->owners.frontOpening();AppAccessPhase.OPEN->{
                    readyEntered.countDown();check(allowReady.await(5,TimeUnit.SECONDS))
                    owners.frontReady(port);frontReady.complete(Unit)
                };AppAccessPhase.LOADING,AppAccessPhase.FAILED->owners.locked(true);else->owners.locked()}})
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            try{
                withTimeout(5_000){controller.state.first{it.canReadRecords}}
                assertTrue(withContext(Dispatchers.IO){readyEntered.await(5,TimeUnit.SECONDS)})
                // OPEN is emitted before changed() completes: record-read readiness is
                // not an acknowledgement that the reminder owner has reached FRONT.
                assertNull(owners.capture())
                allowReady.countDown();withTimeout(5_000){frontReady.await()}
                val old=requireNotNull(owners.capture())
                controller.dispose();assertFalse(old.active);assertNull(owners.capture())
                val closing=async(Dispatchers.Default){port.closeStore{entered.countDown();check(release.await(5,TimeUnit.SECONDS));f.stores.close()}}
                assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                assertFalse(closing.isCompleted);assertNull(owners.capture());assertEquals(0,f.opened.get())
                release.countDown();closing.await();f.storesForRead()
                val fresh=requireNotNull(owners.capture())
                assertFalse(owners.execute(old));assertEquals(0,reads.get())
                owners.execute(fresh);assertEquals(1,reads.get());assertEquals(1,f.opened.get());assertEquals(1,factories.get())
                owners.forget(fresh);owners.closeCold()
                assertNull(owners.find(old.nonce,old.id))
            }finally{allowReady.countDown();release.countDown();controller.dispose();scope.cancel();bridge.close()}
        }
    }
    @Test fun protectedUnknownExplicitlyLockedAndFailedClosureNeverColdOpen():Unit=runBlocking {
        for(kind in listOf("protected","unknown-preference","explicit-lock","unknown-access","failed-close"))NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val reads=AtomicInteger()
            val owners=ReminderProcessOwner(bridge,{true},{reads.incrementAndGet();if(kind=="unknown-preference")error("Synthetic uncertain preference");kind!="protected"},{f.open()},{driver(f)},{f.now}){f.fingerprint}
            val port=owners.prepareFront{f.foregroundRecords()}.second;port.attach{false};owners.frontReady(port)
            try{
                val old=requireNotNull(owners.capture())
                when(kind){"explicit-lock"->owners.locked();"unknown-access"->owners.locked(true);else->owners.releaseFront()}
                if(kind=="failed-close")assertTrue(runCatching{port.closeStore{throw ReminderRuntimeClosureUnconfirmed()}}.isFailure)
                else port.closeStore{}
                assertFalse(old.active);assertFalse(owners.execute(old))
                val fresh=owners.capture()
                if(kind in setOf("protected","unknown-preference")){
                    assertNotNull(fresh);runCatching{owners.execute(fresh!!)};assertEquals(1,reads.get());assertNull(owners.capture())
                }else{assertNull(fresh);assertEquals(0,reads.get())}
                assertEquals(0,f.opened.get())
            }finally{bridge.close()}
        }
    }
    @Test fun ordinaryDisposeWhileInitialReadIsPendingReleasesOpeningOnlyAfterConfirmedClose():Unit=runBlocking {
        for(kind in listOf("ordinary","protected","unknown-preference","explicit-lock","unknown-access","failed-close"))NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val reads=AtomicInteger();val factories=AtomicInteger()
            val owners=ReminderProcessOwner(bridge,{true},{reads.incrementAndGet();if(kind=="unknown-preference")error("Synthetic uncertain preference");kind!="protected"},{f.open()},{driver(f)},{f.now}){f.fingerprint}
            val old=requireNotNull(owners.capture())
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val prepared=CompletableDeferred<ReminderVmRuntime>();val initialRead=CompletableDeferred<Unit>()
            lateinit var front:ReminderVmRuntime
            val preferences=object:AppAccessPreferenceStorage {
                override fun read()=AppAccessPreference(0,false)
                override fun write(expected:AppAccessPreference,enabled:Boolean):AppAccessPreference=error("No preference write")
            }
            val controller=AppAccessController(preferences,scope,{true},{
                val pair=owners.prepareFront{factories.incrementAndGet();f.foregroundRecords()}
                front=pair.second;front.attach{false};prepared.complete(front);initialRead.await()
            },preferenceOperations=AppAccessPreferenceOperations(),disposedSession=owners::releaseFront,
                changed={state->when(state.phase){AppAccessPhase.OPENING->owners.frontOpening();AppAccessPhase.OPEN->owners.frontReady(front);AppAccessPhase.LOADING,AppAccessPhase.FAILED->owners.locked(true);else->owners.locked()}})
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            try{
                val port=withTimeout(5_000){prepared.await()}
                assertEquals(AppAccessPhase.OPENING,controller.state.value.phase);assertFalse(old.active);assertNull(owners.capture())
                when(kind){"explicit-lock"->owners.locked();"unknown-access"->owners.locked(true)}
                controller.dispose()
                val closing=async(Dispatchers.Default){runCatching{port.closeStore{
                    entered.countDown();check(release.await(5,TimeUnit.SECONDS))
                    if(kind=="failed-close")error("Synthetic opening store close failure")
                    f.stores.close()
                }}}
                assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                assertNull(owners.capture());assertEquals(0,reads.get());assertEquals(0,f.opened.get())
                release.countDown();val closed=closing.await()
                assertEquals(kind!="failed-close",closed.isSuccess)
                val fresh=owners.capture()
                if(kind in setOf("ordinary","protected","unknown-preference")){
                    assertNotNull("No fresh candidate after confirmed OPENING release: $kind",fresh)
                    assertFalse(owners.execute(old));assertEquals(0,reads.get())
                    if(kind=="ordinary")f.storesForRead()
                    runCatching{owners.execute(fresh!!)}
                    assertEquals(1,reads.get());assertEquals(if(kind=="ordinary")1 else 0,f.opened.get())
                    if(kind=="ordinary"){owners.forget(fresh!!);owners.closeCold()}else assertNull(owners.capture())
                }else{assertNull(fresh);assertEquals(0,reads.get());assertEquals(0,f.opened.get())}
                assertEquals(1,factories.get());assertNull(owners.find(old.nonce,old.id))
            }finally{release.countDown();controller.dispose();initialRead.complete(Unit);scope.cancel();bridge.close()}
        }
    }
}

internal fun NativeSqlFixture.foregroundRecords():EntryRepository=object:EntryRepository {
    override val reminderRuntime=this@foregroundRecords.stores.resources
    override suspend fun readDraft():TextDraft?=null
    override suspend fun updateDraft(text:String):TextDraft=error("No user write")
    override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("No user write")
    override suspend fun entries():List<StoredEntry> = emptyList()
    override suspend fun revisions(entryId:String):List<OriginalRevision> = emptyList()
    override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("No user write")
}
