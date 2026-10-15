package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

/** Encrypted draft metadata + authenticated encrypted image staging; no URI or plaintext files. */
interface CaptureRepository {
    suspend fun current(): CaptureDraft
    suspend fun edit(expected: CaptureDraft,text: String): CaptureDraft
    suspend fun add(expected: CaptureDraft,intent: AttachmentIntent,bytes: ByteArray): CaptureDraft
    suspend fun image(expected: CaptureDraft,id: String): ByteArray
    suspend fun remove(draftId: String,imageId: String): CaptureDraft
    suspend fun discard(draftId: String): CaptureDraft
    suspend fun save(expected: CaptureDraft,capturedAtMillis: Long,zone: String): CaptureSaveResult
    suspend fun recover()
}
class CaptureConflict : IllegalStateException("Capture draft changed")
class CaptureStorageFailure : IllegalStateException("Capture draft storage unavailable")
