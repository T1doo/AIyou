package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OpenRecordZipCancelled : IllegalStateException("Open export retired")
/** Standard UTF-8 ZIP. Only one authenticated, bounded original image is held at a time.
 * The caller owns the selected document, close acknowledgement and exact streaming readback.
 */
object OpenRecordZipCodec {
    const val FORMAT_VERSION = 1
    const val MAX_FILE_BYTES = RecordMediaPackages.MAX_IMAGE_TOTAL + 2L * OriginalExports.MAX_BYTES + 262144L
    fun imagePath(image: RecordPackageImage) = "images/" + image.attachmentId +
        if (image.mediaType == "image/png") ".png" else ".jpg"

    fun prepare(preview: RecordMediaPreview, read: suspend (RecordPackageImage) -> ByteArray,
        current: suspend () -> Boolean): PreparedRecordMediaPackage {
        preview.snapshot.records.forEach { record ->
            require(preview.images.count { it.sourceId == record.entry.id } == record.imageCount)
        }
        return object : PreparedRecordMediaPackage {
            private val used = AtomicBoolean()
            private val released = AtomicBoolean()
            override fun release() { released.set(true) }
            override suspend fun writeTo(output: OutputStream, active: () -> Boolean) {
                check(used.compareAndSet(false, true))
                fun owner() { if (released.get() || !active()) throw OpenRecordZipCancelled() }
                suspend fun fresh() { owner(); if (!current()) throw OriginalExportConflict(); owner() }
                fresh()
                // Do not close the provider handle here; the IO adapter must observe its close ACK.
                val zip = ZipOutputStream(object : FilterOutputStream(output) {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { owner(); out.write(bytes, offset, length); owner() }
                    override fun write(value: Int) { owner(); out.write(value); owner() }
                    override fun close() = Unit
                }, Charsets.UTF_8)
                val files = mutableListOf<String>()
                fun writeFile(path: String, bytes: ByteArray) {
                    owner()
                    zip.putNextEntry(ZipEntry(path).also { it.time = 0 })
                    var at = 0
                    while (at < bytes.size) { owner(); val n = minOf(8192, bytes.size - at); zip.write(bytes, at, n); at += n }
                    zip.closeEntry(); owner()
                    files += "{\"path\":" + q(path) + ",\"byteSize\":" + bytes.size + ",\"sha256\":" + q(hash(bytes)) + "}"
                }
                try {
                    val jsonl = OriginalExports.utf8(preview.snapshot.records.joinToString("\n", postfix = "\n") { record ->
                        OriginalExportCodec.record(record).dropLast(1) + ",\"images\":[" +
                            preview.images.filter { it.sourceId == record.entry.id }.joinToString(",") { imageJson(it) } + "]}"
                    })
                    try { if (jsonl.size > OriginalExports.MAX_BYTES) throw OriginalExportTooLarge(); writeFile("records.jsonl", jsonl) }
                    finally { jsonl.fill(0) }
                    val markdown = OriginalExports.utf8(buildString {
                        append("# 爱忆选定图文资料包\n\n明文开放格式资料包，非完整备份。保留手填事件日期与 IANA 时区；未知或已清除为 null，不补时刻。原图保留原始元数据，可能含位置等隐私。\n\n")
                        preview.snapshot.records.forEachIndexed { index, record ->
                            append("## 记录 ${index + 1}\n\n")
                            append(OriginalExportCodec.fenced("json", OriginalExportCodec.record(record)))
                            append("\n原文（逐字保留）：\n\n")
                            append(OriginalExportCodec.fenced("text", record.entry.originalText))
                            preview.images.filter { it.sourceId == record.entry.id }.forEach { image ->
                                append("\n![原图]("); append(imagePath(image)); append(")\n")
                            }
                            append("\n")
                        }
                    })
                    try { if (markdown.size > OriginalExports.MAX_BYTES) throw OriginalExportTooLarge(); writeFile("records.md", markdown) }
                    finally { markdown.fill(0) }
                    for (image in preview.images) {
                        fresh()
                        // The production reader authenticates the entire bounded AEAD image BEFORE release.
                        val bytes = read(image)
                        try {
                            fresh()
                            require(bytes.size.toLong() == image.byteSize && hash(bytes) == image.sha256)
                            writeFile(imagePath(image), bytes)
                        } finally { bytes.fill(0) }
                    }
                    fresh()
                    val manifest = OriginalExports.utf8("{\"format\":\"AIyou-open-records\",\"formatVersion\":1,\"schemaVersion\":1," +
                        "\"snapshotId\":" + q(preview.snapshot.id) + ",\"createdAtMillis\":" + preview.snapshot.createdAtMillis +
                        ",\"encrypted\":false,\"recordCount\":" + preview.snapshot.records.size + ",\"imageCount\":" + preview.images.size +
                        ",\"scope\":[\"currentOriginals\",\"manualTags\",\"eventDates\",\"originalImages\"],\"excluded\":[\"history\",\"tasks\",\"keys\",\"aiDerived\"]," +
                        "\"hashAlgorithm\":\"SHA-256\",\"manifestSelfHash\":false,\"images\":[" + preview.images.joinToString(",") { imageJson(it) } +
                        "],\"files\":[" + files.joinToString(",") + "]}\n")
                    try { writeFile("manifest.json", manifest) } finally { manifest.fill(0) }
                    fresh(); zip.finish(); fresh()
                } finally { zip.close() }
            }
        }
    }
    private fun imageJson(image: RecordPackageImage) = "{\"path\":" + q(imagePath(image)) +
        ",\"sourceId\":" + q(image.sourceId) + ",\"sourceRevision\":" + image.sourceRevision +
        ",\"attachmentId\":" + q(image.attachmentId) + ",\"mediaType\":" + q(image.mediaType) +
        ",\"byteSize\":" + image.byteSize + ",\"sha256\":" + q(image.sha256) + "}"
    private fun q(value: String) = OriginalExportCodec.quoted(value)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
