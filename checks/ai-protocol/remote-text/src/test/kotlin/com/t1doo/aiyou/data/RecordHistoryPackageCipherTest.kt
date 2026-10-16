package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real JCA frames and independent metadata writer; no device or SAF delivery claim. */
class RecordHistoryPackageCipherTest {
    @Test fun fullHistoryRoundtripAuthenticatesEveryVersionAndEveryVersionImage(): Unit = runBlocking {
        val record = original("合成-history", listOf("", "后来保留的原文😀"))
        val images = listOf(image(record.sourceId, 1), image(record.sourceId, 2))
        val preview = preview(listOf(record), images)
        val cipher = RecordMediaPackageCipher()
        val password = password()
        val prepared = cipher.seal(preview, password, { png.copyOf() }, { true })
        assertTrue(password.all { it == '\u0000' })
        val out = ByteArrayOutputStream(); prepared.writeTo(out) { true }; val bytes = out.toByteArray()
        assertEquals("AIYOUPK4", String(bytes.copyOfRange(0, 8), Charsets.US_ASCII))
        val readPassword = password()
        val pack = cipher.open({ bytes.inputStream() }, readPassword) as AuthenticatedMediaPackage
        assertTrue(readPassword.all { it == '\u0000' })
        assertTrue(pack.includesHistory); assertEquals(2, pack.records.single().restoredRevision)
        assertEquals(record.history, pack.records.single().history)
        assertEquals(preview.snapshot.id, pack.manifest.backupId)
        assertEquals(record.tags, pack.records.single().tags)
        assertEquals(images, pack.images)
        val read = mutableListOf<Int>()
        cipher.consume(pack) { index, actual -> assertArrayEquals(png, actual); read += index }
        assertEquals(listOf(0, 1), read)
        pack.release()
        assertThrows(IllegalStateException::class.java) { runBlocking { cipher.consume(pack) { _, _ -> fail("Released") } } }
    }

    @Test fun laterDeletionAndAnIdenticalFinalVersionStillBindTheIntermediateText() {
        val a = original("same-source", listOf("首版", "后来已删除的秘密", "最终相同"))
        val b = original("same-source", listOf("首版", "不同中间版本", "最终相同"))
        val backup = uuid()
        val pa = RecordHistoryPackageCodec.parse(RecordHistoryPackageCodec.encode(preview(listOf(a), backupId = backup)))
        val pb = RecordHistoryPackageCodec.parse(RecordHistoryPackageCodec.encode(preview(listOf(b), backupId = backup)))
        assertEquals(pa.records.single().originalText, pb.records.single().originalText)
        assertNotEquals(pa.digest, pb.digest)
        assertNotEquals(pa.recordDigests.single(), pb.recordDigests.single())
        assertNotEquals(recordHistoryDigest(a.history), recordHistoryDigest(b.history))
        assertFalse(a.toString().contains("秘密")); assertFalse(a.history[1].toString().contains("秘密"))
    }

    @Test fun clockRollbackKeepsRevisionOrderAndExactOriginalTimes() {
        val history = listOf(revision(1, "先保存", 900), revision(2, "时钟回拨后", 100), revision(3, "再修改", 700))
        val record = original("clock-history", history = history)
        val pack = RecordHistoryPackageCodec.parse(RecordHistoryPackageCodec.encode(preview(listOf(record))))
        assertEquals(listOf(900L, 100L, 700L), pack.records.single().history.map { it.createdAtMillis })
        assertEquals(listOf(1, 2, 3), pack.records.single().history.map { it.revision })
    }

