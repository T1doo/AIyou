package com.t1doo.aiyou.data

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Existing cell AEAD framing with its existing purpose key/AAD. No device key enters a backup.
 * The version + plaintext is only ever held in bounded memory inside the outer authenticated backup. */
internal object PortableCipherCell {
    fun open(key: ByteArray, aad: ByteArray, bytes: ByteArray, max: Int): ByteArray {
        require(bytes.size in 29..max)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key,"AES"), GCMParameterSpec(128,bytes.copyOfRange(1,13)))
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(bytes,13,bytes.size-13)
        return try { byteArrayOf(bytes[0]) + plain } finally { plain.fill(0) }
    }
    fun seal(key: ByteArray, aad: ByteArray, plain: ByteArray, max: Int): ByteArray {
        require(plain.size in 1..max-28)
        val nonce=ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));cipher.updateAAD(aad)
        return byteArrayOf(plain[0])+nonce+cipher.doFinal(plain,1,plain.size-1)
    }
}
