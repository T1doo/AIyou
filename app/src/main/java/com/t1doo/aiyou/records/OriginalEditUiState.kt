package com.t1doo.aiyou.records

/** UI session identity is memory-only; production edit drafts have separate encrypted identities. */
data class OriginalEditUiState(
    val sessionId: Long,
    val entryId: String,
    val baseRevision: Int,
    val originalText: String,
    val text: String = originalText,
    val saving: Boolean = false,
    val awaitingReadback: Boolean = false,
    val conflict: Boolean = false,
    val error: String? = null,
    val inputNotice: String? = null,
    val sourceRevision: Int = baseRevision,
    val loadingDraft: Boolean = false,
    val discarding: Boolean = false,
    val keeping: Boolean = false,
    val draftConflict: Boolean = false,
    val draftNotice: String = "未保存的修改仅在当前应用会话中保留。",
) {
    val dirty: Boolean get() = text != originalText
    val canCancel: Boolean get() = !saving && !awaitingReadback && !loadingDraft && !discarding && !keeping
}
