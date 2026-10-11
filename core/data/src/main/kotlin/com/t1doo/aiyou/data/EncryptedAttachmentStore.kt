package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.AttachmentBusy
import com.t1doo.aiyou.domain.AttachmentConflict
import com.t1doo.aiyou.domain.AttachmentIntegrityFailure
import com.t1doo.aiyou.domain.AttachmentStore
import com.t1doo.aiyou.model.AttachmentIntent
import com.t1doo.aiyou.model.AttachmentState
import com.t1doo.aiyou.model.LocalAttachment
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The production adapter implements these operations with the encrypted Room ledger. */
internal interface AttachmentLedger {
    suspend fun find(id: String): LocalAttachment?
    suspend fun all(): List<LocalAttachment>
    suspend fun insert(value: LocalAttachment)
    suspend fun transition(expected: LocalAttachment, next: AttachmentState): LocalAttachment
}

/**
 * Versioned envelope around platform AES-256-GCM, never a plaintext temp file.
 * A durable intent precedes file creation; READY follows authenticated verification,
 * file fsync, an atomic same-directory rename, directory fsync, then the ledger commit.
 * All instances/processes coordinate through our own nonblocking lock file.
 */
internal class EncryptedAttachmentStore(
    private val directory: File,
    private val ledger: AttachmentLedger,
    private val keyBytes: ByteArray,
    private val checkpoint: (String) -> Unit = {},
) : AttachmentStore, AutoCloseable {
    private val lifecycle = Any()
    private var activeOperations = 0
    @Volatile private var closed = false
    override suspend fun save(intent: AttachmentIntent, source: () -> InputStream): LocalAttachment = locked {
        validate(intent)
        recoverLocked()
        var row = ledger.find(intent.id)
        if (row != null && row.intent != intent) throw AttachmentConflict()
        if (row?.state == AttachmentState.CORRUPT) throw AttachmentIntegrityFailure()
        if (row?.state == AttachmentState.READY) {
            authenticated(file(intent, false), intent).fill(0)
            return@locked row
        }
        if (row == null) {
            row = LocalAttachment(intent, AttachmentState.WRITING)
            ledger.insert(row) // also validates the immutable source revision FK, before touching files
        } else {
            check(row.state == AttachmentState.FAILED)
            deleteOwnedPartial(intent)
            row = ledger.transition(row, AttachmentState.WRITING)
        }
        checkpoint("intent")
        writeEncrypted(intent, source)
        checkpoint("encrypted")
        row = ledger.transition(row, AttachmentState.STAGED)
        checkpoint("staged")
        authenticated(file(intent, true), intent).fill(0)
        publish(intent)
        checkpoint("published")
        ledger.transition(row, AttachmentState.READY).also { checkpoint("ready") }
    }

    override suspend fun list(entryId: String, revision: Int): List<LocalAttachment> = locked {
        recoverLocked()
        ledger.all().filter { it.intent.entryId == entryId && it.intent.revision == revision }
    }

    override suspend fun read(id: String): ByteArray = locked {
        val row = ledger.find(id) ?: throw AttachmentConflict()
        validate(row.intent)
        if (row.state != AttachmentState.READY) throw AttachmentIntegrityFailure()
        try { authenticated(file(row.intent, false), row.intent) }
        catch (failure: AttachmentIntegrityFailure) {
            ledger.transition(row, AttachmentState.CORRUPT)
            throw failure
        }
    }

    override suspend fun recover(): Unit = locked { recoverLocked() }

    private suspend fun recoverLocked() {
        for (row in ledger.all()) {
            validate(row.intent)
            when (row.state) {
                AttachmentState.WRITING -> {
                    // A file without the durable STAGED marker has no acknowledged complete copy.
                    // Leave bytes for diagnosis; an explicit retry may remove only this row's temp.
                    ledger.transition(row, AttachmentState.FAILED)
                }
                AttachmentState.STAGED -> {
                    val final = file(row.intent, false)
                    val candidate = if (final.exists()) final else file(row.intent, true)
                    try {
                        authenticated(candidate, row.intent).fill(0)
                    } catch (_: AttachmentIntegrityFailure) {
                        ledger.transition(row, AttachmentState.CORRUPT)
                        continue
                    }
                    if (candidate != final) publish(row.intent) else syncDirectory()
                    ledger.transition(row, AttachmentState.READY)
                }
                AttachmentState.READY -> {
                    try { authenticated(file(row.intent, false), row.intent).fill(0) }
                    catch (_: AttachmentIntegrityFailure) { ledger.transition(row, AttachmentState.CORRUPT) }
                }
                else -> Unit // Never enumerate/delete unknown files or replace an acknowledged original.
            }
        }
    }

    private fun writeEncrypted(intent: AttachmentIntent, source: () -> InputStream) {
        val temporary = file(intent, true)
        check(!temporary.exists() && !file(intent, false).exists())
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(intent))
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        // CREATE_NEW prevents replacing a file, including an unexpected symlink.
        Files.createFile(temporary.toPath())
        FileOutputStream(temporary).use { output ->
            output.write(MAGIC)
            output.write(iv)
            source().use { input ->
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (size == 0) throw AttachmentIntegrityFailure()
                        count += size
                        if (count > intent.byteSize) throw AttachmentIntegrityFailure()
                        digest.update(buffer, 0, size)
                        cipher.update(buffer, 0, size)?.let { output.write(it) }
                    }
                    if (count != intent.byteSize || hex(digest.digest()) != intent.sha256) throw AttachmentIntegrityFailure()
                    output.write(cipher.doFinal())
                    output.fd.sync()
                } finally { buffer.fill(0) }
            }
        }
        syncDirectory()
    }

    private fun authenticated(blob: File, intent: AttachmentIntent): ByteArray {
        try {
            if (!blob.isFile || Files.isSymbolicLink(blob.toPath()) || blob.length() != intent.byteSize + 33L)
                throw AttachmentIntegrityFailure()
            // The explicit small bound avoids releasing streamed unauthenticated plaintext.
            val encrypted = FileInputStream(blob).use { input ->
                val bytes = input.readNBytesCompat((intent.byteSize + 34L).toInt())
                if (bytes.size.toLong() != intent.byteSize + 33L) throw AttachmentIntegrityFailure()
                bytes
            }
            if (!encrypted.copyOfRange(0, 5).contentEquals(MAGIC)) throw AttachmentIntegrityFailure()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, encrypted.copyOfRange(5, 17)))
            cipher.updateAAD(aad(intent))
            val plaintext = cipher.doFinal(encrypted, 17, encrypted.size - 17)
            if (plaintext.size.toLong() != intent.byteSize || hex(MessageDigest.getInstance("SHA-256").digest(plaintext)) != intent.sha256) {
                plaintext.fill(0)
                throw AttachmentIntegrityFailure()
            }
            return plaintext
        } catch (failure: AttachmentIntegrityFailure) { throw failure }
        catch (_: java.security.GeneralSecurityException) { throw AttachmentIntegrityFailure() }
    }

    private fun publish(intent: AttachmentIntent) {
        val target = file(intent, false)
        if (target.exists()) throw AttachmentConflict()
        // No copy/delete fallback: unsupported atomic publication is a recoverable storage failure.
        Files.move(file(intent, true).toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory()
    }

    private fun deleteOwnedPartial(intent: AttachmentIntent) {
        if (file(intent, false).exists()) throw AttachmentIntegrityFailure()
        val partial = file(intent, true)
        if (Files.isSymbolicLink(partial.toPath())) throw AttachmentIntegrityFailure()
        if (partial.exists()) { Files.delete(partial.toPath()); syncDirectory() }
    }

    private fun file(intent: AttachmentIntent, temporary: Boolean): File {
        validate(intent)
        return File(directory, intent.id + if (temporary) ".tmp" else ".aead")
    }

    private fun syncDirectory(target: File = directory) {
        FileChannel.open(target.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private suspend fun <T> locked(block: suspend () -> T): T {
        synchronized(lifecycle) {
            check(!closed && keyBytes.size == 32)
            activeOperations++
        }
        try {
            check(!Files.isSymbolicLink(directory.toPath()))
            val parent = checkNotNull(directory.parentFile)
            check(parent.isDirectory && !Files.isSymbolicLink(parent.toPath()))
            check(directory.isDirectory || directory.mkdir())
            // The new child directory's entry belongs to its parent. Sync it even if
            // a previous attempt created the child and failed before confirming it.
            syncDirectory(parent)
            val path = File(directory, ".store.lock").toPath()
            check(!Files.isSymbolicLink(path))
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) throw AttachmentBusy()
                lock.use { return block() }
            }
        } finally {
            synchronized(lifecycle) {
                activeOperations--
                if (closed && activeOperations == 0) keyBytes.fill(0)
            }
        }
    }

    /** Stop new operations immediately; zero the key after already-started operations release it. */
    override fun close() = synchronized(lifecycle) {
        closed = true
        if (activeOperations == 0) keyBytes.fill(0)
    }

    companion object {
        /** Safety bound for this internal storage API, not a frozen product media quota. */
        const val MAX_BYTES = 16L * 1024 * 1024
        private val MAGIC = "AIYA1".toByteArray(Charsets.US_ASCII)
        internal fun validate(intent: AttachmentIntent) {
            require(UUID.fromString(intent.id).toString() == intent.id)
            require(intent.entryId.isNotEmpty() && intent.entryId.length <= 256 && intent.revision > 0)
            require(intent.mediaType in setOf("image/jpeg", "image/png", "audio/mp4", "audio/ogg", "audio/wav"))
            require(intent.sha256.matches(Regex("[0-9a-f]{64}")) && intent.byteSize in 1..MAX_BYTES)
        }
        private fun aad(intent: AttachmentIntent): ByteArray = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeUTF("AIyou attachment v1")
                out.writeUTF(intent.id); out.writeUTF(intent.entryId); out.writeInt(intent.revision)
                out.writeUTF(intent.mediaType); out.writeUTF(intent.sha256); out.writeLong(intent.byteSize)
            }
            bytes.toByteArray()
        }
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}

private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        if (count == 0) throw AttachmentIntegrityFailure()
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
