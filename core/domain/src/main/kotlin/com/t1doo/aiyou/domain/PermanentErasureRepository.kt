package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.PermanentErasureIntent
import com.t1doo.aiyou.model.PermanentErasureReceipt
import com.t1doo.aiyou.model.PermanentErasurePage

/** Borrowed local capability; a durable fence is distinct from complete file and SQL cleanup. */
interface PermanentErasureRepository {
    fun read(entryId: String): PermanentErasureReceipt?
    fun pending(limit: Int = 64, afterEntryId: String? = null): PermanentErasurePage
    suspend fun erase(intent: PermanentErasureIntent): PermanentErasureReceipt
}
class PermanentErasureConflict : IllegalStateException("Erasure target or confirmation changed")
class PermanentErasureIncomplete : IllegalStateException("Local erasure is incomplete")

/** Close seals immediately. Only an external IO owner may wait for actual operations to exit. */
interface AwaitableRecordStorage : AutoCloseable {
    fun awaitClosed()
}
