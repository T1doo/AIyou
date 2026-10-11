package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.OriginalEditDraftBusy
import com.t1doo.aiyou.domain.OriginalEditDraftConflict
import com.t1doo.aiyou.domain.OriginalEditDraftIntegrityFailure
import com.t1doo.aiyou.domain.OriginalEditDraftStore
import com.t1doo.aiyou.model.OriginalEditDraft
import com.t1doo.aiyou.model.OriginalEditDraftRef
import com.t1doo.aiyou.model.OriginalEditDraftState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
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

/** Production AES-256-GCM adapter. Only ciphertext is written, including temporary files. */
class EncryptedOriginalEditStore(
    private val directory: File,
    private val keyBytes: ByteArray,
    private val checkpoint: (String) -> Unit = {},
) : OriginalEditDraftStore, AutoCloseable {
    private val lifecycle = Any()
    private var operations = 0
    private var closed = false

    init { require(keyBytes.size == 32) }

    override suspend fun read(entryId: String): OriginalEditDraft? = locked { readLocked(entryId) }

    override suspend fun pending(): List<OriginalEditDraftRef> = locked {
        directory.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f]{64}\\.edit")) }
            .sortedBy { it.name }.mapNotNull { file ->
                val draft = authenticated(file)
                if (draft.state == OriginalEditDraftState.ACTIVE)
                    OriginalEditDraftRef(draft.id, draft.entryId, draft.baseRevision) else null
            }
    }

    override suspend fun write(expected: OriginalEditDraft?, next: OriginalEditDraft): OriginalEditDraft = locked {
        validate(next)
        if (expected != null && expected.entryId != next.entryId) throw OriginalEditDraftConflict()
        val current = readLocked(next.entryId) // Corrupt files are preserved, never replaced.
        if (current == next) { syncDirectory(directory); return@locked current } // Confirm publication without rewriting ciphertext.
        if (current != expected) throw OriginalEditDraftConflict()
        if (expected == null || next.id != expected.id) {
            require(next.state == OriginalEditDraftState.ACTIVE && next.version == 1L)
        } else {
            if (expected.state != OriginalEditDraftState.ACTIVE) throw OriginalEditDraftConflict()
            require(next.version == Math.addExact(expected.version, 1L) &&
                next.baseRevision == expected.baseRevision && next.sourceRevision == expected.sourceRevision)
            if (next.state == OriginalEditDraftState.ACTIVE) require(next.originalText == expected.originalText)
        }
        publish(next)
        next
    }

    private fun validate(draft: OriginalEditDraft) {
        require(UUID.fromString(draft.id).toString() == draft.id)
        require(draft.entryId.isNotEmpty() && draft.entryId.length <= 256)
        require(draft.baseRevision > 0 && draft.sourceRevision in 1..draft.baseRevision && draft.version > 0)
        require(draft.originalText.length <= MAX_TEXT_LENGTH && draft.text.length <= MAX_TEXT_LENGTH)
        if (draft.state != OriginalEditDraftState.ACTIVE) require(draft.originalText.isEmpty() && draft.text.isEmpty())
    }

    private fun file(entryId: String) = File(directory, hash(entryId) + ".edit")
    private fun readLocked(entryId: String): OriginalEditDraft? {
        require(entryId.isNotEmpty() && entryId.length <= 256)
        val target = file(entryId)
        return if (!target.exists()) null else authenticated(target).also {
            if (it.entryId != entryId) throw OriginalEditDraftIntegrityFailure()
        }
    }

    private fun authenticated(target: File): OriginalEditDraft {
        try {
            if (target.length() !in 30L..MAX_ENVELOPE_BYTES.toLong()) throw OriginalEditDraftIntegrityFailure()
            val envelope = target.readBytes()
            if (envelope.size !in 30..MAX_ENVELOPE_BYTES || envelope[0] != 1.toByte()) throw OriginalEditDraftIntegrityFailure()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
            cipher.updateAAD(aad(target.name))
            val plain = cipher.doFinal(envelope, 13, envelope.size - 13)
            try {
                return DataInputStream(ByteArrayInputStream(plain)).use { input ->
                    val value = OriginalEditDraft(input.readUTF(), input.readUTF(), input.readInt(), input.readInt(),
                        input.readLong(), readText(input), readText(input), OriginalEditDraftState.valueOf(input.readUTF()))
                    if (input.available() != 0 || file(value.entryId).name != target.name) throw OriginalEditDraftIntegrityFailure()
                    validate(value)
                    value
                }
            } finally { plain.fill(0) }
        } catch (failure: OriginalEditDraftIntegrityFailure) { throw failure }
        catch (_: Exception) { throw OriginalEditDraftIntegrityFailure() }
    }

    private fun publish(value: OriginalEditDraft) {
        val target = file(value.entryId)
        val plain = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeUTF(value.id); out.writeUTF(value.entryId); out.writeInt(value.baseRevision)
                out.writeInt(value.sourceRevision); out.writeLong(value.version)
                writeText(out, value.originalText); writeText(out, value.text); out.writeUTF(value.state.name)
            }
        }.toByteArray()
        val envelope = try {
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(target.name))
            byteArrayOf(1) + nonce + cipher.doFinal(plain)
        } finally { plain.fill(0) }
        // Unique owned partials survive failure for diagnosis; they are never restoration sources.
        val temporary = File(directory, target.name + ".partial." + UUID.randomUUID())
        FileOutputStream(temporary).use { it.write(envelope); it.fd.sync() }
        checkpoint("encrypted")
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        checkpoint("published")
        syncDirectory(directory)
        checkpoint("durable")
    }

    private suspend fun <T> locked(action: suspend () -> T): T {
        synchronized(lifecycle) { check(!closed); operations++ }
        try {
            check(!Files.isSymbolicLink(directory.toPath()))
            val parent = checkNotNull(directory.parentFile)
            check(parent.isDirectory && !Files.isSymbolicLink(parent.toPath()))
            check(directory.isDirectory || directory.mkdir())
            // Confirm the parent's child entry every time, including after a failed creation attempt.
            syncDirectory(parent)
            val lockPath = File(directory, ".store.lock").toPath()
            check(!Files.isSymbolicLink(lockPath))
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) throw OriginalEditDraftBusy()
                lock.use { return action() }
            }
        } finally {
            synchronized(lifecycle) { operations--; if (closed && operations == 0) keyBytes.fill(0) }
        }
    }

    override fun close() = synchronized(lifecycle) { closed = true; if (operations == 0) keyBytes.fill(0); Unit }

    companion object {
        const val MAX_TEXT_LENGTH = 100_000
        private const val MAX_ENVELOPE_BYTES = 402_048
        private fun aad(name: String) = ("AIyou original edit envelope v1:" + name).toByteArray(Charsets.US_ASCII)
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        private fun syncDirectory(target: File) = FileChannel.open(target.toPath(), StandardOpenOption.READ).use { it.force(true) }
        // Encode UTF-16 code units exactly, including an IME's unfinished surrogate pair.
        private fun writeText(out: DataOutputStream, text: String) { out.writeInt(text.length); text.forEach { out.writeChar(it.code) } }
        private fun readText(input: DataInputStream): String {
            val length = input.readInt()
            if (length !in 0..MAX_TEXT_LENGTH || length > input.available() / 2) throw OriginalEditDraftIntegrityFailure()
            return CharArray(length) { input.readChar() }.concatToString()
        }
    }
}
