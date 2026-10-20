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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowJobService

/** A framework finish releases the failed OS run; it does not acknowledge the product work item. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,shadows=[QueueSchedulerShadow::class,QueueParametersShadow::class,FailureLeaseServiceShadow::class])
class ReminderJobFailureLeaseTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private var runtime:AndroidReminderRuntime?=null
    @Before fun reset(){
        QueueBinder.queue.clear();QueueBinder.completed.set(0);QueueBinder.cancelled.set(0);QueueBinder.returned.set(0)
        QueueBinder.entered=null;QueueBinder.release=null;QueueBinder.lastJob=null;QueueBinder.lastItem=null
        QueueBinder.workOwners.clear();QueueBinder.autoStops.set(0);QueueBinder.outcome=JobScheduler.RESULT_SUCCESS
        QueueBinder.throwOnEnqueue=false;QueueBinder.publishBeforeReturn=false;FailureLeaseServiceShadow.finishes.clear()
    }
    @After fun cleanup(){runtime?.let{it.scope.cancel();it.bridge.close()};AndroidReminderRuntime.replaceForTest(null);com.t1doo.aiyou.ui.processAppAccessPreferenceOperations.runner=null}
    private fun install(f:NativeSqlFixture,reads:AtomicInteger,calls:AtomicInteger,open:()->OwnedReminderRuntime):AndroidReminderRuntime {
        val driver=object:ReminderRuntimeDriver {
            override fun availability()=f.available
            override fun schedule(command:TaskReminderCommand):TaskReminderPhase{calls.incrementAndGet();error("No schedule")}
            override fun cancel(command:TaskReminderCommand):TaskReminderPhase{calls.incrementAndGet();error("No cancel")}
            override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation{calls.incrementAndGet();return ReminderPostObservation.POST_RETURNED}
        }
        return AndroidReminderRuntime(app){bridge->ReminderProcessOwner(bridge,{true},{reads.incrementAndGet();true},open,{driver},{f.now}){f.fingerprint}}
            .also{runtime=it;AndroidReminderRuntime.replaceForTest(it)}
    }
    private fun receive(f:NativeSqlFixture,token:TaskReminderToken){
        val receiver=AlarmReminderReceiver();val envelope=f.stores.resources.envelopes.seal(token,ReminderEnvelopePurpose.ALARM)
        app.registerReceiver(receiver,IntentFilter(ReminderIntents.ALARM).apply{addDataScheme("aiyou-reminder")},Context.RECEIVER_NOT_EXPORTED)
        try{app.sendBroadcast(ReminderIntents.alarm(app,envelope).setComponent(null).setPackage(app.packageName));Shadows.shadowOf(Looper.getMainLooper()).idle()}
        finally{app.unregisterReceiver(receiver)}
    }
    private fun waitFor(predicate:()->Boolean){
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!predicate()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertTrue("Controlled failed Job lease did not settle",predicate())
    }
    @Test fun coldOpenFailureFinishesOnlyItsPlatformLeaseWithoutCompletingOrAcknowledgingBusinessWork(){NativeSqlFixture().use{f->
        val token=f.token(f.task("failed-cold-open",false));val before=f.stores.resources.tasks.runtimeView(token.taskId)
        val reads=AtomicInteger();val calls=AtomicInteger();val factories=AtomicInteger()
        install(f,reads,calls){factories.incrementAndGet();error("Synthetic cold factory failure before key/database acquisition")}
        receive(f,token);waitFor{QueueBinder.returned.get()==1}
        val controller=Robolectric.buildService(ReminderJobService::class.java).create();val service=controller.get();val start=QueueBinder.parameters()
        try{
            assertTrue(service.onStartJob(start));waitFor{QueueBinder.loopEnded(service)}
            val finished=FailureLeaseServiceShadow.finishes.toList()
            assertEquals(1,finished.size);assertSame(start,finished.single().first);assertFalse(finished.single().second)
            assertEquals(1,reads.get());assertEquals(1,factories.get());assertEquals(0,f.opened.get());assertEquals(0,calls.get())
            assertEquals(0,QueueBinder.completed.get());assertEquals(0,QueueBinder.autoStops.get())
            val after=f.stores.resources.tasks.runtimeView(token.taskId)
            assertEquals(before!!.task,after!!.task);assertTrue(before.receipts.isEmpty());assertTrue(after.receipts.isEmpty())
        }finally{controller.destroy()}
    }}
    @Test fun stopWinningBeforeAStartedColdFactoryFailsSuppressesPlatformFinishAndAllBusinessCalls(){NativeSqlFixture().use{f->
        val token=f.token(f.task("stopped-failed-factory",false));val reads=AtomicInteger();val calls=AtomicInteger()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        install(f,reads,calls){entered.countDown();check(release.await(5,TimeUnit.SECONDS));error("Synthetic late cold factory failure")}
        receive(f,token);waitFor{QueueBinder.returned.get()==1};val job=requireNotNull(QueueBinder.lastJob)
        val controller=Robolectric.buildService(ReminderJobService::class.java).create();val service=controller.get()
        try{
            service.onStartJob(QueueBinder.parameters(job));assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertFalse(service.onStopJob(QueueBinder.parameters(job)));release.countDown();waitFor{QueueBinder.loopEnded(service)}
            assertTrue(FailureLeaseServiceShadow.finishes.isEmpty());assertEquals(0,QueueBinder.completed.get())
            assertEquals(0,QueueBinder.autoStops.get());assertEquals(0,calls.get());assertEquals(0,f.opened.get());assertEquals(1,reads.get())
        }finally{release.countDown();controller.destroy()}
    }}
    @Test fun stoppedOldDequeueFailureCannotFinishReplacementOrStartAnyOldBusinessOperation(){NativeSqlFixture().use{f->
        val a=f.token(f.task("old-dequeue-failure",false));val b=f.token(f.task("new-generation-after-failure",false))
        val reads=AtomicInteger();val calls=AtomicInteger();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        install(f,reads,calls){f.open()};receive(f,a);waitFor{QueueBinder.returned.get()==1}
        val jobA=requireNotNull(QueueBinder.lastJob);val paramsA=QueueBinder.parameters(jobA)
        Shadow.extract<QueueParametersShadow>(paramsA).beforeDequeue={entered.countDown();check(release.await(5,TimeUnit.SECONDS));error("Synthetic old dequeue Binder failure")}
        val controller=Robolectric.buildService(ReminderJobService::class.java).create();val service=controller.get()
        try{
            service.onStartJob(paramsA);assertTrue(entered.await(5,TimeUnit.SECONDS));service.onStopJob(QueueBinder.parameters(jobA))
            assertEquals(0,reads.get());assertEquals(0,calls.get());assertEquals(0,f.opened.get())
            receive(f,b);waitFor{QueueBinder.returned.get()==2};val jobB=requireNotNull(QueueBinder.lastJob)
            assertNotEquals(jobA.extras.getString(AndroidReminderRuntime.GENERATION),jobB.extras.getString(AndroidReminderRuntime.GENERATION))
            service.onStartJob(QueueBinder.parameters(jobB));release.countDown();waitFor{QueueBinder.loopEnded(service)}
            assertTrue(FailureLeaseServiceShadow.finishes.isEmpty());assertEquals(1,QueueBinder.completed.get());assertTrue(QueueBinder.autoStops.get()>0)
            assertEquals(1,reads.get());assertEquals(1,calls.get());assertEquals(1,f.opened.get());assertEquals(1,f.closed.get())
            assertTrue(f.storesForRead().resources.tasks.runtimeView(a.taskId)!!.receipts.isEmpty())
            assertEquals(ReminderPostObservation.POST_RETURNED,f.stores.resources.tasks.runtimeView(b.taskId)!!.receipts.single().result!!.observation)
        }finally{release.countDown();controller.destroy()}
    }}
}

@Implements(JobService::class)
class FailureLeaseServiceShadow:ShadowJobService() {
    @Implementation override fun jobFinished(params:JobParameters,wantsReschedule:Boolean){
        finishes.add(params to wantsReschedule);super.jobFinished(params,wantsReschedule)
    }
    companion object {val finishes=ConcurrentLinkedQueue<Pair<JobParameters,Boolean>>()}
}
