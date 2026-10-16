package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.security.MessageDigest

/** Immutable bounded UTF-8 payload; no plaintext file/cache. Each codec declares its exact scope. */
class OriginalExportPayload internal constructor(val format: OriginalExportFormat, bytes: ByteArray) {
    private val bytes = bytes.copyOf()
    @Volatile private var released = false
    val size get() = bytes.size
    val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Synchronized fun copyBytes(): ByteArray { check(!released) { "Export payload released" }; return bytes.copyOf() }
    @Synchronized fun release() { released = true; bytes.fill(0) }
    override fun toString() = "OriginalExportPayload(bytes=" + size + ", redacted)"
}

object OriginalExportCodec {
    const val FORMAT_VERSION = 2
    fun render(snapshot: OriginalExportSnapshot, format: OriginalExportFormat): OriginalExportPayload {
        val text = when (format) {
            OriginalExportFormat.JSONL -> jsonl(snapshot)
            OriginalExportFormat.MARKDOWN -> markdown(snapshot)
            OriginalExportFormat.MEDIA_ZIP -> throw IllegalArgumentException("Use the streaming image export")
        }
        val bytes = OriginalExports.utf8(text)
        try {
            if (bytes.size > OriginalExports.MAX_BYTES) throw OriginalExportTooLarge()
            return OriginalExportPayload(format, bytes)
        } finally { bytes.fill(0) }
    }
    private fun manifest(snapshot: OriginalExportSnapshot) = "{\"type\":\"manifest\",\"format\":\"AIyou-current-originals\",\"formatVersion\":2,\"snapshotId\":" +
        quoted(snapshot.id) + ",\"createdAtMillis\":" + snapshot.createdAtMillis + ",\"recordCount\":" + snapshot.records.size +
        ",\"encrypted\":false,\"scope\":[\"currentOriginals\",\"manualTags\",\"eventDates\"],\"excluded\":[\"attachments\",\"history\",\"tasks\",\"keys\"]}"
    internal fun record(value: OriginalExportRecord): String {
        val e = value.entry
        return "{\"type\":\"currentOriginal\",\"entryId\":" + quoted(e.id) + ",\"revision\":" + e.currentRevision +
            ",\"capturedAtMillis\":" + e.capturedAtMillis + ",\"captureTimeZone\":" + quoted(e.captureTimeZone) +
            ",\"contentHash\":" + quoted(value.contentHash) + ",\"originalText\":" + quoted(e.originalText) +
            ",\"manualTagVersion\":" + value.tags.version + ",\"manualTags\":[" + value.tags.names.joinToString(",") { quoted(it) } + "],\"eventDateVersion\":" + value.eventDate.version +
            ",\"eventDate\":" + (value.eventDate.date?.let { "{\"localDate\":" + quoted(it.localDate) + ",\"timeZone\":" + quoted(it.timeZone) + "}" } ?: "null") + "}"
    }
    private fun jsonl(snapshot: OriginalExportSnapshot): String {
        val body = snapshot.records.joinToString("") { record(it) + "\n" }
        return manifest(snapshot) + "\n" + body +
            "{\"type\":\"end\",\"snapshotId\":" + quoted(snapshot.id) + ",\"recordCount\":" + snapshot.records.size +
            ",\"recordsSha256\":" + quoted(OriginalExports.hash(body)) + "}\n"
    }
    private fun markdown(snapshot: OriginalExportSnapshot): String = buildString {
        append("# 爱忆当前原文导出\n\n普通明文导出，非完整备份。仅含选定当前原文、手工标签与手填事件日期及 IANA 时区；事件日期为空表示未知或已清除，不推测时刻。不含附件、历史版本、待办或密钥。\n\n")
        append(fenced("json", manifest(snapshot))); append("\n")
        snapshot.records.forEachIndexed { index, value ->
            append("## 原文 "); append(index + 1); append("\n\n")
            // Metadata and literal originals both use variable-length fences, so original Markdown
            // links/HTML and backtick runs cannot escape into active exported markup.
            val metadata = record(value)
            append(fenced("json", metadata)); append("\n原文（逐字保留）：\n\n")
            append(fenced("text", value.entry.originalText)); append("\n")
        }
        append("导出结束 · 原文条数 "); append(snapshot.records.size); append("\n")
    }
    internal fun fenced(language: String, text: String): String {
        val ticks = 96.toChar()
        var run = 0; var longest = 0
        text.forEach { ch -> if (ch == ticks) { run++; longest = maxOf(longest, run) } else run = 0 }
        val fence = ticks.toString().repeat(maxOf(3, longest + 1))
        return fence + language + "\n" + text + "\n" + fence + "\n"
    }
    internal fun quoted(text: String) = buildString {
        append('"')
        text.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 32 || ch.code == 0x2028 || ch.code == 0x2029) append("\\u" + ch.code.toString(16).padStart(4, '0')) else append(ch)
            }
        }
        append('"')
    }
}
