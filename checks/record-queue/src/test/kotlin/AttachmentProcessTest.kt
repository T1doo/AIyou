package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.AttachmentState
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Independent JVM PIDs, production file protocol, owned child termination; never Android/Room. */
class AttachmentProcessTest {
    @Test(timeout = 90000) fun distinctPidsRejectRecoveryAndImportWhileWriterOwnsLockThenRecoverOnce() {
        val s = Scenario("lock")
        val before: BlobIdentity
        val ledgerBefore: ByteArray
        s.start("holder", "write", "staged").use { writer ->
            writer.awaitStage("staged")
            before = s.identity(true)
            ledgerBefore = s.ledger.readBytes()
            s.start("contender", "busy").use { contender ->
                assertNotEquals(writer.pid, contender.pid)
                val result = contender.finish("BUSY")
                assertEquals(0, result.sourceCalls)
                assertArrayEquals(ledgerBefore, s.ledger.readBytes())
                assertEquals(before, s.identity(true))
                s.event("busy", contender.pid, "contender", mapOf("state" to result.state, "source_calls" to 0))
            }
            writer.terminateAt("staged")
            s.verifyInNewPid(writer.pid, "recover", 0)
        }
        assertEquals(before, s.identity(false))
        assertFalse(s.partial.exists())
        assertEquals(1, s.blobs.listFiles()!!.count { it.name.endsWith(".aead") })
        val final = s.identity(false)
        s.start("changed-intent", "conflict").use { child ->
            val result = child.finish("CONFLICT")
            s.event("conflict", child.pid, "changed-intent", mapOf("state" to result.state, "source_calls" to result.sourceCalls))
        }
        assertEquals(final, s.identity(false))
    }

    @Test(timeout = 90000) fun terminatedWritingPidsNeedExplicitSameIntentSourceRetryEvenWithCompleteCiphertext() {
        for (phase in listOf("intent", "writing", "encrypted")) {
            val s = Scenario(phase)
            s.start("writer", "write", phase).use { writer ->
                writer.awaitStage(phase)
                assertEquals(AttachmentState.WRITING, s.row.state)
                if (phase == "intent") assertFalse(s.partial.exists())
                else assertTrue(s.partial.length() >= 17L)
                if (phase == "writing") assertTrue(s.partial.length() in 18L until processBytes().size + 33L)
                if (phase == "encrypted") assertEquals(processBytes().size + 33L, s.partial.length())
                writer.terminateAt(phase)
                s.verifyInNewPid(writer.pid, "retry", 1)
            }
            assertEquals(AttachmentState.READY, s.row.state)
            assertFalse(s.partial.exists())
            assertEquals(1, s.blobs.listFiles()!!.count { it.name.endsWith(".aead") })
        }
    }

    @Test(timeout = 90000) fun terminatedStagedAndPublishedPidsRecoverSameAuthenticatedFileWithoutRecopying() {
        for (phase in listOf("staged", "published")) {
            val s = Scenario(phase)
            val before: BlobIdentity
            s.start("writer", "write", phase).use { writer ->
                writer.awaitStage(phase)
                assertEquals(AttachmentState.STAGED, s.row.state)
                before = s.identity(phase == "staged")
                writer.terminateAt(phase)
                s.verifyInNewPid(writer.pid, "recover", 0)
            }
            assertEquals(before, s.identity(false)) // Same inode/file-key, bytes, size and mtime; no rewrite.
            assertFalse(s.partial.exists())
            assertEquals(AttachmentState.READY, s.row.state)
        }
    }

    @Test(timeout = 90000) fun terminatedPidAfterDurableReadyBeforeLedgerAcknowledgementRecoversReadOnly() {
        val s = Scenario("ledger-ack")
        val before: BlobIdentity
        s.start("writer", "write", "ledger-ack").use { writer ->
            writer.awaitStage("ledger-ack")
            assertEquals(AttachmentState.READY, s.row.state) // Fixture commit persisted, transition has not returned.
            assertFalse(writer.resultFile.exists()) // No save acknowledgement from the writer.
            before = s.identity(false)
            writer.terminateAt("ledger-ack")
            s.verifyInNewPid(writer.pid, "recover", 0)
        }
        assertEquals(before, s.identity(false))
        assertEquals(AttachmentState.READY, s.row.state)
        assertFalse(s.partial.exists())
    }

