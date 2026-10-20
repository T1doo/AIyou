package com.t1doo.aiyou.records

import android.database.sqlite.SQLiteDatabase
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production worker and admission gate, with latches at actual worker/access/factory boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class ReminderOpeningReservationTest {
    private fun owners(f:NativeSqlFixture,bridge:ReminderOperationBridge,reads:AtomicInteger,unlocked:()->Boolean={true})=
        ReminderProcessOwner(bridge,unlocked,{reads.incrementAndGet();true},{f.open()},{object:ReminderRuntimeDriver {
            override fun availability()=f.available
            override fun schedule(command:TaskReminderCommand):TaskReminderPhase=error("No schedule")
            override fun cancel(command:TaskReminderCommand):TaskReminderPhase=error("No cancel")
            override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation=error("No post")
        }},{f.now}){f.fingerprint}
    private suspend fun await(latch:CountDownLatch){assertTrue(withContext(Dispatchers.IO){latch.await(5,TimeUnit.SECONDS)})}
    private suspend fun coldAfterConfirmedRelease(f:NativeSqlFixture,owners:ReminderProcessOwner,reads:AtomicInteger,old:ReminderProcessOwner.Ticket){
        assertFalse(old.active);assertFalse(owners.execute(old));assertEquals(0,reads.get())
        val fresh=owners.capture();assertNotNull("Cancelled opening reservation still prevents a fresh cold candidate",fresh)
        owners.execute(fresh!!);assertEquals(1,reads.get());assertEquals(1,f.opened.get())
        owners.forget(fresh);owners.closeCold()
    }
    @Test fun disposalBeforeWorkerAdmissionCancelsFactoryAndRecoversColdAdmission():Unit=runBlocking {
        NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val reads=AtomicInteger();val owners=owners(f,bridge,reads)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val entered=CountDownLatch(1);val release=CountDownLatch(1);val factories=AtomicInteger()
            val old=requireNotNull(owners.capture())
            val blocker=scope.async{bridge.runtime{entered.countDown();check(release.await(5,TimeUnit.SECONDS))}}
            await(entered)
            val preparing=scope.async(start=CoroutineStart.UNDISPATCHED){runCatching{owners.prepareFront{factories.incrementAndGet();f.foregroundRecords()}}}
            try{
                owners.releaseFront();assertNull(owners.capture());assertEquals(0,factories.get())
                release.countDown();withTimeout(5_000){blocker.await();assertTrue(preparing.await().isFailure)}
                assertEquals(0,factories.get());coldAfterConfirmedRelease(f,owners,reads,old)
            }finally{release.countDown();preparing.join();scope.cancel();bridge.close()}
        }
    }
    @Test fun disposalBeforePrepareIsInvokedConfirmsUnclaimedReservationOnWorkerWithoutReleasingAnotherIntent():Unit=runBlocking {
        for(kind in listOf("ordinary","explicit-lock","unknown-access","replacement"))NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val reads=AtomicInteger();val owners=owners(f,bridge,reads)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val old=requireNotNull(owners.capture())
            val blocker=scope.async{bridge.runtime{entered.countDown();check(release.await(5,TimeUnit.SECONDS))}};await(entered)
            owners.frontOpening();owners.releaseFront();assertNull(owners.capture())
            when(kind){"explicit-lock"->owners.locked();"unknown-access"->owners.locked(true)}
            val replacement=if(kind=="replacement")scope.async(start=CoroutineStart.UNDISPATCHED){owners.prepareFront{f.foregroundRecords()}}else null
            var port:ReminderVmRuntime?=null
            try{
                release.countDown();blocker.await();bridge.runtime{} // Drain the queued observation before checking.
                if(kind=="ordinary")coldAfterConfirmedRelease(f,owners,reads,old)
                else if(kind=="replacement"){
                    port=withTimeout(5_000){replacement!!.await()}.second;port.attach{false};owners.frontReady(port)
                    val fresh=requireNotNull(owners.capture());assertTrue(fresh.active);assertTrue(port.owner.isAdmitted)
                    assertFalse(owners.execute(old));assertEquals(0,reads.get());owners.forget(fresh)
                }else{assertNull(owners.capture());assertEquals(0,reads.get());assertEquals(0,f.opened.get())}
            }finally{release.countDown();port?.closeStore{};scope.cancel();bridge.close()}
        }
    }
    @Test fun disposalDuringWorkerAdmissionRechecksReservationBeforePhysicalOpen():Unit=runBlocking {
        NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val reads=AtomicInteger();val entered=CountDownLatch(1);val release=CountDownLatch(1);val armed=AtomicBoolean()
            val owners=owners(f,bridge,reads){if(armed.compareAndSet(true,false)){entered.countDown();check(release.await(5,TimeUnit.SECONDS))};true}
            val old=requireNotNull(owners.capture());val factories=AtomicInteger();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            armed.set(true)
            val preparing=scope.async{runCatching{owners.prepareFront{factories.incrementAndGet();f.foregroundRecords()}}}
            try{
                await(entered);owners.releaseFront();assertNull(owners.capture());assertEquals(0,factories.get())
                release.countDown();assertTrue(withTimeout(5_000){preparing.await()}.isFailure)
                assertEquals(0,factories.get());coldAfterConfirmedRelease(f,owners,reads,old)
            }finally{release.countDown();preparing.join();scope.cancel();bridge.close()}
        }
    }
    @Test fun disposalInsideStartedFactoryWaitsForRealCloseAndNeverPublishesItsPair():Unit=runBlocking {
        for(kind in listOf("ordinary","explicit-lock","unknown-access","failed-close"))NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val reads=AtomicInteger();val owners=owners(f,bridge,reads)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val closing=CountDownLatch(1);val closeRelease=CountDownLatch(1);val factories=AtomicInteger();val closes=AtomicInteger()
            val key=ByteArray(32){13};val db=AtomicReference<SQLiteDatabase>();val abandoned=AtomicReference<ReminderVmRuntime?>()
            val old=requireNotNull(owners.capture())
            val preparing=scope.async{runCatching{owners.prepareFront<EntryRepository>{
                factories.incrementAndGet();db.set(SQLiteDatabase.create(null));entered.countDown();check(release.await(5,TimeUnit.SECONDS))
                object:EntryRepository by f.foregroundRecords(),AutoCloseable,AwaitableRecordStorage {
                    override fun close(){closes.incrementAndGet();closing.countDown()}
                    override fun awaitClosed(){
                        check(closeRelease.await(5,TimeUnit.SECONDS))
                        if(kind=="failed-close")error("Synthetic started factory close remains unknown")
                        db.get().close();key.fill(0)
                    }
                }
            }}.also{abandoned.set(it.getOrNull()?.second)}}
            try{
                await(entered);owners.releaseFront()
                when(kind){"explicit-lock"->owners.locked();"unknown-access"->owners.locked(true)}
                assertNull(owners.capture());release.countDown()
                withTimeout(5_000){while(closing.count!=0L&&!preparing.isCompleted)delay(5)}
                assertEquals("Disposed factory returned a Pair without starting close: $kind",0L,closing.count)
                assertNull(owners.capture());assertTrue(db.get().isOpen);assertArrayEquals(ByteArray(32){13},key)
                assertEquals(0,reads.get());assertEquals(0,f.opened.get());closeRelease.countDown()
                assertTrue(withTimeout(5_000){preparing.await()}.isFailure);assertEquals(1,factories.get());assertEquals(1,closes.get())
                if(kind=="ordinary"){
                    assertFalse(db.get().isOpen);assertTrue(key.all{it==0.toByte()});coldAfterConfirmedRelease(f,owners,reads,old)
                }else{
                    assertNull(owners.capture());assertEquals(0,reads.get());assertEquals(0,f.opened.get())
                    if(kind=="failed-close"){
                        assertTrue(db.get().isOpen);assertArrayEquals(ByteArray(32){13},key)
                        assertTrue(runCatching{owners.prepareFront{factories.incrementAndGet();f.foregroundRecords()}}.isFailure)
                        assertEquals(1,factories.get());assertTrue(db.get().isOpen);assertArrayEquals(ByteArray(32){13},key)
                    }else{assertFalse(db.get().isOpen);assertTrue(key.all{it==0.toByte()})}
                }
            }finally{release.countDown();closeRelease.countDown();preparing.join();abandoned.get()?.closeStore{db.get()?.close();key.fill(0)};scope.cancel();db.get()?.close();key.fill(0);bridge.close()}
        }
    }
    @Test fun cancelledPreAdmissionReservationCannotClearExplicitLockOrUnknownPriorClosure():Unit=runBlocking {
        for(kind in listOf("explicit-lock","unknown-access","unknown-prior-close"))NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val reads=AtomicInteger();val owners=owners(f,bridge,reads)
            if(kind=="unknown-prior-close"){
                val port=owners.prepareFront{f.foregroundRecords()}.second
                assertTrue(runCatching{port.closeStore{error("Synthetic prior physical closure unknown")}}.isFailure)
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val entered=CountDownLatch(1);val release=CountDownLatch(1);val factories=AtomicInteger()
            val blocker=scope.async{bridge.runtime{entered.countDown();check(release.await(5,TimeUnit.SECONDS))}};await(entered)
            val preparing=scope.async(start=CoroutineStart.UNDISPATCHED){runCatching{owners.prepareFront{factories.incrementAndGet();f.foregroundRecords()}}}
            try{
                owners.releaseFront();when(kind){"explicit-lock"->owners.locked();"unknown-access"->owners.locked(true)}
                release.countDown();blocker.await();assertTrue(withTimeout(5_000){preparing.await()}.isFailure)
                assertNull(owners.capture());assertEquals(0,factories.get());assertEquals(0,reads.get());assertEquals(0,f.opened.get())
                if(kind=="unknown-prior-close")assertThrows(IllegalStateException::class.java){bridge.gate.openOwner(true)}
            }finally{release.countDown();preparing.join();scope.cancel();bridge.close()}
        }
    }
    @Test fun lateOldReservationCleanupCannotClearReplacementForegroundOwnerOrItsTickets():Unit=runBlocking {
        NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val reads=AtomicInteger();val owners=owners(f,bridge,reads)
            val paused=PausedContinuation();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val oldFactories=AtomicInteger();val newFactories=AtomicInteger()
            val blocker=scope.async{bridge.runtime{entered.countDown();check(release.await(5,TimeUnit.SECONDS))}};await(entered)
            val a=scope.async(paused,start=CoroutineStart.UNDISPATCHED){runCatching{owners.prepareFront{oldFactories.incrementAndGet();f.foregroundRecords()}}}
            owners.releaseFront()
            val b=scope.async(start=CoroutineStart.UNDISPATCHED){owners.prepareFront{newFactories.incrementAndGet();f.foregroundRecords()}}
            var port:ReminderVmRuntime?=null
            try{
                release.countDown();blocker.await();port=withTimeout(5_000){b.await()}.second;port.attach{false};owners.frontReady(port)
                val fresh=requireNotNull(owners.capture());assertEquals(1,newFactories.get())
                withTimeout(5_000){while(!a.isCompleted){paused.runQueued();delay(5)}}
                assertTrue(a.await().isFailure);assertEquals(0,oldFactories.get());assertEquals(0,reads.get());assertEquals(0,f.opened.get())
                assertSame(fresh,owners.find(fresh.nonce,fresh.id));assertTrue(fresh.active);assertTrue(port.owner.isAdmitted)
                owners.forget(fresh)
            }finally{release.countDown();withTimeout(5_000){while(!a.isCompleted){paused.runQueued();delay(5)}};port?.closeStore{};scope.cancel();bridge.close()}
        }
    }
    @Test fun cancelledUnclaimedIntentWaitsForPriorOwnerCloseAndOnlyThenObservesItsOwnRelease():Unit=runBlocking {
        for(kind in listOf("front","cold","unknown-close"))NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val reads=AtomicInteger();val owners=owners(f,bridge,reads)
            var prior:ReminderVmRuntime?=null
            try{
                if(kind=="cold"){
                    val start=requireNotNull(owners.capture());owners.execute(start);owners.forget(start)
                }else{
                    prior=owners.prepareFront{f.foregroundRecords()}.second;prior.attach{false};owners.frontReady(prior)
                }
                val beforeReads=reads.get();val beforeOpens=f.opened.get()
                owners.frontOpening();owners.releaseFront();bridge.runtime{}
                assertNull(owners.capture());assertEquals(beforeReads,reads.get())
                if(kind=="unknown-close"){
                    assertTrue(runCatching{prior!!.closeStore{error("Synthetic prior close not confirmed")}}.isFailure)
                    bridge.runtime{};assertNull(owners.capture());assertEquals(beforeReads,reads.get());assertEquals(beforeOpens,f.opened.get())
                    assertThrows(IllegalStateException::class.java){bridge.gate.openOwner(true)}
                }else{
                    if(kind=="cold")owners.closeCold()else prior!!.closeStore{f.stores.close()}
                    bridge.runtime{};f.storesForRead()
                    val fresh=owners.capture();assertNotNull("Prior confirmed close stranded unclaimed intent: $kind",fresh)
                    owners.execute(fresh!!);assertEquals(beforeReads+1,reads.get());assertEquals(beforeOpens+1,f.opened.get())
                    owners.forget(fresh);owners.closeCold()
                }
            }finally{bridge.close()}
        }
    }
    @Test fun cancelledClaimedButDeniedIntentKeepsItsNoOpenProofWhilePriorOwnerRemainsUnclosed():Unit=runBlocking {
        for(kind in listOf("confirmed","unknown-close"))NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val reads=AtomicInteger();val owners=owners(f,bridge,reads)
            val prior=owners.prepareFront{f.foregroundRecords()}.second;prior.attach{false};owners.frontReady(prior)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val factories=AtomicInteger();val closes=AtomicInteger()
            val preparing=scope.async(start=CoroutineStart.UNDISPATCHED){runCatching{owners.prepareFront{factories.incrementAndGet();f.foregroundRecords()}}}
            try{
                owners.releaseFront();assertTrue(withTimeout(5_000){preparing.await()}.isFailure)
                assertEquals(0,factories.get());assertEquals(0,closes.get());assertNull(owners.capture());assertEquals(0,reads.get())
                if(kind=="unknown-close"){
                    assertTrue(runCatching{prior.closeStore{closes.incrementAndGet();error("Synthetic prior close still unknown")}}.isFailure)
                    bridge.runtime{};assertNull(owners.capture());assertEquals(0,reads.get());assertEquals(0,f.opened.get())
                    assertThrows(IllegalStateException::class.java){bridge.gate.openOwner(true)}
                }else{
                    prior.closeStore{closes.incrementAndGet();f.stores.close()};bridge.runtime{};f.storesForRead()
                    val fresh=owners.capture();assertNotNull("Denied reservation lost its no-open proof while waiting for prior close",fresh)
                    owners.execute(fresh!!);assertEquals(1,reads.get());assertEquals(1,f.opened.get());assertEquals(1,closes.get())
                    owners.forget(fresh);owners.closeCold()
                }
            }finally{preparing.join();scope.cancel();bridge.close()}
        }
    }
    @Test fun alreadyCancelledPreparationCallerCannotRetireTheCurrentForegroundOwnerOrItsTicket():Unit=runBlocking {
        NativeSqlFixture().use{f->
            val bridge=ReminderOperationBridge();val reads=AtomicInteger();val owners=owners(f,bridge,reads)
            val current=owners.prepareFront{f.foregroundRecords()}.second;current.attach{false};owners.frontReady(current)
            val fresh=requireNotNull(owners.capture());val factories=AtomicInteger();val caught=AtomicReference<Throwable?>()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val stale=scope.async(start=CoroutineStart.UNDISPATCHED){
                currentCoroutineContext().cancel(java.util.concurrent.CancellationException("Synthetic already cancelled old caller"))
                runCatching{owners.prepareFront{factories.incrementAndGet();f.foregroundRecords()}}.onFailure{caught.set(it)}
            }
            try{
                withTimeout(5_000){stale.join()};assertTrue(stale.isCancelled);assertTrue(caught.get() is java.util.concurrent.CancellationException)
                assertEquals(0,factories.get());assertEquals(0,reads.get());assertEquals(0,f.opened.get())
                assertTrue("A cancelled caller retired the current foreground owner",current.owner.isAdmitted)
                assertTrue(fresh.active);assertSame(fresh,owners.find(fresh.nonce,fresh.id));owners.forget(fresh)
            }finally{stale.join();current.closeStore{};scope.cancel();bridge.close()}
        }
    }
    private class PausedContinuation:CoroutineDispatcher(){
        private val queue=ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable){queue.add(block)}
        fun runQueued(){repeat(16){queue.poll()?.run()?:return}}
    }
}
