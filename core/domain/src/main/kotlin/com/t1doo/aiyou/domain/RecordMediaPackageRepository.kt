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
    suspend fun isCurrent(preview: RecordMediaPreview): Boolean
    suspend fun seal(preview: RecordMediaPreview, password: CharArray): PreparedRecordMediaPackage
    /** Fully authenticates the input before releasing a preview; retains only a revocable read handle/key. */
    suspend fun open(source: () -> InputStream, password: CharArray): VerifiedRecordPackage
    suspend fun restore(verified: VerifiedRecordPackage, newIds: List<String>, importedAtMillis: Long): RecordPackageRestoreResult
    /** Before records become visible, clean unfinished, registered file batches or fail explicitly. */
    suspend fun recover()
}
