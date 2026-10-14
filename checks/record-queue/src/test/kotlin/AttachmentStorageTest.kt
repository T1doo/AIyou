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
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real product encryption/files/locking with a durable test ledger; Room/native is not simulated. */
class AttachmentStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "合成原始附件字节 🌿\n".repeat(700).toByteArray()
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val root get() = temporary.root
    private val directory get() = File(root, "blobs")
    private fun ledger() = DiskLedger(File(root, "ledger"))
    private fun store(ledger: DiskLedger = ledger(), checkpoint: (String) -> Unit = {}) =
        EncryptedAttachmentStore(directory, ledger, key.copyOf(), checkpoint).also {
            it.installErasureHooks(SyntheticCreationProofHooks(root))
        }
    private fun intent(data: ByteArray = bytes, id: String = UUID.randomUUID().toString()) =
        AttachmentIntent(id, "synthetic-original", 1, "image/png", hash(data), data.size.toLong())
    private fun source(data: ByteArray = bytes): () -> InputStream = { ByteArrayInputStream(data) }
    private fun blob(value: AttachmentIntent) = File(directory, value.id + ".aead")
    private fun partial(value: AttachmentIntent) = File(directory, value.id + ".tmp")

    @Test fun readyBytesAreEncryptedVerifiedAndSurviveNewStoreAndLedger() = runBlocking {
        val request = intent()
        val first = store()
        assertEquals(AttachmentState.READY, first.save(request, source()).state)
        assertArrayEquals(bytes, first.read(request.id))
        first.close()
        val ciphertext = blob(request).readBytes()
        assertEquals(bytes.size + 33, ciphertext.size)
        assertFalse(String(ciphertext, Charsets.ISO_8859_1).contains(String(bytes.take(64).toByteArray(), Charsets.ISO_8859_1)))
        assertFalse(partial(request).exists())
        val reopened = store()
        reopened.recover()
        assertArrayEquals(bytes, reopened.read(request.id))
        assertEquals(listOf(LocalAttachment(request, AttachmentState.READY)), reopened.list(request.entryId, 1))
        assertTrue(reopened.list(request.entryId, 2).isEmpty())
    }

    @Test fun interruptionBeforeStagedRequiresExplicitRetryAndPreservesIntent() = runBlocking {
        for (phase in listOf("intent", "encrypted")) {
            val request = intent()
            val interrupted = store(checkpoint = { if (it == phase) throw IOException("synthetic interruption") })
            fails<IOException> { interrupted.save(request, source()) }
            interrupted.close()
            val reopened = store()
            reopened.recover()
            assertEquals(AttachmentState.FAILED, checkNotNull(ledger().find(request.id)).state)
            assertFalse(blob(request).exists())
            fails<AttachmentIntegrityFailure> { reopened.read(request.id) }
            assertEquals(AttachmentState.READY, reopened.save(request, source()).state)
            assertArrayEquals(bytes, reopened.read(request.id))
        }
    }

    @Test fun stagedAndPublishedInterruptionsRecoverWithoutSourceOrDuplicateBytes() = runBlocking {
        for (phase in listOf("staged", "published")) {
            val request = intent()
            val interrupted = store(checkpoint = { if (it == phase) throw IOException("synthetic interruption") })
            fails<IOException> { interrupted.save(request, source()) }
            interrupted.close()
            assertEquals(AttachmentState.STAGED, checkNotNull(ledger().find(request.id)).state)
            val reopened = store()
            reopened.recover()
            assertArrayEquals(bytes, reopened.read(request.id))
            assertEquals(AttachmentState.READY, reopened.save(request) { error("must not reopen source") }.state)
            assertFalse(partial(request).exists())
        }
    }

    @Test fun failedReadyLedgerCommitRecoversPublishedBlobWithoutWritingAgain() = runBlocking {
        val request = intent()
        val failing = ledger().apply { failReadyBefore = true }
        fails<IOException> { store(failing).save(request, source()) }
        val ciphertext = blob(request).readBytes()
        assertEquals(AttachmentState.STAGED, checkNotNull(ledger().find(request.id)).state)
        assertEquals(AttachmentState.READY, store().save(request) { error("must not copy twice") }.state)
        assertArrayEquals(ciphertext, blob(request).readBytes())
    }

    @Test fun lostReadyAcknowledgementIsReadOnlyAndStableIdIsIdempotent() = runBlocking {
        val request = intent()
        val failing = ledger().apply { failReadyAfter = true }
        fails<IOException> { store(failing).save(request, source()) }
        val ciphertext = blob(request).readBytes()
        val reopened = store()
        assertEquals(AttachmentState.READY, reopened.save(request) { error("must not reopen source") }.state)
        assertEquals(1, reopened.list(request.entryId, 1).size)
        assertArrayEquals(ciphertext, blob(request).readBytes())
    }

    @Test fun badHashSizeAndInterruptedSourceNeverBecomeReadyAndRetryKeepsIdentity() = runBlocking {
        val alternatives = listOf(bytes.dropLast(1).toByteArray(), bytes + 0, bytes.copyOf().apply { this[0] = 0 })
        for (wrong in alternatives) {
            val request = intent()
            fails<AttachmentIntegrityFailure> { store().save(request, source(wrong)) }
            val reopened = store()
            reopened.recover()
            assertEquals(AttachmentState.FAILED, checkNotNull(ledger().find(request.id)).state)
            assertFalse(blob(request).exists())
            assertEquals(request, reopened.save(request, source()).intent)
        }
        val request = intent()
        fails<IOException> { store().save(request) { object : InputStream() {
            override fun read(): Int = throw IOException("synthetic source loss")
        } } }
        store().recover()
        assertEquals(AttachmentState.FAILED, checkNotNull(ledger().find(request.id)).state)
    }

    @Test fun alteredCiphertextAndTagReturnNoPlaintextAndRetainOriginalEvidence() = runBlocking {
        for (offset in listOf(0, 5, 17, bytes.size + 32)) {
            val request = intent()
            val value = store()
            value.save(request, source())
            val changed = blob(request).readBytes().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
            blob(request).writeBytes(changed)
            fails<AttachmentIntegrityFailure> { value.read(request.id) }
            assertEquals(AttachmentState.CORRUPT, checkNotNull(ledger().find(request.id)).state)
            fails<AttachmentIntegrityFailure> { value.save(request, source()) }
            assertArrayEquals(changed, blob(request).readBytes())
        }
    }

    @Test fun missingAndTruncatedStagedFilesAreCorruptAndNeverReportedSaved() = runBlocking {
        for (truncate in listOf(false, true)) {
            val request = intent()
            fails<IOException> { store(checkpoint = { if (it == "staged") throw IOException() }).save(request, source()) }
            if (truncate) partial(request).writeBytes(partial(request).readBytes().dropLast(1).toByteArray())
            else check(partial(request).delete())
            val reopened = store()
            reopened.recover()
            assertEquals(AttachmentState.CORRUPT, reopened.list(request.entryId, 1).single { it.intent.id == request.id }.state)
            assertFalse(blob(request).exists())
            fails<AttachmentIntegrityFailure> { reopened.read(request.id) }
        }
    }

    @Test fun metadataBindingRejectsWrongKeySourceRevisionMimeAndDigest() = runBlocking {
        val request = intent()
        val value = store()
        value.save(request, source())
        val encrypted = blob(request).readBytes()
        fails<AttachmentIntegrityFailure> {
            EncryptedAttachmentStore(directory, ledger(), ByteArray(32) { 9 }).read(request.id)
        }
        assertArrayEquals(encrypted, blob(request).readBytes())
        // Restore test metadata to READY and then simulate encrypted-ledger field tampering.
        for (altered in listOf(request.copy(revision = 2), request.copy(mediaType = "audio/ogg"), request.copy(sha256 = "0".repeat(64)))) {
            ledger().replaceForTest(LocalAttachment(altered, AttachmentState.READY))
            fails<AttachmentIntegrityFailure> { store().read(request.id) }
            assertArrayEquals(encrypted, blob(request).readBytes())
        }
    }

    @Test fun changedIntentAndInvalidRevisionNeverOverwriteOrCreateBlob() = runBlocking {
        val request = intent()
        val value = store()
        value.save(request, source())
        val encrypted = blob(request).readBytes()
        fails<AttachmentConflict> { value.save(request.copy(revision = 2), source()) }
        assertArrayEquals(encrypted, blob(request).readBytes())
        val other = intent().copy(entryId = "missing-original")
        fails<AttachmentConflict> { value.save(other) { error("invalid FK must precede source access") } }
        assertFalse(partial(other).exists()); assertFalse(blob(other).exists())
    }

    @Test fun pathTraversalOversizeAndUnsupportedTypesAreRejectedBeforeImport() = runBlocking {
        val valid = intent()
        for (invalid in listOf(valid.copy(id = "../outside"), valid.copy(revision = 0), valid.copy(mediaType = "text/html"),
            valid.copy(byteSize = 0), valid.copy(byteSize = EncryptedAttachmentStore.MAX_BYTES + 1), valid.copy(sha256 = "invalid"))) {
            fails<IllegalArgumentException> { store().save(invalid) { error("invalid intent must not read source") } }
        }
        assertTrue(ledger().all().isEmpty())
    }

    @Test fun unknownFilesAndSymlinksArePreservedWithoutEscapingOwnedDirectory() = runBlocking {
        check(directory.mkdirs())
        val unknown = File(directory, "unrelated.tmp").apply { writeText("synthetic unknown file") }
        store().recover()
        assertEquals("synthetic unknown file", unknown.readText())
        val request = intent()
        val outside = File(root, "outside").apply { writeText("synthetic external target") }
        Files.createSymbolicLink(partial(request).toPath(), outside.toPath())
        fails<IllegalStateException> { store().save(request, source()) }
        store().recover()
        fails<AttachmentIntegrityFailure> { store().save(request, source()) }
        assertEquals("synthetic external target", outside.readText())
        assertTrue(Files.isSymbolicLink(partial(request).toPath()))
        val linkedRoot = File(root, "linked")
        Files.createSymbolicLink(linkedRoot.toPath(), directory.toPath())
        fails<IllegalStateException> { EncryptedAttachmentStore(linkedRoot, ledger(), key.copyOf()).recover() }
    }

    @Test fun simultaneousStoreInstanceCannotRecoverOrReplaceAnActiveWriter() = runBlocking {
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val request = intent()
            val holdingLedger = object : AttachmentLedger by ledger() {
                override suspend fun insert(value: LocalAttachment) {
                    ledger().insert(value)
                    entered.complete(Unit)
                    release.await()
                }
            }
            val writer = async { EncryptedAttachmentStore(directory, holdingLedger, key.copyOf()).save(request, source()) }
            entered.await()
            fails<AttachmentBusy> { store().recover() }
            fails<AttachmentBusy> { store().save(request, source()) }
            assertEquals(AttachmentState.WRITING, checkNotNull(ledger().find(request.id)).state)
            release.complete(Unit)
            assertEquals(AttachmentState.READY, writer.await().state)
            assertArrayEquals(bytes, store().read(request.id))
        }
    }

    @Test fun freshNonceAndClosedStorePreventCiphertextReuseAndAfterCloseAccess() = runBlocking {
        val first = intent(); val second = intent()
        val value = store()
        value.save(first, source()); value.save(second, source())
        assertFalse(blob(first).readBytes().copyOfRange(5, 17).contentEquals(blob(second).readBytes().copyOfRange(5, 17)))
        value.close()
        fails<IllegalStateException> { value.read(first.id) }
        assertArrayEquals(bytes, store().read(first.id))
    }

    @Test fun maximumStorageBoundAuthenticatesExactBytesWithoutPlaintextFiles() = runBlocking {
        val data = ByteArray(EncryptedAttachmentStore.MAX_BYTES.toInt()) { (it % 251).toByte() }
        val request = intent(data)
        val value = store()
        value.save(request, source(data))
        assertEquals(data.size + 33L, blob(request).length())
        assertArrayEquals(data, value.read(request.id))
        assertEquals(setOf(".store.lock", request.id + ".aead"), directory.list()!!.toSet())
    }

    @Test fun closingDuringActiveImportNeverEncryptsWithErasedKey() = runBlocking {
        val request = intent()
        val ownedKey = key.copyOf()
        lateinit var value: EncryptedAttachmentStore
        value = EncryptedAttachmentStore(directory, ledger(), ownedKey) { phase ->
            if (phase == "intent") {
                value.close()
                assertArrayEquals(key, ownedKey)
            }
        }
        assertEquals(AttachmentState.READY, value.save(request, source()).state)
        assertTrue(ownedKey.all { it == 0.toByte() })
        fails<IllegalStateException> { value.read(request.id) }
        assertArrayEquals(bytes, store().read(request.id))
    }

    @Test fun startupRecoveryDetectsMissingOrAlteredReadyBytesBeforeListingSavedState() = runBlocking {
        for (missing in listOf(false, true)) {
            val request = intent()
            val value = store()
            value.save(request, source()); value.close()
            if (missing) check(blob(request).delete())
            else blob(request).writeBytes(blob(request).readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            val reopened = store()
            reopened.recover()
            assertEquals(AttachmentState.CORRUPT, reopened.list(request.entryId, 1).single { it.intent.id == request.id }.state)
            fails<AttachmentIntegrityFailure> { reopened.read(request.id) }
            fails<AttachmentIntegrityFailure> { reopened.save(request, source()) }
        }
    }

    private suspend inline fun <reified T : Throwable> fails(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") }
        catch (failure: Throwable) { assertTrue("Unexpected exception: ${failure.javaClass.simpleName}", failure is T) }
    }
}

