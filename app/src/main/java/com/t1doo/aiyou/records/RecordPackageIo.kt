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
    /** Ownership is checked between provider reads. A blocking provider call cannot be forcibly interrupted. */
    fun openRead(document: SelectedRecordPackageDocument, active: () -> Boolean): InputStream {
        if (!active()) throw RecordPackageCancelled()
        return object : java.io.FilterInputStream(document.openForRead()) {
            private fun checkOwner() { if (!active()) throw RecordPackageCancelled() }
            override fun read(): Int { checkOwner(); return super.read().also { checkOwner() } }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                checkOwner(); return super.read(bytes,offset,length).also { checkOwner() }
            }
        }
    }
    /** Compare streaming ciphertext digest + exact byte count, with a fixed 8 KiB readback buffer. */
    suspend fun writeMediaAndVerify(document: SelectedRecordPackageDocument,
        payload: com.t1doo.aiyou.domain.PreparedRecordMediaPackage, maximumBytes:Long=RecordMediaPackages.MAX_FILE_BYTES, active: () -> Boolean) {
        val written = MessageDigest.getInstance("SHA-256"); var size = 0L
        if (!active()) throw RecordPackageCancelled()
        document.openForWrite().use { destination ->
            val output = object : OutputStream() {
                override fun write(value: Int) { write(byteArrayOf(value.toByte()),0,1) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (!active()) throw RecordPackageCancelled()
                    require(size + length <= maximumBytes)
                    destination.write(bytes,offset,length); written.update(bytes,offset,length); size += length
                }
                override fun flush() = destination.flush()
            }
            payload.writeTo(output,active); output.flush()
        }
        val expected = written.digest(); val actual = MessageDigest.getInstance("SHA-256"); var read = 0L
        val buffer = ByteArray(8192)
        try {
            document.openForRead().use { input ->
                while (true) {
                    if (!active()) throw RecordPackageCancelled()
                    val n = input.read(buffer); if (n < 0) break; require(n > 0)
                    read += n; require(read <= size && read <= maximumBytes); actual.update(buffer,0,n)
                }
            }
            check(active() && read == size && MessageDigest.isEqual(expected,actual.digest()))
        } finally { buffer.fill(0); expected.fill(0) }
    }
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
