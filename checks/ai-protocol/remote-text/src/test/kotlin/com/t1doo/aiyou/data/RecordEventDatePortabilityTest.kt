package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Exact manual calendar dates: real local codecs and SQLite, no device or SAF claim. */
class RecordEventDatePortabilityTest {
    @Test fun markdownAndJsonlRetainDateZoneVersionAndUnknownWithoutInventingAnInstant() {
        val snapshot = snapshot()
        for (format in listOf(OriginalExportFormat.MARKDOWN, OriginalExportFormat.JSONL)) {
            val payload = OriginalExportCodec.render(snapshot, format)
            try {
                val text = payload.copyBytes().toString(Charsets.UTF_8)
                val records = text.lines().filter { it.startsWith("{\"type\":\"currentOriginal\"") }.map { Json.parseToJsonElement(it).jsonObject }
                assertEquals(2, records.size)
                assertEquals("2024-02-29", records[0].getValue("eventDate").jsonObject.getValue("localDate").jsonPrimitive.content)
                assertEquals("America/New_York", records[0].getValue("eventDate").jsonObject.getValue("timeZone").jsonPrimitive.content)
                assertEquals(7L, records[0].getValue("eventDateVersion").jsonPrimitive.long)
                assertEquals(JsonNull, records[1].getValue("eventDate"))
                assertEquals(0L, records[1].getValue("eventDateVersion").jsonPrimitive.long)
                assertEquals(5L, records[0].getValue("capturedAtMillis").jsonPrimitive.long)
                assertEquals("UTC", records[0].getValue("captureTimeZone").jsonPrimitive.content)
                assertEquals(setOf("localDate", "timeZone"), records[0].getValue("eventDate").jsonObject.keys)
                assertTrue(text.contains("eventDates"))
            } finally { payload.release() }
        }
    }

