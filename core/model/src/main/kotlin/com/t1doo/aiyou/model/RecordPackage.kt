package com.t1doo.aiyou.model

import java.time.ZoneId
import java.util.Collections
import java.util.UUID

/** Portable limited scope, never a device database or authorization backup. */
class RecordPackageManifest(val backupId: String, val createdAtMillis: Long, val recordCount: Int) {
    init { RecordPackages.uuid(backupId); require(createdAtMillis >= 0 && recordCount in 0..20) }
    override fun toString() = "RecordPackageManifest(count=" + recordCount + ", redacted)"
}

/** An exact immutable original version, including text that was later changed or removed. */
data class RecordPackageRevision(val revision: Int, val originalText: String, val contentHash: String,
    val createdAtMillis: Long) {
    init {
        require(revision > 0 && originalText.length <= 100_000 && createdAtMillis >= 0)
        require(contentHash == OriginalExports.hash(originalText))
    }
    override fun toString() = "RecordPackageRevision(revision=$revision, redacted)"
}

class RecordPackageOriginal(val sourceId: String, val sourceRevision: Int,
    val capturedAtMillis: Long, val captureTimeZone: String, val originalText: String,
    val contentHash: String, val sourceTagVersion: Long, tags: List<String>, allowImageOnly: Boolean = false,
    history: List<RecordPackageRevision> = emptyList(), val eventDate: RecordEventDate? = null) {
    val tags = Collections.unmodifiableList(tags.toList())
    val history = Collections.unmodifiableList(history.toList())
    val restoredRevision get() = if (history.isEmpty()) 1 else sourceRevision
    init {
        ManualTags.identifier(sourceId); require(sourceRevision > 0)
        ZoneId.of(captureTimeZone); require(captureTimeZone.toByteArray(Charsets.UTF_8).size <= 128)
        require((originalText.isNotBlank() || allowImageOnly) && originalText.length <= 100_000)
        require(contentHash == OriginalExports.hash(originalText))
        require(sourceTagVersion >= 0 && (sourceTagVersion != 0L || tags.isEmpty()))
        require(ManualTags.canonical(tags) == tags)
        if (history.isNotEmpty()) {
            require(capturedAtMillis >= 0)
            require(history.size <= RecordPackages.MAX_REVISIONS_PER_RECORD && history.size == sourceRevision)
            history.forEachIndexed { index, item ->
                require(item.revision == index + 1)
            }
            require(history.last().originalText == originalText && history.last().contentHash == contentHash)
            require(RecordPackages.historyTextBytes(history) <= RecordPackages.MAX_PLAINTEXT)
        }
    }
    override fun toString() = "RecordPackageOriginal(redacted)"
}

object RecordHistoryPackages {
    const val MAX_REVISIONS_PER_RECORD = RecordPackages.MAX_REVISIONS_PER_RECORD
    const val MAX_REVISIONS = RecordPackages.MAX_TOTAL_REVISIONS
    const val MAX_TEXT_BYTES = RecordPackages.MAX_PLAINTEXT
}

class RecordPackageOrigin(val newId: String, val backupId: String, val packageDigest: String,
    val packageCreatedAtMillis: Long, val importedAtMillis: Long,
    val sourceId: String, val sourceRevision: Int, val sourceTagVersion: Long,
    val recordDigest: String, val capturedAtMillis: Long, val captureTimeZone: String,
    val restoredRevisionCount: Int = 1, val historyDigest: String? = null) {
    init {
        RecordPackages.uuid(newId); RecordPackages.uuid(backupId)
        RecordPackages.digest(packageDigest); RecordPackages.digest(recordDigest)
        require(packageCreatedAtMillis >= 0 && importedAtMillis >= 0)
        ManualTags.identifier(sourceId); require(sourceRevision > 0 && sourceTagVersion >= 0)
        ZoneId.of(captureTimeZone)
        require(restoredRevisionCount in 1..RecordPackages.MAX_REVISIONS_PER_RECORD)
        if (historyDigest != null) { RecordPackages.digest(historyDigest); require(restoredRevisionCount == sourceRevision) }
        else require(restoredRevisionCount == 1)
    }
    override fun toString() = "RecordPackageOrigin(redacted)"
}

class RecordPackageRestoreResult(ids: List<String>, val importedAtMillis: Long, val replayed: Boolean,
    val deletedCount: Int = 0, val erasingCount: Int = 0,
    taskIds: List<String> = emptyList(), proposalIds: List<String> = emptyList()) {
    val ids = Collections.unmodifiableList(ids.toList())
    val taskIds = Collections.unmodifiableList(taskIds.toList())
    val proposalIds = Collections.unmodifiableList(proposalIds.toList())
    init { require(ids.size in 0..20 && ids.distinct().size == ids.size); ids.forEach(RecordPackages::uuid); require(importedAtMillis >= 0)
        require(deletedCount >= 0 && erasingCount >= 0 && deletedCount + erasingCount <= ids.size)
        require(replayed || deletedCount + erasingCount == 0)
    }
    override fun toString() = "RecordPackageRestoreResult(count=" + ids.size + ", replayed=" + replayed + ")"
}

/** Controlled encrypted bytes have an explicit lifetime and never log their contents. */
class EncryptedRecordPackage(bytes: ByteArray) {
    private var owned: ByteArray? = null
    val size: Int = bytes.size
    init { require(size in 64..RecordPackages.MAX_ENVELOPE); owned = bytes.copyOf() }
    @Synchronized fun copyBytes(): ByteArray = checkNotNull(owned).copyOf()
    @Synchronized fun release() { owned?.fill(0); owned = null }
    override fun toString() = "EncryptedRecordPackage(bytes=" + size + ", redacted)"
}

object RecordPackages {
    const val MAX_ENVELOPE = 2_097_152
    const val MAX_PLAINTEXT = 2_097_088
    const val ITERATIONS = 600_000
    const val MAX_REVISIONS_PER_RECORD = 128
    const val MAX_TOTAL_REVISIONS = 256
    fun historyTextBytes(history: List<RecordPackageRevision>): Long = history.sumOf { revision ->
        val bytes = OriginalExports.utf8(revision.originalText)
        try { bytes.size.toLong() } finally { bytes.fill(0) }
    }
    fun uuid(value: String) { require(value.length == 36 && UUID.fromString(value).toString() == value) }
    fun digest(value: String) { require(value.matches(Regex("[0-9a-f]{64}"))) }
    fun password(value: CharArray) {
        require(value.size in 12..128 && value.all { it.code in 32..126 } && value.any { it != ' ' })
    }
}
