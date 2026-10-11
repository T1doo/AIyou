package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.AttachmentIntent
import com.t1doo.aiyou.model.LocalAttachment
import java.io.InputStream

/** Local storage only. Call on an IO dispatcher; callers retain the original source on failure. */
interface AttachmentStore {
    suspend fun save(intent: AttachmentIntent, source: () -> InputStream): LocalAttachment
    suspend fun list(entryId: String, revision: Int): List<LocalAttachment>
    /** No plaintext is returned before the complete AEAD tag, hash and size have been checked. */
    suspend fun read(id: String): ByteArray
    suspend fun recover()
}

class AttachmentConflict : IllegalStateException("Attachment intent changed")
class AttachmentIntegrityFailure : IllegalStateException("Attachment integrity check failed")
class AttachmentBusy : IllegalStateException("Attachment store is busy")
