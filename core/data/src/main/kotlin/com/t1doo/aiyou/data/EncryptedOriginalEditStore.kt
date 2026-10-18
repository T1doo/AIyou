package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.OriginalEditDraftBusy
import com.t1doo.aiyou.domain.OriginalEditDraftConflict
import com.t1doo.aiyou.domain.OriginalEditDraftChangedDuringRead
import com.t1doo.aiyou.domain.OriginalEditDraftIntegrityFailure
import com.t1doo.aiyou.domain.OriginalEditDraftStore
import com.t1doo.aiyou.model.OriginalEditDraft
import com.t1doo.aiyou.model.OriginalEditDraftRef
import com.t1doo.aiyou.model.OriginalEditDraftIndex
import com.t1doo.aiyou.model.OriginalEditDraftState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.BadPaddingException
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class OriginalEditImportIntent(val id: String, val entryId: String, val digest: String) {
    init { com.t1doo.aiyou.model.RecordPackages.uuid(id); com.t1doo.aiyou.model.RecordPackages.uuid(entryId)
        com.t1doo.aiyou.model.RecordPackages.digest(digest) }
}

/** Production AES-256-GCM adapter. Only ciphertext is written, including temporary files. */
class EncryptedOriginalEditStore(
    private val directory: File,
    private val keyBytes: ByteArray,
    private val checkpoint: (String) -> Unit = {},
) : OriginalEditDraftStore, AutoCloseable {
    private val lifecycle = java.lang.Object()
    private var erasureHooks: ErasureFileHooks = NoErasureFileHooks
    private var operations = 0
    private var closed = false

    init { require(keyBytes.size == 32) }

    override suspend fun read(entryId: String): OriginalEditDraft? = locked {
        if (erasureHooks.fenced(entryId)) null else readLocked(entryId)
    }

    override suspend fun pending(): OriginalEditDraftIndex = locked {
        val rootBefore = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        checkpoint("listing")
        // A failed list/iterator is a global failure, never an empty successful index.
        val targets = Files.newDirectoryStream(directory.toPath()).use { stream ->
            stream.filter { it.fileName.toString().matches(Regex("[0-9a-f]{64}\\.edit")) }
                .map { it.toFile() }.sortedBy { it.name }
        }
        checkpoint("listed")
        val drafts = mutableListOf<OriginalEditDraftRef>()
        var integrityFailures = 0
        var changedReads = 0
        for (target in targets) {
            try {
                val draft = authenticated(target)
                if (!erasureHooks.fenced(draft.entryId) && draft.state == OriginalEditDraftState.ACTIVE)
                    drafts += OriginalEditDraftRef(draft.id, draft.entryId, draft.baseRevision)
            } catch (_: OriginalEditDraftIntegrityFailure) { integrityFailures++ }
            catch (_: OriginalEditDraftChangedDuringRead) { changedReads++ }
        }
        val rootAfter = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(rootAfter.isDirectory && rootBefore.fileKey() == rootAfter.fileKey() && rootBefore.creationTime() == rootAfter.creationTime())
        OriginalEditDraftIndex(drafts, integrityFailures, changedReads)
    }

    override suspend fun write(expected: OriginalEditDraft?, next: OriginalEditDraft): OriginalEditDraft = locked {
        validate(next)
        if (erasureHooks.fenced(next.entryId)) throw OriginalEditDraftConflict()
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

    private fun observedFile(target: File): BasicFileAttributes = try {
        Files.readAttributes(target.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { throw OriginalEditDraftChangedDuringRead() }

    private fun authenticated(target: File, canonicalName: String = target.name): OriginalEditDraft {
        val before = observedFile(target)
        if (!before.isRegularFile || before.size() !in 30L..MAX_ENVELOPE_BYTES.toLong()) throw OriginalEditDraftIntegrityFailure()
        checkpoint("before-read")
        // File IO stays outside the authentication/format catches. Bound even a file that grows mid-read.
        val envelope = FileInputStream(target).use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                if (bytes.size() + size > MAX_ENVELOPE_BYTES) throw OriginalEditDraftIntegrityFailure()
                bytes.write(buffer, 0, size)
            }
            bytes.toByteArray()
        }
        if (envelope.size !in 30..MAX_ENVELOPE_BYTES || envelope[0] != 1.toByte()) throw OriginalEditDraftIntegrityFailure()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
        cipher.updateAAD(aad(canonicalName))
        val plain = try { cipher.doFinal(envelope, 13, envelope.size - 13) }
            catch (_: BadPaddingException) { throw OriginalEditDraftIntegrityFailure() }
        try {
            val value = try {
                DataInputStream(ByteArrayInputStream(plain)).use { input ->
                    val draft = OriginalEditDraft(input.readUTF(), input.readUTF(), input.readInt(), input.readInt(),
                        input.readLong(), readText(input), readText(input), OriginalEditDraftState.valueOf(input.readUTF()))
                    if (input.available() != 0 || file(draft.entryId).name != canonicalName) throw OriginalEditDraftIntegrityFailure()
                    validate(draft)
                    draft
                }
            } catch (failure: OriginalEditDraftIntegrityFailure) { throw failure }
            catch (_: IOException) { throw OriginalEditDraftIntegrityFailure() } // In-memory envelope format only.
            catch (_: IllegalArgumentException) { throw OriginalEditDraftIntegrityFailure() }
            checkpoint("authenticated")
            val after = observedFile(target)
            if (!after.isRegularFile || before.fileKey() != after.fileKey() || before.size() != after.size() ||
                before.lastModifiedTime() != after.lastModifiedTime() || before.creationTime() != after.creationTime())
                throw OriginalEditDraftChangedDuringRead()
            return value
        } finally { plain.fill(0) }
    }

    private fun publish(value: OriginalEditDraft, partialId: String = UUID.randomUUID().toString()) {
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
        val temporary = File(directory, target.name + ".partial." + partialId)
        erasureHooks.reserve(value.entryId, BUCKET, temporary.name)
        Files.createFile(temporary.toPath())
        FileOutputStream(temporary).use {
            it.write(envelope, 0, 13); it.fd.sync(); syncDirectory(directory)
            val proof = createdFileProof(temporary, 13)
            erasureHooks.created(value.entryId, BUCKET, temporary.name, proof)
            if (erasureHooks.fenced(value.entryId)) throw OriginalEditDraftConflict()
            it.write(envelope, 13, envelope.size-13); it.fd.sync()
        }
        checkpoint("encrypted")
        if (erasureHooks.fenced(value.entryId)) throw OriginalEditDraftConflict()
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        checkpoint("published")
        syncDirectory(directory)
        checkpoint("durable")
        erasureHooks.forgotten(value.entryId, BUCKET, temporary.name)
    }

    /** The package coordinator holds MEDIA before this lease and commits SQL only after publish.
     * The lease cannot escape its live file lock; no nested public store reads are needed. */
    internal suspend fun <T> packageFiles(action: suspend (PackageLease) -> T): T = locked {
        val lease = PackageLease(ErasureDirectoryLease(BUCKET, directory))
        try { action(lease) } finally { lease.active = false }
    }
    internal inner class PackageLease(private val directoryLease: ErasureDirectoryLease) {
        internal var active = true
        private fun verify() { check(active); directoryLease.verify() }
        fun read(entryId: String): OriginalEditDraft? {
            verify(); check(!erasureHooks.fenced(entryId)); return readLocked(entryId)
        }
        fun install(value: OriginalEditDraft) {
            verify(); validate(value)
            require(value.state == OriginalEditDraftState.ACTIVE && value.version == 1L)
            check(!erasureHooks.fenced(value.entryId))
            val existing = readLocked(value.entryId)
            if (existing != null) { check(existing == value); syncDirectory(directory); return }
            val intent = OriginalEditImportIntent(value.id, value.entryId, RecordEditDraftPackageCodec.digest(value))
            // A lost pre-publication ACK can leave this plan's deterministic partial.
            cleanup(intent)
            publish(value, value.id)
            check(readLocked(value.entryId) == value)
            checkpoint("package-edit-published")
        }
        fun cleanup(intent: OriginalEditImportIntent) {
            verify()
            val canonical = file(intent.entryId)
            val partial = canonical.name + ".partial." + intent.id
            val candidates = Files.newDirectoryStream(directory.toPath()).use { stream ->
                var scanned = 0
                stream.filter { path ->
                    check(++scanned <= 4096)
                    val name = path.fileName.toString()
                    name == canonical.name || name.startsWith(canonical.name + ".partial.")
                }.map { it.toFile() }
            }
            // An unexpected competing partial is preserved; it cannot authorize cleanup.
            check(candidates.all { it.name == canonical.name || it.name == partial })
            val observations = candidates.map { candidate ->
                val observed = checkNotNull(directoryLease.observe(candidate))
                val proof = if (candidate.name == partial) erasureHooks.creation(intent.entryId, BUCKET, partial) else null
                if (proof == null || proof != createdFileProof(candidate, 13)) {
                    val value = authenticated(candidate, canonical.name)
                    check(value.id == intent.id && value.entryId == intent.entryId &&
                        RecordEditDraftPackageCodec.digest(value) == intent.digest)
                }
                check(directoryLease.observe(candidate) == observed)
                observed
            }
            observations.forEach(directoryLease::erase)
            directoryLease.sync()
            erasureHooks.forgotten(intent.entryId, BUCKET, partial)
            checkpoint("package-edit-cleaned")
        }
    }

    private suspend fun <T> locked(action: suspend () -> T): T = trackedFileOperation(lifecycle) {
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
                lock.use { checkpoint("locked"); return@trackedFileOperation action() }
            }
        } finally {
            synchronized(lifecycle) { operations--; if (operations == 0) lifecycle.notifyAll(); if (closed && operations == 0) keyBytes.fill(0) }
        }
    }

    override fun close() = synchronized(lifecycle) { closed = true; if (operations == 0) keyBytes.fill(0); Unit }

    internal fun installErasureHooks(hooks: ErasureFileHooks) = synchronized(lifecycle) {
        check(!closed && operations == 0 && erasureHooks === NoErasureFileHooks); erasureHooks = hooks
    }
    internal suspend fun <T> withErasureLease(action: suspend (ErasureDirectoryLease) -> T): T =
        locked { action(ErasureDirectoryLease(BUCKET, directory)) }
    internal fun erasurePlan(entryId: String, lease: ErasureDirectoryLease, budget: ErasureScanBudget): List<ErasureFileObservation> {
        val canonical = file(entryId).name
        val candidates = mutableListOf<File>()
        // Bound both directory enumeration and this source's candidate count. Never truncate to DONE.
        var scanned = 0
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            for (path in stream) {
                if (++scanned > 4096) throw com.t1doo.aiyou.domain.PermanentErasureIncomplete()
                val name = path.fileName.toString()
                if (name == canonical || name.startsWith(canonical + ".partial.")) {
                    if (name != canonical) {
                        val suffix = name.removePrefix(canonical + ".partial.")
                        if (runCatching { UUID.fromString(suffix).toString() == suffix }.getOrDefault(false).not())
                            throw com.t1doo.aiyou.domain.PermanentErasureIncomplete()
                    }
                    if (candidates.size >= 64) throw com.t1doo.aiyou.domain.PermanentErasureIncomplete()
                    candidates += path.toFile()
                }
            }
        }
        return candidates.sortedBy { it.name }.map { candidate ->
            val observed = lease.observe(candidate) ?: throw com.t1doo.aiyou.domain.PermanentErasureIncomplete()
            budget.consume(observed)
            val proof = if (candidate.name != canonical) erasureHooks.creation(entryId, BUCKET, candidate.name) else null
            if ((proof == null || proof != createdFileProof(candidate, 13)) && authenticated(candidate, canonical).entryId != entryId)
                throw com.t1doo.aiyou.domain.PermanentErasureIncomplete()
            if (lease.observe(candidate) != observed) throw com.t1doo.aiyou.domain.PermanentErasureIncomplete()
            observed
        }
    }
    internal fun awaitIdle() {
        checkExternalWait()
        var interrupted = false
        synchronized(lifecycle) { while (operations != 0) try { lifecycle.wait() } catch (_: InterruptedException) { interrupted = true } }
        if (interrupted) Thread.currentThread().interrupt()
    }
    internal fun checkExternalWait() { checkExternalFileWait(lifecycle) }

    companion object {
        internal const val BUCKET = "EDITS"
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
