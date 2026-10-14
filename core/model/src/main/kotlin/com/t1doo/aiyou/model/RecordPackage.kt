package com.t1doo.aiyou.model

import java.time.ZoneId
import java.util.Collections
import java.util.UUID

/** Portable limited scope, never a device database or authorization backup. */
class RecordPackageManifest(val backupId: String, val createdAtMillis: Long, val recordCount: Int) {
    init { RecordPackages.uuid(backupId); require(createdAtMillis >= 0 && recordCount in 1..20) }
    override fun toString() = "RecordPackageManifest(count=" + recordCount + ", redacted)"
}

class RecordPackageOriginal(val sourceId: String, val sourceRevision: Int,
    val capturedAtMillis: Long, val captureTimeZone: String, val originalText: String,
    val contentHash: String, val sourceTagVersion: Long, tags: List<String>) {
    val tags = Collections.unmodifiableList(tags.toList())
    init {
        ManualTags.identifier(sourceId); require(sourceRevision > 0)
        ZoneId.of(captureTimeZone); require(captureTimeZone.toByteArray(Charsets.UTF_8).size <= 128)
        require(originalText.isNotBlank() && originalText.length <= 100_000)
        require(contentHash == OriginalExports.hash(originalText))
        require(sourceTagVersion >= 0 && (sourceTagVersion != 0L || tags.isEmpty()))
        require(ManualTags.canonical(tags) == tags)
    }
    override fun toString() = "RecordPackageOriginal(redacted)"
}

class RecordPackageOrigin(val newId: String, val backupId: String, val packageDigest: String,
    val packageCreatedAtMillis: Long, val importedAtMillis: Long,
    val sourceId: String, val sourceRevision: Int, val sourceTagVersion: Long,
    val recordDigest: String, val capturedAtMillis: Long, val captureTimeZone: String) {
    init {
        RecordPackages.uuid(newId); RecordPackages.uuid(backupId)
        RecordPackages.digest(packageDigest); RecordPackages.digest(recordDigest)
        require(packageCreatedAtMillis >= 0 && importedAtMillis >= 0)
        ManualTags.identifier(sourceId); require(sourceRevision > 0 && sourceTagVersion >= 0)
        ZoneId.of(captureTimeZone)
    }
    override fun toString() = "RecordPackageOrigin(redacted)"
}

class RecordPackageRestoreResult(ids: List<String>, val importedAtMillis: Long, val replayed: Boolean,
    val deletedCount: Int = 0, val erasingCount: Int = 0) {
    val ids = Collections.unmodifiableList(ids.toList())
    init { require(ids.size in 1..20 && ids.distinct().size == ids.size); ids.forEach(RecordPackages::uuid); require(importedAtMillis >= 0)
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
    fun uuid(value: String) { require(value.length == 36 && UUID.fromString(value).toString() == value) }
    fun digest(value: String) { require(value.matches(Regex("[0-9a-f]{64}"))) }
    fun password(value: CharArray) {
        require(value.size in 12..128 && value.all { it.code in 32..126 } && value.any { it != ' ' })
    }
}
