package com.t1doo.aiyou.records

import android.app.Application
import android.app.job.*
import android.content.*
import android.os.Looper
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,shadows=[QueueSchedulerShadow::class,QueueParametersShadow::class])
class ReminderJobStopScopeTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private var runtime:AndroidReminderRuntime?=null
    @Before fun reset(){
        QueueBinder.queue.clear();QueueBinder.completed.set(0);QueueBinder.cancelled.set(0);QueueBinder.returned.set(0)
        QueueBinder.entered=null;QueueBinder.release=null;QueueBinder.lastJob=null;QueueBinder.workOwners.clear();QueueBinder.autoStops.set(0);QueueBinder.lastItem=null
        QueueBinder.outcome=JobScheduler.RESULT_SUCCESS;QueueBinder.throwOnEnqueue=false;QueueBinder.publishBeforeReturn=false
    }
    @After fun cleanup(){runtime?.let{it.scope.cancel();it.bridge.close()};AndroidReminderRuntime.replaceForTest(null);com.t1doo.aiyou.ui.processAppAccessPreferenceOperations.runner=null}
    private fun install(f:NativeSqlFixture,driver:ReminderRuntimeDriver,reads:AtomicInteger):AndroidReminderRuntime=
        AndroidReminderRuntime(app){bridge->ReminderProcessOwner(bridge,{true},{reads.incrementAndGet();true},{f.open()},{driver},{f.now}){f.fingerprint}}
            .also{runtime=it;AndroidReminderRuntime.replaceForTest(it)}
    private fun receive(envelope:ReminderEnvelope){
        val receiver=AlarmReminderReceiver();val filter=IntentFilter(ReminderIntents.ALARM).apply{addDataScheme("aiyou-reminder")}
        app.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
        try{app.sendBroadcast(ReminderIntents.alarm(app,envelope).setComponent(null).setPackage(app.packageName));Shadows.shadowOf(Looper.getMainLooper()).idle()}
        finally{app.unregisterReceiver(receiver)}
    }
    private fun waitFor(predicate:()->Boolean){
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!predicate()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertTrue("Controlled Job run did not settle",predicate())
    }
    @Test fun differentParameterObjectsForOneRunStopBeforeColdAdmissionAndSuppressAllCompletionCallbacks(){NativeSqlFixture().use{f->
        val calls=AtomicInteger();val reads=AtomicInteger()
        val driver=object:ReminderRuntimeDriver {
            override fun availability()=f.available
            override fun schedule(command:TaskReminderCommand):TaskReminderPhase{calls.incrementAndGet();return TaskReminderPhase.SCHEDULED}
            override fun cancel(command:TaskReminderCommand):TaskReminderPhase{calls.incrementAndGet();return TaskReminderPhase.REVOKED}
            override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation{calls.incrementAndGet();return ReminderPostObservation.POST_RETURNED}
        }
        val runtime=install(f,driver,reads);val token=f.token(f.task("stop-before-admission",false))
        receive(f.stores.resources.envelopes.seal(token,ReminderEnvelopePurpose.ALARM));waitFor{QueueBinder.returned.get()==1}
        val controller=Robolectric.buildService(ReminderJobService::class.java).create();val service=controller.get()
        val start=QueueBinder.parameters();val stop=QueueBinder.parameters();assertNotSame(start,stop)
        runBlocking{runtime.jobExecution.lock()}
        try{
            assertTrue(service.onStartJob(start));assertFalse(service.onStopJob(stop))
        }finally{runtime.jobExecution.unlock()}
        try{
            waitFor{ReflectionHelpers.getField<Any?>(service,"running")==null}
            assertEquals(0,reads.get());assertEquals(0,f.opened.get());assertEquals(0,calls.get())
            assertEquals(0,QueueBinder.completed.get());assertFalse(Shadows.shadowOf(service).isJobFinished)
        }finally{controller.destroy()}
    }}
    @Test fun oldGenerationStopCannotRetireReplacementAndStartedStoppedCallStillRecordsItsOwnResult(){NativeSqlFixture().use{f->
        val first=f.token(f.task("first-generation",false));val second=f.token(f.task("replacement-generation",false))
        val enteredA=CountDownLatch(1);val enteredB=CountDownLatch(1);val releaseA=CountDownLatch(1);val releaseB=CountDownLatch(1)
        val calls=AtomicInteger();val reads=AtomicInteger()
        val driver=object:ReminderRuntimeDriver {
            override fun availability()=f.available
            override fun schedule(command:TaskReminderCommand):TaskReminderPhase=error("Stopped run cannot begin a schedule")
            override fun cancel(command:TaskReminderCommand):TaskReminderPhase=error("No cancellation")
            override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation{
                calls.incrementAndGet()
                if(token==first){enteredA.countDown();check(releaseA.await(5,TimeUnit.SECONDS))}
                else{check(token==second);enteredB.countDown();check(releaseB.await(5,TimeUnit.SECONDS))}
                return ReminderPostObservation.POST_RETURNED
            }
        }
        install(f,driver,reads);receive(f.stores.resources.envelopes.seal(first,ReminderEnvelopePurpose.ALARM));waitFor{QueueBinder.returned.get()==1}
        val jobA=requireNotNull(QueueBinder.lastJob)
        val controller=Robolectric.buildService(ReminderJobService::class.java).create();val service=controller.get()
        try{
            service.onStartJob(QueueBinder.parameters(jobA));assertTrue(enteredA.await(5,TimeUnit.SECONDS))
            service.onStopJob(QueueBinder.parameters(jobA));assertEquals(0,f.closed.get());assertEquals(0,QueueBinder.completed.get())
            releaseA.countDown();waitFor{f.closed.get()==1&&ReflectionHelpers.getField<Any?>(service,"running")==null}
            val prior=f.storesForRead().resources.tasks.runtimeView(first.taskId)!!
            assertEquals(ReminderPostObservation.POST_RETURNED,prior.receipts.single().result!!.observation)
            assertFalse(Shadows.shadowOf(service).isJobFinished);assertEquals(0,QueueBinder.completed.get())
            receive(f.stores.resources.envelopes.seal(second,ReminderEnvelopePurpose.ALARM));waitFor{QueueBinder.returned.get()==2}
            val jobB=requireNotNull(QueueBinder.lastJob)
            assertNotEquals(jobA.extras.getString(AndroidReminderRuntime.GENERATION),jobB.extras.getString(AndroidReminderRuntime.GENERATION))
            service.onStartJob(QueueBinder.parameters(jobB));assertTrue(enteredB.await(5,TimeUnit.SECONDS))
            assertFalse(service.onStopJob(QueueBinder.parameters(jobA)))
            releaseB.countDown();waitFor{f.closed.get()==2}
            assertEquals(1,QueueBinder.completed.get());assertFalse(Shadows.shadowOf(service).isJobFinished);assertTrue(QueueBinder.autoStops.get()>0)
            assertEquals(2,calls.get());assertEquals(2,reads.get())
        }finally{releaseA.countDown();releaseB.countDown();controller.destroy()}
    }}
    @Test fun replacementQueuedWhileStoppedPostSettlesKeepsItsOriginalLiveOwnerAndCannotBeKilledByOldStop(){NativeSqlFixture().use{f->
        val a=f.token(f.task("tail-a",false));val b=f.token(f.task("queued-b",false))
        val enteredA=CountDownLatch(1);val releaseA=CountDownLatch(1);val calls=AtomicInteger();val reads=AtomicInteger()
        val driver=object:ReminderRuntimeDriver {
            override fun availability()=f.available
            override fun schedule(command:TaskReminderCommand):TaskReminderPhase=error("No schedule")
            override fun cancel(command:TaskReminderCommand):TaskReminderPhase=error("No cancel")
            override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation{
                calls.incrementAndGet();if(token==a){enteredA.countDown();check(releaseA.await(5,TimeUnit.SECONDS))}else check(token==b)
                return ReminderPostObservation.POST_RETURNED
            }
        }
        install(f,driver,reads);receive(f.stores.resources.envelopes.seal(a,ReminderEnvelopePurpose.ALARM));waitFor{QueueBinder.returned.get()==1}
        val jobA=requireNotNull(QueueBinder.lastJob)
        val controller=Robolectric.buildService(ReminderJobService::class.java).create();val service=controller.get()
        try{
            service.onStartJob(QueueBinder.parameters(jobA));assertTrue(enteredA.await(5,TimeUnit.SECONDS))
            service.onStopJob(QueueBinder.parameters(jobA));assertEquals(0,f.closed.get())
            receive(f.stores.resources.envelopes.seal(b,ReminderEnvelopePurpose.ALARM));waitFor{QueueBinder.returned.get()==2}
            val jobB=requireNotNull(QueueBinder.lastJob)
            assertNotEquals(jobA.extras.getString(AndroidReminderRuntime.GENERATION),jobB.extras.getString(AndroidReminderRuntime.GENERATION))
            service.onStartJob(QueueBinder.parameters(jobB));service.onStopJob(QueueBinder.parameters(jobA))
            assertEquals(0,f.closed.get());assertEquals(1,calls.get())
            releaseA.countDown();waitFor{f.closed.get()==1}
            assertEquals(2,calls.get());assertEquals(1,reads.get());assertEquals(1,f.opened.get())
            assertEquals(1,QueueBinder.completed.get());assertFalse(Shadows.shadowOf(service).isJobFinished);assertTrue(QueueBinder.autoStops.get()>0)
            val view=f.storesForRead().resources.tasks.runtimeView(b.taskId)!!
            assertEquals(ReminderPostObservation.POST_RETURNED,view.receipts.single().result!!.observation)
        }finally{releaseA.countDown();controller.destroy()}
    }}

}
