package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.OriginalExportPayload
import java.io.InputStream
import java.io.OutputStream

/** Only a destination explicitly returned by the end user's document selector. */
interface SelectedExportDocument {
    fun openForWrite(): OutputStream
    fun openForRead(): InputStream
    /** Invoked only after a separate explicit user deletion confirmation, never automatically. */
    fun delete(): Boolean
}
class OriginalExportCancelled : IllegalStateException("Export cancelled")
class OriginalExportFileFailure : IllegalStateException("Export file could not be confirmed")

/** Bounded UTF-8 stream write, close acknowledgement and full readback of that SAME document.
 * Generic document providers are not transactional: failure/cancel can leave an empty/partial file.
 */
object OriginalExportIo {
    /** Hash and exact length of the entire ZIP, including its central directory; 8 KiB readback. */
    suspend fun writeZipAndVerify(document: SelectedExportDocument,
        payload: com.t1doo.aiyou.domain.PreparedRecordMediaPackage, active: () -> Boolean) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        fun checkActive() { if (!active()) throw OriginalExportCancelled() }
        checkActive()
        document.openForWrite().use { destination ->
            val output = object : OutputStream() {
                override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    checkActive()
                    if (size + length > com.t1doo.aiyou.domain.OpenRecordZipCodec.MAX_FILE_BYTES) throw OriginalExportFileFailure()
                    destination.write(bytes, offset, length); digest.update(bytes, offset, length); size += length
                    checkActive()
                }
                override fun flush() { checkActive(); destination.flush(); checkActive() }
            }
            payload.writeTo(output, active); output.flush()
        }
        checkActive()
        val expected = digest.digest()
        val actual = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        try {
            var read = 0L; var zeros = 0
            document.openForRead().use { input ->
                while (true) {
                    checkActive()
                    val n = input.read(buffer)
                    checkActive()
                    if (n < 0) break
                    if (n == 0) { if (++zeros > 16) throw OriginalExportFileFailure(); continue }
                    zeros = 0; read += n
                    if (read > size) throw OriginalExportFileFailure()
                    actual.update(buffer, 0, n)
                }
                if (read != size || !java.security.MessageDigest.isEqual(expected, actual.digest())) throw OriginalExportFileFailure()
            }
            checkActive()
        } finally { buffer.fill(0); expected.fill(0) }
    }
    fun writeAndVerify(document: SelectedExportDocument, payload: OriginalExportPayload, active: () -> Boolean) {
        val bytes = payload.copyBytes()
        fun checkActive() { if (!active()) throw OriginalExportCancelled() }
        try {
            checkActive()
            document.openForWrite().use { out ->
                var offset = 0
                while (offset < bytes.size) {
                    checkActive()
                    val count = minOf(8192, bytes.size - offset)
                    out.write(bytes, offset, count); offset += count
                }
                checkActive(); out.flush(); checkActive()
            }
            checkActive()
            document.openForRead().use { input ->
                val chunk = ByteArray(8192)
                try {
                    var offset = 0; var zeroReads = 0
                    while (true) {
                        checkActive()
                        val count = input.read(chunk)
                        if (count < 0) break
                        if (count == 0) {
                            if (++zeroReads > 16) throw OriginalExportFileFailure()
                            continue
                        }
                        zeroReads = 0
                        if (count > bytes.size - offset) throw OriginalExportFileFailure()
                        for (index in 0 until count) if (chunk[index] != bytes[offset + index]) throw OriginalExportFileFailure()
                        offset += count
                    }
                    if (offset != bytes.size) throw OriginalExportFileFailure()
                } finally { chunk.fill(0) }
            }
            checkActive()
        } catch (cancelled: OriginalExportCancelled) { throw cancelled }
        catch (_: Exception) { throw OriginalExportFileFailure() }
        finally { bytes.fill(0) }
    }
}
