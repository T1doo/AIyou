package com.t1doo.aiyou.model

import java.time.Instant
import java.util.UUID

/** A visible original's frozen identity, captured before an asynchronous permission read. */
data class RecordAuthorizationSource(val source: SourceVersion, val sourceHash: String, val generation: Long) {
    init { require(sourceHash.matches(Regex("[0-9a-f]{64}")) && generation >= 0) }
    override fun toString() = "RecordAuthorizationSource(redacted, generation=$generation)"
}

/** Metadata only. A connection reference is not proof that a credential exists or a sender is ready. */
data class RecordAuthorizationSnapshot(val binding: RecordAuthorizationSource, val policy: ProcessingPolicy,
    val connection: ProviderConnection?, val consent: ConsentGrant?) {
    override fun toString() = "RecordAuthorizationSnapshot(redacted)"
}

enum class RecordCredentialReadiness { NOT_CONFIGURED, UNVERIFIED, LOCAL_REFERENCE_CURRENT }

class RecordAuthorizationPreview(val snapshot: RecordAuthorizationSnapshot, val purpose: TextPurpose,
    val authorizationCurrent: Boolean, val selectedConfiguration: ModelConnectionConfiguration? = null,
    val capabilityProfile: ModelCapabilityProfile? = null) {
    val targetConnection: ProviderConnection? = selectedConfiguration?.let { c ->
        require((if (purpose == TextPurpose.CONVERSATION) ModelConfigurationPurpose.CONVERSATION else ModelConfigurationPurpose.ORGANIZATION) in c.details.purposes)
        ProviderConnection(c.id, c.version, c.details.apiRoot, c.details.protocol, c.details.modelId, requireNotNull(c.secretRef))
    } ?: snapshot.connection
    init { require(capabilityProfile == null || capabilityProfile.connection == selectedConfiguration) }
    val credentialReadiness = when {
        targetConnection == null -> RecordCredentialReadiness.NOT_CONFIGURED
        selectedConfiguration != null -> RecordCredentialReadiness.LOCAL_REFERENCE_CURRENT
        else -> RecordCredentialReadiness.UNVERIFIED
    }
    /** This metadata capability does not resolve credentials, create clients or enable sending. */
    val executionReady: Boolean get() = false
    override fun toString() = "RecordAuthorizationPreview(redacted, executionReady=false)"
}

enum class RecordAuthorizationAction { CONFIRM, WITHDRAW }

/** One immutable user decision. Issued/expiry times and mutation ID never change on retry. */
class RecordAuthorizationChange private constructor(val expected: RecordAuthorizationSnapshot,
    val action: RecordAuthorizationAction, val targetPolicy: ProcessingPolicy,
    val grant: ConsentGrant?, val mutationId: String, val selectedConfiguration: ModelConnectionConfiguration? = null) {
    init { require(UUID.fromString(mutationId).toString() == mutationId) }
    val committedGeneration = Math.addExact(expected.binding.generation, 1L)
    val resultingConnection get() = if (action == RecordAuthorizationAction.CONFIRM) grant?.connection else null
    override fun toString() = "RecordAuthorizationChange(redacted, action=$action)"
    companion object {
        fun confirm(preview: RecordAuthorizationPreview, issuedAt: Instant, expiresAt: Instant, mutationId: String): RecordAuthorizationChange {
            val before = preview.snapshot
            val connection = requireNotNull(preview.targetConnection) { "Complete connection metadata is required" }
            val version = Math.addExact(before.consent?.version ?: 0L, 1L)
            val grant = ConsentGrant("RECORD_AUTH:" + mutationId, version, before.binding.source, connection,
                preview.purpose, issuedAt, expiresAt)
            return RecordAuthorizationChange(before, RecordAuthorizationAction.CONFIRM, ProcessingPolicy.REMOTE_ALLOWED, grant, mutationId, preview.selectedConfiguration)
        }
        fun withdraw(expected: RecordAuthorizationSnapshot, target: ProcessingPolicy, mutationId: String): RecordAuthorizationChange {
            require(target == ProcessingPolicy.LOCAL_ONLY || target == ProcessingPolicy.AI_NONE)
            return RecordAuthorizationChange(expected, RecordAuthorizationAction.WITHDRAW, target, null, mutationId)
        }
    }
}

/** A replay reports the current scope, never reapplies an old permission after withdrawal or ABA. */
class RecordAuthorizationReceipt(val current: RecordAuthorizationSnapshot, val committedGeneration: Long,
    val replayed: Boolean, val decisionStillCurrent: Boolean) {
    override fun toString() = "RecordAuthorizationReceipt(redacted, replayed=$replayed, decisionStillCurrent=$decisionStillCurrent)"
}
