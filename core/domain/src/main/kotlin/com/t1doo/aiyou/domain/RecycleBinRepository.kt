package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.RecycleMutation
import com.t1doo.aiyou.model.RecycleRecord

/** Blocking, borrowed capability over the records owner's encrypted database and SQL lifetime. */
interface RecycleBinRepository {
    fun records(): List<RecycleRecord>
    fun read(entryId: String): RecycleRecord
    /** Durable idempotent receipt plus visibility/authorization change in one transaction. */
    fun apply(mutation: RecycleMutation): RecycleRecord
}
class RecycleConflict : IllegalStateException("Record visibility or version changed")
class RecycleStorageFailure : IllegalStateException("Encrypted recycle bin unavailable")
