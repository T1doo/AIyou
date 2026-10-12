package com.t1doo.aiyou.data

import androidx.room.withTransaction
import com.t1doo.aiyou.domain.AttachmentConflict
import com.t1doo.aiyou.model.AttachmentIntent
import com.t1doo.aiyou.model.AttachmentState
import com.t1doo.aiyou.model.LocalAttachment

internal class RoomAttachmentLedger(private val database: RecordsDatabase) : AttachmentLedger {
    private val dao get() = database.records()
    override suspend fun find(id: String): LocalAttachment? = dao.attachment(id)?.toModel()
    override suspend fun all(): List<LocalAttachment> = dao.attachments().map { it.toModel() }
    override suspend fun insert(value: LocalAttachment) = database.withTransaction {
        val intent = value.intent
        if (value.state != AttachmentState.WRITING) throw AttachmentConflict()
        // Reject a missing immutable source inside this transaction, before any
        // insert/file work. A deferred FK failure at COMMIT can leave the native
        // SQLCipher connection in a transaction after its Java stack is popped.
        if (!dao.revisionExists(intent.entryId, intent.revision)) throw AttachmentConflict()
        dao.insertAttachment(AttachmentEntity(intent.id, intent.entryId, intent.revision,
            intent.mediaType, intent.sha256, intent.byteSize, value.state.name))
    }
    override suspend fun transition(expected: LocalAttachment, next: AttachmentState): LocalAttachment = database.withTransaction {
        if (find(expected.intent.id) != expected) throw AttachmentConflict()
        val permitted = when (expected.state) {
            AttachmentState.WRITING -> next == AttachmentState.STAGED || next == AttachmentState.FAILED
            AttachmentState.FAILED -> next == AttachmentState.WRITING
            AttachmentState.STAGED -> next == AttachmentState.READY || next == AttachmentState.CORRUPT
            AttachmentState.READY -> next == AttachmentState.CORRUPT
            AttachmentState.CORRUPT -> false
        }
        if (!permitted || dao.advanceAttachment(expected.intent.id, expected.state.name, next.name) != 1)
            throw AttachmentConflict()
        checkNotNull(find(expected.intent.id))
    }
}

private fun AttachmentEntity.toModel() = LocalAttachment(
    AttachmentIntent(id, entryId, revision, mediaType, sha256, byteSize), AttachmentState.valueOf(state),
).also { EncryptedAttachmentStore.validate(it.intent) }
