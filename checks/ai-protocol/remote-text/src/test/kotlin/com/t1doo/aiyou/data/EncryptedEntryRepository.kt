package com.t1doo.aiyou.data

import android.app.Application
import com.t1doo.aiyou.domain.EntryRepository

/** No ledger or fallback: host checks must explicitly inject a real SQLite records adapter. */
object EncryptedEntryRepository {
    const val MAX_TEXT_LENGTH = 100_000
    fun open(@Suppress("UNUSED_PARAMETER") application: Application): EntryRepository =
        error("An explicit SQLite records adapter is required by host checks")
}
