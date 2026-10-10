package com.t1doo.aiyou.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.t1doo.aiyou.domain.StorageKeyUnavailable
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound wrapping key; a lost key never causes a database reset or silent key replacement. */
internal class AndroidDatabaseKeyProvider(private val file: File, private val alias: String) {
    fun load(databaseExists: Boolean): ByteArray = synchronized(lock) {
        try {
            val atomic = AtomicFile(file)
            val present = file.exists() || File(file.path + ".bak").exists()
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (present) {
                val key = store.getKey(alias, null) as? SecretKey ?: throw StorageKeyUnavailable()
                val wrapped = atomic.openRead().use { input ->
                    val bytes = ByteArray(66)
                    var count = 0
                    while (count < bytes.size) {
                        val read = input.read(bytes, count, bytes.size - count)
                        if (read < 0) break
                        count += read
                    }
                    bytes.copyOf(count)
                }
                if (wrapped.size != 65 || !wrapped.copyOfRange(0, 5).contentEquals(magic)) throw StorageKeyUnavailable()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOfRange(5, 17)))
                cipher.updateAAD(aad)
                return@synchronized cipher.doFinal(wrapped, 17, wrapped.size - 17).also {
                    if (it.size != 32) { it.fill(0); throw StorageKeyUnavailable() }
                }
            }
            if (databaseExists) throw StorageKeyUnavailable()
            val wrappingKey = (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
                generateKey()
            }
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
                cipher.updateAAD(aad)
                check(cipher.iv.size == 12)
                val payload = magic + cipher.iv + cipher.doFinal(raw)
                check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
                val stream = atomic.startWrite()
                try { stream.write(payload); atomic.finishWrite(stream) }
                catch (failure: Exception) { atomic.failWrite(stream); throw failure }
                raw
            } catch (failure: Exception) { raw.fill(0); throw failure }
        } catch (failure: StorageKeyUnavailable) { throw failure }
        catch (_: Exception) { throw StorageKeyUnavailable() } // no raw key, body, path or platform error in UI/logs
    }

    companion object {
        private val lock = Any()
        private val magic = "AIYK1".toByteArray(Charsets.US_ASCII)
        private val aad = "AIyou database key v1".toByteArray(Charsets.US_ASCII)
    }
}
