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
