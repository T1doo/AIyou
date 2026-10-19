package com.t1doo.aiyou.records

/** Queue-only Android opening shell; device-default persistence is tested by the app suite. */
internal fun openProductionRecordStore(application: android.app.Application): com.t1doo.aiyou.domain.EntryRepository =
    com.t1doo.aiyou.data.EncryptedEntryRepository.open(application)
