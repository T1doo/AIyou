package com.t1doo.aiyou.model

data class TextDraft(val id: String, val version: Long, val text: String)

data class StoredEntry(
    val id: String,
    val currentRevision: Int,
    val originalText: String,
    val capturedAtMillis: Long,
    val captureTimeZone: String,
    val policy: ProcessingPolicy,
)

data class OriginalRevision(
    val entryId: String,
    val revision: Int,
    val originalText: String,
    val contentHash: String,
    val createdAtMillis: Long,
)
