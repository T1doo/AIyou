package com.t1doo.aiyou.ui

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/** Private non-secret setting, separate from records and from their encryption keys. */
internal class FileAppAccessPreference(private val file: File) : AppAccessPreferenceStorage {
    private val guard = locks.computeIfAbsent(file.absolutePath) { Any() }

    override fun read(): AppAccessPreference = synchronized(guard) {
        val atomic = AtomicFile(file)
        fun exists(path: File): Boolean = try {
            Files.readAttributes(path.toPath(), BasicFileAttributes::class.java)
            true
        } catch (_: NoSuchFileException) { false }
        if (!exists(file) && !exists(File(file.path + ".bak"))) {
            if (exists(File(file.path + ".new"))) throw IOException("App access preference incomplete")
            return@synchronized AppAccessPreference(0, false)
        }
        val raw = atomic.openRead().use { input ->
            val bytes = ByteArray(81)
            var length = 0
            while (length < bytes.size) {
                val count = input.read(bytes, length, bytes.size - length)
                if (count < 0) break
                if (count == 0) throw IOException("App access preference unavailable")
                length += count
            }
            if (length == bytes.size) throw IOException("App access preference too large")
            bytes.copyOf(length).toString(Charsets.US_ASCII)
        }
        val match = Regex("AIyou startup access v1\n([1-9][0-9]{0,18})\n(true|false)\n").matchEntire(raw)
            ?: throw IOException("Invalid app access preference")
        val version = match.groupValues[1].toLongOrNull() ?: throw IOException("Invalid app access version")
        AppAccessPreference(version, match.groupValues[2] == "true")
    }

    override fun write(expected: AppAccessPreference, enabled: Boolean): AppAccessPreference = synchronized(guard) {
        if (read() != expected || expected.version < 0 || expected.version == Long.MAX_VALUE)
            throw IOException("App access preference changed")
        val next = AppAccessPreference(expected.version + 1, enabled)
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write("AIyou startup access v1\n${next.version}\n${next.enabled}\n".toByteArray(Charsets.US_ASCII))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (failure: Exception) { atomic.failWrite(stream); throw failure }
        if (read() != next) throw IOException("App access preference not confirmed")
        next
    }

    private companion object { val locks = ConcurrentHashMap<String, Any>() }
}
