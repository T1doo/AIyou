package com.t1doo.aiyou.model

import java.util.Collections

data class SourceTagProposal(val name: String, val reference: OriginalTextReference) {
    init { require(ManualTags.canonical(listOf(name)).single() == name) }
    override fun toString() = "SourceTagProposal(redacted)"
}

/** Memory-only preview. Confirmed labels are independent user-owned manual tags. */
class SourceTagPreview(val source: SourceVersion, val sourceHash: String, val generation: Long,
    val capturedAtMillis: Long, val captureTimeZone: String, val policy: ProcessingPolicy,
    val epoch: Long, val tags: ManualTagSnapshot, proposals: List<SourceTagProposal>, val ruleVersion: Int = 1) {
    val proposals: List<SourceTagProposal> = Collections.unmodifiableList(ArrayList(proposals))
    init {
        require(source.revision in 1..Int.MAX_VALUE.toLong() && sourceHash.matches(Regex("[0-9a-f]{64}")))
        require(generation >= 0 && capturedAtMillis >= 0 && epoch >= 0)
        require(tags.entryId == source.sourceId)
        require(policy != ProcessingPolicy.AI_NONE && proposals.size <= ManualTags.MAX_COUNT)
        require(ruleVersion == 1)
        require(proposals.all { it.reference.source == source } && proposals.map { it.name }.distinct().size == proposals.size)
    }
    override fun toString() = "SourceTagPreview(redacted)"
}

class SourceTagRequest(val preview: SourceTagPreview, val proposal: SourceTagProposal,
    val mutationId: String, val requestedAtMillis: Long) {
    init {
        ManualTags.identifier(mutationId)
        require(requestedAtMillis >= 0 && proposal in preview.proposals && preview.epoch < Long.MAX_VALUE && preview.tags.version < Long.MAX_VALUE)
    }
    override fun toString() = "SourceTagRequest(redacted)"
}
