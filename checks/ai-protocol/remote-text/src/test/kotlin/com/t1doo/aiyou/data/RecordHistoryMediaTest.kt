package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real SQLite, private media owners and product AEAD. No SAF/device/visual substitute claim. */
class RecordHistoryMediaTest {
    @Test fun explicitHistoryRestoresEveryTextTimeAndDistinctImageToItsOwnRevisionWithFreshCiphertext(): Unit = runBlocking {
        val targetKey = ByteArray(32) { (it + 31).toByte() }
        assertFalse(targetKey.contentEquals(PermanentErasureFixture.KEY))
        PermanentErasureFixture().use { source -> PermanentErasureFixture(databaseKey = targetKey).use { target ->
            val id = source.entry(text = "合成后来删掉的文字")
            val intents = (1..3).map { revision ->
                if (revision > 1) revise(source, id, revision, "合成版本 $revision", if (revision == 3) 6 else 7)
                source.attachment(id, image(revision), revision).also { intent -> source.media.save(intent) { image(revision).inputStream() } }
            }
            source.tags.replace(ManualTagMutation(id, 0, listOf("合成历史标签"), uuid()))
            val r = repository(source); val preview = history(source, id, 3)
            assertEquals(listOf(1, 2, 3), preview.history!!.single().history.map { it.revision })
            assertEquals(listOf(1, 2, 3), preview.images.map { it.sourceRevision })
            val bytes = export(r, preview); assertEquals(4, java.nio.ByteBuffer.wrap(bytes, 8, 4).int)
            val targetRepository = repository(target); val pack = targetRepository.open({ bytes.inputStream() }, password())
            val newId = uuid()
            try {
                assertTrue(pack.includesHistory); assertEquals(3, pack.records.single().restoredRevision)
                val result = targetRepository.restore(pack, listOf(newId), 123)
                assertFalse(result.replayed); assertEquals(listOf(newId), result.ids)
                assertEquals(listOf(3L, "AI_NONE"), target.db.read("SELECT currentRevision,privacyPolicy FROM entries WHERE id=?", newId).single())
                assertEquals(source.db.read("SELECT revision,originalText,contentHash,createdAtMillis FROM entry_revisions WHERE entryId=? ORDER BY revision", id),
                    target.db.read("SELECT revision,originalText,contentHash,createdAtMillis FROM entry_revisions WHERE entryId=? ORDER BY revision", newId))
                assertEquals(listOf("合成历史标签"), target.tags.read(newId).names)
                for (revision in 1..3) {
                    val restored = target.media.list(newId, revision).single().intent
                    assertEquals(revision, restored.revision); assertTrue(restored.id !in intents.map { it.id })
                    assertArrayEquals(image(revision), target.media.read(restored.id))
                    val ciphertext = target.mediaRoot.resolve(restored.id + ".aead").readBytes()
                    assertFalse(ciphertext.contentEquals(image(revision)))
                    assertFalse(ciphertext.contentEquals(source.mediaRoot.resolve(intents[revision - 1].id + ".aead").readBytes()))
                    // Independent platform JCA with the exact new identity/AAD proves key replacement,
                    // without routing a wrong-key read through the live store's CORRUPT transition.
                    val independentlyOpened = decryptAttachment(ciphertext, restored, targetKey)
                    try { assertArrayEquals(image(revision), independentlyOpened) } finally { independentlyOpened.fill(0) }
                    assertThrows(AEADBadTagException::class.java) { decryptAttachment(ciphertext, restored, PermanentErasureFixture.KEY) }
                    assertEquals("READY", target.db.read("SELECT state FROM attachments WHERE id=?", restored.id).single().single())
                }
                assertEquals(3, target.packages.origins().getValue(newId).restoredRevisionCount)
                assertNotNull(target.packages.origins().getValue(newId).historyDigest)
                assertEquals(listOf(null, null), target.db.read("SELECT connection,consent FROM remote_source_access WHERE entryId=?", newId).single())
            } finally { pack.release() }
            target.reopen()
            assertEquals(listOf("合成历史标签"), target.tags.read(newId).names)
            for (revision in 1..3) {
                val coldImage = target.media.list(newId, revision).single()
                assertEquals(AttachmentState.READY, coldImage.state)
                assertArrayEquals(image(revision), target.media.read(coldImage.intent.id))
            }
            assertEquals(3L, target.db.read("SELECT currentRevision FROM entries WHERE id=?", newId).single().single())
        } }
    }

