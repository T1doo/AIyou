package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

/** Borrowed blocking IO; capture all selected current originals and tags atomically, never send. */
interface OriginalExportRepository {
    fun capture(sources: List<SourceVersion>, snapshotId: String, createdAtMillis: Long, refreshCurrent: Boolean = false): OriginalExportSnapshot
    /** Exact body/metadata/tag-version check from one transaction; false means a changed source. */
    fun isCurrent(snapshot: OriginalExportSnapshot): Boolean
    /** Explicit optional plaintext scope. Same SQL snapshot, no images or side effects. */
    fun captureActivities(snapshot: OriginalExportSnapshot): RecordPackageActivities {
        throw UnsupportedOperationException("Open task export unavailable")
    }
    fun areActivitiesCurrent(snapshot: OriginalExportSnapshot, activities: RecordPackageActivities): Boolean {
        throw UnsupportedOperationException("Open task export unavailable")
    }
}
class OriginalExportConflict : IllegalStateException("Selected export source changed")
class OriginalExportStorageFailure : IllegalStateException("Encrypted export source unavailable")
class OriginalExportTooLarge : IllegalStateException("Selected export scope exceeds bound")
class OriginalExportEmptyScope : IllegalStateException("No saved content in selected export scope")
