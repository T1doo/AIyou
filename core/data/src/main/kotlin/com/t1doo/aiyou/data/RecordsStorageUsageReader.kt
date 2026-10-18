package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.StorageUsageReader
import com.t1doo.aiyou.model.LocalStorageUsage
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Never opens file contents, follows links, creates directories or performs cleanup. */
class RecordsStorageUsageReader internal constructor(
    private val recordsDirectory: File,
    private val entryLimit: Int,
    private val depthLimit: Int,
) : StorageUsageReader {
    constructor(recordsDirectory: File) : this(recordsDirectory, 100_000, 16)

    override fun read(): LocalStorageUsage {
        val root = recordsDirectory.toPath().toAbsolutePath().normalize()
        // A fresh installation may have no records directory. Other IO failures propagate.
        val attributes = try { Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { return LocalStorageUsage() }
        if (!attributes.isDirectory || attributes.isSymbolicLink) throw IOException("Invalid records directory")
        val sizes = LongArray(4)
        var entries = 0
        fun checkBudget() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Measurement cancelled")
            if (++entries > entryLimit) throw IOException("Measurement entry limit exceeded")
        }
        Files.walkFileTree(root, emptySet(), depthLimit, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkBudget()
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkBudget()
                // Directories at the depth limit arrive here too: partial totals are never success.
                if (!attrs.isRegularFile || attrs.isSymbolicLink) throw IOException("Unsupported records file")
                val relative = root.relativize(file)
                val bucket = when {
                    relative.nameCount == 1 && relative.toString() in DATABASE_FILES -> 0
                    relative.nameCount > 1 && relative.getName(0).toString() == "records.db.attachments" -> 1
                    relative.nameCount > 1 && relative.getName(0).toString() == "records.db.original-edits" -> 2
                    else -> 3
                }
                sizes[bucket] = Math.addExact(sizes[bucket], attrs.size())
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                return FileVisitResult.CONTINUE
            }
        })
        return LocalStorageUsage(sizes[0], sizes[1], sizes[2], sizes[3])
    }

    private companion object {
        val DATABASE_FILES = setOf("records.db", "records.db-wal", "records.db-shm", "records.db-journal")
    }
}
