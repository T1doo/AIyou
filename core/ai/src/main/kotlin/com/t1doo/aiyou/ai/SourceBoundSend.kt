package com.t1doo.aiyou.ai

import com.t1doo.aiyou.model.SourceCommitDenial
import com.t1doo.aiyou.model.StoredTextAnnotation

/** Completed here means an accepted database transaction, not merely a completed wire stream. */
data class SourceBoundSendOutcome(
    val terminal: StreamEvent,
    val wireOutcome: GuardedSendOutcome? = null,
    val annotation: StoredTextAnnotation? = null,
    val storageDenial: SourceCommitDenial? = null,
    val alreadyCommitted: Boolean = false,
)

interface SourceBoundStreamingExchange {
    /**
     * Call on an IO worker. Every preview, including wire Completed, first checks persisted
     * authority and passes an owned cancellation gate. An already admitted callback may
     * finish during concurrent withdrawal; later previews are suppressed. No consumer runs
     * inside a database transaction or owner lock. A terminal callback is not guaranteed
     * after cancellation. Only the returned persisted annotation is an accepted result.
     */
    fun execute(onEvent: (StreamEvent) -> Unit = {}): SourceBoundSendOutcome
    fun cancel()
}
