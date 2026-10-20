package com.t1doo.aiyou.domain

import java.io.InputStream

/** Portable logical records, including trash. Device configuration and remote authority are excluded. */
data class FullBackupSummary(val backupId: String, val createdAtMillis: Long, val records: Int,
    val trash: Int, val revisions: Int, val images: Int, val imageBytes: Long, val editDrafts: Int,
    val tasksAndProposals: Int, val memoriesAndReviews: Int, val captureDrafts: Int,
    val deletionProofs: Int, val audioClips:Int=0, val audioBytes:Long=0)

interface FullBackupSnapshot {
    val summary: FullBackupSummary
    fun release()
}
interface VerifiedFullBackup : FullBackupSnapshot {
    val digest: String
}
data class FullBackupRestored(val summary: FullBackupSummary, val replayed: Boolean)

/** Borrowed from the same draining records owner. Restore is only into a strictly empty logical target. */
interface FullBackupRepository {
    suspend fun capture(backupId: String, createdAtMillis: Long): FullBackupSnapshot
    suspend fun seal(snapshot: FullBackupSnapshot, password: CharArray): PreparedRecordMediaPackage
    suspend fun open(source: () -> InputStream, password: CharArray): VerifiedFullBackup
    /** Read-only stable receipt probe; false never authorizes initialization or import. */
    fun completed(backup:VerifiedFullBackup):Boolean = false
    suspend fun restore(backup: VerifiedFullBackup, active: () -> Boolean): FullBackupRestored
}
