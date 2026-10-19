package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

/** Only ciphertext survives preparation. Callers cannot construct a trusted data-layer intent. */
interface PreparedConnectionSecret {
    val expected: ModelConnectionConfiguration
    val reference: SecretReference
}

/** Blocking local IO; storing a secret never grants record processing or starts networking. */
interface ConnectionSecretVault {
    /** Takes ownership and clears characters on every exit, including validation/storage failure. */
    fun prepare(expected: ModelConnectionConfiguration, mutationId: String, ownedCharacters: CharArray): PreparedConnectionSecret
    /** Same prepared intent confirms only its exact last mutation; no re-encryption on retry. */
    fun apply(prepared: PreparedConnectionSecret): ModelConnectionConfiguration
    fun revoke(expected: ModelConnectionConfiguration, mutationId: String): ModelConnectionConfiguration
    fun current(reference: SecretReference): Boolean
    fun resolve(reference: SecretReference): OwnedConnectionSecret?
    /** Callback only signals cancellation, outside SQL. A watch never grants cached authority. */
    fun watch(reference: SecretReference, onRetired: () -> Unit): AutoCloseable
}

/** These two facilities are constructed over the same records owner and authoritative SQL transactions. */
class LocalModelConnectionServices(val secrets: ConnectionSecretVault, val text: SourceBoundTextRepository) {
    override fun toString() = "LocalModelConnectionServices(redacted)"
}

/** Bounded, owned memory; neither String nor ordinary preferences are a secret storage API. */
class OwnedConnectionSecret private constructor(private var characters: CharArray?) : AutoCloseable {
    @Synchronized fun copyCharacters(): CharArray = checkNotNull(characters).copyOf()
    val isClosed: Boolean get() = synchronized(this) { characters == null }
    @Synchronized override fun close() { characters?.fill('\u0000'); characters = null }
    override fun toString() = "OwnedConnectionSecret(redacted)"
    companion object {
        fun take(owned: CharArray): OwnedConnectionSecret = OwnedConnectionSecret(owned)
    }
}

/** Metadata conversion alone grants neither consent, capability support nor an exchange. */
fun ModelConnectionConfiguration.textConnection(purpose: TextPurpose): ProviderConnection {
    require((if(purpose==TextPurpose.CONVERSATION) ModelConfigurationPurpose.CONVERSATION else ModelConfigurationPurpose.ORGANIZATION) in details.purposes)
    return ProviderConnection(id,version,details.apiRoot,details.protocol,details.modelId,checkNotNull(secretRef))
}
