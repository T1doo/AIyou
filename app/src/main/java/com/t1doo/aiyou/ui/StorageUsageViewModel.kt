package com.t1doo.aiyou.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.t1doo.aiyou.data.RecordsStorageUsageReader
import java.io.File

class StorageUsageViewModel(application: Application) : AndroidViewModel(application) {
    val controller = StorageUsageController(viewModelScope,
        RecordsStorageUsageReader(File(application.noBackupFilesDir, "records")))

    override fun onCleared() {
        controller.dispose()
        super.onCleared()
    }
}
