package com.t1doo.aiyou.model

import java.time.Instant

enum class ConnectionProtocol { CHAT_COMPLETIONS, ANTHROPIC_MESSAGES }
enum class TextPurpose { CONVERSATION, ORGANIZATION }
enum class ChatBudgetField { MAX_TOKENS, MAX_COMPLETION_TOKENS }

data class SecretReference(val value: String) {
    init { require(value.isNotBlank() && value.length <= 256 && value.none { it.isISOControl() }) { "Invalid secret reference" } }
    override fun toString() = "SecretReference(<redacted>)"
}

/** Public configuration contains a reference, never the resolved authentication value. */
data class ProviderConnection(
    val providerId: String,
    val version: Long,
    val apiRoot: String,
    val protocol: ConnectionProtocol,
    val modelId: String,
    val secretRef: SecretReference,
    val chatBudgetField: ChatBudgetField = ChatBudgetField.MAX_TOKENS,
    val chatUsageSupported: Boolean = false,
) {
    init {
        require(providerId.isNotBlank() && providerId.length <= 256 && version > 0 &&
            apiRoot.isNotBlank() && apiRoot.length <= 4096 && modelId.isNotBlank() && modelId.length <= 256) {
            "Invalid connection configuration"
        }
    }
    override fun toString() = "ProviderConnection(<redacted>, version=$version)"
}

data class SourceVersion(val sourceId: String, val revision: Long) {
    init { require(sourceId.isNotBlank() && sourceId.length <= 256 && revision > 0) { "Invalid source version" } }
    override fun toString() = "SourceVersion(<redacted>, revision=$revision)"
}

/** A grant binds the complete approved configuration, not only its claimed version number. */
data class ConsentGrant(
    val consentId: String,
    val version: Long,
    val source: SourceVersion,
    val connection: ProviderConnection,
    val purpose: TextPurpose,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val revoked: Boolean = false,
) {
    init {
        require(consentId.isNotBlank() && consentId.length <= 256 && version > 0 && expiresAt > issuedAt) {
            "Invalid consent grant"
        }
    }
    override fun toString() = "ConsentGrant(<redacted>, version=$version, revoked=$revoked)"
}

data class RemoteSendIntent(val source: SourceVersion, val purpose: TextPurpose) {
    override fun toString() = "RemoteSendIntent(<redacted>)"
}

/** One coherent current state for this source and selected connection. Persistence is separate. */
data class RemoteSendState(
    val source: SourceVersion,
    val policy: ProcessingPolicy = ProcessingPolicy.LOCAL_ONLY,
    val deleted: Boolean = false,
    val connection: ProviderConnection? = null,
    val consent: ConsentGrant? = null,
) {
    override fun toString() = "RemoteSendState(<redacted>, policy=$policy, deleted=$deleted)"
}
