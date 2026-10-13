package com.t1doo.aiyou.model

import java.util.Collections

/** Coherent persisted metadata for explicit access changes; contains no original or resolved credential. */
class SourceAccessSnapshot(val state: RemoteSendState, val generation: Long) {
    override fun toString() = "SourceAccessSnapshot(<redacted>, generation=$generation)"
}

/** Half-open Unicode code-point offsets into one immutable original revision. */
data class OriginalTextReference(val source: SourceVersion, val start: Int, val end: Int, val quote: String) {
    override fun toString() = "OriginalTextReference(<redacted>)"
}

/** Metadata is untrusted at a repository boundary; its stored job and original must match in full. */
class SourceBoundTextJob(
    val id: String,
    val generation: Long,
    val promptVersion: String,
    val intent: RemoteSendIntent,
    val state: RemoteSendState,
    val originalText: String,
    val contentHash: String,
    val capturedAtMillis: Long,
    val captureTimeZone: String,
) {
    override fun toString() = "SourceBoundTextJob(<redacted>, generation=$generation)"
}

/** An annotation candidate, never a confirmed fact or an executable task. */
class DerivedTextCandidate(val text: String, references: List<OriginalTextReference>) {
    val references: List<OriginalTextReference> = Collections.unmodifiableList(ArrayList(references))
    override fun toString() = "DerivedTextCandidate(<redacted>)"
}

class StoredTextAnnotation(
    val jobId: String,
    val source: SourceVersion,
    val generation: Long,
    val candidate: DerivedTextCandidate,
    val createdAtMillis: Long,
) {
    override fun toString() = "StoredTextAnnotation(<redacted>, generation=$generation)"
}

enum class SourceCommitDenial {
    SOURCE_CHANGED, SOURCE_DELETED, POLICY_BLOCKED, AUTHORIZATION_CHANGED, CONSENT_NOT_CURRENT,
    JOB_INVALID, JOB_NOT_ADMITTED, RESULT_CONFLICT, INVALID_REFERENCE, INVALID_RESULT, STORAGE_UNAVAILABLE,
}

sealed interface SourceQueueOutcome {
    class Queued(val job: SourceBoundTextJob) : SourceQueueOutcome
    class AlreadyCommitted(val job: SourceBoundTextJob, val annotation: StoredTextAnnotation) : SourceQueueOutcome
    data class Denied(val reason: SourceCommitDenial) : SourceQueueOutcome
}

sealed interface SourceCommitOutcome {
    class Committed(val annotation: StoredTextAnnotation, val alreadyCommitted: Boolean) : SourceCommitOutcome
    data class Denied(val reason: SourceCommitDenial) : SourceCommitOutcome
}
