package com.t1doo.aiyou.records

import android.app.Application
import android.app.job.*
import android.content.*
import android.database.Cursor
import android.database.sqlite.*
import android.os.Looper
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowJobService

/** Actual receiver -> actual JobWorkItem -> actual service -> actual owner -> SQL/AEAD.
 * Only OS queue Binder and effect driver are controlled; no real permission/alarm/notification call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,shadows=[QueueSchedulerShadow::class,QueueParametersShadow::class])
class ReminderJobChainTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private var runtime:AndroidReminderRuntime?=null
    @Before fun reset(){QueueBinder.queue.clear();QueueBinder.completed.set(0);QueueBinder.cancelled.set(0);QueueBinder.entered=null;QueueBinder.release=null;QueueBinder.returned.set(0);QueueBinder.lastItem=null;QueueBinder.lastJob=null;QueueBinder.workOwners.clear();QueueBinder.autoStops.set(0);QueueBinder.outcome=JobScheduler.RESULT_SUCCESS;QueueBinder.throwOnEnqueue=false;QueueBinder.publishBeforeReturn=false}
    @After fun cleanup(){runtime?.let{it.scope.cancel();it.bridge.close()};AndroidReminderRuntime.replaceForTest(null);com.t1doo.aiyou.ui.processAppAccessPreferenceOperations.runner=null}
    private fun install(f:NativeSqlFixture,driver:ReminderRuntimeDriver,unlocked:()->Boolean={true},access:suspend ()->Boolean={true}):AndroidReminderRuntime {
        val runtime=AndroidReminderRuntime(app){bridge->ReminderProcessOwner(bridge,unlocked,access,{f.open()}, {driver},{f.now}){f.fingerprint}}
        AndroidReminderRuntime.replaceForTest(runtime);this.runtime=runtime;return runtime
    }
    private fun receive(envelope:ReminderEnvelope){
        val receiver=AlarmReminderReceiver()
        app.registerReceiver(receiver,IntentFilter(ReminderIntents.ALARM).apply{addDataScheme("aiyou-reminder")},Context.RECEIVER_NOT_EXPORTED)
        try{
            app.sendBroadcast(ReminderIntents.alarm(app,envelope).setComponent(null).setPackage(app.packageName))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }finally{app.unregisterReceiver(receiver)}
    }
    private fun waitFor(predicate:()->Boolean){
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(!predicate()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertTrue("Controlled chain did not finish within test budget",predicate())
    }
    @Test fun boundedWholeChainPublishesOnlyFreshAlarmAndAdvancesPastAndFailedPrefixes(){NativeSqlFixture().use{f->
        repeat(20){f.task("a-past-$it",false)}
        repeat(17){f.task("b-failed-$it",true)};f.task("z-future",true)
        val due=f.task("trigger",false);val token=f.token(due)
        val schedules=AtomicInteger();val posts=AtomicInteger()
        val driver=object:ReminderRuntimeDriver {
            override fun availability()=f.available
            override fun schedule(command:TaskReminderCommand):TaskReminderPhase{ schedules.incrementAndGet();if(command.token.taskId.startsWith("b-failed"))error("Synthetic port outcome unknown");return TaskReminderPhase.SCHEDULED }
            override fun cancel(command:TaskReminderCommand)=TaskReminderPhase.REVOKED
            override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation{posts.incrementAndGet();return ReminderPostObservation.POST_RETURNED}
        }
        QueueBinder.queue.add(JobWorkItem(Intent().putExtra(AndroidReminderRuntime.NONCE,"old-process").putExtra(AndroidReminderRuntime.TICKET,"old")))
        install(f,driver)
        val envelope=f.stores.resources.envelopes.seal(token,ReminderEnvelopePurpose.ALARM)
        receive(envelope);waitFor{QueueBinder.returned.get()==1&&QueueBinder.queue.size==1};receive(envelope)
        assertEquals(1,QueueBinder.queue.size);assertEquals(1,QueueBinder.cancelled.get())
        val controller=Robolectric.buildService(ReminderJobService::class.java).create();val service=controller.get()
        try{
            assertTrue(service.onStartJob(QueueBinder.parameters()))
            waitFor{f.closed.get()==1}
            assertEquals(1,posts.get());assertTrue(schedules.get() in 18..36)
            assertEquals(TaskReminderPhase.NOTIFICATION_POSTED,f.storesForRead().resources.tasks.runtimeView("trigger")!!.task.reminder!!.phase)
            assertEquals(1,QueueBinder.completed.get())
            assertFalse(Shadows.shadowOf(service).isJobFinished);assertTrue(QueueBinder.autoStops.get()>0)
        }finally{controller.destroy()}
    }}
    @Test fun stoppingDuringActualPortWaitsForOriginalReturnAndNeverStartsNextCommand(){NativeSqlFixture().use{f->
        f.task("future",true);val token=f.token(f.task("trigger",false))
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        val driver=object:ReminderRuntimeDriver {
            override fun availability()=f.available
            override fun schedule(command:TaskReminderCommand):TaskReminderPhase{calls.incrementAndGet();return TaskReminderPhase.SCHEDULED}
            override fun cancel(command:TaskReminderCommand):TaskReminderPhase{calls.incrementAndGet();return TaskReminderPhase.REVOKED}
            override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation{calls.incrementAndGet();entered.countDown();check(release.await(5,TimeUnit.SECONDS));return ReminderPostObservation.POST_RETURNED}
        }
        install(f,driver);receive(f.stores.resources.envelopes.seal(token,ReminderEnvelopePurpose.ALARM));waitFor{QueueBinder.queue.isNotEmpty()}
        val controller=Robolectric.buildService(ReminderJobService::class.java).create();val service=controller.get();val params=QueueBinder.parameters()
        try{
            service.onStartJob(params);assertTrue(entered.await(5,TimeUnit.SECONDS));assertFalse(service.onStopJob(params))
            assertEquals(0,f.closed.get());assertEquals(0,QueueBinder.completed.get())
            release.countDown();waitFor{f.closed.get()==1}
            assertEquals(1,calls.get());assertEquals(0,QueueBinder.completed.get());assertFalse(Shadows.shadowOf(service).isJobFinished)
            val view=f.storesForRead().resources.tasks.runtimeView("trigger")!!
            assertEquals(TaskReminderPhase.NOTIFICATION_POSTED,view.task.reminder!!.phase)
            assertEquals(ReminderPostObservation.POST_RETURNED,view.receipts.single().result!!.observation)
        }finally{release.countDown();controller.destroy()}
    }}
    @Test fun handoffDeadlineRetiresLateSuccessfulEnqueueBeforeAnyColdRead(){NativeSqlFixture().use{f->
        val token=f.token(f.task("trigger",false));val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val driver=object:ReminderRuntimeDriver {
            override fun availability():TaskReminderAvailability=error("No capability")
            override fun schedule(command:TaskReminderCommand):TaskReminderPhase=error("No schedule")
            override fun cancel(command:TaskReminderCommand):TaskReminderPhase=error("No cancel")
            override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation=error("No post")
        }
        install(f,driver);QueueBinder.entered=entered;QueueBinder.release=release
        try{
            receive(f.stores.resources.envelopes.seal(token,ReminderEnvelopePurpose.ALARM));assertTrue(entered.await(5,TimeUnit.SECONDS))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
            release.countDown();waitFor{QueueBinder.queue.isNotEmpty()}
            val controller=Robolectric.buildService(ReminderJobService::class.java).create();val service=controller.get()
            try{service.onStartJob(QueueBinder.parameters());waitFor{QueueBinder.loopEnded(service)};assertEquals(0,f.opened.get());assertEquals(1,QueueBinder.completed.get())}
            finally{controller.destroy()}
        }finally{release.countDown();QueueBinder.entered=null;QueueBinder.release=null}
    }}
    @Test fun oldProcessItemsAndPreUnlockReceiverCannotReadPreferencesKeysOrDatabase(){NativeSqlFixture().use{f->
        val reads=AtomicInteger();val driver=object:ReminderRuntimeDriver {
            override fun availability():TaskReminderAvailability=error("No capability observation")
            override fun schedule(command:TaskReminderCommand):TaskReminderPhase=error("No schedule")
            override fun cancel(command:TaskReminderCommand):TaskReminderPhase=error("No cancel")
            override fun post(token:TaskReminderToken,specification:String):ReminderPostObservation=error("No post")
        }
        val token=f.token(f.task("trigger",false));install(f,driver,{false},{reads.incrementAndGet();true})
        receive(f.stores.resources.envelopes.seal(token,ReminderEnvelopePurpose.ALARM));assertTrue(QueueBinder.queue.isEmpty())
        QueueBinder.queue.add(JobWorkItem(Intent().putExtra(AndroidReminderRuntime.NONCE,"retired-process").putExtra(AndroidReminderRuntime.TICKET,"stale")))
        val controller=Robolectric.buildService(ReminderJobService::class.java).create();val service=controller.get()
        try{assertFalse(service.onStartJob(QueueBinder.parameters()));assertFalse(Shadows.shadowOf(service).isJobFinished);assertEquals(0,reads.get());assertEquals(0,f.opened.get());assertEquals(0,QueueBinder.completed.get())}
        finally{controller.destroy()}
    }}
}

internal object QueueBinder {
    @Volatile var entered:CountDownLatch?=null;@Volatile var release:CountDownLatch?=null
    val workOwners=ConcurrentHashMap<JobWorkItem,JobInfo>()
    val autoStops=AtomicInteger()
    fun loopEnded(service:ReminderJobService)=org.robolectric.util.ReflectionHelpers.getField<Any?>(service,"running")==null
    @Volatile var lastJob:JobInfo?=null
    fun parameters(job:JobInfo?=lastJob):JobParameters=Shadow.newInstanceOf(JobParameters::class.java).also{
        org.robolectric.shadow.api.Shadow.extract<QueueParametersShadow>(it).job=job
    }
    @Volatile var lastItem:JobWorkItem?=null
    @Volatile var outcome=JobScheduler.RESULT_SUCCESS
    @Volatile var throwOnEnqueue=false
    @Volatile var publishBeforeReturn=false
    val returned=AtomicInteger()
    val queue=ConcurrentLinkedQueue<JobWorkItem>();val completed=AtomicInteger();val cancelled=AtomicInteger()
}
@Implements(className="android.app.JobSchedulerImpl",isInAndroidSdk=false)
class QueueSchedulerShadow {
    @Implementation fun cancel(id:Int){QueueBinder.cancelled.incrementAndGet();QueueBinder.queue.clear()}
    @Implementation fun enqueue(job:JobInfo,work:JobWorkItem):Int{
        check(!job.isPersisted && job.id==AndroidReminderRuntime.JOB && job.networkType==JobInfo.NETWORK_TYPE_NONE)
        check(work.intent.extras!!.keySet()==setOf(AndroidReminderRuntime.NONCE,AndroidReminderRuntime.TICKET))
        val outcome=QueueBinder.outcome;val throwing=QueueBinder.throwOnEnqueue;val early=QueueBinder.publishBeforeReturn
        QueueBinder.lastJob=job;QueueBinder.lastItem=work;QueueBinder.workOwners[work]=job
        if(early)QueueBinder.queue.add(work)
        QueueBinder.entered?.countDown()
        try{
            QueueBinder.release?.let{check(it.await(5,TimeUnit.SECONDS))}
            if(throwing)error("Synthetic late enqueue failure")
            if(outcome==JobScheduler.RESULT_SUCCESS&&!early)QueueBinder.queue.add(work)
            return outcome
        }finally{QueueBinder.returned.incrementAndGet()}
    }
}
@Implements(JobParameters::class)
class QueueParametersShadow {
    var job:JobInfo?=null
    var beforeDequeue:(()->Unit)?=null
    @Implementation fun getJobId():Int=job?.id?:AndroidReminderRuntime.JOB
    @Implementation fun getExtras():android.os.PersistableBundle=job?.extras?:android.os.PersistableBundle()
    private val executing=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<JobWorkItem,Boolean>())
    @Implementation fun dequeueWork():JobWorkItem?{
        beforeDequeue?.invoke()
        val generation=job?.extras?.getString(AndroidReminderRuntime.GENERATION)
        val item=QueueBinder.queue.firstOrNull{QueueBinder.workOwners[it]?.extras?.getString(AndroidReminderRuntime.GENERATION).let{owner->owner==null||owner==generation}}
        if(item!=null&&QueueBinder.queue.remove(item)){executing.add(item);return item}
        if(executing.isEmpty())QueueBinder.autoStops.incrementAndGet()
        return null
    }
    @Implementation fun completeWork(work:JobWorkItem){check(executing.remove(work));QueueBinder.workOwners.remove(work);QueueBinder.completed.incrementAndGet()}
}

internal class NativeSqlFixture:AutoCloseable {
    private val db=SQLiteDatabase.create(null)
    private val key=ByteArray(32){(it+3).toByte()}
    val opened=AtomicInteger();val closed=AtomicInteger()
    val available=TaskReminderAvailability(true,TaskReminderPermission.GRANTED,TaskReminderPermission.GRANTED,true)
    val fingerprint=ReminderPlatformFingerprint(8,"UTC",2,available)
    val date=ManualTaskDateInput("2042-11-19","09:30","UTC").parse()!!
    val now=date.instant()!!.toEpochMilli()
    private val connection=object:RecordsSqlConnection {
        override fun execute(sql:String,args:List<Any?>):Int{db.execSQL(sql,args.toTypedArray());return db.rawQuery("SELECT changes()",null).use{it.moveToFirst();it.getInt(0)}}
        override fun query(sql:String,args:List<Any?>):List<List<Any?>> {
            val factory=SQLiteDatabase.CursorFactory{_,driver,editTable,query->
                args.forEachIndexed{index,value->when(value){null->query.bindNull(index+1);is ByteArray->query.bindBlob(index+1,value);is Number->query.bindLong(index+1,value.toLong());else->query.bindString(index+1,value as String)}}
                SQLiteCursor(driver,editTable,query)
            }
            return db.rawQueryWithFactory(factory,sql,null,"").use{cursor->buildList{while(cursor.moveToNext())add((0 until cursor.columnCount).map{n->when(cursor.getType(n)){Cursor.FIELD_TYPE_NULL->null;Cursor.FIELD_TYPE_INTEGER->cursor.getLong(n);Cursor.FIELD_TYPE_FLOAT->cursor.getDouble(n);Cursor.FIELD_TYPE_BLOB->cursor.getBlob(n);else->cursor.getString(n)}})}}
        }
    }
    val sql=object:RecordsSqlDatabase {
        override fun <T> transaction(block:(RecordsSqlConnection)->T):T {db.beginTransaction();try{return block(connection).also{db.setTransactionSuccessful()}}finally{db.endTransaction()}}
    }
    var stores:ReminderRuntimeStores;private set
    init{
        val contract=JSONObject(File(System.getProperty("aiyou.root"),"core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/15.json").readText()).getJSONObject("database")
        val entities=contract.getJSONArray("entities")
        for(n in 0 until entities.length()){val e=entities.getJSONObject(n);db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))}
        for(n in 0 until entities.length()){
            val e=entities.getJSONObject(n);val indices=(e.optJSONArray("indices")?:org.json.JSONArray())
            for(i in 0 until indices.length())db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
        }
        val setup=contract.getJSONArray("setupQueries");for(n in 0 until setup.length())db.execSQL(setup.getString(n))
        db.execSQL(ManualTaskSqlSchema.guard);db.version=15
        validateExistingReminderSchema(connection,contract)
        stores=ReminderRuntimeStores(sql,key)
    }
    fun validate()=validateExistingReminderSchema(connection,JSONObject(File(System.getProperty("aiyou.root"),"core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/15.json").readText()).getJSONObject("database"))
    fun task(id:String,future:Boolean):ManualTaskSnapshot {
        val plan=if(future)ManualTaskDateInput("2042-11-20","09:30","UTC").parse()!! else date
        val task=stores.manualTasks.create(ManualTaskCreate(id,"合成原生接线任务",1,"UTC","create-$id",plan))
        return stores.resources.tasks.changeReminder(TaskReminderMutation(task,TaskReminderMode.EXACT,2,"confirm-$id"))
    }
    fun token(task:ManualTaskSnapshot)=TaskReminderToken(task.id,task.reminder!!.id,task.reminder!!.scheduleVersion)
    fun open():OwnedReminderRuntime {
        opened.incrementAndGet()
        return object:OwnedReminderRuntime {
            override val resources=stores.resources
            override fun close()=Unit
            override fun awaitClosed(){stores.close();closed.incrementAndGet()}
        }
    }
    fun storesForRead():ReminderRuntimeStores=ReminderRuntimeStores(sql,key).also{stores=it}
    override fun close(){stores.close();db.close();key.fill(0)}
}
