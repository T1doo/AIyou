package com.t1doo.aiyou.model

import java.util.Collections

/** Explicit record-level names, not inferred identities, contacts or relationships. */
class ManualPeopleSnapshot(val entryId: String, val version: Long, names: List<String>,
    val sourceRevision: Int, val sourceHash: String, val generation: Long) {
    val names: List<String> = Collections.unmodifiableList(ManualTags.canonical(names))
    init {
        ManualTags.identifier(entryId); require(version >= 0 && sourceRevision > 0 && generation >= 0 && (version > 0 || names.isEmpty()))
        require(sourceHash.matches(Regex("[0-9a-f]{64}")))
    }
    override fun toString() = "ManualPeopleSnapshot(version=$version, redacted)"
}

class ManualPeopleMutation(val before: ManualPeopleSnapshot, names: List<String>, val mutationId: String) {
    val names: List<String> = Collections.unmodifiableList(ManualTags.canonical(names))
    init { ManualTags.identifier(mutationId); require(before.version < Long.MAX_VALUE) }
    override fun toString() = "ManualPeopleMutation(redacted)"
}
