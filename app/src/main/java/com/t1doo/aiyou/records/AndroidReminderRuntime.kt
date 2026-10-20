package com.t1doo.aiyou.records

import android.app.job.*
import android.app.Application
import android.content.*
import android.os.UserManager
import android.os.PersistableBundle
import java.util.UUID
import android.provider.Settings
import com.t1doo.aiyou.data.ExistingReminderStore
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.ui.*
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-context composition. No receiver, Activity, SavedState, or process restart can mint a lease. */
internal class AndroidReminderRuntime(context:Context,createOwner:((ReminderOperationBridge)->ReminderProcessOwner)?=null) {
    private val app:Application=context.applicationContext as Application
    val bridge=ReminderOperationBridge()
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val jobExecution=Mutex()
    private val enqueueGate=Mutex()
    private var initialized=false
    private val submissions=Any()
    private class Submission(val key:String,val info:JobInfo){var active=true;var claimed=false;var finishing=false}
    private var submission:Submission?=null
    private fun submissionKey(params:JobParameters):String?=try{
        if(params.jobId!=JOB||params.extras.getString(NONCE)!=owners.nonce)null
        else params.extras.getString(GENERATION)?.takeIf{it.matches(Regex("[a-f0-9-]{36}"))}
    }catch(_:Exception){null}
    fun runKey(params:JobParameters):String?=submissionKey(params)
    fun claimRun(params:JobParameters):String? {
        val key=submissionKey(params)?:return null
        return synchronized(submissions){submission?.takeIf{it.key==key&&it.active&&!it.claimed}?.also{it.claimed=true}?.key}
    }
    /** 0 reads the owned OS queue, 1 waits for an in-flight enqueue, 2 rejects an old generation.
     * Close new admission before the final empty dequeue; dequeueWork itself auto-stops the job. */
    fun dequeueState(key:String,first:Boolean):Int=synchronized(submissions){
        val current=submission
        when{
            current==null||current.key!=key->2
            first||!current.active||owners.hasReadySubmission(key)->0
            owners.hasPendingSubmission(key)->1
            else->{current.active=false;0}
        }
    }
    fun endRun(key:String){
        synchronized(submissions){submission?.takeIf{it.key==key}?.active=false}
        owners.endSubmission(key)
    }
    /** Main-loop terminal claim. The callback only queues JobServiceEngine's local finish message;
     * no business ACK, Binder wait, or repository IO is performed under this admission guard. */
    fun finishFailedRun(key:String,finish:()->Unit)=synchronized(submissions){
        val current=submission?.takeIf{it.key==key&&it.claimed&&!it.finishing}?:return@synchronized
        current.finishing=true;current.active=false;owners.endSubmission(key)
        finish()
    }
    private fun unlocked()=app.getSystemService(UserManager::class.java)?.isUserUnlocked==true
    private val preferences=FileAppAccessPreference(File(app.filesDir,"access/startup"))
    val owners=createOwner?.invoke(bridge)?:ReminderProcessOwner(bridge,::unlocked,
        {processAppAccessPreferenceOperations.performDirect{!preferences.read().enabled}},
        {ExistingReminderStore.open(app)},
        {AndroidReminderDriver(app,it.envelopes)},System::currentTimeMillis,{
            val boot=try{Settings.Global.getInt(app.contentResolver,Settings.Global.BOOT_COUNT).toLong().takeIf{it>=0}}catch(_:Exception){null}
            val info=app.packageManager.getPackageInfo(app.packageName,0)
            @Suppress("DEPRECATION") val version=if(android.os.Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
            // Capability sampling does not require a store/key. Its envelope port is never used here.
            ReminderPlatformFingerprint(boot,ZoneId.systemDefault().id,version,AndroidReminderDriver(app,NoEnvelopes).availability())
        })
    init {
        processAppAccessPreferenceOperations.runner=object:AppAccessPreferenceRunner {
            override suspend fun <T> run(action:suspend ()->T):T=bridge.runtime{bridge.gate.inspectAdmission{runBlocking{action()}}}
        }
    }
    suspend fun enqueue(ticket:ReminderProcessOwner.Ticket):Boolean=withContext(Dispatchers.IO){enqueueGate.withLock{
        if(!ticket.active)return@withLock false
        val scheduler=app.getSystemService(JobScheduler::class.java)?:return@withLock false
        if(!initialized){scheduler.cancel(JOB);initialized=true}
        if(!ticket.active)return@withLock false
        val batch=synchronized(submissions){
            val current=submission?.takeIf{it.active}?:run{
                val key=UUID.randomUUID().toString()
                val extras=PersistableBundle().apply{putString(NONCE,owners.nonce);putString(GENERATION,key)}
                Submission(key,JobInfo.Builder(JOB,ComponentName(app,ReminderJobService::class.java))
                    .setOverrideDeadline(0).setPersisted(false).setExtras(extras).build()).also{submission=it}
            }
            current.takeIf{owners.bindSubmission(ticket,it.key)}
        }?:return@withLock false
        val item=JobWorkItem(Intent().putExtra(NONCE,ticket.nonce).putExtra(TICKET,ticket.id))
        val accepted=scheduler.enqueue(batch.info,item)==JobScheduler.RESULT_SUCCESS
        if(!accepted||!synchronized(submissions){batch.active&&owners.markEnqueued(ticket,batch.key)}){owners.forget(ticket);false}else true
    }}
    companion object {
        const val JOB=0x41495952
        const val NONCE="process-nonce";const val TICKET="ram-ticket";const val GENERATION="job-generation"
        @Volatile private var instance:AndroidReminderRuntime?=null
        internal fun replaceForTest(runtime:AndroidReminderRuntime?):AndroidReminderRuntime?=synchronized(this){instance.also{instance=runtime}}
        fun get(context:Context):AndroidReminderRuntime=instance?:synchronized(this){instance?:AndroidReminderRuntime(context).also{instance=it}}
    }
    private object NoEnvelopes:com.t1doo.aiyou.domain.ReminderEnvelopePort {
        override fun identity(token:TaskReminderToken):String=error("No envelope access")
        override fun seal(token:TaskReminderToken,purpose:com.t1doo.aiyou.domain.ReminderEnvelopePurpose):com.t1doo.aiyou.domain.ReminderEnvelope=error("No envelope access")
        override fun open(envelope:com.t1doo.aiyou.domain.ReminderEnvelope,purpose:com.t1doo.aiyou.domain.ReminderEnvelopePurpose):TaskReminderToken=error("No envelope access")
        override fun close()=Unit
    }
}
