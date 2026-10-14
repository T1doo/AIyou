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

@Entity(tableName = "remote_source_access", foreignKeys = [ForeignKey(
    entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], deferred = true,
)])
internal data class RemoteSourceAccessEntity(
    @PrimaryKey val entryId: String,
    val generation: Long,
    val deleted: Boolean,
    val connection: ByteArray?,
    val consent: ByteArray?,
)

@Entity(tableName = "remote_text_jobs", foreignKeys = [ForeignKey(
    entity = RevisionEntity::class, parentColumns = ["entryId", "revision"],
    childColumns = ["entryId", "sourceRevision"], deferred = true,
)], indices = [Index(value = ["bindingKey"], unique = true), Index(value = ["entryId", "sourceRevision"])])
internal data class RemoteTextJobEntity(
    @PrimaryKey val id: String,
    val bindingKey: String,
    val entryId: String,
    val sourceRevision: Int,
    val generation: Long,
    val purpose: String,
    val promptVersion: String,
    val connection: ByteArray,
    val consent: ByteArray,
    val state: String,
    val sourceHash: String,
)

@Entity(tableName = "text_annotations", foreignKeys = [ForeignKey(
    entity = RemoteTextJobEntity::class, parentColumns = ["id"], childColumns = ["jobId"], deferred = true,
), ForeignKey(
    entity = RevisionEntity::class, parentColumns = ["entryId", "revision"],
    childColumns = ["entryId", "sourceRevision"], deferred = true,
)], indices = [Index(value = ["entryId", "sourceRevision"])])
internal data class TextAnnotationEntity(
    @PrimaryKey val jobId: String,
    val entryId: String,
    val sourceRevision: Int,
    val generation: Long,
    val candidate: ByteArray,
    val resultHash: String,
    val createdAtMillis: Long,
)

@Entity(tableName = "entry_manual_tags", foreignKeys = [ForeignKey(
    entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"],
)])
internal data class ManualTagEntity(
    @PrimaryKey val entryId: String,
    val version: Long,
    val lastMutationId: String,
    val ciphertext: ByteArray,
)

@Entity(tableName = "manual_tasks")
internal data class ManualTaskEntity(
    @PrimaryKey val id: String,
    val version: Long,
    val lastMutationId: String,
    val ciphertext: ByteArray,
)
