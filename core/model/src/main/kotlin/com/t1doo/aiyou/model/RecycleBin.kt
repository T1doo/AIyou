package com.t1doo.aiyou.model

/** Visibility generation changes on every move/restore, independently of original revisions. */
class RecycleRecord(val entry: StoredEntry, val generation: Long, val deleted: Boolean,
    val tags: ManualTagSnapshot, val revisionCount: Int, val attachmentCount: Int) {
    init {
        ManualTags.identifier(entry.id)
        require(entry.currentRevision > 0 && generation >= 0 && revisionCount > 0 && attachmentCount >= 0)
        require(tags.entryId == entry.id)
    }
    override fun toString() = "RecycleRecord(deleted=$deleted, redacted)"
}

/** An explicit action bound to exactly the version and visibility the user confirmed. */
class RecycleMutation(val source: SourceVersion, val expectedGeneration: Long,
    val expectedDeleted: Boolean, val mutationId: String) {
    init {
        ManualTags.identifier(source.sourceId); ManualTags.identifier(mutationId)
        require(source.revision in 1..Int.MAX_VALUE.toLong() && expectedGeneration >= 0)
    }
    override fun toString() = "RecycleMutation(redacted)"
}
