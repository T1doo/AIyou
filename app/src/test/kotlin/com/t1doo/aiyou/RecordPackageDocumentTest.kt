package com.t1doo.aiyou

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.t1doo.aiyou.data.JcaRecordPackageCipher
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordPackageDocumentTest {
    @Test fun actualContentProviderWritesOnlyCiphertextAndReadsEntireSelectedPackageWithoutDeletion() {
        fixture { file, provider, document ->
            file.writeText("synthetic-old-file".repeat(1000)); val cipher = JcaRecordPackageCipher(); val payload = payload()
            try {
                RecordPackageIo.writeAndVerify(document, payload) { true }
                val bytes = RecordPackageIo.read(document) { true }
                assertFalse(bytes.toString(Charsets.UTF_8).contains("合成完整原文"))
                assertEquals("合成完整原文😀", cipher.open(bytes, PASSWORD.toCharArray()).records.single().originalText)
                assertEquals(listOf("wt", "r", "r"), provider.modes)
                assertEquals(0, provider.deletes); assertFalse(document.toString().contains("private-uri"))
            } finally { payload.release() }
        }
    }
    @Test fun actualProviderDenialAndForeignSchemesPreserveSelectedExistingFile() {
        fixture { file, provider, document ->
            file.writeText("synthetic-existing"); provider.denied = true; val payload = payload()
            try { assertThrows(SecurityException::class.java) { RecordPackageIo.writeAndVerify(document, payload) { true } } }
            finally { payload.release() }
            assertEquals("synthetic-existing", file.readText()); assertEquals(0, provider.deletes)
            for (uri in listOf("file:///synthetic", "https://example.invalid/synthetic"))
                assertThrows(IllegalArgumentException::class.java) { PlatformRecordPackageDocument(RuntimeEnvironment.getApplication().contentResolver, Uri.parse(uri)) }
        }
    }
    private fun payload() = JcaRecordPackageCipher().seal(OriginalExportSnapshot(UUID.randomUUID().toString(), 10,
        listOf(OriginalExportRecord(StoredEntry("one", 1, "合成完整原文😀", 42, "UTC", ProcessingPolicy.LOCAL_ONLY),
            OriginalExports.hash("合成完整原文😀"), ManualTagSnapshot("one", 0, emptyList())))), PASSWORD.toCharArray())
    private fun fixture(block: (File, Provider, PlatformRecordPackageDocument) -> Unit) {
        val app = RuntimeEnvironment.getApplication(); val file = File(app.cacheDir, "synthetic-encrypted-package")
        val provider = Provider(file); provider.attachInfo(app, ProviderInfo().apply { authority = "aiyou.synthetic.package" })
        ShadowContentResolver.registerProviderInternal("aiyou.synthetic.package", provider)
        try { block(file, provider, PlatformRecordPackageDocument(app.contentResolver, Uri.parse("content://aiyou.synthetic.package/private-uri"))) }
        finally { if (file.exists()) check(file.delete()) } // Owned synthetic test fixture only.
    }
    private class Provider(private val file: File) : ContentProvider() {
        var denied = false; var deletes = 0; val modes = mutableListOf<String>()
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            modes += mode; if (denied) throw SecurityException("Synthetic provider detail")
            return ParcelFileDescriptor.open(file, if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY else
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Outside test provider")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deletes++; error("Package flow must never delete") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Outside test provider")
    }
    companion object { const val PASSWORD = " synthetic provider package password! " }
}
