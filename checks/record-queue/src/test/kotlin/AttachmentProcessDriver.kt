package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.AttachmentBusy
import com.t1doo.aiyou.domain.AttachmentConflict
import com.t1doo.aiyou.domain.AttachmentIntegrityFailure
import com.t1doo.aiyou.model.AttachmentIntent
import com.t1doo.aiyou.model.AttachmentState
import com.t1doo.aiyou.model.LocalAttachment
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Test-only child process: real production file protocol, explicitly synthetic disk ledger. */
object AttachmentProcessDriver {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 5)
        val root = File(args[0]).canonicalFile
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
        val label = args[1].also { require(it.matches(Regex("[a-z0-9-]+"))) }
        val mode = args[2]
        val request = processIntent(args[3])
        val pause = args[4]
        val pid = ProcessHandle.current().pid()
        val control = File(root, "control").also { check(it.isDirectory) }
        durableTestFile(File(control, "$label.pid")) {
            it.writeUTF(pid.toString()); it.writeLong(ProcessHandle.current().parent().orElseThrow().pid())
        }
        println("CHILD_START pid=$pid mode=$mode")
        fun gate(stage: String) {
            if (pause != stage) return
            durableTestFile(File(control, "$label.stage")) { it.writeLong(pid); it.writeUTF(stage) }
            println("CHILD_PHASE pid=$pid stage=$stage")
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            val release = File(control, "$label.release")
            while (!release.isFile) {
                check(System.nanoTime() < end) { "Owned child stage deadline exceeded" }
                Thread.sleep(10)
            }
        }
        val ledger = ProcessDiskLedger(File(root, "ledger")) { gate("ledger-ack") }
        val key = ByteArray(32) { if (mode == "wrong-key") 9 else (it + 1).toByte() }
        var sourceCalls = 0
        var status = ""
        var readHash = ""
        EncryptedAttachmentStore(File(root, "blobs"), ledger, key, ::gate).use { store ->
            when (mode) {
                "write", "bad-source" -> {
                    val data = processBytes().also { if (mode == "bad-source") it[0] = (it[0].toInt() xor 1).toByte() }
                    try {
                        store.save(request) {
                            sourceCalls++
                            object : InputStream() {
                                private val input = ByteArrayInputStream(data)
                                private var reads = 0
                                override fun read(): Int = input.read()
                                override fun read(target: ByteArray, offset: Int, size: Int): Int {
                                    if (++reads == 2) gate("writing") // header + first encrypted chunk already written
                                    return input.read(target, offset, size)
                                }
                            }
                        }
                        check(mode == "write")
                        status = "READY"
                    } catch (failure: AttachmentIntegrityFailure) {
                        check(mode == "bad-source")
                        store.recover()
                        status = "SOURCE_REJECTED"
                    }
                }
                "busy" -> {
                    try { store.recover(); error("Expected cross-process recovery lock refusal") }
                    catch (_: AttachmentBusy) { /* Both recovery and import must respect the writer. */ }
                    try { store.save(request) { sourceCalls++; ByteArrayInputStream(processBytes()) }; error("Expected cross-process lock refusal") }
                    catch (_: AttachmentBusy) { status = "BUSY" }
                    check(sourceCalls == 0)
                }
                "conflict" -> {
                    try { store.save(request.copy(entryId = "different-source")) { sourceCalls++; error("Must reject before source") }; error("Expected immutable intent conflict") }
                    catch (_: AttachmentConflict) { status = "CONFLICT" }
                    check(sourceCalls == 0)
                }
                "recover", "retry", "reject", "wrong-key" -> {
                    store.recover()
                    val row = ledger.all().single()
                    if (mode == "retry") {
                        check(row.state == AttachmentState.FAILED)
                        check(!File(root, "blobs/${request.id}.aead").exists())
                        store.save(request) { sourceCalls++; ByteArrayInputStream(processBytes()) }
                    }
                    if (mode == "reject" || mode == "wrong-key") {
                        check(row.state == AttachmentState.CORRUPT)
                        try { store.read(row.intent.id); error("Must not release plaintext") }
                        catch (_: AttachmentIntegrityFailure) { status = "CORRUPT" }
                        check(sourceCalls == 0)
                    } else {
                        check(store.save(request) { sourceCalls++; error("Must not copy authenticated saved bytes again") }.state == AttachmentState.READY)
                        val clear = store.read(request.id)
                        check(clear.contentEquals(processBytes()))
                        readHash = processHash(clear)
                        clear.fill(0)
                        check(sourceCalls == if (mode == "retry") 1 else 0)
                        status = "VERIFIED"
                    }
                }
                else -> error("Unknown owned child operation")
            }
        }
        val row = ledger.all().singleOrNull()
        durableTestFile(File(control, "$label.result")) {
            it.writeLong(pid); it.writeUTF(status); it.writeUTF(row?.state?.name ?: "NONE")
            it.writeUTF(readHash); it.writeInt(sourceCalls)
        }
        println("CHILD_RESULT pid=$pid status=$status state=${row?.state?.name ?: "NONE"} source_calls=$sourceCalls")
    }
}

internal fun processBytes() = ByteArray(64 * 1024) { ((it * 31 + it / 251) % 256).toByte() }
internal fun processHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun processIntent(id: String) = AttachmentIntent(id, "synthetic-original", 1, "image/png", processHash(processBytes()), processBytes().size.toLong())

/** Synthetic metadata only; this adapter never represents Room/SQLCipher transactions. */
internal class ProcessDiskLedger(private val file: File, private val beforeReadyAcknowledgement: () -> Unit = {}) : AttachmentLedger {
    fun rows(): List<LocalAttachment> {
        if (!file.exists()) return emptyList()
        return DataInputStream(FileInputStream(file)).use { input ->
            val count = input.readInt().also { check(it in 0..1) }
            List(count) {
                LocalAttachment(AttachmentIntent(input.readUTF(), input.readUTF(), input.readInt(), input.readUTF(), input.readUTF(), input.readLong()), AttachmentState.valueOf(input.readUTF()))
            }.also { check(input.read() == -1) }
        }
    }
    override suspend fun all() = rows()
    override suspend fun find(id: String) = rows().singleOrNull { it.intent.id == id }
    override suspend fun insert(value: LocalAttachment) {
        check(rows().isEmpty() && value.intent.entryId == "synthetic-original" && value.intent.revision == 1)
        replaceForTest(value)
    }
    override suspend fun transition(expected: LocalAttachment, next: AttachmentState): LocalAttachment {
        check(rows().single() == expected)
        return expected.copy(state = next).also {
            replaceForTest(it)
            if (next == AttachmentState.READY) beforeReadyAcknowledgement()
        }
    }
    // Parent calls only after the explicitly owned writer has exited; intentional tamper fixture.
    fun replaceForTest(value: LocalAttachment) = durableTestFile(file) {
        it.writeInt(1)
        it.writeUTF(value.intent.id); it.writeUTF(value.intent.entryId); it.writeInt(value.intent.revision)
        it.writeUTF(value.intent.mediaType); it.writeUTF(value.intent.sha256); it.writeLong(value.intent.byteSize)
        it.writeUTF(value.state.name)
    }
}

internal fun durableTestFile(file: File, content: (DataOutputStream) -> Unit) {
    val next = File(file.path + ".next")
    check(!next.exists())
    FileOutputStream(next).use { stream ->
        DataOutputStream(stream).also { content(it); it.flush() }
        stream.fd.sync()
    }
    Files.move(next.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    FileChannel.open(checkNotNull(file.parentFile).toPath(), StandardOpenOption.READ).use { it.force(true) }
}
