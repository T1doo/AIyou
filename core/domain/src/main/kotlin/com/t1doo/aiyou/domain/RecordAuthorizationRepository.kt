package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.time.Instant

/** Explicit permission management, separate from the send-only and local-policy capabilities.
 * Blocking IO borrows the existing encrypted records owner. No key resolution/client/job/network.
 * This is not a connection-draft catalog: no incomplete connection or invented secretRef is stored.
 */
interface RecordAuthorizationRepository {
    val supportsConnectionReview: Boolean get() = false
    /** Same encrypted owner checks the entire chosen catalog version and current local reference. No resolve/send. */
    fun previewConnectionAuthorization(expected: RecordAuthorizationSource, configuration: ModelConnectionConfiguration,
        purpose: TextPurpose, clock: () -> Instant): RecordAuthorizationPreview = throw RecordAuthorizationConflict()
    /** Pure receipt/current-state read. Missing receipt is null; never applies or renews an uncertain decision. */
    fun confirmAuthorization(request: RecordAuthorizationChange, clock: () -> Instant): RecordAuthorizationReceipt? = null
    fun readAuthorization(source: SourceVersion): RecordAuthorizationSnapshot
    fun previewAuthorization(expected: RecordAuthorizationSource, purpose: TextPurpose, clock: () -> Instant): RecordAuthorizationPreview
    fun changeAuthorization(request: RecordAuthorizationChange, clock: () -> Instant): RecordAuthorizationReceipt
}

class RecordAuthorizationConflict : IllegalStateException("Record authorization or source changed")
class RecordAuthorizationNotCurrent : IllegalStateException("Consent interval is not current")
class RecordAuthorizationStorageFailure : IllegalStateException("Record authorization storage unavailable")
