package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OriginalExportCodecTest {
    private val text = "合成🙂e\u0301\r\n\"\\\t\u0000\u2028\u2029\n" + 96.toChar().toString().repeat(7) + "\n<script>secret</script> [link](https://example.invalid)\n"
    private fun snapshot(body: String = text) = OriginalExportSnapshot("synthetic-export", 123, listOf(OriginalExportRecord(
        StoredEntry("synthetic-entry", 3, body, 42, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY), OriginalExports.hash(body),
        ManualTagSnapshot("synthetic-entry", 2, listOf("合成🙂标签", "第二标签")))))
    @Test fun jsonlParsesExactOriginalUnicodeControlCharactersMetadataAndRecordStreamHash() {
        val payload = OriginalExportCodec.render(snapshot(), OriginalExportFormat.JSONL)
        try {
            val lines = payload.copyBytes().toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
            assertEquals(3, lines.size); val objects = lines.map { Json.parseToJsonElement(it).jsonObject }
            assertEquals(false, objects[0].getValue("encrypted").jsonPrimitive.boolean)
            assertEquals(setOf("attachments", "history", "tasks", "keys"), objects[0].getValue("excluded").jsonArray.map { it.jsonPrimitive.content }.toSet())
            val record = objects[1]; assertEquals(text, record.getValue("originalText").jsonPrimitive.content)
            assertEquals("synthetic-entry", record.getValue("entryId").jsonPrimitive.content)
            assertEquals(3, record.getValue("revision").jsonPrimitive.int); assertEquals(42L, record.getValue("capturedAtMillis").jsonPrimitive.long)
            assertEquals("Asia/Shanghai", record.getValue("captureTimeZone").jsonPrimitive.content)
            assertEquals(OriginalExports.hash(text), record.getValue("contentHash").jsonPrimitive.content)
            assertEquals(2L, record.getValue("manualTagVersion").jsonPrimitive.long)
            assertEquals(listOf("合成🙂标签", "第二标签"), record.getValue("manualTags").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(OriginalExports.hash(lines[1] + "\n"), objects[2].getValue("recordsSha256").jsonPrimitive.content)
        } finally { payload.release() }
    }
    @Test fun markdownVariableFencesKeepBackticksLinksAndHtmlLiteralAndMetadataPreservesTrailingNewline() {
        val payload = OriginalExportCodec.render(snapshot(), OriginalExportFormat.MARKDOWN)
        try {
            val output = payload.copyBytes().toString(Charsets.UTF_8); val fence = 96.toChar().toString().repeat(8)
            assertTrue(output.contains(fence + "text\n" + text + "\n" + fence))
            val metadata = output.lines().first { it.startsWith("{\"type\":\"currentOriginal\"") }
            assertEquals(text, Json.parseToJsonElement(metadata).jsonObject.getValue("originalText").jsonPrimitive.content)
            assertTrue(output.contains("非完整备份")); assertFalse(output.contains("privacyPolicy"))
        } finally { payload.release() }
    }
    @Test fun deterministicImmutablePayloadCopiesAreIndependentAndReleasedPayloadCannotBeReused() {
        val a = OriginalExportCodec.render(snapshot(), OriginalExportFormat.JSONL); val b = OriginalExportCodec.render(snapshot(), OriginalExportFormat.JSONL)
        assertArrayEquals(a.copyBytes(), b.copyBytes()); assertEquals(a.sha256, b.sha256)
        val changed = a.copyBytes().also { it.fill(0) }; assertFalse(changed.contentEquals(a.copyBytes()))
        assertFalse(a.toString().contains("synthetic")); assertFalse(snapshot().toString().contains("synthetic"))
        assertFalse(snapshot().records.single().toString().contains("合成"))
        a.release(); assertThrows(IllegalStateException::class.java) { a.copyBytes() }; a.release(); b.release()
        assertThrows(UnsupportedOperationException::class.java) { (snapshot().records as MutableList).clear() }
    }
    @Test fun invalidUnicodeHashZoneIdentityAndSizeCannotBecomeAnExport() {
        assertThrows(Exception::class.java) { OriginalExports.hash("\ud800") }
        val e = StoredEntry("synthetic-entry", 1, "合成", 1, "UTC", ProcessingPolicy.LOCAL_ONLY)
        assertThrows(IllegalArgumentException::class.java) { OriginalExportRecord(e, "0".repeat(64), ManualTagSnapshot(e.id, 0, emptyList())) }
        assertThrows(Exception::class.java) { OriginalExportRecord(e.copy(captureTimeZone = "Invalid/Zone"), OriginalExports.hash(e.originalText), ManualTagSnapshot(e.id, 0, emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { snapshot("x".repeat(100_001)) }
        assertTrue(OriginalExportCodec.render(snapshot("\u0001".repeat(100_000)), OriginalExportFormat.MARKDOWN).also { it.release() }.size < OriginalExports.MAX_BYTES)
    }
}
