package com.t1doo.aiyou.storage

import com.t1doo.aiyou.data.RecordsStorageUsageReader
import com.t1doo.aiyou.domain.StorageUsageReader
import com.t1doo.aiyou.model.LocalStorageUsage
import com.t1doo.aiyou.ui.StorageUsageController
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageUsageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun countsActualDatabaseSidecarsCiphertextDraftsAndRecoveryFilesWithoutChangingBytes() {
        val root = temporary.newFolder("records")
        val files = mapOf("records.db" to 113, "records.db-wal" to 7, "records.db-shm" to 5,
            "records.db-journal" to 3, "records.db.attachments/a.aead" to 29,
            "records.db.attachments/b.tmp" to 11, "records.db.original-edits/c.aead" to 17,
            "pending-erasure/receipt" to 19, "other.bin" to 23)
        files.forEach { (name, size) -> write(root, name, size) }
        val snapshots = files.keys.associateWith { File(root, it).readBytes() }
        val reader = RecordsStorageUsageReader(root)
        assertEquals(LocalStorageUsage(128, 40, 17, 42), reader.read())
        snapshots.forEach { (name, bytes) -> assertArrayEquals(bytes, File(root, name).readBytes()) }
        assertEquals(files.keys, root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toSet())
        File(root, "records.db.attachments/b.tmp").delete().also { assertTrue(it) }
        assertEquals(LocalStorageUsage(128, 29, 17, 42), reader.read())
    }

    @Test fun freshInstallationReturnsZeroWithoutCreatingRecordsDirectoryOrReadingSiblingKeys() {
        val root = File(temporary.root, "records")
        val key = write(temporary.root, "keys/untouched", 43)
        val before = key.readBytes()
        assertEquals(LocalStorageUsage(), RecordsStorageUsageReader(root).read())
        assertFalse(root.exists())
        assertArrayEquals(before, key.readBytes())
    }

    @Test fun invalidRootAndNestedSymbolicLinkFailInsteadOfReportingZeroOrFollowingOutside() {
        val outside = temporary.newFolder("outside")
        val secret = write(outside, "synthetic-private.bin", 77)
        val root = temporary.newFolder("records")
        write(root, "records.db", 11)
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { RecordsStorageUsageReader(root).read() }
        val rootLink = File(temporary.root, "root-link")
        Files.createSymbolicLink(rootLink.toPath(), root.toPath())
        assertThrows(IOException::class.java) { RecordsStorageUsageReader(rootLink).read() }
        assertThrows(IOException::class.java) { RecordsStorageUsageReader(secret).read() }
        assertEquals(77L, secret.length())
    }

    @Test fun danglingLinkCannotBecomePartialSuccess() {
        val root = temporary.newFolder("records")
        write(root, "records.db", 11)
        Files.createSymbolicLink(File(root, "missing").toPath(), File(temporary.root, "absent").toPath())
        assertThrows(IOException::class.java) { RecordsStorageUsageReader(root).read() }
    }

    @Test fun entryAndDepthBudgetsRejectPartialTotals() {
        val root = temporary.newFolder("records")
        write(root, "records.db", 11)
        write(root, "records.db.attachments/a.aead", 19)
        assertThrows(IOException::class.java) { RecordsStorageUsageReader(root, 2, 16).read() }
        assertThrows(IOException::class.java) { RecordsStorageUsageReader(root, 100, 1).read() }
        assertEquals(LocalStorageUsage(11, 19), RecordsStorageUsageReader(root).read())
    }

    @Test fun interruptedMeasurementCannotReturnPartialSuccessAndOverflowIsRejected() {
        val root = temporary.newFolder("records")
        write(root, "records.db", 11)
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedIOException::class.java) { RecordsStorageUsageReader(root).read() }
        } finally { Thread.interrupted() }
        assertThrows(ArithmeticException::class.java) { LocalStorageUsage(Long.MAX_VALUE, 1) }
        assertThrows(IllegalArgumentException::class.java) { LocalStorageUsage(-1) }
    }

    @Test fun explicitQueryRunsOnlyOnIoRejectsBusyDuplicateAndRefreshesChangedMeasurement() {
        val main = Steps(); val io = Steps(); val scope = CoroutineScope(SupervisorJob() + main)
        var calls = 0
        var bytes = 13L
        val controller = StorageUsageController(scope, StorageUsageReader { calls++; LocalStorageUsage(bytes) }, io)
        try {
            val owner = controller.enter()
            main.drain(); io.drain()
            assertEquals(0, calls)
            controller.query(owner); controller.query(owner)
            assertTrue(controller.state.value.busy)
            main.drain()
            assertEquals("Reader is queued on IO, never the UI dispatcher", 0, calls)
            io.drain(); main.drain()
            assertEquals(1, calls)
            assertEquals(13L, controller.state.value.usage?.totalBytes)
            bytes = 31
            controller.query(owner)
            assertNull(controller.state.value.usage)
            main.drain(); io.drain(); main.drain()
            assertEquals(2, calls)
            assertEquals(31L, controller.state.value.usage?.totalBytes)
        } finally { controller.dispose(); scope.cancel() }
    }

    @Test fun readFailureHidesPreviousTotalAndExplicitRetryCanConfirmRealZero() {
        val main = Steps(); val io = Steps(); val scope = CoroutineScope(SupervisorJob() + main)
        var fail = false
        var bytes = 31L
        val controller = StorageUsageController(scope, StorageUsageReader {
            if (fail) throw IOException("synthetic-private-path-must-not-be-published")
            LocalStorageUsage(bytes)
        }, io)
        fun query(owner: Long) { controller.query(owner); main.drain(); io.drain(); main.drain() }
        try {
            val owner = controller.enter()
            query(owner)
            assertEquals(31L, controller.state.value.usage?.totalBytes)
            fail = true; query(owner)
            assertTrue(controller.state.value.error)
            assertNull(controller.state.value.usage)
            assertFalse(controller.state.value.busy)
            fail = false; bytes = 0; query(owner)
            assertEquals(LocalStorageUsage(), controller.state.value.usage)
            assertFalse(controller.state.value.error)
        } finally { controller.dispose(); scope.cancel() }
    }

    @Test fun navigationDuringAnAlreadyExecutingReadRejectsItsResultAndRetiredCallback() {
        val main = Steps(); val io = Steps(); val scope = CoroutineScope(SupervisorJob() + main)
        lateinit var controller: StorageUsageController
        var owner = 0L
        var next = 0L
        var calls = 0
        controller = StorageUsageController(scope, StorageUsageReader {
            calls++
            // The old reader has already started and still returns after its UI owner has left.
            controller.leave(owner)
            next = controller.enter()
            LocalStorageUsage(999)
        }, io)
        try {
            owner = controller.enter()
            controller.query(owner); main.drain(); io.drain(); main.drain()
            assertEquals(1, calls)
            assertTrue(next > owner)
            assertEquals(next, controller.state.value.session)
            assertNull(controller.state.value.usage)
            assertFalse(controller.state.value.busy)
            controller.query(owner); controller.leave(owner)
            main.drain(); io.drain(); main.drain()
            assertEquals(1, calls)
            assertTrue(controller.state.value.active)
        } finally { controller.dispose(); scope.cancel() }
    }

    @Test fun disposalCancelsQueuedIoAndCannotReopenOrPublish() {
        val main = Steps(); val io = Steps(); val scope = CoroutineScope(SupervisorJob() + main)
        var calls = 0
        val controller = StorageUsageController(scope, StorageUsageReader { calls++; LocalStorageUsage(999) }, io)
        val owner = controller.enter()
        controller.query(owner); main.drain()
        controller.dispose(); controller.enter(); controller.query(owner)
        io.drain(); main.drain()
        assertEquals(0, calls)
        assertFalse(controller.state.value.active)
        assertNull(controller.state.value.usage)
        scope.cancel()
    }

    private fun write(root: File, name: String, size: Int): File = File(root, name).also {
        check(it.parentFile.mkdirs() || it.parentFile.isDirectory)
        it.writeBytes(ByteArray(size) { index -> (index % 251).toByte() })
    }
    private class Steps : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun drain() {
            var runs = 0
            while (queue.isNotEmpty()) { check(++runs < 100); queue.removeFirst().run() }
        }
    }
}
