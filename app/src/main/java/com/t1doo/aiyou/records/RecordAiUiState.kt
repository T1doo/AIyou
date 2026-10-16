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
    // Displayed RUNNING previews precede wire completion and SQL commit. Once confirming,
    // an absent annotation means the receipt is unknown, not proof that no write happened.
    val contentLabel get() = when {
        annotation != null -> "AI 整理候选"
        phase == RecordAiPhase.RUNNING -> "预览 · 尚未保存"
        else -> "预览 · 保存状态待确认"
    }
    override fun toString() = "RecordAiUiState(sessionId=$sessionId, phase=$phase, <redacted>)"
}