    @Test(timeout = 90000) fun independentPidRecoveryRejectsChangedIdentitySourceHashKeyAndCiphertext() {
        for (damage in listOf("identity", "source", "revision", "digest", "type", "tag", "key")) {
            val s = Scenario("bad-$damage")
            var candidate = s.partial
            val writerPid: Long
            s.start("writer", "write", "staged").use { writer ->
                writer.awaitStage("staged")
                writerPid = writer.pid
                writer.terminateAt("staged")
            }
            val row = s.row
            val altered = when (damage) {
                "identity" -> row.intent.copy(id = UUID.randomUUID().toString())
                "source" -> row.intent.copy(entryId = "different-source")
                "revision" -> row.intent.copy(revision = 2)
                "digest" -> row.intent.copy(sha256 = "0".repeat(64))
                "type" -> row.intent.copy(mediaType = "audio/ogg")
                else -> row.intent
            }
            if (damage == "identity") {
                candidate = File(s.blobs, altered.id + ".tmp")
                Files.move(s.partial.toPath(), candidate.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            if (damage == "tag") candidate.writeBytes(candidate.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            ProcessDiskLedger(s.ledger).replaceForTest(row.copy(intent = altered)) // No child still alive.
            val before = identity(candidate)
            s.start("rejector", if (damage == "key") "wrong-key" else "reject").use { child ->
                assertNotEquals(writerPid, child.pid)
                val result = child.finish("CORRUPT")
                assertEquals(AttachmentState.CORRUPT.name, result.state)
                assertEquals("", result.readHash)
                assertEquals(0, result.sourceCalls)
                s.event("rejected", child.pid, "rejector", mapOf("writer_pid" to writerPid, "state" to result.state, "source_calls" to result.sourceCalls))
            }
            assertEquals(before, identity(candidate))
            assertEquals(AttachmentState.CORRUPT, s.row.state)
            assertTrue(s.blobs.listFiles()!!.none { it.name.endsWith(".aead") })
        }
        val s = Scenario("bad-source-bytes")
        s.start("bad-writer", "bad-source").use { child ->
            val result = child.finish("SOURCE_REJECTED")
            assertEquals(AttachmentState.FAILED.name, result.state)
            assertEquals("", result.readHash)
            assertEquals(1, result.sourceCalls)
            s.event("source-rejected", child.pid, "bad-writer", mapOf("state" to result.state, "source_calls" to 1))
        }
        assertFalse(s.final.exists())
        assertEquals(AttachmentState.FAILED, s.row.state)
    }

    private class Scenario(val name: String) {
        private val evidence = File(checkNotNull(System.getProperty("aiyou.attachment.process.evidence"))).also { it.mkdirs(); check(it.isDirectory) }
        val root: File = Files.createTempDirectory(evidence.toPath(), "$name-").toFile()
        val id = UUID.randomUUID().toString()
        val control = File(root, "control").also { check(it.mkdir()) }
        val blobs = File(root, "blobs")
        val ledger = File(root, "ledger")
        val partial get() = File(blobs, "$id.tmp")
        val final get() = File(blobs, "$id.aead")
        val row get() = ProcessDiskLedger(ledger).rows().single()
        fun start(label: String, mode: String, phase: String = "none") = OwnedJvm(this, label, mode, phase)
        fun identity(partial: Boolean) = identity(if (partial) this.partial else final)
        fun verifyInNewPid(writer: Long, mode: String, sourceCalls: Int) {
            start("recoverer", mode).use { child ->
                assertNotEquals(writer, child.pid)
                val result = child.finish("VERIFIED")
                assertEquals(AttachmentState.READY.name, result.state)
                assertEquals(processHash(processBytes()), result.readHash)
                assertEquals(sourceCalls, result.sourceCalls)
                event("verified", child.pid, "recoverer", mapOf("writer_pid" to writer, "state" to result.state,
                    "read_sha256" to result.readHash, "source_calls" to result.sourceCalls,
                    "cipher_sha256" to identity(false).hash, "cipher_file_key" to identity(false).fileKey,
                    "cipher_bytes" to identity(false).size, "cipher_mtime_millis" to identity(false).modifiedMillis))
            }
        }
        fun event(event: String, pid: Long, role: String, extra: Map<String, Any> = emptyMap()) {
            val fields = linkedMapOf<String, Any>("scenario" to name, "event" to event, "pid" to pid,
                "parent_pid" to ProcessHandle.current().pid(), "role" to role, "time_millis" to System.currentTimeMillis()) + extra
            val line = fields.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
                fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
                quote(key) + ":" + if (value is Number || value is Boolean) value.toString() else quote(value.toString())
            }
            File(root, "events.jsonl").appendText(line + "\n")
            println("D02_ATTACHMENT_PROCESS_EVENT=$line")
        }
    }

    private class OwnedJvm(private val scenario: Scenario, private val label: String, mode: String, phase: String) : AutoCloseable {
        private val log = File(scenario.control, "$label.stdout")
        private val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp", checkNotNull(System.getProperty("aiyou.attachment.process.classpath")),
            AttachmentProcessDriver::class.java.name, scenario.root.absolutePath, label, mode, scenario.id, phase)
            .redirectErrorStream(true).redirectOutput(log).start()
        val pid = child.pid()
        val resultFile = File(scenario.control, "$label.result")
        private var exited = false
        private var reached: String? = null
        init {
            assertNotEquals(ProcessHandle.current().pid(), pid)
            scenario.event("started", pid, label)
        }
        fun awaitStage(stage: String) {
            val marker = File(scenario.control, "$label.stage")
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!marker.isFile) {
                check(child.isAlive && System.nanoTime() < end) { "Owned stage unavailable: $stage; ${log.readText()}" }
                Thread.sleep(10)
            }
            DataInputStream(FileInputStream(marker)).use { assertEquals(pid, it.readLong()); assertEquals(stage, it.readUTF()) }
            validateChildIdentity()
            reached = stage
            val candidate = if (scenario.partial.exists()) scenario.partial else scenario.final
            val snapshot = if (candidate.exists()) identity(candidate) else null
            scenario.event("reached", pid, label, mapOf("stage" to stage, "ledger_state" to scenario.row.state.name,
                "cipher_bytes" to (snapshot?.size ?: 0L), "cipher_sha256" to (snapshot?.hash ?: ""),
                "cipher_file_key" to (snapshot?.fileKey ?: ""), "cipher_mtime_millis" to (snapshot?.modifiedMillis ?: 0L)))
        }
        fun terminateAt(stage: String) {
            assertEquals(stage, reached)
            assertTrue(child.isAlive)
            scenario.event("terminate-requested", pid, label, mapOf("stage" to stage))
            child.destroyForcibly() // Only this exact ProcessBuilder-owned process, never an arbitrary PID.
            awaitExit()
            assertNotEquals(0, child.exitValue())
            assertFalse(resultFile.exists())
        }
        fun finish(expected: String): ChildResult {
            awaitExit()
            assertEquals(log.readText(), 0, child.exitValue())
            validateChildIdentity()
            return DataInputStream(FileInputStream(resultFile)).use {
                ChildResult(it.readLong(), it.readUTF(), it.readUTF(), it.readUTF(), it.readInt())
            }.also { assertEquals(pid, it.pid); assertEquals(expected, it.status) }
        }
        private fun validateChildIdentity() {
            DataInputStream(FileInputStream(File(scenario.control, "$label.pid"))).use {
                assertEquals(pid.toString(), it.readUTF()); assertEquals(ProcessHandle.current().pid(), it.readLong())
            }
        }
        private fun awaitExit() {
            check(child.waitFor(10, TimeUnit.SECONDS)) { "Owned child exit deadline exceeded; ${log.readText()}" }
            assertFalse(child.isAlive)
            if (!exited) {
                scenario.event("exited", pid, label, mapOf("exit" to child.exitValue(), "alive" to false,
                    "result_written" to resultFile.isFile))
                println(log.readText()) // PID/stage/status only; no source bytes or keys.
                exited = true
            }
        }
        override fun close() {
            if (child.isAlive) {
                scenario.event("cleanup-requested", pid, label)
                child.destroyForcibly()
            }
            awaitExit() // Every started child has a confirmed exit, also on a failed assertion.
        }
    }
    private data class ChildResult(val pid: Long, val status: String, val state: String, val readHash: String, val sourceCalls: Int)
    private data class BlobIdentity(val hash: String, val size: Long, val modifiedMillis: Long, val fileKey: String)
    companion object {
        private fun identity(file: File): BlobIdentity {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            return BlobIdentity(processHash(file.readBytes()), attrs.size(), attrs.lastModifiedTime().toMillis(), checkNotNull(attrs.fileKey()).toString())
        }
    }
}
