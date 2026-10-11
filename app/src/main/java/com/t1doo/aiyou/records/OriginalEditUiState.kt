package com.t1doo.aiyou.records

/** An in-memory edit session; never saved into a Bundle or the capture draft slot. */
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
) {
    val dirty: Boolean get() = text != originalText
    val canCancel: Boolean get() = !saving && !awaitingReadback
}
