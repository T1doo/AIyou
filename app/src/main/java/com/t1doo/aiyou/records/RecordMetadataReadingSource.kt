package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.OriginalExports
import com.t1doo.aiyou.model.SourceVersion

/** Authority captured when a metadata button is rendered, using the existing source ledger. */
class RecordMetadataReadingSource internal constructor(val version: SourceVersion, val contentHash: String,
    val generation: Long) {
    init { require(version.revision <= Int.MAX_VALUE && generation >= 0 && contentHash.matches(Regex("[0-9a-f]{64}"))) }
    internal fun same(other: RecordMetadataReadingSource?) = other != null && version == other.version &&
        contentHash == other.contentHash && generation == other.generation
    override fun toString() = "RecordMetadataReadingSource(redacted)"
}

internal fun LocalRecordUiState.metadataReadingSource(id: String, revision: Int): RecordMetadataReadingSource? {
    if (!ready || opening || error != null || !localProcessingAvailable) return null
    val entry = entries.singleOrNull { it.id == id && it.currentRevision == revision } ?: return null
    val source = localProcessingVersions[id]?.takeIf { it.source == SourceVersion(id, revision.toLong()) } ?: return null
    return RecordMetadataReadingSource(source.source, OriginalExports.hash(entry.originalText), source.generation)
}
