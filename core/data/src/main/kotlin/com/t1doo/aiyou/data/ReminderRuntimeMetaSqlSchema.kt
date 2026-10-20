package com.t1doo.aiyou.data

internal object ReminderRuntimeMetaSqlSchema {
    const val createTable="CREATE TABLE IF NOT EXISTS reminder_runtime_meta (slot INTEGER NOT NULL, version INTEGER NOT NULL, ciphertext BLOB NOT NULL, PRIMARY KEY(slot))"
    val migration14To15=listOf(createTable)
}
