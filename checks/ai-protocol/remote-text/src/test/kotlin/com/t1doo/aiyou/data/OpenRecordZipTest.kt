package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.io.*
import java.nio.file.Files
import java.time.Clock
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual SQL, attachment AEAD, streaming ZIP, controller and provider IO; synthetic host data only. */
class OpenRecordZipTest {
    @Test fun standardZipKeepsMixedAndImageOnlyRecordsExactWithCompleteHashesAndOrdinaryFiles(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val mixed = imageEntry(f, "合成原文🙂\r\n```\n</script> [链接](https://synthetic.invalid)\n````")
            val imageOnly = imageEntry(f, "")
            val capture = MixedCaptureSqlTest.repo(f)
            val plainDraft = capture.edit(capture.current(), "合成纯文字")
            capture.save(plainDraft, 5, "UTC"); val plain = plainDraft.id
            f.tags.replace(ManualTagMutation(mixed, 0, listOf("合成标签"), uuid()))
            val preview = preview(f, listOf(mixed, imageOnly, plain))
            val document = Document(); val ready = repo(f).exportOpenZip(preview)
            try { OriginalExportIo.writeZipAndVerify(document, ready) { true } } finally { ready.release() }
            assertTrue(document.outputClosed && document.inputClosed); assertTrue(document.maxRead <= 8192)
            val files = unzip(document.bytes)
            assertEquals(setOf("records.md", "records.jsonl", "manifest.json") + preview.images.map(OpenRecordZipCodec::imagePath), files.keys)
            val manifest = Json.parseToJsonElement(files.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject
            assertEquals(1, manifest.getValue("formatVersion").jsonPrimitive.int); assertFalse(manifest.getValue("encrypted").jsonPrimitive.boolean)
            assertEquals(3, manifest.getValue("recordCount").jsonPrimitive.int); assertEquals(2, manifest.getValue("imageCount").jsonPrimitive.int)
            for (value in manifest.getValue("files").jsonArray) {
                val row = value.jsonObject; val bytes = files.getValue(row.getValue("path").jsonPrimitive.content)
                assertEquals(bytes.size.toLong(), row.getValue("byteSize").jsonPrimitive.long)
                assertEquals(PermanentErasureFixture.hash(bytes), row.getValue("sha256").jsonPrimitive.content)
            }
            assertEquals(files.size - 1, manifest.getValue("files").jsonArray.size)
            val records = files.getValue("records.jsonl").toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }.toList()
            assertEquals(listOf(mixed, imageOnly, plain), records.map { it.getValue("entryId").jsonPrimitive.content })
            assertEquals("", records[1].getValue("originalText").jsonPrimitive.content)
            assertEquals(1, records[1].getValue("images").jsonArray.size)
            val md = files.getValue("records.md").toString(Charsets.UTF_8)
            for (image in preview.images) { assertArrayEquals(PNG, files.getValue(OpenRecordZipCodec.imagePath(image))); assertTrue(md.contains("](" + OpenRecordZipCodec.imagePath(image) + ")")) }
            assertTrue(md.contains("`````text")); assertEquals(0, document.deletions)
            val artifact = File("build/reports/open-record-export/synthetic.zip"); artifact.parentFile.mkdirs(); artifact.writeBytes(document.bytes)
        }
    }
    @Test fun missingCorruptAndNonReadyImagesFailTheWholePreview(): Unit = runBlocking {
        for (mode in listOf("missing", "corrupt", "failed")) MixedCaptureSqlTest.fixture().use { f ->
            val id = imageEntry(f, "合成图文"); val image = f.media.list(id, 1).single().intent
            when (mode) {
                "missing" -> Files.delete(f.mediaRoot.resolve(image.id + ".aead").toPath())
                "corrupt" -> f.mediaRoot.resolve(image.id + ".aead").let { file -> file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }) }
                else -> f.db.write("UPDATE attachments SET state='FAILED' WHERE id=?", image.id)
            }
            fails { preview(f, listOf(id)) }
        }
    }
    @Test fun authenticatedButMalformedImageFailsProductionPreflightBeforeConsentOrPicker(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val id = imageEntry(f, "合成图文")
            val invalid = PNG.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            f.media.save(f.attachment(id, invalid)) { invalid.inputStream() }
            val h = Harness(f, id); h.controller.open(); h.controller.toggle(h.ui.session, id, 1)
            h.controller.format(h.ui.session, OriginalExportFormat.MEDIA_ZIP); h.controller.prepare(h.ui.session); h.drain()
            assertEquals(OriginalExportPhase.FAILED, h.ui.phase); assertNull(h.ui.snapshot)
            assertNull(h.ui.requestKey); assertFalse(h.ui.riskAccepted)
        }
    }
    @Test fun sourceTagsAndImageInventoryChangesCannotWriteOrSilentlyRefresh(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val id = imageEntry(f, "合成图文"); val p = preview(f, listOf(id))
            f.tags.replace(ManualTagMutation(id, 0, listOf("合成更新"), uuid()))
            assertFalse(repo(f).isCurrent(p)); fails { repo(f).exportOpenZip(p) }
            val fresh = preview(f, listOf(id)); f.media.save(f.attachment(id, PNG)) { PNG.inputStream() }
            assertFalse(repo(f).isCurrent(fresh)); fails { repo(f).exportOpenZip(fresh) }
        }
    }
    @Test fun missingImageAfterConsentAndSourceChangeDuringWriteOrReadbackNeverReachDone(): Unit = runBlocking {
        for (mode in listOf("missing", "writeChange", "readChange")) MixedCaptureSqlTest.fixture().use { f ->
            val id = imageEntry(f, "合成图文"); val h = Harness(f, id); h.picker()
            val document = Document()
            if (mode == "missing") Files.delete(f.mediaRoot.resolve(h.ui.mediaPreview!!.images.single().attachmentId + ".aead").toPath())
            else if (mode == "writeChange") document.onWrite = { document.onWrite = null; f.tags.replace(ManualTagMutation(id, 0, listOf("合成写入变化"), uuid())) }
            else document.onRead = { document.onRead = null; f.tags.replace(ManualTagMutation(id, 0, listOf("合成读回变化"), uuid())) }
            h.controller.picked(h.ui.session, h.ui.requestKey!!, document); h.drain()
            assertTrue(h.ui.phase in setOf(OriginalExportPhase.FAILED, OriginalExportPhase.CHANGED))
            assertTrue(h.ui.hasFile); assertFalse(h.ui.riskAccepted); assertNull(h.ui.notice)
            assertTrue(h.ui.error!!.contains("不完整")); assertEquals(0, document.deletions)
        }
    }
    @Test fun cancellationClosesStreamsRetainsPartialTargetAndIgnoresLateCallbacks(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val id = imageEntry(f, ""); val h = Harness(f, id); h.picker()
            val session = h.ui.session; val key = h.ui.requestKey!!; val document = Document()
            document.onWrite = { h.controller.cancel(session) }
            h.controller.picked(session, key, document); h.drain()
            assertEquals(OriginalExportPhase.CANCELLED, h.ui.phase); assertTrue(document.outputClosed)
            assertEquals(0, document.deletions); assertTrue(h.ui.hasFile)
            h.controller.deleteSelectedFile(session, key); h.drain(); assertEquals(1, document.deletions)
            h.controller.close(session); h.controller.open()
            val late = Document(); h.controller.picked(session, key, late); h.drain(); assertEquals(0, late.opens)
        }
    }
    @Test fun providerReadbackMismatchTruncationExtraZeroReadsDenialAndCloseFailuresNeverSucceed(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val id = imageEntry(f, "合成图文")
            for (mode in listOf("write", "outputClose", "read", "inputClose", "mismatch", "truncated", "extra", "zero")) {
                val h = Harness(f, id); h.picker(); val document = Document(mode)
                h.controller.picked(h.ui.session, h.ui.requestKey!!, document); h.drain()
                assertEquals(mode, OriginalExportPhase.FAILED, h.ui.phase)
                assertEquals(0, document.deletions); assertTrue(h.ui.hasFile); assertNull(h.ui.notice)
                assertFalse(h.ui.error!!.contains("sensitive-provider"))
            }
        }
    }
    @Test fun formatScopeAndPickerCancellationRequireNewPreviewAndConsent(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val h = Harness(f, imageEntry(f, "")); h.preview(); h.accept()
            val old = h.ui; h.controller.format(old.session, OriginalExportFormat.JSONL)
            assertEquals(OriginalExportPhase.SELECTING, h.ui.phase); assertNull(h.ui.mediaPreview); assertNull(h.ui.snapshot)
            h.controller.acceptRisk(old.session, old.snapshot!!.id, old.format, old.epoch, true); assertFalse(h.ui.riskAccepted)
            h.controller.format(old.session, OriginalExportFormat.MEDIA_ZIP); h.controller.prepare(old.session); h.drain(); h.accept(); h.choose(); h.drain()
            val key = h.ui.requestKey!!; h.controller.picked(h.ui.session, key, null)
            assertFalse(h.ui.riskAccepted); assertEquals(OriginalExportPhase.PREVIEW, h.ui.phase)
            h.choose(); assertTrue(h.queue.isEmpty())
        }
    }
    @Test fun imagePreviewIsAuthenticatedWipedAndCancelledLateDecodeCannotResurrectIt(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val h = Harness(f, imageEntry(f, "")); h.preview(); val image = h.ui.mediaPreview!!.images.single()
            var decodedBytes: ByteArray? = null; var released = false
            h.decoder = { bytes -> decodedBytes = bytes; assertArrayEquals(PNG, bytes)
                h.controller.cancel(h.ui.session)
                object : AttachmentPicture { override val width = 1; override val height = 1; override fun releaseUnpublished() { released = true } }
            }
            h.controller.viewImage(h.ui.session, image.attachmentId); h.drain()
            assertTrue(decodedBytes!!.all { it == 0.toByte() }); assertTrue(released)
            assertEquals(OriginalExportPhase.CANCELLED, h.ui.phase); assertNull(h.ui.picture); assertFalse(h.ui.busy)
        }
    }
    @Test fun imageAlreadyOfferedToComposeIsNeverRecycledAfterImmediateClose(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val h = Harness(f, imageEntry(f, "")); h.preview(); val image = h.ui.mediaPreview!!.images.single()
            var recycled = false
            h.decoder = { object : AttachmentPicture { override val width = 1; override val height = 1
                override fun releaseUnpublished() { recycled = true } } }
            h.afterPublish = { if (h.local.originalExport?.picture != null) { h.afterPublish = null; h.controller.close(h.ui.session) } }
            h.controller.viewImage(h.ui.session, image.attachmentId); h.drain()
            assertNull(h.local.originalExport); assertFalse(recycled)
        }
    }
    @Test fun cancellingPreflightStopsBeforeDecodingTheNextImage(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val id = imageEntry(f, "合成图文"); f.media.save(f.attachment(id, PNG)) { PNG.inputStream() }
            val h = Harness(f, id); var decodes = 0
            h.decoder = { decodes++; h.controller.cancel(h.ui.session)
                object : AttachmentPicture { override val width = 1; override val height = 1 } }
            h.controller.open(); h.controller.toggle(h.ui.session, id, 1); h.controller.format(h.ui.session, OriginalExportFormat.MEDIA_ZIP)
            h.controller.prepare(h.ui.session); h.drain()
            assertEquals(1, decodes); assertEquals(OriginalExportPhase.CANCELLED, h.ui.phase); assertNull(h.ui.snapshot)
        }
    }
    @Test fun codecRejectsOversizedInventoriesMissingDescriptorsAndReusedOrReleasedWork(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val id = imageEntry(f, "合成图文"); f.media.save(f.attachment(id, PNG)) { PNG.inputStream() }
            val p = preview(f, listOf(id)); val image = p.images.first()
            fails { OpenRecordZipCodec.prepare(RecordMediaPreview(p.snapshot, emptyList()), { PNG.copyOf() }, { true }) }
            fails { RecordMediaPreview(p.snapshot, List(21) { image.copy(attachmentId = uuid()) }) }
            fails { image.copy(byteSize = RecordMediaPackages.MAX_IMAGE_BYTES + 1) }
            fails { RecordMediaPreview(p.snapshot, List(5) { image.copy(attachmentId = uuid(), byteSize = RecordMediaPackages.MAX_IMAGE_BYTES) }) }
            val retained = mutableListOf<ByteArray>(); var activeReads = 0
            val ready = OpenRecordZipCodec.prepare(p, { assertTrue(retained.all { bytes -> bytes.all { it == 0.toByte() } }); activeReads++; PNG.copyOf().also(retained::add) }, { true })
            ready.writeTo(ByteArrayOutputStream()) { true }; assertEquals(2, activeReads); assertTrue(retained.all { bytes -> bytes.all { it == 0.toByte() } })
            fails { ready.writeTo(ByteArrayOutputStream()) { true } }; ready.release()
            val revoked = repo(f).exportOpenZip(p); revoked.release(); fails { revoked.writeTo(ByteArrayOutputStream()) { true } }
        }
    }
    @Test fun currentPureAudioAndMixedMediaExportExactWaveWithPortableSourceManifest(): Unit = runBlocking {
        for (mixed in listOf(false, true)) MixedCaptureSqlTest.fixture().use { f ->
            val id = audioEntry(f, mixed); val p = preview(f, listOf(id))
            assertEquals(1, p.audios.size); assertEquals(if (mixed) 1 else 0, p.images.size)
            val originalCipher = f.mediaRoot.resolve(p.audios.single().attachmentId + ".aead").readBytes()
            val ready = repo(f).exportOpenZip(p); val doc = Document()
            try { OriginalExportIo.writeZipAndVerify(doc, ready) { true } } finally { ready.release() }
            val files = unzip(doc.bytes)
            val audio = p.audios.single(); val path = "audio/${audio.attachmentId}.wav"
            assertEquals(setOf("records.md", "records.jsonl", "manifest.json", path) + p.images.map(OpenRecordZipCodec::imagePath), files.keys)
            assertArrayEquals(CaptureAudioSqlTest.wave(), files.getValue(path))
            assertEquals(4, LocalPcmAudio.inspect(files.getValue(path)).samples)
            val manifest = Json.parseToJsonElement(files.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject
            assertEquals(4, manifest.getValue("formatVersion").jsonPrimitive.int)
            assertEquals(1, manifest.getValue("audioCount").jsonPrimitive.int)
            val descriptor = manifest.getValue("audios").jsonArray.single().jsonObject
            assertEquals(id, descriptor.getValue("sourceId").jsonPrimitive.content)
            assertEquals(1, descriptor.getValue("sourceRevision").jsonPrimitive.int)
            assertEquals(audio.attachmentId, descriptor.getValue("attachmentId").jsonPrimitive.content)
            assertEquals(audio.byteSize, descriptor.getValue("byteSize").jsonPrimitive.long)
            assertEquals(audio.sha256, descriptor.getValue("sha256").jsonPrimitive.content)
            assertEquals(path, descriptor.getValue("path").jsonPrimitive.content)
            val record = Json.parseToJsonElement(files.getValue("records.jsonl").toString(Charsets.UTF_8).trim()).jsonObject
            assertEquals(p.snapshot.records.single().entry.originalText, record.getValue("originalText").jsonPrimitive.content)
            assertEquals(descriptor, record.getValue("audios").jsonArray.single())
            assertTrue(files.getValue("records.md").toString(Charsets.UTF_8).contains("[原音]($path)"))
            manifest.getValue("files").jsonArray.forEach { value ->
                val row = value.jsonObject; val bytes = files.getValue(row.getValue("path").jsonPrimitive.content)
                assertEquals(bytes.size.toLong(), row.getValue("byteSize").jsonPrimitive.long)
                assertEquals(PermanentErasureFixture.hash(bytes), row.getValue("sha256").jsonPrimitive.content)
            }
            assertTrue(doc.outputClosed && doc.inputClosed); assertEquals(0, doc.deletions)
            assertArrayEquals(originalCipher, f.mediaRoot.resolve(audio.attachmentId + ".aead").readBytes())
        }
    }
    @Test fun audioReplacementWithSameCountDuringReadbackCannotConfirmStaleMediaSet(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val id = audioEntry(f); val h = Harness(f, id); h.picker()
            val approved = h.ui.mediaPreview!!; val old = approved.audios.single(); val doc = Document()
            doc.onRead = {
                doc.onRead = null
                val replacement = AttachmentIntent(uuid(), id, 1, "audio/wav", old.sha256, old.byteSize)
                runBlocking { f.media.save(replacement) { CaptureAudioSqlTest.wave().inputStream() } }
                f.db.write("DELETE FROM attachments WHERE id=?", old.attachmentId)
                // Counts/text still match; only the exact approved attachment set has changed.
                assertTrue(SqlOriginalExportRepository(f.gateway, f.tags).isCurrent(approved.snapshot))
                assertFalse(runBlocking { repo(f).isCurrent(approved) })
            }
            h.controller.picked(h.ui.session, h.ui.requestKey!!, doc); h.drain()
            assertEquals(OriginalExportPhase.CHANGED, h.ui.phase)
            assertTrue(h.ui.hasFile); assertNull(h.ui.notice); assertFalse(h.ui.riskAccepted)
            assertTrue(doc.outputClosed && doc.inputClosed); assertEquals(0, doc.deletions)
        }
    }
    @Test fun audioMissingCorruptNonReadyAndChangedSourceFailBeforeOrDuringTheConfirmedWrite(): Unit = runBlocking {
        for (mode in listOf("missing", "corrupt", "failed", "malformedWave", "sourceDuringWrite")) MixedCaptureSqlTest.fixture().use { f ->
            val id = audioEntry(f); val h = Harness(f, id)
            val audio = f.media.list(id, 1).single().intent
            if (mode == "sourceDuringWrite") {
                h.picker(); val doc = Document()
                doc.onWrite = { doc.onWrite = null; f.tags.replace(ManualTagMutation(id, 0, listOf("合成已变化标签"), uuid())) }
                h.controller.picked(h.ui.session, h.ui.requestKey!!, doc); h.drain()
                assertTrue(h.ui.phase in setOf(OriginalExportPhase.FAILED, OriginalExportPhase.CHANGED))
                assertTrue(h.ui.hasFile); assertTrue(doc.outputClosed); assertNull(h.ui.notice); assertEquals(0, doc.deletions)
            } else {
                val file = f.mediaRoot.resolve(audio.id + ".aead")
                when (mode) {
                    "missing" -> Files.delete(file.toPath())
                    "corrupt" -> file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                    "malformedWave" -> {
                        val bad = CaptureAudioSqlTest.wave().also { it[24] = (it[24].toInt() xor 1).toByte() }
                        f.media.save(AttachmentIntent(uuid(), id, 1, "audio/wav", PermanentErasureFixture.hash(bad), bad.size.toLong())) { bad.inputStream() }
                    }
                    else -> f.db.write("UPDATE attachments SET state='FAILED' WHERE id=?", audio.id)
                }
                h.controller.open(); h.controller.toggle(h.ui.session, id, 1)
                h.controller.format(h.ui.session, OriginalExportFormat.MEDIA_ZIP); h.controller.prepare(h.ui.session); h.drain()
                assertEquals(mode, OriginalExportPhase.FAILED, h.ui.phase)
                assertNull(h.ui.snapshot); assertNull(h.ui.requestKey); assertFalse(h.ui.hasFile)
            }
            assertFalse(h.ui.riskAccepted); assertEquals(1, f.db.read("SELECT id FROM entries").size)
        }
    }
    @Test fun audioProviderFailuresCancellationAndLatePickerNeverClaimSuccessOrDeleteAutomatically(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val id = audioEntry(f)
            for (mode in listOf("write", "outputClose", "read", "inputClose", "mismatch", "truncated", "extra", "zero")) {
                val h = Harness(f, id); h.picker(); val doc = Document(mode)
                h.controller.picked(h.ui.session, h.ui.requestKey!!, doc); h.drain()
                assertEquals(mode, OriginalExportPhase.FAILED, h.ui.phase)
                assertTrue(h.ui.hasFile); assertNull(h.ui.notice); assertFalse(h.ui.riskAccepted); assertEquals(0, doc.deletions)
                assertFalse(h.ui.error!!.contains("sensitive-provider"))
            }
            val h = Harness(f, id); h.picker(); val session = h.ui.session; val key = h.ui.requestKey!!; val doc = Document()
            doc.onWrite = { h.controller.cancel(session) }
            h.controller.picked(session, key, doc); h.drain()
            assertEquals(OriginalExportPhase.CANCELLED, h.ui.phase); assertTrue(doc.outputClosed); assertTrue(h.ui.hasFile)
            assertEquals(0, doc.deletions); h.controller.close(session); h.controller.open()
            val late = Document(); h.controller.picked(session, key, late); h.drain()
            assertEquals(0, late.opens); assertEquals(0, late.deletions)
        }
    }
    @Test fun mixedMediaWipesEachAuthenticatedBufferAndKeepsSharedCountAndByteBounds(): Unit = runBlocking {
        MixedCaptureSqlTest.fixture().use { f ->
            val one = audioEntry(f, true); val two = audioEntry(f); val p = preview(f, listOf(one, two))
            val retained = mutableListOf<ByteArray>(); var reads = 0
            val ready = OpenRecordZipCodec.prepare(p, { item ->
                assertTrue(retained.all { bytes -> bytes.all { it == 0.toByte() } }); reads++
                f.media.read(item.attachmentId).also(retained::add)
            }, { repo(f).isCurrent(p) })
            try { ready.writeTo(ByteArrayOutputStream()) { true } } finally { ready.release() }
            assertEquals(3, reads); assertTrue(retained.all { bytes -> bytes.all { it == 0.toByte() } })
            val image = p.images.single(); val audio = p.audios.first()
            // Same shared inventory limits: audio is not an extra allowance outside image capacity.
            val capacitySnapshot = OriginalExportSnapshot(uuid(), 10, p.snapshot.records.map { record ->
                OriginalExportRecord(record.entry, record.contentHash, record.tags, record.sourceGeneration,
                    imageCount = 9, eventDate = record.eventDate, people = record.people, audioCount = 1)
            })
            val capacityImages = capacitySnapshot.records.flatMap { record -> List(9) {
                image.copy(sourceId = record.entry.id, attachmentId = uuid())
            } }
            val atLimit = RecordMediaPreview(capacitySnapshot, capacityImages, audios = p.audios)
            val bounded = OpenRecordZipCodec.prepare(atLimit, { error("Capacity-only check never opens media") }, { true })
            bounded.release()
            fails { RecordMediaPreview(p.snapshot, List(19) { image.copy(attachmentId = uuid()) }, audios = p.audios) }
            fails { RecordMediaPreview(p.snapshot, List(4) { image.copy(attachmentId = uuid(), byteSize = RecordMediaPackages.MAX_IMAGE_BYTES) }, audios = listOf(audio)) }
            val mixedOnly = OriginalExportSnapshot(uuid(), 10, listOf(p.snapshot.records.first()))
            fails { OpenRecordZipCodec.prepare(RecordMediaPreview(mixedOnly, p.images), { PNG.copyOf() }, { true }) }
            val audioOnly = RecordMediaPreview(OriginalExportSnapshot(uuid(), 10, listOf(p.snapshot.records.last())),
                emptyList(), audios = listOf(p.audios.last()))
            var active = true; var held: ByteArray? = null
            val stopped = OpenRecordZipCodec.prepare(audioOnly, { item ->
                f.media.read(item.attachmentId).also { held = it; active = false }
            }, { true })
            try { fails { stopped.writeTo(ByteArrayOutputStream()) { active } } } finally { stopped.release() }
            assertTrue(held!!.all { it == 0.toByte() })
        }
    }
    private class Harness(f: PermanentErasureFixture, id: String) {
        private val originals = SqlOriginalExportRepository(f.gateway, f.tags)
        private val media = repo(f)
        var local = LocalRecordUiState(ready = true, opening = false, supportsOriginalExport = true,
            entries = originals.capture(listOf(SourceVersion(id, 1)), uuid(), 5).records.map { it.entry })
        var decoder: (ByteArray) -> AttachmentPicture = { bytes -> PngPayloadValidator.validate(bytes); object : AttachmentPicture { override val width = 1; override val height = 1 } }
        val queue = ArrayDeque<suspend (EntryRepository) -> Unit>()
        var afterPublish: (() -> Unit)? = null
        private val store = object : EntryRepository {
            override val originalExport = originals
            override val mediaPackages = media
            override suspend fun readDraft(): TextDraft? = null
            override suspend fun updateDraft(text: String): TextDraft = error("Unrelated")
            override suspend fun saveDraft(expected: TextDraft, capturedAtMillis: Long, captureTimeZone: String): StoredEntry = error("Unrelated")
            override suspend fun entries() = local.entries
            override suspend fun revisions(entryId: String) = emptyList<OriginalRevision>()
            override suspend fun revise(entryId: String, expectedRevision: Int, text: String, createdAtMillis: Long): StoredEntry = error("Unrelated")
        }
        val controller = OriginalExportController({ local }, { local = it(local); afterPublish?.invoke() }, { queue.add(it); true }, Clock.systemUTC(), { decoder(it) })
        val ui get() = checkNotNull(local.originalExport)
        suspend fun drain() { while (queue.isNotEmpty()) queue.removeFirst()(store) }
        suspend fun preview() { controller.open(); controller.toggle(ui.session, local.entries.single().id, 1); controller.format(ui.session, OriginalExportFormat.MEDIA_ZIP); controller.prepare(ui.session); drain(); assertEquals(OriginalExportPhase.PREVIEW, ui.phase) }
        fun accept() { controller.acceptRisk(ui.session, ui.snapshot!!.id, ui.format, ui.epoch, true) }
        fun choose() { controller.choose(ui.session, ui.snapshot!!.id, ui.format, ui.epoch) }
        suspend fun picker() { preview(); accept(); choose(); drain(); assertEquals(OriginalExportPhase.PICKING, ui.phase) }
    }
    private class Document(val mode: String = "") : SelectedExportDocument {
        var bytes = byteArrayOf(); var opens = 0; var maxRead = 0; var deletions = 0
        var outputClosed = false; var inputClosed = false
        var onWrite: (() -> Unit)? = null; var onRead: (() -> Unit)? = null
        override fun openForWrite(): OutputStream {
            opens++; if (mode == "write") throw SecurityException("sensitive-provider")
            return object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) { super.write(b, off, len); onWrite?.invoke() }
                override fun close() { bytes = toByteArray(); outputClosed = true; if (mode == "outputClose") throw IOException("sensitive-provider") }
            }
        }
        override fun openForRead(): InputStream {
            onRead?.invoke(); if (mode == "read") throw SecurityException("sensitive-provider")
            val actual = when (mode) { "extra" -> bytes + byteArrayOf(0); "truncated" -> bytes.copyOf(bytes.size - 1)
                "mismatch" -> bytes.copyOf().also { it[0] = 0 }; else -> bytes }
            return object : ByteArrayInputStream(actual) {
                override fun read(b: ByteArray, off: Int, len: Int): Int { maxRead = maxOf(maxRead, len); return if (mode == "zero") 0 else super.read(b, off, len) }
                override fun close() { inputClosed = true; if (mode == "inputClose") throw IOException("sensitive-provider") }
            }
        }
        override fun delete(): Boolean { deletions++; bytes = byteArrayOf(); return true }
    }
    companion object {
        private val PNG = MixedCaptureSqlTest.png
        private fun uuid() = UUID.randomUUID().toString()
        private fun repo(f: PermanentErasureFixture) = SqlRecordMediaPackageRepository(f.gateway, SqlOriginalExportRepository(f.gateway, f.tags), f.media, f.packages)
        private suspend fun imageEntry(f: PermanentErasureFixture, text: String): String {
            val capture = MixedCaptureSqlTest.repo(f); var draft = capture.current()
            if (text.isNotEmpty()) draft = capture.edit(draft, text)
            draft = capture.add(draft, MixedCaptureSqlTest.intent(draft), PNG); capture.save(draft, 5, "UTC"); return draft.id
        }
        private suspend fun audioEntry(f: PermanentErasureFixture, mixed: Boolean = false): String {
            val capture = MixedCaptureSqlTest.repo(f); var draft = capture.current()
            if (mixed) {
                draft = capture.edit(draft, "合成图音原文🌿")
                draft = capture.add(draft, MixedCaptureSqlTest.intent(draft), PNG)
            }
            val reservation = CaptureAudioSqlTest.reserve(capture, draft, CaptureAudioSqlTest.wave())
            val ready = capture.addAudio(reservation, CaptureAudioSqlTest.wave())
            capture.mutationGate.finish(reservation, ready)
            capture.save(ready, 5, "UTC"); return ready.id
        }
        private suspend fun preview(f: PermanentErasureFixture, ids: List<String>) = repo(f).preview(SqlOriginalExportRepository(f.gateway, f.tags).capture(ids.map { SourceVersion(it, 1) }, uuid(), 10))
        private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
            ZipInputStream(bytes.inputStream(), Charsets.UTF_8).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; check(!containsKey(entry.name)); put(entry.name, zip.readBytes()); zip.closeEntry() }
            }
        }
        private suspend fun fails(block: suspend () -> Any?) { var failed = false; try { block() } catch (_: Exception) { failed = true }; assertTrue("Expected fail closed", failed) }
    }
}