/** Persists synthetic metadata to disk so recreating the adapter exercises recovery, not memory reuse. */
private class DiskLedger(private val file: File) : AttachmentLedger {
    var failReadyBefore = false
    var failReadyAfter = false
    override suspend fun find(id: String) = all().find { it.intent.id == id }
    override suspend fun all(): List<LocalAttachment> {
        if (!file.exists()) return emptyList()
        return DataInputStream(FileInputStream(file)).use { input ->
            List(input.readInt()) {
                LocalAttachment(AttachmentIntent(input.readUTF(), input.readUTF(), input.readInt(), input.readUTF(),
                    input.readUTF(), input.readLong()), AttachmentState.valueOf(input.readUTF()))
            }
        }
    }
    override suspend fun insert(value: LocalAttachment) {
        if (value.intent.entryId != "synthetic-original" || value.intent.revision != 1 || find(value.intent.id) != null)
            throw AttachmentConflict()
        persist(all() + value)
    }
    override suspend fun transition(expected: LocalAttachment, next: AttachmentState): LocalAttachment {
        if (find(expected.intent.id) != expected) throw AttachmentConflict()
        if (next == AttachmentState.READY && failReadyBefore) throw IOException("synthetic ledger failure")
        val result = expected.copy(state = next)
        persist(all().map { if (it == expected) result else it })
        if (next == AttachmentState.READY && failReadyAfter) throw IOException("synthetic acknowledgement loss")
        return result
    }
    suspend fun replaceForTest(value: LocalAttachment) = persist(all().map { if (it.intent.id == value.intent.id) value else it })
    private fun persist(rows: List<LocalAttachment>) {
        val temp = File(file.path + ".tmp")
        FileOutputStream(temp).use { stream ->
            val output = DataOutputStream(stream)
            output.writeInt(rows.size)
            rows.forEach {
                val intent = it.intent
                output.writeUTF(intent.id); output.writeUTF(intent.entryId); output.writeInt(intent.revision)
                output.writeUTF(intent.mediaType); output.writeUTF(intent.sha256); output.writeLong(intent.byteSize)
                output.writeUTF(it.state.name)
            }
            output.flush(); stream.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
