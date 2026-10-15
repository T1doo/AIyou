package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

interface FactRecapSnapshot {
    val range: FactRecapRange
    val cards: List<FactRecapCard>
    val recordCount: Int get()=cards.size
    val imageCount: Int get()=cards.sumOf { it.images.size }
}

/** Borrowed IO capability over the same encrypted owner. No persisted recap or background job. */
interface FactRecapRepository {
    fun capture(range: FactRecapRange): FactRecapSnapshot
    /** Complete range membership, body/version, tags, visibility and image ledger checked under one lease. */
    fun publishCurrent(snapshot: FactRecapSnapshot, publish: ()->Unit): Boolean
    fun resolve(snapshot: FactRecapSnapshot, source: SourceVersion, publish: ()->Unit): Boolean
}
class FactRecapTooBroad : IllegalStateException("Complete recap exceeds local bound")
class FactRecapStorageFailure : IllegalStateException("Local recap unavailable")
