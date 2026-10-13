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
    /** Call on an IO worker. Events are previews; consume the returned persisted annotation as the final result. */
    fun execute(onEvent: (StreamEvent) -> Unit = {}): SourceBoundSendOutcome
    fun cancel()
}
