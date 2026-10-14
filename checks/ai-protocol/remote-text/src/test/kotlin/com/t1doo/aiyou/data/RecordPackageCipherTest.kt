package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.nio.ByteBuffer
import java.util.UUID
import java.security.SecureRandom
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecordPackageCipherTest {
    @Test fun independentNodeOpenSslFixtureAuthenticatesExactUnicodeTimesTagsAndDigest() {
        val fixture = fixture(); assertEquals("v24.19.0", fixture["generatorNode"]!!.jsonPrimitive.content)
        assertEquals("3.5.7", fixture["generatorOpenSsl"]!!.jsonPrimitive.content)
        val password = fixture["password"]!!.jsonPrimitive.content.toCharArray()
        val verified = JcaRecordPackageCipher().open(unhex(fixture["envelopeHex"]!!.jsonPrimitive.content), password)
        assertTrue(password.all { it == '\u0000' })
        assertEquals(fixture["packageDigest"]!!.jsonPrimitive.content, verified.digest)
        assertEquals(fixture["recordDigest"]!!.jsonPrimitive.content, (verified as AuthenticatedRecordPackage).recordDigests.single())
        val r = verified.records.single()
        assertEquals("合成-source😀", r.sourceId); assertEquals(3, r.sourceRevision)
        assertEquals(-1L, r.capturedAtMillis); assertEquals("Asia/Shanghai", r.captureTimeZone)
        assertEquals("合成原文😀\n第二行 e\u0301", r.originalText); assertEquals(listOf("生活😀", "e\u0301"), r.tags)
        assertEquals(2L, r.sourceTagVersion)
        assertThrows(UnsupportedOperationException::class.java) { (verified.records as MutableList).clear() }
        assertFalse(verified.toString().contains(r.sourceId)); assertFalse(r.toString().contains(r.originalText))
    }
    @Test fun productionRoundtripFreshSaltNonceExactPassphraseAndReleasedEnvelope() {
        val cipher = JcaRecordPackageCipher(); val snapshot = snapshot()
        val password = " synthetic independent password! ".toCharArray()
        val first = cipher.seal(snapshot, password); assertTrue(password.all { it == '\u0000' })
        val second = cipher.seal(snapshot, PASSWORD.toCharArray())
        val a = first.copyBytes(); val b = second.copyBytes()
        assertFalse(a.copyOfRange(16, 32).contentEquals(b.copyOfRange(16, 32)))
        assertFalse(a.copyOfRange(32, 44).contentEquals(b.copyOfRange(32, 44)))
        assertEquals(cipher.open(a, PASSWORD.toCharArray()).digest, cipher.open(b, PASSWORD.toCharArray()).digest)
        assertThrows(RecordPackageInvalid::class.java) { cipher.open(a, PASSWORD.trim().toCharArray()) }
        val copy = first.copyBytes(); first.release()
        assertThrows(IllegalStateException::class.java) { first.copyBytes() }
        assertTrue(copy.any { it != 0.toByte() }); copy.fill(0); a.fill(0); b.fill(0); second.release()
    }
    @Test fun productionSealMatchesIndependentNodeKnownAnswerByteForByte() {
        val fixture = fixture()
        val fixed = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                require(bytes.size == 16 || bytes.size == 12)
                bytes.indices.forEach { bytes[it] = (if (bytes.size == 16) it else it + 16).toByte() }
            }
        }
        var calls = 0
        val id = "合成-source😀"; val text = "合成原文😀\n第二行 e\u0301"
        val snapshot = OriginalExportSnapshot("01234567-89ab-4cde-8fab-0123456789ab", 1_700_000_000_000,
            listOf(OriginalExportRecord(StoredEntry(id, 3, text, -1, "Asia/Shanghai", ProcessingPolicy.AI_NONE),
                OriginalExports.hash(text), ManualTagSnapshot(id, 2, listOf("生活😀", "e\u0301")))))
        val payload = JcaRecordPackageCipher(fixed) { calls++ }.seal(snapshot, fixturePassword())
        try { assertArrayEquals(fixtureBytes(), payload.copyBytes()); assertEquals(1, calls) }
        finally { payload.release() }
    }
    @Test fun wrongPassphraseTamperSaltNonceTagAndTruncationReleaseNoVerifiedCapability() {
        val valid = fixtureBytes(); val cipher = JcaRecordPackageCipher()
        val badPassword = " definitely wrong password ".toCharArray()
        assertThrows(RecordPackageInvalid::class.java) { cipher.open(valid, badPassword) }
        assertTrue(badPassword.all { it == '\u0000' })
        for (at in listOf(16, 32, 49, valid.lastIndex)) {
            val bad = valid.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            assertThrows(RecordPackageInvalid::class.java) { cipher.open(bad, fixturePassword()) }
        }
        assertThrows(RecordPackageInvalid::class.java) { cipher.open(valid.copyOf(valid.size - 1), fixturePassword()) }
    }
    @Test(timeout = 2_000) fun unknownOuterVersionsParametersLengthsAndOversizeFailBeforeKdf() {
        val valid = fixtureBytes(); var calls = 0; val cipher = JcaRecordPackageCipher(SecureRandom()) { calls++ }
        val cases = mutableListOf(ByteArray(47), ByteArray(RecordPackages.MAX_ENVELOPE + 1), valid + byteArrayOf(1))
        for ((at, value) in listOf(8 to 0x7fffffff, 10 to 0x7fffffff, 12 to Int.MAX_VALUE, 12 to 1, 44 to -1, 44 to Int.MAX_VALUE))
            cases += valid.copyOf().also { ByteBuffer.wrap(it).putInt(at, value) }
        cases += valid.copyOf().also { it[0] = 0 }
        cases.forEach { bytes -> val password = fixturePassword()
            assertThrows(RecordPackageInvalid::class.java) { cipher.open(bytes, password) }; assertTrue(password.all { it == '\u0000' }) }
        assertEquals("Invalid outer inputs must invoke the actual KDF zero times", 0, calls)
    }
    @Test fun independentlyAuthenticatedMalformedManifestsNeverExposePartialPreview() {
        val cipher = JcaRecordPackageCipher()
        val malformed = fixture()["malformedEnvelopes"]!!.jsonObject
        assertEquals(13, malformed.size)
        malformed.forEach { (name, hex) ->
            val password = fixturePassword()
            assertThrows(name, RecordPackageInvalid::class.java) { cipher.open(unhex(hex.jsonPrimitive.content), password) }
            assertTrue(password.all { it == '\u0000' })
        }
    }
    @Test fun invalidPasswordsAndSourceScopeFailAndClearControlledInput() {
        for (value in listOf("", "short", " ".repeat(12), "x".repeat(129), "合成恢复口令足够长度", "valid length\n")) {
            val password = value.toCharArray()
            assertThrows(RecordPackageInvalid::class.java) { JcaRecordPackageCipher().seal(snapshot(), password) }
            assertTrue(password.all { it == '\u0000' })
        }
        assertThrows(IllegalArgumentException::class.java) { OriginalExportSnapshot(UUID.randomUUID().toString(), 1,
            (1..3).map { record("source-" + it, "x".repeat(90_000)) }) }
        assertThrows(IllegalArgumentException::class.java) { RecordPackageOriginal("id", 1, 0, "UTC", "ok",
            OriginalExports.hash("ok"), 1, listOf(" padded ")) }
    }
    companion object {
        const val PASSWORD = " synthetic independent password! "
        fun fixture(): JsonObject = Json.parseToJsonElement(checkNotNull(RecordPackageCipherTest::class.java
            .getResourceAsStream("/record-package-v1/fixture.json")).bufferedReader().use { it.readText() }).jsonObject
        fun fixtureBytes() = unhex(fixture()["envelopeHex"]!!.jsonPrimitive.content)
        fun fixturePassword() = fixture()["password"]!!.jsonPrimitive.content.toCharArray()
        fun unhex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun snapshot(id: String = UUID.randomUUID().toString(), text: String = "合成原文😀\ne\u0301") =
            OriginalExportSnapshot(id, 10, listOf(record("synthetic-source", text)))
        fun record(id: String, text: String) = OriginalExportRecord(StoredEntry(id, 3, text, -1, "Asia/Shanghai", ProcessingPolicy.REMOTE_ALLOWED),
            OriginalExports.hash(text), ManualTagSnapshot(id, 2, listOf("合成😀", "e\u0301")), 7)
        fun verified(snapshot: OriginalExportSnapshot = snapshot()): VerifiedRecordPackage {
            val cipher = JcaRecordPackageCipher(); val payload = cipher.seal(snapshot, PASSWORD.toCharArray())
            val bytes = payload.copyBytes(); payload.release()
            return try { cipher.open(bytes, PASSWORD.toCharArray()) } finally { bytes.fill(0) }
        }
    }
}
