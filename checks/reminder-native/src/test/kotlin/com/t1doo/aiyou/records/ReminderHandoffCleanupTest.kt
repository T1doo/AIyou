package com.t1doo.aiyou.records

import android.app.Application
import android.app.job.*
import android.content.*
import android.os.Looper
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSystemClock

/** Real receiver handoff and JobService; only OS queue/effect boundaries are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,shadows=[QueueSchedulerShadow::class,QueueParametersShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ReminderHandoffCleanupTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private var runtime:AndroidReminderRuntime?=null
    @Before fun reset(){
        QueueBinder.queue.clear();QueueBinder.completed.set(0);QueueBinder.cancelled.set(0)
        QueueBinder.entered=null;QueueBinder.release=null;QueueBinder.returned.set(0);QueueBinder.lastItem=null;QueueBinder.lastJob=null;QueueBinder.workOwners.clear();QueueBinder.autoStops.set(0)
        QueueBinder.outcome=JobScheduler.RESULT_SUCCESS;QueueBinder.throwOnEnqueue=false;QueueBinder.publishBeforeReturn=false
    }
    @After fun cleanup(){
        QueueBinder.release?.countDown()
        runtime?.let{it.scope.cancel();it.bridge.close()}
        AndroidReminderRuntime.replaceForTest(null)
        com.t1doo.aiyou.ui.processAppAccessPreferenceOperations.runner=null
    }
    private fun install(f:NativeSqlFixture,driver:ReminderRuntimeDriver,reads:AtomicInteger=AtomicInteger()):AndroidReminderRuntime {
        return AndroidReminderRuntime(app){bridge->ReminderProcessOwner(bridge,{true},{reads.incrementAndGet();true},{f.open()},{driver},{f.now}){f.fingerprint}}
            .also{runtime=it;AndroidReminderRuntime.replaceForTest(it)}
    }
    private fun driver(f:NativeSqlFixture,posts:AtomicInteger)=object:ReminderRuntimeDriver {
        override fun availability()=f.available
        override fun schedule(command:TaskReminderCommand):TaskReminderPhase=error("No schedule expected")
        override fun cancel(command:TaskReminderCommand):TaskReminderPhase=error("No cancel expected")
        override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation{posts.incrementAndGet();return ReminderPostObservation.POST_RETURNED}
    }
    private fun receive(envelope:ReminderEnvelope?){
        val receiver:BroadcastReceiver=if(envelope==null)ReminderRebuildReceiver()else AlarmReminderReceiver()
        val intent=if(envelope==null)Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(app.packageName)
            else ReminderIntents.alarm(app,envelope).setComponent(null).setPackage(app.packageName)
        val filter=IntentFilter(intent.action).apply{if(envelope!=null)addDataScheme("aiyou-reminder")}
        app.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
        try{app.sendBroadcast(intent);Shadows.shadowOf(Looper.getMainLooper()).idle()}finally{app.unregisterReceiver(receiver)}
    }
    private fun waitFor(pumpMain:Boolean=true,predicate:()->Boolean){
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!predicate()&&System.nanoTime()<end){if(pumpMain)Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertTrue("Controlled handoff did not settle",predicate())
    }
    private fun blocked(){QueueBinder.entered=CountDownLatch(1);QueueBinder.release=CountDownLatch(1)}
    private fun current(runtime:AndroidReminderRuntime):ReminderProcessOwner.Ticket {
        val intent=requireNotNull(QueueBinder.lastItem).intent
        return requireNotNull(runtime.owners.find(intent.getStringExtra(AndroidReminderRuntime.NONCE)!!,intent.getStringExtra(AndroidReminderRuntime.TICKET)!!))
    }
    private fun completeStaleJob(f:NativeSqlFixture){
        val controller=Robolectric.buildService(ReminderJobService::class.java).create();val service=controller.get()
        try{
            service.onStartJob(QueueBinder.parameters());waitFor{QueueBinder.loopEnded(service)}
            assertEquals(0,f.opened.get())
        }finally{controller.destroy()}
    }
    @Test fun moreThanCapacityTimeoutsReleaseAlarmAndRebuildReservationsBeforeAnyColdOwnerExists(){NativeSqlFixture().use{f->
        val posts=AtomicInteger();val reads=AtomicInteger();val runtime=install(f,driver(f,posts),reads)
        val fresh=f.token(f.task("fresh-after-timeouts",false))
        repeat(70){round->
            blocked()
            val envelope=if(round%2==0)f.stores.resources.envelopes.seal(TaskReminderToken("timeout-$round","a".repeat(64),1),ReminderEnvelopePurpose.ALARM)else null
            receive(envelope);assertTrue(QueueBinder.entered!!.await(5,TimeUnit.SECONDS))
            val old=current(runtime)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertFalse(old.active);assertNull(runtime.owners.find(old.nonce,old.id))
            // A valid reservation must fit immediately, before the old Binder call returns.
            val accepted=runtime.owners.capture()
            assertNotNull("Expired reservations exhausted capacity at round $round",accepted)
            runtime.owners.forget(requireNotNull(accepted));runtime.owners.forget(accepted)
            QueueBinder.release!!.countDown();waitFor{QueueBinder.returned.get()==round+1}
            assertEquals(0,reads.get());assertEquals(0,f.opened.get());assertEquals(0,posts.get())
        }
        assertEquals(70,QueueBinder.queue.size);assertEquals(1,QueueBinder.cancelled.get())
        completeStaleJob(f);assertEquals(0,reads.get())
        QueueBinder.entered=null;QueueBinder.release=null
        receive(f.stores.resources.envelopes.seal(fresh,ReminderEnvelopePurpose.ALARM));waitFor{QueueBinder.queue.size==1}
        val controller=Robolectric.buildService(ReminderJobService::class.java).create();val service=controller.get()
        try{
            service.onStartJob(QueueBinder.parameters());waitFor{f.closed.get()==1}
            assertEquals(1,posts.get());assertEquals(1,reads.get());assertEquals(1,f.opened.get())
        }finally{controller.destroy()}
    }}
    @Test fun lateSuccessAfterDeadlineReleasesReservationEvenBeforeExpiryRunnableCanRun(){NativeSqlFixture().use{f->
        val runtime=install(f,driver(f,AtomicInteger()));blocked()
        receive(null);assertTrue(QueueBinder.entered!!.await(5,TimeUnit.SECONDS));val old=current(runtime)
        // Advance elapsed time without dispatching the delayed main-looper expiry callback.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3));assertTrue(old.active)
        QueueBinder.release!!.countDown();waitFor(pumpMain=false){QueueBinder.returned.get()==1}
        waitFor(pumpMain=false){!old.active}
        assertNull(runtime.owners.find(old.nonce,old.id))
        repeat(64){assertNotNull(runtime.owners.capture())};assertNull(runtime.owners.capture())
        assertEquals(0,f.opened.get())
    }}
    @Test fun repeatedIdentityHasOneHandoffAndLaterFailureOrTimeoutCannotRetireItsSuccessfulWork(){NativeSqlFixture().use{f->
        val posts=AtomicInteger();val runtime=install(f,driver(f,posts))
        val token=f.token(f.task("one-handoff",false));val envelope=f.stores.resources.envelopes.seal(token,ReminderEnvelopePurpose.ALARM)
        receive(envelope);waitFor{QueueBinder.returned.get()==1}
        val first=current(runtime)
        // These settings must never be observed: duplicates have no second handoff/timer.
        blocked();QueueBinder.outcome=JobScheduler.RESULT_FAILURE
        receive(envelope)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue("A duplicate receiver retired the first successful work",first.active)
        assertEquals(1,QueueBinder.returned.get());assertEquals(1,QueueBinder.queue.size)
        assertSame(first,runtime.owners.find(first.nonce,first.id))
        QueueBinder.release!!.countDown()
        val controller=Robolectric.buildService(ReminderJobService::class.java).create();val service=controller.get()
        try{service.onStartJob(QueueBinder.parameters());waitFor{f.closed.get()==1};assertEquals(1,posts.get())}
        finally{controller.destroy()}
    }}
    @Test fun lateFailureAndExceptionRemainIdempotentAndDoNotExhaustCapacity(){NativeSqlFixture().use{f->
        val runtime=install(f,driver(f,AtomicInteger()))
        repeat(2){round->
            blocked();QueueBinder.outcome=JobScheduler.RESULT_FAILURE;QueueBinder.throwOnEnqueue=round==1
            receive(null);assertTrue(QueueBinder.entered!!.await(5,TimeUnit.SECONDS));val old=current(runtime)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(3));QueueBinder.release!!.countDown()
            waitFor(pumpMain=false){QueueBinder.returned.get()==round+1};waitFor(pumpMain=false){!old.active}
            runtime.owners.forget(old);runtime.owners.forget(old)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertNull(runtime.owners.find(old.nonce,old.id));assertTrue(QueueBinder.queue.isEmpty())
        }
        val tickets=List(64){requireNotNull(runtime.owners.capture())};assertNull(runtime.owners.capture())
        tickets.forEach{runtime.owners.forget(it)};assertNotNull(runtime.owners.capture());assertEquals(0,f.opened.get())
    }}
    @Test fun timeoutCleanupDoesNotEndStartedPublicationOrWipeItsOwnerBeforeResultTail(){NativeSqlFixture().use{f->
        val entered=CountDownLatch(1);val releasePost=CountDownLatch(1);val calls=AtomicInteger()
        val driver=object:ReminderRuntimeDriver {
            override fun availability()=f.available
            override fun schedule(command:TaskReminderCommand):TaskReminderPhase=error("No new schedule after timeout")
            override fun cancel(command:TaskReminderCommand):TaskReminderPhase=error("No cancel expected")
            override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation{
                calls.incrementAndGet();entered.countDown();check(releasePost.await(5,TimeUnit.SECONDS));return ReminderPostObservation.POST_RETURNED
            }
        }
        val token=f.token(f.task("started-before-timeout",false));val runtime=install(f,driver)
        blocked();QueueBinder.publishBeforeReturn=true
        receive(f.stores.resources.envelopes.seal(token,ReminderEnvelopePurpose.ALARM))
        assertTrue(QueueBinder.entered!!.await(5,TimeUnit.SECONDS));val old=current(runtime)
        val controller=Robolectric.buildService(ReminderJobService::class.java).create();val service=controller.get()
        try{
            service.onStartJob(QueueBinder.parameters());assertTrue(entered.await(5,TimeUnit.SECONDS))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertFalse(old.active);assertNull(runtime.owners.find(old.nonce,old.id));assertEquals(0,f.closed.get())
            QueueBinder.release!!.countDown();waitFor{QueueBinder.returned.get()==1}
            runtime.owners.forget(old);runtime.owners.forget(old);assertEquals(0,f.closed.get());assertEquals(0,QueueBinder.completed.get())
            releasePost.countDown();waitFor{f.closed.get()==1}
            val view=f.storesForRead().resources.tasks.runtimeView(token.taskId)!!
            assertEquals(TaskReminderPhase.NOTIFICATION_POSTED,view.task.reminder!!.phase)
            assertEquals(ReminderPostObservation.POST_RETURNED,view.receipts.single().result!!.observation)
            assertEquals(1,calls.get());assertEquals(1,QueueBinder.completed.get())
        }finally{releasePost.countDown();QueueBinder.release!!.countDown();controller.destroy()}
    }}
    @Test fun oldHandoffCleanupAfterSessionChangeCannotRemoveOrReviveNewSessionWork(){NativeSqlFixture().use{f->
        val runtime=install(f,driver(f,AtomicInteger()));blocked()
        receive(null);assertTrue(QueueBinder.entered!!.await(5,TimeUnit.SECONDS));val old=current(runtime)
        runtime.owners.frontOpening();assertFalse(old.active)
        val repository=object:EntryRepository {
            override val reminderRuntime=f.stores.resources
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String):TextDraft=error("No user write")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("No user write")
            override suspend fun entries():List<StoredEntry> = emptyList()
            override suspend fun revisions(entryId:String):List<OriginalRevision> = emptyList()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("No user write")
        }
        val port=runBlocking{runtime.owners.prepareFront{repository}.second};port.attach{false};runtime.owners.frontReady(port)
        try{
            val fresh=requireNotNull(runtime.owners.capture())
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));QueueBinder.release!!.countDown()
            waitFor{QueueBinder.returned.get()==1}
            repeat(3){runtime.owners.forget(old)}
            assertNull(runtime.owners.find(old.nonce,old.id));assertFalse(old.active)
            assertSame(fresh,runtime.owners.find(fresh.nonce,fresh.id));assertTrue(fresh.active)
            runtime.owners.forget(fresh);runtime.owners.forget(fresh);assertNull(runtime.owners.find(fresh.nonce,fresh.id))
            assertEquals(0,f.opened.get())
        }finally{runBlocking{port.closeStore{}};QueueBinder.release!!.countDown()}
    }}
}
