package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

/** Actual platform JCA + independent SQLite owners + encrypted files. No model/SAF/device substitute claim. */
class RecordMediaPackageTest {
    @Test fun independentNodeOpenSslV2KnownAnswerMatchesProductionInBothDirections(): Unit = runBlocking {
        val fixture = fixture(); assertEquals("v24.19.0",fixture["generatorNode"]!!.jsonPrimitive.content)
        assertEquals("3.5.7",fixture["generatorOpenSsl"]!!.jsonPrimitive.content)
        val bytes = RecordPackageCipherTest.unhex(fixture["envelopeHex"]!!.jsonPrimitive.content)
        val cipher = RecordMediaPackageCipher(); val pack = cipher.open({ bytes.inputStream() },fixture["password"]!!.jsonPrimitive.content.toCharArray())
        try {
            assertEquals(fixture["packageDigest"]!!.jsonPrimitive.content,pack.digest)
            assertEquals("合成原文😀\n第二行 e\u0301",pack.records.single().originalText)
            assertEquals(fixture["imageSha256"]!!.jsonPrimitive.content,pack.images.single().sha256)
            val fixed = object : java.security.SecureRandom() {
                override fun nextBytes(bytes: ByteArray) { bytes.indices.forEach { bytes[it]=(if (bytes.size==16) it else it+16).toByte() } }
            }
            val record = pack.records.single()
            val snapshot = OriginalExportSnapshot(pack.manifest.backupId,pack.manifest.createdAtMillis,listOf(OriginalExportRecord(
                StoredEntry(record.sourceId,record.sourceRevision,record.originalText,record.capturedAtMillis,record.captureTimeZone,ProcessingPolicy.AI_NONE),
                record.contentHash,ManualTagSnapshot(record.sourceId,record.sourceTagVersion,record.tags))))
            val prepared = RecordMediaPackageCipher(fixed).seal(RecordMediaPreview(snapshot,pack.images),fixture["password"]!!.jsonPrimitive.content.toCharArray(),
                { RecordPackageCipherTest.unhex(fixture["imageHex"]!!.jsonPrimitive.content) },{ true })
            val out = ByteArrayOutputStream()
            try { prepared.writeTo(out) { true }; assertArrayEquals(bytes,out.toByteArray()) } finally { prepared.release() }
        } finally { pack.release() }
    }
    @Test fun independentlyAuthenticatedInvalidV2ManifestsAndOuterBoundsReleaseNoPreview(): Unit = runBlocking {
        val fixture = fixture(); val cipher = RecordMediaPackageCipher()
        fixture["malformedEnvelopes"]!!.jsonObject.forEach { (_,hex) ->
            val bytes = RecordPackageCipherTest.unhex(hex.jsonPrimitive.content)
            val password = fixture["password"]!!.jsonPrimitive.content.toCharArray()
            fails { cipher.open({ bytes.inputStream() },password) }; assertTrue(password.all { it == '\u0000' })
        }
        val valid = RecordPackageCipherTest.unhex(fixture["envelopeHex"]!!.jsonPrimitive.content); var kdfCalls = 0
        val bounded = RecordMediaPackageCipher(java.security.SecureRandom()) { kdfCalls++ }
        for (position in listOf(8,12,40)) {
            val bad = valid.copyOf().also { java.nio.ByteBuffer.wrap(it,position,4).putInt(Int.MAX_VALUE) }
            fails { bounded.open({ bad.inputStream() },password()) }
        }
        assertEquals(0,kdfCalls)
    }
    @Test fun multiImagePackageExceedsLegacyBoundAndRestoresFreshEncryptedCopies(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); val first = source.attachment(id,png)
            source.media.save(first) { png.inputStream() }
            val large = ByteArray(3*1024*1024) { 81 }.also { png.copyInto(it) }
            val second = source.attachment(id,large); source.media.save(second) { large.inputStream() }
            source.tags.replace(ManualTagMutation(id,0,listOf("合成图片标签"),uuid()))
            val preview = preview(source,id); assertEquals(2,preview.images.size)
            assertEquals(png.size.toLong()+large.size,preview.imageBytes)
            val bytes = exported(repo(source),preview); assertTrue(bytes.size > RecordPackages.MAX_ENVELOPE)
            val pack = opened(repo(target),bytes); assertEquals(2,pack.images.size)
            val newId = uuid(); val result = repo(target).restore(pack,listOf(newId),99)
            assertEquals(listOf(newId),result.ids); assertFalse(result.replayed)
            assertEquals("AI_NONE",target.db.read("SELECT privacyPolicy FROM entries WHERE id=?",newId).single().single())
            assertEquals(listOf("合成图片标签"),target.tags.read(newId).names)
            val restored = target.media.list(newId,1); assertEquals(2,restored.size)
            restored.forEach { row ->
                assertEquals(AttachmentState.READY,row.state); assertTrue(row.intent.id !in listOf(first.id,second.id))
                val original = if (row.intent.byteSize == png.size.toLong()) png else large
                assertArrayEquals(original,target.media.read(row.intent.id))
                val file = target.mediaRoot.resolve(row.intent.id+".aead").readBytes()
                assertFalse(file.contentEquals(original)); assertFalse(file.contentEquals(source.mediaRoot.resolve(
                    (if (original === png) first.id else second.id)+".aead").readBytes()))
            }
            target.reopen(); val cold = repo(target); cold.recover()
            val replay = cold.restore(opened(cold,bytes),listOf(uuid()),1000)
            assertTrue(replay.replayed); assertEquals(result.ids,replay.ids)
            assertEquals(2,target.media.list(newId,1).size); pack.release(); large.fill(0)
        } }
    }
    @Test fun legacyV1RemainsReadableAndCreatesNoAttachmentRows(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val bytes = JcaRecordPackageCipher().seal(RecordPackageCipherTest.snapshot(),password()).let { p -> try { p.copyBytes() } finally { p.release() } }
            val pack = opened(repo(f),bytes); assertTrue(pack.images.isEmpty())
            val result = repo(f).restore(pack,listOf(uuid()),12)
            assertFalse(result.replayed); assertTrue(f.db.read("SELECT id FROM attachments").isEmpty())
        }
    }
    @Test fun corruptionTruncationTrailingFrameAndForgedOuterLengthFailWithoutAnyImport(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id = f.entry(); f.media.save(f.attachment(id,png)) { png.inputStream() }
            val bytes = exported(repo(f),preview(f,id)); val before = f.db.read("SELECT * FROM entries")
            val cases = listOf(bytes.copyOf().also { it[it.lastIndex-24] = (it[it.lastIndex-24].toInt() xor 1).toByte() },
                bytes.copyOf(bytes.size-1),bytes.copyOf(42),bytes+byteArrayOf(0),
                bytes.copyOf().also { java.nio.ByteBuffer.wrap(it,40,4).putInt(Int.MAX_VALUE) })
            for (bad in cases) {
                var failed = false; try { opened(repo(f),bad) } catch (_: RecordPackageInvalid) { failed = true }; assertTrue(failed)
            }
            assertEquals(before,f.db.read("SELECT * FROM entries"))
            assertTrue(f.db.read("SELECT id FROM record_package_receipts").isEmpty())
        }
    }
    @Test fun previewAndWriteRejectChangedDeletedMissingCorruptOrNotReadySources(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id = f.entry(); val image = f.attachment(id,png); f.media.save(image) { png.inputStream() }
            val r = repo(f); val p = preview(f,id)
            for (state in listOf("WRITING","STAGED","FAILED","CORRUPT")) {
                f.db.write("UPDATE attachments SET state=? WHERE id=?",state,image.id)
                assertFalse(r.isCurrent(p)); fails { preview(f,id) }
            }
            f.db.write("UPDATE attachments SET state='READY' WHERE id=?",image.id)
            assertTrue(r.isCurrent(p))
            assertEquals(1L,f.db.read("SELECT currentRevision FROM entries WHERE id=?",id).single().single())
            assertTrue(f.mediaRoot.resolve(image.id+".aead").delete())
            fails { preview(f,id) } // Still current revision1: this must fail on missing original bytes.
            assertEquals("CORRUPT",f.db.read("SELECT state FROM attachments WHERE id=?",image.id).single().single())
        }
        PermanentErasureFixture().use { f ->
            val id = f.entry(); f.media.save(f.attachment(id,png)) { png.inputStream() }
            val r = repo(f); val p = preview(f,id)
            OriginalExportSqlTest.revise(f.db,id,"后来原文",2)
            assertFalse(r.isCurrent(p)); fails { r.seal(p,password()) }
        }
        PermanentErasureFixture().use { f ->
            val id = f.entry(); f.media.save(f.attachment(id,png)) { png.inputStream() }
            val r = repo(f); val p = preview(f,id)
            f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",id)
            assertFalse(r.isCurrent(p)); val pass = password(); fails { r.seal(p,pass) }; assertTrue(pass.all { it == '\u0000' })
        }
    }
    @Test fun filePublicationFailureLeavesNoVisibleRecordsThenColdCleanupAndRetryUseStableMapping(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); source.media.save(source.attachment(id,png)) { png.inputStream() }
            val bytes = exported(repo(source),preview(source,id)); val newId = uuid()
            target.mediaCheckpoint = { if (it == "package-image-published") error("Synthetic interrupted file publish") }
            fails { repo(target).restore(opened(repo(target),bytes),listOf(newId),50) }
            assertTrue(target.db.read("SELECT id FROM entries").isEmpty())
            assertTrue(target.db.read("SELECT id FROM attachments").isEmpty())
            assertTrue(target.mediaRoot.listFiles()!!.any { it.name.endsWith(".aead") })
            target.reopen(); target.mediaCheckpoint = {}; val cold = repo(target); cold.recover(); cold.recover()
            assertFalse(target.mediaRoot.listFiles()!!.any { it.name.endsWith(".aead") || it.name.endsWith(".tmp") })
            val result = cold.restore(opened(cold,bytes),listOf(uuid()),999)
            assertEquals(listOf(newId),result.ids); assertEquals(50L,result.importedAtMillis)
            assertArrayEquals(png,target.media.read(target.media.list(newId,1).single().intent.id))
        } }
    }
    @Test fun databaseAbortRollsBackTextTagsAndReadyRowsAndCleanupIsReentrant(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); source.media.save(source.attachment(id,png)) { png.inputStream() }
            val bytes = exported(repo(source),preview(source,id)); val r = repo(target)
            target.db.write("CREATE TEMP TRIGGER reject_media_group BEFORE INSERT ON record_package_receipts WHEN NEW.operation='PACKAGE_RESTORE' BEGIN SELECT RAISE(ABORT,'synthetic group failure'); END")
            fails { r.restore(opened(r,bytes),listOf(uuid()),44) }
            assertTrue(target.db.read("SELECT id FROM entries").isEmpty()); assertTrue(target.db.read("SELECT id FROM attachments").isEmpty())
            r.recover(); r.recover(); assertFalse(target.mediaRoot.listFiles()!!.any { it.name.endsWith(".aead") })
            target.db.write("DROP TRIGGER reject_media_group")
            assertFalse(r.restore(opened(r,bytes),listOf(uuid()),99).replayed)
        } }
    }
    @Test fun lostFinalAcknowledgementAndPermanentErasureNeverDuplicateOrReviveImages(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); source.media.save(source.attachment(id,png)) { png.inputStream() }
            val bytes = exported(repo(source),preview(source,id)); val r = repo(target); val newId = uuid()
            var groupWritten = false; var lost = false
            target.db.afterSql = { sql ->
                if (sql.startsWith("INSERT INTO record_package_receipts") && sql.contains("'PACKAGE_RESTORE'")) groupWritten = true
                if (sql == "COMMIT" && groupWritten && !lost) { lost = true; error("Synthetic lost final commit ACK") }
            }
            fails { r.restore(opened(r,bytes),listOf(newId),70) }; assertTrue(lost)
            target.db.afterSql = null; target.reopen(); val cold = repo(target); cold.recover()
            val replay = cold.restore(opened(cold,bytes),listOf(uuid()),999); assertTrue(replay.replayed); assertEquals(listOf(newId),replay.ids)
            val intent = target.intent(newId); val result = target.erase.erase(intent); assertEquals(PermanentErasurePhase.DONE,result.phase)
            val deleted = cold.restore(opened(cold,bytes),listOf(uuid()),1000)
            assertTrue(deleted.replayed); assertEquals(1,deleted.deletedCount)
            assertTrue(target.db.read("SELECT id FROM entries").isEmpty()); assertTrue(target.db.read("SELECT id FROM attachments").isEmpty())
            assertFalse(target.mediaRoot.listFiles()!!.any { it.name.endsWith(".aead") })
        } }
    }
    @Test fun selectedDocumentSubstitutionDuringImportCannotCommitAPrefix(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); source.media.save(source.attachment(id,png)) { png.inputStream() }
            val good = exported(repo(source),preview(source,id)); var current = good
            val r = repo(target); val pack = r.open({ current.inputStream() },password())
            current = good.copyOf(good.size-1)
            fails { r.restore(pack,listOf(uuid()),10) }; assertTrue(target.db.read("SELECT id FROM entries").isEmpty())
            r.recover(); assertTrue(target.db.read("SELECT id FROM attachments").isEmpty()); pack.release()
        } }
    }
    @Test fun quantityByteLimitsAndMimeMismatchFailBeforePackagePublication(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id = f.entry(); val snapshot = SqlOriginalExportRepository(f.gateway,f.tags).capture(listOf(SourceVersion(id,1)),uuid(),5)
            val descriptors = List(21) { RecordPackageImage(id,1,uuid(),"image/png",1,"a".repeat(64)) }
            assertThrows(IllegalArgumentException::class.java) { RecordMediaPreview(snapshot,descriptors) }
            assertThrows(IllegalArgumentException::class.java) { RecordPackageImage(id,1,uuid(),"image/png",RecordMediaPackages.MAX_IMAGE_BYTES+1,"a".repeat(64)) }
            val large = List(5) { RecordPackageImage(id,1,uuid(),"image/png",RecordMediaPackages.MAX_IMAGE_BYTES,"a".repeat(64)) }
            assertThrows(IllegalArgumentException::class.java) { RecordMediaPreview(snapshot,large) }
            val wrong = "synthetic not PNG".toByteArray(); f.media.save(f.attachment(id,wrong)) { wrong.inputStream() }
            fails { preview(f,id) }
        }
    }
    @Test fun importDeletionFenceBeforeFinalTransactionPreventsAllPublication(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); source.media.save(source.attachment(id,png)) { png.inputStream() }
            val bytes = exported(repo(source),preview(source,id)); val newId = uuid()
            target.mediaCheckpoint = { if (it == "package-before-commit") target.db.write(
                "INSERT INTO permanent_erasure_receipts VALUES(?,1,1,?,'PREPARED','','')",newId,uuid()) }
            fails { repo(target).restore(opened(repo(target),bytes),listOf(newId),22) }
            assertTrue(target.db.read("SELECT id FROM entries").isEmpty()); assertTrue(target.db.read("SELECT id FROM attachments").isEmpty())
            target.mediaCheckpoint = {}; repo(target).recover()
            fails { repo(target).restore(opened(repo(target),bytes),listOf(uuid()),22) }
            assertFalse(target.mediaRoot.listFiles()!!.any { it.name.endsWith(".aead") })
        } }
    }
    @Test fun selectedDocumentWriteFailureAndReadbackSubstitutionNeverReturnSuccess(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id = f.entry(); f.media.save(f.attachment(id,png)) { png.inputStream() }
            val r = repo(f); val p = preview(f,id)
            for (writeFails in listOf(true,false)) {
                var bytes = byteArrayOf()
                val document = object : com.t1doo.aiyou.records.SelectedRecordPackageDocument {
                    override fun openForWrite(): java.io.OutputStream = object : ByteArrayOutputStream() {
                        override fun write(b: ByteArray,off: Int,len: Int) { if (writeFails) throw java.io.IOException("Synthetic write failure"); super.write(b,off,len) }
                        override fun close() { bytes = toByteArray() }
                    }
                    override fun openForRead() = (bytes+byteArrayOf(0)).inputStream()
                }
                val prepared = r.seal(p,password())
                try { fails { com.t1doo.aiyou.records.RecordPackageIo.writeMediaAndVerify(document,prepared) { true } } }
                finally { prepared.release() }
            }
            assertTrue(f.db.read("SELECT id FROM record_package_receipts").isEmpty())
        }
    }
    @Test fun cancellationAndSourceEditDuringStreamingRejectTheFinalFrame(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id = f.entry(); f.media.save(f.attachment(id,png)) { png.inputStream() }
            val r = repo(f); val p = preview(f,id); var active = true
            val prepared = r.seal(p,password())
            val out = object : ByteArrayOutputStream() {
                override fun write(b: ByteArray,off: Int,len: Int) { super.write(b,off,len); active = false }
            }
            try { fails { prepared.writeTo(out) { active } } } finally { prepared.release() }
            fails { opened(r,out.toByteArray()) }
            val second = r.seal(p,password()); var changed = false
            val change = object : ByteArrayOutputStream() {
                override fun write(b: ByteArray,off: Int,len: Int) {
                    super.write(b,off,len)
                    if (!changed) { changed = true; f.db.write("UPDATE remote_source_access SET deleted=1,generation=generation+1 WHERE entryId=?",id) }
                }
            }
            try { fails { second.writeTo(change) { true } } } finally { second.release() }
            fails { opened(r,change.toByteArray()) }; assertTrue(changed)
        }
    }
    @Test fun alreadyCleanedAbortedHistoryDoesNotExhaustStartupRecoveryBudget(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); source.media.save(source.attachment(id,png)) { png.inputStream() }
            val bytes = exported(repo(source),preview(source,id)); val r = repo(target)
            target.mediaCheckpoint = { if (it == "package-image-published") error("Synthetic interrupted publish") }
            fails { r.restore(opened(r,bytes),listOf(uuid()),22) }; target.mediaCheckpoint = {}; r.recover()
            val state = target.db.read("SELECT state FROM record_package_receipts WHERE operation='PACKAGE_MEDIA_INTENT'").single().single() as String
            for (index in 1..65) {
                val backup = uuid(); val encoded = Base64.getDecoder().decode(state)
                backup.toByteArray(Charsets.US_ASCII).copyInto(encoded,8)
                target.db.write("INSERT INTO record_package_receipts VALUES(?,?,1,'PACKAGE_MEDIA_INTENT',?)",
                    "PACKAGE_MEDIA:"+backup,uuid(),Base64.getEncoder().encodeToString(encoded))
            }
            r.recover(); r.recover()
            assertFalse(r.restore(opened(r,bytes),listOf(uuid()),99).replayed)
        } }
    }
    @Test fun partialFileCreationAndInterruptedCleanupAreRecoverableAfterReopen(): Unit = runBlocking {
        PermanentErasureFixture().use { source -> PermanentErasureFixture().use { target ->
            val id = source.entry(); source.media.save(source.attachment(id,png)) { png.inputStream() }
            val bytes = exported(repo(source),preview(source,id)); val newId = uuid()
            target.mediaCheckpoint = { if (it == "created") error("Synthetic interruption after registered file creation") }
            fails { repo(target).restore(opened(repo(target),bytes),listOf(newId),19) }
            assertTrue(target.mediaRoot.listFiles()!!.any { it.name.endsWith(".tmp") && it.length() == 17L })
            assertTrue(target.db.read("SELECT id FROM entries").isEmpty())
            target.reopen(); target.mediaCheckpoint = { if (it == "package-cleaned") error("Synthetic lost cleanup ACK") }
            fails { repo(target).recover() }
            target.mediaCheckpoint = {}; repo(target).recover(); repo(target).recover()
            assertFalse(target.mediaRoot.listFiles()!!.any { it.name.endsWith(".tmp") || it.name.endsWith(".aead") })
            assertEquals(listOf(newId),repo(target).restore(opened(repo(target),bytes),listOf(uuid()),999).ids)
        } }
    }
    @Test fun currentSelectionExcludesHistoricalImagesAndRequiresFreshTagVersion(): Unit = runBlocking {
        PermanentErasureFixture().use { f ->
            val id = f.entry(); val historical = f.attachment(id,png); f.media.save(historical) { png.inputStream() }
            OriginalExportSqlTest.revise(f.db,id,"合成当前版本",2)
            val current = f.attachment(id,png,2); f.media.save(current) { png.inputStream() }
            val originals = SqlOriginalExportRepository(f.gateway,f.tags); val r = repo(f)
            val snapshot = originals.capture(listOf(SourceVersion(id,2)),uuid(),10)
            val preview = r.preview(snapshot); assertEquals(listOf(current.id),preview.images.map { it.attachmentId })
            f.mediaRoot.resolve(historical.id+".aead").delete() // Unselected historical file does not silently alter current scope.
            assertTrue(r.isCurrent(preview))
            f.tags.replace(ManualTagMutation(id,0,listOf("新手工标签"),uuid()))
            assertFalse(r.isCurrent(preview)); fails { r.seal(preview,password()) }
            val fresh = r.preview(originals.capture(listOf(SourceVersion(id,2)),uuid(),10))
            assertEquals(listOf("新手工标签"),fresh.snapshot.records.single().tags.names)
            assertEquals(2,fresh.images.single().sourceRevision)
        }
    }
    private fun repo(f: PermanentErasureFixture) = SqlRecordMediaPackageRepository(f.gateway,SqlOriginalExportRepository(f.gateway,f.tags),f.media,f.packages)
    private suspend fun preview(f: PermanentErasureFixture,id: String) = repo(f).preview(SqlOriginalExportRepository(f.gateway,f.tags).capture(listOf(SourceVersion(id,1)),uuid(),5))
    private suspend fun exported(r: RecordMediaPackageRepository,p: RecordMediaPreview): ByteArray {
        val prepared = r.seal(p,password()); val out = ByteArrayOutputStream()
        try { prepared.writeTo(out) { true }; return out.toByteArray() } finally { prepared.release() }
    }
    private suspend fun opened(r: RecordMediaPackageRepository,bytes: ByteArray) = r.open({ bytes.inputStream() },password())
    private suspend fun fails(block: suspend () -> Any?) { var failed = false; try { block() } catch (_: Exception) { failed = true }; assertTrue("Expected fail closed",failed) }
    companion object {
        private fun fixture() = Json.parseToJsonElement(checkNotNull(RecordMediaPackageTest::class.java.getResourceAsStream("/record-package-v2/fixture.json"))
            .bufferedReader().use { it.readText() }).jsonObject
        private fun uuid() = UUID.randomUUID().toString()
        private fun password() = "synthetic media recovery 42".toCharArray()
        private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
    }
}
