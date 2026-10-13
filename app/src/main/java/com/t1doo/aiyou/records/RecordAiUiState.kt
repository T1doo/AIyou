package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.SourceVersion
import com.t1doo.aiyou.model.StoredTextAnnotation

enum class RecordAiPhase { READY, BLOCKED, RUNNING, CONFIRMING, SUCCEEDED, FAILED, CANCELLED }

/** Memory-only previews are never original records, facts or confirmed tasks. */
data class RecordAiUiState(
    val sessionId: Long,
    val source: SourceVersion,
    val phase: RecordAiPhase,
    val notice: String,
    val preview: String = "",
    val annotation: StoredTextAnnotation? = null,
) {
    val running get() = phase == RecordAiPhase.RUNNING || phase == RecordAiPhase.CONFIRMING
    override fun toString() = "RecordAiUiState(sessionId=$sessionId, phase=$phase, <redacted>)"
}
