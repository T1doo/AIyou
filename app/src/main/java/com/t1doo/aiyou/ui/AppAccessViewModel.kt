package com.t1doo.aiyou.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.t1doo.aiyou.records.LocalRecordsViewModel
import com.t1doo.aiyou.records.AndroidReminderRuntime
import com.t1doo.aiyou.records.ReminderVmRuntime
import com.t1doo.aiyou.records.openProductionRecordStore
import java.io.File

internal class AppAccessSession(val id: Long, val owner: ViewModelStoreOwner,
    val records: LocalRecordsViewModel, val storageUsage: StorageUsageViewModel)

/** Retained across rotation, never SavedState. Each admitted session owns its own records VM store. */
internal class AppAccessViewModel internal constructor(application: Application, storage: AppAccessPreferenceStorage) :
    AndroidViewModel(application) {
    constructor(application: Application) : this(application,
        FileAppAccessPreference(File(application.filesDir, "access/startup")))

    private var current: AppAccessSession? = null
    private var runtime:ReminderVmRuntime?=null
    private val reminders=AndroidReminderRuntime.get(application)
    val controller = AppAccessController(storage, viewModelScope, ::retire, ::prepare,
        disposedSession={reminders.owners.releaseFront()},
        changed={state->when(state.phase){
            AppAccessPhase.OPENING->reminders.owners.frontOpening()
            AppAccessPhase.OPEN->runtime?.let{reminders.owners.frontReady(it)}
            AppAccessPhase.LOADING,AppAccessPhase.FAILED->reminders.owners.locked(true)
            else->reminders.owners.locked()
        }})

    private suspend fun prepare(id:Long) {
        current?.let{previous->
            processAppAccessRetirement.register(previous.records.appAccessClosure)
            current=null;runtime=null;previous.owner.viewModelStore.clear()
        }
        processAppAccessRetirement.awaitClosed()
        check(current==null)
        val (store,port)=reminders.owners.prepareFront{openProductionRecordStore(getApplication())}
        val owner=object:ViewModelStoreOwner{override val viewModelStore=ViewModelStore()}
        var records:LocalRecordsViewModel?=null
        try {
            val factory=object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T {
                    check(modelClass==LocalRecordsViewModel::class.java)
                    return LocalRecordsViewModel(getApplication(),{store},reminderRuntime=port,admittedStore=store) as T
                }
            }
            records=ViewModelProvider(owner,factory)[LocalRecordsViewModel::class.java]
            val usage=ViewModelProvider(owner,ViewModelProvider.AndroidViewModelFactory.getInstance(getApplication()))[StorageUsageViewModel::class.java]
            current=AppAccessSession(id,owner,records,usage);runtime=port
            records.awaitInitialOpen()
        }catch(f:Throwable){
            current=null;runtime=null
            if(records!=null){processAppAccessRetirement.register(records.appAccessClosure);owner.viewModelStore.clear()}
            else port.closeStore{store.close();store.awaitClosed()}
            throw f
        }
    }

    fun session(id: Long): AppAccessSession {
        check(controller.state.value.let { it.canReadRecords && it.session == id })
        return requireNotNull(current).also{check(it.id==id)}
    }

    fun retirementReady(id: Long): Boolean = current?.let {
        it.id == id && it.records.canRetireForAppAccess()
    } == true

    /** Observes the already admitted production owner; never constructs another ViewModel. */
    fun admittedRecords(): LocalRecordsViewModel? {
        val state = controller.state.value
        return current?.takeIf { state.canReadRecords && it.id == state.session }?.records
    }

    suspend fun reminderClick(id:Long,envelope:com.t1doo.aiyou.domain.ReminderEnvelope,restoreGeneration:Long):Boolean {
        if(reminders.owners.navigationGeneration()!=restoreGeneration)return false
        val session=current?.takeIf{it.id==id&&controller.state.value.let{s->s.canReadRecords&&s.session==id}}?:return false
        val port=runtime?:return false
        val task=try{reminders.owners.click(port,envelope)}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:Exception){null}?:return false
        return reminders.owners.navigationGeneration()==restoreGeneration && current===session && controller.state.value.let{it.canReadRecords&&it.session==id} &&
            session.records.state.value.tasks.items.any{it.id==task.id&&it.version==task.version}
    }

    private fun retire(): Boolean {
        val previous = current ?: return true
        if (!previous.records.retireForAppAccess()) return false
        processAppAccessRetirement.register(previous.records.appAccessClosure)
        current = null;runtime=null
        previous.owner.viewModelStore.clear()
        return true
    }

    override fun onCleared() {
        controller.dispose()
        current?.let {
            processAppAccessRetirement.register(it.records.appAccessClosure)
            it.owner.viewModelStore.clear()
        }
        current = null
        super.onCleared()
    }
}
