package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.PermanentErasureIncomplete
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext

/** These hooks contain no SQL dependency; production injects its same drained encrypted owner. */
interface ErasureFileHooks {
    fun fenced(entryId: String): Boolean
    fun reserve(entryId: String, bucket: String, name: String)
    fun created(entryId: String, bucket: String, name: String, identity: String)
    fun creation(entryId: String, bucket: String, name: String): String?
    fun forgotten(entryId: String, bucket: String, name: String)
}

internal object NoErasureFileHooks : ErasureFileHooks {
    override fun fenced(entryId: String) = false
    override fun reserve(entryId: String, bucket: String, name: String) {}
    override fun created(entryId: String, bucket: String, name: String, identity: String) {}
    override fun creation(entryId: String, bucket: String, name: String): String? = null
    override fun forgotten(entryId: String, bucket: String, name: String) {}
}

/** Identity after successful CREATE_NEW: size/mtime change while our writer is still running. */
internal fun createdFileProof(file: File, headerSize: Int): String {
    val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!attrs.isRegularFile || attrs.fileKey() == null) throw PermanentErasureIncomplete()
    val header = ByteArray(headerSize)
    try {
        Files.newInputStream(file.toPath()).use { input ->
            var at = 0
            while (at < header.size) { val n = input.read(header, at, header.size-at); if (n <= 0) throw PermanentErasureIncomplete(); at += n }
        }
        val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!after.isRegularFile || after.fileKey() != attrs.fileKey()) throw PermanentErasureIncomplete()
        // Our durable, unique random AEAD nonce distinguishes an inode reused after unlink.
        return attrs.fileKey().toString() + ":" + MessageDigest.getInstance("SHA-256").digest(header).joinToString("") { "%02x".format(it) }
    } finally { header.fill(0) }
}

internal data class ErasureFileObservation(val bucket: String, val name: String, val identity: String,
    val size: Long, val modified: String, val digest: String)

internal fun observeErasureFile(bucket: String, file: File): ErasureFileObservation? {
    val attrs = try { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { return null }
    if (!attrs.isRegularFile || attrs.fileKey() == null) throw PermanentErasureIncomplete()
    if (attrs.size() > 16L * 1024 * 1024 + 33 || attrs.size() < 0) throw PermanentErasureIncomplete()
    val hash = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file.toPath()).use { input ->
        val buffer = ByteArray(8192)
        var count = 0L
        try { while (true) {
            val n = input.read(buffer); if (n < 0) break
            if (n == 0) throw PermanentErasureIncomplete()
            count += n; if (count > attrs.size()) throw PermanentErasureIncomplete()
            hash.update(buffer, 0, n)
        }; if (count != attrs.size()) throw PermanentErasureIncomplete() }
        finally { buffer.fill(0) }
    }
    val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!after.isRegularFile || attrs.fileKey() != after.fileKey() || attrs.size() != after.size() ||
        attrs.lastModifiedTime() != after.lastModifiedTime() || attrs.creationTime() != after.creationTime()) throw PermanentErasureIncomplete()
    return ErasureFileObservation(bucket, file.name, attrs.fileKey().toString() + ":" + attrs.creationTime().toString(),
        attrs.size(), attrs.lastModifiedTime().toString(), hash.digest().joinToString("") { "%02x".format(it) })
}

internal fun eraseObservedFile(directory: File, observation: ErasureFileObservation) {
    // The coordinator supplies only a decoded canonical plan, never a user path.
    val file = File(directory, observation.name)
    val current = observeErasureFile(observation.bucket, file)
    if (current != null) {
        if (current != observation) throw PermanentErasureIncomplete()
        Files.delete(file.toPath())
    }
    // Missing after an interrupted unlink still needs a successful directory durability barrier.
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}

/** Propagates ownership across coroutine dispatchers, so a callback cannot wait for itself. */
private class FileOperations(val owners: Set<Any>) : ThreadContextElement<Set<Any>>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FileOperations> {
        val current = ThreadLocal.withInitial { emptySet<Any>() }
    }
    override fun updateThreadContext(context: CoroutineContext): Set<Any> = current.get().also { current.set(owners) }
    override fun restoreThreadContext(context: CoroutineContext, oldState: Set<Any>) { current.set(oldState) }
}
internal suspend fun <T> trackedFileOperation(owner: Any, action: suspend () -> T): T =
    withContext(FileOperations(coroutineContext[FileOperations]?.owners.orEmpty() + owner)) { action() }
internal fun checkExternalFileWait(owner: Any) { check(owner !in FileOperations.current.get()) { "Cannot wait inside a file operation" } }

internal class ErasureDirectoryLease(val bucket: String, private val directory: File) {
    private val before = rootIdentity()
    val identity get() = before
    private fun rootIdentity(): String {
        val attrs = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val lock = Files.readAttributes(File(directory,".store.lock").toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attrs.isDirectory || attrs.fileKey() == null || !lock.isRegularFile || lock.fileKey() == null) throw PermanentErasureIncomplete()
        // Directory timestamps change on unlink, and creationTime can be a provider fallback.
        // Cooperating stores retain the directory and lock inode; replacement fails closed.
        return attrs.fileKey().toString() + ":" + lock.fileKey().toString()
    }
    fun verify() { if (before != rootIdentity()) throw PermanentErasureIncomplete() }
    fun observe(file: File): ErasureFileObservation? {
        verify(); require(file.parentFile == directory)
        return observeErasureFile(bucket, file).also { verify() }
    }
    fun erase(observation: ErasureFileObservation) {
        require(observation.bucket == bucket && observation.name.isNotEmpty() &&
            observation.name.none { it == '/' || it == '\\' || it.isISOControl() } && observation.name != "." && observation.name != "..")
        verify(); eraseObservedFile(directory, observation); verify()
    }
    fun sync() { verify(); FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }; verify() }
}

internal class ErasureScanBudget {
    private var files = 0
    private var bytes = 0L
    fun consume(observation: ErasureFileObservation) {
        files++; bytes = Math.addExact(bytes, observation.size)
        if (files > 192 || bytes > 64L * 1024 * 1024) throw PermanentErasureIncomplete()
    }
}
