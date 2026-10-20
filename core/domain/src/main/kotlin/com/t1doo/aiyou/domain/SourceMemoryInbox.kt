package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.ManualTags
import com.t1doo.aiyou.model.SourceMemorySnapshot

/** Immutable creation ordering, not a stored query or a grant to review anything. */
data class SourceMemoryInboxCursor(val createdAtMillis: Long, val id: String) {
    init { require(createdAtMillis >= 0); ManualTags.identifier(id) }
    override fun toString() = "SourceMemoryInboxCursor(redacted)"
}

interface SourceMemoryInboxSnapshot {
    val cursor: SourceMemoryInboxCursor?
    val next: SourceMemoryInboxCursor?
    val pending: List<SourceMemorySnapshot>
}

/** Explicit, bounded local browsing. Navigation never accepts or changes a candidate. */
interface SourceMemoryInboxRepository {
    fun capture(cursor: SourceMemoryInboxCursor? = null): SourceMemoryInboxSnapshot
    fun publishCurrent(snapshot: SourceMemoryInboxSnapshot, publish: () -> Unit): Boolean
    fun resolve(snapshot: SourceMemoryInboxSnapshot, item: SourceMemorySnapshot, publish: () -> Unit): Boolean
}
