package com.t1doo.aiyou.records

import android.app.Activity
import android.content.Intent
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import java.io.InputStream
import java.io.OutputStream

/** Legacy request range deliberately never enters AndroidX pending-result SavedState. */
class ActivityRecordPackagePicker(private val activity: Activity, saved: Bundle?) : RecordPackagePicker {
    private class Pending(val key: String, val callback: (SelectedRecordPackageDocument?) -> Unit)
    private val pending = mutableMapOf<Int, Pending>()
    private var next: Int = restoredCounter(saved)
    private var live = true
    private var canLaunch = false
    fun resumed() { if (live) canLaunch = true }
    fun paused() { canLaunch = false }
    fun save(bundle: Bundle) { canLaunch = false; bundle.putInt(COUNTER, next) }
    override fun launch(export: Boolean, requestKey: String, callback: (SelectedRecordPackageDocument?) -> Unit): Boolean {
        if (!live || !canLaunch || next !in 1..65535 || pending.values.any { it.key == requestKey }) return false
        val code = next++ // Consume before launch; success, cancellation and exceptions never reuse.
        pending[code] = Pending(requestKey, callback)
        val intent = Intent(if (export) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
        if (export) intent.putExtra(Intent.EXTRA_TITLE, "AIyou-encrypted-records.aiyoupk")
        return try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, code)
            true
        } catch (_: Exception) { pending.remove(code); false }
    }
    override fun cancel(requestKey: String) { pending.entries.removeAll { it.value.key == requestKey } }
    /** Results are valid while paused. Unknown reserved codes are dropped, never forwarded to registry. */
    fun result(code: Int, resultCode: Int, data: Intent?): Boolean {
        if (code !in 1..65535) return false
        val owned = pending.remove(code) ?: return true
        if (!live) return true
        val uri = if (resultCode == Activity.RESULT_OK) data?.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT } else null
        owned.callback(uri?.let { PlatformRecordPackageDocument(activity.contentResolver, it) })
        return true
    }
    fun destroy() { live = false; canLaunch = false; pending.clear() }
    internal val nextCode get() = next
    override fun toString() = "ActivityRecordPackagePicker(redacted)"
    companion object {
        internal const val COUNTER = "aiyou.recordPackage.nextLegacyRequest"
        @Suppress("DEPRECATION")
        private fun restoredCounter(saved: Bundle?): Int = if (saved == null) 1 else try {
            (saved.get(COUNTER) as? Int)?.takeIf { it in 1..65536 } ?: 65536
        } catch (_: Exception) { 65536 }
    }
}

class PlatformRecordPackageDocument(private val resolver: ContentResolver, private val uri: Uri) : SelectedRecordPackageDocument {
    init { require(uri.scheme == ContentResolver.SCHEME_CONTENT) }
    override fun openForRead(): InputStream = checkNotNull(resolver.openInputStream(uri))
    override fun openForWrite(): OutputStream = checkNotNull(resolver.openOutputStream(uri, "wt"))
    override fun toString() = "SelectedRecordPackageDocument(redacted)"
}
