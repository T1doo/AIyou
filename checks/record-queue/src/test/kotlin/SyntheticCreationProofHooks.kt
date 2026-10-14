package com.t1doo.aiyou.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Test-only durable metadata, alongside the existing synthetic disk attachment ledger.
 * Stores opaque observations from the actual CREATE_NEW callback; it never invents file
 * ownership or duplicates authentication/deletion logic. SQLCipher/fences are tested separately.
 */
internal class SyntheticCreationProofHooks(root: File) : ErasureFileHooks {
    private val directory = File(root, "creation-proofs").also {
        if (!it.exists()) { check(it.mkdir()); sync(root) }
        check(it.isDirectory && !Files.isSymbolicLink(it.toPath()))
    }
    override fun fenced(entryId: String) = false // Legacy queue tests are not erasure-fence tests.
    override fun reserve(entryId: String, bucket: String, name: String) {
        check(!target(entryId, bucket, name).exists())
        write(entryId, bucket, name, "RESERVED", "")
    }
    override fun created(entryId: String, bucket: String, name: String, identity: String) {
        check(read(entryId, bucket, name) == ("RESERVED" to ""))
        check(identity.isNotEmpty())
        write(entryId, bucket, name, "CREATED", identity)
    }
    override fun creation(entryId: String, bucket: String, name: String): String? =
        read(entryId, bucket, name)?.takeIf { it.first == "CREATED" }?.second
    override fun forgotten(entryId: String, bucket: String, name: String) {
        read(entryId, bucket, name) // Check the exact stored identity before touching the fixture row.
        Files.deleteIfExists(target(entryId, bucket, name).toPath()); sync(directory)
    }
    private fun target(entryId: String, bucket: String, name: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest((entryId + '\u0000' + bucket + '\u0000' + name).toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) })
    }
    private fun read(entryId: String, bucket: String, name: String): Pair<String, String>? {
        val file = target(entryId, bucket, name)
        if (!file.exists()) return null
        check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..8192)
        return DataInputStream(FileInputStream(file)).use {
            check(it.readUTF() == "SYNTHETIC_CREATION_V1")
            check(it.readUTF() == entryId && it.readUTF() == bucket && it.readUTF() == name)
            val row = it.readUTF() to it.readUTF(); check(it.read() == -1); row
        }
    }
    private fun write(entryId: String, bucket: String, name: String, state: String, identity: String) {
        val temporary = Files.createTempFile(directory.toPath(), "owned-proof-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { stream ->
                val output = DataOutputStream(stream)
                for (value in listOf("SYNTHETIC_CREATION_V1", entryId, bucket, name, state, identity)) output.writeUTF(value)
                output.flush(); stream.fd.sync()
            }
            Files.move(temporary, target(entryId, bucket, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            sync(directory)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun sync(file: File) = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { it.force(true) }
}
