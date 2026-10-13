package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.StorageKeyUnavailable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Compiles and invokes the actual Android production factory; only platform boundaries are synthetic. */
class RepositoryCiphertextEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedEditEnumerationNeverCallsAnyKeyLoaderOrPublishesReplacement() {
        val root = temporary.newFolder()
        val edits = File(root, "records/records.db.original-edits").apply { mkdirs() }
        val ciphertext = File(edits, "synthetic.edit").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val replacement = File(root, "keys/original-edits.key")
        var calls = 0
        var failure: Throwable? = null
        try {
            EncryptedEntryRepository.openProtected(root,
                listFiles = { if (it == edits) null else it.listFiles() },
                loadKey = { purpose, existing ->
                    calls++
                    if (purpose == RepositoryKeyPurpose.EDITS && !existing) {
                        replacement.parentFile!!.mkdirs(); replacement.writeText("synthetic replacement")
                    }
                    ByteArray(32)
                },
            ) { _, _, editKey, attachmentKey -> editKey(); attachmentKey(false) }
        } catch (caught: Throwable) { failure = caught }
        assertTrue("Unknown edit evidence must fail closed, key calls=$calls replacement=${replacement.exists()}", failure is StorageKeyUnavailable)
        assertEquals(0, calls)
        assertFalse(replacement.exists())
        assertArrayEquals(byteArrayOf(9, 8, 7), ciphertext.readBytes())
    }

    private class Probe(val root: File) {
        val calls = mutableListOf<Pair<RepositoryKeyPurpose, Boolean>>()
        var opened = 0
        var ledger = false
        var ledgerFailure = false
        var beforeKeys: () -> Unit = {}
        var issuedKey: ByteArray? = null
        fun run(evidence: CiphertextEvidence = CiphertextEvidence()) = EncryptedEntryRepository.openProtected(root,
            evidence = evidence,
            loadKey = { purpose, existing -> calls += purpose to existing; ByteArray(32) { 6 }.also { if (purpose == RepositoryKeyPurpose.DATABASE) issuedKey = it } },
        ) { _, _, editKey, attachmentKey ->
            opened++
            beforeKeys()
            if (ledgerFailure) throw IOException("synthetic ledger failure")
            attachmentKey(ledger)
            editKey()
        }
    }

    private fun directory(root: File, suffix: String) = File(root, "records/records.db.$suffix").apply { mkdirs() }
    private fun attrs(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    private fun rejectBeforeKeys(probe: Probe, evidence: CiphertextEvidence = CiphertextEvidence()) {
        assertThrows(StorageKeyUnavailable::class.java) { probe.run(evidence) }
        assertTrue(probe.calls.isEmpty())
        assertEquals(0, probe.opened)
        assertFalse(File(probe.root, "keys").exists())
    }

    @Test fun newStoreAllowsThreeFreshKeysOnlyAfterPreflight() {
        val p = Probe(temporary.newFolder()); p.run()
        assertEquals(3, p.calls.size); assertTrue(p.calls.none { it.second })
        assertEquals(1, p.opened)
    }

    @Test fun emptyExistingDirectoriesAndARegularHeldEmptyLockAreNotCiphertext() {
        val root = temporary.newFolder(); val edits = directory(root, "original-edits"); directory(root, "attachments")
        val lock = File(edits, ".store.lock").toPath()
        FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { val p = Probe(root); p.run(); assertTrue(p.calls.none { it.second }) }
        }
        assertTrue(Files.exists(lock))
    }

    @Test fun ciphertextPartialAndUnknownRegularFilesAlwaysRequireExistingStoreKey() {
        for (suffix in listOf("original-edits", "attachments")) {
            for (name in listOf("synthetic.edit", "synthetic.edit.partial.uuid", "synthetic.aead", "synthetic.tmp", "unrecognized-entry", ".store.lock")) {
                val root = temporary.newFolder(); val dir = directory(root, suffix)
                val file = File(dir, name).apply { writeText("synthetic protected bytes") }
                val p = Probe(root); p.run()
                val purpose = if (suffix == "original-edits") RepositoryKeyPurpose.EDITS else RepositoryKeyPurpose.ATTACHMENTS
                assertEquals(true, p.calls.single { it.first == purpose }.second)
                assertEquals("synthetic protected bytes", file.readText())
            }
        }
    }

    @Test fun attachmentEnumerationNullOrThrownIoFailsBeforeAllKeys() {
        for (throws in listOf(false, true)) {
            val root = temporary.newFolder(); val dir = directory(root, "attachments")
            rejectBeforeKeys(Probe(root), CiphertextEvidence(listFiles = { if (it == dir) { if (throws) throw IOException("synthetic"); null } else it.listFiles() }))
        }
    }

    @Test fun attributeDenialIsNotMissingPath() {
        val root = temporary.newFolder(); directory(root, "original-edits")
        rejectBeforeKeys(Probe(root), CiphertextEvidence(attributes = { if (it.name.endsWith("original-edits")) throw java.nio.file.AccessDeniedException("synthetic") else attrs(it) }))
    }

    @Test fun unavailableFileIdentityFailsBeforeKeys() {
        val root = temporary.newFolder()
        rejectBeforeKeys(Probe(root), CiphertextEvidence(attributes = {
            val actual = attrs(it)
            object : BasicFileAttributes by actual { override fun fileKey(): Any? = null }
        }))
    }

    @Test fun nonDirectoryStoreAndRecordsPathFailBeforeAllKeys() {
        for (relative in listOf("records", "records/records.db.original-edits", "records/records.db.attachments")) {
            val root = temporary.newFolder(); val obstacle = File(root, relative)
            obstacle.parentFile!!.mkdirs(); obstacle.writeText("synthetic obstacle")
            rejectBeforeKeys(Probe(root)); assertEquals("synthetic obstacle", obstacle.readText())
        }
    }

    @Test fun storeParentLockAndCiphertextLinksAreNeverFollowed() {
        for (relative in listOf("records", "records/records.db.original-edits", "records/records.db.attachments", "records/records.db.original-edits/.store.lock", "records/records.db.attachments/blob.aead")) {
            val root = temporary.newFolder(); val target = temporary.newFolder(); val link = File(root, relative)
            link.parentFile!!.mkdirs(); Files.createSymbolicLink(link.toPath(), target.toPath())
            rejectBeforeKeys(Probe(root)); assertTrue(Files.isSymbolicLink(link.toPath())); assertTrue(target.isDirectory)
        }
    }

    @Test fun danglingDatabaseSymlinkAndUnexpectedChildDirectoryAreUnknown() {
        val first = temporary.newFolder(); File(first, "records").mkdirs()
        Files.createSymbolicLink(File(first, "records/records.db").toPath(), File(first, "missing").toPath())
        rejectBeforeKeys(Probe(first))
        val second = temporary.newFolder(); File(directory(second, "attachments"), "unexpected-dir").mkdir()
        rejectBeforeKeys(Probe(second))
    }

    @Test fun directoryReplacementDuringEnumerationFailsBeforeAllKeysWithoutDeletingEitherTree() {
        val root = temporary.newFolder(); val edits = directory(root, "original-edits"); val held = File(root, "held-edits")
        var replaced = false
        rejectBeforeKeys(Probe(root), CiphertextEvidence(listFiles = {
            if (it == edits && !replaced) { replaced = true; check(edits.renameTo(held)); check(edits.mkdir()) }
            it.listFiles()
        }))
        assertTrue(held.isDirectory); assertTrue(edits.isDirectory)
    }

    @Test fun fileAppearingDuringScanAndEscapingListResultFailBeforeAllKeys() {
        val root = temporary.newFolder(); val dir = directory(root, "attachments")
        rejectBeforeKeys(Probe(root), CiphertextEvidence(listFiles = {
            if (it == dir) { File(dir, "new.aead").writeText("synthetic"); emptyArray() } else it.listFiles()
        }))
        val second = temporary.newFolder(); val other = directory(second, "original-edits")
        val escape = temporary.newFile().apply { writeText("synthetic") }
        rejectBeforeKeys(Probe(second), CiphertextEvidence(listFiles = { if (it == other) arrayOf(escape) else it.listFiles() }))
        assertEquals("synthetic", escape.readText())
    }

    @Test fun databaseAndEachSidecarProtectDatabaseKey() {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val root = temporary.newFolder(); val file = File(root, "records/records.db$suffix")
            file.parentFile!!.mkdirs(); file.writeText("synthetic")
            val p = Probe(root); p.run(); assertTrue(p.calls.first().second)
            assertEquals("synthetic", file.readText())
        }
    }

    @Test fun existingEnvelopeBackupOrUnfinishedEnvelopeNeverAuthorizesNewKey() {
        for (purpose in RepositoryKeyPurpose.entries) for (suffix in listOf("", ".bak", ".new")) {
            val root = temporary.newFolder(); val key = File(root, "keys/${purpose.fileName}$suffix")
            key.parentFile!!.mkdirs(); key.writeText("synthetic wrapped material")
            val p = Probe(root); p.run(); assertTrue(p.calls.single { it.first == purpose }.second)
            assertEquals("synthetic wrapped material", key.readText())
        }
    }

    @Test fun inaccessibleOrLinkedKeyEnvelopeFailsBeforeLoaderAndPreservesIt() {
        val root = temporary.newFolder(); val keys = File(root, "keys").apply { mkdirs() }
        val file = File(keys, "original-edits.key").apply { writeText("synthetic") }
        val p = Probe(root)
        assertThrows(StorageKeyUnavailable::class.java) { p.run(CiphertextEvidence(attributes = { if (it == file) throw IOException("synthetic") else attrs(it) })) }
        assertTrue(p.calls.isEmpty()); assertEquals("synthetic", file.readText())
        val other = temporary.newFolder(); File(other, "keys").mkdirs()
        Files.createSymbolicLink(File(other, "keys/database.key").toPath(), file.toPath())
        val q = Probe(other); assertThrows(StorageKeyUnavailable::class.java) { q.run() }; assertTrue(q.calls.isEmpty())
    }

    @Test fun attachmentLedgerRequiresExistingKeyEvenWhenBlobDirectoryIsEmpty() {
        val p = Probe(temporary.newFolder()); p.ledger = true; p.run()
        assertTrue(p.calls.single { it.first == RepositoryKeyPurpose.ATTACHMENTS }.second)
    }

    @Test fun failedLedgerNeverCallsStoreKeyLoadersAndWipesDatabaseKey() {
        val p = Probe(temporary.newFolder()); p.ledgerFailure = true
        assertThrows(IOException::class.java) { p.run() }
        assertEquals(listOf(RepositoryKeyPurpose.DATABASE), p.calls.map { it.first })
        assertArrayEquals(ByteArray(32), p.issuedKey)
    }

    @Test fun changedDirectoryAfterDatabaseOpenStopsStoreKeysAndWipesDatabaseKey() {
        val root = temporary.newFolder(); val dir = directory(root, "original-edits"); val held = File(root, "held")
        val p = Probe(root); p.beforeKeys = { check(dir.renameTo(held)); check(dir.mkdir()) }
        assertThrows(StorageKeyUnavailable::class.java) { p.run() }
        assertEquals(listOf(RepositoryKeyPurpose.DATABASE), p.calls.map { it.first })
        assertArrayEquals(ByteArray(32), p.issuedKey); assertTrue(held.isDirectory)
    }

    @Test fun disappearedEnvelopeStillRequiresExistingKeyWithinSameAttempt() {
        val root = temporary.newFolder(); val key = File(root, "keys/original-edits.key")
        key.parentFile!!.mkdirs(); key.writeText("synthetic")
        val held = File(root, "held-key"); val p = Probe(root); p.beforeKeys = { check(key.renameTo(held)) }
        p.run(); assertTrue(p.calls.single { it.first == RepositoryKeyPurpose.EDITS }.second)
        assertEquals("synthetic", held.readText())
    }

    @Test fun freshOpenAndReopenAllowNormalWritesWhenCreationTimeFallsBackToModificationTime() {
        val root = temporary.newFolder()
        val calls = mutableListOf<Pair<RepositoryKeyPurpose, Boolean>>()
        val evidence = CiphertextEvidence(attributes = {
            val actual = attrs(it)
            object : BasicFileAttributes by actual {
                override fun creationTime() = actual.lastModifiedTime()
            }
        })
        repeat(2) { attempt ->
            EncryptedEntryRepository.openProtected(root, evidence = evidence, loadKey = { purpose, existing ->
                calls += purpose to existing
                val envelope = File(root, "keys/${purpose.fileName}")
                if (!existing) {
                    envelope.parentFile!!.mkdirs(); envelope.writeText("synthetic envelope")
                    if (purpose == RepositoryKeyPurpose.DATABASE) Files.setLastModifiedTime(root.toPath(),
                        java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L))
                }
                ByteArray(32)
            }) { file, _, editKey, attachmentKey ->
                file.parentFile!!.mkdirs(); if (attempt == 0) file.writeText("synthetic DB boundary")
                attachmentKey(false)
                directory(root, "attachments")
                editKey()
                directory(root, "original-edits")
            }
        }
        assertEquals(6, calls.size)
        assertTrue(calls.take(3).none { it.second })
        assertTrue(calls.drop(3).all { it.second })
    }

    @Test fun directoryFirstObservedAfterDatabaseCreationCannotBeReplacedBeforeEditKey() {
        val root = temporary.newFolder()
        val calls = mutableListOf<RepositoryKeyPurpose>()
        assertThrows(StorageKeyUnavailable::class.java) {
            EncryptedEntryRepository.openProtected(root, loadKey = { purpose, _ ->
                calls += purpose
                val keys = File(root, "keys")
                if (purpose == RepositoryKeyPurpose.DATABASE) keys.mkdir()
                if (purpose == RepositoryKeyPurpose.ATTACHMENTS) {
                    check(keys.renameTo(File(root, "held-keys"))); check(keys.mkdir())
                }
                ByteArray(32)
            }) { file, _, editKey, attachmentKey ->
                file.parentFile!!.mkdirs(); file.writeText("synthetic DB boundary")
                attachmentKey(false); editKey()
            }
        }
        assertEquals(listOf(RepositoryKeyPurpose.DATABASE, RepositoryKeyPurpose.ATTACHMENTS), calls)
        assertTrue(File(root, "held-keys").isDirectory)
    }
}
