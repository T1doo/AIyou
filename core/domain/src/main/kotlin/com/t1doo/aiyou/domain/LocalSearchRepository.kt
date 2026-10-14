package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.ManualTagSnapshot
import com.t1doo.aiyou.model.StoredEntry

/** Memory-only user lookup. No query or document may be logged or saved by the caller. */
class LocalSearchRequest(val query: String, val exact: Boolean = false,
    val from: String = "", val through: String = "", val tag: String? = null) {
    override fun toString() = "LocalSearchRequest(redacted)"
}

/** Actual implementations carry a private proof and records owner, never an unchecked ID list. */
interface LocalSearchSnapshot {
    val epoch: Long
    val entries: List<StoredEntry>
    val tags: Map<String, ManualTagSnapshot>
    val pending: Boolean
}

data class LocalSearchProgress(val processed: Int, val remaining: Boolean, val epoch: Long)

/** Borrowed from the same encrypted records owner. All operations and callbacks run on IO. */
interface LocalSearchRepository {
    fun batch(): LocalSearchProgress
    fun rebuild(): Long
    fun epoch(): Long
    fun candidates(request: LocalSearchRequest): LocalSearchSnapshot
    /** Verification and actual publication share one counted SQL write transaction/lease. */
    fun publishCurrent(snapshot: LocalSearchSnapshot, publish: () -> Unit): Boolean
    /** Reads only the visible current original; executes actual location publication under the lease. */
    fun resolve(snapshot: LocalSearchSnapshot, entryId: String, publish: (StoredEntry) -> Unit): Boolean
}

open class LocalSearchFailure : IllegalStateException("Local search unavailable")
class LocalSearchStale : LocalSearchFailure()
class LocalSearchTooBroad : LocalSearchFailure()
class LocalSearchInvalid : LocalSearchFailure()
