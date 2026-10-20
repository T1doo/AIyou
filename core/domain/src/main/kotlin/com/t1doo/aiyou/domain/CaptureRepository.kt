package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

/** Encrypted draft metadata + authenticated encrypted image staging; no URI or plaintext files. */
interface CaptureRepository {
    val mutationGate: CaptureDraftGate? get() = null
    /** Strict read: never creates, recovers or replaces a draft. */
    suspend fun peek(): CaptureDraft? { throw UnsupportedOperationException("Capture peek unavailable") }
    suspend fun addAudio(request: CaptureDraftGate.Reservation, bytes: ByteArray): CaptureDraft {
        throw UnsupportedOperationException("Capture audio unavailable")
    }
    suspend fun reconcileAudio(request: CaptureDraftGate.Reservation): CaptureDraft {
        throw UnsupportedOperationException("Capture audio unavailable")
    }
    /** Explicit user abandonment of only this reservation's base/incomplete intent, never READY. */
    suspend fun abandonAudio(request: CaptureDraftGate.Reservation): CaptureDraft {
        throw UnsupportedOperationException("Capture audio unavailable")
    }
    suspend fun audio(expected: CaptureDraft): ByteArray { throw UnsupportedOperationException("Capture audio unavailable") }
    suspend fun removeAudio(expected: CaptureDraft): CaptureDraft { throw UnsupportedOperationException("Capture audio unavailable") }
    val supportsProcessingChoice: Boolean get() = false
    /** This exact draft only; never grants remote consent or changes a saved entry. */
    suspend fun changePolicy(expected: CaptureDraft, policy: ProcessingPolicy): CaptureDraft {
        throw UnsupportedOperationException("Capture processing choice unavailable")
    }
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