    @Test fun defaultCurrentScopeStillExcludesHistoryAndRestoresOneRevisionOneImage(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); val old = source.attachment(id, image(1)); source.media.save(old) { image(1).inputStream() }
            revise(source, id, 2, "合成当前原文", 7)
            val current = source.attachment(id, image(2), 2); source.media.save(current) { image(2).inputStream() }
            val r = repository(source); val preview = r.preview(snapshot(source, id, 2))
            assertNull(preview.history); assertEquals(listOf(current.id), preview.images.map { it.attachmentId })
            source.mediaRoot.resolve(old.id + ".aead").delete()
            assertTrue(r.isCurrent(preview))
            val bytes = export(r, preview); val t = repository(target); val pack = t.open({ bytes.inputStream() }, password())
            try {
                assertFalse(pack.includesHistory); assertTrue(pack.records.single().history.isEmpty())
                val newId = uuid(); t.restore(pack, listOf(newId), 100)
                assertEquals(listOf(listOf(1L, "合成当前原文")), target.db.read("SELECT revision,originalText FROM entry_revisions WHERE entryId=?", newId))
                assertArrayEquals(image(2), target.media.read(target.media.list(newId, 1).single().intent.id))
                assertEquals(1, target.packages.origins().getValue(newId).restoredRevisionCount)
                assertNull(target.packages.origins().getValue(newId).historyDigest)
            } finally { pack.release() }
        } }
    }

    @Test fun editOrDeletionAfterHistoricalPreviewRejectsEncryptionAndWipesPassword(): Unit = runBlocking {
        for (deleted in listOf(false, true)) PermanentErasureFixture().use { f ->
            val id = f.entry(); revise(f, id, 2, "合成末版本", 7)
            val r = repository(f); val p = history(f, id, 2)
            if (deleted) f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?", id)
            else revise(f, id, 3, "合成追加版本", 9)
            assertFalse(r.isCurrent(p)); val pass = password(); rejects { r.seal(p, pass) }; assertTrue(pass.all { it == '\u0000' })
            assertTrue(f.db.read("SELECT id FROM record_package_receipts").isEmpty())
        }
    }

    @Test fun changedMiddleTextOrTimeWithIdenticalCurrentVersionStillInvalidatesHistoricalPreview(): Unit = runBlocking {
        for (timeOnly in listOf(false, true)) PermanentErasureFixture().use { f ->
            val id = f.entry(); revise(f, id, 2, "合成中间版本", 7); revise(f, id, 3, "合成相同末版", 9)
            val r = repository(f); val p = history(f, id, 3)
            if (timeOnly) f.db.write("UPDATE entry_revisions SET createdAtMillis=8 WHERE entryId=? AND revision=2", id)
            else f.db.write("UPDATE entry_revisions SET originalText=?,contentHash=? WHERE entryId=? AND revision=2", "合成改写中间", OriginalExports.hash("合成改写中间"), id)
            assertEquals("合成相同末版", f.db.read("SELECT originalText FROM entry_revisions WHERE entryId=? AND revision=3", id).single().single())
            assertFalse(r.isCurrent(p)); rejects { r.seal(p, password()) }
        }
    }

    @Test fun incompleteCorruptInvalidTimeOrMispointedHistoryFailsAsAWhole(): Unit = runBlocking {
        for (damage in 0..3) PermanentErasureFixture().use { f ->
            val id = f.entry(); revise(f, id, 2, "合成中间", 7); revise(f, id, 3, "合成末版", 9)
            val selected = snapshot(f, id, 3)
            when (damage) {
                0 -> f.db.write("DELETE FROM entry_revisions WHERE entryId=? AND revision=2", id)
                1 -> f.db.write("UPDATE entry_revisions SET contentHash=? WHERE entryId=? AND revision=2", "0".repeat(64), id)
                2 -> f.db.write("UPDATE entry_revisions SET createdAtMillis=-1 WHERE entryId=? AND revision=2", id)
                3 -> f.db.write("UPDATE entries SET currentRevision=2 WHERE id=?", id)
            }
            rejects { repository(f).previewHistory(selected) }
            assertTrue(f.db.read("SELECT id FROM record_package_receipts").isEmpty())
        }
    }

    @Test fun missingCorruptOrUnreadyOldImagesAreNeverSkippedFromHistoricalScope(): Unit = runBlocking {
        for (damage in 0..2) PermanentErasureFixture().use { f ->
            val id = f.entry(); val old = f.attachment(id, image(1)); f.media.save(old) { image(1).inputStream() }
            revise(f, id, 2, "合成末版", 7)
            when (damage) {
                0 -> assertTrue(f.mediaRoot.resolve(old.id + ".aead").delete())
                1 -> f.mediaRoot.resolve(old.id + ".aead").apply { val b = readBytes(); b[b.lastIndex] = (b.last().toInt() xor 1).toByte(); writeBytes(b) }
                2 -> f.db.write("UPDATE attachments SET state='FAILED' WHERE id=?", old.id)
            }
            val r = repository(f); assertTrue(r.preview(snapshot(f, id, 2)).images.isEmpty())
            rejects { r.previewHistory(snapshot(f, id, 2)) }
        }
    }

    @Test fun historicalBlankTextRequiresItsOwnImageRatherThanAnyCurrentImage(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id = f.entry(text = ""); revise(f, id, 2, "合成后来的文字", 7)
            val image = f.attachment(id, image(2), 2); f.media.save(image) { image(2).inputStream() }
            rejects { history(f, id, 2) }
            val historical = f.attachment(id, image(1)); f.media.save(historical) { image(1).inputStream() }
            assertEquals("", history(f, id, 2).history!!.single().history.first().originalText)
        }
    }

    @Test fun lostCommitAcknowledgementColdRetryAndLaterEditNeverDuplicateOrResetCurrentPointer(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); revise(source, id, 2, "合成历史末版", 7)
            source.media.save(source.attachment(id, image(2), 2)) { image(2).inputStream() }
            val bytes = export(repository(source), history(source, id, 2)); val newId = uuid()
            var receipt = false; var lost = false
            target.db.afterSql = { sql ->
                if (sql.startsWith("INSERT INTO record_package_receipts") && sql.contains("'PACKAGE_RESTORE'")) receipt = true
                if (sql == "COMMIT" && receipt && !lost) { lost = true; error("Synthetic history commit ACK loss") }
            }
            val r = repository(target); val first = r.open({ bytes.inputStream() }, password())
            try { rejects { r.restore(first, listOf(newId), 100) } } finally { first.release() }
            assertTrue(lost); target.db.afterSql = null; target.reopen()
            revise(target, newId, 3, "合成恢复后追加", 200)
            val cold = repository(target); cold.recover(); val pack = cold.open({ bytes.inputStream() }, password())
            try {
                val replay = cold.restore(pack, listOf(uuid()), 999)
                assertTrue(replay.replayed); assertEquals(listOf(newId), replay.ids); assertEquals(100L, replay.importedAtMillis)
                assertEquals(3L, target.db.read("SELECT currentRevision FROM entries WHERE id=?", newId).single().single())
                assertEquals(3, target.db.read("SELECT revision FROM entry_revisions WHERE entryId=?", newId).size)
                assertEquals(1, target.media.list(newId, 2).size); assertTrue(target.media.list(newId, 1).isEmpty())
            } finally { pack.release() }
        } }
    }

    @Test fun coldPendingCleanupUsesExactHistoricalMediaRevisionAndLeavesUnknownFilesUntouched(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); revise(source, id, 2, "合成待恢复历史", 7)
            source.media.save(source.attachment(id, image(2), 2)) { image(2).inputStream() }
            val bytes = export(repository(source), history(source, id, 2)); val newId = uuid()
            target.mediaCheckpoint = { if (it == "package-image-published") error("Synthetic history file interruption") }
            val r = repository(target); val pack = r.open({ bytes.inputStream() }, password())
            try { rejects { r.restore(pack, listOf(newId), 55) } } finally { pack.release() }
            assertTrue(target.db.read("SELECT id FROM entries").isEmpty())
            val unknown = target.mediaRoot.resolve(uuid() + ".aead"); val unknownBytes = byteArrayOf(7, 3, 1); unknown.writeBytes(unknownBytes)
            target.reopen(); target.mediaCheckpoint = {}; val cold = repository(target); cold.recover(); cold.recover()
            assertEquals(listOf(unknown.name), target.mediaRoot.listFiles()!!.filter { it.isFile && !it.name.startsWith(".") }.map { it.name })
            assertArrayEquals(unknownBytes, unknown.readBytes())
            val retry = cold.open({ bytes.inputStream() }, password())
            try {
                assertEquals(listOf(newId), cold.restore(retry, listOf(uuid()), 999).ids)
                assertArrayEquals(image(2), target.media.read(target.media.list(newId, 2).single().intent.id))
            } finally { retry.release() }
        } }
    }

    @Test fun oldV2AndV3JournalsRemainColdReadableAndKeepOriginalEncodingAndMapping(): Unit = runBlocking {
        for (version in listOf(2, 3)) PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); source.media.save(source.attachment(id, image(1))) { image(1).inputStream() }
            val bytes = export(repository(source), repository(source).preview(snapshot(source, id, 1)))
            val r = repository(target); val pack = r.open({ bytes.inputStream() }, password()); val newId = uuid(); val imageId = uuid()
            val legacy = legacyJournal(version, pack, newId, imageId)
            target.db.write("INSERT INTO record_package_receipts VALUES(?,?,1,'PACKAGE_MEDIA_INTENT',?)", "PACKAGE_MEDIA:" + pack.manifest.backupId, newId, legacy)
            pack.release(); target.reopen(); val cold = repository(target); cold.recover()
            val state = target.db.read("SELECT state FROM record_package_receipts WHERE operation='PACKAGE_MEDIA_INTENT'").single().single() as String
            assertEquals(version, PackageReader(Base64.getDecoder().decode(state)).i32())
            val reopened = cold.open({ bytes.inputStream() }, password())
            try {
                assertEquals(listOf(newId), cold.restore(reopened, listOf(uuid()), 999).ids)
                assertArrayEquals(image(1), target.media.read(imageId)); assertEquals(1, target.media.list(newId, 1).size)
                val done = target.db.read("SELECT state FROM record_package_receipts WHERE operation='PACKAGE_MEDIA_INTENT'").single().single() as String
                assertEquals(version, PackageReader(Base64.getDecoder().decode(done)).i32())
            } finally { reopened.release() }
        } }
    }

    @Test fun permanentlyErasedHistoricalRestoreReplaysReceiptWithoutRevivingAnyVersionOrMedia(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); revise(source, id, 2, "合成历史末版", 7)
            source.media.save(source.attachment(id, image(2), 2)) { image(2).inputStream() }
            val bytes = export(repository(source), history(source, id, 2)); val newId = uuid(); val r = repository(target)
            val first = r.open({ bytes.inputStream() }, password())
            try { r.restore(first, listOf(newId), 99) } finally { first.release() }
            assertEquals(PermanentErasurePhase.DONE, target.erase.erase(target.intent(newId)).phase)
            target.reopen(); val cold = repository(target); cold.recover(); val again = cold.open({ bytes.inputStream() }, password())
            try {
                val result = cold.restore(again, listOf(uuid()), 1000)
                assertTrue(result.replayed); assertEquals(1, result.deletedCount)
                assertTrue(target.db.read("SELECT id FROM entries").isEmpty()); assertTrue(target.db.read("SELECT entryId FROM entry_revisions").isEmpty())
                assertTrue(target.db.read("SELECT id FROM attachments").isEmpty())
                assertFalse(target.mediaRoot.listFiles()!!.any { it.name.endsWith(".aead") || it.name.endsWith(".tmp") })
            } finally { again.release() }
        } }
    }

    @Test fun historicalReplayWithMissingOldImageOrChangedRevisionRefusesAndNeverRecreatesBytes(): Unit = runBlocking {
        for (missing in listOf(true, false)) PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); source.media.save(source.attachment(id, image(1))) { image(1).inputStream() }
            revise(source, id, 2, "合成当前原文", 7); source.media.save(source.attachment(id, image(2), 2)) { image(2).inputStream() }
            val bytes = export(repository(source), history(source, id, 2)); val r = repository(target); val newId = uuid()
            val pack = r.open({ bytes.inputStream() }, password())
            try { r.restore(pack, listOf(newId), 100) } finally { pack.release() }
            val oldImage = target.media.list(newId, 1).single().intent
            val path = target.mediaRoot.resolve(oldImage.id + ".aead")
            if (missing) assertTrue(path.delete())
            else target.db.injectSyntheticAttachmentCorruption("UPDATE attachments SET revision=2 WHERE id=?", oldImage.id)
            val before = target.db.read("SELECT id,entryId,revision,mediaType,sha256,byteSize,state FROM attachments ORDER BY id")
            assertTrue(before.all { it.last() == "READY" })
            target.reopen(); val cold = repository(target); val retry = cold.open({ bytes.inputStream() }, password())
            try { rejects { cold.restore(retry, listOf(uuid()), 999) } } finally { retry.release() }
            val expected = if (missing) before.map { row ->
                // Existing authenticated-read failure marks only the missing original CORRUPT.
                // Every identity/hash/size and every other row must remain byte-for-byte unchanged.
                if (row.first() == oldImage.id) row.dropLast(1) + "CORRUPT" else row
            } else before
            assertEquals(expected, target.db.read("SELECT id,entryId,revision,mediaType,sha256,byteSize,state FROM attachments ORDER BY id"))
            assertEquals(1, target.db.read("SELECT id FROM entries").size)
            if (missing) assertFalse(path.exists())
            else assertTrue(path.exists())
            assertEquals(2L, target.db.read("SELECT currentRevision FROM entries WHERE id=?", newId).single().single())
        } }
    }

    @Test fun historicalPerRecordTotalRevisionAndUtf8ByteLimitsRejectWholeInventoryWhileCurrentScopeRemainsAvailable(): Unit = runBlocking {
        for (bound in 0..2) PermanentErasureFixture().use { f ->
            val count = if (bound == 1) 3 else 1
            val revisionCount = when (bound) { 0 -> 129; 1 -> 87; else -> 8 }
            val text = if (bound == 2) "史".repeat(100_000) else "合成有界历史"
            val ids = List(count) { f.entry(text = text) }
            val hash = OriginalExports.hash(text)
            f.db.transaction { sql ->
                ids.forEach { id ->
                    for (revision in 2..revisionCount) sql.execute("INSERT INTO entry_revisions VALUES(?,?,?,?,?)", listOf(id, revision, text, hash, revision.toLong()))
                    sql.execute("UPDATE entries SET currentRevision=? WHERE id=?", listOf(revisionCount, id))
                }
            }
            val selected = SqlOriginalExportRepository(f.gateway, f.tags).capture(ids.map { SourceVersion(it, revisionCount.toLong()) }, uuid(), 50)
            val r = repository(f); val current = r.preview(selected)
            assertNull(current.history); assertEquals(count, current.snapshot.records.size)
            assertTrue(current.images.isEmpty()); assertTrue(r.isCurrent(current))
            val before = f.db.read("SELECT entryId,revision,contentHash,createdAtMillis FROM entry_revisions ORDER BY entryId,revision")
            rejects { r.previewHistory(selected) }
            assertEquals(count * revisionCount, before.size)
            assertEquals(before, f.db.read("SELECT entryId,revision,contentHash,createdAtMillis FROM entry_revisions ORDER BY entryId,revision"))
            assertTrue(f.db.read("SELECT id FROM record_package_receipts").isEmpty())
            assertTrue(r.isCurrent(current))
        }
    }

    private fun legacyJournal(version: Int, pack: VerifiedRecordPackage, newId: String, imageId: String): String = PackageWriter(32768).use { w ->
        w.i32(version); w.string(pack.manifest.backupId, 36); w.raw(packageUnhex(pack.digest)); w.i64(55)
        w.string("PENDING", 16); w.strings(listOf(newId), 20, 36); w.i32(1)
        val image = pack.images.single(); w.string(imageId, 36); w.string(newId, 36); w.string(image.mediaType, 32); w.i64(image.byteSize); w.raw(packageUnhex(image.sha256))
        Base64.getEncoder().encodeToString(w.bytes())
    }
    private fun decryptAttachment(ciphertext: ByteArray, intent: AttachmentIntent, key: ByteArray): ByteArray {
        assertArrayEquals("AIYA1".toByteArray(Charsets.US_ASCII), ciphertext.copyOfRange(0, 5))
        val aad = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeUTF("AIyou attachment v1"); out.writeUTF(intent.id); out.writeUTF(intent.entryId); out.writeInt(intent.revision)
                out.writeUTF(intent.mediaType); out.writeUTF(intent.sha256); out.writeLong(intent.byteSize)
            }
            bytes.toByteArray()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ciphertext.copyOfRange(5, 17)))
        try { cipher.updateAAD(aad); return cipher.doFinal(ciphertext, 17, ciphertext.size - 17) } finally { aad.fill(0) }
    }
    companion object {
        internal fun repository(f: PermanentErasureFixture) = SqlRecordMediaPackageRepository(f.gateway, SqlOriginalExportRepository(f.gateway, f.tags), f.media, f.packages)
        internal fun snapshot(f: PermanentErasureFixture, id: String, revision: Int) = SqlOriginalExportRepository(f.gateway, f.tags).capture(listOf(SourceVersion(id, revision.toLong())), uuid(), 50)
        internal suspend fun history(f: PermanentErasureFixture, id: String, revision: Int) = repository(f).previewHistory(snapshot(f, id, revision))
        internal fun revise(f: PermanentErasureFixture, id: String, revision: Int, text: String, time: Long) = f.db.transaction { sql ->
            sql.execute("INSERT INTO entry_revisions VALUES(?,?,?,?,?)", listOf(id, revision, text, OriginalExports.hash(text), time))
            sql.execute("UPDATE entries SET currentRevision=? WHERE id=?", listOf(revision, id)); Unit
        }
        internal suspend fun export(r: RecordMediaPackageRepository, preview: RecordMediaPreview): ByteArray {
            val prepared = r.seal(preview, password()); val out = ByteArrayOutputStream()
            try { prepared.writeTo(out) { true }; return out.toByteArray() } finally { prepared.release() }
        }
        internal suspend fun rejects(block: suspend () -> Any?) { var failed = false; try { block() } catch (_: Exception) { failed = true }; assertTrue("Expected whole batch rejection", failed) }
        internal fun uuid() = UUID.randomUUID().toString()
        internal fun password() = "synthetic history recovery 42".toCharArray()
        private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
        internal fun image(revision: Int) = png + byteArrayOf(revision.toByte())
    }
}
