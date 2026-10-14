package com.t1doo.aiyou.model

/** One explicit confirmation of a currently trashed original and its visibility generation. */
class PermanentErasureIntent(val source: SourceVersion, val generation: Long, val mutationId: String) {
    init {
        ManualTags.identifier(source.sourceId); ManualTags.identifier(mutationId)
        require(source.revision in 1..Int.MAX_VALUE.toLong() && generation >= 0)
    }
    override fun toString() = "PermanentErasureIntent(redacted)"
}

enum class PermanentErasurePhase { PREPARED, PLANNED, DONE }

/** No plaintext or private filenames leave the encrypted storage adapter. */
class PermanentErasureReceipt(val intent: PermanentErasureIntent, val phase: PermanentErasurePhase) {
    val complete get() = phase == PermanentErasurePhase.DONE
    override fun toString() = "PermanentErasureReceipt(phase=$phase, redacted)"
}

/** A bounded recovery page. Unreadable receipts remain fenced and are never counted as done. */
class PermanentErasurePage(receipts: List<PermanentErasureReceipt>, val unreadableCount: Int, val nextEntryId: String?) {
    val receipts = java.util.Collections.unmodifiableList(receipts.toList())
    init { require(unreadableCount >= 0) }
    override fun toString() = "PermanentErasurePage(count=${receipts.size}, unreadable=$unreadableCount, redacted)"
}
