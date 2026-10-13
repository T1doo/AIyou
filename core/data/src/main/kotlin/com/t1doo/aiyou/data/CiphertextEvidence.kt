package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.StorageKeyUnavailable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Read-only pre-key inspection. Unknown IO is never interpreted as an empty store. */
internal class CiphertextEvidence(
    private val listFiles: (File) -> Array<File>? = { it.listFiles() },
    private val attributes: (File) -> BasicFileAttributes = {
        Files.readAttributes(it.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    },
) {
    // creationTime may fall back to mtime on Android; legitimate writes change it.
    internal data class Identity(val key: Any)
    internal data class Snapshot(
        val required: Map<RepositoryKeyPurpose, Boolean>,
        val directories: Map<File, Identity>,
    )

    fun inspect(root: File, previous: Snapshot? = null): Snapshot = try {
        val observed = linkedMapOf<File, BasicFileAttributes?>()
        fun read(file: File): BasicFileAttributes? {
            val value = try { attributes(file) } catch (_: NoSuchFileException) { null }
            observed[file] = value
            if (value?.isSymbolicLink == true) throw StorageKeyUnavailable()
            return value
        }
        fun directory(file: File, required: Boolean = false): Boolean {
            val value = read(file)
            if (value == null) {
                if (required) throw StorageKeyUnavailable()
                return false
            }
            if (!value.isDirectory || value.fileKey() == null) throw StorageKeyUnavailable()
            return true
        }
        fun regular(file: File): Boolean {
            val value = read(file) ?: return false
            if (!value.isRegularFile || value.fileKey() == null) throw StorageKeyUnavailable()
            return true
        }
        fun store(file: File): Boolean {
            if (!directory(file)) return false
            val entries = listFiles(file) ?: throw StorageKeyUnavailable()
            if (entries.map { it.name }.distinct().size != entries.size) throw StorageKeyUnavailable()
            var found = false
            for (entry in entries) {
                // Reject substituted/escaping enumeration results, including the otherwise allowed lock.
                if (entry.parentFile != file || entry.name in listOf(".", "..")) throw StorageKeyUnavailable()
                if (!regular(entry)) throw StorageKeyUnavailable()
                val info = checkNotNull(observed[entry])
                // Every regular non-lock entry (known cipher, partial or unknown name) is evidence.
                // A nonempty lock is also evidence, never silently discarded as a legal empty lock.
                if (entry.name != ".store.lock" || info.size() != 0L) found = true
            }
            return found
        }
        directory(root, required = true)
        val records = File(root, "records")
        val hasRecords = directory(records)
        val database = File(records, "records.db")
        var databaseEvidence = false
        var editEvidence = false
        var attachmentEvidence = false
        if (hasRecords) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                databaseEvidence = regular(File(database.path + suffix)) || databaseEvidence
            }
            editEvidence = store(File(records, "records.db.original-edits"))
            attachmentEvidence = store(File(records, "records.db.attachments"))
        }
        val required = linkedMapOf(
            RepositoryKeyPurpose.DATABASE to databaseEvidence,
            RepositoryKeyPurpose.EDITS to editEvidence,
            RepositoryKeyPurpose.ATTACHMENTS to attachmentEvidence,
        )
        val keys = File(root, "keys")
        if (directory(keys)) {
            for (purpose in RepositoryKeyPurpose.entries) {
                val key = File(keys, purpose.fileName)
                // Existing envelopes are protected even if ciphertext has not yet been published.
                val present = regular(key)
                val backup = regular(File(key.path + ".bak"))
                val partial = regular(File(key.path + ".new"))
                required[purpose] = checkNotNull(required[purpose]) || present || backup || partial
            }
        }
        // Detect changes during the scan, including absent paths appearing and list/stat disagreement.
        for ((path, before) in observed) {
            val after = try { attributes(path) } catch (_: NoSuchFileException) { null }
            if (before == null && after == null) continue
            if (before == null || after == null || before.fileKey() != after.fileKey() ||
                before.creationTime() != after.creationTime() || before.lastModifiedTime() != after.lastModifiedTime() ||
                before.size() != after.size() || before.isDirectory != after.isDirectory ||
                before.isRegularFile != after.isRegularFile || after.isSymbolicLink) throw StorageKeyUnavailable()
        }
        val directories = observed.filterValues { it?.isDirectory == true }.mapValues { (_, value) ->
            Identity(checkNotNull(value?.fileKey()))
        }
        if (previous != null) {
            for ((path, identity) in previous.directories) if (directories[path] != identity) throw StorageKeyUnavailable()
            // Evidence is sticky for this open attempt; disappearance cannot authorize fresh keys.
            for (purpose in RepositoryKeyPurpose.entries) required[purpose] = checkNotNull(required[purpose]) || checkNotNull(previous.required[purpose])
        }
        Snapshot(required, directories)
    } catch (failure: StorageKeyUnavailable) {
        throw failure
    } catch (_: Exception) {
        throw StorageKeyUnavailable() // No user path/body/platform details in a surfaced error.
    }
}