    @Test fun activityExportsAndOpenZipRetainSameEventMetadata(): Unit = runBlocking {
        val snapshot = snapshot()
        val activities = RecordPackageActivities(emptyList(), emptyList(), snapshot.records.map { it.sourceGeneration })
        val activityPayload = OriginalActivityExportCodec.render(snapshot, activities, OriginalExportFormat.JSONL)
        try { assertTrue(activityPayload.copyBytes().toString(Charsets.UTF_8).contains("\"eventDate\":{\"localDate\":\"2024-02-29\",\"timeZone\":\"America/New_York\"}")) }
        finally { activityPayload.release() }
        val prepared = OpenRecordZipCodec.prepare(RecordMediaPreview(snapshot, emptyList()), { error("No image") }, { true })
        val out = ByteArrayOutputStream(); prepared.writeTo(out) { true }
        val files = linkedMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) { val next = zip.nextEntry ?: break; files[next.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        for (name in listOf("records.jsonl", "records.md")) {
            assertTrue(files.getValue(name).contains("2024-02-29")); assertTrue(files.getValue(name).contains("America/New_York"))
            assertTrue(files.getValue(name).contains("\"eventDate\":null"))
        }
        assertTrue(files.getValue("manifest.json").contains("eventDates"))
    }

    @Test fun currentV5AuthenticatesDateZoneAndLeavesUnknownRecordUnknown(): Unit = runBlocking {
        val cipher = RecordMediaPackageCipher(); val bytes = encrypted(RecordMediaPreview(snapshot(), emptyList()))
        assertEquals("AIYOUPK5", bytes.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        val pack = cipher.open({ bytes.inputStream() }, password()) as AuthenticatedMediaPackage
        try {
            assertFalse(pack.includesHistory); assertEquals(DATE, pack.records[0].eventDate); assertNull(pack.records[1].eventDate)
            assertEquals(5L, pack.records[0].capturedAtMillis); assertEquals("UTC", pack.records[0].captureTimeZone)
            assertEquals(1, pack.records[0].restoredRevision); cipher.consume(pack) { _, _ -> fail("No image") }
        } finally { pack.release() }
    }

    @Test fun fullHistoryV5KeepsEachOriginalTimeAndActivities(): Unit = runBlocking {
        val preview = historyPreview()
        val cipher = RecordMediaPackageCipher(); val bytes = encrypted(preview)
        val pack = cipher.open({ bytes.inputStream() }, password())
        try {
            assertTrue(pack.includesHistory); assertEquals(DATE, pack.records.single().eventDate)
            assertEquals(listOf(17L, 3L), pack.records.single().history.map { it.createdAtMillis })
            assertEquals(2, pack.records.single().restoredRevision); assertNotNull(pack.activities)
        } finally { pack.release() }
    }

    @Test fun dateAndIanaZoneBothChangeAuthenticatedRecordDigest() {
        val a = encodedHistory(historyPreview())
        val b = encodedHistory(historyPreview(RecordEventDate("2024-03-01", DATE.timeZone)))
        val c = encodedHistory(historyPreview(RecordEventDate(DATE.localDate, "Asia/Shanghai")))
        assertNotEquals(a.digest, b.digest); assertNotEquals(a.recordDigests, b.recordDigests)
        assertNotEquals(a.digest, c.digest); assertNotEquals(a.recordDigests, c.recordDigests)
        assertEquals(a.records.single().history, c.records.single().history)
    }

    @Test fun malformedMetadataRejectsTrailingDuplicateIndexInvalidDayZoneAndSource() {
        val preview = historyPreview(); val base = RecordHistoryPackageCodec.encode(RecordEventDatePackageCodec.legacyPreview(preview))
        try {
            for (kind in listOf("trailing", "index", "source", "day", "zone", "flag", "count", "empty", "base-version")) {
                val raw = PackageWriter(RecordMediaPackages.MAX_METADATA).use { out ->
                    out.string("AIyou-event-dates-v5", 32); out.i32(5); out.i32(1); out.i32(if (kind == "base-version") 5 else 4)
                    out.i32(base.size); out.raw(base); out.i32(if (kind == "count") 2 else 1)
                    out.i32(if (kind == "index") 1 else 0); out.string(if (kind == "source") "other-source" else preview.snapshot.records.single().entry.id, 512)
                    out.i32(when(kind) { "flag" -> 2; "empty" -> 0; else -> 1 })
                    if (kind != "empty") { out.string(if (kind == "day") "2023-02-29" else DATE.localDate, 10); out.string(if (kind == "zone") "+08:00" else DATE.timeZone, 128) }
                    if (kind == "trailing") out.i32(9)
                    out.bytes()
                }
                try { assertThrows(kind, Exception::class.java) { RecordEventDatePackageCodec.parse(raw) { bytes, _ -> RecordHistoryPackageCodec.parse(bytes) } } }
                finally { raw.fill(0) }
            }
        } finally { base.fill(0) }
    }

    @Test fun changingDateInvalidatesFrozenExportAndLegacyV1RefusesRatherThanLosesIt() {
        val a = snapshot().records.first()
        val b = OriginalExportRecord(a.entry, a.contentHash, a.tags, a.sourceGeneration, a.imageCount,
            RecordEventDateSnapshot(a.entry.id, 8, null))
        assertFalse(a.same(b))
        val password = password()
        assertThrows(RecordPackageInvalid::class.java) { JcaRecordPackageCipher().seal(snapshot(), password) }
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun v5EnvelopeTamperOrTruncationCannotReleaseAuthenticatedPreview(): Unit = runBlocking {
        val bytes = encrypted(RecordMediaPreview(snapshot(), emptyList()))
        for (damaged in listOf(bytes.copyOf(bytes.size - 1), bytes.copyOf().also { it[55] = (it[55].toInt() xor 1).toByte() })) {
            val pass = password()
            try { RecordMediaPackageCipher().open({ damaged.inputStream() }, pass); fail("Authenticated damaged document") }
            catch (_: RecordPackageInvalid) { assertTrue(pass.all { it == '\u0000' }) }
        }
    }

    private fun snapshot(): OriginalExportSnapshot = OriginalExportSnapshot(uuid(), 50, listOf(record("event-original", DATE, 7), record("unknown-original", null, 0)))
    private fun record(id: String, date: RecordEventDate?, version: Long, revision: Int = 1) = OriginalExportRecord(
        StoredEntry(id, revision, "合成日期原文", 5, "UTC", ProcessingPolicy.LOCAL_ONLY), OriginalExports.hash("合成日期原文"),
        ManualTagSnapshot(id, 0, emptyList()), eventDate = RecordEventDateSnapshot(id, version, date))
    private fun historyPreview(date: RecordEventDate = DATE): RecordMediaPreview {
        val current = record("history-event", date, 7, 2)
        val history = listOf(RecordPackageRevision(1, "合成早期原文", OriginalExports.hash("合成早期原文"), 17),
            RecordPackageRevision(2, current.entry.originalText, current.contentHash, 3))
        val original = RecordPackageOriginal(current.entry.id, 2, 5, "UTC", current.entry.originalText,
            current.contentHash, 0, emptyList(), history = history, eventDate = date)
        val snapshot = OriginalExportSnapshot(uuid(), 50, listOf(current))
        return RecordMediaPreview(snapshot, emptyList(), RecordPackageActivities(emptyList(), emptyList(), listOf(0)), listOf(original))
    }
    private fun encodedHistory(preview: RecordMediaPreview): AuthenticatedRecordPackage {
        val base = RecordHistoryPackageCodec.encode(RecordEventDatePackageCodec.legacyPreview(preview))
        val raw = try { RecordEventDatePackageCodec.encode(preview, 4, base) } finally { base.fill(0) }
        return try { RecordEventDatePackageCodec.parse(raw) { bytes, _ -> RecordHistoryPackageCodec.parse(bytes) } } finally { raw.fill(0) }
    }
    private suspend fun encrypted(preview: RecordMediaPreview): ByteArray {
        val prepared = RecordMediaPackageCipher().seal(preview, password(), { error("No image") }, { true })
        return try { ByteArrayOutputStream().use { out -> prepared.writeTo(out) { true }; out.toByteArray() } } finally { prepared.release() }
    }
    companion object {
        private val DATE = RecordEventDate("2024-02-29", "America/New_York")
        private fun uuid() = UUID.randomUUID().toString()
        private fun password() = "synthetic event recovery 42".toCharArray()
    }
}
