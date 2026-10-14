package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.*
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Selected content document only, no deletion or persistent permission capability. */
interface SelectedRecordPackageDocument {
    fun openForRead(): InputStream
    fun openForWrite(): OutputStream
}
class RecordPackageCancelled : IllegalStateException("Record package operation retired")
object RecordPackageIo {
    fun read(document: SelectedRecordPackageDocument, active: () -> Boolean): ByteArray {
        if (!active()) throw RecordPackageCancelled()
        val buffer = ByteArray(8192)
        val out = object : ByteArrayOutputStream() { fun wipe() { buf.fill(0); reset() } }
        try {
            document.openForRead().use { input ->
                var zeroReads = 0
                while (true) {
                    if (!active()) throw RecordPackageCancelled()
                    val allowed = minOf(buffer.size, RecordPackages.MAX_ENVELOPE + 1 - out.size())
                    check(allowed > 0)
                    val count = input.read(buffer, 0, allowed)
                    if (count < 0) break
                    if (count == 0) { check(++zeroReads <= 16); continue }
                    require(count <= allowed); zeroReads = 0; out.write(buffer, 0, count)
                    require(out.size() <= RecordPackages.MAX_ENVELOPE)
                }
            }
            if (!active()) throw RecordPackageCancelled()
            return out.toByteArray()
        } finally { buffer.fill(0); out.wipe() }
    }
    fun writeAndVerify(document: SelectedRecordPackageDocument, payload: EncryptedRecordPackage, active: () -> Boolean) {
        val bytes = payload.copyBytes()
        try {
            if (!active()) throw RecordPackageCancelled()
            document.openForWrite().use { output ->
                var at = 0
                while (at < bytes.size) {
                    if (!active()) throw RecordPackageCancelled()
                    val count = minOf(8192, bytes.size - at); output.write(bytes, at, count); at += count
                }
                if (!active()) throw RecordPackageCancelled()
                output.flush()
            }
            val readback = read(document, active)
            try { check(MessageDigest.isEqual(bytes, readback)) } finally { readback.fill(0) }
        } finally { bytes.fill(0) }
    }
}

/** Picker owns callbacks in memory, never the ActivityResultRegistry pending SavedState. */
interface RecordPackagePicker {
    fun launch(export: Boolean, requestKey: String, callback: (SelectedRecordPackageDocument?) -> Unit): Boolean
    fun cancel(requestKey: String)
}
