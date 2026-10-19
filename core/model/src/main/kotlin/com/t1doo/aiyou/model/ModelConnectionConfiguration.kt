package com.t1doo.aiyou.model

import java.net.URI
import java.util.Collections

/** Planned uses only; none of these choices grants processing or demonstrates a capability. */
enum class ModelConfigurationPurpose { CONVERSATION, ORGANIZATION, VISION, TRANSCRIPTION, EMBEDDING }

/** Non-secret configuration. Deliberately cannot become a runnable ProviderConnection. */
class ModelConnectionDetails(val name: String, val apiRoot: String, val protocol: ConnectionProtocol,
    val modelId: String, purposes: Set<ModelConfigurationPurpose>) {
    val purposes: Set<ModelConfigurationPurpose> = Collections.unmodifiableSet(purposes.toSet())
    init {
        require(name.isNotBlank() && name.length <= 80 && validText(name))
        require(modelId.isNotBlank() && modelId.length <= 256 && validText(modelId))
        require(apiRoot.length in 1..2048 && validText(apiRoot) && apiRoot.none { it.isWhitespace() })
        val uri = URI(apiRoot)
        require(uri.scheme.equals("https",ignoreCase=true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535))
        require(listOf("%2f","%5c").none { uri.rawPath.orEmpty().lowercase().contains(it) })
        val segments = uri.path.orEmpty().split('/')
        require(segments.none { it == "." || it == ".." || it.contains('\\') })
        val path = uri.path.orEmpty().trimEnd('/').lowercase()
        require(listOf("/chat/completions", "/messages", "/responses", "/models").none(path::endsWith))
        require(this.purposes.isNotEmpty())
    }
    override fun equals(other: Any?) = other is ModelConnectionDetails && name == other.name && apiRoot == other.apiRoot &&
        protocol == other.protocol && modelId == other.modelId && purposes == other.purposes
    override fun hashCode() = listOf(name, apiRoot, protocol, modelId, purposes).hashCode()
    override fun toString() = "ModelConnectionDetails(redacted, credentials=NOT_CONFIGURED, capabilities=UNKNOWN)"
    companion object {
        private fun validText(value: String) = LocalCandidates.validUnicode(value) && value.none { it.isISOControl() }
    }
}

data class ModelConnectionConfiguration(val id: String, val version: Long, val details: ModelConnectionDetails,
    val secretRef: SecretReference? = null) {
    init { require(id.length in 1..128 && id.none { it.isISOControl() } && version > 0) }
    override fun toString() = "ModelConnectionConfiguration(version=$version, redacted)"
}

/** Null details delete an existing configuration; the id/version tombstone remains. */
class ModelConnectionChange(val id: String, val expectedVersion: Long, val mutationId: String,
    val details: ModelConnectionDetails?) {
    init {
        require(id.length in 1..128 && id.none { it.isISOControl() })
        require(mutationId.length in 1..128 && mutationId.none { it.isISOControl() })
        require(expectedVersion >= 0 && expectedVersion < Long.MAX_VALUE && (details != null || expectedVersion > 0))
    }
    override fun toString() = "ModelConnectionChange(expectedVersion=$expectedVersion, redacted)"
}
