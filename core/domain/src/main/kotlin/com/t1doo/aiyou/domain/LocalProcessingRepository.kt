package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

/** Local choices only. This capability cannot grant remote processing. */
enum class LocalProcessingChoice { AI_NONE, LOCAL_ONLY }
data class LocalProcessingVersion(val source: SourceVersion, val generation: Long) {
    init { require(generation >= 0) }
}
data class LocalProcessingSnapshot(val source: SourceVersion, val sourceHash: String,
    val generation: Long, val policy: ProcessingPolicy) {
    init { require(generation >= 0 && sourceHash.matches(Regex("[0-9a-f]{64}"))) }
    override fun toString() = "LocalProcessingSnapshot(redacted)"
}
data class LocalProcessingChange(val expected: LocalProcessingSnapshot,
    val target: LocalProcessingChoice, val mutationId: String) {
    init {
        ManualTags.identifier(mutationId)
        require(expected.policy.name in LocalProcessingChoice.entries.map { it.name } && expected.policy.name != target.name)
    }
    override fun toString() = "LocalProcessingChange(redacted)"
}

/** Blocking IO borrowed from EntryRepository's encrypted SQL owner. */
interface LocalProcessingRepository {
    fun read(source: SourceVersion): LocalProcessingSnapshot
    fun versions(): Map<String, LocalProcessingVersion>
    /** Atomic generation CAS plus exact durable receipt. A replay never resets subsequent choices. */
    fun change(request: LocalProcessingChange): LocalProcessingSnapshot
}
class LocalProcessingConflict : IllegalStateException("Record processing scope changed")
class LocalProcessingStorageFailure : IllegalStateException("Record processing scope unavailable")
