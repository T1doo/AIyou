package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.SecretReference
import com.t1doo.aiyou.model.ModelConnectionDetails
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Existing uniformly random, Keystore-wrapped DB key; distinct HKDF-Expand purpose and AEAD AAD. */
internal class ConnectionSecretCipher(private val databaseKey: ByteArray) {
    fun crypt(id: String, version: Long, mutation: String, ref: SecretReference, details: ModelConnectionDetails,
        input: ByteArray, seal: Boolean): ByteArray {
        require(databaseKey.size==32)
        val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(databaseKey,"HmacSHA256"))
        val key=mac.doFinal("com.t1doo.aiyou.connection-secret.v1".toByteArray(Charsets.US_ASCII)+byteArrayOf(1))
        val aad=ByteArrayOutputStream().use { b -> DataOutputStream(b).use { out ->
            out.writeUTF("AIyou/connection-secret/1");out.writeUTF(id);out.writeLong(version);out.writeUTF(mutation);out.writeUTF(ref.value)
            out.write(MessageDigest.getInstance("SHA-256").digest(ModelConnectionPayload.encode(ModelConnectionPayload.Value(details))))
        };b.toByteArray() }
        return try {
            if(seal) PortableCipherCell.seal(key,aad,input,8192)
            else { require(input[0]==1.toByte());PortableCipherCell.open(key,aad,input,8192) }
        } finally { key.fill(0);aad.fill(0) }
    }
}
