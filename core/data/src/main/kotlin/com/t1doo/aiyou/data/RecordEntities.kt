package com.t1doo.aiyou.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "entries", foreignKeys = [ForeignKey(
    entity = RevisionEntity::class, parentColumns = ["entryId", "revision"],
    childColumns = ["id", "currentRevision"], deferred = true,
)], indices = [Index(value = ["id", "currentRevision"])])
internal data class EntryEntity(
    @PrimaryKey val id: String,
    val currentRevision: Int,
    val capturedAtMillis: Long,
    val captureTimeZone: String,
    val privacyPolicy: String,
)

@Entity(tableName = "entry_revisions", primaryKeys = ["entryId", "revision"], foreignKeys = [ForeignKey(
    entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], deferred = true,
)], indices = [Index(value = ["entryId"])])
internal data class RevisionEntity(
    val entryId: String,
    val revision: Int,
    val originalText: String,
    val contentHash: String,
    val createdAtMillis: Long,
)

@Entity(tableName = "capture_draft")
internal data class DraftEntity(@PrimaryKey val slot: Int = 1, val id: String, val version: Long, val text: String)

@Entity(tableName = "local_jobs", foreignKeys = [ForeignKey(
    entity = RevisionEntity::class, parentColumns = ["entryId", "revision"],
    childColumns = ["entryId", "sourceRevision"], deferred = true,
)], indices = [Index(value = ["entryId", "sourceRevision"])])
internal data class LocalJobEntity(
    @PrimaryKey val id: String,
    val entryId: String,
    val sourceRevision: Int,
    val operation: String = "LOCAL_INDEX",
    val state: String = "PENDING",
)

internal data class EntryRow(
    val id: String, val currentRevision: Int, val capturedAtMillis: Long,
    val captureTimeZone: String, val privacyPolicy: String,
    val originalText: String, val contentHash: String,
)

@Entity(tableName = "attachments", foreignKeys = [ForeignKey(
    entity = RevisionEntity::class, parentColumns = ["entryId", "revision"],
    childColumns = ["entryId", "revision"], deferred = true,
)], indices = [Index(value = ["entryId", "revision"])])
internal data class AttachmentEntity(
    @PrimaryKey val id: String,
    val entryId: String,
    val revision: Int,
    val mediaType: String,
    val sha256: String,
    val byteSize: Long,
    val state: String,
)
