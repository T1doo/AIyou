package com.t1doo.aiyou.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.t1doo.aiyou.records.LocalRecordsViewModel
import java.io.File

internal class AppAccessSession(val id: Long, val owner: ViewModelStoreOwner,
    val records: LocalRecordsViewModel, val storageUsage: StorageUsageViewModel)

/** Retained across rotation, never SavedState. Each admitted session owns its own records VM store. */
internal class AppAccessViewModel internal constructor(application: Application, storage: AppAccessPreferenceStorage) :
    AndroidViewModel(application) {
    constructor(application: Application) : this(application,
        FileAppAccessPreference(File(application.filesDir, "access/startup")))

    private var current: AppAccessSession? = null
    val controller = AppAccessController(storage, viewModelScope, ::retire, processAppAccessRetirement::awaitClosed)

    fun session(id: Long): AppAccessSession {
        check(controller.state.value.let { it.canReadRecords && it.session == id })
        current?.let { check(it.id == id); return it }
        check(processAppAccessRetirement.isClear())
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val provider = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(getApplication()))
        return try {
            AppAccessSession(id, owner, provider[LocalRecordsViewModel::class.java], provider[StorageUsageViewModel::class.java])
                .also { current = it }
        } catch (failure: Exception) { owner.viewModelStore.clear(); throw failure }
    }

    fun retirementReady(id: Long): Boolean = current?.let {
        it.id == id && it.records.canRetireForAppAccess()
    } == true

    /** Observes the already admitted production owner; never constructs another ViewModel. */
    fun admittedRecords(): LocalRecordsViewModel? {
        val state = controller.state.value
        return current?.takeIf { state.canReadRecords && it.id == state.session }?.records
    }

    private fun retire(): Boolean {
        val previous = current ?: return true
        if (!previous.records.retireForAppAccess()) return false
        processAppAccessRetirement.register(previous.records.appAccessClosure)
        current = null
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