    @Test fun defaultCurrentScopeKeepsIndependentV2KnownAnswerBytesAndRestoresOneRevision(): Unit = runBlocking {
        val fixture = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/record-package-v2/fixture.json"))
            .bufferedReader().use { it.readText() }).jsonObject
        val bytes = RecordPackageCipherTest.unhex(fixture["envelopeHex"]!!.jsonPrimitive.content)
        val pass = fixture["password"]!!.jsonPrimitive.content
        val cipher = RecordMediaPackageCipher(); val pack = cipher.open({ bytes.inputStream() }, pass.toCharArray())
        try {
            assertFalse(pack.includesHistory); assertTrue(pack.records.single().history.isEmpty())
            assertEquals(1, pack.records.single().restoredRevision)
            val r = pack.records.single()
            val snapshot = OriginalExportSnapshot(pack.manifest.backupId, pack.manifest.createdAtMillis,
                listOf(OriginalExportRecord(StoredEntry(r.sourceId, r.sourceRevision, r.originalText, r.capturedAtMillis,
                    r.captureTimeZone, ProcessingPolicy.AI_NONE), r.contentHash, ManualTagSnapshot(r.sourceId, r.sourceTagVersion, r.tags))))
            val prepared = RecordMediaPackageCipher(fixedRandom()).seal(RecordMediaPreview(snapshot, pack.images), pass.toCharArray(),
                { RecordPackageCipherTest.unhex(fixture["imageHex"]!!.jsonPrimitive.content) }, { true })
            val out = ByteArrayOutputStream(); prepared.writeTo(out) { true }
            assertArrayEquals(bytes, out.toByteArray())
        } finally { pack.release() }
    }

    @Test fun currentOnlyAndFullHistoryRangeCannotBeConfusedByModels() {
        val record = original("snapshot-source", listOf("原来", "现在"))
        val valid = preview(listOf(record))
        assertThrows(IllegalArgumentException::class.java) {
            RecordMediaPreview(valid.snapshot, emptyList(), history = listOf(original("other-source", listOf("原来", "现在"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecordMediaPreview(valid.snapshot, emptyList(), history = listOf(original("snapshot-source", listOf("原来", "不同"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecordMediaPreview(valid.snapshot, emptyList(), history = listOf(RecordPackageOriginal(record.sourceId, record.sourceRevision,
                record.capturedAtMillis, record.captureTimeZone, record.originalText, record.contentHash, 0, emptyList())))
        }
        val empty = OriginalExportSnapshot(uuid(), 1, emptyList(), true)
        assertThrows(IllegalArgumentException::class.java) { RecordMediaPreview(empty, emptyList(), history = emptyList()) }
    }

    @Test fun callerListsCannotMutateFrozenHistoryAndImages() {
        val versions = mutableListOf(revision(1, "保留一", 1), revision(2, "保留二", 2))
        val r = original("frozen-source", history = versions)
        versions.clear(); assertEquals(2, r.history.size)
        val originals = mutableListOf(r); val descriptors = mutableListOf(image(r.sourceId, 1))
        val p = preview(originals, descriptors); originals.clear(); descriptors.clear()
        assertEquals(1, p.history!!.size); assertEquals(1, p.images.size)
        assertThrows(UnsupportedOperationException::class.java) { (r.history as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (p.history as MutableList).clear() }
    }

    @Test fun perRecordAndTotalVersionLimitsRefuseAllWithoutTruncation() {
        assertThrows(IllegalArgumentException::class.java) { original("too-many", List(129) { "v$it" }) }
        val records = listOf(original("first", List(128) { "a" }), original("second", List(128) { "b" }), original("third", listOf("c")))
        assertThrows(IllegalArgumentException::class.java) { preview(records) }
        assertEquals(listOf(128, 128, 1), records.map { it.history.size })
        val exact = records.take(2)
        val decoded = RecordHistoryPackageCodec.parse(RecordHistoryPackageCodec.encode(preview(exact)))
        assertEquals(256, decoded.records.sumOf { it.history.size })
    }

    @Test fun strictUtf8CumulativeByteLimitIsNotACharacterOrPerRecordLimit() {
        val text = "合".repeat(90_000)
        val records = listOf(original("first-wide", List(4) { text }), original("second-wide", List(4) { text }))
        assertTrue(records.all { RecordPackages.historyTextBytes(it.history) <= RecordPackages.MAX_PLAINTEXT })
        assertThrows(IllegalArgumentException::class.java) { preview(records) }
        assertEquals(4, records[0].history.size); assertEquals(text, records[1].history.last().originalText)
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { revision(1, "\uD800", 1) }
    }

    @Test fun independentlyAuthenticatedMissingDuplicateAndReorderedChainsReleaseNoPreview(): Unit = runBlocking {
        val a = WireRevision(1, "首版"); val b = WireRevision(2, "末版")
        val cases = listOf(WireRecord(revisions = listOf(b), current = 2), WireRecord(revisions = listOf(a, a), current = 2),
            WireRecord(revisions = listOf(b, a), current = 2), WireRecord(revisions = listOf(a, b), current = 1),
            WireRecord(revisions = listOf(a), current = 2), WireRecord(revisions = emptyList(), current = 1))
        cases.forEach { malformed(wire(listOf(it))) }
    }

    @Test fun independentlyAuthenticatedBadHashesTimesUtf8AndCurrentPointerReleaseNoPreview(): Unit = runBlocking {
        val cases = listOf(WireRecord(currentHash = "a".repeat(64)), WireRecord(capturedAt = -1),
            WireRecord(revisions = listOf(WireRevision(1, "合法", createdAt = -1))),
            WireRecord(revisions = listOf(WireRevision(1, "合法", hash = "b".repeat(64)))),
            WireRecord(revisions = listOf(WireRevision(1, "合法", rawText = byteArrayOf(0xc3.toByte(), 0x28)))))
        cases.forEach { malformed(wire(listOf(it))) }
    }

    @Test fun independentlyAuthenticatedMetadataCountsScopesAndEofAreStrict(): Unit = runBlocking {
        malformed(wire(emptyList()))
        malformed(wire(listOf(WireRecord(), WireRecord())))
        malformed(wire(listOf(WireRecord()), scopes = listOf("currentOriginals", "manualTags", "readyOriginalImages")))
        malformed(wire(listOf(WireRecord()), exclusions = listOf("aiDerived")))
        malformed(wire(listOf(WireRecord()), descriptorPath = "../original.bin"))
        malformed(wire(listOf(WireRecord()), descriptorHash = ByteArray(32)))
        malformed(wire(listOf(WireRecord())) + byteArrayOf(0))
        malformed(wire(listOf(WireRecord())).dropLast(1).toByteArray())
        val tooMany = listOf(WireRecord("a", revisions = List(128) { WireRevision(it + 1, "a") }, current = 128),
            WireRecord("b", revisions = List(128) { WireRevision(it + 1, "b") }, current = 128), WireRecord("c"))
        malformed(wire(tooMany))
    }

    @Test fun authenticatedUtf8CumulativeLimitRejectsEvenWithinTheMetadataEnvelope(): Unit = runBlocking {
        val text = "合".repeat(87_500)
        val records = listOf("first-wide", "second-wide").map { id ->
            WireRecord(id, revisions = List(4) { WireRevision(it + 1, text) }, current = 4)
        }
        val raw = wire(records)
        assertTrue(raw.size <= RecordMediaPackages.MAX_METADATA)
        assertTrue(8L * text.toByteArray(Charsets.UTF_8).size > RecordPackages.MAX_PLAINTEXT)
        malformed(raw)
    }

    @Test fun independentlyAuthenticatedMissingOrForeignHistoricalMediaRefusesAll(): Unit = runBlocking {
        val blankOld = WireRecord(revisions = listOf(WireRevision(1, ""), WireRevision(2, "现在")), current = 2)
        malformed(wire(listOf(blankOld)))
        malformed(wire(listOf(WireRecord()), images = listOf(image("another-source", 1))))
        malformed(wire(listOf(WireRecord()), images = listOf(image("wire-source", 2))))
        val i = image("wire-source", 1)
        malformed(wire(listOf(WireRecord()), images = listOf(i, i)))
    }

    @Test fun fullHistoryDoesNotUpgradeAnOutdatedDraftToCurrentSource(): Unit = runBlocking {
        val r = original("draft-source", listOf("旧版原文", "当前原文"))
        val stale = TaskSourceBinding(SourceVersion(r.sourceId, 1), r.history.first().contentHash, 0, 0, 2)
        val draft = TaskProposal("stale-draft", 1, stale, "合成候选", ManualTaskDateInput(), 1, "UTC", 1)
        val activities = RecordPackageActivities(emptyList(), listOf(RecordPackageDraft(draft, 0)), listOf(0))
        assertThrows(IllegalArgumentException::class.java) {
            RecordHistoryPackageCodec.encode(preview(listOf(r), activities = activities))
        }
        val rawActivities = RecordPackageActivityCodec.encode(activities)
        try {
            malformed(wire(listOf(WireRecord(r.sourceId,
                revisions = r.history.map { WireRevision(it.revision, it.originalText, it.contentHash, it.createdAtMillis) }, current = 2)),
                activityBytes = rawActivities))
        } finally { rawActivities.fill(0) }
    }

    @Test fun optionalActivitiesAuthenticateCurrentSourceAndKeepMissingSourceUnavailable(): Unit = runBlocking {
        val r = original("activity-source", listOf("过去", "当前来源"))
        val binding = TaskSourceBinding(SourceVersion(r.sourceId, 2), r.contentHash, 3, 0, 2)
        val task = ManualTaskSnapshot("formal-task", 1, "正式任务", ManualTaskStatus.OPEN, 1, "UTC", 1)
        val draft = TaskProposal("saved-draft", 1, binding, "待确认草稿", ManualTaskDateInput(), 1, "UTC", 1)
        val activities = RecordPackageActivities(listOf(RecordPackageTask(task)), listOf(RecordPackageDraft(draft, 0)), listOf(3))
        val bytes = exported(preview(listOf(r), activities = activities))
        val pack = RecordMediaPackageCipher().open({ bytes.inputStream() }, password())
        try {
            assertTrue(pack.includesHistory); assertEquals("正式任务", pack.activities!!.tasks.single().task.title)
            assertEquals(-1, pack.activities!!.tasks.single().sourceRecord)
            assertEquals(2L, pack.activities!!.drafts.single().proposal.binding.source.revision)
            assertNull(pack.activities!!.tasks.single().task.reminder)
        } finally { pack.release() }
    }

    @Test fun wrongPasswordTruncationTamperAndTrailingFramesNeverReleasePreview(): Unit = runBlocking {
        val bytes = exported(preview(listOf(original("secured-history", listOf("原来", "现在"))), listOf(image("secured-history", 1))))
        val cipher = RecordMediaPackageCipher()
        val wrong = "synthetic wrong password".toCharArray()
        assertThrows(RecordPackageInvalid::class.java) { runBlocking { cipher.open({ bytes.inputStream() }, wrong) } }
        assertTrue(wrong.all { it == '\u0000' })
        val cases = listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0),
            bytes.copyOf().also { it[it.lastIndex - 25] = (it[it.lastIndex - 25].toInt() xor 1).toByte() })
        cases.forEach { bad -> assertThrows(RecordPackageInvalid::class.java) { runBlocking { cipher.open({ bad.inputStream() }, password()) } } }
    }

    @Test fun invalidOuterParametersAndMetadataLengthsFailBeforeExpensiveKdf(): Unit = runBlocking {
        val bytes = exported(preview(listOf(original("bounded-history", listOf("只有原文")))))
        var kdf = 0; val cipher = RecordMediaPackageCipher(SecureRandom()) { kdf++ }
        val cases = listOf(8 to 5, 12 to 1, 40 to Int.MAX_VALUE, 40 to 15)
        cases.forEach { (offset, value) ->
            val bad = bytes.copyOf().also { ByteBuffer.wrap(it, offset, 4).putInt(value) }
            val pass = password()
            assertThrows(RecordPackageInvalid::class.java) { runBlocking { cipher.open({ bad.inputStream() }, pass) } }
            assertTrue(pass.all { it == '\u0000' })
        }
        assertEquals(0, kdf)
    }

    @Test fun changedPreviewAndSelectedDocumentSubstitutionCannotPublishValidHistory(): Unit = runBlocking {
        val p = preview(listOf(original("frozen-package", listOf("原来", "现在"))))
        var current = true; val cipher = RecordMediaPackageCipher()
        val prepared = cipher.seal(p, password(), { fail("No image requested"); byteArrayOf() }, { current })
        current = false; val out = ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) { runBlocking { prepared.writeTo(out) { true } } }
        assertEquals(0, out.size())
        val valid = exported(p); var selected = valid
        val pack = cipher.open({ selected.inputStream() }, password()) as AuthenticatedMediaPackage
        try {
            selected = valid.copyOf(valid.size - 1)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { cipher.consume(pack) { _, _ -> fail("No frames") } } }
        } finally { pack.release() }
    }

    private suspend fun malformed(metadata: ByteArray) {
        val bytes = authenticatedMetadata(metadata)
        val pass = password()
        assertThrows(RecordPackageInvalid::class.java) { runBlocking { RecordMediaPackageCipher().open({ bytes.inputStream() }, pass) } }
        assertTrue(pass.all { it == '\u0000' })
    }

    private suspend fun exported(preview: RecordMediaPreview): ByteArray {
        val p = RecordMediaPackageCipher().seal(preview, password(), { png.copyOf() }, { true })
        return ByteArrayOutputStream().also { p.writeTo(it) { true } }.toByteArray()
    }

    private fun original(id: String, texts: List<String> = emptyList(),
        history: List<RecordPackageRevision> = texts.mapIndexed { index, text -> revision(index + 1, text, index + 1L) }): RecordPackageOriginal {
        val current = history.last()
        return RecordPackageOriginal(id, current.revision, 0, "Asia/Shanghai", current.originalText, current.contentHash,
            1, listOf("人工标签"), true, history)
    }

    private fun preview(records: List<RecordPackageOriginal>, images: List<RecordPackageImage> = emptyList(),
        backupId: String = uuid(), activities: RecordPackageActivities? = null): RecordMediaPreview {
        val snapshot = OriginalExportSnapshot(backupId, 100, records.map { record ->
            OriginalExportRecord(StoredEntry(record.sourceId, record.sourceRevision, record.originalText, record.capturedAtMillis,
                record.captureTimeZone, ProcessingPolicy.LOCAL_ONLY), record.contentHash,
                ManualTagSnapshot(record.sourceId, record.sourceTagVersion, record.tags), imageCount = images.count {
                    it.sourceId == record.sourceId && it.sourceRevision == record.sourceRevision })
        })
        return RecordMediaPreview(snapshot, images, activities, records)
    }

    private data class WireRevision(val revision: Int, val text: String, val hash: String = OriginalExports.hash(text),
        val createdAt: Long = 1, val rawText: ByteArray? = null)
    private data class WireRecord(val id: String = "wire-source", val revisions: List<WireRevision> = listOf(WireRevision(1, "合成原文")),
        val current: Int = 1, val currentHash: String = revisions.lastOrNull()?.hash ?: "a".repeat(64), val capturedAt: Long = 0)

    /** Deliberately independent field writer permits authenticated structurally invalid metadata. */
    private fun wire(records: List<WireRecord>, images: List<RecordPackageImage> = emptyList(),
        scopes: List<String>? = null, exclusions: List<String>? = null, descriptorPath: String? = null,
        descriptorHash: ByteArray? = null, activityBytes: ByteArray? = null): ByteArray {
        val blocks = records.map { record -> output { out ->
            out.string(record.id); out.writeInt(record.current); out.writeLong(record.capturedAt); out.string("UTC")
            out.write(RecordPackageCipherTest.unhex(record.currentHash)); out.writeLong(0); out.writeInt(0)
            out.writeInt(record.revisions.size)
            record.revisions.forEach { revision ->
                out.writeInt(revision.revision); val text = revision.rawText ?: revision.text.toByteArray(Charsets.UTF_8)
                out.writeInt(text.size); out.write(text); out.write(RecordPackageCipherTest.unhex(revision.hash)); out.writeLong(revision.createdAt)
            }
        } }
        return output { out ->
            out.string("AIyou-history-v4"); out.writeInt(4); out.writeInt(1); out.writeInt(if (activityBytes == null) 0 else 1)
            out.strings(scopes ?: (listOf("fullOriginalHistory", "manualTags", "readyOriginalImages") +
                if (activityBytes == null) emptyList() else listOf("manualTasks", "taskProposals")))
            out.strings(exclusions ?: if (activityBytes == null) listOf("tasks", "drafts", "aiDerived", "serviceKeys", "deviceKeys", "reminders", "consents")
                else listOf("captureDrafts", "editDrafts", "aiDerived", "serviceKeys", "deviceKeys", "reminders", "consents"))
            out.string("01234567-89ab-4cde-8fab-0123456789ab"); out.writeLong(1)
            out.string("PBKDF2-HMAC-SHA256"); out.string("AES-256-GCM")
            listOf(RecordPackages.ITERATIONS, 256, 16, 12, 128).forEach(out::writeInt)
            out.writeInt(blocks.size)
            blocks.forEachIndexed { index, block ->
                out.string(descriptorPath ?: "records/" + index.toString().padStart(2, '0') + ".bin")
                out.writeInt(block.size); out.write(descriptorHash ?: hash(block))
            }
            blocks.forEach { block -> out.writeInt(block.size); out.write(block) }
            out.writeInt(images.size)
            images.forEach { image ->
                out.string(image.sourceId); out.writeInt(image.sourceRevision); out.string(image.attachmentId); out.string(image.mediaType)
                out.writeLong(image.byteSize); out.write(RecordPackageCipherTest.unhex(image.sha256))
            }
            activityBytes?.let { out.writeInt(it.size); out.write(it) }
        }
    }

    /** Separate JCA calls authenticate malformed plaintext instead of relying on production encoding. */
    private fun authenticatedMetadata(metadata: ByteArray): ByteArray {
        val salt = ByteArray(16) { it.toByte() }; val prefix = ByteArray(8) { (it + 16).toByte() }
        val header = ByteBuffer.allocate(40).put("AIYOUPK4".toByteArray(Charsets.US_ASCII)).putInt(4)
            .putInt(RecordPackages.ITERATIONS).put(salt).put(prefix).array()
        val spec = PBEKeySpec(password(), salt, RecordPackages.ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        try {
            val binding = hash(metadata)
            return output { out ->
                out.write(header)
                fun frame(index: Int, aadBinding: ByteArray, bytes: ByteArray) {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128,
                        prefix + ByteBuffer.allocate(4).putInt(index).array()))
                    cipher.updateAAD(header + aadBinding + ByteBuffer.allocate(4).putInt(index).array())
                    val encrypted = cipher.doFinal(bytes)
                    out.writeInt(encrypted.size); out.write(encrypted); encrypted.fill(0)
                }
                frame(0, ByteArray(32), metadata); frame(1, binding, binding)
            }
        } finally { key.fill(0) }
    }

    private fun DataOutputStream.string(text: String) { val bytes = text.toByteArray(Charsets.UTF_8); writeInt(bytes.size); write(bytes) }
    private fun DataOutputStream.strings(strings: List<String>) { writeInt(strings.size); strings.forEach { string(it) } }
    private fun output(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use(write)
    }.toByteArray()

    companion object {
        private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
        private fun uuid() = UUID.randomUUID().toString()
        private fun password() = "synthetic history package password".toCharArray()
        private fun revision(number: Int, text: String, at: Long) = RecordPackageRevision(number, text, OriginalExports.hash(text), at)
        private fun image(id: String, revision: Int) = RecordPackageImage(id, revision, uuid(), "image/png", png.size.toLong(), packageHex(hash(png)))
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun fixedRandom() = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) { bytes.indices.forEach { bytes[it] = (if (bytes.size == 16) it else it + 16).toByte() } }
        }
    }
}
