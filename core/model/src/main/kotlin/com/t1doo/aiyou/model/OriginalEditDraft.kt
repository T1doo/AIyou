package com.t1doo.aiyou.model

enum class OriginalEditDraftState { ACTIVE, DISCARDED, COMMITTED }

/** Separate identity from capture drafts and immutable original revisions. */
data class OriginalEditDraft(
    val id: String,
    val entryId: String,
    val baseRevision: Int,
    val sourceRevision: Int,
    val version: Long,
    val originalText: String,
    val text: String,
    val state: OriginalEditDraftState = OriginalEditDraftState.ACTIVE,
)

data class OriginalEditDraftRef(val id: String, val entryId: String, val baseRevision: Int)
