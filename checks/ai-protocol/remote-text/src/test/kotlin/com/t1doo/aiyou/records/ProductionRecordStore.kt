package com.t1doo.aiyou.records

/** Existing Android opening shell; SQL tests inject their actual owner and never call this factory. */
internal fun openProductionRecordStore(application: android.app.Application): com.t1doo.aiyou.domain.EntryRepository =
    com.t1doo.aiyou.data.EncryptedEntryRepository.open(application)
