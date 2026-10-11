package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.OriginalRevision

/** Read-only, memory-only. Neither originals nor editor input enter saved navigation state. */
data class OriginalHistoryUiState(
    val sessionId: Long,
    val entryId: String,
    val requestId: Long = 1,
    val loading: Boolean = true,
    val currentRevision: Int? = null,
    val revisions: List<OriginalRevision> = emptyList(),
    val selectedRevision: Int? = null,
    val unavailable: Boolean = false,
    val error: String? = null,
)
