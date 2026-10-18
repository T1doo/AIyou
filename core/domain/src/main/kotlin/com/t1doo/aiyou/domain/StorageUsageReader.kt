package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.LocalStorageUsage

/** Metadata only. Run on IO; failure must never be presented as an empty successful measurement. */
fun interface StorageUsageReader {
    fun read(): LocalStorageUsage
}
