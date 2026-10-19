package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.EncryptedEntryRepository
import com.t1doo.aiyou.ui.FileUiPreferences
import java.io.File

/** Platform composition only; the data owner reads this device default at new-slot creation. */
internal fun openProductionRecordStore(application: Application): EncryptedEntryRepository {
    val defaults = FileUiPreferences(File(application.filesDir, "display/preferences"))
    return EncryptedEntryRepository.open(application, defaults::newCapturePolicy)
}
