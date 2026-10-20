package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

/** Read-only preview. Production import additionally checks an internal authenticated capability. */
interface VerifiedRecordPackage {
    val manifest: RecordPackageManifest
    val records: List<RecordPackageOriginal>
    val digest: String
    val images: List<com.t1doo.aiyou.model.RecordPackageImage> get() = emptyList()
    val audios: List<RecordPackageAudio> get() = emptyList()
    val media: List<RecordPackageAttachment> get() = images+audios
    val activities: RecordPackageActivities? get() = null
    val includesHistory: Boolean get() = false
    val editDrafts: List<OriginalEditDraft>? get() = null
    fun release() {}
}

interface RecordPackageCipher {
    /** Consumes and clears the supplied independent passphrase on every path. */
    fun seal(snapshot: OriginalExportSnapshot, password: CharArray): EncryptedRecordPackage
    /** No preview or import capability is released until full authentication and bounded parsing. */
    fun open(envelope: ByteArray, password: CharArray): VerifiedRecordPackage
}

interface RecordPackageRepository {
    fun restore(verified: VerifiedRecordPackage, newIds: List<String>, importedAtMillis: Long): RecordPackageRestoreResult
    fun origins(): Map<String, RecordPackageOrigin>
}

class RecordPackageInvalid : IllegalArgumentException("Record package authentication or format failed")
class RecordPackageConflict : IllegalStateException("Record package identity conflicts")
class RecordPackageStorageFailure : IllegalStateException("Record package local storage unavailable")

/** A bounded inventory was refused in full; no item was silently omitted. */
enum class RecordPackageActivityBound { FORMAL_TASKS, SAVED_DRAFTS, PROPOSAL_LEDGER }
class RecordPackageActivityLimit(val bound: RecordPackageActivityBound) :
    IllegalStateException("Saved activity inventory exceeds $bound bound")
