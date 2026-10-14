package com.t1doo.aiyou

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.*
import com.t1doo.aiyou.domain.OriginalExportCodec
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlatformExportDocumentTest {
    @Test fun actualContentResolverTruncatesSelectedSyntheticFileAndVerifiesExactUtf8Readback() {
        fixture { file, provider, document ->
            file.writeText("synthetic-old-file".repeat(5000))
            val payload = payload()
            try {
                val expected = payload.copyBytes(); OriginalExportIo.writeAndVerify(document, payload) { true }
                assertArrayEquals(expected, file.readBytes()); assertEquals(listOf("wt", "r"), provider.modes)
                assertFalse(document.toString().contains("sensitive-id")); assertEquals(0, provider.deletes)
            } finally { payload.release() }
        }
    }
    @Test fun actualProviderPermissionDenialPreservesExistingFileAndSelectedDeleteIsExplicit() {
        fixture { file, provider, document ->
            file.writeText("synthetic-original-file"); provider.denied = true; val payload = payload()
            try { assertThrows(OriginalExportFileFailure::class.java) { OriginalExportIo.writeAndVerify(document, payload) { true } } }
            finally { payload.release() }
            assertEquals("synthetic-original-file", file.readText()); assertEquals(0, provider.deletes)
            provider.denied = false; assertTrue(document.delete()); assertEquals(1, provider.deletes); assertFalse(file.exists())
        }
    }
    @Test fun fileAndNetworkSchemesCannotBecomeSelectedDocuments() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        for (uri in listOf("file:///synthetic.jsonl", "https://example.invalid/synthetic.jsonl"))
            assertThrows(IllegalArgumentException::class.java) { PlatformExportDocument(resolver, Uri.parse(uri)) }
    }
    private fun payload() = OriginalExportCodec.render(OriginalExportSnapshot("synthetic", 1, listOf(OriginalExportRecord(
        StoredEntry("one", 1, "合成导出🙂\r\n完整正文", 42, "Asia/Shanghai", ProcessingPolicy.LOCAL_ONLY),
        OriginalExports.hash("合成导出🙂\r\n完整正文"), ManualTagSnapshot("one", 1, listOf("合成标签"))))), OriginalExportFormat.JSONL)
    private fun fixture(block: (File, Provider, PlatformExportDocument) -> Unit) {
        val app = RuntimeEnvironment.getApplication(); val file = File(app.cacheDir, "synthetic-export-provider.jsonl")
        val provider = Provider(file); provider.attachInfo(app, ProviderInfo().apply { authority = "aiyou.synthetic.export" })
        ShadowContentResolver.registerProviderInternal("aiyou.synthetic.export", provider)
        try { block(file, provider, PlatformExportDocument(app.contentResolver, Uri.parse("content://aiyou.synthetic.export/document/sensitive-id"))) }
        finally { if (file.exists()) check(file.delete()) }
    }
    private class Provider(private val file: File) : ContentProvider() {
        var denied = false; var deletes = 0; val modes = mutableListOf<String>()
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/x-ndjson"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            modes.add(mode); if (denied) throw SecurityException("synthetic-provider-sensitive-detail")
            return ParcelFileDescriptor.open(file, if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY
                else ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            check(method == "android:deleteDocument"); deletes++; check(file.delete()); return Bundle()
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Outside test provider")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Requires DocumentsContract")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Outside test provider")
    }
}
