package com.t1doo.aiyou.records

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.io.OutputStream

/** Access only the URI the user selected; no persistent grant or broad storage access. */
class PlatformExportDocument(private val resolver: ContentResolver, private val uri: Uri) : SelectedExportDocument {
    init { require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Export requires a selected document" } }
    override fun openForWrite(): OutputStream = checkNotNull(resolver.openOutputStream(uri, "wt")) { "Export output unavailable" }
    override fun openForRead(): InputStream = checkNotNull(resolver.openInputStream(uri)) { "Export input unavailable" }
    override fun delete(): Boolean = DocumentsContract.deleteDocument(resolver, uri)
    override fun toString() = "PlatformExportDocument(redacted)"
}
