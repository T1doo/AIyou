package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.time.Instant

/**
 * Blocking IO contract. Implementations use the existing records database transaction,
 * not an independently updated in-memory authority. Invoke on an IO worker.
 * The trusted nonblocking clock is evaluated inside each authoritative transaction.
 */
interface SourceBoundTextRepository {
    /** Reads only the requested current, explicitly remote-approved original; no caller payload. */
    fun queue(intent: RemoteSendIntent, promptVersion: String, clock: () -> Instant): SourceQueueOutcome
    fun check(job: SourceBoundTextJob, clock: () -> Instant): SendDenial?
    /** Atomically rechecks persisted versions/permission and consumes the queued job before sending. */
    fun admit(job: SourceBoundTextJob, clock: () -> Instant): SendDenial?
    /** Validates original references, current permission and duplicate result inside the same write transaction. */
    fun commit(job: SourceBoundTextJob, candidate: DerivedTextCandidate, clock: () -> Instant): SourceCommitOutcome
    /** Marks only this unfinished job cancelled; cannot remove a committed result. */
    fun cancel(job: SourceBoundTextJob)
}
