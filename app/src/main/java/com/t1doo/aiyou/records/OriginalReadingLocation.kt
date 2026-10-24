package com.t1doo.aiyou.records

/** Memory-only source binding/offsets. No original/query text or Saver. */
data class OriginalReadingLocation(val entryId: String, val revision: Int, val contentHash: String,
    val startCodePoint: Int, val endCodePoint: Int, val highlightRange: Boolean = true) {
    override fun toString() = "OriginalReadingLocation(redacted)"
}

