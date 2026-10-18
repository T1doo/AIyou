package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.io.InputStream
import java.io.OutputStream

/** One bounded image at a time; never an in-memory representation of the entire package. */
interface PreparedRecordMediaPackage {
    suspend fun writeTo(output: OutputStream, active: () -> Boolean)
    fun release()
}
interface RecordMediaPackageRepository {
    suspend fun preview(snapshot: OriginalExportSnapshot): RecordMediaPreview
    suspend fun previewActivities(snapshot: OriginalExportSnapshot): RecordMediaPreview {
        throw UnsupportedOperationException("Task migration unavailable")
    }
    /** Explicit history scope; missing versions or media refuse the entire snapshot. */
    suspend fun previewHistory(snapshot: OriginalExportSnapshot, includeActivities: Boolean = false): RecordMediaPreview {
        throw UnsupportedOperationException("Original history migration unavailable")
    }
    suspend fun previewEditDrafts(snapshot: OriginalExportSnapshot, includeActivities: Boolean = false): RecordMediaPreview {
        throw UnsupportedOperationException("Original edit draft migration unavailable")
    }
    suspend fun previewOpenZip(snapshot: OriginalExportSnapshot, active: () -> Boolean): RecordMediaPreview = preview(snapshot)
    suspend fun isCurrent(preview: RecordMediaPreview): Boolean
    suspend fun seal(preview: RecordMediaPreview, password: CharArray): PreparedRecordMediaPackage
    /** Explicitly confirmed plaintext ZIP; current originals + every current image, no restore capability. */
    suspend fun exportOpenZip(preview: RecordMediaPreview): PreparedRecordMediaPackage {
        throw UnsupportedOperationException("Open image export unavailable")
    }
    /** One revocable preview read. Caller wipes bytes after decoding a bounded thumbnail. */
    suspend fun readOpenImage(preview: RecordMediaPreview, image: RecordPackageImage): ByteArray {
        throw UnsupportedOperationException("Open image preview unavailable")
    }
    /** Fully authenticates the input before releasing a preview; retains only a revocable read handle/key. */
    suspend fun open(source: () -> InputStream, password: CharArray): VerifiedRecordPackage
    suspend fun restore(verified: VerifiedRecordPackage, newIds: List<String>, importedAtMillis: Long): RecordPackageRestoreResult
    /** Before records become visible, clean unfinished, registered file batches or fail explicitly. */
    suspend fun recover()
}
