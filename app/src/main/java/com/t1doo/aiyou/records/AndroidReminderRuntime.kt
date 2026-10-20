package com.t1doo.aiyou.records

import android.app.job.*
import android.content.*
import android.os.UserManager
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
    private val app=context.applicationContext
    val bridge=ReminderOperationBridge()
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val jobExecution=Mutex()
    private val enqueueGate=Mutex()
    private var initialized=false
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
        val item=JobWorkItem(Intent().putExtra(NONCE,ticket.nonce).putExtra(TICKET,ticket.id))
        val job=JobInfo.Builder(JOB,ComponentName(app,ReminderJobService::class.java)).setOverrideDeadline(0).setPersisted(false).build()
        scheduler.enqueue(job,item)==JobScheduler.RESULT_SUCCESS
    }}
    companion object {
        const val JOB=0x41495952
        const val NONCE="process-nonce";const val TICKET="ram-ticket"
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
